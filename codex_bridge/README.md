# Codex Adapter

`codex_bridge/codex_adapter.py` is the Codex execution Adapter managed by the `silk-agent` Host. The Host owns device pairing, the private key, HTTPS/WSS validation, reconnects, and the multiplexed `/agent-connect` WebSocket. The Adapter receives only ACP messages and its fixed `agentInstanceId` over Host IPC v2 on stdin/stdout.

Direct `--server` / `--token` mode and the user-level Bridge Token have been retired. `bridge.sh` now exits with a migration message; `cc-connect` uses a separate group-token compatibility path until Phase 8.

## Start

```bash
silk-agent connect codex --server https://<silk-host>
```

The Host starts `codex_adapter.py --silk-host-stdio` automatically. Set `BRIDGE_PYTHON` only when the packaged Adapter should use a specific Python interpreter. Codex CLI must already be installed and authenticated for the Host user.

Managed prompts start the device user's normal Codex CLI, so `CODEX_HOME`, login credentials, `config.toml`, `AGENTS.md`, rules, hooks, MCP servers, plugins, model/provider selection, and other native preferences remain available. Silk adds Binding-derived command-line limits for sandbox mode and shell access, and never adds the dangerous approval/sandbox bypass to a managed run. The Adapter supports streaming, cancellation, session resume, and Silk `_silk/*` directory/session helpers.

## Tests

```bash
python3 -m unittest discover -s codex_bridge/tests
```
