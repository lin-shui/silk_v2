package com.silk.android

import android.content.Intent
import android.net.Uri
import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
import com.silk.shared.models.Message
import com.silk.shared.models.MessageCategory
import com.silk.shared.models.MessageType
import com.silk.shared.models.isAgentUserId
import com.silk.shared.models.SILK_AGENT_USER_ID
import com.silk.shared.models.SILK_AGENT_DISPLAY_NAME
import com.silk.shared.utils.formatMessageTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

// Web版的 collectAsState 实现
@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsState(): State<T> {
    val state = remember { mutableStateOf(value) }
    LaunchedEffect(this) {
        collect { state.value = it }
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(appState: AppState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val user = appState.currentUser ?: return
    val group = appState.selectedGroup ?: return

    val baseUrl = BackendUrlHolder.getBaseUrl()
    val wsUrl = baseUrl.replaceFirst("http", "ws")
    val debugLogs = remember { mutableStateListOf<String>() }

    fun addLog(message: String) {
        debugLogs.add(message)
        while (debugLogs.size > 32) {
            debugLogs.removeAt(0)
        }
        println(message)
    }

    val chatClient = remember {
        addLog("🔧 创建 ChatClient，URL: $wsUrl")
        ChatClient(wsUrl) { logMessage ->
            addLog(logMessage)
        }
    }

    val messages by chatClient.messages.collectAsState()
    val transientMessage by chatClient.transientMessage.collectAsState()
    val statusMessages by chatClient.statusMessages.collectAsState()
    val connectionState by chatClient.connectionState.collectAsState()
    val isGenerating by chatClient.isGenerating.collectAsState()

    var hasSentDefaultInstruction by remember { mutableStateOf(false) }
    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var showInvitationDialog by remember { mutableStateOf(false) }
    var isExiting by remember { mutableStateOf(false) }
    var showDebugInfo by remember { mutableStateOf(false) }
    var isWaitingForAI by remember { mutableStateOf(false) }

    var isVoiceRecording by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }
    val mediaRecorderRef = remember { mutableStateOf<MediaRecorder?>(null) }
    val audioFilePathRef = remember { mutableStateOf("") }
    val micPermissionGranted = remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    ) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micPermissionGranted.value = granted }

    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf("") }
    var showFolderExplorer by remember { mutableStateOf(false) }
    var folderFiles by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var processedUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }

    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedMessages = remember { mutableStateListOf<String>() }
    var showForwardToGroupDialog by remember { mutableStateOf(false) }
    var showForwardToContactDialog by remember { mutableStateOf(false) }
    var userGroups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoadingGroups by remember { mutableStateOf(false) }
    var forwardResult by remember { mutableStateOf<String?>(null) }
    var messageToForward by remember { mutableStateOf<Message?>(null) }

    var showAddContactConfirm by remember { mutableStateOf<Message?>(null) }
    var isAddingContact by remember { mutableStateOf(false) }
    var addContactResult by remember { mutableStateOf<String?>(null) }

    var showAddMemberDialog by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var groupMembers by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var isLoadingContacts by remember { mutableStateOf(false) }
    var addMemberResult by remember { mutableStateOf<String?>(null) }

    var recallingMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showMembersDialog by remember { mutableStateOf(false) }
    var selectedMemberForInvite by remember { mutableStateOf<GroupMember?>(null) }
    var isInvitingMember by remember { mutableStateOf(false) }
    var inviteMemberResult by remember { mutableStateOf<String?>(null) }

    var showMentionMenu by remember { mutableStateOf(false) }
    var mentionSearchText by remember { mutableStateOf("") }
    var mentionStartIndex by remember { mutableStateOf(-1) }

    val filePickerLauncher = rememberChatFilePickerLauncher(
        context = context,
        group = group,
        user = user,
        scope = scope,
        chatClient = chatClient,
        addLog = ::addLog,
        onUploadingChange = { isUploading = it },
        onUploadProgressChange = { uploadProgress = it },
    )

    val sessionUsers = rememberChatSessionUsers(messages, groupMembers, user)
    val listState = rememberLazyListState()
    val aiMessageExpandedStates = remember { mutableStateMapOf<String, Boolean>() }
    val thinkingExpandedStates = remember { mutableStateMapOf<String, Boolean>() }
    var expandScrollJob by remember { mutableStateOf<Job?>(null) }
    val isHistoryLoading by chatClient.isLoadingHistory.collectAsState()

    ChatScreenEffects(
        user = user,
        group = group,
        wsUrl = wsUrl,
        baseUrl = baseUrl,
        chatClient = chatClient,
        messages = messages,
        transientMessage = transientMessage,
        connectionState = connectionState,
        isWaitingForAI = isWaitingForAI,
        isHistoryLoading = isHistoryLoading,
        listState = listState,
        addLog = ::addLog,
        onGroupMembersLoaded = { groupMembers = it },
        onHasSentDefaultInstructionChange = { hasSentDefaultInstruction = it },
        onWaitingForAiChange = { isWaitingForAI = it },
    )

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    ChatScreenScaffoldContent(
        appState = appState,
        user = user,
        group = group,
        context = context,
        chatClient = chatClient,
        scrollBehavior = scrollBehavior,
        messages = messages,
        transientMessage = transientMessage,
        statusMessages = statusMessages,
        connectionState = connectionState,
        isHistoryLoading = isHistoryLoading,
        isGenerating = isGenerating,
        isWaitingForAI = isWaitingForAI,
        isSelectionMode = isSelectionMode,
        selectedMessages = selectedMessages,
        isExiting = isExiting,
        isUploading = isUploading,
        uploadProgress = uploadProgress,
        messageText = messageText,
        listState = listState,
        aiMessageExpandedStates = aiMessageExpandedStates,
        thinkingExpandedStates = thinkingExpandedStates,
        expandScrollJob = expandScrollJob,
        sessionUsers = sessionUsers,
        showMentionMenu = showMentionMenu,
        mentionSearchText = mentionSearchText,
        mentionStartIndex = mentionStartIndex,
        isVoiceRecording = isVoiceRecording,
        isTranscribing = isTranscribing,
        micPermissionGranted = micPermissionGranted.value,
        recallingMessageIds = recallingMessageIds,
        filePickerLauncher = filePickerLauncher,
        onExitingChange = { isExiting = it },
        onSelectionModeChange = { isSelectionMode = it },
        onClearSelectedMessages = { selectedMessages.clear() },
        onMessageSelected = { messageId ->
            if (selectedMessages.contains(messageId)) selectedMessages.remove(messageId) else selectedMessages.add(messageId)
        },
        onWaitingForAiChange = { isWaitingForAI = it },
        onShowForwardToGroupDialog = { showForwardToGroupDialog = it },
        onShowForwardToContactDialog = { showForwardToContactDialog = it },
        onShowInvitationDialog = { showInvitationDialog = it },
        onShowFolderExplorer = { showFolderExplorer = it },
        onFolderFilesLoaded = { files, urls ->
            folderFiles = files
            processedUrls = urls
        },
        onLoadingFilesChange = { isLoadingFiles = it },
        onShowAddMemberDialog = { showAddMemberDialog = it },
        onShowMembersDialog = { showMembersDialog = it },
        onContactsLoaded = { contacts = it },
        onGroupMembersLoaded = { groupMembers = it },
        onMessageTextChange = { messageText = it },
        onMentionMenuChange = { showMentionMenu = it },
        onMentionSearchTextChange = { mentionSearchText = it },
        onMentionStartIndexChange = { mentionStartIndex = it },
        onVoiceRecordingChange = { isVoiceRecording = it },
        onTranscribingChange = { isTranscribing = it },
        onShowAddContactConfirm = { showAddContactConfirm = it },
        onMessageToForward = { messageToForward = it },
        onLoadingGroupsChange = { isLoadingGroups = it },
        onUserGroupsLoaded = { userGroups = it },
        onExpandScrollJobChange = { expandScrollJob = it },
        onRecallingMessageIdsChange = { recallingMessageIds = it },
        mediaRecorderRef = mediaRecorderRef,
        audioFilePathRef = audioFilePathRef,
        micPermissionLauncher = micPermissionLauncher,
        addLog = ::addLog,
    )

    ChatScreenDialogsHost(
        appState = appState,
        user = user,
        group = group,
        context = context,
        chatClient = chatClient,
        messages = messages,
        debugLogs = debugLogs,
        connectionState = connectionState,
        showDebugInfo = showDebugInfo,
        showInvitationDialog = showInvitationDialog,
        showAddMemberDialog = showAddMemberDialog,
        contacts = contacts,
        groupMembers = groupMembers,
        isLoadingContacts = isLoadingContacts,
        addMemberResult = addMemberResult,
        showMembersDialog = showMembersDialog,
        selectedMemberForInvite = selectedMemberForInvite,
        isInvitingMember = isInvitingMember,
        inviteMemberResult = inviteMemberResult,
        showFolderExplorer = showFolderExplorer,
        folderFiles = folderFiles,
        processedUrls = processedUrls,
        isLoadingFiles = isLoadingFiles,
        showAddContactConfirm = showAddContactConfirm,
        isAddingContact = isAddingContact,
        addContactResult = addContactResult,
        showForwardToGroupDialog = showForwardToGroupDialog,
        showForwardToContactDialog = showForwardToContactDialog,
        userGroups = userGroups,
        isLoadingGroups = isLoadingGroups,
        selectedMessages = selectedMessages,
        messageToForward = messageToForward,
        forwardResult = forwardResult,
        onShowDebugInfo = { showDebugInfo = it },
        onShowInvitationDialog = { showInvitationDialog = it },
        onShowAddMemberDialog = { showAddMemberDialog = it },
        onAddMemberResult = { addMemberResult = it },
        onShowMembersDialog = { showMembersDialog = it },
        onSelectedMemberForInvite = { selectedMemberForInvite = it },
        onInvitingMemberChange = { isInvitingMember = it },
        onInviteMemberResult = { inviteMemberResult = it },
        onShowFolderExplorer = { showFolderExplorer = it },
        onShowAddContactConfirm = { showAddContactConfirm = it },
        onAddingContactChange = { isAddingContact = it },
        onAddContactResult = { addContactResult = it },
        onShowForwardToGroupDialog = { showForwardToGroupDialog = it },
        onShowForwardToContactDialog = { showForwardToContactDialog = it },
        onMessageToForward = { messageToForward = it },
        onForwardResult = { forwardResult = it },
        onSelectionModeChange = { isSelectionMode = it },
        onClearSelectedMessages = { selectedMessages.clear() },
        onGroupMembersLoaded = { groupMembers = it },
        addLog = ::addLog,
    )
}

@Composable
private fun rememberChatFilePickerLauncher(
    context: Context,
    group: Group,
    user: User,
    scope: kotlinx.coroutines.CoroutineScope,
    chatClient: ChatClient,
    addLog: (String) -> Unit,
    onUploadingChange: (Boolean) -> Unit,
    onUploadProgressChange: (String) -> Unit,
): ActivityResultLauncher<String> =
    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                onUploadingChange(true)
                onUploadProgressChange("正在上传...")
                try {
                    runLoggedSuspendAction("❌ 上传异常", addLog) {
                        val fileName = getFileName(context, selectedUri)
                        addLog("📎 开始上传文件: $fileName")
                        val inputStream = context.contentResolver.openInputStream(selectedUri)
                        if (inputStream != null) {
                            val success = uploadFile(
                                inputStream = inputStream,
                                fileName = fileName,
                                sessionId = group.id,
                                userId = user.id,
                                onProgress = onUploadProgressChange,
                            )
                            if (success) {
                                addLog("✅ 文件上传成功: $fileName")
                                chatClient.sendMessage(user.id, user.fullName, "📎 已上传文件: $fileName")
                            } else {
                                addLog("❌ 文件上传失败")
                            }
                        }
                    }
                } finally {
                    onUploadingChange(false)
                    onUploadProgressChange("")
                }
            }
        }
    }

