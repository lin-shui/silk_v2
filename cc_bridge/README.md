# Claude Code Adapter

`cc_bridge/acp_adapter.py` is the Claude Code execution Adapter managed by the `silk-agent` Host. The Host owns device pairing, the private key, HTTPS/WSS validation, reconnects, and the multiplexed `/agent-connect` WebSocket. The Adapter receives only ACP messages and its fixed `agentInstanceId` over Host IPC v2 on stdin/stdout.

Direct `--server` / `--token` mode and the user-level Bridge Token have been retired. `bridge.sh` now exits with a migration message; `cc-connect` uses a separate group-token compatibility path until Phase 8.

## Start

```bash
silk-agent connect claude-code --server https://<silk-host>
```

The Host starts `acp_adapter.py --silk-host-stdio` automatically. Set `BRIDGE_PYTHON` only when the packaged Adapter should use a specific Python interpreter. `--working-dir` and `--log-level` remain Adapter options used by managed launches.

Managed prompts carry an execution-policy envelope derived from the Agent owner's runtime mode and the Workspace Binding. `NATIVE_DEFAULT` leaves Claude's native approval policy unchanged; explicit approval/read-only/automatic modes are applied per prompt without rewriting local settings. Claude tools use an explicit allowlist; Bash is available only with read, write, and command permissions and requires the native sandbox even in automatic mode.

## Components

- `acp_adapter.py`: ACP server and Host IPC endpoint.
- `executor.py`: Claude CLI subprocess and stream-json handling.
- `bridge.sh`: retired direct-mode migration guard.
- `requirements.txt`: Python runtime dependency declaration.

Run the repository Python validation commands from `docs/context/quality/TEST_MATRIX.md` after changes.
