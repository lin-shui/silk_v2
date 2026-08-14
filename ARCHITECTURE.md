# Architecture

Silk 是一个以 Kotlin 为主的多端聊天系统：

- 后端：Ktor JVM，承担 HTTP、WebSocket、AI/tool calling、文件路由、导出、Todo、Workflow、Knowledge Base、Audio Duplex 代理、Agent 框架（Claude Code 与 Codex 经 ACP 协议接入）。
- 前端主线：Kotlin Multiplatform + Compose，包含 `frontend/shared`、`webApp`、`androidApp`、`desktopApp`。
- 独立端：`frontend/harmonyApp` 为 ArkTS/ArkUI，未复用 KMP 代码。
- 辅助服务：`search/`（Weaviate 相关脚本，主线已由 Claude 原生 web_search + 后端 grep 替代）、`cc_bridge/`（Claude CLI ACP adapter）、`codex_bridge/`（Codex CLI ACP adapter）、`feishu_bot/`（飞书网关）。

## Primary Runtime Flow

1. 前端通过 HTTP + `/chat` WebSocket 连接后端。
2. `Application.kt` 安装基础 Ktor 插件并调用 `configureWebSockets()`、`configureRouting()`。
3. `Routing.kt` 是 HTTP 总入口；`routes/RoomRoutes.kt`、`FileRoutes.kt`、`AsrRoutes.kt` 等专项路由已拆分为独立文件。
4. `WebSocketConfig.kt` 内的 `ChatServer` 是聊天主链：
   - 权限校验
   - 历史回放
   - 消息持久化
   - 未读计数
   - URL/PDF 下载提取
   - Agent 框架（Claude Code / Codex）拦截：`AgentRuntime.handleIfActive()`
   - Silk AI / `DirectModelAgent` 响应
5. Claude Code / Codex 的 ACP adapter 由 `silk-agent` 托管，通过本地 Host IPC v2 接入单一 `/agent-connect` 设备 WSS；兼容 `/agent-bridge` 只接受设备签名旧 Host。外部 `cc_bridge/acp_adapter.py` 和 `codex_bridge/codex_adapter.py` 跑各自 CLI 并流式回传。
   - 新设备认证底座已新增 `/api/agent-pairings*` 与 `/agent-connect`：初次请求在出码前把 `--account` 登录名解析为 owner userId，非目标账号对短码统一得到 not-found；返回的 `/device#code=...` 链接只在浏览器 fragment 中携带短码，Web 登录后自动预览但仍要求用户明确批准。设备以 raw Ed25519 公钥完成 proof，后续连接使用短时一次性 challenge-response。未配置 canonical 公网地址时认证 origin 取 Host `--server`，不使用后端内网监听地址；Web/API 分 origin 时由部署配置或 Host `--web` 明确提供验证页 origin。已登记设备可通过 `POST /api/agent-pairings/agents` 提交覆盖 Agent 元数据的一次性设备签名请求，仍需 owner 在 Web 明确批准才创建第二个 Agent。配对码尝试按来源与登录主体做节点内存限速，限速桶有周期清理与硬上限；创建新配对时会清理过期超过 7 天的配对事务，安全审计事件不随之删除。
   - `/agent-connect` 已在一次设备签名认证后承载版本化 `agent_open / agent_rpc / agent_close` envelope，并按 `agentInstanceId` 多路复用 Claude Code 与 Codex ACP 逻辑流；每个流独立做 Agent 状态、能力、Binding 和权限检查。`/agent-bridge` 仅保留旧 Host 设备签名兼容握手，query Token 明确拒绝，`/ccconnect-bridge` 仍使用旧群组 Token。
   - 仓库内 `silk-agent/` 是独立 Go companion Host：负责设备密钥、配对、已登记设备新增 Agent 的签名请求/热加载、单一设备 WSS、心跳/重连、前台/后台运行、增量日志跟随、当前用户级 systemd/LaunchAgent/Scheduled Task 服务安装、受保护的 Unix/Windows 控制 IPC、系统凭据存储、加密身份备份/轮换，以及受控 Adapter 子进程监督。Windows Scheduled Task 通过显式 `run --config-dir` 固定实际 profile；Windows profile 目录启动时会移除继承 ACL 并收紧到当前用户、SYSTEM 与 Administrators，若管理员提升进程创建的目录 owner 是 Administrators 则先安全转交给当前用户，其他普通账号 owner 仍拒绝，私密输入文件按 owner/DACL fail-closed 校验。Windows 受管 Adapter 使用 `CREATE_NO_WINDOW` 运行，不弹出可见的 Python 控制台窗口。Host IPC v2 只双向转发 ACP 对象，Adapter 不接触设备签名接口、Silk 凭据或后端 WSS；被撤销身份不会无限重连，所有启用身份都被撤销时 Host 终止并提示重新配对。Host 启动前校验 wrapper、入口脚本及所有随包 Python 模块的 owner/写权限，屏蔽 raw CLI I/O 日志并禁止在发行目录写 Python bytecode；六平台签名发行包由 `silk-agent/scripts/package-release.sh` 生成且拒绝 group/other 可写输出。
   - Web `/device` 页面复用现有 JWT 登录，区分新设备/新增 Agent 的配对预览与批准/拒绝，并提供设备与 Agent 在线状态/撤销及 Room/Workspace Binding 增删改、细粒度权限选择和双主体审批。跨所有者 Binding 可由 Agent owner 或目标管理者发起，只有 Agent owner 与 Room HOST/OPERATOR（或 Workspace owner）均批准后才进入 ACTIVE；设备签名 Agent 的 Room TEAM、Workspace prompt、带 Workspace 目标的目录读取/切换和 Source Control RPC 均按当前连接的精确 `agentInstanceId` 检查 ACTIVE Binding、权限与声明能力。每次受管 prompt 还要求 `EXECUTION_POLICY_V1` 能力并携带版本化 `_silk.executionPolicy`。Claude Code 与 Codex 继承设备用户的原生认证、模型、hooks、MCP、插件和规则；Silk 只对核心本地文件/命令工具增加 Binding 权限上限，Claude 使用显式 deny、工作区路径检查和完整权限下的原生 Bash sandbox，Codex 使用 `read-only/workspace-write` sandbox 与 shell gate，受管路径不启用 dangerous bypass。缺少版本能力的旧登记 Agent 会拒绝 prompt 并需升级后重新登记。Ktor 的同名固定路由会在 Nginx fallback 场景返回已构建 SPA `index.html`。
