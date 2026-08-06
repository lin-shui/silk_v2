package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.silk.shared.models.CreateRoomRequest
import com.silk.shared.models.RoomKind
import com.silk.shared.models.RoomSummaryDto
import com.silk.shared.models.sortedByLatestActivity
import com.silk.web.workspace.WorkspaceApiException
import com.silk.web.workspace.WorkspaceDto
import com.silk.web.workspace.fetchWorkspaces
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.delay
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
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginLeft
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.maxHeight
import org.jetbrains.compose.web.css.minHeight
import org.jetbrains.compose.web.css.minWidth
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.position
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea

private enum class RoomFilter(val label: String) {
    ALL("全部"),
    WORKFLOW("工作群组"),
    CHAT("聊天群组"),
}

private enum class AddConversationMode {
    CREATE,
    JOIN,
}

private const val TEAM_STREAM_ID = "team"

internal enum class RoomMenuAction(val label: String, val destructive: Boolean = false) {
    INVITE("邀请成员"),
    RENAME("重命名"),
    LEAVE("退出群组", destructive = true),
    DELETE("删除群组", destructive = true),
}

internal fun availableRoomMenuActions(room: RoomSummaryDto): List<RoomMenuAction> = when {
    room.roomKind == RoomKind.SILK_PRIVATE -> emptyList()
    room.role == "OWNER" -> listOf(RoomMenuAction.INVITE, RoomMenuAction.RENAME, RoomMenuAction.DELETE)
    else -> listOf(RoomMenuAction.INVITE, RoomMenuAction.LEAVE)
}

private data class RoomMenuPopup(
    val room: RoomSummaryDto,
    val top: Double,
    val left: Double,
)

