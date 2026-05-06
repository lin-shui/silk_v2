# Architecture

Silk 是一个以 Kotlin 为主的多端聊天系统：

- 后端：Ktor JVM，承担 HTTP、WebSocket、AI/tool calling、文件路由、导出、Todo、Workflow、Knowledge Base、Agent 框架（Claude Code 与 Codex 经 ACP 协议接入）。
- 前端主线：Kotlin Multiplatform + Compose，包含 `frontend/shared`、`webApp`、`androidApp`、`desktopApp`。
- 独立端：`frontend/harmonyApp` 为 ArkTS/ArkUI，未复用 KMP 代码。
- 辅助服务：`search/`（Weaviate 相关脚本）、`cc_bridge/`（Claude CLI ACP adapter）、`codex_bridge/`（Codex CLI ACP adapter）、`feishu_bot/`（飞书网关）。

## Primary Runtime Flow

1. 前端通过 HTTP + `/chat` WebSocket 连接后端。
2. `Application.kt` 安装基础 Ktor 插件并调用 `configureWebSockets()`、`configureRouting()`。
3. `Routing.kt` 是 HTTP 总入口；`routes/FileRoutes.kt`、`routes/AsrRoutes.kt` 已从大文件中拆出。
4. `WebSocketConfig.kt` 内的 `ChatServer` 是聊天主链：
   - 权限校验
   - 历史回放
   - 消息持久化
   - 未读计数
   - Weaviate 索引
   - URL/PDF 下载提取
   - Agent 框架（Claude Code）拦截：`AgentRuntime.handleIfActive()`
   - Silk AI / `DirectModelAgent` 响应
5. Claude Code / Codex 通过 ACP 协议（JSON-RPC 2.0 over WebSocket）连接 `/agent-bridge` 端点；外部 `cc_bridge/acp_adapter.py` 和 `codex_bridge/codex_adapter.py` adapter 跑各自 CLI 并流式回传。
6. Workflow 持久化每条工作流的 `activeAgent` 与 per-agent `agentSessions[agentType]`；进入工作流时 `autoActivateForWorkflow` 读 `workflow.activeAgent`（不再硬编码 claude-code），用户 `/use <agent>` 切换会落盘。
7. `frontend/shared` 定义多端共享消息模型、WebSocket 客户端行为、解析逻辑。

## Persistent State

- SQLite：`./silk_database.db`（可用 `-Dsilk.databasePath=...` 覆盖）
- 聊天历史：`chat_history/<session>/session.json`、`chat_history.json`
- 上传文件：`chat_history/<session>/uploads/`
- URL 去重缓存：`processed_urls.txt`
- 用户 Todo：`chat_history/user_todos/<user>.json`
- Workflow：`workflows/workflow_store.json`
- TrustedDir：`workflows/trusted_dirs.json`
- Knowledge Base：`knowledge_base/kb_store.json`
- Web 静态产物/APK 分发：`backend/static/`

## Code Surfaces By Responsibility

| Surface | Primary Paths | Notes |
| --- | --- | --- |
| App/bootstrap | `Application.kt`, `settings.gradle.kts`, root `build.gradle.kts`, `silk.sh` | 运行入口与构建编排 |
| HTTP routes | `Routing.kt`, `routes/FileRoutes.kt`, `routes/AsrRoutes.kt` | `Routing.kt` 仍然很大，是主索引点 |
| Chat/WebSocket | `WebSocketConfig.kt`, `ChatHistoryManager.kt` | 消息主链、历史、URL 下载 |
| Agent framework | `agents/core/`, `agents/acp/`, `agents/adapters/` | Claude Code 与 Codex via ACP，唯一执行路径 |
| AI/tools/search | `ai/`, `search/`, `utils/WebPageDownloader.kt` | 当前主线是 `DirectModelAgent` |
| Auth/data | `auth/`, `database/`, `models/` | SQLite + Exposed |
| Domain modules | `todos/`, `workflow/`, `kb/`, `export/`, `pdf/` | Todo/Workflow/KB 混合文件存储 |
| Shared client contract | `frontend/shared/` | 三端消息/文件合同面 |
| Web | `frontend/webApp/` | 当前最完整的桌面浏览器 UI |
| Android | `frontend/androidApp/` | 三 Tab + 移动端流程 |
| Desktop | `frontend/desktopApp/` | 可编译/可测试，但能力面窄于 Web/Android |
| Harmony | `frontend/harmonyApp/` | 独立 ArkTS 应用，含 Todo/Workflow/KB |
| External bridges | `cc_bridge/`, `codex_bridge/`, `feishu_bot/` | Python 服务，不在 Gradle 主工程内 |

## Context Docs

- 上下文维护契约： [docs/context/INDEX.md](docs/context/INDEX.md)
- 最小任务路由： [docs/context/TASK_ROUTER.md](docs/context/TASK_ROUTER.md)
- 项目级上下文： [docs/context/project/BOOTSTRAP.md](docs/context/project/BOOTSTRAP.md)
- 后端深挖： [docs/context/modules/backend/INDEX.md](docs/context/modules/backend/INDEX.md)
- 前端深挖： [docs/context/modules/frontend/INDEX.md](docs/context/modules/frontend/INDEX.md)
- 辅助集成： [docs/context/integrations/CLAUDE_CODE_AND_BRIDGES.md](docs/context/integrations/CLAUDE_CODE_AND_BRIDGES.md)
- 质量门禁： [docs/context/quality/INDEX.md](docs/context/quality/INDEX.md)
- 规划治理： [docs/context/planning/INDEX.md](docs/context/planning/INDEX.md)
- Agent 提交/PR workflow skill： [docs/skills/local-change-submit/SKILL.md](docs/skills/local-change-submit/SKILL.md)
