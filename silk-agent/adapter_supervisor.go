package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	adapterHandshakeTimeout = 5 * time.Second
	adapterHealthInterval   = 20 * time.Second
	adapterHealthTimeout    = 5 * time.Second
	adapterStopTimeout      = 2 * time.Second
	adapterStableWindow     = time.Minute
	adapterMaxRapidRestarts = 5
	adapterMaxMessageBytes  = 1024 * 1024
)

type adapterProcessSpec struct {
	Path       string
	Args       []string
	WorkingDir string
}

type adapterStatus struct {
	State        string `json:"state"`
	PID          int    `json:"pid,omitempty"`
	RestartCount int    `json:"restartCount"`
	Version      string `json:"version,omitempty"`
	LastError    string `json:"lastError,omitempty"`
}

type adapterSupervisor struct {
	agent      agentConfig
	spec       adapterProcessSpec
	host       adapterHostContext
	log        io.Writer
	setStatus  func(adapterStatus)
	setProcess func(*adapterProcess)
}

type adapterHostContext struct {
	ForwardACP func(json.RawMessage) error
}

type adapterRequestHandler func(adapterRPCMessage) (any, error)

type adapterProcess struct {
	command *exec.Cmd
	stdin   io.WriteCloser
	log     io.Writer
	agent   agentConfig
	handler adapterRequestHandler

	writeMu sync.Mutex
	mu      sync.Mutex
	pending map[string]chan adapterRPCMessage
	waitErr error
	done    chan struct{}
	nextID  atomic.Uint64
}

func packagedAdapterSpec(agentType string) (adapterProcessSpec, bool, error) {
	if _, supported := supportedAgents[agentType]; !supported {
		return adapterProcessSpec{}, false, fmt.Errorf("managed Adapter type %q is not supported", agentType)
	}
	executable, err := os.Executable()
	if err != nil {
		return adapterProcessSpec{}, false, fmt.Errorf("resolve silk-agent executable: %w", err)
	}
	directory := filepath.Dir(executable)
	return platformPackagedAdapterSpec(directory, agentType)
}

func validatePackagedAdapterSources(root string, agentType string) error {
	agentDirectory := map[string]string{
		"claude-code": "cc_bridge",
		"codex":       "codex_bridge",
	}[agentType]
	if agentDirectory == "" {
		return fmt.Errorf("managed Adapter type %q is not supported", agentType)
	}
	for _, relativeDirectory := range []string{"bridge_common", agentDirectory} {
		directory := filepath.Join(root, relativeDirectory)
		sourceCount := 0
		err := filepath.WalkDir(directory, func(path string, entry os.DirEntry, walkErr error) error {
			if walkErr != nil {
				return walkErr
			}
			if entry.Type()&os.ModeSymlink != 0 {
				return fmt.Errorf("managed Adapter source path %s must not be a symbolic link", path)
			}
			if entry.IsDir() || filepath.Ext(entry.Name()) != ".py" {
				return nil
			}
			info, err := entry.Info()
			if err != nil || !info.Mode().IsRegular() {
				return fmt.Errorf("managed Adapter source %s is not a trusted regular file", path)
			}
			if err := platformValidateAdapterSource(path, info); err != nil {
				return fmt.Errorf("managed Adapter source %s: %w", path, err)
			}
			sourceCount++
			return nil
		})
		if err != nil {
			return err
		}
		if sourceCount == 0 {
			return fmt.Errorf("managed Adapter source directory %s contains no Python modules", directory)
		}
	}
	return nil
}

func validatePackagedAdapterSourceTree(binaryDirectory string, agentType string) error {
	agentDirectory := map[string]string{
		"claude-code": "cc_bridge",
		"codex":       "codex_bridge",
	}[agentType]
	for _, root := range []string{binaryDirectory, filepath.Clean(filepath.Join(binaryDirectory, ".."))} {
		commonInfo, commonErr := os.Stat(filepath.Join(root, "bridge_common"))
		agentInfo, agentErr := os.Stat(filepath.Join(root, agentDirectory))
		if commonErr != nil || agentErr != nil || !commonInfo.IsDir() || !agentInfo.IsDir() {
			continue
		}
		return validatePackagedAdapterSources(root, agentType)
	}
	return fmt.Errorf("managed Adapter Python source tree for %s is missing", agentType)
}