private data class PendingRoomAction(
    val room: RoomSummaryDto,
    val action: RoomMenuAction,
)

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
fun ConversationScene(appState: WebAppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()
    var rooms by remember(user.id) { mutableStateOf<List<RoomSummaryDto>>(emptyList()) }
    var isLoading by remember(user.id) { mutableStateOf(true) }
    var loadError by remember(user.id) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(RoomFilter.ALL) }
    var showAddDialog by remember { mutableStateOf(false) }
    var invitationRoom by remember(user.id) { mutableStateOf<RoomSummaryDto?>(null) }
    var pendingRoomAction by remember(user.id) { mutableStateOf<PendingRoomAction?>(null) }
    var refreshVersion by remember { mutableStateOf(0) }
    var sidebarCollapsed by remember {
        mutableStateOf(
            LayoutPrefs.getBool(
                "silk_room_list_collapsed",
                LayoutPrefs.getBool(
                    "silk_wf_list_collapsed",
                    LayoutPrefs.getBool("silk_chat_list_collapsed", false),
                ),
            )
        )
    }
    val selectedRoom = rooms.firstOrNull { it.roomId == appState.selectedGroup?.id }
    var expandedWorkflowRoomIds by remember(user.id) { mutableStateOf<Set<String>>(emptySet()) }
    var workspaceCreateRequestVersion by remember(selectedRoom?.roomId) { mutableStateOf(0) }
    var selectedWorkspaceId by remember(selectedRoom?.roomId) {
        mutableStateOf(selectedRoom?.roomId?.let(::loadLastWorkflowStream) ?: TEAM_STREAM_ID)
    }
    var workspaces by remember(selectedRoom?.roomId) { mutableStateOf<List<WorkspaceDto>>(emptyList()) }
    var workspaceError by remember(selectedRoom?.roomId) { mutableStateOf<String?>(null) }

    suspend fun refreshRooms() {
        try {
            rooms = ApiClient.getVisibleRooms()
            loadError = null
        } catch (e: Exception) {
            console.error("加载统一会话列表失败:", e)
            if (rooms.isEmpty()) loadError = "会话列表加载失败，请稍后重试"
        }
    }

    fun recordRoomActivity(roomId: String, timestamp: Long) {
        if (timestamp <= 0L) return
        rooms = rooms.withRoomActivity(roomId, timestamp)
    }

    LaunchedEffect(user.id, refreshVersion) {
        isLoading = true
        refreshRooms()
        isLoading = false
        while (true) {
            delay(15_000)
            refreshRooms()
        }
    }

    val workflowTarget = appState.roomNavigationTarget?.takeIf { it.legacyWorkflowId != null }
    LaunchedEffect(workflowTarget?.requestId, rooms) {
        val target = workflowTarget ?: return@LaunchedEffect
        val targetRoom = rooms.firstOrNull { it.workflowId == target.legacyWorkflowId } ?: return@LaunchedEffect
        if (appState.selectedGroup?.id != targetRoom.roomId) {
            appState.selectGroup(targetRoom.toGroup())
        }
    }

    LaunchedEffect(selectedRoom?.roomId, selectedRoom?.roomKind) {
        val room = selectedRoom?.takeIf { it.roomKind == RoomKind.WORKFLOW } ?: run {
            workspaces = emptyList()
            workspaceError = null
            return@LaunchedEffect
        }
        val token = JwtManager.getAccessToken() ?: return@LaunchedEffect
        while (true) {
            try {
                workspaces = fetchWorkspaces(room.roomId, token)
                workspaceError = null
                if (selectedWorkspaceId != TEAM_STREAM_ID && workspaces.none { it.workspaceId == selectedWorkspaceId }) {
                    selectedWorkspaceId = TEAM_STREAM_ID
                    saveLastWorkflowStream(room.roomId, TEAM_STREAM_ID)
                }
            } catch (e: WorkspaceApiException) {
                workspaceError = e.message
            } catch (e: Exception) {
                workspaceError = "工作区加载失败"
                console.error("加载统一会话工作区失败:", e)
            }
            delay(5_000)
        }
    }

    LaunchedEffect(selectedRoom?.roomId, selectedRoom?.roomKind) {
        val room = selectedRoom?.takeIf { it.roomKind == RoomKind.WORKFLOW } ?: return@LaunchedEffect
        expandedWorkflowRoomIds = if (loadWorkflowRoomExpanded(room.roomId)) {
            expandedWorkflowRoomIds + room.roomId
        } else {
            expandedWorkflowRoomIds - room.roomId
        }
    }

    if (appState.currentScene == Scene.CONTACTS) {
        ContactsScene(appState)
        return
    }

    Div({
        attr("class", "silk-conversation-scene")
        style {
            display(DisplayStyle.Flex)
            width(100.percent)
            height(100.percent)
            property("overflow", "hidden")
            property("background", SilkColors.backgroundGradient)
        }
    }) {
        RoomListPanel(
            rooms = rooms,
            selectedRoom = selectedRoom,
            isLoading = isLoading,
            loadError = loadError,
            query = query,
            filter = filter,
            workspaces = workspaces,
            workspaceError = workspaceError,
            selectedWorkspaceId = selectedWorkspaceId,
            expandedWorkflowRoomIds = expandedWorkflowRoomIds,
            isCollapsed = sidebarCollapsed,
            onQueryChange = { query = it },
            onFilterChange = { filter = it },
            onAdd = { showAddDialog = true },
            onContacts = { appState.navigateTo(Scene.CONTACTS) },
            onSelectRoom = { room ->
                scope.launch {
                    if (selectedRoom?.roomId == room.roomId && room.roomKind == RoomKind.WORKFLOW) {
                        val expanded = room.roomId !in expandedWorkflowRoomIds
                        expandedWorkflowRoomIds = if (expanded) {
                            expandedWorkflowRoomIds + room.roomId
                        } else {
                            expandedWorkflowRoomIds - room.roomId
                        }
                        saveWorkflowRoomExpanded(room.roomId, expanded)
                        return@launch
                    }
                    if (room.roomKind == RoomKind.WORKFLOW) {
                        expandedWorkflowRoomIds = if (loadWorkflowRoomExpanded(room.roomId)) {
                            expandedWorkflowRoomIds + room.roomId
                        } else {
                            expandedWorkflowRoomIds - room.roomId
                        }
                    }
                    ApiClient.markGroupAsRead(user.id, room.roomId)
                    rooms = rooms.map { if (it.roomId == room.roomId) it.copy(unreadCount = 0) else it }
                    appState.selectGroup(room.toGroup())
                }
            },
            onSelectWorkspace = { workspaceId ->
                val roomId = selectedRoom?.roomId ?: return@RoomListPanel
                selectedWorkspaceId = workspaceId
                saveLastWorkflowStream(roomId, workspaceId)
            },
            onCreateWorkspace = { workspaceCreateRequestVersion += 1 },
            onInvite = { invitationRoom = it },
            onManage = { room, action -> pendingRoomAction = PendingRoomAction(room, action) },
            onCollapsedChange = { collapsed ->
                sidebarCollapsed = collapsed
                LayoutPrefs.setBool("silk_room_list_collapsed", collapsed)
            },
        )

        Div({
            attr("class", "silk-conversation-detail")
            style {
                property("flex", "1")
                minWidth(0.px)
                height(100.percent)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("overflow", "hidden")
            }
        }) {
            if (selectedRoom != null) {
                Div({
                    attr("class", "silk-conversation-mobile-back")
                    style {
                        padding(8.px, 12.px)
                        property("border-bottom", "1px solid ${SilkColors.border}")
                        backgroundColor(Color(SilkColors.surfaceElevated))
                    }
                }) {
                    Button({
                        attr("title", "返回会话列表")
                        style {
                            border(0.px)
                            backgroundColor(Color("transparent"))
                            color(Color(SilkColors.textPrimary))
                            fontSize(15.px)
                            property("cursor", "pointer")
                        }
                        onClick { appState.clearRoomSelection() }
                    }) { Text("‹  会话") }
                }
            }
            Div({
                attr("class", "silk-conversation-detail-content")
                style {
                    property("flex", "1")
                    minHeight(0.px)
                    minWidth(0.px)
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("overflow", "hidden")
                }
            }) {
                when {
                    selectedRoom == null -> ConversationPlaceholder()
                    selectedRoom.roomKind == RoomKind.WORKFLOW -> WorkflowRoomView(
                        appState = appState,
                        room = selectedRoom,
                        selectedWorkspaceId = selectedWorkspaceId,
                        workspaceCreateRequestVersion = workspaceCreateRequestVersion,
                        onWorkspaceSelected = { workspaceId ->
                            selectedWorkspaceId = workspaceId
                            saveLastWorkflowStream(selectedRoom.roomId, workspaceId)
                        },
                        onWorkspaceCreated = { created ->
                            workspaces = workspaces.filterNot { it.workspaceId == created.workspaceId } + created
                        },
                        onWorkspaceUpdated = { updated ->
                            workspaces = workspaces.map { if (it.workspaceId == updated.workspaceId) updated else it }
                        },
                        onWorkspaceDeleted = { deletedId ->
                            workspaces = workspaces.filterNot { it.workspaceId == deletedId }
                        },
                        onRoomActivity = { timestamp -> recordRoomActivity(selectedRoom.roomId, timestamp) },
                    )
                    else -> ChatRoomView(
                        appState = appState,
                        group = selectedRoom.toGroup(),
                        onRoomActivity = { timestamp -> recordRoomActivity(selectedRoom.roomId, timestamp) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddConversationDialog(
            userId = user.id,
            onDismiss = { showAddDialog = false },
            onRoomCreated = { response ->
                rooms = (rooms.filterNot { it.roomId == response.room.roomId } + response.room)
                    .sortedByLatestActivity()
                appState.selectGroup(response.room.toGroup())
            },
            onGroupJoined = {
                showAddDialog = false
                refreshVersion += 1
                appState.selectGroup(it)
            },
        )
    }
    invitationRoom?.let { room ->
        InvitationDialog(
            group = room.toGroup(),
            strings = com.silk.shared.i18n.getStrings(com.silk.shared.models.Language.CHINESE),
            onDismiss = { invitationRoom = null },
        )
    }
    pendingRoomAction?.let { pending ->
        RoomManagementDialog(
            room = pending.room,
            action = pending.action,
            onDismiss = { pendingRoomAction = null },
            onRenamed = { updatedRoom ->
                rooms = rooms.map { room -> if (room.roomId == updatedRoom.roomId) updatedRoom else room }
                if (appState.selectedGroup?.id == updatedRoom.roomId) {
                    appState.selectGroup(updatedRoom.toGroup())
                }
                pendingRoomAction = null
            },
            onRemoved = { roomId ->
                rooms = rooms.filterNot { it.roomId == roomId }
                expandedWorkflowRoomIds = expandedWorkflowRoomIds - roomId
                if (appState.selectedGroup?.id == roomId) appState.clearRoomSelection()
                pendingRoomAction = null
            },
        )
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun RoomListPanel(
    rooms: List<RoomSummaryDto>,
    selectedRoom: RoomSummaryDto?,
    isLoading: Boolean,
    loadError: String?,
    query: String,
    filter: RoomFilter,
    workspaces: List<WorkspaceDto>,
    workspaceError: String?,
    selectedWorkspaceId: String,
    expandedWorkflowRoomIds: Set<String>,
    isCollapsed: Boolean,
    onQueryChange: (String) -> Unit,
    onFilterChange: (RoomFilter) -> Unit,
    onAdd: () -> Unit,
    onContacts: () -> Unit,
    onSelectRoom: (RoomSummaryDto) -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onCreateWorkspace: () -> Unit,
    onInvite: (RoomSummaryDto) -> Unit,
    onManage: (RoomSummaryDto, RoomMenuAction) -> Unit,
    onCollapsedChange: (Boolean) -> Unit,
) {
    var menuPopup by remember { mutableStateOf<RoomMenuPopup?>(null) }
    val visibleRooms = rooms.filter { room ->
        if (room.roomKind == RoomKind.SILK_PRIVATE) return@filter true
        val matchesFilter = when (filter) {
            RoomFilter.ALL -> true
            RoomFilter.WORKFLOW -> room.roomKind == RoomKind.WORKFLOW
            RoomFilter.CHAT -> room.roomKind != RoomKind.WORKFLOW
        }
        val matchesQuery = query.isBlank() || room.name.contains(query.trim(), ignoreCase = true) ||
            room.ownerDisplayName.contains(query.trim(), ignoreCase = true)
        matchesFilter && matchesQuery
    }
    Div({
        attr(
            "class",
            buildString {
                append("silk-conversation-sidebar")
                if (selectedRoom != null) append(" has-selection")
                if (isCollapsed) append(" is-collapsed")
            },
        )
        style {
            width(320.px)
            property("flex-shrink", "0")
            height(100.percent)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            backgroundColor(Color(SilkColors.surface))
            property("border-right", "1px solid ${SilkColors.border}")
            property("overflow", "hidden")
        }
    }) {
        Div({ attr("class", "silk-conversation-sidebar-reopen") }) {
            ReopenBar { onCollapsedChange(false) }
        }
        Div({ attr("class", "silk-conversation-sidebar-content") }) {
        Div({
            style {
                padding(14.px, 14.px, 10.px, 14.px)
                property("border-bottom", "1px solid ${SilkColors.border}")
            }
        }) {
            Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); justifyContent(JustifyContent.SpaceBetween) } }) {
                Span({ style { fontSize(18.px); fontWeight("700"); color(Color(SilkColors.textPrimary)) } }) { Text("会话") }
                Div({ style { display(DisplayStyle.Flex); property("gap", "6px") } }) {
                    RoomHeaderIcon(
                        title = "收起会话列表",
                        label = "‹",
                        onClick = { onCollapsedChange(true) },
                        cssClass = "silk-conversation-collapse-control",
                    )
                    RoomHeaderIcon("联系人", "◎", onContacts)
                    RoomHeaderIcon("添加会话", "+", onAdd, primary = true)
                }
            }
            Input(InputType.Text) {
                value(query)
                attr("placeholder", "搜索会话")
                style {
                    width(100.percent)
                    property("box-sizing", "border-box")
                    marginTop(12.px)
                    padding(8.px, 10.px)
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    borderRadius(6.px)
                    backgroundColor(Color.white)
                    color(Color(SilkColors.textPrimary))
                }
                onInput { onQueryChange(it.value) }
            }
            Div({ style { display(DisplayStyle.Flex); marginTop(8.px); property("gap", "4px") } }) {
                RoomFilter.entries.forEach { item ->
                    Button({
                        style {
                            property("flex", "1")
                            padding(6.px, 4.px)
                            borderRadius(5.px)
                            border(1.px, LineStyle.Solid, Color(if (filter == item) SilkColors.primary else SilkColors.border))
                            backgroundColor(Color(if (filter == item) "rgba(201, 168, 108, 0.16)" else "transparent"))
                            color(Color(if (filter == item) SilkColors.primaryDark else SilkColors.textSecondary))
                            fontSize(11.px)
                            property("cursor", "pointer")
                        }
                        onClick { onFilterChange(item) }
                    }) { Text(item.label) }
                }
            }
        }
        Div({ style { property("flex", "1"); property("overflow-y", "auto") } }) {
            when {
                isLoading -> RoomListNotice("加载中...")
                loadError != null -> RoomListNotice(loadError, isError = true)
                visibleRooms.isEmpty() -> RoomListNotice(if (query.isBlank()) "暂无会话" else "没有匹配的会话")
                else -> orderRoomsForNavigation(visibleRooms).forEach { room ->
                    key(room.roomId) {
                        val isSelected = selectedRoom?.roomId == room.roomId
                        val isWorkflowExpanded = isSelected && room.roomId in expandedWorkflowRoomIds
                        RoomListItem(
                            room = room,
                            selected = isSelected,
                            onClick = { onSelectRoom(room) },
                            onMenuClick = { anchorRight, anchorBottom ->
                                val menuHeight = availableRoomMenuActions(room).size * 38.0 + 12.0
                                menuPopup = RoomMenuPopup(
                                    room = room,
                                    top = minOf(
                                        anchorBottom + 4.0,
                                        window.innerHeight.toDouble() - menuHeight - 8.0,
                                    ).coerceAtLeast(8.0),
                                    left = (anchorRight - 190.0).coerceAtLeast(8.0),
                                )
                            },
                        )
                        if (isWorkflowExpanded && room.roomKind == RoomKind.WORKFLOW) {
                            WorkflowWorkspaceTree(
                                roomId = room.roomId,
                                workspaces = workspaces,
                                loadError = workspaceError,
                                selectedWorkspaceId = selectedWorkspaceId,
                                onSelect = onSelectWorkspace,
                                onCreate = onCreateWorkspace,
                            )
                        }
                    }
                }
            }
        }
        }
    }
    menuPopup?.let { popup ->
        RoomActionsPopup(
            popup = popup,
            onDismiss = { menuPopup = null },
            onAction = { action ->
                menuPopup = null
                if (action == RoomMenuAction.INVITE) {
                    onInvite(popup.room)
                } else {
                    onManage(popup.room, action)
                }
            },
        )
    }
}

@Composable
private fun RoomHeaderIcon(
    title: String,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    cssClass: String = "",
) {
    Button({
        if (cssClass.isNotBlank()) attr("class", cssClass)
        attr("title", title)
        style {
            width(30.px); height(30.px); padding(0.px)
            borderRadius(5.px)
            border(1.px, LineStyle.Solid, Color(if (primary) SilkColors.primary else SilkColors.border))
            backgroundColor(Color(if (primary) SilkColors.primary else SilkColors.surfaceElevated))
            color(Color(if (primary) "#FFFFFF" else SilkColors.textSecondary))
            fontSize(17.px)
            property("cursor", "pointer")
        }
        onClick { onClick() }
    }) { Text(label) }
}

@Composable
private fun RoomListNotice(text: String, isError: Boolean = false) {
    Div({ style { padding(24.px, 16.px); property("text-align", "center"); color(Color(if (isError) SilkColors.error else SilkColors.textSecondary)); fontSize(13.px) } }) {
        Text(text)
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun RoomListItem(
    room: RoomSummaryDto,
    selected: Boolean,
    onClick: () -> Unit,
    onMenuClick: (anchorRight: Double, anchorBottom: Double) -> Unit,
) {
    Div({
        style {
            padding(11.px, 14.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            property("border-left", if (selected) "3px solid ${SilkColors.primary}" else "3px solid transparent")
            backgroundColor(Color(if (selected) "rgba(201, 168, 108, 0.13)" else "transparent"))
            property("cursor", "pointer")
        }
        onClick { onClick() }
    }) {
        Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); property("gap", "8px") } }) {
            Span({
                style {
                    property("flex", "1")
                    minWidth(0.px)
                    fontSize(14.px)
                    fontWeight(if (selected) "700" else "600")
                    color(Color(SilkColors.textPrimary))
                    property("overflow", "hidden")
                    property("text-overflow", "ellipsis")
                    property("white-space", "nowrap")
                }
            }) { Text(room.name) }
            val badge = when (room.roomKind) {
                RoomKind.WORKFLOW -> "工作"
                RoomKind.SILK_PRIVATE -> "AI"
                RoomKind.CHAT -> room.integration?.agentType?.takeIf { it.isNotBlank() } ?: "聊天"
            }
            Span({
                style {
                    fontSize(9.px)
                    padding(2.px, 5.px)
                    borderRadius(3.px)
                    backgroundColor(Color(if (room.roomKind == RoomKind.WORKFLOW) "#E8F0FE" else "#F2F2F2"))
                    color(Color(if (room.roomKind == RoomKind.WORKFLOW) "#285EA8" else SilkColors.textSecondary))
                    property("flex-shrink", "0")
                }
            }) { Text(badge) }
            if (room.unreadCount > 0) {
                Span({
                    style {
                        minWidth(18.px); height(18.px); padding(0.px, 4.px)
                        borderRadius(9.px); backgroundColor(Color("#D8483E")); color(Color.white)
                        display(DisplayStyle.Flex); alignItems(AlignItems.Center); justifyContent(JustifyContent.Center)
                        fontSize(10.px); property("flex-shrink", "0")
                    }
                }) { Text(if (room.unreadCount > 99) "99+" else room.unreadCount.toString()) }
            }
            if (availableRoomMenuActions(room).isNotEmpty()) {
                Button({
                    attr("title", "群组操作")
                    style {
                        width(26.px); height(26.px); padding(0.px)
                        border(0.px); borderRadius(4.px)
                        backgroundColor(Color("transparent")); color(Color(SilkColors.textSecondary))
                        fontSize(18.px); property("cursor", "pointer"); property("flex-shrink", "0")
                    }
                    onClick { event ->
                        event.stopPropagation()
                        val rect = event.nativeEvent.asDynamic().currentTarget.getBoundingClientRect()
                        onMenuClick(rect.right as Double, rect.bottom as Double)
                    }
                }) { Text("⋯") }
            }
        }
        Div({ style { display(DisplayStyle.Flex); marginTop(4.px); alignItems(AlignItems.Center); property("gap", "6px") } }) {
            Span({ style { color(Color(SilkColors.textSecondary)); fontSize(11.px) } }) {
                Text(if (room.roomKind == RoomKind.WORKFLOW) room.ownerDisplayName else room.role)
            }
            room.integration?.let { integration ->
                Span({ style { color(Color(if (integration.connected) "#2E7D32" else "#B26A00")); fontSize(10.px) } }) {
                    Text(if (integration.connected) "● cc-connect" else "○ cc-connect")
                }
            }
        }
    }
}

@Composable
private fun RoomActionsPopup(
    popup: RoomMenuPopup,
    onDismiss: () -> Unit,
    onAction: (RoomMenuAction) -> Unit,
) {
    DisposableEffect(onDismiss) {
        val keyHandler: (org.w3c.dom.events.Event) -> Unit = { event ->
            if (event.asDynamic().key == "Escape") onDismiss()
        }
        window.addEventListener("keydown", keyHandler)
        onDispose { window.removeEventListener("keydown", keyHandler) }
    }
    Div({
        style {
            position(Position.Fixed); property("inset", "0"); property("z-index", "1190")
        }
        onClick { onDismiss() }
    }) {
        Div({
            style {
                position(Position.Fixed)
                property("top", "${popup.top}px"); property("left", "${popup.left}px")
                width(190.px); padding(6.px)
                backgroundColor(Color.white); borderRadius(6.px)
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                property("box-shadow", "0 10px 30px rgba(0,0,0,0.18)")
            }
            onClick { it.stopPropagation() }
        }) {
            availableRoomMenuActions(popup.room).forEach { action ->
                Button({
                    style {
                        width(100.percent); padding(9.px, 10.px)
                        border(0.px); borderRadius(4.px)
                        backgroundColor(Color("transparent"))
                        color(Color(if (action.destructive) SilkColors.error else SilkColors.textPrimary))
                        fontSize(13.px); property("text-align", "left"); property("cursor", "pointer")
                    }
                    onClick { onAction(action) }
                }) { Text(action.label) }
            }
        }
    }
}

@Composable
private fun WorkflowWorkspaceTree(
    roomId: String,
    workspaces: List<WorkspaceDto>,
    loadError: String?,
    selectedWorkspaceId: String,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
) {
    var expanded by remember(roomId) { mutableStateOf(loadExpandedWorkspaceSections(roomId)) }
    val active = workspaces.filter { it.lifecycleState == "ACTIVE" && !it.historyOnly }
    val mine = active.filter { it.ownerId == JwtManager.getStoredUser()?.id }
    val copilot = active.filter { it.role == "COPILOT" }
    val memberGroups = active.filter { it.ownerId != JwtManager.getStoredUser()?.id && it.role in setOf("OBSERVER", "COPILOT") }
        .groupBy { it.ownerId }
        .map { (ownerId, items) -> Triple(ownerId, items.firstOrNull()?.ownerDisplayName.orEmpty().ifBlank { "Room member" }, items) }
        .sortedBy { it.second }
    val historical = workspaces.filter { it.historyOnly || it.lifecycleState == "REVOKED" }
    val archived = workspaces.filter { it.lifecycleState == "ARCHIVED" }

    fun toggle(section: String) {
        expanded = if (section in expanded) expanded - section else expanded + section
        saveExpandedWorkspaceSections(roomId, expanded)
    }

    Div({ attr("class", "silk-workspace-tree-desktop"); style { padding(4.px, 10.px, 10.px, 22.px); backgroundColor(Color("rgba(0,0,0,0.018)")) } }) {
        WorkspaceTreeItem("▣  Team Channel", selectedWorkspaceId == TEAM_STREAM_ID) { onSelect(TEAM_STREAM_ID) }
        WorkspaceTreeSection(
            label = "我的工作区",
            sectionId = "mine",
            items = mine,
            expanded = expanded,
            onToggle = ::toggle,
            selectedWorkspaceId = selectedWorkspaceId,
            onSelect = onSelect,
            alwaysVisible = true,
            actionTitle = "新建工作区",
            onAction = onCreate,
        )
        WorkspaceTreeSection("Co-pilot", "copilot", copilot, expanded, ::toggle, selectedWorkspaceId, onSelect)
        memberGroups.forEach { (ownerId, ownerName, items) ->
            WorkspaceTreeSection(ownerName, "owner:$ownerId", items, expanded, ::toggle, selectedWorkspaceId, onSelect)
        }
        WorkspaceTreeSection("历史共享", "history", historical, expanded, ::toggle, selectedWorkspaceId, onSelect)
        WorkspaceTreeSection("已归档", "archived", archived, expanded, ::toggle, selectedWorkspaceId, onSelect)
        if (loadError != null) {
            Div({ style { padding(6.px); color(Color(SilkColors.error)); fontSize(11.px) } }) { Text(loadError) }
        }
    }
}

@Composable
private fun WorkspaceTreeSection(
    label: String,
    sectionId: String,
    items: List<WorkspaceDto>,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    selectedWorkspaceId: String,
    onSelect: (String) -> Unit,
    alwaysVisible: Boolean = false,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
) {
    if (items.isEmpty() && !alwaysVisible) return
    val isExpanded = sectionId in expanded
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            padding(4.px, 5.px)
            color(Color(SilkColors.textSecondary))
            fontSize(11.px)
            property("user-select", "none")
        }
    }) {
        Div({
            style {
                property("flex", "1")
                padding(2.px, 0.px)
                property("cursor", if (items.isEmpty()) "default" else "pointer")
            }
            if (items.isNotEmpty()) onClick { onToggle(sectionId) }
        }) {
            val marker = if (items.isEmpty()) " " else if (isExpanded) "▾" else "▸"
            Text("$marker  $label (${items.size})")
        }
        if (onAction != null) {
            Button({
                attr("title", actionTitle ?: label)
                style {
                    width(22.px); height(22.px); padding(0.px)
                    borderRadius(4.px)
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    backgroundColor(Color(SilkColors.surfaceElevated))
                    color(Color(SilkColors.primaryDark))
                    fontSize(16.px)
                    property("cursor", "pointer")
                    property("flex-shrink", "0")
                }
                onClick { onAction() }
            }) { Text("+") }
        }
    }
    if (isExpanded) {
        items.distinctBy { it.workspaceId }.sortedByDescending { it.recentActivityAt }.forEach { workspace ->
            WorkspaceTreeItem(
                label = "${workspaceActivitySymbol(workspace.activity.state)}  ${workspace.name}",
                selected = selectedWorkspaceId == workspace.workspaceId,
            ) { onSelect(workspace.workspaceId) }
        }
    }
}

