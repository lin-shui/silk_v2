# KB Copilot: Streaming + Inline Diff Edit + UX Polish

Status: 已完成
Date: 2026-07-10（第1项 2026-07-12 完成；第2项 2026-07-12 完成；第3-5项 2026-07-12 完成）

## Goal

让 KB Copilot 的体验接近 VS Code Copilot + 飞书，消除五个核心差距：

1. **流式响应**：模型推理时用户能看到逐步生成的思考/回复，而不是空转等待。
2. **内联 Diff 编辑**：AI 生成的修改直接在编辑器内以 diff 形式展示，用户逐块确认/拒绝后再应用，而不是"填回编辑器"全量替换。
3. **可折叠侧栏**：左侧知识空间/条目列表支持折叠收起，减少竖栏过多带来的拥挤感，让编辑器获得更多横向空间。
4. **飞书式权限按钮**：权限管理界面从"分栏搜索用户→分配角色"改为每个用户 chip 上直接选择角色，像飞书分享面板那样直观。
5. **聊天内 `$` 快捷引用 KB 文档**：在聊天输入框中输入 `$` 触发 KB 文档选择器，像飞书 `@` 提及一样弹出可搜索的文档列表，选中后自动插入 `[[kb:entryId|title]]` 引用。

## Current State

### KB Copilot
- `POST /api/kb/copilot` 同步 + `POST /api/kb/copilot/stream` SSE 流式双端点 ✅
- 前端 `streamKBCopilot()` 通过 SSE 消费流式事件，侧栏打字机效果实时展示 ✅
- 生成草稿后侧栏显示"草稿预览"区块，用户点击"在编辑器中显示修改"时进入 diff 审查模式 ✅
- diff 审查模式：后端 `DiffChunk` 数据结构 + LCS 行级 diff 算法 → 前端 `DiffReviewPane` 逐块着色显示（新增/删除/修改/未更改），每块支持 ✓ 接受 / ✗ 拒绝，顶部"接受全部"与"应用修改"按钮 ✅
- 用户确认后最终内容写入编辑器，不再全量替换 ✅
- `KnowledgeBaseCopilotDraft` 新增 `diffChunks` 字段（默认空列表，向后兼容）

### 权限管理 ✅
- `TopicAccessDialog` 重构为飞书式统一权限面板：单一搜索框，选中用户后直接选择角色（👁 只读 / ✏ 可写 / ⚙ 管理）添加
- 已添加的用户列表每行显示名称 + 角色 toggle chip（角色间不互斥，可同时选择多个角色）+ 移除按钮 ✕
- 后端接口不变，仅前端重构用户体验

### 聊天引用 KB 文档 ✅
- 聊天输入框（Main.kt）和 Workflow composer 输入框（WorkflowScene.kt）输入 `$` 字符时触发 KB 文档浮动选择器
- 选择器调用 `GET /api/kb/entries/search` 后端端点搜索当前用户可读的已发布条目
- 选中后自动在光标处插入 `[[kb:entryId|title]]` 引用
- `Esc` / 空格 / 点击面板外区域关闭面板
- 面板跟随输入框位置 fixed 定位

## Design

### 1. 流式响应（SSE） ✅ 2026-07-12

**后端改动** ✅：

- 新增 `POST /api/kb/copilot/stream` 端点，返回 `text/event-stream`（SSE）
- 代替当前 `POST /api/kb/copilot` 的同步调用
- SSE 事件流：
  - `event: thinking` → `data: {"text": "..."}`（模型思考过程）
  - `event: text` → `data: {"text": "..."}`（模型回复正文）
  - `event: draft` → `data: {"draft": {...}}`（最终解析出的 KB draft，JSON）
  - `event: applied` → `data: {"entry": {...}}`（applyChanges=true 时，写入后的条目全文）
  - `event: error` → `data: {"message": "..."}`
  - `event: done` → 结束标记
- `DirectModelAgent.processInput()` 的 callback 现在通过 `onStreamEvent` 参数转发 SSE 事件
- 保持 `KnowledgeBaseCopilotRequest` 请求体不变
- 新增 `CopilotStreamEvent` 数据类

**前端改动** ✅：

