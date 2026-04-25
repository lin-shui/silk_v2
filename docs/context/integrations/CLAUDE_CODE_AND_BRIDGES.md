# Claude Code And Bridges

## Backend Side

- `backend/claudecode/ClaudeCodeManager.kt`
- `backend/claudecode/BridgeRegistry.kt`
- `backend/claudecode/StreamParser.kt`

核心事实：

- CC 模式是 per-user-per-group 状态机
- 真正执行 Claude CLI 的不是 backend，而是外部 bridge
- `ChatServer.broadcast()` 会先拦截 CC 模式消息
- `UserCCState` 字段私有，外部只能通过 `state.withLock { h -> ... }` 修改：所有多字段写入强制走 Mutex 保护，避免 chat WebSocket 路径与 HTTP `/cc-fs/*` 入口的并发 race。`snapshot()` 提供无锁只读快照
- 切目录有两种入口：
  - HTTP `POST /users/{userId}/cc-fs/cd`（UI"更改"按钮 + 创建工作流时的 initialDir）→ `ClaudeCodeManager.cdSync()` 走 RPC 等 bridge `cd_result` 完成，原子更新 state，返回 `CdResult.Ok | CdResult.Err`
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

- `bridge_agent.py`
- `executor.py`
- `session_manager.py`
- `bridge.sh`

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
- 新增 state 修改入口：必须走 `UserCCState.withLock { h -> ... }`（字段 setter 是 private，编译期会强制）
- 新增 bridge 命令类型：同时改 `BridgeRequest`（@EncodeDefault NEVER 默认值省略） + bridge_agent.py dispatcher
- 新增 RPC 风格响应：在 `handleBridgeMessage` 走 `pendingRpc.complete()` 路径，不要新建 broadcastFn 上下文
- 新增需要持久化的 CC state 字段：在 `Workflow` 加字段 + `WorkflowManager` 加 update 方法 + `WorkflowPersistence` 回调 + `loadSeed` 取出 + `autoActivateForWorkflow` seed，否则重启后会丢
