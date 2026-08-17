package main

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

const releaseManifestVersion = 1

// Injected into production artifacts with -ldflags. Verification fails closed when absent.
var releasePublicKey string

type releaseManifest struct {
	SchemaVersion int               `json:"schemaVersion"`
	Version       string            `json:"version"`
	Artifacts     []releaseArtifact `json:"artifacts"`
}

type releaseArtifact struct {
	Name   string `json:"name"`
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
}

func releaseCommand(arguments []string) error {
	if len(arguments) == 0 {
		return errors.New("release requires manifest or verify")
	}
	switch arguments[0] {
	case "manifest":
		flags := flag.NewFlagSet("release manifest", flag.ContinueOnError)
		version := flags.String("version", "", "release semantic version")
		artifacts := flags.String("artifacts", "", "artifact directory")
		privateKeyFile := flags.String("private-key-file", "", "protected Ed25519 release private key file")
		manifestPath := flags.String("output", "", "new manifest path")
		signaturePath := flags.String("signature", "", "new detached signature path")
		if err := flags.Parse(arguments[1:]); err != nil {
			return err
		}
		return createReleaseManifestFiles(
			*version, *artifacts, *privateKeyFile, *manifestPath, *signaturePath,
		)
	case "verify":
		flags := flag.NewFlagSet("release verify", flag.ContinueOnError)
		manifestPath := flags.String("manifest", "", "release manifest path")
		signaturePath := flags.String("signature", "", "detached signature path")
		artifactDir := flags.String("artifacts", "", "artifact directory")
		publicKeyFile := flags.String("public-key-file", "", "development trusted public key override")
		requireNewer := flags.Bool("require-newer", false, "require the manifest version to be newer than this binary")
		if err := flags.Parse(arguments[1:]); err != nil {
			return err
		}
		manifest, err := verifyReleaseFiles(
			*manifestPath, *signaturePath, *artifactDir, *publicKeyFile,
		)
		if err != nil {
			return err
		}
		if *requireNewer {
			comparison, compareErr := compareSemanticVersions(manifest.Version, hostVersion)
			if compareErr != nil {
				return compareErr
			}
			if comparison <= 0 {
				return fmt.Errorf("release %s is not newer than installed %s", manifest.Version, hostVersion)
			}
		}
		fmt.Printf("Verified Silk Agent release %s (%d artifacts).\n", manifest.Version, len(manifest.Artifacts))
		return nil
	default:
		return fmt.Errorf("unknown release command %q", arguments[0])
	}
}

func createReleaseManifestFiles(
	version string,
	artifactDir string,
	privateKeyPath string,
	manifestPath string,
	signaturePath string,
) error {
	if _, err := parseSemanticVersion(version); err != nil {
		return err
	}
	if artifactDir == "" || privateKeyPath == "" || manifestPath == "" || signaturePath == "" {
		return errors.New("--artifacts, --private-key-file, --output, and --signature are required")
	}
	manifest, err := buildReleaseManifest(version, artifactDir)
	if err != nil {
		return err
	}
	contents, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		return err
	}
	contents = append(contents, '\n')
	privateKey, err := readReleasePrivateKey(privateKeyPath)
	if err != nil {
		return err
	}
	defer clearBytes(privateKey)
	signature := base64.RawURLEncoding.EncodeToString(ed25519.Sign(privateKey, contents)) + "\n"
	if err := writeNewFile(manifestPath, contents, 0o644); err != nil {
		return err
	}
	if err := writeNewFile(signaturePath, []byte(signature), 0o644); err != nil {
		_ = os.Remove(manifestPath)
		return err
	}
	return nil
}

func buildReleaseManifest(version string, artifactDir string) (releaseManifest, error) {
	entries, err := os.ReadDir(artifactDir)
	if err != nil {
		return releaseManifest{}, err
	}
	artifacts := make([]releaseArtifact, 0, len(entries))
	for _, entry := range entries {
		if entry.Name() == "manifest.json" || entry.Name() == "manifest.sig" {
			continue
		}
		if entry.IsDir() {
			return releaseManifest{}, fmt.Errorf("release artifact directory contains unexpected directory %s", entry.Name())
		}
		path := filepath.Join(artifactDir, entry.Name())
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() {
			return releaseManifest{}, fmt.Errorf("inspect release artifact %s", entry.Name())
		}
		digest, size, err := hashReleaseArtifact(path)
		if err != nil {
			return releaseManifest{}, err
		}
		artifacts = append(artifacts, releaseArtifact{
			Name:   entry.Name(),
			SHA256: digest,
			Size:   size,
		})
	}
	if len(artifacts) == 0 {
		return releaseManifest{}, errors.New("release artifact directory is empty")
	}
	sort.Slice(artifacts, func(i int, j int) bool { return artifacts[i].Name < artifacts[j].Name })
	return releaseManifest{SchemaVersion: releaseManifestVersion, Version: version, Artifacts: artifacts}, nil
}

