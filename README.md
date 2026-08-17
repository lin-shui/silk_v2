# Silk

A cross-platform chat application built with Kotlin Multiplatform. It provides a web app, an Android APK, and optional desktop clients, with a Kotlin backend, Weaviate for vector search, and OpenAI-compatible AI APIs.

---

## Project overview

- **Backend**: Kotlin (Ktor), serves API and static web assets. Reads config from project-root `.env`.
- **Frontend**: Kotlin/JS WebApp (production build is copied to `backend/static` and served there).
- **Android**: Kotlin Multiplatform Android app; APK can be built and served for download.
- **Vector store**: Weaviate (run via Docker by `silk.sh`). Used for context and file search.
- **AI**: Any OpenAI-compatible API; configure `API_BASE_URL`, `OPENAI_API_KEY`, and `AI_MODEL` in `.env`.
- **Workflow**: Named, persistent Claude Code agent sessions. Each workflow creates a dedicated chat room with CC mode always active — no need to type `/cc`.

## Todo Roadmap Governance

Todo is treated as a long-term core module. Any todo-related feature must follow:

1. Update/align roadmap first: `docs/todo-roadmap.md`
2. Then implement code changes.
3. Write back status/changelog to roadmap after implementation.

Persistent Cursor rule:
- `.cursor/rules/todo_planning_governance.mdc`

All day-to-day operations (build, run, stop, logs, Weaviate) are driven by the **`silk.sh`** script in the project root.

---

## Project structure

| Path | Description |
|------|-------------|
| `silk.sh` | Main script: deploy, start/stop, build, logs, Weaviate management. |
| `.env` | Local config (create from `.env.example`). Not committed. |
| `.env.example` | Template and documentation for required/optional env vars. |
| `backend/` | Kotlin backend (Ktor), static files, chat history. |
| `backend/.../agents/` | Agent framework: AgentRuntime, ACP protocol layer, Claude Code adapter descriptor. |
| `cc_bridge/` | ACP Bridge Adapter: external Python process running Claude CLI; connects to backend `/agent-bridge` via ACP. |
| `silk-agent/` | Go companion Host: device pairing, one multiplexed `/agent-connect` WSS, and managed Claude/Codex Adapter processes. |
| `frontend/webApp/` | Kotlin/JS web frontend. |
| `frontend/androidApp/` | Android app; APK output can be copied to `backend/static`. |
| `frontend/desktopApp/` | Desktop client (optional). |
| `frontend/shared/` | Shared KMP code for frontends. |
| `backend/workflows/` | Workflow data store (`workflow_store.json`). |
| `search/` | Weaviate schema and indexing (e.g. `schema.py`). |

---

## Configuration

Configuration is done via a **`.env`** file in the project root. The script `silk.sh` loads it automatically (and strips CRLF line endings).

1. Copy the example: `cp .env.example .env`
2. Edit `.env` and set at least:
   - **Backend / public URL**: `BACKEND_HOST`, `BACKEND_HTTP_PORT` (default `8006`)
   - **Protocol**: set `BACKEND_SCHEME=https` (or directly set `BACKEND_BASE_URL=https://...`)
   - **AI**: `OPENAI_API_KEY`, `API_BASE_URL`, `AI_MODEL`
   - **Weaviate**: `WEAVIATE_URL` (e.g. `http://<host>:8008`)
3. Optional: external search (e.g. `SERPAPI_KEY`), feature flags. See `.env.example` for comments.

Do not commit `.env`; it is listed in `.gitignore`.

---

## Installation

Follow these steps to get Silk running using `silk.sh`. The script expects **Java 17**, **Python 3**, and (for Weaviate) **Docker**. Gradle is used via the project’s wrapper.

### 1. Clone and enter the project

```bash
cd /path/to/silk   # or your repo root
```

### 2. Create and edit `.env`

```bash
cp .env.example .env
# Edit .env: set BACKEND_HOST, BACKEND_HTTP_PORT, OPENAI_API_KEY, API_BASE_URL, AI_MODEL, WEAVIATE_URL
```

