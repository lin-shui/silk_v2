# Runtime And Storage

## Core Processes

- Backend：Ktor Netty，入口 `backend/.../Application.kt`
- Web 前端：Kotlin/JS dev server 或 backend 静态资源
- Android / Desktop / Harmony：各自原生客户端
- Weaviate：由 `silk.sh` 或 `search/` 脚本管理
- Claude Code Bridge：`cc_bridge/bridge_agent.py`
- Feishu 网关：`feishu_bot/main.py`

## Persistent Stores

| Store | Path / Override | Used By |
| --- | --- | --- |
| SQLite | `./silk_database.db` or `-Dsilk.databasePath=...` | 用户、群组、联系人、未读、设置 |
| Chat history root | `chat_history/` or `-Dsilk.chatHistoryDir=...` | `ChatHistoryManager`, 文件上传、URL 缓存 |
| Session history | `chat_history/<session>/session.json`, `chat_history.json` | WebSocket 历史回放 |
| Uploaded files | `chat_history/<session>/uploads/` | 文件消息、下载 |
| URL dedupe | `chat_history/<session>/uploads/processed_urls.txt` | URL/PDF 下载去重 |
| User todos | `chat_history/user_todos/*.json` or `-Dsilk.userTodoBaseDir=...` | `UserTodoStore` |
| Workflow store | `workflows/workflow_store.json` or `-Dsilk.workflowDir=...` | `WorkflowManager` |
| KB store | `knowledge_base/kb_store.json` or `-Dsilk.kbDir=...` | `KnowledgeBaseManager` |
| Web static / APK | `backend/static/` | 后端静态分发 |
| Bridge sessions | `~/.silk/cc_sessions.json` | `cc_bridge/session_manager.py` |
| Feishu bindings | `feishu_bot/data/user_bindings.json` | 飞书账号绑定 |

## Chat Session Naming

- 群聊主路径使用 `group_<groupId>`
- `ChatHistoryManager` 会把裸 UUID session 也标准化到 `group_` 前缀
- 文件上传与历史目录都依赖这个命名约定；不要随意修改

## Search / Indexing

- 上传文件后，后端异步广播文件消息并尝试索引到 Weaviate
- 聊天文本消息也会尝试索引到 Weaviate
- URL/PDF 链接经 `WebPageDownloader` 下载提取后可生成文件消息并持久化

## Cross-Client Contract Surface

- WebSocket 消息模型：后端 `WebSocketConfig.kt` 与 `frontend/shared/.../models/Message.kt`
- 文件卡片 payload：后端 `buildFileMessageContent` / 文件路由，与三端 `FileContractsTest`
