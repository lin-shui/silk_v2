package com.silk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowScreen(appState: AppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()

    var workflows by remember { mutableStateOf<List<WorkflowItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }

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
                            onClick = { appState.selectWorkflow(wf) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SilkColors.cardBackground),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(wf.name, style = MaterialTheme.typography.bodyLarge)
                                IconButton(onClick = {
                                    scope.launch {
                                        ApiClient.deleteWorkflow(wf.id, user.id)
                                        workflows = ApiClient.getWorkflows(user.id)
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = SilkColors.error)
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
    var dirLoadError by remember { mutableStateOf<String?>(null) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showTrustConfirm by remember { mutableStateOf(false) }
    var trustBridgeId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    // 打开时拉一次 Bridge 默认目录
    LaunchedEffect(Unit) {
        if (newInitialDir.isBlank()) {
            dirLoadError = null
            val resp = ApiClient.listCcDir(userId, null)
            if (resp.success && resp.path.isNotBlank()) {
                newInitialDir = resp.path
            } else {
                dirLoadError = resp.error ?: "无法获取默认目录"
            }
        }
    }

    val canCreate = !submitting && dirLoadError == null &&
                    newName.isNotBlank() && newInitialDir.isNotBlank()

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
                Text(
                    "工作目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = SilkColors.textSecondary,
                )
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
                        enabled = dirLoadError == null,
                        placeholder = {
                            Text(
                                if (dirLoadError != null) "Bridge 未连接，请先启动 Bridge"
                                else "加载默认目录中…",
                            )
                        },
                    )
                    TextButton(
                        onClick = { showFolderPicker = true },
                        enabled = dirLoadError == null,
                    ) { Text("📂 选择") }
                }
                if (dirLoadError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "⚠ $dirLoadError。请先启动 Bridge Agent 再创建工作流。",
                        style = MaterialTheme.typography.bodySmall,
                        color = SilkColors.error,
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
                            performCreate(userId, name, initDir, onCreated) { msg ->
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
                            performCreate(userId, newName.trim(), newInitialDir.trim(), onCreated) { msg ->
                                errorMessage = msg
                            }
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
    onCreated: (WorkflowItem) -> Unit,
    onError: (String) -> Unit,
) {
    when (val r = ApiClient.createWorkflow(name, "", userId, initialDir)) {
        is CreateWorkflowResult.Ok -> onCreated(r.workflow)
        is CreateWorkflowResult.Err -> onError("创建工作流失败：${r.message}")
    }
}
