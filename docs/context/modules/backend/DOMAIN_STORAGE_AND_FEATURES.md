# Domain Storage And Features

## Database-Backed Domains

`database/` + `auth/` 主要承载：

- 用户注册 / 登录
- 群组与成员
- 联系人与好友请求
- 未读计数
- 用户设置（含 Claude Code bridge token）

SQLite 数据库默认在 `./silk_database.db`，测试或特殊运行场景可用 `-Dsilk.databasePath=...` 覆盖。

## File-Backed Domains

- `ChatHistoryManager.kt`:
  - 会话元数据
  - 聊天历史
  - 成员列表
  - uploads 目录协同
  - 持久化消息级 `kbContextSelection`，保留用户对下一轮 KB context 的固定 / 排除选择；新消息即使显式清空偏好也会落盘，供前端刷新 / 重连后恢复当前状态
- `kb/KnowledgeBaseContextPreferenceStore.kt`:
  - `knowledge_base/context_preferences.json`
  - 用户级 KB 上下文长期偏好，当前承载 `excludedSpaceIds`
  - `GET/PUT /api/kb/context-preferences` 读写
  - 聊天与 Workflow 在生成前会把该长期偏好与本条消息携带的 `kbContextSelection` 合并；长期关闭某个空间的自动推荐不会影响手动 `[[kb:...]]` 或 pinned 条目
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
  - Topic / Entry CRUD
  - `POST /api/kb/captures` 把聊天/AI 回答/工作流内容沉淀为 `CANDIDATE` entry，并记录 `sourceType/sourceGroupId/workflowId/messageIds`
  - capture route 支持显式 `status`，但 `CHAT` / `AI_RESPONSE` / `WORKFLOW` 来源会被后端强制回落到 `CANDIDATE`；`MEETING` / `FILE` / `URL` 等来源可按调用方传入 `status` 建条，供未来会议纪要或导入流复用统一入口
  - `/api/kb/*` 当前会优先信任 `X-Silk-Authenticated-User-Id` 请求头；若该值与 query/body 里的 `userId` 不一致则拒绝请求，未接入认证头的旧客户端仍回退到 `userId`
  - `PUT /api/kb/entries/{entryId}` 可更新 `title/content/tags/status`，供 candidate inbox 做发布/归档流转
  - Topic 级 personal/team scope 与 ACL（`read/write/manage` grants、`writeLocked`、team member write policy）
  - 聊天上下文自动召回的 lexical 检索入口（仅在 caller 可读范围内检索 `PUBLISHED` entries，可按当前 group boost）
  - `resolveKnowledgeBasePromptContext()` 会同时处理手动 `[[kb:...]]`、消息里传入的 pinned/excluded 条目与 excluded spaces 偏好，以及自动 lexical 候选，顺序为 手动 > 固定 > 自动；space 级关闭只影响自动召回，不覆盖手动引用或 pinned 条目
  - Topic update 支持改 `name` / `project` / `accessPolicy`，仅 owner / team host / manage grant 可操作
  - 旧 `ownerId`-only store 兼容读取；缺失 ACL 元数据时回退到 personal topic
  - `/api/kb/*`、Obsidian export、`[[kb:...]]` resolver 与自动 context builder 统一走同一套读写权限判定
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
- KB HTTP 在 `Routing.kt` 的 `/api/kb/*`

## Change Checklist

- 改 Todo 逻辑前先看 `docs/todo-roadmap.md`；该文件是 human-maintained roadmap，不应被 agent 当作自动日志持续改写
- 改 JSON store 结构时，优先保持向后兼容，避免直接破坏已有本地数据
- 改 KB 导出、引用解析或自动 context builder 时，检查 ACL 是否仍覆盖 route / export / resolver / auto-search 四个入口
- 改导出接口时，检查成员权限与下载文件名合同
