//go:build !windows

package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestHostControlSocketIsPrivateAndRoutesCommands(t *testing.T) {
	directory := t.TempDir()
	path := controlSocketPath(directory)
	listener, err := listenControl(path)
	if err != nil {
		t.Fatal(err)
	}
	defer closeControl(listener, path)

	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if permissions := info.Mode().Perm(); permissions != 0o600 {
		t.Fatalf("expected host socket mode 0600, got %o", permissions)
	}

	done := make(chan struct{})
	go func() {
		connection, acceptErr := listener.Accept()
		if acceptErr == nil {
			serveControlConnection(connection, func(command hostCommand) hostCommandResponse {
				return hostCommandResponse{OK: command.Command == "status", Message: command.Command}
			})
		}
		close(done)
	}()
	response, err := sendHostCommand(directory, hostCommand{Command: "status"})
	if err != nil {
		t.Fatal(err)
	}
	if !response.OK || response.Message != "status" {
		t.Fatalf("unexpected control response: %#v", response)
	}
	<-done
}

func TestPIDRoundTripAndStalePIDRemoval(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, pidFileName)
	if err := os.WriteFile(path+".new", []byte("stale"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := writePID(path, 12345); err != nil {
		t.Fatal(err)
	}
	pid, err := readPID(path)
	if err != nil || pid != 12345 {
		t.Fatalf("unexpected PID %d (%v)", pid, err)
	}
	removePIDIfOwned(path, 54321)
	if _, err := os.Stat(path); err != nil {
		t.Fatal(err)
	}
	removePIDIfOwned(path, 12345)
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("expected owned PID file to be removed, got %v", err)
	}
}
