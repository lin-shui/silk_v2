package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestHostUsesOneAuthenticatedWebSocketForTwoAgentStreams(t *testing.T) {
	_, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer := &deviceSigner{privateKey: privateKey}
	serverDone := make(chan error, 1)
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/agent-connect" {
			serverDone <- fmt.Errorf("unexpected WebSocket path %s", request.URL.Path)
			return
		}
		socket, upgradeErr := upgrader.Upgrade(writer, request, nil)
		if upgradeErr != nil {
			serverDone <- upgradeErr
			return
		}
		defer socket.Close()
		serverDone <- serveHostHandshakeTest(socket, signer, "http://"+request.Host)
	}))
	defer server.Close()

	codex := agentConfig{AgentInstanceID: "codex-1", AgentType: "codex", Enabled: true}
	claude := agentConfig{AgentInstanceID: "claude-1", AgentType: "claude-code", Enabled: true}
	connection, err := dialMultiplexedHost(
		context.Background(),
		hostConfig{ServerOrigin: server.URL, DeviceID: "device-1", AllowInsecureHTTP: true},
		codex,
		signer,
	)
	if err != nil {
		t.Fatal(err)
	}
	if err := connection.openAgent(codex); err != nil {
		t.Fatal(err)
	}
	if err := connection.openAgent(claude); err != nil {
		t.Fatal(err)
	}
	if err := connection.sendAgentRPC(
		codex.AgentInstanceID,
		json.RawMessage(`{"jsonrpc":"2.0","id":7,"result":{"protocolVersion":"0.2"}}`),
	); err != nil {
		t.Fatal(err)
	}
	connection.close()

	select {
	case err := <-serverDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("mock Silk server did not receive Host envelopes")
	}
}

func TestHostAuthenticatesOverTLSWebSocket(t *testing.T) {
	_, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer := &deviceSigner{privateKey: privateKey}
	serverDone := make(chan error, 1)
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	server := httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		socket, upgradeErr := upgrader.Upgrade(writer, request, nil)
		if upgradeErr != nil {
			serverDone <- upgradeErr
			return
		}
		defer socket.Close()
		serverDone <- serveHostHandshakeTest(socket, signer, "https://"+request.Host)
	}))
	defer server.Close()

	dialer := *websocket.DefaultDialer
	dialer.TLSClientConfig = server.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	codex := agentConfig{AgentInstanceID: "codex-1", AgentType: "codex", Enabled: true}
	claude := agentConfig{AgentInstanceID: "claude-1", AgentType: "claude-code", Enabled: true}
	connection, err := dialMultiplexedHostWithDialer(
		context.Background(),
		hostConfig{ServerOrigin: server.URL, DeviceID: "device-1"},
		codex,
		signer,
		&dialer,
	)
	if err != nil {
		t.Fatal(err)
	}
	if err := connection.openAgent(codex); err != nil {
		t.Fatal(err)
	}
	if err := connection.openAgent(claude); err != nil {
		t.Fatal(err)
	}
	if err := connection.sendAgentRPC(
		codex.AgentInstanceID,
		json.RawMessage(`{"jsonrpc":"2.0","id":7,"result":{}}`),
	); err != nil {
		t.Fatal(err)
	}
	connection.close()
	if err := <-serverDone; err != nil {
		t.Fatal(err)
	}
}

func TestHostRejectsAuthenticationOriginMutation(t *testing.T) {
	_, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer := &deviceSigner{privateKey: privateKey}
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		socket, upgradeErr := upgrader.Upgrade(writer, request, nil)
		if upgradeErr != nil {
			return
		}
		defer socket.Close()
		var hello socketHello
		if readErr := socket.ReadJSON(&hello); readErr != nil {
			return
		}
		_ = socket.WriteJSON(socketChallenge{
			Type:         "challenge",
			ChallengeID:  "challenge-1",
			Nonce:        "nonce-1",
			ServerOrigin: "https://attacker.example.com",
			ExpiresAtMs:  time.Now().Add(time.Minute).UnixMilli(),
			ServerTimeMs: time.Now().UnixMilli(),
		})
	}))
	defer server.Close()

	_, err = dialMultiplexedHost(
		context.Background(),
		hostConfig{
			ServerOrigin:         server.URL,
			AuthenticationOrigin: server.URL,
			DeviceID:             "device-1",
			AllowInsecureHTTP:    true,
		},
		agentConfig{AgentInstanceID: "codex-1", AgentType: "codex", Enabled: true},
		signer,
	)
	if err == nil || !strings.Contains(err.Error(), "authentication origin") {
		t.Fatalf("expected authentication origin rejection, got %v", err)
	}
}