@Composable
private fun rememberChatSessionUsers(
    messages: List<Message>,
    groupMembers: List<GroupMember>,
    user: User,
): List<Pair<String, String>> = remember(messages, groupMembers, user.id, user.fullName) {
    val users = mutableSetOf<Pair<String, String>>()
    users.add(SILK_AGENT_USER_ID to "🤖 $SILK_AGENT_DISPLAY_NAME")
    users.add(user.id to user.fullName)
    groupMembers.forEach { member ->
        if (!isAgentUserId(member.id) && member.id != user.id) {
            users.add(member.id to member.fullName)
        }
    }
    messages.forEach { msg ->
        if (!isAgentUserId(msg.userId) && msg.userId != user.id) {
            users.add(msg.userId to msg.userName)
        }
    }
    users.toList()
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun ChatScreenEffects(
    user: User,
    group: Group,
    wsUrl: String,
    baseUrl: String,
    chatClient: ChatClient,
    messages: List<Message>,
    transientMessage: Message?,
    connectionState: ConnectionState,
    isWaitingForAI: Boolean,
    isHistoryLoading: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    addLog: (String) -> Unit,
    onGroupMembersLoaded: (List<GroupMember>) -> Unit,
    onHasSentDefaultInstructionChange: (Boolean) -> Unit,
    onWaitingForAiChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val last = messages.last()
            addLog("📊 消息列表+1: 总共${messages.size}条")
            addLog("   最新: ${last.userName}: ${last.content.take(20)}...")
            if (isAgentUserId(last.userId) && isWaitingForAI) {
                onWaitingForAiChange(false)
                addLog("✅ AI 响应已收到，清除等待状态")
            }
        }
    }

    LaunchedEffect(transientMessage) {
        if (transientMessage != null) {
            addLog("⏳ 临时消息: ${transientMessage.content.take(20)}... (${transientMessage.content.length}字)")
        } else {
            addLog("🗑️ 临时消息已清除")
        }
    }

    LaunchedEffect(Unit) {
        addLog("📱 应用版本: 1.0.12-robust-reconnect (Build 12)")
        addLog("📱 Android版本: ${android.os.Build.VERSION.RELEASE}")
        addLog("📱 设备型号: ${android.os.Build.MODEL}")
        addLog("🔧 WebSocket URL: $wsUrl")
        addLog("🔧 HTTP BASE_URL: $baseUrl")
    }

    LaunchedEffect(connectionState) {
        addLog("📊 连接状态变化: $connectionState")
    }

    LaunchedEffect(group.id) {
        onHasSentDefaultInstructionChange(false)
        addLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        addLog("🔌 开始连接WebSocket...")
        addLog("   URL: $wsUrl")
        addLog("   用户: ${user.fullName}")
        addLog("   用户ID: ${user.id}")
        addLog("   群组: ${group.name}")
        addLog("   群组ID: ${group.id}")
        addLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        launch {
            val membersResponse = ApiClient.getGroupMembers(group.id)
            if (membersResponse.success) {
                onGroupMembersLoaded(membersResponse.members.sortedByDescending { it.id == group.hostId })
                addLog("✅ 群成员列表已加载，共 ${membersResponse.members.size} 人")
            } else {
                addLog("❌ 加载群成员列表失败")
            }
        }
        launch {
            runLoggedSuspendAction("❌ connect() 抛出异常", addLog) {
                addLog("⏳ 启动 chatClient.connect()...")
                chatClient.connect(user.id, user.fullName, group.id)
                addLog("⚠️ connect() 返回了（连接已关闭）")
            }
        }
        addLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        addLog("✅ WebSocket 连接协程已启动")
        addLog("━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    var lastMessageCount by remember { mutableStateOf(0) }
    LaunchedEffect(isHistoryLoading) {
        if (!isHistoryLoading && messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(messages.size) {
        if (!isHistoryLoading && messages.size > lastMessageCount && lastMessageCount > 0) {
            listState.animateScrollToItem(0)
        }
        lastMessageCount = messages.size
    }
    LaunchedEffect(transientMessage != null) {
        if (transientMessage != null && messages.isNotEmpty()) {
            kotlinx.coroutines.delay(100)
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(isWaitingForAI) {
        if (isWaitingForAI) {
            kotlinx.coroutines.delay(100)
            listState.scrollToItem(0)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var connectionJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        WebSocketForegroundService.start(context, group.name)
        addLog("🚀 [前台服务] 已启动 WebSocket 保活服务")
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    scope.launch {
                        addLog("🔄 [生命周期] 应用返回前台")
                        addLog("   当前连接状态: $connectionState")
                        if (connectionState != ConnectionState.CONNECTED) {
                            addLog("🔄 [生命周期] 连接未就绪，检查是否需要重连...")
                            kotlinx.coroutines.delay(500)
                            if (connectionState == ConnectionState.DISCONNECTED) {
                                addLog("🔌 [生命周期] 连接已断开，尝试重新连接...")
                                connectionJob?.cancel()
                                kotlinx.coroutines.delay(300)
                                connectionJob = scope.launch {
                                    runLoggedSuspendAction("❌ 重新连接失败", addLog) {
                                        addLog("🔌 建立新的WebSocket连接...")
                                        chatClient.connect(user.id, user.fullName, group.id)
                                        var attempts = 0
                                        while (connectionState != ConnectionState.CONNECTED && attempts < 10) {
                                            kotlinx.coroutines.delay(500)
                                            attempts++
                                            addLog("⏳ 等待连接建立... (${attempts}/10)")
                                        }
                                        if (connectionState == ConnectionState.CONNECTED) {
                                            addLog("✅ WebSocket重新连接成功！")
                                            kotlinx.coroutines.delay(300)
                                            if (messages.isNotEmpty()) {
                                                listState.scrollToItem(0)
                                            }
                                        } else {
                                            addLog("❌ WebSocket重新连接超时")
                                        }
                                    }
                                }
                            } else if (connectionState == ConnectionState.CONNECTING) {
                                addLog("⏳ [生命周期] 连接正在建立中，等待完成...")
                            }
                        } else {
                            addLog("✅ [生命周期] 连接正常，无需重连")
                            WebSocketForegroundService.start(context, group.name)
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    scope.launch {
                        addLog("⏸️ [生命周期] 应用进入后台 - 保持连接（前台服务保活）")
                        addLog("   当前连接状态: $connectionState")
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    scope.launch {
                        addLog("⏹️ [生命周期] 应用停止 - 前台服务保活中")
                        addLog("   当前连接状态: $connectionState")
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            scope.launch {
                addLog("🔌 [生命周期] 离开聊天界面，清理资源...")
                WebSocketForegroundService.stop(context)
                addLog("🛑 [前台服务] 已停止 WebSocket 保活服务")
                chatClient.disconnect()
                kotlinx.coroutines.delay(300)
                if (ApiClient.markGroupAsRead(user.id, group.id)) {
                    addLog("✅ 已标记群组为已读")
                } else {
                    addLog("⚠️ 标记已读失败")
                }
            }
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod")
@Composable
private fun ChatScreenScaffoldContent(
    appState: AppState,
    user: User,
    group: Group,
    context: Context,
    chatClient: ChatClient,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    messages: List<Message>,
    transientMessage: Message?,
    statusMessages: List<Message>,
    connectionState: ConnectionState,
    isHistoryLoading: Boolean,
    isGenerating: Boolean,
    isWaitingForAI: Boolean,
    isSelectionMode: Boolean,
    selectedMessages: SnapshotStateList<String>,
    isExiting: Boolean,
    isUploading: Boolean,
    uploadProgress: String,
    messageText: TextFieldValue,
    listState: androidx.compose.foundation.lazy.LazyListState,
    aiMessageExpandedStates: MutableMap<String, Boolean>,
    thinkingExpandedStates: MutableMap<String, Boolean>,
    expandScrollJob: Job?,
    sessionUsers: List<Pair<String, String>>,
    showMentionMenu: Boolean,
    mentionSearchText: String,
    mentionStartIndex: Int,
    isVoiceRecording: Boolean,
    isTranscribing: Boolean,
    micPermissionGranted: Boolean,
    recallingMessageIds: Set<String>,
    filePickerLauncher: ActivityResultLauncher<String>,
    onExitingChange: (Boolean) -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onClearSelectedMessages: () -> Unit,
    onMessageSelected: (String) -> Unit,
    onWaitingForAiChange: (Boolean) -> Unit,
    onShowForwardToGroupDialog: (Boolean) -> Unit,
    onShowForwardToContactDialog: (Boolean) -> Unit,
    onShowInvitationDialog: (Boolean) -> Unit,
    onShowFolderExplorer: (Boolean) -> Unit,
    onFolderFilesLoaded: (List<FileItem>, List<String>) -> Unit,
    onLoadingFilesChange: (Boolean) -> Unit,
    onShowAddMemberDialog: (Boolean) -> Unit,
    onShowMembersDialog: (Boolean) -> Unit,
    onContactsLoaded: (List<Contact>) -> Unit,
    onGroupMembersLoaded: (List<GroupMember>) -> Unit,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onMentionMenuChange: (Boolean) -> Unit,
    onMentionSearchTextChange: (String) -> Unit,
    onMentionStartIndexChange: (Int) -> Unit,
    onVoiceRecordingChange: (Boolean) -> Unit,
    onTranscribingChange: (Boolean) -> Unit,
    onShowAddContactConfirm: (Message?) -> Unit,
    onMessageToForward: (Message?) -> Unit,
    onLoadingGroupsChange: (Boolean) -> Unit,
    onUserGroupsLoaded: (List<Group>) -> Unit,
    onExpandScrollJobChange: (Job?) -> Unit,
    onRecallingMessageIdsChange: (Set<String>) -> Unit,
    mediaRecorderRef: MutableState<MediaRecorder?>,
    audioFilePathRef: MutableState<String>,
    micPermissionLauncher: ActivityResultLauncher<String>,
    addLog: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(group.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "${messages.size} 条消息",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                handleChatBackNavigation(
                                    appState = appState,
                                    group = group,
                                    user = user,
                                    chatClient = chatClient,
                                    isExiting = isExiting,
                                    onExitingChange = onExitingChange,
                                )
                            }
                        },
                        enabled = !isExiting,
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    val selectedContent = messages
                                        .filter { selectedMessages.contains(it.id) }
                                        .sortedBy { it.timestamp }
                                        .joinToString("\n\n") { "${it.userName}:\n${it.content}" }
                                    if (selectedContent.isNotEmpty()) {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("消息", selectedContent))
                                        android.widget.Toast.makeText(context, "已复制 ${selectedMessages.size} 条消息", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    onSelectionModeChange(false)
                                    onClearSelectedMessages()
                                },
                                enabled = selectedMessages.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text("📋复制", fontSize = 12.sp, color = Color.White)
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        onLoadingGroupsChange(true)
                                        val response = ApiClient.getUserGroups(user.id)
                                        onUserGroupsLoaded(response.groups?.filter { it.id != group.id } ?: emptyList())
                                        onLoadingGroupsChange(false)
                                        onShowForwardToGroupDialog(true)
                                    }
                                },
                                enabled = selectedMessages.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text("💬转发", fontSize = 12.sp, color = Color.White)
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        onLoadingFilesChange(true)
                                        val contactsResponse = ApiClient.getContacts(user.id)
                                        onContactsLoaded(contactsResponse.contacts ?: emptyList())
                                        onLoadingFilesChange(false)
                                        onShowForwardToContactDialog(true)
                                    }
                                },
                                enabled = selectedMessages.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text("👤私聊", fontSize = 12.sp, color = Color.White)
                            }
                            TextButton(
                                onClick = {
                                    val selectedContent = messages
                                        .filter { selectedMessages.contains(it.id) }
                                        .sortedBy { it.timestamp }
                                        .joinToString("\n\n") { msg ->
                                            val time = formatMessageTimestamp(timestamp = msg.timestamp, includeSeconds = false)
                                            "[$time] ${msg.userName}:\n${msg.content}"
                                        }
                                    if (selectedContent.isNotEmpty()) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, selectedContent)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "分享到"))
                                    }
                                    onSelectionModeChange(false)
                                    onClearSelectedMessages()
                                },
                                enabled = selectedMessages.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text("📤分享", fontSize = 12.sp, color = Color.White)
                            }
                            TextButton(
                                onClick = {
                                    onSelectionModeChange(false)
                                    onClearSelectedMessages()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text("✕取消", fontSize = 12.sp, color = Color.White)
                            }
                        }
                    } else {
                        IconButton(
                            onClick = {
                                onSelectionModeChange(true)
                                onClearSelectedMessages()
                                android.widget.Toast.makeText(context, "已进入选择模式，点击消息进行选择", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("☑️", fontSize = 16.sp)
                        }
                        IconButton(
                            onClick = {
                                onShowFolderExplorer(true)
                                onLoadingFilesChange(true)
                                scope.launch {
                                    try {
                                        val result = loadGroupFilesAndUrls(group.id)
                                        onFolderFilesLoaded(result.files, result.processedUrls)
                                    } finally {
                                        onLoadingFilesChange(false)
                                    }
                                }
                            }
                        ) {
                            Text("📁", fontSize = 16.sp)
                        }
                        IconButton(onClick = { if (!isUploading) filePickerLauncher.launch("*/*") }, enabled = !isUploading) {
                            Text(text = if (isUploading) "⏳" else "📎", fontSize = 16.sp)
                        }
                        IconButton(onClick = { onShowInvitationDialog(true) }) {
                            Icon(Icons.Default.Share, contentDescription = "邀请", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val contactsResponse = ApiClient.getContacts(user.id)
                                    val membersResponse = ApiClient.getGroupMembers(group.id)
                                    onContactsLoaded(contactsResponse.contacts ?: emptyList())
                                    onGroupMembersLoaded(membersResponse.members.sortedByDescending { it.id == group.hostId })
                                    onShowAddMemberDialog(true)
                                }
                            }
                        ) {
                            Text("➕", fontSize = 16.sp)
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val contactsResponse = ApiClient.getContacts(user.id)
                                    val membersResponse = ApiClient.getGroupMembers(group.id)
                                    onContactsLoaded(contactsResponse.contacts ?: emptyList())
                                    onGroupMembersLoaded(membersResponse.members.sortedByDescending { it.id == group.hostId })
                                    onShowMembersDialog(true)
                                }
                            }
                        ) {
                            Text("👥", fontSize = 16.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (connectionState == ConnectionState.CONNECTING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("正在连接...", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (isUploading && uploadProgress.isNotEmpty()) {
                Surface(modifier = Modifier.fillMaxWidth(), color = Color.LightGray.copy(alpha = 0.3f)) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Gray)
                        Text("📎 $uploadProgress", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
            ChatScreenHistoryPane(
                messages = messages,
                transientMessage = transientMessage,
                statusMessages = statusMessages,
                isHistoryLoading = isHistoryLoading,
                isWaitingForAI = isWaitingForAI,
                isSelectionMode = isSelectionMode,
                selectedMessages = selectedMessages,
                currentUserId = user.id,
                context = context,
                listState = listState,
                aiMessageExpandedStates = aiMessageExpandedStates,
                thinkingExpandedStates = thinkingExpandedStates,
                expandScrollJob = expandScrollJob,
                onExpandScrollJobChange = onExpandScrollJobChange,
                onMessageSelected = onMessageSelected,
                onShowAddContactConfirm = onShowAddContactConfirm,
                onWaitingForAiChange = onWaitingForAiChange,
                onRecallingMessageIdsChange = onRecallingMessageIdsChange,
                onSelectionModeChange = onSelectionModeChange,
                onClearSelectedMessages = onClearSelectedMessages,
                recallingMessageIds = recallingMessageIds,
                user = user,
                group = group,
                onMessageToForward = onMessageToForward,
                onLoadingGroupsChange = onLoadingGroupsChange,
                onUserGroupsLoaded = onUserGroupsLoaded,
                onShowForwardToGroupDialog = onShowForwardToGroupDialog,
            )
            ChatScreenInputPane(
                context = context,
                user = user,
                group = group,
                chatClient = chatClient,
                messageText = messageText,
                isGenerating = isGenerating,
                isWaitingForAI = isWaitingForAI,
                isVoiceRecording = isVoiceRecording,
                isTranscribing = isTranscribing,
                micPermissionGranted = micPermissionGranted,
                sessionUsers = sessionUsers,
                showMentionMenu = showMentionMenu,
                mentionSearchText = mentionSearchText,
                mentionStartIndex = mentionStartIndex,
                mediaRecorderRef = mediaRecorderRef,
                audioFilePathRef = audioFilePathRef,
                micPermissionLauncher = micPermissionLauncher,
                onMessageTextChange = onMessageTextChange,
                onMentionMenuChange = onMentionMenuChange,
                onMentionSearchTextChange = onMentionSearchTextChange,
                onMentionStartIndexChange = onMentionStartIndexChange,
                onVoiceRecordingChange = onVoiceRecordingChange,
                onTranscribingChange = onTranscribingChange,
                onWaitingForAiChange = onWaitingForAiChange,
                addLog = addLog,
            )
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun ColumnScope.ChatScreenHistoryPane(
    messages: List<Message>,
    transientMessage: Message?,
    statusMessages: List<Message>,
    isHistoryLoading: Boolean,
    isWaitingForAI: Boolean,
    isSelectionMode: Boolean,
    selectedMessages: SnapshotStateList<String>,
    currentUserId: String,
    context: Context,
    listState: androidx.compose.foundation.lazy.LazyListState,
    aiMessageExpandedStates: MutableMap<String, Boolean>,
    thinkingExpandedStates: MutableMap<String, Boolean>,
    expandScrollJob: Job?,
    onExpandScrollJobChange: (Job?) -> Unit,
    onMessageSelected: (String) -> Unit,
    onShowAddContactConfirm: (Message?) -> Unit,
    onWaitingForAiChange: (Boolean) -> Unit,
    onRecallingMessageIdsChange: (Set<String>) -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onClearSelectedMessages: () -> Unit,
    recallingMessageIds: Set<String>,
    user: User,
    group: Group,
    onMessageToForward: (Message?) -> Unit,
    onLoadingGroupsChange: (Boolean) -> Unit,
    onUserGroupsLoaded: (List<Group>) -> Unit,
    onShowForwardToGroupDialog: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Crossfade(targetState = isHistoryLoading, animationSpec = tween(durationMillis = 300), label = "history_loading") { loading ->
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = SilkColors.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("加载历史消息...", color = SilkColors.textSecondary)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    reverseLayout = true,
                ) {
                    if (shouldShowEmptyChatState(messages, transientMessage, statusMessages, isWaitingForAI)) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                Text("暂无消息，开始聊天吧！", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        transientMessage?.let { message ->
                            item(key = "transient_message") {
                                MessageItem(
                                    message = message,
                                    currentUserId = currentUserId,
                                    context = context,
                                    isTransient = true,
                                    isSelectionMode = false,
                                    isSelected = false,
                                    onToggleSelection = {},
                                    onUserNameClick = null,
                                )
                            }
                        }
                        if (statusMessages.isNotEmpty() || isWaitingForAI) {
                            item(key = "status_messages") {
                                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                    if (statusMessages.isNotEmpty() && isWaitingForAI) {
                                        LaunchedEffect(statusMessages) { onWaitingForAiChange(false) }
                                    }
                                    statusMessages.reversed().forEach { statusMsg ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                            val content = statusMsg.content
                                            val hasToolIcon = content.isNotEmpty() && !Character.isLetterOrDigit(content.codePointAt(0))
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
                                    if (isWaitingForAI && statusMessages.isEmpty()) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = Color.Gray.copy(alpha = 0.7f),
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("🤔 Silk 正在思考...", style = MaterialTheme.typography.bodySmall, color = Color.Gray.copy(alpha = 0.7f))
                                        }
                                    }
                                }
                            }
                        }
                        items(messages.reversed(), key = { it.id }) { message ->
                            MessageItem(
                                message = message,
                                currentUserId = currentUserId,
                                context = context,
                                isTransient = false,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedMessages.contains(message.id),
                                onToggleSelection = onMessageSelected,
                                onLongPress = { messageId ->
                                    if (!isSelectionMode) {
                                        onSelectionModeChange(true)
                                        onClearSelectedMessages()
                                        onMessageSelected(messageId)
                                        android.os.Build.VERSION.SDK_INT.let {
                                            if (it >= android.os.Build.VERSION_CODES.O) {
                                                (context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
                                                    ?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                            }
                                        }
                                    }
                                },
                                onUserNameClick = { clickedMessage ->
                                    if (clickedMessage.userId != user.id) onShowAddContactConfirm(clickedMessage)
                                },
                                isRecalling = message.id in recallingMessageIds,
                                onRecall = { messageId ->
                                    if (messageId !in recallingMessageIds) {
                                        onRecallingMessageIdsChange(recallingMessageIds + messageId)
                                        scope.launch {
                                            try {
                                                val response = ApiClient.recallMessage(group.id, messageId, user.id)
                                                if (!response.success) {
                                                    android.widget.Toast.makeText(context, "撤回失败: ${response.message}", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            } finally {
                                                onRecallingMessageIdsChange(recallingMessageIds - messageId)
                                            }
                                        }
                                    }
                                },
                                isAIExpanded = aiMessageExpandedStates[message.id] ?: false,
                                onAIExpandChange = { messageId, isExpanded ->
                                    val reversedMessages = messages.reversed()
                                    val idx = reversedMessages.indexOfFirst { it.id == messageId }
                                    val itemOffset = (if (transientMessage != null) 1 else 0) + (if (statusMessages.isNotEmpty() || isWaitingForAI) 1 else 0)
                                    val targetIdx = if (idx >= 0) itemOffset + idx else -1
                                    if (isExpanded) {
                                        aiMessageExpandedStates[messageId] = true
                                        if (targetIdx >= 0) {
                                            expandScrollJob?.cancel()
                                            onExpandScrollJobChange(scope.launch {
                                                kotlinx.coroutines.delay(80)
                                                listState.scrollToItem(targetIdx, 0)
                                                var prevSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx }?.size ?: 0
                                                snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx }?.size ?: 0 }
                                                    .distinctUntilChanged()
                                                    .collect { size ->
                                                        if (size > 0 && prevSize > 0 && size > prevSize) {
                                                            listState.scroll { scrollBy((size - prevSize).toFloat()) }
                                                        }
                                                        if (size > 0) prevSize = size
                                                    }
                                            })
                                        }
                                    } else {
                                        expandScrollJob?.cancel()
                                        onExpandScrollJobChange(null)
                                        if (targetIdx >= 0) {
                                            scope.launch {
                                                listState.scrollToItem(targetIdx, 0)
                                                aiMessageExpandedStates[messageId] = false
                                            }
                                        } else {
                                            aiMessageExpandedStates[messageId] = false
                                        }
                                    }
                                },
                                isThinkingExpanded = thinkingExpandedStates[message.id] ?: false,
                                onThinkingExpandChange = { messageId, expanded ->
                                    val reversedMessages = messages.reversed()
                                    val idx = reversedMessages.indexOfFirst { it.id == messageId }
                                    val itemOffset = (if (transientMessage != null) 1 else 0) + (if (statusMessages.isNotEmpty() || isWaitingForAI) 1 else 0)
                                    val targetIdx = if (idx >= 0) itemOffset + idx else -1
                                    if (expanded) {
                                        thinkingExpandedStates[messageId] = true
                                        if (targetIdx >= 0) {
                                            expandScrollJob?.cancel()
                                            onExpandScrollJobChange(scope.launch {
                                                kotlinx.coroutines.delay(80)
                                                listState.scrollToItem(targetIdx, 0)
                                                var prevSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx }?.size ?: 0
                                                withTimeoutOrNull(3000L) {
                                                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx }?.size ?: 0 }
                                                        .distinctUntilChanged()
                                                        .collect { size ->
                                                            if (size > 0 && prevSize > 0 && size > prevSize) {
                                                                listState.scroll { scrollBy((size - prevSize).toFloat()) }
                                                            }
                                                            if (size > 0) prevSize = size
                                                        }
                                                }
                                            })
                                        }
                                    } else {
                                        expandScrollJob?.cancel()
                                        onExpandScrollJobChange(null)
                                        if (targetIdx >= 0) {
                                            scope.launch {
                                                listState.scrollToItem(targetIdx, 0)
                                                thinkingExpandedStates[messageId] = false
                                            }
                                        } else {
                                            thinkingExpandedStates[messageId] = false
                                        }
                                    }
                                },
                                onLongContentCollapsed = { messageId ->
                                    expandScrollJob?.cancel()
                                    val reversedMessages = messages.reversed()
                                    val idx = reversedMessages.indexOfFirst { it.id == messageId }
                                    if (idx >= 0) {
                                        scope.launch {
                                            kotlinx.coroutines.delay(80)
                                            val itemOffset = (if (transientMessage != null) 1 else 0) + (if (statusMessages.isNotEmpty() || isWaitingForAI) 1 else 0)
                                            listState.scrollToItem(itemOffset + idx)
                                        }
                                    }
                                },
                                onCopy = { content ->
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("消息", content))
                                    android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                onForward = { msg ->
                                    onMessageToForward(msg)
                                    scope.launch {
                                        onLoadingGroupsChange(true)
                                        val response = ApiClient.getUserGroups(user.id)
                                        onUserGroupsLoaded(response.groups?.filter { it.id != group.id } ?: emptyList())
                                        onLoadingGroupsChange(false)
                                        onShowForwardToGroupDialog(true)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun ChatScreenInputPane(
    context: Context,
    user: User,
    group: Group,
    chatClient: ChatClient,
    messageText: TextFieldValue,
    isGenerating: Boolean,
    isWaitingForAI: Boolean,
    isVoiceRecording: Boolean,
    isTranscribing: Boolean,
    micPermissionGranted: Boolean,
    sessionUsers: List<Pair<String, String>>,
    showMentionMenu: Boolean,
    mentionSearchText: String,
    mentionStartIndex: Int,
    mediaRecorderRef: MutableState<MediaRecorder?>,
    audioFilePathRef: MutableState<String>,
    micPermissionLauncher: ActivityResultLauncher<String>,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onMentionMenuChange: (Boolean) -> Unit,
    onMentionSearchTextChange: (String) -> Unit,
    onMentionStartIndexChange: (Int) -> Unit,
    onVoiceRecordingChange: (Boolean) -> Unit,
    onTranscribingChange: (Boolean) -> Unit,
    onWaitingForAiChange: (Boolean) -> Unit,
    addLog: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (connectionStateOf(chatClient) == ConnectionState.CONNECTED) {
        Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                val isSilkPrivateChat = group.name.startsWith("[Silk]")
                if (!isSilkPrivateChat) {
                    Surface(
                        onClick = {
                            val prefix = "@Silk "
                            if (!messageText.text.startsWith(prefix)) {
                                val newText = prefix + messageText.text
                                val newSelection = TextRange(
                                    start = (messageText.selection.start + prefix.length).coerceIn(0, newText.length),
                                    end = (messageText.selection.end + prefix.length).coerceIn(0, newText.length),
                                )
                                onMessageTextChange(messageText.copy(text = newText, selection = newSelection))
                            }
                        },
                        color = SilkColors.primary.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("@Silk", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = SilkColors.primary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (showMentionMenu) {
                            val filteredUsers = sessionUsers.filter { (_, name) ->
                                mentionSearchText.isEmpty() || name.lowercase().contains(mentionSearchText.lowercase())
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(8.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            ) {
                                if (filteredUsers.isEmpty()) {
                                    Text("无匹配用户", modifier = Modifier.padding(12.dp, 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                        items(filteredUsers.size) { index ->
                                            val (userId, userName) = filteredUsers[index]
                                            val displayName = if (isAgentUserId(userId)) "Silk" else userName
                                            Surface(
                                                onClick = {
                                                    val beforeAt = messageText.text.substring(0, mentionStartIndex.coerceAtLeast(0))
                                                    val newText = "$beforeAt@$displayName "
                                                    onMessageTextChange(TextFieldValue(text = newText, selection = TextRange(newText.length)))
                                                    onMentionMenuChange(false)
                                                    onMentionStartIndexChange(-1)
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text(
                                                    text = userName,
                                                    modifier = Modifier.padding(10.dp, 12.dp),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isAgentUserId(userId)) FontWeight.SemiBold else FontWeight.Normal,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        if (isVoiceRecording) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("录音中...", color = Color(0xFFFF4D4F), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Button(
                                    onClick = {
                                        onVoiceRecordingChange(false)
                                        onTranscribingChange(true)
                                        val recorder = mediaRecorderRef.value
                                        val filePath = audioFilePathRef.value
                                        try { recorder?.stop() } catch (_: Exception) {}
                                        try { recorder?.release() } catch (_: Exception) {}
                                        mediaRecorderRef.value = null
                                        scope.launch {
                                            try {
                                                runLoggedSuspendAction("语音识别出错", addLog) {
                                                    val file = java.io.File(filePath)
                                                    if (file.exists()) {
                                                        val bytes = file.readBytes()
                                                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                                        val result = ApiClient.transcribeAudio(base64, "m4a")
                                                        if (result.success && result.text.isNotBlank()) {
                                                            val current = messageText.text
                                                            val newText = if (current.isNotBlank()) "$current ${result.text}" else result.text
                                                            onMessageTextChange(TextFieldValue(newText, TextRange(newText.length)))
                                                        } else {
                                                            addLog("ASR 失败: ${result.error ?: "未知错误"}")
                                                        }
                                                        file.delete()
                                                    }
                                                }
                                            } finally {
                                                onTranscribingChange(false)
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D4F)),
                                    modifier = Modifier.height(56.dp),
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "停止录音", tint = Color.White)
                                }
                            }
                        } else if (isTranscribing) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = SilkColors.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("识别中...", color = Color.Gray)
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = messageText,
                                    onValueChange = { newValue ->
                                        val oldText = messageText.text
                                        onMessageTextChange(newValue)
                                        val newText = newValue.text
                                        if (newText.length > oldText.length && newText.lastOrNull() == '@') {
                                            onMentionMenuChange(true)
                                            onMentionStartIndexChange(newText.length - 1)
                                            onMentionSearchTextChange("")
                                            return@OutlinedTextField
                                        }
                                        if (showMentionMenu && mentionStartIndex >= 0) {
                                            if (mentionStartIndex >= newText.length || newText.getOrNull(mentionStartIndex) != '@') {
                                                onMentionMenuChange(false)
                                                onMentionStartIndexChange(-1)
                                            } else {
                                                val textAfterAt = newText.substring(mentionStartIndex + 1)
                                                val spaceIndex = textAfterAt.indexOf(' ')
                                                if (spaceIndex >= 0) {
                                                    onMentionMenuChange(false)
                                                    onMentionStartIndexChange(-1)
                                                } else {
                                                    onMentionSearchTextChange(textAfterAt)
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text(if (group.name.startsWith("[Silk]")) "直接输入消息与 Silk 对话..." else "输入消息... @ 提及成员 / @silk 提问AI") },
                                    maxLines = 3,
                                )
                                IconButton(
                                    onClick = {
                                        if (!micPermissionGranted) {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            return@IconButton
                                        }
                                        runCatching {
                                            val filePath = "${context.cacheDir.absolutePath}/silk_voice_${System.currentTimeMillis()}.m4a"
                                            audioFilePathRef.value = filePath
                                            @Suppress("DEPRECATION")
                                            val recorder = MediaRecorder().apply {
                                                setAudioSource(MediaRecorder.AudioSource.MIC)
                                                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                                setAudioSamplingRate(16000)
                                                setAudioChannels(1)
                                                setOutputFile(filePath)
                                                prepare()
                                                start()
                                            }
                                            mediaRecorderRef.value = recorder
                                            onVoiceRecordingChange(true)
                                        }.onFailure { error ->
                                            if (error is CancellationException) throw error
                                            addLog("无法启动录音: ${error.message}")
                                        }
                                    },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = "语音输入", tint = Color.Gray)
                                }
                                val showStopButton = isGenerating || isWaitingForAI
                                if (showStopButton) {
                                    Button(
                                        onClick = {
                                            chatClient.stopGeneration(user.id, user.fullName)
                                            onWaitingForAiChange(false)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D4F)),
                                        modifier = Modifier.height(56.dp),
                                    ) {
                                        Text("停止", color = Color.White)
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            if (messageText.text.isNotBlank()) {
                                                val msgContent = messageText.text
                                                addLog("📤 发送消息: ${msgContent.take(20)}...")
                                                if (msgContent.lowercase().startsWith("@silk") || isSilkPrivateChat) {
                                                    onWaitingForAiChange(true)
                                                    addLog("⏳ 开始等待 AI 响应...")
                                                }
                                                scope.launch {
                                                    chatClient.sendMessage(user.id, user.fullName, msgContent)
                                                    addLog("✅ 消息已发送")
                                                    onMessageTextChange(TextFieldValue(""))
                                                }
                                            }
                                        },
                                        enabled = messageText.text.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
                                        modifier = Modifier.height(56.dp),
                                    ) {
                                        Icon(Icons.Default.Send, contentDescription = "发送")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
            Text("连接已断开，正在重新连接...", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun ChatScreenDialogsHost(
    appState: AppState,
    user: User,
    group: Group,
    context: Context,
    chatClient: ChatClient,
    messages: List<Message>,
    debugLogs: SnapshotStateList<String>,
    connectionState: ConnectionState,
    showDebugInfo: Boolean,
    showInvitationDialog: Boolean,
    showAddMemberDialog: Boolean,
    contacts: List<Contact>,
    groupMembers: List<GroupMember>,
    isLoadingContacts: Boolean,
    addMemberResult: String?,
    showMembersDialog: Boolean,
    selectedMemberForInvite: GroupMember?,
    isInvitingMember: Boolean,
    inviteMemberResult: String?,
    showFolderExplorer: Boolean,
    folderFiles: List<FileItem>,
    processedUrls: List<String>,
    isLoadingFiles: Boolean,
    showAddContactConfirm: Message?,
    isAddingContact: Boolean,
    addContactResult: String?,
    showForwardToGroupDialog: Boolean,
    showForwardToContactDialog: Boolean,
    userGroups: List<Group>,
    isLoadingGroups: Boolean,
    selectedMessages: SnapshotStateList<String>,
    messageToForward: Message?,
    forwardResult: String?,
    onShowDebugInfo: (Boolean) -> Unit,
    onShowInvitationDialog: (Boolean) -> Unit,
    onShowAddMemberDialog: (Boolean) -> Unit,
    onAddMemberResult: (String?) -> Unit,
    onShowMembersDialog: (Boolean) -> Unit,
    onSelectedMemberForInvite: (GroupMember?) -> Unit,
    onInvitingMemberChange: (Boolean) -> Unit,
    onInviteMemberResult: (String?) -> Unit,
    onShowFolderExplorer: (Boolean) -> Unit,
    onShowAddContactConfirm: (Message?) -> Unit,
    onAddingContactChange: (Boolean) -> Unit,
    onAddContactResult: (String?) -> Unit,
    onShowForwardToGroupDialog: (Boolean) -> Unit,
    onShowForwardToContactDialog: (Boolean) -> Unit,
    onMessageToForward: (Message?) -> Unit,
    onForwardResult: (String?) -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onClearSelectedMessages: () -> Unit,
    onGroupMembersLoaded: (List<GroupMember>) -> Unit,
    addLog: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (showDebugInfo) {
        AlertDialog(
            onDismissRequest = { onShowDebugInfo(false) },
            title = { Text("🐛 调试信息") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(debugLogs.size) { index ->
                        Text(text = debugLogs[index], style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                    }
                    item {
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "当前连接状态: $connectionState",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (connectionState) {
                                ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
                                ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = { onShowDebugInfo(false) }) { Text("关闭") } },
            dismissButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("调试日志", debugLogs.joinToString("\n")))
                        android.widget.Toast.makeText(context, "日志已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                    }
                ) { Text("复制日志") }
            },
        )
    }
    if (showInvitationDialog) {
        InvitationDialog(group = group, onDismiss = { onShowInvitationDialog(false) })
    }
    if (showAddMemberDialog) {
        AddMemberDialog(
            contacts = contacts,
            groupMembers = groupMembers,
            isLoading = isLoadingContacts,
            result = addMemberResult,
            onAddMember = { contact ->
                scope.launch {
                    val response = ApiClient.addMemberToGroup(group.id, contact.contactId)
                    onAddMemberResult(
                        if (response.success) {
                            val membersResponse = ApiClient.getGroupMembers(group.id)
                            onGroupMembersLoaded(membersResponse.members.sortedByDescending { it.id == group.hostId })
                            "✅ 已添加 ${contact.contactName}"
                        } else {
                            "❌ ${response.message}"
                        }
                    )
                }
            },
            onDismiss = {
                onShowAddMemberDialog(false)
                onAddMemberResult(null)
            },
        )
    }
    if (showMembersDialog) {
        MembersDialog(
            members = groupMembers,
            contacts = contacts,
            currentUserId = user.id,
            isLoading = isLoadingContacts,
            onMemberClick = { member ->
                val isContact = contacts.any { it.contactId == member.id }
                if (isContact) {
                    scope.launch {
                        onShowMembersDialog(false)
                        disconnectChatClientQuietly(chatClient, addLog)
                        val response = ApiClient.startPrivateChat(user.id, member.id)
                        if (response.success && response.group != null) {
                            appState.selectGroup(response.group!!)
                        } else {
                            android.widget.Toast.makeText(context, "无法创建对话: ${response.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    onSelectedMemberForInvite(member)
                }
            },
            onDismiss = {
                onShowMembersDialog(false)
                onSelectedMemberForInvite(null)
                onInviteMemberResult(null)
            },
        )
    }
    selectedMemberForInvite?.let { member ->
        AlertDialog(
            onDismissRequest = {
                onSelectedMemberForInvite(null)
                onInviteMemberResult(null)
            },
            title = { Text("添加联系人", fontWeight = FontWeight.Bold, color = SilkColors.primary) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("${member.fullName} 不在您的联系人列表中。")
                    Text("是否发送联系人请求？")
                    inviteMemberResult?.let { result ->
                        Text(
                            text = result,
                            color = if (result.contains("成功") || result.contains("已发送")) SilkColors.success else SilkColors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            onInvitingMemberChange(true)
                            onInviteMemberResult(null)
                            val response = ApiClient.sendContactRequestById(user.id, member.id)
                            onInviteMemberResult(if (response.success) "✅ 联系人请求已发送" else "❌ ${response.message}")
                            onInvitingMemberChange(false)
                            if (response.success) {
                                kotlinx.coroutines.delay(1500)
                                onSelectedMemberForInvite(null)
                                onInviteMemberResult(null)
                            }
                        }
                    },
                    enabled = !isInvitingMember && inviteMemberResult == null,
                    colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
                ) {
                    if (isInvitingMember) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("发送请求")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onSelectedMemberForInvite(null)
                    onInviteMemberResult(null)
                }) { Text("取消") }
            },
        )
    }
    if (showFolderExplorer) {
        FolderExplorerDialog(
            files = folderFiles,
            processedUrls = processedUrls,
            isLoading = isLoadingFiles,
            onDismiss = { onShowFolderExplorer(false) },
            onFileClick = { file ->
                val relativeDownloadUrl = file.downloadUrl.ifEmpty { "/api/files/download/${Uri.encode(group.id)}/${Uri.encode(file.name)}" }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BackendUrlHolder.getBaseUrl()}$relativeDownloadUrl")))
            },
            onUrlClick = { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
        )
    }
    showAddContactConfirm?.let { clickedMessage ->
        AlertDialog(
            onDismissRequest = {
                onShowAddContactConfirm(null)
                onAddContactResult(null)
            },
            title = { Text("添加联系人", fontWeight = FontWeight.Bold, color = SilkColors.primary) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("是否将 ${clickedMessage.userName} 添加为联系人？")
                    addContactResult?.let { result ->
                        Text(
                            text = result,
                            color = if (result.contains("成功") || result.contains("已发送")) SilkColors.success else SilkColors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            onAddingContactChange(true)
                            onAddContactResult(null)
                            val response = ApiClient.sendContactRequestById(user.id, clickedMessage.userId)
                            onAddContactResult(if (response.success) "联系人请求已发送" else response.message)
                            onAddingContactChange(false)
                            if (response.success) {
                                kotlinx.coroutines.delay(1500)
                                onShowAddContactConfirm(null)
                                onAddContactResult(null)
                            }
                        }
                    },
                    enabled = !isAddingContact && addContactResult == null,
                    colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
                ) {
                    if (isAddingContact) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("发送请求")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onShowAddContactConfirm(null)
                    onAddContactResult(null)
                }) { Text("取消") }
            },
        )
    }
    if (showForwardToGroupDialog) {
        val messagesToForward = if (messageToForward != null) listOf(messageToForward) else messages.filter { selectedMessages.contains(it.id) }.sortedBy { it.timestamp }
        ForwardToGroupDialog(
            groups = userGroups,
            isLoading = isLoadingGroups,
            selectedMessages = messagesToForward.filterNotNull(),
            onForward = { targetGroup ->
                scope.launch {
                    onForwardResult(null)
                    val forwardMessage = if (messageToForward != null) buildForwardPayloadForSingle(group.name, messageToForward) else buildForwardPayloadForBatch(group.name, messagesToForward.filterNotNull())
                    val success = ApiClient.sendMessageToGroup(targetGroup.id, user.id, user.fullName, forwardMessage)
                    if (success) {
                        onForwardResult("✅ 已转发到 ${targetGroup.name}")
                        android.widget.Toast.makeText(context, "已转发 ${messagesToForward.size} 条消息到 ${targetGroup.name}", android.widget.Toast.LENGTH_SHORT).show()
                        kotlinx.coroutines.delay(1000)
                        onShowForwardToGroupDialog(false)
                        onSelectionModeChange(false)
                        onClearSelectedMessages()
                        onMessageToForward(null)
                        onForwardResult(null)
                    } else {
                        onForwardResult("❌ 转发失败")
                    }
                }
            },
            onDismiss = {
                onShowForwardToGroupDialog(false)
                onMessageToForward(null)
                onForwardResult(null)
            },
            result = forwardResult,
        )
    }
    if (showForwardToContactDialog) {
        val messagesToForward = messages.filter { selectedMessages.contains(it.id) }.sortedBy { it.timestamp }
        ForwardToContactDialog(
            contacts = contacts,
            isLoading = isLoadingContacts,
            selectedMessages = messagesToForward,
            onForward = { contact ->
                scope.launch {
                    onForwardResult(null)
                    val chatResponse = ApiClient.startPrivateChat(user.id, contact.contactId)
                    if (chatResponse.success && chatResponse.group != null) {
                        val success = ApiClient.sendMessageToGroup(
                            groupId = chatResponse.group!!.id,
                            userId = user.id,
                            userName = user.fullName,
                            content = buildForwardPayloadForBatch(group.name, messagesToForward),
                        )
                        if (success) {
                            onForwardResult("✅ 已转发给 ${contact.contactName}")
                            android.widget.Toast.makeText(context, "已转发 ${selectedMessages.size} 条消息给 ${contact.contactName}", android.widget.Toast.LENGTH_SHORT).show()
                            kotlinx.coroutines.delay(1000)
                            onShowForwardToContactDialog(false)
                            onSelectionModeChange(false)
                            onClearSelectedMessages()
                            onForwardResult(null)
                        } else {
                            onForwardResult("❌ 转发失败")
                        }
                    } else {
                        onForwardResult("❌ 无法创建对话: ${chatResponse.message}")
                    }
                }
            },
            onDismiss = {
                onShowForwardToContactDialog(false)
                onForwardResult(null)
            },
            result = forwardResult,
        )
    }
}

private suspend fun handleChatBackNavigation(
    appState: AppState,
    group: Group,
    user: User,
    chatClient: ChatClient,
    isExiting: Boolean,
    onExitingChange: (Boolean) -> Unit,
) {
    if (isExiting) return
    onExitingChange(true)
    chatClient.disconnect()
    chatClient.clearMessages()
    kotlinx.coroutines.delay(500)
    if (ApiClient.markGroupAsRead(user.id, group.id)) {
        println("✅ 已标记群组 ${group.id} 为已读")
    } else {
        println("⚠️ 标记已读失败")
    }
    appState.navigateBack()
}

private fun connectionStateOf(chatClient: ChatClient): ConnectionState = chatClient.connectionState.value

// ==================== AI 消息卡片组件 ====================

/**
 * AI 消息卡片 - 专门用于 Silk AI 回复的卡片样式
 * 
 * @param isExpanded 外部控制的展开状态（由父组件管理）
 * @param onExpandChange 展开/收起状态变化回调
 */
@Composable
fun AIMessageCardAndroid(
    message: Message,
    timeString: String,
    isTransient: Boolean = false,
    isExpanded: Boolean = true,
    onExpandChange: (Boolean) -> Unit = {},
    isThinkingExpanded: Boolean = false,
    onThinkingExpandChange: (Boolean) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onForward: (Message) -> Unit = {}
) {
    val cardState = remember(message.content, message.userName, isTransient, isExpanded) {
        buildAiMessageCardState(
            content = message.content,
            userName = message.userName,
            isTransient = isTransient,
            isExpanded = isExpanded,
        )
    }

    LogAiMessageCardState(
        messageId = message.id,
        bodyLength = cardState.bodyContent.length,
        isLongContent = cardState.isLongContent,
        isExpanded = isExpanded,
    )

    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF8F6F0)  // 温暖的奶白色背景
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            AIMessageCardHeader(
                state = cardState,
                timeString = timeString,
                onExpandChange = onExpandChange,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Divider(color = Color(0xFFE8E0D4), thickness = 1.dp)

            Spacer(modifier = Modifier.height(12.dp))

            AIMessageThinkingPanel(
                thinkingText = cardState.thinkingText,
                isExpanded = isThinkingExpanded,
                onExpandChange = onThinkingExpandChange,
            )
            AIMessageCardBody(state = cardState, onExpandChange = onExpandChange)
            AIMessageCardFooter(
                isTransient = isTransient,
                message = message,
                onCopy = onCopy,
                onForward = onForward,
            )
        }
    }
}

private data class AIMessageCardState(
    val displayName: String,
    val thinkingText: String,
    val bodyContent: String,
    val isTransient: Boolean,
    val isLongContent: Boolean,
    val effectiveExpanded: Boolean,
    val previewText: String,
)

private data class AIMessageContentSections(
    val thinkingText: String,
    val bodyContent: String,
)

private fun buildAiMessageCardState(
    content: String,
    userName: String,
    isTransient: Boolean,
    isExpanded: Boolean,
): AIMessageCardState {
    val sections = splitAiMessageContent(content)
    return AIMessageCardState(
        displayName = resolveAiMessageDisplayName(userName),
        thinkingText = sections.thinkingText,
        bodyContent = sections.bodyContent,
        isTransient = isTransient,
        isLongContent = sections.bodyContent.length > 500,
        effectiveExpanded = if (isTransient) true else isExpanded,
        previewText = sections.bodyContent.toAiMessagePreview(),
    )
}

private fun splitAiMessageContent(content: String): AIMessageContentSections {
    val thinkingMarker = "<!--THINKING_END-->"
    if (!content.contains(thinkingMarker)) {
        return AIMessageContentSections(
            thinkingText = "",
            bodyContent = content,
        )
    }
    return AIMessageContentSections(
        thinkingText = content.substringBefore(thinkingMarker).trim(),
        bodyContent = content.substringAfter(thinkingMarker).trim(),
    )
}

private fun resolveAiMessageDisplayName(userName: String): String =
    userName.trimStart().removePrefix("\uD83E\uDD16").trim()
        .ifBlank { "Silk AI" }
        .let { if (it == "Silk") "Silk AI" else it }

private fun String.toAiMessagePreview(maxLength: Int = 220): String =
    if (length > maxLength) take(maxLength) + "..." else this

@Composable
private fun LogAiMessageCardState(
    messageId: String,
    bodyLength: Int,
    isLongContent: Boolean,
    isExpanded: Boolean,
) {
    LaunchedEffect(messageId, isExpanded) {
        println(
            "🤖 AIMessageCardAndroid: " +
                "messageId=$messageId, bodyLength=$bodyLength, " +
                "isLongContent=$isLongContent, isExpanded=$isExpanded"
        )
    }
}

@Composable
private fun AIMessageCardHeader(
    state: AIMessageCardState,
    timeString: String,
    onExpandChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFFC9A86C), Color(0xFFA8894D)),
                    ),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("🤖", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = state.displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFC9A86C),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = timeString,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AIMessageCardExpandToggle(
            isVisible = state.isLongContent && !state.isTransient,
            isExpanded = state.effectiveExpanded,
            onExpandChange = onExpandChange,
        )
    }
}

@Composable
private fun RowScope.AIMessageCardExpandToggle(
    isVisible: Boolean,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
) {
    if (!isVisible) return

    Spacer(modifier = Modifier.weight(1f))

    Box(
        modifier = Modifier
            .background(
                color = if (isExpanded) Color.Transparent else Color(0x1AC9A86C),
                shape = RoundedCornerShape(4.dp),
            )
            .clickable { onExpandChange(!isExpanded) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = if (isExpanded) "▼ 收起" else "▶ 展开",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AIMessageThinkingPanel(
    thinkingText: String,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
) {
    if (thinkingText.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(
                color = Color(0xFFFAF8F4),
                shape = RoundedCornerShape(8.dp),
            )
            .border(1.dp, Color(0xFFE8E0D4), RoundedCornerShape(8.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandChange(!isExpanded) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isExpanded) "▼" else "▶",
                fontSize = 10.sp,
                color = Color(0xFF8B7355),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "\uD83D\uDCAD 思考过程",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF8B7355),
            )
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                Divider(color = Color(0xFFE8E0D4), thickness = 1.dp)
                Text(
                    text = thinkingText,
                    fontSize = 12.sp,
                    color = Color(0xFF8B7355),
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun AIMessageCardBody(
    state: AIMessageCardState,
    onExpandChange: (Boolean) -> Unit,
) {
    when {
        !state.isLongContent || state.isTransient -> MarkdownWebView(state.bodyContent)
        !state.effectiveExpanded -> AIMessageCollapsedBody(
            previewText = state.previewText,
            onExpand = { onExpandChange(true) },
        )
        else -> AIMessageExpandedBody(
            bodyContent = state.bodyContent,
            onCollapse = { onExpandChange(false) },
        )
    }
}

@Composable
private fun AIMessageCollapsedBody(
    previewText: String,
    onExpand: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = previewText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        AIMessageBodyToggle(
            label = "查看全文",
            arrow = "▼",
            fontSize = 13.sp,
            textColor = SilkColors.primary,
            verticalPadding = 6.dp,
            onClick = onExpand,
        )
    }
}

@Composable
private fun AIMessageExpandedBody(
    bodyContent: String,
    onCollapse: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MarkdownWebView(bodyContent)
        AIMessageBodyToggle(
            label = "收起",
            arrow = "▲",
            fontSize = 12.sp,
            textColor = SilkColors.textSecondary,
            verticalPadding = 4.dp,
            onClick = onCollapse,
        )
    }
}

@Composable
private fun AIMessageBodyToggle(
    label: String,
    arrow: String,
    fontSize: TextUnit,
    textColor: Color,
    verticalPadding: Dp,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clickable(onClick = onClick)
            .padding(vertical = verticalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            fontWeight = if (label == "查看全文") FontWeight.Medium else FontWeight.Normal,
            color = textColor,
        )
        Text("  $arrow", fontSize = if (label == "查看全文") 12.sp else 11.sp, color = textColor)
    }
}

@Composable
private fun AIMessageCardFooter(
    isTransient: Boolean,
    message: Message,
    onCopy: (String) -> Unit,
    onForward: (Message) -> Unit,
) {
    if (isTransient) {
        AIMessageTransientStatus()
        return
    }

    Spacer(modifier = Modifier.height(12.dp))
    Divider(
        color = Color(0xFFE8E0D4).copy(alpha = 0.5f),
        thickness = 0.5.dp,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        AIMessageActionButton(
            icon = "📋",
            label = "复制",
            onClick = { onCopy(message.content) },
        )
        AIMessageActionButton(
            icon = "↗",
            label = "转发",
            onClick = { onForward(message) },
        )
    }
}

@Composable
private fun AIMessageActionButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Text(icon, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AIMessageTransientStatus() {
    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "⏳", fontSize = 12.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "生成中...",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE8B86C),
        )
    }
}

// ==================== 代码语法高亮颜色配置 ====================
private val codeColors = mapOf(
    // 关键字
    "keyword" to Color(0xFFCF8E6D),      // 橙色
    "string" to Color(0xFF6AAB73),       // 绿色
    "number" to Color(0xFFB8A965),       // 黄色
    "comment" to Color(0xFF6A7B8C),      // 灰色
    "function" to Color(0xFF79C0FF),     // 蓝色
    "operator" to Color(0xFFFF79C6),     // 粉色
    "punctuation" to Color(0xFFE8E0D4),  // 浅灰
    "variable" to Color(0xFFE0E0E0),     // 白色
    "type" to Color(0xFF9CDCFE),         // 青色
    "builtin" to Color(0xFFCE9178)       // 棕色
)

private val keywordSet = setOf(
    "fun", "val", "var", "if", "else", "when", "for", "while", "do", "return",
    "class", "interface", "object", "enum", "sealed", "data", "abstract",
    "override", "private", "public", "protected", "internal", "open", "final",
    "suspend", "inline", "reified", "crossinline", "noinline",
    "import", "package", "as", "is", "in", "out", "by", "lazy", "lateinit",
    "companion", "constructor", "init", "get", "set", "where", "typealias",
    "true", "false", "null", "this", "super", "it", "self",
    "def", "lambda", "pass", "raise", "try", "except", "finally", "with",
    "yield", "global", "nonlocal", "assert", "async", "await",
    "const", "let", "function", "typeof", "new", "delete", "void",
    "static", "struct", "union", "sizeof", "typedef", "extern",
    "break", "continue", "goto", "switch", "case", "default",
    "throw", "throws", "implements", "extends", "instanceof",
    "protocol", "extension", "guard", "defer", "vararg"
)

private val builtinSet = setOf(
    "print", "println", "log", "debug", "info", "warn", "error",
    "listOf", "setOf", "mapOf", "arrayOf", "mutableListOf", "mutableSetOf",
    "mutableMapOf", "intArrayOf", "doubleArrayOf", "booleanArrayOf",
    "size", "length", "isEmpty", "isNotEmpty", "contains", "get", "put",
    "add", "remove", "clear", "first", "last", "take", "drop", "filter",
    "map", "reduce", "fold", "forEach", "apply", "also", "let", "run", "with",
    "toInt", "toString", "toDouble", "toFloat", "toLong", "toBoolean",
    "split", "join", "joinToString", "trim", "substring", "replace",
    "range", "ranges", "until", "downTo", "step",
    "len", "str", "int", "float", "bool", "dict", "tuple", "set",
    "append", "extend", "insert", "pop", "keys", "values", "items",
    "console", "window", "document", "Math", "Array", "Object", "String", "Number",
    "require", "module", "exports", "define", "setTimeout", "setInterval"
)

/**
 * 简单的代码语法高亮
 */
private fun highlightCode(code: String, language: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = code.lines()
        lines.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) append("\n")
            highlightLine(line, language)
        }
    }
}

private fun AnnotatedString.Builder.highlightLine(line: String, language: String) {
    var i = 0
    val lang = language.lowercase()
    
    while (i < line.length) {
        val currentChar = line[i]

        // 注释
        if (line.startsCodeCommentAt(i, lang)) {
            appendCodeToken("comment", line.substring(i))
            return
        }
        i = when {
            currentChar.isWhitespace() -> {
                append(currentChar)
                i + 1
            }
            currentChar.isCodeStringDelimiter() -> {
                val end = findQuotedTokenEnd(line, i)
                appendCodeToken("string", line.substring(i, end))
                end
            }
            line.isNumberTokenStartAt(i) -> {
                val end = findNumberTokenEnd(line, i)
                appendCodeToken("number", line.substring(i, end))
                end
            }
            currentChar.isLetter() || currentChar == '_' -> {
                val end = findIdentifierTokenEnd(line, i)
                val word = line.substring(i, end)
                appendCodeToken(classifyIdentifierColor(word, line, end), word)
                end
            }
            currentChar.isCodeOperatorChar() -> {
                val end = findOperatorTokenEnd(line, i)
                appendCodeToken("operator", line.substring(i, end))
                end
            }
            currentChar.isCodePunctuationChar() -> {
                appendCodeToken("punctuation", currentChar.toString())
                i + 1
            }
            else -> {
                appendCodeToken("variable", currentChar.toString())
                i + 1
            }
        }
    }
}

// ==================== 数学公式解析 ====================

/**
 * 简化的数学公式渲染
 * 支持上下标、分数、根号、希腊字母等
 */
@Composable
fun MathFormulaAndroid(formula: String, isBlock: Boolean = false) {
    val processedFormula = remember(formula) { processMathFormula(formula) }
    
    if (isBlock) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = processedFormula,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = Color(0xFF1A1A2E),
                modifier = Modifier.padding(12.dp)
            )
        }
    } else {
        Text(
            text = processedFormula,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = Color(0xFF1A1A2E)
        )
    }
}

private fun processMathFormula(formula: String): String {
    var result = formula.trim()
    
    // 移除外层分隔符
    if (result.startsWith("$$") && result.endsWith("$$")) {
        result = result.removePrefix("$$").removeSuffix("$$").trim()
    } else if (result.startsWith("$") && result.endsWith("$")) {
        result = result.removePrefix("$").removeSuffix("$").trim()
    }
    
    // 处理 \sqrt[n]{x} -> ⁿ√x
    val sqrtNPattern = Regex("""\\sqrt\[(\d+)\]\{([^}]+)\}""")
    result = sqrtNPattern.replace(result) { match ->
        "${match.groupValues[1]}√(${match.groupValues[2]})"
    }
    
    // 处理 \sqrt{x} -> √x
    val sqrtPattern = Regex("""\\sqrt\{([^}]+)\}""")
    result = sqrtPattern.replace(result) { match ->
        "√(${match.groupValues[1]})"
    }
    
    // 处理分数 \frac{a}{b} -> (a)/(b)
    val fracPattern = Regex("""\\frac\{([^{}]+)\}\{([^{}]+)\}""")
    result = fracPattern.replace(result) { match ->
        val numerator = match.groupValues[1]
        val denominator = match.groupValues[2]
        "($numerator)/($denominator)"
    }
    
    // 直接替换 LaTeX 命令
    val replacements = listOf(
        // 希腊字母（小写）
        """\alpha""" to "α", """\beta""" to "β", """\gamma""" to "γ", """\delta""" to "δ",
        """\epsilon""" to "ε", """\varepsilon""" to "ε", """\zeta""" to "ζ", """\eta""" to "η",
        """\theta""" to "θ", """\vartheta""" to "θ", """\iota""" to "ι", """\kappa""" to "κ",
        """\lambda""" to "λ", """\mu""" to "μ", """\nu""" to "ν", """\xi""" to "ξ",
        """\pi""" to "π", """\varpi""" to "π", """\rho""" to "ρ", """\varrho""" to "ρ",
        """\sigma""" to "σ", """\varsigma""" to "σ", """\tau""" to "τ", """\upsilon""" to "υ",
        """\phi""" to "φ", """\varphi""" to "φ", """\chi""" to "χ", """\psi""" to "ψ", """\omega""" to "ω",
        // 希腊字母（大写）
        """\Gamma""" to "Γ", """\Delta""" to "Δ", """\Theta""" to "Θ",
        """\Lambda""" to "Λ", """\Xi""" to "Ξ", """\Pi""" to "Π",
        """\Sigma""" to "∑", """\Phi""" to "Φ", """\Psi""" to "Ψ", """\Omega""" to "Ω",
        // 数学运算符
        """\infty""" to "∞", """\partial""" to "∂", """\nabla""" to "∇",
        """\forall""" to "∀", """\exists""" to "∃", """\in""" to "∈", """\notin""" to "∉",
        """\subset""" to "⊂", """\supset""" to "⊃", """\cup""" to "∪", """\cap""" to "∩",
        """\leq""" to "≤", """\geq""" to "≥", """\neq""" to "≠", """\approx""" to "≈",
        """\equiv""" to "≡", """\sim""" to "∼", """\propto""" to "∝",
        """\pm""" to "±", """\mp""" to "∓", """\times""" to "×", """\div""" to "÷",
        """\cdot""" to "·", """\ast""" to "∗", """\star""" to "⋆",
        """\oplus""" to "⊕", """\ominus""" to "⊖", """\otimes""" to "⊗", """\odot""" to "⊙",
        // 箭头
        """\rightarrow""" to "→", """\leftarrow""" to "←", """\Rightarrow""" to "⇒",
        """\Leftarrow""" to "⇐", """\leftrightarrow""" to "↔", """\Leftrightarrow""" to "⇔",
        """\mapsto""" to "↦", """\to""" to "→",
        // 大运算符
        """\sum""" to "∑", """\prod""" to "∏", """\int""" to "∫",
        """\iint""" to "∬", """\iiint""" to "∭", """\oint""" to "∮",
        """\lim""" to "lim", """\log""" to "log", """\ln""" to "ln",
        """\exp""" to "exp", """\min""" to "min", """\max""" to "max",
        // 其他
        """\prime""" to "′", """\degree""" to "°", """\angle""" to "∠",
        """\triangle""" to "△", """\square""" to "□", """\circ""" to "∘",
        """\vert""" to "|", """\Vert""" to "‖", """\ldots""" to "…", """\cdots""" to "⋯",
        """\vdots""" to "⋮", """\ddots""" to "⋱",
        """\Re""" to "ℜ", """\Im""" to "ℑ", """\emptyset""" to "∅",
        // 括号
        """\left(""" to "(", """\right)""" to ")",
        """\left[""" to "[", """\right]""" to "]",
        """\left{""" to "{", """\right}""" to "}",
        """\left|""" to "|", """\right|""" to "|",
        // 环境和其他命令
        """\mathbf{""" to "", """\text{""" to "", """\mathrm{""" to "",
        """\begin{aligned}""" to "", """\end{aligned}""" to "",
        """\begin{pmatrix}""" to "[", """\end{pmatrix}""" to "]",
        """\begin{bmatrix}""" to "[", """\end{bmatrix}""" to "]",
        """\begin{vmatrix}""" to "|", """\end{vmatrix}""" to "|",
        """\begin{cases}""" to "", """\end{cases}""" to "",
        // 空格
        """\,""" to " ", """\;""" to " ", """\quad""" to "  ", """\qquad""" to "    ",
        """\!""" to "",
        // 换行（LaTeX的 \\）
        """\\""" to "; "
    )
    
    for ((latex, symbol) in replacements) {
        result = result.replace(latex, symbol)
    }
    
    // Unicode 上标字符映射
    val superscriptMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'a' to 'ᵃ', 'b' to 'ᵇ',
        'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'f' to 'ᶠ', 'g' to 'ᵍ',
        'h' to 'ʰ', 'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'o' to 'ᵒ',
        'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
        'v' to 'ᵛ', 'w' to 'ʷ'
    )
    // Unicode 下标字符映射
    val subscriptMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'i' to 'ᵢ', 'o' to 'ₒ', 'r' to 'ᵣ',
        'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ', 'k' to 'ₖ', 'n' to 'ₙ',
        'p' to 'ₚ', 's' to 'ₛ', 't' to 'ₜ', 'j' to 'ⱼ'
    )
    
    // 处理上标 ^{...}
    val upperLimitPattern = Regex("""\^\{([^}]+)\}""")
    result = upperLimitPattern.replace(result) { match ->
        val content = match.groupValues[1]
        val superscript = content.map { c -> superscriptMap[c] ?: c }.joinToString("")
        superscript
    }
    
    // 处理下标 _{...}
    val lowerLimitPattern = Regex("""_\{([^}]+)\}""")
    result = lowerLimitPattern.replace(result) { match ->
        val content = match.groupValues[1]
        val subscript = content.map { c -> subscriptMap[c] ?: c }.joinToString("")
        subscript
    }
    
    // 处理单字符上标 x^n
    val superSinglePattern = Regex("""\^([0-9a-zA-Z+-])""")
    result = superSinglePattern.replace(result) { match ->
        val c = match.groupValues[1][0]
        (superscriptMap[c] ?: "^$c").toString()
    }
    
    // 处理单字符下标 x_n
    val subSinglePattern = Regex("""_([0-9a-zA-Z+-])""")
    result = subSinglePattern.replace(result) { match ->
        val c = match.groupValues[1][0]
        (subscriptMap[c] ?: "_$c").toString()
    }
    
    // 移除未配对的花括号
    result = result.replace("{", "").replace("}", "")
    
    // 清理多余的空格
    result = result.trim()
    result = result.replace(Regex("""\s+"""), " ")
    
    return result
}

// ==================== 表格解析 ====================

/**
 * Markdown 表格组件
 */
@Composable
fun MarkdownTableAndroid(lines: List<String>) {
    val table = remember(lines) { parseMarkdownTable(lines) } ?: return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (table.headerCells.isNotEmpty()) {
                MarkdownTableHeaderRow(
                    headerCells = table.headerCells,
                    maxCols = table.maxCols,
                )
                Divider(color = Color(0xFFE2E8F0), thickness = 1.dp)
            }

            table.rows.forEachIndexed { rowIndex, rowCells ->
                MarkdownTableDataRow(
                    rowCells = rowCells,
                    rowIndex = rowIndex,
                    maxCols = table.maxCols,
                    showDivider = rowIndex < table.rows.lastIndex,
                )
            }
        }
    }
}

// ==================== 任务列表项 ====================

/**
 * 任务列表项组件
 */
@Composable
fun TaskListItemAndroid(content: String, isChecked: Boolean, onToggle: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 4.dp)
            .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checkbox
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(
                    if (isChecked) Color(0xFF4CAF50) else Color.Transparent,
                    RoundedCornerShape(3.dp)
                )
                .border(
                    1.5.dp,
                    if (isChecked) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                    RoundedCornerShape(3.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isChecked) {
                Text(
                    text = "✓",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // 内容
        val textContent = content.trim()
        if (isChecked) {
            Text(
                text = textContent,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9E9E9E),
                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
            )
        } else {
            InlineMarkdownAndroid(textContent)
        }
    }
}

/**
 * Markdown 内容渲染 - Android 端 (备用实现)
 * 
 * 注意: 主要渲染已改用 MarkdownWebView (WebView + KaTeX)，能正确显示数学公式
 * 此函数保留作为备用，使用简单的 Unicode 符号替换方式处理 LaTeX
 * 
 * 支持表格、数学公式、代码高亮、任务列表、链接
 */
@Composable
fun MarkdownContentAndroid(content: String) {
    val context = LocalContext.current
    val renderItems = remember(content) { parseMarkdownContent(content) }

    Column(modifier = Modifier.fillMaxWidth()) {
        renderItems.forEach { item ->
            RenderMarkdownContentItem(item = item, context = context)
        }
    }
}

/**
 * 代码块组件 - 带语法高亮
 */
@Composable
fun CodeBlockAndroid(code: String, language: String) {
    val highlightedCode = remember(code, language) { highlightCode(code, language) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 语言标签
            if (language.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2D2D2D))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // 复制按钮
                    val context = LocalContext.current
                    androidx.compose.material3.IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("code", code)
                            clipboard.setPrimaryClip(clip)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.ContentCopy,
                            contentDescription = "复制代码",
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            // 代码内容
            SelectionContainer {
                Text(
                    text = highlightedCode,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp),
                    color = Color(0xFFE0E0E0)
                )
            }
        }
    }
}

/**
 * 行内 Markdown 渲染 - 支持链接点击和行内公式
 */
@Composable
fun InlineMarkdownAndroid(text: String, context: Context? = null) {
    val localContext = context ?: LocalContext.current

    // 处理行内数学公式
    val processedText = remember(text) { extractInlineMath(text) }

    val annotatedText = buildAnnotatedString {
        var remaining = processedText.first
        val mathSegments = processedText.second
        var offset = 0

        while (remaining.isNotEmpty()) {
            val match = findNextInlineMarkdownMatch(remaining, offset, mathSegments)
            if (match == null) {
                append(remaining)
                break
            }

            if (match.startIndex > 0) {
                append(remaining.substring(0, match.startIndex))
            }
            appendInlineMarkdownMatch(match)

            val consumedLength = (match.startIndex + match.rawLength).coerceAtMost(remaining.length)
            remaining = remaining.substring(consumedLength)
            offset += consumedLength
        }
    }

    // 渲染可点击的文本
    Text(
        text = annotatedText,
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF4A4038),
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) {
            // 点击时检查是否点击了链接
            val annotations = annotatedText.getStringAnnotations("URL", 0, annotatedText.length)
            if (annotations.isNotEmpty()) {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotations.first().item))
                    localContext.startActivity(intent)
                }.onFailure { error ->
                    Log.w("ChatScreen", "无法打开链接: ${annotations.first().item}", error)
                }
            }
        }
    )
}

