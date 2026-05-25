package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.Position
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.bottom
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.left
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.minWidth
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.position
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.right
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.top
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea

@Composable
private fun TopicSidebar(
    topics: List<KBTopicItem>,
    isLoading: Boolean,
    selectedTopic: KBTopicItem?,
    onCreateTopic: () -> Unit,
    onTopicSelect: (KBTopicItem) -> Unit,
) {
    Div({
        style {
            width(220.px)
            property("flex-shrink", "0")
            property("border-right", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            backgroundColor(Color(SilkColors.surface))
        }
    }) {
        KnowledgeColumnHeader(
            title = "主题",
            actionLabel = "+",
            onAction = onCreateTopic,
        )
        TopicSidebarContent(
            topics = topics,
            isLoading = isLoading,
            selectedTopic = selectedTopic,
            onTopicSelect = onTopicSelect,
        )
    }
}

@Composable
private fun TopicSidebarContent(
    topics: List<KBTopicItem>,
    isLoading: Boolean,
    selectedTopic: KBTopicItem?,
    onTopicSelect: (KBTopicItem) -> Unit,
) {
    Div({ style { property("flex", "1"); property("overflow-y", "auto") } }) {
        if (isLoading) {
            KnowledgeCenteredMessage("加载中...", SilkColors.textSecondary, 16.px)
        } else {
            topics.forEach { topic ->
                TopicRow(
                    topic = topic,
                    isSelected = selectedTopic?.id == topic.id,
                    onClick = { onTopicSelect(topic) },
                )
            }
        }
    }
}

@Composable
private fun TopicRow(topic: KBTopicItem, isSelected: Boolean, onClick: () -> Unit) {
    Div({
        style {
            padding(10.px, 14.px)
            property("cursor", "pointer")
            if (isSelected) backgroundColor(Color("rgba(201,168,108,0.15)"))
            property("border-bottom", "1px solid ${SilkColors.border}")
        }
        onClick { onClick() }
    }) {
        Div({
            style {
                fontSize(14.px)
                color(Color(SilkColors.textPrimary))
                fontWeight(if (isSelected) "600" else "400")
            }
        }) { Text(topic.name) }
        if (topic.project.isNotBlank()) {
            Div({
                style {
                    fontSize(11.px)
                    color(Color(SilkColors.textLight))
                    marginTop(2.px)
                }
            }) { Text(topic.project) }
        }
    }
}

@Composable
private fun EntrySidebar(
    selectedTopic: KBTopicItem?,
    entries: List<KBEntryItem>,
    selectedEntry: KBEntryItem?,
    onCreateEntry: () -> Unit,
    onEntrySelect: (KBEntryItem) -> Unit,
) {
    Div({
        style {
            width(240.px)
            property("flex-shrink", "0")
            property("border-right", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            backgroundColor(Color(SilkColors.surfaceElevated))
        }
    }) {
        KnowledgeColumnHeader(
            title = selectedTopic?.name ?: "条目",
            actionLabel = if (selectedTopic != null) "+" else null,
            onAction = onCreateEntry,
        )
        EntrySidebarContent(
            selectedTopic = selectedTopic,
            entries = entries,
            selectedEntry = selectedEntry,
            onEntrySelect = onEntrySelect,
        )
    }
}

@Composable
private fun EntrySidebarContent(
    selectedTopic: KBTopicItem?,
    entries: List<KBEntryItem>,
    selectedEntry: KBEntryItem?,
    onEntrySelect: (KBEntryItem) -> Unit,
) {
    Div({ style { property("flex", "1"); property("overflow-y", "auto") } }) {
        when {
            selectedTopic == null -> KnowledgeListEmptyState("请先选择主题")
            entries.isEmpty() -> KnowledgeListEmptyState("暂无条目")
            else -> entries.forEach { entry ->
                EntryRow(
                    entry = entry,
                    isSelected = selectedEntry?.id == entry.id,
                    onClick = { onEntrySelect(entry) },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(entry: KBEntryItem, isSelected: Boolean, onClick: () -> Unit) {
    Div({
        style {
            padding(10.px, 14.px)
            property("cursor", "pointer")
            if (isSelected) backgroundColor(Color("rgba(201,168,108,0.1)"))
            property("border-bottom", "1px solid ${SilkColors.border}")
        }
        onClick { onClick() }
    }) {
        Div({
            style {
                fontSize(13.px)
                color(Color(SilkColors.textPrimary))
                fontWeight(if (isSelected) "600" else "400")
            }
        }) { Text(entry.title) }
    }
}

@Composable
private fun KnowledgeEditorPane(
    selectedEntry: KBEntryItem?,
    editorContent: String,
    isSaving: Boolean,
    saveMessage: String,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
) {
    Div({
        style {
            property("flex", "1")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            minWidth(0.px)
        }
    }) {
        if (selectedEntry == null) {
            EmptyEditorState()
        } else {
            KnowledgeEditorToolbar(
                title = selectedEntry.title,
                isSaving = isSaving,
                saveMessage = saveMessage,
                onSave = onSave,
                onExport = onExport,
            )
            TextArea {
                value(editorContent)
                onInput { event -> onContentChange(event.value) }
                attr("placeholder", "在这里输入 Markdown 内容...")
                style {
                    property("flex", "1")
                    width(100.percent)
                    border(0.px)
                    padding(16.px)
                    fontSize(14.px)
                    property("font-family", "monospace")
                    property("resize", "none")
                    property("outline", "none")
                    backgroundColor(Color(SilkColors.background))
                    color(Color(SilkColors.textPrimary))
                }
            }
        }
    }
}

@Composable
private fun KnowledgeEditorToolbar(
    title: String,
    isSaving: Boolean,
    saveMessage: String,
    onSave: () -> Unit,
    onExport: () -> Unit,
) {
    Div({
        style {
            padding(8.px, 16.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            backgroundColor(Color(SilkColors.surfaceElevated))
        }
    }) {
        Span({
            style { fontSize(16.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) }
        }) { Text(title) }
        Div({
            style { display(DisplayStyle.Flex); property("gap", "8px"); alignItems(AlignItems.Center) }
        }) {
            if (saveMessage.isNotEmpty()) {
                Span({ style { fontSize(12.px); color(Color(SilkColors.success)) } }) { Text(saveMessage) }
            }
            KnowledgeToolbarButton(
                label = if (isSaving) "保存中..." else "保存",
                background = SilkColors.primary,
                onClick = onSave,
            )
            KnowledgeToolbarButton(
                label = "导出 Obsidian",
                background = SilkColors.info,
                onClick = onExport,
            )
        }
    }
}

@Composable
private fun KnowledgeToolbarButton(label: String, background: String, onClick: () -> Unit) {
    Button({
        style {
            backgroundColor(Color(background))
            color(Color.white)
            border(0.px)
            borderRadius(6.px)
            padding(6.px, 14.px)
            property("cursor", "pointer")
            fontSize(13.px)
        }
        onClick { onClick() }
    }) { Text(label) }
}

@Composable
private fun EmptyEditorState() {
    Div({
        style {
            property("flex", "1")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            flexDirection(FlexDirection.Column)
        }
    }) {
        Span({ style { fontSize(48.px); marginBottom(16.px) } }) { Text("\uD83D\uDCDA") }
        Span({
            style { fontSize(18.px); color(Color(SilkColors.textSecondary)) }
        }) { Text("选择或创建条目开始编辑") }
        Span({
            style { fontSize(14.px); color(Color(SilkColors.textLight)); marginTop(8.px) }
        }) { Text("内容将自动归类到 Obsidian 知识库") }
    }
}

@Composable
private fun CreateTopicDialog(
    topicName: String,
    topicProject: String,
    onTopicNameChange: (String) -> Unit,
    onTopicProjectChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalDialog(title = "创建主题", onDismiss = onDismiss) {
        LabeledInput("主题名称", topicName) { value -> onTopicNameChange(value) }
        LabeledInput("所属项目（可选）", topicProject) { value -> onTopicProjectChange(value) }
        DialogActions(
            onCancel = onDismiss,
            onConfirm = onConfirm,
            confirmLabel = "创建",
        )
    }
}

@Composable
private fun CreateEntryDialog(
    entryTitle: String,
    onEntryTitleChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalDialog(title = "创建条目", onDismiss = onDismiss) {
        LabeledInput("条目标题", entryTitle) { value -> onEntryTitleChange(value) }
        DialogActions(
            onCancel = onDismiss,
            onConfirm = onConfirm,
            confirmLabel = "创建",
        )
    }
}

@Composable
private fun KnowledgeColumnHeader(title: String, actionLabel: String?, onAction: () -> Unit) {
    Div({
        style {
            padding(12.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
        }
    }) {
        Span({
            style { fontSize(16.px); fontWeight("bold"); color(Color(SilkColors.textPrimary)) }
        }) { Text(title) }
        if (actionLabel != null) {
            Button({
                style {
                    backgroundColor(Color(SilkColors.primary))
                    color(Color.white)
                    border(0.px)
                    borderRadius(6.px)
                    padding(4.px, 10.px)
                    property("cursor", "pointer")
                    fontSize(12.px)
                }
                onClick { onAction() }
            }) { Text(actionLabel) }
        }
    }
}

@Composable
private fun KnowledgeListEmptyState(message: String) {
    KnowledgeCenteredMessage(message, SilkColors.textLight, 20.px)
}

@Composable
private fun KnowledgeCenteredMessage(message: String, color: String, paddingSize: org.jetbrains.compose.web.css.CSSNumeric) {
    Div({
        style {
            padding(paddingSize)
            property("text-align", "center")
            this.color(Color(color))
        }
    }) { Text(message) }
}

private fun resetTopicDialog(
    onVisibilityChange: (Boolean) -> Unit,
    onNameChange: (String) -> Unit,
    onProjectChange: (String) -> Unit,
) {
    onVisibilityChange(false)
    onNameChange("")
    onProjectChange("")
}

private fun resetEntryDialog(
    onVisibilityChange: (Boolean) -> Unit,
    onTitleChange: (String) -> Unit,
) {
    onVisibilityChange(false)
    onTitleChange("")
}

private suspend fun loadKnowledgeEntries(
    topic: KBTopicItem,
    userId: String,
    onSelectedTopicChange: (KBTopicItem?) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
) {
    onSelectedTopicChange(topic)
    onSelectedEntryChange(null)
    onEditorContentChange("")
    onEntriesChange(ApiClient.getKBEntries(topic.id, userId))
}

private fun loadKnowledgeEntry(
    entry: KBEntryItem,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
) {
    onSelectedEntryChange(entry)
    onEditorContentChange(entry.content)
}

private suspend fun saveKnowledgeEntry(
    entry: KBEntryItem?,
    topic: KBTopicItem?,
    editorContent: String,
    userId: String,
    onSavingChange: (Boolean) -> Unit,
    onSaveMessageChange: (String) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
) {
    if (entry == null || topic == null) return
    onSavingChange(true)
    onSaveMessageChange("")
    val updated = ApiClient.updateKBEntry(entry.id, null, editorContent, null, userId)
    if (updated != null) {
        onSelectedEntryChange(updated)
        onEntriesChange(ApiClient.getKBEntries(topic.id, userId))
        onSaveMessageChange("已保存")
    }
    onSavingChange(false)
}

private suspend fun exportKnowledgeEntry(
    entry: KBEntryItem?,
    topic: KBTopicItem?,
    onSaveMessageChange: (String) -> Unit,
) {
    if (entry == null) return
    val exported = ApiClient.exportKBEntry(entry.id) ?: return
    if (ObsidianVaultManager.isSupported()) {
        recoverSuspendNonCancellation(
            block = {
                val handle = ObsidianVaultManager.getCachedHandleIfValid()
                    ?: ObsidianVaultManager.pickVaultDirectory()
                ObsidianVaultManager.saveToVault(
                    handle,
                    topic?.name ?: "General",
                    exported.markdown,
                    exported.fileName,
                )
                onSaveMessageChange("已导出到 Obsidian")
            },
            recover = { error ->
                console.error("Obsidian export failed:", error)
                downloadAsFile(exported.markdown, exported.fileName)
                onSaveMessageChange("已下载文件")
            },
        )
    } else {
        downloadAsFile(exported.markdown, exported.fileName)
        onSaveMessageChange("已下载文件")
    }
}

private suspend fun createKnowledgeTopic(
    topicName: String,
    topicProject: String,
    userId: String,
    onTopicsChange: (List<KBTopicItem>) -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
    onNameChange: (String) -> Unit,
    onProjectChange: (String) -> Unit,
) {
    if (topicName.isBlank()) return
    ApiClient.createKBTopic(topicName.trim(), topicProject.trim(), userId)
    onTopicsChange(ApiClient.getKBTopics(userId))
    resetTopicDialog(onVisibilityChange, onNameChange, onProjectChange)
}

private suspend fun createKnowledgeEntry(
    topic: KBTopicItem?,
    entryTitle: String,
    userId: String,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
    onTitleChange: (String) -> Unit,
) {
    if (topic == null || entryTitle.isBlank()) return
    val entry = ApiClient.createKBEntry(topic.id, entryTitle.trim(), "", emptyList(), userId)
    if (entry != null) {
        onEntriesChange(ApiClient.getKBEntries(topic.id, userId))
        loadKnowledgeEntry(entry, onSelectedEntryChange, onEditorContentChange)
    }
    resetEntryDialog(onVisibilityChange, onTitleChange)
}

@Composable
fun KnowledgeBaseScene(appState: WebAppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()

    var topics by remember { mutableStateOf<List<KBTopicItem>>(emptyList()) }
    var selectedTopic by remember { mutableStateOf<KBTopicItem?>(null) }
    var entries by remember { mutableStateOf<List<KBEntryItem>>(emptyList()) }
    var selectedEntry by remember { mutableStateOf<KBEntryItem?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    var showCreateTopicDialog by remember { mutableStateOf(false) }
    var newTopicName by remember { mutableStateOf("") }
    var newTopicProject by remember { mutableStateOf("") }

    var showCreateEntryDialog by remember { mutableStateOf(false) }
    var newEntryTitle by remember { mutableStateOf("") }

    var editorContent by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("") }

    LaunchedEffect(user.id) {
        isLoading = true
        topics = ApiClient.getKBTopics(user.id)
        isLoading = false
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            height(100.percent)
            width(100.percent)
            property("overflow", "hidden")
            property("background", SilkColors.backgroundGradient)
        }
    }) {
        TopicSidebar(
            topics = topics,
            isLoading = isLoading,
            selectedTopic = selectedTopic,
            onCreateTopic = { showCreateTopicDialog = true },
            onTopicSelect = { topic ->
                scope.launch {
                    loadKnowledgeEntries(
                        topic = topic,
                        userId = user.id,
                        onSelectedTopicChange = { selectedTopic = it },
                        onSelectedEntryChange = { selectedEntry = it },
                        onEditorContentChange = { editorContent = it },
                        onEntriesChange = { entries = it },
                    )
                }
            },
        )
        EntrySidebar(
            selectedTopic = selectedTopic,
            entries = entries,
            selectedEntry = selectedEntry,
            onCreateEntry = { showCreateEntryDialog = true },
            onEntrySelect = { entry ->
                loadKnowledgeEntry(
                    entry = entry,
                    onSelectedEntryChange = { selectedEntry = it },
                    onEditorContentChange = { editorContent = it },
                )
            },
        )
        KnowledgeEditorPane(
            selectedEntry = selectedEntry,
            editorContent = editorContent,
            isSaving = isSaving,
            saveMessage = saveMessage,
            onContentChange = { editorContent = it },
            onSave = {
                scope.launch {
                    saveKnowledgeEntry(
                        entry = selectedEntry,
                        topic = selectedTopic,
                        editorContent = editorContent,
                        userId = user.id,
                        onSavingChange = { isSaving = it },
                        onSaveMessageChange = { saveMessage = it },
                        onSelectedEntryChange = { selectedEntry = it },
                        onEntriesChange = { entries = it },
                    )
                }
            },
            onExport = {
                scope.launch {
                    exportKnowledgeEntry(
                        entry = selectedEntry,
                        topic = selectedTopic,
                        onSaveMessageChange = { saveMessage = it },
                    )
                }
            },
        )
    }

    if (showCreateTopicDialog) {
        CreateTopicDialog(
            topicName = newTopicName,
            topicProject = newTopicProject,
            onTopicNameChange = { newTopicName = it },
            onTopicProjectChange = { newTopicProject = it },
            onDismiss = {
                resetTopicDialog(
                    onVisibilityChange = { showCreateTopicDialog = it },
                    onNameChange = { newTopicName = it },
                    onProjectChange = { newTopicProject = it },
                )
            },
            onConfirm = {
                scope.launch {
                    createKnowledgeTopic(
                        topicName = newTopicName,
                        topicProject = newTopicProject,
                        userId = user.id,
                        onTopicsChange = { topics = it },
                        onVisibilityChange = { showCreateTopicDialog = it },
                        onNameChange = { newTopicName = it },
                        onProjectChange = { newTopicProject = it },
                    )
                }
            },
        )
    }

    if (showCreateEntryDialog && selectedTopic != null) {
        CreateEntryDialog(
            entryTitle = newEntryTitle,
            onEntryTitleChange = { newEntryTitle = it },
            onDismiss = {
                resetEntryDialog(
                    onVisibilityChange = { showCreateEntryDialog = it },
                    onTitleChange = { newEntryTitle = it },
                )
            },
            onConfirm = {
                scope.launch {
                    createKnowledgeEntry(
                        topic = selectedTopic,
                        entryTitle = newEntryTitle,
                        userId = user.id,
                        onEntriesChange = { entries = it },
                        onSelectedEntryChange = { selectedEntry = it },
                        onEditorContentChange = { editorContent = it },
                        onVisibilityChange = { showCreateEntryDialog = it },
                        onTitleChange = { newEntryTitle = it },
                    )
                }
            },
        )
    }
}

// ---- Shared dialog helpers ----

@Composable
fun ModalDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Div({
        style {
            position(Position.Fixed)
            top(0.px); left(0.px); right(0.px); bottom(0.px)
            backgroundColor(Color("rgba(0,0,0,0.4)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
        }
        onClick { onDismiss() }
    }) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                padding(24.px)
                width(380.px)
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({ style { marginTop(0.px); marginBottom(16.px); color(Color(SilkColors.textPrimary)) } }) { Text(title) }
            content()
        }
    }
}

@Composable
fun LabeledInput(placeholder: String, currentValue: String, onValueChange: (String) -> Unit) {
    Input(InputType.Text) {
        value(currentValue)
        onInput { onValueChange(it.value) }
        attr("placeholder", placeholder)
        style {
            width(100.percent)
            height(40.px)
            borderRadius(6.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            padding(8.px)
            fontSize(14.px)
            marginBottom(12.px)
            property("box-sizing", "border-box")
        }
    }
}

@Composable
fun DialogActions(onCancel: () -> Unit, onConfirm: () -> Unit, confirmLabel: String = "确定") {
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.FlexEnd)
            property("gap", "8px")
            marginTop(8.px)
        }
    }) {
        Button({
            style {
                backgroundColor(Color(SilkColors.surface))
                color(Color(SilkColors.textSecondary))
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                borderRadius(6.px)
                padding(8.px, 16.px)
                property("cursor", "pointer")
            }
            onClick { onCancel() }
        }) { Text("取消") }
        Button({
            style {
                backgroundColor(Color(SilkColors.primary))
                color(Color.white)
                border(0.px)
                borderRadius(6.px)
                padding(8.px, 16.px)
                property("cursor", "pointer")
            }
            onClick { onConfirm() }
        }) { Text(confirmLabel) }
    }
}
