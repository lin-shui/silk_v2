package com.silk.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WorkflowScreen(appState: AppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()

    var workflows by remember { mutableStateOf<List<WorkflowItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var workflowToDelete by remember { mutableStateOf<WorkflowItem?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var workflowToRename by remember { mutableStateOf<WorkflowItem?>(null) }
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuWorkflow by remember { mutableStateOf<WorkflowItem?>(null) }

    LaunchedEffect(user.id) {
        isLoading = true
        workflows = ApiClient.getWorkflows(user.id)
        isLoading = false
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = SilkColors.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建工作流")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("工作流", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
            Divider(color = SilkColors.divider)

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (workflows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔗", fontSize = 48.sp)
                        Spacer(Modifier.height(16.dp))
                        Text("工作流功能开发中", color = SilkColors.textSecondary, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("可先创建工作流名称，后续将支持多机器人编排", color = SilkColors.textLight, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(workflows) { wf ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { appState.selectWorkflow(wf) },
                                    onLongClick = {
                                        contextMenuWorkflow = wf
                                        showContextMenu = true
                                    },
                                ),
                            colors = CardDefaults.cardColors(containerColor = SilkColors.cardBackground),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(wf.name, style = MaterialTheme.typography.bodyLarge)
                                Box {
                                    IconButton(onClick = {
                                        contextMenuWorkflow = wf
                                        showContextMenu = true
                                    }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "更多操作", tint = SilkColors.textSecondary)
                                    }
                                    DropdownMenu(
                                        expanded = showContextMenu && contextMenuWorkflow?.id == wf.id,
                                        onDismissRequest = {
                                            showContextMenu = false
                                            contextMenuWorkflow = null
                                        }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("重命名") },
                                            onClick = {
                                                showContextMenu = false
                                                workflowToRename = wf
                                                renameText = wf.name
                                                showRenameDialog = true
                                            },
                                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("删除", color = SilkColors.error) },
                                            onClick = {
                                                showContextMenu = false
                                                workflowToDelete = wf
                                                showDeleteConfirm = true
                                            },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = SilkColors.error) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateWorkflowDialog(
            userId = user.id,
            onDismiss = { showCreateDialog = false },
            onCreated = { wf ->
                showCreateDialog = false
                scope.launch {
                    workflows = ApiClient.getWorkflows(user.id)
                }
                appState.selectWorkflow(wf)
            },
        )
    }

    // 删除确认对话框
    if (showDeleteConfirm && workflowToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirm = false
                workflowToDelete = null
            },
            title = { Text("确认删除") },
            text = { Text("确定要删除工作流 \"${workflowToDelete?.name}\" 吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        val wf = workflowToDelete ?: return@Button
                        scope.launch {
                            ApiClient.deleteWorkflow(wf.id, user.id)
                            workflows = ApiClient.getWorkflows(user.id)
                        }
                        showDeleteConfirm = false
                        workflowToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SilkColors.error)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    workflowToDelete = null
                }) { Text("取消") }
            }
        )
    }

    // 重命名对话框
    if (showRenameDialog && workflowToRename != null) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                workflowToRename = null
            },
            title = { Text("重命名工作流") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("工作流名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val wf = workflowToRename ?: return@Button
                        val trimmed = renameText.trim()
                        if (trimmed.isBlank()) return@Button
                        scope.launch {
                            ApiClient.renameWorkflow(wf.id, user.id, trimmed)
                            workflows = ApiClient.getWorkflows(user.id)
                        }
                        showRenameDialog = false
                        workflowToRename = null
                    },
                    enabled = renameText.trim().isNotBlank()
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    workflowToRename = null
                }) { Text("取消") }
            }
        )
    }
}

