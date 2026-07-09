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
  - `kb/KnowledgeBaseAiActions.kt`：统一解析 `silk_kb_action`、执行 create/update、生成“KB 执行结果”摘要；若请求携带 `workflowId`，未显式指定 `sourceType` 的 create 默认记为 `WORKFLOW`，并把 `workflowId/sourceGroupId/recentMessageIds` 写入 `KBEntrySource`
  - `kb/KnowledgeBaseCopilot.kt`：KB 页面专用 AI 协作入口；围绕当前 entry 构造编辑提示词，要求模型返回当前条目的 `update_entry` 草稿，并可选择是否立刻通过同一 `silk_kb_action` 管线写回
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
  - `detectAutoMemoryCaptures(...)` 覆盖五种自动检测器：`response_language`、`response_style`、`code_language_preference`、`tech_stack_preference`、`output_format_preference`
  - `containsSensitiveContent(...)` 过滤密码/token/API key/信用卡号/私钥等敏感输入，命中后整轮不触发自动记忆
  - 生成 memory title/key/tags，供去重和 prompt 展示复用  - **Phase 3 — 合并与冲突处理：**
    - `memoryContentSimilarity(a, b)` — 基于字符 tri-gram Jaccard 的语义相似度检测
    - `archiveOldVersion(existingEntry, newContent, ...)` — 覆盖前自动归档旧值到 `archivedVersions`
    - `mergeNearDuplicateMemories(entries)` — 合并同类型高相似度记忆（阈值 0.60）
    - `applyTTLDecay(entries)` — EPISODIC 超 90 天归档，PREFERENCE/PROCEDURAL 超 180 天低优先级，PROFILE 永不过期
    - `consolidateMemories(entries)` — 合并近重复 + TTL 衰减，返回 `ConsolidationReport`
    - `recencyScore(meta)` / `markMemoryAccessed(meta)` — 按最近访问时间加权排序与访问追踪- `kb/KnowledgeBasePromptContext.kt`:
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
- KB HTTP 在 `Routing.kt` 的 `/api/kb/*`（含 `PUT /api/kb/topics/{id}` 改主题/访问策略、`POST /api/kb/captures` 入库候选、`PUT /api/kb/entries/{entryId}` 支持移动条目到同 space 内其他 topic、`POST /api/kb/copilot` 在 KB 页面内生成/应用 AI 编辑草稿、`GET/POST/DELETE /api/kb/memory*` 管理显式长期记忆、`GET /api/kb/memory/{entryId}` 带访问追踪读取单条记忆、`POST /api/kb/memory/consolidate` 触发去重合并与 TTL 衰减、`GET/PUT /api/kb/context-preferences` 读写用户级空间与 memory 偏好；既有路由按调用方 userId 做读写/成员可见性鉴权）
- ACP 外部 agent 的 KB 后处理在 `AgentRuntime.kt`：最终回复会先抽取 `silk_kb_action` 再广播，从而让 workflow 内的 Claude Code / Codex / Cursor 与内建 Silk AI 共享同一套 KB 落库路径

## Change Checklist

- 改 Todo 逻辑前先看 `docs/todo-roadmap.md`；该文件是 human-maintained roadmap，不应被 agent 当作自动日志持续改写
- 改 JSON store 结构时，优先保持向后兼容，避免直接破坏已有本地数据
- 改导出接口时，检查成员权限与下载文件名合同
