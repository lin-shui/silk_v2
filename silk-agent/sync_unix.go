//go:build !windows

package main

import (
	"fmt"
	"os"
	"syscall"
)

func syncDirectory(path string) error {
	file, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open config directory for sync: %w", err)
	}
	defer file.Close()
	if err := file.Sync(); err != nil && err != syscall.EINVAL {
		return fmt.Errorf("sync config directory: %w", err)
	}
	return nil
}
