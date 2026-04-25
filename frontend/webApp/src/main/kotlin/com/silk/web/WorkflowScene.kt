package com.silk.web

import androidx.compose.runtime.*
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
import com.silk.shared.models.DirEntry
import com.silk.shared.models.DirListingResponse
import com.silk.shared.models.Message
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun WorkflowScene(appState: WebAppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()
    var workflows by remember { mutableStateOf<List<WorkflowItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newInitialDir by remember { mutableStateOf("") }
    var showCreatePicker by remember { mutableStateOf(false) }
    var selectedWorkflow by remember { mutableStateOf<WorkflowItem?>(null) }
    // 创建对话框中"加载默认目录"的失败原因（一般是 Bridge 未连接）。
    // Bridge 离线时整个创建流程都会失败（后端拒绝），所以这里也用作"是否禁用创建按钮"的依据。
    var dirLoadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(user.id) {
        isLoading = true
        workflows = ApiClient.getWorkflows(user.id)
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
        // Left: workflow list
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

            // List
            Div({
                style {
                    property("flex", "1")
                    property("overflow-y", "auto")
                }
            }) {
                if (isLoading) {
                    Div({ style { padding(20.px); property("text-align", "center") } }) {
                        Text("加载中...")
                    }
                } else if (workflows.isEmpty()) {
                    Div({
                        style {
                            padding(40.px, 20.px)
                            property("text-align", "center")
                            color(Color(SilkColors.textSecondary))
                        }
                    }) {
                        Text("暂无工作流")
                    }
                } else {
                    workflows.forEach { wf ->
                        val isSelected = selectedWorkflow?.id == wf.id
                        Div({
                            style {
                                padding(12.px, 16.px)
                                property("border-bottom", "1px solid ${SilkColors.border}")
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.SpaceBetween)
                                alignItems(AlignItems.Center)
                                property("cursor", "pointer")
                                backgroundColor(
                                    if (isSelected) Color("rgba(201, 168, 108, 0.15)")
                                    else Color("transparent")
                                )
                                if (isSelected) {
                                    property("border-left", "3px solid ${SilkColors.primary}")
                                }
                            }
                            onClick {
                                selectedWorkflow = wf
                            }
                        }) {
                            Span({
                                style {
                                    color(Color(SilkColors.textPrimary))
                                    fontSize(14.px)
                                    property("flex", "1")
                                    property("overflow", "hidden")
                                    property("text-overflow", "ellipsis")
                                    property("white-space", "nowrap")
                                    if (isSelected) fontWeight("bold")
                                }
                            }) { Text(wf.name) }
                            Button({
                                style {
                                    backgroundColor(Color("transparent"))
                                    color(Color(SilkColors.error))
                                    border(0.px)
                                    property("cursor", "pointer")
                                    fontSize(12.px)
                                }
                                onClick { evt ->
                                    evt.stopPropagation()
                                    scope.launch {
                                        ApiClient.deleteWorkflow(wf.id, user.id)
                                        if (selectedWorkflow?.id == wf.id) {
                                            selectedWorkflow = null
                                        }
                                        workflows = ApiClient.getWorkflows(user.id)
                                    }
                                }
                            }) { Text("删除") }
                        }
                    }
                }
            }
        }

        // Right: chat area or placeholder
        Div({
            style {
                property("flex", "1")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                height(100.percent)
                property("overflow", "hidden")
            }
        }) {
            val wf = selectedWorkflow
            if (wf == null || wf.groupId.isBlank()) {
                // Placeholder
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
            } else {
                // Chat panel for selected workflow
                WorkflowChatPanel(
                    userId = user.id,
                    userName = user.fullName,
                    groupId = wf.groupId,
                    workflowName = wf.name,
                )
            }
        }
    }

    // Create dialog
    if (showCreateDialog) {
        // 打开时若 newInitialDir 为空，拉一次 bridge 默认目录作为初始值；
        // 失败时记下原因，UI 切换提示，并禁用创建按钮（避免拿到 backend 服务器进程的 cwd 当兜底）
        //
        // key 用 Unit 表达"每次对话框打开只执行一次"：外层 if (showCreateDialog) 决定
        // 这个 LaunchedEffect 是否进入组合，一旦进入就跑一次；用 showCreateDialog 做 key
        // 在"key 始终为 true"的情况下会造成阅读歧义，所以改用 Unit 更贴合语义
        LaunchedEffect(Unit) {
            if (newInitialDir.isBlank()) {
                dirLoadError = null
                val resp = ApiClient.listCcDir(user.id, null)
                if (resp.success && resp.path.isNotBlank()) {
                    newInitialDir = resp.path
                } else {
                    dirLoadError = resp.error ?: "无法获取默认目录"
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
                dirLoadError = null
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
                        marginBottom(if (dirLoadError != null) 6.px else 16.px)
                    }
                }) {
                    Input(InputType.Text) {
                        value(newInitialDir)
                        onInput { newInitialDir = it.value }
                        // Bridge 离线时整个创建流程都会失败（后端会拒绝），输入路径也无意义，
                        // 因此明确引导用户先去解决 Bridge 问题，不再鼓励手动输入
                        attr(
                            "placeholder",
                            if (dirLoadError != null) "Bridge 未连接，请先启动 Bridge"
                            else "加载默认目录中…",
                        )
                        // Bridge 离线时禁用输入框，避免用户白费功夫
                        if (dirLoadError != null) attr("disabled", "")
                        style {
                            property("flex", "1")
                            height(40.px)
                            borderRadius(6.px)
                            border(1.px, LineStyle.Solid, Color(SilkColors.border))
                            padding(8.px)
                            fontSize(13.px)
                            fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                            property("box-sizing", "border-box")
                            if (dirLoadError != null) {
                                backgroundColor(Color(SilkColors.surface))
                                color(Color(SilkColors.textLight))
                            }
                        }
                    }
                    Button({
                        // Bridge 离线时浏览器肯定也用不了，提前禁用更友好
                        val pickerDisabled = dirLoadError != null
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
                // 加载失败时的小提示行
                if (dirLoadError != null) {
                    Div({
                        style {
                            fontSize(12.px)
                            color(Color(SilkColors.error))
                            marginBottom(16.px)
                        }
                    }) { Text("⚠ ${dirLoadError}。请先启动 Bridge Agent 再创建工作流。") }
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
                            dirLoadError = null
                        }
                    }) { Text("取消") }
                    Button({
                        // Bridge 离线时直接禁用创建（后端也会拒绝，提前挡在前端更及时）
                        val canCreate = dirLoadError == null && newName.isNotBlank() && newInitialDir.isNotBlank()
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
                                dirLoadError != null -> "Bridge 未连接，无法创建"
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
                                        val bridgeLabel = trustCheck.bridgeId ?: "未知机器"
                                        val confirmed = kotlinx.browser.window.confirm(
                                            "您选择了工作目录：$initDir\n" +
                                            "Bridge 机器：$bridgeLabel\n\n" +
                                            "是否信任并授权该目录及其子目录的读写执行权限？\n" +
                                            "信任后，下次在该机器上选择此目录或其子目录时将不再询问。"
                                        )
                                        if (confirmed) {
                                            val added = ApiClient.addTrustedDir(user.id, initDir)
                                            if (!added) {
                                                kotlinx.browser.window.alert("添加信任记录失败，请重试。")
                                                return@launch
                                            }
                                        } else {
                                            return@launch
                                        }
                                    }
                                    is ApiClient.TrustCheckResult.Error -> {
                                        kotlinx.browser.window.alert("检查信任状态失败：${trustCheck.message}")
                                        return@launch
                                    }
                                    else -> {} // 已信任，继续
                                }

                                // 2. 创建 workflow
                                val result = ApiClient.createWorkflow(
                                    newName.trim(), "", user.id, initDir,
                                )
                                workflows = ApiClient.getWorkflows(user.id)
                                when (result) {
                                    is ApiClient.CreateWorkflowResult.Ok -> {
                                        selectedWorkflow = result.workflow
                                        showCreateDialog = false
                                        newName = ""
                                        newInitialDir = ""
                                        dirLoadError = null
                                    }
                                    is ApiClient.CreateWorkflowResult.Err -> {
                                        // 保留用户输入，把后端错误以浏览器 alert 呈现
                                        kotlinx.browser.window.alert("创建工作流失败：${result.message}")
                                        // 创建期间 bridge 可能刚断了；重探一次状态，让按钮/输入框 UI 正确反映
                                        val probe = ApiClient.listCcDir(user.id, null)
                                        dirLoadError = if (probe.success) null else (probe.error ?: "无法获取默认目录")
                                    }
                                }
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
}

@Composable
private fun WorkflowChatPanel(
    userId: String,
    userName: String,
    groupId: String,
    workflowName: String,
) {
    val scope = rememberCoroutineScope()
    val wsUrl = remember { backendWsOrigin() }
    val chatClient = remember { ChatClient(wsUrl) }
    val messages by chatClient.messages.collectAsState()
    val transientMessage by chatClient.transientMessage.collectAsState()
    val statusMessages by chatClient.statusMessages.collectAsState()
    val connectionState by chatClient.connectionState.collectAsState()
    val isGenerating by chatClient.isGenerating.collectAsState()
    var messageText by remember(groupId) { mutableStateOf("") }
    var workingDir by remember(groupId) { mutableStateOf("") }
    var showFolderPicker by remember(groupId) { mutableStateOf(false) }

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
        }
    }

    // Connect WebSocket when groupId changes
    LaunchedEffect(groupId) {
        chatClient.clearMessages()
        kotlinx.coroutines.delay(500)
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

    // Auto-scroll
    LaunchedEffect(messages.size, transientMessage, statusMessages.size) {
        js("""
            setTimeout(function() {
                var c = document.getElementById('wf-messages');
                if (c) c.scrollTop = c.scrollHeight;
            }, 100);
        """)
    }

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
                        color(Color(SilkColors.primary))
                        property("cursor", "pointer")
                        property("flex-shrink", "0")
                        property("white-space", "nowrap")
                        property("user-select", "none")
                        property("text-decoration", "underline")
                        property("text-decoration-style", "dotted")
                    }
                    attr("title", "选择工作目录")
                    onClick { showFolderPicker = true }
                }) {
                    Text(if (workingDir.isBlank()) "\uD83D\uDCC2 选择目录" else "更改")
                }
            }
        }
        // Connection status indicator - only show when not connected
        if (connectionState != ConnectionState.CONNECTED) {
            Span({
                style {
                    fontSize(12.px)
                    color(
                        when (connectionState) {
                            ConnectionState.CONNECTING -> Color("#FF9800")
                            else -> Color("#F44336")
                        }
                    )
                }
            }) {
                Text(
                    when (connectionState) {
                        ConnectionState.CONNECTING -> "● 连接中..."
                        else -> "● 会话连接失败"
                    }
                )
            }
        }
    }

    // Messages area
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
        // Persistent messages
        messages.forEach { message ->
            MessageItem(
                message = message,
                isTransient = false,
                currentUserId = userId,
                groupId = groupId,
                onCopy = { content -> copyTextToClipboard(content) }
            )
        }

        // Status messages
        if (statusMessages.isNotEmpty()) {
            Div({
                style {
                    backgroundColor(Color("#F5F5F5"))
                    borderRadius(8.px)
                    padding(10.px, 14.px)
                    marginBottom(8.px)
                    property("border-left", "3px solid #9E9E9E")
                }
            }) {
                statusMessages.forEach { status ->
                    Div({
                        style {
                            color(Color("#757575"))
                            fontSize(13.px)
                            fontStyle("italic")
                            marginBottom(4.px)
                        }
                    }) {
                        Text(status.content)
                    }
                }
            }
        }

        // Transient (streaming) message
        transientMessage?.let { message ->
            if (
                message.content.isNotBlank() &&
                message.currentStep == null &&
                message.totalSteps == null &&
                !isLikelyAgentStatusContent(message.content)
            ) {
                MessageItem(
                    message = message.copy(category = com.silk.shared.models.MessageCategory.NORMAL),
                    isTransient = true,
                    currentUserId = userId,
                    groupId = groupId,
                    onCopy = { content -> copyTextToClipboard(content) }
                )
            } else {
                TransientMessageItem(message)
            }
        }
    }

    // Input area
    Div({
        style {
            property("flex-shrink", "0")
            padding(12.px, 16.px)
            property("border-top", "1px solid ${SilkColors.border}")
            backgroundColor(Color(SilkColors.surfaceElevated))
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "10px")
        }
    }) {
        Input(InputType.Text) {
            value(messageText)
            onInput { messageText = it.value }
            attr("placeholder", "向 Agent 发送消息...")
            onKeyDown { event ->
                if (event.key == "Enter" && !event.shiftKey && messageText.isNotBlank()) {
                    event.preventDefault()
                    val text = messageText.trim()
                    messageText = ""
                    scope.launch {
                        chatClient.sendMessage(userId, userName, text)
                    }
                }
            }
            style {
                property("flex", "1")
                height(40.px)
                borderRadius(8.px)
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                padding(8.px, 12.px)
                fontSize(14.px)
                property("box-sizing", "border-box")
                property("outline", "none")
            }
        }

        if (isGenerating) {
            Button({
                style {
                    backgroundColor(Color("#FF4D4F"))
                    color(Color.white)
                    border(0.px)
                    borderRadius(8.px)
                    padding(8.px, 16.px)
                    property("cursor", "pointer")
                    fontSize(14.px)
                    property("font-weight", "600")
                    property("transition", "all 0.2s ease")
                }
                onClick {
                    scope.launch { chatClient.stopGeneration(userId, userName) }
                }
            }) { Text("停止") }
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
                            chatClient.sendMessage(userId, userName, text)
                        }
                    }
                }
            }) { Text("发送") }
        }
    }

    // Folder Picker 弹窗
    if (showFolderPicker) {
        FolderPickerDialog(
            userId = userId,
            initialPath = workingDir.ifBlank { null },
            onDismiss = { showFolderPicker = false },
            onConfirm = { selectedPath ->
                showFolderPicker = false
                scope.launch {
                    // 1. 信任目录检查
                    val trustCheck = ApiClient.checkTrustedDir(userId, selectedPath)
                    when (trustCheck) {
                        is ApiClient.TrustCheckResult.BridgeDisconnected -> {
                            kotlinx.browser.window.alert("Bridge 未连接，无法切换目录。")
                            return@launch
                        }
                        is ApiClient.TrustCheckResult.NotTrusted -> {
                            val bridgeLabel = trustCheck.bridgeId ?: "未知机器"
                            val confirmed = kotlinx.browser.window.confirm(
                                "您选择了工作目录：$selectedPath\n" +
                                "Bridge 机器：$bridgeLabel\n\n" +
                                "是否信任并授权该目录及其子目录的读写执行权限？\n" +
                                "信任后，下次在该机器上选择此目录或其子目录时将不再询问。"
                            )
                            if (confirmed) {
                                val added = ApiClient.addTrustedDir(userId, selectedPath)
                                if (!added) {
                                    kotlinx.browser.window.alert("添加信任记录失败，请重试。")
                                    return@launch
                                }
                            } else {
                                return@launch
                            }
                        }
                        is ApiClient.TrustCheckResult.Error -> {
                            kotlinx.browser.window.alert("检查信任状态失败：${trustCheck.message}")
                            return@launch
                        }
                        else -> {} // 已信任，继续
                    }

                    // 2. 执行 cd
                    val resp = ApiClient.cdCcDir(userId, groupId, selectedPath)
                    if (resp.success) {
                        workingDir = resp.workingDir
                    } else {
                        val err = resp.error
                        if (err != null) {
                            kotlinx.browser.window.alert("切换目录失败：$err")
                        }
                    }
                }
            },
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
            // 标题
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

            // 面包屑
            val current = listing
            if (current != null) {
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
                                color(
                                    if (isLast) Color(SilkColors.textPrimary)
                                    else Color(SilkColors.primary)
                                )
                                if (!isLast) property("cursor", "pointer")
                                if (isLast) fontWeight("600")
                                padding(2.px, 6.px)
                                borderRadius(4.px)
                            }
                            if (!isLast) {
                                onClick {
                                    // 拼接 segments[0..idx] -> 路径（使用后端提供的 separator）
                                    val target = buildBreadcrumbPath(current.segments, idx, current.separator)
                                    requestLoad(target)
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

            // 列表区
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
                    loading -> Div({
                        style {
                            padding(40.px)
                            property("text-align", "center")
                            color(Color(SilkColors.textSecondary))
                        }
                    }) { Text("加载中...") }

                    errorMsg != null -> Div({
                        style {
                            padding(20.px)
                            color(Color(SilkColors.error))
                            fontSize(13.px)
                        }
                    }) { Text("⚠ $errorMsg") }

                    current != null -> {
                        // ..
                        if (current.parent != null) {
                            FolderRow(name = "..", subtle = true) {
                                requestLoad(current.parent)
                            }
                        }
                        current.entries.forEach { entry ->
                            FolderRow(name = entry.name) {
                                val nextPath = joinPath(current.path, entry.name, current.separator)
                                requestLoad(nextPath)
                            }
                        }
                        if (current.entries.isEmpty() && current.parent == null) {
                            Div({
                                style {
                                    padding(20.px)
                                    color(Color(SilkColors.textSecondary))
                                    fontSize(13.px)
                                }
                            }) { Text("此目录下无子文件夹") }
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
                }
            }

            // 底部：路径输入 + 操作
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
                    onInput { manualInput = it.value }
                    attr("placeholder", "路径，Enter 跳转")
                    onKeyDown { evt ->
                        if (evt.key == "Enter" && manualInput.isNotBlank()) {
                            evt.preventDefault()
                            requestLoad(manualInput.trim())
                        } else if (evt.key == "Escape") {
                            onDismiss()
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
                    val enabled = current?.success == true
                    if (!enabled) attr("disabled", "")
                    style {
                        backgroundColor(
                            if (enabled) Color(SilkColors.primary)
                            else Color(SilkColors.primaryLight)
                        )
                        color(Color.white)
                        border(0.px)
                        borderRadius(6.px)
                        padding(7.px, 14.px)
                        property("cursor", if (enabled) "pointer" else "not-allowed")
                        fontSize(13.px)
                        property("font-weight", "600")
                    }
                    onClick {
                        val path = current?.path
                        if (!path.isNullOrBlank()) onConfirm(path)
                    }
                }) { Text("选择此目录") }
            }
        }
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