@Composable
private fun ForwardedMessageBubble(
    parts: ForwardedMessageParts,
    isOwn: Boolean,
    isExpanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
) {
    val bubbleState = remember(parts, isOwn) { buildForwardedBubbleState(parts, isOwn) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(SilkColors.primary)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .background(bubbleState.backgroundColor, bubbleState.shape)
                .padding(12.dp)
        ) {
            ForwardedMessageHeader(parts = parts)
            ForwardedMessageSender(senderName = parts.senderName)

            if (!isExpanded) {
                ForwardedMessageCollapsedContent(
                    previewText = bubbleState.previewText,
                    onExpand = onExpand,
                )
            } else {
                ForwardedMessageExpandedContent(
                    body = parts.body,
                    batchItems = bubbleState.batchItems,
                    onCollapse = onCollapse,
                )
            }
        }
    }
}

@Composable
private fun ForwardedMessageHeader(parts: ForwardedMessageParts) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("📨", fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = forwardedMessageTitle(parts.isBatch),
            fontSize = 12.sp,
            color = Color(0xFF7A6B5A),
        )
        Text(
            text = " · 来自",
            fontSize = 12.sp,
            color = SilkColors.textLight,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            text = parts.sourceName.ifEmpty { "未命名会话" },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = SilkColors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ForwardedMessageSender(senderName: String) {
    if (senderName.isEmpty()) return

    Text(
        text = senderName,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = SilkColors.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
    )
}

@Composable
private fun ForwardedMessageCollapsedContent(
    previewText: String,
    onExpand: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        Text(
            text = previewText,
            fontSize = 13.sp,
            color = SilkColors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        ForwardedMessageToggleRow(
            label = "查看转发全文",
            indicator = "▼",
            onClick = onExpand,
            color = SilkColors.primary,
            fontSize = 13.sp,
            indicatorFontSize = 12.sp,
            topPadding = 10.dp,
            verticalPadding = 6.dp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ForwardedMessageExpandedContent(
    body: String,
    batchItems: List<BatchForwardItem>,
    onCollapse: () -> Unit,
) {
    if (batchItems.isNotEmpty()) {
        ForwardedMessageBatchList(batchItems = batchItems)
    } else {
        Divider(
            color = Color(0xFFE0D8CC),
            thickness = 1.dp,
            modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
        )
        MarkdownWebView(body)
    }

    ForwardedMessageToggleRow(
        label = "收起",
        indicator = "▲",
        onClick = onCollapse,
        color = SilkColors.textSecondary,
        fontSize = 12.sp,
        indicatorFontSize = 11.sp,
        topPadding = 10.dp,
        verticalPadding = 4.dp,
    )
}

@Composable
private fun ForwardedMessageBatchList(batchItems: List<BatchForwardItem>) {
    batchItems.forEachIndexed { index, item ->
        ForwardedMessageBatchItem(
            item = item,
            isFirst = index == 0,
            showDivider = index > 0,
        )
    }
}

@Composable
private fun ForwardedMessageBatchItem(
    item: BatchForwardItem,
    isFirst: Boolean,
    showDivider: Boolean,
) {
    if (showDivider) {
        Spacer(Modifier.height(14.dp))
        Divider(color = Color(0xFFE0D8CC), thickness = 1.dp)
        Spacer(Modifier.height(2.dp))
    }

    Text(
        text = item.senderName.ifEmpty { "用户" },
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = SilkColors.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (isFirst) 10.dp else 14.dp,
                bottom = 6.dp,
                start = 4.dp,
            ),
    )
    MarkdownWebView(item.content)
}

@Composable
private fun ForwardedMessageToggleRow(
    label: String,
    indicator: String,
    onClick: () -> Unit,
    color: Color,
    fontSize: TextUnit,
    indicatorFontSize: TextUnit,
    topPadding: Dp,
    verticalPadding: Dp,
    fontWeight: FontWeight? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .clickable(onClick = onClick)
            .padding(vertical = verticalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            color = color,
            fontWeight = fontWeight,
        )
        Text(
            text = "  $indicator",
            fontSize = indicatorFontSize,
            color = color,
        )
    }
}

private fun buildForwardedBubbleState(
    parts: ForwardedMessageParts,
    isOwn: Boolean,
): ForwardedMessageBubbleState {
    val batchItems = parts.takeIf(ForwardedMessageParts::isBatch)
        ?.body
        ?.let(::parseBatchForwardMarkdownBody)
        .orEmpty()
    return ForwardedMessageBubbleState(
        batchItems = batchItems,
        previewText = forwardedMessagePreviewText(parts.previewSource(batchItems)),
        backgroundColor = if (isOwn) Color(0xFFEDE4D4) else Color(0xFFFAF7F2),
        shape = forwardedMessageBubbleShape(isOwn),
    )
}

private fun forwardedMessageTitle(isBatch: Boolean): String =
    if (isBatch) "批量转发" else "转发"

private fun ForwardedMessageParts.previewSource(batchItems: List<BatchForwardItem>): String =
    batchItems.firstOrNull()?.content ?: body

private fun forwardedMessagePreviewText(rawBody: String): String {
    val normalizedBody = rawBody.replace("\r", "").trim()
    if (normalizedBody.isEmpty()) return "（无正文）"

    val firstLine = normalizedBody.lineSequence().firstOrNull(String::isNotBlank) ?: normalizedBody
    val previewLine = firstLine.ifEmpty { normalizedBody }
    val maxLength = 96
    return if (previewLine.length <= maxLength) previewLine else previewLine.take(maxLength) + "…"
}

private fun forwardedMessageBubbleShape(isOwn: Boolean): RoundedCornerShape =
    RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = if (isOwn) 12.dp else 4.dp,
        bottomEnd = if (isOwn) 4.dp else 12.dp,
    )

private enum class MessageRenderMode {
    AI,
    FILE,
    SYSTEM,
    REGULAR,
}

private data class FileMessageUiState(
    val fileName: String,
    val fileSize: Long,
    val downloadUrl: String,
)

private data class PdfMessageUiState(
    val bodyLines: List<String>,
    val pdfUrl: String?,
    val fileName: String?,
)

private data class RegularMessageVisualState(
    val bubbleColor: Color,
    val textColor: Color,
    val stepColor: Color,
    val useForwardedBubble: Boolean,
)

private fun messageRenderMode(message: Message): MessageRenderMode = when {
    isAgentUserId(message.userId) &&
        message.type == MessageType.TEXT &&
        message.category != MessageCategory.AGENT_STATUS -> MessageRenderMode.AI
    message.type == MessageType.FILE -> MessageRenderMode.FILE
    message.type == MessageType.SYSTEM -> MessageRenderMode.SYSTEM
    else -> MessageRenderMode.REGULAR
}

private fun fileMessageUiState(message: Message): FileMessageUiState {
    val fileContent = parseAndroidFileMessageContent(message.content)
    return FileMessageUiState(
        fileName = fileContent.fileName,
        fileSize = fileContent.fileSize,
        downloadUrl = fileContent.downloadUrl,
    )
}

private fun isPdfReportMessage(message: Message): Boolean =
    message.content.contains("/download/report/") && message.content.contains(".pdf")

private fun parsePdfMessageUiState(message: Message): PdfMessageUiState {
    val bodyLines = mutableListOf<String>()
    var pdfUrl: String? = null
    var fileName: String? = null

    message.content.lineSequence().forEach { line ->
        val trimmedLine = line.trim()
        if (trimmedLine.startsWith("/download/report/") && trimmedLine.contains(".pdf")) {
            pdfUrl = trimmedLine
            val encodedFileName = trimmedLine.substringAfterLast("/")
            fileName = runCatching {
                java.net.URLDecoder.decode(encodedFileName, "UTF-8")
            }.getOrElse { encodedFileName }
        } else if (trimmedLine.isNotEmpty()) {
            bodyLines += line
        }
    }

    return PdfMessageUiState(
        bodyLines = bodyLines,
        pdfUrl = pdfUrl,
        fileName = fileName,
    )
}

@Composable
private fun rememberRegularMessageVisualState(
    message: Message,
    isCurrentUser: Boolean,
    isSelected: Boolean,
    isTransient: Boolean,
    useForwardedBubble: Boolean,
): RegularMessageVisualState {
    return RegularMessageVisualState(
        bubbleColor = regularMessageBubbleColor(
            category = message.category,
            isCurrentUser = isCurrentUser,
            isSelected = isSelected,
            isTransient = isTransient,
            useForwardedBubble = useForwardedBubble,
        ),
        textColor = regularMessageTextColor(
            category = message.category,
            isCurrentUser = isCurrentUser,
            isTransient = isTransient,
        ),
        stepColor = regularMessageStepColor(isCurrentUser = isCurrentUser),
        useForwardedBubble = useForwardedBubble,
    )
}

@Composable
private fun regularMessageBubbleColor(
    category: MessageCategory,
    isCurrentUser: Boolean,
    isSelected: Boolean,
    isTransient: Boolean,
    useForwardedBubble: Boolean,
): Color = when {
    isSelected && useForwardedBubble -> Color.Transparent
    isSelected -> Color(0xFF81D4FA)
    useForwardedBubble -> Color.Transparent
    category == MessageCategory.FINAL_REPORT ->
        if (isCurrentUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    category == MessageCategory.STEP_PROCESS || category == MessageCategory.TODO_LIST ->
        regularMessageBaseBubbleColor(isCurrentUser = isCurrentUser).copy(alpha = 0.6f)
    isTransient -> regularMessageBaseBubbleColor(isCurrentUser = isCurrentUser).copy(alpha = 0.5f)
    else -> regularMessageBaseBubbleColor(isCurrentUser = isCurrentUser)
}

@Composable
private fun regularMessageTextColor(
    category: MessageCategory,
    isCurrentUser: Boolean,
    isTransient: Boolean,
): Color = when (category) {
    MessageCategory.FINAL_REPORT -> regularMessageBaseTextColor(isCurrentUser = isCurrentUser)
    MessageCategory.STEP_PROCESS,
    MessageCategory.TODO_LIST -> regularMessageBaseTextColor(isCurrentUser = isCurrentUser).copy(alpha = 0.5f)
    else -> {
        val alpha = if (isTransient) 0.4f else 1f
        regularMessageBaseTextColor(isCurrentUser = isCurrentUser).copy(alpha = alpha)
    }
}

@Composable
private fun regularMessageStepColor(isCurrentUser: Boolean): Color =
    regularMessageBaseTextColor(isCurrentUser = isCurrentUser).copy(alpha = 0.5f)

@Composable
private fun regularMessageBaseBubbleColor(isCurrentUser: Boolean): Color =
    if (isCurrentUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant

@Composable
private fun regularMessageBaseTextColor(isCurrentUser: Boolean): Color =
    if (isCurrentUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

private fun openBackendRelativeUrl(
    context: Context,
    relativeUrl: String,
    description: String,
) {
    if (relativeUrl.isEmpty()) return
    val fullUrl = "${BackendUrlHolder.getBaseUrl()}$relativeUrl"
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)))
    }.onFailure { error ->
        Log.w("ChatScreen", "无法打开$description: $fullUrl", error)
    }
}

