//go:build linux

package main

import (
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"os"
	"os/exec"
	"strings"
)

type linuxSecretServiceStore struct {
	profile string
}

func platformDeviceCredentialStore(configDir string) deviceCredentialStore {
	digest := sha256.Sum256([]byte(configDir))
	return &linuxSecretServiceStore{profile: base64.RawURLEncoding.EncodeToString(digest[:12])}
}

func (store *linuxSecretServiceStore) name() string { return "linux-secret-service" }

func (store *linuxSecretServiceStore) available() bool {
	if strings.TrimSpace(os.Getenv("DBUS_SESSION_BUS_ADDRESS")) == "" {
		return false
	}
	_, err := exec.LookPath("secret-tool")
	return err == nil
}

func (store *linuxSecretServiceStore) load() ([]byte, bool, error) {
	output, err := exec.Command(
		"secret-tool", "lookup", "service", "silk-agent", "profile", store.profile,
	).Output()
	if err != nil {
		var exitError *exec.ExitError
		if errors.As(err, &exitError) && len(exitError.Stderr) == 0 {
			return nil, false, nil
		}
		return nil, false, err
	}
	decoded, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(string(output)))
	return decoded, true, err
}

func (store *linuxSecretServiceStore) store(value []byte) error {
	command := exec.Command(
		"secret-tool", "store", "--label=Silk Agent device key",
		"service", "silk-agent", "profile", store.profile,
	)
	command.Stdin = strings.NewReader(base64.RawURLEncoding.EncodeToString(value))
	return command.Run()
}

func (store *linuxSecretServiceStore) remove() error {
	return exec.Command(
		"secret-tool", "clear", "service", "silk-agent", "profile", store.profile,
	).Run()
}
