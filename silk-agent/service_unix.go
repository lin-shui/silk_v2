//go:build !windows

package main

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

const (
	serviceName     = "com.silk.silk-agent"
	systemdUnitName = "silk-agent.service"
	launchAgentName = "com.silk.silk-agent.plist"
)

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
	if runtime.GOOS == "darwin" {
		return installLaunchAgent(executable, configDir)
	}
	return installSystemdUserUnit(executable, configDir)
}

func uninstallService(configDir string) error {
	if runtime.GOOS == "darwin" {
		return uninstallLaunchAgent(configDir)
	}
	return uninstallSystemdUserUnit(configDir)
}

func serviceStatus(configDir string) (string, error) {
	if runtime.GOOS == "darwin" {
		return launchAgentStatus(configDir)
	}
	return systemdUserStatus()
}

func installSystemdUserUnit(executable, configDir string) error {
	base, err := os.UserConfigDir()
	if err != nil {
		return fmt.Errorf("resolve user config directory: %w", err)
	}
	unitDir := filepath.Join(base, "systemd", "user")
	if err := os.MkdirAll(unitDir, 0o700); err != nil {
		return fmt.Errorf("create systemd user directory: %w", err)
	}
	unitPath := filepath.Join(unitDir, systemdUnitName)
	contents := renderSystemdUserUnit(executable, configDir)
	if err := os.WriteFile(unitPath, []byte(contents), 0o600); err != nil {
		return fmt.Errorf("write systemd user unit: %w", err)
	}
	if err := runServiceManager("systemctl", "--user", "daemon-reload"); err != nil {
		return err
	}
	if err := runServiceManager("systemctl", "--user", "enable", "--now", systemdUnitName); err != nil {
		return err
	}
	fmt.Printf("Installed and started %s\n", unitPath)
	return nil
}

func uninstallSystemdUserUnit(configDir string) error {
	_ = configDir
	_ = runServiceManager("systemctl", "--user", "disable", "--now", systemdUnitName)
	base, err := os.UserConfigDir()
	if err != nil {
		return fmt.Errorf("resolve user config directory: %w", err)
	}
	unitPath := filepath.Join(base, "systemd", "user", systemdUnitName)
	if err := os.Remove(unitPath); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("remove systemd user unit: %w", err)
	}
	if err := runServiceManager("systemctl", "--user", "daemon-reload"); err != nil {
		return err
	}
	fmt.Printf("Uninstalled %s\n", unitPath)
	return nil
}

func systemdUserStatus() (string, error) {
	output, err := exec.Command("systemctl", "--user", "is-active", systemdUnitName).CombinedOutput()
	status := strings.TrimSpace(string(output))
	if status == "" {
		status = "inactive"
	}
	if err != nil && status != "inactive" && status != "failed" {
		return "", fmt.Errorf("query systemd user service: %w", err)
	}
	return status, nil
}

func installLaunchAgent(executable, configDir string) error {
	home, err := os.UserHomeDir()
	if err != nil {
		return fmt.Errorf("resolve home directory: %w", err)
	}
	launchDir := filepath.Join(home, "Library", "LaunchAgents")
	if err := os.MkdirAll(launchDir, 0o700); err != nil {
		return fmt.Errorf("create LaunchAgents directory: %w", err)
	}
	path := filepath.Join(launchDir, launchAgentName)
	if err := os.WriteFile(path, []byte(renderLaunchAgentPlist(executable, configDir)), 0o600); err != nil {
		return fmt.Errorf("write LaunchAgent: %w", err)
	}
	uid := strconv.Itoa(os.Getuid())
	_ = runServiceManager("launchctl", "bootout", "gui/"+uid+"/"+serviceName)
	if err := runServiceManager("launchctl", "bootstrap", "gui/"+uid, path); err != nil {
		return err
	}
	fmt.Printf("Installed and started %s\n", path)
	return nil
}

func uninstallLaunchAgent(configDir string) error {
	_ = configDir
	home, err := os.UserHomeDir()
	if err != nil {
		return fmt.Errorf("resolve home directory: %w", err)
	}
	path := filepath.Join(home, "Library", "LaunchAgents", launchAgentName)
	uid := strconv.Itoa(os.Getuid())
	_ = runServiceManager("launchctl", "bootout", "gui/"+uid+"/"+serviceName)
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("remove LaunchAgent: %w", err)
	}
	fmt.Printf("Uninstalled %s\n", path)
	return nil
}

func launchAgentStatus(configDir string) (string, error) {
	_ = configDir
	uid := strconv.Itoa(os.Getuid())
	output, err := exec.Command("launchctl", "print", "gui/"+uid+"/"+serviceName).CombinedOutput()
	if err != nil {
		return "inactive", nil
	}
	if strings.TrimSpace(string(output)) == "" {
		return "inactive", nil
	}
	return "active", nil
}

func runServiceManager(name string, arguments ...string) error {
	if _, err := exec.LookPath(name); err != nil {
		return fmt.Errorf("%s is required for service management: %w", name, err)
	}
	command := exec.Command(name, arguments...)
	output, err := command.CombinedOutput()
	if err != nil {
		message := strings.TrimSpace(string(output))
		if message == "" {
			message = err.Error()
		}
		return fmt.Errorf("%s %s failed: %s", name, strings.Join(arguments, " "), message)
	}
	return nil
}

func renderSystemdUserUnit(executable, configDir string) string {
	return fmt.Sprintf(`[Unit]
Description=Silk external Agent Host
After=default.target

[Service]
Type=simple
ExecStart=%s run
Environment=SILK_AGENT_HOME=%s
Restart=on-failure
RestartSec=5
NoNewPrivileges=true

[Install]
WantedBy=default.target
`, systemdEscape(executable), systemdEscape(configDir))
}

func renderLaunchAgentPlist(executable, configDir string) string {
	return fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>%s</string>
  <key>ProgramArguments</key>
  <array><string>%s</string><string>run</string></array>
  <key>EnvironmentVariables</key>
  <dict><key>SILK_AGENT_HOME</key><string>%s</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict>
</plist>
`, serviceName, plistEscape(executable), plistEscape(configDir))
}

func systemdEscape(value string) string {
	return strings.ReplaceAll(strings.ReplaceAll(value, `\`, `\\`), ` `, `\x20`)
}

func plistEscape(value string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;").Replace(value)
}
