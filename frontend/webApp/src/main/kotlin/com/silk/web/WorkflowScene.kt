package com.silk.web

import androidx.compose.runtime.*
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
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
    var selectedWorkflow by remember { mutableStateOf<WorkflowItem?>(null) }

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
                    workflowName = wf.name
                )
            }
        }
    }

    // Create dialog
    if (showCreateDialog) {
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
            onClick { showCreateDialog = false }
        }) {
            Div({
                style {
                    backgroundColor(Color.white)
                    borderRadius(12.px)
                    padding(24.px)
                    width(360.px)
                    property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
                }
                onClick { it.stopPropagation() }
            }) {
                H3({ style { marginTop(0.px); color(Color(SilkColors.textPrimary)) } }) { Text("创建工作流") }
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
                        marginBottom(16.px)
                        property("box-sizing", "border-box")
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
                        onClick { showCreateDialog = false; newName = "" }
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
                        onClick {
                            if (newName.isNotBlank()) {
                                scope.launch {
                                    val created = ApiClient.createWorkflow(newName.trim(), "", user.id)
                                    workflows = ApiClient.getWorkflows(user.id)
                                    if (created != null) {
                                        selectedWorkflow = created
                                    }
                                    showCreateDialog = false
                                    newName = ""
                                }
                            }
                        }
                    }) { Text("创建") }
                }
            }
        }
    }
}

@Composable
private fun WorkflowChatPanel(
    userId: String,
    userName: String,
    groupId: String,
    workflowName: String
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
        Span({
            style {
                fontSize(16.px)
                fontWeight("600")
                color(Color(SilkColors.textPrimary))
                property("flex", "1")
            }
        }) { Text(workflowName) }
        // Connection status indicator
        Span({
            style {
                fontSize(12.px)
                color(
                    when (connectionState) {
                        ConnectionState.CONNECTED -> Color("#4CAF50")
                        ConnectionState.CONNECTING -> Color("#FF9800")
                        else -> Color("#9E9E9E")
                    }
                )
            }
        }) {
            Text(
                when (connectionState) {
                    ConnectionState.CONNECTED -> "● 已连接"
                    ConnectionState.CONNECTING -> "● 连接中..."
                    else -> "● 未连接"
                }
            )
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
}