@Suppress("CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateWorkflowDialog(
    userId: String,
    onDismiss: () -> Unit,
    onCreated: (WorkflowItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }
    var newInitialDir by remember { mutableStateOf("") }
    var availableAgents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var selectedAgentType by remember { mutableStateOf("") }
    var selectedPermMode by remember { mutableStateOf("") }
    var agentDropdownExpanded by remember { mutableStateOf(false) }
    var permDropdownExpanded by remember { mutableStateOf(false) }
    var bridgeConnected by remember { mutableStateOf(true) }
    var dirWarning by remember { mutableStateOf<String?>(null) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showTrustConfirm by remember { mutableStateOf(false) }
    var trustBridgeId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    // 打开时检查 Bridge 连接状态、拉 agent 列表、拉默认目录
    LaunchedEffect(Unit) {
        val agents = ApiClient.listAgents(userId)
        availableAgents = agents
        bridgeConnected = agents.any { it.connected }
        if (selectedAgentType.isBlank()) {
            val codexOk = agents.firstOrNull { it.agentType == "codex" && it.connected } != null
            val ccOk = agents.firstOrNull { it.agentType == "claude_code" && it.connected } != null
            selectedAgentType = when {
                codexOk -> "codex"
                ccOk -> "claude_code"
                else -> "claude_code"
            }
        }
        if (newInitialDir.isBlank() && bridgeConnected) {
            dirWarning = null
            val resp = ApiClient.listCcDir(userId, null)
            if (resp.success && resp.path.isNotBlank()) {
                newInitialDir = resp.path
            } else {
                dirWarning = resp.error ?: "无法获取默认目录"
            }
        }
    }

    val canCreate = !submitting && bridgeConnected &&
                    newName.isNotBlank() && newInitialDir.isNotBlank()

    val permModeLabel = { mode: String ->
        when (mode) {
            "ACCEPT_EDITS" -> "Accept Edits"
            "BYPASS" -> "Bypass"
            else -> "Interactive"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("创建工作流") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("工作流名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))

                // Agent 选择
                Text("Agent", style = MaterialTheme.typography.bodySmall, color = SilkColors.textSecondary)
                Spacer(Modifier.height(4.dp))
                Box {
                    OutlinedTextField(
                        value = availableAgents.firstOrNull { it.agentType == selectedAgentType }?.displayName ?: selectedAgentType,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { agentDropdownExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "选择")
                            }
                        },
                    )
                    DropdownMenu(
                        expanded = agentDropdownExpanded,
                        onDismissRequest = { agentDropdownExpanded = false },
                    ) {
                        availableAgents.forEach { agent ->
                            DropdownMenuItem(
                                text = {
                                    val suffix = if (agent.connected) "" else "（未连接）"
                                    Text("${agent.displayName}$suffix")
                                },
                                onClick = {
                                    selectedAgentType = agent.agentType
                                    agentDropdownExpanded = false
                                },
                                enabled = agent.connected,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 权限模式
                Text("权限模式", style = MaterialTheme.typography.bodySmall, color = SilkColors.textSecondary)
                Spacer(Modifier.height(4.dp))
                Box {
                    OutlinedTextField(
                        value = permModeLabel(selectedPermMode),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { permDropdownExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "选择")
                            }
                        },
                    )
                    DropdownMenu(
                        expanded = permDropdownExpanded,
                        onDismissRequest = { permDropdownExpanded = false },
                    ) {
                        listOf("" to "Interactive", "ACCEPT_EDITS" to "Accept Edits", "BYPASS" to "Bypass").forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedPermMode = value
                                    permDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 工作目录
                Text("工作目录", style = MaterialTheme.typography.bodySmall, color = SilkColors.textSecondary)
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newInitialDir,
                        onValueChange = { newInitialDir = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = bridgeConnected,
                        placeholder = {
                            Text(
                                when {
                                    !bridgeConnected -> "Bridge 未连接，请先启动 Bridge"
                                    dirWarning != null -> "请输入或选择工作目录"
                                    else -> "加载默认目录中…"
                                },
                            )
                        },
                    )
                    TextButton(
                        onClick = { showFolderPicker = true },
                        enabled = bridgeConnected,
                    ) { Text("📂 选择") }
                }
                if (!bridgeConnected) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "⚠ Bridge 未连接。请先启动 Bridge Agent 再创建工作流。",
                        style = MaterialTheme.typography.bodySmall,
                        color = SilkColors.error,
                    )
                } else if (dirWarning != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "⚠ $dirWarning，请手动输入或选择工作目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = SilkColors.warning,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!canCreate) return@Button
                    val initDir = newInitialDir.trim()
                    val name = newName.trim()
                    val agentType = selectedAgentType.ifBlank { "claude_code" }
                    val permMode = selectedPermMode
                    submitting = true
                    scope.launch {
                        try {
                            // 1. 信任目录检查
                            when (val tc = ApiClient.checkTrustedDir(userId, initDir)) {
                                is TrustCheckResult.BridgeDisconnected -> {
                                    errorMessage = "Bridge 未连接，无法创建工作流。"
                                    return@launch
                                }
                                is TrustCheckResult.Error -> {
                                    errorMessage = "检查信任状态失败：${tc.message}"
                                    return@launch
                                }
                                is TrustCheckResult.NotTrusted -> {
                                    trustBridgeId = tc.bridgeId
                                    showTrustConfirm = true
                                    return@launch
                                }
                                is TrustCheckResult.Trusted -> {} // 继续
                            }
                            // 2. 已信任：直接创建
                            performCreate(userId, name, initDir, agentType, permMode, onCreated) { msg ->
                                errorMessage = msg
                            }
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = canCreate,
                colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
            ) { Text(if (submitting) "创建中…" else "创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        },
    )

    if (showFolderPicker) {
        FolderPickerDialog(
            userId = userId,
            initialPath = newInitialDir.ifBlank { null },
            onDismiss = { showFolderPicker = false },
            onConfirm = { path ->
                newInitialDir = path
                showFolderPicker = false
            },
        )
    }

    if (showTrustConfirm) {
        TrustConfirmDialog(
            path = newInitialDir.trim(),
            bridgeId = trustBridgeId,
            onDismiss = {
                showTrustConfirm = false
                submitting = false
            },
            onTrust = {
                submitting = true
                scope.launch {
                    try {
                        val added = ApiClient.addTrustedDir(userId, newInitialDir.trim())
                        showTrustConfirm = false
                        if (added) {
                            performCreate(
                                userId, newName.trim(), newInitialDir.trim(),
                                selectedAgentType.ifBlank { "claude_code" }, selectedPermMode,
                                onCreated,
                            ) { msg -> errorMessage = msg }
                        } else {
                            errorMessage = "添加信任记录失败，请重试。"
                        }
                    } finally {
                        submitting = false
                    }
                }
            },
        )
    }

    errorMessage?.let { msg ->
        WorkflowErrorDialog(message = msg, onDismiss = { errorMessage = null })
    }
}

private suspend fun performCreate(
    userId: String,
    name: String,
    initialDir: String,
    agentType: String,
    permissionMode: String,
    onCreated: (WorkflowItem) -> Unit,
    onError: (String) -> Unit,
) {
    when (val r = ApiClient.createWorkflow(name, "", userId, initialDir, agentType, permissionMode)) {
        is CreateWorkflowResult.Ok -> onCreated(r.workflow)
        is CreateWorkflowResult.Err -> onError("创建工作流失败：${r.message}")
    }
}
