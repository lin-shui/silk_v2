# silk-agent

`silk-agent` is the standalone Go companion Host for Silk external Agents. The
current slice implements device authentication, Host lifecycle management, one
device-level multiplexed WSS, and direct Claude/Codex Adapter supervision. The legacy
`cc-connect` transport is not managed by this Host yet.

## Current commands

```text
silk-agent connect <claude-code|codex> --server http(s)://host:port --account <login-name> [--web http(s)://web-host]
silk-agent run [--config-dir <path>]
silk-agent start
silk-agent status
silk-agent stop <claude-code|codex|host>
silk-agent logs [--follow]
silk-agent service install|uninstall|status
silk-agent identity backup|restore|rotate|migrate-keychain
silk-agent release manifest|verify
```

For the first Agent, `--account` is required and binds the pairing request to
that exact Silk login name before a code is issued. A different logged-in
account receives the same not-found response as an invalid code and cannot
claim the device. `connect` creates a one-time device pairing request, prints
a browser URL whose fragment pre-fills the short code, waits for the user to
review the request and click **Approve connection**, proves the local Ed25519 private
key, and persists the returned device/Agent IDs. On an enrolled device, the same
command signs a one-time add-Agent request; the device owner still approves it
in the Web UI. If the Host is already running, the approved Agent is hot-loaded
through the protected control socket; otherwise `connect` runs the foreground
Host with its managed Adapter.
`--server` is the backend connection origin and is sent as the safe development
fallback for the canonical authentication origin. `--web` is needed only when
the browser UI uses a different origin and the backend does not have
`BACKEND_WEB_APP_BASE_URL`; ports are never guessed.
The local registry supports one active instance of each Agent type per device,
so Claude Code and Codex can coexist, but two instances of the same type on a
single device cannot. The server rejects that duplicate enrollment with
`AGENT_ALREADY_ENROLLED`. The current backend runtime also keeps one active
connection per account and Agent type; connecting the same type from another
device replaces the older runtime connection until explicit device routing is
added.
`run` is the foreground Host mode; `--config-dir` pins an explicit absolute profile
directory for service managers and advanced deployments. `start` launches it in the background and redirects
diagnostics to the Host log. `stop <agent>` updates the local Agent enablement
and, when the Host is running, stops that loop immediately. `stop host` shuts
down the background Host. `logs --follow` prints the existing log once and then
only bytes appended after that point; log truncation or replacement restarts at
the beginning of the new file.

On Unix, Host control uses a 0600 Unix-domain socket; Windows uses a named pipe
whose DACL grants only the current user. The Host never puts the
private key, pairing poll secret, or a long-lived bearer token in a URL,
environment variable, or log. Device keys prefer macOS Keychain, Windows
DPAPI, or Linux Secret Service. On Unix, the secure-file fallback is
`device_key` mode 0600 in a mode 0700 configuration directory. On Windows,
the Host replaces inherited configuration-directory permissions with a
protected DACL limited to the current user, SYSTEM, and Administrators. If an
elevated process created the profile with Administrators as owner, the Host
first transfers ownership to the current user; directories owned by another
ordinary account remain rejected. Fallback secret files use the corresponding
DACL validation. `identity backup` writes a new
passphrase-encrypted backup, `restore` refuses to overwrite an existing
identity, `rotate` requires confirmation that the old server-side device was
revoked, and `migrate-keychain` moves an existing secure-file key into an
available platform store.
On Windows, passphrase and release-private-key inputs are accepted only when
the current user owns the file and allow entries are limited to that user,
SYSTEM, Administrators, or Windows owner placeholders; broader or unreadable
DACLs fail closed.
`config.json` keeps the connection origin separate from the backend-provided
canonical authentication origin so reverse-proxy aliases do not change signed
payloads.

The Host authenticates one `/agent-connect` connection for the device and opens
one logical stream per enabled Agent. Every `agent_open`, `agent_rpc`, and
`agent_close` envelope carries the fixed `agentInstanceId`. Each stream has its
own bounded Host and Adapter queues; stopping or revoking one Agent closes only
that stream, while device revocation closes the physical WSS. Authentication
rejection for a revoked identity is terminal for that identity: a Host with no
remaining enabled identity exits with an actionable error instead of retrying
forever; a multi-Agent Host can continue by authenticating with a surviving
identity.

`service install` installs a current-user service: a systemd user unit on Linux,
a LaunchAgent on macOS, or a Windows Scheduled Task
on Windows. It requires an enrolled device and starts `silk-agent run` with the
resolved configuration directory pinned explicitly, so a custom
`SILK_AGENT_HOME` survives a later login. It never requires root or stores
credentials in the service definition. `service status` and `service uninstall`
use the same per-user manager. Service managers do not inherit an interactive
shell's provider credentials, custom API endpoints, or CLI-specific environment.
Use foreground `run` or background `start` when those values exist only in the
current shell; use service mode only after configuring the required environment
through the operating system's protected service mechanism.

## Managed Adapter contract

