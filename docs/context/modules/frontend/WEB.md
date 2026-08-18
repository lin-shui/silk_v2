# Web

## Entry Surface

- `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt`
- `AppState.kt`
- `ApiClient.kt`
- `ConversationScene.kt`
- `ConversationDetailScaffold.kt`
- `ConversationChromeStyles.kt`
- `ConversationComposerRoomTools.kt`
- `ConversationRoomActions.kt`
- `ConversationRoomMembersDialog.kt`
- `KnowledgeBaseScene.kt`
- `WorkflowScene.kt`
- `AudioDuplexScene.kt`
- `SettingsScene.kt`
- `SilkChatStyles.kt` -- claudian 风格 CSS 样式注入（消息卡片、头像、hover 操作栏、动画）
- `ApiClient.kt` 提供 Workflow Room GitHub binding 的脱敏状态读取、绑定和解绑 API；PAT 只作为请求字段，不进入 Web 状态模型，换绑时可留空复用服务端已有密文。Workflow Team Channel 头部提供 GitHub 集成面板，Owner 可绑定/换绑/解绑，成员只读查看仓库、接收方式（定时同步/Webhook）、事件范围、最近同步和可恢复错误。`workspace/WorkspaceApiClient.kt` 提供 Issue-to-Workspace 请求合同和可选 `linkedGithubRef` DTO 字段；GitHub 卡片链接会打开 GitHub，Issue 的“开始开发”会进入工作区创建、目录信任和 Issue 摘要展示流程，摘要以 `SYSTEM` 消息发送且不触发 Agent
- Room/Team Channel 头部提供“管理 Agent”入口；Workspace 头部由 Owner 管理该 Workspace 的 Agent。`AgentBindingManagementDialog.kt` 复用 `/api/agent-bindings` 合同，按具体 `agentInstanceId` 展示 Agent 类型、设备和在线状态，支持添加、编辑、审批、移除、Room 唯一 mention 与 Workspace `只读 / 需审批 / 放行` 三档访问模式。Workspace 的当前 Agent 也保存具体实例，同类型 Agent 位于不同设备时不会再按类型猜测；`/device` 继续负责设备/Agent 库存、安全撤销与全局使用范围总览

## Current Shape

- Compose for Web
- 新建 Workspace 与 GitHub Issue “开始开发”会从当前用户所有 ACTIVE Agent 实例中选择具体 `agentInstanceId`；同类型多设备全部列出，显示自定义 Agent 名、设备名、类型与在线状态，离线实例可见但不可选。新建 Workspace 默认 `APPROVAL_REQUIRED`，用户可改为只读或放行。目录选择和信任请求固定到所选实例；新建时会自动预填当前用户在同一工作群组、同一 Agent 实例上最近成功使用的目录，切换实例时改读该实例自己的记忆，避免不同设备路径串用
- `/device` 的 ACTIVE 设备和 Agent 卡片支持所有者重命名；名称更新不影响设备密钥、Agent 类型或 Binding，并同步用于 Binding、Workspace 当前 Agent 与各实例选择项。Agent 卡片同时提供 `原生默认 / 需要审批 / 只读 / 自动执行` 四档运行权限，保存到 Silk 后端并从下一轮生效，不要求设备侧重启
- 登录后是左侧 `NavRail` + 右侧内容区；联系人作为底部全局入口放在设置齿轮上方，退出登录统一放在设置页账户区
- `/device` 是复用现有登录态的设备与外部 Agent 管理入口；无登录态先显示既有登录页，登录后回到配对页。`/device#code=ABCD-EFGH` 会把 fragment 中的短码保留在当前标签页、立即清理地址栏并在登录后自动预览；首次打开链接和已打开 `/device` 页面再次粘贴链接都会消费 `hashchange`，不需要用户再次复制短码。错误账号只能得到不泄露详情的提示并可切换账号，任何情况下仍需点击“批准连接”，不会因打开链接自动绑定。手工输入短码继续兼容。页面还支持设备和 Agent 列表/在线状态/撤销、Agent 运行权限、Room/Workspace Binding 增删改、访问模式及双主体审批；Agent 与 Binding 选择项同时展示设备名，同一 Agent 类型可在不同设备上作为独立实例选择。Room Binding 固定为消息范围并需要设置 Room 内唯一的提及词（如 `cc`、`cc2`）；Workspace 只显示 `只读 / 需审批 / 放行`，不再暴露消息、文件、命令等粒度复选框。Agent 主卡片显示设备归属、类型、版本、连接状态和 owner 可编辑的运行权限，声明能力按需在详情面板查看。待审批项显示缺失的批准侧，并仅按服务端能力字段展示批准、拒绝、编辑或撤销操作。导航栏底部也提供同一入口
- 主 Tab：
  - 会话
  - Knowledge Base
  - Audio Duplex
