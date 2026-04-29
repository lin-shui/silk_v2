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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
                // Title row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("选择工作目录", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("×") }
                }
                Divider(color = SilkColors.divider)

                // Breadcrumb
                val current = listing
                if (current != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        current.segments.forEachIndexed { idx, seg ->
                            val isLast = idx == current.segments.size - 1
                            Text(
                                text = if (isLast) seg else "$seg ›",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isLast) SilkColors.textPrimary else SilkColors.primary,
                                modifier = if (!isLast) {
                                    Modifier.clickable {
                                        val target = buildBreadcrumbPath(current.segments, idx, current.separator)
                                        requestLoad(target)
                                    }
                                } else Modifier,
                            )
                        }
                    }
                    Divider(color = SilkColors.divider)
                }

                // Listing area
                Box(modifier = Modifier.heightIn(min = 200.dp, max = 360.dp).fillMaxWidth()) {
                    when {
                        loading -> Box(
                            modifier = Modifier.fillMaxWidth().padding(40.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }

                        errorMsg != null -> Text(
                            "⚠ $errorMsg",
                            color = SilkColors.error,
                            modifier = Modifier.padding(16.dp),
                        )

                        current != null -> LazyColumn {
                            if (current.parent != null) {
                                item {
                                    FolderRow(name = "..", subtle = true) {
                                        requestLoad(current.parent)
                                    }
                                }
                            }
                            items(current.entries, key = { it.name }) { entry ->
                                FolderRow(name = entry.name) {
                                    val next = joinPath(current.path, entry.name, current.separator)
                                    requestLoad(next)
                                }
                            }
                            if (current.entries.isEmpty() && current.parent == null) {
                                item {
                                    Text(
                                        "此目录下无子文件夹",
                                        color = SilkColors.textSecondary,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                            if (current.truncated) {
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
                }
                Divider(color = SilkColors.divider)

                // Bottom: input + actions
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("路径") },
                        singleLine = true,
                    )
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = {
                            val path = listing?.path
                            if (!path.isNullOrBlank()) onConfirm(path)
                        },
                        enabled = listing?.success == true,
                        colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
                    ) { Text("选择此目录") }
                }
                // Manual jump button (Android replacement for web's Enter-key handling)
                if (manualInput.isNotBlank() && manualInput != listing?.path) {
                    TextButton(
                        onClick = { requestLoad(manualInput.trim()) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("跳到 $manualInput") }
                }
            }
        }
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
