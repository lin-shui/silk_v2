# Silk Knowledge Base Context And Sharing Plan

Status: 进行中
Date: 2026-06-22
Scope: 当前知识库实现盘点、目标体验、权限与共享、context 构建、自动沉淀实施计划

## Progress Update

- 已完成：M1-M6 的基础闭环，包括 ACL、personal/team space、context tray、capture inbox、会议 capture 入口、移动端最小 parity，以及 7.1 中列出的 Web P1/P2 可用性修正。
- 当前剩余缺口：
  - 更完整的 candidate merge 策略，包括跨 topic merge 和冲突 diff
  - 用户选择式分享体验，而不只是直接编辑 ACL 字段
  - 聊天页 / Workflow composer 的 KB Context Tray 偏好恢复与持久偏好回填
  - Harmony 端 bearer caller 对齐
  - 空间级降权等更细的召回策略

## 1. 结论先行

建议把知识库从当前的“Markdown 条目管理器”升级为 Silk 的上下文供给层和共享记忆层。第一阶段不要追求完全自动化，先做权限安全、上下文透明和候选归档体验，避免隐私泄漏和知识库污染。

- 当前实现已有 Topic/Entry CRUD、Markdown 编辑、Obsidian 导出、手动 `[[kb:entryId|标题]]` 引用注入 AI 上下文，适合作为 MVP 底座。
- 关键缺口是权限模型、团队空间、自动检索注入、聊天/工作流沉淀、来源追踪、版本/审计，以及用户可理解的交互。
- 产品体验优先级应高于后台自动化：用户需要知道本轮用了哪些文档、为什么用、能否移除、谁能看、谁能改。
- 权限必须由后端强制执行，不能继续依赖客户端传入 `userId` 作为唯一可信边界；导出、深链、引用解析都必须经过同一套 `canRead` / `canWrite` 判定。

## 2. 当前实现盘点

当前能力可以支撑“手动知识库引用”，但还不能支撑“自动 context 构建”和“共享权限”。

| 能力面 | 当前实现 | 产品/技术缺口 |
| --- | --- | --- |
| 存储与模型 | `knowledge_base/kb_store.json`；`knowledge_base/context_preferences.json`；`KBTopic` 已有 personal/team scope、`groupId`、topic-level ACL、`writeLocked`；`KBEntry` 已有 `status` / `source` / `createdBy` / `updatedBy`，旧 store 兼容读取。 | 还没有版本/审计、索引状态、完整 lifecycle 管理，也还没有 capture inbox 的候选编排。 |
| HTTP API | `/api/kb/topics` 与 `/api/kb/entries` 提供 CRUD；topic 创建/更新可传 `spaceType` / `groupId` / `accessPolicy`；`POST /api/kb/captures` 默认生成 `CANDIDATE`，并允许 `MEETING` / `FILE` / `URL` 来源显式传 `status` 复用统一入库入口；登录/注册/`/auth/validate` 会返回 bearer 可用的认证令牌，`/api/kb/*` 现优先按 `Authorization: Bearer` 解析 caller，并在其与 query/body `userId` 冲突时拒绝请求；list/get/export/`[[kb:...]]` resolver 已统一读权限校验。 | topic 分享仍停留在 ACL 字段级编辑，还没有独立分享人搜索/选择体验；Harmony 端也还没把 KB 调用整体切到 bearer principal。 |
| 上下文注入 | 聊天链会优先解析手动 `[[kb:entryId|标题]]`，再应用消息级 pinned/excluded 条目与 `excludedSpaceIds` 空间偏好，最后对 caller 可读的 `PUBLISHED` entries 做 lexical 自动召回；用户级长期 `excludedSpaceIds` 会在服务端生成前与当前消息选择合并；三者统一编排到本轮 `promptBlock`，并生成带 `spaceId/spaceLabel` 的 `kb://topic/entry` available 引用。Web 的 Silk 聊天和 Workflow composer 都会把这批 available 引用渲染成可操作的 Context Tray。 | 还缺显式 token 预算与更强召回策略；Tray 侧的“最近一条选择恢复”和用户级持久偏好回填目前还没接上。 |
| Web 体验 | 三栏 Topic/Entry/Markdown 编辑器；支持 split/preview、复制引用、点击 KB 引用打开文档、Obsidian 导出；已接 personal/team 空间切换、topic 权限 badge、topic/entry 搜索过滤、条目状态/来源 badge、只读态、topic 权限编辑面板、聊天/工作流消息“📚入库”到 candidate、candidate inbox 过滤与批量发布/归档/并入已有文档、KB 内“会议入库”对话框、去噪后的条目 meta bar provenance 明细，以及 entry 拖到 topic 的最小拖拽整理交互。 | 仍缺用户选择式分享体验、更完整的 merge/review 流，以及 Context Tray 偏好在刷新/重连后的显式恢复。 |
| Android/Harmony | 已有个人/团队空间切换、topic/entry 的空间/状态/来源 badge、只读态提示、provenance 明细、`全部 / 候选 / 已发布 / 已归档` 过滤与单条发布/归档/重新发布动作，以及 KB 页内“会议入库”入口。 | 仍明显少于 Web；还缺批量候选收件箱/merge、分享面板、搜索，以及聊天/工作流内联“📚入库”。 |
| Workflow 关系 | Workflow 持久化 `ownerId` / `groupId` / agent 状态；聊天走 `groupId`。 | KB 未与 workflow/group/team 关联，无法自动从 workflow 获取上下文或把 workflow 产物沉淀到团队 KB。 |

