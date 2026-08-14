//go:build windows

package main

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"syscall"
	"unsafe"
)

const cryptProtectUIForbidden = 0x1

var (
	crypt32            = syscall.NewLazyDLL("crypt32.dll")
	cryptProtectData   = crypt32.NewProc("CryptProtectData")
	cryptUnprotectData = crypt32.NewProc("CryptUnprotectData")
	kernel32           = syscall.NewLazyDLL("kernel32.dll")
	localFree          = kernel32.NewProc("LocalFree")
)

type windowsDataBlob struct {
	length uint32
	data   *byte
}

type windowsDPAPIStore struct {
	path    string
	entropy []byte
}

func platformDeviceCredentialStore(configDir string) deviceCredentialStore {
	digest := sha256.Sum256([]byte("silk-agent:" + configDir))
	return &windowsDPAPIStore{
		path:    filepath.Join(configDir, "device_key.dpapi"),
		entropy: digest[:],
	}
}

func (store *windowsDPAPIStore) name() string    { return "windows-dpapi" }
func (store *windowsDPAPIStore) available() bool { return true }

func (store *windowsDPAPIStore) load() ([]byte, bool, error) {
	contents, err := os.ReadFile(store.path)
	if os.IsNotExist(err) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	plain, err := windowsUnprotect(contents, store.entropy)
	return plain, err == nil, err
}

func (store *windowsDPAPIStore) store(value []byte) error {
	protected, err := windowsProtect(value, store.entropy)
	if err != nil {
		return err
	}
	return writeCredentialBlob(store.path, protected)
}

func (store *windowsDPAPIStore) remove() error {
	if err := os.Remove(store.path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

func windowsProtect(value []byte, entropy []byte) ([]byte, error) {
	input := windowsBlob(value)
	extra := windowsBlob(entropy)
	var output windowsDataBlob
	result, _, callErr := cryptProtectData.Call(
		uintptr(unsafe.Pointer(&input)), 0, uintptr(unsafe.Pointer(&extra)), 0, 0,
		cryptProtectUIForbidden, uintptr(unsafe.Pointer(&output)),
	)
	if result == 0 {
		return nil, fmt.Errorf("protect device key with DPAPI: %w", callErr)
	}
	return copyWindowsBlob(output), nil
}

func windowsUnprotect(value []byte, entropy []byte) ([]byte, error) {
	input := windowsBlob(value)
	extra := windowsBlob(entropy)
	var output windowsDataBlob
	result, _, callErr := cryptUnprotectData.Call(
		uintptr(unsafe.Pointer(&input)), 0, uintptr(unsafe.Pointer(&extra)), 0, 0,
		cryptProtectUIForbidden, uintptr(unsafe.Pointer(&output)),
	)
	if result == 0 {
		return nil, fmt.Errorf("unprotect device key with DPAPI: %w", callErr)
	}
	return copyWindowsBlob(output), nil
}

func windowsBlob(value []byte) windowsDataBlob {
	if len(value) == 0 {
		return windowsDataBlob{}
	}
	return windowsDataBlob{length: uint32(len(value)), data: &value[0]}
}

func copyWindowsBlob(value windowsDataBlob) []byte {
	if value.data == nil || value.length == 0 {
		return nil
	}
	defer localFree.Call(uintptr(unsafe.Pointer(value.data)))
	return append([]byte(nil), unsafe.Slice(value.data, value.length)...)
}

func writeCredentialBlob(path string, contents []byte) error {
	if len(contents) == 0 {
		return errors.New("DPAPI returned an empty device key blob")
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".device-key.*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if _, err := temporary.Write(contents); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporaryPath, path)
}
