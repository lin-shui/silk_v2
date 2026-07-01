package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
import com.silk.shared.models.KnowledgeBaseContextSelection
import com.silk.shared.models.DirEntry
import com.silk.shared.models.DirListingResponse
import com.silk.shared.models.Message
import kotlinx.coroutines.CoroutineScope
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
import org.jetbrains.compose.web.css.fontFamily
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontStyle
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.left
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.maxHeight
import org.jetbrains.compose.web.css.minHeight
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
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea


private fun shouldSubmitWorkflowMessage(event: org.jetbrains.compose.web.events.SyntheticKeyboardEvent, messageText: String): Boolean {
    if (event.key != "Enter") return false
    if (event.shiftKey) return false
    val isComposing = event.nativeEvent.asDynamic().isComposing == true
    return !isComposing && messageText.isNotBlank()
}

@Suppress("CyclomaticComplexMethod")
@Composable
fun WorkflowScene(appState: WebAppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()
    var workflows by remember { mutableStateOf<List<WorkflowItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newInitialDir by remember { mutableStateOf("") }
    var availableAgents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var selectedAgentType by remember { mutableStateOf("") }
    var selectedPermissionMode by remember { mutableStateOf("") }
    var showCreatePicker by remember { mutableStateOf(false) }
    var selectedWorkflow by remember { mutableStateOf<WorkflowItem?>(null) }
    var listWidth by remember { mutableStateOf(LayoutPrefs.getInt("silk_wf_list_w", 320)) }
    var listCollapsed by remember { mutableStateOf(LayoutPrefs.getBool("silk_wf_list_collapsed", false)) }
    ensureLayoutStylesInjected()
    // 操作菜单状态
    var menuWorkflow by remember { mutableStateOf<WorkflowItem?>(null) }
    var renameTarget by remember { mutableStateOf<WorkflowItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<WorkflowItem?>(null) }
    // Bridge 是否在线（从 agent 列表判断），离线时禁用创建相关操作
    var bridgeConnected by remember { mutableStateOf(true) }
    // 默认目录加载失败时的非致命警告（不阻塞创建，用户仍可手动输入或选择目录）
    var dirWarning by remember { mutableStateOf<String?>(null) }
    // 信任确认弹窗状态（替代浏览器原生 confirm）
    var showTrustConfirm by remember { mutableStateOf(false) }
    var trustConfirmPath by remember { mutableStateOf("") }
    var trustConfirmBridgeId by remember { mutableStateOf<String?>(null) }

    // 提取创建 workflow 的 suspend 函数，供信任弹窗回调复用
    suspend fun performCreateWorkflow() {
        val agentType = selectedAgentType.ifBlank { "claude_code" }
        val result = ApiClient.createWorkflow(
            newName.trim(), "", user.id, newInitialDir.trim(), agentType, selectedPermissionMode,
        )
        workflows = ApiClient.getWorkflows(user.id)
        when (result) {
            is ApiClient.CreateWorkflowResult.Ok -> {
                selectedWorkflow = result.workflow
                showCreateDialog = false
                newName = ""
                newInitialDir = ""
                selectedAgentType = ""
                selectedPermissionMode = ""
                dirWarning = null
            }
            is ApiClient.CreateWorkflowResult.Err -> {
                kotlinx.browser.window.alert("创建工作流失败：${result.message}")
                // 重新检查 bridge 连接状态
                val agents = ApiClient.listAgents(user.id)
                bridgeConnected = agents.any { it.connected }
            }
        }
    }

    LaunchedEffect(user.id) {
        isLoading = true
        workflows = ApiClient.getWorkflows(user.id)
        isLoading = false
    }

    val workflowNavigationTarget = appState.workflowNavigationTarget
    LaunchedEffect(workflows, workflowNavigationTarget?.requestId) {
        val target = workflowNavigationTarget ?: return@LaunchedEffect
        val workflow = workflows.find { it.id == target.workflowId } ?: return@LaunchedEffect
        selectedWorkflow = workflow
        pendingWorkflowMessageId = target.messageId?.takeIf { it.isNotBlank() }
        appState.consumeWorkflowNavigationTarget(target.requestId)
    }

    val resetCreateDialog = {
        showCreateDialog = false
        newName = ""
        newInitialDir = ""
        selectedAgentType = ""
        selectedPermissionMode = ""
        dirWarning = null
    }
    val resetKnowledgeCaptureDialog = {
        kbCaptureDraft = null
        kbCaptureTopics = emptyList()
        kbCaptureGroups = emptyList()
        kbCaptureSelectedSpaceId = PERSONAL_SPACE_ID
        kbCaptureSelectedTopicId = ""
        kbCaptureTitle = ""
        kbCaptureContent = ""
        kbCaptureSaving = false
        kbCaptureResult = null
    }

    WorkflowSceneLayout(
        workflows = workflows,
        isLoading = isLoading,
        selectedWorkflow = selectedWorkflow,
        pendingWorkflowMessageId = pendingWorkflowMessageId,
        userId = user.id,
        userName = user.fullName,
        onCaptureToKnowledgeBase = { workflow, message ->
            scope.launch {
                val context = loadKnowledgeCaptureContext(user.id)
                val preferredSpaceId = preferredKnowledgeCaptureSpaceId(workflow.groupId, context.topics)
                kbCaptureDraft = KnowledgeCaptureDraft(
                    message = message,
                    sourceType = messageKnowledgeCaptureSourceType(message, KBSourceType.WORKFLOW),
                    sourceGroupId = workflow.groupId,
                    workflowId = workflow.id,
                    preferredSpaceId = preferredSpaceId,
                )
                kbCaptureTopics = context.topics
                kbCaptureGroups = context.groups
                kbCaptureSelectedSpaceId = preferredSpaceId
                kbCaptureSelectedTopicId = defaultKnowledgeCaptureTopicId(context.topics, preferredSpaceId).orEmpty()
                kbCaptureTitle = buildDefaultKnowledgeCaptureTitle(message.content)
                kbCaptureContent = message.content
                kbCaptureSaving = false
                kbCaptureResult = if (context.topics.isEmpty()) "还没有可用主题，请先去知识库创建主题。" else null
            }
        },
        onOpenCreateDialog = { showCreateDialog = true },
        onSelectWorkflow = { selectedWorkflow = it },
        onWorkflowMessageNavigated = { pendingWorkflowMessageId = null },
        onToggleMenuWorkflow = { wf -> menuWorkflow = toggleWorkflowMenu(menuWorkflow, wf) },
    )
    WorkflowCreateFlow(
        scope = scope,
        userId = user.id,
        showCreateDialog = showCreateDialog,
        newName = newName,
        newInitialDir = newInitialDir,
        availableAgents = availableAgents,
        selectedAgentType = selectedAgentType,
        selectedPermissionMode = selectedPermissionMode,
        bridgeConnected = bridgeConnected,
        dirWarning = dirWarning,
        showCreatePicker = showCreatePicker,
        showTrustConfirm = showTrustConfirm,
        trustConfirmPath = trustConfirmPath,
        trustConfirmBridgeId = trustConfirmBridgeId,
        onNameChange = { newName = it },
        onInitialDirChange = { newInitialDir = it },
        onAvailableAgentsChange = { availableAgents = it },
        onSelectedAgentTypeChange = { selectedAgentType = it },
        onSelectedPermissionModeChange = { selectedPermissionMode = it },
        onBridgeConnectedChange = { bridgeConnected = it },
        onDirWarningChange = { dirWarning = it },
        onShowCreatePickerChange = { showCreatePicker = it },
        onShowTrustConfirmChange = { showTrustConfirm = it },
        onTrustConfirmPathChange = { trustConfirmPath = it },
        onTrustConfirmBridgeIdChange = { trustConfirmBridgeId = it },
        onWorkflowsChange = { workflows = it },
        onSelectedWorkflowChange = { selectedWorkflow = it },
        onDismissCreateDialog = resetCreateDialog,
    )
    WorkflowManagementDialogs(
        scope = scope,
        userId = user.id,
        menuWorkflow = menuWorkflow,
        renameTarget = renameTarget,
        renameText = renameText,
        deleteTarget = deleteTarget,
        selectedWorkflowId = selectedWorkflow?.id,
        onMenuWorkflowChange = { menuWorkflow = it },
        onRenameTargetChange = { renameTarget = it },
        onRenameTextChange = { renameText = it },
        onDeleteTargetChange = { deleteTarget = it },
        onSelectedWorkflowChange = { selectedWorkflow = it },
        onWorkflowsChange = { workflows = it },
    )

    kbCaptureDraft?.let { draft ->
        KnowledgeBaseCaptureDialog(
            draft = draft,
            spaceOptions = buildKnowledgeSpaceOptions(kbCaptureGroups),
            topics = kbCaptureTopics,
            selectedSpaceId = kbCaptureSelectedSpaceId,
            selectedTopicId = kbCaptureSelectedTopicId,
            title = kbCaptureTitle,
            content = kbCaptureContent,
            isSaving = kbCaptureSaving,
            resultMessage = kbCaptureResult,
            onSelectedSpaceIdChange = { kbCaptureSelectedSpaceId = it },
            onSelectedTopicIdChange = { kbCaptureSelectedTopicId = it },
            onTitleChange = { kbCaptureTitle = it },
            onContentChange = { kbCaptureContent = it },
            onDismiss = resetKnowledgeCaptureDialog,
            onConfirm = {
                if (!canSubmitKnowledgeCapture(kbCaptureSaving, kbCaptureSelectedTopicId, kbCaptureTitle, kbCaptureContent)) {
                    return@KnowledgeBaseCaptureDialog
                }
                scope.launch {
                    kbCaptureSaving = true
                    val created = ApiClient.captureKBEntry(
                        topicId = kbCaptureSelectedTopicId,
                        title = kbCaptureTitle.trim(),
                        content = kbCaptureContent,
                        tags = emptyList(),
                        userId = user.id,
                        source = KBEntrySource(
                            sourceType = draft.sourceType,
                            sourceGroupId = draft.sourceGroupId,
                            workflowId = draft.workflowId,
                            messageIds = listOf(draft.message.id),
                        ),
                    )
                    kbCaptureSaving = false
                    if (created == null) {
                        kbCaptureResult = "保存失败，请确认目标主题仍可写。"
                    } else {
                        resetKnowledgeCaptureDialog()
                        appState.openKnowledgeBaseEntry(created.id, created.topicId)
                    }
                }
            },
        )
    }
}

private fun toggleWorkflowMenu(
    current: WorkflowItem?,
    workflow: WorkflowItem,
): WorkflowItem? = if (current == workflow) null else workflow

@Composable
private fun WorkflowSceneLayout(
    workflows: List<WorkflowItem>,
    isLoading: Boolean,
    selectedWorkflow: WorkflowItem?,
    pendingWorkflowMessageId: String?,
    userId: String,
    userName: String,
    onCaptureToKnowledgeBase: (WorkflowItem, Message) -> Unit,
    onOpenCreateDialog: () -> Unit,
    onSelectWorkflow: (WorkflowItem) -> Unit,
    onWorkflowMessageNavigated: () -> Unit,
    onToggleMenuWorkflow: (WorkflowItem) -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            height(100.percent)
            width(100.percent)
            property("overflow", "hidden")
            property("background", SilkColors.backgroundGradient)
        }
    }) {
        if (listCollapsed) {
            ReopenBar(onExpand = {
                listCollapsed = false
                LayoutPrefs.setBool("silk_wf_list_collapsed", false)
            })
        } else {
        // Left: workflow list
        Div({
            style {
                width(listWidth.px)
                property("flex-shrink", "0")
                property("border-right", "1px solid ${SilkColors.border}")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                backgroundColor(Color(SilkColors.surface))
            }
        }) {
            // Header
            Div({
                style {
                    padding(16.px)
                    property("border-bottom", "1px solid ${SilkColors.border}")
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    alignItems(AlignItems.Center)
                }
            }) {
                Span({
                    style {
                        fontSize(18.px)
                        fontWeight("bold")
                        color(Color(SilkColors.textPrimary))
                    }
                }) { Text("工作流") }
                Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); property("gap", "8px") } }) {
                    Button({
                        attr("title", "收起列表")
                        style {
                            backgroundColor(Color(SilkColors.surfaceElevated))
                            color(Color(SilkColors.textSecondary))
                            property("border", "1px solid ${SilkColors.border}")
                            borderRadius(6.px)
                            padding(6.px, 10.px)
                            property("cursor", "pointer")
                        }
                        onClick {
                            listCollapsed = true
                            LayoutPrefs.setBool("silk_wf_list_collapsed", true)
                        }
                    }) { Text("«") }
                    Button({
                        style {
                            backgroundColor(Color(SilkColors.primary))
                            color(Color.white)
                            border(0.px)
                            borderRadius(6.px)
                            padding(6.px, 12.px)
                            property("cursor", "pointer")
                        }
                        onClick { showCreateDialog = true }
                    }) { Text("+ 创建") }
                }
            }

