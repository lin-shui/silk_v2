# Backend Index

## Primary Surfaces

- 启动：`Application.kt`
- HTTP：`Routing.kt`, `routes/FileRoutes.kt`, `routes/AsrRoutes.kt`
- Chat/WebSocket：`WebSocketConfig.kt`
- 历史/文件：`ChatHistoryManager.kt`, `routes/FileRoutes.kt`
- AI：`ai/DirectModelAgent.kt`, `ai/ToolPolicyManager.kt`, `ai/AIConfig.kt`
- 搜索：`search/WeaviateClient.kt`, `search/ExternalSearchService.kt`
- Claude Code：`claudecode/ClaudeCodeManager.kt`, `claudecode/BridgeRegistry.kt`

## Read Next By Task

- 路由/聊天： [CHAT_AND_ROUTING.md](CHAT_AND_ROUTING.md)
- AI/搜索/Claude Code： [AI_AND_INTEGRATIONS.md](AI_AND_INTEGRATIONS.md)
- 存储/业务模块： [DOMAIN_STORAGE_AND_FEATURES.md](DOMAIN_STORAGE_AND_FEATURES.md)