- “会话”由 `ConversationScene` 统一展示 `CHAT / WORKFLOW / SILK_PRIVATE`；Silk AI 固定置顶，其余 Room 按最近活动倒排，有消息时使用最后消息时间、无消息时使用创建时间。当前 Room 收到或发出持久化消息后通过已有 WebSocket 本地立即重排，15 秒列表轮询仅兜底其他 Room、跨设备活动和未读状态；支持搜索、全部/工作群组/聊天群组筛选、未读和 cc-connect 状态。侧栏 `+` 是统一“添加会话”入口，一级以 Tab 切换创建群组/通过邀请码加入，创建模式下以带字段标签的单选控件二级选择聊天群组/工作群组。非 Silk Room 的 `⋯` 菜单向所有成员提供邀请，Owner 提供重命名/删除，非 Owner 提供退出；不提供批量退出模式，成功后立即更新或移除本地列表
- 创建工作群组只建 Room + Workflow 元数据，无需 Bridge；用户显式新建 Workspace 时才进入目录信任和 Agent 选择流程
- GitHub binding API 返回 `enabled/provider/owner/repo/events/ingestionMode/lastDeliveryAt/lastSuccessfulPollAt/syncError/status` 脱敏摘要；当前 Web 已接入 `ApiClient` 合同和 Team Channel 管理面板，模式由后端自动选择，绑定状态与成员只读/Owner 管理权限由后端最终裁决
- 普通聊天和工作群组共享 `ConversationDetailScaffold` / `ConversationPaneScaffold` 几何骨架，并复用 `ConversationHeader`、`ConversationHeaderActionButton` 与 `ConversationComposer*` 组件统一标题区、右侧图标操作、KB/快捷区、输入框和发送/停止按钮。普通聊天与 Workflow Team Channel 还共用 `ConversationRoomHeaderActions`，固定提供会话文件、Markdown/Obsidian 导出、邀请和单一成员入口（支持 Vault 时多一个目录选择按钮）；`ConversationRoomMembersDialog` 统一成员列表和面板内添加成员，两类 Room 打开添加区时都先展示尚未入群的联系人，并可按用户名、姓名或电话号码搜索其他用户，清空搜索词后恢复联系人候选。搜索允许单字符输入；输入后尚未提交时显示搜索提示，只有完成搜索且结果为空时才显示“未找到”；后端按精确、前缀和包含统一排序，用户名/姓名再支持有序字符及有限编辑距离的模糊匹配，手机号不做拼写纠错，并忽略大小写及字段中的分隔符。Workflow 仅 Owner 可添加和移除成员。两者还通过 `ConversationRoomComposerTools` 共用目录上传、文件上传、截屏和语音输入；截图预览及发送也走同一 Room 级实现。Workspace Agent 仍只保留编码会话控制，不展示这些依赖具体 Room 文件空间的工具；聊天多选与 Workspace Agent/权限/代码审查仍由各自主体提供。固定区使用 `silk-conversation-fixed-region`，消息区是唯一的 `silk-conversation-scroll-region`，业务状态和消息 scope 仍由各自主体管理
- 桌面宽度下，当前工作群组在 Room 下展开 Team Channel、我的工作区、Co-pilot、成员、历史共享和已归档分组；再次点击当前工作群组只收拢/展开该子树，不切换或重载右侧会话。Room 树展开状态按 Room 持久化到 `silk_room_tree_expanded_<roomId>`，内部分类折叠状态持久化到 `silk_room_workspace_sections_<roomId>`。Room 侧栏可收起为 28px 重开条，整条均可点击展开，状态持久化到 `silk_room_list_collapsed`，并兼容读取旧 `silk_wf_list_collapsed` / `silk_chat_list_collapsed` 偏好。统一会话壳层根据会话容器宽度在 `<= 1100px` 时切为列表/详情单页、隐藏折叠控件且强制展示完整列表，并使用单一 Workspace 下拉选择器；根据详情容器宽度在 `<= 900px` 时把代码审查改为上下分栏，并保留 viewport media query 作为旧浏览器兜底。Conversation detail 到消息区必须保持完整的 `flex + min-height: 0` 高度链，避免消息区折叠为零高度
- 旧 `GroupListScene`、`ChatScene` 和 `WorkflowScene` 外层列表已删除；聊天与工作群组仅保留统一壳层下的 `ChatAppWithGroup` / `WorkflowRoomView` 主体，创建和邀请码加入共用 `ConversationScene` 内的添加会话弹窗

## Build-Time Facts

