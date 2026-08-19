package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"github.com/gorilla/websocket"
)

func TestCanonicalPayloadsMatchVersionOneContract(t *testing.T) {
	pairing := string(canonicalPairingProof(
		"https://silk.example.com",
		"pairing-1",
		"challenge-1",
		"nonce-1",
		"device-1",
		"agent-1",
		"SHA256:fingerprint",
		1000,
	))
	expectedPairing := strings.Join([]string{
		"silk-device-pairing/v1",
		"https://silk.example.com",
		"1",
		"pairing-1",
		"challenge-1",
		"nonce-1",
		"device-1",
		"agent-1",
		"SHA256:fingerprint",
		"1000",
	}, "\n")
	if pairing != expectedPairing {
		t.Fatalf("unexpected pairing payload:\n%s", pairing)
	}

	authentication := string(canonicalAgentAuth(
		"https://silk.example.com",
		"challenge-1",
		"nonce-1",
		"device-1",
		"agent-1",
		2000,
	))
	expectedAuthentication := strings.Join([]string{
		"silk-device-auth/v1",
		"https://silk.example.com",
		"1",
		"challenge-1",
		"nonce-1",
		"device-1",
		"agent-1",
		"2000",
	}, "\n")
	if authentication != expectedAuthentication {
		t.Fatalf("unexpected authentication payload:\n%s", authentication)
	}

	agentRequest := string(canonicalTrustedDeviceAgentRequest(
		"https://silk.example.com",
		"request-1234567890123456789012",
		"device-1",
		"codex",
		"Codex",
		"ACP",
		"0.2.0",
		[]string{"STREAM", "CANCEL", "PROMPT"},
		3000,
	))
	expectedAgentRequest := strings.Join([]string{
		"silk-agent-add/v1",
		"https://silk.example.com",
		"1",
		"request-1234567890123456789012",
		"device-1",
		"codex",
		"Codex",
		"ACP",
		"0.2.0",
		"CANCEL,PROMPT,STREAM",
		"3000",
	}, "\n")
	if agentRequest != expectedAgentRequest {
		t.Fatalf("unexpected trusted-device Agent request payload:\n%s", agentRequest)
	}

	refresh := string(canonicalAgentCapabilityRefresh(
		"https://silk.example.com",
		"device-1",
		"agent-1",
		"codex",
		"0.4.12",
		[]string{"EXECUTION_POLICY_V2", "PROMPT", "STREAM"},
		4000,
	))
	expectedRefresh := strings.Join([]string{
		"silk-agent-capability-refresh/v1",
		"https://silk.example.com",
		"1",
		"device-1",
		"agent-1",
		"codex",
		"0.4.12",
		"EXECUTION_POLICY_V2,PROMPT,STREAM",
		"4000",
	}, "\n")
	if refresh != expectedRefresh {
		t.Fatalf("unexpected capability refresh payload:\n%s", refresh)
	}
}

func TestAgentRequestIDsAreOpaqueAndUnique(t *testing.T) {
	first, err := randomRequestID()
	if err != nil {
		t.Fatal(err)
	}
	second, err := randomRequestID()
	if err != nil {
		t.Fatal(err)
	}
	if first == second || len(first) != 43 || strings.Contains(first, "=") {
		t.Fatalf("unexpected Agent request IDs %q and %q", first, second)
	}
}

func TestTerminalAgentConnectionErrors(t *testing.T) {
	tests := []struct {
		name     string
		err      error
		terminal bool
	}{
		{
			name:     "server rejection",
			err:      &agentServerError{Code: "DEVICE_OR_AGENT_REVOKED", Message: "not active"},
			terminal: true,
		},
		{
			name:     "active revocation close",
			err:      fmt.Errorf("wrapped: %w", &websocket.CloseError{Code: websocket.ClosePolicyViolation, Text: "agent revoked"}),
			terminal: true,
		},
		{
			name:     "temporary close",
			err:      &websocket.CloseError{Code: websocket.CloseGoingAway, Text: "restart"},
			terminal: false,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if actual := isTerminalAgentConnectionError(test.err); actual != test.terminal {
				t.Fatalf("expected terminal=%t, got %t", test.terminal, actual)
			}
		})
	}
}

func TestRawPublicKeyAndFingerprint(t *testing.T) {
	publicKey, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	encoded := rawPublicKey(publicKey)
	decoded, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		t.Fatal(err)
	}
	if len(decoded) != ed25519.PublicKeySize {
		t.Fatalf("expected %d public-key bytes, got %d", ed25519.PublicKeySize, len(decoded))
	}
	value, err := fingerprint(encoded)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(value, "SHA256:") {
		t.Fatalf("unexpected fingerprint %q", value)
	}
}

func TestOriginNormalizationAndWebSocketURL(t *testing.T) {
	websocket, err := websocketURL("https://Silk.Example.com:443/")
	if err != nil {
		t.Fatal(err)
	}
	if websocket != "wss://silk.example.com/agent-connect" {
		t.Fatalf("unexpected WebSocket URL %q", websocket)
	}
	if _, err := normalizeOrigin("https://user:password@silk.example.com"); err == nil {
		t.Fatal("expected credentials in origin to be rejected")
	}
	if _, err := normalizeOrigin("https://silk.example.com/api"); err == nil {
		t.Fatal("expected path in origin to be rejected")
	}
}

func TestProductionTransportRequiresHTTPS(t *testing.T) {
	if err := validateServerTransport("https://silk.example.com", false); err != nil {
		t.Fatal(err)
	}
	if err := validateServerTransport("http://127.0.0.1:8006", false); err == nil {
		t.Fatal("expected HTTP transport to require an explicit development override")
	}
	if err := validateServerTransport("http://127.0.0.1:8006", true); err != nil {
		t.Fatal(err)
	}
}

func TestMultiplexedEnvelopeEmbedsACPAsJSONObject(t *testing.T) {
	encoded, err := json.Marshal(hostAgentRPC{
		Type:            "agent_rpc",
		ProtocolVersion: protocolVersion,
		AgentInstanceID: "codex-1",
		Payload:         json.RawMessage(`{"jsonrpc":"2.0","id":1,"result":{}}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	text := string(encoded)
	if !strings.Contains(text, `"agentInstanceId":"codex-1"`) ||
		!strings.Contains(text, `"payload":{"jsonrpc":"2.0"`) {
		t.Fatalf("unexpected multiplexed envelope: %s", text)
	}
}