- `ApiClient` 新增 `streamKBCopilot()`：使用 `fetch` + `ReadableStream` 消费 SSE（当前 fallback 为 `response.text()` 全量解析，后续可升级为真正的逐帧读取）
- `runKnowledgeBaseCopilot()` 改为通过 SSE 事件逐帧更新状态：
  - `assistantReply` 随 `text` 事件实时追加
  - `draft` 在收到 `draft` 事件时设置
  - `appliedEntry` 在收到 `applied` 事件时设置
- 侧栏增加流式打字机效果：AI 说明区实时展示正在生成的回复
- 收到 `text`/`thinking` 事件期间 `isRunning` 保持 true，收到 `done`/`error` 后设为 false

### 2. 内联 Diff 编辑（Accept / Reject）

取代"填回编辑器"全量替换，改为在 Markdown 编辑器内以行内高亮展示 AI 建议的修改，用户逐块确认（✓）或拒绝（✗），全部确认后落盘。直接走完整 Inline Diff，不拆阶段。 ✅

**后端改动** ✅：

- `KnowledgeBaseCopilotDraft` 增加 `diffChunks: List<DiffChunk>` 字段 ✅
- `DiffChunk` 定义：
  ```kotlin
  @Serializable
  data class DiffChunk(
      val type: String, // "unchanged" | "deleted" | "inserted" | "modified"
      val originalText: String = "",
      val newText: String = "",
      val lineStart: Int = 0,
      val lineEnd: Int = 0,
  )
  ```
- 后端在解析出 `update_entry` 草稿后，对 `entry.content`（原）和 `draft.content`（新）做逐行 diff ✅
- diff 算法：LCS（Longest Common Subsequence），输出结构化 `DiffChunk` 列表 ✅
- 响应中 `draft.diffChunks` 被前端消费 ✅

**前端改动** ✅：

- `KnowledgeBaseScene.kt` 新增 `showDiffReview` / `diffChunks` / `acceptedChunkIndices` / `rejectedChunkIndices` 状态 ✅
- 新增 `DiffReviewPane` composable：逐块着色显示（unchanged/deleted/inserted/modified），每块 ✓ 接受 / ✗ 拒绝 ✅
- 顶部"接受全部"快捷按钮 ✅
- 接受：该块的新内容写入最终合并结果；拒绝：恢复原内容 ✅
- 全部确认后点击"应用修改"写入编辑器 ✅
- 侧栏"把草稿填回编辑器"按钮替换为"在编辑器中显示修改"，点击激活 `DiffReviewPane` ✅
- "退出对比"恢复普通编辑态 ✅

**实现方式**：使用独立的 `DiffReviewPane` 替换编辑器区域，而非在 textarea 上叠加（Compose for Web textarea 无法逐行着色），逐块展示 diff 内容+状态标签+操作按钮，最终合并结果写入编辑器。

### 3. 可折叠左侧栏

当前 KB 页面有三栏：左侧知识空间/条目列表、中间编辑器、右侧 Copilot 侧栏。竖栏过多让编辑器显得拥挤，需让左侧栏可折叠。

**前端改动**：

- `TopicSidebar`（左栏）增加折叠/展开按钮（`◀` / `▶` 或类似图标）
- 折叠时左栏缩为仅显示图标按钮的窄条（约 40-48px），不销毁 composable 树，仅隐藏内容
- 展开按钮固定在折叠窄条内，点击恢复全宽
- 折叠/展开状态通过 `remember` + `localStorage` 持久化（键名如 `kb_sidebar_collapsed`）
- 折叠后编辑器自动扩展至左栏释放的横向空间
- 若当前有 Copilot 侧栏打开，编辑器在左栏折叠后可获得近乎全宽的编辑区域

**交互细节**：

- 折叠按钮放在 `TopicSidebar` 顶部或底部，与现有 UI 风格一致
- 折叠动画：简单的宽度 transition（`transition("width", 300)`），无需复杂动效
- 折叠窄条上保留 KB 图标或“☰”菜单入口，提示用户可展开
- 选中条目时若侧栏处于折叠状态，条目内容正常渲染在编辑器区——折叠仅隐藏列表，不影响功能

### 4. 飞书式权限管理（用户 Chip 直接切换角色）

