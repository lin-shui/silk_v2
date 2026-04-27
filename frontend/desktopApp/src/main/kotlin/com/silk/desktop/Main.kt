package com.silk.desktop

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Sms
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
    ) {
        // 发送者名称和时间
        if (!isCurrentUser) {
            Text(
                text = "${message.userName} · $timeString",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        
        // 消息气泡（带右键菜单和文本选择）
        MessageWithContextMenu(
            content = {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isCurrentUser) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.widthIn(max = 600.dp)
                ) {
                    when {
                        message.type == MessageType.FILE -> FileDownloadMessage(message, scope)
                        pdfReportContent != null -> PDFDownloadMessage(pdfReportContent, scope)
                        else -> {
                            Column(modifier = Modifier.padding(12.dp)) {
                                // 临时消息标识
                                if (isTransient) {
                                    Text(
                                        text = "AI 处理中...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                
                                // 进度条（如果有）
                                ProgressIndicator(
                                    currentStep = message.currentStep,
                                    totalSteps = message.totalSteps
                                )
                                if (message.currentStep != null && message.totalSteps != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                
                                // 检查是否包含诊断按钮提示，如果有则高亮图标
                                if (message.content.contains("医院按钮") || message.content.contains("Silk按钮")) {
                                    val content = message.content
                                    
                                    // 高亮🏥和🤖图标
                                    Text(
                                        text = buildAnnotatedString {
                                            var i = 0
                                            while (i < content.length) {
                                                val remaining = content.substring(i)
                                                
                                                when {
                                                    remaining.startsWith("🏥") -> {
                                                        withStyle(
                                                            style = SpanStyle(
                                                                color = MaterialTheme.colorScheme.tertiary,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        ) {
                                                            append("🏥")
                                                        }
                                                        i += "🏥".length
                                                    }
                                                    remaining.startsWith("🤖") -> {
                                                        withStyle(
                                                            style = SpanStyle(
                                                                color = MaterialTheme.colorScheme.secondary,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        ) {
                                                            append("🤖")
                                                        }
                                                        i += "🤖".length
                                                    }
                                                    else -> {
                                                        append(content[i])
                                                        i++
                                                    }
                                                }
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                } else {
                                    Text(
                                        text = message.content,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            },
            message = message,
            onCopy = { println("✅ 消息已复制") },
            onForwardToWeChat = { println("✅ 已转发到微信") },
            onForwardToSMS = { println("✅ 已转发到SMS") }
        )
        
        // 当前用户的时间显示在右侧
        if (isCurrentUser) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = timeString,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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

/**
 * 打开或下载远程文件
 */
suspend fun downloadRemoteFile(
    downloadUrl: String,
    defaultFileName: String,
    saveDialogTitle: String,
    requiredExtension: String? = null
): DownloadResult {
    return try {
        // 构建完整的下载URL
        val fullUrl = "${BuildConfig.BACKEND_BASE_URL}$downloadUrl"
        
        println("📋 开始从服务器下载PDF文件:")
        println("   下载URL: $fullUrl")
        println("   文件名: $defaultFileName")
        
        // 从服务器下载PDF到临时文件
        val tempFile = withContext(Dispatchers.IO) {
            val tempSuffix = defaultFileName.substringAfterLast('.', "bin")
                .takeIf { it.isNotBlank() }
                ?.let { ".$it" }
                ?: ".bin"
            val tempFile = java.io.File.createTempFile("silk_download_", tempSuffix)
            
            println("⏳ 正在从服务器下载...")
            
            try {
                val url = java.net.URL(fullUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                
                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    println("✅ 文件下载完成，大小: ${tempFile.length()} bytes")
                    tempFile
                } else {
                    println("❌ 服务器返回错误: HTTP $responseCode")
                    null
                }
            } catch (e: Exception) {
                println("❌ 下载失败: ${e.message}")
                e.printStackTrace()
                null
            }
        }
        
        if (tempFile == null || !tempFile.exists() || tempFile.length() == 0L) {
            println("❌ 文件下载失败或文件为空")
            return DownloadResult.FAILED
        }
        
        println("✅ 文件准备就绪，大小: ${tempFile.length()} bytes")
        
        // 在主线程显示文件选择器
        // 使用 AWT FileDialog（macOS 原生对话框）而不是 JFileChooser
        // 原生对话框对中文字符的显示支持更好
        val selectedFile = withContext(Dispatchers.Main) {
            val fileDialog = java.awt.FileDialog(null as java.awt.Frame?, saveDialogTitle, java.awt.FileDialog.SAVE)
            
            // 设置默认目录和文件名
            val downloadsDir = java.io.File(System.getProperty("user.home"), "Downloads")
            fileDialog.directory = downloadsDir.absolutePath
            fileDialog.file = defaultFileName
            
            // 显示对话框
            fileDialog.isVisible = true
            
            // 获取用户选择的文件
            if (fileDialog.file != null) {
                java.io.File(fileDialog.directory, fileDialog.file)
            } else {
                null
            }
        }
        
        if (selectedFile != null) {
            // 用户选择了保存位置，在 IO 线程复制文件
            try {
                withContext(Dispatchers.IO) {
                    val finalFile = java.io.File(
                        resolveDownloadTargetFileName(
                            selectedFilePath = selectedFile.absolutePath,
                            defaultFileName = defaultFileName,
                            requiredExtension = requiredExtension
                        )
                    )
                    
                    // 复制临时文件到目标位置
                    Files.copy(
                        tempFile.toPath(),
                        finalFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    
                    // 删除临时文件
                    tempFile.delete()
                    
                    println("✅ PDF 已保存到: ${finalFile.absolutePath}")
                }
                DownloadResult.SUCCESS
            } catch (e: Exception) {
                println("❌ 文件复制失败: ${e.message}")
                e.printStackTrace()
                // 清理临时文件
                try {
                    tempFile.delete()
                } catch (e2: Exception) {
                    // 忽略
                }
                DownloadResult.FAILED
            }
        } else {
            // 用户点击了取消，清理临时文件
            println("ℹ️ 用户取消了保存")
            try {
                tempFile.delete()
            } catch (e: Exception) {
                // 忽略
            }
            DownloadResult.CANCELLED
        }
    } catch (e: Exception) {
        println("❌ 操作失败: ${e.message}")
        e.printStackTrace()
        DownloadResult.FAILED
    }
}
