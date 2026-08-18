# Chat And Routing

## Entry Points

- `Application.kt`:
  - 加载 `.env`
  - 初始化数据库
  - 安装 Ktor 插件
  - 调 `configureWebSockets()` / `configureRouting()`
- `Routing.kt`:
  - 大多数 HTTP 路由仍在这里
  - 还挂载了 `fileRoutes()` 与 `asrRoutes()`
- `WebSocketConfig.kt`:
  - 定义 `Message` / `MessageType`（含 `CARD`、`CARD_REPLY`）/ `MessageCategory`
  - `Message.action` 字段支持消息替换（`"edit"` = 覆盖同 ID 消息）
  - 定义 `ChatServer`
- `card/` 目录：
  - `CardModels.kt` — 交互卡片 JSON schema 数据类
  - `CardBuilder.kt` — 卡片构造 Builder API
  - `CardReplyRouter.kt` — 卡片回复路由注册表

## HTTP Route Groups

`Routing.kt` 当前主要承载：

- `/auth/*`
- `/groups/*`
- `/contacts/*`
- `/users/*/settings`
- `/api/unread/*`
- `/api/rooms/*`（统一 Room 发现、创建和管理）
- `/api/calendar/workday/*`
- `/api/user-todos/*`
- `/api/messages/*`
- `/api/agent-pairings*`（公开创建会在出码前预绑定目标 loginName，并使用 Host `--server` 作为未配置 canonical origin 的安全回退；设备轮询/proof、已登记设备签名新增 Agent + JWT preview/approve；非目标账号对短码统一返回 not-found）
- `/api/agent-devices`、`/api/agent-instances`、`/api/agent-bindings`（JWT 管理与查询；Binding 支持 POST/PUT 创建和更新、`POST /{bindingId}/approval` 双主体批准/拒绝、DELETE 软撤销；Room Binding 的 `mentionAlias` 在 Room + scope 内唯一并直接指向一个 AgentInstance，允许同类 Agent 以不同 mention 并存；设备/Agent 列表的 `connected` 汇总 `/agent-connect` 物理连接及逻辑流与兼容 `/agent-bridge` 活跃会话；Web 主列表隐藏 `REVOKED`，历史可展开）
- `POST /api/agent-revocation-history/cleanup`（JWT；按 `SILK_AGENT_REVOKED_RETENTION_DAYS` 清理当前账号到期的撤销 Device/Agent/Binding，审计事件和设备公钥 tombstone 不删除）
- `/device`（固定返回已构建 Web `index.html`，供 Nginx 未命中静态文件后回退到 Ktor 的部署方式使用；页面内数据仍由 JWT API 保护）
- `/api/workflows` (旧客户端兼容入口，POST **requires directory trust**)
- `/api/kb/*`
- `/api/files/app-version`
- `/api/files/hap-version`
- `/api/files/download-hap`
- `/users/{userId}/cc-settings*`
- `/users/{userId}/cc-state/{groupId}`
- `/users/{userId}/cc-fs/list` (GET, query: workspaceId/path/showHidden；指定 Workspace 时，设备签名 Agent 要求目标的 `READ_FILE` Binding 权限与能力；无 workspaceId 的调用只用于已登录 owner 在创建 Workspace 前选择本地目录，不携带 Silk 目标数据)
- `/users/{userId}/cc-fs/cd` (POST, JSON body: workspaceId/path；**rejects untrusted directories**，设备签名 Agent 另要求 `WRITE_WORKSPACE` Binding 权限与能力)
- `/api/rooms/{roomId}/workspaces/recent-working-dir?agentInstanceId=...` (GET, 当前用户必须是工作群组成员且 Agent 实例属于当前用户；返回该群组/精确 Agent 实例最近一次成功应用的目录，供新建 Workspace 预填)
- `/users/{userId}/cc-settings/update` 的 Agent 切换可同时提交 `activeAgent` 与 `activeAgentInstanceId`；实例必须属于 Workspace Owner 且已有该 Workspace 的 ACTIVE Binding。Workspace 消息、目录与代码审查随后均使用这个精确实例授权和路由；旧 Workspace 的空实例字段继续走唯一 Binding 的兼容解析
- `/users/{userId}/trusted-dirs/check` (GET, query: path)
- `/users/{userId}/trusted-dirs` (POST, DELETE, GET)
- `/chat` WebSocket
- `/ws/audio-duplex` WebSocket proxy
- `/agent-bridge` WebSocket（ACP 兼容入口，仅设备签名 Host 迁移握手；新 Host 使用 `/agent-connect`）
- `/agent-connect` WebSocket（设备签名 challenge-response + heartbeat；Host 模式在一次认证后使用 `agent_open / agent_rpc / agent_close` envelope 按 `agentInstanceId` 多路复用 ACP）

