package main

import (
	"context"
	"encoding/json"
	"path/filepath"
	"testing"
	"time"
)

func TestHostReloadsApprovedAgentFromProtectedConfig(t *testing.T) {
	configDir := t.TempDir()
	configPath := filepath.Join(configDir, "config.json")
	base := hostConfig{
		ServerOrigin:         "http://silk.internal:8006",
		AuthenticationOrigin: "https://silk.example.com",
		DeviceID:             "device-1",
		Agents:               map[string]agentConfig{},
	}
	updated := base
	updated.Agents = map[string]agentConfig{
		"codex": {
			AgentInstanceID: "agent-1",
			AgentType:       "codex",
			Capabilities:    []string{"PROMPT", "STREAM"},
			Enabled:         false,
		},
	}
	if err := saveHostConfig(configPath, updated); err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	runtime := &hostRuntime{
		configPath:  configPath,
		config:      base,
		ctx:         ctx,
		loopCancels: map[string]context.CancelFunc{},
		loopIDs:     map[string]uint64{},
		connected:   map[string]bool{},
		adapters:    map[string]adapterStatus{},
	}
	if err := runtime.reloadAgent("codex"); err != nil {
		t.Fatal(err)
	}
	if runtime.config.Agents["codex"].AgentInstanceID != "agent-1" {
		t.Fatalf("approved Agent was not loaded: %#v", runtime.config.Agents)
	}

	updated.AuthenticationOrigin = "https://attacker.example.com"
	if err := saveHostConfig(configPath, updated); err != nil {
		t.Fatal(err)
	}
	if err := runtime.reloadAgent("codex"); err == nil {
		t.Fatal("expected authentication origin mismatch to be rejected")
	}
}

func TestHostRoutesACPByAgentInstanceWithoutCrossStreamBlocking(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	codexQueue := make(chan json.RawMessage, 1)
	claudeQueue := make(chan json.RawMessage, 1)
	runtime := &hostRuntime{
		ctx: ctx,
		config: hostConfig{Agents: map[string]agentConfig{
			"codex": {
				AgentInstanceID: "codex-1",
				AgentType:       "codex",
				Enabled:         true,
			},
			"claude-code": {
				AgentInstanceID: "claude-1",
				AgentType:       "claude-code",
				Enabled:         true,
			},
		}},
		connected: map[string]bool{"codex": true, "claude-code": true},
		adapterQueues: map[string]chan json.RawMessage{
			"codex":       codexQueue,
			"claude-code": claudeQueue,
		},
	}
	payload := json.RawMessage(`{"jsonrpc":"2.0","id":7,"method":"initialize"}`)
	contents, err := json.Marshal(hostAgentRPC{
		Type:            "agent_rpc",
		ProtocolVersion: protocolVersion,
		AgentInstanceID: "codex-1",
		Payload:         payload,
	})
	if err != nil {
		t.Fatal(err)
	}
	runtime.handleHostEnvelope(socketEnvelope{Type: "agent_rpc", AgentInstanceID: "codex-1"}, contents)
	select {
	case actual := <-codexQueue:
		if string(actual) != string(payload) {
			t.Fatalf("unexpected Codex ACP payload: %s", actual)
		}
	case <-time.After(time.Second):
		t.Fatal("Codex logical stream did not receive its ACP payload")
	}
	select {
	case unexpected := <-claudeQueue:
		t.Fatalf("Claude logical stream received Codex payload: %s", unexpected)
	default:
	}

	runtime.handleHostEnvelope(
		socketEnvelope{Type: "agent_close", AgentInstanceID: "codex-1"},
		[]byte(`{"type":"agent_close","agentInstanceId":"codex-1"}`),
	)
	if runtime.connected["codex"] || !runtime.connected["claude-code"] {
		t.Fatalf("closing Codex changed the wrong logical stream: %#v", runtime.connected)
	}
}

func TestOldAgentLoopCannotReplaceNewRuntimeState(t *testing.T) {
	currentProcess := &adapterProcess{}
	runtime := &hostRuntime{
		loopIDs:          map[string]uint64{"codex": 2},
		connected:        map[string]bool{"codex": true},
		adapters:         map[string]adapterStatus{"codex": {State: "healthy"}},
		adapterProcesses: map[string]*adapterProcess{"codex": currentProcess},
	}
	agent := agentConfig{AgentInstanceID: "codex-1", AgentType: "codex", Enabled: true}

	runtime.setAdapterProcess("codex", agent, 1, nil)
	runtime.setAdapterStatus("codex", 1, adapterStatus{State: "failed"})

	if runtime.adapterProcesses["codex"] != currentProcess {
		t.Fatal("an old Agent loop removed the new Adapter process")
	}
	if runtime.adapters["codex"].State != "healthy" {
		t.Fatal("an old Agent loop replaced the new Adapter status")
	}
}
