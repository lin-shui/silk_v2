package com.silk.desktop

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
import com.silk.shared.models.Message
import com.silk.shared.models.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.HeadlessException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Silk",
        state = rememberWindowState(size = DpSize(900.dp, 700.dp))
    ) {
        MaterialTheme {
            SilkApp()
        }
    }
}

@Composable
fun SilkApp() {
    val appState = remember { AppState() }
    
    // 启动时重新验证用户
    LaunchedEffect(Unit) {
        if (appState.currentUser != null) {
            println("🔐 重新验证用户...")
            val isValid = appState.revalidateUser()
            if (!isValid) {
                println("❌ 用户验证失败，返回登录界面")
            }
        }
    }
    
    // 显示验证中的加载界面
    if (appState.isValidating) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    text = "正在验证用户信息...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    } else {
        // 根据当前场景显示对应的界面
        when (appState.currentScene) {
            Scene.LOGIN -> LoginScreen(appState)
            Scene.GROUP_LIST -> GroupListScreen(appState)
            Scene.CHAT_ROOM -> ChatScreen(appState)
            Scene.SETTINGS -> SettingsScreen(appState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(appState: AppState) {
    val group = appState.selectedGroup ?: return
    val user = appState.currentUser ?: return
    
    val chatClient = remember { ChatClient(BuildConfig.BACKEND_WS_URL) }
    val messages by chatClient.messages.collectAsState()
    val transientMessage by chatClient.transientMessage.collectAsState()
    val connectionState by chatClient.connectionState.collectAsState()
    val isGenerating by chatClient.isGenerating.collectAsState()
    val scope = rememberCoroutineScope()
    
    // Track if we've sent the default instruction for this session
    var hasSentDefaultInstruction by remember { mutableStateOf(false) }
    
    var messageText by remember { mutableStateOf("") }
    var showInvitationDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    
    LaunchedEffect(group.id) {
        // Reset flag when group changes
        hasSentDefaultInstruction = false
        
        launch {
            chatClient.connect(user.id, user.fullName, group.id)
        }
    }
        
    // reverseLayout=true: index 0 = visual bottom (newest). Scroll to 0 shows latest.
    LaunchedEffect(messages.size, transientMessage) {
        if (messages.isNotEmpty() || transientMessage != null) {
            listState.animateScrollToItem(0)
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            scope.launch {
                chatClient.disconnect()
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(group.name)
                        Text(
                            text = when (connectionState) {
                                ConnectionState.CONNECTED -> "已连接"
                                ConnectionState.CONNECTING -> "连接中..."
                                ConnectionState.DISCONNECTED -> "未连接"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = {
                    // 返回按钮
                    IconButton(onClick = { appState.navigateBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 邀请入群按钮
                    IconButton(onClick = { showInvitationDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "邀请入群",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 状态栏（如果需要）
            if (connectionState == ConnectionState.CONNECTING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            
            // 消息列表 - reverseLayout: newest at visual bottom near input
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    reverseLayout = true
                ) {
                    // reverseLayout: item index 0 appears at the visual bottom
                    transientMessage?.let { message ->
                        item(key = "transient") {
                            MessageBubble(message, scope, user.id, isTransient = true)
                        }
                    }
                    
                    items(messages.reversed(), key = { it.id }) { message ->
                        MessageBubble(message, scope, user.id)
                    }
                }
            }
            
            // 输入框 - single-row: text field + action buttons inline
            Surface(
                shadowElevation = 4.dp
            ) {
                var showDiagMenu by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入消息...") },
                        maxLines = 3,
                        enabled = connectionState == ConnectionState.CONNECTED
                    )
                    
                    // Diagnostic shortcuts in a dropdown
                    Box {
                        IconButton(
                            onClick = { showDiagMenu = true },
                            enabled = connectionState == ConnectionState.CONNECTED
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "快捷诊断")
                        }
                        DropdownMenu(
                            expanded = showDiagMenu,
                            onDismissRequest = { showDiagMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("完整诊断") },
                                leadingIcon = { Icon(Icons.Default.LocalHospital, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    showDiagMenu = false
                                    scope.launch { chatClient.sendMessage(user.id, user.fullName, "@完整诊断") }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("智能诊断") },
                                leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    showDiagMenu = false
                                    scope.launch { chatClient.sendMessage(user.id, user.fullName, "@诊断") }
                                }
                            )
                        }
                    }
                    
                    if (isGenerating) {
                        IconButton(
                            onClick = { chatClient.stopGeneration(user.id, user.fullName) }
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "停止",
                                tint = androidx.compose.ui.graphics.Color(0xFFFF4D4F)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (messageText.isNotBlank()) {
                                    scope.launch {
                                        chatClient.sendMessage(user.id, user.fullName, messageText)
                                        messageText = ""
                                    }
                                }
                            },
                            enabled = connectionState == ConnectionState.CONNECTED && messageText.isNotBlank()
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "发送")
                        }
                    }
                }
            }
        }
    }
    
    // 邀请对话框
    if (showInvitationDialog) {
        InvitationDialog(
            group = group,
            onDismiss = { showInvitationDialog = false }
        )
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
fun MessageBubble(
    message: Message,
    scope: kotlinx.coroutines.CoroutineScope,
    currentUserId: String,
    isTransient: Boolean = false
) {
    val isCurrentUser = message.userId == currentUserId
    val timeString = remember(message.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date(message.timestamp))
    }
    val pdfReportContent = remember(message.content) {
        parseDesktopPdfReportContent(message.content)
    }
    val bubbleColor = if (isCurrentUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
    ) {
        MessageSenderHeader(
            isCurrentUser = isCurrentUser,
            userName = message.userName,
            timeString = timeString
        )
        MessageWithContextMenu(
            content = {
                MessageBubbleSurface(
                    message = message,
                    scope = scope,
                    pdfReportContent = pdfReportContent,
                    bubbleColor = bubbleColor,
                    isTransient = isTransient
                )
            },
            message = message
        )

        MessageSenderFooter(
            isCurrentUser = isCurrentUser,
            timeString = timeString
        )
    }
}

@Composable
private fun MessageSenderHeader(
    isCurrentUser: Boolean,
    userName: String,
    timeString: String
) {
    if (isCurrentUser) {
        return
    }

    Text(
        text = "$userName · $timeString",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun MessageBubbleSurface(
    message: Message,
    scope: kotlinx.coroutines.CoroutineScope,
    pdfReportContent: DesktopPdfReportContent?,
    bubbleColor: androidx.compose.ui.graphics.Color,
    isTransient: Boolean
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bubbleColor,
        modifier = Modifier.widthIn(max = 600.dp)
    ) {
        when {
            message.type == MessageType.FILE -> FileDownloadMessage(message, scope)
            pdfReportContent != null -> PDFDownloadMessage(pdfReportContent, scope)
            else -> PlainMessageBubbleContent(message, isTransient)
        }
    }
}

@Composable
private fun PlainMessageBubbleContent(
    message: Message,
    isTransient: Boolean
) {
    Column(modifier = Modifier.padding(12.dp)) {
        TransientMessageIndicator(isTransient)
        ProgressSection(
            currentStep = message.currentStep,
            totalSteps = message.totalSteps
        )
        DiagnosticAwareMessageText(content = message.content)
    }
}

@Composable
private fun TransientMessageIndicator(isTransient: Boolean) {
    if (!isTransient) {
        return
    }

    Text(
        text = "AI 处理中...",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun ProgressSection(currentStep: Int?, totalSteps: Int?) {
    ProgressIndicator(
        currentStep = currentStep,
        totalSteps = totalSteps
    )
    if (currentStep != null && totalSteps != null) {
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun DiagnosticAwareMessageText(content: String) {
    if (!containsDiagnosticButtons(content)) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val annotatedContent = remember(content, tertiaryColor, secondaryColor) {
        buildDiagnosticAnnotatedContent(
            content = content,
            tertiaryColor = tertiaryColor,
            secondaryColor = secondaryColor
        )
    }
    Text(
        text = annotatedContent,
        style = MaterialTheme.typography.bodyMedium
    )
}

private fun containsDiagnosticButtons(content: String): Boolean {
    return content.contains("医院按钮") || content.contains("Silk按钮")
}

private fun buildDiagnosticAnnotatedContent(
    content: String,
    tertiaryColor: androidx.compose.ui.graphics.Color,
    secondaryColor: androidx.compose.ui.graphics.Color
) = buildAnnotatedString {
    var index = 0
    while (index < content.length) {
        val remaining = content.substring(index)
        when {
            remaining.startsWith("🏥") -> {
                withStyle(
                    style = SpanStyle(
                        color = tertiaryColor,
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append("🏥")
                }
                index += "🏥".length
            }
            remaining.startsWith("🤖") -> {
                withStyle(
                    style = SpanStyle(
                        color = secondaryColor,
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append("🤖")
                }
                index += "🤖".length
            }
            else -> {
                append(content[index])
                index++
            }
        }
    }
}

@Composable
private fun MessageSenderFooter(
    isCurrentUser: Boolean,
    timeString: String
) {
    if (!isCurrentUser) {
        return
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = timeString,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun ProgressIndicator(currentStep: Int?, totalSteps: Int?) {
    if (currentStep == null || totalSteps == null) return
    
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "步骤 $currentStep/$totalSteps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "处理中...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = (currentStep.toFloat() / totalSteps),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun PDFDownloadMessage(pdfReport: DesktopPdfReportContent, scope: kotlinx.coroutines.CoroutineScope) {
    RemoteDownloadMessage(
        title = "📄 PDF 诊断报告已生成",
        fileName = pdfReport.fileName,
        downloadUrl = pdfReport.downloadUrl,
        scope = scope,
        buttonText = "📥 打开/下载诊断报告",
        saveDialogTitle = "保存诊断报告到...",
        requiredExtension = "pdf"
    )
}

@Composable
fun FileDownloadMessage(message: Message, scope: kotlinx.coroutines.CoroutineScope) {
    val fileContent = remember(message.content) {
        parseDesktopFileMessageContent(message.content)
    }

    RemoteDownloadMessage(
        title = "📎 文件消息",
        fileName = fileContent.fileName,
        downloadUrl = fileContent.downloadUrl,
        fileSize = fileContent.fileSize.takeIf { it > 0L },
        scope = scope,
        buttonText = "📥 下载文件",
        saveDialogTitle = "保存文件到..."
    )
}

@Composable
fun RemoteDownloadMessage(
    title: String,
    fileName: String,
    downloadUrl: String,
    scope: kotlinx.coroutines.CoroutineScope,
    buttonText: String,
    saveDialogTitle: String,
    fileSize: Long? = null,
    requiredExtension: String? = null
) {
    var isDownloading by remember { mutableStateOf(false) }
    var downloadStatus by remember { mutableStateOf("") }
    val detailText = buildString {
        append("文件名：$fileName")
        fileSize?.let { append("\n大小：${formatDesktopFileSize(it)}") }
    }

    Column(modifier = Modifier.padding(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = desktopFileIconForName(fileName),
                fontSize = 32.sp
            )
            Text(
                text = detailText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                scope.launch {
                    isDownloading = true
                    downloadStatus = "正在从服务器下载..."

                    val result = downloadRemoteFile(
                        downloadUrl = downloadUrl,
                        defaultFileName = fileName,
                        saveDialogTitle = saveDialogTitle,
                        requiredExtension = requiredExtension
                    )

                    isDownloading = false
                    downloadStatus = when (result) {
                        DownloadResult.SUCCESS -> "✅ 下载成功！"
                        DownloadResult.CANCELLED -> "ℹ️ 下载已取消"
                        DownloadResult.FAILED -> "❌ 下载失败，请检查网络连接"
                    }
                }
            },
            enabled = !isDownloading && downloadUrl.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "下载",
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isDownloading) "处理中..." else buttonText,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (downloadStatus.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = downloadStatus,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    downloadStatus.contains("✅") -> MaterialTheme.colorScheme.primary
                    downloadStatus.contains("ℹ️") -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

/**
 * 下载结果枚举
 */
enum class DownloadResult {
    SUCCESS,    // 成功
    CANCELLED,  // 用户取消
    FAILED      // 失败
}

private sealed interface SaveTargetSelection {
    data class Selected(val file: File) : SaveTargetSelection
    data object Cancelled : SaveTargetSelection
    data object Failed : SaveTargetSelection
}

/**
 * 打开或下载远程文件
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException", "NestedBlockDepth", "CyclomaticComplexMethod", "PrintStackTrace")
suspend fun downloadRemoteFile(
    downloadUrl: String,
    defaultFileName: String,
    saveDialogTitle: String,
    requiredExtension: String? = null
): DownloadResult {
    val fullUrl = "${BuildConfig.BACKEND_BASE_URL}$downloadUrl"

    println("📋 开始从服务器下载PDF文件:")
    println("   下载URL: $fullUrl")
    println("   文件名: $defaultFileName")

    val tempFile = downloadRemoteFileToTemp(fullUrl, defaultFileName) ?: return DownloadResult.FAILED
    if (!tempFile.exists() || tempFile.length() == 0L) {
        println("❌ 文件下载失败或文件为空")
        deleteTempFile(tempFile, "空下载文件")
        return DownloadResult.FAILED
    }

    println("✅ 文件准备就绪，大小: ${tempFile.length()} bytes")

    return when (val selection = selectDownloadTarget(defaultFileName, saveDialogTitle)) {
        is SaveTargetSelection.Selected -> {
            val saved = saveDownloadedFile(
                tempFile = tempFile,
                selectedFile = selection.file,
                defaultFileName = defaultFileName,
                requiredExtension = requiredExtension
            )
            deleteTempFile(tempFile, "保存后清理临时文件")
            if (saved) DownloadResult.SUCCESS else DownloadResult.FAILED
        }
        SaveTargetSelection.Cancelled -> {
            println("ℹ️ 用户取消了保存")
            deleteTempFile(tempFile, "取消保存后清理临时文件")
            DownloadResult.CANCELLED
        }
        SaveTargetSelection.Failed -> {
            deleteTempFile(tempFile, "保存对话框失败后清理临时文件")
            DownloadResult.FAILED
        }
    }
}

private suspend fun downloadRemoteFileToTemp(fullUrl: String, defaultFileName: String): File? = withContext(Dispatchers.IO) {
    val tempFile = createTempDownloadFile(defaultFileName) ?: return@withContext null
    println("⏳ 正在从服务器下载...")

    var connection: HttpURLConnection? = null
    try {
        connection = openDownloadConnection(fullUrl)
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            println("❌ 服务器返回错误: HTTP $responseCode")
            deleteTempFile(tempFile, "HTTP 下载失败")
            return@withContext null
        }

        connection.inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        println("✅ 文件下载完成，大小: ${tempFile.length()} bytes")
        tempFile
    } catch (e: IOException) {
        println("❌ 下载失败: ${e.message}")
        deleteTempFile(tempFile, "下载失败")
        null
    } catch (e: SecurityException) {
        println("❌ 下载失败: ${e.message}")
        deleteTempFile(tempFile, "下载失败")
        null
    } catch (e: ClassCastException) {
        println("❌ 下载失败: ${e.message}")
        deleteTempFile(tempFile, "下载失败")
        null
    } finally {
        connection?.disconnect()
    }
}

private fun createTempDownloadFile(defaultFileName: String): File? {
    val tempSuffix = defaultFileName.substringAfterLast('.', "bin")
        .takeIf { it.isNotBlank() }
        ?.let { ".$it" }
        ?: ".bin"

    return try {
        File.createTempFile("silk_download_", tempSuffix)
    } catch (e: IOException) {
        println("❌ 创建临时文件失败: ${e.message}")
        null
    } catch (e: SecurityException) {
        println("❌ 创建临时文件失败: ${e.message}")
        null
    }
}

private fun openDownloadConnection(fullUrl: String): HttpURLConnection {
    return (URL(fullUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 30_000
    }
}

private suspend fun selectDownloadTarget(
    defaultFileName: String,
    saveDialogTitle: String
): SaveTargetSelection = withContext(Dispatchers.Main) {
    try {
        val fileDialog = FileDialog(null as Frame?, saveDialogTitle, FileDialog.SAVE)
        val downloadsDir = File(System.getProperty("user.home"), "Downloads")
        fileDialog.directory = downloadsDir.absolutePath
        fileDialog.file = defaultFileName
        fileDialog.isVisible = true

        val selectedName = fileDialog.file
        if (selectedName.isNullOrBlank()) {
            SaveTargetSelection.Cancelled
        } else {
            val selectedDirectory = fileDialog.directory
            val selectedPath = if (selectedDirectory.isNullOrBlank()) {
                selectedName
            } else {
                File(selectedDirectory, selectedName).absolutePath
            }
            SaveTargetSelection.Selected(File(selectedPath))
        }
    } catch (e: HeadlessException) {
        println("❌ 无法打开保存对话框: ${e.message}")
        SaveTargetSelection.Failed
    } catch (e: SecurityException) {
        println("❌ 无法打开保存对话框: ${e.message}")
        SaveTargetSelection.Failed
    }
}

private suspend fun saveDownloadedFile(
    tempFile: File,
    selectedFile: File,
    defaultFileName: String,
    requiredExtension: String?
): Boolean = withContext(Dispatchers.IO) {
    val finalFile = File(
        resolveDownloadTargetFileName(
            selectedFilePath = selectedFile.absolutePath,
            defaultFileName = defaultFileName,
            requiredExtension = requiredExtension
        )
    )

    try {
        Files.copy(
            tempFile.toPath(),
            finalFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        println("✅ PDF 已保存到: ${finalFile.absolutePath}")
        true
    } catch (e: IOException) {
        println("❌ 文件复制失败: ${e.message}")
        false
    } catch (e: SecurityException) {
        println("❌ 文件复制失败: ${e.message}")
        false
    }
}

private fun deleteTempFile(file: File, reason: String) {
    if (!file.exists()) {
        return
    }

    try {
        if (!file.delete()) {
            println("⚠️ 未能删除临时文件（$reason）: ${file.absolutePath}")
        }
    } catch (e: SecurityException) {
        println("⚠️ 未能删除临时文件（$reason）: ${e.message}")
    }
}
