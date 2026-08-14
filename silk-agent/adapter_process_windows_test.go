//go:build windows

package main

import (
	"os/exec"
	"testing"
)

func TestManagedAdapterRunsWithoutVisibleConsoleWindow(t *testing.T) {
	command := exec.Command("python.exe")
	prepareAdapterCommand(command)
	if command.SysProcAttr == nil {
		t.Fatal("expected Windows process attributes")
	}
	expected := uint32(newProcessGroupCreationFlag | noWindowCreationFlag)
	if command.SysProcAttr.CreationFlags != expected {
		t.Fatalf("expected creation flags %#x, got %#x", expected, command.SysProcAttr.CreationFlags)
	}
}