6. SQLite `groups.room_kind` 以 `CHAT / WORKFLOW / SILK_PRIVATE` 显式区分 Room，`groups.last_message_at` 独立记录最近一条持久化消息时间；启动迁移器分别从 Workflow/Workspace 元数据和历史消息幂等回填旧数据。`Group.name` 是 Room 展示名权威，Workflow 名称仅作旧数据兼容；运行时不再根据 `wf_` 或 `[Silk]` 名称前缀判断类型，也不以重命名等元数据更新时间代替消息活跃时间。
7. Workflow Room 复用 Group（`roomId = groupId`），消息显式分为 `TEAM` 与 `WORKSPACE(roomId, workspaceId)`；TEAM 始终对 Room 成员广播，只有以 `@Silk` 开头的用户消息才进入 `DirectModelAgent`，WORKSPACE 经 Owner/Co-pilot 鉴权后进入 `AgentRuntime(ownerId, workspaceId)`。
8. `WorkspaceManager` 在 `workspace_store.json` 持久化工作目录、active agent、权限模式、per-agent session、可见性、最后共享名称、Co-pilot 和 `ACTIVE/ARCHIVED` 生命周期；`workflow_store.json` 暂保留工作流入口元数据并作为一次性迁移源。Workflow Room 连接只恢复已显式创建且 cwd 非空的活动工作区，不会隐式创建默认编码工作区。SHARED 转 PRIVATE 后，Observer 的历史入口只返回 Owner 与最后共享名称，不暴露私有阶段名称或 runtime 元数据。
9. Web 端以单一“会话”入口消费 `GET /api/rooms/visible`，Silk 专属会话固定置顶，其余普通聊天和工作群组按最近活动倒排：有消息时使用最后消息时间，无消息时使用创建时间。当前 Room 的持久化消息通过已有 WebSocket 在本地立即更新排序，15 秒 Room 列表轮询仅作为其他 Room、跨设备活动和未读状态的兜底；侧栏 `+` 统一承载创建群组和通过邀请码加入，Room 行菜单按 JWT 角色提供邀请、重命名、退出和删除，操作成功后本地立即更新列表。联系人位于主导航底部、设置齿轮上方，退出登录位于设置页账户区。普通聊天与 Workflow Team Channel 共用 `@Silk` 快捷按钮并触发同一 `TEAM` Silk AI 链路，Workspace 则继续使用独立的编码 Agent 链路；Room 详情共同复用 header、图标操作按钮、上下文/快捷区、输入行和发送/停止按钮几何，普通聊天与 Team Channel 还共用 Room 级会话文件、Markdown/Obsidian 导出、邀请、单一成员入口和成员面板，以及目录上传、文件上传、截屏和语音输入工具；添加成员收敛到成员面板内部，两类 Room 均先展示未入群联系人并可搜索其他用户，成员搜索允许单字符输入并按精确度进行模糊排序，管理权限仍由 Room 类型决定。Workspace Agent 不展示依赖 Room 文件空间的四项工具，聊天多选与 Workspace Agent 控制仍由各自主体提供。桌面 Room 侧栏和工作群组树可折叠并持久化偏好，`<= 1100px` 的列表/详情单页布局会忽略侧栏偏好并保持列表完整可用。
10. `Routing.kt` 另提供 `/ws/audio-duplex`，把客户端音频双工 WebSocket 代理到 `AIConfig.AUDIO_DUPLEX_URL` 上游 Worker。
11. `frontend/shared` 定义多端共享消息模型、Room 合同、WebSocket 客户端行为与解析逻辑。

