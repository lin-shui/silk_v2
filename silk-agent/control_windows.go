//go:build windows

package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"path/filepath"
	"strings"
	"time"

	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
)

func listenControl(path string) (net.Listener, error) {
	pipePath := windowsControlPipePath(path)
	timeout := 250 * time.Millisecond
	if existing, err := winio.DialPipe(pipePath, &timeout); err == nil {
		_ = existing.Close()
		return nil, errors.New("silk-agent control pipe is already in use")
	}
	descriptor, err := currentUserPipeSecurityDescriptor()
	if err != nil {
		return nil, err
	}
	listener, err := winio.ListenPipe(pipePath, &winio.PipeConfig{
		SecurityDescriptor: descriptor,
		InputBufferSize:    64 * 1024,
		OutputBufferSize:   64 * 1024,
	})
	if err != nil {
		return nil, fmt.Errorf("listen on protected Host control pipe: %w", err)
	}
	return listener, nil
}

func dialControl(path string) (net.Conn, error) {
	timeout := 2 * time.Second
	connection, err := winio.DialPipe(windowsControlPipePath(path), &timeout)
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
	_ = path
	if listener != nil {
		_ = listener.Close()
	}
}

func windowsControlPipePath(path string) string {
	normalized := strings.ToLower(filepath.Clean(path))
	digest := sha256.Sum256([]byte(normalized))
	return `\\.\pipe\silk-agent-` + hex.EncodeToString(digest[:12])
}

func currentUserPipeSecurityDescriptor() (string, error) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return "", fmt.Errorf("resolve current Windows user for Host control pipe: %w", err)
	}
	return fmt.Sprintf("D:P(A;;GA;;;%s)", user.User.Sid.String()), nil
}
