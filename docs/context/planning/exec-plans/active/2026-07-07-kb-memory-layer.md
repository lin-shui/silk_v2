# KB Memory Layer Plan

Status: 进行中（Phase 1-4 已完成，Phase 5 挂起；Android 记忆管理入口 2026-07-10 已补）
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
- 已支持在 `autoCaptureEnabled` 打开时，对低风险偏好指令自动抽取 `response_language` / `response_style` / `code_language_preference` / `tech_stack_preference` / `output_format_preference`
- 已添加敏感信息过滤器（密码/token/API key/信用卡号/私钥等），敏感内容命中后整轮不触发自动记忆
- 已在 `resolveKnowledgeBasePromptContext(...)` 中注入相关 memory，并在 prompt 明确“当前输入优先”
- 已提供 `GET/POST/DELETE /api/kb/memory*` 与 `GET/PUT /api/kb/context-preferences`
- Web 端 `KnowledgeBaseScene` 已补 memory 管理弹层：可查看/新增/删除个人 memory，并保存 `memoryEnabled` / `autoCaptureEnabled` / `ephemeralSessionEnabled`

待补：

- 更稳的冲突合并（同 key 去重与合并、旧值归档）
- episodic memory 的 recency / TTL 衰减与周期性 consolidation
- ~~Android / Harmony / Desktop 侧的 memory 管理入口~~（2026-07-10 Android 已补：`KnowledgeMemoryDialog` + `KnowledgeMemoryEntryCard`，支持个人/群组管理、设置切换、创建/删除；Harmony / Desktop 待补）（若需要多端一致）

目标：先把“可见、可删、可控”的长期记忆跑通。

### Phase 2: Low-Risk Auto Memory ✅

- 自动抽取语言偏好、输出格式偏好、技术栈偏好、procedural 偏好
- 高风险内容不自动入库，需要确认或直接丢弃
- 扩展现有 preference/settings store，至少支持：
  - `memoryEnabled`
  - `autoCaptureEnabled`
  - `ephemeralSessionEnabled`

当前落地（2026-07-09）：

- 已实现全部五种自动检测器：`response_language`、`response_style`、`code_language_preference`、`tech_stack_preference`、`output_format_preference`
- 已实现敏感内容过滤器（`containsSensitiveContent`），检测密码/token/API key/信用卡号/私钥等模式，命中后整轮不触发自动记忆
- 自动记忆按 key 去重 upsert，且不会覆盖用户显式记忆
- 代码面：`backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseMemory.kt` — `detectAutoMemoryCaptures`、`detectTechStackPreference`、`detectOutputFormatPreference`、`containsSensitiveContent`
- 测试面：`backend/src/test/kotlin/com/silk/backend/kb/KnowledgeBaseMemoryTest.kt` — 覆盖全部检测器与敏感过滤

目标：获得基础的 GPT-style memory 体验，但保持可解释。

### Phase 3: Merge And Conflict Handling ✅

- 已实现同 key 记忆覆盖时自动归档旧值（`archiveOldVersion`）：新内容覆盖前将旧值保存到 `KBMemoryMetadata.archivedVersions`，保留审计轨迹
- 已实现基于字符 tri-gram Jaccard 的近重复检测（`memoryContentSimilarity`，阈值 0.60）
- 已实现 `mergeNearDuplicateMemories`：合并同类型、高相似度的记忆条目，被合并条目的内容归档到保留条目
- 已实现 EPISODIC 记忆 TTL 衰减（`applyTTLDecay`）：90 天未访问自动归档为 `ARCHIVED`
- 已实现 PREFERENCE/PROCEDURAL 不活跃衰减：180 天未访问标记为低优先级
- PROFILE 记忆永不过期
- 已实现 `consolidateMemories` 主入口：合并近重复 + TTL 衰减，返回 `ConsolidationReport`
- 已实现 `recencyScore`：按最近访问时间加权，近 1 小时最高（8 分），逐级衰减
- 已实现 `markMemoryAccessed`：追踪访问次数和最后访问时间
- 已实现 `getMemoryEntryWithAccess`：访问时自动更新 `lastAccessedAt` / `accessedCount`
- 已实现 `consolidateMemoryStore(userId)`：后端内存合并入口，由 `POST /api/kb/memory/consolidate` 触发
- 已新增 `GET /api/kb/memory/{entryId}` 路由，支持访问追踪
- 已新增 `ArchivedMemoryVersion` 模型：记录旧版本的内容、标题、归档时间与原因
- 已更新 `KBMemoryMetadata`：增加 `lastAccessedAt`、`accessedCount`、`archivedVersions` 字段
- 新增 15 个测试覆盖：归档、相似度、合并、TTL、recency 分、consolidation 全链路

目标：避免 memory 越积越脏，旧偏好可追溯、近重复自动合并。

### Phase 4: Scoped Project Memory ✅