@Composable
private fun WorkspaceTreeItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Div({
        style {
            padding(6.px, 8.px)
            marginBottom(2.px)
            borderRadius(4.px)
            fontSize(12.px)
            color(Color(if (selected) SilkColors.primaryDark else SilkColors.textPrimary))
            backgroundColor(Color(if (selected) "rgba(201, 168, 108, 0.16)" else "transparent"))
            property("overflow", "hidden"); property("text-overflow", "ellipsis"); property("white-space", "nowrap")
            property("cursor", "pointer")
        }
        onClick { onClick() }
    }) { Text(label) }
}

private fun workspaceActivitySymbol(state: String): String = when (state) {
    "RUNNING" -> "●"
    "WAITING" -> "◐"
    else -> "○"
}

@Composable
private fun ConversationPlaceholder() {
    Div({
        style {
            width(100.percent); height(100.percent)
            display(DisplayStyle.Flex); flexDirection(FlexDirection.Column)
            alignItems(AlignItems.Center); justifyContent(JustifyContent.Center)
            color(Color(SilkColors.textSecondary))
        }
    }) {
        Span({ style { fontSize(30.px); marginBottom(10.px) } }) { Text("▤") }
        Span({ style { fontSize(15.px) } }) { Text("选择一个会话") }
    }
}