@Composable
private fun WorkflowSidebar(
    workflows: List<WorkflowItem>,
    isLoading: Boolean,
    selectedWorkflow: WorkflowItem?,
    onOpenCreateDialog: () -> Unit,
    onSelectWorkflow: (WorkflowItem) -> Unit,
    onToggleMenuWorkflow: (WorkflowItem) -> Unit,
) {
    Div({
        style {
            width(320.px)
            property("flex-shrink", "0")
            property("border-right", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            backgroundColor(Color(SilkColors.surface))
        }
    }) {
        WorkflowSidebarHeader(onOpenCreateDialog)
        WorkflowSidebarList(
            workflows = workflows,
            isLoading = isLoading,
            selectedWorkflow = selectedWorkflow,
            onSelectWorkflow = onSelectWorkflow,
            onToggleMenuWorkflow = onToggleMenuWorkflow,
        )
    }
}

@Composable
private fun WorkflowSidebarHeader(onOpenCreateDialog: () -> Unit) {
    Div({
        style {
            padding(16.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
        }
    }) {
        Span({
            style {
                fontSize(18.px)
                fontWeight("bold")
                color(Color(SilkColors.textPrimary))
            }
        }) { Text("工作流") }
        Button({
            style {
                backgroundColor(Color(SilkColors.primary))
                color(Color.white)
                border(0.px)
                borderRadius(6.px)
                padding(6.px, 12.px)
                property("cursor", "pointer")
            }
            onClick { onOpenCreateDialog() }
        }) { Text("+ 创建") }
    }
}

@Composable
private fun WorkflowSidebarList(
    workflows: List<WorkflowItem>,
    isLoading: Boolean,
    selectedWorkflow: WorkflowItem?,
    onSelectWorkflow: (WorkflowItem) -> Unit,
    onToggleMenuWorkflow: (WorkflowItem) -> Unit,
) {
    Div({
        style {
            property("flex", "1")
            property("overflow-y", "auto")
        }
    }) {
        when {
            isLoading -> WorkflowSidebarMessage("加载中...")
            workflows.isEmpty() -> WorkflowSidebarMessage("暂无工作流", true)
            else -> workflows.forEach { workflow ->
                WorkflowSidebarItem(
                    workflow = workflow,
                    isSelected = selectedWorkflow?.id == workflow.id,
                    onSelect = onSelectWorkflow,
                    onToggleMenu = onToggleMenuWorkflow,
                )
            }
        }
        ColumnResizer(
            isLeftPanel = true,
            minWidth = 220,
            maxWidth = 520,
            currentWidth = { listWidth },
            onResize = { listWidth = it },
            onCommit = { LayoutPrefs.setInt("silk_wf_list_w", listWidth) },
        )
        } // close 列表非折叠分支

@Composable
private fun WorkflowSidebarMessage(
    message: String,
    emptyState: Boolean = false,
) {
    Div({
        style {
            if (emptyState) {
                padding(40.px, 20.px)
            } else {
                // Chat panel for selected workflow
                // key(wf.groupId) 使切换 workflow 时 Compose 销毁旧组件再创建新组件，
                // 避免共用同一个 ChatClient 导致旧会话的流式消息泄漏到新会话 UI
                key(wf.groupId) {
                    WorkflowChatPanel(
                        appState = appState,
                        userId = user.id,
                        userName = user.fullName,
                        groupId = wf.groupId,
                        workflowId = wf.id,
                        workflowName = wf.name,
                    )
                }
            }
        }) { Text(workflow.name) }
        Button({
            style {
                backgroundColor(Color("transparent"))
                color(Color(SilkColors.textSecondary))
                border(0.px)
                property("cursor", "pointer")
                fontSize(18.px)
                padding(4.px, 8.px)
            }
            onClick { event ->
                event.stopPropagation()
                onToggleMenu(workflow)
            }
        }) { Text("⋮") }
    }
}

@Composable
private fun WorkflowMainPanel(
    selectedWorkflow: WorkflowItem?,
    pendingMessageId: String?,
    userId: String,
    userName: String,
    onCaptureToKnowledgeBase: (WorkflowItem, Message) -> Unit,
    onMessageNavigated: () -> Unit,
) {
    Div({
        style {
            property("flex", "1")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            height(100.percent)
            property("overflow", "hidden")
        }
    }) {
        val workflow = selectedWorkflow
        if (workflow == null || workflow.groupId.isBlank()) {
            WorkflowEmptyState()
        } else {
            key(workflow.groupId) {
                WorkflowChatPanel(
                    userId = userId,
                    userName = userName,
                    groupId = workflow.groupId,
                    workflowName = workflow.name,
                    pendingMessageId = pendingMessageId,
                    onCaptureToKnowledgeBase = { message -> onCaptureToKnowledgeBase(workflow, message) },
                    onMessageNavigated = onMessageNavigated,
                )
            }
        }
    }
}

@Composable
private fun WorkflowEmptyState() {
    Div({
        style {
            property("flex", "1")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            flexDirection(FlexDirection.Column)
        }
    }) {
        Span({ style { fontSize(48.px); marginBottom(16.px) } }) { Text("\uD83E\uDD16") }
        Span({
            style {
                fontSize(18.px)
                color(Color(SilkColors.textSecondary))
            }
        }) { Text("选择或创建一个工作流开始对话") }
    }
}

@Composable
private fun WorkflowCreateFlow(
    scope: CoroutineScope,
    userId: String,
    showCreateDialog: Boolean,
    newName: String,
    newInitialDir: String,
    availableAgents: List<AgentInfo>,
    selectedAgentType: String,
    selectedPermissionMode: String,
    bridgeConnected: Boolean,
    dirWarning: String?,
    showCreatePicker: Boolean,
    showTrustConfirm: Boolean,
    trustConfirmPath: String,
    trustConfirmBridgeId: String?,
    onNameChange: (String) -> Unit,
    onInitialDirChange: (String) -> Unit,
    onAvailableAgentsChange: (List<AgentInfo>) -> Unit,
    onSelectedAgentTypeChange: (String) -> Unit,
    onSelectedPermissionModeChange: (String) -> Unit,
    onBridgeConnectedChange: (Boolean) -> Unit,
    onDirWarningChange: (String?) -> Unit,
    onShowCreatePickerChange: (Boolean) -> Unit,
    onShowTrustConfirmChange: (Boolean) -> Unit,
    onTrustConfirmPathChange: (String) -> Unit,
    onTrustConfirmBridgeIdChange: (String?) -> Unit,
    onWorkflowsChange: (List<WorkflowItem>) -> Unit,
    onSelectedWorkflowChange: (WorkflowItem?) -> Unit,
    onDismissCreateDialog: () -> Unit,
) {
    if (showCreateDialog) {
        LaunchedEffect(Unit) {
            // 加载可选 agent 列表，并按"已连接 codex > 已连接 claude_code > 兜底 claude_code"决定默认值。
            val agents = ApiClient.listAgents(user.id)
            availableAgents = agents
            if (selectedAgentType.isBlank()) {
                val codexConnected = agents.firstOrNull { it.agentType == "codex" && it.connected } != null
                val ccConnected = agents.firstOrNull { it.agentType == "claude_code" && it.connected } != null
                selectedAgentType = when {
                    codexConnected -> "codex"
                    ccConnected -> "claude_code"
                    else -> "claude_code"
                }
            }
            bridgeConnected = agents.any { it.connected }
            if (newInitialDir.isBlank() && bridgeConnected) {
                dirWarning = null
                val resp = ApiClient.listCcDir(user.id, null)
                if (resp.success && resp.path.isNotBlank()) {
                    newInitialDir = resp.path
                } else {
                    dirWarning = resp.error ?: "无法获取默认目录"
                }
            }
        }
        ModalOverlay(
            onDismiss = {
                // 任意路径关闭对话框（点遮罩、未来加 Esc 等）都统一清空 state，
                // 下次再开会重新拉一次 bridge 默认目录，避免旧值 stale
                showCreateDialog = false
                newName = ""
                newInitialDir = ""
                selectedAgentType = ""
                dirWarning = null
            },
            zIndex = 1000,
        ) {
            Div({
                style {
                    backgroundColor(Color.white)
                    borderRadius(12.px)
                    padding(24.px)
                    width(440.px)
                    property("max-width", "90vw")
                    property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
                }
            }) {
                H3({ style { marginTop(0.px); color(Color(SilkColors.textPrimary)) } }) { Text("创建工作流") }
                // 工作流名称
                Input(InputType.Text) {
                    value(newName)
                    onInput { newName = it.value }
                    attr("placeholder", "工作流名称")
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
                // Agent 选择
                Span({
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        property("display", "block")
                        marginBottom(4.px)
                    }
                }) { Text("Agent") }
                Select({
                    style {
                        width(100.percent)
                        height(40.px)
                        borderRadius(6.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(8.px)
                        fontSize(14.px)
                        marginBottom(12.px)
                        property("box-sizing", "border-box")
                        backgroundColor(Color.white)
                    }
                    onChange { event ->
                        val newValue = event.value ?: return@onChange
                        selectedAgentType = newValue
                    }
                }) {
                    if (availableAgents.isEmpty()) {
                        Option("") { Text("加载中…") }
                    } else {
                        availableAgents.forEach { agent ->
                            Option(
                                value = agent.agentType,
                                attrs = {
                                    if (!agent.connected) attr("disabled", "")
                                    if (agent.agentType == selectedAgentType) attr("selected", "")
                                },
                            ) {
                                val suffix = if (agent.connected) "" else "（未连接）"
                                Text("${agent.displayName}${suffix}")
                            }
                        }
                    }
                }
                // 权限模式
                Span({
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        property("display", "block")
                        marginBottom(4.px)
                    }
                }) { Text("权限模式") }
                Select({
                    style {
                        width(100.percent)
                        height(40.px)
                        borderRadius(6.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(8.px)
                        fontSize(14.px)
                        marginBottom(12.px)
                        property("box-sizing", "border-box")
                        backgroundColor(Color.white)
                    }
                    onChange { event ->
                        selectedPermissionMode = event.value ?: ""
                    }
                }) {
                    Option("", attrs = { if (selectedPermissionMode.isBlank()) attr("selected", "") }) { Text("Interactive") }
                    Option("ACCEPT_EDITS", attrs = { if (selectedPermissionMode == "ACCEPT_EDITS") attr("selected", "") }) { Text("Accept Edits") }
                    Option("BYPASS", attrs = { if (selectedPermissionMode == "BYPASS") attr("selected", "") }) { Text("Bypass") }
                }
                // 工作目录
                Span({
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        property("display", "block")
                        marginBottom(4.px)
                    }
                }) { Text("工作目录") }
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("gap", "8px")
                        marginBottom(if (!bridgeConnected || dirWarning != null) 6.px else 16.px)
                    }
                }) {
                    Input(InputType.Text) {
                        value(newInitialDir)
                        onInput { newInitialDir = it.value }
                        attr(
                            "placeholder",
                            when {
                                !bridgeConnected -> "Bridge 未连接，请先启动 Bridge"
                                dirWarning != null -> "请输入或选择工作目录"
                                else -> "加载默认目录中…"
                            },
                        )
                        // Bridge 离线时禁用输入框
                        if (!bridgeConnected) attr("disabled", "")
                        style {
                            property("flex", "1")
                            height(40.px)
                            borderRadius(6.px)
                            border(1.px, LineStyle.Solid, Color(SilkColors.border))
                            padding(8.px)
                            fontSize(13.px)
                            fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                            property("box-sizing", "border-box")
                            if (!bridgeConnected) {
                                backgroundColor(Color(SilkColors.surface))
                                color(Color(SilkColors.textLight))
                            }
                        }
                    }
                    Button({
                        val pickerDisabled = !bridgeConnected
                        if (pickerDisabled) attr("disabled", "")
                        style {
                            backgroundColor(Color("transparent"))
                            color(
                                if (pickerDisabled) Color(SilkColors.textLight)
                                else Color(SilkColors.primary)
                            )
                            border(1.px, LineStyle.Solid, Color(SilkColors.border))
                            borderRadius(6.px)
                            padding(6.px, 10.px)
                            property("cursor", if (pickerDisabled) "not-allowed" else "pointer")
                            fontSize(13.px)
                            property("white-space", "nowrap")
                        }
                        attr("title", "浏览选择目录（需 Bridge 在线）")
                        onClick { if (!pickerDisabled) showCreatePicker = true }
                    }) { Text("\uD83D\uDCC2 选择…") }
                }
                if (!bridgeConnected) {
                    Div({
                        style {
                            fontSize(12.px)
                            color(Color(SilkColors.error))
                            marginBottom(16.px)
                        }
                    }) { Text("⚠ Bridge 未连接。请先启动 Bridge Agent 再创建工作流。") }
                } else if (dirWarning != null) {
                    Div({
                        style {
                            fontSize(12.px)
                            color(Color(SilkColors.warning))
                            marginBottom(16.px)
                        }
                    }) { Text("⚠ ${dirWarning}，请手动输入或选择工作目录。") }
                }
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        justifyContent(JustifyContent.FlexEnd)
                        property("gap", "8px")
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
                        onClick {
                            showCreateDialog = false
                            newName = ""
                            newInitialDir = ""
                            selectedAgentType = ""
                            selectedPermissionMode = ""
                            dirWarning = null
                        }
                    }) { Text("取消") }
                    Button({
                        val canCreate = newName.isNotBlank() && bridgeConnected && newInitialDir.isNotBlank()
                        if (!canCreate) attr("disabled", "")
                        style {
                            backgroundColor(
                                if (canCreate) Color(SilkColors.primary)
                                else Color(SilkColors.primaryLight)
                            )
                            color(Color.white)
                            border(0.px)
                            borderRadius(6.px)
                            padding(8.px, 16.px)
                            property("cursor", if (canCreate) "pointer" else "not-allowed")
                        }
                        attr(
                            "title",
                            when {
                                !bridgeConnected -> "Bridge 未连接，无法创建"
                                newName.isBlank() -> "请输入工作流名称"
                                newInitialDir.isBlank() -> "请填写或选择工作目录"
                                else -> "创建工作流"
                            },
                        )
                        onClick {
                            if (!canCreate) return@onClick
                            val initDir = newInitialDir.trim()
                            scope.launch {
                                // 1. 信任目录检查
                                val trustCheck = ApiClient.checkTrustedDir(user.id, initDir)
                                when (trustCheck) {
                                    is ApiClient.TrustCheckResult.BridgeDisconnected -> {
                                        kotlinx.browser.window.alert("Bridge 未连接，无法创建工作流。请先启动 Bridge Agent。")
                                        return@launch
                                    }
                                    is ApiClient.TrustCheckResult.NotTrusted -> {
                                        trustConfirmPath = initDir
                                        trustConfirmBridgeId = trustCheck.bridgeId
                                        showTrustConfirm = true
                                        return@launch
                                    }
                                    is ApiClient.TrustCheckResult.Error -> {
                                        kotlinx.browser.window.alert("检查信任状态失败：${trustCheck.message}")
                                        return@launch
                                    }
                                    else -> {} // 已信任，继续
                                }

                                // 2. 创建 workflow
                                performCreateWorkflow()
                            }
                        }
                    }) { Text("创建") }
                }
            }
        }
    }

    // 创建工作流对话框里的 Folder Picker
    if (showCreatePicker) {
        FolderPickerDialog(
            userId = user.id,
            initialPath = newInitialDir.ifBlank { null },
            onDismiss = { showCreatePicker = false },
            onConfirm = { selectedPath ->
                newInitialDir = selectedPath
                showCreatePicker = false
            },
        )
    }

    if (showTrustConfirm) {
        TrustConfirmDialog(
            path = trustConfirmPath,
            bridgeId = trustConfirmBridgeId,
            onDismiss = { onShowTrustConfirmChange(false) },
            onTrust = {
                handleWorkflowCreateTrustConfirm(
                    scope = scope,
                    userId = userId,
                    trustConfirmPath = trustConfirmPath,
                    newName = newName,
                    newInitialDir = newInitialDir,
                    selectedAgentType = selectedAgentType,
                    selectedPermissionMode = selectedPermissionMode,
                    onShowTrustConfirmChange = onShowTrustConfirmChange,
                    onWorkflowsChange = onWorkflowsChange,
                    onSelectedWorkflowChange = onSelectedWorkflowChange,
                    onDismissCreateDialog = onDismissCreateDialog,
                    onBridgeConnectedChange = onBridgeConnectedChange,
                    onSelectedAgentTypeChange = onSelectedAgentTypeChange,
                    onSelectedPermissionModeChange = onSelectedPermissionModeChange,
                    onDirWarningChange = onDirWarningChange,
                    onInitialDirChange = onInitialDirChange,
                )
            },
        )
    }
}

