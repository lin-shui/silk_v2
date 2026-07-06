# KB AI Operations Plan

Status: 进行中
Date: 2026-07-06

## Goal

让 Silk 的 Knowledge Base 不再只是手动 CRUD，而是同时支持：

- AI 在回复时主动查阅用户可读的 KB/Skill 内容
- 用户在聊天中用自然语言要求 AI 总结当前对话并沉淀到 KB
- 用户在聊天中要求 AI 新建/更新 KB 条目，后端执行受权限约束的真实操作

## Scope

- 后端聊天主链：`backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt`
- AI agent：`backend/src/main/kotlin/com/silk/backend/ai/DirectModelAgent.kt`
- KB 存储/查询：`backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseManager.kt`
- 后端测试：`backend/src/test/kotlin/com/silk/backend/**`

## Proposed Implementation

1. 把当前用户可读的 KB 条目同步到 agent workspace，生成 manifest，允许 Claude/Codex 风格的 Grep/Read 自主查阅 KB 与 Skill 条目。
2. 给 Silk 内建 AI 增加一套受控的 KB action 协议：
   - AI 正常回答用户
   - 当用户明确要求“总结进 KB / 更新 KB / 新建技能文档”等操作时，额外输出结构化 action block
   - 后端解析 action block，做权限校验后执行 create/update
3. 对话总结默认以 `CHAT` / `AI_RESPONSE` provenance 写入 `CANDIDATE`，避免静默发布污染 KB。
4. 在系统提示里明确约束：
   - 不得伪造 topicId / entryId
   - 不确定时先读 `knowledge_base/manifest.md`
   - 操作失败要向用户解释并提示改用更明确的 topic/entry

## Risks

- 模型输出的 action block 可能格式不稳定，需要后端做宽容解析与兜底忽略。
- Topic/Entry 名称歧义会导致操作目标不明确，需要优先按 id，其次按精确名称匹配。
- 自动把 KB 暴露到 workspace 时，必须只同步当前用户可读内容，避免 ACL 泄漏。

## Verification

- `./gradlew :backend:test`
- 补充 KB route / manager / AI action 相关单测

## Current Status

- 已确认当前仓库已有 KB capture、candidate lifecycle 与 prompt context 注入，可在此基础上扩展。
- 本轮先实现后端 MVP，不额外扩前端专用控制面。