When a packaged Adapter is present beside the Host binary at
`adapters/silk-<agent-type>-adapter` (or directly beside the binary), the Host
starts it with `--silk-host-stdio`. The Adapter speaks Host IPC v2 as
line-delimited JSON-RPC 2.0 over stdin/stdout:

- Host sends `host/initialize` with a fresh one-time nonce, the fixed
  `agentInstanceId`, Agent type, and capabilities.
- The Adapter must echo the protocol version, nonce, and exact instance ID;
  otherwise the process is rejected.
- Host forwards backend ACP objects with `adapter/acp`; the Adapter returns ACP
  responses and notifications with `host/forwardAcp`. The Host supplies the
  bound `agentInstanceId` in the network envelope, so the child cannot select
  another logical stream.
- Host periodically calls `adapter/health`; shutdown uses `adapter/shutdown`.
- Adapter stderr is copied to the Host log with an Agent prefix. Adapter
  stdout is reserved for JSON-RPC. The child receives neither a device signing
  API nor a backend credential and never opens a Silk WebSocket in Host mode.

Host IPC v1 Adapters are intentionally rejected with an upgrade message. When
upgrading an existing installation to `silk-agent` 0.3+, replace the Host and
both packaged Adapter wrappers as one bundle. Older Hosts can continue using
the compatibility device-signature `/agent-bridge` path during migration, but
query Token authentication and standalone Adapter startup are retired.

Managed Claude Code and Codex registrations advertise `EXECUTION_POLICY_V1` and
`EXECUTION_POLICY_V2`. The backend requires `EXECUTION_POLICY_V2` for new
device-signed Workspace prompts so an older Agent cannot silently ignore the
two-layer per-prompt execution policy. Agents enrolled before V2 was introduced
are upgraded automatically when `silk-agent` 0.4.12 or newer reconnects: the Host
refreshes its local capability snapshot and sends a device-signed `agent_open`
metadata envelope. The backend preserves the existing `agentInstanceId`,
Device identity, Workspace Binding, approvals, and sessions, and only
auto-adds the safe `EXECUTION_POLICY_V2` capability. No Silk Web unbind/rebind
action is required. An old Host that does not send the refresh envelope
remains fail-closed until it is upgraded.

The child receives a filtered environment: `SILK_AGENT_HOME`, Silk/Bridge token
variables, and reserved `SILK_AGENT_SECRET_*` variables are removed. The
device user's ordinary runtime variables (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`,
provider variables, and API credentials) remain available so the vendor CLI
uses the same native authentication and configuration as a direct terminal
session. Claude/Codex user config, project instructions, hooks, MCP servers,
plugins, and rules are loaded by the vendor CLI; Silk only adds the
Binding-derived core file/command limits described above. The private key is
never included in the handshake. Raw Claude CLI I/O logging is
disabled in Host mode, and Python bytecode writes are suppressed so the signed
bundle is not modified at runtime. Before launch, Unix verifies the Adapter
wrapper, every imported packaged Python module, and their parent path ownership
and write permissions. Windows applies owner/DACL checks to the entry script,
all packaged Python modules, and the directory that could replace them; public
read access is allowed, but write access by an untrusted principal is rejected.
This is an OS-user trust boundary, not a defense against a fully compromised
local user account.

If the packaged Adapter is absent, Host status reports `not-installed`. The
repository wrappers in `adapters/` launch `cc_bridge` / `codex_bridge` with
`--silk-host-stdio`; release bundles include the Adapter sources and wrappers,
while Python, Python requirements, and the selected vendor CLI remain host
prerequisites. On Windows, managed Adapter processes run without a visible
console window. Rapid crashes are retried
with bounded backoff and transition to `failed` after five consecutive quick
failures; an Agent failure does not terminate other Agent processes.

## Releases

`scripts/package-release.sh` builds Linux, macOS, and Windows bundles for
amd64/arm64. It embeds the trusted raw Ed25519 release public key, writes a
SHA-256 manifest, signs the exact manifest, and verifies that the release
directory has no unsigned files or directories. Production binaries do not
accept a runtime public-key override. The GitHub release workflow obtains the
private/public signing material from repository secrets and publishes only the
verified artifacts. Archive, manifest, and signature modes are normalized to
0644; bundle directories are 0755, executables are 0755, and source files are
0644. Group/other-writable release output is rejected.

## Development

```bash
go test ./...
go test -race ./...
go vet ./...
GOOS=windows GOARCH=amd64 go test -c -o silk-agent-windows.test.exe
go build -o silk-agent .
```

From the repository root, verify the shared Bridge IPC helpers with
`python3 -m unittest discover -s bridge_common/tests`; wrapper syntax can be
checked with `sh -n silk-agent/adapters/silk-claude-code-adapter silk-agent/adapters/silk-codex-adapter`.

The Go module targets Go 1.22 or newer. The server-side protocol contract is
defined in `backend/.../agents/auth/AgentAuthProtocol.kt`; keep canonical field
order, raw Ed25519 base64url encoding, and origin normalization in sync when
changing either side.
