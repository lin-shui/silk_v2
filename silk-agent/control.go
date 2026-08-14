package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	controlSocketFileName = "host.sock"
	pidFileName           = "host.pid"
	logFileName           = "host.log"
)

type hostCommand struct {
	Command   string `json:"command"`
	AgentType string `json:"agentType,omitempty"`
}

type hostCommandResponse struct {
	OK      bool              `json:"ok"`
	Message string            `json:"message,omitempty"`
	Status  *hostStatusReport `json:"status,omitempty"`
}

type hostStatusReport struct {
	PID    int                     `json:"pid"`
	Agents map[string]agentRuntime `json:"agents"`
}

type agentRuntime struct {
	AgentInstanceID string        `json:"agentInstanceId"`
	AgentType       string        `json:"agentType"`
	Enabled         bool          `json:"enabled"`
	Connected       bool          `json:"connected"`
	Adapter         adapterStatus `json:"adapter"`
}

func controlSocketPath(configDir string) string {
	return filepath.Join(configDir, controlSocketFileName)
}
func pidFilePath(configDir string) string { return filepath.Join(configDir, pidFileName) }
func hostLogPath(configDir string) string { return filepath.Join(configDir, logFileName) }

func writePID(path string, pid int) error {
	directory := filepath.Dir(path)
	contents := []byte(strconv.Itoa(pid) + "\n")
	temporary, err := os.CreateTemp(directory, ".host.pid.*")
	if err != nil {
		return fmt.Errorf("create host PID file: %w", err)
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		_ = temporary.Close()
		return fmt.Errorf("protect host PID file: %w", err)
	}
	if _, err := temporary.Write(contents); err != nil {
		_ = temporary.Close()
		return fmt.Errorf("write host PID file: %w", err)
	}
	if err := temporary.Sync(); err != nil {
		_ = temporary.Close()
		return fmt.Errorf("sync host PID file: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close host PID file: %w", err)
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		return fmt.Errorf("install host PID file: %w", err)
	}
	return syncDirectory(directory)
}

func readPID(path string) (int, error) {
	contents, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(contents)))
	if err != nil || pid <= 0 {
		return 0, errors.New("host PID file is invalid")
	}
	return pid, nil
}

func removePIDIfOwned(path string, pid int) {
	current, err := readPID(path)
	if err == nil && current == pid {
		_ = os.Remove(path)
	}
}

func processRunning(pid int) bool { return processExists(pid) }

func startCommand(configDir string, config hostConfig) error {
	if config.DeviceID == "" {
		return errors.New("no enrolled device; run silk-agent connect first")
	}
	if len(config.Agents) == 0 {
		return errors.New("no Agent instances are configured")
	}
	pidPath := pidFilePath(configDir)
	if pid, err := readPID(pidPath); err == nil {
		if processRunning(pid) {
			return fmt.Errorf("silk-agent is already running (pid %d)", pid)
		}
		_ = os.Remove(pidPath)
	} else if !os.IsNotExist(err) {
		return err
	}
	if err := os.MkdirAll(configDir, 0o700); err != nil {
		return fmt.Errorf("create host directory: %w", err)
	}
	logFile, err := os.OpenFile(hostLogPath(configDir), os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return fmt.Errorf("open host log: %w", err)
	}
	if err := logFile.Chmod(0o600); err != nil {
		_ = logFile.Close()
		return fmt.Errorf("protect host log: %w", err)
	}
	executable, err := os.Executable()
	if err != nil {
		_ = logFile.Close()
		return fmt.Errorf("resolve silk-agent executable: %w", err)
	}
	devNull, err := os.Open(os.DevNull)
	if err != nil {
		_ = logFile.Close()
		return fmt.Errorf("open host stdin: %w", err)
	}
	command := &os.ProcAttr{
		Dir:   "",
		Files: []*os.File{devNull, logFile, logFile},
		Env:   os.Environ(),
		Sys:   detachedProcessAttributes(),
	}
	process, err := os.StartProcess(
		executable,
		[]string{executable, "run", "--config-dir", configDir},
		command,
	)
	_ = devNull.Close()
	_ = logFile.Close()
	if err != nil {
		return fmt.Errorf("start silk-agent host: %w", err)
	}
	_ = process.Release()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if response, controlErr := sendHostCommand(configDir, hostCommand{Command: "status"}); controlErr == nil && response.Status != nil {
			fmt.Printf("silk-agent started (pid %d)\n", response.Status.PID)
			return nil
		}
		if !processRunning(process.Pid) {
			return errors.New("silk-agent host exited during startup")
		}
		time.Sleep(25 * time.Millisecond)
	}
	fmt.Printf("silk-agent started (pid %d)\n", process.Pid)
	return nil
}

func logsCommand(configDir string, follow bool) error {
	path := hostLogPath(configDir)
	if follow {
		return followFile(path)
	}
	contents, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return errors.New("silk-agent has no log yet")
	}
	if err != nil {
		return fmt.Errorf("read host log: %w", err)
	}
	// Keep the CLI useful on long-lived hosts without loading an unbounded log
	// into the terminal. A line is deliberately not parsed as JSON here: adapter
	// diagnostics may be plain text during the compatibility phase.
	lines := strings.Split(string(contents), "\n")
	start := 0
	if len(lines) > 201 {
		start = len(lines) - 201
	}
	fmt.Print(strings.Join(lines[start:], "\n"))
	return nil
}

func followFile(path string) error {
	state := fileFollowState{}
	if err := copyFileChanges(path, os.Stdout, &state); err != nil {
		return err
	}
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for range ticker.C {
		if err := copyFileChanges(path, os.Stdout, &state); err != nil {
			return err
		}
	}
	return nil
}

type fileFollowState struct {
	info   os.FileInfo
	offset int64
}

func copyFileChanges(path string, output io.Writer, state *fileFollowState) error {
	file, err := os.Open(path)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("open host log: %w", err)
	}
	defer file.Close()

	info, err := file.Stat()
	if err != nil {
		return fmt.Errorf("stat host log: %w", err)
	}
	if state.info == nil || !os.SameFile(state.info, info) || info.Size() < state.offset {
		state.offset = 0
	}
	state.info = info
	if _, err := file.Seek(state.offset, io.SeekStart); err != nil {
		return fmt.Errorf("seek host log: %w", err)
	}
	written, err := io.Copy(output, file)
	state.offset += written
	if err != nil {
		return fmt.Errorf("follow host log: %w", err)
	}
	return nil
}

func sendHostCommand(configDir string, command hostCommand) (hostCommandResponse, error) {
	connection, err := dialControl(controlSocketPath(configDir))
	if err != nil {
		return hostCommandResponse{}, err
	}
	defer connection.Close()
	if err := json.NewEncoder(connection).Encode(command); err != nil {
		return hostCommandResponse{}, fmt.Errorf("send host command: %w", err)
	}
	var response hostCommandResponse
	if err := json.NewDecoder(connection).Decode(&response); err != nil {
		return hostCommandResponse{}, fmt.Errorf("read host command response: %w", err)
	}
	if !response.OK {
		return response, errors.New(response.Message)
	}
	return response, nil
}
