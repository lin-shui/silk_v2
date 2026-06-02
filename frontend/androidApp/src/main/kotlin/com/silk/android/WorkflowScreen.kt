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

    LaunchedEffect(Unit) {
        initializeCreateWorkflowDialog(
            userId = userId,
            selectedAgentType = selectedAgentType,
            newInitialDir = newInitialDir,
            onAgentsLoaded = { agents ->
                availableAgents = agents
                bridgeConnected = agents.any { it.connected }
            },
            onSelectedAgentType = { selectedAgentType = it },
            onInitialDirLoaded = { path, warning ->
                newInitialDir = path
                dirWarning = warning
            },
        )
    }

    val canCreate = !submitting && bridgeConnected &&
        newName.isNotBlank() && newInitialDir.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("创建工作流") },
        text = {
            CreateWorkflowDialogContent(
                newName = newName,
                newInitialDir = newInitialDir,
                availableAgents = availableAgents,
                selectedAgentType = selectedAgentType,
                selectedPermMode = selectedPermMode,
                agentDropdownExpanded = agentDropdownExpanded,
                permDropdownExpanded = permDropdownExpanded,
                bridgeConnected = bridgeConnected,
                dirWarning = dirWarning,
                onNameChange = { newName = it },
                onInitialDirChange = { newInitialDir = it },
                onAgentDropdownExpandedChange = { agentDropdownExpanded = it },
                onPermDropdownExpandedChange = { permDropdownExpanded = it },
                onAgentSelected = { selectedAgentType = it },
                onPermSelected = { selectedPermMode = it },
                onOpenFolderPicker = { showFolderPicker = true },
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!canCreate) return@Button
                    submitting = true
                    scope.launch {
                        attemptCreateWorkflow(
                            userId = userId,
                            newName = newName,
                            newInitialDir = newInitialDir,
                            selectedAgentType = selectedAgentType,
                            selectedPermMode = selectedPermMode,
                            onCreated = onCreated,
                            onNeedsTrust = { bridgeId ->
                                trustBridgeId = bridgeId
                                showTrustConfirm = true
                            },
                            onError = { errorMessage = it },
                            setSubmitting = { submitting = it },
                        )
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

    CreateWorkflowAuxDialogs(
        showFolderPicker = showFolderPicker,
        userId = userId,
        newInitialDir = newInitialDir,
        showTrustConfirm = showTrustConfirm,
        trustBridgeId = trustBridgeId,
        errorMessage = errorMessage,
        onDismissFolderPicker = { showFolderPicker = false },
        onFolderPicked = { path ->
            newInitialDir = path
            showFolderPicker = false
        },
        onDismissTrustConfirm = {
            showTrustConfirm = false
            submitting = false
        },
        onTrust = {
            submitting = true
            scope.launch {
                attemptTrustAndCreateWorkflow(
                    userId = userId,
                    newName = newName,
                    newInitialDir = newInitialDir,
                    selectedAgentType = selectedAgentType,
                    selectedPermMode = selectedPermMode,
                    onCreated = onCreated,
                    onTrustHandled = { showTrustConfirm = false },
                    onError = { errorMessage = it },
                    setSubmitting = { submitting = it },
                )
            }
        },
        onDismissError = { errorMessage = null },
    )
}

@Composable
private fun CreateWorkflowDialogContent(
    newName: String,
    newInitialDir: String,
    availableAgents: List<AgentInfo>,
    selectedAgentType: String,
    selectedPermMode: String,
    agentDropdownExpanded: Boolean,
    permDropdownExpanded: Boolean,
    bridgeConnected: Boolean,
    dirWarning: String?,
    onNameChange: (String) -> Unit,
    onInitialDirChange: (String) -> Unit,
    onAgentDropdownExpandedChange: (Boolean) -> Unit,
    onPermDropdownExpandedChange: (Boolean) -> Unit,
    onAgentSelected: (String) -> Unit,
    onPermSelected: (String) -> Unit,
    onOpenFolderPicker: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = newName,
            onValueChange = onNameChange,
            label = { Text("工作流名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))

        CreateWorkflowAgentSelector(
            availableAgents = availableAgents,
            selectedAgentType = selectedAgentType,
            expanded = agentDropdownExpanded,
            onExpandedChange = onAgentDropdownExpandedChange,
            onSelected = onAgentSelected,
        )
        Spacer(Modifier.height(12.dp))

        CreateWorkflowPermissionSelector(
            selectedPermMode = selectedPermMode,
            expanded = permDropdownExpanded,
            onExpandedChange = onPermDropdownExpandedChange,
            onSelected = onPermSelected,
        )
        Spacer(Modifier.height(12.dp))

        CreateWorkflowInitialDirField(
            newInitialDir = newInitialDir,
            bridgeConnected = bridgeConnected,
            dirWarning = dirWarning,
            onInitialDirChange = onInitialDirChange,
            onOpenFolderPicker = onOpenFolderPicker,
        )
    }
}

@Composable
private fun CreateWorkflowAgentSelector(
    availableAgents: List<AgentInfo>,
    selectedAgentType: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
) {
    CreateWorkflowFieldLabel(text = "Agent")
    Box {
        OutlinedTextField(
            value = availableAgents.firstOrNull { it.agentType == selectedAgentType }?.displayName ?: selectedAgentType,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { onExpandedChange(true) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "选择")
                }
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            availableAgents.forEach { agent ->
                DropdownMenuItem(
                    text = {
                        val suffix = if (agent.connected) "" else "（未连接）"
                        Text("${agent.displayName}$suffix")
                    },
                    onClick = {
                        onSelected(agent.agentType)
                        onExpandedChange(false)
                    },
                    enabled = agent.connected,
                )
            }
        }
    }
}

@Composable
private fun CreateWorkflowPermissionSelector(
    selectedPermMode: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
) {
    CreateWorkflowFieldLabel(text = "权限模式")
    Box {
        OutlinedTextField(
            value = workflowPermissionModeLabel(selectedPermMode),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { onExpandedChange(true) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "选择")
                }
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            listOf(
                "" to "Interactive",
                "ACCEPT_EDITS" to "Accept Edits",
                "BYPASS" to "Bypass",
            ).forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelected(value)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@Composable
private fun CreateWorkflowFieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = SilkColors.textSecondary,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun CreateWorkflowInitialDirField(
    newInitialDir: String,
    bridgeConnected: Boolean,
    dirWarning: String?,
    onInitialDirChange: (String) -> Unit,
    onOpenFolderPicker: () -> Unit,
) {
    CreateWorkflowFieldLabel(text = "工作目录")
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = newInitialDir,
            onValueChange = onInitialDirChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = bridgeConnected,
            placeholder = {
                Text(createWorkflowDirPlaceholder(bridgeConnected = bridgeConnected, dirWarning = dirWarning))
            },
        )
        TextButton(
            onClick = onOpenFolderPicker,
            enabled = bridgeConnected,
        ) { Text("📂 选择") }
    }

    when {
        !bridgeConnected -> CreateWorkflowDirHint(
            message = "⚠ Bridge 未连接。请先启动 Bridge Agent 再创建工作流。",
            color = SilkColors.error,
        )
        dirWarning != null -> CreateWorkflowDirHint(
            message = "⚠ $dirWarning，请手动输入或选择工作目录。",
            color = SilkColors.warning,
        )
    }
}

@Composable
private fun CreateWorkflowDirHint(
    message: String,
    color: Color,
) {
    Spacer(Modifier.height(6.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun CreateWorkflowAuxDialogs(
    showFolderPicker: Boolean,
    userId: String,
    newInitialDir: String,
    showTrustConfirm: Boolean,
    trustBridgeId: String?,
    errorMessage: String?,
    onDismissFolderPicker: () -> Unit,
    onFolderPicked: (String) -> Unit,
    onDismissTrustConfirm: () -> Unit,
    onTrust: () -> Unit,
    onDismissError: () -> Unit,
) {
    if (showFolderPicker) {
        FolderPickerDialog(
            userId = userId,
            initialPath = newInitialDir.ifBlank { null },
            onDismiss = onDismissFolderPicker,
            onConfirm = onFolderPicked,
        )
    }

    if (showTrustConfirm) {
        TrustConfirmDialog(
            path = newInitialDir.trim(),
            bridgeId = trustBridgeId,
            onDismiss = onDismissTrustConfirm,
            onTrust = onTrust,
        )
    }

    errorMessage?.let { msg ->
        WorkflowErrorDialog(message = msg, onDismiss = onDismissError)
    }
}

private fun workflowPermissionModeLabel(mode: String): String = when (mode) {
    "ACCEPT_EDITS" -> "Accept Edits"
    "BYPASS" -> "Bypass"
    else -> "Interactive"
}

private fun createWorkflowDirPlaceholder(
    bridgeConnected: Boolean,
    dirWarning: String?,
): String = when {
    !bridgeConnected -> "Bridge 未连接，请先启动 Bridge"
    dirWarning != null -> "请输入或选择工作目录"
    else -> "加载默认目录中…"
}

private suspend fun initializeCreateWorkflowDialog(
    userId: String,
    selectedAgentType: String,
    newInitialDir: String,
    onAgentsLoaded: (List<AgentInfo>) -> Unit,
    onSelectedAgentType: (String) -> Unit,
    onInitialDirLoaded: (String, String?) -> Unit,
) {
    val agents = ApiClient.listAgents(userId)
    val bridgeConnected = agents.any { it.connected }
    onAgentsLoaded(agents)

    if (selectedAgentType.isBlank()) {
        onSelectedAgentType(defaultWorkflowAgentType(agents))
    }

    if (newInitialDir.isBlank() && bridgeConnected) {
        val resp = ApiClient.listCcDir(userId, null)
        onInitialDirLoaded(
            if (resp.success && resp.path.isNotBlank()) resp.path else "",
            if (resp.success && resp.path.isNotBlank()) null else resp.error ?: "无法获取默认目录",
        )
    }
}

private fun defaultWorkflowAgentType(agents: List<AgentInfo>): String {
    val codexOk = agents.any { it.agentType == "codex" && it.connected }
    val ccOk = agents.any { it.agentType == "claude_code" && it.connected }
    return when {
        codexOk -> "codex"
        ccOk -> "claude_code"
        else -> "claude_code"
    }
}

private suspend fun attemptCreateWorkflow(
    userId: String,
    newName: String,
    newInitialDir: String,
    selectedAgentType: String,
    selectedPermMode: String,
    onCreated: (WorkflowItem) -> Unit,
    onNeedsTrust: (String?) -> Unit,
    onError: (String) -> Unit,
    setSubmitting: (Boolean) -> Unit,
) {
    try {
        val initDir = newInitialDir.trim()
        when (val trustCheck = ApiClient.checkTrustedDir(userId, initDir)) {
            is TrustCheckResult.BridgeDisconnected -> onError("Bridge 未连接，无法创建工作流。")
            is TrustCheckResult.Error -> onError("检查信任状态失败：${trustCheck.message}")
            is TrustCheckResult.NotTrusted -> onNeedsTrust(trustCheck.bridgeId)
            is TrustCheckResult.Trusted -> {
                performCreate(
                    userId = userId,
                    name = newName.trim(),
                    initialDir = initDir,
                    agentType = selectedAgentType.ifBlank { "claude_code" },
                    permissionMode = selectedPermMode,
                    onCreated = onCreated,
                    onError = onError,
                )
            }
        }
    } finally {
        setSubmitting(false)
    }
}

private suspend fun attemptTrustAndCreateWorkflow(
    userId: String,
    newName: String,
    newInitialDir: String,
    selectedAgentType: String,
    selectedPermMode: String,
    onCreated: (WorkflowItem) -> Unit,
    onTrustHandled: () -> Unit,
    onError: (String) -> Unit,
    setSubmitting: (Boolean) -> Unit,
) {
    try {
        val added = ApiClient.addTrustedDir(userId, newInitialDir.trim())
        onTrustHandled()
        if (added) {
            performCreate(
                userId = userId,
                name = newName.trim(),
                initialDir = newInitialDir.trim(),
                agentType = selectedAgentType.ifBlank { "claude_code" },
                permissionMode = selectedPermMode,
                onCreated = onCreated,
                onError = onError,
            )
        } else {
            onError("添加信任记录失败，请重试。")
        }
    } finally {
        setSubmitting(false)
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