@Composable
private fun WorkflowCreateDialog(
    newName: String,
    newInitialDir: String,
    availableAgents: List<AgentInfo>,
    selectedAgentType: String,
    selectedPermissionMode: String,
    bridgeConnected: Boolean,
    dirWarning: String?,
    onNameChange: (String) -> Unit,
    onInitialDirChange: (String) -> Unit,
    onSelectedAgentTypeChange: (String) -> Unit,
    onSelectedPermissionModeChange: (String) -> Unit,
    onOpenPicker: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    ModalOverlay(
        onDismiss = onDismiss,
        zIndex = 1000,
    ) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                padding(24.px)
                width(440.px)
                property("max-width", "90vw")
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
            }
        }) {
            H3({ style { marginTop(0.px); color(Color(SilkColors.textPrimary)) } }) { Text("创建工作流") }
            WorkflowCreateNameField(
                value = newName,
                onValueChange = onNameChange,
            )
            WorkflowCreateAgentField(
                availableAgents = availableAgents,
                selectedAgentType = selectedAgentType,
                onSelectedAgentTypeChange = onSelectedAgentTypeChange,
            )
            WorkflowCreatePermissionField(
                selectedPermissionMode = selectedPermissionMode,
                onSelectedPermissionModeChange = onSelectedPermissionModeChange,
            )
            WorkflowCreateDirectoryField(
                newInitialDir = newInitialDir,
                bridgeConnected = bridgeConnected,
                dirWarning = dirWarning,
                onInitialDirChange = onInitialDirChange,
                onOpenPicker = onOpenPicker,
            )
            WorkflowCreateDirectoryWarning(
                bridgeConnected = bridgeConnected,
                dirWarning = dirWarning,
            )
            WorkflowCreateDialogActions(
                newName = newName,
                newInitialDir = newInitialDir,
                bridgeConnected = bridgeConnected,
                onDismiss = onDismiss,
                onCreate = onCreate,
            )
        }
    }
}

@Composable
private fun WorkflowCreateNameField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Input(InputType.Text) {
        value(value)
        onInput { onValueChange(it.value) }
        attr("placeholder", "工作流名称")
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
private fun WorkflowCreateAgentField(
    availableAgents: List<AgentInfo>,
    selectedAgentType: String,
    onSelectedAgentTypeChange: (String) -> Unit,
) {
    WorkflowCreateFieldLabel("Agent")
    Select({
        style {
            width(100.percent)
            height(40.px)
            borderRadius(6.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            padding(8.px)
            fontSize(14.px)
            marginBottom(12.px)
            property("box-sizing", "border-box")
            backgroundColor(Color.white)
        }
        onChange { event ->
            val newValue = event.value ?: return@onChange
            onSelectedAgentTypeChange(newValue)
        }
    }) {
        if (availableAgents.isEmpty()) {
            Option("") { Text("加载中…") }
        } else {
            availableAgents.forEach { agent ->
                Option(
                    value = agent.agentType,
                    attrs = {
                        if (!agent.connected) {
                            attr("disabled", "")
                        }
                        if (agent.agentType == selectedAgentType) {
                            attr("selected", "")
                        }
                    },
                ) {
                    Text("${agent.displayName}${if (agent.connected) "" else "（未连接）"}")
                }
            }
        }
    }
}

@Composable
private fun WorkflowCreatePermissionField(
    selectedPermissionMode: String,
    onSelectedPermissionModeChange: (String) -> Unit,
) {
    WorkflowCreateFieldLabel("权限模式")
    Select({
        style {
            width(100.percent)
            height(40.px)
            borderRadius(6.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            padding(8.px)
            fontSize(14.px)
            marginBottom(12.px)
            property("box-sizing", "border-box")
            backgroundColor(Color.white)
        }
        onChange { event ->
            onSelectedPermissionModeChange(event.value ?: "")
        }
    }) {
        Option("", attrs = { if (selectedPermissionMode.isBlank()) attr("selected", "") }) { Text("Interactive") }
        Option("ACCEPT_EDITS", attrs = { if (selectedPermissionMode == "ACCEPT_EDITS") attr("selected", "") }) { Text("Accept Edits") }
        Option("BYPASS", attrs = { if (selectedPermissionMode == "BYPASS") attr("selected", "") }) { Text("Bypass") }
    }
}

@Composable
private fun WorkflowCreateFieldLabel(label: String) {
    Span({
        style {
            fontSize(12.px)
            color(Color(SilkColors.textSecondary))
            property("display", "block")
            marginBottom(4.px)
        }
    }) { Text(label) }
}

@Composable
private fun WorkflowCreateDirectoryField(
    newInitialDir: String,
    bridgeConnected: Boolean,
    dirWarning: String?,
    onInitialDirChange: (String) -> Unit,
    onOpenPicker: () -> Unit,
) {
    WorkflowCreateFieldLabel("工作目录")
    Div({
        style {
            display(DisplayStyle.Flex)
            property("gap", "8px")
            marginBottom(if (!bridgeConnected || dirWarning != null) 6.px else 16.px)
        }
    }) {
        Input(InputType.Text) {
            value(newInitialDir)
            onInput { onInitialDirChange(it.value) }
            attr("placeholder", workflowCreateDirectoryPlaceholder(bridgeConnected, dirWarning))
            if (!bridgeConnected) {
                attr("disabled", "")
            }
            style {
                property("flex", "1")
                height(40.px)
                borderRadius(6.px)
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                padding(8.px)
                fontSize(13.px)
                fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                property("box-sizing", "border-box")
                if (!bridgeConnected) {
                    backgroundColor(Color(SilkColors.surface))
                    color(Color(SilkColors.textLight))
                }
            }
        }
        Button({
            val pickerDisabled = !bridgeConnected
            if (pickerDisabled) {
                attr("disabled", "")
            }
            style {
                backgroundColor(Color("transparent"))
                color(Color(if (pickerDisabled) SilkColors.textLight else SilkColors.primary))
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                borderRadius(6.px)
                padding(6.px, 10.px)
                property("cursor", if (pickerDisabled) "not-allowed" else "pointer")
                fontSize(13.px)
                property("white-space", "nowrap")
            }
            attr("title", "浏览选择目录（需 Bridge 在线）")
            onClick {
                if (!pickerDisabled) {
                    onOpenPicker()
                }
            }
        }) { Text("\uD83D\uDCC2 选择…") }
    }
}

@Composable
private fun WorkflowCreateDirectoryWarning(
    bridgeConnected: Boolean,
    dirWarning: String?,
) {
    when {
        !bridgeConnected -> Div({
            style {
                fontSize(12.px)
                color(Color(SilkColors.error))
                marginBottom(16.px)
            }
        }) { Text("⚠ Bridge 未连接。请先启动 Bridge Agent 再创建工作流。") }

        dirWarning != null -> Div({
            style {
                fontSize(12.px)
                color(Color(SilkColors.warning))
                marginBottom(16.px)
            }
        }) { Text("⚠ ${dirWarning}，请手动输入或选择工作目录。") }
    }
}

@Composable
private fun WorkflowCreateDialogActions(
    newName: String,
    newInitialDir: String,
    bridgeConnected: Boolean,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    val canCreate = workflowCreateCanSubmit(newName, bridgeConnected, newInitialDir)

    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.FlexEnd)
            property("gap", "8px")
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
            onClick { onDismiss() }
        }) { Text("取消") }
        Button({
            if (!canCreate) {
                attr("disabled", "")
            }
            style {
                backgroundColor(Color(if (canCreate) SilkColors.primary else SilkColors.primaryLight))
                color(Color.white)
                border(0.px)
                borderRadius(6.px)
                padding(8.px, 16.px)
                property("cursor", if (canCreate) "pointer" else "not-allowed")
            }
            attr("title", workflowCreateSubmitTitle(newName, bridgeConnected, newInitialDir))
            onClick {
                if (canCreate) {
                    onCreate()
                }
            }
        }) { Text("创建") }
    }
}

private fun workflowCreateCanSubmit(
    newName: String,
    bridgeConnected: Boolean,
    newInitialDir: String,
): Boolean = newName.isNotBlank() && bridgeConnected && newInitialDir.isNotBlank()

private fun workflowCreateSubmitTitle(
    newName: String,
    bridgeConnected: Boolean,
    newInitialDir: String,
): String = when {
    !bridgeConnected -> "Bridge 未连接，无法创建"
    newName.isBlank() -> "请输入工作流名称"
    newInitialDir.isBlank() -> "请填写或选择工作目录"
    else -> "创建工作流"
}

private fun workflowCreateDirectoryPlaceholder(
    bridgeConnected: Boolean,
    dirWarning: String?,
): String = when {
    !bridgeConnected -> "Bridge 未连接，请先启动 Bridge"
    dirWarning != null -> "请输入或选择工作目录"
    else -> "加载默认目录中…"
}

private suspend fun initializeWorkflowCreateDialog(
    userId: String,
    currentInitialDir: String,
    currentSelectedAgentType: String,
    onAvailableAgentsChange: (List<AgentInfo>) -> Unit,
    onSelectedAgentTypeChange: (String) -> Unit,
    onBridgeConnectedChange: (Boolean) -> Unit,
    onDirWarningChange: (String?) -> Unit,
    onInitialDirChange: (String) -> Unit,
) {
    val agents = ApiClient.listAgents(userId)
    onAvailableAgentsChange(agents)
    if (currentSelectedAgentType.isBlank()) {
        onSelectedAgentTypeChange(defaultWorkflowAgentType(agents))
    }
    val bridgeConnected = agents.any { it.connected }
    onBridgeConnectedChange(bridgeConnected)
    if (currentInitialDir.isBlank() && bridgeConnected) {
        onDirWarningChange(null)
        val response = ApiClient.listCcDir(userId, null)
        if (response.success && response.path.isNotBlank()) {
            onInitialDirChange(response.path)
        } else {
            onDirWarningChange(response.error ?: "无法获取默认目录")
        }
    }
}

