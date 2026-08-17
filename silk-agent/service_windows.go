//go:build windows

package main

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
)

const windowsServiceName = "SilkAgent"

func installService(configDir string, config hostConfig) error {
	if config.DeviceID == "" {
		return errors.New("no enrolled device; run silk-agent connect first")
	}
	if len(config.Agents) == 0 {
		return errors.New("no Agent instances are configured")
	}
	executable, err := os.Executable()
	if err != nil {
		return fmt.Errorf("resolve silk-agent executable: %w", err)
	}
	action, err := renderWindowsTaskAction(executable, configDir)
	if err != nil {
		return err
	}
	return runWindowsServiceCommand(
		"/Create", "/TN", windowsServiceName, "/SC", "ONLOGON", "/TR", action, "/F",
	)
}

func uninstallService(configDir string) error {
	_ = configDir
	return runWindowsServiceCommand("/Delete", "/TN", windowsServiceName, "/F")
}

func serviceStatus(configDir string) (string, error) {
	_ = configDir
	output, err := exec.Command("schtasks", "/Query", "/TN", windowsServiceName, "/FO", "LIST").CombinedOutput()
	if err != nil {
		return "inactive", nil
	}
	if strings.Contains(strings.ToLower(string(output)), "running") {
		return "active", nil
	}
	return "installed", nil
}

func runWindowsServiceCommand(arguments ...string) error {
	output, err := exec.Command("schtasks", arguments...).CombinedOutput()
	if err != nil {
		message := strings.TrimSpace(string(output))
		if message == "" {
			message = err.Error()
		}
		return fmt.Errorf("schtasks %s failed: %s", strings.Join(arguments, " "), message)
	}
	return nil
}