- 在现有 team KB topic 基础上承载 `group` / project memory：新增 `findOrCreateGroupMemoryTopic` / `captureExplicitGroupMemory` / `captureAutoGroupMemory` / `searchGroupMemoryEntriesForContext` / `listGroupMemoryEntries` / `consolidateGroupMemoryStore`
- 检索时区分 `user` 与 `group` scope：`resolveKnowledgeBasePromptContext` 会在 `preferredGroupId` 可用时同时检索个人记忆与群组记忆，prompt 中分节展示
- 保持现有 space/ACL 隔离，避免跨群污染：群组记忆 topic 使用 `spaceType=TEAM` + `groupId`，通过 `canReadTopic` / `canWriteTopic` 强制执行群组成员可见性
- WebSocket 聊天主链：群组会话中的显式"记住 xxx"与自动记忆会同步写入群组记忆空间
- HTTP API：`GET/POST /api/kb/memory` 支持 `groupId` 参数路由到群组记忆；`POST /api/kb/memory/consolidate` 支持 `groupId` 参数
- Web 前端：`KnowledgeMemoryDialog` 增加 Tab 切换（个人记忆 / 群组记忆），在团队空间内打开记忆弹窗时默认切换到群组 Tab，创建/删除操作按活动 Tab 路由到正确空间
- 测试覆盖：5 个新测试覆盖群组记忆创建、成员可见性、自动不覆盖显式、搜索匹配与 consolidation

当前落地（2026-07-10）：
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseManager.kt` — `findOrCreateGroupMemoryTopic`、`captureExplicitGroupMemory`、`captureAutoGroupMemory`、`captureGroupMemory`、`searchGroupMemoryEntriesForContext`、`listGroupMemoryEntries`、`consolidateGroupMemoryStore`
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBasePromptContext.kt` — `resolveMemoryKnowledgeBaseReferences` 增加 `preferredGroupId` 参数，检索群组记忆并注入 prompt
- `backend/src/main/kotlin/com/silk/backend/Routing.kt` — `GET/POST /api/kb/memory` 支持 `groupId`；删除路由兼容群组记忆；consolidate 支持 `groupId`
- `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt` — 群组会话中记忆同步保存到群组空间
- `backend/src/test/kotlin/com/silk/backend/kb/KnowledgeBaseMemoryTest.kt` — 5 个 Phase 4 测试- **Web 前端群组记忆管理入口**（2026-07-10 补）：
  - `frontend/webApp/ApiClient.kt` — `listKBMemoryEntries`/`createKBMemoryEntry`/`deleteKBMemoryEntry` 增加可选 `groupId` 参数
  - `frontend/webApp/KnowledgeBaseScene.kt` — `KnowledgeMemoryDialog` 增加 Tab 切换（个人记忆 / 群组记忆），在团队空间内打开记忆弹窗时默认切换到群组 Tab，创建/删除操作按活动 Tab 路由到正确空间
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

### Phase 1-3
- `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt`
- `backend/src/main/kotlin/com/silk/backend/Routing.kt`
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseManager.kt`
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBasePromptContext.kt`
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseContextPreferenceStore.kt`
- `frontend/shared/src/commonMain/kotlin/com/silk/shared/models/Message.kt`
- `frontend/shared/src/commonMain/kotlin/com/silk/shared/ChatClient.kt`
- `frontend/webApp/src/main/kotlin/com/silk/web/**`

### Phase 4（新增）
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseManager.kt` — `findOrCreateGroupMemoryTopic`, `captureExplicitGroupMemory`, `captureAutoGroupMemory`, `captureGroupMemory`, `searchGroupMemoryEntriesForContext`, `listGroupMemoryEntries`, `consolidateGroupMemoryStore`
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBasePromptContext.kt` — `resolveMemoryKnowledgeBaseReferences` 增加 `preferredGroupId`，同时检索个人与群组记忆
- `backend/src/main/kotlin/com/silk/backend/Routing.kt` — `GET/POST /api/kb/memory` 支持 `groupId`；consolidate 支持 `groupId`
- `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt` — 群组会话中记忆同步写入群组空间
- `backend/src/test/kotlin/com/silk/backend/kb/KnowledgeBaseMemoryTest.kt` — 5 个 Phase 4 测试

## Risks

- 直接复用 KB JSON store 会很快遇到检索和维护成本问题
- 自动抽取会有误记忆，必须有删除、归档和开关
- procedural memory 过强时可能压过用户当前要求，prompt 必须明确“当前输入优先”
- group memory 若边界不严，会污染其他群的上下文

## Verification

- `./gradlew :backend:test`
- 改 shared contract 时：`./gradlew :frontend:webApp:nodeTest :frontend:androidApp:testDebugUnitTest :frontend:desktopApp:test`

已有测试（Phase 4 新增 5 个）：

- `group memory topic is created lazily` — 懒创建 + 非成员不可见
- `group memory is accessible to all group members` — 同群组多成员可见
- `group auto memory does not override explicit group memory` — 自动记忆不覆盖显式
- `searchGroupMemoryEntriesForContext returns matching entries` — 搜索匹配
- `group memory consolidation works` — 群组记忆去重合并

Phase 5（2026-07-13 新增 14 个 prompt 上下文测试）：

- `KnowledgeBasePromptContextTest` 覆盖优先级、排除、空间过滤、群体记忆路由、诊断计数等
- 已验证 `./gradlew :backend:test` 通过

已覆盖的场景：

- **memory route contract** — 已有 `KnowledgeBaseRouteContractTest` 覆盖 memory CRUD 路由
- **prompt 注入优先级** — `KnowledgeBasePromptContextTest` 覆盖 manual > pinned > auto > memory 排序、`memoryEnabled` 开关、排除条目/空间过滤
- **前端群组记忆管理入口** — Web 端已补（2026-07-10），Android 已补（2026-07-10），Harmony / Desktop 待补（若需要多端一致）
