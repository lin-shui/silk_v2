# Web

## Entry Surface

- `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt`
- `AppState.kt`
- `ApiClient.kt`
- `GroupListScene.kt`
- `KnowledgeBaseScene.kt`
- `WorkflowScene.kt`
- `AudioDuplexScene.kt`
- `SettingsScene.kt`
- `SilkChatStyles.kt` -- claudian 风格 CSS 样式注入（消息卡片、头像、hover 操作栏、动画）

## Current Shape

- Compose for Web
- 登录后是左侧 `NavRail` + 右侧内容区
- 主 Tab：
  - Silk
  - Workflow
  - Knowledge Base
  - Audio Duplex

## Build-Time Facts

- 从 `.env` 读取后端端口并生成 `BuildConfig.kt`
- 生产构建最终供后端静态分发
- JS 轻量测试跑 `nodeTest`，不依赖浏览器自动化

## Watch Points

- 改文件消息/下载逻辑时，优先看 `FileContracts.kt` / `FileContractsTest.kt`
- 改布局壳层时，确认 `AppState.kt` 与 `Main.kt` 的 scene/tab 状态流
- 改 Audio Duplex 时看 `AudioDuplexScene.kt` 与后端 `/ws/audio-duplex`
- 工作流面板（`WorkflowScene.kt`）含 Folder Picker：
  - header 显示 agent 名（取自 `Message.userName`）和当前工作目录
  - "更改" 链接 / 创建工作流的"选择…" 按钮 → `FolderPickerDialog`（面包屑 + `..` + 子目录 + 手动输入）
  - 切目录走 HTTP `cdCcDir`（不发聊天 `/cd` 气泡）；FolderPicker 内部用 `loadJob` 取消旧请求避免 stale 覆盖
  - 共用 `ModalOverlay` composable；后端 `DirListingResponse.separator` 字段决定路径拼接，前端不猜 Unix vs Windows
- 知识库（`KnowledgeBaseScene.kt` + `KnowledgeBaseCaptureDialog.kt` / `KnowledgeBaseContextTray.kt` / `KnowledgeBaseMeetingCaptureDialog.kt`）：
  - 知识库面板支持复制 `[[kb:entryId|标题]]` 引用；聊天/工作流消息中的该格式和 AI 返回的 KB `available` 引用都可点击并切到对应知识库文档（`KnowledgeBaseReferences.kt` / `AppState.openKnowledgeBaseEntry`）
  - Silk 聊天输入区和 Workflow composer 上方都显示 KB Context Tray：后端为本轮准备知识库上下文时，前端用状态消息里的 `references(kind=available, path=kb://...)` 渲染卡片，展示手动/固定/自动来源、加入原因与摘要，并可点回原文档；该 KB 上下文状态条会从普通灰色状态列表里过滤掉（`isKnowledgeBaseContextStatusMessage`），避免和 Tray 重复
  - Context Tray 支持"固定下轮 / 排除下轮"控制：选择写进消息合同 `kbContextSelection(pinnedEntryIds, excludedEntryIds)`，随 `ChatClient.sendMessage` 发送，后端按该选择重建下一轮 KB context
  - 聊天与 Workflow 文本消息操作栏支持"📚入库"：选目标 topic 后把消息存为 `candidate` 知识条目（`POST /api/kb/captures`），带 `CHAT` / `AI_RESPONSE` / `WORKFLOW` 来源元数据（AI 消息自动标记为 `AI_RESPONSE`），成功后跳到对应 KB 文档
  - 知识库条目侧栏：candidate inbox 过滤（全部/候选/已发布/已归档）+ 批量发布/归档/并入；会议入库入口（选空间/主题/标签/置信度，存为 candidate 或 published，写入 `MEETING` provenance）
  - M2 空间/权限：按"个人 + 我所在群组"切空间并过滤可访问 topic；topic/entry 编辑器展示空间/读写/状态/来源 badge；无写权限时禁用创建与保存、编辑区只读；owner/team host/topic manager 可在"权限"面板改名称、项目、`read/write/manage` grants、`writeLocked`、`teamMembersCanWrite`
