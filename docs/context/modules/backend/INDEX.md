# Backend Index

## Primary Surfaces

- 启动：`Application.kt`
- HTTP：`Routing.kt`, `routes/FileRoutes.kt`, `routes/AsrRoutes.kt`
- Chat/WebSocket：`WebSocketConfig.kt`
- 历史/文件：`ChatHistoryManager.kt`, `routes/FileRoutes.kt`
- AI：`ai/DirectModelAgent.kt`, `ai/UserHistoryAgent.kt`, `ai/ToolPolicyManager.kt`, `ai/AIConfig.kt`
- 搜索：`search/WeaviateClient.kt`, `search/ExternalSearchService.kt` — 主线已由 AnthropicClient + grep 替代
- Knowledge Base：`kb/KnowledgeBaseManager.kt`（CRUD）、`kb/KnowledgeBaseReferenceResolver.kt`（`[[kb:...]]` 内联引用解析）
- 目录信任：`trust/TrustedDirManager.kt`
- Agent 框架（Claude Code / Codex 入口）：`agents/core/AgentRuntime.kt`, `agents/acp/AcpClient.kt`, `agents/acp/AcpRegistry.kt`
- 用户历史视图：`UserWorkspaceManager.kt`（为 `/recall` 创建 per-user hardlink workspace）

## Read Next By Task

- 路由/聊天： [CHAT_AND_ROUTING.md](CHAT_AND_ROUTING.md)
- AI/搜索/Agent： [AI_AND_INTEGRATIONS.md](AI_AND_INTEGRATIONS.md)
- 存储/业务模块： [DOMAIN_STORAGE_AND_FEATURES.md](DOMAIN_STORAGE_AND_FEATURES.md)
