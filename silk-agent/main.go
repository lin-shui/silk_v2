package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

var hostVersion = "0.4.11"

type supportedAgent struct {
	Type         string
	DisplayName  string
	Capabilities []string
}

var supportedAgents = map[string]supportedAgent{
	"claude-code": {
		Type:         "claude-code",
		DisplayName:  "Claude Code",
		Capabilities: []string{"PROMPT", "STREAM", "CANCEL", "QUESTION_RESPONSE", "PERMISSION_RESPONSE", "SESSION_RESUME", "READ_FILE", "WRITE_FILE", "RUN_COMMAND", "EXECUTION_POLICY_V1", "EXECUTION_POLICY_V2", "READ_WORKSPACE", "WRITE_WORKSPACE", "IMAGE_INPUT", "IMAGE_OUTPUT"},
	},
	"codex": {
		Type:         "codex",
		DisplayName:  "Codex",
		Capabilities: []string{"PROMPT", "STREAM", "CANCEL", "QUESTION_RESPONSE", "PERMISSION_RESPONSE", "SESSION_RESUME", "READ_FILE", "WRITE_FILE", "RUN_COMMAND", "EXECUTION_POLICY_V1", "EXECUTION_POLICY_V2", "READ_WORKSPACE", "WRITE_WORKSPACE", "IMAGE_INPUT", "IMAGE_OUTPUT"},
	},
}

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "silk-agent:", err)
		os.Exit(1)
	}
}

func run(arguments []string) error {
	if len(arguments) == 0 {
		printUsage()
		return nil
	}
	switch arguments[0] {
	case "release":
		return releaseCommand(arguments[1:])
	case "version", "--version", "-v":
		fmt.Println(hostVersion)
		return nil
	case "help", "--help", "-h":
		printUsage()
		return nil
	}
	configDir, err := defaultConfigDir()
	if err != nil {
		return err
	}
	if arguments[0] == "run" {
		configDir, err = resolveRunConfigDir(arguments[1:], configDir)
		if err != nil {
			return err
		}
	}
	configPath := filepath.Join(configDir, "config.json")
	config, err := loadHostConfig(configPath)
	if err != nil {
		return err
	}

	switch arguments[0] {
	case "connect":
		return connectCommand(arguments[1:], configDir, configPath, config)
	case "run":
		return runCommand(configDir, config)
	case "start":
		return startCommand(configDir, config)
	case "status":
		return statusCommand(configDir, config)
	case "stop":
		return stopCommand(arguments[1:], configDir, configPath, config)
	case "logs":
		return logsCommandFromArgs(arguments[1:], configDir)
	case "service":
		return serviceCommand(arguments[1:], configDir, config)
	case "identity":
		return identityCommand(arguments[1:], configDir, configPath, config)
	default:
		return fmt.Errorf("unknown command %q", arguments[0])
	}
}