现状依据：

- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseManager.kt`
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBasePromptContext.kt`
- `backend/src/main/kotlin/com/silk/backend/Routing.kt` 的 `/api/kb/*`
- `backend/src/main/kotlin/com/silk/backend/ChatServerAiSupport.kt` 的 DirectModelAgent 上下文注入
- `frontend/webApp/src/main/kotlin/com/silk/web/KnowledgeBaseScene.kt`
- `frontend/webApp/src/main/kotlin/com/silk/web/KnowledgeBaseReferences.kt`
- `frontend/androidApp/src/main/kotlin/com/silk/android/KnowledgeBaseScreen.kt`
- `frontend/harmonyApp/entry/src/main/ets/pages/KnowledgeBasePage.ets`

## 3. 目标产品体验

知识库不应该只是“存文档”的地方，而应该在聊天、工作流和未来会议纪要之间承担三件事：找得到、用得上、管得住。

| 体验组件 | 用户看到什么 | 为什么重要 |
| --- | --- | --- |
| Spaces 空间切换 | 个人知识库、团队知识库、当前工作流相关知识库在左侧或顶部清晰切换。 | 用户先理解范围，才会信任自动 context。 |
| Context Tray 上下文托盘 | 发消息前/生成中展示“本轮将使用/已使用”的文档 chips，可查看原因、移除、固定。 | 自动召回必须可解释，否则体验像黑箱。 |
| Capture Inbox 候选收件箱 | 聊天、工作流、会议纪要生成“待归档候选”，用户确认标题、空间、权限后入库。 | 先保护质量，避免知识库被低价值片段污染。 |
| Share Sheet 分享面板 | 每篇文档可设置 personal/team、读写权限、单独分享用户、关闭写权限。 | 权限是产品动作，不是隐藏后台字段。 |
| Source & Provenance 来源卡 | 展示来自哪次聊天、哪个工作流、哪份会议纪要、谁创建、何时更新。 | 让内容可追溯，支持团队协作和审计。 |
| Inline Actions 就地动作 | 聊天/工作流消息上直接“保存到知识库”“作为上下文使用”“分享给团队”。 | 减少用户在 KB 页面和聊天页面之间来回切换。 |

建议的默认体验规则：

- 用户手动引用的文档永远最高优先级，且在 Context Tray 中固定显示。
- 自动召回默认开启但必须透明显示；用户可以对单轮关闭，也可以对某个空间关闭。
- 聊天/工作流内容默认进入候选收件箱，不静默发布；会议纪要可根据会议所属团队自动进入团队 KB 候选或已发布状态。
- 团队 KB 默认团队成员可读；写权限默认可配置，建议 MVP 先允许团队成员写，文档 owner/host 可一键关闭写权限。
- 只要用户没有读权限，搜索、引用解析、导出、深链打开和自动上下文都不能暴露标题、摘要或内容。

## 4. 权限与共享模型

建议把权限设计成独立服务，而不是散落在各个 route 的 `if` 判断里。所有 KB 读取、写入、导出、引用解析和自动检索都调用同一组 `canRead` / `canWrite` / `canManage`。

| 概念 | 字段/规则建议 | 说明 |
| --- | --- | --- |
| `KnowledgeSpace` | `id`, `type=personal/team`, `ownerUserId?`, `groupId?`, `name`, `defaultRead`, `defaultWrite` | 对应个人空间或团队空间。MVP 可把 team 直接映射现有 Group。 |
| `KnowledgeEntry` | `spaceId`, `topicId`, `title`, `content`, `tags`, `status`, `source`, `createdBy`, `updatedBy`, `createdAt`, `updatedAt` | Entry 属于某个空间；topic 只做组织，不承担唯一权限边界。 |
| `AccessPolicy` | `inheritFromSpace`, `readPrincipals`, `writePrincipals`, `managePrincipals`, `writeLocked` | 支持团队默认权限、单独分享、关闭写权限。 |
| `Source/Provenance` | `sourceType=manual/chat/workflow/meeting/file/url`, `sourceGroupId`, `workflowId`, `messageIds`, `confidence` | 支撑自动沉淀、审计和“为什么会被召回”。 |
| `Lifecycle` | `candidate/published/archived/deleted` | 自动生成内容先 candidate，用户确认后 published。 |
| `Revision` | `revisionId`, `entryId`, `authorId`, `contentHash`, `summary`, `createdAt` | MVP 可先只存 `updatedAt`；团队协作上线前需要版本/恢复能力。 |

