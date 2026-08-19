# Claude Code And Bridges

> **Plan E + E2 + E3 + E4 + F1 M1-M4 已完成**：所有业务代码走 ACP，旧 `ClaudeCodeManager` / `BridgeRegistry` / `StreamParser` / `bridge_agent.py` 已物理删除。Claude Code 与 Codex 分别由 `cc_bridge/acp_adapter.py`、`codex_bridge/codex_adapter.py` 执行，通过 `silk-agent` Host IPC v2 和单一 `/agent-connect` WSS 接入；`/cc-bridge` 已删除，兼容 `/agent-bridge` 只接受设备签名。

## Unified Device Authentication Baseline

统一外部 Agent 认证的服务端 Phase 0–2、`silk-agent` Host Phase 3、直接 Bridge Phase 4、Web 管理 Phase 5、统一 Host WSS Phase 6 和 Phase 7 安全收尾已经完成仓库级验收；cc-connect 迁移仍留在 Phase 8：

- `agents/auth/` 固化 raw Ed25519（32-byte base64url、无 padding）、`SHA256:<base64url>` 指纹和 v1 canonical payload；`deviceId` / `agentInstanceId` 使用服务端 UUID。初次配对在出码前按 loginName 预绑定 owner，其他账号对该短码只能得到 not-found；Host 提交的 `--server` 是未配置 `BACKEND_BASE_URL` 时的 canonical origin 来源，不再使用后端内网监听地址。
- SQLite 新增 `agent_devices`、`agent_instances`、`agent_bindings`、`agent_binding_audit_events`、`agent_pairing_requests`、`agent_device_revocation_tombstones`；`agent_instances.runtime_permission_mode` 保存 owner 在 Silk 选择的 Agent 运行覆盖，变更写安全事件，不上传或改写设备原生 CLI 配置。Binding 安全状态变化写不可变快照审计，私钥永不上传，短码和 `devicePollSecret` 只保存摘要。撤销历史默认保留 90 天并可配置，过期业务行由后端每日清理；安全事件、Binding 审计和旧设备公钥 tombstone 不清理，避免丢失审计或允许撤销密钥重新注册。
- `routes/AgentAuthRoutes.kt` 提供配对创建、JWT preview/approve、设备轮询/proof、已登记设备签名新增 Agent、设备/Agent 查询与软撤销。新增请求签名绑定 origin、一次性 requestId、Agent 元数据和能力集合，且必须由设备 owner 在 Web 再次确认。
- `/agent-connect` 完成一次性短时 challenge-response 后，以 `agent_open / agent_rpc / agent_close` v1 envelope 承载 ACP；每帧携带 `agentInstanceId`，每个逻辑流独立队列、初始化、关闭和授权。新 Host 的 `agent_open` 还会用设备私钥签名当前 connector 版本和能力，后端只把白名单中的 `EXECUTION_POLICY_V2` 自动合并到原 AgentInstance；原 `agentInstanceId`、设备、Binding、审批和会话保持不变，无需用户在 Web 端撤销或重新绑定。旧 Host 未携带刷新元数据时继续按旧能力 fail-closed。后端 `AcpRegistry` 与运行时 session 按 `agentInstanceId` 注册，同一用户不同设备上的同类 Agent 可同时在线，只有同一实例重连才替换旧连接。`/agent-bridge` 仅保留设备签名旧 Host 兼容握手，query Token 明确拒绝。
- `silk-agent/` 是独立 Go companion，提供 macOS Keychain、Windows DPAPI、Linux Secret Service 和 secure-file fallback DeviceSigner、加密身份 backup/restore/rotate、配对轮询/proof、已登记设备新增 Agent 的签名请求、运行中 Host 热加载、origin 规范化、单一设备 WSS challenge/heartbeat/reconnect、`run/start/status/stop/logs`、当前用户级 `service install/uninstall/status`、Unix 0600/Windows 当前用户控制通道、受控 Adapter 子进程监督和 stdin/stdout JSON-RPC nonce/instance 绑定。`logs --follow` 只输出新增字节；Windows Scheduled Task 用 `run --config-dir` 固定实际 profile，profile 目录启动时收紧为当前用户/SYSTEM/Administrators 的受保护可继承 DACL；提升进程造成的 Administrators owner 会转交当前用户，其他普通账号 owner 继续拒绝。Windows 受管 Adapter 使用 `CREATE_NO_WINDOW`，后台运行不弹出可见 Python 控制台。备份口令与发行私钥文件要求当前用户 owner 且允许型 DACL 不得授予其他普通主体。Host IPC v2 使用 `adapter/acp` 与 `host/forwardAcp` 双向转发 ACP；Adapter 不再请求签名或建立后端 WSS。六平台签名发行包由仓库脚本生成。
- 第一阶段只允许 `claude-code` / `codex` + `ACP` 创建新配对；cc-connect 明确返回 deferred，不会被误报为已经迁移。
- Web `/device` 页面复用现有 JWT；服务端返回的 `/device#code=...` 链接用浏览器 fragment 预填短码，Web 读取后清理地址栏，登录后自动 preview，但仍需明确批准/拒绝。错误账号不能查看请求并可切换账号，手工输入继续兼容。页面还支持设备与 Agent 在线状态/撤销、Agent owner-only 运行权限、Room/Workspace Binding 增删改和跨所有者双主体审批；本地静态服务和 Nginx 回退后的 Ktor 固定路由都会把 `/device` 交给 SPA 入口。
- `/agent-bridge` 仅保留旧 Host 设备签名兼容握手，用户级 query Token 已下线；`/ccconnect-bridge` 仍使用群组 legacy Token，留给 Phase 8。新 Host 的 Claude/Codex 在同一 `/agent-connect` 并存，单个 Agent 关闭/背压/Adapter 失败不会主动关闭其他逻辑流；设备撤销才关闭整条连接。Binding 已记录 Agent owner/目标管理者双侧审批，配置变化会重置审批，安全相关状态变化另写 `agent_binding_audit_events` 不可变快照，只有 ACTIVE Binding 才参与授权。用户权限模型是 Agent 运行覆盖与 Silk Workspace Binding 两层：Agent owner 可在 `/device` 选择 `NATIVE_DEFAULT / APPROVAL_REQUIRED / READ_ONLY / AUTOMATIC`，Workspace 选择 `READ_ONLY / APPROVAL_REQUIRED / AUTONOMOUS`；Room 内部固定 `CHAT_ONLY`，旧 `permissionsJson` 仅是 `accessMode` 的兼容投影。Agent 覆盖只保存在 Silk，`NATIVE_DEFAULT` 使用设备原生配置，显式模式无需登录设备或重启 Host，并从下一轮 prompt 生效。服务端经 `AgentBindingAuthorizationService` 把连接钉到精确 `agentInstanceId`，检查目标 Binding、内部权限投影及声明能力，再按两层中更严格的一层计算有效策略。设备签名 prompt 要求 `EXECUTION_POLICY_V2`，通过 `_silk.executionPolicy` 下推有效 `accessMode`、`agentPermissionMode` 与文件/命令能力交集；V1 信封仅供 Adapter 保守解析旧请求，缺少 V2 能力的旧登记 Agent 会拒绝新 prompt，升级当前 Host 后会自动刷新，无需重新登记。`READ_ONLY` 关闭写/命令工具并将 Codex 原生审批设为 `never`；`APPROVAL_REQUIRED` 对 Claude 使用 `manual`，对 Codex 使用 app-server `on-request` + `read-only` sandbox，使命令、文件变更和额外权限请求在执行前进入统一 Silk `permission_request` 卡片；只有有效 Workspace 模式为 `AUTONOMOUS` 且 Agent 选择 `AUTOMATIC` 时，Claude 才使用 `bypassPermissions`、Codex 才显式使用 `approvalPolicy=never`。Agent 的 `NATIVE_DEFAULT` 会保留设备原生审批配置。任何模式都继续执行路径 containment、deny、sandbox 与 shell gate，且不启用 Codex dangerous no-sandbox bypass。受管连接缺失信封或收到异常版本均按空权限处理。Host IPC v1 与 v2 不兼容，升级 `silk-agent` 0.3+ 时必须同步替换 Host 和 Adapter bundle；真实公网 TLS ingress、平台凭据后端和正式密钥发布仍需部署环境验收。