private fun copyMessageContent(context: Context, content: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("消息", content)
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
    }.onFailure { error ->
        Log.w("ChatScreen", "复制消息失败", error)
    }
}

private fun shareMessageContent(context: Context, message: Message) {
    runCatching {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${message.userName}: ${message.content}")
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享到"))
    }.onFailure { error ->
        Log.w("ChatScreen", "分享消息失败: ${message.id}", error)
    }
}

private fun vibrateMessageLongPress(context: Context) {
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            (context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
                ?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }.onFailure { error ->
        Log.w("ChatScreen", "消息长按震动失败", error)
    }
}

@Composable
private fun FileMessageItem(
    message: Message,
    currentUserId: String,
    timeString: String,
    context: Context,
) {
    val isCurrentUser = message.userId == currentUserId
    val fileState = remember(message.content) { fileMessageUiState(message) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start,
    ) {
        MessageMetaHeader(
            message = message,
            timeString = timeString,
            isCurrentUser = isCurrentUser,
            onUserNameClick = null,
        )

        Surface(
            color = if (isCurrentUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.clickable {
                openBackendRelativeUrl(
                    context = context,
                    relativeUrl = fileState.downloadUrl,
                    description = "文件 ${fileState.fileName}",
                )
            },
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = androidFileIconForName(fileState.fileName),
                    fontSize = 32.sp,
                    modifier = Modifier.padding(end = 12.dp),
                )

                Column {
                    Text(
                        text = fileState.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isCurrentUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (fileState.fileSize > 0) formatFileSize(fileState.fileSize) else "点击查看",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCurrentUser) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        MessageOwnTimestamp(timeString = timeString, isCurrentUser = isCurrentUser)
    }
}

@Composable
private fun SystemMessageItem(message: Message) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageMetaHeader(
    message: Message,
    timeString: String,
    isCurrentUser: Boolean,
    onUserNameClick: ((Message) -> Unit)?,
) {
    if (isCurrentUser) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        if (!isAgentUserId(message.userId) && onUserNameClick != null) {
            Text(
                text = message.userName,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = SilkColors.primary,
                modifier = Modifier.clickable { onUserNameClick(message) },
            )
        } else {
            Text(
                text = message.userName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = " · $timeString",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun MessageSelectionIndicator() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(0xFF2196F3), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MessageOwnTimestamp(timeString: String, isCurrentUser: Boolean) {
    if (!isCurrentUser) return

    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = timeString,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun RegularMessageContextMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    message: Message,
    context: Context,
    canRecall: Boolean,
    isRecalling: Boolean,
    onForward: (Message) -> Unit,
    onRecall: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("消息操作", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                TextButton(
                    onClick = {
                        onDismiss()
                        copyMessageContent(context, message.content)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("📋 复制", color = MaterialTheme.colorScheme.onSurface) }

                TextButton(
                    onClick = {
                        onDismiss()
                        onForward(message)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("↗ 转发", color = MaterialTheme.colorScheme.onSurface) }

                TextButton(
                    onClick = {
                        onDismiss()
                        shareMessageContent(context, message)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("📤 分享", color = MaterialTheme.colorScheme.onSurface) }

                if (canRecall && !isRecalling) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onRecall(message.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("↩ 撤回", color = MaterialTheme.colorScheme.onSurface) }
                }

                TextButton(
                    onClick = {
                        onDismiss()
                        onLongPress(message.id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("☑️ 多选", color = MaterialTheme.colorScheme.onSurface) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun RegularMessageContent(
    message: Message,
    isCurrentUser: Boolean,
    isTransient: Boolean,
    isPdfMessage: Boolean,
    onLongContentCollapsed: (String) -> Unit,
    visualState: RegularMessageVisualState,
) {
    val forwardedParts = remember(message.content) { parseForwardedMessageContent(message.content) }
    var isForwardExpanded by remember(message.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(if (visualState.useForwardedBubble) 0.dp else 12.dp),
    ) {
        when {
            isPdfMessage -> PdfMessageContent(
                message = message,
                isCurrentUser = isCurrentUser,
                textColor = visualState.textColor,
            )
            forwardedParts != null -> ForwardedMessageBubble(
                parts = forwardedParts,
                isOwn = isCurrentUser,
                isExpanded = isForwardExpanded,
                onExpand = { isForwardExpanded = true },
                onCollapse = {
                    isForwardExpanded = false
                    onLongContentCollapsed(message.id)
                },
            )
            else -> Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = visualState.textColor,
            )
        }

        if (isTransient && message.currentStep != null && message.totalSteps != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "步骤 ${message.currentStep}/${message.totalSteps}",
                style = MaterialTheme.typography.bodySmall,
                color = visualState.stepColor,
            )
        }
    }
}

@Composable
private fun PdfMessageContent(
    message: Message,
    isCurrentUser: Boolean,
    textColor: Color,
) {
    val pdfState = remember(message.content) { parsePdfMessageUiState(message) }
    val context = LocalContext.current

    pdfState.bodyLines.forEach { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
    }

    val pdfUrl = pdfState.pdfUrl ?: return
    Spacer(modifier = Modifier.height(8.dp))

    Button(
        onClick = {
            openBackendRelativeUrl(
                context = context,
                relativeUrl = pdfUrl,
                description = "PDF 报告 ${pdfState.fileName.orEmpty()}",
            )
        },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
    ) {
        Text("📥 下载PDF报告")
    }

    val fileName = pdfState.fileName ?: return
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "文件名：$fileName",
        style = MaterialTheme.typography.bodySmall,
        color = if (isCurrentUser) {
            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        },
    )
}

@Composable
private fun RegularMessageItem(
    message: Message,
    currentUserId: String,
    timeString: String,
    context: Context,
    isTransient: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onUserNameClick: ((Message) -> Unit)?,
    canRecall: Boolean,
    isRecalling: Boolean,
    onRecall: (String) -> Unit,
    onLongContentCollapsed: (String) -> Unit,
    onForward: (Message) -> Unit,
) {
    val isCurrentUser = message.userId == currentUserId
    val isPdfMessage = remember(message.content) { isPdfReportMessage(message) }
    val forwardedParts = remember(message.content) { parseForwardedMessageContent(message.content) }
    val visualState = rememberRegularMessageVisualState(
        message = message,
        isCurrentUser = isCurrentUser,
        isSelected = isSelected,
        isTransient = isTransient,
        useForwardedBubble = forwardedParts != null && !isPdfMessage,
    )
    var showContextMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start,
    ) {
        MessageMetaHeader(
            message = message,
            timeString = timeString,
            isCurrentUser = isCurrentUser,
            onUserNameClick = onUserNameClick,
        )

        RegularMessageContextMenu(
            visible = showContextMenu,
            onDismiss = { showContextMenu = false },
            message = message,
            context = context,
            canRecall = canRecall,
            isRecalling = isRecalling,
            onForward = onForward,
            onRecall = onRecall,
            onLongPress = onLongPress,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isCurrentUser) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSelected && !isCurrentUser) {
                MessageSelectionIndicator()
                Spacer(modifier = Modifier.width(8.dp))
            }

            Box(
                modifier = regularMessageBoxModifier(
                    isTransient = isTransient,
                    isSelected = isSelected,
                    isSelectionMode = isSelectionMode,
                    messageId = message.id,
                    onToggleSelection = onToggleSelection,
                    onShowContextMenu = { showContextMenu = true },
                    onVibrate = { vibrateMessageLongPress(context) },
                ),
            ) {
                Surface(
                    color = visualState.bubbleColor,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = if (isTransient) 0.5.dp else 1.dp,
                ) {
                    RegularMessageContent(
                        message = message,
                        isCurrentUser = isCurrentUser,
                        isTransient = isTransient,
                        isPdfMessage = isPdfMessage,
                        onLongContentCollapsed = onLongContentCollapsed,
                        visualState = visualState,
                    )
                }
            }

            if (isSelected && isCurrentUser) {
                Spacer(modifier = Modifier.width(8.dp))
                MessageSelectionIndicator()
            }
        }

        MessageOwnTimestamp(timeString = timeString, isCurrentUser = isCurrentUser)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.regularMessageBoxModifier(
    isTransient: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    messageId: String,
    onToggleSelection: (String) -> Unit,
    onShowContextMenu: () -> Unit,
    onVibrate: () -> Unit,
): Modifier {
    if (isTransient) return Modifier.weight(1f, fill = false)

    return Modifier
        .weight(1f, fill = false)
        .background(
            if (isSelected) Color(0xFF4FC3F7).copy(alpha = 0.4f) else Color.Transparent,
            shape = MaterialTheme.shapes.medium,
        )
        .combinedClickable(
            onClick = {
                if (isSelectionMode) {
                    onToggleSelection(messageId)
                }
            },
            onLongClick = {
                if (!isSelectionMode) {
                    onShowContextMenu()
                    onVibrate()
                }
            },
        )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: Message,
    currentUserId: String,
    context: android.content.Context,
    isTransient: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: (String) -> Unit = {},
    onLongPress: (String) -> Unit = {},
    onUserNameClick: ((Message) -> Unit)? = null,
    // 撤回功能相关参数
    isRecalling: Boolean = false,
    onRecall: (String) -> Unit = {},
    // AI 消息展开状态相关参数
    isAIExpanded: Boolean = true,
    onAIExpandChange: (String, Boolean) -> Unit = { _, _ -> },
    // AI 思考过程展开状态相关参数
    isThinkingExpanded: Boolean = false,
    onThinkingExpandChange: (String, Boolean) -> Unit = { _, _ -> },
    /** 长文本/转发全文收起后由列表滚回该条，与 Harmony onLongContentCollapsed 一致 */
    onLongContentCollapsed: (String) -> Unit = {},
    // 复制和转发功能相关参数
    onCopy: (String) -> Unit = {},
    onForward: (Message) -> Unit = {}
) {
    val isCurrentUser = message.userId == currentUserId
    val renderMode = remember(message.userId, message.type, message.category) { messageRenderMode(message) }
    val canRecall = isCurrentUser &&
        !isAgentUserId(message.userId) &&
        renderMode != MessageRenderMode.SYSTEM &&
        !isTransient
    val timeString = formatMessageTimestamp(message.timestamp)
    when (renderMode) {
        MessageRenderMode.AI -> AIMessageCardAndroid(
            message = message,
            timeString = timeString,
            isTransient = isTransient,
            isExpanded = isAIExpanded,
            onExpandChange = { newExpanded -> onAIExpandChange(message.id, newExpanded) },
            isThinkingExpanded = isThinkingExpanded,
            onThinkingExpandChange = { newExpanded -> onThinkingExpandChange(message.id, newExpanded) },
            onCopy = onCopy,
            onForward = onForward,
        )
        MessageRenderMode.FILE -> FileMessageItem(
            message = message,
            currentUserId = currentUserId,
            timeString = timeString,
            context = context,
        )
        MessageRenderMode.SYSTEM -> SystemMessageItem(message = message)
        MessageRenderMode.REGULAR -> RegularMessageItem(
            message = message,
            currentUserId = currentUserId,
            timeString = timeString,
            context = context,
            isTransient = isTransient,
            isSelectionMode = isSelectionMode,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onLongPress = onLongPress,
            onUserNameClick = onUserNameClick,
            canRecall = canRecall,
            isRecalling = isRecalling,
            onRecall = onRecall,
            onLongContentCollapsed = onLongContentCollapsed,
            onForward = onForward,
        )
    }
}

@Composable
fun InvitationDialog(
    group: Group,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("邀请成员") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("分享以下信息邀请其他人加入群组：")
                
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "群组名称",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Divider()
                        
                        Text(
                            text = "邀请码",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = group.invitationCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val shareText = """
                        🎀 加入我的 Silk 群组！
                        
                        群组名称：${group.name}
                        邀请码：${group.invitationCode}
                        
                        📱 下载/访问 Silk：
                        • Android APK: ${BackendUrlHolder.getBaseUrl()}/api/files/download-apk
                        • Web 网页版: ${BackendUrlHolder.getBaseUrl().substringBeforeLast(":")}:${BuildConfig.FRONTEND_PORT}
                        
                        打开 Silk，点击"加入群组"，输入邀请码即可加入！
                    """.trimIndent()
                    
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享邀请"))
                    onDismiss()
                }
            ) {
                Text("分享")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * 文件项数据类
 */
data class FileItem(
    val name: String,
    val size: Long,
    val uploadTime: Long,
    val uploadedBy: String,
    val downloadUrl: String = ""
)

/**
 * 文件夹浏览对话框
 */
@Composable
fun FolderExplorerDialog(
    files: List<FileItem>,
    processedUrls: List<String>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onFileClick: (FileItem) -> Unit,
    onUrlClick: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f),
            shape = MaterialTheme.shapes.large,
            color = SilkColors.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 标题栏 - Silk 风格
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    SilkColors.primary,
                                    SilkColors.primary.copy(alpha = 0.8f)
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "📁",
                                fontSize = 24.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "会话资源",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Text("✕", color = Color.White, fontSize = 20.sp)
                        }
                    }
                }
                
                // 内容列表
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = SilkColors.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("加载中...", color = SilkColors.textSecondary)
                        }
                    }
                } else if (files.isEmpty() && processedUrls.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📂", fontSize = 48.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "暂无资源",
                                color = SilkColors.textSecondary,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "上传文件或发送URL后将在这里显示",
                                color = SilkColors.textSecondary.copy(alpha = 0.7f),
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        // 1️⃣ 首先显示已下载的 URL 清单
                        if (processedUrls.isNotEmpty()) {
                            item {
                                Text(
                                    text = "🔗 已下载的网页 (${processedUrls.size})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SilkColors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                            }
                            items(processedUrls) { url ->
                                UrlItemCard(
                                    url = url,
                                    onClick = { onUrlClick(url) }
                                )
                            }
                            
                            // 分隔线
                            if (files.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }
                        
                        // 2️⃣ 然后显示文件列表
                        if (files.isNotEmpty()) {
                            item {
                                Text(
                                    text = "📁 上传的文件 (${files.size})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SilkColors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                            }
                            items(files) { file ->
                                FileItemCard(
                                    file = file,
                                    onClick = { onFileClick(file) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * URL 项卡片
 */
@Composable
fun UrlItemCard(
    url: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF0FFF4)  // 淡绿色背景
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🌐",
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = url,
                    fontSize = 13.sp,
                    color = SilkColors.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 已索引标记
            Box(
                modifier = Modifier
                    .background(
                        color = Color(0xFF48BB78),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "✓ 已索引",
                    fontSize = 11.sp,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * 文件项卡片
 */
@Composable
fun FileItemCard(
    file: FileItem,
    onClick: () -> Unit
) {
    val fileIcon = remember(file.name) { file.name.toFileCardIcon() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = SilkColors.cardBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fileIcon,
                fontSize = 32.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontWeight = FontWeight.Medium,
                    color = SilkColors.textPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${formatFileSize(file.size)} · ${formatTime(file.uploadTime)}",
                    color = SilkColors.textSecondary,
                    fontSize = 12.sp
                )
            }
            Text(
                text = "⬇️",
                fontSize = 20.sp,
                color = SilkColors.primary
            )
        }
    }
}

private fun String.toFileCardIcon(): String {
    val normalizedName = lowercase()
    return fileCardIconMappings
        .firstOrNull { (_, extensions) -> extensions.any(normalizedName::endsWith) }
        ?.first
        ?: "📎"
}

/**
 * 从 Uri 获取文件名
 */
fun getFileName(context: android.content.Context, uri: Uri): String {
    return queryDisplayName(context, uri)
        ?: uri.path?.substringAfterLast('/')
        ?: "unknown_file"
}

/**
 * 上传文件
 * 后端 API: POST /api/files/upload
 * 表单字段: sessionId, userId, file
 */
suspend fun uploadFile(
    inputStream: InputStream,
    fileName: String,
    sessionId: String,
    userId: String,
    onProgress: (String) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val boundary = "===" + System.currentTimeMillis() + "==="
        val url = URL("${BackendUrlHolder.getBaseUrl()}/api/files/upload")
        val connection = AndroidHttpCompat.openConnection(url)
        
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.doInput = true
        connection.useCaches = false
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setRequestProperty("Connection", "Keep-Alive")
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        
        val outputStream = connection.outputStream
        val writer = java.io.PrintWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"), true)
        
        // 写入 sessionId 字段
        writer.append("--$boundary").append("\r\n")
        writer.append("Content-Disposition: form-data; name=\"sessionId\"").append("\r\n")
        writer.append("\r\n")
        writer.append(sessionId).append("\r\n")
        
        // 写入 userId 字段
        writer.append("--$boundary").append("\r\n")
        writer.append("Content-Disposition: form-data; name=\"userId\"").append("\r\n")
        writer.append("\r\n")
        writer.append(userId).append("\r\n")
        
        // 写入文件部分
        writer.append("--$boundary").append("\r\n")
        writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"").append("\r\n")
        writer.append("Content-Type: application/octet-stream").append("\r\n")
        writer.append("\r\n")
        writer.flush()
        
        // 写入文件内容
        val buffer = ByteArray(4096)
        var bytesRead: Int
        var totalBytesRead = 0L
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            onProgress("已上传 ${formatFileSize(totalBytesRead)}")
        }
        outputStream.flush()
        inputStream.close()
        
        // 写入结束边界
        writer.append("\r\n")
        writer.append("--$boundary--").append("\r\n")
        writer.flush()
        writer.close()
        
        val responseCode = connection.responseCode
        connection.disconnect()
        
        responseCode == 200 || responseCode == 201
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        Log.w("ChatScreen", "文件上传失败: $fileName", error)
        false
    }
}

/**
 * 文件列表和 URL 清单的响应数据
 */
data class FilesAndUrls(
    val files: List<FileItem>,
    val processedUrls: List<String>
)

/**
 * 加载群组文件列表和已处理的 URL
 */
suspend fun loadGroupFilesAndUrls(groupId: String): FilesAndUrls = withContext(Dispatchers.IO) {
    runCatching {
        val url = URL("${BackendUrlHolder.getBaseUrl()}/api/files/list/$groupId")
        val connection = AndroidHttpCompat.openConnection(url)
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        
        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            // 解析 JSON
            parseFileListAndUrls(response)
        } else {
            FilesAndUrls(emptyList(), emptyList())
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        Log.w("ChatScreen", "加载群组文件列表失败: $groupId", error)
        FilesAndUrls(emptyList(), emptyList())
    }
}

private suspend fun runLoggedSuspendAction(
    failurePrefix: String,
    log: (String) -> Unit,
    block: suspend () -> Unit,
) {
    runCatching {
        block()
    }.onFailure { error ->
        if (error is CancellationException) throw error
        log("$failurePrefix: ${error.message}")
    }
}

private suspend fun disconnectChatClientQuietly(
    chatClient: ChatClient,
    log: (String) -> Unit,
) {
    runCatching {
        chatClient.disconnect()
    }.onFailure { error ->
        if (error is CancellationException) throw error
        log("⚠️ 切换会话前断开旧连接失败: ${error.message}")
    }
}

/**
 * 格式化文件大小
 */
fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}

/**
 * 格式化时间 - 使用上海时区 (UTC+8)
 */
fun formatTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return sdf.format(Date(timestamp))
}

/**
 * 格式化时间为 HH:mm 格式 - 使用上海时区 (UTC+8)
 */
fun formatTimeHM(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return sdf.format(Date(timestamp))
}

/**
 * 格式化时间为 HH:mm:ss 格式 - 使用上海时区 (UTC+8)
 */
fun formatTimeHMS(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return sdf.format(Date(timestamp))
}


/**
 * 添加成员对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMemberDialog(
    contacts: List<Contact>,
    groupMembers: List<GroupMember>,
    isLoading: Boolean,
    result: String?,
    onAddMember: (Contact) -> Unit,
    onDismiss: () -> Unit
) {
    // 过滤出不在群组中的联系人
    val memberIds = groupMembers.map { it.id }.toSet()
    val availableContacts = contacts.filter { it.contactId !in memberIds }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                Text(
                    text = "➕ 添加成员到群组",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // 结果提示
                result?.let {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        color = if (it.startsWith("✅")) 
                            Color(0xFFE8F5E9) 
                        else 
                            Color(0xFFFFEBEE),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(12.dp),
                            color = if (it.startsWith("✅")) 
                                Color(0xFF2E7D32) 
                            else 
                                Color(0xFFC62828)
                        )
                    }
                }
                
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (availableContacts.isEmpty()) {
                    Text(
                        text = "没有可添加的联系人\n（所有联系人已在群组中）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    // 联系人列表
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availableContacts) { contact ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = contact.contactName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = contact.contactPhone,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    
                                    Button(
                                        onClick = { onAddMember(contact) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text("添加")
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 关闭按钮
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

/**
 * 群组成员列表对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersDialog(
    members: List<GroupMember>,
    contacts: List<Contact>,
    currentUserId: String,
    isLoading: Boolean,
    onMemberClick: (GroupMember) -> Unit,
    onDismiss: () -> Unit
) {
    val contactIds = remember(contacts) { contacts.map(Contact::contactId).toSet() }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                MembersDialogHeader(memberCount = members.size)
                MembersDialogBody(
                    members = members,
                    contactIds = contactIds,
                    currentUserId = currentUserId,
                    isLoading = isLoading,
                    onMemberClick = onMemberClick,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun MembersDialogHeader(memberCount: Int) {
    Text(
        text = "👥 群组成员 ($memberCount)",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 16.dp),
    )
}

@Composable
private fun ColumnScope.MembersDialogBody(
    members: List<GroupMember>,
    contactIds: Set<String>,
    currentUserId: String,
    isLoading: Boolean,
    onMemberClick: (GroupMember) -> Unit,
) {
    when {
        isLoading -> MembersDialogLoadingState()
        members.isEmpty() -> MembersDialogEmptyState()
        else -> MembersDialogList(
            members = members,
            contactIds = contactIds,
            currentUserId = currentUserId,
            onMemberClick = onMemberClick,
        )
    }
}

@Composable
private fun MembersDialogLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MembersDialogEmptyState() {
    Text(
        text = "暂无成员",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(20.dp),
    )
}

@Composable
private fun ColumnScope.MembersDialogList(
    members: List<GroupMember>,
    contactIds: Set<String>,
    currentUserId: String,
    onMemberClick: (GroupMember) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(members) { member ->
            MembersDialogMemberRow(
                state = member.toMembersDialogState(
                    currentUserId = currentUserId,
                    contactIds = contactIds,
                ),
                onClick = { onMemberClick(member) },
            )
        }
    }
}

@Composable
private fun MembersDialogMemberRow(
    state: MembersDialogMemberState,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (state.isClickable) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MembersDialogMemberAvatar(state)
                MembersDialogMemberDetails(state)
            }
            MembersDialogMemberAction(state)
        }
    }
}

@Composable
private fun MembersDialogMemberAvatar(state: MembersDialogMemberState) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = MaterialTheme.shapes.medium,
        color = state.avatarColor,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = state.avatarText,
                color = Color.White,
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun MembersDialogMemberDetails(state: MembersDialogMemberState) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (state.isCurrentUser) {
                Text(
                    text = " (我)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = state.statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MembersDialogMemberAction(state: MembersDialogMemberState) {
    state.actionEmoji?.let { emoji ->
        Text(
            text = emoji,
            fontSize = 20.sp,
        )
    }
}

@Composable
private fun GroupMember.toMembersDialogState(
    currentUserId: String,
    contactIds: Set<String>,
): MembersDialogMemberState {
    val isCurrentUser = id == currentUserId
    val isSilkAI = isAgentUserId(id)
    val isContact = id in contactIds
    return MembersDialogMemberState(
        displayName = fullName,
        isCurrentUser = isCurrentUser,
        isClickable = !isCurrentUser && !isSilkAI,
        statusText = membersDialogStatusText(
            isSilkAI = isSilkAI,
            isCurrentUser = isCurrentUser,
            isContact = isContact,
        ),
        avatarText = membersDialogAvatarText(
            displayName = fullName,
            isSilkAI = isSilkAI,
            isCurrentUser = isCurrentUser,
            isContact = isContact,
        ),
        avatarColor = membersDialogAvatarColor(
            isSilkAI = isSilkAI,
            isCurrentUser = isCurrentUser,
            isContact = isContact,
        ),
        actionEmoji = membersDialogActionEmoji(
            isSilkAI = isSilkAI,
            isCurrentUser = isCurrentUser,
            isContact = isContact,
        ),
    )
}

private fun membersDialogStatusText(
    isSilkAI: Boolean,
    isCurrentUser: Boolean,
    isContact: Boolean,
): String = when {
    isSilkAI -> "AI 助手"
    isCurrentUser -> "当前用户"
    isContact -> "联系人 · 点击聊天"
    else -> "点击添加联系人"
}

private fun membersDialogAvatarText(
    displayName: String,
    isSilkAI: Boolean,
    isCurrentUser: Boolean,
    isContact: Boolean,
): String = when {
    isSilkAI -> "🤖"
    isCurrentUser -> "👤"
    isContact -> "✓"
    else -> displayName.firstOrNull()?.toString() ?: "?"
}

@Composable
private fun membersDialogAvatarColor(
    isSilkAI: Boolean,
    isCurrentUser: Boolean,
    isContact: Boolean,
): Color = when {
    isSilkAI -> SilkColors.info
    isCurrentUser -> SilkColors.primary
    isContact -> SilkColors.success
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun membersDialogActionEmoji(
    isSilkAI: Boolean,
    isCurrentUser: Boolean,
    isContact: Boolean,
): String? = when {
    isCurrentUser || isSilkAI -> null
    isContact -> "💬"
    else -> "➕"
}

/**
 * 转发到群组对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardToGroupDialog(
    groups: List<Group>,
    isLoading: Boolean,
    selectedMessages: List<Message>,
    onForward: (Group) -> Unit,
    onDismiss: () -> Unit,
    result: String?
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = SilkColors.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "💬 转发到对话",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SilkColors.primary
                    )
                    Text(
                        text = "${selectedMessages.size} 条消息",
                        style = MaterialTheme.typography.bodySmall,
                        color = SilkColors.textSecondary
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 预览选中的消息
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp),
                    color = SilkColors.cardBackground,
                    shape = MaterialTheme.shapes.small
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        items(selectedMessages.take(3)) { msg ->
                            Text(
                                text = "${msg.userName}: ${msg.content.take(50)}${if (msg.content.length > 50) "..." else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SilkColors.textSecondary,
                                maxLines = 1
                            )
                        }
                        if (selectedMessages.size > 3) {
                            item {
                                Text(
                                    text = "... 还有 ${selectedMessages.size - 3} 条消息",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SilkColors.textSecondary
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 结果提示
                result?.let {
                    Text(
                        text = it,
                        color = if (it.startsWith("✅")) SilkColors.success else SilkColors.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                // 群组列表
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SilkColors.primary)
                    }
                } else if (groups.isEmpty()) {
                    Text(
                        text = "没有其他对话可转发",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SilkColors.textSecondary,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(groups) { targetGroup ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onForward(targetGroup) },
                                colors = CardDefaults.cardColors(
                                    containerColor = SilkColors.cardBackground
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text("💬", fontSize = 24.sp)
                                        Text(
                                            text = targetGroup.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Text("➡️", fontSize = 18.sp)
                                }
                            }
                        }
                    }
                }
                
                // 取消按钮
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text("取消", color = SilkColors.textSecondary)
                }
            }
        }
    }
}

/**
 * 转发到联系人对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardToContactDialog(
    contacts: List<Contact>,
    isLoading: Boolean,
    selectedMessages: List<Message>,
    onForward: (Contact) -> Unit,
    onDismiss: () -> Unit,
    result: String?
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = SilkColors.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "👤 转发给联系人",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SilkColors.primary
                    )
                    Text(
                        text = "${selectedMessages.size} 条消息",
                        style = MaterialTheme.typography.bodySmall,
                        color = SilkColors.textSecondary
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 预览选中的消息
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp),
                    color = SilkColors.cardBackground,
                    shape = MaterialTheme.shapes.small
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        items(selectedMessages.take(3)) { msg ->
                            Text(
                                text = "${msg.userName}: ${msg.content.take(50)}${if (msg.content.length > 50) "..." else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SilkColors.textSecondary,
                                maxLines = 1
                            )
                        }
                        if (selectedMessages.size > 3) {
                            item {
                                Text(
                                    text = "... 还有 ${selectedMessages.size - 3} 条消息",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SilkColors.textSecondary
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 结果提示
                result?.let {
                    Text(
                        text = it,
                        color = if (it.startsWith("✅")) SilkColors.success else SilkColors.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                // 联系人列表
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SilkColors.primary)
                    }
                } else if (contacts.isEmpty()) {
                    Text(
                        text = "暂无联系人",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SilkColors.textSecondary,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(contacts) { contact ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onForward(contact) },
                                colors = CardDefaults.cardColors(
                                    containerColor = SilkColors.cardBackground
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(40.dp),
                                            shape = MaterialTheme.shapes.medium,
                                            color = SilkColors.primary
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                Text(
                                                    text = contact.contactName.firstOrNull()?.toString() ?: "?",
                                                    color = Color.White,
                                                    fontSize = 18.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Column {
                                            Text(
                                                text = contact.contactName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = contact.contactPhone,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = SilkColors.textSecondary
                                            )
                                        }
                                    }
                                    Text("➡️", fontSize = 18.sp)
                                }
                            }
                        }
                    }
                }
                
                // 取消按钮
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text("取消", color = SilkColors.textSecondary)
                }
            }
        }
    }
}

/**
 * 提取行内数学公式
 * 支持 $...$ 和 \(...\) 格式
 * 返回处理后的文本和数学公式的位置列表
 */
private fun extractInlineMath(text: String): Pair<String, List<Pair<Int, String>>> {
    val mathSegments = mutableListOf<Pair<Int, String>>()
    val result = StringBuilder()
    var i = 0
    var offset = 0

    while (i < text.length) {
        val mathMatch = findInlineMathMatch(text, i)
        if (mathMatch != null) {
            mathSegments.add(offset to mathMatch.processedText)
            result.append(mathMatch.processedText)
            offset += mathMatch.processedText.length
            i += mathMatch.rawLength
        } else {
            result.append(text[i])
            offset++
            i++
        }
    }

    return Pair(result.toString(), mathSegments)
}

private fun shouldShowEmptyChatState(
    messages: List<Message>,
    transientMessage: Message?,
    statusMessages: List<Message>,
    isWaitingForAI: Boolean,
): Boolean {
    if (messages.isNotEmpty()) return false
    if (transientMessage != null) return false
    if (statusMessages.isNotEmpty()) return false
    return !isWaitingForAI
}

private fun AnnotatedString.Builder.appendCodeToken(
    colorKey: String,
    text: String,
) {
    withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors.getValue(colorKey))) {
        append(text)
    }
}

private fun String.startsCodeCommentAt(index: Int, language: String): Boolean {
    if (startsWith("//", startIndex = index)) return true
    return language == "python" && this[index] == '#'
}

private fun Char.isCodeStringDelimiter(): Boolean = this == '"' || this == '\'' || this == '`'

private fun String.isNumberTokenStartAt(index: Int): Boolean {
    val current = this[index]
    if (current.isDigit()) return true
    if (current != '-') return false
    val nextIndex = index + 1
    return nextIndex < length && this[nextIndex].isDigit()
}

private fun Char.isNumberTokenChar(): Boolean = isDigit() || this == '.' || this == 'x' || this == 'X'

private fun findQuotedTokenEnd(line: String, startIndex: Int): Int {
    var index = startIndex + 1
    val quote = line[startIndex]
    while (index < line.length) {
        val currentChar = line[index]
        index += if (currentChar == '\\' && index + 1 < line.length) 2 else 1
        if (currentChar == quote) {
            break
        }
    }
    return index
}

private fun findNumberTokenEnd(line: String, startIndex: Int): Int {
    var index = if (line[startIndex] == '-') startIndex + 1 else startIndex
    while (index < line.length && line[index].isNumberTokenChar()) {
        index++
    }
    return index
}

private fun findIdentifierTokenEnd(line: String, startIndex: Int): Int {
    var index = startIndex
    while (index < line.length && (line[index].isLetterOrDigit() || line[index] == '_')) {
        index++
    }
    return index
}

private fun classifyIdentifierColor(
    word: String,
    line: String,
    endIndex: Int,
): String = when {
    keywordSet.contains(word) -> "keyword"
    builtinSet.contains(word) -> "builtin"
    line.hasFunctionCallAt(endIndex) -> "function"
    word.first().isUpperCase() -> "type"
    else -> "variable"
}

private fun String.hasFunctionCallAt(index: Int): Boolean = index < length && this[index] == '('

private fun Char.isCodeOperatorChar(): Boolean = this in "+-*/=!<>&|^~?:"

private fun findOperatorTokenEnd(line: String, startIndex: Int): Int {
    var index = startIndex
    while (index < line.length && line[index].isCodeOperatorChar()) {
        index++
    }
    return index
}

private fun Char.isCodePunctuationChar(): Boolean = this in "(){}[],;."

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    if (uri.scheme != "content") return null
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (columnIndex < 0) return@use null
        cursor.getString(columnIndex)
    }
}

private fun String.startsInlineDollarMathAt(index: Int): Boolean {
    if (this[index] != '$') return false
    if (index > 0 && this[index - 1] == '$') return false
    return index + 1 >= length || this[index + 1] != '$'
}

private enum class InlineMarkdownMatchType {
    Math,
    Link,
    Bold,
    Italic,
    Code,
}

private data class ForwardedMessageBubbleState(
    val batchItems: List<BatchForwardItem>,
    val previewText: String,
    val backgroundColor: Color,
    val shape: RoundedCornerShape,
)

private data class MembersDialogMemberState(
    val displayName: String,
    val isCurrentUser: Boolean,
    val isClickable: Boolean,
    val statusText: String,
    val avatarText: String,
    val avatarColor: Color,
    val actionEmoji: String?,
)

private data class ParsedMarkdownTable(
    val headerCells: List<String>,
    val rows: List<List<String>>,
    val maxCols: Int,
)

private sealed interface MarkdownContentItem

private data class MarkdownHeadingItem(
    val text: String,
    val level: Int,
    val topSpacingDp: Int,
    val bottomSpacingDp: Int,
) : MarkdownContentItem

private data class MarkdownParagraphItem(
    val text: String,
) : MarkdownContentItem

private data class MarkdownTaskListItem(
    val text: String,
    val isChecked: Boolean,
) : MarkdownContentItem

private data class MarkdownUnorderedListItem(
    val text: String,
) : MarkdownContentItem

private data class MarkdownOrderedListItem(
    val number: String,
    val text: String,
) : MarkdownContentItem

private data object MarkdownDividerItem : MarkdownContentItem

private data class MarkdownQuoteItem(
    val text: String,
) : MarkdownContentItem

private data class MarkdownTableBlockItem(
    val lines: List<String>,
) : MarkdownContentItem

private data class MarkdownCodeBlockItem(
    val code: String,
    val language: String,
) : MarkdownContentItem

private data class MarkdownMathBlockItem(
    val text: String,
) : MarkdownContentItem

private data object MarkdownBlankLineItem : MarkdownContentItem

private data class MarkdownParseState(
    var inCodeBlock: Boolean = false,
    val codeBlockContent: StringBuilder = StringBuilder(),
    var codeLanguage: String = "",
    var inTable: Boolean = false,
    val tableLines: MutableList<String> = mutableListOf(),
    var inMathBlock: Boolean = false,
    val mathBlockContent: StringBuilder = StringBuilder(),
)

private data class InlineMarkdownMatch(
    val startIndex: Int,
    val rawLength: Int,
    val displayText: String,
    val type: InlineMarkdownMatchType,
    val annotation: String? = null,
)

private data class InlineMathMatch(
    val rawLength: Int,
    val processedText: String,
)

private fun parseMarkdownContent(content: String): List<MarkdownContentItem> {
    val lines = content.split("\n")
    val items = mutableListOf<MarkdownContentItem>()
    val state = MarkdownParseState()

    lines.forEachIndexed { index, line ->
        if (state.inTable && !line.trim().startsWith("|")) {
            flushTrailingMarkdownTable(state, items)
        }
        if (consumeMarkdownMathLine(line, state, items)) return@forEachIndexed
        if (consumeMarkdownCodeLine(line, state, items)) return@forEachIndexed
        if (consumeMarkdownTableLine(line, state)) return@forEachIndexed

        classifyMarkdownLine(
            line = line,
            index = index,
            lines = lines,
        )?.let(items::add)
    }

    flushTrailingMarkdownTable(state, items)
    return items
}

private fun consumeMarkdownMathLine(
    line: String,
    state: MarkdownParseState,
    items: MutableList<MarkdownContentItem>,
): Boolean {
    val trimmedLine = line.trim()
    if (!state.inCodeBlock && trimmedLine.startsMarkdownMathBlock()) {
        if (state.inMathBlock) {
            flushMarkdownMathBlock(state, items)
            state.inMathBlock = false
        } else {
            startMarkdownMathBlock(trimmedLine, state, items)
        }
        return true
    }
    if (state.inMathBlock && trimmedLine.isMarkdownMathBlockEndMarker()) {
        flushMarkdownMathBlock(state, items)
        state.inMathBlock = false
        return true
    }
    if (state.inMathBlock) {
        state.mathBlockContent.append(line).append("\n")
        return true
    }
    return false
}

private fun startMarkdownMathBlock(
    trimmedLine: String,
    state: MarkdownParseState,
    items: MutableList<MarkdownContentItem>,
) {
    val marker = markdownMathBlockMarker(trimmedLine)
    val startMarker = marker.first
    val endMarker = marker.second
    if (trimmedLine.endsWith(endMarker) && trimmedLine.length > startMarker.length + endMarker.length) {
        val mathContent = trimmedLine.removePrefix(startMarker).removeSuffix(endMarker).trim()
        items += MarkdownMathBlockItem(mathContent)
        return
    }
    state.inMathBlock = true
    val firstContent = trimmedLine.removePrefix(startMarker).trim()
    if (firstContent.isNotEmpty()) {
        state.mathBlockContent.append(firstContent).append("\n")
    }
}

private fun flushMarkdownMathBlock(
    state: MarkdownParseState,
    items: MutableList<MarkdownContentItem>,
) {
    if (state.mathBlockContent.isNotEmpty()) {
        items += MarkdownMathBlockItem(state.mathBlockContent.toString().trim())
        state.mathBlockContent.setLength(0)
    }
}

private fun consumeMarkdownCodeLine(
    line: String,
    state: MarkdownParseState,
    items: MutableList<MarkdownContentItem>,
): Boolean {
    val trimmedLine = line.trim()
    if (!trimmedLine.startsWith("```")) {
        if (state.inCodeBlock) {
            state.codeBlockContent.append(line).append("\n")
            return true
        }
        return false
    }

    if (state.inCodeBlock) {
        if (state.codeBlockContent.isNotEmpty()) {
            items += MarkdownCodeBlockItem(
                code = state.codeBlockContent.toString().trimEnd(),
                language = state.codeLanguage,
            )
            state.codeBlockContent.setLength(0)
        }
        state.inCodeBlock = false
        state.codeLanguage = ""
    } else {
        state.inCodeBlock = true
        state.codeLanguage = trimmedLine.removePrefix("```").trim()
    }
    return true
}

private fun consumeMarkdownTableLine(
    line: String,
    state: MarkdownParseState,
): Boolean {
    val trimmedLine = line.trim()
    if (trimmedLine.startsWith("|") && trimmedLine.endsWith("|")) {
        if (!state.inTable) {
            state.inTable = true
            state.tableLines.clear()
        }
        state.tableLines.add(line)
        return true
    }
    return false
}

private fun flushTrailingMarkdownTable(
    state: MarkdownParseState,
    items: MutableList<MarkdownContentItem>,
) {
    if (state.inTable && state.tableLines.isNotEmpty()) {
        items += MarkdownTableBlockItem(state.tableLines.toList())
        state.tableLines.clear()
    }
    state.inTable = false
}

private fun classifyMarkdownLine(
    line: String,
    index: Int,
    lines: List<String>,
): MarkdownContentItem? =
    parseMarkdownHeadingItem(line, index)
        ?: parseMarkdownTaskListItem(line)
        ?: parseMarkdownUnorderedListItem(line)
        ?: parseMarkdownOrderedListItem(line)
        ?: parseMarkdownDividerItem(line)
        ?: parseMarkdownQuoteItem(line)
        ?: parseMarkdownParagraphItem(line)
        ?: parseMarkdownBlankLineItem(index, lines)

private fun parseMarkdownHeadingItem(
    line: String,
    index: Int,
): MarkdownHeadingItem? = when {
    line.startsWith("### ") -> MarkdownHeadingItem(
        text = line.removePrefix("### "),
        level = 3,
        topSpacingDp = if (index > 0) 8 else 0,
        bottomSpacingDp = 4,
    )

    line.startsWith("## ") -> MarkdownHeadingItem(
        text = line.removePrefix("## "),
        level = 2,
        topSpacingDp = if (index > 0) 12 else 0,
        bottomSpacingDp = 6,
    )

    line.startsWith("# ") -> MarkdownHeadingItem(
        text = line.removePrefix("# "),
        level = 1,
        topSpacingDp = if (index > 0) 16 else 0,
        bottomSpacingDp = 8,
    )

    else -> null
}

private fun parseMarkdownTaskListItem(line: String): MarkdownTaskListItem? {
    val trimmedLine = line.trim()
    if (!trimmedLine.isMarkdownTaskListLine()) return null
    return MarkdownTaskListItem(
        text = trimmedLine
            .removePrefix("- [ ] ").removePrefix("- [x] ")
            .removePrefix("* [ ] ").removePrefix("* [x] "),
        isChecked = trimmedLine.contains("[x]"),
    )
}

private fun String.isMarkdownTaskListLine(): Boolean =
    startsWith("- [ ] ") || startsWith("- [x] ") || startsWith("* [ ] ") || startsWith("* [x] ")

private fun parseMarkdownUnorderedListItem(line: String): MarkdownUnorderedListItem? {
    val trimmedLine = line.trim()
    if (!trimmedLine.startsWith("- ") && !trimmedLine.startsWith("* ")) return null
    return MarkdownUnorderedListItem(
        text = trimmedLine.removePrefix("- ").removePrefix("* "),
    )
}

private fun parseMarkdownOrderedListItem(line: String): MarkdownOrderedListItem? {
    val match = markdownOrderedListPattern.matchEntire(line.trim()) ?: return null
    return MarkdownOrderedListItem(
        number = match.groupValues[1],
        text = match.groupValues[2].trim(),
    )
}

private fun parseMarkdownDividerItem(line: String): MarkdownContentItem? =
    if (line.trim() == "---" || line.trim() == "***") MarkdownDividerItem else null

private fun parseMarkdownQuoteItem(line: String): MarkdownQuoteItem? =
    if (line.startsWith("> ")) MarkdownQuoteItem(line.removePrefix("> ")) else null

private fun parseMarkdownParagraphItem(line: String): MarkdownParagraphItem? =
    if (line.isNotBlank()) MarkdownParagraphItem(line) else null

private fun parseMarkdownBlankLineItem(
    index: Int,
    lines: List<String>,
): MarkdownContentItem? =
    if (index > 0 && lines.getOrNull(index - 1)?.isNotBlank() == true) MarkdownBlankLineItem else null

private fun String.startsMarkdownMathBlock(): Boolean = startsWith("$$") || startsWith("\\[")

private fun String.isMarkdownMathBlockEndMarker(): Boolean = this == "$$" || this == "\\]"

private fun markdownMathBlockMarker(trimmedLine: String): Pair<String, String> =
    if (trimmedLine.startsWith("$$")) "$$" to "$$" else "\\[" to "\\]"

@Composable
private fun RenderMarkdownContentItem(
    item: MarkdownContentItem,
    context: Context,
) {
    when (item) {
        is MarkdownHeadingItem -> MarkdownHeadingBlock(item)
        is MarkdownParagraphItem -> MarkdownParagraphBlock(item.text, context)
        is MarkdownTaskListItem -> TaskListItemAndroid(item.text, item.isChecked)
        is MarkdownUnorderedListItem -> MarkdownUnorderedListBlock(item.text, context)
        is MarkdownOrderedListItem -> MarkdownOrderedListBlock(item, context)
        MarkdownDividerItem -> MarkdownDividerBlock()
        is MarkdownQuoteItem -> MarkdownQuoteBlock(item.text, context)
        is MarkdownTableBlockItem -> MarkdownTableAndroid(item.lines)
        is MarkdownCodeBlockItem -> CodeBlockAndroid(item.code, item.language)
        is MarkdownMathBlockItem -> MathFormulaAndroid(item.text, isBlock = true)
        MarkdownBlankLineItem -> Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun MarkdownHeadingBlock(item: MarkdownHeadingItem) {
    if (item.topSpacingDp > 0) {
        Spacer(modifier = Modifier.height(item.topSpacingDp.dp))
    }
    Text(
        text = item.text,
        style = when (item.level) {
            3 -> MaterialTheme.typography.titleSmall
            2 -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.titleLarge
        },
        fontWeight = if (item.level == 3) FontWeight.SemiBold else FontWeight.Bold,
        color = if (item.level == 1) Color(0xFFC9A86C) else Color(0xFF4A4038),
    )
    Spacer(modifier = Modifier.height(item.bottomSpacingDp.dp))
}

@Composable
private fun MarkdownParagraphBlock(
    text: String,
    context: Context,
) {
    InlineMarkdownAndroid(text, context)
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun MarkdownUnorderedListBlock(
    text: String,
    context: Context,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 4.dp),
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFC9A86C),
            modifier = Modifier.padding(end = 8.dp),
        )
        InlineMarkdownAndroid(text, context)
    }
}

@Composable
private fun MarkdownOrderedListBlock(
    item: MarkdownOrderedListItem,
    context: Context,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 4.dp),
    ) {
        Text(
            text = "${item.number}.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFC9A86C),
            modifier = Modifier.padding(end = 8.dp),
        )
        InlineMarkdownAndroid(item.text, context)
    }
}

