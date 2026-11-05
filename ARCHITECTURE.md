# Architecture

Silk 是一个以 Kotlin 为主的多端聊天系统：

- 后端：Ktor JVM，承担 HTTP、WebSocket、AI/tool calling、文件路由、导出、Todo、Knowledge Base、Audio Duplex 代理。
- 前端主线：Kotlin Multiplatform + Compose，包含 `frontend/shared`、`webApp`、`androidApp`、`desktopApp`。
- 独立端：`frontend/harmonyApp` 为 ArkTS/ArkUI，未复用 KMP 代码。
- 辅助服务：`search/`（Weaviate 相关脚本，主线已由 Claude 原生 web_search + 后端 grep 替代）、`feishu_bot/`（飞书网关）。

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
   - Silk AI / `DirectModelAgent` 响应
5. `Routing.kt` 另提供 `/ws/audio-duplex`，把客户端音频双工 WebSocket 代理到 `AIConfig.AUDIO_DUPLEX_URL` 上游 Worker。
6. `frontend/shared` 定义多端共享消息模型、WebSocket 客户端行为、解析逻辑。

## Persistent State

- SQLite：`./silk_database.db`（可用 `-Dsilk.databasePath=...` 覆盖）
- PostgreSQL（可选）：通过 `docker-compose-pg.yml` 启动，`SILK_KB_STORE=postgres` 切换 KB 主存储为 PostgreSQL + pgvector；`POST /api/admin/kb/migrate-to-pg` 从 JSON store 迁移
- 聊天历史：`chat_history/<session>/session.json`、`chat_history.json`
- 上传文件：`chat_history/<session>/uploads/`
- AI 工作区（跨群上下文）：`backend/chat_workspaces/<session>/other_groups/`
- URL 去重缓存：`processed_urls.txt`
- 用户历史 workspace 视图：`user_workspace_views/user_<user>/`（通过 hardlink 映射该用户可访问的 `chat_history/group_<group>/`，供 `/recall` 只读检索）
- AI 跨群工作区：`backend/chat_workspaces/<session>/other_groups/`（Silk 专属对话写入其他群最近历史，供 AI Grep/Read 跨群检索）
- 用户 Todo：`chat_history/user_todos/<user>.json`
- Knowledge Base：`knowledge_base/kb_store.json`（Topic / Entry CRUD；个人/团队空间 + 访问控制 `KBAccessPolicy`；条目状态/来源；user memory 复用 KB store，以 `purpose=MEMORY` 的个人 topic 保存 `profile` / `preference` / `episodic` / `procedural` 条目；group memory 以 `purpose=MEMORY` + `spaceType=TEAM` + `groupId` 的团队 topic 保存团队共享记忆；`[[kb:...]]` 内联引用 + 固定/排除/自动检索的上下文选择 `kbContextSelection`，以及可关闭的长期 memory 注入（含个人与群组双层记忆），统一由 `resolveKnowledgeBasePromptContext` 注入 AI 上下文）
- Knowledge Base AI bridge：聊天主链会把当前用户可读的 KB 条目同步到 agent workspace `knowledge_base/manifest.md` + `knowledge_base/topics/**`，供 `DirectModelAgent` 通过 Grep/Read 自主查阅；当模型在回复末尾输出 `silk_kb_action` JSON block 时，后端会按当前用户权限执行 KB create/update，并把结果回写到最终回复；该后处理覆盖内建 Silk AI 的回复（`KBSourceType.WORKFLOW` 仅作为历史条目兼容值保留）；Web KB 页另有 `POST /api/kb/copilot`（同步）和 `POST /api/kb/copilot/stream`（SSE 流式），复用同一套 `DirectModelAgent + silk_kb_action` 流程，支持条目级（`update_entry`）和主题级（`create_entry`）两种模式；`entryId` 可选填，为空时自动进入主题级模式；还支持多轮对话（`ConversationTurn`），`conversationHistory` 注入历史轮次引导 AI 基于上下文响应当前指令。流式端点通过 SSE 事件（`thinking`/`text`/`draft`/`applied`/`error`/`done`）逐帧推送 AI 生成进度，前端 `ApiClient.streamKBCopilot()` 消费并实时展示打字机效果。前端 `KnowledgeCopilotSidebar` 按三状态（INPUT/PREVIEW/REVIEW）渲染，每个状态一个主按钮，审阅控制移至侧栏而非编辑区。当用户显式发送“记住 xxx”时，聊天主链会先写入长期 memory；当用户打开 `autoCaptureEnabled` 时，主链还会从低风险偏好指令中自动提取 `response_language` / `response_style` / `code_language_preference` / `tech_stack_preference` / `output_format_preference`，并通过 `containsSensitiveContent` 过滤敏感内容；随后再按偏好开关决定是否把相关 memory 一并注入本轮 prompt。KB 搜索已支持嵌入向量语义检索（`kb/KnowledgeBaseEmbedding.kt`），通过 `EMBEDDING_ENABLED` + `EMBEDDING_API_KEY` 启用后，搜索从纯关键词升级为混合评分（关键词分 × α + 向量分 × β），嵌入缓存在 `kb_embeddings.json` 侧边文件。KB 存储后端支持 JSON file store（默认）和 PostgreSQL + pgvector（`SILK_KB_STORE=postgres`），通过 `KnowledgeBaseManager.storeBackend` 路由到 `PgKnowledgeBaseRepository`（JDBC 原生 SQL + pgvector ANN 混合检索）
- Web 静态产物/APK/HAP 分发：`backend/static/`

## Code Surfaces By Responsibility