## Persistent State

- SQLite：`./silk_database.db`（可用 `-Dsilk.databasePath=...` 覆盖）
- 外部 Agent 身份：SQLite `agent_devices`、`agent_instances`、`agent_bindings`、`agent_binding_audit_events`、`agent_pairing_requests`；Binding 的安全相关状态变化写入不可变快照审计，设备表只保存 Ed25519 公钥，配对短码和轮询 secret 只保存 SHA-256 摘要。
- silk-agent 本地身份：用户配置目录下的 `device_key`（raw Ed25519 私钥；Unix 0600，Windows DPAPI/当前用户 ACL）、`config.json`（Unix 0600，分别保存连接 `serverOrigin` 与签名绑定 `authenticationOrigin`）、`host.pid`、`host.sock`（Unix，0600）和 `host.log`（Unix 0600）；Unix profile 目录为 0700，Windows profile 目录使用受保护 DACL，只允许当前用户、SYSTEM 与 Administrators，并让子文件继承。Windows 的备份口令与发行私钥输入要求当前用户 owner，允许型 DACL 仅可包含当前用户、SYSTEM、Administrators 与 owner 占位主体。私钥不进入 HTTP/WSS 或 Adapter。受控 Adapter 通过 stdin/stdout JSON-RPC 接收一次性 nonce 和固定 `agentInstanceId`，不接收 Silk 凭据。
- PostgreSQL（可选）：通过 `docker-compose-pg.yml` 启动，`SILK_KB_STORE=postgres` 切换 KB 主存储为 PostgreSQL + pgvector；`POST /api/admin/kb/migrate-to-pg` 从 JSON store 迁移
- 聊天历史：`chat_history/<session>/session.json`、`chat_history.json`
- 上传文件：`chat_history/<session>/uploads/`
- AI 工作区（跨群上下文）：`backend/chat_workspaces/<session>/other_groups/`
- URL 去重缓存：`processed_urls.txt`
- 用户历史 workspace 视图：`user_workspace_views/user_<user>/`（消息历史按 TEAM/WORKSPACE ACL 生成过滤副本；`session.json` 可使用 hardlink）
- AI 跨群工作区：`backend/chat_workspaces/<session>/other_groups/`（Silk 专属对话按调用者消息 ACL 写入其他群最近历史）
- 用户 Todo：`chat_history/user_todos/<user>.json`
- Workflow 元数据：`~/.silk-data/workflows/workflow_store.json`
- Personal Workspace 运行状态：`~/.silk-data/workflows/workspace_store.json`（二者均可用 `SILK_WORKFLOW_DIR` 或 `-Dsilk.workflowDir=...` 覆盖）
- TrustedDir：`~/.silk-data/workflows/trusted_dirs.json`（与 Workflow 目录同源）
- Knowledge Base：`knowledge_base/kb_store.json`（Topic / Entry CRUD；个人/团队空间 + 访问控制 `KBAccessPolicy`；条目状态/来源；user memory 复用 KB store，以 `purpose=MEMORY` 的个人 topic 保存 `profile` / `preference` / `episodic` / `procedural` 条目；group memory 以 `purpose=MEMORY` + `spaceType=TEAM` + `groupId` 的团队 topic 保存团队共享记忆；`[[kb:...]]` 内联引用 + 固定/排除/自动检索的上下文选择 `kbContextSelection`，以及可关闭的长期 memory 注入（含个人与群组双层记忆），统一由 `resolveKnowledgeBasePromptContext` 注入 AI 上下文）
- Knowledge Base AI bridge：聊天主链会把当前用户可读的 KB 条目同步到 agent workspace `knowledge_base/manifest.md` + `knowledge_base/topics/**`，供 `DirectModelAgent` 与 ACP 外部 agent 通过 Grep/Read 自主查阅；当模型在回复末尾输出 `silk_kb_action` JSON block 时，后端会按当前用户权限执行 KB create/update，并把结果回写到最终回复；该后处理现在同时覆盖内建 Silk AI 与 `AgentRuntime` 承载的外部 agent 回复。若当前会话属于 workflow，会补齐 `workflowId + sourceGroupId + recentMessageIds` provenance，且未显式指定 `sourceType` 的 KB create 默认按 `WORKFLOW` 候选入库；Web KB 页另有 `POST /api/kb/copilot`（同步）和 `POST /api/kb/copilot/stream`（SSE 流式），复用同一套 `DirectModelAgent + silk_kb_action` 流程，支持条目级（`update_entry`）和主题级（`create_entry`）两种模式；`entryId` 可选填，为空时自动进入主题级模式；还支持多轮对话（`ConversationTurn`），`conversationHistory` 注入历史轮次引导 AI 基于上下文响应当前指令。流式端点通过 SSE 事件（`thinking`/`text`/`draft`/`applied`/`error`/`done`）逐帧推送 AI 生成进度，前端 `ApiClient.streamKBCopilot()` 消费并实时展示打字机效果。前端 `KnowledgeCopilotSidebar` 按三状态（INPUT/PREVIEW/REVIEW）渲染，每个状态一个主按钮，审阅控制移至侧栏而非编辑区。当用户显式发送“记住 xxx”时，聊天主链会先写入长期 memory；当用户打开 `autoCaptureEnabled` 时，主链还会从低风险偏好指令中自动提取 `response_language` / `response_style` / `code_language_preference` / `tech_stack_preference` / `output_format_preference`，并通过 `containsSensitiveContent` 过滤敏感内容；随后再按偏好开关决定是否把相关 memory 一并注入本轮 prompt。KB 搜索已支持嵌入向量语义检索（`kb/KnowledgeBaseEmbedding.kt`），通过 `EMBEDDING_ENABLED` + `EMBEDDING_API_KEY` 启用后，搜索从纯关键词升级为混合评分（关键词分 × α + 向量分 × β），嵌入缓存在 `kb_embeddings.json` 侧边文件。KB 存储后端支持 JSON file store（默认）和 PostgreSQL + pgvector（`SILK_KB_STORE=postgres`），通过 `KnowledgeBaseManager.storeBackend` 路由到 `PgKnowledgeBaseRepository`（JDBC 原生 SQL + pgvector ANN 混合检索）
- Web 静态产物/APK/HAP 分发：`backend/static/`

