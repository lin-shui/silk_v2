package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/crypto/pbkdf2"
)

const (
	identityBackupVersion    = 1
	identityBackupIterations = 600_000
	identityBackupMaxBytes   = 1024 * 1024
)

var identityBackupAAD = []byte("silk-agent-identity-backup/v1")

type identityBackupEnvelope struct {
	Version    int    `json:"version"`
	KDF        string `json:"kdf"`
	Iterations int    `json:"iterations"`
	Salt       string `json:"salt"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}

type identityBackupPlaintext struct {
	Version    int        `json:"version"`
	Config     hostConfig `json:"config"`
	PrivateKey string     `json:"privateKey"`
	PublicKey  string     `json:"publicKey"`
}

func identityCommand(
	arguments []string,
	configDir string,
	configPath string,
	config hostConfig,
) error {
	if len(arguments) == 0 {
		return errors.New("identity requires backup, restore, rotate, or migrate-keychain")
	}
	switch arguments[0] {
	case "backup":
		flags := flag.NewFlagSet("identity backup", flag.ContinueOnError)
		output := flags.String("output", "", "new encrypted backup path")
		passphraseFile := flags.String("passphrase-file", "", "0600 file containing the backup passphrase")
		if err := flags.Parse(arguments[1:]); err != nil {
			return err
		}
		return backupIdentityCommand(configDir, config, *output, *passphraseFile)
	case "restore":
		flags := flag.NewFlagSet("identity restore", flag.ContinueOnError)
		input := flags.String("input", "", "encrypted backup path")
		passphraseFile := flags.String("passphrase-file", "", "protected file containing the backup passphrase")
		if err := flags.Parse(arguments[1:]); err != nil {
			return err
		}
		return restoreIdentityCommand(configDir, configPath, config, *input, *passphraseFile)
	case "rotate":
		flags := flag.NewFlagSet("identity rotate", flag.ContinueOnError)
		output := flags.String("backup", "", "new encrypted backup path")
		passphraseFile := flags.String("passphrase-file", "", "protected file containing the backup passphrase")
		confirmed := flags.Bool("confirm-device-revoked", false, "confirm the old device was revoked in Silk")
		if err := flags.Parse(arguments[1:]); err != nil {
			return err
		}
		if !*confirmed {
			return errors.New("revoke the old device in Silk, then pass --confirm-device-revoked")
		}
		if err := backupIdentityCommand(configDir, config, *output, *passphraseFile); err != nil {
			return err
		}
		signer, err := loadDeviceSigner(configDir, false)
		if err != nil {
			return err
		}
		empty := hostConfig{Agents: map[string]agentConfig{}}
		if err := saveHostConfig(configPath, empty); err != nil {
			return err
		}
		if err := signer.remove(); err != nil {
			_ = saveHostConfig(configPath, config)
			return fmt.Errorf("remove old device identity: %w", err)
		}
		fmt.Println("Old local identity removed. Run silk-agent connect to pair a new device key.")
		return nil
	case "migrate-keychain":
		return migrateIdentityToKeychain(configDir)
	default:
		return fmt.Errorf("unknown identity command %q", arguments[0])
	}
}

func backupIdentityCommand(configDir string, config hostConfig, output string, passphrasePath string) error {
	if config.DeviceID == "" {
		return errors.New("no enrolled device identity to back up")
	}
	if strings.TrimSpace(output) == "" {
		return errors.New("--output is required")
	}
	signer, err := loadDeviceSigner(configDir, false)
	if err != nil {
		return err
	}
	passphrase, err := readBackupPassphrase(passphrasePath)
	if err != nil {
		return err
	}
	defer clearBytes(passphrase)
	contents, err := encryptIdentityBackup(config, signer.privateKey, passphrase)
	if err != nil {
		return err
	}
	if err := writeNewProtectedFile(output, contents); err != nil {
		return err
	}
	fmt.Println("Encrypted identity backup written to", output)
	return nil
}

func restoreIdentityCommand(
	configDir string,
	configPath string,
	current hostConfig,
	input string,
	passphrasePath string,
) error {
	if current.DeviceID != "" {
		return errors.New("refusing to overwrite an enrolled local identity")
	}
	if strings.TrimSpace(input) == "" {
		return errors.New("--input is required")
	}
	exists, err := deviceIdentityExists(configDir, platformDeviceCredentialStore(configDir))
	if err != nil {
		return err
	}
	if exists {
		return errors.New("refusing to overwrite an existing local device key")
	}
	passphrase, err := readBackupPassphrase(passphrasePath)
	if err != nil {
		return err
	}
	defer clearBytes(passphrase)
	contents, err := readLimitedFile(input, identityBackupMaxBytes)
	if err != nil {
		return err
	}
	config, privateKey, err := decryptIdentityBackup(contents, passphrase)
	if err != nil {
		return err
	}
	signer, err := installDeviceSigner(configDir, privateKey)
	if err != nil {
		return err
	}
	if err := saveHostConfig(configPath, config); err != nil {
		_ = signer.remove()
		return err
	}
	fmt.Printf("Restored device %s using %s.\n", config.DeviceID, signer.backend)
	return nil
}

func migrateIdentityToKeychain(configDir string) error {
	signer, err := loadDeviceSigner(configDir, false)
	if err != nil {
		return err
	}
	if signer.backend != "secure-file" {
		fmt.Println("Device identity already uses", signer.backend)
		return nil
	}
	store := platformDeviceCredentialStore(configDir)
	if store == nil || !store.available() {
		return errors.New("no supported system credential store is available")
	}
	if err := store.store(signer.privateKey); err != nil {
		return fmt.Errorf("store device key in %s: %w", store.name(), err)
	}
	loaded, found, err := store.load()
	if err != nil || !found || !ed25519.PrivateKey(loaded).Equal(signer.privateKey) {
		_ = store.remove()
		return errors.New("system credential store verification failed")
	}
	if err := os.Remove(signer.path); err != nil {
		_ = store.remove()
		return fmt.Errorf("remove migrated secure-file key: %w", err)
	}
	fmt.Println("Device identity migrated to", store.name())
	return nil
}

func encryptIdentityBackup(config hostConfig, privateKey ed25519.PrivateKey, passphrase []byte) ([]byte, error) {
	plain, err := json.Marshal(identityBackupPlaintext{
		Version:    identityBackupVersion,
		Config:     config,
		PrivateKey: base64.RawURLEncoding.EncodeToString(privateKey),
		PublicKey:  base64.RawURLEncoding.EncodeToString(privateKey.Public().(ed25519.PublicKey)),
	})
	if err != nil {
		return nil, err
	}
	defer clearBytes(plain)
	salt := make([]byte, 16)
	if _, err := io.ReadFull(rand.Reader, salt); err != nil {
		return nil, err
	}
	key := pbkdf2.Key(passphrase, salt, identityBackupIterations, 32, sha256.New)
	defer clearBytes(key)
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	ciphertext := gcm.Seal(nil, nonce, plain, identityBackupAAD)
	return json.MarshalIndent(identityBackupEnvelope{
		Version:    identityBackupVersion,
		KDF:        "PBKDF2-HMAC-SHA256",
		Iterations: identityBackupIterations,
		Salt:       base64.RawURLEncoding.EncodeToString(salt),
		Nonce:      base64.RawURLEncoding.EncodeToString(nonce),
		Ciphertext: base64.RawURLEncoding.EncodeToString(ciphertext),
	}, "", "  ")
}

func decryptIdentityBackup(contents []byte, passphrase []byte) (hostConfig, ed25519.PrivateKey, error) {
	var envelope identityBackupEnvelope
	if err := json.Unmarshal(contents, &envelope); err != nil {
		return hostConfig{}, nil, errors.New("identity backup is not valid JSON")
	}
	if envelope.Version != identityBackupVersion || envelope.KDF != "PBKDF2-HMAC-SHA256" ||
		envelope.Iterations != identityBackupIterations {
		return hostConfig{}, nil, errors.New("identity backup uses an unsupported format")
	}
	salt, err := base64.RawURLEncoding.DecodeString(envelope.Salt)
	if err != nil || len(salt) != 16 {
		return hostConfig{}, nil, errors.New("identity backup salt is invalid")
	}
	nonce, err := base64.RawURLEncoding.DecodeString(envelope.Nonce)
	if err != nil {
		return hostConfig{}, nil, errors.New("identity backup nonce is invalid")
	}
	ciphertext, err := base64.RawURLEncoding.DecodeString(envelope.Ciphertext)
	if err != nil {
		return hostConfig{}, nil, errors.New("identity backup ciphertext is invalid")
	}
	key := pbkdf2.Key(passphrase, salt, envelope.Iterations, 32, sha256.New)
	defer clearBytes(key)
	block, err := aes.NewCipher(key)
	if err != nil {
		return hostConfig{}, nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil || len(nonce) != gcm.NonceSize() {
		return hostConfig{}, nil, errors.New("identity backup nonce is invalid")
	}
	plain, err := gcm.Open(nil, nonce, ciphertext, identityBackupAAD)
	if err != nil {
		return hostConfig{}, nil, errors.New("identity backup authentication failed")
	}
	defer clearBytes(plain)
	var backup identityBackupPlaintext
	if err := json.Unmarshal(plain, &backup); err != nil || backup.Version != identityBackupVersion {
		return hostConfig{}, nil, errors.New("identity backup payload is invalid")
	}
	privateKey, err := base64.RawURLEncoding.DecodeString(backup.PrivateKey)
	if err != nil || len(privateKey) != ed25519.PrivateKeySize || backup.Config.DeviceID == "" {
		return hostConfig{}, nil, errors.New("identity backup device data is invalid")
	}
	actualPublic := base64.RawURLEncoding.EncodeToString(ed25519.PrivateKey(privateKey).Public().(ed25519.PublicKey))
	if actualPublic != backup.PublicKey {
		return hostConfig{}, nil, errors.New("identity backup public key does not match its private key")
	}
	return backup.Config, ed25519.PrivateKey(privateKey), nil
}

func readBackupPassphrase(path string) ([]byte, error) {
	if strings.TrimSpace(path) == "" {
		return nil, errors.New("--passphrase-file is required; passphrases are never accepted as command arguments")
	}
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || !secretFileProtected(path, info) {
		return nil, errors.New("passphrase file permissions are too broad")
	}
	contents, err := readLimitedFile(path, 4096)
	if err != nil {
		return nil, err
	}
	contents = []byte(strings.TrimSpace(string(contents)))
	if len(contents) < 12 {
		return nil, errors.New("backup passphrase must contain at least 12 characters")
	}
	return contents, nil
}

func readLimitedFile(path string, limit int64) ([]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	contents, err := io.ReadAll(io.LimitReader(file, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(contents)) > limit {
		return nil, errors.New("file exceeds the allowed size")
	}
	return contents, nil
}

func writeNewProtectedFile(path string, contents []byte) error {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	file, err := os.OpenFile(absolute, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return fmt.Errorf("create protected file: %w", err)
	}
	written := false
	defer func() {
		_ = file.Close()
		if !written {
			_ = os.Remove(absolute)
		}
	}()
	if err := file.Chmod(0o600); err != nil {
		return err
	}
	if err := protectPrivateFile(absolute); err != nil {
		return err
	}
	if _, err := file.Write(contents); err != nil {
		return err
	}
	if err := file.Sync(); err != nil {
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	written = true
	return syncDirectory(filepath.Dir(absolute))
}
