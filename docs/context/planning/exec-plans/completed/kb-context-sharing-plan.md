# Silk Knowledge Base Context And Sharing Plan

Status: 已完成
Date: 2026-06-22（M1-M7 全部完成，归档于 2026-07-13）
Scope: 当前知识库实现盘点、目标体验、权限与共享、context 构建、自动沉淀实施计划

## Progress Update

- 已完成：M1-M6 的基础闭环，包括 ACL、personal/team space、context tray、capture inbox、会议 capture 入口、移动端最小 parity，以及 7.1 中列出的 Web P1/P2 可用性修正。
- 2026-07-10：KB Copilot 扩展至主题级模式，支持在未选中条目时创建新文档。后端 entryId 改为可选，新增 topicId 字段；前端侧栏从编辑器内部移到场景级，自动感知条目模式/主题模式；无条目时 EmptyEditorState 增加"AI 协作—创建新条目"入口。详见本节各条目。
- 2026-07-10：KB 文件富预览（M7 Rich Preview）一期完成。
  - 后端：`FileRoutes.handleFileDownload` 扩展为对 PDF/音频/视频也用 `ContentDisposition.Inline` 响应，浏览器可直接渲染。
  - Web 端：新增 `KnowledgeFilePreview.kt` 组件族，包含 `KnowledgeFilePreview`（按 mimeType 路由）、`KnowledgeImagePreview`（含灯箱放大）、`KnowledgePdfPreview`（object embed）、`KnowledgeAudioPreview`（HTML5 `<audio>`）、`KnowledgeVideoPreview`（HTML5 `<video>`）、`KnowledgeFilePreviewOverlay`（全屏遮罩+Escape 关闭）。
  - Web 端集成：`GroupFilesContent`/`GroupAssetFileRow` 新增"预览"按钮，点击后弹出 overlay 预览；`KnowledgeEditorPane` 在条目带有 `fileRef` 时自动在编辑区上方展示 inline 文件预览。
  - Android 端：`KnowledgeBaseEditorPage` 新增 `KnowledgeFilePreviewCard`，使用 WebView 预览图片/PDF/音视频，支持全屏打开；`KnowledgeFileRef` 模型在条目详情中渲染为可交互的预览卡片。
