package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestManagedAdapterHandshakeHealthAndShutdown(t *testing.T) {
	t.Setenv("SILK_AGENT_TEST_HELPER", "healthy")
	t.Setenv("SILK_AGENT_HOME", "must-not-reach-adapter")
	t.Setenv("BRIDGE_TOKEN", "must-not-reach-adapter")
	agent := agentConfig{
		AgentInstanceID: "agent-instance-1",
		AgentType:       "codex",
		Capabilities:    []string{"PROMPT", "STREAM"},
		Enabled:         true,
	}
	process, initialized, err := startAdapterProcess(
		context.Background(),
		helperAdapterSpec(t),
		agent,
		adapterHostContext{},
		io.Discard,
		nil,
	)
	if err != nil {
		t.Fatal(err)
	}
	if initialized.AgentInstanceID != agent.AgentInstanceID || initialized.AdapterVersion != "test-adapter" {
		t.Fatalf("unexpected Adapter handshake: %#v", initialized)
	}
	healthContext, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	var health adapterHealthResult
	if err := process.call(healthContext, "adapter/health", struct{}{}, &health); err != nil {
		t.Fatal(err)
	}
	if health.Status != "ok" {
		t.Fatalf("unexpected Adapter health: %#v", health)
	}
	acpContext, cancelACP := context.WithTimeout(context.Background(), time.Second)
	defer cancelACP()
	if err := process.call(
		acpContext,
		"adapter/acp",
		adapterACPParams{Message: mustJSON(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "initialize"})},
		nil,
	); err != nil {
		t.Fatal(err)
	}
	process.stop()
	select {
	case <-process.done:
	case <-time.After(3 * time.Second):
		t.Fatal("managed Adapter did not stop")
	}
}

func TestManagedAdapterRejectsUnboundHandshake(t *testing.T) {
	t.Setenv("SILK_AGENT_TEST_HELPER", "wrong-binding")
	agent := agentConfig{AgentInstanceID: "agent-instance-1", AgentType: "claude-code"}
	process, _, err := startAdapterProcess(
		context.Background(),
		helperAdapterSpec(t),
		agent,
		adapterHostContext{},
		io.Discard,
		nil,
	)
	if process != nil {
		process.stop()
	}
	if err == nil || !strings.Contains(err.Error(), "invalid bound handshake") {
		t.Fatalf("expected invalid handshake error, got %v", err)
	}
}

func TestManagedAdapterRejectsForgedNonce(t *testing.T) {
	t.Setenv("SILK_AGENT_TEST_HELPER", "wrong-nonce")
	agent := agentConfig{AgentInstanceID: "agent-instance-1", AgentType: "codex"}
	process, _, err := startAdapterProcess(
		context.Background(),
		helperAdapterSpec(t),
		agent,
		adapterHostContext{},
		io.Discard,
		nil,
	)
	if process != nil {
		process.stop()
	}
	if err == nil || !strings.Contains(err.Error(), "invalid bound handshake") {
		t.Fatalf("expected forged nonce to fail, got %v", err)
	}
}

func TestAdapterHostForwardsOnlyValidACPObjects(t *testing.T) {
	agent := agentConfig{AgentInstanceID: "agent-instance-1", AgentType: "codex"}
	forwarded := make(chan string, 1)
	handler := adapterHostRequestHandler(agent, adapterHostContext{
		ForwardACP: func(payload json.RawMessage) error {
			forwarded <- string(payload)
			return nil
		},
	})
	request := adapterRPCMessage{
		Method: "host/forwardAcp",
		Params: mustJSON(adapterACPParams{
			Message: mustJSON(map[string]any{"jsonrpc": "2.0", "id": 1, "result": map[string]any{}}),
		}),
	}
	_, err := handler(request)
	if err != nil {
		t.Fatal(err)
	}
	if payload := <-forwarded; !strings.Contains(payload, `"jsonrpc":"2.0"`) {
		t.Fatalf("unexpected forwarded ACP payload: %q", payload)
	}

	request.Params = mustJSON(adapterACPParams{Message: mustJSON([]string{"not", "an", "object"})})
	if _, err := handler(request); err == nil || !strings.Contains(err.Error(), "JSON object") {
		t.Fatalf("expected non-object ACP payload to fail, got %v", err)
	}
}