Use your real API base URL and key; set `BACKEND_HOST` to the IP or hostname other devices will use to reach this machine (e.g. for the web UI and APK download).

### 3. One-shot deploy (recommended first time)

This builds the WebApp and APK, frees the ports used by Silk, starts Weaviate (Docker), then starts the backend and the web frontend:

```bash
./silk.sh deploy
```

When it finishes, the script prints the URLs (backend, web UI, APK download). Use `./silk.sh status` to confirm all services.

### 4. Start only (if already built)

If you have already run `deploy` or built manually:

```bash
./silk.sh start
```

### 5. Check status and logs

```bash
./silk.sh status   # Backend, frontend, Weaviate, APK path
./silk.sh logs     # Tail backend/frontend logs
```

### Default ports

| Service      | Port  | Note                    |
|-------------|-------|-------------------------|
| Backend     | 8006  | API + static + APK URL  |
| Web frontend| 8005  | HTTP server for WebApp  |
| Weaviate HTTP | 8008 | Vector DB               |
| Weaviate gRPC | 50051 | Vector DB             |

If a port is in use, `deploy` will try to free it; for `start`, the script may prompt. You can stop everything with `./silk.sh stop`.

---

## Operations (silk.sh)

All commands are run from the project root. `silk.sh` loads `.env` automatically.

| Command | Description |
|---------|-------------|
| `./silk.sh deploy` | Clean ports, build WebApp + APK, start Weaviate, backend, and frontend. |
| `./silk.sh start` | Start Weaviate, backend, and frontend (builds WebApp if missing). |
| `./silk.sh stop` | Stop backend, frontend, and Weaviate. |
| `./silk.sh restart` | Stop then start. |
| `./silk.sh status` | Show status of backend, frontend, Weaviate, and latest APK. |
| `./silk.sh logs` | Tail backend and frontend logs. |
| `./silk.sh build` | Build WebApp only; output is copied to `backend/static`. |
| `./silk.sh build-apk` | Build Android APK; copies to `backend/static` and sets `silk.apk` link. |
| `./silk.sh build-all` | Build WebApp and APK. |
| `./silk.sh weaviate start\|stop\|status\|schema` | Manage Weaviate (Docker) and schema. |

APK download URL (when backend is up): `https://<BACKEND_HOST>:8006/api/files/download-apk` (or `http://` if `BACKEND_SCHEME=http`).

## Development checks

Run the repository lint gate before sending broad Kotlin or Gradle-script changes:

```bash
./gradlew silkLint
```

Only refresh `config/lint/detekt/` baselines with `./gradlew silkLintBaseline` when existing findings are intentionally accepted.

---

## Workflow

Workflows give each user a dedicated Claude Code programming session with its own persistent chat history. Unlike regular chats where you type `/cc` to enter CC mode, a workflow chat has CC mode **always on** — every message goes directly to the Claude Code agent.

### Quick start

1. Open the **Workflow** tab in the left navigation bar.
2. Click **+ 创建**, enter a name, and confirm.
3. Select the workflow to open its chat panel — Claude Code is ready immediately.
4. Type any programming task; Claude Code will read, write, and edit files via the Bridge Agent.
5. Use standard CC commands (`/new`, `/cancel`, `/status`, `/cd`, `/session`, `/compact`, `/help`) inside the workflow chat.

### How it works

- Creating a workflow automatically creates a chat group behind the scenes and links them together.
- When you open a workflow, the WebSocket connection detects it and silently activates CC mode — no `/cc` needed.
- Workflows are per-user; each user's workflow list and sessions are isolated.
- Workflow data is stored in `backend/workflows/workflow_store.json`.

### API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/workflows?userId=...` | List workflows |
| POST | `/api/workflows` | Create workflow (`{userId, name, description, initialDir}`); requires directory trust |
| DELETE | `/api/workflows/{id}?userId=...` | Delete workflow |

### Trusted Directories

