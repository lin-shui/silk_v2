//go:build windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
)

func adapterFileExecutable(info os.FileInfo) bool {
	return true
}

func platformValidateAdapterExecutable(path string, info os.FileInfo) error {
	if !windowsExecutablePathIsTrusted(path) {
		return fmt.Errorf("managed Adapter executable must not be writable by another Windows user")
	}
	return nil
}

func platformValidateAdapterSource(path string, info os.FileInfo) error {
	return platformValidateAdapterExecutable(path, info)
}

func platformPackagedAdapterSpec(directory string, agentType string) (adapterProcessSpec, bool, error) {
	module := map[string]string{
		"claude-code": "cc_bridge/acp_adapter.py",
		"codex":       "codex_bridge/codex_adapter.py",
	}[agentType]
	for _, root := range []string{directory, filepath.Clean(filepath.Join(directory, ".."))} {
		script := filepath.Join(root, filepath.FromSlash(module))
		info, err := os.Lstat(script)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
			return adapterProcessSpec{}, false, fmt.Errorf("managed Adapter script %s is not a trusted regular file", script)
		}
		if err := platformValidateAdapterExecutable(script, info); err != nil {
			return adapterProcessSpec{}, false, fmt.Errorf("managed Adapter script %s: %w", script, err)
		}
		if err := validatePackagedAdapterSourceTree(directory, agentType); err != nil {
			return adapterProcessSpec{}, false, err
		}
		python := strings.TrimSpace(os.Getenv("BRIDGE_PYTHON"))
		if python == "" {
			python, err = exec.LookPath("python.exe")
		}
		if err != nil || python == "" {
			return adapterProcessSpec{}, false, fmt.Errorf("managed Adapter requires Python: %w", err)
		}
		return adapterProcessSpec{
			Path:       python,
			Args:       []string{script, "--silk-host-stdio"},
			WorkingDir: root,
		}, true, nil
	}
	return adapterProcessSpec{}, false, nil
}

func prepareAdapterCommand(command *exec.Cmd) {
	command.SysProcAttr = &syscall.SysProcAttr{
		CreationFlags: newProcessGroupCreationFlag | noWindowCreationFlag,
	}
}

func requestAdapterStop(command *exec.Cmd) {
	if command.Process != nil {
		_ = command.Process.Kill()
	}
}

func killAdapterProcess(command *exec.Cmd) {
	if command.Process != nil {
		_ = command.Process.Kill()
	}
}