@Composable
private fun ChatRoomView(
    appState: WebAppState,
    group: Group,
    onRoomActivity: (Long) -> Unit,
) {
    val user = appState.currentUser ?: return
    DisposableEffect(appState) {
        val bridge: (String?, String) -> Unit = { topicId, entryId ->
            appState.openKnowledgeBaseEntry(entryId = entryId, topicId = topicId)
        }
        window.asDynamic().__silkOpenKnowledgeBaseEntry = bridge
        onDispose { window.asDynamic().__silkOpenKnowledgeBaseEntry = null }
    }
    key(group.id) {
        ChatAppWithGroup(user, group, appState, onRoomActivity)
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun RoomManagementDialog(
    room: RoomSummaryDto,
    action: RoomMenuAction,
    onDismiss: () -> Unit,
    onRenamed: (RoomSummaryDto) -> Unit,
    onRemoved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(room.roomId) { mutableStateOf(room.name) }
    var isSubmitting by remember(room.roomId, action) { mutableStateOf(false) }
    var error by remember(room.roomId, action) { mutableStateOf<String?>(null) }
    val title = when (action) {
        RoomMenuAction.RENAME -> "重命名群组"
        RoomMenuAction.LEAVE -> "退出群组"
        RoomMenuAction.DELETE -> "删除群组"
        RoomMenuAction.INVITE -> "邀请成员"
    }
    val confirmation = when (action) {
        RoomMenuAction.LEAVE -> "退出后你将无法访问“${room.name}”；你创建的工作区记录会保留。"
        RoomMenuAction.DELETE -> "将删除“${room.name}”、消息历史以及工作区配置。此操作无法撤销。"
        else -> null
    }
    DisposableEffect(isSubmitting) {
        val keyHandler: (org.w3c.dom.events.Event) -> Unit = { event ->
            if (event.asDynamic().key == "Escape" && !isSubmitting) onDismiss()
        }
        window.addEventListener("keydown", keyHandler)
        onDispose { window.removeEventListener("keydown", keyHandler) }
    }

    Div({
        style {
            position(Position.Fixed); property("inset", "0")
            backgroundColor(Color("rgba(0,0,0,0.42)"))
            display(DisplayStyle.Flex); alignItems(AlignItems.Center); justifyContent(JustifyContent.Center)
            property("z-index", "1200"); padding(16.px)
        }
        onClick { if (!isSubmitting) onDismiss() }
    }) {
        Div({
            style {
                width(400.px); property("max-width", "100%")
                backgroundColor(Color.white); borderRadius(8.px); padding(22.px)
                property("box-shadow", "0 16px 50px rgba(0,0,0,0.22)")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({
                style {
                    marginTop(0.px); marginBottom(14.px)
                    color(Color(SilkColors.textPrimary)); fontSize(18.px)
                }
            }) { Text(title) }
            if (action == RoomMenuAction.RENAME) {
                Input(InputType.Text) {
                    value(name); attr("placeholder", "群组名称")
                    style {
                        width(100.percent); property("box-sizing", "border-box")
                        padding(9.px); marginBottom(10.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border)); borderRadius(5.px)
                    }
                    onInput { name = it.value; error = null }
                }
            } else if (confirmation != null) {
                Div({
                    style {
                        color(Color(SilkColors.textSecondary)); fontSize(13.px)
                        property("line-height", "1.6"); marginBottom(12.px)
                    }
                }) { Text(confirmation) }
            }
            if (error != null) {
                Div({ style { color(Color(SilkColors.error)); fontSize(12.px); marginBottom(8.px) } }) {
                    Text(error ?: "")
                }
            }
            Div({
                style {
                    display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd)
                    property("gap", "8px"); marginTop(8.px)
                }
            }) {
                Button({ onClick { if (!isSubmitting) onDismiss() } }) { Text("取消") }
                Button({
                    style {
                        backgroundColor(
                            Color(
                                if (action.destructive) SilkColors.error else SilkColors.primary
                            )
                        )
                        color(Color.white); border(0.px); borderRadius(5.px); padding(8.px, 16.px)
                        property("cursor", if (isSubmitting) "default" else "pointer")
                    }
                    onClick {
                        if (isSubmitting) return@onClick
                        if (action == RoomMenuAction.RENAME && name.isBlank()) {
                            error = "请输入群组名称"
                            return@onClick
                        }
                        scope.launch {
                            isSubmitting = true
                            error = null
                            val response = when (action) {
                                RoomMenuAction.RENAME -> ApiClient.renameRoom(room.roomId, name.trim())
                                RoomMenuAction.LEAVE -> ApiClient.leaveRoom(room.roomId)
                                RoomMenuAction.DELETE -> ApiClient.deleteRoom(room.roomId)
                                RoomMenuAction.INVITE -> return@launch
                            }
                            isSubmitting = false
                            if (!response.success) {
                                error = response.message
                            } else if (action == RoomMenuAction.RENAME) {
                                val updatedRoom = response.room
                                if (updatedRoom == null) error = "服务端未返回更新后的群组" else onRenamed(updatedRoom)
                            } else {
                                onRemoved(room.roomId)
                            }
                        }
                    }
                }) {
                    Text(
                        when {
                            isSubmitting -> "处理中..."
                            action == RoomMenuAction.RENAME -> "保存"
                            action == RoomMenuAction.LEAVE -> "退出"
                            else -> "删除"
                        }
                    )
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
@Composable
private fun AddConversationDialog(
    userId: String,
    onDismiss: () -> Unit,
    onRoomCreated: (com.silk.shared.models.CreateRoomResponse) -> Unit,
    onGroupJoined: (Group) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(AddConversationMode.CREATE) }
    var kind by remember { mutableStateOf(RoomKind.CHAT) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var ccConnect by remember { mutableStateOf(false) }
    var invitationCode by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var createdToken by remember { mutableStateOf<String?>(null) }

    DisposableEffect(isSubmitting) {
        val keyHandler: (org.w3c.dom.events.Event) -> Unit = { event ->
            if (event.asDynamic().key == "Escape" && !isSubmitting) onDismiss()
        }
        window.addEventListener("keydown", keyHandler)
        onDispose { window.removeEventListener("keydown", keyHandler) }
    }

    Div({
        style {
            position(Position.Fixed)
            property("inset", "0")
            backgroundColor(Color("rgba(0,0,0,0.42)"))
            display(DisplayStyle.Flex); alignItems(AlignItems.Center); justifyContent(JustifyContent.Center)
            property("z-index", "1200")
            padding(16.px)
        }
        onClick { if (!isSubmitting) onDismiss() }
    }) {
        Div({
            style {
                width(420.px); property("max-width", "100%")
                backgroundColor(Color.white); borderRadius(8.px); padding(22.px)
                property("box-shadow", "0 16px 50px rgba(0,0,0,0.22)")
            }
            onClick { it.stopPropagation() }
        }) createDialogContent@ {
            H3({ style { marginTop(0.px); marginBottom(16.px); color(Color(SilkColors.textPrimary)); fontSize(18.px) } }) { Text("添加会话") }
            if (createdToken != null) {
                Div({ style { color(Color(SilkColors.textSecondary)); fontSize(13.px); marginBottom(8.px) } }) { Text("cc-connect Token") }
                Div({
                    style {
                        padding(10.px); backgroundColor(Color("#F5F5F5")); borderRadius(5.px)
                        property("word-break", "break-all"); fontSize(12.px); color(Color(SilkColors.textPrimary))
                    }
                }) { Text(createdToken ?: "") }
                Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd); marginTop(16.px) } }) {
                    Button({ onClick { onDismiss() } }) { Text("完成") }
                }
                return@createDialogContent
            }
            Div({ style { display(DisplayStyle.Flex); property("gap", "6px"); marginBottom(14.px) } }) {
                listOf(
                    AddConversationMode.CREATE to "创建群组",
                    AddConversationMode.JOIN to "通过邀请码加入",
                ).forEach { (value, label) ->
                    Button({
                        style {
                            property("flex", "1"); padding(9.px); borderRadius(5.px)
                            border(1.px, LineStyle.Solid, Color(if (mode == value) SilkColors.primary else SilkColors.border))
                            backgroundColor(Color(if (mode == value) "rgba(201, 168, 108, 0.16)" else "transparent"))
                            color(Color(SilkColors.textPrimary)); property("cursor", if (isSubmitting) "default" else "pointer")
                        }
                        onClick {
                            if (!isSubmitting) {
                                mode = value
                                error = null
                            }
                        }
                    }) { Text(label) }
                }
            }
            if (mode == AddConversationMode.JOIN) {
                Input(InputType.Text) {
                    value(invitationCode)
                    attr("placeholder", "请输入 6 位邀请码")
                    attr("maxlength", "6")
                    style {
                        width(100.percent); property("box-sizing", "border-box"); padding(11.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border)); borderRadius(5.px)
                        marginBottom(10.px); property("text-transform", "uppercase"); property("text-align", "center")
                        property("letter-spacing", "0")
                    }
                    onInput {
                        invitationCode = it.value.uppercase().take(6)
                        error = null
                    }
                }
                if (error != null) Div({ style { color(Color(SilkColors.error)); fontSize(12.px); marginBottom(8.px) } }) { Text(error ?: "") }
                Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd); property("gap", "8px"); marginTop(8.px) } }) {
                    Button({ onClick { if (!isSubmitting) onDismiss() } }) { Text("取消") }
                    Button({
                        style {
                            backgroundColor(Color(SilkColors.primary)); color(Color.white); border(0.px); borderRadius(5.px)
                            padding(8.px, 16.px); property("cursor", if (isSubmitting || invitationCode.length != 6) "default" else "pointer")
                            property("opacity", if (isSubmitting || invitationCode.length != 6) "0.6" else "1")
                        }
                        onClick {
                            if (!isSubmitting && invitationCode.length == 6) scope.launch {
                                isSubmitting = true
                                error = null
                                try {
                                    val response = ApiClient.joinGroup(userId, invitationCode)
                                    if (response.success && response.group != null) {
                                        onGroupJoined(response.group)
                                    } else {
                                        error = response.message.ifBlank { "加入失败" }
                                    }
                                } catch (exception: Exception) {
                                    error = "加入失败: ${exception.message ?: "请稍后重试"}"
                                } finally {
                                    isSubmitting = false
                                }
                            }
                        }
                    }) { Text(if (isSubmitting) "加入中..." else "加入") }
                }
                return@createDialogContent
            }
            Div({ style { display(DisplayStyle.Flex); property("gap", "6px"); marginBottom(14.px) } }) {
                listOf(RoomKind.CHAT to "聊天群组", RoomKind.WORKFLOW to "工作群组").forEach { (value, label) ->
                    Button({
                        style {
                            property("flex", "1"); padding(9.px); borderRadius(5.px)
                            border(1.px, LineStyle.Solid, Color(if (kind == value) SilkColors.primary else SilkColors.border))
                            backgroundColor(Color(if (kind == value) "rgba(201, 168, 108, 0.16)" else "transparent"))
                            color(Color(SilkColors.textPrimary)); property("cursor", "pointer")
                        }
                        onClick {
                            if (!isSubmitting) {
                                kind = value
                                if (value == RoomKind.WORKFLOW) ccConnect = false
                            }
                        }
                    }) { Text(label) }
                }
            }
            Input(InputType.Text) {
                value(name); attr("placeholder", "名称")
                style { width(100.percent); property("box-sizing", "border-box"); padding(9.px); border(1.px, LineStyle.Solid, Color(SilkColors.border)); borderRadius(5.px); marginBottom(10.px) }
                onInput { name = it.value; error = null }
            }
            if (kind == RoomKind.WORKFLOW) {
                TextArea {
                    value(description); attr("placeholder", "描述（可选）")
                    style { width(100.percent); property("box-sizing", "border-box"); minHeight(72.px); padding(9.px); border(1.px, LineStyle.Solid, Color(SilkColors.border)); borderRadius(5.px); marginBottom(10.px) }
                    onInput { description = it.value }
                }
            } else {
                Button({
                    style {
                        width(100.percent); padding(9.px); marginBottom(10.px); borderRadius(5.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border)); backgroundColor(Color("transparent"))
                        color(Color(SilkColors.textPrimary)); property("text-align", "left"); property("cursor", "pointer")
                    }
                    onClick { if (!isSubmitting) ccConnect = !ccConnect }
                }) { Text("${if (ccConnect) "☑" else "☐"}  启用 cc-connect") }
            }
            if (error != null) Div({ style { color(Color(SilkColors.error)); fontSize(12.px); marginBottom(8.px) } }) { Text(error ?: "") }
            Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd); property("gap", "8px"); marginTop(8.px) } }) {
                Button({ onClick { if (!isSubmitting) onDismiss() } }) { Text("取消") }
                Button({
                    style { backgroundColor(Color(SilkColors.primary)); color(Color.white); border(0.px); borderRadius(5.px); padding(8.px, 16.px); property("cursor", if (isSubmitting) "default" else "pointer") }
                    onClick {
                        if (name.isBlank() || isSubmitting) {
                            if (name.isBlank()) error = "请输入名称"
                        } else scope.launch {
                            isSubmitting = true
                            error = null
                            try {
                                val response = ApiClient.createRoom(
                                    CreateRoomRequest(
                                        name = name.trim(),
                                        roomKind = kind,
                                        description = description.trim(),
                                        integrationType = if (ccConnect) "ccconnect" else null,
                                    )
                                )
                                if (response == null) {
                                    error = "创建失败，请检查名称后重试"
                                } else {
                                    onRoomCreated(response)
                                    if (response.ccConnectToken != null) createdToken = response.ccConnectToken else onDismiss()
                                }
                            } catch (exception: Exception) {
                                error = "创建失败: ${exception.message ?: "请稍后重试"}"
                            } finally {
                                isSubmitting = false
                            }
                        }
                    }
                }) { Text(if (isSubmitting) "创建中..." else "创建") }
            }
        }
    }
}

