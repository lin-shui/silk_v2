package com.silk.web

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
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
import com.silk.shared.messageStreamKey
import com.silk.shared.models.KnowledgeBaseContextSelection
import com.silk.shared.models.DirEntry
import com.silk.shared.models.DirListingResponse
import com.silk.shared.models.Message
import com.silk.shared.models.MessageType
import com.silk.shared.models.MessageScope
import com.silk.shared.models.RoomSummaryDto
import com.silk.web.workspace.WorkspaceDto
import com.silk.web.workspace.WorkspaceApiException
import com.silk.web.workspace.createWorkspace as createRoomWorkspace
import com.silk.web.workspace.deleteWorkspace as deleteRoomWorkspace
import com.silk.web.workspace.fetchWorkspaces
import com.silk.web.workspace.patchWorkspace as patchRoomWorkspace
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.Position
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.bottom
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontFamily
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontStyle
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.left
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginLeft
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.maxHeight
import org.jetbrains.compose.web.css.minHeight
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.position
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.right
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.top
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea


private fun shouldSubmitWorkflowMessage(
    event: org.jetbrains.compose.web.events.SyntheticKeyboardEvent,
    messageText: String,
    hasPendingImage: Boolean = false,
): Boolean {
    if (event.key != "Enter") return false
    if (event.shiftKey) return false
    val isComposing = event.nativeEvent.asDynamic().isComposing == true
    return !isComposing && (messageText.isNotBlank() || hasPendingImage)
}

internal fun parseGithubIssueAction(action: String, expectedRoomId: String): Int? {
    val prefix = "github:issue-to-workspace:"
    if (!action.startsWith(prefix)) return null
    val payload = action.removePrefix(prefix)
    val separator = payload.lastIndexOf(':')
    if (separator <= 0) return null
    if (payload.take(separator) != expectedRoomId) return null
    return payload.substring(separator + 1).toIntOrNull()?.takeIf { it > 0 }
}

