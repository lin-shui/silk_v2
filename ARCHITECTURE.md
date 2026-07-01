# Architecture

Silk 是一个以 Kotlin 为主的多端聊天系统：

- 后端：Ktor JVM，承担 HTTP、WebSocket、AI/tool calling、文件路由、导出、Todo、Workflow、Knowledge Base、Audio Duplex 代理、Agent 框架（Claude Code 与 Codex 经 ACP 协议接入）。
- 前端主线：Kotlin Multiplatform + Compose，包含 `frontend/shared`、`webApp`、`androidApp`、`desktopApp`。
- 独立端：`frontend/harmonyApp` 为 ArkTS/ArkUI，未复用 KMP 代码。
- 辅助服务：`search/`（Weaviate 相关脚本，主线已由 Claude 原生 web_search + 后端 grep 替代）、`cc_bridge/`（Claude CLI ACP adapter）、`codex_bridge/`（Codex CLI ACP adapter）、`feishu_bot/`（飞书网关）。

## Primary Runtime Flow

1. 前端通过 HTTP + `/chat` WebSocket 连接后端。
2. `Application.kt` 安装基础 Ktor 插件并调用 `configureWebSockets()`、`configureRouting()`。
3. `Routing.kt` 是 HTTP 总入口；`routes/FileRoutes.kt`、`routes/AsrRoutes.kt` 已从大文件中拆出。
4. `WebSocketConfig.kt` 内的 `ChatServer` 是聊天主链：
   - 权限校验
   - 历史回放
   - 消息持久化
   - 未读计数
   - URL/PDF 下载提取
  - 知识库上下文构建（手动 `[[kb:entryId|标题]]` 引用优先，支持消息级固定/排除条目与空间级自动召回开关，再叠加当前可访问 KB 的自动 lexical 候选 → 本轮 AI 上下文 + `[available:N]`）
   - Agent 框架（Claude Code / Codex）拦截：`AgentRuntime.handleIfActive()`
   - Silk AI / `DirectModelAgent` 响应
5. Claude Code / Codex 通过 ACP 协议（JSON-RPC 2.0 over WebSocket）连接 `/agent-bridge` 端点；外部 `cc_bridge/acp_adapter.py` 和 `codex_bridge/codex_adapter.py` adapter 跑各自 CLI 并流式回传。
6. Workflow 持久化每条工作流的 `activeAgent` 与 per-agent `agentSessions[agentType]`；进入工作流时 `autoActivateForWorkflow` 读 `workflow.activeAgent`（不再硬编码 claude-code），用户 `/use <agent>` 切换会落盘。
7. `Routing.kt` 另提供 `/ws/audio-duplex`，把客户端音频双工 WebSocket 代理到 `AIConfig.AUDIO_DUPLEX_URL` 上游 Worker。
8. `frontend/shared` 定义多端共享消息模型、WebSocket 客户端行为、解析逻辑。

## Persistent State

- SQLite：`./silk_database.db`（可用 `-Dsilk.databasePath=...` 覆盖）
  - `user_settings` 现同时承载用户设置、`app_auth_token`（HTTP bearer caller）与 `cc_bridge_token`
- 聊天历史：`chat_history/<session>/session.json`、`chat_history.json`
- 上传文件：`chat_history/<session>/uploads/`
- AI 工作区（跨群上下文）：`backend/chat_workspaces/<session>/other_groups/`
- URL 去重缓存：`processed_urls.txt`
- 用户历史 workspace 视图：`user_workspace_views/user_<user>/`（通过 hardlink 映射该用户可访问的 `chat_history/group_<group>/`，供 `/recall` 只读检索）
- AI 跨群工作区：`backend/chat_workspaces/<session>/other_groups/`（Silk 专属对话写入其他群最近历史，供 AI Grep/Read 跨群检索）
- 用户 Todo：`chat_history/user_todos/<user>.json`
- Workflow：`~/.silk-data/workflows/workflow_store.json`（可用 `SILK_WORKFLOW_DIR` 或 `-Dsilk.workflowDir=...` 覆盖）
- TrustedDir：`~/.silk-data/workflows/trusted_dirs.json`（与 Workflow 目录同源）
- Knowledge Base：`knowledge_base/kb_store.json`（Topic / Entry CRUD；个人/团队空间 + 访问控制 `KBAccessPolicy`；条目状态/来源；`[[kb:...]]` 内联引用 + 固定/排除/自动检索的上下文选择 `kbContextSelection`，由 `resolveKnowledgeBasePromptContext` 注入 AI 上下文）
- Web 静态产物/APK/HAP 分发：`backend/static/`

