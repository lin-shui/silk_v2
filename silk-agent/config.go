package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

type hostConfig struct {
	ServerOrigin         string                 `json:"serverOrigin"`
	AuthenticationOrigin string                 `json:"authenticationOrigin,omitempty"`
	DeviceID             string                 `json:"deviceId"`
	DeviceName           string                 `json:"deviceName"`
	Platform             string                 `json:"platform"`
	AllowInsecureHTTP    bool                   `json:"allowInsecureHttp,omitempty"`
	Agents               map[string]agentConfig `json:"agents"`
}

type agentConfig struct {
	AgentInstanceID string   `json:"agentInstanceId"`
	AgentType       string   `json:"agentType"`
	Capabilities    []string `json:"capabilities"`
	Enabled         bool     `json:"enabled"`
}

func defaultConfigDir() (string, error) {
	if configured := os.Getenv("SILK_AGENT_HOME"); configured != "" {
		path, err := filepath.Abs(configured)
		if err != nil {
			return "", fmt.Errorf("resolve SILK_AGENT_HOME: %w", err)
		}
		return filepath.Clean(path), nil
	}
	base, err := os.UserConfigDir()
	if err != nil {
		return "", fmt.Errorf("resolve user config directory: %w", err)
	}
	return filepath.Join(base, "silk-agent"), nil
}

func loadHostConfig(path string) (hostConfig, error) {
	if err := protectConfigDirectory(filepath.Dir(path)); err != nil {
		return hostConfig{}, err
	}
	contents, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return hostConfig{Agents: map[string]agentConfig{}}, nil
	}
	if err != nil {
		return hostConfig{}, fmt.Errorf("read host config: %w", err)
	}
	var config hostConfig
	if err := json.Unmarshal(contents, &config); err != nil {
		return hostConfig{}, fmt.Errorf("parse host config: %w", err)
	}
	if config.Agents == nil {
		config.Agents = map[string]agentConfig{}
	}
	return config, nil
}

func saveHostConfig(path string, config hostConfig) error {
	directory := filepath.Dir(path)
	if err := protectConfigDirectory(directory); err != nil {
		return err
	}
	if config.Agents == nil {
		config.Agents = map[string]agentConfig{}
	}
	contents, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return fmt.Errorf("encode host config: %w", err)
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".config.*")
	if err != nil {
		return fmt.Errorf("create temporary host config: %w", err)
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		temporary.Close()
		return fmt.Errorf("protect temporary host config: %w", err)
	}
	if _, err := temporary.Write(contents); err != nil {
		temporary.Close()
		return fmt.Errorf("write host config: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close host config: %w", err)
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		return fmt.Errorf("install host config: %w", err)
	}
	if err := syncDirectory(directory); err != nil {
		return err
	}
	return nil
}

func protectConfigDirectory(directory string) error {
	if err := protectPrivateDirectory(directory); err != nil {
		return fmt.Errorf("protect host config directory: %w", err)
	}
	return nil
}
