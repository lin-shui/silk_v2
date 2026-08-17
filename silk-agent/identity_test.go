package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"testing"
)

func TestEncryptedIdentityBackupRoundTripAndAuthentication(t *testing.T) {
	_, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	config := hostConfig{
		ServerOrigin: "https://silk.example.com",
		DeviceID:     "device-1",
		Agents: map[string]agentConfig{
			"codex": {AgentInstanceID: "agent-1", AgentType: "codex", Enabled: true},
		},
	}
	passphrase := []byte("correct horse battery staple")
	backup, err := encryptIdentityBackup(config, privateKey, passphrase)
	if err != nil {
		t.Fatal(err)
	}
	restoredConfig, restoredKey, err := decryptIdentityBackup(backup, passphrase)
	if err != nil {
		t.Fatal(err)
	}
	if restoredConfig.DeviceID != config.DeviceID || !restoredKey.Equal(privateKey) {
		t.Fatal("identity backup did not round-trip")
	}
	if _, _, err := decryptIdentityBackup(backup, []byte("incorrect passphrase")); err == nil {
		t.Fatal("expected an incorrect passphrase to fail authenticated decryption")
	}
	backup[len(backup)-2] ^= 1
	if _, _, err := decryptIdentityBackup(backup, passphrase); err == nil {
		t.Fatal("expected a modified identity backup to fail")
	}
}
