package main

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestFirstPairingRequiresTargetAccount(t *testing.T) {
	configDir := t.TempDir()
	err := connectCommand(
		[]string{
			"claude-code",
			"--server", "http://127.0.0.1:8006",
			"--allow-insecure-http",
		},
		configDir,
		filepath.Join(configDir, "config.json"),
		hostConfig{},
	)
	if err == nil || !strings.Contains(err.Error(), "--account is required") {
		t.Fatalf("expected target account requirement, got %v", err)
	}
}