- 2026-07-10：KB Copilot 增加多轮对话支持（`ConversationTurn`），支持在侧栏内连续迭代修改。后端 `KnowledgeBaseCopilotRequest` 新增 `conversationHistory: List<ConversationTurn>`；`executeKnowledgeBaseCopilot` 将历史轮次注入 user prompt 并引导 AI 仅响应当前指令；前端侧栏新增对话历史面板、继续修改输入框、🔄 新对话重置按钮，切换条目/主题时自动清空历史。
- 2026-07-09：Workflow 外部 agent 回复已接入统一 `silk_kb_action` 服务端后处理；ACP 最终回复会和内建 Silk AI 一样按 caller ACL 执行 KB create/update，并补齐 `workflowId + sourceGroupId + recentMessageIds` provenance。
- 2026-07-09：Web 端已补齐聊天页与 Workflow composer 的 KB Context Tray 偏好恢复，会从最近一条带 `kbContextSelection` 的用户消息恢复 pinned/excluded 条目，并把服务端 `excludedSpaceIds` 持久偏好回填到当前 Tray。
- 2026-07-09：Web 候选收件箱的 merge 已支持同一 knowledge space 内跨 topic 选择目标文档；合并完成后会自动切到目标 topic / entry，减少“候选在 A、正式文档在 B”时的手动跳转。
- 2026-07-09：KB 页面已补上 `KB Copilot` MVP；编辑器工具栏可直接发起 `/api/kb/copilot`，由后端围绕当前条目生成 `update_entry` 草稿，用户可先填回编辑器或直接按 caller ACL 写回当前条目。
- 2026-07-10：KB Copilot UI 从模态对话框升级为右侧侧栏面板（`KnowledgeCopilotSidebar`），与编辑器并列显示；工具栏"AI 协作"按钮切换侧栏开关，切换条目时自动关闭；侧栏宽度可调并持久化。
- 2026-07-09：自动记忆检测已扩展至全部五种偏好类型（`response_language` / `response_style` / `code_language_preference` / `tech_stack_preference` / `output_format_preference`），并新增敏感内容过滤器（密码/token/API key 等不进入自动记忆）。
- 2026-07-09：Web 端 `TopicAccessDialog` 从逗号分隔文本字段升级为带用户搜索选择器的可视化分享面板，新增 `UserSearchSelector` 组件支持按名称搜索用户并以 chips 展示已选用户；后端新增 `GET /api/users/search-by-name?q=` API 和 `UserRepository.searchUsersByName` 模糊搜索方法。
- 2026-07-09：Memory Layer Phase 3 完成（去重合并 + TTL 衰减 + 旧值归档 + 访问追踪）。详见 `2026-07-07-kb-memory-layer.md`。
- 2026-07-10：Memory Layer Phase 4 完成（群组记忆）。个人记忆之外新增群组级共享记忆，聊天中的显式/自动记忆在群组会话中同步写入群组记忆空间；HTTP API 通过可选 `groupId` 参数路由到群组记忆；`resolveKnowledgeBasePromptContext` 在 `preferredGroupId` 可用时同时检索个人与群组记忆并分层注入 prompt。详见 `2026-07-07-kb-memory-layer.md`。
- 2026-07-10：群空间资产浏览与互跳 MVP 完成。
- 2026-07-10：群文件"创建 KB 文档"增加目标主题选择对话框；用户可从可写主题列表中选择目标，不再写入第一个可用主题。
- 2026-07-10：群文件 UI 重构——"群资产"→"群文件"，从中栏切换按钮改为左栏独立主题。
  - 命名："群资产"统一改为"群文件"（`GroupAssetsContent`→`GroupFilesContent`）
  - 位置：移除中栏 `EntrySidebar` 右上角的切换按钮（`secondaryAction`），改为在 `TopicSidebar` 底部新增 `GroupFilesTopicRow` 特殊主题行（📁 群文件）
  - 交互：点击左栏"📁 群文件"主题，中栏展示 `GroupFilesContent`；选择普通主题或切换空间时自动退出群文件视图
  - 后端数据源扩展：`getGroupAssets` 增加 `collectKBLinkedFiles`（KB 条目 fileRef 引用的文件）和 `collectGroupUrlExtractedFiles`（URL 下载提取的网页内容）；`GroupAssetFile` 新增 `sourceType` 字段（`upload`/`kb_entry_file`/`url_extracted`）；前端 `GroupAssetFileRow` 增加来源类型 badge
- 2026-07-10：Android 端增加记忆管理入口（`KnowledgeMemoryDialog`），支持个人/群组记忆管理、设置开关、创建/删除。
  - `KBEntrySource` 新增 `fileRef: KBFileRef?` 字段，FILE 类型条目可直接携带文件引用信息（`fileName`/`fileSize`/`mimeType`/`downloadUrl`/`sourceMessageId`），无需回溯聊天消息
  - 后端新增 `GET /api/kb/group-assets/{groupId}` 统一接口，返回群上传文件 + KB 条目
  - 后端新增 `POST /api/kb/entries/{entryId}/link-file` 接口，关联文件到已有条目
  - Web KB 页团队空间新增"群资产"切换按钮，点击后在中栏展示群文件与 KB 条目的统一列表
  - 文件行支持"下载"和"创建 KB 文档"动作；已关联条目的文件显示"已关联"badge 和"查看文件"链接
  - 详见模型改动：`backend/.../models/KnowledgeBase.kt`（`KBFileRef`）；后端逻辑：`KnowledgeBaseManager.kt`（`getGroupAssets`/`linkFileToEntry`）与 `Routing.kt`（新路由）；前端：`ApiClient.kt`（`GroupAssetsResponse`）与 `KnowledgeBaseScene.kt`（`GroupAssetsContent`/`GroupAssetFileRow`/`GroupAssetEntryRow`）
