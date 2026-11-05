# Backend Index

## Primary Surfaces

- 启动：`Application.kt`
- HTTP：`Routing.kt`, `routes/FileRoutes.kt`, `routes/AsrRoutes.kt`
- Chat/WebSocket：`WebSocketConfig.kt`
- 历史/文件：`ChatHistoryManager.kt`, `routes/FileRoutes.kt`
- AI：`ai/DirectModelAgent.kt`, `ai/UserHistoryAgent.kt`, `ai/ToolPolicyManager.kt`, `ai/AIConfig.kt`
- 搜索：`search/WeaviateClient.kt`, `search/ExternalSearchService.kt` — 主线已由 AnthropicClient + grep 替代
- Knowledge Base：`kb/KnowledgeBaseManager.kt`（CRUD + user memory topic/entry 管理）、`kb/KnowledgeBasePromptContext.kt`（`resolveKnowledgeBasePromptContext` 内联引用 + KB/Memory 上下文注入）、`kb/KnowledgeBaseContextPreferenceStore.kt`（用户级 space 排除偏好 + `memoryEnabled/autoCaptureEnabled/ephemeralSessionEnabled` 开关）、`kb/KnowledgeBaseMemory.kt`（显式"记住 xxx"抽取 + 五种自动记忆检测器 + 敏感内容过滤）
- dsh-superagent bot：`routes/DshBotRoutes.kt`（HTTP 面）、`dsh/DshBotIdentity.kt`、`dsh/DshBotTokenRepository.kt`、`AgentIdentity.kt`（AI 消息身份判定）
- 用户历史视图：`UserWorkspaceManager.kt`（为 `/recall` 创建 per-user hardlink workspace）

## Read Next By Task

- 路由/聊天： [CHAT_AND_ROUTING.md](CHAT_AND_ROUTING.md)
- AI/搜索/Agent： [AI_AND_INTEGRATIONS.md](AI_AND_INTEGRATIONS.md)
- 存储/业务模块： [DOMAIN_STORAGE_AND_FEATURES.md](DOMAIN_STORAGE_AND_FEATURES.md)