func connectCommand(arguments []string, configDir string, configPath string, config hostConfig) error {
	if len(arguments) == 0 {
		return errors.New("connect requires an Agent type: claude-code or codex")
	}
	agent, exists := supportedAgents[arguments[0]]
	if !exists {
		return fmt.Errorf("Agent %q is not available in the direct Bridge phase", arguments[0])
	}
	flags := flag.NewFlagSet("connect", flag.ContinueOnError)
	server := flags.String("server", config.ServerOrigin, "Silk backend http(s) origin")
	account := flags.String("account", "", "target Silk account login name (required for the first pairing)")
	web := flags.String("web", "", "Silk Web http(s) origin when it differs from --server")
	deviceName := flags.String("device-name", defaultDeviceName(), "device display name")
	noBrowser := flags.Bool("no-browser", false, "print the verification URL without opening a browser")
	allowInsecureHTTP := flags.Bool(
		"allow-insecure-http",
		config.AllowInsecureHTTP,
		"allow credential-bearing HTTP/WS for controlled development only",
	)
	if err := flags.Parse(arguments[1:]); err != nil {
		return err
	}
	if *server == "" {
		return errors.New("--server is required for the first pairing")
	}
	normalized, err := normalizeOrigin(*server)
	if err != nil {
		return err
	}
	serverOrigin := normalized.String()
	if err := validateServerTransport(serverOrigin, *allowInsecureHTTP); err != nil {
		return err
	}
	if config.ServerOrigin != "" && config.ServerOrigin != serverOrigin {
		return errors.New("this local Silk profile is already bound to a different server")
	}
	verificationOrigin := ""
	if strings.TrimSpace(*web) != "" {
		normalizedWeb, normalizeWebErr := normalizeOrigin(*web)
		if normalizeWebErr != nil {
			return fmt.Errorf("invalid --web origin: %w", normalizeWebErr)
		}
		verificationOrigin = normalizedWeb.String()
		if transportErr := validateServerTransport(verificationOrigin, *allowInsecureHTTP); transportErr != nil {
			return transportErr
		}
	}
	accountLoginName := strings.TrimSpace(*account)
	if config.DeviceID == "" && accountLoginName == "" {
		return errors.New("--account is required for the first pairing")
	}
	signer, err := loadDeviceSigner(configDir, config.DeviceID == "")
	if err != nil {
		return err
	}

	configuredAgent, alreadyEnrolled := config.Agents[agent.Type]
	if !alreadyEnrolled {
		client, clientErr := newSilkAPIClient(serverOrigin)
		if clientErr != nil {
			return clientErr
		}
		var created createPairingResponse
		var createErr error
		if config.DeviceID == "" {
			created, createErr = client.createPairing(context.Background(), createPairingRequest{
				ProtocolVersion:    protocolVersion,
				KeyAlgorithm:       keyAlgorithm,
				PublicKey:          signer.publicKeyEncoded(),
				AccountLoginName:   accountLoginName,
				ConnectionOrigin:   serverOrigin,
				VerificationOrigin: verificationOrigin,
				DeviceName:         *deviceName,
				Platform:           runtime.GOOS,
				AgentType:          agent.Type,
				AgentDisplayName:   agent.DisplayName,
				TransportAdapter:   "ACP",
				ConnectorVersion:   hostVersion,
				Capabilities:       agent.Capabilities,
			})
		} else {
			requestID, requestErr := randomRequestID()
			if requestErr != nil {
				return requestErr
			}
			timestamp := time.Now().UnixMilli()
			request := createTrustedDeviceAgentRequest{
				ProtocolVersion:    protocolVersion,
				DeviceID:           config.DeviceID,
				RequestID:          requestID,
				TimestampMs:        timestamp,
				AgentType:          agent.Type,
				AgentDisplayName:   agent.DisplayName,
				TransportAdapter:   "ACP",
				ConnectorVersion:   hostVersion,
				Capabilities:       agent.Capabilities,
				VerificationOrigin: verificationOrigin,
			}
			authenticationOrigin := config.AuthenticationOrigin
			if authenticationOrigin == "" {
				authenticationOrigin = serverOrigin
			}
			request.Signature = signer.sign(canonicalTrustedDeviceAgentRequest(
				authenticationOrigin,
				request.RequestID,
				request.DeviceID,
				request.AgentType,
				request.AgentDisplayName,
				request.TransportAdapter,
				request.ConnectorVersion,
				request.Capabilities,
				request.TimestampMs,
			))
			created, createErr = client.createAgentAddition(context.Background(), request)
		}
		if createErr != nil {
			return createErr
		}
		if config.DeviceID == "" {
			fmt.Println("Pairing is locked to Silk account:", accountLoginName)
		}
		fmt.Println("Open this Silk verification page:")
		fmt.Println(created.VerificationURI)
		fmt.Println("Pairing code:", created.UserCode)
		fmt.Println("The code expires in about 5 minutes.")
		if !*noBrowser {
			if browserErr := openBrowser(created.VerificationURI); browserErr != nil {
				fmt.Fprintln(os.Stderr, "Could not open a browser automatically; use the URL above.")
			}
		}
		pairingContext, cancelPairing := context.WithDeadline(context.Background(), time.UnixMilli(created.ExpiresAtEpochMs))
		completed, pairingErr := waitForPairingApproval(pairingContext, client, signer, created)
		cancelPairing()
		if pairingErr != nil {
			return pairingErr
		}
		config.ServerOrigin = serverOrigin
		config.AllowInsecureHTTP = normalized.Scheme == "http" && *allowInsecureHTTP
		config.AuthenticationOrigin = created.ServerOrigin
		config.DeviceID = completed.DeviceID
		if config.DeviceName == "" {
			config.DeviceName = *deviceName
		}
		if config.Platform == "" {
			config.Platform = runtime.GOOS
		}
		configuredAgent = agentConfig{
			AgentInstanceID: completed.AgentInstanceID,
			AgentType:       agent.Type,
			Capabilities:    agent.Capabilities,
			Enabled:         true,
		}
		config.Agents[agent.Type] = configuredAgent
		if err := saveHostConfig(configPath, config); err != nil {
			return err
		}
		fmt.Printf("Agent instance %s approved for device %s.\n", completed.AgentInstanceID, completed.DeviceID)
	} else {
		configuredAgent.Enabled = true
		config.Agents[agent.Type] = configuredAgent
		if err := saveHostConfig(configPath, config); err != nil {
			return err
		}
	}

	if response, controlErr := sendHostCommand(configDir, hostCommand{Command: "reload", AgentType: agent.Type}); controlErr == nil {
		if !response.OK {
			return errors.New(response.Message)
		}
		fmt.Printf("Agent %s loaded by the running Host.\n", agent.Type)
		return nil
	}
	return runHost(configDir, configPath, config, signer)
}