## Code Surfaces By Responsibility

| Surface | Primary Paths | Notes |
| --- | --- | --- |
| App/bootstrap | `Application.kt`, `settings.gradle.kts`, root `build.gradle.kts`, `silk.sh` | 运行入口与构建编排 |
| HTTP routes | `Routing.kt`, `routes/FileRoutes.kt`, `routes/AsrRoutes.kt`, `routes/AgentChangesRoutes.kt` | `Routing.kt` 仍然很大，是主索引点；`AgentChangesRoutes` 是只读代码审查（Source Control）路由 |
| Chat/WebSocket | `WebSocketConfig.kt`, `ChatHistoryManager.kt` | 消息主链、历史、URL 下载 |
| Agent framework | `agents/core/`, `agents/acp/`, `agents/adapters/` | Claude Code 与 Codex via ACP，唯一执行路径 |
| AI/tools/search | `ai/`（AnthropicClient + DirectModelAgent）, `utils/WebPageDownloader.kt` | Anthropic Messages API + 原生 web_search 工具 + 后端 grep 搜索 |
| Auth/data | `auth/`, `database/`, `models/` | SQLite + Exposed |
| Card system | `card/CardBuilder.kt`, `card/CardReplyRouter.kt`, `card/CardModels.kt` | 交互卡片构造、JSON schema、回复路由 |
| Domain modules | `todos/`, `workflow/`, `trust/`, `kb/`, `export/`, `pdf/` | Todo/Workflow/TrustedDir/KB（含 `[[kb:...]]` 内联引用）混合文件存储 |
| Shared client contract | `frontend/shared/` | 三端消息/文件/Audio Duplex 合同面 |
| Web | `frontend/webApp/` | 当前最完整的桌面浏览器 UI |
| Android | `frontend/androidApp/` | 四 Tab + 移动端流程 |
| Desktop | `frontend/desktopApp/` | 可编译/可测试，但能力面窄于 Web/Android |
| Harmony | `frontend/harmonyApp/` | 独立 ArkTS 应用，含 Todo/Workflow/KB/Audio Duplex |
| External bridges | `backend/scripts/`（PTY bridge）, `cc_bridge/`, `codex_bridge/`, `feishu_bot/` | Python 服务，不在 Gradle 主工程内 |

## cc-connect Integration

