package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPairingPollSecretNeverUsesURL(t *testing.T) {
	const pollSecret = "poll-secret"
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.RawQuery != "" {
			t.Fatalf("pairing credential leaked into query: %q", request.URL.RawQuery)
		}
		if request.Method == http.MethodGet && request.Header.Get(devicePollSecretHeader) != pollSecret {
			t.Fatalf("missing pairing poll header: %q", request.Header.Get(devicePollSecretHeader))
		}
		if request.Method == http.MethodPost {
			var body createPairingRequest
			if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if body.AccountLoginName != "agent-owner" || body.ConnectionOrigin != "http://"+request.Host {
				t.Fatalf("pairing request did not bind account and connection origin: %#v", body)
			}
		}
		writer.Header().Set("Content-Type", "application/json")
		if request.Method == http.MethodGet {
			_ = json.NewEncoder(writer).Encode(pairingStatus{
				PairingID: "pairing-1",
				State:     "USER_PENDING",
			})
			return
		}
		_ = json.NewEncoder(writer).Encode(createPairingResponse{
			PairingID:        "pairing-1",
			DevicePollSecret: pollSecret,
			VerificationURI:  "http://test/device",
			UserCode:         "ABCD-EFGH",
			ExpiresAtEpochMs: 123,
		})
	}))
	defer server.Close()

	client, err := newSilkAPIClient(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	created, err := client.createPairing(context.Background(), createPairingRequest{
		AccountLoginName: "agent-owner",
		ConnectionOrigin: server.URL,
	})
	if err != nil {
		t.Fatal(err)
	}
	if created.PairingID != "pairing-1" {
		t.Fatalf("unexpected pairing response: %#v", created)
	}
	status, err := client.pairingStatus(context.Background(), created.PairingID, pollSecret)
	if err != nil {
		t.Fatal(err)
	}
	if status.State != "USER_PENDING" {
		t.Fatalf("unexpected pairing status: %#v", status)
	}
}

func TestTrustedDeviceAgentRequestUsesDedicatedCredentialFreeEndpoint(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodPost || request.URL.Path != "/api/agent-pairings/agents" {
			t.Fatalf("unexpected Agent request endpoint: %s %s", request.Method, request.URL.Path)
		}
		if request.URL.RawQuery != "" || request.Header.Get(devicePollSecretHeader) != "" {
			t.Fatal("trusted-device Agent request leaked a credential into URL or poll header")
		}
		var body createTrustedDeviceAgentRequest
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if body.DeviceID != "device-1" || body.Signature != "device-signature" {
			t.Fatalf("unexpected Agent request body: %#v", body)
		}
		writer.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(writer).Encode(createPairingResponse{PairingID: "pairing-2"})
	}))
	defer server.Close()

	client, err := newSilkAPIClient(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	created, err := client.createAgentAddition(context.Background(), createTrustedDeviceAgentRequest{
		DeviceID:  "device-1",
		Signature: "device-signature",
	})
	if err != nil {
		t.Fatal(err)
	}
	if created.PairingID != "pairing-2" {
		t.Fatalf("unexpected Agent request response: %#v", created)
	}
}

func TestPairingProofRejectsAuthenticationOriginMutation(t *testing.T) {
	_, err := submitPairingProof(
		context.Background(),
		nil,
		nil,
		createPairingResponse{ServerOrigin: "https://silk.example.com"},
		proofChallenge{ServerOrigin: "https://attacker.example.com"},
	)
	if err == nil {
		t.Fatal("expected changed pairing authentication origin to be rejected")
	}
}

func TestPairingClientRejectsRedirectWithoutForwardingPollSecret(t *testing.T) {
	const pollSecret = "must-not-leak"
	received := make(chan string, 1)
	target := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		received <- request.Header.Get(devicePollSecretHeader)
		writer.WriteHeader(http.StatusOK)
	}))
	defer target.Close()

	redirector := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		http.Redirect(writer, request, target.URL+"/stolen", http.StatusTemporaryRedirect)
	}))
	defer redirector.Close()

	client, err := newSilkAPIClient(redirector.URL)
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.pairingStatus(context.Background(), "pairing-1", pollSecret)
	if err == nil || !strings.Contains(err.Error(), "HTTP 307") {
		t.Fatalf("expected redirect to be rejected, got %v", err)
	}
	select {
	case leaked := <-received:
		t.Fatalf("redirect target received pairing secret %q", leaked)
	default:
	}
}
