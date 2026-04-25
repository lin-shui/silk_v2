# Shared

## Why It Matters

`frontend/shared` 是 Web / Android / Desktop **以及 backend** 的合同面。后端消息结构、文件 payload、HTTP 响应、WebSocket 行为如果变了，先看这里。

虽然位于 `frontend/` 目录下（历史命名），实际上是前后端共享的合同/协议模块——backend `build.gradle.kts` 里 `implementation(project(":frontend:shared"))` 消费它的 desktop (JVM) target；Harmony 端不通过 Gradle 复用，需要手工保持类型同步。

## Primary Files

- `ChatClient.kt`
- `models/Message.kt`
- `models/ApiResponses.kt`
- `models/UserSettings.kt`（含 `CcStateResponse` / `DirEntry` / `DirListingResponse` 等 CC 模块协议）
- `PlatformWebSocket*`
- `i18n/Strings.kt`

## Responsibilities

- 维护 WebSocket 连接状态
- 历史回放缓冲与一次性刷入
- transient / incremental AI 消息拼接
- 停止生成消息发送
- 跨端消息模型与基础 API response 模型
- CC 模块前后端共享 DTO（避免双份维护）

## Contract Change Checklist

- 改 `MessageType` / `MessageCategory` / `Message` 字段：
  - 后端 `WebSocketConfig.kt`
  - `frontend/shared/models/Message.kt`
  - 三端 UI 解析
- 改文件消息 payload：
  - Web / Android / Desktop `FileContractsTest`
  - 后端文件相关合同测试
- 改 CC HTTP DTO（`CcStateResponse` / `DirListingResponse` 等）：
  - 只改 `frontend/shared/models/UserSettings.kt` 一处
  - 后端通过 `import com.silk.shared.models.*` 自动看到
  - 老客户端解析靠 `ignoreUnknownKeys = true` 兼容新字段

## Default Validation

- `./gradlew :frontend:webApp:nodeTest`
- `./gradlew :frontend:androidApp:testDebugUnitTest`
- `./gradlew :frontend:desktopApp:test`
- 改了被 backend 引用的类型，再加 `./gradlew :backend:compileKotlin`
