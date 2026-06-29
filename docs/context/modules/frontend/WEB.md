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
- 知识库面板支持复制 `[[kb:entryId|标题]]` 引用；聊天/工作流消息中的该格式和 AI 返回的 KB `available` 引用都可点击并切到对应知识库文档
- Silk 聊天输入区和 Workflow composer 上方都会显示 KB Context Tray：当后端为本轮问题准备了知识库上下文时，前端用状态消息里的 `references(kind=available, path=kb://...)` 渲染卡片，展示手动 / 固定 / 自动来源、加入原因与摘要，并可点回原文档
- Context Tray 支持“固定下轮 / 排除下轮 / 关闭某个空间的自动推荐”最小控制闭环：Web 会把用户选择写进消息合同里的 `kbContextSelection(pinnedEntryIds, excludedEntryIds, excludedSpaceIds)`，即使当前已清空也会显式落盘，后端按该选择重建下一轮 KB context；space 级关闭只抑制自动召回，不覆盖手动引用或 pinned 条目
- Web 还会通过 `GET/PUT /api/kb/context-preferences` 维护用户级长期空间偏好：聊天页和 Workflow 面板初始化时会先拉取持久化的 `excludedSpaceIds`，再与最近一条用户消息里的 `kbContextSelection` 合并；因此“关闭某个空间的自动推荐”不仅能跨刷新 / 重连保留，也不会因为用户下一条消息没有手动再带一次而失效。Workflow 面板仍会把这类 KB context 状态从普通灰色状态列表中过滤掉，避免和 Tray 重复展示
- 聊天与 Workflow 的文本消息操作栏支持“📚入库”：选择目标 topic 后会把消息保存成 `candidate` 知识条目；用户消息会带 `CHAT` / `WORKFLOW` 来源元数据，AI 回复会带 `AI_RESPONSE` 来源元数据，保存成功后直接跳到对应 KB 文档
- KB 编辑区的条目 meta bar 不再只显示来源 badge，还会展开 provenance 明细：来源群组、工作流 id、消息 id 摘要、置信度、创建人/更新人，便于在 candidate inbox 和已发布条目中快速追溯上下文
- 知识库条目侧栏提供 candidate inbox 过滤与批处理：可按“全部 / 候选 / 已发布 / 已归档”切换；候选条目支持勾选后批量发布/归档/并入已有文档，单条 candidate 也可在编辑区直接发布，已发布条目可归档，归档条目可重新发布
- 候选条目支持最小 merge 流：在 KB 编辑区可把当前 `candidate` 并入同 topic 下的已有文档；candidate inbox 里也可把多条已选候选批量并入同一目标文档。前端会先更新目标条目内容和 tags，再把原 candidate 归档
- 知识库条目侧栏还提供“会议入库”入口：用户可在 KB 页面直接录入会议纪要，选择目标空间/主题、标签、置信度，以及保存为 `candidate` 或直接 `published`；该入口走统一 `POST /api/kb/captures` 契约并写入 `MEETING` provenance
- 知识库面板已接上 M2 骨架：
  - 左侧按“个人 + 我所在群组”切换空间，前端对可访问 topic 做 personal/team 过滤
  - topic / entry 编辑器展示空间 badge、读写 badge、条目状态与来源
  - entry 列表行会直接展示状态与来源 badge，便于快速区分 candidate / published 以及聊天 / AI 回答 / 工作流等沉淀来源
  - topic 无写权限时，条目创建按钮与 Markdown 保存入口禁用，编辑区切只读态
  - 新建 topic 可直接指定 personal 或某个 team(group) 空间，默认 team topic 打开 `teamMembersCanWrite`
  - owner / team host / topic manager 可在编辑器工具栏打开“权限”面板，修改 topic 名称、项目、`read/write/manage` grants、`writeLocked`、`teamMembersCanWrite`