@Composable
private fun MarkdownDividerBlock() {
    Spacer(modifier = Modifier.height(8.dp))
    Divider(
        color = Color(0xFFE8E0D4),
        thickness = 1.dp,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun MarkdownQuoteBlock(
    text: String,
    context: Context,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
        shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 8.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(Color(0xFF7BA8C9)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            InlineMarkdownAndroid(text, context)
        }
    }
}

private fun parseMarkdownTable(lines: List<String>): ParsedMarkdownTable? {
    if (lines.isEmpty()) return null
    val hasHeader = lines.hasMarkdownTableHeader()
    val headerCells = if (hasHeader) parseMarkdownTableRow(lines.first()) else emptyList()
    val rows = lines.drop(if (hasHeader) 2 else 0).map(::parseMarkdownTableRow)
    if (headerCells.isEmpty() && rows.isEmpty()) return null
    val maxCols = maxOf(headerCells.size, rows.maxOfOrNull(List<String>::size) ?: 0)
    if (maxCols == 0) return null
    return ParsedMarkdownTable(
        headerCells = headerCells,
        rows = rows,
        maxCols = maxCols,
    )
}

private fun List<String>.hasMarkdownTableHeader(): Boolean {
    if (size <= 1) return false
    val separatorLine = this[1]
    return separatorLine.contains("|") &&
        separatorLine.all { it == '|' || it == '-' || it == ' ' || it == ':' }
}

private fun parseMarkdownTableRow(line: String): List<String> =
    line.split("|")
        .map(String::trim)
        .filter(String::isNotEmpty)

@Composable
private fun MarkdownTableHeaderRow(
    headerCells: List<String>,
    maxCols: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEFF6FF))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        headerCells.forEach { cell ->
            MarkdownTableCell {
                Text(
                    text = cell,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1E3A5F),
                )
            }
        }
        MarkdownTableTrailingCells(maxCols - headerCells.size)
    }
}

