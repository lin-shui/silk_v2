# Domain Storage And Features

## Database-Backed Domains

`database/` + `auth/` 主要承载：

- 用户注册 / 登录
- 群组与成员
- 联系人与好友请求
- 未读计数
- 用户设置（含 Claude Code bridge token、`app_auth_token` 用于前端 Bearer 鉴权）

SQLite 数据库默认在 `./silk_database.db`，测试或特殊运行场景可用 `-Dsilk.databasePath=...` 覆盖。

## File-Backed Domains

- `ChatHistoryManager.kt`:
  - 会话元数据
  - 聊天历史
  - 成员列表
  - uploads 目录协同
- `todos/UserTodoStore.kt`:
  - 用户 Todo JSON 存储
  - 长期模板 / 短期实例生命周期合并
  - 重开、去重、模板实例化
- `workflow/WorkflowManager.kt`:
  - 默认 `~/.silk-data/workflows/workflow_store.json`
  - 可用 `SILK_WORKFLOW_DIR` 或 `-Dsilk.workflowDir=...` 覆盖
  - 基础 CRUD + 工作流 agent 状态持久化（`workingDir` / `activeAgent` / `agentSessions[agentType]`），用于后端重启后无感恢复用户的工作目录、当前 agent 与对话历史
  - `agentSessions[agentType].sessionId` 存对应 adapter 的真实 session id（Claude Code 为 `cliSessionId`，Codex 为 thread id）；旧 `sessionId` / `sessionStarted` 字段保留为当前 agent 的兼容镜像
  - `updateWorkingDir(groupId, workingDir)` / `updateSessionState(groupId, agentType, sessionId, sessionStarted)` / `updateActiveAgent(...)` 由 `AgentRuntime.WorkflowPersistence` 回调驱动；写入跳过等值无变化以省 I/O
- `kb/KnowledgeBaseManager.kt`:
  - `knowledge_base/kb_store.json`
  - Topic / Entry CRUD（Topic 带 `purpose`(GENERAL/MEMORY) + `spaceType`(PERSONAL/TEAM) / `groupId` / `KBAccessPolicy`；Entry 带 `status`(CANDIDATE/PUBLISHED/ARCHIVED/DELETED) / `KBEntrySource` 来源 / 可选 `memory` 元数据，`KBSourceType` 含 `MANUAL/CHAT/AI_RESPONSE/WORKFLOW/MEETING/FILE/URL`，其中 `CHAT`/`AI_RESPONSE`/`WORKFLOW` 强制入库为 `CANDIDATE`）
  - 访问控制贯穿所有 KB 路由：`canReadTopic/canWriteTopic/canManageTopic`、成员可见性、`writeLocked`；调用方身份优先经 `Authorization: Bearer <access_token|app_auth_token>` 解析（先验 JWT access token，再兼容旧 `app_auth_token`），若与 query/body 里的 `userId` 不一致则拒绝请求；无 Bearer 时回退到 `X-Silk-Authenticated-User-Id` 头
  - `searchEntriesForContext(...)`：按用户可见性 + preferredGroup 检索已发布条目，供上下文自动补充；支持 `excludedSpaceIds` 在 space 级屏蔽自动召回
  - `updateEntry(...)` 支持把条目移动到同一 knowledge space 内的其他 topic（personal ↔ personal、同 group team ↔ team）；跨 space move 会被 `canMoveEntryBetweenTopics` 拒绝
  - user memory MVP：复用同一 store，为每个用户懒创建 `purpose=MEMORY` 的个人 topic；`captureExplicitMemory(...)` 按 `profile/preference/episodic/procedural` 写入/覆写显式长期记忆；`captureAutoMemory(...)` 以 key 为单位覆写低风险自动记忆，但不会覆盖同 key 的显式记忆；`listMemoryEntries/searchMemoryEntriesForContext` 供管理接口与 prompt 注入使用；memory topic 默认不出现在普通 `/api/kb/topics` 列表中
