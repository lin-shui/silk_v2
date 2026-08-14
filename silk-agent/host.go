package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/signal"
	"sort"
	"sync"
	"syscall"
	"time"
)

// hostRuntime owns the device signer, authenticated Agent loops, and managed
// Adapter processes. Adapters receive a bound local handshake, never the
// private key or a Silk server credential.
type hostRuntime struct {
	configDir  string
	configPath string
	config     hostConfig
	signer     *deviceSigner
	ctx        context.Context
	cancel     context.CancelFunc
	listener   net.Listener

	mu               sync.Mutex
	loopCancels      map[string]context.CancelFunc
	loopIDs          map[string]uint64
	connected        map[string]bool
	adapters         map[string]adapterStatus
	adapterProcesses map[string]*adapterProcess
	adapterQueues    map[string]chan json.RawMessage
	connection       *multiplexedHostConnection
	terminalErr      error
	waitGroup        sync.WaitGroup
}

func runHost(configDir string, configPath string, config hostConfig, signer *deviceSigner) error {
	enabled := 0
	for _, agent := range config.Agents {
		if agent.Enabled {
			enabled++
		}
	}
	if enabled == 0 {
		return errors.New("no enabled Agent instances")
	}
	if existingPID, pidErr := readPID(pidFilePath(configDir)); pidErr == nil && existingPID != os.Getpid() {
		if processRunning(existingPID) {
			return fmt.Errorf("silk-agent is already running (pid %d)", existingPID)
		}
		_ = os.Remove(pidFilePath(configDir))
	} else if pidErr != nil && !os.IsNotExist(pidErr) {
		return pidErr
	}
	if err := os.MkdirAll(configDir, 0o700); err != nil {
		return fmt.Errorf("create host directory: %w", err)
	}
	listener, err := listenControl(controlSocketPath(configDir))
	if err != nil {
		return err
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	runtime := &hostRuntime{
		configDir:        configDir,
		configPath:       configPath,
		config:           config,
		signer:           signer,
		ctx:              ctx,
		cancel:           cancel,
		listener:         listener,
		loopCancels:      make(map[string]context.CancelFunc),
		loopIDs:          make(map[string]uint64),
		connected:        make(map[string]bool),
		adapters:         make(map[string]adapterStatus),
		adapterProcesses: make(map[string]*adapterProcess),
		adapterQueues:    make(map[string]chan json.RawMessage),
	}
	pid := os.Getpid()
	if err := writePID(pidFilePath(configDir), pid); err != nil {
		closeControl(listener, controlSocketPath(configDir))
		cancel()
		return err
	}
	defer func() {
		runtime.stopAll()
		closeControl(listener, controlSocketPath(configDir))
		removePIDIfOwned(pidFilePath(configDir), pid)
		cancel()
	}()

	for agentType, agent := range config.Agents {
		if agent.Enabled {
			if err := runtime.startAgent(agentType); err != nil {
				fmt.Fprintf(os.Stderr, "Agent %s could not start: %v\n", agentType, err)
			}
		}
	}
	runtime.waitGroup.Add(1)
	go runtime.runConnectionLoop()
	go runtime.acceptControl()
	<-ctx.Done()
	runtime.waitGroup.Wait()
	return runtime.terminalError()
}

func (runtime *hostRuntime) acceptControl() {
	for {
		connection, err := runtime.listener.Accept()
		if err != nil {
			if runtime.ctx.Err() != nil {
				return
			}
			continue
		}
		go serveControlConnection(connection, runtime.handleCommand)
	}
}

func (runtime *hostRuntime) handleCommand(command hostCommand) hostCommandResponse {
	switch command.Command {
	case "status":
		return hostCommandResponse{OK: true, Status: runtime.status()}
	case "start":
		if err := runtime.setAgentEnabled(command.AgentType, true); err != nil {
			return hostCommandResponse{Message: err.Error()}
		}
		return hostCommandResponse{OK: true, Message: "started"}
	case "stop":
		if err := runtime.setAgentEnabled(command.AgentType, false); err != nil {
			return hostCommandResponse{Message: err.Error()}
		}
		return hostCommandResponse{OK: true, Message: "stopped"}
	case "reload":
		if err := runtime.reloadAgent(command.AgentType); err != nil {
			return hostCommandResponse{Message: err.Error()}
		}
		return hostCommandResponse{OK: true, Message: "reloaded"}
	case "shutdown":
		runtime.cancel()
		return hostCommandResponse{OK: true, Message: "shutting down"}
	default:
		return hostCommandResponse{Message: "unknown host command"}
	}
}

func (runtime *hostRuntime) reloadAgent(agentType string) error {
	config, err := loadHostConfig(runtime.configPath)
	if err != nil {
		return err
	}
	agent, exists := config.Agents[agentType]
	if !exists {
		return fmt.Errorf("Agent %q is not configured", agentType)
	}
	runtime.mu.Lock()
	if config.ServerOrigin != runtime.config.ServerOrigin || config.DeviceID != runtime.config.DeviceID {
		runtime.mu.Unlock()
		return errors.New("reloaded config does not match the running Host identity")
	}
	currentAuthenticationOrigin := runtime.config.AuthenticationOrigin
	if currentAuthenticationOrigin == "" {
		currentAuthenticationOrigin = runtime.config.ServerOrigin
	}
	reloadedAuthenticationOrigin := config.AuthenticationOrigin
	if reloadedAuthenticationOrigin == "" {
		reloadedAuthenticationOrigin = config.ServerOrigin
	}
	if reloadedAuthenticationOrigin != currentAuthenticationOrigin {
		runtime.mu.Unlock()
		return errors.New("reloaded config does not match the running Host authentication origin")
	}
	runtime.config.Agents[agentType] = agent
	_, running := runtime.loopCancels[agentType]
	runtime.mu.Unlock()
	if agent.Enabled && !running {
		return runtime.startAgent(agentType)
	}
	if !agent.Enabled && running {
		return runtime.setAgentEnabled(agentType, false)
	}
	return nil
}

func (runtime *hostRuntime) status() *hostStatusReport {
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	status := &hostStatusReport{PID: os.Getpid(), Agents: make(map[string]agentRuntime, len(runtime.config.Agents))}
	for agentType, agent := range runtime.config.Agents {
		adapter := runtime.adapters[agentType]
		if adapter.State == "" {
			if agent.Enabled {
				adapter.State = "starting"
			} else {
				adapter.State = "stopped"
			}
		}
		status.Agents[agentType] = agentRuntime{
			AgentInstanceID: agent.AgentInstanceID,
			AgentType:       agent.AgentType,
			Enabled:         agent.Enabled,
			Connected:       runtime.connected[agentType],
			Adapter:         adapter,
		}
	}
	return status
}

func (runtime *hostRuntime) setAgentEnabled(agentType string, enabled bool) error {
	runtime.mu.Lock()
	agent, exists := runtime.config.Agents[agentType]
	if !exists {
		runtime.mu.Unlock()
		return fmt.Errorf("Agent %q is not configured", agentType)
	}
	previous := agent
	agent.Enabled = enabled
	runtime.config.Agents[agentType] = agent
	if err := saveHostConfig(runtime.configPath, runtime.config); err != nil {
		runtime.config.Agents[agentType] = previous
		runtime.mu.Unlock()
		return err
	}
	if enabled {
		if _, running := runtime.loopCancels[agentType]; !running {
			queue := make(chan json.RawMessage, 64)
			runtime.adapterQueues[agentType] = queue
			childContext, cancel := context.WithCancel(runtime.ctx)
			runtime.loopCancels[agentType] = cancel
			runtime.loopIDs[agentType]++
			loopID := runtime.loopIDs[agentType]
			runtime.waitGroup.Add(1)
			go runtime.runAgent(childContext, agentType, agent, loopID, queue)
		}
	} else if cancel, running := runtime.loopCancels[agentType]; running {
		cancel()
		delete(runtime.loopCancels, agentType)
		delete(runtime.adapterQueues, agentType)
		runtime.loopIDs[agentType]++
		runtime.connected[agentType] = false
		runtime.adapters[agentType] = adapterStatus{State: "stopping", RestartCount: runtime.adapters[agentType].RestartCount}
	}
	connection := runtime.connection
	runtime.mu.Unlock()
	if !enabled && connection != nil {
		_ = connection.closeAgent(agent.AgentInstanceID, "Agent stopped by user")
	}
	return nil
}

func (runtime *hostRuntime) startAgent(agentType string) error {
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	agent, exists := runtime.config.Agents[agentType]
	if !exists {
		return fmt.Errorf("Agent %q is not configured", agentType)
	}
	if _, running := runtime.loopCancels[agentType]; running {
		return nil
	}
	queue := make(chan json.RawMessage, 64)
	runtime.adapterQueues[agentType] = queue
	childContext, cancel := context.WithCancel(runtime.ctx)
	runtime.loopCancels[agentType] = cancel
	runtime.loopIDs[agentType]++
	loopID := runtime.loopIDs[agentType]
	runtime.waitGroup.Add(1)
	go runtime.runAgent(childContext, agentType, agent, loopID, queue)
	return nil
}

func (runtime *hostRuntime) runAgent(
	ctx context.Context,
	agentType string,
	agent agentConfig,
	loopID uint64,
	queue <-chan json.RawMessage,
) {
	defer runtime.waitGroup.Done()
	adapterDone := make(chan struct{})
	if spec, found, err := packagedAdapterSpec(agent.AgentType); err != nil {
		runtime.setAdapterStatus(agentType, loopID, adapterStatus{State: "failed", LastError: err.Error()})
		close(adapterDone)
	} else if !found {
		runtime.setAdapterStatus(agentType, loopID, adapterStatus{State: "not-installed"})
		close(adapterDone)
	} else {
		supervisor := &adapterSupervisor{
			agent: agent,
			spec:  spec,
			host: adapterHostContext{ForwardACP: func(payload json.RawMessage) error {
				return runtime.forwardACP(agentType, agent.AgentInstanceID, payload)
			}},
			log: os.Stderr,
			setStatus: func(status adapterStatus) {
				runtime.setAdapterStatus(agentType, loopID, status)
			},
			setProcess: func(process *adapterProcess) {
				runtime.setAdapterProcess(agentType, agent, loopID, process)
			},
		}
		go func() {
			defer close(adapterDone)
			supervisor.run(ctx)
		}()
	}
	defer func() {
		runtime.mu.Lock()
		if currentID, ok := runtime.loopIDs[agentType]; ok && currentID == loopID {
			delete(runtime.loopCancels, agentType)
			delete(runtime.adapterQueues, agentType)
			runtime.connected[agentType] = false
		}
		runtime.mu.Unlock()
	}()
	for {
		select {
		case <-ctx.Done():
			<-adapterDone
			return
		case <-adapterDone:
			return
		case payload := <-queue:
			if err := runtime.deliverACP(agentType, agent, payload); err != nil {
				fmt.Fprintf(os.Stderr, "Agent %s ACP stream closed: %v\n", agentType, err)
				runtime.closeLogicalAgent(agentType, agent.AgentInstanceID, "Adapter unavailable")
			}
		}
	}
}

func (runtime *hostRuntime) setAdapterProcess(
	agentType string,
	agent agentConfig,
	loopID uint64,
	process *adapterProcess,
) {
	runtime.mu.Lock()
	if runtime.loopIDs[agentType] != loopID {
		runtime.mu.Unlock()
		return
	}
	if process == nil {
		delete(runtime.adapterProcesses, agentType)
	} else {
		runtime.adapterProcesses[agentType] = process
	}
	connection := runtime.connection
	if process == nil {
		runtime.connected[agentType] = false
	}
	runtime.mu.Unlock()
	if process != nil && connection != nil {
		if err := connection.openAgent(agent); err != nil {
			fmt.Fprintf(os.Stderr, "Agent %s logical stream could not open: %v\n", agentType, err)
		}
	} else if process == nil && connection != nil {
		_ = connection.closeAgent(agent.AgentInstanceID, "managed Adapter stopped")
	}
}

func (runtime *hostRuntime) forwardACP(agentType string, agentInstanceID string, payload json.RawMessage) error {
	runtime.mu.Lock()
	agent, exists := runtime.config.Agents[agentType]
	connection := runtime.connection
	runtime.mu.Unlock()
	if !exists || !agent.Enabled || agent.AgentInstanceID != agentInstanceID {
		return errors.New("Adapter is not bound to an enabled Agent instance")
	}
	if connection == nil {
		return errors.New("Host WebSocket is disconnected")
	}
	return connection.sendAgentRPC(agentInstanceID, payload)
}

func (runtime *hostRuntime) deliverACP(agentType string, agent agentConfig, payload json.RawMessage) error {
	runtime.mu.Lock()
	process := runtime.adapterProcesses[agentType]
	runtime.mu.Unlock()
	if process == nil {
		return errors.New("managed Adapter is not running")
	}
	ctx, cancel := context.WithTimeout(runtime.ctx, adapterHealthTimeout)
	defer cancel()
	return process.call(ctx, "adapter/acp", adapterACPParams{Message: payload}, nil)
}

func (runtime *hostRuntime) closeLogicalAgent(agentType string, agentInstanceID string, reason string) {
	runtime.mu.Lock()
	runtime.connected[agentType] = false
	connection := runtime.connection
	runtime.mu.Unlock()
	if connection != nil {
		_ = connection.closeAgent(agentInstanceID, reason)
	}
}

func (runtime *hostRuntime) runConnectionLoop() {
	defer runtime.waitGroup.Done()
	backoff := time.Second
	authenticationIndex := 0
	terminallyRejected := make(map[string]struct{})
	for runtime.ctx.Err() == nil {
		enabledAgents := runtime.enabledAgents()
		agents := make([]agentConfig, 0, len(enabledAgents))
		for _, agent := range enabledAgents {
			if _, rejected := terminallyRejected[agent.AgentInstanceID]; !rejected {
				agents = append(agents, agent)
			}
		}
		if len(agents) == 0 {
			if len(enabledAgents) > 0 {
				runtime.setTerminalError(errors.New(
					"every enabled Agent identity was revoked by the server; revoke the old local identity and pair again",
				))
				runtime.cancel()
				return
			}
			select {
			case <-runtime.ctx.Done():
				return
			case <-time.After(time.Second):
			}
			continue
		}
		authenticationAgent := agents[authenticationIndex%len(agents)]
		authenticationIndex++
		config := runtime.currentConfig()
		connection, err := dialMultiplexedHost(runtime.ctx, config, authenticationAgent, runtime.signer)
		if err == nil {
			backoff = time.Second
			delete(terminallyRejected, authenticationAgent.AgentInstanceID)
			runtime.installConnection(connection)
			fmt.Printf("Silk Host authenticated device %s with one multiplexed WSS\n", config.DeviceID)
			err = connection.serve(runtime.ctx, runtime.handleHostEnvelope)
			runtime.removeConnection(connection)
			connection.close()
		}
		if runtime.ctx.Err() != nil {
			return
		}
		if isTerminalAgentConnectionError(err) {
			terminallyRejected[authenticationAgent.AgentInstanceID] = struct{}{}
			fmt.Fprintf(
				os.Stderr,
				"Silk Host authentication rejected Agent %s (%s) as revoked; it will not be retried in this Host run\n",
				authenticationAgent.AgentType,
				authenticationAgent.AgentInstanceID,
			)
			backoff = time.Second
			continue
		}
		fmt.Fprintf(os.Stderr, "Silk Host WebSocket disconnected: %v; reconnecting in %s\n", err, backoff)
		select {
		case <-runtime.ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 30*time.Second {
			backoff *= 2
			if backoff > 30*time.Second {
				backoff = 30 * time.Second
			}
		}
	}
}

func (runtime *hostRuntime) setTerminalError(err error) {
	runtime.mu.Lock()
	if runtime.terminalErr == nil {
		runtime.terminalErr = err
	}
	runtime.mu.Unlock()
}

func (runtime *hostRuntime) terminalError() error {
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	return runtime.terminalErr
}

func (runtime *hostRuntime) enabledAgents() []agentConfig {
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	types := make([]string, 0, len(runtime.config.Agents))
	for agentType, agent := range runtime.config.Agents {
		if agent.Enabled {
			types = append(types, agentType)
		}
	}
	sort.Strings(types)
	agents := make([]agentConfig, 0, len(types))
	for _, agentType := range types {
		agents = append(agents, runtime.config.Agents[agentType])
	}
	return agents
}

func (runtime *hostRuntime) installConnection(connection *multiplexedHostConnection) {
	runtime.mu.Lock()
	runtime.connection = connection
	agents := make([]agentConfig, 0, len(runtime.adapterProcesses))
	for agentType := range runtime.adapterProcesses {
		agent := runtime.config.Agents[agentType]
		if agent.Enabled {
			agents = append(agents, agent)
		}
	}
	runtime.mu.Unlock()
	for _, agent := range agents {
		if err := connection.openAgent(agent); err != nil {
			fmt.Fprintf(os.Stderr, "Agent %s logical stream could not open: %v\n", agent.AgentType, err)
		}
	}
}

func (runtime *hostRuntime) removeConnection(connection *multiplexedHostConnection) {
	runtime.mu.Lock()
	if runtime.connection == connection {
		runtime.connection = nil
		for agentType := range runtime.connected {
			runtime.connected[agentType] = false
		}
	}
	runtime.mu.Unlock()
}

func (runtime *hostRuntime) handleHostEnvelope(envelope socketEnvelope, contents []byte) {
	switch envelope.Type {
	case "heartbeat_ack":
		return
	case "agent_opened":
		var opened hostAgentOpened
		if json.Unmarshal(contents, &opened) != nil || opened.ProtocolVersion != protocolVersion {
			return
		}
		if agentType, _, ok := runtime.agentByInstanceID(opened.AgentInstanceID); ok {
			runtime.mu.Lock()
			runtime.connected[agentType] = true
			runtime.mu.Unlock()
			fmt.Printf("Agent %s logical stream opened (instance %s)\n", agentType, opened.AgentInstanceID)
		}
	case "agent_rpc":
		var message hostAgentRPC
		if json.Unmarshal(contents, &message) != nil || message.ProtocolVersion != protocolVersion {
			return
		}
		agentType, _, ok := runtime.agentByInstanceID(message.AgentInstanceID)
		if !ok {
			return
		}
		runtime.mu.Lock()
		queue := runtime.adapterQueues[agentType]
		connection := runtime.connection
		runtime.mu.Unlock()
		select {
		case queue <- append(json.RawMessage(nil), message.Payload...):
		default:
			if connection != nil {
				_ = connection.closeAgent(message.AgentInstanceID, "Adapter ACP queue is full")
			}
		}
	case "agent_close":
		agentType, _, ok := runtime.agentByInstanceID(envelope.AgentInstanceID)
		if ok {
			runtime.mu.Lock()
			runtime.connected[agentType] = false
			runtime.mu.Unlock()
		}
	case "error":
		if agentType, _, ok := runtime.agentByInstanceID(envelope.AgentInstanceID); ok {
			runtime.mu.Lock()
			runtime.connected[agentType] = false
			runtime.mu.Unlock()
		}
		fmt.Fprintf(os.Stderr, "Silk Host envelope rejected (%s): %s\n", envelope.Error, envelope.Message)
	}
}

func (runtime *hostRuntime) agentByInstanceID(agentInstanceID string) (string, agentConfig, bool) {
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	for agentType, agent := range runtime.config.Agents {
		if agent.AgentInstanceID == agentInstanceID && agent.Enabled {
			return agentType, agent, true
		}
	}
	return "", agentConfig{}, false
}

func (runtime *hostRuntime) setAdapterStatus(agentType string, loopID uint64, status adapterStatus) {
	runtime.mu.Lock()
	if runtime.loopIDs[agentType] == loopID {
		runtime.adapters[agentType] = status
	}
	runtime.mu.Unlock()
}

func (runtime *hostRuntime) currentConfig() hostConfig {
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	return runtime.config
}

func (runtime *hostRuntime) stopAll() {
	runtime.mu.Lock()
	for _, cancel := range runtime.loopCancels {
		cancel()
	}
	runtime.loopCancels = make(map[string]context.CancelFunc)
	runtime.mu.Unlock()
	runtime.waitGroup.Wait()
}