@Composable
private fun MarkdownTableDataRow(
    rowCells: List<String>,
    rowIndex: Int,
    maxCols: Int,
    showDivider: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (rowIndex % 2 == 0) Color.White else Color(0xFFFAFAFA))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            rowCells.forEach { cell ->
                MarkdownTableCell { InlineMarkdownAndroid(cell) }
            }
            MarkdownTableTrailingCells(maxCols - rowCells.size)
        }
        if (showDivider) {
            Divider(color = Color(0xFFF0F0F0), thickness = 0.5.dp)
        }
    }
}

@Composable
private fun RowScope.MarkdownTableCell(
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 4.dp),
    ) {
        content()
    }
}

@Composable
private fun RowScope.MarkdownTableTrailingCells(count: Int) {
    repeat(count.coerceAtLeast(0)) {
        Box(modifier = Modifier.weight(1f))
    }
}

private fun findNextInlineMarkdownMatch(
    remaining: String,
    offset: Int,
    mathSegments: List<Pair<Int, String>>,
): InlineMarkdownMatch? {
    val candidates = listOfNotNull(
        findInlineMathMarkdownMatch(offset, remaining.length, mathSegments),
        findMarkdownLinkMatch(remaining),
        findAutoUrlMatch(remaining),
        findBoldMatch(remaining),
        findItalicMatch(remaining),
        findInlineCodeMatch(remaining),
    )
    return candidates.minByOrNull { it.startIndex }
}

