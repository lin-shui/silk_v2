//go:build !windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
)

func adapterFileExecutable(info os.FileInfo) bool {
	return info.Mode().Perm()&0o111 != 0 && info.Mode().Perm()&0o022 == 0
}

func platformValidateAdapterExecutable(path string, info os.FileInfo) error {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || (stat.Uid != uint32(os.Getuid()) && stat.Uid != 0) {
		return fmt.Errorf("managed Adapter executable must be owned by the current user or root")
	}
	if info.Mode()&(os.ModeSetuid|os.ModeSetgid) != 0 {
		return fmt.Errorf("managed Adapter executable must not be setuid or setgid")
	}
	absPath, err := filepath.Abs(path)
	if err != nil {
		return fmt.Errorf("resolve managed Adapter path: %w", err)
	}
	for directory := filepath.Dir(absPath); ; directory = filepath.Dir(directory) {
		directoryInfo, err := os.Lstat(directory)
		if err != nil {
			return fmt.Errorf("inspect managed Adapter parent directory %s: %w", directory, err)
		}
		if directoryInfo.Mode()&os.ModeSymlink != 0 || !directoryInfo.IsDir() {
			return fmt.Errorf("managed Adapter parent path %s is not a trusted directory", directory)
		}
		writableByOthers := directoryInfo.Mode().Perm()&0o022 != 0
		stickyWorldWritable := directoryInfo.Mode().Perm()&0o002 != 0 && directoryInfo.Mode()&os.ModeSticky != 0
		if writableByOthers && !stickyWorldWritable {
			return fmt.Errorf("managed Adapter parent directory %s is writable by another user", directory)
		}
		parent := filepath.Dir(directory)
		if parent == directory {
			break
		}
	}
	return nil
}

func platformValidateAdapterSource(path string, info os.FileInfo) error {
	if info.Mode().Perm()&0o022 != 0 {
		return fmt.Errorf("Python module must not be writable by another user")
	}
	return platformValidateAdapterExecutable(path, info)
}

func platformPackagedAdapterSpec(directory string, agentType string) (adapterProcessSpec, bool, error) {
	name := "silk-" + agentType + "-adapter"
	for _, candidate := range []string{
		filepath.Join(directory, "adapters", name),
		filepath.Join(directory, name),
	} {
		if _, err := os.Stat(candidate); os.IsNotExist(err) {
			continue
		}
		if err := validateAdapterExecutable(candidate); err != nil {
			return adapterProcessSpec{}, false, fmt.Errorf("managed Adapter %s: %w", candidate, err)
		}
		if err := validatePackagedAdapterSourceTree(directory, agentType); err != nil {
			return adapterProcessSpec{}, false, err
		}
		return adapterProcessSpec{Path: candidate, Args: []string{"--silk-host-stdio"}}, true, nil
	}
	return adapterProcessSpec{}, false, nil
}

func prepareAdapterCommand(command *exec.Cmd) {
	command.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
}

func requestAdapterStop(command *exec.Cmd) {
	if command.Process != nil {
		_ = syscall.Kill(-command.Process.Pid, syscall.SIGTERM)
	}
}

func killAdapterProcess(command *exec.Cmd) {
	if command.Process != nil {
		_ = syscall.Kill(-command.Process.Pid, syscall.SIGKILL)
	}
}
