# KB AI Operations Plan

Status: 已完成
Date: 2026-07-06

## Goal

让 Silk 的 Knowledge Base 不再只是手动 CRUD，而是同时支持：

- AI 在回复时主动查阅用户可读的 KB/Skill 内容
- 用户在聊天中用自然语言要求 AI 总结当前对话并沉淀到 KB
- 用户在聊天中要求 AI 新建/更新 KB 条目，后端执行受权限约束的真实操作
- 上述 KB 修改能力在普通聊天和 Workflow 会话里保持一致，而不是只在 Silk 主聊天里可用

## Scope

- 后端聊天主链：`backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt`
- AI agent：`backend/src/main/kotlin/com/silk/backend/ai/DirectModelAgent.kt`
- KB 存储/查询：`backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseManager.kt`
- Workflow 会话入口：`frontend/webApp/src/main/kotlin/com/silk/web/WorkflowScene.kt`
- 外部 agent 运行时：`backend/src/main/kotlin/com/silk/backend/agents/core/AgentRuntime.kt`
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
5. Workflow 场景按两条链路分别打通：
   - `silk_chat` / 内建 Silk AI：继续复用 `generateIntelligentResponse -> executeKnowledgeBaseAiActions`，只需补 workflow 维度的验收与文档约束
   - `claude-code` / `codex` 等外部 workflow agent：在 agent 最终回复落盘前增加同样的 `silk_kb_action` 解析与执行后处理，统一走后端 ACL 和 candidate 策略，而不是要求前端单独调 KB API
6. KB action request 需要补齐 workflow 语义：
   - 记录 `workflowId`
   - `sourceType=WORKFLOW` 时强制 `CANDIDATE`
   - summary/result 类写入保留 `sourceGroupId + workflowId + recentMessageIds` provenance，便于 KB 侧回跳原始工作流

## Risks

- 模型输出的 action block 可能格式不稳定，需要后端做宽容解析与兜底忽略。
- Topic/Entry 名称歧义会导致操作目标不明确，需要优先按 id，其次按精确名称匹配。
- 自动把 KB 暴露到 workspace 时，必须只同步当前用户可读内容，避免 ACL 泄漏。
- 若外部 agent 也支持 KB 写入，必须确保 action 执行仍以 Silk 服务端 caller 身份和 `canWrite*` 判定为准，不能信任 agent 自报的 topic/entry 归属。
- Workflow 生成的总结/决策默认应入 `CANDIDATE`，否则容易把临时思路直接污染团队知识空间。

## Verification

- `./gradlew :backend:test`
- 补充 KB route / manager / AI action 相关单测

## Current Status

- 已确认当前仓库已有 KB capture、candidate lifecycle 与 prompt context 注入，可在此基础上扩展。
- Workflow 页消息发送仍复用聊天 WebSocket，因此当 workflow 当前 agent 是 `silk_chat` 时，KB action 已可随 `generateIntelligentResponse` 一并执行。
- 2026-07-09 已补齐外部 agent 回复后处理：`AgentRuntime.executeSinglePrompt()` 会在最终广播前统一执行 `extractKnowledgeBaseAiActions -> executeKnowledgeBaseAiActions -> buildKnowledgeBaseActionSummary`。
- `KnowledgeBaseAiExecutionRequest` 现已补齐 `workflowId`；当当前群属于 workflow 时，KB action 会把 `workflowId + sourceGroupId + recentMessageIds` 写入 provenance，且未显式指定 `sourceType` 的 create 默认按 `WORKFLOW` 候选入库。
- 2026-07-09 已补上 KB 页 `POST /api/kb/copilot`：后端围绕当前 entry 构造定向编辑 prompt，并要求模型返回当前条目的 `update_entry` 草稿；Web 端可选择先把草稿填回编辑器，或直接按 caller ACL 写回当前条目。
- 2026-07-10：KB Copilot UI 从模态对话框升级为右侧侧栏面板（`KnowledgeCopilotSidebar`），与编辑器并列显示；工具栏"AI 协作"按钮切换侧栏开关，切换条目时自动关闭；侧栏宽度可调并持久化。
- 已补充 `KnowledgeBaseAiActionsTest` 并通过 `./gradlew :backend:test --tests 'com.silk.backend.kb.KnowledgeBaseAiActionsTest'` 与 `./gradlew :backend:test` 验证。
