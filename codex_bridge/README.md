# codex_bridge

Silk ACP bridge for OpenAI Codex CLI. Connects to `silk` backend via
`/agent-bridge?agentType=codex` and runs `codex exec --json` on prompts.

Mirror of `cc_bridge/` (Claude Code bridge). M1 supports plain text chat
only — no tool-call streaming, no session resume, no `_silk/*` extensions.

## Prerequisites

- Codex CLI v0.125.0+ (`codex --version`)
- Logged in: `~/.codex/auth.json` exists (run `codex login` if not)
- Python 3.10+ with `websockets>=12`
- A Silk bridge token (Settings → Generate Bridge Token)

## Setup

1. Create `.env` next to this README:
   ```
   BRIDGE_SERVER=localhost:8006
   BRIDGE_TOKEN=<your-bridge-token>
   BRIDGE_WORKING_DIR=/path/to/your/project
   # Optional:
   # BRIDGE_PYTHON=/path/to/venv/bin/python3
   # CODEX_AUTO_APPROVE=1   # default; pass --dangerously-bypass-approvals-and-sandbox
   ```

2. Install deps in your bridge venv:
   ```
   pip install -r requirements.txt
   ```

3. Start:
   ```
   ./bridge.sh start
   ```

4. In Silk UI: `/use codex` then send any message.

## Tests

```
python3 -m pytest tests/ -v
```

## Troubleshooting

- "codex: command not found" → install Codex CLI, ensure on PATH for the
  user that runs `bridge.sh`.
- "auth.json not found" → run `codex login` once.
- Bridge connects but `/codex hi` says "未连接" → token mismatch; regenerate
  in Silk UI and update `.env`.