Before creating a workflow or changing its working directory, the selected directory must be explicitly trusted for the current bridge machine. Trust records are per-user and per-bridge (strict v1: exact bridge ID match). Trusted parent directories automatically cover their children.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/users/{userId}/trusted-dirs/check?path=...` | Check if a directory is trusted on the current bridge |
| POST | `/users/{userId}/trusted-dirs` | Add trust for a directory (`{path}`) |
| DELETE | `/users/{userId}/trusted-dirs` | Remove trust for a directory (`{path}`) |
| GET | `/users/{userId}/trusted-dirs` | List all trusted directories for the user |

### Prerequisites

Workflows require a running **silk-agent Host and managed ACP Adapter** — see [External Agent Host](#external-agent-host) below.

---

## Claude Code integration

Silk supports a **Claude Code (CC) mode** that lets users interact with a Claude Code CLI from any chat session. This turns Silk into a programming assistant interface — users can ask Claude to read, write, and edit code on the filesystem.

CC mode uses an **ACP (Agent Client Protocol) Host** architecture: the Silk backend does not run the Claude CLI itself. The standalone `silk-agent` Host authenticates a user-owned device, maintains one multiplexed WebSocket, and supervises the packaged Python Adapter that executes Claude Code locally. This decouples the backend deployment from the Claude execution environment without giving the Adapter a Silk credential or device-signing API.

### Prerequisites

- **`silk-agent` Host** paired and running on the execution device (see [External Agent Host](#external-agent-host) below)
- **Claude CLI** and Python installed on that device (`npm install -g @anthropic-ai/claude-code` or equivalent)

### Configuration

The following environment variables are inherited by the managed Claude Adapter from the environment that starts **`silk-agent`** (set on the execution device, not the Silk backend):

```bash
# Claude CLI binary path (default: auto-detected from PATH)
# CLAUDE_CODE_PATH=claude

# Max tool-call rounds per execution (default: 100)
# CLAUDE_CODE_MAX_TURNS=100

# Per-execution timeout in seconds (default: 36000 = 10 hours)
# CLAUDE_CODE_TIMEOUT=36000