- 从 `.env` 读取后端端口并生成 `BuildConfig.kt`
- 生产构建最终供后端静态分发
- `silk.sh` 使用 `scripts/silk_static_server.py` 提供本地生产静态包，使 `/device` 直达请求回落到 SPA `index.html`
- JS 轻量测试跑 `nodeTest`，不依赖浏览器自动化

## Watch Points

- 改文件消息/下载逻辑时，优先看 `FileContracts.kt` / `FileContractsTest.kt`
- 改布局壳层时，确认 `AppState.kt` 与 `Main.kt` 的 scene/tab 状态流
- 改 Audio Duplex 时看 `AudioDuplexScene.kt` 与后端 `/ws/audio-duplex`
- 工作群组主体（`WorkflowScene.kt`）含 Folder Picker：
  - Team Channel 与 Personal Workspace 使用独立 Tab；composer、停止生成、卡片回复和 Agent 回包都携带显式 `scope/workspaceId`
  - Team Channel 中的人工对话不自动触发 AI；composer 复用普通聊天的 `@Silk` 快捷按钮，只有以 `@Silk` 开头的消息进入 Silk `DirectModelAgent`。Workspace 由后端路由到目标 Owner 的编码 Agent，不显示 Team Channel 的 `@Silk` 按钮。Observer 只读发送时共享的历史，不能操作卡片或本地控制面
  - Workflow 列表按当前 JWT 成员身份发现，非 Owner 加入后也能看到共享 Room；header 的“成员”面板向所有成员展示名单，Owner 可按登录名、姓名或手机号搜索并增删成员
  - 工作区可在 Web 内创建、重命名、切换共享、管理 Room 成员 Co-pilot、归档/恢复/删除；他人当前共享工作区按 Owner 身份稳定分组（Observer 与 Co-pilot 都保留在 Owner 名下），Co-pilot 另有操作权限快捷分组；已撤销共享但仍可合法读取的消息单列为“历史共享”，选项显示“Owner - 最后共享名称”，窄屏收敛为去重后的单一下拉选择器
  - 切换 Team Channel 或 Workspace 时，消息区在内容完成布局后滚到当前流底部；显式跳转到某条消息时由消息定位逻辑接管，不被自动滚底覆盖
  - 活动状态每 5 秒从 Workspace API 刷新；Observer/已归档/已撤销共享状态不渲染 composer，Co-pilot 明确标识正在操作 Owner 的远程设备
  - CC 状态、切目录、Agent/Workspace 访问模式与 Source Control 全部使用 `workspaceId`，不再使用 `groupId` 作为 Agent context key
  - header 显示 agent 名（取自 `Message.userName`）和当前工作目录
  - "更改" 链接 / 创建工作流的"选择…" 按钮 → `FolderPickerDialog`（面包屑 + `..` + 子目录 + 手动输入）
  - 切目录走 HTTP `cdCcDir`（不发聊天 `/cd` 气泡）；FolderPicker 内部用 `loadJob` 取消旧请求避免 stale 覆盖
  - 共用 `ModalOverlay` composable；后端 `DirListingResponse.separator` 字段决定路径拼接，前端不猜 Unix vs Windows
- 知识库（`KnowledgeBaseScene.kt` + `KnowledgeBaseCaptureDialog.kt` / `KnowledgeBaseContextTray.kt` / `KnowledgeBaseMeetingCaptureDialog.kt`）：
  - 左侧 `TopicSidebar` 和中间 `EntrySidebar` 均支持折叠，折叠按钮统一放在 header 右侧按钮区最左位（`«`），折叠后缩为 28px 窄条（`ReopenBar`），状态经 `LayoutPrefs`（`kb_sidebar_collapsed` / `kb_entry_sidebar_collapsed`）持久化到 localStorage
  - `TopicAccessDialog` 重构为飞书式统一权限面板：单一搜索框 + 角色选择（👁 只读 / ✏ 可写 / ⚙ 管理）+ 已用用户列表含角色 toggle chip + 移除按钮
  - 聊天/Workflow 输入框输入 `$` 触发 KB 文档浮动选择器，调用 `GET /api/kb/entries/search` 搜索已发布条目，选中后插入 `[[kb:entryId|title]]` 引用
