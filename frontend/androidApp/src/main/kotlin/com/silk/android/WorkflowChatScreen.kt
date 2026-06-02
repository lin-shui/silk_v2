package com.silk.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
import com.silk.shared.models.Message
import com.silk.shared.models.MessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private data class WorkflowChatHeaderState(
    val workflowName: String,
    val workingDir: String,
    val connectionState: ConnectionState,
)

private data class WorkflowChatBadgeState(
    val activeAgentDisplay: String,
    val permissionMode: String,
    val availableAgents: List<AgentInfo>,
    val showPermModeDropdown: Boolean,
    val showAgentDropdown: Boolean,
)

private data class WorkflowChatAgentState(
    val display: String,
    val availableAgents: List<AgentInfo>,
    val showDropdown: Boolean,
)

private data class WorkflowChatPermissionState(
    val mode: String,
    val showDropdown: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("CyclomaticComplexMethod")
fun WorkflowChatScreen(appState: AppState) {
    val workflow = appState.selectedWorkflow ?: return
    val user = appState.currentUser ?: return
    val groupId = workflow.groupId
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val baseUrl = BackendUrlHolder.getBaseUrl()
    val wsUrl = baseUrl.replaceFirst("http", "ws")

    key(groupId) {
        val chatClient = remember { ChatClient(wsUrl) }
        val messages by chatClient.messages.collectAsState()
        val transientMessage by chatClient.transientMessage.collectAsState()
        val statusMessages by chatClient.statusMessages.collectAsState()
        val connectionState by chatClient.connectionState.collectAsState()
        val isGenerating by chatClient.isGenerating.collectAsState()

        var messageText by remember(groupId) { mutableStateOf(TextFieldValue("")) }
        var workingDir by remember(groupId) { mutableStateOf(workflow.workingDir) }
        var activeAgentDisplay by remember(groupId) { mutableStateOf("") }
        var permissionMode by remember(groupId) { mutableStateOf("") }
        var showFolderPicker by remember(groupId) { mutableStateOf(false) }
        var showTrustConfirm by remember(groupId) { mutableStateOf(false) }
        var trustConfirmPath by remember(groupId) { mutableStateOf("") }
        var trustConfirmBridgeId by remember(groupId) { mutableStateOf<String?>(null) }
        var errorDialogMessage by remember(groupId) { mutableStateOf<String?>(null) }
        var availableAgents by remember(groupId) { mutableStateOf<List<AgentInfo>>(emptyList()) }
        var showPermModeDropdown by remember(groupId) { mutableStateOf(false) }
        var showAgentDropdown by remember(groupId) { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val aiExpandedStates = remember { mutableStateMapOf<String, Boolean>() }
        val thinkingExpandedStates = remember { mutableStateMapOf<String, Boolean>() }

        WorkflowChatEffects(
            groupId = groupId,
            user = user,
            chatClient = chatClient,
            connectionState = connectionState,
            messages = messages,
            transientMessage = transientMessage,
            statusMessages = statusMessages,
            listState = listState,
            onWorkingDirLoaded = { if (it.isNotBlank()) workingDir = it },
            onAgentStateLoaded = { displayName, permMode ->
                activeAgentDisplay = displayName
                permissionMode = permMode
            },
            onAgentsLoaded = { availableAgents = it },
        )

        WorkflowChatScreenContent(
            headerState = WorkflowChatHeaderState(
                workflowName = workflow.name,
                workingDir = workingDir,
                connectionState = connectionState,
            ),
            badgeState = WorkflowChatBadgeState(
                activeAgentDisplay = activeAgentDisplay,
                permissionMode = permissionMode,
                availableAgents = availableAgents,
                showPermModeDropdown = showPermModeDropdown,
                showAgentDropdown = showAgentDropdown,
            ),
            listState = listState,
            messages = messages,
            transientMessage = transientMessage,
            statusMessages = statusMessages,
            currentUserId = user.id,
            currentUserName = user.fullName,
            context = context,
            chatClient = chatClient,
            messageText = messageText,
            isGenerating = isGenerating,
            aiExpandedStates = aiExpandedStates,
            thinkingExpandedStates = thinkingExpandedStates,
            onBack = { appState.navigateBack() },
            onShowFolderPicker = { showFolderPicker = true },
            onPermissionDropdownChange = {
                showPermModeDropdown = it
                if (it) showAgentDropdown = false
            },
            onAgentDropdownChange = {
                showAgentDropdown = it
                if (it) showPermModeDropdown = false
            },
            onPermissionModeSelect = { selectedMode, isCurrent ->
                showPermModeDropdown = false
                if (!isCurrent) {
                    scope.launch {
                        val resp = ApiClient.updateCcSettings(
                            user.id,
                            groupId,
                            permissionMode = selectedMode.ifBlank { "INTERACTIVE" },
                        )
                        if (resp.success) {
                            permissionMode = resp.permissionMode
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                resp.error ?: "切换失败",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            onAgentSelect = { agent, isCurrent ->
                showAgentDropdown = false
                if (!isCurrent && agent.connected) {
                    scope.launch {
                        val resp = ApiClient.updateCcSettings(
                            user.id,
                            groupId,
                            activeAgent = agent.agentType,
                        )
                        if (resp.success) {
                            activeAgentDisplay = resp.agentDisplayName
                            permissionMode = resp.permissionMode
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                resp.error ?: "切换失败",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            onNewSession = {
                scope.launch {
                    chatClient.sendMessage(user.id, user.fullName, "/new")
                }
            },
            onMessageTextChange = { messageText = it },
            onSend = {
                val text = messageText.text.trim()
                if (text.isNotEmpty()) {
                    scope.launch {
                        chatClient.sendMessage(user.id, user.fullName, text)
                    }
                    messageText = TextFieldValue("")
                }
            },
            onStop = { chatClient.stopGeneration(user.id, user.fullName) },
        )

        WorkflowChatDialogs(
            showFolderPicker = showFolderPicker,
            userId = user.id,
            initialPath = workingDir.ifBlank { null },
            onDismissFolderPicker = { showFolderPicker = false },
            onConfirmFolderPicker = { selectedPath ->
                showFolderPicker = false
                if (selectedPath != workingDir) {
                    scope.launch {
                        when (val tc = ApiClient.checkTrustedDir(user.id, selectedPath)) {
                            is TrustCheckResult.BridgeDisconnected ->
                                errorDialogMessage = "Bridge 未连接，无法切换目录。"
                            is TrustCheckResult.NotTrusted -> {
                                trustConfirmPath = selectedPath
                                trustConfirmBridgeId = tc.bridgeId
                                showTrustConfirm = true
                            }
                            is TrustCheckResult.Error ->
                                errorDialogMessage = "检查信任状态失败：${tc.message}"
                            is TrustCheckResult.Trusted -> {
                                val cdResp = ApiClient.cdCcDir(user.id, groupId, selectedPath)
                                if (cdResp.success) {
                                    workingDir = cdResp.workingDir
                                } else {
                                    errorDialogMessage = "切换目录失败：${cdResp.error ?: "未知错误"}"
                                }
                            }
                        }
                    }
                }
            },
            showTrustConfirm = showTrustConfirm,
            trustConfirmPath = trustConfirmPath,
            trustConfirmBridgeId = trustConfirmBridgeId,
            onDismissTrustConfirm = { showTrustConfirm = false },
            onTrust = {
                scope.launch {
                    val added = ApiClient.addTrustedDir(user.id, trustConfirmPath)
                    if (!added) {
                        errorDialogMessage = "添加信任记录失败，请重试。"
                        showTrustConfirm = false
                        return@launch
                    }
                    showTrustConfirm = false
                    val cdResp = ApiClient.cdCcDir(user.id, groupId, trustConfirmPath)
                    if (cdResp.success) {
                        workingDir = cdResp.workingDir
                    } else {
                        errorDialogMessage = "切换目录失败：${cdResp.error ?: "未知错误"}"
                    }
                }
            },
            errorDialogMessage = errorDialogMessage,
            onDismissError = { errorDialogMessage = null },
        )
    }
}

@Composable
private fun WorkflowChatEffects(
    groupId: String,
    user: User,
    chatClient: ChatClient,
    connectionState: ConnectionState,
    messages: List<Message>,
    transientMessage: Message?,
    statusMessages: List<Message>,
    listState: LazyListState,
    onWorkingDirLoaded: (String) -> Unit,
    onAgentStateLoaded: (String, String) -> Unit,
    onAgentsLoaded: (List<AgentInfo>) -> Unit,
) {
    WorkflowChatConnectEffect(groupId, user, chatClient)
    WorkflowChatSyncStateEffect(groupId, user, connectionState, onWorkingDirLoaded, onAgentStateLoaded, onAgentsLoaded)
    WorkflowChatDisconnectEffect(chatClient)
    WorkflowChatAgentRefreshEffect(groupId, user, messages, onAgentStateLoaded)
    WorkflowChatAutoScrollEffect(listState, messages, transientMessage, statusMessages)
}

@Composable
private fun WorkflowChatConnectEffect(
    groupId: String,
    user: User,
    chatClient: ChatClient,
) {
    LaunchedEffect(groupId) {
        if (groupId.isBlank()) return@LaunchedEffect
        runCatching {
            chatClient.clearMessages()
            chatClient.connect(user.id, user.fullName, groupId)
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            println("❌ 工作流 WebSocket 连接失败: ${error.message}")
        }
    }
}

@Composable
private fun WorkflowChatSyncStateEffect(
    groupId: String,
    user: User,
    connectionState: ConnectionState,
    onWorkingDirLoaded: (String) -> Unit,
    onAgentStateLoaded: (String, String) -> Unit,
    onAgentsLoaded: (List<AgentInfo>) -> Unit,
) {
    LaunchedEffect(groupId, connectionState) {
        if (groupId.isBlank() || connectionState != ConnectionState.CONNECTED) return@LaunchedEffect
        kotlinx.coroutines.delay(200)
        val snap = ApiClient.getCcState(user.id, groupId)
        if (snap.success) {
            onWorkingDirLoaded(snap.workingDir)
            onAgentStateLoaded(snap.agentDisplayName, snap.permissionMode)
        }
        onAgentsLoaded(ApiClient.listAgents(user.id))
    }
}

@Composable
private fun WorkflowChatDisconnectEffect(chatClient: ChatClient) {
    DisposableEffect(Unit) {
        onDispose {
            kotlinx.coroutines.GlobalScope.launch {
                try {
                    chatClient.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }
}

@Composable
private fun WorkflowChatAgentRefreshEffect(
    groupId: String,
    user: User,
    messages: List<Message>,
    onAgentStateLoaded: (String, String) -> Unit,
) {
    LaunchedEffect(messages.size) {
        val latest = messages.lastOrNull() ?: return@LaunchedEffect
        if (latest.type != MessageType.SYSTEM || !isWorkflowAgentStateMessage(latest)) return@LaunchedEffect
        val snap = ApiClient.getCcState(user.id, groupId)
        if (snap.success) {
            onAgentStateLoaded(snap.agentDisplayName, snap.permissionMode)
        }
    }
}

@Composable
private fun WorkflowChatAutoScrollEffect(
    listState: LazyListState,
    messages: List<Message>,
    transientMessage: Message?,
    statusMessages: List<Message>,
) {
    LaunchedEffect(messages.size, transientMessage, statusMessages.size) {
        if (messages.isNotEmpty() || transientMessage != null || statusMessages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowChatScreenContent(
    headerState: WorkflowChatHeaderState,
    badgeState: WorkflowChatBadgeState,
    listState: LazyListState,
    messages: List<Message>,
    transientMessage: Message?,
    statusMessages: List<Message>,
    currentUserId: String,
    currentUserName: String,
    context: android.content.Context,
    chatClient: ChatClient,
    messageText: TextFieldValue,
    isGenerating: Boolean,
    aiExpandedStates: MutableMap<String, Boolean>,
    thinkingExpandedStates: MutableMap<String, Boolean>,
    onBack: () -> Unit,
    onShowFolderPicker: () -> Unit,
    onPermissionDropdownChange: (Boolean) -> Unit,
    onAgentDropdownChange: (Boolean) -> Unit,
    onPermissionModeSelect: (String, Boolean) -> Unit,
    onAgentSelect: (AgentInfo, Boolean) -> Unit,
    onNewSession: () -> Unit,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Scaffold(
        topBar = {
            WorkflowChatTopBar(
                headerState = headerState,
                onBack = onBack,
                onShowFolderPicker = onShowFolderPicker,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            WorkflowChatMessages(
                listState = listState,
                messages = messages,
                transientMessage = transientMessage,
                statusMessages = statusMessages,
                currentUserId = currentUserId,
                context = context,
                chatClient = chatClient,
                currentUserName = currentUserName,
                aiExpandedStates = aiExpandedStates,
                thinkingExpandedStates = thinkingExpandedStates,
            )
            WorkflowChatInputSection(
                badgeState = badgeState,
                messageText = messageText,
                isGenerating = isGenerating,
                onPermissionDropdownChange = onPermissionDropdownChange,
                onAgentDropdownChange = onAgentDropdownChange,
                onPermissionModeSelect = onPermissionModeSelect,
                onAgentSelect = onAgentSelect,
                onNewSession = onNewSession,
                onMessageTextChange = onMessageTextChange,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowChatTopBar(
    headerState: WorkflowChatHeaderState,
    onBack: () -> Unit,
    onShowFolderPicker: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    headerState.workflowName,
                    style = MaterialTheme.typography.titleMedium,
                    color = SilkColors.textPrimary,
                )
                if (headerState.workingDir.isNotBlank()) {
                    Text(
                        headerState.workingDir,
                        style = MaterialTheme.typography.bodySmall,
                        color = SilkColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (headerState.connectionState != ConnectionState.CONNECTED) {
                    Text(
                        text = when (headerState.connectionState) {
                            ConnectionState.CONNECTING -> "● 连接中..."
                            else -> "● 会话连接失败"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (headerState.connectionState) {
                            ConnectionState.CONNECTING -> Color(0xFFFF9800)
                            else -> Color(0xFFF44336)
                        },
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            TextButton(onClick = onShowFolderPicker) {
                Text("更改", color = SilkColors.primary)
            }
        },
    )
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.WorkflowChatMessages(
    listState: LazyListState,
    messages: List<Message>,
    transientMessage: Message?,
    statusMessages: List<Message>,
    currentUserId: String,
    currentUserName: String,
    context: android.content.Context,
    chatClient: ChatClient,
    aiExpandedStates: MutableMap<String, Boolean>,
    thinkingExpandedStates: MutableMap<String, Boolean>,
) {
    val scope = rememberCoroutineScope()
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        reverseLayout = true,
    ) {
        if (messages.isEmpty() && transientMessage == null && statusMessages.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("向 Agent 发送消息开始对话", color = SilkColors.textSecondary)
                }
            }
        } else {
            transientMessage?.let { msg ->
                item(key = "wf_transient") {
                    MessageItem(
                        message = msg,
                        currentUserId = currentUserId,
                        context = context,
                        isTransient = true,
                    )
                }
            }
            if (statusMessages.isNotEmpty()) {
                item(key = "wf_status") {
                    WorkflowChatStatusMessages(statusMessages)
                }
            }
            val reversedMessages = messages.reversed()
            val lastMsgId = messages.lastOrNull()?.id
            items(reversedMessages, key = { it.id }) { msg ->
                if (!CardMessageItem(msg, chatClient, currentUserId, currentUserName)) {
                    MessageItem(
                        message = msg,
                        currentUserId = currentUserId,
                        context = context,
                        isTransient = false,
                        isAIExpanded = aiExpandedStates[msg.id] ?: (msg.id == lastMsgId),
                        onAIExpandChange = { messageId, isExpanded ->
                            scope.launch {
                                handleWorkflowMessageExpansion(
                                    listState = listState,
                                    reversedMessages = reversedMessages,
                                    messageId = messageId,
                                    isExpanded = isExpanded,
                                    expandedStates = aiExpandedStates,
                                    useTimeout = false,
                                )
                            }
                        },
                        isThinkingExpanded = thinkingExpandedStates[msg.id] ?: false,
                        onThinkingExpandChange = { messageId, expanded ->
                            scope.launch {
                                handleWorkflowMessageExpansion(
                                    listState = listState,
                                    reversedMessages = reversedMessages,
                                    messageId = messageId,
                                    isExpanded = expanded,
                                    expandedStates = thinkingExpandedStates,
                                    useTimeout = true,
                                )
                            }
                        },
                        onLongContentCollapsed = { messageId ->
                            val idx = reversedMessages.indexOfFirst { it.id == messageId }
                            if (idx >= 0) {
                                scope.launch {
                                    kotlinx.coroutines.delay(80)
                                    listState.scrollToItem(idx)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkflowChatStatusMessages(statusMessages: List<Message>) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        statusMessages.reversed().forEach { statusMsg ->
            val content = statusMsg.content
            val hasToolIcon = content.isNotEmpty() && !Character.isLetterOrDigit(content.codePointAt(0))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                if (!hasToolIcon) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = Color.Gray.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WorkflowChatInputSection(
    badgeState: WorkflowChatBadgeState,
    messageText: TextFieldValue,
    isGenerating: Boolean,
    onPermissionDropdownChange: (Boolean) -> Unit,
    onAgentDropdownChange: (Boolean) -> Unit,
    onPermissionModeSelect: (String, Boolean) -> Unit,
    onAgentSelect: (AgentInfo, Boolean) -> Unit,
    onNewSession: () -> Unit,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        color = SilkColors.surfaceElevated,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            WorkflowChatBadgeRow(
                badgeState = badgeState,
                onPermissionDropdownChange = onPermissionDropdownChange,
                onAgentDropdownChange = onAgentDropdownChange,
                onPermissionModeSelect = onPermissionModeSelect,
                onAgentSelect = onAgentSelect,
                onNewSession = onNewSession,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = onMessageTextChange,
                    placeholder = { Text("向 Agent 发送消息...") },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
                if (isGenerating) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D4F)),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text("停止", color = Color.White)
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = messageText.text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "发送", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowChatBadgeRow(
    badgeState: WorkflowChatBadgeState,
    onPermissionDropdownChange: (Boolean) -> Unit,
    onAgentDropdownChange: (Boolean) -> Unit,
    onPermissionModeSelect: (String, Boolean) -> Unit,
    onAgentSelect: (AgentInfo, Boolean) -> Unit,
    onNewSession: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        Surface(
            onClick = onNewSession,
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFE3F2FD),
            border = BorderStroke(1.dp, Color(0xFF90CAF9)),
        ) {
            Text(
                text = "新会话",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1565C0),
            )
        }

        WorkflowChatOptionalBadges(
            permissionState = WorkflowChatPermissionState(
                mode = badgeState.permissionMode,
                showDropdown = badgeState.showPermModeDropdown,
            ),
            agentState = WorkflowChatAgentState(
                display = badgeState.activeAgentDisplay,
                availableAgents = badgeState.availableAgents,
                showDropdown = badgeState.showAgentDropdown,
            ),
            onPermissionDropdownChange = onPermissionDropdownChange,
            onAgentDropdownChange = onAgentDropdownChange,
            onPermissionModeSelect = onPermissionModeSelect,
            onAgentSelect = onAgentSelect,
        )
    }
}

@Composable
private fun WorkflowChatOptionalBadges(
    permissionState: WorkflowChatPermissionState,
    agentState: WorkflowChatAgentState,
    onPermissionDropdownChange: (Boolean) -> Unit,
    onAgentDropdownChange: (Boolean) -> Unit,
    onPermissionModeSelect: (String, Boolean) -> Unit,
    onAgentSelect: (AgentInfo, Boolean) -> Unit,
) {
    if (permissionState.mode.isBlank() && agentState.display.isBlank()) return
    if (permissionState.mode.isNotBlank()) {
        WorkflowPermissionBadge(permissionState, onPermissionDropdownChange, onPermissionModeSelect)
    }
    if (agentState.display.isNotBlank()) {
        WorkflowAgentBadge(agentState, onAgentDropdownChange, onAgentSelect)
    }
}

@Composable
private fun WorkflowPermissionBadge(
    permissionState: WorkflowChatPermissionState,
    onPermissionDropdownChange: (Boolean) -> Unit,
    onPermissionModeSelect: (String, Boolean) -> Unit,
) {
    Box {
        Surface(
            onClick = { onPermissionDropdownChange(!permissionState.showDropdown) },
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFF7F7F7),
            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
        ) {
            Text(
                text = permModeLabel(permissionState.mode),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF555555),
            )
        }
        DropdownMenu(
            expanded = permissionState.showDropdown,
            onDismissRequest = { onPermissionDropdownChange(false) },
        ) {
            listOf(
                "" to "Interactive",
                "ACCEPT_EDITS" to "Accept Edits",
                "BYPASS" to "Bypass",
            ).forEach { (value, label) ->
                val isCurrent = value == permissionState.mode || (value == "" && permissionState.mode == "INTERACTIVE")
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isCurrent) "\u2713 $label" else "  $label",
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCurrent) SilkColors.primary else Color.Unspecified,
                        )
                    },
                    onClick = { onPermissionModeSelect(value, isCurrent) },
                )
            }
        }
    }
}

@Composable
private fun WorkflowAgentBadge(
    agentState: WorkflowChatAgentState,
    onAgentDropdownChange: (Boolean) -> Unit,
    onAgentSelect: (AgentInfo, Boolean) -> Unit,
) {
    Box {
        Surface(
            onClick = { onAgentDropdownChange(!agentState.showDropdown) },
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFF3E5F5),
            border = BorderStroke(1.dp, Color(0xFFCE93D8)),
        ) {
            Text(
                text = agentState.display,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF6A1B9A),
            )
        }
        DropdownMenu(
            expanded = agentState.showDropdown,
            onDismissRequest = { onAgentDropdownChange(false) },
        ) {
            agentState.availableAgents.forEach { agent ->
                val isCurrent = agentState.display.contains(agent.displayName) || agentState.display.contains(agent.agentType)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isCurrent) "\u2713 ${agent.displayName}" else "  ${agent.displayName}",
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                isCurrent -> Color(0xFF6A1B9A)
                                !agent.connected -> Color.Gray
                                else -> Color.Unspecified
                            },
                        )
                    },
                    onClick = { onAgentSelect(agent, isCurrent) },
                    enabled = agent.connected,
                )
            }
        }
    }
}

private fun isWorkflowAgentStateMessage(message: Message): Boolean =
    message.content.startsWith("已切换到") ||
        message.content.contains("已激活") ||
        message.content.contains("已退出 agent")

@Composable
private fun WorkflowChatDialogs(
    showFolderPicker: Boolean,
    userId: String,
    initialPath: String?,
    onDismissFolderPicker: () -> Unit,
    onConfirmFolderPicker: (String) -> Unit,
    showTrustConfirm: Boolean,
    trustConfirmPath: String,
    trustConfirmBridgeId: String?,
    onDismissTrustConfirm: () -> Unit,
    onTrust: () -> Unit,
    errorDialogMessage: String?,
    onDismissError: () -> Unit,
) {
    if (showFolderPicker) {
        FolderPickerDialog(
            userId = userId,
            initialPath = initialPath,
            onDismiss = onDismissFolderPicker,
            onConfirm = onConfirmFolderPicker,
        )
    }
    if (showTrustConfirm) {
        TrustConfirmDialog(
            path = trustConfirmPath,
            bridgeId = trustConfirmBridgeId,
            onDismiss = onDismissTrustConfirm,
            onTrust = onTrust,
        )
    }
    errorDialogMessage?.let { msg ->
        WorkflowErrorDialog(message = msg, onDismiss = onDismissError)
    }
}

private suspend fun handleWorkflowMessageExpansion(
    listState: LazyListState,
    reversedMessages: List<Message>,
    messageId: String,
    isExpanded: Boolean,
    expandedStates: MutableMap<String, Boolean>,
    useTimeout: Boolean,
) {
    val idx = reversedMessages.indexOfFirst { it.id == messageId }
    if (isExpanded) {
        expandedStates[messageId] = true
        if (idx >= 0) {
            kotlinx.coroutines.delay(80)
            listState.scrollToItem(idx, 0)
            var prevSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }?.size ?: 0
            val collectBlock: suspend () -> Unit = {
                snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }?.size ?: 0
                }
                    .distinctUntilChanged()
                    .collect { size ->
                        if (size > 0 && prevSize > 0 && size > prevSize) {
                            listState.scroll { scrollBy((size - prevSize).toFloat()) }
                        }
                        if (size > 0) prevSize = size
                    }
            }
            if (useTimeout) {
                withTimeoutOrNull(3000L) { collectBlock() }
            } else {
                collectBlock()
            }
        }
    } else {
        if (idx >= 0) {
            listState.scrollToItem(idx, 0)
        }
        expandedStates[messageId] = false
    }
}