func (supervisor *adapterSupervisor) run(ctx context.Context) {
	restarts := 0
	rapidRestarts := 0
	backoff := time.Second
	for {
		if ctx.Err() != nil {
			supervisor.update(adapterStatus{State: "stopped", RestartCount: restarts})
			return
		}
		state := "starting"
		if restarts > 0 {
			state = "restarting"
		}
		supervisor.update(adapterStatus{State: state, RestartCount: restarts})
		startedAt := time.Now()
		process, result, err := startAdapterProcess(
			ctx,
			supervisor.spec,
			supervisor.agent,
			supervisor.host,
			supervisor.log,
			adapterHostRequestHandler(supervisor.agent, supervisor.host),
		)
		if err == nil {
			if supervisor.setProcess != nil {
				supervisor.setProcess(process)
			}
			supervisor.update(adapterStatus{
				State:        "healthy",
				PID:          process.command.Process.Pid,
				RestartCount: restarts,
				Version:      result.AdapterVersion,
			})
			err = supervisor.monitor(ctx, process)
		}
		if ctx.Err() != nil {
			if process != nil {
				if supervisor.setProcess != nil {
					supervisor.setProcess(nil)
				}
				process.stop()
			}
			supervisor.update(adapterStatus{State: "stopped", RestartCount: restarts})
			return
		}
		if process != nil {
			if supervisor.setProcess != nil {
				supervisor.setProcess(nil)
			}
			process.stop()
		}
		restarts++
		if time.Since(startedAt) >= adapterStableWindow {
			rapidRestarts = 0
			backoff = time.Second
		} else {
			rapidRestarts++
		}
		lastError := "Adapter stopped unexpectedly"
		if err != nil {
			lastError = err.Error()
		}
		if rapidRestarts >= adapterMaxRapidRestarts {
			supervisor.update(adapterStatus{
				State:        "failed",
				RestartCount: restarts,
				LastError:    lastError,
			})
			return
		}
		supervisor.update(adapterStatus{
			State:        "restarting",
			RestartCount: restarts,
			LastError:    lastError,
		})
		select {
		case <-ctx.Done():
			continue
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

func (supervisor *adapterSupervisor) monitor(ctx context.Context, process *adapterProcess) error {
	ticker := time.NewTicker(adapterHealthInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-process.done:
			return process.err()
		case <-ticker.C:
			healthContext, cancel := context.WithTimeout(ctx, adapterHealthTimeout)
			var health adapterHealthResult
			err := process.call(healthContext, "adapter/health", struct{}{}, &health)
			cancel()
			if err != nil {
				return fmt.Errorf("Adapter health check: %w", err)
			}
			if health.Status != "ok" {
				return fmt.Errorf("Adapter health check returned %q", health.Status)
			}
		}
	}
}

func (supervisor *adapterSupervisor) update(status adapterStatus) {
	if supervisor.setStatus != nil {
		supervisor.setStatus(status)
	}
}

func startAdapterProcess(
	ctx context.Context,
	spec adapterProcessSpec,
	agent agentConfig,
	host adapterHostContext,
	log io.Writer,
	requestHandler adapterRequestHandler,
) (*adapterProcess, adapterInitializeResult, error) {
	if strings.TrimSpace(spec.Path) == "" {
		return nil, adapterInitializeResult{}, errors.New("managed Adapter path is empty")
	}
	if err := validateAdapterExecutable(spec.Path); err != nil {
		return nil, adapterInitializeResult{}, err
	}
	command := exec.Command(spec.Path, spec.Args...)
	command.Dir = spec.WorkingDir
	command.Env = adapterEnvironment(os.Environ())
	prepareAdapterCommand(command)
	stdin, err := command.StdinPipe()
	if err != nil {
		return nil, adapterInitializeResult{}, fmt.Errorf("open Adapter stdin: %w", err)
	}
	stdout, err := command.StdoutPipe()
	if err != nil {
		_ = stdin.Close()
		return nil, adapterInitializeResult{}, fmt.Errorf("open Adapter stdout: %w", err)
	}
	stderr, err := command.StderrPipe()
	if err != nil {
		_ = stdin.Close()
		return nil, adapterInitializeResult{}, fmt.Errorf("open Adapter stderr: %w", err)
	}
	if err := command.Start(); err != nil {
		_ = stdin.Close()
		return nil, adapterInitializeResult{}, fmt.Errorf("start managed Adapter: %w", err)
	}
	process := &adapterProcess{
		command: command,
		stdin:   stdin,
		log:     log,
		agent:   agent,
		handler: requestHandler,
		pending: make(map[string]chan adapterRPCMessage),
		done:    make(chan struct{}),
	}
	go process.readStdout(stdout)
	go process.copyStderr(stderr)
	go func() {
		err := command.Wait()
		process.mu.Lock()
		process.waitErr = err
		for id, response := range process.pending {
			delete(process.pending, id)
			close(response)
		}
		process.mu.Unlock()
		close(process.done)
	}()

	nonceBytes := make([]byte, 32)
	if _, err := rand.Read(nonceBytes); err != nil {
		process.stop()
		return nil, adapterInitializeResult{}, fmt.Errorf("generate Adapter handshake: %w", err)
	}
	initialize := adapterInitializeParams{
		ProtocolVersion:   adapterProtocolVersion,
		Nonce:             base64.RawURLEncoding.EncodeToString(nonceBytes),
		AgentInstanceID:   agent.AgentInstanceID,
		AgentType:         agent.AgentType,
		HostVersion:       hostVersion,
		AgentCapabilities: append([]string(nil), agent.Capabilities...),
	}
	handshakeContext, cancel := context.WithTimeout(ctx, adapterHandshakeTimeout)
	defer cancel()
	var result adapterInitializeResult
	if err := process.call(handshakeContext, "host/initialize", initialize, &result); err != nil {
		process.stop()
		return nil, adapterInitializeResult{}, fmt.Errorf(
			"initialize managed Adapter; install the Host IPC v2 Adapter bundle: %w",
			err,
		)
	}
	if result.ProtocolVersion != adapterProtocolVersion || result.Nonce != initialize.Nonce || result.AgentInstanceID != agent.AgentInstanceID {
		process.stop()
		return nil, adapterInitializeResult{}, errors.New("managed Adapter returned an invalid bound handshake; install an Adapter that supports Host IPC v2")
	}
	return process, result, nil
}

func validateAdapterExecutable(path string) error {
	info, err := os.Lstat(path)
	if err != nil {
		return fmt.Errorf("inspect managed Adapter executable: %w", err)
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return errors.New("managed Adapter executable must not be a symbolic link")
	}
	if !info.Mode().IsRegular() || !adapterFileExecutable(info) {
		return errors.New("managed Adapter executable is not trusted or executable")
	}
	return platformValidateAdapterExecutable(path, info)
}

func (process *adapterProcess) call(ctx context.Context, method string, params any, target any) error {
	paramsJSON, err := json.Marshal(params)
	if err != nil {
		return fmt.Errorf("encode Adapter request params: %w", err)
	}
	id := fmt.Sprintf("host-%d", process.nextID.Add(1))
	response := make(chan adapterRPCMessage, 1)
	process.mu.Lock()
	select {
	case <-process.done:
		process.mu.Unlock()
		return process.err()
	default:
	}
	process.pending[id] = response
	process.mu.Unlock()

	request := adapterRPCMessage{JSONRPC: "2.0", ID: id, Method: method, Params: paramsJSON}
	err = process.writeMessage(request)
	if err != nil {
		process.removePending(id)
		return fmt.Errorf("write Adapter request: %w", err)
	}

	select {
	case <-ctx.Done():
		process.removePending(id)
		return ctx.Err()
	case <-process.done:
		process.removePending(id)
		return process.err()
	case message, ok := <-response:
		if !ok {
			return process.err()
		}
		if message.Error != nil {
			return fmt.Errorf("Adapter RPC %s failed (%d): %s", method, message.Error.Code, message.Error.Message)
		}
		if target != nil && len(message.Result) > 0 {
			if err := json.Unmarshal(message.Result, target); err != nil {
				return fmt.Errorf("decode Adapter RPC %s result: %w", method, err)
			}
		}
		return nil
	}
}

func (process *adapterProcess) readStdout(stdout io.Reader) {
	scanner := bufio.NewScanner(stdout)
	scanner.Buffer(make([]byte, 64*1024), adapterMaxMessageBytes)
	for scanner.Scan() {
		var message adapterRPCMessage
		if err := json.Unmarshal(scanner.Bytes(), &message); err != nil || message.JSONRPC != "2.0" {
			process.logLine("invalid JSON-RPC output from managed Adapter")
			continue
		}
		if message.Method != "" {
			if message.ID == "" {
				process.logLine("unsupported Adapter JSON-RPC notification")
				continue
			}
			go process.handleAdapterRequest(message)
			continue
		}
		if message.ID == "" {
			process.logLine("invalid Adapter JSON-RPC response")
			continue
		}
		process.mu.Lock()
		response, exists := process.pending[message.ID]
		if exists {
			delete(process.pending, message.ID)
		}
		process.mu.Unlock()
		if exists {
			response <- message
		}
	}
	if err := scanner.Err(); err != nil {
		process.logLine("read managed Adapter output: " + err.Error())
	}
}

func (process *adapterProcess) handleAdapterRequest(request adapterRPCMessage) {
	response := adapterRPCMessage{JSONRPC: "2.0", ID: request.ID}
	if process.handler == nil {
		response.Error = &adapterRPCError{Code: -32601, Message: "Adapter request method is not supported"}
	} else {
		result, err := process.handler(request)
		if err != nil {
			response.Error = &adapterRPCError{Code: -32000, Message: err.Error()}
		} else {
			encoded, encodeErr := json.Marshal(result)
			if encodeErr != nil {
				response.Error = &adapterRPCError{Code: -32603, Message: "Could not encode Host response"}
			} else {
				response.Result = encoded
			}
		}
	}
	if err := process.writeMessage(response); err != nil {
		process.logLine("write managed Adapter response: " + err.Error())
	}
}

func (process *adapterProcess) writeMessage(message adapterRPCMessage) error {
	process.writeMu.Lock()
	defer process.writeMu.Unlock()
	return json.NewEncoder(process.stdin).Encode(message)
}

func adapterHostRequestHandler(agent agentConfig, host adapterHostContext) adapterRequestHandler {
	return func(request adapterRPCMessage) (any, error) {
		if request.Method != "host/forwardAcp" {
			return nil, fmt.Errorf("Host method %q is not supported", request.Method)
		}
		if host.ForwardACP == nil {
			return nil, errors.New("Host Agent connection is unavailable")
		}
		var params adapterACPParams
		if err := json.Unmarshal(request.Params, &params); err != nil {
			return nil, errors.New("invalid ACP forwarding request")
		}
		if len(params.Message) == 0 || len(params.Message) > adapterMaxMessageBytes {
			return nil, errors.New("ACP forwarding payload is invalid")
		}
		var object map[string]json.RawMessage
		if err := json.Unmarshal(params.Message, &object); err != nil || object == nil {
			return nil, errors.New("ACP forwarding payload must be a JSON object")
		}
		return struct{}{}, host.ForwardACP(append(json.RawMessage(nil), params.Message...))
	}
}

func (process *adapterProcess) copyStderr(stderr io.Reader) {
	scanner := bufio.NewScanner(stderr)
	scanner.Buffer(make([]byte, 64*1024), adapterMaxMessageBytes)
	for scanner.Scan() {
		process.logLine(scanner.Text())
	}
}

func (process *adapterProcess) logLine(line string) {
	if process.log == nil {
		return
	}
	line = strings.ReplaceAll(line, "\r", "")
	fmt.Fprintf(process.log, "Adapter[%s] %s\n", process.agent.AgentType, line)
}

func (process *adapterProcess) removePending(id string) {
	process.mu.Lock()
	delete(process.pending, id)
	process.mu.Unlock()
}

func (process *adapterProcess) err() error {
	process.mu.Lock()
	defer process.mu.Unlock()
	if process.waitErr == nil {
		return errors.New("managed Adapter exited")
	}
	return process.waitErr
}

func (process *adapterProcess) stop() {
	select {
	case <-process.done:
		return
	default:
	}
	shutdownContext, cancel := context.WithTimeout(context.Background(), adapterStopTimeout/2)
	_ = process.call(shutdownContext, "adapter/shutdown", struct{}{}, nil)
	cancel()
	select {
	case <-process.done:
		return
	default:
	}
	requestAdapterStop(process.command)
	select {
	case <-process.done:
		return
	case <-time.After(adapterStopTimeout):
		killAdapterProcess(process.command)
		<-process.done
	}
}

func adapterEnvironment(environment []string) []string {
	unsafeLoaderVariables := map[string]struct{}{
		"DYLD_INSERT_LIBRARIES":   {},
		"DYLD_LIBRARY_PATH":       {},
		"LD_PRELOAD":              {},
		"PYTHONHOME":              {},
		"PYTHONPATH":              {},
		"PYTHONPYCACHEPREFIX":     {},
		"PYTHONDONTWRITEBYTECODE": {},
	}
	filtered := make([]string, 0, len(environment)+1)
	for _, entry := range environment {
		name, _, found := strings.Cut(entry, "=")
		if !found {
			continue
		}
		upper := strings.ToUpper(name)
		_, unsafeLoaderVariable := unsafeLoaderVariables[upper]
		if upper == "SILK_AGENT_HOME" || upper == "SILK_TOKEN" || upper == "BRIDGE_TOKEN" || upper == "BRIDGE_SERVER" ||
			upper == "BRIDGE_CLI_RAW_LOG" || upper == "BRIDGE_CLI_RAW_LOG_DIR" || unsafeLoaderVariable ||
			strings.HasPrefix(upper, "SILK_AGENT_SECRET_") {
			continue
		}
		filtered = append(filtered, entry)
	}
	// Packaged Python Adapters are part of the signed/trusted bundle. Keep that
	// tree immutable at runtime instead of creating writable __pycache__ files
	// next to the reviewed sources.
	filtered = append(filtered, "PYTHONDONTWRITEBYTECODE=1")
	return filtered
}