## Code Surfaces By Responsibility

| Surface | Primary Paths | Notes |
| --- | --- | --- |
| App/bootstrap | `Application.kt`, `settings.gradle.kts`, root `build.gradle.kts`, `silk.sh` | 运行入口与构建编排 |
| HTTP routes | `Routing.kt`, `routes/RoomRoutes.kt`, `routes/FileRoutes.kt`, `routes/AsrRoutes.kt`, `routes/AgentChangesRoutes.kt`, `routes/ObsidianRoutes.kt` | `RoomRoutes` 提供统一 Room 发现/创建；`Routing.kt` 仍然是其他路由的主索引点 |
| Obsidian integration | `obsidian-plugin/silk-sync/` | Obsidian 插件，一键同步 Silk 聊天记录和 KB 条目到 vault |
| Chat/WebSocket | `WebSocketConfig.kt`, `ChatHistoryManager.kt`, `workspace/WorkspaceAccessPolicy.kt` | 消息主链、scope ACL、历史、URL 下载 |
| Agent framework | `agents/core/`, `agents/acp/`, `agents/adapters/`, `agents/auth/`, `routes/AgentAuthRoutes.kt` | Claude Code 与 Codex 的既有执行路径仍走 ACP；`agents/auth/` 提供新设备/Agent 配对、签名合同、持久化、Binding 授权与撤销底座 |
| silk-agent Host | `silk-agent/` | 独立 Go companion；提供 DeviceSigner、系统凭据/加密备份、配对、单一设备 WSS、`agentInstanceId` 多路复用、重连、本地 Host 控制、受控 Adapter ProcessSupervisor/stdio IPC v2、密钥轮换和签名发行包 |
| AI/tools/search | `ai/`（AnthropicClient + DirectModelAgent）, `utils/WebPageDownloader.kt` | Anthropic Messages API + 原生 web_search 工具 + 后端 grep 搜索 |
| Auth/data | `auth/`, `database/`, `models/` | SQLite + Exposed |
| Card system | `card/CardBuilder.kt`, `card/CardReplyRouter.kt`, `card/CardModels.kt` | 交互卡片构造、JSON schema、回复路由 |
| Domain modules | `todos/`, `workflow/`, `workspace/`, `trust/`, `kb/`, `git/`, `export/`, `pdf/` | Todo/Workflow/PersonalWorkspace/TrustedDir/KB/GitHub integration（GitHub 支持 Webhook/Polling 双接收；含 `[[kb:...]]` 内联引用）混合文件存储 + 可选 PostgreSQL（`SILK_KB_STORE=postgres`） |
| Shared client contract | `frontend/shared/` | 三端消息/文件/Audio Duplex 合同面 |
| Web | `frontend/webApp/` | 当前最完整的桌面浏览器 UI；`DeviceManagementScene.kt` 提供 `/device` 配对与外部 Agent 管理 |
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