- 2026-07-10：KB Copilot 从模态对话框升级为右侧侧栏面板，与编辑器并列显示。
  - 工具栏"AI 协作"按钮改为切换式（单击打开/关闭侧栏）
  - 侧栏支持调整宽度（默认 360px），宽度通过 localStorage 持久化
  - 切换条目时自动关闭侧栏
  - 侧栏包含：当前条目信息、AI 指令输入框、直接写回开关、执行按钮、AI 说明区、草稿预览及"填回编辑器"按钮
  - 原有模态对话框 `KnowledgeCopilotDialog` 保留，未来可用于紧凑布局
- 2026-07-10：KB Copilot 侧栏增加拖拽缩放把手（`KnowledgeHorizontalResizeHandle`），侧栏左侧出现与主题/条目侧栏一致的拖拽条，宽度双向调节；`copilotSidebarOffset` 同步计入把手宽度。
- 2026-07-10：KB Copilot 指令输入框增加 `Ctrl+Enter`（macOS `⌘Enter`）快捷键，无需鼠标点击即可提交；执行按钮同时显示 `(⌘Enter)` 提示。
- 2026-07-10：KB Copilot 增加主题级（空间级）模式，支持在未选中条目时创建新文档。
  - 后端 `KnowledgeBaseCopilotRequest.entryId` 改为可选，新增 `topicId` 字段
  - 当 `entryId` 为空时自动进入主题级模式：模型输出 `create_entry` 操作，生成新条目草稿
  - 新增 `buildKnowledgeBaseTopicCopilotSystemPrompt` 主题级系统提示词
  - 前端侧栏渲染从 `KnowledgeEditorPane` 内部移到场景级，与编辑器并列的 `FlexRow` 容器
  - 侧栏自动感知模式：有选中条目 → 条目模式（编辑），仅选中主题 → 主题模式（创建）
  - 无条目时 `EmptyEditorState` 增加"AI 协作—创建新条目"按钮作为入口
  - 主题模式"填回编辑器"直接调用 `ApiClient.createKBEntry` 创建正式条目并跳转
