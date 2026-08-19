# silk-agent

`silk-agent` is the standalone Go companion Host for Silk external Agents. The
current slice implements device authentication, Host lifecycle management, one
device-level multiplexed WSS, and direct Claude/Codex Adapter supervision. The legacy
`cc-connect` transport is not managed by this Host yet.

Most users should download a prebuilt bundle from the
[GitHub Releases page](https://github.com/shenman9/silk_v2/releases) instead of
building from source. A bundle contains the Host binary, both Adapter wrappers,
and the Python modules required by the managed Adapters; keep the whole extracted
directory together.

## Download and use a prebuilt bundle

The following is a Linux x86_64 example. Choose the matching `linux_arm64`,
`darwin_amd64`, `darwin_arm64`, `windows_amd64`, or `windows_arm64` archive for
the target device. Replace `0.4.12` with the release version you want to use.

```bash
VERSION=0.4.12
ARCHIVE=silk-agent_${VERSION}_linux_amd64.tar.gz
RELEASE_URL="https://github.com/shenman9/silk_v2/releases/download/silk-agent-v${VERSION}"

curl -fL -o "$ARCHIVE" "$RELEASE_URL/$ARCHIVE"
curl -fL -o SHA256SUMS "$RELEASE_URL/SHA256SUMS"
grep "  $ARCHIVE$" SHA256SUMS | sha256sum -c -
tar -xzf "$ARCHIVE"
cd "silk-agent_${VERSION}_linux_amd64"
./silk-agent --version
```

The last command should print the selected version. Do not copy only the
`silk-agent` executable out of the directory: the `adapters/`,
`bridge_common/`, `cc_bridge/`, and `codex_bridge/` paths are part of the signed
bundle.

### First use on a new device

Install Python and the desired vendor CLI on the device first. Then pair the
first Agent and approve the displayed URL/code in Silk Web:

```bash
./silk-agent connect claude-code \
  --server https://<silk-backend-host> \
  --account <silk-login-name>
# or: ./silk-agent connect codex --server https://<silk-backend-host> --account <silk-login-name>
```

If the Web UI and backend use different origins, also pass
`--web https://<silk-web-host>`. The initial `connect` command may run the Host
in the foreground after approval; press `Ctrl-C` when you are ready to manage
it with the background command below:

```bash
./silk-agent start
./silk-agent status
```

### Upgrade an existing device without re-pairing

Use the same OS user and preserve the existing profile directory. If the old
installation used a custom `SILK_AGENT_HOME`, export the same value before
running the new binary:

```bash
export SILK_AGENT_HOME=/path/to/existing/silk-agent-profile  # only if previously configured
./silk-agent stop host
./silk-agent start
./silk-agent status
```

Do not run `connect`, revoke the device, delete `config.json`, or delete the
profile's `device_key`. With `silk-agent` 0.4.12 or newer, reconnecting sends
signed capability metadata and the backend preserves the existing device,
Agent instance, Workspace Binding, approvals, and sessions while adding the
allowlisted capability needed by the new runtime policy.

If the old installation runs as a user service, update that service to point at
the new extracted bundle instead:

```bash
./silk-agent service uninstall
./silk-agent service install
./silk-agent service status
```

Service installation is per-user and does not require root. Keep provider CLI
credentials and other runtime environment variables available to the service
manager; an interactive shell's environment is not automatically inherited.

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
accept a runtime public-key override. Publishing the verified output as GitHub
Release assets is a separate release operation; never publish an artifact that
was not produced and verified by this script. Archive, manifest, and signature
modes are normalized to 0644; bundle directories are 0755, executables are
0755, and source files are 0644. Group/other-writable release output is
rejected.

### Build a release bundle from source

This section is for maintainers and CI. Most users should use the prebuilt
installation above. Use Go 1.22 or newer, an isolated empty output directory,
and an Ed25519 release key pair. The private key must be protected as a 0600
file and must never be committed to the repository or placed in a bundle.

```bash
cd silk-agent
go version

PRIVATE_KEY_FILE=/secure/path/release-private.key
PUBLIC_KEY_FILE=/secure/path/release-public.key
OUTPUT_DIR=$(mktemp -d)

./scripts/package-release.sh \
  0.4.12 \
  "$OUTPUT_DIR" \
  "$PRIVATE_KEY_FILE" \
  "$PUBLIC_KEY_FILE"
```

The script requires exactly four arguments: `<version> <empty-output-dir>
<release-private-key-file> <release-public-key-file>`. Prefixing the version
with `v` is also accepted. It cross-compiles six archives
(`linux|darwin|windows` x `amd64|arm64`), includes the Host and Adapter files,
creates `manifest.json` and `manifest.sig`, and verifies hashes, signatures,
permissions, and unsigned files before returning success. Set `GO_BIN` when a
specific Go executable is required, for example
`GO_BIN=/opt/go/bin/go ./scripts/package-release.sh ...`.

For an independently downloaded release, keep the archive, `manifest.json`,
`manifest.sig`, and any release metadata in the same release directory and run
the embedded verifier from the extracted Host:

```bash
./silk-agent release verify \
  --manifest /path/to/release/manifest.json \
  --signature /path/to/release/manifest.sig \
  --artifacts /path/to/release
```

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
