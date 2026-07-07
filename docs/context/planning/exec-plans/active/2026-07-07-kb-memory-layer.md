# KB Memory Layer Plan

Status: 进行中
Date: 2026-07-07

## Goal

在现有聊天历史和 Knowledge Base 之上补一层 Memory Layer，让 Silk 能稳定记住用户偏好、项目上下文和关键决策，但只在相关时注入 prompt，不做全量历史塞入。

## Current Baseline

这些能力已经存在，不再作为本计划目标：

- 聊天历史已持久化，可作为短期记忆来源
- KB 已有 topic/entry、ACL、candidate lifecycle
- 回复前已支持 KB 手动引用、固定条目、自动检索和 prompt 注入
- 用户级 KB context preference 已有基础存储

本计划只关注这些现有能力之外的增量：长期 memory 的抽取、管理、冲突处理和注入策略。

## Scope

MVP 只做：

- `user` 级长期 memory
- 四类 memory：`profile`、`preference`、`episodic`、`procedural`
- 显式记忆 + 低风险自动记忆
- 查看 / 删除 / 关闭记忆

MVP 不做：

- 全量历史向量化
- 敏感信息自动记忆
- GraphRAG / KG
- 复杂 org/project 继承

## Proposed Plan

### Phase 1: Explicit Memory MVP

- 用户显式说“记住 xxx”时写入 memory
- 先复用 `knowledge_base/kb_store.json`，为每个用户建立 memory topic
- 每次回复前检索 top 3-5 条相关 memory 注入 prompt
- 提供基础管理接口：列表、删除、开关

当前落地：

- 已复用 `kb_store.json`，通过 `KBTopic.purpose=MEMORY` 为每个用户懒创建个人 memory topic
- 已支持显式“记住 xxx”抽取，按 `profile/preference/episodic/procedural` 写入或按 key 覆写 memory
- 已支持在 `autoCaptureEnabled` 打开时，对低风险偏好指令自动抽取 `response_language` / `response_style` / `code_language_preference`
- 已在 `resolveKnowledgeBasePromptContext(...)` 中注入相关 memory，并在 prompt 明确“当前输入优先”
- 已提供 `GET/POST/DELETE /api/kb/memory*` 与 `GET/PUT /api/kb/context-preferences`
- Web 端 `KnowledgeBaseScene` 已补 memory 管理弹层：可查看/新增/删除个人 memory，并保存 `memoryEnabled` / `autoCaptureEnabled` / `ephemeralSessionEnabled`

待补：

- 更稳的冲突合并、更多自动记忆类别与敏感记忆过滤
- Android / Harmony / Desktop 侧的 memory 管理入口（若需要多端一致）

目标：先把“可见、可删、可控”的长期记忆跑通。

### Phase 2: Low-Risk Auto Memory

- 自动抽取语言偏好、输出格式偏好、技术栈偏好、procedural 偏好
- 高风险内容不自动入库，需要确认或直接丢弃
- 扩展现有 preference/settings store，至少支持：
  - `memoryEnabled`
  - `autoCaptureEnabled`
  - `ephemeralSessionEnabled`

目标：获得基础的 GPT-style memory 体验，但保持可解释。

### Phase 3: Merge And Conflict Handling

- 同 key 记忆去重与合并
- 新偏好覆盖旧偏好时自动归档旧值
- episodic memory 增加 recency / TTL 衰减
- 增加周期性 consolidation

目标：避免 memory 越积越脏。

### Phase 4: Scoped Project Memory

- 在现有 team KB topic 基础上承载 `group` / project memory
- 检索时区分 `user` 与 `group` scope
- 保持现有 space/ACL 隔离，避免跨群污染

目标：把“用户偏好”和“项目约束”分层。

### Phase 5: Storage Upgrade

- 当 JSON store 无法承载增长后，再迁移到独立 memory store
- 优先方案：`PostgreSQL + pgvector`
- 检索改为结构化命中 + 语义召回混合模式

## Data / Prompt Rules

- memory 只作为辅助上下文，不是权威事实
- 当前用户输入与 memory 冲突时，以当前输入为准
- 敏感信息默认不自动记忆
- 自动记忆优先记录摘要和结构化键值，不保留冗长原文

建议优先支持的 key：

- `response_language`
- `response_style`
- `code_language_preference`
- `tech_stack_preference`
- `workflow_preference`

## Affected Surfaces

- `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt`
- `backend/src/main/kotlin/com/silk/backend/Routing.kt`
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseManager.kt`
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBasePromptContext.kt`
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseContextPreferenceStore.kt`
- `frontend/shared/src/commonMain/kotlin/com/silk/shared/models/Message.kt`
- `frontend/shared/src/commonMain/kotlin/com/silk/shared/ChatClient.kt`
- `frontend/webApp/src/main/kotlin/com/silk/web/**`

## Risks

- 直接复用 KB JSON store 会很快遇到检索和维护成本问题
- 自动抽取会有误记忆，必须有删除、归档和开关
- procedural memory 过强时可能压过用户当前要求，prompt 必须明确“当前输入优先”
- group memory 若边界不严，会污染其他群的上下文

## Verification

- `./gradlew :backend:test`
- 改 shared contract 时：`./gradlew :frontend:webApp:nodeTest :frontend:androidApp:testDebugUnitTest :frontend:desktopApp:test`

建议补的测试：

- memory route contract
- prompt 注入优先级
- merge / archive / TTL
- user/group scope 隔离
- 敏感记忆过滤