| Surface | Primary Paths | Notes |
| --- | --- | --- |
| App/bootstrap | `Application.kt`, `settings.gradle.kts`, root `build.gradle.kts`, `silk.sh` | 运行入口与构建编排 |
| HTTP routes | `Routing.kt`, `routes/FileRoutes.kt`, `routes/AsrRoutes.kt`, `routes/ObsidianRoutes.kt`, `routes/DshBotRoutes.kt` | `Routing.kt` 仍然很大，是主索引点；`ObsidianRoutes` 提供 `/api/obsidian/sync` 一键导出；`DshBotRoutes` 是 dsh-superagent 集成 bot 的 HTTP 面 |
| Obsidian integration | `obsidian-plugin/silk-sync/` | Obsidian 插件，一键同步 Silk 聊天记录和 KB 条目到 vault |
| Chat/WebSocket | `WebSocketConfig.kt`, `ChatHistoryManager.kt`, `AgentIdentity.kt` | 消息主链、历史、URL 下载、AI 消息身份判定 |
| AI/tools/search | `ai/`（AnthropicClient + DirectModelAgent）, `utils/WebPageDownloader.kt` | Anthropic Messages API + 原生 web_search 工具 + 后端 grep 搜索 |
| Auth/data | `auth/`, `database/`, `models/` | SQLite + Exposed |
| Card system | `card/CardBuilder.kt`, `card/CardReplyRouter.kt`, `card/CardModels.kt` | 交互卡片构造、JSON schema、回复路由 |
| Domain modules | `todos/`, `kb/`, `export/`, `pdf/` | Todo/KB（含 `[[kb:...]]` 内联引用）混合文件存储 + 可选 PostgreSQL（`SILK_KB_STORE=postgres`） |
| Shared client contract | `frontend/shared/` | 三端消息/文件/Audio Duplex 合同面 |
| Web | `frontend/webApp/` | 当前最完整的桌面浏览器 UI |
| Android | `frontend/androidApp/` | 四 Tab + 移动端流程 |
| Desktop | `frontend/desktopApp/` | 可编译/可测试，但能力面窄于 Web/Android |
| Harmony | `frontend/harmonyApp/` | 独立 ArkTS 应用，含 Todo/KB/Audio Duplex |
| External bridges | `backend/scripts/`（PTY bridge）, `feishu_bot/` | Python 服务，不在 Gradle 主工程内 |

## Removed: cc-connect

cc-connect 集成（`/ccconnect-bridge` WebSocket、`ccconnect/` 包、按角色/前缀转发、图片转发与卡片聚合）已于 2026-11 删除：多人协作编排统一交给外部 dsh-superagent（见下节），Silk 只保留 IM 本体与内建 Silk AI。历史实现可查 git 历史与 `docs/context/planning/exec-plans/2026-11-03-dsh-superagent-im-pivot-plan.md`。

## Removed: ACP agents and workflows

Claude Code / Codex 的 ACP 接入（`/agent-bridge`、`agents/` 包、`cc_bridge/`、`codex_bridge/`）与 Workflow/TrustedDir/Source Control 编排面已于 2026-11 删除。Silk 不再托管任何外部 coding agent 会话，也不再提供工作流、目录信任或代码审查 API；编写代码的工作由 dsh-superagent 的 Executor 在 Silk 之外完成。

## dsh-superagent Integration

多人协作编排由外部 [dsh-superagent-prototype](https://github.com/lin-shui/dsh-superagent-prototype) 承担；Silk 回归 IM 本体，仅以集成 bot 身份作为 dsh 的一个 Source。设计决策与阶段计划见 `docs/context/planning/exec-plans/2026-11-03-dsh-superagent-im-pivot-plan.md`。

- bot 是固定身份的用户 `dsh_ai_agent`（显示名 DSH，不可登录），以 GUEST 角色按邀请码入群；谁能发起工作由 dsh 侧 Policy 决定，silk 不赋予触发权。
- `routes/DshBotRoutes.kt` 提供 HTTP 面：token 生命周期（`dshbot_tokens` 表，单有效 token）、`me`/`join`、按时间游标拉群消息、幂等发消息（`dsh_<idempotencyKey>` 消息 ID 与 `ChatServer.broadcast` 去重同源）。
- bot 消息不触发 Silk AI：`AgentIdentity.isAgentUserId`（`backend/src/main/kotlin/com/silk/backend/AgentIdentity.kt`）白名单含 `DSH_BOT_USER_ID`。
- 映射约定：一个 silk 群 = 一个 dsh Thread；群消息 = Trigger；回复走幂等 Channel send。

## Context Docs

- 上下文维护契约： [docs/context/INDEX.md](docs/context/INDEX.md)
- 最小任务路由： [docs/context/TASK_ROUTER.md](docs/context/TASK_ROUTER.md)
- 项目级上下文： [docs/context/project/BOOTSTRAP.md](docs/context/project/BOOTSTRAP.md)
- 后端深挖： [docs/context/modules/backend/INDEX.md](docs/context/modules/backend/INDEX.md)
- 前端深挖： [docs/context/modules/frontend/INDEX.md](docs/context/modules/frontend/INDEX.md)
- 辅助集成： [docs/context/integrations/SEARCH_AND_AUX_SERVICES.md](docs/context/integrations/SEARCH_AND_AUX_SERVICES.md)
- 质量门禁： [docs/context/quality/INDEX.md](docs/context/quality/INDEX.md)
- 规划治理： [docs/context/planning/INDEX.md](docs/context/planning/INDEX.md)
- 定期清查记录： [docs/context/project/PERIODIC_AUDIT.md](docs/context/project/PERIODIC_AUDIT.md)
- Agent 提交/PR workflow skill： [docs/skills/local-change-submit/SKILL.md](docs/skills/local-change-submit/SKILL.md)
