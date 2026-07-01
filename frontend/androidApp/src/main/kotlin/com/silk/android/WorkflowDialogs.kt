package com.silk.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.silk.shared.models.DirListingResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 通用错误提示对话框（Snackbar 在工作流场景下时机不好，
 * 改用 AlertDialog 直接打断用户）。
 */
@Composable
fun WorkflowErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提示") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好的") } },
    )
}

/**
 * 信任目录确认弹窗，与 Web 文案对齐。
 * 用户授权后该目录及子目录在该 bridge 上不再询问。
 */
@Composable
fun TrustConfirmDialog(
    path: String,
    bridgeId: String?,
    onDismiss: () -> Unit,
    onTrust: () -> Unit,
) {
    val bridgeLabel = bridgeId ?: "未知机器"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⚠️  信任目录确认") },
        text = {
            Column {
                Text("您选择了工作目录：")
                Spacer(Modifier.height(4.dp))
                Text(path, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("Bridge 机器：$bridgeLabel")
                Spacer(Modifier.height(12.dp))
                Text("是否信任并授权该目录及其子目录的读写执行权限？")
                Spacer(Modifier.height(4.dp))
                Text(
                    "信任后，下次在该机器上选择此目录或其子目录时将不再询问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = SilkColors.textLight,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("信任并授权") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 把 segments[0..upToIndex] 拼成路径。语义对齐 Web 端 buildBreadcrumbPath。
 * - Unix: head=="/" → "/" + 中段 join "/"
 * - Windows: head=="C:\\" → head + 中段 join "\\"
 */
internal fun buildBreadcrumbPath(segments: List<String>, upToIndex: Int, separator: String): String {
    if (segments.isEmpty() || upToIndex < 0) return separator
    val head = segments[0]
    if (upToIndex == 0) return head
    val tail = segments.subList(1, upToIndex + 1)
    val joined = tail.joinToString(separator)
    return if (head.endsWith(separator)) head + joined else head + separator + joined
}

/**
 * 拼接 parent 和 child，使用后端提供的 [separator]；parent 已带分隔符时不再补。
 */
internal fun joinPath(parent: String, child: String, separator: String): String {
    if (parent.isEmpty()) return child
    return if (parent.endsWith(separator)) parent + child else parent + separator + child
}

@Suppress("CyclomaticComplexMethod")
@Composable
fun FolderPickerDialog(
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
    var loadJob by remember { mutableStateOf<Job?>(null) }

    fun requestLoad(path: String?) {
        loadJob?.cancel()
        loadJob = scope.launch {
            loading = true
            errorMsg = null
            val resp = ApiClient.listCcDir(userId, path)
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
    DisposableEffect(Unit) { onDispose { loadJob?.cancel() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            color = SilkColors.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                FolderPickerHeader(onDismiss = onDismiss)
                Divider(color = SilkColors.divider)

                val current = listing
                FolderPickerBreadcrumb(
                    listing = current,
                    onNavigate = { path -> requestLoad(path) },
                )

                FolderPickerListingArea(
                    listing = current,
                    loading = loading,
                    errorMsg = errorMsg,
                    onNavigate = { path -> requestLoad(path) },
                )
                Divider(color = SilkColors.divider)

                FolderPickerFooter(
                    manualInput = manualInput,
                    currentPath = listing?.path,
                    canConfirm = listing?.success == true,
                    onManualInputChange = { manualInput = it },
                    onDismiss = onDismiss,
                    onConfirm = {
                        val path = listing?.path
                        if (!path.isNullOrBlank()) onConfirm(path)
                    },
                    onJump = { requestLoad(manualInput.trim()) },
                )
            }
        }
    }
}

@Composable
private fun FolderPickerHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("选择工作目录", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onDismiss) { Text("×") }
    }
}

@Composable
private fun FolderPickerBreadcrumb(
    listing: DirListingResponse?,
    onNavigate: (String?) -> Unit,
) {
    if (listing == null) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listing.segments.forEachIndexed { idx, seg ->
            val isLast = idx == listing.segments.lastIndex
            Text(
                text = if (isLast) seg else "$seg ›",
                style = MaterialTheme.typography.bodySmall,
                color = if (isLast) SilkColors.textPrimary else SilkColors.primary,
                modifier = if (isLast) {
                    Modifier
                } else {
                    Modifier.clickable {
                        onNavigate(buildBreadcrumbPath(listing.segments, idx, listing.separator))
                    }
                },
            )
        }
    }
    Divider(color = SilkColors.divider)
}

@Composable
private fun FolderPickerListingArea(
    listing: DirListingResponse?,
    loading: Boolean,
    errorMsg: String?,
    onNavigate: (String?) -> Unit,
) {
    Box(modifier = Modifier.heightIn(min = 200.dp, max = 360.dp).fillMaxWidth()) {
        when {
            loading -> FolderPickerLoadingState()
            errorMsg != null -> FolderPickerErrorState(errorMsg = errorMsg)
            listing != null -> FolderPickerEntriesList(
                listing = listing,
                onNavigate = onNavigate,
            )
        }
    }
}

@Composable
private fun FolderPickerLoadingState() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FolderPickerErrorState(errorMsg: String) {
    Text(
        "⚠ $errorMsg",
        color = SilkColors.error,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun FolderPickerEntriesList(
    listing: DirListingResponse,
    onNavigate: (String?) -> Unit,
) {
    LazyColumn {
        if (listing.parent != null) {
            item {
                FolderRow(name = "..", subtle = true) {
                    onNavigate(listing.parent)
                }
            }
        }
        items(listing.entries, key = { it.name }) { entry ->
            FolderRow(name = entry.name) {
                onNavigate(joinPath(listing.path, entry.name, listing.separator))
            }
        }
        if (listing.entries.isEmpty() && listing.parent == null) {
            item {
                Text(
                    "此目录下无子文件夹",
                    color = SilkColors.textSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (listing.truncated) {
            item {
                Text(
                    "目录项过多，仅显示前 500 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = SilkColors.textLight,
                    modifier = Modifier.padding(8.dp, 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderPickerFooter(
    manualInput: String,
    currentPath: String?,
    canConfirm: Boolean,
    onManualInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onJump: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = manualInput,
            onValueChange = onManualInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("路径") },
            singleLine = true,
        )
        TextButton(onClick = onDismiss) { Text("取消") }
        Button(
            onClick = onConfirm,
            enabled = canConfirm,
            colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
        ) { Text("选择此目录") }
    }
    if (manualInput.isNotBlank() && manualInput != currentPath) {
        TextButton(
            onClick = onJump,
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("跳到 $manualInput") }
    }
}

@Composable
private fun FolderRow(name: String, subtle: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (subtle) "↩" else "📁")
        Text(
            name,
            color = if (subtle) SilkColors.textSecondary else SilkColors.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal fun permModeLabel(mode: String): String = when (mode) {
    "ACCEPT_EDITS" -> "Accept Edits"
    "BYPASS" -> "Bypass"
    else -> "Interactive"
}

/**
 * 会话设置弹窗：工作目录 / Agent / 权限模式三合一。
 */
@Suppress("CyclomaticComplexMethod")
@Composable
fun WorkflowSettingsDialog(
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
    var agentDropdownExpanded by remember { mutableStateOf(false) }
    var permDropdownExpanded by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showTrustConfirm by remember { mutableStateOf(false) }
    var trustConfirmPath by remember { mutableStateOf("") }
    var trustConfirmBridgeId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val agents = ApiClient.listAgents(userId)
        availableAgents = agents
        val snap = ApiClient.getCcState(userId, groupId)
        if (snap.success && snap.agentType.isNotBlank()) {
            selectedAgentType = snap.agentType.replace('-', '_')
        }
    }

    val permModeLabel = { mode: String ->
        when (mode) {
            "ACCEPT_EDITS" -> "Accept Edits"
            "BYPASS" -> "Bypass"
            "INTERACTIVE" -> "Interactive"
            else -> "Interactive"
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
            // 1. 目录变化
            if (resultDir.isNotBlank() && resultDir != currentWorkingDir) {
                when (val tc = ApiClient.checkTrustedDir(userId, resultDir)) {
                    is TrustCheckResult.BridgeDisconnected -> { errorMsg = "Bridge 未连接，无法切换目录。"; return }
                    is TrustCheckResult.NotTrusted -> {
                        trustConfirmPath = resultDir
                        trustConfirmBridgeId = tc.bridgeId
                        showTrustConfirm = true
                        return
                    }
                    is TrustCheckResult.Error -> { errorMsg = "检查信任状态失败：${tc.message}"; return }
                    is TrustCheckResult.Trusted -> {}
                }
                val cdResp = ApiClient.cdCcDir(userId, groupId, resultDir)
                if (!cdResp.success) { errorMsg = "切换目录失败：${cdResp.error ?: "未知错误"}"; return }
                newDir = cdResp.workingDir
            }

            // 2. Agent / 权限模式变化
            val currentAgentUnderscore = ApiClient.getCcState(userId, groupId).agentType.replace('-', '_')
            val agentChanged = selectedAgentType.isNotBlank() && selectedAgentType != currentAgentUnderscore
            val permChanged = selectedPermMode != currentPermissionMode
            if (agentChanged || permChanged) {
                val resp = ApiClient.updateCcSettings(
                    userId, groupId,
                    activeAgent = if (agentChanged) selectedAgentType else null,
                    permissionMode = if (permChanged) selectedPermMode.ifBlank { "INTERACTIVE" } else null,
                )
                if (!resp.success) { errorMsg = "更新设置失败：${resp.error ?: "未知错误"}"; return }
                newAgentDisplay = resp.agentDisplayName
                newPermMode = resp.permissionMode
                if (newDir == currentWorkingDir) newDir = resp.workingDir
            }
            onApplied(newDir, newAgentDisplay, newPermMode)
        } finally {
            saving = false
        }
    }

    suspend fun applyAfterTrust() {
        saving = true
        errorMsg = null
        try {
            val added = ApiClient.addTrustedDir(userId, trustConfirmPath)
            if (!added) { errorMsg = "添加信任记录失败，请重试。"; return }
            showTrustConfirm = false
            applyChanges()
        } finally {
            saving = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("会话设置") },
        text = {
            Column {
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
                        listOf(
                            "" to "Interactive",
                            "ACCEPT_EDITS" to "Accept Edits",
                            "BYPASS" to "Bypass",
                        ).forEach { (value, label) ->
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
                        value = editDir,
                        onValueChange = { editDir = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("工作目录路径") },
                    )
                    TextButton(onClick = { showFolderPicker = true }) { Text("📂 选择") }
                }

                errorMsg?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = SilkColors.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { scope.launch { applyChanges() } },
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )

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