完整目标与当前进度见 [统一外部 Agent 认证方案](../planning/exec-plans/2026-08-10-unified-external-agent-auth.md)。

## Agent Framework

- `backend/agents/core/AgentRuntime.kt` — 对外门面，`WebSocketConfig` 的唯一入口
- `backend/agents/core/CommandRouter.kt` — 命令解析（`/cc`、`/use`、`/status`、`@agent` 等）
- `backend/agents/core/GroupAgentContext.kt` — per-(userId, groupId) 上下文，含 workingDir、currentAgentType、sessions
- `backend/agents/core/AgentSession.kt` — per-agent 会话状态（running、queue、acpSessionId、cliSessionId）
- `backend/agents/core/AgentRegistry.kt` — agent 类型注册表
- `backend/agents/core/AcpExtensions.kt` — Silk 私有扩展调用（`_silk/compact`、`_silk/list_local_sessions`、`_silk/set_cwd`、`_silk/list_dir`、`_silk/git_status`、`_silk/git_diff`）
- `backend/agents/adapters/claudecode/ClaudeCodeDescriptor.kt` — CC adapter 描述符
- `backend/agents/adapters/codex/CodexDescriptor.kt` — Codex adapter 描述符
- `backend/agents/acp/` — ACP 协议层（`AcpClient`、`AcpTransport`、`AcpRegistry`、JSON-RPC 消息类型）