private fun defaultWorkflowAgentType(agents: List<AgentInfo>): String = when {
    agents.any { it.agentType == "codex" && it.connected } -> "codex"
    agents.any { it.agentType == "claude_code" && it.connected } -> "claude_code"
    else -> "claude_code"
}

private fun handleWorkflowCreateSubmit(
    scope: CoroutineScope,
    userId: String,
    newName: String,
    newInitialDir: String,
    selectedAgentType: String,
    selectedPermissionMode: String,
    onShowTrustConfirmChange: (Boolean) -> Unit,
    onTrustConfirmPathChange: (String) -> Unit,
    onTrustConfirmBridgeIdChange: (String?) -> Unit,
    onWorkflowsChange: (List<WorkflowItem>) -> Unit,
    onSelectedWorkflowChange: (WorkflowItem?) -> Unit,
    onDismissCreateDialog: () -> Unit,
    onBridgeConnectedChange: (Boolean) -> Unit,
    onSelectedAgentTypeChange: (String) -> Unit,
    onSelectedPermissionModeChange: (String) -> Unit,
    onDirWarningChange: (String?) -> Unit,
    onInitialDirChange: (String) -> Unit,
) {
    val initDir = newInitialDir.trim()
    scope.launch {
        when (val trustCheck = ApiClient.checkTrustedDir(userId, initDir)) {
            is TrustCheckResult.BridgeDisconnected -> {
                kotlinx.browser.window.alert("Bridge 未连接，无法创建工作流。请先启动 Bridge Agent。")
            }
            is TrustCheckResult.NotTrusted -> {
                onTrustConfirmPathChange(initDir)
                onTrustConfirmBridgeIdChange(trustCheck.bridgeId)
                onShowTrustConfirmChange(true)
            }
            is TrustCheckResult.Error -> {
                kotlinx.browser.window.alert("检查信任状态失败：${trustCheck.message}")
            }
            else -> {
                launchWorkflowCreate(
                    userId = userId,
                    newName = newName,
                    newInitialDir = newInitialDir,
                    selectedAgentType = selectedAgentType,
                    selectedPermissionMode = selectedPermissionMode,
                    onWorkflowsChange = onWorkflowsChange,
                    onSelectedWorkflowChange = onSelectedWorkflowChange,
                    onDismissCreateDialog = onDismissCreateDialog,
                    onBridgeConnectedChange = onBridgeConnectedChange,
                    onSelectedAgentTypeChange = onSelectedAgentTypeChange,
                    onSelectedPermissionModeChange = onSelectedPermissionModeChange,
                    onDirWarningChange = onDirWarningChange,
                    onInitialDirChange = onInitialDirChange,
                )
            }
        }
    }
}

private fun handleWorkflowCreateTrustConfirm(
    scope: CoroutineScope,
    userId: String,
    trustConfirmPath: String,
    newName: String,
    newInitialDir: String,
    selectedAgentType: String,
    selectedPermissionMode: String,
    onShowTrustConfirmChange: (Boolean) -> Unit,
    onWorkflowsChange: (List<WorkflowItem>) -> Unit,
    onSelectedWorkflowChange: (WorkflowItem?) -> Unit,
    onDismissCreateDialog: () -> Unit,
    onBridgeConnectedChange: (Boolean) -> Unit,
    onSelectedAgentTypeChange: (String) -> Unit,
    onSelectedPermissionModeChange: (String) -> Unit,
    onDirWarningChange: (String?) -> Unit,
    onInitialDirChange: (String) -> Unit,
) {
    scope.launch {
        val added = ApiClient.addTrustedDir(userId, trustConfirmPath)
        if (!added) {
            kotlinx.browser.window.alert("添加信任记录失败，请重试。")
            return@launch
        }
        onShowTrustConfirmChange(false)
        launchWorkflowCreate(
            userId = userId,
            newName = newName,
            newInitialDir = newInitialDir,
            selectedAgentType = selectedAgentType,
            selectedPermissionMode = selectedPermissionMode,
            onWorkflowsChange = onWorkflowsChange,
            onSelectedWorkflowChange = onSelectedWorkflowChange,
            onDismissCreateDialog = onDismissCreateDialog,
            onBridgeConnectedChange = onBridgeConnectedChange,
            onSelectedAgentTypeChange = onSelectedAgentTypeChange,
            onSelectedPermissionModeChange = onSelectedPermissionModeChange,
            onDirWarningChange = onDirWarningChange,
            onInitialDirChange = onInitialDirChange,
        )
    }
}

private suspend fun launchWorkflowCreate(
    userId: String,
    newName: String,
    newInitialDir: String,
    selectedAgentType: String,
    selectedPermissionMode: String,
    onWorkflowsChange: (List<WorkflowItem>) -> Unit,
    onSelectedWorkflowChange: (WorkflowItem?) -> Unit,
    onDismissCreateDialog: () -> Unit,
    onBridgeConnectedChange: (Boolean) -> Unit,
    onSelectedAgentTypeChange: (String) -> Unit,
    onSelectedPermissionModeChange: (String) -> Unit,
    onDirWarningChange: (String?) -> Unit,
    onInitialDirChange: (String) -> Unit,
) {
    val agentType = selectedAgentType.ifBlank { "claude_code" }
    val result = ApiClient.createWorkflow(
        newName.trim(),
        "",
        userId,
        newInitialDir.trim(),
        agentType,
        selectedPermissionMode,
    )
    onWorkflowsChange(ApiClient.getWorkflows(userId))
    when (result) {
        is CreateWorkflowResult.Ok -> {
            onSelectedWorkflowChange(result.workflow)
            onDismissCreateDialog()
            onSelectedAgentTypeChange("")
            onSelectedPermissionModeChange("")
            onDirWarningChange(null)
            onInitialDirChange("")
        }
        is CreateWorkflowResult.Err -> {
            kotlinx.browser.window.alert("创建工作流失败：${result.message}")
            val agents = ApiClient.listAgents(userId)
            onBridgeConnectedChange(agents.any { it.connected })
        }
    }
}

@Composable
private fun WorkflowManagementDialogs(
    scope: CoroutineScope,
    userId: String,
    menuWorkflow: WorkflowItem?,
    renameTarget: WorkflowItem?,
    renameText: String,
    deleteTarget: WorkflowItem?,
    selectedWorkflowId: String?,
    onMenuWorkflowChange: (WorkflowItem?) -> Unit,
    onRenameTargetChange: (WorkflowItem?) -> Unit,
    onRenameTextChange: (String) -> Unit,
    onDeleteTargetChange: (WorkflowItem?) -> Unit,
    onSelectedWorkflowChange: (WorkflowItem?) -> Unit,
    onWorkflowsChange: (List<WorkflowItem>) -> Unit,
) {
    menuWorkflow?.let { workflow ->
        WorkflowActionMenuDialog(
            workflow = workflow,
            onDismiss = { onMenuWorkflowChange(null) },
            onRename = {
                onRenameTargetChange(workflow)
                onRenameTextChange(workflow.name)
                onMenuWorkflowChange(null)
            },
            onDelete = {
                onDeleteTargetChange(workflow)
                onMenuWorkflowChange(null)
            },
        )
    }

    renameTarget?.let { workflow ->
        WorkflowRenameDialog(
            workflowName = workflow.name,
            renameText = renameText,
            onRenameTextChange = onRenameTextChange,
            onDismiss = {
                onRenameTargetChange(null)
                onRenameTextChange("")
            },
            onConfirm = {
                scope.launch {
                    val updated = ApiClient.renameWorkflow(workflow.id, userId, renameText.trim())
                    if (updated != null) {
                        onWorkflowsChange(ApiClient.getWorkflows(userId))
                        onRenameTargetChange(null)
                        onRenameTextChange("")
                    } else {
                        kotlinx.browser.window.alert("重命名失败")
                    }
                }
            },
        )
    }

    deleteTarget?.let { workflow ->
        WorkflowDeleteDialog(
            workflowName = workflow.name,
            onDismiss = { onDeleteTargetChange(null) },
            onConfirm = {
                scope.launch {
                    ApiClient.deleteWorkflow(workflow.id, userId)
                    if (selectedWorkflowId == workflow.id) {
                        onSelectedWorkflowChange(null)
                    }
                    onWorkflowsChange(ApiClient.getWorkflows(userId))
                    onDeleteTargetChange(null)
                }
            },
        )
    }
}

@Composable
private fun WorkflowActionMenuDialog(
    workflow: WorkflowItem,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalOverlay(
        onDismiss = onDismiss,
        zIndex = 1001,
    ) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                padding(16.px)
                width(200.px)
                property("box-shadow", "0 4px 16px rgba(0,0,0,0.12)")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "4px")
            }
        }) {
            Span({
                style {
                    fontSize(14.px)
                    fontWeight("bold")
                    color(Color(SilkColors.textPrimary))
                    padding(8.px, 12.px)
                }
            }) { Text(workflow.name) }
            Div({
                style {
                    height(1.px)
                    backgroundColor(Color(SilkColors.border))
                    marginTop(4.px)
                    marginBottom(4.px)
                }
            }) {}
            WorkflowActionMenuButton("✏ 重命名", SilkColors.textPrimary, onRename)
            WorkflowActionMenuButton("🗑 删除", SilkColors.error, onDelete)
            WorkflowActionMenuButton("取消", SilkColors.textSecondary, onDismiss)
        }
    }
}

@Composable
private fun WorkflowActionMenuButton(
    label: String,
    textColor: String,
    onClick: () -> Unit,
) {
    Button({
        style {
            backgroundColor(Color("transparent"))
            color(Color(textColor))
            border(0.px)
            borderRadius(6.px)
            padding(10.px, 12.px)
            property("cursor", "pointer")
            fontSize(14.px)
            property("text-align", "left")
        }
        onClick { onClick() }
    }) { Text(label) }
}

