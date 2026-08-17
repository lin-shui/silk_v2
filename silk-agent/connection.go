package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type socketEnvelope struct {
	Type            string `json:"type"`
	Error           string `json:"error"`
	Message         string `json:"message"`
	AgentInstanceID string `json:"agentInstanceId"`
}

type agentServerError struct {
	Code            string
	Message         string
	AgentInstanceID string
}

func (err *agentServerError) Error() string {
	return fmt.Sprintf("Silk Agent WebSocket rejected request (%s): %s", err.Code, err.Message)
}

type multiplexedHostConnection struct {
	socket  *websocket.Conn
	writeMu sync.Mutex
}

func dialMultiplexedHost(
	ctx context.Context,
	config hostConfig,
	authenticationAgent agentConfig,
	signer *deviceSigner,
) (*multiplexedHostConnection, error) {
	return dialMultiplexedHostWithDialer(ctx, config, authenticationAgent, signer, websocket.DefaultDialer)
}

func dialMultiplexedHostWithDialer(
	ctx context.Context,
	config hostConfig,
	authenticationAgent agentConfig,
	signer *deviceSigner,
	dialer *websocket.Dialer,
) (*multiplexedHostConnection, error) {
	if err := validateServerTransport(config.ServerOrigin, config.AllowInsecureHTTP); err != nil {
		return nil, err
	}
	endpoint, err := websocketURL(config.ServerOrigin)
	if err != nil {
		return nil, err
	}
	socket, response, err := dialer.DialContext(ctx, endpoint, http.Header{})
	if err != nil {
		if response != nil {
			return nil, fmt.Errorf("connect Agent Host WebSocket: HTTP %d", response.StatusCode)
		}
		return nil, fmt.Errorf("connect Agent Host WebSocket: %w", err)
	}
	if err := socket.SetReadDeadline(time.Now().Add(10 * time.Second)); err != nil {
		_ = socket.Close()
		return nil, fmt.Errorf("set Agent Host authentication deadline: %w", err)
	}
	connection := &multiplexedHostConnection{socket: socket}
	fail := func(err error) (*multiplexedHostConnection, error) {
		_ = socket.Close()
		return nil, err
	}

	if err := connection.writeJSON(socketHello{
		Type:            "hello",
		ProtocolVersion: protocolVersion,
		DeviceID:        config.DeviceID,
		AgentInstanceID: authenticationAgent.AgentInstanceID,
		ConnectionMode:  hostConnectionMode,
	}); err != nil {
		return fail(fmt.Errorf("send Agent Host hello: %w", err))
	}

	var challenge socketChallenge
	if err := readExpected(socket, "challenge", &challenge); err != nil {
		return fail(err)
	}
	authenticationOrigin := config.AuthenticationOrigin
	if authenticationOrigin == "" {
		authenticationOrigin = config.ServerOrigin
	}
	if challenge.ServerOrigin != authenticationOrigin {
		return fail(errors.New("Silk Agent authentication origin does not match the enrolled profile"))
	}
	timestamp := challenge.ServerTimeMs
	payload := canonicalAgentAuth(
		challenge.ServerOrigin,
		challenge.ChallengeID,
		challenge.Nonce,
		config.DeviceID,
		authenticationAgent.AgentInstanceID,
		timestamp,
	)
	if err := connection.writeJSON(socketAuthenticate{
		Type:            "authenticate",
		ProtocolVersion: protocolVersion,
		ChallengeID:     challenge.ChallengeID,
		DeviceID:        config.DeviceID,
		AgentInstanceID: authenticationAgent.AgentInstanceID,
		TimestampMs:     timestamp,
		Signature:       signer.sign(payload),
	}); err != nil {
		return fail(fmt.Errorf("send Host device authentication: %w", err))
	}

	var authenticated socketAuthenticated
	if err := readExpected(socket, "authenticated", &authenticated); err != nil {
		return fail(err)
	}
	if authenticated.DeviceID != config.DeviceID ||
		authenticated.AgentInstanceID != authenticationAgent.AgentInstanceID ||
		authenticated.ConnectionMode != hostConnectionMode {
		return fail(errors.New("Silk did not acknowledge multiplexed Host mode"))
	}
	if err := socket.SetReadDeadline(time.Time{}); err != nil {
		return fail(fmt.Errorf("clear Agent Host authentication deadline: %w", err))
	}
	return connection, nil
}