关键路径：

1. **聊天**：`WebSocketConfig.broadcast()` → Binding/能力授权 → `AgentRuntime.handleIfActive()` → `CommandRouter.route()` → ACP `session/prompt`（受管连接含 `_silk.executionPolicy`）→ adapter 按权限启动 CLI → `session/update` 流式回传
2. **/cc-fs/cd**：`Routing.kt` → `AgentRuntime.cdSync()` → ACP `_silk/set_cwd` → adapter 验证 + 返回 resolved path
3. **/cc-fs/list**：`Routing.kt` → `AgentRuntime.listDirectory()` → ACP `_silk/list_dir` → adapter 调 `fs_listing.list_directory`
4. **持久化**：`AgentRuntime.WorkflowPersistence` 接 `WorkspaceManager`；prompt response 的 `meta.cliSessionId` 写入 Workspace `agentSessions` 并兼容镜像到旧单值 session 字段，`activeAgent` 也随切换落盘；Workspace 访问模式只存于 SQLite Agent Binding，Agent 运行覆盖只存于 SQLite AgentInstance
5. **设备撤销**：`DELETE /api/agent-devices/{deviceId}` 或 `DELETE /api/agent-instances/{agentInstanceId}` → 持久化安全事件驱动所有节点关闭对应连接
6. **AskUserQuestion / 权限**：Claude CLI 以 `--input-format stream-json --permission-prompt-tool stdio` 启动，权限请求通过 stdout `control_request` 事件输出；Codex 受管 prompt 通过 `codex app-server --stdio` 接收 `item/commandExecution/requestApproval`、`item/fileChange/requestApproval` 和 `item/permissions/requestApproval`。两条链路都由 Adapter 转成 ACP `session/update(permission_request)`，通知 backend → `AgentRuntime.setupAcpHandlers` 生成统一权限卡片 → 用户点击按钮 → `CARD_REPLY` → `CardReplyRouter` → ACP `_silk/resolve_permission` → Adapter 设置 Future 并把 allow/deny 写回各自 CLI 协议。Claude AskUserQuestion 与 Codex `item/tool/requestUserInput` 也统一映射为 `ask_user_question`，通过 `_silk/resolve_question` 多问题状态机回答；Codex 的其他 app-server 事件继续映射命令/文件/函数调用到同一套 `session/update`。

ACP 不可用时直接报"未连接"，无 fallback。

核心事实：

