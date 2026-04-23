# Domain Storage And Features

## Database-Backed Domains

`database/` + `auth/` 主要承载：

- 用户注册 / 登录
- 群组与成员
- 联系人与好友请求
- 未读计数
- 用户设置（含 Claude Code bridge token）

SQLite 数据库固定在 `./silk_database.db`。

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
  - `workflows/workflow_store.json`
  - 目前是基础 CRUD，占位结构
- `kb/KnowledgeBaseManager.kt`:
  - `knowledge_base/kb_store.json`
  - Topic / Entry CRUD

## Feature Modules

- `export/ChatObsidianExporter.kt`: 群聊 Markdown 导出
- `kb/KBObsidianExporter.kt`: Knowledge Base 导出
- `pdf/PDFReportGenerator.kt`: PDF 报告导出
- `todos/HolidayCalendarCn.kt`: 中国法定工作日/节假日口径

## Route Ownership

- Todo HTTP 主要仍在 `Routing.kt`
- Workflow HTTP 在 `Routing.kt` 的 `/api/workflows`
- KB HTTP 在 `Routing.kt` 的 `/api/kb/*`

## Change Checklist

- 改 Todo 逻辑前先看 `docs/todo-roadmap.md`；该文件是 human-maintained roadmap，不应被 agent 当作自动日志持续改写
- 改 JSON store 结构时，优先保持向后兼容，避免直接破坏已有本地数据
- 改导出接口时，检查成员权限与下载文件名合同