- `kb/KnowledgeBaseContextPreferenceStore.kt`:
  - `knowledge_base/context_preferences.json`
  - 用户级 KB 上下文长期偏好，当前承载 `excludedSpaceIds`（space 级关闭自动召回）以及 `memoryEnabled` / `autoCaptureEnabled` / `ephemeralSessionEnabled`
  - `GET/PUT /api/kb/context-preferences` 读写
  - space 级关闭只抑制自动召回，不覆盖手动 `[[kb:...]]` 引用或 pinned 条目
- `kb/KnowledgeBaseMemory.kt`:
  - 识别显式“记住 xxx”消息
  - 低风险规则把显式记忆分类到 `profile` / `preference` / `episodic` / `procedural`
  - `detectAutoMemoryCaptures(...)` 仅对白名单偏好指令做自动记忆，当前覆盖 `response_language`、`response_style`、`code_language_preference`
  - 生成 memory title/key/tags，供去重和 prompt 展示复用
- `kb/KnowledgeBasePromptContext.kt`:
  - `resolveKnowledgeBasePromptContext(...)`：解析聊天消息中的 `[[kb:...]]` 内联引用（手动），叠加用户固定(`pinnedEntryIds`)、自动检索候选和相关长期 memory，剔除排除项(`excludedEntryIds` / `excludedSpaceIds`)，注入匹配条目内容作为 AI prompt 上下文，并返回 `diagnostics`（手动/固定/自动/memory/排除计数，含 space 级排除数）；取代旧 `KnowledgeBaseReferenceResolver`
  - 由 `WebSocketConfig.generateIntelligentResponse` 调用：本轮的 `kbContextSelection`（消息合同字段）决定固定/排除，context preference 决定是否启用 memory；结果经 `broadcastSystemStatus(references=...)` 广播给前端 KB Context Tray；解析出的 prompt 文本块经 `additionalContext` 参数传入 `DirectModelAgent.processInput`，与 `availableReferences` 一起注入系统提示词，并在 memory 段明确“当前输入优先”
- `trust/TrustedDirManager.kt`:
  - 默认 `~/.silk-data/workflows/trusted_dirs.json`
  - 可用 `SILK_WORKFLOW_DIR` 或 `-Dsilk.workflowDir=...` 覆盖
  - Per-user + per-bridge directory trust records
  - Path-level matching with subdirectory inheritance (`path == trusted || path.startsWith("${record.path}/")`)
  - `canonicalPath` normalization with exception fallback
  - Used by workflow creation (`POST /api/workflows`) and CC directory change (`POST /cc-fs/cd`) to reject untrusted directories

## Feature Modules

- `export/ChatObsidianExporter.kt`: 群聊 Markdown 导出
- `kb/KBObsidianExporter.kt`: Knowledge Base 导出
- `pdf/PDFReportGenerator.kt`: PDF 报告导出
- `todos/HolidayCalendarCn.kt`: 中国法定工作日/节假日口径

## Route Ownership

- Todo HTTP 主要仍在 `Routing.kt`
- Workflow HTTP 在 `Routing.kt` 的 `/api/workflows`
- Trusted directory HTTP 在 `Routing.kt` 的 `/users/{userId}/trusted-dirs/*`
- KB HTTP 在 `Routing.kt` 的 `/api/kb/*`（含 `PUT /api/kb/topics/{id}` 改主题/访问策略、`POST /api/kb/captures` 入库候选、`PUT /api/kb/entries/{entryId}` 支持移动条目到同 space 内其他 topic、`GET/POST/DELETE /api/kb/memory*` 管理显式长期记忆、`GET/PUT /api/kb/context-preferences` 读写用户级空间与 memory 偏好；既有路由按调用方 userId 做读写/成员可见性鉴权）

## Change Checklist

- 改 Todo 逻辑前先看 `docs/todo-roadmap.md`；该文件是 human-maintained roadmap，不应被 agent 当作自动日志持续改写
- 改 JSON store 结构时，优先保持向后兼容，避免直接破坏已有本地数据
- 改导出接口时，检查成员权限与下载文件名合同