func TestHostStopsRetryingWhenEveryEnabledAgentIsRevoked(t *testing.T) {
	_, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer := &deviceSigner{privateKey: privateKey}
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	requests := make(chan struct{}, 4)
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		socket, upgradeErr := upgrader.Upgrade(writer, request, nil)
		if upgradeErr != nil {
			return
		}
		defer socket.Close()
		requests <- struct{}{}
		_ = socket.WriteJSON(socketEnvelope{
			Type:    "error",
			Error:   "DEVICE_OR_AGENT_REVOKED",
			Message: "Device or Agent is not active",
		})
	}))
	defer server.Close()

	ctx, cancel := context.WithCancel(context.Background())
	runtime := &hostRuntime{
		config: hostConfig{
			ServerOrigin:      server.URL,
			DeviceID:          "device-revoked",
			AllowInsecureHTTP: true,
			Agents: map[string]agentConfig{
				"codex": {
					AgentInstanceID: "codex-revoked",
					AgentType:       "codex",
					Enabled:         true,
				},
			},
		},
		signer:    signer,
		ctx:       ctx,
		cancel:    cancel,
		connected: make(map[string]bool),
	}
	runtime.waitGroup.Add(1)
	go runtime.runConnectionLoop()

	select {
	case <-ctx.Done():
	case <-time.After(3 * time.Second):
		t.Fatal("Host did not stop after the only enabled Agent was revoked")
	}
	runtime.waitGroup.Wait()
	if got := len(requests); got != 1 {
		t.Fatalf("expected one revoked authentication attempt, got %d", got)
	}
	if terminalErr := runtime.terminalError(); terminalErr == nil || !strings.Contains(terminalErr.Error(), "revoked") {
		t.Fatalf("expected a clear terminal revocation error, got %v", terminalErr)
	}
}

func serveHostHandshakeTest(
	socket *websocket.Conn,
	signer *deviceSigner,
	serverOrigin string,
) error {
	var hello socketHello
	if err := socket.ReadJSON(&hello); err != nil {
		return err
	}
	if hello.ConnectionMode != hostConnectionMode || hello.AgentInstanceID != "codex-1" {
		return fmt.Errorf("unexpected Host hello: %#v", hello)
	}
	timestamp := time.Now().UnixMilli()
	challenge := socketChallenge{
		Type:            "challenge",
		ProtocolVersion: protocolVersion,
		ChallengeID:     "challenge-1",
		Nonce:           "nonce-1",
		ServerOrigin:    serverOrigin,
		ServerTimeMs:    timestamp,
		ExpiresAtMs:     timestamp + 30_000,
	}
	if err := socket.WriteJSON(challenge); err != nil {
		return err
	}
	var authentication socketAuthenticate
	if err := socket.ReadJSON(&authentication); err != nil {
		return err
	}
	payload := canonicalAgentAuth(
		serverOrigin,
		challenge.ChallengeID,
		challenge.Nonce,
		authentication.DeviceID,
		authentication.AgentInstanceID,
		authentication.TimestampMs,
	)
	signature, err := base64.RawURLEncoding.DecodeString(authentication.Signature)
	if err != nil || !ed25519.Verify(signer.publicKey(), payload, signature) {
		return errors.New("Host sent an invalid device signature")
	}
	if err := socket.WriteJSON(socketAuthenticated{
		Type:            "authenticated",
		ConnectionID:    "connection-1",
		DeviceID:        "device-1",
		AgentInstanceID: "codex-1",
		ConnectionMode:  hostConnectionMode,
		ServerTimeMs:    timestamp,
	}); err != nil {
		return err
	}

	var firstOpen hostAgentOpen
	if err := socket.ReadJSON(&firstOpen); err != nil {
		return err
	}
	var secondOpen hostAgentOpen
	if err := socket.ReadJSON(&secondOpen); err != nil {
		return err
	}
	if firstOpen.AgentInstanceID != "codex-1" || secondOpen.AgentInstanceID != "claude-1" {
		return fmt.Errorf("unexpected logical Agent opens: %#v %#v", firstOpen, secondOpen)
	}
	var rpc hostAgentRPC
	if err := socket.ReadJSON(&rpc); err != nil {
		return err
	}
	var rpcObject map[string]json.RawMessage
	if err := json.Unmarshal(rpc.Payload, &rpcObject); err != nil || rpc.AgentInstanceID != "codex-1" {
		return fmt.Errorf("unexpected multiplexed ACP envelope: %#v", rpc)
	}
	return nil
}
