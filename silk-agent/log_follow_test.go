package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCopyFileChangesOnlyWritesAppendedBytes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "host.log")
	if err := os.WriteFile(path, []byte("first\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	var output strings.Builder
	state := fileFollowState{}
	if err := copyFileChanges(path, &output, &state); err != nil {
		t.Fatal(err)
	}
	if err := copyFileChanges(path, &output, &state); err != nil {
		t.Fatal(err)
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := file.WriteString("second\n"); err != nil {
		_ = file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	if err := copyFileChanges(path, &output, &state); err != nil {
		t.Fatal(err)
	}
	if actual := output.String(); actual != "first\nsecond\n" {
		t.Fatalf("expected each log byte once, got %q", actual)
	}
}

func TestCopyFileChangesRestartsAfterTruncation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "host.log")
	if err := os.WriteFile(path, []byte("old log contents\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	var output strings.Builder
	state := fileFollowState{}
	if err := copyFileChanges(path, &output, &state); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("new\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := copyFileChanges(path, &output, &state); err != nil {
		t.Fatal(err)
	}
	if actual := output.String(); actual != "old log contents\nnew\n" {
		t.Fatalf("expected truncated log to restart at byte zero, got %q", actual)
	}
}

func TestCopyFileChangesRestartsAfterReplacement(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "host.log")
	if err := os.WriteFile(path, []byte("old\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	var output strings.Builder
	state := fileFollowState{}
	if err := copyFileChanges(path, &output, &state); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(path, filepath.Join(directory, "host.log.1")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("replacement is longer\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := copyFileChanges(path, &output, &state); err != nil {
		t.Fatal(err)
	}
	if actual := output.String(); actual != "old\nreplacement is longer\n" {
		t.Fatalf("expected replacement log to restart at byte zero, got %q", actual)
	}
}
