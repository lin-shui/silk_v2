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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowChatScreen(appState: AppState) {
    val workflow = appState.selectedWorkflow ?: return
    val user = appState.currentUser ?: return
    val groupId = workflow.groupId
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val baseUrl = BackendUrlHolder.getBaseUrl()
    val wsUrl = baseUrl.replaceFirst("http", "ws")

    // key(groupId) — destroy/recreate on workflow switch to prevent message leaking
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

        // AI 消息展开/收起状态（与 ChatScreen 相同的 pattern）
        val aiExpandedStates = remember { mutableStateMapOf<String, Boolean>() }
        val thinkingExpandedStates = remember { mutableStateMapOf<String, Boolean>() }

        // Connect WebSocket
        LaunchedEffect(groupId) {
            if (groupId.isBlank()) return@LaunchedEffect
            try {
                chatClient.clearMessages()
                chatClient.connect(user.id, user.fullName, groupId)
            } catch (e: Exception) {
                println("❌ 工作流 WebSocket 连接失败: ${e.message}")
            }
        }

        // Sync working dir, agent display, permission mode after WebSocket connects
        LaunchedEffect(groupId, connectionState) {
            if (groupId.isBlank()) return@LaunchedEffect
            if (connectionState == ConnectionState.CONNECTED) {
                kotlinx.coroutines.delay(200)
                val snap = ApiClient.getCcState(user.id, groupId)
                if (snap.success) {
                    if (snap.workingDir.isNotBlank()) workingDir = snap.workingDir
                    activeAgentDisplay = snap.agentDisplayName
                    permissionMode = snap.permissionMode
                }
                availableAgents = ApiClient.listAgents(user.id)
            }
        }

        // Disconnect on dispose
        DisposableEffect(Unit) {
            onDispose {
                scope.launch {
                    try { chatClient.disconnect() } catch (_: Exception) {}
                }
            }
        }

        // 监听新增消息，刷新 activeAgent / permissionMode 显示
        LaunchedEffect(messages.size) {
            val latest = messages.lastOrNull() ?: return@LaunchedEffect
            val isAgentSwitchMsg = latest.content.startsWith("已切换到") ||
                latest.content.contains("已激活") || latest.content.contains("已退出 agent")
            if (latest.type == com.silk.shared.models.MessageType.SYSTEM && isAgentSwitchMsg) {
                val snap = ApiClient.getCcState(user.id, groupId)
                if (snap.success) {
                    activeAgentDisplay = snap.agentDisplayName
                    permissionMode = snap.permissionMode
                }
            }
        }

        // Auto-scroll to bottom (index 0 in reverseLayout)
        LaunchedEffect(messages.size, transientMessage, statusMessages.size) {
            if (messages.isNotEmpty() || transientMessage != null || statusMessages.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                workflow.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = SilkColors.textPrimary,
                            )
                            if (workingDir.isNotBlank()) {
                                Text(
                                    workingDir,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SilkColors.textSecondary,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            if (connectionState != ConnectionState.CONNECTED) {
                                Text(
                                    text = when (connectionState) {
                                        ConnectionState.CONNECTING -> "● 连接中..."
                                        else -> "● 会话连接失败"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (connectionState) {
                                        ConnectionState.CONNECTING -> Color(0xFFFF9800)
                                        else -> Color(0xFFF44336)
                                    },
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { appState.navigateBack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        TextButton(onClick = { showFolderPicker = true }) {
                            Text(
                                "更改",
                                color = SilkColors.primary,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // === Messages area ===
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
                        // 1. Transient streaming message (bottom-most in reverseLayout)
                        transientMessage?.let { msg ->
                            item(key = "wf_transient") {
                                MessageItem(
                                    message = msg,
                                    currentUserId = user.id,
                                    context = context,
                                    isTransient = true,
                                )
                            }
                        }
                        // 2. Status messages (tool calls, search progress)
                        if (statusMessages.isNotEmpty()) {
                            item(key = "wf_status") {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    statusMessages.reversed().forEach { statusMsg ->
                                        val content = statusMsg.content
                                        val hasToolIcon = content.isNotEmpty() &&
                                            !Character.isLetterOrDigit(content.codePointAt(0))
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
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // 3. Persistent messages (reversed for reverseLayout)
                        val lastMsgId = messages.lastOrNull()?.id
                        items(messages.reversed(), key = { it.id }) { msg ->
                            if (!CardMessageItem(msg, chatClient, user.id, user.fullName)) {
                                MessageItem(
                                    message = msg,
                                    currentUserId = user.id,
                                    context = context,
                                    isTransient = false,
                                    isAIExpanded = aiExpandedStates[msg.id] ?: (msg.id == lastMsgId),
                                    onAIExpandChange = { messageId, isExpanded ->
                                        val idx = messages.reversed().indexOfFirst { it.id == messageId }
                                        if (isExpanded) {
                                            aiExpandedStates[messageId] = true
                                            if (idx >= 0) {
                                                scope.launch {
                                                    kotlinx.coroutines.delay(80)
                                                    listState.scrollToItem(idx, 0)

                                                    var prevSize = listState.layoutInfo.visibleItemsInfo
                                                        .firstOrNull { it.index == idx }?.size ?: 0
                                                    snapshotFlow {
                                                        listState.layoutInfo.visibleItemsInfo
                                                            .firstOrNull { it.index == idx }?.size ?: 0
                                                    }
                                                    .distinctUntilChanged()
                                                    .collect { size ->
                                                        if (size > 0 && prevSize > 0 && size > prevSize) {
                                                            listState.scroll { scrollBy((size - prevSize).toFloat()) }
                                                        }
                                                        if (size > 0) prevSize = size
                                                    }
                                                }
                                            }
                                        } else {
                                            if (idx >= 0) {
                                                scope.launch {
                                                    listState.scrollToItem(idx, 0)
                                                    aiExpandedStates[messageId] = false
                                                }
                                            } else {
                                                aiExpandedStates[messageId] = false
                                            }
                                        }
                                    },
                                    isThinkingExpanded = thinkingExpandedStates[msg.id] ?: false,
                                    onThinkingExpandChange = { messageId, expanded ->
                                        val idx = messages.reversed().indexOfFirst { it.id == messageId }
                                        if (expanded) {
                                            thinkingExpandedStates[messageId] = true
                                            if (idx >= 0) {
                                                scope.launch {
                                                    kotlinx.coroutines.delay(80)
                                                    listState.scrollToItem(idx, 0)

                                                    var prevSize = listState.layoutInfo.visibleItemsInfo
                                                        .firstOrNull { it.index == idx }?.size ?: 0
                                                    kotlinx.coroutines.withTimeoutOrNull(3000L) {
                                                        snapshotFlow {
                                                            listState.layoutInfo.visibleItemsInfo
                                                                .firstOrNull { it.index == idx }?.size ?: 0
                                                        }
                                                        .distinctUntilChanged()
                                                        .collect { size ->
                                                            if (size > 0 && prevSize > 0 && size > prevSize) {
                                                                listState.scroll { scrollBy((size - prevSize).toFloat()) }
                                                            }
                                                            if (size > 0) prevSize = size
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            if (idx >= 0) {
                                                scope.launch {
                                                    listState.scrollToItem(idx, 0)
                                                    thinkingExpandedStates[messageId] = false
                                                }
                                            } else {
                                                thinkingExpandedStates[messageId] = false
                                            }
                                        }
                                    },
                                    onLongContentCollapsed = { messageId ->
                                        val idx = messages.reversed().indexOfFirst { it.id == messageId }
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

                // === Input area ===
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
                        // Badge row: new session + permission mode + agent quick-switch
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            // New session badge
                            Surface(
                                onClick = {
                                    scope.launch {
                                        chatClient.sendMessage(user.id, user.fullName, "/new")
                                    }
                                },
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

                        if (permissionMode.isNotBlank() || activeAgentDisplay.isNotBlank()) {
                                // Permission mode badge
                                if (permissionMode.isNotBlank()) {
                                    Box {
                                        Surface(
                                            onClick = {
                                                showPermModeDropdown = !showPermModeDropdown
                                                showAgentDropdown = false
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color(0xFFF7F7F7),
                                            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                                        ) {
                                            Text(
                                                text = permModeLabel(permissionMode),
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFF555555),
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showPermModeDropdown,
                                            onDismissRequest = { showPermModeDropdown = false },
                                        ) {
                                            listOf(
                                                "" to "Interactive",
                                                "ACCEPT_EDITS" to "Accept Edits",
                                                "BYPASS" to "Bypass",
                                            ).forEach { (value, label) ->
                                                val isCurrent = value == permissionMode ||
                                                    (value == "" && permissionMode == "INTERACTIVE")
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = if (isCurrent) "\u2713 $label" else "  $label",
                                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                                            color = if (isCurrent) SilkColors.primary else Color.Unspecified,
                                                        )
                                                    },
                                                    onClick = {
                                                        showPermModeDropdown = false
                                                        if (!isCurrent) {
                                                            val newMode = value.ifBlank { "INTERACTIVE" }
                                                            scope.launch {
                                                                val resp = ApiClient.updateCcSettings(
                                                                    user.id, groupId,
                                                                    permissionMode = newMode,
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
                                                )
                                            }
                                        }
                                    }
                                }

                                // Agent badge
                                if (activeAgentDisplay.isNotBlank()) {
                                    Box {
                                        Surface(
                                            onClick = {
                                                showAgentDropdown = !showAgentDropdown
                                                showPermModeDropdown = false
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color(0xFFF3E5F5),
                                            border = BorderStroke(1.dp, Color(0xFFCE93D8)),
                                        ) {
                                            Text(
                                                text = activeAgentDisplay,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFF6A1B9A),
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showAgentDropdown,
                                            onDismissRequest = { showAgentDropdown = false },
                                        ) {
                                            availableAgents.forEach { agent ->
                                                val isCurrent = activeAgentDisplay.contains(agent.displayName) ||
                                                    activeAgentDisplay.contains(agent.agentType)
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = if (isCurrent) "\u2713 ${agent.displayName}"
                                                                   else "  ${agent.displayName}",
                                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                                            color = when {
                                                                isCurrent -> Color(0xFF6A1B9A)
                                                                !agent.connected -> Color.Gray
                                                                else -> Color.Unspecified
                                                            },
                                                        )
                                                    },
                                                    onClick = {
                                                        showAgentDropdown = false
                                                        if (!isCurrent && agent.connected) {
                                                            scope.launch {
                                                                val resp = ApiClient.updateCcSettings(
                                                                    user.id, groupId,
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
                                                    enabled = agent.connected,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Input field + send/stop button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = messageText,
                                onValueChange = { messageText = it },
                                placeholder = { Text("向 Agent 发送消息...") },
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                            )
                            if (isGenerating) {
                                Button(
                                    onClick = { chatClient.stopGeneration(user.id, user.fullName) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D4F)),
                                    modifier = Modifier.height(56.dp),
                                ) { Text("停止", color = Color.White) }
                            } else {
                                Button(
                                    onClick = {
                                        val text = messageText.text.trim()
                                        if (text.isNotEmpty()) {
                                            scope.launch {
                                                chatClient.sendMessage(user.id, user.fullName, text)
                                            }
                                            messageText = TextFieldValue("")
                                        }
                                    },
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
        }

        if (showFolderPicker) {
            FolderPickerDialog(
                userId = user.id,
                initialPath = workingDir.ifBlank { null },
                onDismiss = { showFolderPicker = false },
                onConfirm = { selectedPath ->
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
            )
        }

        if (showTrustConfirm) {
            TrustConfirmDialog(
                path = trustConfirmPath,
                bridgeId = trustConfirmBridgeId,
                onDismiss = { showTrustConfirm = false },
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
            )
        }

        errorDialogMessage?.let { msg ->
            WorkflowErrorDialog(
                message = msg,
                onDismiss = { errorDialogMessage = null },
            )
        }
    }
}