func TestManagedAdapterCanForwardACPThroughHost(t *testing.T) {
	t.Setenv("SILK_AGENT_TEST_HELPER", "request-forward")
	agent := agentConfig{AgentInstanceID: "agent-instance-1", AgentType: "codex"}
	forwarded := make(chan struct{}, 1)
	host := adapterHostContext{
		ForwardACP: func(payload json.RawMessage) error {
			if !strings.Contains(string(payload), `"method":"session/update"`) {
				t.Errorf("unexpected managed Adapter ACP payload: %s", payload)
			}
			forwarded <- struct{}{}
			return nil
		},
	}
	process, _, err := startAdapterProcess(
		context.Background(),
		helperAdapterSpec(t),
		agent,
		host,
		io.Discard,
		adapterHostRequestHandler(agent, host),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer process.stop()
	select {
	case <-forwarded:
	case <-time.After(time.Second):
		t.Fatal("managed Adapter ACP forwarding request was not handled")
	}
}

func TestAdapterEnvironmentRemovesSilkCredentials(t *testing.T) {
	filtered := adapterEnvironment([]string{
		"PATH=/bin",
		"HOME=/home/test",
		"SILK_AGENT_HOME=/private/profile",
		"SILK_TOKEN=secret",
		"BRIDGE_TOKEN=legacy-secret",
		"BRIDGE_SERVER=https://silk.example.com",
		"BRIDGE_CLI_RAW_LOG=1",
		"BRIDGE_CLI_RAW_LOG_DIR=/tmp/raw",
		"SILK_AGENT_SECRET_TEST=secret",
		"PYTHONPATH=/untrusted/modules",
		"PYTHONPYCACHEPREFIX=/untrusted/cache",
		"PYTHONDONTWRITEBYTECODE=0",
		"LD_PRELOAD=/untrusted/library.so",
		"ANTHROPIC_API_KEY=agent-runtime-secret",
	})
	joined := strings.Join(filtered, "\n")
	for _, forbidden := range []string{"SILK_AGENT_HOME=", "SILK_TOKEN=", "BRIDGE_TOKEN=", "BRIDGE_SERVER=", "BRIDGE_CLI_RAW_LOG=", "BRIDGE_CLI_RAW_LOG_DIR=", "SILK_AGENT_SECRET_TEST=", "PYTHONPATH=", "PYTHONPYCACHEPREFIX=", "PYTHONDONTWRITEBYTECODE=0", "LD_PRELOAD="} {
		if strings.Contains(joined, forbidden) {
			t.Fatalf("Adapter environment leaked %s", forbidden)
		}
	}
	if !strings.Contains(joined, "PATH=/bin") || !strings.Contains(joined, "ANTHROPIC_API_KEY=") {
		t.Fatalf("Adapter environment removed required runtime variables: %q", joined)
	}
	if strings.Count(joined, "PYTHONDONTWRITEBYTECODE=1") != 1 {
		t.Fatalf("Adapter environment did not enforce bytecode suppression: %q", joined)
	}
}

func helperAdapterSpec(t *testing.T) adapterProcessSpec {
	t.Helper()
	directory := t.TempDir()
	if err := os.Chmod(directory, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(directory, "silk-agent-test-helper")
	source, err := os.Open(os.Args[0])
	if err != nil {
		t.Fatal(err)
	}
	defer source.Close()
	target, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o700)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.Copy(target, source); err != nil {
		_ = target.Close()
		t.Fatal(err)
	}
	if err := target.Close(); err != nil {
		t.Fatal(err)
	}
	return adapterProcessSpec{
		Path: path,
		Args: []string{"-test.run=TestManagedAdapterHelperProcess", "--"},
	}
}

func TestManagedAdapterHelperProcess(t *testing.T) {
	mode := os.Getenv("SILK_AGENT_TEST_HELPER")
	if mode == "" {
		return
	}
	if os.Getenv("SILK_AGENT_HOME") != "" || os.Getenv("BRIDGE_TOKEN") != "" {
		os.Exit(20)
	}
	reader := bufio.NewScanner(os.Stdin)
	writer := json.NewEncoder(os.Stdout)
	for reader.Scan() {
		var request adapterRPCMessage
		if err := json.Unmarshal(reader.Bytes(), &request); err != nil {
			os.Exit(21)
		}
		response := adapterRPCMessage{JSONRPC: "2.0", ID: request.ID}
		if mode == "request-forward" && request.ID == "adapter-forward-1" && request.Method == "" {
			continue
		}
		switch request.Method {
		case "host/initialize":
			var initialize adapterInitializeParams
			if err := json.Unmarshal(request.Params, &initialize); err != nil {
				os.Exit(22)
			}
			instanceID := initialize.AgentInstanceID
			nonce := initialize.Nonce
			if mode == "wrong-binding" {
				instanceID = "different-agent"
			}
			if mode == "wrong-nonce" {
				nonce = "forged-nonce"
			}
			response.Result = mustJSON(adapterInitializeResult{
				ProtocolVersion: adapterProtocolVersion,
				Nonce:           nonce,
				AgentInstanceID: instanceID,
				AdapterVersion:  "test-adapter",
			})
		case "adapter/health":
			response.Result = mustJSON(adapterHealthResult{Status: "ok"})
		case "adapter/acp":
			var params adapterACPParams
			if err := json.Unmarshal(request.Params, &params); err != nil || !strings.Contains(string(params.Message), `"jsonrpc":"2.0"`) {
				os.Exit(27)
			}
			response.Result = mustJSON(struct{}{})
		case "adapter/shutdown":
			response.Result = mustJSON(struct{}{})
			if err := writer.Encode(response); err != nil {
				os.Exit(23)
			}
			return
		default:
			response.Error = &adapterRPCError{Code: -32601, Message: "method not found"}
		}
		if err := writer.Encode(response); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(24)
		}
		if mode == "request-forward" && request.Method == "host/initialize" {
			if err := writer.Encode(adapterRPCMessage{
				JSONRPC: "2.0",
				ID:      "adapter-forward-1",
				Method:  "host/forwardAcp",
				Params: mustJSON(adapterACPParams{
					Message: mustJSON(map[string]any{
						"jsonrpc": "2.0",
						"method":  "session/update",
						"params":  map[string]any{"sessionId": "session-1"},
					}),
				}),
			}); err != nil {
				os.Exit(26)
			}
		}
	}
	if err := reader.Err(); err != nil {
		os.Exit(25)
	}
}

func mustJSON(value any) json.RawMessage {
	contents, err := json.Marshal(value)
	if err != nil {
		panic(err)
	}
	return contents
}
