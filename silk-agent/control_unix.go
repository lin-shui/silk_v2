//go:build !windows

package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"time"
)

func listenControl(path string) (net.Listener, error) {
	if existing, err := net.DialTimeout("unix", path, 250*time.Millisecond); err == nil {
		_ = existing.Close()
		return nil, errors.New("silk-agent control socket is already in use")
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return nil, fmt.Errorf("remove stale host socket: %w", err)
	}
	listener, err := net.Listen("unix", path)
	if err != nil {
		return nil, fmt.Errorf("listen on host control socket: %w", err)
	}
	if err := os.Chmod(path, 0o600); err != nil {
		_ = listener.Close()
		_ = os.Remove(path)
		return nil, fmt.Errorf("protect host control socket: %w", err)
	}
	return listener, nil
}

func dialControl(path string) (net.Conn, error) {
	connection, err := net.DialTimeout("unix", path, 2*time.Second)
	if err != nil {
		return nil, errors.New("silk-agent host is not running")
	}
	return connection, nil
}

func serveControlConnection(connection net.Conn, handler func(hostCommand) hostCommandResponse) {
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(5 * time.Second))
	var command hostCommand
	if err := json.NewDecoder(connection).Decode(&command); err != nil {
		_ = json.NewEncoder(connection).Encode(hostCommandResponse{Message: "invalid host command"})
		return
	}
	_ = json.NewEncoder(connection).Encode(handler(command))
}

func closeControl(listener net.Listener, path string) {
	if listener != nil {
		_ = listener.Close()
	}
	_ = os.Remove(path)
}