权限判定规则：

- 个人文档：owner 可读写管理；被分享用户按 grant 获得 read 或 write；`writeLocked=true` 时非 owner/manager 只能读。
- 团队文档：`GroupRepository.isUserInGroup(groupId, userId)` 是团队读权限底线；写权限由 space 默认策略和 entry override 决定。
- 管理权限：owner、团队 host、显式 manage grant 可改分享、归档、删除、写锁。
- 后端所有入口必须先解析 authenticated user，再做 ACL；query/body 里的 `userId` 只能作为兼容字段或被弃用。
- 导出与 `kb://` 深链不能绕过 ACL；引用解析遇到无权限 entry 时只能显示用户输入的 label，不返回真实标题/摘要。

## 5. Context 构建设计

Context Builder 应该成为聊天和工作流共用的后端能力。它接收用户、group/workflow、原始输入、手动引用和前端选择，输出可审计的上下文包。

| 阶段 | 处理逻辑 | 产品可见性 |
| --- | --- | --- |
| 收集候选 | 手动 `[[kb:...]]`、用户 pin 的文档、当前 workflow 绑定文档、个人/团队空间自动检索候选。 | Context Tray 显示来源：手动、固定、自动推荐。 |
| 权限过滤 | 所有候选统一 `canRead`；无权限候选直接丢弃且不暴露元数据。 | 如果用户尝试打开无权限引用，显示“无访问权限”。 |
| 排序与预算 | 手动引用 > pin > workflow 绑定 > 高相关自动召回；按 token 预算截断，保留标题/来源/摘要。 | 每个 chip 显示“为什么加入”：标题匹配、标签匹配、工作流绑定等。 |
| Prompt 编排 | 生成稳定的 `promptBlock`，保留 `[available:N]` 引用和 `kb://path`；去重同一 entry。 | 回答中的引用可点击回源。 |
| 反馈闭环 | 用户移除/固定/禁用某个空间，记录偏好；后续召回用偏好降权或屏蔽。 | 用户觉得“可控”，不是被系统强行塞上下文。 |

MVP 检索策略：

- 短期先做服务端 lexical 检索：title、tags、content 摘要、source metadata；避免第一版引入新的向量服务复杂度。
- 检索范围由 user + current group/workflow + 前端空间选择决定，永远先做权限过滤。
- Context Builder 输出结构化 diagnostics：候选数、过滤数、最终条数、token 估算、每条加入原因。
- 后续再接 embedding/vector index，但入口保持不变，避免 UI 和权限逻辑重写。

## 6. 自动沉淀设计

“聊天和工作流的信息也可以归到知识库里”建议拆成捕获、生成候选、确认发布三步。Todo 自动抽取已有类似模式，可复用其异步服务思路，但 KB 更强调质量、来源和权限。

| 来源 | 默认归档目标 | MVP 行为 |
| --- | --- | --- |
| 聊天消息 | 个人 KB 或当前群团队 KB | 消息菜单“保存到知识库”；多选消息生成候选摘要；默认 candidate。 |
| AI 回答 | 当前聊天/工作流空间 | 用户点击“保存回答到知识库”；来源记为 `AI_RESPONSE`，并保留原消息 id / group / workflow provenance；默认 candidate。 |
| Workflow | 当前 workflow 绑定的团队/个人空间 | 工作流结束或阶段总结时生成“过程记录/决策/产物”候选，保留 `workflowId` 和 `groupId`。 |
| 文件/URL/PDF | 上传者个人 KB，可转团队 | 文件提取文本后可一键建文档；后续可自动生成摘要和标签。 |
| 会议纪要 | 会议所属团队 KB | 未来会议纪要模块调用统一 capture API；可配置自动 published 或先入 candidate。 |

Capture Inbox 体验：

- 候选卡片包含标题、摘要、来源、建议空间、建议标签、权限预览、置信度和重复提示。
- 用户可以批量发布、合并到已有文档、丢弃、改为个人私有、改为团队共享、关闭写权限。
- 对于会议纪要这类明确属于团队资产的内容，默认目标是团队 KB；但仍应显示来源和权限状态。
- 自动沉淀不应阻塞聊天响应主链，采用异步队列和状态提示。