@Composable
private fun WorkflowRenameDialog(
    workflowName: String,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalOverlay(
        onDismiss = onDismiss,
        zIndex = 1002,
    ) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                padding(24.px)
                width(380.px)
                property("max-width", "90vw")
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
            }
        }) {
            H3({ style { marginTop(0.px); color(Color(SilkColors.textPrimary)) } }) { Text("重命名工作流") }
            Input(InputType.Text) {
                value(renameText)
                onInput { onRenameTextChange(it.value) }
                attr("placeholder", workflowName)
                style {
                    width(100.percent)
                    height(40.px)
                    borderRadius(6.px)
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    padding(8.px)
                    fontSize(14.px)
                    marginBottom(16.px)
                    property("box-sizing", "border-box")
                }
            }
            WorkflowConfirmActions(
                confirmLabel = "确认",
                confirmColor = SilkColors.primary,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
            )
        }
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun WorkflowDeleteDialog(
    workflowName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalOverlay(
        onDismiss = onDismiss,
        zIndex = 1002,
    ) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                padding(24.px)
                width(360.px)
                property("max-width", "90vw")
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
            }
        }) {
            H3({ style { marginTop(0.px); color(Color(SilkColors.error)) } }) { Text("删除工作流") }
            P({ style { fontSize(14.px); color(Color(SilkColors.textPrimary)); marginBottom(20.px) } }) {
                Text("确定要删除「${workflowName}」吗？此操作不可撤销。")
            }
            WorkflowConfirmActions(
                confirmLabel = "删除",
                confirmColor = SilkColors.error,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
private fun WorkflowConfirmActions(
    confirmLabel: String,
    confirmColor: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.FlexEnd)
            property("gap", "8px")
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
            onClick { onDismiss() }
        }) { Text("取消") }
        Button({
            style {
                backgroundColor(Color(confirmColor))
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

@Composable
private fun WorkflowChatPanel(
    appState: WebAppState,
    userId: String,
    userName: String,
    groupId: String,
    workflowId: String,
    workflowName: String,
    pendingMessageId: String?,
    onCaptureToKnowledgeBase: (Message) -> Unit,
    onMessageNavigated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val wsUrl = remember { backendWsOrigin() }
    val chatClient = remember { ChatClient(wsUrl) }
    val messages by chatClient.messages.collectAsState()
    val transientMessage by chatClient.transientMessage.collectAsState()
    val statusMessages by chatClient.statusMessages.collectAsState()
    val connectionState by chatClient.connectionState.collectAsState()
    val isGenerating by chatClient.isGenerating.collectAsState()
    val isHistoryLoading by chatClient.isLoadingHistory.collectAsState()
    var messageText by remember(groupId) { mutableStateOf("") }
    var kbContextSelection by remember(groupId) { mutableStateOf(KnowledgeBaseContextSelection()) }
    var persistentKbExcludedSpaceIds by remember(groupId) { mutableStateOf<List<String>?>(null) }
    var restoredKbContextSelection by remember(groupId) { mutableStateOf(false) }
    var workingDir by remember(groupId) { mutableStateOf("") }
    var activeAgentDisplay by remember(groupId) { mutableStateOf("") }
    var permissionMode by remember(groupId) { mutableStateOf("") }
    var availableAgents by remember(groupId) { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var showFolderPicker by remember(groupId) { mutableStateOf(false) }
    var showTrustConfirm by remember(groupId) { mutableStateOf(false) }
    var trustConfirmPath by remember(groupId) { mutableStateOf("") }
    var trustConfirmBridgeId by remember(groupId) { mutableStateOf<String?>(null) }
    var showPermModeDropdown by remember(groupId) { mutableStateOf(false) }
    var showAgentDropdown by remember(groupId) { mutableStateOf(false) }
    var switchError by remember(groupId) { mutableStateOf<String?>(null) }
    var sourcePanelOpen by remember(groupId) { mutableStateOf(false) }
    var diffRefreshSignal by remember(groupId) { mutableStateOf(0) }
    var sourcePanelWidth by remember { mutableStateOf(LayoutPrefs.getInt("silk_wf_scpanel_w", 420)) }
    var kbContextSelection by remember(groupId) { mutableStateOf(KnowledgeBaseContextSelection()) }
    var kbCaptureDraft by remember(groupId) { mutableStateOf<KnowledgeCaptureDraft?>(null) }
    var kbCaptureTopics by remember(groupId) { mutableStateOf<List<KBTopicItem>>(emptyList()) }
    var kbCaptureGroups by remember(groupId) { mutableStateOf<List<Group>>(emptyList()) }
    var kbCaptureSelectedSpaceId by remember(groupId) { mutableStateOf(PERSONAL_SPACE_ID) }
    var kbCaptureSelectedTopicId by remember(groupId) { mutableStateOf("") }
    var kbCaptureTitle by remember(groupId) { mutableStateOf("") }
    var kbCaptureContent by remember(groupId) { mutableStateOf("") }
    var kbCaptureSaving by remember(groupId) { mutableStateOf(false) }
    var kbCaptureResult by remember(groupId) { mutableStateOf<String?>(null) }
    val resetKnowledgeCaptureDialog: () -> Unit = {
        kbCaptureDraft = null
        kbCaptureTopics = emptyList()
        kbCaptureGroups = emptyList()
        kbCaptureSelectedSpaceId = PERSONAL_SPACE_ID
        kbCaptureSelectedTopicId = ""
        kbCaptureTitle = ""
        kbCaptureContent = ""
        kbCaptureSaving = false
        kbCaptureResult = null
    }
    val onCaptureToKnowledgeBase: (Message) -> Unit = { message ->
        scope.launch {
            val context = loadKnowledgeCaptureContext(userId)
            val preferredSpaceId = preferredKnowledgeCaptureSpaceId(groupId, context.topics)
            kbCaptureDraft = KnowledgeCaptureDraft(
                message = message,
                sourceType = KBSourceType.WORKFLOW,
                sourceGroupId = groupId,
                workflowId = workflowId,
                preferredSpaceId = preferredSpaceId,
            )
            kbCaptureTopics = context.topics
            kbCaptureGroups = context.groups
            kbCaptureSelectedSpaceId = preferredSpaceId
            kbCaptureSelectedTopicId = defaultKnowledgeCaptureTopicId(context.topics, preferredSpaceId).orEmpty()
            kbCaptureTitle = buildDefaultKnowledgeCaptureTitle(message.content)
            kbCaptureContent = message.content
            kbCaptureSaving = false
            kbCaptureResult = if (context.topics.isEmpty()) "还没有可用主题，请先去知识库创建主题。" else null
        }
    }

    // 拉取当前 CC 工作目录：
    // - groupId 变化时（切换工作流）
    // - WebSocket 连接状态变化（连上后 autoActivateForWorkflow 才会创建 CC state）
    //
    // 注意：原先这里还依赖 messages.size，每条新消息都会 round-trip 一次 /cc-state。
    // 自从移除聊天 /cd 命令后，workingDir 只会在这些入口被改：
    //   1. 前端自己通过 "更改" 按钮调 cdCcDir（成功后已直接本地 set workingDir，无需拉）
    //   2. 创建工作流时后端 cdSync（返回 workflow 时前端会重新进入面板，这里会触发）
    //   3. session_resumed / new_session 事件（极低频）
    // 因此此处不再按消息数量 poll；若将来需要捕获 3 的变化，可监听特定系统消息再触发。
    LaunchedEffect(groupId, connectionState) {
        if (groupId.isBlank()) return@LaunchedEffect
        // WebSocket 连上后稍等片刻，让后端 autoActivateForWorkflow 完成
        if (connectionState == ConnectionState.CONNECTED) {
            kotlinx.coroutines.delay(200)
        }
        val snap = ApiClient.getCcState(userId, groupId)
        if (snap.success) {
            workingDir = snap.workingDir
            activeAgentDisplay = snap.agentDisplayName
            permissionMode = snap.permissionMode
        }
        availableAgents = ApiClient.listAgents(userId)
    }

    // 监听新增消息，刷新 activeAgent / permissionMode 显示。
    // 后端在 agent 切换、权限模式切换时都会广播 SYSTEM 消息（"已切换到 ..."），
    // 新增消息改变 messages.size → 触发此 effect → 仅检查最新一条是否匹配。
    LaunchedEffect(messages.size) {
        val latest = messages.lastOrNull() ?: return@LaunchedEffect
        val isAgentStatusMessage = latest.type == com.silk.shared.models.MessageType.SYSTEM &&
            (latest.content.startsWith("已切换到") || latest.content.contains("已激活") || latest.content.contains("已退出 agent"))
        if (isAgentStatusMessage) {
            val snap = ApiClient.getCcState(userId, groupId)
            if (snap.success) {
                activeAgentDisplay = snap.agentDisplayName
                permissionMode = snap.permissionMode
            }
        }
    }

    // Connect WebSocket when groupId changes
    // key(wf.groupId) 保证切换 workflow 时此组件被销毁重建，ChatClient 始终是新实例，
    // 无需 delay 等待旧连接关闭
    LaunchedEffect(groupId) {
        chatClient.clearMessages()
        try {
            chatClient.connect(userId, userName, groupId)
        } catch (e: dynamic) {
            console.error("❌ 工作流 WebSocket 连接失败:", e.toString())
        }
    }

    // Disconnect on dispose
    DisposableEffect(Unit) {
        onDispose {
            try {
                scope.launch { chatClient.disconnect() }
            } catch (_: dynamic) {}
        }
    }

    LaunchedEffect(groupId) {
        persistentKbExcludedSpaceIds = ApiClient.getKBContextPreferences(userId).excludedSpaceIds
    }

    LaunchedEffect(groupId, isHistoryLoading, messages, persistentKbExcludedSpaceIds) {
        val persistentExcludedSpaceIds = persistentKbExcludedSpaceIds
        if (restoredKbContextSelection || isHistoryLoading || persistentExcludedSpaceIds == null) {
            return@LaunchedEffect
        }
        kbContextSelection = mergeKnowledgeBaseContextSelectionWithPersistentSpaces(
            restoredSelection = latestKnowledgeBaseContextSelection(messages, userId),
            persistentExcludedSpaceIds = persistentExcludedSpaceIds,
        )
        restoredKbContextSelection = true
    }

    HandlePendingWorkflowMessageNavigation(
        groupId = groupId,
        isHistoryLoading = isHistoryLoading,
        messagesSize = messages.size,
        pendingMessageId = pendingMessageId,
        onMessageNavigated = onMessageNavigated,
    )

    // Auto-scroll
    LaunchedEffect(messages.size, transientMessage, statusMessages.size) {
        js("""
            setTimeout(function() {
                var c = document.getElementById('wf-messages');
                if (c) c.scrollTop = c.scrollHeight;
            }, 100);
        """)
    }

    // 代理回合结束（isGenerating → false）后去抖 ~0.5s bump 刷新信号，让源代码管理面板自动重拉状态
    LaunchedEffect(isGenerating) {
        if (!isGenerating && sourcePanelOpen) {
            kotlinx.coroutines.delay(500)
            diffRefreshSignal += 1
        }
    }

    // 横向布局（D5）：左聊天列 + 右"源代码管理"面板。对话框/选择器是 fixed 叠层，留在本 Row 之后
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Row)
            property("flex", "1")
            property("min-height", "0")
            width(100.percent)
            property("overflow", "hidden")
        }
    }) {
    // 聊天列（保留原有 header/messages/input 纵向布局）
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("flex", "1")
            property("min-width", "0")
            height(100.percent)
        }
    }) {
    // Header
    Div({
        style {
            property("flex-shrink", "0")
            padding(14.px, 20.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "12px")
            backgroundColor(Color(SilkColors.surfaceElevated))
        }
    }) {
        Span({ style { fontSize(20.px) } }) { Text("\uD83E\uDD16") }
        WorkflowChatHeaderTitle(
            workflowName = workflowName,
            workingDir = workingDir,
            onChangeDirectory = onChangeDirectory,
        )
        WorkflowConnectionStatus(connectionState)
    }
}

@Composable
private fun WorkflowChatHeaderTitle(
    workflowName: String,
    workingDir: String,
    onChangeDirectory: () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "2px")
            property("flex", "1")
            property("min-width", "0")
        }
    }) {
        Span({
            style {
                fontSize(16.px)
                fontWeight("600")
                color(Color(SilkColors.textPrimary))
                property("overflow", "hidden")
                property("text-overflow", "ellipsis")
                property("white-space", "nowrap")
            }
        }) { Text(workflowName) }
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "8px")
                property("min-width", "0")
            }
        }) {
            Span({
                style {
                    fontSize(16.px)
                    fontWeight("600")
                    color(Color(SilkColors.textPrimary))
                    property("overflow", "hidden")
                    property("text-overflow", "ellipsis")
                    property("white-space", "nowrap")
                }
            }) { Text(workflowName) }
            // 工作目录 + 内联"更改"链接（贴在路径右侧，视觉更紧凑）
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "8px")
                    property("min-width", "0")
                }
            }) {
                if (workingDir.isNotBlank()) {
                    Span({
                        style {
                            fontSize(11.px)
                            color(Color(SilkColors.textSecondary))
                            fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                            property("overflow", "hidden")
                            property("text-overflow", "ellipsis")
                            property("white-space", "nowrap")
                            property("min-width", "0")
                        }
                        attr("title", workingDir)
                    }) { Text("\uD83D\uDCC1 $workingDir") }
                }
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textSecondary))
                        fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                        property("overflow", "hidden")
                        property("text-overflow", "ellipsis")
                        property("white-space", "nowrap")
                        property("min-width", "0")
                    }
                    attr("title", "切换工作目录")
                    onClick { showFolderPicker = true }
                }) {
                    Text("更改")
                }
            }
            Span({
                style {
                    fontSize(11.px)
                    color(Color(SilkColors.primary))
                    property("cursor", "pointer")
                    property("flex-shrink", "0")
                    property("white-space", "nowrap")
                    property("user-select", "none")
                    property("text-decoration", "underline")
                    property("text-decoration-style", "dotted")
                }
                attr("title", "切换工作目录")
                onClick { onChangeDirectory() }
            }) {
                Text("更改")
            }
        }
        // 源代码管理面板开关（D5）：打开时立即触发一次刷新
        Button({
            onClick { sourcePanelOpen = !sourcePanelOpen; if (sourcePanelOpen) diffRefreshSignal += 1 }
            style { property("cursor", "pointer"); property("margin-left", "auto") }
        }) { Text(if (sourcePanelOpen) "✕ 审查" else "⌥ 审查") }
    }
}

@Composable
private fun WorkflowConnectionStatus(connectionState: ConnectionState) {
    if (connectionState == ConnectionState.CONNECTED) {
        return
    }

    Span({
        style {
            fontSize(12.px)
            color(Color(if (connectionState == ConnectionState.CONNECTING) "#FF9800" else "#F44336"))
        }
    }) {
        Text(if (connectionState == ConnectionState.CONNECTING) "● 连接中..." else "● 会话连接失败")
    }
}

