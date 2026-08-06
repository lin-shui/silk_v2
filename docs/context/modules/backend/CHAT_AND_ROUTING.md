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
- `/api/workflows` (旧客户端兼容入口，POST **requires directory trust**)
- `/api/kb/*`
- `/api/files/app-version`
- `/api/files/hap-version`
- `/api/files/download-hap`
- `/users/{userId}/cc-settings*`
- `/users/{userId}/cc-state/{groupId}`
- `/users/{userId}/cc-fs/list` (GET, query: path/showHidden)
- `/users/{userId}/cc-fs/cd` (POST, JSON body: groupId/path; **rejects untrusted directories**)
- `/users/{userId}/trusted-dirs/check` (GET, query: path)
- `/users/{userId}/trusted-dirs` (POST, DELETE, GET)
- `/chat` WebSocket
- `/ws/audio-duplex` WebSocket proxy
- `/agent-bridge` WebSocket（ACP 协议，Claude Code / Codex adapter 连接点）

已拆出的专项路由：

- `routes/FileRoutes.kt`:
  - `/api/files/upload`
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
8. Agent 框架（Claude Code / Codex）拦截：`AgentRuntime.handleIfActive()`
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