已拆出的专项路由：

- `routes/FileRoutes.kt`:
  - `/api/files/upload`
  - 上传后仅图片进入异步 Vision 路径；普通文件只进入文件预处理与 `FILE` 消息路径
  - `/api/files/download/{sessionId}/{fileId}`
  - `/api/files/list/{sessionId}`
  - `/api/files/download-apk`
  - `/api/files/app-version`
  - `/api/files/hap-version`
  - `/api/files/download-hap`
  - app version 查询
- `routes/AsrRoutes.kt`:
  - `/api/asr/transcribe`
- `routes/ObsidianRoutes.kt`:
  - `GET /api/obsidian/sync` — 一键导出用户所有群聊 + KB 条目的 Obsidian Markdown
- `routes/RoomRoutes.kt`:
  - `GET /api/rooms/visible` — 按 JWT 成员关系聚合 RoomKind、角色、未读、Workflow ID 与 cc-connect 摘要；有消息时按 `lastMessageAt`、无消息时按创建时间统一倒排
  - `POST /api/rooms` — 创建聊天或工作群组；工作群组不隐式创建 Workspace
  - `PUT /api/rooms/{roomId}` — Owner 重命名；工作群组双写 Group/Workflow，cc-connect 同步 token label
  - `POST /api/rooms/{roomId}/leave` — 非 Owner 按 JWT 身份退出；工作群组同时停止本人 Agent 状态、把本人 Workspace 收回为私密、清空 Co-pilot 并撤销在线 Room 会话；Owner 从成员面板移除成员时复用同一权限收口
  - `DELETE /api/rooms/{roomId}` — Owner 删除；同步清理 Workflow/Workspace、cc-connect token/连接和在线 Room 会话

## ChatServer Flow

`ChatServer.broadcast()` 的主要副作用顺序：

1. 去重
2. 非 transient 消息写入内存历史
3. 持久化到 `ChatHistoryManager`，并以消息时间单调更新 `groups.last_message_at`（同时兼容更新 `updated_at`）
4. 未读计数
5. 广播到所有 session
7. 对普通文本异步触发 URL/PDF 处理
8. Agent 框架（Claude Code / Codex）拦截：Workspace 走 `AgentRuntime.handleIfActive()`；设备签名 Agent 先按精确 `agentInstanceId` 检查 ACTIVE Binding、内部权限投影以及 `PROMPT` / `EXECUTION_POLICY_V2`，再把 Workspace `accessMode` 与文件/命令能力交集下推到 ACP `_silk.executionPolicy`。用户只选择 `READ_ONLY / APPROVAL_REQUIRED / AUTONOMOUS`，Agent 原生策略可继续收紧；runtime 不再持久化或自动升级另一套权限模式。缺少 V2 能力的旧登记 Agent 会拒绝 prompt，需升级 Host 后重新登记。Room TEAM 按固定 `CHAT_ONLY` Binding 和 `ALL / MENTION / EVENT` 触发策略路由，当前 `EVENT` 不消费用户文本；TEAM 是无工作目录的消息上下文，`session/new` 使用空 cwd 让远程 Adapter 选择其本机默认目录，不把 Backend 或其他设备路径发送给 Agent。定向 `@cc /new` / `@codex /new` 由 Silk 清空对应 TEAM Agent 会话，不作为普通 prompt 转发给 CLI；TEAM `STOP_GENERATE` 会取消该 Room 中所有正在运行的 bound Agent ACP session，同时取消内置 Silk AI 任务并清理流状态；目录和 Source Control RPC 复用同一授权器
9. `/recall` 命令交给 `UserHistoryAgent`，在 per-user hardlink workspace 中只读检索历史会话
10. Silk AI / `DirectModelAgent` 响应

