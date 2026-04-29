# Claude Code And Bridges

> **过渡期注意**：`develop_acp` 分支引入了 `AgentRuntime` 框架层。Plan E 已完成，ACP 新桥（`acp_adapter.py`）为默认执行路径，旧桥（`bridge_agent.py`）作为回退（`BRIDGE_MODE=legacy`）。详见 `KNOWN_DRIFT.md#Agent Framework In Transition`。

## Agent Framework Layer (新)

- `backend/agents/core/AgentRuntime.kt` — 对外门面，替代 `ClaudeCodeManager` 作为 `WebSocketConfig` 的唯一入口
- `backend/agents/core/CommandRouter.kt` — 命令解析（`/cc`、`/use`、`/status`、`@agent` 等）
- `backend/agents/core/GroupAgentContext.kt` — per-(userId, groupId) 上下文，含 workingDir、currentAgentType、sessions
- `backend/agents/core/AgentSession.kt` — per-agent 会话状态（running、queue、acpSessionId）
- `backend/agents/core/AgentRegistry.kt` — agent 类型注册表
- `backend/agents/adapters/claudecode/ClaudeCodeDescriptor.kt` — CC adapter 描述符
- `backend/agents/acp/` — ACP 协议层（`AcpClient`、`AcpTransport`、`AcpRegistry`、JSON-RPC 消息类型）

关键路径：

1. `WebSocketConfig.broadcast()` → `AgentRuntime.handleIfActive()` → `CommandRouter.route()`
2. ACP 可用（默认）→ `AcpRegistry` + `AcpClient` → adapter `session/prompt` → `Executor` 跑 Claude CLI → `session/update` 流式回传
3. ACP 不可用 → **回退** `ClaudeCodeManager.handleIfActive()`（旧路径，E3 后移除）
4. `autoActivateForWorkflow` 双写 `ClaudeCodeManager` + `AgentRuntime`（E3 后移除双写）

## Backend Side (旧，过渡期仍为实际执行层)

- `backend/claudecode/ClaudeCodeManager.kt`
- `backend/claudecode/BridgeRegistry.kt`
- `backend/claudecode/StreamParser.kt`

核心事实：

- CC 模式是 per-user-per-group 状态机
- 真正执行 Claude CLI 的不是 backend，而是外部 bridge
- `ChatServer.broadcast()` 会先拦截 CC 模式消息
- `UserCCState` 字段私有，外部只能通过 `state.withLock { h -> ... }` 修改：所有多字段写入强制走 Mutex 保护，避免 chat WebSocket 路径与 HTTP `/cc-fs/*` 入口的并发 race。`snapshot()` 提供无锁只读快照
- 切目录有两种入口：
  - HTTP `POST /users/{userId}/cc-fs/cd`（UI"更改"按钮 + 创建工作流时的 initialDir）→ 先经 `TrustedDirManager.isTrusted()` 验证目录信任状态，未信任则返回 `400 DIRECTORY_NOT_TRUSTED`；通过后再调 `ClaudeCodeManager.cdSync()` 走 RPC 等 bridge `cd_result` 完成，原子更新 state，返回 `CdResult.Ok | CdResult.Err`
  - 历史的聊天 `/cd` 命令已废弃，`routeMessage` 命中后只回一条引导提示
- 目录浏览：HTTP `GET /users/{userId}/cc-fs/list?path=&showHidden=` → `listDirectory()` 通过 RPC 让 bridge 跑 `handle_list_dir`
- RPC 通用机制：`pendingRpc: Map<requestId, CompletableDeferred>`，bridge 响应在 `handleBridgeMessage` 顶部优先 complete Deferred；超时 5s（withTimeout）
- 工作流持久化（"无感重启"）：`Workflow` 数据类带 `workingDir` / `sessionId` / `sessionStarted` 字段，与运行时 `UserCCState` 镜像。
  - `WorkflowPersistence` 接口由 `Routing.kt#configureRouting` 启动时注入；callback 委托给 `WorkflowManager` 的 `updateWorkingDir` / `updateSessionState`
  - 写入入口：`cdSync` 成功、`complete`/`error`/`session_resumed`/`new_session`、`handleExit` 都会异步落盘
  - 读出入口：`autoActivateForWorkflow` 在 `states` map 首次创建 entry 时调 `loadSeed(rawGroupId)`，把记录里的字段 seed 进新 state——重启后用户进入工作流仍看到原工作目录，下次 prompt 用 `resume=true` 续上原 session
  - 失败兜底：bridge 端 `~/.silk/cc_sessions.json` 若已不含此 sessionId，bridge 会回 error；用户 `/new` 即可重置

## `cc_bridge/`

主要文件：

- `acp_adapter.py` — **新默认桥**（`BRIDGE_MODE=acp`），ACP server 连接 `/agent-bridge` 端点，注册到 `AcpRegistry`，复用 `Executor` 执行 Claude CLI，支持 `_silk/*` 扩展
- `bridge_agent.py` — 旧桥（`BRIDGE_MODE=legacy`），连接 `/cc-bridge` 端点，注册到 `BridgeRegistry`
- `executor.py` — 实际调用 Claude CLI 的执行器（新旧桥共用）
- `session_manager.py` — 本地会话管理（`~/.silk/cc_sessions.json`，新旧桥共用）
- `bridge.sh` — 启动/停止管理脚本，`BRIDGE_MODE` 环境变量控制走新桥还是旧桥（默认 `acp`）

关键职责：

- 通过 WebSocket 连 Silk backend
- 在本机运行 Claude CLI
- 保存会话到 `~/.silk/cc_sessions.json`
- 处理来自 silk 的命令：execute / cancel / cd / list_dir / new_session / list_sessions / resume_session / compact
- `working_dir_holder = [...]` 持有当前 cwd，被 `handle_cd` 修改、被 `handle_execute` / `handle_list_dir` 在消息未带 path 时作为 fallback

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
- 新增 state 修改入口：旧桥走 `UserCCState.withLock { h -> ... }`；新框架走 `GroupAgentContext` / `AgentSession` 字段（`@Volatile`，当前不需 mutex）
- 新增 bridge 命令类型：旧桥改 `BridgeRequest` + `bridge_agent.py` dispatcher；新桥改 `AcpExtensions.kt` + `acp_adapter.py`
- 新增 RPC 风格响应：旧桥走 `pendingRpc.complete()`；新桥走 ACP JSON-RPC `_call()`
- 新增需要持久化的 CC state 字段：在 `Workflow` 加字段 + `WorkflowManager` 加 update 方法 + `WorkflowPersistence` 回调 + `loadSeed` 取出 + `autoActivateForWorkflow` seed，否则重启后会丢
- 改入口面（`WebSocketConfig` 调用点）：只改 `AgentRuntime`，不要直接调 `ClaudeCodeManager`