private fun RoomSummaryDto.toGroup(): Group = Group(
    id = roomId,
    name = name,
    invitationCode = invitationCode,
    hostId = ownerId,
    hostName = ownerDisplayName,
    createdAt = createdAt,
    roomKind = roomKind,
)

internal fun orderRoomsForNavigation(rooms: Iterable<RoomSummaryDto>): List<RoomSummaryDto> {
    val grouped = rooms.groupBy { it.roomKind == RoomKind.SILK_PRIVATE }
    return grouped[true].orEmpty().sortedByLatestActivity() + grouped[false].orEmpty().sortedByLatestActivity()
}

internal fun Iterable<RoomSummaryDto>.withRoomActivity(roomId: String, timestamp: Long): List<RoomSummaryDto> =
    map { room ->
        if (room.roomId == roomId && timestamp > room.lastMessageAt) {
            room.copy(lastMessageAt = timestamp)
        } else {
            room
        }
    }

private fun workflowStreamKey(roomId: String): String = "silk_room_stream_$roomId"
private fun workflowTreeExpandedKey(roomId: String): String = "silk_room_tree_expanded_$roomId"
private fun workspaceSectionsKey(roomId: String): String = "silk_room_workspace_sections_$roomId"

private fun loadLastWorkflowStream(roomId: String): String = runCatching {
    localStorage.getItem(workflowStreamKey(roomId)).orEmpty().ifBlank { TEAM_STREAM_ID }
}.getOrDefault(TEAM_STREAM_ID)

private fun saveLastWorkflowStream(roomId: String, streamId: String) {
    runCatching { localStorage.setItem(workflowStreamKey(roomId), streamId) }
}

internal fun parseStoredWorkflowRoomExpanded(raw: String?): Boolean = raw?.toBooleanStrictOrNull() ?: true

private fun loadWorkflowRoomExpanded(roomId: String): Boolean = runCatching {
    parseStoredWorkflowRoomExpanded(localStorage.getItem(workflowTreeExpandedKey(roomId)))
}.getOrDefault(true)

private fun saveWorkflowRoomExpanded(roomId: String, expanded: Boolean) {
    runCatching { localStorage.setItem(workflowTreeExpandedKey(roomId), expanded.toString()) }
}

private fun loadExpandedWorkspaceSections(roomId: String): Set<String> = runCatching {
    localStorage.getItem(workspaceSectionsKey(roomId))
        ?.split(',')
        ?.filter(String::isNotBlank)
        ?.toSet()
        ?: setOf("mine", "copilot")
}.getOrDefault(setOf("mine", "copilot"))

private fun saveExpandedWorkspaceSections(roomId: String, sections: Set<String>) {
    runCatching { localStorage.setItem(workspaceSectionsKey(roomId), sections.joinToString(",")) }
}
