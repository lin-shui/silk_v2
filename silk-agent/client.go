package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"
)

const devicePollSecretHeader = "X-Silk-Device-Poll-Secret"

type silkAPIClient struct {
	origin string
	client *http.Client
}

type apiError struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

func newSilkAPIClient(origin string) (*silkAPIClient, error) {
	parsed, err := normalizeOrigin(origin)
	if err != nil {
		return nil, err
	}
	return &silkAPIClient{
		origin: parsed.String(),
		client: &http.Client{
			Timeout: 15 * time.Second,
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}, nil
}

func (client *silkAPIClient) createPairing(ctx context.Context, request createPairingRequest) (createPairingResponse, error) {
	var response createPairingResponse
	err := client.doJSON(ctx, http.MethodPost, "/api/agent-pairings", "", request, &response)
	return response, err
}

func (client *silkAPIClient) createAgentAddition(
	ctx context.Context,
	request createTrustedDeviceAgentRequest,
) (createPairingResponse, error) {
	var response createPairingResponse
	err := client.doJSON(ctx, http.MethodPost, "/api/agent-pairings/agents", "", request, &response)
	return response, err
}

func (client *silkAPIClient) pairingStatus(ctx context.Context, pairingID string, pollSecret string) (pairingStatus, error) {
	var response pairingStatus
	err := client.doJSON(
		ctx,
		http.MethodGet,
		"/api/agent-pairings/"+pairingID+"/status",
		pollSecret,
		nil,
		&response,
	)
	return response, err
}

func (client *silkAPIClient) completePairing(ctx context.Context, pairingID string, pollSecret string, request completeProofRequest) (completeProofResponse, error) {
	var response completeProofResponse
	err := client.doJSON(
		ctx,
		http.MethodPost,
		"/api/agent-pairings/"+pairingID+"/proof",
		pollSecret,
		request,
		&response,
	)
	return response, err
}

func (client *silkAPIClient) doJSON(ctx context.Context, method string, path string, pollSecret string, requestBody any, responseBody any) error {
	endpoint, err := apiURL(client.origin, path)
	if err != nil {
		return err
	}
	var body io.Reader
	if requestBody != nil {
		encoded, encodeErr := json.Marshal(requestBody)
		if encodeErr != nil {
			return fmt.Errorf("encode request: %w", encodeErr)
		}
		body = bytes.NewReader(encoded)
	}
	request, err := http.NewRequestWithContext(ctx, method, endpoint, body)
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	if requestBody != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if pollSecret != "" {
		request.Header.Set(devicePollSecretHeader, pollSecret)
	}
	response, err := client.client.Do(request)
	if err != nil {
		return fmt.Errorf("request Silk: %w", err)
	}
	defer response.Body.Close()
	contents, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return fmt.Errorf("read Silk response: %w", err)
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		var remote apiError
		if json.Unmarshal(contents, &remote) == nil && remote.Message != "" {
			return fmt.Errorf("Silk rejected request (%s): %s", remote.Error, remote.Message)
		}
		return fmt.Errorf("Silk rejected request with HTTP %d", response.StatusCode)
	}
	if responseBody == nil || len(contents) == 0 {
		return nil
	}
	if err := json.Unmarshal(contents, responseBody); err != nil {
		return fmt.Errorf("decode Silk response: %w", err)
	}
	return nil
}

func waitForPairingApproval(ctx context.Context, client *silkAPIClient, signer *deviceSigner, created createPairingResponse) (completeProofResponse, error) {
	deadline := time.UnixMilli(created.ExpiresAtEpochMs)
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for {
		if time.Now().After(deadline) {
			return completeProofResponse{}, errors.New("pairing request expired before approval")
		}
		status, err := client.pairingStatus(ctx, created.PairingID, created.DevicePollSecret)
		if err != nil {
			return completeProofResponse{}, err
		}
		switch status.State {
		case "DEVICE_PROOF_PENDING":
			if status.ProofChallenge == nil {
				return completeProofResponse{}, errors.New("Silk omitted the device proof challenge")
			}
			return submitPairingProof(ctx, client, signer, created, *status.ProofChallenge)
		case "REJECTED", "CANCELLED", "FAILED", "EXPIRED":
			return completeProofResponse{}, fmt.Errorf("pairing ended in state %s", status.State)
		case "CONSUMED":
			return completeProofResponse{
				PairingID:       status.PairingID,
				State:           status.State,
				DeviceID:        status.DeviceID,
				AgentInstanceID: status.AgentInstanceID,
			}, nil
		}
		select {
		case <-ctx.Done():
			return completeProofResponse{}, ctx.Err()
		case <-ticker.C:
		}
	}
}

func submitPairingProof(ctx context.Context, client *silkAPIClient, signer *deviceSigner, created createPairingResponse, challenge proofChallenge) (completeProofResponse, error) {
	if challenge.ServerOrigin != created.ServerOrigin {
		return completeProofResponse{}, errors.New("Silk pairing authentication origin changed during enrollment")
	}
	timestamp := challenge.ServerTimeMs
	payload := canonicalPairingProof(
		challenge.ServerOrigin,
		created.PairingID,
		challenge.ChallengeID,
		challenge.Nonce,
		challenge.DeviceID,
		challenge.AgentInstanceID,
		challenge.PublicKeyFingerprint,
		timestamp,
	)
	return client.completePairing(
		ctx,
		created.PairingID,
		created.DevicePollSecret,
		completeProofRequest{
			ChallengeID: challenge.ChallengeID,
			TimestampMs: timestamp,
			Signature:   signer.sign(payload),
		},
	)
}
