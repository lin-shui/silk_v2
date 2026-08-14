//go:build darwin

package main

import (
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"os/exec"
	"strings"
)

type macOSKeychainStore struct {
	account string
}

func platformDeviceCredentialStore(configDir string) deviceCredentialStore {
	digest := sha256.Sum256([]byte(configDir))
	return &macOSKeychainStore{account: base64.RawURLEncoding.EncodeToString(digest[:12])}
}

func (store *macOSKeychainStore) name() string    { return "macos-keychain" }
func (store *macOSKeychainStore) available() bool { return true }

func (store *macOSKeychainStore) load() ([]byte, bool, error) {
	output, err := exec.Command(
		"security", "find-generic-password", "-s", "com.silk.agent.device", "-a", store.account, "-w",
	).Output()
	if err != nil {
		var exitError *exec.ExitError
		if errors.As(err, &exitError) && exitError.ExitCode() == 44 {
			return nil, false, nil
		}
		return nil, false, err
	}
	decoded, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(string(output)))
	return decoded, true, err
}

func (store *macOSKeychainStore) store(value []byte) error {
	return exec.Command(
		"security", "add-generic-password", "-U", "-s", "com.silk.agent.device", "-a", store.account,
		"-w", base64.RawURLEncoding.EncodeToString(value),
	).Run()
}

func (store *macOSKeychainStore) remove() error {
	return exec.Command(
		"security", "delete-generic-password", "-s", "com.silk.agent.device", "-a", store.account,
	).Run()
}
