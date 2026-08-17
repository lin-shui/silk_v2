//go:build windows

package main

import (
	"os"
	"syscall"
)

const (
	detachedProcessCreationFlag = 0x00000008
	newProcessGroupCreationFlag = 0x00000200
	noWindowCreationFlag        = 0x08000000
)

func processExists(pid int) bool {
	process, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	return process != nil
}

func detachedProcessAttributes() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{
		CreationFlags: detachedProcessCreationFlag | newProcessGroupCreationFlag,
	}
}