- Agent 模式是 per-user-per-workspace/room 状态机，内部优先按 `agentInstanceId` 保存独立 `AgentSession`；仅没有实例身份的旧 Workspace 调用保留无歧义的 agentType 兼容路径
- 真正执行 Claude CLI / Codex CLI 的不是 backend，而是外部 ACP adapter
- `ChatServer.broadcast()` 会先拦截已激活 agent 的消息
- `/codex <text>` 可一步切到 Codex 并提问；`@codex <text>` 可跨 agent 路由
- 切目录有两种入口：
  - HTTP `POST /users/{userId}/cc-fs/cd`（UI"更改"按钮 + 创建工作流时的 initialDir）→ 先经 `TrustedDirManager.isTrusted()` 验证目录信任状态，未信任则返回 `400 DIRECTORY_NOT_TRUSTED`；通过后再调 `AgentRuntime.cdSync()` 走 ACP `_silk/set_cwd` 完成，原子更新 state，返回 `CdResult.Ok | CdResult.Err`
  - 历史的聊天 `/cd` 命令已废弃，`routeMessage` 命中后只回一条引导提示
- 目录浏览：HTTP `GET /users/{userId}/cc-fs/list?path=&showHidden=` → `listDirectory()` 通过 RPC 让 bridge 跑 `handle_list_dir`
- 代码审查（Source Control，只读）：HTTP `GET /api/agent/changes` / `GET /api/agent/changes/file`（`routes/AgentChangesRoutes.kt`）→ `AgentRuntime.getActiveAcpSession()` 取活动 agent 的 `(AcpClient, acpSessionId)` → `AcpExtensions.gitStatus/gitDiff` → ACP `_silk/git_status` / `_silk/git_diff` → adapter 在 `sess.cwd` 跑 `git_ops.py`（`git -c core.quotePath=false`、`LANG=C`，工作树 vs HEAD）。降级：无 session 且 `CcConnectRegistry.isConnected` 命中 → `reason="ccconnect"` 专属空态；旧 bridge 未广播扩展（`AcpRpcException`）→ `supported=false`。仅 Web 端有面板（`frontend/webApp` `SourceControlPanel` + diff2html，懒加载：列表便宜、单文件 diff 展开时才取）
- RPC 通用机制：`pendingRpc: Map<requestId, CompletableDeferred>`，bridge 响应在 `handleBridgeMessage` 顶部优先 complete Deferred；超时 5s（withTimeout）
- 工作流持久化（"无感重启"）：`Workflow` 数据类带 `workingDir` / `activeAgent` / `agentSessions[agentType]`，并保留旧 `sessionId` / `sessionStarted` 兼容字段。
  - `WorkflowPersistence` 接口由 `Routing.kt#configureRouting` 启动时注入；callback 委托给 `WorkflowManager` 的 `updateWorkingDir` / `updateSessionState`
  - 写入入口：`cdSync` 成功、prompt response 返回真实 session id、`/use` 切换 active agent 时异步落盘
  - 读出入口：`autoActivateForWorkflow` 读 `workflow.activeAgent` 和对应 `agentSessions[agentType]` seed 进新 state——重启后用户进入工作流仍看到原工作目录与当前 agent，下次 prompt 续上对应 session
  - 失败兜底：Claude Code 恢复已被本机删除的 session 时，Adapter 识别明确的 missing-session 错误并自动用新 session 重试一次；其他认证/执行错误保持可见。用户也可主动用 `/new` 重置；Workspace 对当前 Agent 使用 `/new`，Room TEAM 的定向 Binding 使用 `@cc /new` 或 `@codex /new`

## `cc_bridge/`

主要文件：

