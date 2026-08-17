package main

import (
	"crypto/ed25519"
	"encoding/base64"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestDeviceSignerCreatesProtectedKeyAndReloadsIt(t *testing.T) {
	directory := t.TempDir()
	signer, err := loadDeviceSignerWithStore(directory, true, nil)
	if err != nil {
		t.Fatal(err)
	}
	keyPath := filepath.Join(directory, privateKeyFileName)
	info, err := os.Stat(keyPath)
	if err != nil {
		t.Fatal(err)
	}
	if !secretFileProtected(keyPath, info) {
		t.Fatal("expected device key to be private to the current user")
	}

	payload := []byte("signed device proof")
	signature, err := base64.RawURLEncoding.DecodeString(signer.sign(payload))
	if err != nil {
		t.Fatal(err)
	}
	if !ed25519.Verify(signer.publicKey(), payload, signature) {
		t.Fatal("device signature did not verify")
	}

	reloaded, err := loadDeviceSignerWithStore(directory, false, nil)
	if err != nil {
		t.Fatal(err)
	}
	if reloaded.publicKeyEncoded() != signer.publicKeyEncoded() {
		t.Fatal("reloading changed the device identity")
	}
}

func TestDeviceSignerDoesNotReplaceMissingEnrolledKey(t *testing.T) {
	directory := t.TempDir()
	if _, err := loadDeviceSignerWithStore(directory, false, nil); err == nil {
		t.Fatal("expected an enrolled profile with no key to fail")
	}
}

func TestDeviceSignerRejectsBroadPermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows DACL rejection is covered by a Windows-specific integration test")
	}
	directory := t.TempDir()
	signer, err := loadDeviceSignerWithStore(directory, true, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(signer.path, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := loadDeviceSignerWithStore(directory, false, nil); err == nil {
		t.Fatal("expected broad private-key permissions to fail")
	}
}

func TestDeviceSignerPrefersAvailableSystemCredentialStore(t *testing.T) {
	directory := t.TempDir()
	store := &memoryCredentialStore{backend: "test-keychain", enabled: true}
	signer, err := loadDeviceSignerWithStore(directory, true, store)
	if err != nil {
		t.Fatal(err)
	}
	if signer.backend != "test-keychain" || signer.path != "" {
		t.Fatalf("expected keychain signer, got %#v", signer)
	}
	if _, err := os.Stat(filepath.Join(directory, privateKeyFileName)); !os.IsNotExist(err) {
		t.Fatal("system credential identity must not also create a plaintext key file")
	}
	reloaded, err := loadDeviceSignerWithStore(directory, false, store)
	if err != nil {
		t.Fatal(err)
	}
	if reloaded.publicKeyEncoded() != signer.publicKeyEncoded() {
		t.Fatal("credential-store reload changed the device identity")
	}
}

func TestDeviceSignerDoesNotReplaceCredentialStoreOnLoadFailure(t *testing.T) {
	directory := t.TempDir()
	store := &memoryCredentialStore{backend: "test-keychain", enabled: true, loadErr: os.ErrPermission}
	if _, err := loadDeviceSignerWithStore(directory, true, store); err == nil {
		t.Fatal("expected credential-store load failure to preserve the existing identity")
	}
	if len(store.value) != 0 {
		t.Fatal("credential-store load failure must not generate a replacement key")
	}
}

func TestDeviceIdentityExistsRejectsCorruptSecureFile(t *testing.T) {
	directory := t.TempDir()
	if err := os.WriteFile(filepath.Join(directory, privateKeyFileName), []byte("corrupt"), 0o600); err != nil {
		t.Fatal(err)
	}
	exists, err := deviceIdentityExists(directory, nil)
	if err != nil || !exists {
		t.Fatalf("existing corrupt key must still block restore: exists=%v err=%v", exists, err)
	}
}

type memoryCredentialStore struct {
	backend string
	enabled bool
	value   []byte
	loadErr error
}

func (store *memoryCredentialStore) name() string    { return store.backend }
func (store *memoryCredentialStore) available() bool { return store.enabled }
func (store *memoryCredentialStore) load() ([]byte, bool, error) {
	if store.loadErr != nil {
		return nil, false, store.loadErr
	}
	return append([]byte(nil), store.value...), len(store.value) > 0, nil
}
func (store *memoryCredentialStore) store(value []byte) error {
	store.value = append([]byte(nil), value...)
	return nil
}
func (store *memoryCredentialStore) remove() error {
	store.value = nil
	return nil
}