- 2026-07-13：Web 端 Context Tray 增加三态空间控制（normal → 🔽 downranked → ❌ excluded），后端 `KnowledgeBaseContextPreferences` 新增 `downrankedSpaceIds`，`resolveKnowledgeBasePromptContext` 和 `searchEntriesForContext` 线程传递降权参数，降权空间条目 score 减半排在普通空间之后；同时 Web/Workflow 双端加载持久降权偏好并合并到 `kbContextSelection`。详见空间级降权实施。
- 当前剩余缺口：
  - ~~KB Copilot 流式响应与内联 Diff 编辑~~（2026-07-12 已完成）
  - ~~更完整的 candidate merge 策略，尤其是冲突 diff / review~~（2026-07-13 已完成：合并对话框增加行级 diff 预览，选择目标后可展开差异详情，新增 8 个单测覆盖 diff 算法）
  - ~~Harmony 端 bearer caller 对齐~~（2026-07-13 已完成：`ApiClient.ets` 所有 HTTP 方法（`get`/`post`/`put`/`delete` + `Raw` 变体）统一注入 `Authorization: Bearer <token>`；`AppStore.ets` 登录/恢复会话时同步 token、登出时清除 token；后端已有 3 级降级解析优先走 bearer，无需后端改动）
  - ~~空间级降权等更细的召回策略~~（2026-07-13 已完成：三态空间控制 + score 减半降权）
  - ~~群会话文件和知识库浏览已打通 MVP，但 KB 对音频 / 视频等非 Markdown 资产还缺原生预览面，难以承接群文件沉淀~~（2026-07-10 已补）
  - ~~群文件"创建 KB 文档"目前写入第一个可用主题，缺少目标空间/主题选择~~（2026-07-10 已补：改为弹出主题选择对话框，用户可从可写主题列表中选择目标）
  - ~~KB Copilot 主题级创建模式仍为单轮，后续可支持多轮对话迭代细化草稿~~（2026-07-10 已补：`ConversationTurn` 支持多轮对话，侧栏内可连续修改）

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
| HTTP API | `/api/kb/topics` 与 `/api/kb/entries` 提供 CRUD；topic 创建/更新可传 `spaceType` / `groupId` / `accessPolicy`；`POST /api/kb/captures` 默认生成 `CANDIDATE`，并允许 `MEETING` / `FILE` / `URL` 来源显式传 `status` 复用统一入库入口；`POST /api/kb/copilot` 会围绕当前 entry 调用 `DirectModelAgent + silk_kb_action` 生成或直接应用 `update_entry` 草稿；登录/注册/`/auth/validate` 会返回 bearer 可用的认证令牌，`/api/kb/*` 现优先按 `Authorization: Bearer` 解析 caller，并在其与 query/body `userId` 冲突时拒绝请求；list/get/export/`[[kb:...]]` resolver 已统一读权限校验。 | topic 分享仍停留在 ACL 字段级编辑，还没有独立分享人搜索/选择体验。 |
| 上下文注入 | 聊天链会优先解析手动 `[[kb:entryId|标题]]`，再应用消息级 pinned/excluded 条目与 `excludedSpaceIds` 空间偏好，最后对 caller 可读的 `PUBLISHED` entries 做 lexical 自动召回；用户级长期 `excludedSpaceIds` 会在服务端生成前与当前消息选择合并；三者统一编排到本轮 `promptBlock`，并生成带 `spaceId/spaceLabel` 的 `kb://topic/entry` available 引用。Web 的 Silk 聊天和 Workflow composer 都会把这批 available 引用渲染成可操作的 Context Tray，并在刷新/重连后从最近一条带 `kbContextSelection` 的用户消息恢复条目级选择，同时回填用户级 `excludedSpaceIds`。 | 还缺显式 token 预算与更强召回策略。 |
| Web 体验 | 三栏 Topic/Entry/Markdown 编辑器；支持 split/preview、复制引用、点击 KB 引用打开文档、Obsidian 导出；已接 personal/team 空间切换、topic 权限 badge、topic/entry 搜索过滤、条目状态/来源 badge、只读态、topic 权限编辑面板、聊天/工作流消息"📚入库"到 candidate、candidate inbox 过滤与批量发布/归档/并入已有文档（已支持同一 knowledge space 的跨 topic merge）、KB 内"会议入库"对话框、去噪后的条目 meta bar provenance 明细、`KB Copilot` 侧栏面板，以及 entry 拖到 topic 的最小拖拽整理交互；Context Tray 在刷新/重连后可恢复最近一条选择并回填空间级持久偏好。 | 仍缺用户选择式分享体验，以及更完整的 merge/review 流。 |
| Android/Harmony | 已有个人/团队空间切换、topic/entry 的空间/状态/来源 badge、只读态提示、provenance 明细、`全部 / 候选 / 已发布 / 已归档` 过滤与单条发布/归档/重新发布动作，以及 KB 页内“会议入库”入口。 | 仍明显少于 Web；还缺批量候选收件箱/merge、分享面板、搜索，以及聊天/工作流内联“📚入库”。 |
| 群文件 / 资产联动 | 聊天/Workflow 已能把消息或摘要"📚入库"为候选；KB 条目 provenance 已能记录 `sourceGroupId` / `workflowId` / `messageIds` 并在 Web 端回跳源消息或工作流；群文件已提升为左栏独立主题，支持浏览上传文件、KB 条目关联文件、URL 提取内容，并显示来源类型 badge；`FolderExplorerDialog`（会话文件面板）也已集成 KB 群空间条目一览，用户在聊天中可直接查看群 KB 条目。 | 群 KB 页面（`GroupFilesContent`）的"知识库条目"区域已移除，因同一页面上方即可见，避免冗余。 |
| 文件预览 | Web 端 `GroupAssetFileRow` 新增"预览"按钮，支持图片灯箱、PDF embed、音视频播放；`KnowledgeEditorPane` 对 FILE 类型条目自动展示 inline 文件预览。Android 端 `KnowledgeBaseEditorPage` 新增 `KnowledgeFilePreviewCard` 用 WebView 统一预览。后端下载接口已对图片/PDF/音视频均返回 `ContentDisposition.Inline`。 | 还缺视频/音频的逐帧/波形图缩略图等更丰富的 preview 元信息；PDF 预览暂依赖浏览器原生支持。 |
| Workflow 关系 | Workflow 持久化 `ownerId` / `groupId` / agent 状态；Workflow composer 已复用聊天链路的 KB Context Tray 与“📚入库”动作；外部 agent 最终回复也会统一走 `silk_kb_action` 服务端后处理并补齐 workflow provenance。 | 仍缺 workflow 级更细的默认 topic/space 绑定策略，以及 KB 页面内直接面向 workflow 的 AI Copilot 入口。 |

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
| KB Copilot 侧栏 | 在知识库页内直接和 AI 对话：让它总结、改写、补充、整理当前文档或当前空间，而不是切到 Silk 聊天页。 | 现实里用户更可能“让 AI 写 md”，而不是手工编辑 Markdown。 |
| Context Tray 上下文托盘 | 发消息前/生成中展示“本轮将使用/已使用”的文档 chips，可查看原因、移除、固定。 | 自动召回必须可解释，否则体验像黑箱。 |
| Capture Inbox 候选收件箱 | 聊天、工作流、会议纪要生成“待归档候选”，用户确认标题、空间、权限后入库。 | 先保护质量，避免知识库被低价值片段污染。 |
| Group Files Browser 群文件浏览 | 在 KB 左栏"📁 群文件"主题中可浏览当前群空间的所有文件型内容（上传文件、KB 条目附件、URL 提取内容），每个文件标注来源类型。 | 群文件和知识库本质上都属于同一组协作资产，不应分裂成两套入口。 |
| Rich Preview 富预览 | 图片、PDF、音频、视频和文本都能在 KB 内直接预览，必要时再切换到 Markdown 摘要或结构化元数据。 | 群空间里很多知识先以文件存在，若只支持 Markdown，会阻断沉淀和复用。 |
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
| 群会话文件 | 当前群团队空间，必要时可降到上传者个人空间 | 群文件区直接“加入知识空间”；默认保留原文件引用、提取文本摘要、media metadata 与权限归属。 |
| 文件/URL/PDF | 上传者个人 KB，可转团队 | 文件提取文本后可一键建文档；后续扩展为“原文件 + 摘要文档”双轨，而不是只保留 Markdown。 |
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
| M7 KB Copilot 与群文件打通 | 让 KB 成为"AI 编辑 + 群空间文件浏览"的一等入口。 | KB 页面内 AI Copilot（侧栏面板）；群文件区与 KB 双向浏览/跳转；文件型资产统一元数据模型；图片/PDF/音视频预览；workflow 级默认知识空间/主题绑定。 | 用户无需切到 Silk 主聊天也能自然语言改 KB；群文件和 KB 可以互相浏览；workflow 中无论内建还是外部 agent，受权限约束的 KB 修改行为一致。 |

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
| 群文件与 KB 打通后，文件会不会绕过现有 ACL。 | 文件浏览、预览、下载、入库都必须统一走 group membership + KB ACL；不允许因为“只是预览文件”就旁路权限判定。 |
| 音视频预览成本可能明显高于 Markdown 文档。 | 先做元数据 + 原生 `<audio>/<video>` / 浏览器预览能力，转码与波形、字幕、OCR 之类放到后续增量。 |

建议评审时确认三件事：

1. MVP 的团队 KB 是否直接绑定现有 Group。
2. 自动 context 默认开启还是默认只做推荐。我的建议是透明开启，但前端必须显示 Context Tray。
3. 会议纪要归档默认是 candidate 还是 published。我的建议是团队会议默认 candidate，正式会议可由会议设置切为自动 published。

今天会后的新增确认，建议一并拍板：

4. KB 页面是否接受“AI Copilot 作为默认编辑入口”，即把 Markdown 手工编辑降级为专家模式。
5. 群文件与 KB 打通时，是否采用“同一资产、双视图”模型：文件区看群资产，KB 区看知识整理与摘要，两边都可回到同一来源对象。
6. Workflow 是否需要“默认知识空间 / 默认 topic”绑定，用于让外部 agent 和内建 Silk AI 在产出总结时优先落到约定位置，而不是每次都靠模型自行选择。
