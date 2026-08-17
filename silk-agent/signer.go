package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

const privateKeyFileName = "device_key"

type deviceSigner struct {
	path       string
	privateKey ed25519.PrivateKey
	store      deviceCredentialStore
	backend    string
}

func loadDeviceSigner(configDir string, allowCreate bool) (*deviceSigner, error) {
	return loadDeviceSignerWithStore(configDir, allowCreate, platformDeviceCredentialStore(configDir))
}

func loadDeviceSignerWithStore(
	configDir string,
	allowCreate bool,
	credentialStore deviceCredentialStore,
) (*deviceSigner, error) {
	if err := protectPrivateDirectory(configDir); err != nil {
		return nil, fmt.Errorf("protect secure config directory: %w", err)
	}
	if credentialStore != nil && credentialStore.available() {
		contents, found, loadErr := credentialStore.load()
		if loadErr != nil {
			return nil, fmt.Errorf("load device key from %s: %w", credentialStore.name(), loadErr)
		}
		if found {
			if len(contents) != ed25519.PrivateKeySize {
				return nil, errors.New("system credential device key has invalid length")
			}
			return &deviceSigner{
				privateKey: ed25519.PrivateKey(contents),
				store:      credentialStore,
				backend:    credentialStore.name(),
			}, nil
		}
	}
	path := filepath.Join(configDir, privateKeyFileName)
	contents, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		if !allowCreate {
			return nil, errors.New("enrolled device key is missing; revoke the old device and pair again")
		}
		_, privateKey, generateErr := ed25519.GenerateKey(rand.Reader)
		if generateErr != nil {
			return nil, fmt.Errorf("generate device key: %w", generateErr)
		}
		if credentialStore != nil && credentialStore.available() {
			if storeErr := credentialStore.store(privateKey); storeErr == nil {
				return &deviceSigner{
					privateKey: privateKey,
					store:      credentialStore,
					backend:    credentialStore.name(),
				}, nil
			}
		}
		if writeErr := writePrivateKeyAtomically(path, privateKey); writeErr != nil {
			return nil, writeErr
		}
		return &deviceSigner{path: path, privateKey: privateKey, backend: "secure-file"}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read device key: %w", err)
	}
	if info, statErr := os.Stat(path); statErr != nil {
		return nil, fmt.Errorf("stat device key: %w", statErr)
	} else if !info.Mode().IsRegular() || !secretFileProtected(path, info) {
		return nil, errors.New("device key permissions are too broad for the current platform")
	}
	if len(contents) != ed25519.PrivateKeySize {
		return nil, errors.New("device key has invalid length")
	}
	return &deviceSigner{path: path, privateKey: ed25519.PrivateKey(contents), backend: "secure-file"}, nil
}

func deviceIdentityExists(configDir string, credentialStore deviceCredentialStore) (bool, error) {
	if _, err := os.Lstat(filepath.Join(configDir, privateKeyFileName)); err == nil {
		return true, nil
	} else if !os.IsNotExist(err) {
		return false, err
	}
	if credentialStore == nil || !credentialStore.available() {
		return false, nil
	}
	_, found, err := credentialStore.load()
	if err != nil {
		return false, fmt.Errorf("inspect device key in %s: %w", credentialStore.name(), err)
	}
	return found, nil
}

func (signer *deviceSigner) publicKey() ed25519.PublicKey {
	publicKey := make(ed25519.PublicKey, ed25519.PublicKeySize)
	copy(publicKey, signer.privateKey[ed25519.SeedSize:])
	return publicKey
}

func (signer *deviceSigner) publicKeyEncoded() string {
	return base64.RawURLEncoding.EncodeToString(signer.publicKey())
}

func (signer *deviceSigner) sign(payload []byte) string {
	return base64.RawURLEncoding.EncodeToString(ed25519.Sign(signer.privateKey, payload))
}

func (signer *deviceSigner) remove() error {
	if signer.store != nil {
		if err := signer.store.remove(); err != nil {
			return err
		}
	}
	if signer.path != "" {
		if err := os.Remove(signer.path); err != nil && !os.IsNotExist(err) {
			return fmt.Errorf("remove secure-file device key: %w", err)
		}
	}
	return nil
}

func installDeviceSigner(configDir string, privateKey ed25519.PrivateKey) (*deviceSigner, error) {
	if len(privateKey) != ed25519.PrivateKeySize {
		return nil, errors.New("restored device key has invalid length")
	}
	store := platformDeviceCredentialStore(configDir)
	if store != nil && store.available() {
		if err := store.store(privateKey); err == nil {
			return &deviceSigner{privateKey: privateKey, store: store, backend: store.name()}, nil
		}
	}
	path := filepath.Join(configDir, privateKeyFileName)
	if err := writePrivateKeyAtomically(path, privateKey); err != nil {
		return nil, err
	}
	return &deviceSigner{path: path, privateKey: privateKey, backend: "secure-file"}, nil
}

func writePrivateKeyAtomically(path string, key ed25519.PrivateKey) error {
	directory := filepath.Dir(path)
	temporary, err := os.OpenFile(path+".new", os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		if os.IsExist(err) {
			return errors.New("another process is creating the device key")
		}
		return fmt.Errorf("create temporary device key: %w", err)
	}
	temporaryPath := path + ".new"
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		temporary.Close()
		return fmt.Errorf("protect temporary device key: %w", err)
	}
	if _, err := temporary.Write(key); err != nil {
		temporary.Close()
		return fmt.Errorf("write device key: %w", err)
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return fmt.Errorf("sync device key: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close device key: %w", err)
	}
	if err := protectPrivateFile(temporaryPath); err != nil {
		return err
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		return fmt.Errorf("install device key: %w", err)
	}
	if err := syncDirectory(directory); err != nil {
		return err
	}
	return nil
}
