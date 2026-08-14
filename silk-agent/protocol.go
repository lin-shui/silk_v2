package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"sort"
	"strings"
)

const (
	protocolVersion = 1
	keyAlgorithm    = "Ed25519"
)

type createPairingRequest struct {
	ProtocolVersion    int      `json:"protocolVersion"`
	KeyAlgorithm       string   `json:"keyAlgorithm"`
	PublicKey          string   `json:"publicKey"`
	AccountLoginName   string   `json:"accountLoginName"`
	ConnectionOrigin   string   `json:"connectionOrigin"`
	VerificationOrigin string   `json:"verificationOrigin,omitempty"`
	DeviceName         string   `json:"deviceName"`
	Platform           string   `json:"platform"`
	AgentType          string   `json:"agentType"`
	AgentDisplayName   string   `json:"agentDisplayName"`
	TransportAdapter   string   `json:"transportAdapter"`
	ConnectorVersion   string   `json:"connectorVersion"`
	Capabilities       []string `json:"capabilities"`
}

type createPairingResponse struct {
	PairingID         string `json:"pairingId"`
	DevicePollSecret  string `json:"devicePollSecret"`
	ServerOrigin      string `json:"serverOrigin"`
	VerificationURI   string `json:"verificationUri"`
	UserCode          string `json:"userCode"`
	ExpiresAtEpochMs  int64  `json:"expiresAtEpochMs"`
	AgentWebSocketURI string `json:"agentWebSocketUri"`
}

type createTrustedDeviceAgentRequest struct {
	ProtocolVersion    int      `json:"protocolVersion"`
	DeviceID           string   `json:"deviceId"`
	RequestID          string   `json:"requestId"`
	TimestampMs        int64    `json:"timestampEpochMs"`
	AgentType          string   `json:"agentType"`
	AgentDisplayName   string   `json:"agentDisplayName"`
	TransportAdapter   string   `json:"transportAdapter"`
	ConnectorVersion   string   `json:"connectorVersion"`
	Capabilities       []string `json:"capabilities"`
	VerificationOrigin string   `json:"verificationOrigin,omitempty"`
	Signature          string   `json:"signature"`
}

type pairingStatus struct {
	PairingID       string          `json:"pairingId"`
	State           string          `json:"state"`
	ExpiresAtMs     int64           `json:"expiresAtEpochMs"`
	ProofChallenge  *proofChallenge `json:"proofChallenge"`
	DeviceID        string          `json:"deviceId"`
	AgentInstanceID string          `json:"agentInstanceId"`
	ServerTimeMs    int64           `json:"serverTimeEpochMs"`
}

type proofChallenge struct {
	ChallengeID          string `json:"challengeId"`
	Nonce                string `json:"nonce"`
	ServerOrigin         string `json:"serverOrigin"`
	DeviceID             string `json:"deviceId"`
	AgentInstanceID      string `json:"agentInstanceId"`
	PublicKeyFingerprint string `json:"publicKeyFingerprint"`
	ExpiresAtMs          int64  `json:"expiresAtEpochMs"`
	ServerTimeMs         int64  `json:"serverTimeEpochMs"`
}

type completeProofRequest struct {
	ChallengeID string `json:"challengeId"`
	TimestampMs int64  `json:"timestampEpochMs"`
	Signature   string `json:"signature"`
}

type completeProofResponse struct {
	PairingID       string `json:"pairingId"`
	State           string `json:"state"`
	DeviceID        string `json:"deviceId"`
	AgentInstanceID string `json:"agentInstanceId"`
}

type socketHello struct {
	Type            string `json:"type"`
	ProtocolVersion int    `json:"protocolVersion"`
	DeviceID        string `json:"deviceId"`
	AgentInstanceID string `json:"agentInstanceId"`
	ConnectionMode  string `json:"connectionMode,omitempty"`
}

type socketChallenge struct {
	Type            string `json:"type"`
	ProtocolVersion int    `json:"protocolVersion"`
	ChallengeID     string `json:"challengeId"`
	Nonce           string `json:"nonce"`
	ServerOrigin    string `json:"serverOrigin"`
	ExpiresAtMs     int64  `json:"expiresAtEpochMs"`
	ServerTimeMs    int64  `json:"serverTimeEpochMs"`
}

type socketAuthenticate struct {
	Type            string `json:"type"`
	ProtocolVersion int    `json:"protocolVersion"`
	ChallengeID     string `json:"challengeId"`
	DeviceID        string `json:"deviceId"`
	AgentInstanceID string `json:"agentInstanceId"`
	TimestampMs     int64  `json:"timestampEpochMs"`
	Signature       string `json:"signature"`
}

type socketAuthenticated struct {
	Type            string   `json:"type"`
	ConnectionID    string   `json:"connectionId"`
	DeviceID        string   `json:"deviceId"`
	AgentInstanceID string   `json:"agentInstanceId"`
	Capabilities    []string `json:"capabilities"`
	ServerTimeMs    int64    `json:"serverTimeEpochMs"`
	ConnectionMode  string   `json:"connectionMode,omitempty"`
}

type heartbeat struct {
	Type        string `json:"type"`
	TimestampMs int64  `json:"timestampEpochMs"`
}

const hostConnectionMode = "HOST_MULTIPLEXED_V1"