private fun findInlineMathMarkdownMatch(
    offset: Int,
    remainingLength: Int,
    mathSegments: List<Pair<Int, String>>,
): InlineMarkdownMatch? {
    val mathSegment = mathSegments.firstOrNull { it.first >= offset } ?: return null
    val startIndex = mathSegment.first - offset
    if (startIndex >= remainingLength) return null
    return InlineMarkdownMatch(
        startIndex = startIndex,
        rawLength = mathSegment.second.length,
        displayText = mathSegment.second,
        type = InlineMarkdownMatchType.Math,
    )
}

private fun findMarkdownLinkMatch(remaining: String): InlineMarkdownMatch? {
    val linkStart = remaining.indexOf("[")
    if (linkStart < 0) return null
    val linkEnd = remaining.indexOf("]", linkStart)
    if (linkEnd <= linkStart) return null
    val urlStart = remaining.indexOf("(", linkEnd)
    if (urlStart != linkEnd + 1) return null
    val urlEnd = remaining.indexOf(")", urlStart)
    if (urlEnd <= urlStart) return null
    return InlineMarkdownMatch(
        startIndex = linkStart,
        rawLength = urlEnd + 1 - linkStart,
        displayText = remaining.substring(linkStart + 1, linkEnd),
        type = InlineMarkdownMatchType.Link,
        annotation = remaining.substring(urlStart + 1, urlEnd),
    )
}

