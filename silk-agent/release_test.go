package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"os"
	"path/filepath"
	"testing"
)

func TestSignedReleaseManifestRejectsTamperedArtifact(t *testing.T) {
	directory := t.TempDir()
	artifacts := filepath.Join(directory, "artifacts")
	if err := os.Mkdir(artifacts, 0o700); err != nil {
		t.Fatal(err)
	}
	artifactPath := filepath.Join(artifacts, "silk-agent_0.5.0_linux_amd64.tar.gz")
	if err := os.WriteFile(artifactPath, []byte("signed artifact"), 0o600); err != nil {
		t.Fatal(err)
	}
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	privateKeyPath := filepath.Join(directory, "release-private-key")
	if err := os.WriteFile(
		privateKeyPath,
		[]byte(base64.RawURLEncoding.EncodeToString(privateKey)),
		0o600,
	); err != nil {
		t.Fatal(err)
	}
	publicKeyPath := filepath.Join(directory, "release-public-key")
	if err := os.WriteFile(
		publicKeyPath,
		[]byte(base64.RawURLEncoding.EncodeToString(publicKey)),
		0o600,
	); err != nil {
		t.Fatal(err)
	}
	manifestPath := filepath.Join(directory, "manifest.json")
	signaturePath := filepath.Join(directory, "manifest.sig")
	if err := createReleaseManifestFiles(
		"0.5.0", artifacts, privateKeyPath, manifestPath, signaturePath,
	); err != nil {
		t.Fatal(err)
	}
	manifest, err := verifyReleaseFiles(
		manifestPath, signaturePath, artifacts, publicKeyPath,
	)
	if err != nil {
		t.Fatal(err)
	}
	if manifest.Version != "0.5.0" {
		t.Fatalf("unexpected verified version %s", manifest.Version)
	}
	if comparison, err := compareSemanticVersions(manifest.Version, hostVersion); err != nil || comparison <= 0 {
		t.Fatal("expected test release to be newer than the current Host")
	}
	unsignedPath := filepath.Join(artifacts, "unsigned-helper.exe")
	if err := os.WriteFile(unsignedPath, []byte("unsigned"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := verifyReleaseFiles(manifestPath, signaturePath, artifacts, publicKeyPath); err == nil {
		t.Fatal("expected an artifact omitted from the signed manifest to fail verification")
	}
	if err := os.Remove(unsignedPath); err != nil {
		t.Fatal(err)
	}
	hiddenUnsignedPath := filepath.Join(artifacts, ".unsigned")
	if err := os.WriteFile(hiddenUnsignedPath, []byte("unsigned"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := verifyReleaseFiles(manifestPath, signaturePath, artifacts, publicKeyPath); err == nil {
		t.Fatal("expected a hidden artifact omitted from the signed manifest to fail verification")
	}
	if err := os.Remove(hiddenUnsignedPath); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(artifactPath, []byte("tampered artifact"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := verifyReleaseFiles(manifestPath, signaturePath, artifacts, publicKeyPath); err == nil {
		t.Fatal("expected artifact tampering to fail verification")
	}
}

func TestVersionDoesNotRequireAHostProfile(t *testing.T) {
	invalidHome := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(invalidHome, []byte("file"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("SILK_AGENT_HOME", invalidHome)
	if err := run([]string{"version"}); err != nil {
		t.Fatalf("version unexpectedly loaded the Host profile: %v", err)
	}
}

func TestEmbeddedReleaseTrustCannotBeOverridden(t *testing.T) {
	previous := releasePublicKey
	releasePublicKey = base64.RawURLEncoding.EncodeToString(make([]byte, ed25519.PublicKeySize))
	t.Cleanup(func() { releasePublicKey = previous })
	if _, err := trustedReleasePublicKey(filepath.Join(t.TempDir(), "attacker-key")); err == nil {
		t.Fatal("expected production release trust override to be rejected")
	}
}