- 知识库面板支持复制 `[[kb:entryId|标题]]` 引用；聊天/工作流消息中的该格式和 AI 返回的 KB `available` 引用都可点击并切到对应知识库文档（`KnowledgeBaseReferences.kt` / `AppState.openKnowledgeBaseEntry`）
- Silk 聊天输入区和 Workflow composer 上方都显示 KB Context Tray：后端为本轮准备知识库上下文时，前端用状态消息里的 `references(kind=available, path=kb://...)` 渲染卡片，展示手动/固定/自动来源、加入原因与摘要，并可点回原文档；该 KB 上下文状态条会从普通灰色状态列表里过滤掉（`isKnowledgeBaseContextStatusMessage`），避免和 Tray 重复
- Context Tray 支持"固定下轮 / 排除下轮"控制：选择写进消息合同 `kbContextSelection(pinnedEntryIds, excludedEntryIds, excludedSpaceIds)`，随 `ChatClient.sendMessage` 发送，后端按该选择重建下一轮 KB context；聊天页与 Workflow composer 会从最近一条带 `kbContextSelection` 的用户消息恢复条目级选择，并把用户级 `excludedSpaceIds` 偏好回填到当前 Tray
- 聊天与 Workflow 文本消息操作栏支持"📚入库"：选目标 topic 后把消息存为 `candidate` 知识条目（`POST /api/kb/captures`），带 `CHAT` / `AI_RESPONSE` / `WORKFLOW` 来源元数据（AI 消息自动标记为 `AI_RESPONSE`），成功后跳到对应 KB 文档
- KB 左栏新增 `KB Memory` 快捷面板：显示长期记忆是否启用，并弹出 memory 管理弹层；Web 端现可读写 `GET/PUT /api/kb/context-preferences` 与 `GET/POST/DELETE /api/kb/memory*`，支持查看/新增/删除 memory，以及控制 `memoryEnabled` / `autoCaptureEnabled` / `ephemeralSessionEnabled`；其中 `autoCaptureEnabled` 已接到后端低风险自动记忆（回答语言 / 风格 / 代码语言偏好）
- KB Memory 弹层支持团队空间内的群组记忆管理：当处于团队空间时，弹层顶部出现"个人记忆 / 群组记忆"Tab 切换；群组 Tab 下可查看/新增/删除该群组的共享记忆，新建/删除操作经 `groupId` 参数路由到 `/api/kb/memory` 端点；后端保持 ACL 隔离，非群组成员看不到群组记忆条目
- KB 编辑器工具栏新增 `AI 协作`：切换 `KB Copilot` 右侧侧栏，通过 `POST /api/kb/copilot`（同步）或 `POST /api/kb/copilot/stream`（SSE 流式）让后端生成编辑草稿；流式模式下 `ApiClient.streamKBCopilot()` 消费 SSE 事件（`thinking`/`text`/`draft`/`applied`/`error`/`done`），侧栏实时展示打字机效果；支持两种模式——**条目模式**（有选中条目时围绕当前条目生成 `update_entry` 草稿）和**主题模式**（无条目时根据用户指令在当前主题创建新条目，生成 `create_entry` 草稿）；侧栏与编辑器并列显示，支持宽度调节，切换条目时自动关闭；支持多轮对话（`ConversationTurn`），侧栏内显示历史对话、继续修改输入框和"🔄 新对话"重置按钮，切换条目/主题时自动清空历史；用户在主题模式下可从侧栏"在编辑器中显示修改"直接创建新条目；也可直接让后端按当前 caller 权限写回或创建条目。条目模式下，草稿附带 `diffChunks`（后端 LCS 行级 diff），点击"在编辑器中显示修改"进入 `DiffReviewPane` 审查模式，逐块 ✓ 接受 / ✗ 拒绝，全部确认后写入编辑器，取代全量替换。
- Candidate inbox 支持把候选条目并入同一 knowledge space 下的其他 topic 文档；合并对话框会跨 topic 拉取目标文档列表，合并完成后自动切到实际目标文档
  - 知识库条目侧栏：candidate inbox 过滤（全部/候选/已发布/已归档）+ 批量发布/归档/并入；会议入库入口（选空间/主题/标签/置信度，存为 candidate 或 published，写入 `MEETING` provenance）
  - M2 空间/权限：按"个人 + 我所在群组"切空间并过滤可访问 topic；topic/entry 编辑器展示空间/读写/状态/来源 badge；无写权限时禁用创建与保存、编辑区只读；owner/team host/topic manager 可在"权限"面板改名称、项目、`read/write/manage` grants、`writeLocked`、`teamMembersCanWrite`
  - KB Web UX backlog 已继续回补：条目标题默认展示为静态标题，单击后才进入编辑态并复用现有保存链路；来源群组 / 工作流 / 来源消息字段本身可点击回跳，不再额外堆叠按钮；消息回跳优先在目标消息容器内按 `data-message-id` 定位并只滚动对应容器
  - KB 编辑器右上角高频动作已收敛为模式切换、复制引用、保存和“菜单”入口；低频 move / merge / lifecycle / delete / export 走统一 menu，topic 权限/删除则移到左侧“知识空间”的管理态，不再混在单条 entry 工具栏里；模式切换会按可用宽度在完整标签 / 紧凑单字 / 下拉选择三档之间自适应，避免窄栏把“编辑｜预览｜分栏”挤没；菜单展开后支持点击外部区域或按 `Esc` 收起
