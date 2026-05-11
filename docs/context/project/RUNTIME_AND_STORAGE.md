# Runtime And Storage

## Core Processes

- Backend：Ktor Netty，入口 `backend/.../Application.kt`
- Web 前端：Kotlin/JS dev server 或 backend 静态资源
- Android / Desktop / Harmony：各自原生客户端
- Weaviate（主线不再需要 — 已由 Claude 原生 web_search + 后端 grep 替代；遗留脚本仍可由 `silk.sh` 或 `search/` 管理）
- Audio Duplex Worker：后端 `/ws/audio-duplex` 代理到 `AUDIO_DUPLEX_URL`（默认 `http://localhost:22700`）
- Claude Code ACP Adapter：`cc_bridge/acp_adapter.py`（外部进程，连 backend `/agent-bridge` 端点）
- Codex ACP Adapter：`codex_bridge/codex_adapter.py`（外部进程，连 backend `/agent-bridge` 端点）
- Feishu 网关：`feishu_bot/main.py`

## Persistent Stores

| Store | Path / Override | Used By |
| --- | --- | --- |
| SQLite | `./silk_database.db` or `-Dsilk.databasePath=...` | 用户、群组、联系人、未读、设置 |
| Chat history root | `chat_history/` or `-Dsilk.chatHistoryDir=...` | `ChatHistoryManager`, 文件上传、URL 缓存 |
| Session history | `chat_history/<session>/session.json`, `chat_history.json` | WebSocket 历史回放 |
| Uploaded files | `chat_history/<session>/uploads/` | 文件消息、下载 |
| URL dedupe | `chat_history/<session>/uploads/processed_urls.txt` | URL/PDF 下载去重 |
| User todos | `chat_history/user_todos/*.json` or `-Dsilk.userTodoBaseDir=...` | `UserTodoStore`（兼容旧 `backend/chat_history/user_todos` / `../chat_history/user_todos` 查找） |
| Workflow store | `~/.silk-data/workflows/workflow_store.json`, `SILK_WORKFLOW_DIR`, or `-Dsilk.workflowDir=...` | `WorkflowManager` |
| Trusted directories | `~/.silk-data/workflows/trusted_dirs.json` (co-located with workflow store) | `TrustedDirManager` |
| KB store | `knowledge_base/kb_store.json` or `-Dsilk.kbDir=...` | `KnowledgeBaseManager` |
| Web static / APK / HAP | `backend/static/` | 后端静态分发 |
| Claude Code sessions | `~/.silk/cc_sessions.json` | `cc_bridge/session_manager.py` |
| Codex sessions | `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl` | `codex_bridge/codex_session_index.py` |
| Feishu bindings | `feishu_bot/data/user_bindings.json` | 飞书账号绑定 |

## Chat Session Naming

- 群聊主路径使用 `group_<groupId>`
- `ChatHistoryManager` 会把裸 UUID session 也标准化到 `group_` 前缀
- 文件上传与历史目录都依赖这个命名约定；不要随意修改

## Search / Indexing

- 上传文件后，PDF 文本经 PDFBox 提取保存为 `_text.txt` 供 AI grep 搜索
- 聊天文本消息持久化到 `session.json`
- URL/PDF 链接经 `WebPageDownloader` 下载提取后可生成文件消息并持久化
- AI 搜索由 `DirectModelAgent.searchContext()` 通过 grep 检索 `_text.txt` 和 `session.json`，受 accessibleSessionIds 隔离

## Cross-Client Contract Surface

- WebSocket 消息模型：后端 `WebSocketConfig.kt` 与 `frontend/shared/.../models/Message.kt`
- 文件卡片 payload：后端 `buildFileMessageContent` / 文件路由，与三端 `FileContractsTest`
- Audio Duplex 状态模型：`frontend/shared/.../models/AudioDuplexModels.kt`；Harmony 端手工保持同类状态