其中 Silk AI 主链新增两段 KB 闭环：

- 生成前：把当前用户可读 KB 同步到 agent workspace 的 `knowledge_base/manifest.md` 与 `knowledge_base/topics/**`，供 Grep/Read 自主查阅
- 生成后：若模型附带 `silk_kb_action` JSON block，后端按当前 userId 权限执行 KB create/update，并把执行结果追加到最终回复

## Contracts Visible To Clients

- WebSocket 消息模型同时存在于：
  - 后端 `WebSocketConfig.kt`
  - 共享前端 `frontend/shared/.../models/Message.kt`
- HTTP 响应/请求 DTO 中的 CC 模块（`CcStateResponse` / `DirEntry` / `DirListingResponse`）只在 `frontend/shared/.../models/UserSettings.kt` 一处定义；backend 通过 `implementation(project(":frontend:shared"))` 直接 import。新增字段改一处即可。
- Room 合同（含 `RenameRoomRequest` / `RoomActionResponse`）在 `frontend/shared/.../models/RoomModels.kt`，`RoomKind` 是运行时分类权威，`Group.name` 是 Room 展示名权威；列表活动时间优先使用 `RoomSummaryDto.lastMessageAt`，无消息时回退 `createdAtEpochMs`，元数据更新时间不参与正常排序。Workflow/Workspace 元数据在迁移期只用于 fail-closed 保护和专项数据。
- `GroupRepository` 的普通 Room 与联系人 Room 创建入口都显式写入本次 insert 的 `createdAt/updatedAt`，不能依赖 `Groups` 表对象初始化时求值的时间默认值，否则同一后端进程创建的 Room 会获得相同时间。
- WebSocket `blocks_state` 消息：后端流式发送完整 content block 列表（含 type/content/isComplete/elapsedMs），前端替换前一次列表。类型包括 `thinking`（ThinkingBlock 折叠渲染）、`text`（MarkdownContent）、`tool_use`（ToolCallBlock）。`elapsedMs` 仅对 thinking block 有意义——进行中 block 由后端推 live 计时（`now - blockStartMs`），完成时锁定真实耗时；前端据此显示"Thought for Xs"，避免组件重新挂载导致计时丢失。0 表示后端未提供（旧消息兼容），前端回退到本地计时。
- 文件消息 payload 同时影响：
  - `routes/FileRoutes.kt`
  - `backend/BackendFileContractTest.kt`
  - `frontend/*/FileContractsTest.kt`
- Audio Duplex WebSocket 透传协议影响 Web / Android / Harmony 的 Audio Duplex 页面与 `AIConfig.AUDIO_DUPLEX_URL`。

## Safe Change Checklist

- 改消息枚举、字段、payload 时，同步检查 `frontend/shared`
- 改文件路由时，同步检查 Web/Android/Desktop 文件合同测试
- 改历史/持久化时，同步检查 `ChatHistoryManager.kt` 与 `TestWorkspace`
- 改 WebSocket 权限或回放逻辑时，同步检查 `BackendWebSocketContractTest`
- 改 `/ws/audio-duplex` 时，同步检查三端 Audio Duplex 调用端
- 改 `/agent-bridge` 或 agent 指令路由时，同步检查 `AgentRuntime` / ACP 相关测试与 adapter
- 改设备配对、新增 Agent 的设备签名、Ed25519 canonical payload 或 `/agent-connect` 时，同步检查 `AgentAuthRouteContractTest`、`AgentAuthProtocolTest` 与 `AcpMultiplexedTransportTest`；不得把配对 secret 放入 URL 或日志
