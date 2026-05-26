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
- `/api/calendar/workday/*`
- `/api/user-todos/*`
- `/api/messages/*`
- `/api/workflows` (POST **requires directory trust**)
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

## ChatServer Flow

`ChatServer.broadcast()` 的主要副作用顺序：

1. 去重
2. 非 transient 消息写入内存历史
3. 持久化到 `ChatHistoryManager`
4. 未读计数
5. 广播到所有 session
7. 对普通文本异步触发 URL/PDF 处理
8. Agent 框架（Claude Code / Codex）拦截：`AgentRuntime.handleIfActive()`
9. `/recall` 命令交给 `UserHistoryAgent`，在 per-user hardlink workspace 中只读检索历史会话
10. Silk AI / `DirectModelAgent` 响应

## Contracts Visible To Clients

- WebSocket 消息模型同时存在于：
  - 后端 `WebSocketConfig.kt`
  - 共享前端 `frontend/shared/.../models/Message.kt`
- HTTP 响应/请求 DTO 中的 CC 模块（`CcStateResponse` / `DirEntry` / `DirListingResponse`）只在 `frontend/shared/.../models/UserSettings.kt` 一处定义；backend 通过 `implementation(project(":frontend:shared"))` 直接 import。新增字段改一处即可。
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