func verifyReleaseFiles(
	manifestPath string,
	signaturePath string,
	artifactDir string,
	publicKeyPath string,
) (releaseManifest, error) {
	manifestContents, err := readLimitedFile(manifestPath, identityBackupMaxBytes)
	if err != nil {
		return releaseManifest{}, err
	}
	signatureContents, err := readLimitedFile(signaturePath, 4096)
	if err != nil {
		return releaseManifest{}, err
	}
	trustedKey, err := trustedReleasePublicKey(publicKeyPath)
	if err != nil {
		return releaseManifest{}, err
	}
	signature, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(string(signatureContents)))
	if err != nil || len(signature) != ed25519.SignatureSize ||
		!ed25519.Verify(trustedKey, manifestContents, signature) {
		return releaseManifest{}, errors.New("release manifest signature is invalid")
	}
	var manifest releaseManifest
	if err := json.Unmarshal(manifestContents, &manifest); err != nil ||
		manifest.SchemaVersion != releaseManifestVersion {
		return releaseManifest{}, errors.New("release manifest format is invalid")
	}
	if _, err := parseSemanticVersion(manifest.Version); err != nil {
		return releaseManifest{}, err
	}
	if len(manifest.Artifacts) == 0 {
		return releaseManifest{}, errors.New("release manifest contains no artifacts")
	}
	seen := make(map[string]struct{}, len(manifest.Artifacts))
	for _, artifact := range manifest.Artifacts {
		if artifact.Name == "" || filepath.Base(artifact.Name) != artifact.Name {
			return releaseManifest{}, errors.New("release manifest contains an unsafe artifact name")
		}
		if _, duplicate := seen[artifact.Name]; duplicate {
			return releaseManifest{}, errors.New("release manifest contains duplicate artifacts")
		}
		seen[artifact.Name] = struct{}{}
		digest, size, err := hashReleaseArtifact(filepath.Join(artifactDir, artifact.Name))
		if err != nil {
			return releaseManifest{}, err
		}
		if size != artifact.Size || digest != artifact.SHA256 {
			return releaseManifest{}, fmt.Errorf("release artifact %s failed hash or size verification", artifact.Name)
		}
	}
	entries, err := os.ReadDir(artifactDir)
	if err != nil {
		return releaseManifest{}, err
	}
	for _, entry := range entries {
		name := entry.Name()
		if name == "manifest.json" || name == "manifest.sig" {
			continue
		}
		if entry.IsDir() {
			return releaseManifest{}, fmt.Errorf("release directory contains unexpected directory %s", name)
		}
		if _, expected := seen[name]; !expected {
			return releaseManifest{}, fmt.Errorf("release directory contains unsigned artifact %s", name)
		}
	}
	return manifest, nil
}

func hashReleaseArtifact(path string) (string, int64, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer file.Close()
	hash := sha256.New()
	size, err := io.Copy(hash, file)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(hash.Sum(nil)), size, nil
}

func trustedReleasePublicKey(path string) (ed25519.PublicKey, error) {
	encoded := strings.TrimSpace(releasePublicKey)
	if path != "" {
		if encoded != "" {
			return nil, errors.New("trusted release public key override is disabled in production artifacts")
		}
		contents, err := readLimitedFile(path, 4096)
		if err != nil {
			return nil, err
		}
		encoded = strings.TrimSpace(string(contents))
	}
	decoded, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil || len(decoded) != ed25519.PublicKeySize {
		return nil, errors.New("no valid trusted release public key is configured")
	}
	return ed25519.PublicKey(decoded), nil
}

func clearBytes(value []byte) {
	for index := range value {
		value[index] = 0
	}
}

func readReleasePrivateKey(path string) (ed25519.PrivateKey, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || !secretFileProtected(path, info) {
		return nil, errors.New("release private key file permissions are too broad")
	}
	contents, err := readLimitedFile(path, 4096)
	if err != nil {
		return nil, err
	}
	decoded, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(string(contents)))
	if err != nil {
		return nil, errors.New("release private key is not valid base64url")
	}
	if len(decoded) == ed25519.SeedSize {
		return ed25519.NewKeyFromSeed(decoded), nil
	}
	if len(decoded) != ed25519.PrivateKeySize {
		return nil, errors.New("release private key has invalid length")
	}
	return ed25519.PrivateKey(decoded), nil
}

func writeNewFile(path string, contents []byte, mode os.FileMode) error {
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
	if err != nil {
		return err
	}
	written := false
	defer func() {
		_ = file.Close()
		if !written {
			_ = os.Remove(path)
		}
	}()
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
	return nil
}

func compareSemanticVersions(left string, right string) (int, error) {
	l, err := parseSemanticVersion(left)
	if err != nil {
		return 0, err
	}
	r, err := parseSemanticVersion(right)
	if err != nil {
		return 0, err
	}
	for index := range l {
		if l[index] < r[index] {
			return -1, nil
		}
		if l[index] > r[index] {
			return 1, nil
		}
	}
	return 0, nil
}

func parseSemanticVersion(value string) ([3]int, error) {
	var result [3]int
	parts := strings.Split(strings.TrimPrefix(strings.TrimSpace(value), "v"), ".")
	if len(parts) != 3 {
		return result, fmt.Errorf("release version %q must be major.minor.patch", value)
	}
	for index, part := range parts {
		parsed, err := strconv.Atoi(part)
		if err != nil || parsed < 0 {
			return result, fmt.Errorf("release version %q must be major.minor.patch", value)
		}
		result[index] = parsed
	}
	return result, nil
}
