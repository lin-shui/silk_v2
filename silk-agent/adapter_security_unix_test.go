//go:build !windows

package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestManagedAdapterRejectsWritableOrSymlinkExecutable(t *testing.T) {
	directory := t.TempDir()
	if err := os.Chmod(directory, 0o700); err != nil {
		t.Fatal(err)
	}
	executable := filepath.Join(directory, "adapter")
	if err := os.WriteFile(executable, []byte("#!/bin/sh\nexit 0\n"), 0o775); err != nil {
		t.Fatal(err)
	}
	if err := validateAdapterExecutable(executable); err == nil {
		t.Fatal("expected group-writable Adapter executable to be rejected")
	}
	if err := os.Chmod(executable, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := validateAdapterExecutable(executable); err != nil {
		t.Fatal(err)
	}
	symlink := filepath.Join(directory, "adapter-link")
	if err := os.Symlink(executable, symlink); err != nil {
		t.Fatal(err)
	}
	if err := validateAdapterExecutable(symlink); err == nil {
		t.Fatal("expected symlinked Adapter executable to be rejected")
	}
}

func TestManagedAdapterRejectsExecutableBelowWritableParent(t *testing.T) {
	root := t.TempDir()
	if err := os.Chmod(root, 0o700); err != nil {
		t.Fatal(err)
	}
	directory := filepath.Join(root, "writable")
	if err := os.Mkdir(directory, 0o700); err != nil {
		t.Fatal(err)
	}
	executable := filepath.Join(directory, "adapter")
	if err := os.WriteFile(executable, []byte("#!/bin/sh\nexit 0\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(directory, 0o777); err != nil {
		t.Fatal(err)
	}
	if err := validateAdapterExecutable(executable); err == nil {
		t.Fatal("expected Adapter below writable parent directory to be rejected")
	}
}

func TestPackagedAdapterRejectsWritableImportedPythonModule(t *testing.T) {
	root := t.TempDir()
	if err := os.Chmod(root, 0o700); err != nil {
		t.Fatal(err)
	}
	for _, directory := range []string{"bridge_common", "cc_bridge"} {
		if err := os.Mkdir(filepath.Join(root, directory), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(root, directory, "module.py"), []byte("pass\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if err := validatePackagedAdapterSources(root, "claude-code"); err != nil {
		t.Fatal(err)
	}
	module := filepath.Join(root, "cc_bridge", "module.py")
	if err := os.Chmod(module, 0o664); err != nil {
		t.Fatal(err)
	}
	if err := validatePackagedAdapterSources(root, "claude-code"); err == nil {
		t.Fatal("expected a group-writable imported Python module to be rejected")
	}
}