- `acp_adapter.py` — ACP server 连接 `/agent-bridge` 端点，注册到 `AcpRegistry`，复用 `Executor` 执行 Claude CLI，支持 `_silk/*` 扩展（`compact` / `list_local_sessions` / `set_cwd` / `list_dir` / `resolve_question` / `git_status` / `git_diff`）
- `executor.py` — 实际调用 Claude CLI 的执行器（`--input-format stream-json --permission-prompt-tool stdio`，通过 stdin/stdout JSON 双向通信，权限请求通过 control_request/control_response 处理）；受管 prompt 默认加载设备用户的原生 settings、认证、模型、hooks、MCP、插件和项目配置，Silk 通过额外 CLI 参数拒绝有效策略未授予的本地读/写/命令工具，只有完整的读/写/命令授权才暴露 Bash，并要求原生 sandbox 可用且禁止 unsandboxed escape hatch。有效审批模式分别映射为不额外指定、`manual` 或 `bypassPermissions`，其中自动执行仍保留 Silk deny 与 sandbox；ACP/CLI UTF-8 边界会保留正常 Unicode、合并合法 UTF-16 surrogate pair，并将孤立 surrogate 替换为 U+FFFD，避免异常客户端输入导致 prompt 写入失败
- `cc_session_index.py` — 从 Claude 原生会话目录发现并定位可恢复会话文件
- `fs_listing.py` — 目录列表工具（被 `_silk/list_dir` 使用）
- `git_ops.py` — 工作树 vs HEAD 的 git status/diff（被 `_silk/git_status` / `_silk/git_diff` 使用；`git -c core.quotePath=false` + `LANG=C`，未跟踪文件计为新增，二进制/超大 patch 截断）
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
- `codex_executor.py` / `codex_app_server.py` — 非受管兼容调用仍使用 `codex exec --json`；受管 prompt 使用 `codex app-server --stdio`，完成 initialize、thread/start/resume、turn/start，并把原生执行前审批请求转成 Adapter 回调。有效 `READ_ONLY` 使用 `read-only + approvalPolicy=never`，`APPROVAL_REQUIRED` 使用 `read-only + on-request + approvalsReviewer=user`；有效 `AUTONOMOUS` 在 Agent `NATIVE_DEFAULT` 时保留设备原生 approvalPolicy，在 Agent `AUTOMATIC` 时显式使用 `approvalPolicy=never`。未授予命令权限时关闭 shell/unified exec feature，绝不使用 dangerous no-sandbox bypass。命令 cwd、文件变更路径和额外 filesystem permissions 在转卡前再次执行 Binding workspace containment；JSON/UTF-8 边界与超时清理由 Adapter 负责。受管连接需要本机 Codex CLI 支持 app-server；不支持时 Adapter fail closed，不回退到 dangerous exec。
- `codex_session_index.py` — 扫描 `~/.codex/sessions/**/rollout-*.jsonl`，用于 `_silk/list_local_sessions` 与 `session/load`
- `fs_listing.py` — 目录列表工具（与 Claude Code adapter 保持同类响应）
- `git_ops.py` — 与 `cc_bridge/git_ops.py` 同源拷贝，支持 `_silk/git_status` / `_silk/git_diff`
- `bridge.sh` — 启动/停止管理脚本

关键职责：

- 通过 WebSocket 连 Silk backend
- 在本机运行 Codex CLI（受管 prompt 走 app-server，兼容调用走 exec JSONL）
- 将 Codex app-server/JSONL 中的 agent_message / reasoning / Bash/Edit / function / built-in tool 事件映射成 ACP `session/update`
- 接收 Codex 原生 command/file/permissions approval request，转为 Silk `permission_request` 并在用户决定后恢复 app-server
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
- 改 AskUserQuestion 或权限处理逻辑：Claude 检查 `executor.py` 的 `_parse_control_request` / `_write_control_response`、`acp_adapter.py` 的 `_on_permission_request` / `_handle_silk_resolve_question` / `_handle_silk_resolve_permission`；Codex 检查 `codex_app_server.py` 的 app-server request/response 映射、`codex_adapter.py` 的 `_on_permission_request` / `_on_question_request` / `_handle_silk_resolve_permission` / `_handle_silk_resolve_question`
- 新增 state 修改入口：走 `GroupAgentContext` / `AgentSession` 字段（`@Volatile`，当前不需 mutex）
- 新增 bridge 命令类型：改 `AcpExtensions.kt` + 对应 adapter
- 新增 RPC 风格响应：走 ACP JSON-RPC `_call()`
- 新增需要持久化的 agent state 字段：在 `Workflow` 加字段 + `WorkflowManager` 加 update 方法 + `WorkflowPersistence` 回调 + `loadSeed` 取出 + `autoActivateForWorkflow` seed，否则重启后会丢
- 改入口面（`WebSocketConfig` 调用点）：只改 `AgentRuntime`
- 改 `agents/auth/`、`/api/agent-pairings*` 或 `/agent-connect`：保持 canonical payload 字段顺序和时间单位不变，并运行 `AgentAuthRouteContractTest`、`AgentAuthProtocolTest`
