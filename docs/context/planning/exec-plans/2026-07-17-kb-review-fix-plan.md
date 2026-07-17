# KB Review 高危状态一致性修复计划

Date: 2026-07-17
Status: ✅ 全部完成（P0/P1/P2 均已在 2026-07-17 实现并编译验证）

## 总体策略

先修复 3 个 P0 状态一致性问题（编辑切换 / 移动目标 / 删除反馈），再修复 P1 明显影响业务可靠性的问题，最后修复 P2 交互优化。

---

## P0-1: 未保存时切换条目会形成混合编辑状态

**根因**：`onEntrySelect` 直接调用 `loadKnowledgeEntry()`，无条件覆盖 `editorContent` 和 `selectedEntry`。脏状态只在工具栏有标题比较，但没有阻止切换。

**改动**：
1. 在 `KnowledgeBaseScene` 主 composable 中添加 `hasUnsavedChanges` 派生状态
2. 添加 `pendingSwitchEntry` 状态变量
3. 添加 `showUnsavedChangesDialog` 对话框控制
4. `onEntrySelect` 回调中，如果脏则记录目标条目到 `pendingSwitchEntry` 并弹出对话框
5. 对话框提供"保存 / 放弃 / 取消"三个操作

**涉及文件**：`frontend/webApp/.../KnowledgeBaseScene.kt`

---

## P0-2: 未选择目标也能执行移动

**根因**：`LaunchedEffect` 在对话框打开时自动选中 `moveTargetTopics.firstOrNull()?.id`，导致用户无需显式选择即可点击确认。

**改动**：
1. 移除 LaunchedEffect 中自动设 `moveTargetTopicId` 的逻辑（保留 topic 切换时重置的逻辑但不自动填充）
2. 初始化 `moveTargetTopicId` 为空字符串（已在状态声明中为空）
3. `MoveKnowledgeEntryDialog` 添加确认文案："将移动到：xxx"
4. 确认按钮保持 `confirmEnabled = !isSaving && selectedTargetTopicId.isNotBlank()`

**涉及文件**：`frontend/webApp/.../KnowledgeBaseScene.kt`

---

## P0-3: 删除成功后前端仍显示失败状态

**根因**：后端 DELETE `/api/kb/entries/{entryId}` 和 DELETE `/api/kb/topics/{topicId}` 返回 `{"success":true}` 没有 `message` 字段。前端 `SimpleResponse.message` 是非可选字段，导致 `jsonParser.decodeFromString` 解析异常，catch 返回 `SimpleResponse(false, "网络错误")`，dialog 不关闭。

**改动**：
1. 后端两个端点响应加上 `"message":""` 字段
2. 前端 `deleteKnowledgeEntry` 和 `deleteKnowledgeTopic` 函数确认 `onDialogVisibilityChange(false)` 在成功路径上正确执行

**涉及文件**：
- `backend/src/main/kotlin/com/silk/backend/Routing.kt`
- `frontend/webApp/.../KnowledgeBaseScene.kt`

---

## P1: 明显影响业务可靠性问题

1. **会议入库默认主题**：`meetingCaptureSpaceId` 和 `meetingCaptureTopicId` 应默认使用当前 QA 主题的空间和主题，而不是 `PERSONAL_SPACE_ID` 和空
2. **置信度输入限制**：`meetingCaptureConfidenceText` 输入 2.5 仍可提交。应在提交时 clamp 到 0-1 范围
3. **768px 窄屏布局**：设置 `overflow-x: hidden` 且三栏被裁切。需自动折叠侧栏或在窄屏下切换为抽屉 ✅
4. **筛选与编辑器状态脱节**：切换 `entryFilter` 后 `selectedEntry` 可能不在可见列表，编辑器仍显示旧内容 ✅
    - 实现：添加 `LaunchedEffect(entryFilter, filteredEntries)` 检测脱节并清除编辑器；`loadKnowledgeEntries` 新增 `onEntryFilterChange` 参数，切换主题时自动重置筛选器为"全部"
5. **失效 KB 引用 404 无提示**：点击 `[[kb:...]]` 或 `kb://` 链接指向已删除条目时静默失败 ✅
    - 实现：`kbNavigationTarget` 的 `LaunchedEffect` 中当 `navigateToKnowledgeBaseTarget` 返回 `false` 时，设置 `saveMessage = "条目不存在或已被删除"`
    - 实现：添加 `DisposableEffect` 监听 `resize`，`window.innerWidth < 768` 时自动折叠两侧栏并持久化状态

**涉及文件**：
- `frontend/webApp/.../KnowledgeBaseScene.kt`

---

## P2: 交互与表达优化

1. **中英文状态混用**：筛选标签已为中文，但合并对话框等信息区域仍有英文 status name ✅
    - 新增 `knowledgeStatusLabel()` 函数统一映射为中文，替换 `EntryRow`/`KnowledgeEntryMetaBar`/合并对话框/两处 CaptureDialog 中的硬编码英文
2. **新建主题成功后自动选中** ✅（已有实现：`createKnowledgeTopic` 中 `onSelectedTopicChange` 自动选中新主题）
3. **保存按钮脏状态**：无修改时禁用保存按钮 ✅
    - `KnowledgeEditorToolbarActions.canSave` 增加 `hasUnsavedChanges` 检查，经 `KnowledgeEditorPane` → `KnowledgeEditorToolbar` 贯通

**涉及文件**：
- `frontend/webApp/.../KnowledgeBaseScene.kt`
- `frontend/webApp/.../KnowledgeBaseCaptureDialog.kt`
- `frontend/webApp/.../KnowledgeBaseMeetingCaptureDialog.kt`