@Composable
fun WorkflowRoomView(
    appState: WebAppState,
    room: RoomSummaryDto,
    selectedWorkspaceId: String = "team",
    workspaceCreateRequestVersion: Int = 0,
    onWorkspaceSelected: (String) -> Unit = {},
    onWorkspaceCreated: (WorkspaceDto) -> Unit = {},
    onWorkspaceUpdated: (WorkspaceDto) -> Unit = {},
    onWorkspaceDeleted: (String) -> Unit = {},
    onInvite: () -> Unit = {},
    onRoomActivity: (Long) -> Unit = {},
) {
    val user = appState.currentUser ?: return
    val workflowId = room.workflowId
    if (workflowId.isNullOrBlank()) {
        Div({ style { padding(24.px); color(Color(SilkColors.error)) } }) {
            Text("工作群组元数据缺失，请刷新后重试。")
        }
        return
    }
    key(room.roomId) {
        WorkflowChatPanel(
            appState = appState,
            userId = user.id,
            userName = user.fullName,
            groupId = room.roomId,
            workflowId = workflowId,
            workflowName = room.name,
            workflowRole = room.role,
            initialWorkspaceId = selectedWorkspaceId,
            showDesktopWorkspaceNavigation = false,
            workspaceCreateRequestVersion = workspaceCreateRequestVersion,
            onActiveWorkspaceChange = onWorkspaceSelected,
            onWorkspaceCreated = onWorkspaceCreated,
            onWorkspaceUpdated = onWorkspaceUpdated,
            onWorkspaceDeleted = onWorkspaceDeleted,
            onInvite = onInvite,
            onRoomActivity = onRoomActivity,
        )
    }
}

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
private fun WorkflowChatPanel(
    appState: WebAppState,
    userId: String,
    userName: String,
    groupId: String,
    workflowId: String,
    workflowName: String,
    workflowRole: String,
    initialWorkspaceId: String = "team",
    showDesktopWorkspaceNavigation: Boolean = true,
    workspaceCreateRequestVersion: Int = 0,
    onActiveWorkspaceChange: (String) -> Unit = {},
    onWorkspaceCreated: (WorkspaceDto) -> Unit = {},
    onWorkspaceUpdated: (WorkspaceDto) -> Unit = {},
    onWorkspaceDeleted: (String) -> Unit = {},
    onInvite: () -> Unit = {},
    onRoomActivity: (Long) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val wsUrl = remember { backendWsOrigin() }
    val chatClient = remember { ChatClient(wsUrl) }
    val messages by chatClient.messages.collectAsState()
    val transientMessages by chatClient.transientMessages.collectAsState()
    val statusMessagesByStream by chatClient.statusMessagesByStream.collectAsState()
    val connectionState by chatClient.connectionState.collectAsState()
    val isGenerating by chatClient.isGenerating.collectAsState()
    val latestMessageTimestamp = messages.maxOfOrNull { it.timestamp } ?: 0L

    LaunchedEffect(groupId, latestMessageTimestamp) {
        if (latestMessageTimestamp > 0L) onRoomActivity(latestMessageTimestamp)
    }
    var messageText by remember(groupId) { mutableStateOf("") }
    var pendingRoomImage by remember(groupId) { mutableStateOf<dynamic>(null) }
    var pendingRoomImageUrl by remember(groupId) { mutableStateOf<String?>(null) }
    var userLanguage by remember(userId) {
        mutableStateOf(com.silk.shared.models.Language.CHINESE)
    }
    val strings = com.silk.shared.i18n.getStrings(userLanguage)
    var showKbRefMenu by remember(groupId) { mutableStateOf(false) }
    var kbRefSearchText by remember(groupId) { mutableStateOf("") }
    var kbRefStartIndex by remember(groupId) { mutableStateOf(-1) }
    var kbRefMenuPosition by remember(groupId) { mutableStateOf(Pair(0.0, 0.0)) }
    var kbRefSearchResults by remember(groupId) { mutableStateOf<List<KnowledgeBaseEntrySearchResult>>(emptyList()) }
    var kbRefIsSearching by remember(groupId) { mutableStateOf(false) }
    var workingDir by remember(groupId) { mutableStateOf("") }
    var activeAgentDisplay by remember(groupId) { mutableStateOf("") }
    var permissionMode by remember(groupId) { mutableStateOf("") }
    var availableAgents by remember(groupId) { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var showFolderPicker by remember(groupId) { mutableStateOf(false) }
    var showTrustConfirm by remember(groupId) { mutableStateOf(false) }
    var trustConfirmPath by remember(groupId) { mutableStateOf("") }
    var trustConfirmBridgeId by remember(groupId) { mutableStateOf<String?>(null) }
    var showPermModeDropdown by remember(groupId) { mutableStateOf(false) }
    var showAgentDropdown by remember(groupId) { mutableStateOf(false) }
    var switchError by remember(groupId) { mutableStateOf<String?>(null) }
    var sourcePanelOpen by remember(groupId) { mutableStateOf(false) }
    var diffRefreshSignal by remember(groupId) { mutableStateOf(0) }
    var sourcePanelWidth by remember { mutableStateOf(LayoutPrefs.getInt("silk_wf_scpanel_w", 420)) }
    var kbContextSelection by remember(groupId) { mutableStateOf(KnowledgeBaseContextSelection()) }
    var kbPersistentExcludedSpaceIds by remember(groupId) { mutableStateOf<List<String>>(emptyList()) }
    var kbPersistentDownrankedSpaceIds by remember(groupId) { mutableStateOf<List<String>>(emptyList()) }
    var kbContextSelectionTouched by remember(groupId) { mutableStateOf(false) }
    var kbCaptureDraft by remember(groupId) { mutableStateOf<KnowledgeCaptureDraft?>(null) }
    var kbCaptureTopics by remember(groupId) { mutableStateOf<List<KBTopicItem>>(emptyList()) }
    var kbCaptureGroups by remember(groupId) { mutableStateOf<List<Group>>(emptyList()) }
    var kbCaptureSelectedSpaceId by remember(groupId) { mutableStateOf(PERSONAL_SPACE_ID) }
    var kbCaptureSelectedTopicId by remember(groupId) { mutableStateOf("") }
    var kbCaptureTitle by remember(groupId) { mutableStateOf("") }
    var kbCaptureContent by remember(groupId) { mutableStateOf("") }
    var kbCaptureSaving by remember(groupId) { mutableStateOf(false) }
    var kbCaptureResult by remember(groupId) { mutableStateOf<String?>(null) }
    // Workspace tab bar state
    var workspaces by remember(groupId) { mutableStateOf<List<WorkspaceDto>>(emptyList()) }
    var workspaceLoadError by remember(groupId) { mutableStateOf<String?>(null) }
    var activeTab by remember(groupId) { mutableStateOf(initialWorkspaceId.ifBlank { "team" }) }
    var workspaceRefreshVersion by remember(groupId) { mutableStateOf(0) }
    var showWorkspaceCreate by remember(groupId) { mutableStateOf(false) }
    var workspaceManageTarget by remember(groupId) { mutableStateOf<WorkspaceDto?>(null) }
    var groupMembers by remember(groupId) { mutableStateOf<List<GroupMember>>(emptyList()) }
    var showRoomMembers by remember(groupId) { mutableStateOf(false) }
    var roomMembers by remember(groupId) { mutableStateOf<List<WorkflowRoomMember>>(emptyList()) }
    var roomContacts by remember(groupId) { mutableStateOf<List<Contact>>(emptyList()) }
    var memberCandidates by remember(groupId) { mutableStateOf<List<WorkflowMemberCandidate>>(emptyList()) }
    var memberQuery by remember(groupId) { mutableStateOf("") }
    var memberSearchAttempted by remember(groupId) { mutableStateOf(false) }
    var memberBusy by remember(groupId) { mutableStateOf(false) }
    var memberError by remember(groupId) { mutableStateOf<String?>(null) }
    var memberFeedback by remember(groupId) { mutableStateOf<String?>(null) }
    var showFolderExplorer by remember(groupId) { mutableStateOf(false) }
    var isExportingMarkdown by remember(groupId) { mutableStateOf(false) }
    var exportMarkdownHint by remember(groupId) { mutableStateOf<String?>(null) }
    var showGitIntegration by remember(groupId) { mutableStateOf(false) }
    var githubIssueNumber by remember(groupId) { mutableStateOf<Int?>(null) }

    suspend fun refreshRoomMembers() {
        val response = ApiClient.getWorkflowRoomMembers(workflowId)
        if (response.success) {
            roomMembers = response.members
            groupMembers = response.members.map {
                GroupMember(id = it.id, fullName = it.fullName, role = it.role)
            }
            memberError = null
        } else {
            memberError = response.message.ifBlank { "成员加载失败" }
        }
        if (workflowRole == "OWNER") {
            val contactsResponse = ApiClient.getContacts(userId)
            roomContacts = contactsResponse.contacts ?: emptyList()
        }
    }
    val currentWorkspaces = workspaces.filterNot { it.historyOnly }
    val historicalWorkspaceMetadata = workspaces
        .filter { it.historyOnly }
        .map { it.copy(lifecycleState = "REVOKED") }
    val historicalWorkspaceTabs = historicalWorkspaceMetadata + messages.asSequence()
        .filter { it.scope == MessageScope.WORKSPACE && it.observerVisible && !it.workspaceId.isNullOrBlank() }
        .mapNotNull { it.workspaceId }
        .distinct()
        .filter { id -> currentWorkspaces.none { it.workspaceId == id } }
        .filter { id -> historicalWorkspaceMetadata.none { it.workspaceId == id } }
        .map { id ->
            WorkspaceDto(
                workspaceId = id,
                roomId = groupId,
                ownerId = "",
                name = "已撤销的工作区",
                role = "OBSERVER",
                lifecycleState = "REVOKED",
                historyOnly = true,
            )
        }
        .toList()
    val workspaceTabs = currentWorkspaces + historicalWorkspaceTabs
    val activeWorkspace = workspaceTabs.firstOrNull { it.workspaceId == activeTab }
    val activeWorkspaceId = activeWorkspace?.workspaceId
    val canControlActiveWorkspace = activeWorkspace?.lifecycleState == "ACTIVE" &&
        (activeWorkspace.role == "OWNER" || activeWorkspace.role == "COPILOT")
    val canSendToActiveTab = activeTab == "team" || canControlActiveWorkspace
    val hasPendingRoomImage = activeTab == "team" && pendingRoomImage != null
    val sendActiveMessage: () -> Unit = {
        val text = messageText.trim()
        val image = if (activeTab == "team") pendingRoomImage else null
        if (canSendToActiveTab && (text.isNotBlank() || image != null)) {
            messageText = ""
            if (image != null) {
                pendingRoomImage = null
                pendingRoomImageUrl?.let { objectUrl ->
                    window.asDynamic().URL.revokeObjectURL(objectUrl)
                }
                pendingRoomImageUrl = null
                uploadConversationImage(groupId, userId, image, text)
            } else {
                scope.launch {
                    chatClient.sendMessage(
                        userId = userId,
                        userName = userName,
                        content = text,
                        kbContextSelection = kbContextSelection.takeIf(::hasKnowledgeBaseContextSelection),
                        scope = if (activeTab == "team") MessageScope.TEAM else MessageScope.WORKSPACE,
                        workspaceId = activeTab.takeUnless { it == "team" },
                    )
                }
            }
        }
    }
    val activeStreamKey = messageStreamKey(
        if (activeTab == "team") MessageScope.TEAM else MessageScope.WORKSPACE,
        activeWorkspaceId,
    )
    val transientMessage = transientMessages[activeStreamKey]
    val statusMessages = statusMessagesByStream[activeStreamKey].orEmpty()
    val isActiveStreamGenerating = if (activeTab == "team") {
        isGenerating && (transientMessage != null || statusMessages.isNotEmpty())
    } else {
        transientMessage != null || statusMessages.isNotEmpty() ||
            activeWorkspace?.activity?.state in setOf("RUNNING", "WAITING")
    }
    val belongsToActiveTab: (Message) -> Boolean = { message ->
        if (activeTab == "team") {
            message.scope == MessageScope.TEAM
        } else {
            message.scope == MessageScope.WORKSPACE && message.workspaceId == activeTab
        }
    }
    val activeMessageCount = messages.count(belongsToActiveTab)

    LaunchedEffect(initialWorkspaceId) {
        val requested = initialWorkspaceId.ifBlank { "team" }
        if (requested != activeTab) activeTab = requested
    }
    LaunchedEffect(workspaceCreateRequestVersion) {
        if (workspaceCreateRequestVersion > 0) showWorkspaceCreate = true
    }
    LaunchedEffect(activeTab) {
        onActiveWorkspaceChange(activeTab)
    }
    LaunchedEffect(workspaceTabs.map { it.workspaceId }, workspaceLoadError) {
        if (workspaceLoadError == null && activeTab != "team" && workspaceTabs.none { it.workspaceId == activeTab }) {
            activeTab = "team"
        }
    }
    val resetKnowledgeCaptureDialog: () -> Unit = {
        kbCaptureDraft = null
        kbCaptureTopics = emptyList()
        kbCaptureGroups = emptyList()
        kbCaptureSelectedSpaceId = PERSONAL_SPACE_ID
        kbCaptureSelectedTopicId = ""
        kbCaptureTitle = ""
        kbCaptureContent = ""
        kbCaptureSaving = false
        kbCaptureResult = null
    }
    val onCaptureToKnowledgeBase: (Message) -> Unit = { message ->
        scope.launch {
            val context = loadKnowledgeCaptureContext(userId)
            val preferredSpaceId = preferredKnowledgeCaptureSpaceId(groupId, context.topics)
            kbCaptureDraft = KnowledgeCaptureDraft(
                message = message,
                sourceType = KBSourceType.WORKFLOW,
                sourceGroupId = groupId,
                workflowId = workflowId,
                preferredSpaceId = preferredSpaceId,
            )
            kbCaptureTopics = context.topics
            kbCaptureGroups = context.groups
            kbCaptureSelectedSpaceId = preferredSpaceId
            kbCaptureSelectedTopicId = defaultKnowledgeCaptureTopicId(context.topics, preferredSpaceId).orEmpty()
            kbCaptureTitle = buildDefaultKnowledgeCaptureTitle(message.content)
            kbCaptureContent = message.content
            kbCaptureSaving = false
            kbCaptureResult = if (context.topics.isEmpty()) "还没有可用主题，请先去知识库创建主题。" else null
        }
    }
    val onGithubIssueAction: (String) -> Unit = { action ->
        val issueNumber = parseGithubIssueAction(action, groupId)
        if (issueNumber == null) {
            switchError = "GitHub Issue 操作无效，请刷新卡片后重试。"
        } else {
            githubIssueNumber = issueNumber
        }
    }
    LaunchedEffect(userId, groupId) {
        val prefs = ApiClient.getKBContextPreferences(userId)
        kbPersistentExcludedSpaceIds = prefs.excludedSpaceIds
        kbPersistentDownrankedSpaceIds = prefs.downrankedSpaceIds
        if (!kbContextSelectionTouched) {
            kbContextSelection = mergeKnowledgeBaseContextSelectionWithPersistentSpaces(
                restoredSelection = kbContextSelection.takeIf {
                    it.pinnedEntryIds.isNotEmpty() || it.excludedEntryIds.isNotEmpty()
                },
                persistentExcludedSpaceIds = kbPersistentExcludedSpaceIds,
                persistentDownrankedSpaceIds = kbPersistentDownrankedSpaceIds,
            )
        }
    }
    // Workspace discovery and activity state share one server-authoritative snapshot.
    LaunchedEffect(groupId, connectionState, workspaceRefreshVersion) {
        if (connectionState == ConnectionState.CONNECTED) kotlinx.coroutines.delay(150)
        val token = JwtManager.getAccessToken() ?: return@LaunchedEffect
        while (true) {
            try {
                workspaces = fetchWorkspaces(groupId, token)
                workspaceLoadError = null
            } catch (e: WorkspaceApiException) {
                workspaceLoadError = e.message
                console.error("fetchWorkspaces error:", e.message)
            } catch (e: Exception) {
                workspaceLoadError = "工作区加载失败"
                console.error("fetchWorkspaces error:", e.message)
            }
            kotlinx.coroutines.delay(5_000)
        }
    }
    LaunchedEffect(groupId, userId) {
        refreshRoomMembers()
        availableAgents = ApiClient.listAgents(userId)
    }
    LaunchedEffect(userId) {
        val response = ApiClient.getUserSettings(userId)
        if (response.success && response.settings != null) {
            userLanguage = response.settings!!.language
        }
    }
    LaunchedEffect(messages.size, userId, kbPersistentExcludedSpaceIds, kbPersistentDownrankedSpaceIds, kbContextSelectionTouched) {
        if (kbContextSelectionTouched) return@LaunchedEffect
        if (kbContextSelection.pinnedEntryIds.isNotEmpty() || kbContextSelection.excludedEntryIds.isNotEmpty()) {
            return@LaunchedEffect
        }
        val restoredSelection = latestKnowledgeBaseContextSelection(messages, userId) ?: return@LaunchedEffect
        kbContextSelection = mergeKnowledgeBaseContextSelectionWithPersistentSpaces(
            restoredSelection = restoredSelection,
            persistentExcludedSpaceIds = kbPersistentExcludedSpaceIds,
            persistentDownrankedSpaceIds = kbPersistentDownrankedSpaceIds,
        )
    }

    // 拉取当前 CC 工作目录：
    // - groupId 变化时（切换工作流）
    // - WebSocket 连接状态变化（连上后 autoActivateForWorkflow 才会创建 CC state）
    //
    // 注意：原先这里还依赖 messages.size，每条新消息都会 round-trip 一次 /cc-state。
    // 自从移除聊天 /cd 命令后，workingDir 只会在这些入口被改：
    //   1. 前端自己通过 "更改" 按钮调 cdCcDir（成功后已直接本地 set workingDir，无需拉）
    //   2. 创建工作流时后端 cdSync（返回 workflow 时前端会重新进入面板，这里会触发）
    //   3. session_resumed / new_session 事件（极低频）
    // 因此此处不再按消息数量 poll；若将来需要捕获 3 的变化，可监听特定系统消息再触发。
    LaunchedEffect(activeWorkspaceId, connectionState) {
        val workspaceId = activeWorkspaceId
        if (workspaceId == null || !canControlActiveWorkspace) {
            workingDir = ""
            activeAgentDisplay = ""
            permissionMode = ""
            sourcePanelOpen = false
            return@LaunchedEffect
        }
        // WebSocket 连上后稍等片刻，让后端 autoActivateForWorkflow 完成
        if (connectionState == ConnectionState.CONNECTED) {
            kotlinx.coroutines.delay(200)
        }
        val snap = ApiClient.getCcState(userId, workspaceId)
        if (snap.success) {
            workingDir = snap.workingDir
            activeAgentDisplay = snap.agentDisplayName
            permissionMode = snap.permissionMode
        }
        availableAgents = ApiClient.listAgents(userId)
    }

    // 监听新增消息，刷新 activeAgent / permissionMode 显示。
    // 后端在 agent 切换、权限模式切换时都会广播 SYSTEM 消息（"已切换到 ..."），
    // 新增消息改变 messages.size → 触发此 effect → 仅检查最新一条是否匹配。
    LaunchedEffect(messages.size, activeWorkspaceId) {
        val workspaceId = activeWorkspaceId ?: return@LaunchedEffect
        if (!canControlActiveWorkspace) return@LaunchedEffect
        val latest = messages.lastOrNull() ?: return@LaunchedEffect
        val isAgentStatusMessage = latest.type == com.silk.shared.models.MessageType.SYSTEM &&
            (latest.content.startsWith("已切换到") || latest.content.contains("已激活") || latest.content.contains("已退出 agent"))
        if (isAgentStatusMessage) {
            val snap = ApiClient.getCcState(userId, workspaceId)
            if (snap.success) {
                activeAgentDisplay = snap.agentDisplayName
                permissionMode = snap.permissionMode
            }
        }
    }

    // Connect WebSocket when groupId changes
    // key(wf.groupId) 保证切换 workflow 时此组件被销毁重建，ChatClient 始终是新实例，
    // 无需 delay 等待旧连接关闭
    LaunchedEffect(groupId) {
        chatClient.clearMessages()
        try {
            chatClient.connect(userId, userName, groupId, token = JwtManager.getAccessToken())
        } catch (e: dynamic) {
            console.error("❌ 工作流 WebSocket 连接失败:", e.toString())
        }
    }

    // Disconnect on dispose
    DisposableEffect(Unit) {
        onDispose {
            try {
                scope.launch { chatClient.disconnect() }
            } catch (_: dynamic) {}
        }
    }

    // Auto-scroll
    val activeWorkflowNavigationTarget = appState.roomNavigationTarget?.takeIf {
        it.roomId == groupId || it.legacyWorkflowId == workflowId
    }
    LaunchedEffect(activeTab, activeMessageCount, transientMessage, statusMessages.size) {
        if (activeWorkflowNavigationTarget?.messageId?.isNullOrBlank() == false) return@LaunchedEffect
        kotlinx.coroutines.delay(50)
        scrollContainerToBottom(WORKFLOW_MESSAGES_CONTAINER_ID)
        // Cards and formatted content may settle after the first layout pass.
        kotlinx.coroutines.delay(150)
        scrollContainerToBottom(WORKFLOW_MESSAGES_CONTAINER_ID)
    }

    LaunchedEffect(activeWorkflowNavigationTarget?.requestId, messages.size, transientMessage?.id) {
        val target = activeWorkflowNavigationTarget ?: return@LaunchedEffect
        val messageId = target.messageId
        if (messageId.isNullOrBlank()) {
            appState.consumeRoomNavigationTarget(target.requestId)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(80)
        if (scrollMessageIntoContainer(WORKFLOW_MESSAGES_CONTAINER_ID, messageId)) {
            appState.consumeRoomNavigationTarget(target.requestId)
        }
    }

    // 代理回合结束（isGenerating → false）后去抖 ~0.5s bump 刷新信号，让源代码管理面板自动重拉状态
    LaunchedEffect(isGenerating) {
        if (!isGenerating && sourcePanelOpen) {
            kotlinx.coroutines.delay(500)
            diffRefreshSignal += 1
        }
    }

    // Shared room scaffold owns the sizing contract; Workflow only adds its auxiliary source panel.
    ConversationDetailScaffold(ConversationDetailVariant.WORKFLOW) {
    // 聊天列（保留原有 header/messages/input 纵向布局）
    ConversationPaneScaffold("silk-workflow-chat-column") {
    ConversationHeader(
        icon = if (activeTab == "team") "#" else "▣",
        title = workflowName,
        className = "silk-workflow-header",
        titleSuffix = if (connectionState == ConnectionState.CONNECTED) null else {
            {
                Span({
                    style {
                        fontSize(11.px)
                        color(
                            if (connectionState == ConnectionState.CONNECTING) Color("#D98600")
                            else Color(SilkColors.error)
                        )
                    }
                }) {
                    Text(if (connectionState == ConnectionState.CONNECTING) "● 连接中" else "● 连接失败")
                }
            }
        },
        subtitle = {
            if (activeTab == "team") {
                Text("Team Channel")
            } else {
                activeWorkspace?.let { workspace ->
                    Span { Text("${workspace.ownerDisplayName} / ${workspace.name}") }
                }
                if (workingDir.isNotBlank()) {
                    Span({
                        attr("title", workingDir)
                        style {
                            property("overflow", "hidden")
                            property("text-overflow", "ellipsis")
                            property("white-space", "nowrap")
                            fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                        }
                    }) { Text("· $workingDir") }
                }
            }
        },
    ) {
        if (activeTab == "team") {
            ConversationRoomHeaderActions(
                inviteLabel = strings.inviteButton,
                membersLabel = strings.membersButton,
                isExporting = isExportingMarkdown,
                exportHint = exportMarkdownHint,
                onOpenFiles = { showFolderExplorer = true },
                onExport = {
                    scope.launch {
                        isExportingMarkdown = true
                        try {
                            exportConversationMarkdown(groupId, workflowName, userId) {
                                exportMarkdownHint = it
                            }
                        } finally {
                            isExportingMarkdown = false
                        }
                    }
                },
                onChooseVault = {
                    scope.launch { chooseConversationVault { exportMarkdownHint = it } }
                },
                onInvite = onInvite,
                onMembers = {
                    showRoomMembers = true
                    scope.launch { refreshRoomMembers() }
                },
            )
            ConversationHeaderActionButton(
                label = "GitHub 集成",
                icon = "◈",
                tone = if (showGitIntegration) ConversationActionTone.PRIMARY else ConversationActionTone.DEFAULT,
                onClick = { showGitIntegration = true },
            )
        } else {
            // Owner 管理完整工作区；Co-pilot 只管理获授权目录。
            if (activeWorkspace?.role == "OWNER" || canControlActiveWorkspace) {
                ConversationHeaderActionButton(
                    label = if (activeWorkspace?.role == "OWNER") "工作区设置" else "设置工作目录",
                    icon = "⚙",
                ) {
                    if (activeWorkspace?.role == "OWNER") {
                        workspaceManageTarget = activeWorkspace
                    } else {
                        showFolderPicker = true
                    }
                }
            }
            ConversationHeaderActionButton(label = "Room 成员", icon = "👥") {
                showRoomMembers = true
                scope.launch { refreshRoomMembers() }
            }
            if (canControlActiveWorkspace) {
                ConversationHeaderActionButton(
                    label = if (sourcePanelOpen) "关闭代码审查" else "打开代码审查",
                    icon = if (sourcePanelOpen) "×" else "⌥",
                    tone = if (sourcePanelOpen) ConversationActionTone.PRIMARY else ConversationActionTone.DEFAULT,
                ) {
                    sourcePanelOpen = !sourcePanelOpen
                    if (sourcePanelOpen) diffRefreshSignal += 1
                }
            }
        }
    }

    // Workspace navigation: grouped on desktop, one stable selector on narrow screens.
    val ownedActive = currentWorkspaces
        .filter { it.ownerId == userId && it.lifecycleState == "ACTIVE" }
        .sortedByDescending { it.recentActivityAt }
    val archivedOwned = currentWorkspaces
        .filter { it.ownerId == userId && it.lifecycleState == "ARCHIVED" }
        .sortedByDescending { it.recentActivityAt }
    val copilotWorkspaces = currentWorkspaces
        .filter { it.role == "COPILOT" && it.lifecycleState == "ACTIVE" }
        .sortedByDescending { it.recentActivityAt }
    val memberWorkspaceGroups = currentWorkspaces
        .filter {
            it.ownerId != userId && it.lifecycleState == "ACTIVE" &&
                it.role in setOf("OBSERVER", "COPILOT")
        }
        .groupBy { it.ownerId }
        .map { (ownerId, ownerWorkspaces) ->
            Triple(
                ownerId,
                ownerWorkspaces.firstOrNull()?.ownerDisplayName.orEmpty().ifBlank { "Room member" },
                ownerWorkspaces.sortedByDescending { it.recentActivityAt },
            )
        }
        .sortedBy { it.second }
    val historicalSharedWorkspaces = historicalWorkspaceTabs.sortedByDescending { workspace ->
        messages.lastOrNull { it.workspaceId == workspace.workspaceId }?.timestamp ?: 0L
    }
    val mobileWorkspaces = (
        ownedActive + copilotWorkspaces + memberWorkspaceGroups.flatMap { it.third } +
            archivedOwned + historicalSharedWorkspaces
        ).distinctBy { it.workspaceId }

    Div({
        attr(
            "class",
            "silk-workflow-toolbar silk-conversation-fixed-region" +
                if (showDesktopWorkspaceNavigation) "" else " silk-workflow-toolbar-sidebar-owned",
        )
        style {
            property("flex-shrink", "0")
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("border-bottom", "1px solid ${SilkColors.border}")
            backgroundColor(Color(SilkColors.surfaceElevated))
            padding(6.px, 12.px)
            property("gap", "8px")
            property("min-height", "42px")
            property("box-sizing", "border-box")
        }
    }) {
        if (showDesktopWorkspaceNavigation) Div({ attr("class", "silk-workspace-nav-desktop") }) {
            WorkspaceTabButton(
                label = "Team Channel",
                isActive = activeTab == "team",
                onClick = { activeTab = "team" },
            )
            ownedActive.forEach { workspace ->
                WorkspaceTabButton(
                    label = workspace.name,
                    workspace = workspace,
                    isActive = activeTab == workspace.workspaceId,
                    onClick = { activeTab = workspace.workspaceId },
                )
            }
            if (copilotWorkspaces.isNotEmpty()) {
                WorkspaceGroupSelect(
                    label = "Co-pilot",
                    workspaces = copilotWorkspaces,
                    activeTab = activeTab,
                    onSelect = { activeTab = it },
                )
            }
            memberWorkspaceGroups.forEach { (ownerId, ownerName, ownerWorkspaces) ->
                key(ownerId) {
                    WorkspaceGroupSelect(
                        label = ownerName,
                        workspaces = ownerWorkspaces,
                        activeTab = activeTab,
                        onSelect = { activeTab = it },
                    )
                }
            }
            if (historicalSharedWorkspaces.isNotEmpty()) {
                WorkspaceGroupSelect(
                    label = "历史共享",
                    workspaces = historicalSharedWorkspaces,
                    activeTab = activeTab,
                    onSelect = { activeTab = it },
                )
            }
            if (archivedOwned.isNotEmpty()) {
                WorkspaceGroupSelect(
                    label = "已归档",
                    workspaces = archivedOwned,
                    activeTab = activeTab,
                    onSelect = { activeTab = it },
                )
            }
            if (activeTab != "team" && activeWorkspace == null) {
                Span({
                    style {
                        padding(7.px, 10.px)
                        color(Color(SilkColors.error))
                        fontSize(13.px)
                    }
                }) { Text("工作区不可用") }
            }
        } else {
            Div({ attr("class", "silk-workspace-nav-desktop") })
        }

        Div({ attr("class", "silk-workspace-nav-mobile") }) {
            Select({
                style {
                    property("flex", "1")
                    property("min-width", "0")
                    height(34.px)
                    borderRadius(6.px)
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    backgroundColor(Color.white)
                    padding(4.px, 8.px)
                }
                onChange { activeTab = it.value ?: "team" }
            }) {
                Option("team", attrs = { if (activeTab == "team") attr("selected", "") }) {
                    Text("Team Channel")
                }
                if (activeTab != "team" && activeWorkspace == null) {
                    Option(activeTab, attrs = { attr("selected", ""); attr("disabled", "") }) {
                        Text("工作区不可用")
                    }
                }
                mobileWorkspaces.forEach { workspace ->
                    val prefix = when {
                        workspace.lifecycleState == "REVOKED" -> "历史共享"
                        workspace.lifecycleState == "ARCHIVED" -> "已归档"
                        workspace.ownerId == userId -> "我的"
                        workspace.role == "COPILOT" -> "Co-pilot · ${workspace.ownerDisplayName}"
                        else -> workspace.ownerDisplayName
                    }
                    val optionLabel = if (workspace.lifecycleState == "REVOKED") {
                        "$prefix / ${workspace.ownerDisplayName} - ${workspace.name}"
                    } else {
                        "$prefix / ${workspace.name} · ${workspace.activity.state}"
                    }
                    Option(
                        workspace.workspaceId,
                        attrs = { if (activeTab == workspace.workspaceId) attr("selected", "") },
                    ) { Text(optionLabel) }
                }
            }
        }

        if (workspaceLoadError != null) {
            Span({
                attr("title", workspaceLoadError ?: "工作区加载失败")
                style { color(Color(SilkColors.error)); fontSize(12.px) }
            }) { Text("工作区加载失败") }
        }
        Button({
            if (!showDesktopWorkspaceNavigation) attr("class", "silk-workspace-create-mobile")
            attr("title", "新建工作区")
            style {
                width(32.px); height(32.px); padding(0.px)
                borderRadius(6.px)
                border(0.px)
                backgroundColor(Color(SilkColors.primary))
                color(Color.white)
                fontSize(20.px)
                property("cursor", "pointer")
                property("flex-shrink", "0")
            }
            onClick { showWorkspaceCreate = true }
        }) { Text("+") }
    }

    // Messages area
    Div({
        id(WORKFLOW_MESSAGES_CONTAINER_ID)
        attr("class", "silk-conversation-scroll-region")
        style {
            property("flex", "1")
            property("min-height", "0")
            property("overflow-y", "auto")
            padding(16.px)
            property("background", SilkColors.backgroundGradient)
        }
    }) {
        // Persistent messages — filtered by active tab scope
        val visibleMessages = messages.filter(belongsToActiveTab)
        visibleMessages.forEachIndexed { index, message ->
            key(message.id) {
                MessageItem(
                    message = message,
                    isTransient = false,
                    isLastMessage = index == visibleMessages.lastIndex,
                    currentUserId = userId,
                    currentUserName = userName,
                    groupId = groupId,
                    chatClient = chatClient,
                    canInteractWithCards = message.scope != MessageScope.WORKSPACE ||
                        workspaceTabs.firstOrNull { it.workspaceId == message.workspaceId }?.let {
                            it.lifecycleState == "ACTIVE" && it.role in setOf("OWNER", "COPILOT")
                        } == true,
                    onCopy = { content -> copyTextToClipboard(content) },
                    onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
                    onGithubIssueAction = onGithubIssueAction,
                )
            }
        }

        // Status messages（KB 上下文状态条改由输入区上方的 KnowledgeBaseContextTray 展示）
        val visibleStatusMessages = statusMessages
            .filter(belongsToActiveTab)
            .filterNot(::isKnowledgeBaseContextStatusMessage)
        if (visibleStatusMessages.isNotEmpty()) {
            Div({
                style {
                    backgroundColor(Color("#F5F5F5"))
                    borderRadius(8.px)
                    padding(10.px, 14.px)
                    marginBottom(8.px)
                    property("border-left", "3px solid #9E9E9E")
                }
            }) {
                visibleStatusMessages.forEach { status ->
                    Div({
                        style {
                            color(Color("#757575"))
                            fontSize(13.px)
                            fontStyle("italic")
                            marginBottom(4.px)
                            property("white-space", "pre-wrap")
                            property("word-break", "break-word")
                        }
                    }) {
                        Text(status.content)
                    }
                }
            }
        }

        // Transient (streaming) message
        transientMessage?.takeIf(belongsToActiveTab)?.let { message ->
            val shouldShowTransient = message.content.isNotBlank() &&
                message.currentStep == null &&
                message.totalSteps == null &&
                !isLikelyAgentStatusContent(message.content)
            if (shouldShowTransient) {
                MessageItem(
                    message = message.copy(category = com.silk.shared.models.MessageCategory.NORMAL),
                    isTransient = true,
                    currentUserId = userId,
                    currentUserName = userName,
                    groupId = groupId,
                    chatClient = chatClient,
                    canInteractWithCards = canControlActiveWorkspace,
                    onCopy = { content -> copyTextToClipboard(content) },
                    onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
                    onGithubIssueAction = onGithubIssueAction,
                )
            } else {
                TransientMessageItem(message)
            }
        }
    }

    if (activeWorkspace?.role == "COPILOT") {
        Div({
            attr("class", "silk-conversation-fixed-region")
            style {
                property("flex-shrink", "0")
                padding(8.px, 16.px)
                backgroundColor(Color("#FFF8E1"))
                color(Color("#7A4F00"))
                property("border-top", "1px solid #F2D28B")
                fontSize(12.px)
            }
        }) {
            Text("Co-pilot · ${activeWorkspace.ownerDisplayName} / ${activeWorkspace.name} · 远程设备操作")
        }
    }

    // Input area is absent in observer and archived modes, so cards and composer cannot imply control.
    if (canSendToActiveTab) {
    ConversationComposerScaffold("silk-workflow-composer") {
        if (activeTab == "team") {
            pendingRoomImageUrl?.let { objectUrl ->
                ConversationComposerImagePreview(objectUrl) {
                    pendingRoomImage = null
                    pendingRoomImageUrl = null
                    window.asDynamic().URL.revokeObjectURL(objectUrl)
                }
            }
        }
        ConversationComposerContext {
            KnowledgeBaseContextTray(
                statusMessages = statusMessages.filter(belongsToActiveTab),
                selection = kbContextSelection,
                onSelectionChange = {
                    kbContextSelectionTouched = true
                    kbContextSelection = it
                },
            )
        }
        // Badge row: new session + permission mode + agent quick-switch
        ConversationComposerAccessoryRow("silk-workflow-composer-badges") {
            if (activeTab == "team") {
                SilkMentionButton(
                    inputElementId = "wf-composer-input",
                    messageText = messageText,
                    onMessageTextChange = { messageText = it },
                )
            }

            // Workspace session controls are only available to Owner / Co-pilot.
            if (canControlActiveWorkspace) Div({ style { property("position", "relative"); display(DisplayStyle.InlineBlock) } }) {
                Span({
                    style {
                        fontSize(12.px)
                        fontWeight("500")
                        color(Color("#1565C0"))
                        backgroundColor(Color("#E3F2FD"))
                        border(1.px, LineStyle.Solid, Color("#90CAF9"))
                        borderRadius(12.px)
                        padding(2.px, 10.px)
                        property("cursor", "pointer")
                        property("user-select", "none")
                    }
                    onClick {
                        scope.launch {
                            chatClient.sendMessage(
                                userId = userId,
                                userName = userName,
                                content = "/new",
                                scope = if (activeTab == "team") MessageScope.TEAM else MessageScope.WORKSPACE,
                                workspaceId = activeTab.takeUnless { it == "team" },
                            )
                        }
                    }
                }) { Text("新会话") }
            }

            // Permission mode badge
                if (permissionMode.isNotBlank()) {
                    val modeLabel = when (permissionMode) {
                        "INTERACTIVE" -> "Interactive"
                        "ACCEPT_EDITS" -> "Accept Edits"
                        "BYPASS" -> "Bypass"
                        else -> permissionMode
                    }
                    Div({ style { property("position", "relative"); display(DisplayStyle.InlineBlock) } }) {
                        Span({
                            style {
                                fontSize(12.px)
                                fontWeight("500")
                                color(Color("#555555"))
                                backgroundColor(Color("#F7F7F7"))
                                border(1.px, LineStyle.Solid, Color("#E0E0E0"))
                                borderRadius(12.px)
                                padding(2.px, 10.px)
                                property("cursor", "pointer")
                                property("user-select", "none")
                            }
                            onClick {
                                showPermModeDropdown = !showPermModeDropdown
                                showAgentDropdown = false
                            }
                        }) { Text(modeLabel) }

                        if (showPermModeDropdown) {
                            Div({
                                style {
                                    property("position", "absolute")
                                    bottom(28.px)
                                    property("left", "0")
                                    backgroundColor(Color.white)
                                    border(1.px, LineStyle.Solid, Color("#E0E0E0"))
                                    borderRadius(8.px)
                                    property("box-shadow", "0 4px 12px rgba(0,0,0,0.12)")
                                    property("z-index", "100")
                                    property("min-width", "140px")
                                    padding(4.px)
                                }
                            }) {
                                listOf(
                                    "INTERACTIVE" to "Interactive",
                                    "ACCEPT_EDITS" to "Accept Edits",
                                    "BYPASS" to "Bypass",
                                ).forEach { (value, label) ->
                                    val isCurrent = value == permissionMode ||
                                        (value == "INTERACTIVE" && permissionMode.isBlank())
                                    Div({
                                        style {
                                            padding(8.px, 12.px)
                                            borderRadius(4.px)
                                            fontSize(14.px)
                                            property("cursor", "pointer")
                                            fontWeight(if (isCurrent) "600" else "normal")
                                            color(if (isCurrent) Color(SilkColors.primary) else Color("#333333"))
                                            if (isCurrent) backgroundColor(Color("#F0F4FF"))
                                        }
                                        onClick {
                                            showPermModeDropdown = false
                                            if (!isCurrent) {
                                                scope.launch {
                                                    val resp = ApiClient.updateCcSettings(
                                                        userId, activeWorkspaceId ?: return@launch,
                                                        permissionMode = value,
                                                    )
                                                    if (resp.success) {
                                                        permissionMode = resp.permissionMode
                                                    } else {
                                                        switchError = resp.error ?: "切换失败"
                                                    }
                                                }
                                            }
                                        }
                                    }) {
                                        Text(if (isCurrent) "\u2713 $label" else "  $label")
                                    }
                                }
                            }
                        }
                    }
                }

                // Agent badge
                if (activeAgentDisplay.isNotBlank()) {
                    Div({ style { property("position", "relative"); display(DisplayStyle.InlineBlock) } }) {
                        Span({
                            style {
                                fontSize(12.px)
                                fontWeight("500")
                                color(Color("#6A1B9A"))
                                backgroundColor(Color("#F3E5F5"))
                                border(1.px, LineStyle.Solid, Color("#CE93D8"))
                                borderRadius(12.px)
                                padding(2.px, 10.px)
                                property("cursor", "pointer")
                                property("user-select", "none")
                            }
                            onClick {
                                showAgentDropdown = !showAgentDropdown
                                showPermModeDropdown = false
                            }
                        }) { Text(activeAgentDisplay) }

                        if (showAgentDropdown) {
                            Div({
                                style {
                                    property("position", "absolute")
                                    bottom(28.px)
                                    property("left", "0")
                                    backgroundColor(Color.white)
                                    border(1.px, LineStyle.Solid, Color("#E0E0E0"))
                                    borderRadius(8.px)
                                    property("box-shadow", "0 4px 12px rgba(0,0,0,0.12)")
                                    property("z-index", "100")
                                    property("min-width", "180px")
                                    padding(4.px)
                                }
                            }) {
                                availableAgents.forEach { agent ->
                                    val isCurrent = activeAgentDisplay.contains(agent.displayName) ||
                                        activeAgentDisplay.contains(agent.agentType)
                                    Div({
                                        style {
                                            padding(8.px, 12.px)
                                            borderRadius(4.px)
                                            fontSize(14.px)
                                            property("cursor", if (agent.connected) "pointer" else "default")
                                            fontWeight(if (isCurrent) "600" else "normal")
                                            color(when {
                                                isCurrent -> Color("#6A1B9A")
                                                !agent.connected -> Color("#BDBDBD")
                                                else -> Color("#333333")
                                            })
                                            if (isCurrent) backgroundColor(Color("#F3E5F5"))
                                            if (!agent.connected) property("opacity", "0.6")
                                        }
                                        onClick {
                                            if (!isCurrent && agent.connected) {
                                                showAgentDropdown = false
                                                scope.launch {
                                                    val resp = ApiClient.updateCcSettings(
                                                        userId, activeWorkspaceId ?: return@launch,
                                                        activeAgent = agent.agentType,
                                                    )
                                                    if (resp.success) {
                                                        activeAgentDisplay = resp.agentDisplayName
                                                        permissionMode = resp.permissionMode
                                                    } else {
                                                        switchError = resp.error ?: "切换失败"
                                                    }
                                                }
                                            }
                                        }
                                    }) {
                                        val suffix = if (!agent.connected) "（未连接）" else ""
                                        Text(if (isCurrent) "\u2713 ${agent.displayName}$suffix" else "  ${agent.displayName}$suffix")
                                    }
                                }
                            }
                        }
                    }
                }

                // Error toast
                switchError?.let { err ->
                    Span({
                        style {
                            fontSize(11.px)
                            color(Color("#F44336"))
                        }
                    }) { Text(err) }
                    // Auto-clear after display
                    LaunchedEffect(err) {
                        kotlinx.coroutines.delay(3000)
                        switchError = null
                    }
                }
        }

        // Input row
        ConversationComposerInputRow {
        ConversationComposerInputSurface {
        TextArea {
            if (!canSendToActiveTab) attr("disabled", "")
            value(messageText)
            onInput { event ->
                val newValue = event.value
                val oldValue = messageText
                messageText = newValue
                // 检测 $ 触发 KB 引用
                if (newValue.length > oldValue.length) {
                    val lastChar = newValue.lastOrNull()
                    if (lastChar == '$') {
                        val input = document.getElementById("wf-composer-input") as? org.w3c.dom.HTMLElement
                        if (input != null) {
                            val rect = input.getBoundingClientRect()
                            kbRefMenuPosition = Pair(rect.left, window.innerHeight - rect.top + 4)
                        }
                        showKbRefMenu = true
                        kbRefStartIndex = newValue.length - 1
                        kbRefSearchText = ""
                        kbRefSearchResults = emptyList()
                    }
                }
                if (showKbRefMenu && kbRefStartIndex >= 0) {
                    val textAfterDollar = newValue.substring(kbRefStartIndex + 1)
                    val spaceIndex = textAfterDollar.indexOf(' ')
                    if (spaceIndex >= 0) {
                        showKbRefMenu = false
                        kbRefSearchResults = emptyList()
                    } else {
                        kbRefSearchText = textAfterDollar
                    }
                }
            }
            attr("id", "wf-composer-input")
            attr(
                "placeholder",
                if (activeTab == "team") "发送消息...（Shift+Enter 换行）" else "向 Agent 发送消息...（Shift+Enter 换行）",
            )
            attr("class", "silk-conversation-composer-input")
            onKeyDown { event ->
                if (canSendToActiveTab && shouldSubmitWorkflowMessage(event, messageText, hasPendingRoomImage)) {
                    event.preventDefault()
                    sendActiveMessage()
                }
            }
        }

        // $ KB 引用浮动面板
        if (showKbRefMenu) {
            LaunchedEffect(kbRefSearchText) {
                if (kbRefSearchText.length >= 1) {
                    kbRefIsSearching = true
                    kotlinx.coroutines.delay(300)
                    val results = ApiClient.searchKbEntries(userId, kbRefSearchText)
                    kbRefSearchResults = results
                    kbRefIsSearching = false
                } else {
                    kbRefSearchResults = emptyList()
                    kbRefIsSearching = false
                }
            }
            Div({
                style {
                    property("position", "fixed")
                    property("left", "${kbRefMenuPosition.first}px")
                    property("bottom", "${kbRefMenuPosition.second}px")
                    backgroundColor(Color(SilkColors.surface))
                    border {
                        width(1.px)
                        style(LineStyle.Solid)
                        color(Color(SilkColors.border))
                    }
                    borderRadius(8.px)
                    property("box-shadow", "0 4px 12px rgba(0,0,0,0.15)")
                    property("z-index", "9999")
                    property("max-height", "260px")
                    property("overflow-y", "auto")
                    property("min-width", "280px")
                    property("max-width", "400px")
                }
            }) {
                Div({
                    style {
                        padding(8.px, 12.px)
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        property("border-bottom", "1px solid ${SilkColors.border}")
                        property("background", SilkColors.surfaceElevated)
                    }
                }) { Text("📚 搜索 KB 文档") }
                if (kbRefIsSearching) {
                    Div({ style { padding(12.px); fontSize(13.px); color(Color(SilkColors.textLight)) } }) {
                        Text("搜索中...")
                    }
                } else if (kbRefSearchResults.isEmpty()) {
                    if (kbRefSearchText.length >= 1) {
                        Div({ style { padding(12.px); fontSize(13.px); color(Color(SilkColors.textLight)) } }) {
                            Text("未找到匹配文档")
                        }
                    } else {
                        Div({ style { padding(12.px); fontSize(13.px); color(Color(SilkColors.textLight)) } }) {
                            Text("输入关键词搜索 KB 文档...")
                        }
                    }
                } else {
                    kbRefSearchResults.forEach { result ->
                        Div({
                            style {
                                padding(8.px, 12.px)
                                property("cursor", "pointer")
                                property("transition", "background-color 0.15s ease")
                                property("border-bottom", "1px solid ${SilkColors.border}")
                            }
                            onClick {
                                val beforeDollar = messageText.substring(0, kbRefStartIndex)
                                val afterDollar = messageText.substring(kbRefStartIndex + 1)
                                val afterSpace = afterDollar.indexOf(' ').let { idx ->
                                    if (idx >= 0) afterDollar.substring(idx) else ""
                                }
                                messageText = "$beforeDollar[[kb:${result.entryId}|${result.title}]]$afterSpace"
                                showKbRefMenu = false
                                kbRefSearchResults = emptyList()
                                window.setTimeout({
                                    val input = document.getElementById("wf-composer-input")
                                    input?.asDynamic()?.focus()
                                }, 0)
                            }
                            onMouseEnter {
                                (it.target as? org.w3c.dom.HTMLElement)?.style?.backgroundColor = SilkColors.secondary
                            }
                            onMouseLeave {
                                (it.target as? org.w3c.dom.HTMLElement)?.style?.backgroundColor = "transparent"
                            }
                        }) {
                            Div({ style { fontSize(14.px); fontWeight("500"); color(Color(SilkColors.textPrimary)) } }) {
                                Text(result.title)
                            }
                            Div({ style { marginTop(2.px); fontSize(12.px); color(Color(SilkColors.textLight)) } }) {
                                Span({ }) { Text(result.topicName) }
                                Span({ style { marginLeft(8.px) } }) { Text("(${result.spaceLabel})") }
                            }
                        }
                    }
                }
            }
        }

        } // input surface
        ConversationComposerPrimaryAction(
            isGenerating = isActiveStreamGenerating,
            enabled = (messageText.isNotBlank() || hasPendingRoomImage) && canSendToActiveTab,
            sendLabel = "发送",
            stopLabel = "停止",
            onStop = {
                scope.launch {
                    chatClient.stopGeneration(
                        userId = userId,
                        userName = userName,
                        scope = if (activeTab == "team") MessageScope.TEAM else MessageScope.WORKSPACE,
                        workspaceId = activeTab.takeUnless { it == "team" },
                    )
                }
            },
            onSend = sendActiveMessage,
        )
        } // close input row Div
        if (activeTab == "team") {
            ConversationRoomComposerTools(
                roomId = groupId,
                userId = userId,
                messageText = messageText,
                onMessageTextChange = { messageText = it },
                onScreenshotCaptured = { blob, objectUrl ->
                    pendingRoomImageUrl?.let { previousUrl ->
                        window.asDynamic().URL.revokeObjectURL(previousUrl)
                    }
                    pendingRoomImage = blob
                    pendingRoomImageUrl = objectUrl
                },
            )
        }
    }
    } else {
        Div({
            attr("class", "silk-conversation-fixed-region")
            style {
                property("flex-shrink", "0")
                padding(12.px, 16.px)
                property("border-top", "1px solid ${SilkColors.border}")
                backgroundColor(Color(SilkColors.surfaceElevated))
                color(Color(SilkColors.textSecondary))
                fontSize(13.px)
            }
        }) {
            Text(
                when {
                    activeWorkspace == null -> "工作区不可用或共享已撤销"
                    activeWorkspace.lifecycleState == "REVOKED" -> "历史共享只读 · 当前共享已撤销"
                    activeWorkspace.lifecycleState == "ARCHIVED" -> "工作区已归档"
                    else -> "只读旁观"
                }
            )
        }
    }
    } // close conversation pane
        if (sourcePanelOpen && activeWorkspaceId != null && canControlActiveWorkspace) {
            ColumnResizer(
                isLeftPanel = false,
                minWidth = 300,
                maxWidth = 800,
                currentWidth = { sourcePanelWidth },
                onResize = { sourcePanelWidth = it },
                onCommit = { LayoutPrefs.setInt("silk_wf_scpanel_w", sourcePanelWidth) },
            )
            SourceControlPanel(
                workspaceId = activeWorkspaceId,
                refreshSignal = diffRefreshSignal,
                widthPx = sourcePanelWidth,
            )
        }
    } // close shared room scaffold

    // 知识库入库对话框
    kbCaptureDraft?.let { draft ->
        KnowledgeBaseCaptureDialog(
            draft = draft,
            spaceOptions = buildKnowledgeSpaceOptions(kbCaptureGroups),
            topics = kbCaptureTopics,
            selectedSpaceId = kbCaptureSelectedSpaceId,
            selectedTopicId = kbCaptureSelectedTopicId,
            title = kbCaptureTitle,
            content = kbCaptureContent,
            isSaving = kbCaptureSaving,
            resultMessage = kbCaptureResult,
            onSelectedSpaceIdChange = { kbCaptureSelectedSpaceId = it },
            onSelectedTopicIdChange = { kbCaptureSelectedTopicId = it },
            onTitleChange = { kbCaptureTitle = it },
            onContentChange = { kbCaptureContent = it },
            onDismiss = resetKnowledgeCaptureDialog,
            onConfirm = {
                if (!canSubmitKnowledgeCapture(kbCaptureSaving, kbCaptureSelectedTopicId, kbCaptureTitle, kbCaptureContent)) {
                    return@KnowledgeBaseCaptureDialog
                }
                scope.launch {
                    kbCaptureSaving = true
                    val created = ApiClient.captureKBEntry(
                        topicId = kbCaptureSelectedTopicId,
                        title = kbCaptureTitle.trim(),
                        content = kbCaptureContent,
                        tags = emptyList(),
                        userId = userId,
                        source = KBEntrySource(
                            sourceType = draft.sourceType,
                            sourceGroupId = draft.sourceGroupId,
                            workflowId = draft.workflowId,
                            messageIds = listOf(draft.message.id),
                        ),
                    )
                    kbCaptureSaving = false
                    if (created == null) {
                        kbCaptureResult = "保存失败，请确认目标主题仍可写。"
                    } else {
                        resetKnowledgeCaptureDialog()
                        appState.openKnowledgeBaseEntry(created.id, created.topicId)
                    }
                }
            },
        )
    }

    // Folder picker dialog (direct, no settings wrapper)
    if (showFolderPicker) {
        FolderPickerDialog(
            userId = userId,
            workspaceId = activeWorkspaceId,
            initialPath = workingDir.ifBlank { null },
            onDismiss = { showFolderPicker = false },
            onConfirm = { selectedPath ->
                showFolderPicker = false
                if (selectedPath != workingDir) {
                    scope.launch {
                        when (val tc = ApiClient.checkTrustedDir(userId, selectedPath)) {
                            is ApiClient.TrustCheckResult.BridgeDisconnected ->
                                switchError = "Bridge 未连接，无法切换目录。"
                            is ApiClient.TrustCheckResult.NotTrusted -> {
                                trustConfirmPath = selectedPath
                                trustConfirmBridgeId = tc.bridgeId
                                showTrustConfirm = true
                            }
                            is ApiClient.TrustCheckResult.Error ->
                                switchError = "检查信任状态失败：${tc.message}"
                            is ApiClient.TrustCheckResult.Trusted -> {
                                val cdResp = ApiClient.cdCcDir(
                                    userId,
                                    activeWorkspaceId ?: return@launch,
                                    selectedPath,
                                )
                                if (cdResp.success) {
                                    workingDir = cdResp.workingDir
                                } else {
                                    switchError = "切换目录失败：${cdResp.error ?: "未知错误"}"
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
                    val added = ApiClient.addTrustedDir(userId, trustConfirmPath)
                    if (!added) {
                        switchError = "添加信任记录失败，请重试。"
                        showTrustConfirm = false
                        return@launch
                    }
                    showTrustConfirm = false
                    val cdResp = ApiClient.cdCcDir(
                        userId,
                        activeWorkspaceId ?: return@launch,
                        trustConfirmPath,
                    )
                    if (cdResp.success) {
                        workingDir = cdResp.workingDir
                    } else {
                        switchError = "切换目录失败：${cdResp.error ?: "未知错误"}"
                    }
                }
            },
        )
    }

    if (showWorkspaceCreate) {
        WorkspaceCreateDialog(
            userId = userId,
            roomId = groupId,
            agents = availableAgents,
            onDismiss = { showWorkspaceCreate = false },
            onCreated = { created ->
                workspaces = (workspaces.filterNot { it.workspaceId == created.workspaceId } + created)
                activeTab = created.workspaceId
                onWorkspaceCreated(created)
                showWorkspaceCreate = false
                workspaceRefreshVersion += 1
            },
        )
    }

    workspaceManageTarget?.let { target ->
        WorkspaceManageDialog(
            roomId = groupId,
            workspace = target,
            members = groupMembers,
            workingDir = workingDir,
            onChooseWorkingDirectory = {
                workspaceManageTarget = null
                showFolderPicker = true
            },
            onDismiss = { workspaceManageTarget = null },
            onUpdated = { updated ->
                workspaces = workspaces.map { if (it.workspaceId == updated.workspaceId) updated else it }
                workspaceManageTarget = updated
                onWorkspaceUpdated(updated)
                workspaceRefreshVersion += 1
            },
            onDeleted = { deletedId ->
                workspaces = workspaces.filterNot { it.workspaceId == deletedId }
                if (activeTab == deletedId) activeTab = "team"
                workspaceManageTarget = null
                onWorkspaceDeleted(deletedId)
                workspaceRefreshVersion += 1
            },
        )
    }

    if (showRoomMembers) {
        val memberItems = roomMembers.map { member ->
            ConversationRoomMemberItem(
                id = member.id,
                displayName = member.fullName,
                roleLabel = if (member.role == "OWNER") "Owner" else "Member",
                avatarText = member.fullName.firstOrNull()?.toString() ?: "?",
                avatarTone = if (member.role == "OWNER") {
                    ConversationMemberTone.PRIMARY
                } else {
                    ConversationMemberTone.MUTED
                },
                canRemove = workflowRole == "OWNER" && member.role != "OWNER",
            )
        }
        val memberIds = roomMembers.mapTo(mutableSetOf()) { it.id }
        val candidateItems = if (memberQuery.isBlank()) {
            roomContacts
                .filter { it.contactId !in memberIds }
                .map { contact ->
                    ConversationRoomMemberCandidateItem(
                        id = contact.contactId,
                        displayName = contact.contactName,
                        detail = contact.contactPhone,
                    )
                }
        } else {
            memberCandidates
                .filter { it.id !in memberIds }
                .map { candidate ->
                    ConversationRoomMemberCandidateItem(
                        id = candidate.id,
                        displayName = candidate.fullName,
                        detail = "${candidate.loginName} · ${candidate.phoneNumber}",
                    )
                }
        }
        ConversationRoomMembersDialog(
            strings = strings,
            members = memberItems,
            candidates = candidateItems,
            canAddMembers = workflowRole == "OWNER",
            query = memberQuery,
            loading = memberBusy,
            busy = memberBusy,
            errorMessage = memberError,
            successMessage = memberFeedback,
            emptyCandidatesMessage = roomMemberCandidateEmptyMessage(
                strings = strings,
                query = memberQuery,
                searchAttempted = memberSearchAttempted,
            ),
            onQueryChange = {
                memberQuery = it
                memberCandidates = emptyList()
                memberSearchAttempted = false
                memberFeedback = null
            },
            onSearch = {
                val searchedQuery = memberQuery.trim()
                memberSearchAttempted = false
                scope.launch {
                    memberBusy = true
                    memberError = null
                    memberFeedback = null
                    try {
                        val response = ApiClient.searchWorkflowMemberCandidates(workflowId, searchedQuery)
                        if (memberQuery.trim() != searchedQuery) return@launch
                        memberSearchAttempted = true
                        if (response.success) {
                            memberCandidates = response.candidates
                        } else {
                            memberError = response.message.ifBlank { "搜索失败" }
                        }
                    } finally {
                        memberBusy = false
                    }
                }
            },
            onAdd = { candidateId ->
                val candidate = candidateItems.firstOrNull { it.id == candidateId }
                    ?: return@ConversationRoomMembersDialog
                scope.launch {
                    memberBusy = true
                    memberError = null
                    memberFeedback = null
                    try {
                        val response = ApiClient.addWorkflowRoomMember(workflowId, candidate.id)
                        if (response.success) {
                            roomMembers = response.members
                            groupMembers = response.members.map {
                                GroupMember(id = it.id, fullName = it.fullName, role = it.role)
                            }
                            memberCandidates = memberCandidates.filterNot { it.id == candidate.id }
                            memberFeedback = "已添加 ${candidate.displayName}"
                        } else {
                            memberError = response.message.ifBlank { "添加成员失败" }
                        }
                    } finally {
                        memberBusy = false
                    }
                }
            },
            onRemove = { memberId ->
                val member = roomMembers.firstOrNull { it.id == memberId }
                    ?: return@ConversationRoomMembersDialog
                scope.launch {
                    memberBusy = true
                    memberError = null
                    memberFeedback = null
                    try {
                        val response = ApiClient.removeWorkflowRoomMember(workflowId, member.id)
                        if (response.success) {
                            roomMembers = response.members
                            groupMembers = response.members.map {
                                GroupMember(id = it.id, fullName = it.fullName, role = it.role)
                            }
                            memberFeedback = "已移除 ${member.fullName}"
                            workspaceRefreshVersion += 1
                        } else {
                            memberError = response.message.ifBlank { "移除成员失败" }
                        }
                    } finally {
                        memberBusy = false
                    }
                }
            },
            onDismiss = {
                showRoomMembers = false
                memberCandidates = emptyList()
                memberQuery = ""
                memberSearchAttempted = false
                memberError = null
                memberFeedback = null
            },
        )
    }

    if (showFolderExplorer) {
        FolderExplorerDialog(
            groupId = groupId,
            userId = userId,
            strings = strings,
            onDismiss = { showFolderExplorer = false },
        )
    }
    if (showGitIntegration) {
        GitHubIntegrationDialog(
            roomId = groupId,
            canManage = workflowRole == "OWNER",
            onDismiss = { showGitIntegration = false },
        )
    }
    githubIssueNumber?.let { issueNumber ->
        GithubIssueWorkspaceDialog(
            userId = userId,
            roomId = groupId,
            issueNumber = issueNumber,
            agents = availableAgents,
            initialWorkingDir = activeWorkspace?.workingDir.orEmpty().ifBlank {
                currentWorkspaces.firstOrNull { it.ownerId == userId && it.lifecycleState == "ACTIVE" }
                    ?.workingDir.orEmpty()
            },
            onDismiss = { githubIssueNumber = null },
            onCreated = { response ->
                val created = response.workspace
                workspaces = workspaces.filterNot { it.workspaceId == created.workspaceId } + created
                activeTab = created.workspaceId
                onWorkspaceCreated(created)
                githubIssueNumber = null
                workspaceRefreshVersion += 1
                scope.launch {
                    chatClient.sendMessage(
                        userId = userId,
                        userName = userName,
                        content = response.issueSummary,
                        type = MessageType.SYSTEM,
                        scope = MessageScope.WORKSPACE,
                        workspaceId = created.workspaceId,
                    )
                }
            },
        )
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
@Composable
private fun WorkspaceCreateDialog(
    userId: String,
    roomId: String,
    agents: List<AgentInfo>,
    onDismiss: () -> Unit,
    onCreated: (WorkspaceDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var workingDir by remember { mutableStateOf("") }
    var agentType by remember(agents) {
        mutableStateOf(agents.firstOrNull { it.connected }?.agentType.orEmpty())
    }
    var visibility by remember { mutableStateOf("PRIVATE") }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showTrustConfirm by remember { mutableStateOf(false) }
    var trustBridgeId by remember { mutableStateOf<String?>(null) }

    suspend fun create() {
        val token = JwtManager.getAccessToken()
        if (token == null) {
            errorMessage = "登录状态已失效"
            return
        }
        if (name.isBlank() || workingDir.isBlank() || agentType.isBlank()) {
            errorMessage = "请填写名称、Agent 和工作目录"
            return
        }
        saving = true
        errorMessage = null
        try {
            val created = createRoomWorkspace(
                roomId = roomId,
                authToken = token,
                name = name.trim(),
                workingDir = workingDir.trim(),
                agentType = agentType,
                visibility = visibility,
            )
            onCreated(created)
        } catch (e: WorkspaceApiException) {
            if (e.errorCode == "DIRECTORY_NOT_TRUSTED") {
                trustBridgeId = e.bridgeId
                showTrustConfirm = true
            } else {
                errorMessage = e.message
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "创建工作区失败"
        } finally {
            saving = false
        }
    }

    suspend fun validateAndCreate() {
        if (workingDir.isBlank()) {
            errorMessage = "请选择工作目录"
            return
        }
        when (val trust = ApiClient.checkTrustedDir(userId, workingDir.trim())) {
            is ApiClient.TrustCheckResult.Trusted -> create()
            is ApiClient.TrustCheckResult.NotTrusted -> {
                trustBridgeId = trust.bridgeId
                showTrustConfirm = true
            }
            is ApiClient.TrustCheckResult.BridgeDisconnected -> errorMessage = "Bridge 未连接"
            is ApiClient.TrustCheckResult.Error -> errorMessage = trust.message
        }
    }

    ModalOverlay(onDismiss = { if (!saving) onDismiss() }) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(8.px)
                padding(24.px)
                width(460.px)
                property("max-width", "calc(100vw - 32px)")
                property("box-sizing", "border-box")
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.16)")
            }
        }) {
            H3({ style { marginTop(0.px); marginBottom(20.px); color(Color(SilkColors.textPrimary)) } }) {
                Text("新建工作区")
            }
            WorkspaceFormLabel("名称")
            Input(InputType.Text) {
                value(name)
                onInput { name = it.value }
                attr("placeholder", "例如：auth refactor")
                style { workspaceFormInputStyle() }
            }

            WorkspaceFormLabel("Agent")
            Select({
                style { workspaceFormInputStyle() }
                onChange { agentType = it.value ?: "" }
            }) {
                if (agents.isEmpty()) {
                    Option("") { Text("没有可用 Agent") }
                }
                agents.forEach { agent ->
                    Option(
                        agent.agentType,
                        attrs = {
                            if (!agent.connected) attr("disabled", "")
                            if (agent.agentType == agentType) attr("selected", "")
                        },
                    ) { Text("${agent.displayName}${if (agent.connected) "" else "（离线）"}") }
                }
            }

            WorkspaceFormLabel("工作目录")
            Div({ style { display(DisplayStyle.Flex); property("gap", "8px") } }) {
                Input(InputType.Text) {
                    value(workingDir)
                    onInput { workingDir = it.value }
                    attr("placeholder", "工作目录路径")
                    style { workspaceFormInputStyle(flex = true) }
                }
                Button({
                    attr("title", "选择工作目录")
                    style {
                        width(40.px); height(40.px); padding(0.px)
                        borderRadius(6.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        backgroundColor(Color.white)
                        color(Color(SilkColors.primary))
                        property("cursor", "pointer")
                    }
                    onClick { showFolderPicker = true }
                }) { Text("📂") }
            }

            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.SpaceBetween)
                    marginTop(16.px)
                    marginBottom(18.px)
                }
            }) {
                Div {
                    Div({ style { fontSize(13.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                        Text("团队可见")
                    }
                    Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginTop(2.px) } }) {
                        Text(if (visibility == "SHARED") "SHARED" else "PRIVATE")
                    }
                }
                Input(InputType.Checkbox) {
                    checked(visibility == "SHARED")
                    onInput { visibility = if (visibility == "SHARED") "PRIVATE" else "SHARED" }
                    style { property("transform", "scale(1.2)") }
                }
            }

            errorMessage?.let { message ->
                Div({ style { color(Color(SilkColors.error)); fontSize(12.px); marginBottom(12.px) } }) {
                    Text(message)
                }
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.FlexEnd)
                    property("gap", "8px")
                }
            }) {
                WorkspaceSecondaryButton("取消", disabled = saving, onClick = onDismiss)
                WorkspacePrimaryButton(
                    label = if (saving) "创建中..." else "创建",
                    disabled = saving || name.isBlank() || workingDir.isBlank() || agentType.isBlank(),
                    onClick = { scope.launch { validateAndCreate() } },
                )
            }
        }
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            userId = userId,
            initialPath = workingDir.ifBlank { null },
            onDismiss = { showFolderPicker = false },
            onConfirm = {
                workingDir = it
                showFolderPicker = false
            },
        )
    }
    if (showTrustConfirm) {
        TrustConfirmDialog(
            path = workingDir,
            bridgeId = trustBridgeId,
            onDismiss = { showTrustConfirm = false },
            onTrust = {
                scope.launch {
                    saving = true
                    val added = ApiClient.addTrustedDir(userId, workingDir)
                    saving = false
                    if (added) {
                        showTrustConfirm = false
                        create()
                    } else {
                        errorMessage = "添加信任记录失败"
                    }
                }
            },
        )
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
@Composable
private fun WorkspaceManageDialog(
    roomId: String,
    workspace: WorkspaceDto,
    members: List<GroupMember>,
    workingDir: String,
    onChooseWorkingDirectory: () -> Unit,
    onDismiss: () -> Unit,
    onUpdated: (WorkspaceDto) -> Unit,
    onDeleted: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(workspace.workspaceId) { mutableStateOf(workspace.name) }
    var visibility by remember(workspace.workspaceId) { mutableStateOf(workspace.visibility) }
    var copilots by remember(workspace.workspaceId) { mutableStateOf(workspace.copilots.toSet()) }
    var saving by remember { mutableStateOf(false) }
    var deleteArmed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    suspend fun update(
        updatedName: String? = null,
        updatedVisibility: String? = null,
        updatedCopilots: List<String>? = null,
        lifecycleState: String? = null,
    ) {
        val token = JwtManager.getAccessToken()
        if (token == null) {
            errorMessage = "登录状态已失效"
            return
        }
        saving = true
        errorMessage = null
        successMessage = null
        try {
            val updated = patchRoomWorkspace(
                roomId = roomId,
                workspaceId = workspace.workspaceId,
                authToken = token,
                name = updatedName,
                visibility = updatedVisibility,
                copilots = updatedCopilots,
                lifecycleState = lifecycleState,
            )
            onUpdated(updated)
            successMessage = when (lifecycleState) {
                "ARCHIVED" -> "工作区已归档"
                "ACTIVE" -> "工作区已恢复"
                else -> "设置已保存"
            }
        } catch (e: WorkspaceApiException) {
            errorMessage = e.message
        } catch (e: Exception) {
            errorMessage = e.message ?: "工作区更新失败"
        } finally {
            saving = false
        }
    }

    suspend fun delete() {
        val token = JwtManager.getAccessToken()
        if (token == null) {
            errorMessage = "登录状态已失效"
            return
        }
        saving = true
        errorMessage = null
        try {
            deleteRoomWorkspace(roomId, workspace.workspaceId, token)
            onDeleted(workspace.workspaceId)
        } catch (e: WorkspaceApiException) {
            errorMessage = e.message
        } catch (e: Exception) {
            errorMessage = e.message ?: "删除工作区失败"
        } finally {
            saving = false
        }
    }

    ModalOverlay(onDismiss = { if (!saving) onDismiss() }) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(8.px)
                padding(24.px)
                width(500.px)
                property("max-width", "calc(100vw - 32px)")
                property("max-height", "calc(100vh - 48px)")
                property("overflow-y", "auto")
                property("box-sizing", "border-box")
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.16)")
            }
        }) {
            H3({ style { marginTop(0.px); marginBottom(4.px); color(Color(SilkColors.textPrimary)) } }) {
                Text("工作区设置")
            }
            Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginBottom(18.px) } }) {
                Text("${workspace.ownerDisplayName} · ${workspace.agentType} · ${workspace.activity.state}")
            }

            WorkspaceFormLabel("名称")
            Input(InputType.Text) {
                value(name)
                onInput {
                    name = it.value
                    successMessage = null
                }
                style { workspaceFormInputStyle() }
            }

            WorkspaceFormLabel("工作目录")
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "8px")
                    padding(8.px, 10.px)
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    borderRadius(6.px)
                    backgroundColor(Color("#FAFAFA"))
                }
            }) {
                Span({
                    attr("title", workingDir)
                    style {
                        property("flex", "1")
                        property("min-width", "0")
                        property("overflow", "hidden")
                        property("text-overflow", "ellipsis")
                        property("white-space", "nowrap")
                        fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                        fontSize(12.px)
                        color(Color(if (workingDir.isBlank()) SilkColors.textSecondary else SilkColors.textPrimary))
                    }
                }) { Text(workingDir.ifBlank { "尚未设置" }) }
                WorkspaceSecondaryButton(
                    label = "📂 更改",
                    disabled = saving || workspace.lifecycleState != "ACTIVE",
                    onClick = onChooseWorkingDirectory,
                )
            }

            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.SpaceBetween)
                    marginTop(14.px)
                    marginBottom(14.px)
                }
            }) {
                Div {
                    Div({ style { fontSize(13.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                        Text("团队可见")
                    }
                    Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginTop(2.px) } }) {
                        Text(if (visibility == "SHARED") "SHARED" else "PRIVATE")
                    }
                }
                Input(InputType.Checkbox) {
                    checked(visibility == "SHARED")
                    onInput {
                        visibility = if (visibility == "SHARED") "PRIVATE" else "SHARED"
                        successMessage = null
                    }
                    style { property("transform", "scale(1.2)") }
                }
            }

            Div({
                style {
                    padding(10.px, 12.px)
                    backgroundColor(Color("#FFF8E1"))
                    color(Color("#7A4F00"))
                    borderRadius(6.px)
                    fontSize(12.px)
                    property("line-height", "1.5")
                    marginBottom(10.px)
                }
            }) { Text("Co-pilot 可以在 ${workspace.ownerDisplayName} 的设备上执行代码。") }

            WorkspaceFormLabel("Co-pilot")
            val eligibleMembers = members.filter { it.id != workspace.ownerId }
            if (eligibleMembers.isEmpty()) {
                Div({ style { color(Color(SilkColors.textSecondary)); fontSize(12.px); marginBottom(14.px) } }) {
                    Text("没有可授权的 Room 成员")
                }
            } else {
                Div({
                    style {
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(6.px)
                        property("max-height", "160px")
                        property("overflow-y", "auto")
                        marginBottom(14.px)
                    }
                }) {
                    eligibleMembers.forEach { member ->
                        val selected = member.id in copilots
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                justifyContent(JustifyContent.SpaceBetween)
                                padding(9.px, 12.px)
                                property("border-bottom", "1px solid ${SilkColors.border}")
                            }
                        }) {
                            Div {
                                Div({ style { fontSize(13.px); color(Color(SilkColors.textPrimary)) } }) {
                                    Text(member.fullName)
                                }
                                Div({ style { fontSize(11.px); color(Color(SilkColors.textSecondary)); marginTop(2.px) } }) {
                                    Text(member.role)
                                }
                            }
                            Input(InputType.Checkbox) {
                                checked(selected && visibility == "SHARED")
                                if (visibility != "SHARED") attr("disabled", "")
                                onInput {
                                    copilots = if (selected) copilots - member.id else copilots + member.id
                                    successMessage = null
                                }
                            }
                        }
                    }
                }
            }

            errorMessage?.let { message ->
                Div({ style { color(Color(SilkColors.error)); fontSize(12.px); marginBottom(12.px) } }) {
                    Text(message)
                }
            }
            successMessage?.let { message ->
                Div({ style { color(Color("#2E7D32")); fontSize(12.px); marginBottom(12.px) } }) {
                    Text(message)
                }
            }

            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("flex-wrap", "wrap")
                    justifyContent(JustifyContent.SpaceBetween)
                    property("gap", "8px")
                    padding(14.px, 0.px)
                    property("border-top", "1px solid ${SilkColors.border}")
                }
            }) {
                Div({ style { display(DisplayStyle.Flex); property("gap", "8px") } }) {
                    WorkspaceSecondaryButton(
                        label = if (workspace.lifecycleState == "ACTIVE") "归档" else "恢复",
                        disabled = saving,
                        onClick = {
                            scope.launch {
                                update(
                                    lifecycleState = if (workspace.lifecycleState == "ACTIVE") "ARCHIVED" else "ACTIVE"
                                )
                            }
                        },
                    )
                    if (workspace.lifecycleState == "ARCHIVED") {
                        if (deleteArmed) {
                            WorkspaceDangerButton("确认删除", saving) { scope.launch { delete() } }
                        } else {
                            WorkspaceDangerButton("删除", saving) { deleteArmed = true }
                        }
                    }
                }
                Div({ style { display(DisplayStyle.Flex); property("gap", "8px") } }) {
                    WorkspaceSecondaryButton("取消", saving, onDismiss)
                    WorkspacePrimaryButton(
                        label = when {
                            saving -> "保存中..."
                            successMessage == "设置已保存" -> "已保存"
                            else -> "保存"
                        },
                        disabled = saving || name.isBlank(),
                        onClick = {
                            scope.launch {
                                update(
                                    updatedName = name.trim(),
                                    updatedVisibility = visibility,
                                    updatedCopilots = if (visibility == "SHARED") copilots.toList() else emptyList(),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceFormLabel(label: String) {
    Span({
        style {
            property("display", "block")
            fontSize(12.px)
            color(Color(SilkColors.textSecondary))
            marginTop(12.px)
            marginBottom(5.px)
        }
    }) { Text(label) }
}

internal fun org.jetbrains.compose.web.css.StyleScope.workspaceFormInputStyle(flex: Boolean = false) {
    if (flex) property("flex", "1") else width(100.percent)
    height(40.px)
    borderRadius(6.px)
    border(1.px, LineStyle.Solid, Color(SilkColors.border))
    padding(8.px, 10.px)
    fontSize(13.px)
    backgroundColor(Color.white)
    property("box-sizing", "border-box")
}

@Composable
internal fun WorkspacePrimaryButton(label: String, disabled: Boolean, onClick: () -> Unit) {
    Button({
        if (disabled) attr("disabled", "")
        style {
            minHeight(36.px)
            border(0.px)
            borderRadius(6.px)
            padding(7.px, 16.px)
            backgroundColor(Color(if (disabled) SilkColors.primaryLight else SilkColors.primary))
            color(Color.white)
            property("cursor", if (disabled) "not-allowed" else "pointer")
        }
        onClick { if (!disabled) onClick() }
    }) { Text(label) }
}

@Composable
internal fun WorkspaceSecondaryButton(label: String, disabled: Boolean, onClick: () -> Unit) {
    Button({
        if (disabled) attr("disabled", "")
        style {
            minHeight(36.px)
            borderRadius(6.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            padding(7.px, 14.px)
            backgroundColor(Color.white)
            color(Color(SilkColors.textSecondary))
            property("cursor", if (disabled) "not-allowed" else "pointer")
        }
        onClick { if (!disabled) onClick() }
    }) { Text(label) }
}

@Composable
internal fun WorkspaceDangerButton(label: String, disabled: Boolean, onClick: () -> Unit) {
    Button({
        if (disabled) attr("disabled", "")
        style {
            minHeight(36.px)
            borderRadius(6.px)
            border(1.px, LineStyle.Solid, Color("#D32F2F"))
            padding(7.px, 14.px)
            backgroundColor(Color.white)
            color(Color("#D32F2F"))
            property("cursor", if (disabled) "not-allowed" else "pointer")
        }
        onClick { if (!disabled) onClick() }
    }) { Text(label) }
}

@Composable
private fun WorkspaceActivityDot(state: String) {
    val color = when (state) {
        "RUNNING" -> "#2E7D32"
        "WAITING" -> "#F9A825"
        "IDLE" -> "#78909C"
        else -> "#BDBDBD"
    }
    Span({
        attr("title", state.lowercase().replaceFirstChar { it.uppercase() })
        style {
            width(8.px); height(8.px)
            borderRadius(50.percent)
            backgroundColor(Color(color))
            property("display", "inline-block")
            property("flex-shrink", "0")
        }
    })
}

@Composable
private fun WorkspaceTabButton(
    label: String,
    isActive: Boolean,
    workspace: WorkspaceDto? = null,
    onClick: () -> Unit,
) {
    Div({
        attr("title", workspace?.let { "${it.ownerDisplayName} / ${it.name} / ${it.activity.state}" } ?: label)
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "6px")
            padding(7.px, 10.px)
            borderRadius(6.px)
            fontSize(13.px)
            fontWeight(if (isActive) "600" else "400")
            color(Color(if (isActive) SilkColors.primary else SilkColors.textSecondary))
            backgroundColor(Color(if (isActive) SilkColors.surface else "transparent"))
            property("cursor", "pointer")
            property("user-select", "none")
            property("max-width", "190px")
            property("white-space", "nowrap")
        }
        onClick { onClick() }
    }) {
        workspace?.let { WorkspaceActivityDot(it.activity.state) }
        Span({
            style {
                property("overflow", "hidden")
                property("text-overflow", "ellipsis")
            }
        }) { Text(label) }
    }
}

@Composable
private fun WorkspaceGroupSelect(
    label: String,
    workspaces: List<WorkspaceDto>,
    activeTab: String,
    onSelect: (String) -> Unit,
) {
    val selected = workspaces.firstOrNull { it.workspaceId == activeTab }
    key(selected?.workspaceId.orEmpty()) {
        Select({
            attr("title", "$label · ${workspaces.size}")
            style {
                height(32.px)
                property("max-width", "190px")
                borderRadius(6.px)
                border(1.px, LineStyle.Solid, Color(if (selected != null) SilkColors.primary else SilkColors.border))
                backgroundColor(Color.white)
                color(Color(if (selected != null) SilkColors.primary else SilkColors.textSecondary))
                padding(4.px, 8.px)
                fontSize(12.px)
            }
            onChange { value -> value.value?.takeIf { it.isNotBlank() }?.let(onSelect) }
        }) {
            Option("", attrs = { if (selected == null) attr("selected", "") }) {
                Text("$label (${workspaces.size})")
            }
            workspaces.forEach { workspace ->
                Option(
                    workspace.workspaceId,
                    attrs = { if (selected?.workspaceId == workspace.workspaceId) attr("selected", "") },
                ) {
                    Text(
                        if (workspace.lifecycleState == "REVOKED") {
                            "${workspace.ownerDisplayName} - ${workspace.name}"
                        }
                        else "${workspace.name} · ${workspace.activity.state}"
                    )
                }
            }
        }
    }
}

/**
 * 会话设置弹窗：工作目录 / Agent / 权限模式三合一。
 */
@Suppress("UnusedPrivateMember", "CyclomaticComplexMethod")
@Composable
private fun WorkflowSettingsDialog(
    userId: String,
    workspaceId: String,
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
    var showFolderPicker by remember { mutableStateOf(false) }
    var showTrustConfirm by remember { mutableStateOf(false) }
    var trustConfirmPath by remember { mutableStateOf("") }
    var trustConfirmBridgeId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 初始化：加载 agent 列表和当前 agent 状态
    LaunchedEffect(Unit) {
        val agents = ApiClient.listAgents(userId)
        availableAgents = agents
        // 从当前显示名反查 agentType
        val snap = ApiClient.getCcState(userId, workspaceId)
        if (snap.success && snap.agentType.isNotBlank()) {
            // agentType 从 runtime 是 dash form，转 underscore form 用于 dropdown
            selectedAgentType = snap.agentType.replace('-', '_')
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
            // 1. 如果目录变了，先 cd（含信任检查）
            if (resultDir.isNotBlank() && resultDir != currentWorkingDir) {
                val trustCheck = ApiClient.checkTrustedDir(userId, resultDir)
                when (trustCheck) {
                    is ApiClient.TrustCheckResult.BridgeDisconnected -> {
                        errorMsg = "Bridge 未连接，无法切换目录。"
                        return
                    }
                    is ApiClient.TrustCheckResult.NotTrusted -> {
                        trustConfirmPath = resultDir
                        trustConfirmBridgeId = trustCheck.bridgeId
                        showTrustConfirm = true
                        return
                    }
                    is ApiClient.TrustCheckResult.Error -> {
                        errorMsg = "检查信任状态失败：${trustCheck.message}"
                        return
                    }
                    else -> {}
                }
                val cdResp = ApiClient.cdCcDir(userId, workspaceId, resultDir)
                if (!cdResp.success) {
                    errorMsg = "切换目录失败：${cdResp.error ?: "未知错误"}"
                    return
                }
                newDir = cdResp.workingDir
            }

            // 2. Agent / 权限模式变化：合并为一次 API 调用
            val currentAgentUnderscore = currentAgentDisplay.let {
                // 从当前 snapshot 再取一次精确的 agentType
                val s = ApiClient.getCcState(userId, workspaceId)
                s.agentType.replace('-', '_')
            }
            val agentChanged = selectedAgentType.isNotBlank() && selectedAgentType != currentAgentUnderscore
            val permChanged = selectedPermMode != currentPermissionMode
            if (agentChanged || permChanged) {
                val resp = ApiClient.updateCcSettings(
                    userId, workspaceId,
                    activeAgent = if (agentChanged) selectedAgentType else null,
                    permissionMode = if (permChanged) selectedPermMode.ifBlank { "INTERACTIVE" } else null,
                )
                if (!resp.success) {
                    errorMsg = "更新设置失败：${resp.error ?: "未知错误"}"
                    return
                }
                newAgentDisplay = resp.agentDisplayName
                newPermMode = resp.permissionMode
                // 如果前面没 cd 过，workingDir 也从这里取
                if (newDir == currentWorkingDir) newDir = resp.workingDir
            }

            onApplied(newDir, newAgentDisplay, newPermMode)
        } finally {
            saving = false
        }
    }

    // 信任后重试 cd + 继续 apply
    suspend fun applyAfterTrust() {
        saving = true
        errorMsg = null
        try {
            val added = ApiClient.addTrustedDir(userId, trustConfirmPath)
            if (!added) {
                errorMsg = "添加信任记录失败，请重试。"
                return
            }
            showTrustConfirm = false
            applyChanges()
        } finally {
            saving = false
        }
    }

    // 背景遮罩
    Div({
        style {
            position(Position.Fixed)
            property("inset", "0")
            backgroundColor(Color("rgba(0,0,0,0.4)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
        }
        onClick { if (!saving) onDismiss() }
    }) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                padding(24.px)
                width(440.px)
                property("max-width", "90vw")
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({ style { marginTop(0.px); color(Color(SilkColors.textPrimary)) } }) { Text("会话设置") }

            // Agent 选择
            Span({
                style {
                    fontSize(12.px); color(Color(SilkColors.textSecondary))
                    property("display", "block"); marginBottom(4.px)
                }
            }) { Text("Agent") }
            Select({
                style {
                    width(100.percent); height(40.px); borderRadius(6.px)
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    padding(8.px); fontSize(14.px); marginBottom(12.px)
                    property("box-sizing", "border-box"); backgroundColor(Color.white)
                }
                onChange { selectedAgentType = it.value ?: "" }
            }) {
                if (availableAgents.isEmpty()) {
                    Option("") { Text("加载中…") }
                } else {
                    availableAgents.forEach { agent ->
                        Option(
                            value = agent.agentType,
                            attrs = {
                                if (!agent.connected) attr("disabled", "")
                                if (agent.agentType == selectedAgentType) attr("selected", "")
                            },
                        ) {
                            val suffix = if (agent.connected) "" else "（未连接）"
                            Text("${agent.displayName}${suffix}")
                        }
                    }
                }
            }

            // 权限模式
            Span({
                style {
                    fontSize(12.px); color(Color(SilkColors.textSecondary))
                    property("display", "block"); marginBottom(4.px)
                }
            }) { Text("权限模式") }
            Select({
                style {
                    width(100.percent); height(40.px); borderRadius(6.px)
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    padding(8.px); fontSize(14.px); marginBottom(12.px)
                    property("box-sizing", "border-box"); backgroundColor(Color.white)
                }
                onChange { selectedPermMode = it.value ?: "" }
            }) {
                Option("", attrs = { if (selectedPermMode.isBlank() || selectedPermMode == "INTERACTIVE") attr("selected", "") }) { Text("Interactive") }
                Option("ACCEPT_EDITS", attrs = { if (selectedPermMode == "ACCEPT_EDITS") attr("selected", "") }) { Text("Accept Edits") }
                Option("BYPASS", attrs = { if (selectedPermMode == "BYPASS") attr("selected", "") }) { Text("Bypass") }
            }

            // 工作目录
            Span({
                style {
                    fontSize(12.px); color(Color(SilkColors.textSecondary))
                    property("display", "block"); marginBottom(4.px)
                }
            }) { Text("工作目录") }
            Div({
                style {
                    display(DisplayStyle.Flex); property("gap", "8px"); marginBottom(16.px)
                }
            }) {
                Input(InputType.Text) {
                    value(editDir)
                    onInput { editDir = it.value }
                    attr("placeholder", "工作目录路径")
                    style {
                        property("flex", "1"); height(40.px); borderRadius(6.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(8.px); fontSize(13.px)
                        fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                        property("box-sizing", "border-box")
                    }
                }
                Button({
                    style {
                        backgroundColor(Color("transparent"))
                        color(Color(SilkColors.primary))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(6.px); padding(6.px, 10.px)
                        property("cursor", "pointer"); fontSize(13.px)
                        property("white-space", "nowrap")
                    }
                    onClick { showFolderPicker = true }
                }) { Text("\uD83D\uDCC2 选择…") }
            }

            // 错误提示
            errorMsg?.let { msg ->
                Div({
                    style { fontSize(12.px); color(Color(SilkColors.error)); marginBottom(12.px) }
                }) { Text(msg) }
            }

            // 按钮行
            Div({
                style {
                    display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd)
                    property("gap", "8px")
                }
            }) {
                Button({
                    if (saving) attr("disabled", "")
                    style {
                        backgroundColor(Color(SilkColors.surface))
                        color(Color(SilkColors.textSecondary))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(6.px); padding(8.px, 16.px)
                        property("cursor", "pointer")
                    }
                    onClick { onDismiss() }
                }) { Text("取消") }
                Button({
                    if (saving) attr("disabled", "")
                    style {
                        backgroundColor(Color(SilkColors.primary))
                        color(Color.white); border(0.px)
                        borderRadius(6.px); padding(8.px, 16.px)
                        property("cursor", if (saving) "wait" else "pointer")
                    }
                    onClick { scope.launch { applyChanges() } }
                }) { Text(if (saving) "保存中…" else "保存") }
            }
        }
    }

    // Folder Picker 子弹窗
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

    // 信任确认子弹窗
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

/**
 * 目录选择对话框：
 * - 面包屑：各段可点击直接跳到该层
 * - 列表：仅显示子目录；单击进入
 * - .. 返回上一级
 * - 底部：显示当前路径（只读展示），支持手动输入跳转
 * - 确认按钮：以当前展示的路径作为结果
 */
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun FolderPickerDialog(
    userId: String,
    workspaceId: String? = null,
    zIndex: Int = 2000,
    initialPath: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<DirListingResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var manualInput by remember { mutableStateOf("") }
    // 当前正在进行的加载 Job；新请求发起前取消旧的，避免老响应覆盖新 state
    var loadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // 发起一次目录加载：自动取消上一次未完成的 load，保证 state 始终由最新请求写入
    fun requestLoad(path: String?) {
        loadJob?.cancel()
        loadJob = scope.launch {
            loading = true
            errorMsg = null
            val resp = ApiClient.listCcDir(userId, path, workspaceId = workspaceId)
            // 到这里说明本协程未被 cancel（否则 listCcDir 内部会重新抛 CancellationException）
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
    // 弹窗消失时清理未完成的请求
    DisposableEffect(Unit) {
        onDispose { loadJob?.cancel() }
    }

    ModalOverlay(onDismiss = onDismiss, zIndex = zIndex) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                width(620.px)
                property("max-width", "90vw")
                property("max-height", "80vh")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.18)")
                property("overflow", "hidden")
            }
        }) {
            // 标题
            Div({
                style {
                    padding(16.px, 20.px)
                    property("border-bottom", "1px solid ${SilkColors.border}")
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    alignItems(AlignItems.Center)
                }
            }) {
                Span({
                    style {
                        fontSize(16.px)
                        fontWeight("600")
                        color(Color(SilkColors.textPrimary))
                    }
                }) { Text("选择工作目录") }
                Span({
                    style {
                        fontSize(18.px)
                        property("cursor", "pointer")
                        color(Color(SilkColors.textSecondary))
                    }
                    onClick { onDismiss() }
                }) { Text("×") }
            }

            // 面包屑
            val current = listing
            if (current != null) {
                Div({
                    style {
                        padding(10.px, 20.px)
                        property("border-bottom", "1px solid ${SilkColors.border}")
                        display(DisplayStyle.Flex)
                        property("flex-wrap", "wrap")
                        alignItems(AlignItems.Center)
                        property("gap", "4px")
                        fontSize(13.px)
                        fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                    }
                }) {
                    current.segments.forEachIndexed { idx, seg ->
                        val isLast = idx == current.segments.size - 1
                        Span({
                            style {
                                color(
                                    if (isLast) Color(SilkColors.textPrimary)
                                    else Color(SilkColors.primary)
                                )
                                if (!isLast) property("cursor", "pointer")
                                if (isLast) fontWeight("600")
                                padding(2.px, 6.px)
                                borderRadius(4.px)
                            }
                            if (!isLast) {
                                onClick {
                                    // 拼接 segments[0..idx] -> 路径（使用后端提供的 separator）
                                    val target = buildBreadcrumbPath(current.segments, idx, current.separator)
                                    requestLoad(target)
                                }
                            }
                        }) { Text(seg) }
                        if (!isLast) {
                            Span({
                                style {
                                    color(Color(SilkColors.textLight))
                                    padding(0.px, 2.px)
                                }
                            }) { Text("›") }
                        }
                    }
                }
            }

            // 列表区
            Div({
                style {
                    property("flex", "1")
                    property("overflow-y", "auto")
                    padding(8.px, 0.px)
                    property("min-height", "240px")
                    property("max-height", "380px")
                }
            }) {
                when {
                    loading -> Div({
                        style {
                            padding(40.px)
                            property("text-align", "center")
                            color(Color(SilkColors.textSecondary))
                        }
                    }) { Text("加载中...") }

                    errorMsg != null -> Div({
                        style {
                            padding(20.px)
                            color(Color(SilkColors.error))
                            fontSize(13.px)
                        }
                    }) { Text("⚠ $errorMsg") }

                    current != null -> {
                        // ..
                        if (current.parent != null) {
                            FolderRow(name = "..", subtle = true) {
                                requestLoad(current.parent)
                            }
                        }
                        current.entries.forEach { entry ->
                            FolderRow(name = entry.name) {
                                val nextPath = joinPath(current.path, entry.name, current.separator)
                                requestLoad(nextPath)
                            }
                        }
                        if (current.entries.isEmpty() && current.parent == null) {
                            Div({
                                style {
                                    padding(20.px)
                                    color(Color(SilkColors.textSecondary))
                                    fontSize(13.px)
                                }
                            }) { Text("此目录下无子文件夹") }
                        }
                        if (current.truncated) {
                            Div({
                                style {
                                    padding(8.px, 20.px)
                                    fontSize(12.px)
                                    color(Color(SilkColors.textLight))
                                    fontStyle("italic")
                                }
                            }) { Text("目录项过多，仅显示前 500 个") }
                        }
                    }
                }
            }

            // 底部：路径输入 + 操作
            Div({
                style {
                    padding(12.px, 20.px)
                    property("border-top", "1px solid ${SilkColors.border}")
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "8px")
                }
            }) {
                Input(InputType.Text) {
                    value(manualInput)
                    onInput { manualInput = it.value }
                    attr("placeholder", "路径，Enter 跳转")
                    onKeyDown { evt ->
                        if (evt.key == "Enter" && manualInput.isNotBlank()) {
                            evt.preventDefault()
                            requestLoad(manualInput.trim())
                        } else if (evt.key == "Escape") {
                            onDismiss()
                        }
                    }
                    style {
                        property("flex", "1")
                        height(34.px)
                        borderRadius(6.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(4.px, 10.px)
                        fontSize(13.px)
                        fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                        property("box-sizing", "border-box")
                        property("outline", "none")
                    }
                }
                Button({
                    style {
                        backgroundColor(Color(SilkColors.surface))
                        color(Color(SilkColors.textSecondary))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(6.px)
                        padding(7.px, 14.px)
                        property("cursor", "pointer")
                        fontSize(13.px)
                    }
                    onClick { onDismiss() }
                }) { Text("取消") }
                Button({
                    val enabled = current?.success == true
                    if (!enabled) attr("disabled", "")
                    style {
                        backgroundColor(
                            if (enabled) Color(SilkColors.primary)
                            else Color(SilkColors.primaryLight)
                        )
                        color(Color.white)
                        border(0.px)
                        borderRadius(6.px)
                        padding(7.px, 14.px)
                        property("cursor", if (enabled) "pointer" else "not-allowed")
                        fontSize(13.px)
                        property("font-weight", "600")
                    }
                    onClick {
                        val path = current?.path
                        if (!path.isNullOrBlank()) onConfirm(path)
                    }
                }) { Text("选择此目录") }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, subtle: Boolean = false, onClick: () -> Unit) {
    Div({
        style {
            padding(8.px, 20.px)
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "8px")
            property("cursor", "pointer")
            fontSize(13.px)
            property("user-select", "none")
        }
        onClick { onClick() }
    }) {
        Span({ style { fontSize(14.px) } }) {
            Text(if (subtle) "\uD83D\uDD19" else "\uD83D\uDCC1")
        }
        Span({
            style {
                color(
                    if (subtle) Color(SilkColors.textSecondary)
                    else Color(SilkColors.textPrimary)
                )
                fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
            }
        }) { Text(name) }
    }
}

/**
 * 全屏遮罩层 + 居中对话框骨架。点击遮罩 [onDismiss]，点击 [content] 内不冒泡。
 * z-index 通过 [zIndex] 控制（FolderPicker 比 Create dialog 高，便于嵌套打开）。
 */
@Composable
internal fun ModalOverlay(
    onDismiss: () -> Unit,
    zIndex: Int = 1000,
    content: @Composable () -> Unit,
) {
    Div({
        style {
            position(Position.Fixed)
            top(0.px); left(0.px); right(0.px); bottom(0.px)
            backgroundColor(Color("rgba(0,0,0,0.4)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", zIndex.toString())
        }
        onClick { onDismiss() }
    }) {
        // 内容容器：阻止冒泡，避免点到对话框时触发 onDismiss
        Div({
            onClick { it.stopPropagation() }
        }) {
            content()
        }
    }
}

/**
 * 按面包屑段下标拼接成路径，使用后端提供的 [separator]。
 * - Unix: segments[0] == "/"，separator == "/" → "/" + 中段以 "/" 拼
 * - Windows: segments[0] == "C:\\"（已带分隔符），separator == "\\" → head + 中段以 "\\" 拼
 */
private fun buildBreadcrumbPath(segments: List<String>, upToIndex: Int, separator: String): String {
    if (segments.isEmpty() || upToIndex < 0) return separator
    val head = segments[0]
    if (upToIndex == 0) return head
    val tail = segments.subList(1, upToIndex + 1)
    // head 已包含或就是分隔符（Unix 的 "/", Windows 的 "C:\"）；
    // 若 head 自身以 separator 结尾就直接拼，否则补一个 separator
    val joined = tail.joinToString(separator)
    return if (head.endsWith(separator)) head + joined else head + separator + joined
}

/**
 * 拼接子目录路径，使用后端提供的 [separator]。
 */
private fun joinPath(parent: String, child: String, separator: String): String {
    if (parent.isEmpty()) return child
    return if (parent.endsWith(separator)) parent + child else parent + separator + child
}

/**
 * 信任目录确认弹窗（Silk 风格，替代浏览器原生 confirm）。
 * 使用 ModalOverlay 遮罩层 + 自定义样式按钮，与整体 UI 一致。
 */
@Composable
internal fun TrustConfirmDialog(
    path: String,
    bridgeId: String?,
    onDismiss: () -> Unit,
    onTrust: () -> Unit,
) {
    val bridgeLabel = bridgeId ?: "未知机器"
    ModalOverlay(onDismiss = onDismiss, zIndex = 3000) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                padding(24.px)
                width(420.px)
                property("max-width", "90vw")
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
            }
        }) {
            H3({ style { marginTop(0.px); color(Color(SilkColors.textPrimary)) } }) { Text("⚠️  信任目录确认") }
            Div({
                style {
                    marginTop(16.px)
                    marginBottom(24.px)
                    fontSize(14.px)
                    color(Color(SilkColors.textPrimary))
                    property("line-height", "1.6")
                }
            }) {
                Text("您选择了工作目录：")
                Div({
                    style {
                        fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                        marginTop(4.px)
                        marginBottom(12.px)
                        color(Color(SilkColors.textSecondary))
                        fontSize(13.px)
                    }
                }) { Text(path) }
                Text("Bridge 机器：$bridgeLabel")
                Div({ style { marginTop(12.px) } }) {
                    Text("是否信任并授权该目录及其子目录的读写执行权限？")
                }
                Div({
                    style {
                        marginTop(4.px)
                        fontSize(12.px)
                        color(Color(SilkColors.textLight))
                    }
                }) {
                    Text("信任后，下次在该机器上选择此目录或其子目录时将不再询问。")
                }
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.FlexEnd)
                    property("gap", "8px")
                }
            }) {
                Button({
                    style {
                        backgroundColor(Color(SilkColors.surface))
                        color(Color(SilkColors.textSecondary))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(6.px)
                        padding(8.px, 16.px)
                        property("cursor", "pointer")
                    }
                    onClick { onDismiss() }
                }) { Text("取消") }
                Button({
                    style {
                        backgroundColor(Color(SilkColors.primary))
                        color(Color.white)
                        border(0.px)
                        borderRadius(6.px)
                        padding(8.px, 16.px)
                        property("cursor", "pointer")
                    }
                    onClick { onTrust() }
                }) { Text("信任并授权") }
            }
        }
    }
}
