package com.silk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
import kotlinx.coroutines.launch

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
        var showFolderPicker by remember(groupId) { mutableStateOf(false) }
        var showTrustConfirm by remember(groupId) { mutableStateOf(false) }
        var trustConfirmPath by remember(groupId) { mutableStateOf("") }
        var trustConfirmBridgeId by remember(groupId) { mutableStateOf<String?>(null) }
        var errorDialogMessage by remember(groupId) { mutableStateOf<String?>(null) }
        val listState = rememberLazyListState()

        // AI 消息展开/收起状态（与 ChatScreen 相同的 pattern）
        val aiExpandedStates = remember { mutableStateMapOf<String, Boolean>() }

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

        // Sync working dir after WebSocket connects (autoActivateForWorkflow completes on backend)
        LaunchedEffect(groupId, connectionState) {
            if (groupId.isBlank()) return@LaunchedEffect
            if (connectionState == ConnectionState.CONNECTED) {
                kotlinx.coroutines.delay(200)
                val snap = ApiClient.getCcState(user.id, groupId)
                if (snap.success && snap.workingDir.isNotBlank()) {
                    workingDir = snap.workingDir
                }
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

        // Auto-scroll to bottom (index 0 in reverseLayout)
        LaunchedEffect(messages.size, transientMessage, statusMessages.size) {
            if (messages.isNotEmpty() || transientMessage != null || statusMessages.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
        }

        suspend fun performCd(path: String) {
            val resp = ApiClient.cdCcDir(user.id, groupId, path)
            if (resp.success && resp.workingDir.isNotBlank()) {
                workingDir = resp.workingDir
            } else {
                errorDialogMessage = resp.error ?: "切换目录失败"
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
                                    "📁 $workingDir",
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
                                if (workingDir.isBlank()) "📂 选择" else "更改",
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
                            MessageItem(
                                message = msg,
                                currentUserId = user.id,
                                context = context,
                                isTransient = false,
                                isAIExpanded = aiExpandedStates[msg.id] ?: (msg.id == lastMsgId),
                                onAIExpandChange = { messageId, isExpanded ->
                                    aiExpandedStates[messageId] = isExpanded
                                    if (!isExpanded) {
                                        val idx = messages.reversed().indexOfFirst { it.id == messageId }
                                        if (idx >= 0) {
                                            scope.launch {
                                                kotlinx.coroutines.delay(80)
                                                listState.scrollToItem(idx)
                                            }
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

                // === Input area ===
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp,
                    color = SilkColors.surfaceElevated,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
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

        if (showFolderPicker) {
            FolderPickerDialog(
                userId = user.id,
                initialPath = workingDir.ifBlank { null },
                onDismiss = { showFolderPicker = false },
                onConfirm = { selectedPath ->
                    showFolderPicker = false
                    scope.launch {
                        when (val tc = ApiClient.checkTrustedDir(user.id, selectedPath)) {
                            is TrustCheckResult.BridgeDisconnected -> {
                                errorDialogMessage = "Bridge 未连接，无法切换目录。"
                            }
                            is TrustCheckResult.NotTrusted -> {
                                trustConfirmPath = selectedPath
                                trustConfirmBridgeId = tc.bridgeId
                                showTrustConfirm = true
                            }
                            is TrustCheckResult.Error -> {
                                errorDialogMessage = "检查信任状态失败：${tc.message}"
                            }
                            is TrustCheckResult.Trusted -> performCd(selectedPath)
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
                        if (added) {
                            showTrustConfirm = false
                            performCd(trustConfirmPath)
                        } else {
                            errorDialogMessage = "添加信任记录失败，请重试。"
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