Silk 支持通过 [cc-connect](https://github.com/chenhg5/cc-connect) 连接外部 AI 编程代理（Claude Code / Cursor / Gemini CLI / Codex 等）。

**架构**：cc-connect 通过原生 silk 平台插件（位于 [cc-connect 仓库](https://github.com/chenhg5/cc-connect) 的 `platform/silk/silk.go`）连接到 Silk 后端的 `/ccconnect-bridge` WebSocket 端点。不需要中间适配器进程。

**用户流程**：
1. Silk 前端创建 cc-connect 群组 → 后端生成 token
2. 将 token 贴入 cc-connect 的 `config.toml`
3. cc-connect 启动后自动连接 Silk 对应群组
4. 群组内的用户消息按角色路由：仅 HOST / OPERATOR 的消息转发到 cc-connect 代理，GUEST 消息不触发命令；多人群中 HOST/OPERATOR 需用 `@<agent>` 前缀触发（如 `@claude`、`@cursor`，通用 `@cc` 始终可用）；单人时直接转发

**消息聚合**：Go 插件实现 cc-connect 的 `StreamingCardPlatform` 接口，将整个 agent turn（thinking、tool use、text）聚合为单条可更新消息。中间状态通过 `reply_stream`（`incremental: false`）做全量替换，最终回复走 `reply` 持久化；若单条 `reply_stream` 正文合并包含思考/工具 emoji，后端按 emoji 边界拆分回填各段。后端在 `thinking` 状态起进入「分阶段 turn」：按 emoji 前缀把 `reply` 归入思考/工具/正文，拼成带 `<!--CC_TURN-->` 与 `<!--THINKING_END-->` 的结构化 Markdown 广播；Web 端识别该标记展示临时气泡，并在 Markdown 管线中剥离标记。最终落盘消息在工具段后插入 `<!--TOOLS_END-->`，Web 将工具调用折叠为与思考区类似的 `<details>`；折叠块内工具原文经 markdown-it 渲染后再 DOMPurify 消毒。

**图片转发**：cc-connect 群组内上传图片时，若图片说明以 `@claude`/`@cc` 前缀开头，则绕过 Silk 自带 vision 管线，将图片 base64 编码后通过 `UserMessage.images` 字段转发给 cc-connect 的 Claude Agent 处理。`UserMessage` 协议现已扩展 `images: [{mime_type, data(base64), file_name}]`。单人模式（群组仅 1 名成员）下无需 `@` 前缀，所有图片自动转到 cc-connect。无前缀的多人群图片仍走 Silk 自带 vision。若 cc-connect 未连接则自动降级为 Silk vision。

**回复图片**：cc-connect Agent 生成图片（SVG/PNG 等，通过 Write/Bash 等工具）后，`silk.go` 的 `Finalize` 会检测工作目录中本次 turn 内新增/修改的图片文件，base64 编码后通过 `reply_images` 消息发送给 Silk。Silk 后端直接以 Markdown data URI（`![](data:image/png;base64,...)`）广播，前端 Markdown 渲染器原生支持，无需文件服务端点。限制：仅扫描项目根目录（非递归），单次最多 5 张，单张 ≤10MB。

**代码面**：
- 后端：`ccconnect/CcConnectTokenRepository.kt`（token 存储）、`ccconnect/CcConnectRegistry.kt`（连接注册 + `agentTriggerName()` 映射）、`ccconnect/CcConnectProtocol.kt`（协议数据类）
- 路由：`Routing.kt` → `/ccconnect-bridge` WebSocket + token 管理 API
- 消息路由：`WebSocketConfig.kt` → `CcConnectRegistry.isConnected()` + `GroupRepository.getMemberRole()` 角色检查（HOST / OPERATOR 可转发）优先于 AgentRuntime/Silk AI
- 插件：`platform/silk/silk.go`（位于 [cc-connect 仓库](https://github.com/chenhg5/cc-connect)，实现 `StreamingCardPlatform`；本地不再保留副本）

## Context Docs

- 上下文维护契约： [docs/context/INDEX.md](docs/context/INDEX.md)
- 最小任务路由： [docs/context/TASK_ROUTER.md](docs/context/TASK_ROUTER.md)
- 项目级上下文： [docs/context/project/BOOTSTRAP.md](docs/context/project/BOOTSTRAP.md)
- 后端深挖： [docs/context/modules/backend/INDEX.md](docs/context/modules/backend/INDEX.md)
- 前端深挖： [docs/context/modules/frontend/INDEX.md](docs/context/modules/frontend/INDEX.md)
- 辅助集成： [docs/context/integrations/CLAUDE_CODE_AND_BRIDGES.md](docs/context/integrations/CLAUDE_CODE_AND_BRIDGES.md)
- 质量门禁： [docs/context/quality/INDEX.md](docs/context/quality/INDEX.md)
- 规划治理： [docs/context/planning/INDEX.md](docs/context/planning/INDEX.md)
- 定期清查记录： [docs/context/project/PERIODIC_AUDIT.md](docs/context/project/PERIODIC_AUDIT.md)
- Agent 提交/PR workflow skill： [docs/skills/local-change-submit/SKILL.md](docs/skills/local-change-submit/SKILL.md)
