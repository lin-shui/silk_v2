# KB Copilot: 三状态流程改造（弱聊天、强任务、强审阅）

Status: 已完成
Date: 2026-07-13

## Goal

将 KB Copilot 从"平铺所有功能于一栏"改为明确的三阶段流程：

**提出需求 → 预览草稿 → 审核并应用**

## 改动内容

### 1. 三状态侧栏

`KnowledgeCopilotSidebar` 现在内部按 `CopilotSidebarState` 渲染三个独立 composable：

- **`CopilotInputState`**（状态1：输入需求）
  - 默认只显示：当前条目、修改要求输入框、"生成修改"主按钮
  - 删除了："新对话"按钮、"直接写回知识库"开关、AI 说明、草稿预览、对话历史
  - "生成草稿" → "生成修改"
  - "新对话" → 右上角 `⋯` 菜单中"清空会话"
  - `⋯` 菜单包含：清空会话、查看历史对话、Copilot 设置、反馈问题

- **`CopilotPreviewState`**（状态2：草稿预览）
  - 当 `draft` 非空时自动切换
  - Header 显示"← 返回修改要求"按钮
  - 合并原"AI 说明"+"草稿预览"为：结果说明、变更摘要（从 diffChunks 自动计算）、精简预览
  - "在编辑器中显示修改" → "查看并审阅修改"（主按钮）
  - "重新生成"为次级文字按钮

- **`CopilotReviewState`**（状态3：修改审阅）
  - 当 `showDiffReview` 为 true 时接管侧栏
  - 显示进度："已处理 X / Y 处"
  - 块级动作：[接受此修改] [跳过此修改]
  - 全局动作：[接受全部] [放弃全部修改]
  - 全部决定后显示："已选择 X 项修改，跳过 Y 项" + [应用到文档] [取消]
  - 应用后自动关闭侧栏

### 2. DiffReviewPane 简化

- 移除了顶部"接受全部 / 退出对比 / 应用修改"三个彩色按钮
- 所有审查控制移至侧栏 REVIEW 状态
- 主编辑区 DiffReviewPane 只保留 diff 块显示 + 逐块接受/拒绝按钮

### 3. 按钮规则

- 每个状态只有一个主按钮（金色 `SilkColors.primary`）
- 同一区域最多两个可见按钮
- 低频操作移入 `⋯` 菜单

### 4. 文案更新

| 旧文案 | 新文案 |
|--------|--------|
| 生成草稿 | 生成修改 |
| 新对话 | 清空会话（菜单） |
| 直接写回知识库 | 已删除（全部经审阅） |
| AI 说明 | 修改摘要（合并到预览） |
| 草稿预览 | 修改预览 |
| 在编辑器中显示修改 | 查看并审阅修改 |
| 已决定 X / Y 块 | 已处理 X / Y 处 |

### 5. "直接写回知识库"开关

- 彻底删除 `copilotApplyChanges` 状态和 `KnowledgeBooleanSetting` 组件引用
- AI 修改默认全部经过审阅，不再支持跳过确认直接写回

### 6. 颜色规则

- 主操作：`SilkColors.primary`（品牌金色 #C9A86C）
- 接受：`SilkColors.success`（绿色 #7DAE6C），仅审阅时出现
- 危险/放弃：红色，仅确认时出现
- 其他操作：文字按钮或无背景描边

### 7. 侧栏宽度

- 默认宽度：380px（原 360px）
- 最小宽度：320px（原 280px）
- 可拖拽调整：320–520px

## 改动文件

- `frontend/webApp/src/main/kotlin/com/silk/web/KnowledgeBaseScene.kt`
  - 新增 `CopilotSidebarState` 枚举（INPUT / PREVIEW / REVIEW）
  - 新增 `buildChangeSummary()` 工具函数
  - 重写 `KnowledgeCopilotSidebar` → 分派到三个子 composable
  - 新增 `CopilotInputState`、`CopilotPreviewState`、`CopilotReviewState`、`CopilotMenuRow`
  - 简化 `DiffReviewPane`：移除动作按钮，收缩签名为仅含 accept/reject 回调
  - 场景状态管理：新增 `effectiveSidebarState` 派生状态，移除 `copilotApplyChanges`
  - 侧栏调用更新：传递新参数，处理状态转换（backToInput / startReview / acceptAll / applyChanges / cancelReview）

## Verified

- `./gradlew :frontend:webApp:compileProductionExecutableKotlinJs` ✅
- `./gradlew :backend:test` 不需要（纯前端改动）
