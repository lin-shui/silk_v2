# Claude Code And Bridges

> **Plan E + E2 + E3 + E4 + F1 M1-M4 已完成**：所有业务代码走 ACP，旧 `ClaudeCodeManager` / `BridgeRegistry` / `StreamParser` / `bridge_agent.py` 已物理删除。Claude Code 与 Codex 分别通过 `cc_bridge/acp_adapter.py`、`codex_bridge/codex_adapter.py` 连接 `/agent-bridge`，`/cc-bridge` 端点已删除。详见 `KNOWN_DRIFT.md#Agent Framework In Transition`。

## Agent Framework

- `backend/agents/core/AgentRuntime.kt` — 对外门面，`WebSocketConfig` 的唯一入口
- `backend/agents/core/CommandRouter.kt` — 命令解析（`/cc`、`/use`、`/status`、`@agent` 等）
- `backend/agents/core/GroupAgentContext.kt` — per-(userId, groupId) 上下文，含 workingDir、currentAgentType、sessions
- `backend/agents/core/AgentSession.kt` — per-agent 会话状态（running、queue、acpSessionId、cliSessionId）
- `backend/agents/core/AgentRegistry.kt` — agent 类型注册表
- `backend/agents/core/AcpExtensions.kt` — Silk 私有扩展调用（`_silk/compact`、`_silk/list_local_sessions`、`_silk/set_cwd`、`_silk/list_dir`）
- `backend/agents/adapters/claudecode/ClaudeCodeDescriptor.kt` — CC adapter 描述符
- `backend/agents/adapters/codex/CodexDescriptor.kt` — Codex adapter 描述符
- `backend/agents/acp/` — ACP 协议层（`AcpClient`、`AcpTransport`、`AcpRegistry`、JSON-RPC 消息类型）

关键路径：

1. **聊天**：`WebSocketConfig.broadcast()` → `AgentRuntime.handleIfActive()` → `CommandRouter.route()` → ACP `session/prompt` → adapter 跑 `Executor` → `session/update` 流式回传
2. **/cc-fs/cd**：`Routing.kt` → `AgentRuntime.cdSync()` → ACP `_silk/set_cwd` → adapter 验证 + 返回 resolved path
3. **/cc-fs/list**：`Routing.kt` → `AgentRuntime.listDirectory()` → ACP `_silk/list_dir` → adapter 调 `fs_listing.list_directory`
4. **持久化**：`AgentRuntime.WorkflowPersistence` 接 `WorkflowManager`；prompt response 的 `meta.cliSessionId` 写入 `Workflow.agentSessions[agentType]`，并兼容镜像到旧 `Workflow.sessionId`；`activeAgent` 也随 `/use` 切换落盘
5. **Token 重生**：`/cc-settings/generate-token` → `AcpRegistry.disconnect(userId)` 关老连接
6. **AskUserQuestion / 权限**：Claude CLI 以 `--input-format stream-json --permission-prompt-tool stdio` 启动，权限请求通过 stdout `control_request` 事件输出 → `executor.py` 解析后回调 `acp_adapter.py._on_permission_request` → ACP `session/update(ask_user_question | permission_request)` 通知 backend → `AgentRuntime.setupAcpHandlers` 解析为 `List<StructuredQuestion>` 或权限卡片 → 广播 CARD 消息 → 用户点击按钮 → CARD_REPLY → `CardReplyRouter` → 多问题状态机逐题推进 → ACP `_silk/resolve_question` 或 `_silk/resolve_permission` → adapter 设置 asyncio.Future 结果 → executor 写 `control_response` 回 stdin → CLI 继续。用户也可通过底部文本输入框回答当前问题

ACP 不可用时直接报"未连接"，无 fallback。

核心事实：

- Agent 模式是 per-user-per-group 状态机，内部按 agentType 保存独立 `AgentSession`
- 真正执行 Claude CLI / Codex CLI 的不是 backend，而是外部 ACP adapter
- `ChatServer.broadcast()` 会先拦截已激活 agent 的消息
- `/codex <text>` 可一步切到 Codex 并提问；`@codex <text>` 可跨 agent 路由
- 切目录有两种入口：
  - HTTP `POST /users/{userId}/cc-fs/cd`（UI"更改"按钮 + 创建工作流时的 initialDir）→ 先经 `TrustedDirManager.isTrusted()` 验证目录信任状态，未信任则返回 `400 DIRECTORY_NOT_TRUSTED`；通过后再调 `AgentRuntime.cdSync()` 走 ACP `_silk/set_cwd` 完成，原子更新 state，返回 `CdResult.Ok | CdResult.Err`
  - 历史的聊天 `/cd` 命令已废弃，`routeMessage` 命中后只回一条引导提示
