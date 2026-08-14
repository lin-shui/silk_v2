//go:build !windows

package main

import (
	"fmt"
	"os"
)

func protectPrivateDirectory(path string) error {
	if err := os.MkdirAll(path, 0o700); err != nil {
		return fmt.Errorf("create private directory: %w", err)
	}
	if err := os.Chmod(path, 0o700); err != nil {
		return fmt.Errorf("set private directory mode: %w", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		return fmt.Errorf("stat private directory: %w", err)
	}
	if !info.IsDir() || info.Mode().Perm()&0o077 != 0 {
		return fmt.Errorf("private directory permissions are too broad; expected 0700")
	}
	return nil
}

func secretFileProtected(path string, info os.FileInfo) bool {
	_ = path
	return info.Mode().IsRegular() && info.Mode().Perm()&0o077 == 0
}
