package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestResolveRunConfigDirUsesExplicitAbsoluteOverride(t *testing.T) {
	configured := filepath.Join(t.TempDir(), "custom home")
	resolved, err := resolveRunConfigDir([]string{"--config-dir", configured}, "ignored-default")
	if err != nil {
		t.Fatal(err)
	}
	expected, err := filepath.Abs(configured)
	if err != nil {
		t.Fatal(err)
	}
	if resolved != filepath.Clean(expected) {
		t.Fatalf("expected %q, got %q", filepath.Clean(expected), resolved)
	}
}

func TestResolveRunConfigDirRejectsUnexpectedArguments(t *testing.T) {
	if _, err := resolveRunConfigDir([]string{"unexpected"}, "default"); err == nil {
		t.Fatal("expected unexpected run arguments to be rejected")
	}
}

func TestHostConfigRoundTripUsesProtectedFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	written := hostConfig{
		ServerOrigin:         "http://silk.internal:8006",
		AuthenticationOrigin: "https://silk.example.com",
		DeviceID:             "device-1",
		DeviceName:           "workstation",
		Platform:             "linux",
		Agents: map[string]agentConfig{
			"codex": {
				AgentInstanceID: "agent-1",
				AgentType:       "codex",
				Capabilities:    []string{"PROMPT", "STREAM"},
				Enabled:         true,
			},
		},
	}
	if err := saveHostConfig(path, written); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if !secretFileProtected(path, info) {
		t.Fatal("expected config file to be private to the current user")
	}
	loaded, err := loadHostConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.DeviceID != written.DeviceID ||
		loaded.AuthenticationOrigin != written.AuthenticationOrigin ||
		loaded.Agents["codex"].AgentInstanceID != "agent-1" {
		t.Fatalf("unexpected config round trip: %#v", loaded)
	}
}
