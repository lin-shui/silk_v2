# Claude Code And Bridges

## Backend Side

- `backend/claudecode/ClaudeCodeManager.kt`
- `backend/claudecode/BridgeRegistry.kt`
- `backend/claudecode/StreamParser.kt`

核心事实：

- CC 模式是 per-user-per-group 状态机
- 真正执行 Claude CLI 的不是 backend，而是外部 bridge
- `ChatServer.broadcast()` 会先拦截 CC 模式消息

## `cc_bridge/`

主要文件：

- `bridge_agent.py`
- `executor.py`
- `session_manager.py`
- `bridge.sh`

关键职责：

- 通过 WebSocket 连 Silk backend
- 在本机运行 Claude CLI
- 保存会话到 `~/.silk/cc_sessions.json`

## `feishu_bot/`

主要文件：

- `main.py`
- `feishu_handler.py`
- `silk_client.py`
- `streaming.py`
- `user_binding.py`

关键职责：

- 作为飞书到 Silk 的网关
- 复用 Silk 后端既有 HTTP / WebSocket 能力
- 账号绑定数据写入 `feishu_bot/data/user_bindings.json`

## Change Checklist

- 改 CC 指令或元信息格式：检查后端测试与 `cc_bridge` 兼容性
- 改 Silk message shape：确认飞书消息适配层是否仍能消费
