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
- Context Tray 支持"固定下轮 / 排除下轮"控制：选择写进消息合同 `kbContextSelection(pinnedEntryIds, excludedEntryIds, excludedSpaceIds)`，随 `ChatClient.sendMessage` 发送，后端按该选择重建下一轮 KB context；聊天页与 Workflow composer 会从最近一条带 `kbContextSelection` 的用户消息恢复条目级选择，并把用户级 `excludedSpaceIds` 偏好回填到当前 Tray
- 聊天与 Workflow 文本消息操作栏支持"📚入库"：选目标 topic 后把消息存为 `candidate` 知识条目（`POST /api/kb/captures`），带 `CHAT` / `AI_RESPONSE` / `WORKFLOW` 来源元数据（AI 消息自动标记为 `AI_RESPONSE`），成功后跳到对应 KB 文档
- KB 左栏新增 `KB Memory` 快捷面板：显示长期记忆是否启用，并弹出 memory 管理弹层；Web 端现可读写 `GET/PUT /api/kb/context-preferences` 与 `GET/POST/DELETE /api/kb/memory*`，支持查看/新增/删除 memory，以及控制 `memoryEnabled` / `autoCaptureEnabled` / `ephemeralSessionEnabled`；其中 `autoCaptureEnabled` 已接到后端低风险自动记忆（回答语言 / 风格 / 代码语言偏好）
- KB 编辑器工具栏新增 `AI 协作`：打开 `KB Copilot` 对话框，通过 `POST /api/kb/copilot` 让后端围绕当前条目生成 `update_entry` 草稿；用户可先把草稿填回编辑器再手动保存，也可直接让后端按当前 caller 权限写回条目
- Candidate inbox 支持把候选条目并入同一 knowledge space 下的其他 topic 文档；合并对话框会跨 topic 拉取目标文档列表，合并完成后自动切到实际目标文档
  - 知识库条目侧栏：candidate inbox 过滤（全部/候选/已发布/已归档）+ 批量发布/归档/并入；会议入库入口（选空间/主题/标签/置信度，存为 candidate 或 published，写入 `MEETING` provenance）
  - M2 空间/权限：按"个人 + 我所在群组"切空间并过滤可访问 topic；topic/entry 编辑器展示空间/读写/状态/来源 badge；无写权限时禁用创建与保存、编辑区只读；owner/team host/topic manager 可在"权限"面板改名称、项目、`read/write/manage` grants、`writeLocked`、`teamMembersCanWrite`
  - KB Web UX backlog 已继续回补：条目标题默认展示为静态标题，单击后才进入编辑态并复用现有保存链路；来源群组 / 工作流 / 来源消息字段本身可点击回跳，不再额外堆叠按钮；消息回跳优先在目标消息容器内按 `data-message-id` 定位并只滚动对应容器
  - KB 编辑器右上角高频动作已收敛为模式切换、复制引用、保存和“菜单”入口；低频 move / merge / lifecycle / delete / export 走统一 menu，topic 权限/删除则移到左侧“知识空间”的管理态，不再混在单条 entry 工具栏里；模式切换会按可用宽度在完整标签 / 紧凑单字 / 下拉选择三档之间自适应，避免窄栏把“编辑｜预览｜分栏”挤没；菜单展开后支持点击外部区域或按 `Esc` 收起