@Composable
private fun WorkflowMessagesArea(
    messages: List<Message>,
    statusMessages: List<Message>,
    transientMessage: Message?,
    userId: String,
    userName: String,
    chatClient: ChatClient,
    onCaptureToKnowledgeBase: (Message) -> Unit,
) {
    Div({
        id("wf-messages")
        style {
            property("flex", "1")
            property("min-height", "0")
            property("overflow-y", "auto")
            padding(16.px)
            property("background", SilkColors.backgroundGradient)
        }
    }) {
        messages.forEachIndexed { index, message ->
            key(message.id) {
                MessageItem(
                    message = message,
                    isTransient = false,
                    isLastMessage = index == messages.lastIndex,
                    currentUserId = userId,
                    currentUserName = userName,
                    groupId = groupId,
                    chatClient = chatClient,
                    onCopy = { content -> copyTextToClipboard(content) },
                    onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
                )
            }
        }
        WorkflowStatusMessages(statusMessages)
        WorkflowTransientMessage(
            transientMessage = transientMessage,
            userId = userId,
            userName = userName,
            chatClient = chatClient,
            onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
        )
    }
}

        // Status messages（KB 上下文状态条改由输入区上方的 KnowledgeBaseContextTray 展示）
        val visibleStatusMessages = statusMessages.filterNot(::isKnowledgeBaseContextStatusMessage)
        if (visibleStatusMessages.isNotEmpty()) {
            Div({
                style {
                    color(Color("#757575"))
                    fontSize(13.px)
                    fontStyle("italic")
                    marginBottom(4.px)
                    property("white-space", "pre-wrap")
                    property("word-break", "break-word")
                }
            }) {
                visibleStatusMessages.forEach { status ->
                    Div({
                        style {
                            color(Color("#757575"))
                            fontSize(13.px)
                            fontStyle("italic")
                            marginBottom(4.px)
                            property("white-space", "pre-wrap")
                            property("word-break", "break-word")
                        }
                    }) {
                        Text(status.content)
                    }
                }
            }
        }

        // Transient (streaming) message
        transientMessage?.let { message ->
            val shouldShowTransient = message.content.isNotBlank() &&
                message.currentStep == null &&
                message.totalSteps == null &&
                !isLikelyAgentStatusContent(message.content)
            if (shouldShowTransient) {
                MessageItem(
                    message = message.copy(category = com.silk.shared.models.MessageCategory.NORMAL),
                    isTransient = true,
                    currentUserId = userId,
                    currentUserName = userName,
                    groupId = groupId,
                    chatClient = chatClient,
                    onCopy = { content -> copyTextToClipboard(content) },
                    onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
                )
            } else {
                TransientMessageItem(message)
            }
        }
    }
}

@Composable
private fun WorkflowTransientMessage(
    transientMessage: Message?,
    userId: String,
    userName: String,
    chatClient: ChatClient,
    onCaptureToKnowledgeBase: (Message) -> Unit,
) {
    val message = transientMessage ?: return
    if (shouldRenderInlineTransientMessage(message)) {
        MessageItem(
            message = message.copy(category = com.silk.shared.models.MessageCategory.NORMAL),
            isTransient = true,
            currentUserId = userId,
            currentUserName = userName,
            chatClient = chatClient,
            onCopy = { content -> copyTextToClipboard(content) },
            onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
        )
        return
    }
    TransientMessageItem(message)
}