func (connection *multiplexedHostConnection) openAgent(agent agentConfig) error {
	return connection.writeJSON(hostAgentOpen{
		Type:            "agent_open",
		ProtocolVersion: protocolVersion,
		AgentInstanceID: agent.AgentInstanceID,
		AgentType:       agent.AgentType,
	})
}

func (connection *multiplexedHostConnection) sendAgentRPC(agentInstanceID string, payload json.RawMessage) error {
	if len(payload) == 0 || len(payload) > adapterMaxMessageBytes {
		return errors.New("ACP payload exceeds Host message limit")
	}
	var object map[string]json.RawMessage
	if err := json.Unmarshal(payload, &object); err != nil || object == nil {
		return errors.New("ACP payload must be a JSON object")
	}
	return connection.writeJSON(hostAgentRPC{
		Type:            "agent_rpc",
		ProtocolVersion: protocolVersion,
		AgentInstanceID: agentInstanceID,
		Payload:         payload,
	})
}

func (connection *multiplexedHostConnection) closeAgent(agentInstanceID string, reason string) error {
	return connection.writeJSON(hostAgentClose{
		Type:            "agent_close",
		ProtocolVersion: protocolVersion,
		AgentInstanceID: agentInstanceID,
		Reason:          reason,
	})
}

func (connection *multiplexedHostConnection) serve(
	ctx context.Context,
	handleMessage func(socketEnvelope, []byte),
) error {
	messages := make(chan []byte, 64)
	readErrors := make(chan error, 1)
	go func() {
		for {
			_, contents, err := connection.socket.ReadMessage()
			if err != nil {
				readErrors <- err
				return
			}
			select {
			case messages <- contents:
			case <-ctx.Done():
				return
			}
		}
	}()

	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			_ = connection.socket.WriteControl(
				websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.CloseNormalClosure, "Host stopping"),
				time.Now().Add(time.Second),
			)
			return nil
		case err := <-readErrors:
			return fmt.Errorf("Agent Host WebSocket closed: %w", err)
		case contents := <-messages:
			var envelope socketEnvelope
			if err := json.Unmarshal(contents, &envelope); err != nil || envelope.Type == "" {
				return errors.New("Silk sent an invalid Host envelope")
			}
			handleMessage(envelope, contents)
		case now := <-ticker.C:
			if err := connection.writeJSON(heartbeat{Type: "heartbeat", TimestampMs: now.UnixMilli()}); err != nil {
				return fmt.Errorf("send Host heartbeat: %w", err)
			}
		}
	}
}

func (connection *multiplexedHostConnection) writeJSON(value any) error {
	connection.writeMu.Lock()
	defer connection.writeMu.Unlock()
	if err := connection.socket.SetWriteDeadline(time.Now().Add(10 * time.Second)); err != nil {
		return err
	}
	return connection.socket.WriteJSON(value)
}

func (connection *multiplexedHostConnection) close() {
	_ = connection.socket.Close()
}

func readExpected(connection *websocket.Conn, expectedType string, target any) error {
	_, contents, err := connection.ReadMessage()
	if err != nil {
		return fmt.Errorf("read Agent WebSocket: %w", err)
	}
	var envelope socketEnvelope
	if err := json.Unmarshal(contents, &envelope); err != nil {
		return fmt.Errorf("decode Agent WebSocket envelope: %w", err)
	}
	if envelope.Type == "error" {
		return &agentServerError{
			Code:            envelope.Error,
			Message:         envelope.Message,
			AgentInstanceID: envelope.AgentInstanceID,
		}
	}
	if envelope.Type != expectedType {
		return fmt.Errorf("expected Agent WebSocket message %s, got %s", expectedType, envelope.Type)
	}
	if err := json.Unmarshal(contents, target); err != nil {
		return fmt.Errorf("decode Agent WebSocket message: %w", err)
	}
	return nil
}

func isTerminalAgentConnectionError(err error) bool {
	var serverError *agentServerError
	if errors.As(err, &serverError) && serverError.Code == "DEVICE_OR_AGENT_REVOKED" {
		return true
	}
	var closeError *websocket.CloseError
	return errors.As(err, &closeError) &&
		closeError.Code == websocket.ClosePolicyViolation &&
		strings.Contains(strings.ToLower(closeError.Text), "revok")
}
