package com.silk.web

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

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

    fun loadEntries(topic: KBTopicItem) {
        scope.launch {
            selectedTopic = topic
            selectedEntry = null
            editorContent = ""
            entries = ApiClient.getKBEntries(topic.id, user.id)
        }
    }

    fun loadEntry(entry: KBEntryItem) {
        selectedEntry = entry
        editorContent = entry.content
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
        // Left column: topics
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
                }) { Text("主题") }
                Button({
                    style {
                        backgroundColor(Color(SilkColors.primary)); color(Color.white)
                        border(0.px); borderRadius(6.px); padding(4.px, 10.px)
                        property("cursor", "pointer"); fontSize(12.px)
                    }
                    onClick { showCreateTopicDialog = true }
                }) { Text("+") }
            }

            Div({ style { property("flex", "1"); property("overflow-y", "auto") } }) {
                if (isLoading) {
                    Div({ style { padding(16.px); property("text-align", "center"); color(Color(SilkColors.textSecondary)) } }) {
                        Text("加载中...")
                    }
                } else {
                    topics.forEach { topic ->
                        Div({
                            style {
                                padding(10.px, 14.px)
                                property("cursor", "pointer")
                                if (selectedTopic?.id == topic.id) backgroundColor(Color("rgba(201,168,108,0.15)"))
                                property("border-bottom", "1px solid ${SilkColors.border}")
                            }
                            onClick { loadEntries(topic) }
                        }) {
                            Div({
                                style {
                                    fontSize(14.px); color(Color(SilkColors.textPrimary))
                                    fontWeight(if (selectedTopic?.id == topic.id) "600" else "400")
                                }
                            }) { Text(topic.name) }
                            if (topic.project.isNotBlank()) {
                                Div({
                                    style { fontSize(11.px); color(Color(SilkColors.textLight)); marginTop(2.px) }
                                }) { Text(topic.project) }
                            }
                        }
                    }
                }
            }
        }

        // Middle column: entries list
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
                    style { fontSize(14.px); fontWeight("bold"); color(Color(SilkColors.textPrimary)) }
                }) { Text(selectedTopic?.name ?: "条目") }
                if (selectedTopic != null) {
                    Button({
                        style {
                            backgroundColor(Color(SilkColors.primary)); color(Color.white)
                            border(0.px); borderRadius(6.px); padding(4.px, 10.px)
                            property("cursor", "pointer"); fontSize(12.px)
                        }
                        onClick { showCreateEntryDialog = true }
                    }) { Text("+") }
                }
            }

            Div({ style { property("flex", "1"); property("overflow-y", "auto") } }) {
                if (selectedTopic == null) {
                    Div({
                        style { padding(20.px); property("text-align", "center"); color(Color(SilkColors.textLight)) }
                    }) { Text("请先选择主题") }
                } else if (entries.isEmpty()) {
                    Div({
                        style { padding(20.px); property("text-align", "center"); color(Color(SilkColors.textLight)) }
                    }) { Text("暂无条目") }
                } else {
                    entries.forEach { entry ->
                        Div({
                            style {
                                padding(10.px, 14.px)
                                property("cursor", "pointer")
                                if (selectedEntry?.id == entry.id) backgroundColor(Color("rgba(201,168,108,0.1)"))
                                property("border-bottom", "1px solid ${SilkColors.border}")
                            }
                            onClick { loadEntry(entry) }
                        }) {
                            Div({
                                style {
                                    fontSize(13.px); color(Color(SilkColors.textPrimary))
                                    fontWeight(if (selectedEntry?.id == entry.id) "600" else "400")
                                }
                            }) { Text(entry.title) }
                        }
                    }
                }
            }
        }

        // Right: editor
        Div({
            style {
                property("flex", "1")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                minWidth(0.px)
            }
        }) {
            if (selectedEntry != null) {
                // Toolbar
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
                    }) { Text(selectedEntry!!.title) }
                    Div({
                        style { display(DisplayStyle.Flex); property("gap", "8px"); alignItems(AlignItems.Center) }
                    }) {
                        if (saveMessage.isNotEmpty()) {
                            Span({ style { fontSize(12.px); color(Color(SilkColors.success)) } }) { Text(saveMessage) }
                        }
                        Button({
                            style {
                                backgroundColor(Color(SilkColors.primary)); color(Color.white)
                                border(0.px); borderRadius(6.px); padding(6.px, 14.px)
                                property("cursor", "pointer"); fontSize(13.px)
                            }
                            onClick {
                                scope.launch {
                                    isSaving = true; saveMessage = ""
                                    val updated = ApiClient.updateKBEntry(selectedEntry!!.id, null, editorContent, null, user.id)
                                    if (updated != null) {
                                        selectedEntry = updated
                                        entries = ApiClient.getKBEntries(selectedTopic!!.id, user.id)
                                        saveMessage = "已保存"
                                    }
                                    isSaving = false
                                }
                            }
                        }) { Text(if (isSaving) "保存中..." else "保存") }
                        Button({
                            style {
                                backgroundColor(Color(SilkColors.info)); color(Color.white)
                                border(0.px); borderRadius(6.px); padding(6.px, 14.px)
                                property("cursor", "pointer"); fontSize(13.px)
                            }
                            onClick {
                                scope.launch {
                                    val exported = ApiClient.exportKBEntry(selectedEntry!!.id)
                                    if (exported != null) {
                                        if (ObsidianVaultManager.isSupported()) {
                                            try {
                                                val handle = ObsidianVaultManager.getCachedHandleIfValid()
                                                    ?: ObsidianVaultManager.pickVaultDirectory()
                                                ObsidianVaultManager.saveToVault(
                                                    handle,
                                                    selectedTopic?.name ?: "General",
                                                    exported.markdown,
                                                    exported.fileName
                                                )
                                                saveMessage = "已导出到 Obsidian"
                                            } catch (e: Exception) {
                                                console.error("Obsidian export failed:", e)
                                                downloadAsFile(exported.markdown, exported.fileName)
                                                saveMessage = "已下载文件"
                                            }
                                        } else {
                                            downloadAsFile(exported.markdown, exported.fileName)
                                            saveMessage = "已下载文件"
                                        }
                                    }
                                }
                            }
                        }) { Text("导出 Obsidian") }
                    }
                }

                // Editor textarea
                TextArea {
                    value(editorContent)
                    onInput { event -> editorContent = event.value }
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
            } else {
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
        }
    }

    // Create topic dialog
    if (showCreateTopicDialog) {
        ModalDialog(
            title = "创建主题",
            onDismiss = { showCreateTopicDialog = false; newTopicName = ""; newTopicProject = "" }
        ) {
            LabeledInput("主题名称", newTopicName) { v -> newTopicName = v }
            LabeledInput("所属项目（可选）", newTopicProject) { v -> newTopicProject = v }
            DialogActions(
                onCancel = { showCreateTopicDialog = false; newTopicName = ""; newTopicProject = "" },
                onConfirm = {
                    if (newTopicName.isNotBlank()) {
                        scope.launch {
                            ApiClient.createKBTopic(newTopicName.trim(), newTopicProject.trim(), user.id)
                            topics = ApiClient.getKBTopics(user.id)
                            showCreateTopicDialog = false; newTopicName = ""; newTopicProject = ""
                        }
                    }
                },
                confirmLabel = "创建"
            )
        }
    }

    // Create entry dialog
    if (showCreateEntryDialog && selectedTopic != null) {
        ModalDialog(
            title = "创建条目",
            onDismiss = { showCreateEntryDialog = false; newEntryTitle = "" }
        ) {
            LabeledInput("条目标题", newEntryTitle) { v -> newEntryTitle = v }
            DialogActions(
                onCancel = { showCreateEntryDialog = false; newEntryTitle = "" },
                onConfirm = {
                    if (newEntryTitle.isNotBlank()) {
                        scope.launch {
                            val entry = ApiClient.createKBEntry(selectedTopic!!.id, newEntryTitle.trim(), "", emptyList(), user.id)
                            if (entry != null) {
                                entries = ApiClient.getKBEntries(selectedTopic!!.id, user.id)
                                loadEntry(entry)
                            }
                            showCreateEntryDialog = false; newEntryTitle = ""
                        }
                    }
                },
                confirmLabel = "创建"
            )
        }
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