@Composable
private fun WorkflowChatComposer(
    statusMessages: List<Message>,
    kbContextSelection: KnowledgeBaseContextSelection,
    permissionMode: String,
    activeAgentDisplay: String,
    availableAgents: List<AgentInfo>,
    showPermModeDropdown: Boolean,
    showAgentDropdown: Boolean,
    switchError: String?,
    messageText: String,
    isGenerating: Boolean,
    onTogglePermModeDropdown: () -> Unit,
    onToggleAgentDropdown: () -> Unit,
    onSelectPermMode: (String) -> Unit,
    onSelectAgent: (AgentInfo) -> Unit,
    onClearSwitchError: () -> Unit,
    onMessageTextChange: (String) -> Unit,
    onKnowledgeBaseContextSelectionChange: (KnowledgeBaseContextSelection) -> Unit,
    onStartNewSession: () -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
) {
    Div({
        style {
            property("flex-shrink", "0")
            padding(12.px, 16.px)
            property("border-top", "1px solid ${SilkColors.border}")
            backgroundColor(Color(SilkColors.surfaceElevated))
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "6px")
        }
    }) {
        KnowledgeBaseContextTray(
            statusMessages = statusMessages,
            selection = kbContextSelection,
            onSelectionChange = { kbContextSelection = it },
        )
        // Badge row: new session + permission mode + agent quick-switch
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "6px")
                property("position", "relative")
            }
        }) {
            // New session badge
            Div({ style { property("position", "relative"); display(DisplayStyle.InlineBlock) } }) {
                Span({
                    style {
                        fontSize(12.px)
                        fontWeight("500")
                        color(Color("#1565C0"))
                        backgroundColor(Color("#E3F2FD"))
                        border(1.px, LineStyle.Solid, Color("#90CAF9"))
                        borderRadius(12.px)
                        padding(2.px, 10.px)
                        property("cursor", "pointer")
                        property("user-select", "none")
                    }
                    onClick {
                        scope.launch {
                            chatClient.sendMessage(userId, userName, "/new")
                        }
                    }
                }) { Text("新会话") }
            }

            // Permission mode badge
                if (permissionMode.isNotBlank()) {
                    val modeLabel = when (permissionMode) {
                        "INTERACTIVE" -> "Interactive"
                        "ACCEPT_EDITS" -> "Accept Edits"
                        "BYPASS" -> "Bypass"
                        else -> permissionMode
                    }
                    Div({ style { property("position", "relative"); display(DisplayStyle.InlineBlock) } }) {
                        Span({
                            style {
                                fontSize(12.px)
                                fontWeight("500")
                                color(Color("#555555"))
                                backgroundColor(Color("#F7F7F7"))
                                border(1.px, LineStyle.Solid, Color("#E0E0E0"))
                                borderRadius(12.px)
                                padding(2.px, 10.px)
                                property("cursor", "pointer")
                                property("user-select", "none")
                            }
                            onClick {
                                showPermModeDropdown = !showPermModeDropdown
                                showAgentDropdown = false
                            }
                        }) { Text(modeLabel) }

                        if (showPermModeDropdown) {
                            Div({
                                style {
                                    property("position", "absolute")
                                    bottom(28.px)
                                    property("left", "0")
                                    backgroundColor(Color.white)
                                    border(1.px, LineStyle.Solid, Color("#E0E0E0"))
                                    borderRadius(8.px)
                                    property("box-shadow", "0 4px 12px rgba(0,0,0,0.12)")
                                    property("z-index", "100")
                                    property("min-width", "140px")
                                    padding(4.px)
                                }
                            }) {
                                listOf(
                                    "INTERACTIVE" to "Interactive",
                                    "ACCEPT_EDITS" to "Accept Edits",
                                    "BYPASS" to "Bypass",
                                ).forEach { (value, label) ->
                                    val isCurrent = value == permissionMode ||
                                        (value == "INTERACTIVE" && permissionMode.isBlank())
                                    Div({
                                        style {
                                            padding(8.px, 12.px)
                                            borderRadius(4.px)
                                            fontSize(14.px)
                                            property("cursor", "pointer")
                                            fontWeight(if (isCurrent) "600" else "normal")
                                            color(if (isCurrent) Color(SilkColors.primary) else Color("#333333"))
                                            if (isCurrent) backgroundColor(Color("#F0F4FF"))
                                        }
                                        onClick {
                                            showPermModeDropdown = false
                                            if (!isCurrent) {
                                                scope.launch {
                                                    val resp = ApiClient.updateCcSettings(
                                                        userId, groupId,
                                                        permissionMode = value,
                                                    )
                                                    if (resp.success) {
                                                        permissionMode = resp.permissionMode
                                                    } else {
                                                        switchError = resp.error ?: "切换失败"
                                                    }
                                                }
                                            }
                                        }
                                    }) {
                                        Text(if (isCurrent) "\u2713 $label" else "  $label")
                                    }
                                }
                            }
                        }
                    }
                }

                // Agent badge
                if (activeAgentDisplay.isNotBlank()) {
                    Div({ style { property("position", "relative"); display(DisplayStyle.InlineBlock) } }) {
                        Span({
                            style {
                                fontSize(12.px)
                                fontWeight("500")
                                color(Color("#6A1B9A"))
                                backgroundColor(Color("#F3E5F5"))
                                border(1.px, LineStyle.Solid, Color("#CE93D8"))
                                borderRadius(12.px)
                                padding(2.px, 10.px)
                                property("cursor", "pointer")
                                property("user-select", "none")
                            }
                            onClick {
                                showAgentDropdown = !showAgentDropdown
                                showPermModeDropdown = false
                            }
                        }) { Text(activeAgentDisplay) }

                        if (showAgentDropdown) {
                            Div({
                                style {
                                    property("position", "absolute")
                                    bottom(28.px)
                                    property("left", "0")
                                    backgroundColor(Color.white)
                                    border(1.px, LineStyle.Solid, Color("#E0E0E0"))
                                    borderRadius(8.px)
                                    property("box-shadow", "0 4px 12px rgba(0,0,0,0.12)")
                                    property("z-index", "100")
                                    property("min-width", "180px")
                                    padding(4.px)
                                }
                            }) {
                                availableAgents.forEach { agent ->
                                    val isCurrent = activeAgentDisplay.contains(agent.displayName) ||
                                        activeAgentDisplay.contains(agent.agentType)
                                    Div({
                                        style {
                                            padding(8.px, 12.px)
                                            borderRadius(4.px)
                                            fontSize(14.px)
                                            property("cursor", if (agent.connected) "pointer" else "default")
                                            fontWeight(if (isCurrent) "600" else "normal")
                                            color(when {
                                                isCurrent -> Color("#6A1B9A")
                                                !agent.connected -> Color("#BDBDBD")
                                                else -> Color("#333333")
                                            })
                                            if (isCurrent) backgroundColor(Color("#F3E5F5"))
                                            if (!agent.connected) property("opacity", "0.6")
                                        }
                                        onClick {
                                            if (!isCurrent && agent.connected) {
                                                showAgentDropdown = false
                                                scope.launch {
                                                    val resp = ApiClient.updateCcSettings(
                                                        userId, groupId,
                                                        activeAgent = agent.agentType,
                                                    )
                                                    if (resp.success) {
                                                        activeAgentDisplay = resp.agentDisplayName
                                                        permissionMode = resp.permissionMode
                                                    } else {
                                                        switchError = resp.error ?: "切换失败"
                                                    }
                                                }
                                            }
                                        }
                                    }) {
                                        val suffix = if (!agent.connected) "（未连接）" else ""
                                        Text(if (isCurrent) "\u2713 ${agent.displayName}$suffix" else "  ${agent.displayName}$suffix")
                                    }
                                }
                            }
                        }
                    }
                }

                // Error toast
                switchError?.let { err ->
                    Span({
                        style {
                            fontSize(11.px)
                            color(Color("#F44336"))
                        }
                    }) { Text(err) }
                    // Auto-clear after display
                    LaunchedEffect(err) {
                        kotlinx.coroutines.delay(3000)
                        switchError = null
                    }
                }
        }

        // Input row
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "10px")
            }
        }) {
        TextArea {
            value(messageText)
            onInput { onMessageTextChange(it.value) }
            attr("placeholder", "向 Agent 发送消息...（Shift+Enter 换行）")
            onKeyDown { event ->
                if (shouldSubmitWorkflowMessage(event, messageText)) {
                    event.preventDefault()
                    val text = messageText.trim()
                    messageText = ""
                    scope.launch {
                        chatClient.sendMessage(
                            userId,
                            userName,
                            text,
                            kbContextSelection.takeIf(::hasKnowledgeBaseContextSelection),
                        )
                    }
                }
            }
            style {
                property("flex", "1")
                minHeight(40.px)
                maxHeight(160.px)
                borderRadius(8.px)
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                padding(8.px, 12.px)
                fontSize(14.px)
                property("box-sizing", "border-box")
                property("outline", "none")
                property("resize", "none")
                property("white-space", "pre-wrap")
                property("line-height", "1.5")
            }
        }

        if (isGenerating) {
            WorkflowChatActionButton(
                label = "停止",
                background = "#FF4D4F",
                cursor = "pointer",
                fontWeight = "600",
                onClick = onStopGeneration,
            )
        } else {
            Button({
                style {
                    backgroundColor(
                        if (messageText.isNotBlank()) Color(SilkColors.primary) else Color(SilkColors.primaryLight)
                    )
                    color(Color.white)
                    border(0.px)
                    borderRadius(8.px)
                    padding(8.px, 16.px)
                    property("cursor", if (messageText.isNotBlank()) "pointer" else "default")
                    fontSize(14.px)
                }
                onClick {
                    if (messageText.isNotBlank()) {
                        val text = messageText.trim()
                        messageText = ""
                        scope.launch {
                            chatClient.sendMessage(
                                userId,
                                userName,
                                text,
                                kbContextSelection.takeIf(::hasKnowledgeBaseContextSelection),
                            )
                        }
                    }
                }
            }) { Text("发送") }
        }
        } // close input row Div
    }
    } // close 聊天列 Div
        if (sourcePanelOpen) {
            ColumnResizer(
                isLeftPanel = false,
                minWidth = 300,
                maxWidth = 800,
                currentWidth = { sourcePanelWidth },
                onResize = { sourcePanelWidth = it },
                onCommit = { LayoutPrefs.setInt("silk_wf_scpanel_w", sourcePanelWidth) },
            )
            SourceControlPanel(
                userId = userId,
                groupId = groupId,
                refreshSignal = diffRefreshSignal,
                widthPx = sourcePanelWidth,
            )
        }
    } // close 横向布局 Div

    // 知识库入库对话框
    kbCaptureDraft?.let { draft ->
        KnowledgeBaseCaptureDialog(
            draft = draft,
            spaceOptions = buildKnowledgeSpaceOptions(kbCaptureGroups),
            topics = kbCaptureTopics,
            selectedSpaceId = kbCaptureSelectedSpaceId,
            selectedTopicId = kbCaptureSelectedTopicId,
            title = kbCaptureTitle,
            content = kbCaptureContent,
            isSaving = kbCaptureSaving,
            resultMessage = kbCaptureResult,
            onSelectedSpaceIdChange = { kbCaptureSelectedSpaceId = it },
            onSelectedTopicIdChange = { kbCaptureSelectedTopicId = it },
            onTitleChange = { kbCaptureTitle = it },
            onContentChange = { kbCaptureContent = it },
            onDismiss = resetKnowledgeCaptureDialog,
            onConfirm = {
                if (!canSubmitKnowledgeCapture(kbCaptureSaving, kbCaptureSelectedTopicId, kbCaptureTitle, kbCaptureContent)) {
                    return@KnowledgeBaseCaptureDialog
                }
                scope.launch {
                    kbCaptureSaving = true
                    val created = ApiClient.captureKBEntry(
                        topicId = kbCaptureSelectedTopicId,
                        title = kbCaptureTitle.trim(),
                        content = kbCaptureContent,
                        tags = emptyList(),
                        userId = userId,
                        source = KBEntrySource(
                            sourceType = draft.sourceType,
                            sourceGroupId = draft.sourceGroupId,
                            workflowId = draft.workflowId,
                            messageIds = listOf(draft.message.id),
                        ),
                    )
                    kbCaptureSaving = false
                    if (created == null) {
                        kbCaptureResult = "保存失败，请确认目标主题仍可写。"
                    } else {
                        resetKnowledgeCaptureDialog()
                        appState.openKnowledgeBaseEntry(created.id, created.topicId)
                    }
                }
            },
        )
    }
}

    // Folder picker dialog (direct, no settings wrapper)
    if (showFolderPicker) {
        FolderPickerDialog(
            userId = userId,
            initialPath = workingDir.ifBlank { null },
            onDismiss = { showFolderPicker = false },
            onConfirm = { selectedPath ->
                showFolderPicker = false
                if (selectedPath != workingDir) {
                    scope.launch {
                        when (val tc = ApiClient.checkTrustedDir(userId, selectedPath)) {
                            is ApiClient.TrustCheckResult.BridgeDisconnected ->
                                switchError = "Bridge 未连接，无法切换目录。"
                            is ApiClient.TrustCheckResult.NotTrusted -> {
                                trustConfirmPath = selectedPath
                                trustConfirmBridgeId = tc.bridgeId
                                showTrustConfirm = true
                            }
                            is ApiClient.TrustCheckResult.Error ->
                                switchError = "检查信任状态失败：${tc.message}"
                            is ApiClient.TrustCheckResult.Trusted -> {
                                val cdResp = ApiClient.cdCcDir(userId, groupId, selectedPath)
                                if (cdResp.success) {
                                    workingDir = cdResp.workingDir
                                } else {
                                    switchError = "切换目录失败：${cdResp.error ?: "未知错误"}"
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    if (showTrustConfirm) {
        TrustConfirmDialog(
            path = trustConfirmPath,
            bridgeId = trustConfirmBridgeId,
            onDismiss = { showTrustConfirm = false },
            onTrust = {
                scope.launch {
                    val added = ApiClient.addTrustedDir(userId, trustConfirmPath)
                    if (!added) {
                        switchError = "添加信任记录失败，请重试。"
                        showTrustConfirm = false
                        return@launch
                    }
                    showTrustConfirm = false
                    val cdResp = ApiClient.cdCcDir(userId, groupId, trustConfirmPath)
                    if (cdResp.success) {
                        workingDir = cdResp.workingDir
                    } else {
                        switchError = "切换目录失败：${cdResp.error ?: "未知错误"}"
                    }
                }
                Button({
                    style {
                        backgroundColor(Color("transparent"))
                        color(Color(SilkColors.primary))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(6.px); padding(6.px, 10.px)
                        property("cursor", "pointer"); fontSize(13.px)
                        property("white-space", "nowrap")
                    }
                    onClick { onFolderPickerClick() }
                }) { Text("\uD83D\uDCC2 选择…") }
            }

            // 错误提示
            errorMsg?.let { msg ->
                Div({
                    style { fontSize(12.px); color(Color(SilkColors.error)); marginBottom(12.px) }
                }) { Text(msg) }
            }

            // 按钮行
            Div({
                style {
                    display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd)
                    property("gap", "8px")
                }
            }) {
                Button({
                    if (saving) attr("disabled", "")
                    style {
                        backgroundColor(Color(SilkColors.surface))
                        color(Color(SilkColors.textSecondary))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(6.px); padding(8.px, 16.px)
                        property("cursor", "pointer")
                    }
                    onClick { onDismiss() }
                }) { Text("取消") }
                Button({
                    if (saving) attr("disabled", "")
                    style {
                        backgroundColor(Color(SilkColors.primary))
                        color(Color.white); border(0.px)
                        borderRadius(6.px); padding(8.px, 16.px)
                        property("cursor", if (saving) "wait" else "pointer")
                    }
                    onClick { onSave() }
                }) { Text(if (saving) "保存中…" else "保存") }
            }
        }
    }
}

/**
 * 会话设置弹窗：工作目录 / Agent / 权限模式三合一。
 */
@Suppress("UnusedPrivateMember", "CyclomaticComplexMethod")
@Composable
private fun WorkflowSettingsDialog(
    userId: String,
    groupId: String,
    currentWorkingDir: String,
    currentAgentDisplay: String,
    currentPermissionMode: String,
    onDismiss: () -> Unit,
    onApplied: (workingDir: String, agentDisplay: String, permissionMode: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var editDir by remember { mutableStateOf(currentWorkingDir) }
    var availableAgents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var selectedAgentType by remember { mutableStateOf("") }
    var selectedPermMode by remember { mutableStateOf(currentPermissionMode) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showTrustConfirm by remember { mutableStateOf(false) }
    var trustConfirmPath by remember { mutableStateOf("") }
    var trustConfirmBridgeId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 初始化：加载 agent 列表和当前 agent 状态
    LaunchedEffect(Unit) {
        val agents = ApiClient.listAgents(userId)
        availableAgents = agents
        // 从当前显示名反查 agentType
        val snap = ApiClient.getCcState(userId, groupId)
        if (snap.success && snap.agentType.isNotBlank()) {
            // agentType 从 runtime 是 dash form，转 underscore form 用于 dropdown
            selectedAgentType = snap.agentType.replace('-', '_')
        }
    }

    suspend fun applyChanges() {
        saving = true
        errorMsg = null
        val resultDir = editDir.trim()
        var newDir = currentWorkingDir
        var newAgentDisplay = currentAgentDisplay
        var newPermMode = currentPermissionMode

        try {
            // 1. 如果目录变了，先 cd（含信任检查）
            if (resultDir.isNotBlank() && resultDir != currentWorkingDir) {
                val trustCheck = ApiClient.checkTrustedDir(userId, resultDir)
                when (trustCheck) {
                    is ApiClient.TrustCheckResult.BridgeDisconnected -> {
                        errorMsg = "Bridge 未连接，无法切换目录。"
                        return
                    }
                    is ApiClient.TrustCheckResult.NotTrusted -> {
                        trustConfirmPath = resultDir
                        trustConfirmBridgeId = trustCheck.bridgeId
                        showTrustConfirm = true
                        return
                    }
                    is ApiClient.TrustCheckResult.Error -> {
                        errorMsg = "检查信任状态失败：${trustCheck.message}"
                        return
                    }
                    else -> {}
                }
                val cdResp = ApiClient.cdCcDir(userId, groupId, resultDir)
                if (!cdResp.success) {
                    errorMsg = "切换目录失败：${cdResp.error ?: "未知错误"}"
                    return
                }
                newDir = cdResp.workingDir
            }

            // 2. Agent / 权限模式变化：合并为一次 API 调用
            val currentAgentUnderscore = currentAgentDisplay.let {
                // 从当前 snapshot 再取一次精确的 agentType
                val s = ApiClient.getCcState(userId, groupId)
                s.agentType.replace('-', '_')
            }
            val agentChanged = selectedAgentType.isNotBlank() && selectedAgentType != currentAgentUnderscore
            val permChanged = selectedPermMode != currentPermissionMode
            if (agentChanged || permChanged) {
                val resp = ApiClient.updateCcSettings(
                    userId, groupId,
                    activeAgent = if (agentChanged) selectedAgentType else null,
                    permissionMode = if (permChanged) selectedPermMode.ifBlank { "INTERACTIVE" } else null,
                )
                if (!resp.success) {
                    errorMsg = "更新设置失败：${resp.error ?: "未知错误"}"
                    return
                }
                newAgentDisplay = resp.agentDisplayName
                newPermMode = resp.permissionMode
                // 如果前面没 cd 过，workingDir 也从这里取
                if (newDir == currentWorkingDir) newDir = resp.workingDir
            }

            onApplied(newDir, newAgentDisplay, newPermMode)
        } finally {
            saving = false
        }
    }

    // 信任后重试 cd + 继续 apply
    suspend fun applyAfterTrust() {
        saving = true
        errorMsg = null
        try {
            val added = ApiClient.addTrustedDir(userId, trustConfirmPath)
            if (!added) {
                errorMsg = "添加信任记录失败，请重试。"
                return
            }
            showTrustConfirm = false
            applyChanges()
        } finally {
            saving = false
        }
    }

    // 背景遮罩
    Div({
        style {
            position(Position.Fixed)
            property("inset", "0")
            backgroundColor(Color("rgba(0,0,0,0.4)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
        }
        onClick { if (!saving) onDismiss() }
    }) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                padding(24.px)
                width(440.px)
                property("max-width", "90vw")
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({ style { marginTop(0.px); color(Color(SilkColors.textPrimary)) } }) { Text("会话设置") }

            // Agent 选择
            Span({
                style {
                    fontSize(12.px); color(Color(SilkColors.textSecondary))
                    property("display", "block"); marginBottom(4.px)
                }
            }) { Text("Agent") }
            Select({
                style {
                    width(100.percent); height(40.px); borderRadius(6.px)
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    padding(8.px); fontSize(14.px); marginBottom(12.px)
                    property("box-sizing", "border-box"); backgroundColor(Color.white)
                }
                onChange { selectedAgentType = it.value ?: "" }
            }) {
                if (availableAgents.isEmpty()) {
                    Option("") { Text("加载中…") }
                } else {
                    availableAgents.forEach { agent ->
                        Option(
                            value = agent.agentType,
                            attrs = {
                                if (!agent.connected) attr("disabled", "")
                                if (agent.agentType == selectedAgentType) attr("selected", "")
                            },
                        ) {
                            val suffix = if (agent.connected) "" else "（未连接）"
                            Text("${agent.displayName}${suffix}")
                        }
                    }
                }
            }

            // 权限模式
            Span({
                style {
                    fontSize(12.px); color(Color(SilkColors.textSecondary))
                    property("display", "block"); marginBottom(4.px)
                }
            }) { Text("权限模式") }
            Select({
                style {
                    width(100.percent); height(40.px); borderRadius(6.px)
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    padding(8.px); fontSize(14.px); marginBottom(12.px)
                    property("box-sizing", "border-box"); backgroundColor(Color.white)
                }
                onChange { selectedPermMode = it.value ?: "" }
            }) {
                Option("", attrs = { if (selectedPermMode.isBlank() || selectedPermMode == "INTERACTIVE") attr("selected", "") }) { Text("Interactive") }
                Option("ACCEPT_EDITS", attrs = { if (selectedPermMode == "ACCEPT_EDITS") attr("selected", "") }) { Text("Accept Edits") }
                Option("BYPASS", attrs = { if (selectedPermMode == "BYPASS") attr("selected", "") }) { Text("Bypass") }
            }

            // 工作目录
            Span({
                style {
                    fontSize(12.px); color(Color(SilkColors.textSecondary))
                    property("display", "block"); marginBottom(4.px)
                }
            }) { Text("工作目录") }
            Div({
                style {
                    display(DisplayStyle.Flex); property("gap", "8px"); marginBottom(16.px)
                }
            }) {
                Input(InputType.Text) {
                    value(editDir)
                    onInput { editDir = it.value }
                    attr("placeholder", "工作目录路径")
                    style {
                        property("flex", "1"); height(40.px); borderRadius(6.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(8.px); fontSize(13.px)
                        fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                        property("box-sizing", "border-box")
                    }
                }
                Button({
                    style {
                        backgroundColor(Color("transparent"))
                        color(Color(SilkColors.primary))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(6.px); padding(6.px, 10.px)
                        property("cursor", "pointer"); fontSize(13.px)
                        property("white-space", "nowrap")
                    }
                    onClick { showFolderPicker = true }
                }) { Text("\uD83D\uDCC2 选择…") }
            }

            // 错误提示
            errorMsg?.let { msg ->
                Div({
                    style { fontSize(12.px); color(Color(SilkColors.error)); marginBottom(12.px) }
                }) { Text(msg) }
            }

            // 按钮行
            Div({
                style {
                    display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd)
                    property("gap", "8px")
                }
            }) {
                Button({
                    if (saving) attr("disabled", "")
                    style {
                        backgroundColor(Color(SilkColors.surface))
                        color(Color(SilkColors.textSecondary))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(6.px); padding(8.px, 16.px)
                        property("cursor", "pointer")
                    }
                    onClick { onDismiss() }
                }) { Text("取消") }
                Button({
                    if (saving) attr("disabled", "")
                    style {
                        backgroundColor(Color(SilkColors.primary))
                        color(Color.white); border(0.px)
                        borderRadius(6.px); padding(8.px, 16.px)
                        property("cursor", if (saving) "wait" else "pointer")
                    }
                    onClick { scope.launch { applyChanges() } }
                }) { Text(if (saving) "保存中…" else "保存") }
            }
        }
    }

    // Folder Picker 子弹窗
    if (showFolderPicker) {
        FolderPickerDialog(
            userId = userId,
            initialPath = editDir.ifBlank { null },
            onDismiss = { showFolderPicker = false },
            onConfirm = { path ->
                editDir = path
                showFolderPicker = false
            },
        )
    }

    // 信任确认子弹窗
    if (showTrustConfirm) {
        TrustConfirmDialog(
            path = trustConfirmPath,
            bridgeId = trustConfirmBridgeId,
            onDismiss = {
                showTrustConfirm = false
                saving = false
            },
            onTrust = { scope.launch { applyAfterTrust() } },
        )
    }
}

/**
 * 目录选择对话框：
 * - 面包屑：各段可点击直接跳到该层
 * - 列表：仅显示子目录；单击进入
 * - .. 返回上一级
 * - 底部：显示当前路径（只读展示），支持手动输入跳转
 * - 确认按钮：以当前展示的路径作为结果
 */
@Suppress("CyclomaticComplexMethod")
@Composable
private fun FolderPickerDialog(
    userId: String,
    initialPath: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<DirListingResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var manualInput by remember { mutableStateOf("") }
    // 当前正在进行的加载 Job；新请求发起前取消旧的，避免老响应覆盖新 state
    var loadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // 发起一次目录加载：自动取消上一次未完成的 load，保证 state 始终由最新请求写入
    fun requestLoad(path: String?) {
        loadJob?.cancel()
        loadJob = scope.launch {
            loading = true
            errorMsg = null
            val resp = ApiClient.listCcDir(userId, path)
            // 到这里说明本协程未被 cancel（否则 listCcDir 内部会重新抛 CancellationException）
            loading = false
            if (resp.success) {
                listing = resp
                manualInput = resp.path
            } else {
                errorMsg = resp.error ?: "未知错误"
            }
        }
    }

    LaunchedEffect(Unit) { requestLoad(initialPath) }
    // 弹窗消失时清理未完成的请求
    DisposableEffect(Unit) {
        onDispose { loadJob?.cancel() }
    }

    ModalOverlay(onDismiss = onDismiss, zIndex = 2000) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                width(620.px)
                property("max-width", "90vw")
                property("max-height", "80vh")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.18)")
                property("overflow", "hidden")
            }
        }) {
            val current = listing
            FolderPickerHeader(onDismiss = onDismiss)
            FolderPickerBreadcrumbs(current = current, onNavigate = { requestLoad(it) })
            FolderPickerListContent(
                loading = loading,
                errorMsg = errorMsg,
                current = current,
                onNavigate = { requestLoad(it) },
            )
            FolderPickerFooter(
                manualInput = manualInput,
                current = current,
                onManualInputChange = { manualInput = it },
                onDismiss = onDismiss,
                onNavigate = { requestLoad(it) },
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
private fun FolderPickerHeader(onDismiss: () -> Unit) {
    Div({
        style {
            padding(16.px, 20.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
        }
    }) {
        Span({
            style {
                fontSize(16.px)
                fontWeight("600")
                color(Color(SilkColors.textPrimary))
            }
        }) { Text("选择工作目录") }
        Span({
            style {
                fontSize(18.px)
                property("cursor", "pointer")
                color(Color(SilkColors.textSecondary))
            }
            onClick { onDismiss() }
        }) { Text("×") }
    }
}

@Composable
private fun FolderPickerBreadcrumbs(
    current: DirListingResponse?,
    onNavigate: (String?) -> Unit,
) {
    if (current == null) {
        return
    }

    Div({
        style {
            padding(10.px, 20.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            property("flex-wrap", "wrap")
            alignItems(AlignItems.Center)
            property("gap", "4px")
            fontSize(13.px)
            fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
        }
    }) {
        current.segments.forEachIndexed { idx, seg ->
            val isLast = idx == current.segments.size - 1
            Span({
                style {
                    color(if (isLast) Color(SilkColors.textPrimary) else Color(SilkColors.primary))
                    if (!isLast) property("cursor", "pointer")
                    if (isLast) fontWeight("600")
                    padding(2.px, 6.px)
                    borderRadius(4.px)
                }
                if (!isLast) {
                    onClick {
                        onNavigate(buildBreadcrumbPath(current.segments, idx, current.separator))
                    }
                }
            }) { Text(seg) }
            if (!isLast) {
                Span({
                    style {
                        color(Color(SilkColors.textLight))
                        padding(0.px, 2.px)
                    }
                }) { Text("›") }
            }
        }
    }
}

@Composable
private fun FolderPickerListContent(
    loading: Boolean,
    errorMsg: String?,
    current: DirListingResponse?,
    onNavigate: (String?) -> Unit,
) {
    Div({
        style {
            property("flex", "1")
            property("overflow-y", "auto")
            padding(8.px, 0.px)
            property("min-height", "240px")
            property("max-height", "380px")
        }
    }) {
        when {
            loading -> FolderPickerStatusMessage("加载中...", SilkColors.textSecondary)
            errorMsg != null -> FolderPickerStatusMessage("⚠ $errorMsg", SilkColors.error)
            current != null -> FolderPickerDirectoryEntries(current = current, onNavigate = onNavigate)
        }
    }
}

@Composable
private fun FolderPickerDirectoryEntries(
    current: DirListingResponse,
    onNavigate: (String?) -> Unit,
) {
    current.parent?.let { parent ->
        FolderRow(name = "..", subtle = true) {
            onNavigate(parent)
        }
    }

    current.entries.forEach { entry ->
        FolderRow(name = entry.name) {
            onNavigate(joinPath(current.path, entry.name, current.separator))
        }
    }

    if (current.entries.isEmpty() && current.parent == null) {
        FolderPickerStatusMessage("此目录下无子文件夹", SilkColors.textSecondary)
    }

    if (current.truncated) {
        Div({
            style {
                padding(8.px, 20.px)
                fontSize(12.px)
                color(Color(SilkColors.textLight))
                fontStyle("italic")
            }
        }) { Text("目录项过多，仅显示前 500 个") }
    }
}

@Composable
private fun FolderPickerStatusMessage(
    message: String,
    textColor: String,
) {
    Div({
        style {
            padding(if (message == "加载中...") 40.px else 20.px)
            property("text-align", if (message == "加载中...") "center" else "left")
            color(Color(textColor))
            fontSize(13.px)
        }
    }) { Text(message) }
}

@Composable
private fun FolderPickerFooter(
    manualInput: String,
    current: DirListingResponse?,
    onManualInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onNavigate: (String?) -> Unit,
    onConfirm: (String) -> Unit,
) {
    val confirmEnabled = current?.success == true

    Div({
        style {
            padding(12.px, 20.px)
            property("border-top", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "8px")
        }
    }) {
        Input(InputType.Text) {
            value(manualInput)
            onInput { onManualInputChange(it.value) }
            attr("placeholder", "路径，Enter 跳转")
            onKeyDown { evt ->
                when {
                    evt.key == "Enter" && manualInput.isNotBlank() -> {
                        evt.preventDefault()
                        onNavigate(manualInput.trim())
                    }
                    evt.key == "Escape" -> onDismiss()
                }
            }
            style {
                property("flex", "1")
                height(34.px)
                borderRadius(6.px)
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                padding(4.px, 10.px)
                fontSize(13.px)
                fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                property("box-sizing", "border-box")
                property("outline", "none")
            }
        }
        Button({
            style {
                backgroundColor(Color(SilkColors.surface))
                color(Color(SilkColors.textSecondary))
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                borderRadius(6.px)
                padding(7.px, 14.px)
                property("cursor", "pointer")
                fontSize(13.px)
            }
            onClick { onDismiss() }
        }) { Text("取消") }
        Button({
            if (!confirmEnabled) attr("disabled", "")
            style {
                backgroundColor(if (confirmEnabled) Color(SilkColors.primary) else Color(SilkColors.primaryLight))
                color(Color.white)
                border(0.px)
                borderRadius(6.px)
                padding(7.px, 14.px)
                property("cursor", if (confirmEnabled) "pointer" else "not-allowed")
                fontSize(13.px)
                property("font-weight", "600")
            }
            onClick {
                val path = current?.path
                if (!path.isNullOrBlank()) {
                    onConfirm(path)
                }
            }
        }) { Text("选择此目录") }
    }
}

@Composable
private fun FolderRow(name: String, subtle: Boolean = false, onClick: () -> Unit) {
    Div({
        style {
            padding(8.px, 20.px)
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "8px")
            property("cursor", "pointer")
            fontSize(13.px)
            property("user-select", "none")
        }
        onClick { onClick() }
    }) {
        Span({ style { fontSize(14.px) } }) {
            Text(if (subtle) "\uD83D\uDD19" else "\uD83D\uDCC1")
        }
        Span({
            style {
                color(
                    if (subtle) Color(SilkColors.textSecondary)
                    else Color(SilkColors.textPrimary)
                )
                fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
            }
        }) { Text(name) }
    }
}

/**
 * 全屏遮罩层 + 居中对话框骨架。点击遮罩 [onDismiss]，点击 [content] 内不冒泡。
 * z-index 通过 [zIndex] 控制（FolderPicker 比 Create dialog 高，便于嵌套打开）。
 */
@Composable
private fun ModalOverlay(
    onDismiss: () -> Unit,
    zIndex: Int = 1000,
    content: @Composable () -> Unit,
) {
    Div({
        style {
            position(Position.Fixed)
            top(0.px); left(0.px); right(0.px); bottom(0.px)
            backgroundColor(Color("rgba(0,0,0,0.4)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", zIndex.toString())
        }
        onClick { onDismiss() }
    }) {
        // 内容容器：阻止冒泡，避免点到对话框时触发 onDismiss
        Div({
            onClick { it.stopPropagation() }
        }) {
            content()
        }
    }
}

/**
 * 按面包屑段下标拼接成路径，使用后端提供的 [separator]。
 * - Unix: segments[0] == "/"，separator == "/" → "/" + 中段以 "/" 拼
 * - Windows: segments[0] == "C:\\"（已带分隔符），separator == "\\" → head + 中段以 "\\" 拼
 */
private fun buildBreadcrumbPath(segments: List<String>, upToIndex: Int, separator: String): String {
    if (segments.isEmpty() || upToIndex < 0) return separator
    val head = segments[0]
    if (upToIndex == 0) return head
    val tail = segments.subList(1, upToIndex + 1)
    // head 已包含或就是分隔符（Unix 的 "/", Windows 的 "C:\"）；
    // 若 head 自身以 separator 结尾就直接拼，否则补一个 separator
    val joined = tail.joinToString(separator)
    return if (head.endsWith(separator)) head + joined else head + separator + joined
}

/**
 * 拼接子目录路径，使用后端提供的 [separator]。
 */
private fun joinPath(parent: String, child: String, separator: String): String {
    if (parent.isEmpty()) return child
    return if (parent.endsWith(separator)) parent + child else parent + separator + child
}

/**
 * 信任目录确认弹窗（Silk 风格，替代浏览器原生 confirm）。
 * 使用 ModalOverlay 遮罩层 + 自定义样式按钮，与整体 UI 一致。
 */
@Composable
private fun TrustConfirmDialog(
    path: String,
    bridgeId: String?,
    onDismiss: () -> Unit,
    onTrust: () -> Unit,
) {
    val bridgeLabel = bridgeId ?: "未知机器"
    ModalOverlay(onDismiss = onDismiss, zIndex = 3000) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                padding(24.px)
                width(420.px)
                property("max-width", "90vw")
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
            }
        }) {
            H3({ style { marginTop(0.px); color(Color(SilkColors.textPrimary)) } }) { Text("⚠️  信任目录确认") }
            Div({
                style {
                    marginTop(16.px)
                    marginBottom(24.px)
                    fontSize(14.px)
                    color(Color(SilkColors.textPrimary))
                    property("line-height", "1.6")
                }
            }) {
                Text("您选择了工作目录：")
                Div({
                    style {
                        fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                        marginTop(4.px)
                        marginBottom(12.px)
                        color(Color(SilkColors.textSecondary))
                        fontSize(13.px)
                    }
                }) { Text(path) }
                Text("Bridge 机器：$bridgeLabel")
                Div({ style { marginTop(12.px) } }) {
                    Text("是否信任并授权该目录及其子目录的读写执行权限？")
                }
                Div({
                    style {
                        marginTop(4.px)
                        fontSize(12.px)
                        color(Color(SilkColors.textLight))
                    }
                }) {
                    Text("信任后，下次在该机器上选择此目录或其子目录时将不再询问。")
                }
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.FlexEnd)
                    property("gap", "8px")
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
                    onClick { onDismiss() }
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
                    onClick { onTrust() }
                }) { Text("信任并授权") }
            }
        }
    }
}