private fun findAutoUrlMatch(remaining: String): InlineMarkdownMatch? {
    val urlMatch = inlineMarkdownUrlPattern.find(remaining) ?: return null
    return InlineMarkdownMatch(
        startIndex = urlMatch.range.first,
        rawLength = urlMatch.value.length,
        displayText = urlMatch.value,
        type = InlineMarkdownMatchType.Link,
        annotation = urlMatch.value,
    )
}

private fun findBoldMatch(remaining: String): InlineMarkdownMatch? {
    val boldStart = remaining.indexOf("**")
    if (boldStart < 0) return null
    val boldEnd = remaining.indexOf("**", boldStart + 2)
    if (boldEnd <= boldStart) return null
    return InlineMarkdownMatch(
        startIndex = boldStart,
        rawLength = boldEnd + 2 - boldStart,
        displayText = remaining.substring(boldStart + 2, boldEnd),
        type = InlineMarkdownMatchType.Bold,
    )
}

private fun findItalicMatch(remaining: String): InlineMarkdownMatch? {
    val italicStart = remaining.indexOf("*")
    if (italicStart < 0 || (italicStart > 0 && remaining[italicStart - 1] == '*')) return null
    val italicEnd = remaining.indexOf("*", italicStart + 1)
    if (italicEnd <= italicStart) return null
    if (italicEnd < remaining.length - 1 && remaining[italicEnd + 1] == '*') return null
    return InlineMarkdownMatch(
        startIndex = italicStart,
        rawLength = italicEnd + 1 - italicStart,
        displayText = remaining.substring(italicStart + 1, italicEnd),
        type = InlineMarkdownMatchType.Italic,
    )
}

private fun findInlineCodeMatch(remaining: String): InlineMarkdownMatch? {
    val codeStart = remaining.indexOf("`")
    if (codeStart < 0) return null
    val codeEnd = remaining.indexOf("`", codeStart + 1)
    if (codeEnd <= codeStart) return null
    return InlineMarkdownMatch(
        startIndex = codeStart,
        rawLength = codeEnd + 1 - codeStart,
        displayText = remaining.substring(codeStart + 1, codeEnd),
        type = InlineMarkdownMatchType.Code,
    )
}

private fun AnnotatedString.Builder.appendInlineMarkdownMatch(match: InlineMarkdownMatch) {
    when (match.type) {
        InlineMarkdownMatchType.Math -> {
            withStyle(
                androidx.compose.ui.text.SpanStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    background = Color(0xFFF5F5F5),
                )
            ) {
                append(match.displayText)
            }
        }

        InlineMarkdownMatchType.Link -> {
            val url = match.annotation ?: return
            pushStringAnnotation(tag = "URL", annotation = url)
            withStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = Color(0xFF1565C0),
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                )
            ) {
                append(match.displayText)
            }
            pop()
        }

        InlineMarkdownMatchType.Bold -> {
            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                append(match.displayText)
            }
        }

        InlineMarkdownMatchType.Italic -> {
            withStyle(
                androidx.compose.ui.text.SpanStyle(
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                )
            ) {
                append(match.displayText)
            }
        }

        InlineMarkdownMatchType.Code -> {
            withStyle(
                androidx.compose.ui.text.SpanStyle(
                    background = Color(0xFFF0F0F0),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            ) {
                append(" ${match.displayText} ")
            }
        }
    }
}

private fun findInlineMathMatch(text: String, startIndex: Int): InlineMathMatch? =
    findEscapedInlineMathMatch(text, startIndex) ?: findDollarInlineMathMatch(text, startIndex)

private fun findEscapedInlineMathMatch(text: String, startIndex: Int): InlineMathMatch? {
    if (startIndex + 1 >= text.length || text[startIndex] != '\\' || text[startIndex + 1] != '(') return null
    val endIndex = findEscapedInlineMathEnd(text, startIndex + 2)
    if (endIndex < 0) return null
    val formula = text.substring(startIndex + 2, endIndex)
    return InlineMathMatch(
        rawLength = endIndex + 2 - startIndex,
        processedText = processMathFormula(formula),
    )
}

private fun findDollarInlineMathMatch(text: String, startIndex: Int): InlineMathMatch? {
    if (!text.startsInlineDollarMathAt(startIndex)) return null
    val endIndex = text.indexOf('$', startIndex + 1)
    if (endIndex < 0) return null
    val formula = text.substring(startIndex + 1, endIndex)
    return InlineMathMatch(
        rawLength = endIndex + 1 - startIndex,
        processedText = processMathFormula(formula),
    )
}

private fun findEscapedInlineMathEnd(text: String, startIndex: Int): Int {
    var index = startIndex
    while (index + 1 < text.length) {
        if (text[index] == '\\' && text[index + 1] == ')') {
            return index
        }
        index++
    }
    return -1
}

private val inlineMarkdownUrlPattern = Regex("""(https?://[^\s<>\[\]()]+)""")
private val markdownOrderedListPattern = Regex("""^(\d+)\.\s+(.*)$""")
private val fileCardIconMappings = listOf(
    "📄" to setOf(".pdf"),
    "📝" to setOf(".doc", ".docx"),
    "📊" to setOf(".xls", ".xlsx"),
    "🖼️" to setOf(".jpg", ".png", ".gif"),
    "🎵" to setOf(".mp3", ".wav"),
    "🎬" to setOf(".mp4", ".avi"),
    "📦" to setOf(".zip", ".rar"),
)