type hostAgentOpen struct {
	Type            string `json:"type"`
	ProtocolVersion int    `json:"protocolVersion"`
	AgentInstanceID string `json:"agentInstanceId"`
	AgentType       string `json:"agentType"`
}

type hostAgentOpened struct {
	Type            string   `json:"type"`
	ProtocolVersion int      `json:"protocolVersion"`
	AgentInstanceID string   `json:"agentInstanceId"`
	AgentType       string   `json:"agentType"`
	Capabilities    []string `json:"capabilities"`
}

type hostAgentRPC struct {
	Type            string          `json:"type"`
	ProtocolVersion int             `json:"protocolVersion"`
	AgentInstanceID string          `json:"agentInstanceId"`
	Payload         json.RawMessage `json:"payload"`
}

type hostAgentClose struct {
	Type            string `json:"type"`
	ProtocolVersion int    `json:"protocolVersion"`
	AgentInstanceID string `json:"agentInstanceId"`
	Reason          string `json:"reason"`
}

func rawPublicKey(publicKey ed25519.PublicKey) string {
	return base64.RawURLEncoding.EncodeToString(publicKey)
}

func fingerprint(publicKey string) (string, error) {
	raw, err := base64.RawURLEncoding.DecodeString(publicKey)
	if err != nil || len(raw) != ed25519.PublicKeySize {
		return "", errors.New("invalid raw Ed25519 public key")
	}
	digest := sha256.Sum256(raw)
	return "SHA256:" + base64.RawURLEncoding.EncodeToString(digest[:]), nil
}

func canonicalPairingProof(serverOrigin string, pairingID string, challengeID string, nonce string, deviceID string, agentID string, keyFingerprint string, timestampMs int64) []byte {
	return []byte(strings.Join([]string{
		"silk-device-pairing/v1", serverOrigin, "1", pairingID, challengeID,
		nonce, deviceID, agentID, keyFingerprint, fmt.Sprintf("%d", timestampMs),
	}, "\n"))
}

func canonicalAgentAuth(serverOrigin string, challengeID string, nonce string, deviceID string, agentID string, timestampMs int64) []byte {
	return []byte(strings.Join([]string{
		"silk-device-auth/v1", serverOrigin, "1", challengeID, nonce,
		deviceID, agentID, fmt.Sprintf("%d", timestampMs),
	}, "\n"))
}

func canonicalTrustedDeviceAgentRequest(
	serverOrigin string,
	requestID string,
	deviceID string,
	agentType string,
	agentDisplayName string,
	transportAdapter string,
	connectorVersion string,
	capabilities []string,
	timestampMs int64,
) []byte {
	sortedCapabilities := append([]string(nil), capabilities...)
	sort.Strings(sortedCapabilities)
	return []byte(strings.Join([]string{
		"silk-agent-add/v1", serverOrigin, "1", requestID, deviceID,
		agentType, agentDisplayName, transportAdapter, connectorVersion,
		strings.Join(sortedCapabilities, ","), fmt.Sprintf("%d", timestampMs),
	}, "\n"))
}

func randomRequestID() (string, error) {
	contents := make([]byte, 32)
	if _, err := rand.Read(contents); err != nil {
		return "", fmt.Errorf("generate Agent request ID: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(contents), nil
}

func websocketURL(origin string) (string, error) {
	parsed, err := normalizeOrigin(origin)
	if err != nil {
		return "", err
	}
	if parsed.Scheme == "https" {
		parsed.Scheme = "wss"
	} else {
		parsed.Scheme = "ws"
	}
	parsed.Path = "/agent-connect"
	return parsed.String(), nil
}

func apiURL(origin string, path string) (string, error) {
	parsed, err := normalizeOrigin(origin)
	if err != nil {
		return "", err
	}
	parsed.Path = path
	return parsed.String(), nil
}

func normalizeOrigin(raw string) (*url.URL, error) {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return nil, errors.New("server must be an http(s) origin")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return nil, errors.New("server must be a credential-free http(s) origin")
	}
	if parsed.Path != "" && parsed.Path != "/" {
		return nil, errors.New("server origin must not contain a path")
	}
	scheme := strings.ToLower(parsed.Scheme)
	hostname := strings.ToLower(parsed.Hostname())
	if hostname == "" {
		return nil, errors.New("server origin must contain a host")
	}
	port := parsed.Port()
	defaultPort := (scheme == "https" && port == "443") || (scheme == "http" && port == "80")
	if port != "" && !defaultPort {
		if strings.Contains(hostname, ":") {
			parsed.Host = net.JoinHostPort(hostname, port)
		} else {
			parsed.Host = hostname + ":" + port
		}
	} else if strings.Contains(hostname, ":") {
		parsed.Host = "[" + hostname + "]"
	} else {
		parsed.Host = hostname
	}
	parsed.Scheme = scheme
	parsed.Path = ""
	parsed.RawPath = ""
	return parsed, nil
}

func validateServerTransport(origin string, allowInsecureHTTP bool) error {
	parsed, err := normalizeOrigin(origin)
	if err != nil {
		return err
	}
	if parsed.Scheme == "http" && !allowInsecureHTTP {
		return errors.New("Silk production connections require HTTPS/WSS; use --allow-insecure-http only for controlled development")
	}
	return nil
}
