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
  - 定义 `Message` / `MessageType` / `MessageCategory`
  - 定义 `ChatServer`

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
- `/users/{userId}/cc-settings*`
- `/users/{userId}/cc-state/{groupId}`
- `/users/{userId}/cc-fs/list` (GET, query: path/showHidden)
- `/users/{userId}/cc-fs/cd` (POST, JSON body: groupId/path; **rejects untrusted directories**)
- `/users/{userId}/trusted-dirs/check` (GET, query: path)
- `/users/{userId}/trusted-dirs` (POST, DELETE, GET)
- `/chat` WebSocket
- `/cc-bridge` WebSocket

已拆出的专项路由：

- `routes/FileRoutes.kt`:
  - `/api/files/upload`
  - `/api/files/download/{sessionId}/{fileId}`
  - `/api/files/list/{sessionId}`
  - `/api/files/download-apk`
  - app version 查询
- `routes/AsrRoutes.kt`:
  - `/api/asr/transcribe`

## ChatServer Flow

`ChatServer.broadcast()` 的主要副作用顺序：

1. 去重
2. 非 transient 消息写入内存历史
3. 持久化到 `ChatHistoryManager`
4. 未读计数
5. 异步 Weaviate 索引
6. 广播到所有 session
7. 对普通文本异步触发 URL/PDF 处理
8. Claude Code 模式拦截
9. Silk AI / `DirectModelAgent` 响应

## Contracts Visible To Clients

- WebSocket 消息模型同时存在于：
  - 后端 `WebSocketConfig.kt`
  - 共享前端 `frontend/shared/.../models/Message.kt`
- HTTP 响应/请求 DTO 中的 CC 模块（`CcStateResponse` / `DirEntry` / `DirListingResponse`）只在 `frontend/shared/.../models/UserSettings.kt` 一处定义；backend 通过 `implementation(project(":frontend:shared"))` 直接 import。新增字段改一处即可。
- 文件消息 payload 同时影响：
  - `routes/FileRoutes.kt`
  - `backend/BackendFileContractTest.kt`
  - `frontend/*/FileContractsTest.kt`

## Safe Change Checklist

- 改消息枚举、字段、payload 时，同步检查 `frontend/shared`
- 改文件路由时，同步检查 Web/Android/Desktop 文件合同测试
- 改历史/持久化时，同步检查 `ChatHistoryManager.kt` 与 `TestWorkspace`
- 改 WebSocket 权限或回放逻辑时，同步检查 `BackendWebSocketContractTest`
