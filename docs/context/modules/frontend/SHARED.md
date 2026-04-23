# Shared

## Why It Matters

`frontend/shared` 是 Web / Android / Desktop 的合同面。后端消息结构、文件 payload、WebSocket 行为如果变了，先看这里。

## Primary Files

- `ChatClient.kt`
- `models/Message.kt`
- `models/ApiResponses.kt`
- `models/UserSettings.kt`
- `PlatformWebSocket*`
- `i18n/Strings.kt`

## Responsibilities

- 维护 WebSocket 连接状态
- 历史回放缓冲与一次性刷入
- transient / incremental AI 消息拼接
- 停止生成消息发送
- 跨端消息模型与基础 API response 模型

## Contract Change Checklist

- 改 `MessageType` / `MessageCategory` / `Message` 字段：
  - 后端 `WebSocketConfig.kt`
  - `frontend/shared/models/Message.kt`
  - 三端 UI 解析
- 改文件消息 payload：
  - Web / Android / Desktop `FileContractsTest`
  - 后端文件相关合同测试

## Default Validation

- `./gradlew :frontend:webApp:nodeTest`
- `./gradlew :frontend:androidApp:testDebugUnitTest`
- `./gradlew :frontend:desktopApp:test`