func runCommand(configDir string, config hostConfig) error {
	if config.DeviceID == "" {
		return errors.New("no enrolled device; run silk-agent connect first")
	}
	if err := validateServerTransport(config.ServerOrigin, config.AllowInsecureHTTP); err != nil {
		return err
	}
	signer, err := loadDeviceSigner(configDir, false)
	if err != nil {
		return err
	}
	return runHost(configDir, filepath.Join(configDir, "config.json"), config, signer)
}

func resolveRunConfigDir(arguments []string, fallback string) (string, error) {
	flags := flag.NewFlagSet("run", flag.ContinueOnError)
	configured := flags.String("config-dir", "", "absolute Silk Agent configuration directory")
	if err := flags.Parse(arguments); err != nil {
		return "", err
	}
	if flags.NArg() != 0 {
		return "", fmt.Errorf("run does not accept positional arguments: %q", flags.Arg(0))
	}
	if *configured == "" {
		return fallback, nil
	}
	path, err := filepath.Abs(*configured)
	if err != nil {
		return "", fmt.Errorf("resolve --config-dir: %w", err)
	}
	return filepath.Clean(path), nil
}

func logsCommandFromArgs(arguments []string, configDir string) error {
	flags := flag.NewFlagSet("logs", flag.ContinueOnError)
	follow := flags.Bool("follow", false, "wait for new host log output")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	return logsCommand(configDir, *follow)
}

func statusCommand(configDir string, config hostConfig) error {
	if config.DeviceID == "" {
		fmt.Println("No Silk device is enrolled.")
		return nil
	}
	signer, err := loadDeviceSigner(configDir, false)
	if err != nil {
		return err
	}
	fingerprintValue, err := fingerprint(signer.publicKeyEncoded())
	if err != nil {
		return err
	}
	fmt.Println("Server:", config.ServerOrigin)
	fmt.Println("Device:", config.DeviceName)
	fmt.Println("Device ID:", config.DeviceID)
	fmt.Println("Fingerprint:", fingerprintValue)
	fmt.Println("Key storage:", signer.backend)
	types := make([]string, 0, len(config.Agents))
	for agentType := range config.Agents {
		types = append(types, agentType)
	}
	sort.Strings(types)
	for _, agentType := range types {
		agent := config.Agents[agentType]
		state := "disabled"
		if agent.Enabled {
			state = "enabled"
		}
		fmt.Printf("Agent %s: %s (%s)\n", agent.AgentType, agent.AgentInstanceID, state)
	}
	if response, controlErr := sendHostCommand(configDir, hostCommand{Command: "status"}); controlErr == nil && response.Status != nil {
		for _, agentType := range types {
			runtimeState := response.Status.Agents[agentType]
			fmt.Printf(
				"  runtime %s: connected=%t adapter=%s restarts=%d\n",
				agentType,
				runtimeState.Connected,
				runtimeState.Adapter.State,
				runtimeState.Adapter.RestartCount,
			)
			if runtimeState.Adapter.LastError != "" {
				fmt.Printf("    adapter error: %s\n", runtimeState.Adapter.LastError)
			}
		}
	}
	return nil
}

func stopCommand(arguments []string, configDir string, configPath string, config hostConfig) error {
	if len(arguments) == 0 {
		return errors.New("stop requires an Agent type or host")
	}
	agentType := strings.TrimSpace(arguments[0])
	if agentType == "host" || agentType == "--host" {
		response, err := sendHostCommand(configDir, hostCommand{Command: "shutdown"})
		if err != nil {
			return err
		}
		fmt.Println(response.Message)
		return nil
	}
	agent, exists := config.Agents[agentType]
	if !exists {
		return fmt.Errorf("Agent %q is not configured", agentType)
	}
	agent.Enabled = false
	config.Agents[agentType] = agent
	if err := saveHostConfig(configPath, config); err != nil {
		return err
	}
	if response, err := sendHostCommand(configDir, hostCommand{Command: "stop", AgentType: agentType}); err == nil {
		fmt.Printf("Agent %s stopped (%s).\n", agentType, response.Message)
	} else {
		fmt.Printf("Agent %s disabled for the next silk-agent run.\n", agentType)
	}
	return nil
}

func defaultDeviceName() string {
	hostname, err := os.Hostname()
	if err != nil || strings.TrimSpace(hostname) == "" {
		return "silk-device"
	}
	return hostname
}

func printUsage() {
	fmt.Println(`silk-agent manages Silk device authentication for direct Agent bridges.

Usage:
  silk-agent connect <claude-code|codex> --server <https://host> --account <login-name>
  silk-agent run [--config-dir <path>]
  silk-agent start
  silk-agent status
  silk-agent stop <claude-code|codex|host>
  silk-agent logs [--follow]
  silk-agent service install|uninstall|status
  silk-agent identity backup|restore|rotate|migrate-keychain
  silk-agent release manifest|verify
  silk-agent version

The Host pairs with a device key and multiplexes managed Adapter ACP over one
device WSS. Insecure HTTP/WS requires the explicit --allow-insecure-http
development override.`)
}