## 7. 分阶段实施计划

| 阶段 | 目标 | 主要交付 | 验收标准 |
| --- | --- | --- | --- |
| M0 审阅确认 | 对齐产品方向与默认策略。 | 本计划；确认 team 是否复用 Group、自动 context 默认策略、团队写权限默认策略。 | 评审通过后再动代码。 |
| M1 权限底座 | 让 KB 具备 scope 和 ACL，先保证不泄漏。 | KB model v2 或 repository abstraction；`canRead` / `canWrite` / `canManage`；所有 `/api/kb/*`、export、resolver 统一鉴权；迁移旧 `ownerId` 数据为 personal space。 | 他人无法 list/get/export/resolve 私有文档；团队成员权限按策略生效；写锁生效。 |
| M2 Web 体验骨架 | 把 KB 从 CRUD 页升级为空间化产品页。 | 个人/团队空间切换、权限 badge、分享面板、只读态、搜索/过滤、来源信息、文档卡片优化。 | 用户能清楚看到文档属于谁、谁能看、谁能改；无写权限时编辑入口被禁用并说明原因。 |
| M3 Context Builder MVP | 聊天/工作流可以自动从 KB 构建上下文。 | 后端 `ContextBuilder`；manual + auto candidates；token 预算；Context Tray；回答引用回源。 | 同一问题能自动带入相关 KB；用户可移除/固定；无权限文档不会出现在候选或 prompt。 |
| M4 Capture Inbox | 聊天/工作流信息可以进入 KB。 | 统一 capture API；聊天/回答保存；workflow summary 候选；候选收件箱；来源追踪。 | 用户可从聊天/工作流创建 KB 候选，确认后入库并可被后续 context 召回。 |
| M5 团队与会议准备 | 为会议纪要自动归入团队 KB 留接口。 | `sourceType=meeting`；team default policy；自动候选/自动发布策略；会议入口 mock/接口契约。 | 未来会议纪要模块只需调用 capture API 即可落入团队 KB。 |
| M6 移动端与硬化 | 补齐 Android/Harmony 并提高可靠性。 | 移动端空间/权限/只读态；版本/审计；存储扩展；性能与并发测试。 | 三端关键体验一致；大文档/多用户/并发写有明确行为。 |

## 8. 验证与质量门禁

- 后端：`KnowledgeBaseManager` / repository ACL 单测；Routing KB route 权限测试；resolver/export 权限测试；迁移兼容测试。
- 聊天链：扩展 `DirectModelAgentCitationTest` 与 KB prompt context 测试，覆盖自动 context、手动引用优先级、无权限过滤、available 引用回源。
- Web：扩展 `KnowledgeBaseReferenceTest`；覆盖 Context Tray 与分享面板状态；无写权限下保存按钮不可用。
- 移动端：Android unit/compile 与 Harmony 手动或 hvigor 验证按受影响范围执行。
- 文档：代码、脚本、HTTP/WebSocket payload、存储路径、验证命令变化时同步 `ARCHITECTURE.md`、`docs/context/**` 或 `README.md`。

## 9. 风险与决策点

| 风险/决策 | 建议 |
| --- | --- |
| HTTP 目前仍有大量非 KB 接口信任 `userId` 参数。 | KB 已切到 bearer principal；其他域后续也应迁到同一套服务端 caller 解析，逐步缩小 `userId` 直传面的鉴权职责。 |
| JSON 文件存储对 ACL、查询、并发和迁移不友好。 | MVP 可做 v2 schema + repository abstraction；团队权限和检索稳定后评估迁到 SQLite，与现有用户/群组表靠拢。 |
| 自动 context 可能让用户感到失控。 | 必须有 Context Tray、加入原因、移除/固定/关闭空间能力。 |
| 自动沉淀可能污染知识库。 | 聊天/工作流默认 candidate；会议纪要可配置自动发布，但必须带 provenance 和权限预览。 |
| 团队概念是否等同现有 Group。 | MVP 建议直接复用 Group，减少模型数量；未来如果有组织层级，再抽象 Team。 |
| 团队写权限默认打开还是关闭。 | 建议团队空间默认成员可写，文档 owner/host 可关闭；如果评审偏保守，则默认只读，显式授写。 |

建议评审时确认三件事：

1. MVP 的团队 KB 是否直接绑定现有 Group。
2. 自动 context 默认开启还是默认只做推荐。我的建议是透明开启，但前端必须显示 Context Tray。
3. 会议纪要归档默认是 candidate 还是 published。我的建议是团队会议默认 candidate，正式会议可由会议设置切为自动 published。