当前 `TopicAccessDialog` 将读/写/管理三个角色分三个独立 `UserSearchSelector`，用户须在不同列表间重复搜索同一人。改为飞书式分享面板：每个被添加的用户只出现一次，角色在该用户的 chip 上以 toggle 按钮切换。

**前端改动**：

- `TopicAccessDialog` 重构：
  - 移除三个独立的 `UserSearchSelector`，替换为统一的用户搜索与角色分配面板
  - 搜索用户后，直接选择该用户的角色（只读/可写/可管理），然后添加
  - 已添加的用户列表每行显示：头像/名称 + 角色选择器（三个 toggle 按钮 `👁 只读` / `✏ 可写` / `⚙ 管理`）+ 移除按钮
  - 切换角色直接更新对应列表（`readUserIds`/`writeUserIds`/`manageUserIds`）
  - 保持 `writeLocked` 和 `teamMembersCanWrite` toggle 不变
- 后端无需改动（接口不变，仅前端重新编排用户体验）

**交互细节**：

```
┌─────────────────────────────────┐
│  主题权限                        │
│                                  │
│  🔍 搜索用户名称添加权限...       │
│                                  │
│  ── 已有成员 ──                  │
│  👤 张三    [👁] [✏] [⚙]  ✕    │
│  👤 李四    [👁] [✏] [⚙]  ✕    │
│  👤 王五    [ ] [✏] [ ]  ✕    │
│                                  │
│  [🔒 锁定写入] [👥 团队成员可写]  │
│                                  │
│           [取消]  [保存权限]      │
└─────────────────────────────────┘
```

- 每个角色按钮独立 toggle：点击高亮表示"授予此角色"，再次点击取消
- 角色间不互斥（用户可以同时是只读+可写+管理）
- 搜索框支持模糊搜索，选中后弹出角色选择确认再添加

### 5. 聊天输入框 `$` 快捷引用 KB 文档

在聊天/Workflow 输入框中输入 `$` 字符时，弹出 KB 文档选择器面板，类似飞书的 `@` 提及体验。选中后自动在光标处插入 `[[kb:entryId|title]]` 引用。

**前端改动**：

- `Main.kt` / `WorkflowScene.kt` 的输入框增加 `$` 触发检测（`onInput` 中检测当前文本末尾或光标前的 token）
- 检测到 `$`（或 `$$` 后跟搜索词）时，弹出浮动选择器面板
- 选择器面板内容：
  - 搜索输入框（预填当前搜索词）
  - 搜索结果列表：调用后端 `GET /api/kb/entries/search?q=...&userId=...` 或复用现有 KB 列表
  - 每条结果展示：标题 + 所属主题 + 空间标识
  - 选中后自动关闭面板，在输入框中插入 `[[kb:entryId|title]]`，光标移到 `]]` 前
- 面板通过 `Esc` 关闭，点击面板外区域关闭
- 面板位置跟随光标

**后端改动**：

- 新增 `GET /api/kb/entries/search` 端点（若不存在），支持按关键字搜索当前用户可读的 PUBLISHED 条目
- 返回 `List<KBEntrySearchResult>`：`entryId` / `title` / `topicName` / `spaceLabel`
- 搜索范围：当前用户 `canRead` 的所有 PUBLISHED 条目，排除 DELETED
- 简单的标题/内容/标签文本搜索（复用现有 `listTopics` + `listEntries` 过滤，小规模下够用）

**交互细节**：

```
输入: "请根据 $部 来回答"
                   ┌──────────────┐
                   │ 🔍 部署      │
                   │ 📄 部署流程   │
                   │     └ 运维手册 │
                   │ 📄 部署检查清单│
                   │     └ 项目规范 │
                   └──────────────┘
结果: "请根据 [[kb:entry1|部署流程]] 来回答"
```

- 仅在聊天/Workflow 输入框触发，KB 编辑器内不触发（已有 KB Copilot 侧栏）
- `$` 后紧跟非空格字符时开始搜索，`$` 后为空格时保持弹出空面板
- 支持连续输入搜索词缩小结果
- 选中后 `[[kb:...]]` 引用在发送消息时被后端 `resolveKnowledgeBasePromptContext` 正常解析

## Affected Code Surfaces