# Max output characters per execution (default: 30000)
# CLAUDE_CODE_MAX_OUTPUT_CHARS=30000
```

### Usage

In any Silk chat (group or private), send `/cc` to enter Claude Code mode:

```
/cc              Enter CC mode (new session each time)
<any text>       Send as prompt to Claude Code
/exit            Exit CC mode, return to normal Silk chat
```

#### Session management

```
/new             Start a new CC session (reset context)
/session         List historical sessions
/session <id>    Resume a previous session by ID prefix
/cd <path>       Change working directory (creates new session)
/cd              Reset to default working directory
/compact         Compress session context
```

#### Task control

```
/cancel          Cancel running task and clear queue
/queue           View queued messages
/queue clear     Clear the message queue
/status          Show current CC state (session, directory, running/idle)
/help            Show all CC commands
```

#### Behavior notes

- **Per-user isolation**: each user has their own CC state per group; one user entering CC mode does not affect others
- **CC responses are private**: only the user who activated CC sees the responses; other group members see the user's messages but not CC output
- **Message queue**: if a task is running, new messages are queued (max 10) and auto-executed when the current task finishes
- **Session persistence**: CLI session IDs are retained in Silk Agent state and may be reset with `/new`; vendor CLI history remains in the device user's native configuration directory
- **Permission mode**: Host-managed Agents enforce Binding-derived file/command policy; malformed or missing managed policy envelopes fail closed

### Architecture

CC mode is implemented in `backend/src/main/kotlin/com/silk/backend/agents/`:

| Package/File | Responsibility |
|------|---------------|
| `agents/core/AgentRuntime.kt` | Command routing, per-user state, message queue, workflow persistence |
| `agents/core/CommandRouter.kt` | Parse `/cc`, `/new`, `/status`, `@agent` etc. |
| `agents/acp/AcpClient.kt` | ACP JSON-RPC client (talks to adapter) |
| `agents/acp/AcpRegistry.kt` | Manage logical ACP connections per (userId, agentType), including Host-multiplexed streams |
| `agents/core/AcpExtensions.kt` | `_silk/*` extension calls (set_cwd, list_dir, compact, list_local_sessions) |

The integration point is `AgentRuntime.handleIfActive()` called from `ChatServer.broadcast()` (in `WebSocketConfig.kt`).

### External Agent Host

The Claude CLI runs on a separate machine (or the same machine in a different process) via the **ACP Bridge Adapter** managed by `silk-agent`: the Host owns one device-authenticated `/agent-connect` WSS and carries each Adapter's ACP objects in `agentInstanceId` envelopes. The Adapter receives no Silk credential or signing API. The old user-level `/agent-bridge` Token path is retired; cc-connect keeps its separate group Token until Phase 8. This is useful when:

- The backend runs in a container or VM without Claude CLI installed
- You want to run Claude CLI on a machine with direct access to your codebase
- You need to separate the backend deployment from the Claude execution environment

#### How it works

```
User (browser) → Silk backend ← one WSS → silk-agent Host ← stdio ACP → Adapter → Claude CLI
```

`silk-agent` authenticates the device once and opens a logical stream for each approved Agent; `cc_bridge/acp_adapter.py --silk-host-stdio` handles ACP requests (`session/new`, `session/prompt`, `_silk/*` extensions) over Host IPC v2.

#### Device Setup

1. Install the signed `silk-agent` bundle for the target platform and ensure Python, the Adapter requirements, and the desired Claude/Codex CLI are available.

2. Pair the first Agent and approve the displayed code on `/device`:

   ```bash
   silk-agent connect claude-code --server https://<silk-host> --account <silk-login-name>
   # or
   silk-agent connect codex --server https://<silk-host> --account <silk-login-name>
   ```

   If Web and Backend use different origins and the backend has no
   `BACKEND_WEB_APP_BASE_URL`, also pass `--web https://<silk-web-host>`.

3. Use `silk-agent status`, `start`, `stop`, or `logs` to manage the Host. Adding the other Agent type repeats `connect` and requires another Web approval; the running Host hot-loads it. `service install` is available for advanced deployments, but service managers do not inherit an interactive shell's provider credentials or custom environment; configure those explicitly before relying on service mode.

4. Create a Room or Workspace Binding on `/device`. A cross-owner Binding remains pending until both the Agent owner and target manager approve it.

Identity recovery and release verification are available through:

```bash
silk-agent identity backup|restore|rotate|migrate-keychain
silk-agent release verify --manifest manifest.json --signature manifest.sig --artifacts <dir>
```

#### Files

| File | Responsibility |
|------|---------------|
| `silk-agent/` | Device identity, pairing, Host lifecycle, multiplexed WSS, Adapter supervision and signed releases |
| `cc_bridge/acp_adapter.py` | Managed ACP Adapter: handles session/prompt, streams updates and `_silk/*` extensions over Host IPC |
| `cc_bridge/executor.py` | Claude CLI subprocess management |
| `cc_bridge/cc_session_index.py` | Claude native session discovery and resume-file lookup |
| `cc_bridge/fs_listing.py` | Directory listing helper (used by _silk/list_dir) |
| `bridge_common/` | Shared Host IPC v2, policy parsing and Unicode boundary helpers |

---

## Development

- **Backend only**: `./gradlew :backend:run` (still need `.env` and optionally Weaviate).
- **Web dev**: `./gradlew :frontend:webApp:browserDevelopmentRun` (dev server; for production use `./silk.sh build` then serve via backend or `./silk.sh start`).
- **Env**: `silk.sh` sources `.env` with CRLF stripped. Prefer LF line endings in `.env` to avoid issues.

If the APK build fails with “Unable to delete directory” under `frontend/androidApp/build/snapshot/`, remove that directory and run `./silk.sh build-apk` again.

---

## License

MIT