- 目录浏览：HTTP `GET /users/{userId}/cc-fs/list?path=&showHidden=` → `listDirectory()` 通过 RPC 让 bridge 跑 `handle_list_dir`
- RPC 通用机制：`pendingRpc: Map<requestId, CompletableDeferred>`，bridge 响应在 `handleBridgeMessage` 顶部优先 complete Deferred；超时 5s（withTimeout）
- 工作流持久化（"无感重启"）：`Workflow` 数据类带 `workingDir` / `activeAgent` / `agentSessions[agentType]`，并保留旧 `sessionId` / `sessionStarted` 兼容字段。
  - `WorkflowPersistence` 接口由 `Routing.kt#configureRouting` 启动时注入；callback 委托给 `WorkflowManager` 的 `updateWorkingDir` / `updateSessionState`
  - 写入入口：`cdSync` 成功、prompt response 返回真实 session id、`/use` 切换 active agent 时异步落盘
  - 读出入口：`autoActivateForWorkflow` 读 `workflow.activeAgent` 和对应 `agentSessions[agentType]` seed 进新 state——重启后用户进入工作流仍看到原工作目录与当前 agent，下次 prompt 续上对应 session
  - 失败兜底：Claude Code 端 `~/.silk/cc_sessions.json` 或 Codex 端 `~/.codex/sessions/**/rollout-*.jsonl` 若找不到 session，adapter 会回 error；用户可用 `/new` 重置

## `cc_bridge/`

主要文件：

- `acp_adapter.py` — ACP server 连接 `/agent-bridge` 端点，注册到 `AcpRegistry`，复用 `Executor` 执行 Claude CLI，支持 `_silk/*` 扩展（`compact` / `list_local_sessions` / `set_cwd` / `list_dir` / `resolve_question`）
- `executor.py` — 实际调用 Claude CLI 的执行器（`--input-format stream-json --permission-prompt-tool stdio`，通过 stdin/stdout JSON 双向通信，权限请求通过 control_request/control_response 处理）
- `session_manager.py` — 本地会话管理（`~/.silk/cc_sessions.json`）
- `fs_listing.py` — 目录列表工具（被 `_silk/list_dir` 使用）
- `bridge.sh` — 启动/停止管理脚本

关键职责：

- 通过 WebSocket 连 Silk backend
- 在本机运行 Claude CLI
- 保存会话到 `~/.silk/cc_sessions.json`
- 处理来自 silk 的命令：execute / cancel / cd / list_dir / new_session / list_sessions / resume_session / compact
- 处理 AskUserQuestion / 权限请求：Claude CLI stdout `control_request` → executor 回调 adapter → ACP 通知 backend → 用户回答 → resolve → executor 写 `control_response` 回 stdin
- `working_dir_holder = [...]` 持有当前 cwd，被 `handle_cd` 修改、被 `handle_execute` / `handle_list_dir` 在消息未带 path 时作为 fallback

## `codex_bridge/`

主要文件：

- `codex_adapter.py` — ACP server 连接 `/agent-bridge?agentType=codex`，把 Silk prompt / cancel / session-load / `_silk/*` 请求映射到 Codex CLI
- `codex_executor.py` — 实际调用 `codex exec --json`，解析 tool/function/turn.failed 等 JSONL 事件，并受 `CODEX_TIMEOUT` 看门狗保护
- `codex_session_index.py` — 扫描 `~/.codex/sessions/**/rollout-*.jsonl`，用于 `_silk/list_local_sessions` 与 `session/load`
- `fs_listing.py` — 目录列表工具（与 Claude Code adapter 保持同类响应）
- `bridge.sh` — 启动/停止管理脚本

关键职责：

- 通过 WebSocket 连 Silk backend
- 在本机运行 Codex CLI
- 将 Codex JSONL 中的 agent_message / reasoning / Bash/Edit / function / built-in tool 事件映射成 ACP `session/update`
- 从 Codex rollout JSONL 中恢复 thread/session
- 处理来自 Silk 的 prompt / cancel / list_dir / list_sessions / set_cwd / session_load 等 ACP 请求

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

- 改 agent 指令或元信息格式：检查后端测试与对应 adapter 兼容性
- 改 Silk message shape：确认飞书消息适配层是否仍能消费
- 改 AskUserQuestion 或权限处理逻辑：检查 `executor.py` 的 `_parse_control_request` / `_write_control_response`、`acp_adapter.py` 的 `_on_permission_request` / `_handle_silk_resolve_question` / `_handle_silk_resolve_permission`
- 新增 state 修改入口：走 `GroupAgentContext` / `AgentSession` 字段（`@Volatile`，当前不需 mutex）
- 新增 bridge 命令类型：改 `AcpExtensions.kt` + 对应 adapter
- 新增 RPC 风格响应：走 ACP JSON-RPC `_call()`
- 新增需要持久化的 agent state 字段：在 `Workflow` 加字段 + `WorkflowManager` 加 update 方法 + `WorkflowPersistence` 回调 + `loadSeed` 取出 + `autoActivateForWorkflow` seed，否则重启后会丢
- 改入口面（`WebSocketConfig` 调用点）：只改 `AgentRuntime`