| 面 | 文件 | 改动 |
| --- | --- | --- |
| 后端路由 | `Routing.kt` | 新增 `POST /api/kb/copilot/stream` SSE 端点 |
| 后端 KB Copilot | `kb/KnowledgeBaseCopilot.kt` | `executeKnowledgeBaseCopilot` 支持流式 callback；返回 `diffChunks` |
| 后端 AI Agent | `ai/DirectModelAgent.kt` | 确认流式 callback 接口可被 SSE 复用 |
| 后端 Models | `kb/KnowledgeBaseCopilot.kt` | 新增 `DiffChunk` 数据类 |
| 前端 API 层 | `ApiClient.kt` | 新增 `streamKBCopilot()` SSE 消费方法 |
| 前端 KB 场景 | `KnowledgeBaseScene.kt` | `runKnowledgeBaseCopilot` 改为流式更新；新增 diff 模式状态 |
| 前端编辑器 | `KnowledgeBaseScene.kt` | 新增 inline diff 渲染组件（diff 高亮层 + accept/reject 按钮） |
| 前端侧栏 | `KnowledgeBaseScene.kt` | 流式打字机效果、"在编辑器中显示修改"按钮 |
| 前端左栏 | `KnowledgeBaseScene.kt`（`TopicSidebar`） | 新增折叠/展开按钮、折叠窄条、折叠状态持久化 |
| 前端权限对话框 | `KnowledgeBaseScene.kt`（`TopicAccessDialog`） | 重构为统一角色切换面板（chip 内 toggle 只读/可写/管理） |
| 前端聊天输入 | `Main.kt`、`WorkflowScene.kt` | 新增 `$` 触发检测 + KB 文档浮动选择器 |
| 后端搜索 API | `Routing.kt` | 新增 `GET /api/kb/entries/search` 搜索端点 |

## Risks

- SSE 读取在 Compose for Web 上需要原生 JS `EventSource` 或 `fetch` + `ReadableStream` 桥接，Compose 协程侧需要处理好取消与生命周期
- 后端 SSE 端点需要设置正确的 `Content-Type: text/event-stream` 和 `Cache-Control: no-cache`
- diff 块过大时（全文 rewrite），逐块 accept/reject 体验反而不如全量替换，需要依赖"接受全部"快捷按钮兜底
- 流式生成中用户切换条目/关闭侧栏应自动取消 SSE 连接，避免 stale 状态竞争
- 流式响应时同时输出 `silk_kb_action`，需要等完整回复结束才能解析出 `draft`；建议先流式推 `text` 事件，推理结束后再推 `draft` 事件
- 左侧栏折叠后，编辑器宽度会动态变化，编辑器内 diff 高亮层的坐标可能需要联动重算
- 折叠状态建议用 localStorage 持久化，避免每次打开 KB 页都重新折叠
- 权限面板改为单用户多角色后，需要处理好 UX 边界：当用户同时具有读写管理三个角色时，chip 上三个 toggle 都高亮可能视觉过载；建议限制默认只高亮最高权限角色，其他可选
- `$` 触发检测需要处理好中文输入法（IME）场景：输入中文拼音 `s` 时不应触发；建议在 `input`/`compositionend` 事件中检测 `isComposing`，仅非合成状态时触发
- `$` 选择器搜索需要后端新端点；小规模下先做内存文本搜索，不需要向量索引

## Verification

- 后端：`./gradlew :backend:test`，补充 `KnowledgeBaseCopilotTest` 覆盖 SSE 路由与 diff 生成
- Web 编译：`./gradlew :frontend:webApp:compileProductionExecutableKotlinJs`
- 手动测试：打开 KB 页 → 选中条目 → 打开 Copilot 侧栏 → 输入指令 → 确认流式打字机效果 → 确认 diff 显示 → 确认 accept/reject 功能

## Current Status

- 计划初稿完成，待 team 评审。
- 五个改动一并实施：流式 SSE + 内联 Inline Diff + 可折叠左侧栏 + 飞书式权限按钮 + `$` 快捷引用 KB。
- 不拆阶段，不搞 split diff 过渡方案。
- 权限重构和 `$` 引用为纯前端改动（`$` 引用需增加一个后端搜索端点），与后端 streaming/diff 可并行开发。
