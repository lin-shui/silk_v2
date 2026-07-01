package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.silk.shared.i18n.Strings
import com.silk.shared.i18n.getStrings
import com.silk.shared.models.Language
import com.silk.shared.models.isAgentUserId
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
import org.jetbrains.compose.web.css.gap
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.left
import org.jetbrains.compose.web.css.margin
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginLeft
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.maxHeight
import org.jetbrains.compose.web.css.maxWidth
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.position
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.right
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.textAlign
import org.jetbrains.compose.web.css.top
import org.jetbrains.compose.web.css.vh
import org.jetbrains.compose.web.css.vw
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Style
import org.jetbrains.compose.web.dom.Text

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
fun GroupListScene(appState: WebAppState) {
    val scope = rememberCoroutineScope()

    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }

    // 删除模式相关状态
    var isDeleteMode by remember { mutableStateOf(false) }
    var selectedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isDeleting by remember { mutableStateOf(false) }

    // 成员列表相关状态
    var showMembersDialog by remember { mutableStateOf(false) }
    var selectedGroupForMembers by remember { mutableStateOf<Group?>(null) }
    var groupMembers by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var isLoadingMembers by remember { mutableStateOf(false) }

    // ✅ 未读消息计数
    var unreadCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    
    // cc-connect status: groupId -> CcConnectTokenInfo
    var ccConnectStatus by remember { mutableStateOf<Map<String, CcConnectTokenInfo>>(emptyMap()) }
    
    // Language and strings
    var userLanguage by remember { mutableStateOf<Language>(Language.CHINESE) }
    val strings = getStrings(userLanguage)
    
    // Load user language preference
    // Reload when user changes OR when navigating to this scene
    LaunchedEffect(appState.currentUser?.id, appState.currentScene) {
        if (appState.currentScene == Scene.GROUP_LIST) {
            appState.currentUser?.let { user ->
                scope.launch {
                    try {
                        val response = ApiClient.getUserSettings(user.id)
                        if (response.success && response.settings != null) {
                            userLanguage = response.settings!!.language
                        }
                    } catch (e: Exception) {
                        console.error("Failed to load user settings:", e)
                    }
                }
            }
        }
    }
    
    // 加载群组列表和未读数（每次进入 GROUP_LIST 场景时刷新）
    LaunchedEffect(appState.currentScene) {
        if (appState.currentScene == Scene.GROUP_LIST) {
            console.log("📋 GroupListScene - 开始加载群组...")
            console.log("   当前用户:", appState.currentUser?.fullName)
            
            isLoading = true
            try {
                val response = appState.currentUser?.let { user ->
                    console.log("   发送API请求获取群组...")
                    ApiClient.getUserGroups(user.id)
                }
                
                if (response != null && response.success) {
                    // 过滤掉工作流自动创建的关联群组（命名约定为 wf_ 前缀），
                    // 它们应该只通过工作流 Tab 访问，不在 Silk 群组列表中显示
                    groups = (response.groups ?: emptyList()).filterNot { it.name.startsWith("wf_") }
                    console.log("✅ 加载了${groups.size}个群组")
                    groups.forEach { group ->
                        console.log("   - ${group.name} (${group.invitationCode})")
                    }
                    
                    // 加载未读消息数
                    appState.currentUser?.let { user ->
                        val unreadResponse = ApiClient.getUnreadCounts(user.id)
                        if (unreadResponse.success) {
                            unreadCounts = unreadResponse.unreadCounts
                            console.log("✅ 未读消息: ", unreadCounts)
                        }
                    }
                    
                    // 加载 cc-connect 连接状态
                    val statusMap = mutableMapOf<String, CcConnectTokenInfo>()
                    val currentUserId = appState.currentUser?.id ?: ""
                    groups.forEach { group ->
                        val info = ApiClient.getCcConnectTokenInfo(group.id, currentUserId)
                        if (info != null && info.success) {
                            statusMap[group.id] = info
                        }
                    }
                    ccConnectStatus = statusMap
                } else {
                    console.log("⚠️ 加载群组失败:", response?.message)
                }
            } catch (e: Exception) {
                console.error("❌ 加载群组异常:", e.message)
                console.error("   详细错误:", e)
            } finally {
                isLoading = false
                console.log("📋 群组加载完成，isLoading =", isLoading)
            }
        }
    }
    
    // 定期刷新未读数（每30秒）
    LaunchedEffect(groups) {
        if (groups.isNotEmpty()) {
            while (true) {
                kotlinx.coroutines.delay(30000)
                appState.currentUser?.let { user ->
                    val unreadResponse = ApiClient.getUnreadCounts(user.id)
                    if (unreadResponse.success) {
                        unreadCounts = unreadResponse.unreadCounts
                    }
                }
            }
        }
    }
    
    // 设置全局样式，去掉浏览器滚动条
    Style {
        """
        html, body {
            height: 100%;
            margin: 0;
            padding: 0;
            overflow: hidden;
        }
        #root {
            height: 100%;
        }
        """.trimIndent()
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("width", "100%")
            property("height", "100%")
            property("overflow", "hidden")
            property("background", SilkColors.backgroundGradient)
            fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
        }
    }) {
        GroupListHeader(
            currentUserName = currentUser?.fullName.orEmpty(),
            currentUserId = currentUserId,
            groups = groups,
            strings = strings,
            isDeleteMode = isDeleteMode,
            selectedGroups = selectedGroups,
            isDeleting = isDeleting,
            onCancelDeleteMode = {
                isDeleteMode = false
                selectedGroups = emptySet()
            },
            onConfirmDeleteSelection = {
                scope.launch {
                    handleGroupDeleteSelection(
                        appState = appState,
                        groups = groups,
                        selectedGroups = selectedGroups,
                        isDeleting = isDeleting,
                        setDeleting = { isDeleting = it },
                        onGroupsLoaded = { groups = it },
                        onDeleteModeChanged = { isDeleteMode = it },
                        onSelectionChanged = { selectedGroups = it },
                    )
                }
            },
            onEnterDeleteMode = { isDeleteMode = true },
            onShowCreateDialog = { showCreateDialog = true },
            onShowJoinDialog = { showJoinDialog = true },
            onOpenContacts = { appState.navigateTo(Scene.CONTACTS) },
            onOpenSilkChat = {
                scope.launch {
                    openSilkPrivateChat(appState)
                }
            },
            onOpenSettings = { appState.navigateTo(Scene.SETTINGS) },
            onLogout = { appState.logout() },
        )

        GroupListContent(
            groups = groups,
            isLoading = isLoading,
            isDeleteMode = isDeleteMode,
            selectedGroups = selectedGroups,
            unreadCounts = unreadCounts,
            currentUserId = currentUserId,
            strings = strings,
            onShowCreateDialog = { showCreateDialog = true },
            onShowJoinDialog = { showJoinDialog = true },
            onToggleGroupSelection = { groupId, wasSelected ->
                selectedGroups = toggleSelectedGroupSelection(selectedGroups, groupId, wasSelected)
            },
            onOpenGroup = { group ->
                scope.launch {
                    openSelectedGroup(
                        appState = appState,
                        group = group,
                        onGroupMarkedRead = { groupId ->
                            unreadCounts = unreadCounts - groupId
                        },
                    )
                }
            },
            onShowMembersDialog = { group ->
                scope.launch {
                    loadGroupMembersDialog(
                        appState = appState,
                        group = group,
                        setLoading = { isLoadingMembers = it },
                        onGroupSelected = { selectedGroupForMembers = it },
                        onContactsLoaded = { contacts = it },
                        onMembersLoaded = { groupMembers = it },
                        onDialogVisibilityChanged = { showMembersDialog = it },
                    )
                }
            },
        )

        GroupListOverlays(
            scope = scope,
            appState = appState,
            groups = groups,
            strings = strings,
            showCreateDialog = showCreateDialog,
            showJoinDialog = showJoinDialog,
            showMembersDialog = showMembersDialog,
            selectedGroupForMembers = selectedGroupForMembers,
            groupMembers = groupMembers,
            contacts = contacts,
            currentUserId = currentUserId,
            isLoadingMembers = isLoadingMembers,
            onShowCreateDialog = { showCreateDialog = true },
            onCreateDialogDismiss = { showCreateDialog = false },
            onJoinDialogDismiss = { showJoinDialog = false },
            onGroupCreated = { newGroup ->
                groups = groups + newGroup
                showCreateDialog = false
            },
            onGroupJoined = { newGroup ->
                groups = groups + newGroup
                showJoinDialog = false
            },
            onMembersDialogDismiss = {
                showMembersDialog = false
                selectedGroupForMembers = null
            },
        )
    }
}

@Composable
private fun GroupListEffects(
    appState: WebAppState,
    groups: List<Group>,
    onLanguageLoaded: (Language) -> Unit,
    setLoading: (Boolean) -> Unit,
    onGroupsLoaded: (List<Group>) -> Unit,
    onUnreadCountsLoaded: (Map<String, Int>) -> Unit,
) {
    LaunchedEffect(appState.currentUser?.id, appState.currentScene) {
        loadGroupListLanguage(appState, onLanguageLoaded)
    }

    LaunchedEffect(appState.currentScene) {
        refreshGroupList(appState, setLoading, onGroupsLoaded, onUnreadCountsLoaded)
    }

    LaunchedEffect(groups, appState.currentUser?.id) {
        pollUnreadCountsForVisibleGroups(appState, groups, onUnreadCountsLoaded)
    }
}

@Composable
private fun GroupListHeader(
    currentUserName: String,
    currentUserId: String,
    groups: List<Group>,
    strings: Strings,
    isDeleteMode: Boolean,
    selectedGroups: Set<String>,
    isDeleting: Boolean,
    onCancelDeleteMode: () -> Unit,
    onConfirmDeleteSelection: () -> Unit,
    onEnterDeleteMode: () -> Unit,
    onShowCreateDialog: () -> Unit,
    onShowJoinDialog: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSilkChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    Div({
        style {
            property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
            color(Color.white)
            padding(16.px, 20.px)
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            property("box-shadow", "0 2px 12px rgba(169, 137, 77, 0.25)")
        }
    }) {
        GroupListHeaderIdentity(currentUserName)
        GroupListHeaderActions(
            currentUserId = currentUserId,
            groups = groups,
            strings = strings,
            isDeleteMode = isDeleteMode,
            selectedGroups = selectedGroups,
            isDeleting = isDeleting,
            onCancelDeleteMode = onCancelDeleteMode,
            onConfirmDeleteSelection = onConfirmDeleteSelection,
            onEnterDeleteMode = onEnterDeleteMode,
            onShowCreateDialog = onShowCreateDialog,
            onShowJoinDialog = onShowJoinDialog,
            onOpenContacts = onOpenContacts,
            onOpenSilkChat = onOpenSilkChat,
            onOpenSettings = onOpenSettings,
            onLogout = onLogout,
        )
    }
}

@Composable
private fun GroupListHeaderIdentity(currentUserName: String) {
    Div {
        Div({
            style {
                fontSize(24.px)
                property("font-weight", "700")
                property("letter-spacing", "4px")
                property("text-transform", "uppercase")
                marginBottom(4.px)
            }
        }) {
            Text("Silk")
        }
        Div({
            style {
                fontSize(13.px)
                property("opacity", "0.9")
                property("letter-spacing", "1px")
            }
        }) {
            Text(currentUserName)
        }
    }
}

@Composable
private fun GroupListHeaderActions(
    currentUserId: String,
    groups: List<Group>,
    strings: Strings,
    isDeleteMode: Boolean,
    selectedGroups: Set<String>,
    isDeleting: Boolean,
    onCancelDeleteMode: () -> Unit,
    onConfirmDeleteSelection: () -> Unit,
    onEnterDeleteMode: () -> Unit,
    onShowCreateDialog: () -> Unit,
    onShowJoinDialog: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSilkChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            gap(12.px)
            alignItems(AlignItems.Center)
        }
    }) {
        if (isDeleteMode) {
            GroupListHeaderButton(
                label = strings.cancelButton,
                background = "rgba(255,255,255,0.3)",
                onClick = onCancelDeleteMode,
            )
            if (selectedGroups.isNotEmpty()) {
                GroupListHeaderButton(
                    label = deleteSelectionLabel(groups, selectedGroups, currentUserId, strings, isDeleting),
                    background = "#e74c3c",
                    onClick = onConfirmDeleteSelection,
                    cursor = if (isDeleting) "not-allowed" else "pointer",
                    opacity = if (isDeleting) "0.6" else "1",
                )
            }
        } else {
            GroupListHeaderButton(
                label = "➖ ${strings.exitButton}",
                background = "rgba(255,255,255,0.2)",
                onClick = onEnterDeleteMode,
            )
            GroupListHeaderButton(
                label = "➕ ${strings.createButton}",
                background = "rgba(255,255,255,0.2)",
                onClick = onShowCreateDialog,
            )
        }

        GroupListHeaderButton(
            label = "🔗 ${strings.joinButton}",
            background = "rgba(255,255,255,0.2)",
            onClick = onShowJoinDialog,
        )
        GroupListHeaderButton(
            label = "👤 ${strings.contactsButton}",
            background = "rgba(255,255,255,0.2)",
            onClick = onOpenContacts,
        )
        GroupListHeaderButton(
            label = "🤖 ${strings.chatWithSilk}",
            background = "#7BA8C9",
            onClick = onOpenSilkChat,
            fontWeight = "600",
            boxShadow = "0 2px 8px rgba(123, 168, 201, 0.4)",
        )
        GroupListHeaderButton(
            label = "⚙️ ${strings.settingsButton}",
            background = "rgba(255,255,255,0.2)",
            onClick = onOpenSettings,
        )
        GroupListHeaderButton(
            label = strings.logoutButton,
            background = "rgba(255,255,255,0.15)",
            onClick = onLogout,
        )
    }
}

@Composable
private fun GroupListHeaderButton(
    label: String,
    background: String,
    onClick: () -> Unit,
    cursor: String = "pointer",
    opacity: String = "1",
    fontWeight: String = "500",
    boxShadow: String? = null,
) {
    Button({
        style {
            padding(10.px, 18.px)
            backgroundColor(Color(background))
            color(Color.white)
            border { width(0.px) }
            borderRadius(8.px)
            property("cursor", cursor)
            property("opacity", opacity)
            property("transition", "all 0.2s ease")
            fontSize(14.px)
            property("font-weight", fontWeight)
            if (background.startsWith("rgba")) {
                property("backdrop-filter", "blur(4px)")
            }
            boxShadow?.let { property("box-shadow", it) }
        }
        onClick { onClick() }
    }) {
        Text(label)
    }
}

@Composable
private fun GroupListContent(
    groups: List<Group>,
    isLoading: Boolean,
    isDeleteMode: Boolean,
    selectedGroups: Set<String>,
    unreadCounts: Map<String, Int>,
    currentUserId: String,
    strings: Strings,
    onShowCreateDialog: () -> Unit,
    onShowJoinDialog: () -> Unit,
    onToggleGroupSelection: (groupId: String, wasSelected: Boolean) -> Unit,
    onOpenGroup: (Group) -> Unit,
    onShowMembersDialog: (Group) -> Unit,
) {
    Div({
        style {
            property("flex", "1")
            property("overflow-y", "auto")
            padding(24.px)
        }
    }) {
        when {
            isLoading -> GroupListLoadingState(strings.loading)
            groups.isEmpty() -> GroupListEmptyState(strings, onShowCreateDialog, onShowJoinDialog)
            else -> {
                GroupListDeleteModeNotice(
                    isDeleteMode = isDeleteMode,
                    selectedGroups = selectedGroups,
                    groups = groups,
                    currentUserId = currentUserId,
                    strings = strings,
                )
                groups.forEach { group ->
                    val isSelected = group.id in selectedGroups
                    GroupCard(
                        group = group,
                        isHost = group.hostId == currentUserId,
                        isDeleteMode = isDeleteMode,
                        isSelected = isSelected,
                        unreadCount = unreadCounts[group.id] ?: 0,
                        strings = strings,
                        onClick = {
                            if (isDeleteMode) {
                                onToggleGroupSelection(group.id, isSelected)
                            } else {
                                onOpenGroup(group)
                            }
                        },
                        onMembersClick = { onShowMembersDialog(group) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupListLoadingState(loadingLabel: String) {
    Div({
        style {
            textAlign("center")
            padding(60.px)
            color(Color(SilkColors.textSecondary))
            fontSize(15.px)
            property("letter-spacing", "1px")
        }
    }) {
        Text(loadingLabel)
    }
}

@Composable
private fun GroupListEmptyState(
    strings: Strings,
    onShowCreateDialog: () -> Unit,
    onShowJoinDialog: () -> Unit,
) {
    Div({
        style {
            textAlign("center")
            padding(60.px, 40.px)
        }
    }) {
        H3({
            style {
                color(Color(SilkColors.textPrimary))
                marginBottom(12.px)
                property("font-weight", "600")
                property("letter-spacing", "1px")
            }
        }) {
            Text(strings.noGroupsMessage)
        }
        P({
            style {
                color(Color(SilkColors.textSecondary))
                fontSize(14.px)
                property("letter-spacing", "0.5px")
            }
        }) {
            Text(strings.noGroupsSubmessage)
        }

        Div({
            style {
                property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                color(Color.white)
                padding(12.px, 16.px)
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.FlexStart)
                property("box-shadow", "0 2px 12px rgba(169, 137, 77, 0.25)")
            }
        }) {
            Div({
                style {
                    property("flex-shrink", "0")
                    property("min-width", "0")
                }
            }) {
                // Logo
                Div({
                    style {
                        fontSize(24.px)
                        property("font-weight", "700")
                        property("letter-spacing", "4px")
                        property("text-transform", "uppercase")
                        marginBottom(4.px)
                    }
                }) {
                    Text("Silk")
                }
                Div({ 
                    style { 
                        fontSize(13.px)
                        property("opacity", "0.9")
                        property("white-space", "nowrap")
                        property("overflow", "hidden")
                        property("text-overflow", "ellipsis")
                    } 
                }) {
                    Text(appState.currentUser?.fullName ?: "")
                }
            }
            
            // 右侧按钮组
            Div({
                style {
                    display(DisplayStyle.Flex)
                    gap(4.px)
                    alignItems(AlignItems.Center)
                }
            }) {
                // 🤖 与 Silk 对话按钮
                Button({
                    style {
                        padding(8.px, 10.px)
                        backgroundColor(Color("#7BA8C9"))
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        property("box-shadow", "0 2px 8px rgba(123, 168, 201, 0.4)")
                        fontSize(18.px)
                        property("flex-shrink", "0")
                    }
                    onClick {
                        scope.launch {
                            val uid = appState.currentUser?.id ?: return@launch
                            val r = ApiClient.startSilkPrivateChat(uid)
                            if (r.success && r.group != null) appState.selectGroup(r.group!!)
                        }
                    }
                }) { Text("🤖") }
                
                // 下拉菜单
                var showMenu by remember { mutableStateOf(false) }
                Div({
                    style {
                        position(Position.Relative)
                    }
                }) {
                    Button({
                        style {
                            padding(8.px, 14.px)
                            backgroundColor(Color("rgba(255,255,255,0.2)"))
                            color(Color.white)
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", "pointer")
                            property("backdrop-filter", "blur(4px)")
                            fontSize(18.px)
                        }
                        onClick { showMenu = !showMenu }
                    }) { Text("☰") }
                    
                    if (showMenu) {
                        Div({
                            style {
                                position(Position.Fixed)
                                top(0.px); left(0.px); right(0.px); bottom(0.px)
                                property("z-index", "99")
                            }
                            onClick { showMenu = false }
                        })
                        
                        Div({
                            style {
                                position(Position.Absolute)
                                top(100.percent)
                                right(0.px)
                                property("z-index", "100")
                                backgroundColor(Color("#2a2a2a"))
                                borderRadius(10.px)
                                property("box-shadow", "0 4px 20px rgba(0,0,0,0.3)")
                                property("min-width", "160px")
                                padding(6.px)
                                marginTop(6.px)
                            }
                            onClick { showMenu = false }
                        }) {
                            if (isDeleteMode) {
                                Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { isDeleteMode = false; selectedGroups = emptySet(); showMenu = false } }) { Text("🔙  ${strings.cancelButton}") }
                                if (selectedGroups.isNotEmpty()) {
                                    Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color("#e74c3c")); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { scope.launch { isDeleting = true; val userId = appState.currentUser?.id ?: ""; selectedGroups.forEach { groupId -> val group = groups.find { it.id == groupId }; if (group != null && group.hostId == userId) ApiClient.deleteGroup(groupId, userId) else ApiClient.leaveGroup(groupId, userId) }; val r2 = ApiClient.getUserGroups(userId); if (r2.success) { groups = r2.groups ?: emptyList() } else { /* keep existing groups on failure */ }; isDeleting = false; isDeleteMode = false; selectedGroups = emptySet(); showMenu = false } } }) { Text("🗑  ${strings.exitButton} (${selectedGroups.size})") }
                                }
                            } else {
                                Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { isDeleteMode = true; showMenu = false } }) { Text("➖  ${strings.exitButton}") }
                                Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { showCreateDialog = true; showMenu = false } }) { Text("➕  ${strings.createButton}") }
                            }
                            Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { showJoinDialog = true; showMenu = false } }) { Text("🔗  ${strings.joinButton}") }
                            Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { appState.navigateTo(Scene.CONTACTS); showMenu = false } }) { Text("👤  ${strings.contactsButton}") }
                            Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { appState.logout(); showMenu = false } }) { Text("🚪  ${strings.logoutButton}") }
                        }
                    }
                }
            }
        }
        
        // 内容区域
        Div({
            style {
                property("flex", "1")
                property("overflow-y", "auto")
                padding(24.px)
            }
        }) {
            when {
                isLoading -> {
                    Div({
                        style {
                            textAlign("center")
                            padding(60.px)
                            color(Color(SilkColors.textSecondary))
                            fontSize(15.px)
                            property("letter-spacing", "1px")
                        }
                    }) {
                        Text(strings.loading)
                    }
                }
                groups.isEmpty() -> {
                    // 空状态 - 丝滑风格
                    Div({
                        style {
                            textAlign("center")
                            padding(60.px, 40.px)
                        }
                    }) {
                        H3({ 
                            style { 
                                color(Color(SilkColors.textPrimary))
                                marginBottom(12.px)
                                property("font-weight", "600")
                                property("letter-spacing", "1px")
                            } 
                        }) { 
                            Text(strings.noGroupsMessage) 
                        }
                        P({ 
                            style { 
                                color(Color(SilkColors.textSecondary))
                                fontSize(14.px)
                                property("letter-spacing", "0.5px")
                            } 
                        }) { 
                            Text(strings.noGroupsSubmessage) 
                        }
                        
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.Center)
                                property("gap", "16px")
                                marginTop(32.px)
                            }
                        }) {
                            Button({
                                style {
                                    padding(14.px, 28.px)
                                    property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                                    color(Color.white)
                                    border { width(0.px) }
                                    borderRadius(8.px)
                                    property("cursor", "pointer")
                                    fontSize(14.px)
                                    property("font-weight", "600")
                                    property("letter-spacing", "1px")
                                    property("box-shadow", "0 4px 12px rgba(169, 137, 77, 0.3)")
                                    property("transition", "all 0.2s ease")
                                }
                                onClick { showCreateDialog = true }
                            }) {
                                Text(strings.createGroup)
                            }
                            
                            Button({
                                style {
                                    padding(14.px, 28.px)
                                    backgroundColor(Color(SilkColors.surfaceElevated))
                                    color(Color(SilkColors.primary))
                                    border {
                                        width(2.px)
                                        style(LineStyle.Solid)
                                        color(Color(SilkColors.primary))
                                    }
                                    borderRadius(8.px)
                                    property("cursor", "pointer")
                                    fontSize(14.px)
                                    property("font-weight", "600")
                                    property("letter-spacing", "1px")
                                    property("transition", "all 0.2s ease")
                                }
                                onClick { showJoinDialog = true }
                            }) {
                                Text(strings.joinGroup)
                            }
                        }
                    }
                }
                else -> {
                    // 删除模式提示
                    if (isDeleteMode) {
                        Div({
                            style {
                                backgroundColor(Color("#FFF3E0"))
                                padding(12.px, 16.px)
                                borderRadius(8.px)
                                marginBottom(16.px)
                                color(Color("#E65100"))
                                fontSize(14.px)
                                property("text-align", "center")
                            }
                        }) {
                            val currentUserId = appState.currentUser?.id ?: ""
                            val hostGroups = selectedGroups.count { gid -> groups.find { it.id == gid }?.hostId == currentUserId }
                            val memberGroups = selectedGroups.size - hostGroups
                            if (hostGroups > 0 && memberGroups > 0) {
                                Text("选中 ${hostGroups} 个群组将删除（作为群主），${memberGroups} 个群组将退出（作为成员）")
                            } else if (hostGroups > 0) {
                                Text("选中 ${hostGroups} 个群组将被删除（您是群主）")
                            } else if (memberGroups > 0) {
                                Text(strings.selectGroupsToExit)
                            } else {
                                Text(strings.selectGroupsToExit)
                            }
                        }
                    }
                    
                    // 群组列表（Silk AI → CC-Connect → Silk Groups）
                    val silkPrivateGroups = groups.filter { it.name.startsWith("[Silk]") }
                    val ccGroups = groups.filter { ccConnectStatus.containsKey(it.id) }
                    val silkNormalGroups = groups.filter {
                        !it.name.startsWith("[Silk]") && !ccConnectStatus.containsKey(it.id)
                    }

                    @Composable
                    fun renderGroupCard(group: Group) {
                        val isSelected = group.id in selectedGroups
                        val unreadCount = unreadCounts[group.id] ?: 0
                        val ccInfo = ccConnectStatus[group.id]
                        val isSilkPrivate = group.name.startsWith("[Silk]")
                        GroupCard(
                            group = group,
                            isHost = group.hostId == appState.currentUser?.id,
                            isDeleteMode = isDeleteMode,
                            isSelected = isSelected,
                            unreadCount = unreadCount,
                            ccConnectInfo = ccInfo,
                            strings = strings,
                            onClick = {
                                if (isDeleteMode) {
                                    selectedGroups = if (isSelected) {
                                        selectedGroups - group.id
                                    } else {
                                        selectedGroups + group.id
                                    }
                                } else {
                                    scope.launch {
                                        appState.currentUser?.let { user ->
                                            ApiClient.markGroupAsRead(user.id, group.id)
                                            unreadCounts = unreadCounts - group.id
                                        }
                                    }
                                    appState.selectGroup(group)
                                }
                            },
                            onMembersClick = ({
                                selectedGroupForMembers = group
                                scope.launch {
                                    isLoadingMembers = true
                                    val userId = appState.currentUser?.id ?: return@launch
                                    val contactsResponse = ApiClient.getContacts(userId)
                                    contacts = contactsResponse.contacts ?: emptyList()
                                    val membersResponse = ApiClient.getGroupMembers(group.id)
                                    val sortedMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
                                    groupMembers = sortedMembers
                                    isLoadingMembers = false
                                    showMembersDialog = true
                                }
                                Unit
                            } as (() -> Unit)?).takeIf { !isSilkPrivate }
                        )
                    }

                    // --- Section 1: Silk 专属对话 ---
                    if (silkPrivateGroups.isNotEmpty()) {
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                property("gap", "8px")
                                marginBottom(12.px)
                            }
                        }) {
                            Span({
                                style {
                                    fontSize(12.px)
                                    color(Color(SilkColors.primary))
                                    property("font-weight", "700")
                                    property("letter-spacing", "1px")
                                    property("white-space", "nowrap")
                                }
                            }) { Text("Silk AI") }
                            Div({
                                style {
                                    property("flex", "1")
                                    height(1.px)
                                    backgroundColor(Color(SilkColors.primary))
                                    property("opacity", "0.3")
                                }
                            })
                        }
                        silkPrivateGroups.forEach { renderGroupCard(it) }
                    }

                    // --- Section 2: CC-Connect 群组 ---
                    if (ccGroups.isNotEmpty()) {
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                property("gap", "8px")
                                marginTop(if (silkPrivateGroups.isNotEmpty()) 16.px else 0.px)
                                marginBottom(12.px)
                            }
                        }) {
                            Span({
                                style {
                                    fontSize(12.px)
                                    color(Color("#2E7D32"))
                                    property("font-weight", "700")
                                    property("letter-spacing", "1px")
                                    property("white-space", "nowrap")
                                }
                            }) { Text("CC-Connect") }
                            Div({
                                style {
                                    property("flex", "1")
                                    height(1.px)
                                    backgroundColor(Color("#4CAF50"))
                                    property("opacity", "0.3")
                                }
                            })
                        }
                        ccGroups.forEach { renderGroupCard(it) }
                    }

                    // --- Section 3: Silk 普通群组 ---
                    if (silkNormalGroups.isNotEmpty()) {
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                property("gap", "8px")
                                marginTop(if (silkPrivateGroups.isNotEmpty() || ccGroups.isNotEmpty()) 16.px else 0.px)
                                marginBottom(12.px)
                            }
                        }) {
                            Span({
                                style {
                                    fontSize(12.px)
                                    color(Color(SilkColors.textSecondary))
                                    property("font-weight", "700")
                                    property("letter-spacing", "1px")
                                    property("white-space", "nowrap")
                                }
                            }) { Text("Silk Groups") }
                            Div({
                                style {
                                    property("flex", "1")
                                    height(1.px)
                                    backgroundColor(Color(SilkColors.textSecondary))
                                    property("opacity", "0.2")
                                }
                            })
                        }
                        silkNormalGroups.forEach { renderGroupCard(it) }
                    }
                }
            }
        }
        
        // FAB按钮（创建群组）- 丝滑风格
        if (groups.isNotEmpty()) {
            Button({
                style {
                    padding(14.px, 28.px)
                    property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(8.px)
                    property("cursor", "pointer")
                    fontSize(14.px)
                    property("font-weight", "600")
                    property("letter-spacing", "1px")
                    property("box-shadow", "0 4px 12px rgba(169, 137, 77, 0.3)")
                    property("transition", "all 0.2s ease")
                }
                onClick { onShowCreateDialog() }
            }) {
                Text(strings.createGroup)
            }

            Button({
                style {
                    padding(14.px, 28.px)
                    backgroundColor(Color(SilkColors.surfaceElevated))
                    color(Color(SilkColors.primary))
                    border {
                        width(2.px)
                        style(LineStyle.Solid)
                        color(Color(SilkColors.primary))
                    }
                    borderRadius(8.px)
                    property("cursor", "pointer")
                    fontSize(14.px)
                    property("font-weight", "600")
                    property("letter-spacing", "1px")
                    property("transition", "all 0.2s ease")
                }
                onClick { onShowJoinDialog() }
            }) {
                Text(strings.joinGroup)
            }
        }
        
        // 对话框
        if (showCreateDialog) {
            CreateGroupDialog(
                appState = appState,
                strings = strings,
                onDismiss = { showCreateDialog = false },
                onGroupCreated = { newGroup ->
                    groups = groups + newGroup
                },
                onComplete = { showCreateDialog = false }
            )
        }
    }) {
        Text(deleteModeNoticeText(selectedGroups, groups, currentUserId, strings))
    }
}

@Composable
private fun GroupListOverlays(
    scope: kotlinx.coroutines.CoroutineScope,
    appState: WebAppState,
    groups: List<Group>,
    strings: Strings,
    showCreateDialog: Boolean,
    showJoinDialog: Boolean,
    showMembersDialog: Boolean,
    selectedGroupForMembers: Group?,
    groupMembers: List<GroupMember>,
    contacts: List<Contact>,
    currentUserId: String,
    isLoadingMembers: Boolean,
    onShowCreateDialog: () -> Unit,
    onCreateDialogDismiss: () -> Unit,
    onJoinDialogDismiss: () -> Unit,
    onGroupCreated: (Group) -> Unit,
    onGroupJoined: (Group) -> Unit,
    onMembersDialogDismiss: () -> Unit,
) {
    if (groups.isNotEmpty()) {
        GroupListCreateFab(onShowCreateDialog)
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            appState = appState,
            strings = strings,
            onDismiss = onCreateDialogDismiss,
            onGroupCreated = onGroupCreated,
        )
    }

    if (showJoinDialog) {
        JoinGroupDialog(
            appState = appState,
            strings = strings,
            onDismiss = onJoinDialogDismiss,
            onGroupJoined = onGroupJoined,
        )
    }

    val group = selectedGroupForMembers
    if (showMembersDialog && group != null) {
        GroupMembersListDialog(
            group = group,
            members = groupMembers,
            contacts = contacts,
            currentUserId = currentUserId,
            isLoading = isLoadingMembers,
            strings = strings,
            onMemberClick = { member ->
                scope.launch {
                    handleGroupMemberClick(
                        appState = appState,
                        member = member,
                        contacts = contacts,
                        onDialogDismiss = onMembersDialogDismiss,
                    )
                }
            },
            onDismiss = onMembersDialogDismiss,
        )
    }
}

@Composable
private fun GroupListCreateFab(onClick: () -> Unit) {
    Button({
        style {
            position(Position.Fixed)
            bottom(28.px)
            right(28.px)
            width(60.px)
            height(60.px)
            borderRadius(50.percent)
            property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
            color(Color.white)
            border { width(0.px) }
            fontSize(28.px)
            property("cursor", "pointer")
            property("box-shadow", "0 4px 16px rgba(169, 137, 77, 0.4)")
            property("transition", "all 0.2s ease")
        }
        onClick { onClick() }
    }) {
        Text("+")
    }
}

private suspend fun loadGroupListLanguage(
    appState: WebAppState,
    onLanguageLoaded: (Language) -> Unit,
) {
    if (appState.currentScene != Scene.GROUP_LIST) {
        return
    }

    val user = appState.currentUser ?: return
    recoverSuspendNonCancellation(
        block = {
            val response = ApiClient.getUserSettings(user.id)
            if (response.success) {
                response.settings?.language?.let(onLanguageLoaded)
            }
        },
        recover = { error ->
            console.error("Failed to load user settings:", error)
        },
    )
}

private suspend fun refreshGroupList(
    appState: WebAppState,
    setLoading: (Boolean) -> Unit,
    onGroupsLoaded: (List<Group>) -> Unit,
    onUnreadCountsLoaded: (Map<String, Int>) -> Unit,
) {
    if (appState.currentScene != Scene.GROUP_LIST) {
        return
    }

    console.log("📋 GroupListScene - 开始加载群组...")
    console.log("   当前用户:", appState.currentUser?.fullName)
    setLoading(true)

    try {
        recoverSuspendNonCancellation(
            block = {
                val user = appState.currentUser
                if (user == null) {
                    console.log("⚠️ 当前用户为空，跳过群组加载")
                    return@recoverSuspendNonCancellation
                }

                console.log("   发送API请求获取群组...")
                val response = ApiClient.getUserGroups(user.id)
                if (!response.success) {
                    console.log("⚠️ 加载群组失败:", response.message)
                    return@recoverSuspendNonCancellation
                }

                val visibleGroups = visibleGroups(response.groups ?: emptyList())
                onGroupsLoaded(visibleGroups)
                console.log("✅ 加载了${visibleGroups.size}个群组")
                visibleGroups.forEach { group ->
                    console.log("   - ${group.name} (${group.invitationCode})")
                }

                fetchUnreadCounts(user.id)?.let { unreadCounts ->
                    onUnreadCountsLoaded(unreadCounts)
                    console.log("✅ 未读消息: ", unreadCounts)
                }
            },
            recover = { error ->
                console.error("❌ 加载群组异常:", error.message)
                console.error("   详细错误:", error)
            },
        )
    } finally {
        setLoading(false)
        console.log("📋 群组加载完成")
    }
}

private suspend fun pollUnreadCountsForVisibleGroups(
    appState: WebAppState,
    groups: List<Group>,
    onUnreadCountsLoaded: (Map<String, Int>) -> Unit,
) {
    if (groups.isEmpty()) {
        return
    }

    val user = appState.currentUser ?: return
    while (true) {
        kotlinx.coroutines.delay(30000)
        fetchUnreadCounts(user.id)?.let(onUnreadCountsLoaded)
    }
}

private suspend fun fetchUnreadCounts(userId: String): Map<String, Int>? {
    val unreadResponse = ApiClient.getUnreadCounts(userId)
    return if (unreadResponse.success) unreadResponse.unreadCounts else null
}

private suspend fun handleGroupDeleteSelection(
    appState: WebAppState,
    groups: List<Group>,
    selectedGroups: Set<String>,
    isDeleting: Boolean,
    setDeleting: (Boolean) -> Unit,
    onGroupsLoaded: (List<Group>) -> Unit,
    onDeleteModeChanged: (Boolean) -> Unit,
    onSelectionChanged: (Set<String>) -> Unit,
) {
    if (isDeleting) {
        return
    }

    val userId = appState.currentUser?.id ?: return
    setDeleting(true)
    try {
        selectedGroups.forEach { groupId ->
            val group = groups.find { it.id == groupId }
            if (group != null && group.hostId == userId) {
                val response = ApiClient.deleteGroup(groupId, userId)
                console.log("删除群组 $groupId: ${response.message}")
            } else {
                val response = ApiClient.leaveGroup(groupId, userId)
                console.log("退出群组 $groupId: ${response.message}")
            }
        }

        val response = ApiClient.getUserGroups(userId)
        if (response.success) {
            onGroupsLoaded(visibleGroups(response.groups ?: emptyList()))
        }
    } finally {
        setDeleting(false)
        onDeleteModeChanged(false)
        onSelectionChanged(emptySet())
    }
}

private suspend fun openSilkPrivateChat(appState: WebAppState) {
    val userId = appState.currentUser?.id ?: return
    val response = ApiClient.startSilkPrivateChat(userId)
    val group = response.group
    if (response.success && group != null) {
        console.log("✅ 打开与 Silk 的对话: ${group.name}")
        appState.selectGroup(group)
    } else {
        console.log("❌ 打开 Silk 对话失败: ${response.message}")
    }
}

private suspend fun openSelectedGroup(
    appState: WebAppState,
    group: Group,
    onGroupMarkedRead: (String) -> Unit,
) {
    val user = appState.currentUser ?: return
    ApiClient.markGroupAsRead(user.id, group.id)
    onGroupMarkedRead(group.id)
    appState.selectGroup(group)
}

private suspend fun loadGroupMembersDialog(
    appState: WebAppState,
    group: Group,
    setLoading: (Boolean) -> Unit,
    onGroupSelected: (Group?) -> Unit,
    onContactsLoaded: (List<Contact>) -> Unit,
    onMembersLoaded: (List<GroupMember>) -> Unit,
    onDialogVisibilityChanged: (Boolean) -> Unit,
) {
    val userId = appState.currentUser?.id ?: return
    onGroupSelected(group)
    setLoading(true)
    try {
        val contactsResponse = ApiClient.getContacts(userId)
        onContactsLoaded(contactsResponse.contacts ?: emptyList())

        val membersResponse = ApiClient.getGroupMembers(group.id)
        onMembersLoaded(membersResponse.members.sortedByDescending { it.id == group.hostId })
        onDialogVisibilityChanged(true)
    } finally {
        setLoading(false)
    }
}

private suspend fun handleGroupMemberClick(
    appState: WebAppState,
    member: GroupMember,
    contacts: List<Contact>,
    onDialogDismiss: () -> Unit,
) {
    val userId = appState.currentUser?.id ?: return
    val isContact = contacts.any { it.contactId == member.id }

    if (isContact) {
        onDialogDismiss()
        val response = ApiClient.startPrivateChat(userId, member.id)
        val group = response.group
        if (response.success && group != null) {
            appState.selectGroup(group)
        } else {
            console.log("无法创建对话: ${response.message}")
        }
        return
    }

    val response = ApiClient.sendContactRequestById(userId, member.id)
    if (response.success) {
        console.log("联系人请求已发送给 ${member.fullName}")
    } else {
        console.log("发送失败: ${response.message}")
    }
}

private fun visibleGroups(groups: List<Group>): List<Group> =
    groups.filterNot { it.name.startsWith("wf_") }

private fun toggleSelectedGroupSelection(
    selectedGroups: Set<String>,
    groupId: String,
    wasSelected: Boolean,
): Set<String> = if (wasSelected) {
    selectedGroups - groupId
} else {
    selectedGroups + groupId
}

private fun deleteSelectionLabel(
    groups: List<Group>,
    selectedGroups: Set<String>,
    currentUserId: String,
    strings: Strings,
    isDeleting: Boolean,
): String {
    if (isDeleting) {
        return strings.exiting
    }

    val hasHostGroups = selectedGroups.any { groupId ->
        groups.find { it.id == groupId }?.hostId == currentUserId
    }
    val hasMemberGroups = selectedGroups.any { groupId ->
        groups.find { it.id == groupId }?.hostId != currentUserId
    }

    val actionText = when {
        hasHostGroups && hasMemberGroups -> "删除/退出"
        hasHostGroups -> "删除"
        else -> "退出"
    }
    return "$actionText (${selectedGroups.size})"
}

private fun deleteModeNoticeText(
    selectedGroups: Set<String>,
    groups: List<Group>,
    currentUserId: String,
    strings: Strings,
): String {
    val hostGroups = selectedGroups.count { groupId ->
        groups.find { it.id == groupId }?.hostId == currentUserId
    }
    val memberGroups = selectedGroups.size - hostGroups

    return when {
        hostGroups > 0 && memberGroups > 0 ->
            "选中 ${hostGroups} 个群组将删除（作为群主），${memberGroups} 个群组将退出（作为成员）"
        hostGroups > 0 -> "选中 ${hostGroups} 个群组将被删除（您是群主）"
        else -> strings.selectGroupsToExit
    }
}

@Suppress("CyclomaticComplexMethod", "UnusedParameter")
@Composable
fun GroupCard(
    group: Group,
    isHost: Boolean, 
    isDeleteMode: Boolean = false,
    isSelected: Boolean = false,
    unreadCount: Int = 0,
    ccConnectInfo: CcConnectTokenInfo? = null,
    strings: Strings,
    onClick: () -> Unit,
    onMembersClick: (() -> Unit)? = null
) {
    val hasUnread = unreadCount > 0
    val isCcConnect = ccConnectInfo != null
    val ccConnected = ccConnectInfo?.connected == true
    
    Div({
        attr("data-host", isHost.toString())
        style {
            backgroundColor(Color(visualState.backgroundColor))
            borderRadius(8.px)
            padding(10.px, 14.px)
            marginBottom(8.px)
            property("box-shadow", visualState.boxShadow)
            property("cursor", "pointer")
            property("transition", "all 0.2s ease")
            property("border", visualState.border)
        }
        onClick { onClick() }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
            }
        }) {
            // 左侧：未读指示器 + 群名 + 邀请码（紧凑布局）
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "10px")
                    property("flex", "1")
                }
            }) {
                GroupCardUnreadBadge(visualState)
                Span({
                    style {
                        fontSize(14.px)
                        property("font-weight", visualState.nameFontWeight)
                        color(Color(visualState.nameColor))
                    }
                }) {
                    Text(group.name)
                }
                
                // 邀请码（非 Silk 专属对话才显示）
                if (!group.name.startsWith("[Silk]")) {
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textSecondary))
                        property("letter-spacing", "1px")
                    }
                }) {
                    Text("[${group.invitationCode}]")
                }
                }
                
                // cc-connect status badge
                if (isCcConnect) {
                    val badgeName = run {
                        val raw = (ccConnectInfo?.agentType ?: "").lowercase().trim()
                        when {
                            raw.startsWith("claude") -> "claude"
                            raw.startsWith("cursor") -> "cursor"
                            raw.startsWith("gemini") -> "gemini"
                            raw.startsWith("codex")  -> "codex"
                            raw.startsWith("copilot") -> "copilot"
                            raw.isBlank() -> "cc"
                            else -> raw
                        }
                    }
                    Span({
                        style {
                            fontSize(10.px)
                            padding(2.px, 6.px)
                            borderRadius(4.px)
                            property("font-weight", "600")
                            property("letter-spacing", "0.5px")
                            if (ccConnected) {
                                backgroundColor(Color("#E8F5E9"))
                                color(Color("#2E7D32"))
                            } else {
                                backgroundColor(Color("#FFF3E0"))
                                color(Color("#E65100"))
                            }
                        }
                        if (ccConnected) {
                            title("${ccConnectInfo?.agentType ?: "agent"} — ${ccConnectInfo?.project ?: ""}")
                        }
                    }) {
                        Text(if (ccConnected) badgeName else "$badgeName (offline)")
                    }
                }
            }
            GroupCardActions(
                visualState = visualState,
                newMessageLabel = strings.newMessage,
                onMembersClick = onMembersClick,
            )
        }
    }
}

private data class GroupCardVisualState(
    val backgroundColor: String,
    val boxShadow: String,
    val border: String,
    val nameFontWeight: String,
    val nameColor: String,
    val unreadBadgeText: String?,
    val showUnreadLabel: Boolean,
    val deleteIndicatorColor: String?,
    val showDeleteCheckmark: Boolean,
)

private fun buildGroupCardVisualState(
    isSelected: Boolean,
    hasUnread: Boolean,
    isDeleteMode: Boolean,
    unreadCount: Int,
): GroupCardVisualState = GroupCardVisualState(
    backgroundColor = groupCardBackgroundColor(isSelected, hasUnread),
    boxShadow = if (hasUnread) "0 2px 8px rgba(255, 152, 0, 0.3)" else "0 1px 4px rgba(169, 137, 77, 0.06)",
    border = groupCardBorder(isSelected, hasUnread),
    nameFontWeight = if (hasUnread) "700" else "600",
    nameColor = if (hasUnread) "#E65100" else SilkColors.textPrimary,
    unreadBadgeText = unreadBadgeText(hasUnread, isDeleteMode, unreadCount),
    showUnreadLabel = hasUnread && !isDeleteMode,
    deleteIndicatorColor = deleteIndicatorColor(isDeleteMode, isSelected),
    showDeleteCheckmark = isDeleteMode && isSelected,
)

private fun groupCardBackgroundColor(isSelected: Boolean, hasUnread: Boolean): String = when {
    isSelected -> "#FFEBEE"
    hasUnread -> "#FFF8E1"
    else -> SilkColors.surfaceElevated
}

private fun groupCardBorder(isSelected: Boolean, hasUnread: Boolean): String = when {
    isSelected -> "2px solid #e74c3c"
    hasUnread -> "2px solid #FF9800"
    else -> "1px solid ${SilkColors.border}"
}

private fun unreadBadgeText(
    hasUnread: Boolean,
    isDeleteMode: Boolean,
    unreadCount: Int,
): String? {
    if (!hasUnread || isDeleteMode) {
        return null
    }
    return if (unreadCount > 99) "99+" else unreadCount.toString()
}

private fun deleteIndicatorColor(
    isDeleteMode: Boolean,
    isSelected: Boolean,
): String? {
    if (!isDeleteMode) {
        return null
    }
    return if (isSelected) "#e74c3c" else "#ddd"
}

@Composable
private fun GroupCardUnreadBadge(visualState: GroupCardVisualState) {
    val unreadText = visualState.unreadBadgeText ?: return

    Span({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            justifyContent(JustifyContent.Center)
            width(24.px)
            height(24.px)
            backgroundColor(Color("#FF5722"))
            borderRadius(50.percent)
            color(Color.white)
            fontSize(10.px)
            property("font-weight", "bold")
        }
    }) {
        Text(unreadText)
    }
}

@Composable
private fun GroupCardActions(
    visualState: GroupCardVisualState,
    newMessageLabel: String,
    onMembersClick: (() -> Unit)?,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "8px")
        }
    }) {
        if (visualState.showUnreadLabel) {
            Span({
                style {
                    fontSize(11.px)
                    color(Color("#FF5722"))
                    property("font-weight", "bold")
                }
            }) {
                Text(newMessageLabel)
            }
        }

        // cc-connect 工作目录（第二行）
        val cwdText = ccConnectInfo?.cwd
        if (isCcConnect && ccConnected && !cwdText.isNullOrBlank()) {
            Div({
                style {
                    marginTop(4.px)
                    property("padding-left", if (hasUnread && !isDeleteMode) "34px" else "0px")
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "4px")
                }
            }) {
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textSecondary))
                        property("opacity", "0.7")
                    }
                }) {
                    Text("📁")
                }
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textSecondary))
                        property("font-family", "monospace")
                        property("overflow", "hidden")
                        property("text-overflow", "ellipsis")
                        property("white-space", "nowrap")
                    }
                    title(cwdText)
                }) {
                    Text(cwdText)
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
fun CreateGroupDialog(
    appState: WebAppState,
    strings: Strings,
    onDismiss: () -> Unit,
    onGroupCreated: (Group) -> Unit,
    onComplete: () -> Unit = onDismiss,
) {
    val scope = rememberCoroutineScope()
    var groupName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isCcConnect by remember { mutableStateOf(false) }
    var generatedToken by remember { mutableStateOf<String?>(null) }
    var tokenCopied by remember { mutableStateOf(false) }
    
    val userName = appState.currentUser?.fullName ?: ""
    val previewName = if (groupName.isNotBlank()) "$userName's $groupName" else ""
    
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.vh)
            backgroundColor(Color("rgba(74, 64, 56, 0.5)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
            property("backdrop-filter", "blur(4px)")
        }
        onClick { if (generatedToken == null) onDismiss() }
    }) {
        Div({
            style {
                backgroundColor(Color(SilkColors.surfaceElevated))
                borderRadius(16.px)
                padding(28.px)
                width(420.px)
                maxWidth(90.vw)
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.2)")
                property("border", "1px solid ${SilkColors.border}")
            }
            onClick { it.stopPropagation() }
        }) {
            if (generatedToken != null) {
                // Token display phase
                H3({ 
                    style { 
                        marginTop(0.px); marginBottom(16.px)
                        color(Color(SilkColors.textPrimary))
                        property("font-weight", "600")
                    } 
                }) { Text("cc-connect Token") }

                Div({ style {
                    padding(16.px); borderRadius(8.px)
                    property("background", SilkColors.surface)
                    property("border", "1px solid ${SilkColors.border}")
                    marginBottom(16.px)
                    property("word-break", "break-all")
                    fontFamily("monospace"); fontSize(14.px)
                    color(Color(SilkColors.textPrimary))
                } }) { Text(generatedToken!!) }

                Div({ style {
                    fontSize(13.px); color(Color(SilkColors.textSecondary))
                    marginBottom(16.px); property("line-height", "1.6")
                } }) {
                    Text("Paste this token into cc-connect's config.toml:")
                    Div({ style {
                        fontSize(12.px); padding(12.px); borderRadius(6.px)
                        property("background", SilkColors.surface)
                        property("border", "1px solid ${SilkColors.border}")
                        property("overflow-x", "auto")
                        color(Color(SilkColors.textPrimary))
                        fontFamily("monospace")
                        property("white-space", "pre")
                        marginTop(8.px)
                    } }) {
                        Text("""[[projects.platforms]]
type = "silk"
[projects.platforms.options]
server = "wss://your-server:15003/ccconnect-bridge"
token  = "${generatedToken}"
""")
                    }
                }

                Div({ style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.FlexEnd)
                    property("gap", "12px")
                } }) {
                    Button({
                        style {
                            padding(12.px, 20.px)
                            backgroundColor(Color(if (tokenCopied) "#4CAF50" else SilkColors.secondary))
                            color(Color(if (tokenCopied) "white" else SilkColors.textPrimary))
                            border { width(0.px) }; borderRadius(8.px)
                            property("cursor", "pointer"); fontSize(14.px)
                        }
                        onClick {
                            kotlinx.browser.window.navigator.clipboard.writeText(generatedToken!!)
                            tokenCopied = true
                        }
                    }) { Text(if (tokenCopied) "Copied!" else "Copy Token") }
                    Button({
                        style {
                            padding(12.px, 20.px)
                            property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                            color(Color.white); border { width(0.px) }; borderRadius(8.px)
                            property("cursor", "pointer"); fontSize(14.px); property("font-weight", "600")
                        }
                        onClick { onComplete() }
                    }) { Text("Done") }
                }
            } else {
                // Creation form phase
                H3({ 
                    style { 
                        marginTop(0.px); marginBottom(24.px)
                        color(Color(SilkColors.textPrimary))
                        property("font-weight", "600")
                        property("letter-spacing", "1px")
                    } 
                }) { Text(strings.createGroupTitle) }

                // Group type selector
                Div({ style { marginBottom(20.px) } }) {
                    Span({ style { fontSize(13.px); color(Color(SilkColors.textSecondary)) } }) {
                        Text("Type")
                    }
                    Div({ style { display(DisplayStyle.Flex); property("gap", "8px"); marginTop(8.px) } }) {
                        Button({
                            style {
                                padding(8.px, 16.px); borderRadius(6.px); fontSize(13.px)
                                property("cursor", "pointer")
                                if (!isCcConnect) {
                                    property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                                    color(Color.white); border { width(0.px) }
                                } else {
                                    backgroundColor(Color(SilkColors.surface))
                                    color(Color(SilkColors.textPrimary))
                                    property("border", "1px solid ${SilkColors.border}")
                                }
                            }
                            onClick { isCcConnect = false }
                        }) { Text("Normal") }
                        Button({
                            style {
                                padding(8.px, 16.px); borderRadius(6.px); fontSize(13.px)
                                property("cursor", "pointer")
                                if (isCcConnect) {
                                    property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                                    color(Color.white); border { width(0.px) }
                                } else {
                                    backgroundColor(Color(SilkColors.surface))
                                    color(Color(SilkColors.textPrimary))
                                    property("border", "1px solid ${SilkColors.border}")
                                }
                            }
                            onClick { isCcConnect = true }
                        }) { Text("cc-connect") }
                    }
                }

                Div({ style { marginBottom(20.px) } }) {
                    Label { 
                        Span({ style { fontSize(13.px); color(Color(SilkColors.textSecondary)); property("letter-spacing", "0.5px") } }) {
                            Text(strings.groupName)
                        }
                    }
                    Input(InputType.Text) {
                        value(groupName)
                        onInput { groupName = it.value; errorMessage = "" }
                        style {
                            width(100.percent); padding(14.px); fontSize(14.px); marginTop(8.px)
                            border { width(1.px); style(LineStyle.Solid); color(Color(SilkColors.border)) }
                            borderRadius(8.px)
                            property("box-sizing", "border-box")
                            property("background", SilkColors.surface)
                            property("color", SilkColors.textPrimary)
                            fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                        }
                    }
                }
                
                if (previewName.isNotEmpty() && !isCcConnect) {
                    Div({ style { fontSize(13.px); color(Color(SilkColors.textSecondary)); marginBottom(20.px); property("font-style", "italic") } }) {
                        Text("${strings.fullName}: $previewName")
                    }
                }

                if (isCcConnect) {
                    Div({ style {
                        fontSize(12.px); color(Color(SilkColors.textSecondary))
                        marginBottom(16.px); padding(12.px); borderRadius(8.px)
                        property("background", SilkColors.surface)
                        property("border", "1px solid ${SilkColors.border}")
                        property("line-height", "1.5")
                    } }) {
                        Text("A token will be generated for connecting cc-connect to this group. Paste it into your cc-connect config.toml.")
                    }
                }
                
                if (errorMessage.isNotEmpty()) {
                    Div({ style { 
                        color(Color(SilkColors.error)); fontSize(13.px); marginBottom(20.px)
                        padding(12.px); backgroundColor(Color("#FDF5F5")); borderRadius(8.px)
                    } }) { Text(errorMessage) }
                }
                
                Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd); property("gap", "12px") } }) {
                    Button({
                        style {
                            padding(12.px, 20.px); backgroundColor(Color(SilkColors.secondary))
                            color(Color(SilkColors.textPrimary)); border { width(0.px) }; borderRadius(8.px)
                            property("cursor", "pointer"); fontSize(14.px); property("font-weight", "500")
                        }
                        onClick { onDismiss() }
                    }) { Text(strings.cancelButton) }
                    
                    Button({
                        style {
                            padding(12.px, 20.px)
                            property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                            color(Color.white); border { width(0.px) }; borderRadius(8.px)
                            property("cursor", if (isLoading || groupName.isBlank()) "not-allowed" else "pointer")
                            property("opacity", if (isLoading || groupName.isBlank()) "0.6" else "1")
                            fontSize(14.px); property("font-weight", "600")
                            property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.25)")
                        }
                        onClick {
                            if (!isLoading && groupName.isNotBlank()) {
                                scope.launch {
                                    isLoading = true
                                    try {
                                        val type = if (isCcConnect) "ccconnect" else null
                                        val response = appState.currentUser?.let { user ->
                                            ApiClient.createGroup(user.id, groupName, type)
                                        }
                                        
                                        if (response != null && response.success && response.group != null) {
                                            onGroupCreated(response.group)
                                            if (isCcConnect && response.ccConnectToken != null) {
                                                generatedToken = response.ccConnectToken
                                            } else {
                                                onComplete()
                                            }
                                        } else {
                                            errorMessage = response?.message ?: "创建失败"
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = "创建失败: ${e.message}"
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        }
                    }) { Text(if (isLoading) strings.creating else strings.createButton) }
                }
            }
        }

        GroupDialogErrorMessage(errorMessage)

        GroupDialogActions(
            cancelLabel = strings.cancelButton,
            confirmLabel = if (isLoading) strings.creating else strings.createButton,
            isConfirmEnabled = !isLoading && groupName.isNotBlank(),
            onDismiss = onDismiss,
            onConfirm = {
                createGroupFromDialog(
                    scope = scope,
                    appState = appState,
                    groupName = groupName,
                    setLoading = { isLoading = it },
                    setError = { errorMessage = it },
                    onGroupCreated = onGroupCreated,
                )
            },
        )
    }
}

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
fun JoinGroupDialog(
    appState: WebAppState,
    strings: Strings,
    onDismiss: () -> Unit,
    onGroupJoined: (Group) -> Unit
) {
    val scope = rememberCoroutineScope()
    var invitationCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    GroupDialogCard(title = strings.joinGroupTitle, onDismiss = onDismiss) {
        GroupDialogTextField(
            label = strings.invitationCode,
            value = invitationCode,
            placeholder = strings.invitationCodePlaceholder,
            fontSizePx = 16,
            maxLength = 6,
            isCentered = true,
            letterSpacingPx = 4,
            textTransform = "uppercase",
            onValueChange = { value ->
                invitationCode = value.uppercase().take(6)
                errorMessage = ""
            },
        )

        GroupDialogErrorMessage(errorMessage)

        GroupDialogActions(
            cancelLabel = strings.cancelButton,
            confirmLabel = if (isLoading) strings.joining else strings.joinButton,
            isConfirmEnabled = !isLoading && invitationCode.length == 6,
            onDismiss = onDismiss,
            onConfirm = {
                joinGroupFromDialog(
                    scope = scope,
                    appState = appState,
                    invitationCode = invitationCode,
                    setLoading = { isLoading = it },
                    setError = { errorMessage = it },
                    onGroupJoined = onGroupJoined,
                )
            },
        )
    }
}

/**
 * 群组成员列表对话框（在群列表页面使用）
 */
@Suppress("CyclomaticComplexMethod")
@Composable
fun GroupMembersListDialog(
    group: Group,
    members: List<GroupMember>,
    contacts: List<Contact>,
    currentUserId: String,
    isLoading: Boolean,
    strings: Strings,
    onMemberClick: (GroupMember) -> Unit,
    onDismiss: () -> Unit
) {
    val contactIds = contacts.map { it.contactId }.toSet()

    GroupMembersDialogCard(
        title = "👥 ${strings.groupMembersTitle} ${group.name}",
        closeLabel = strings.closeButton,
        onDismiss = onDismiss,
    ) {
        GroupMembersDialogContent(
            members = members,
            groupHostId = group.hostId,
            currentUserId = currentUserId,
            contactIds = contactIds,
            isLoading = isLoading,
            noMembersLabel = strings.noMembers,
            onMemberClick = onMemberClick,
            hostLabel = strings.host,
            currentUserLabel = strings.me,
        )
    }
}

@Composable
private fun GroupDialogCard(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.vh)
            backgroundColor(Color("rgba(74, 64, 56, 0.5)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
            property("backdrop-filter", "blur(4px)")
        }
        onClick { onDismiss() }
    }) {
        Div({
            style {
                backgroundColor(Color(SilkColors.surfaceElevated))
                borderRadius(16.px)
                padding(28.px)
                width(420.px)
                maxWidth(90.vw)
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.2)")
                property("border", "1px solid ${SilkColors.border}")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({
                style {
                    marginTop(0.px)
                    marginBottom(24.px)
                    color(Color(SilkColors.textPrimary))
                    property("font-weight", "600")
                    property("letter-spacing", "1px")
                }
            }) {
                Text(title)
            }

            content()
        }
    }
}

@Composable
private fun GroupDialogTextField(
    label: String,
    value: String,
    placeholder: String?,
    fontSizePx: Int,
    onValueChange: (String) -> Unit,
    maxLength: Int? = null,
    isCentered: Boolean = false,
    letterSpacingPx: Int? = null,
    textTransform: String? = null,
) {
    Div({ style { marginBottom(20.px) } }) {
        Label {
            Span({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    property("letter-spacing", "0.5px")
                }
            }) {
                Text(label)
            }
        }
        Input(InputType.Text) {
            value(value)
            onInput { onValueChange(it.value) }
            style {
                width(100.percent)
                padding(14.px)
                fontSize(fontSizePx.px)
                marginTop(8.px)
                border {
                    width(1.px)
                    style(LineStyle.Solid)
                    color(Color(SilkColors.border))
                }
                borderRadius(8.px)
                property("box-sizing", "border-box")
                property("background", SilkColors.surface)
                property("color", SilkColors.textPrimary)
                fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                if (isCentered) {
                    property("text-align", "center")
                }
                letterSpacingPx?.let { property("letter-spacing", "${it}px") }
                textTransform?.let { property("text-transform", it) }
            }
            placeholder?.let { attr("placeholder", it) }
            maxLength?.let { attr("maxlength", it.toString()) }
        }
    }
}

@Composable
private fun GroupDialogPreview(label: String, value: String) {
    Div({
        style {
            fontSize(13.px)
            color(Color(SilkColors.textSecondary))
            marginBottom(20.px)
            property("font-style", "italic")
        }
    }) {
        Text("$label: $value")
    }
}

@Composable
private fun GroupDialogErrorMessage(message: String) {
    if (message.isEmpty()) {
        return
    }

    Div({
        style {
            color(Color(SilkColors.error))
            fontSize(13.px)
            marginBottom(20.px)
            padding(12.px)
            backgroundColor(Color("#FDF5F5"))
            borderRadius(8.px)
        }
    }) {
        Text(message)
    }
}

@Composable
private fun GroupDialogActions(
    cancelLabel: String,
    confirmLabel: String,
    isConfirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val confirmCursor = if (isConfirmEnabled) "pointer" else "not-allowed"
    val confirmOpacity = if (isConfirmEnabled) "1" else "0.6"

    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.FlexEnd)
            property("gap", "12px")
        }
    }) {
        Button({
            style {
                padding(12.px, 20.px)
                backgroundColor(Color(SilkColors.secondary))
                color(Color(SilkColors.textPrimary))
                border { width(0.px) }
                borderRadius(8.px)
                property("cursor", "pointer")
                fontSize(14.px)
                property("font-weight", "500")
            }
            onClick { onDismiss() }
        }) {
            Text(cancelLabel)
        }

        Button({
            style {
                padding(12.px, 20.px)
                property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                color(Color.white)
                border { width(0.px) }
                borderRadius(8.px)
                property("cursor", confirmCursor)
                property("opacity", confirmOpacity)
                fontSize(14.px)
                property("font-weight", "600")
                property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.25)")
            }
            onClick {
                if (isConfirmEnabled) {
                    onConfirm()
                }
            }
        }) {
            Text(confirmLabel)
        }
    }
}

private fun createGroupFromDialog(
    scope: kotlinx.coroutines.CoroutineScope,
    appState: WebAppState,
    groupName: String,
    setLoading: (Boolean) -> Unit,
    setError: (String) -> Unit,
    onGroupCreated: (Group) -> Unit,
) {
    scope.launch {
        setLoading(true)
        try {
            recoverSuspendNonCancellation(
                block = {
                    val response = appState.currentUser?.let { user ->
                        ApiClient.createGroup(user.id, groupName)
                    }

                    if (response != null && response.success && response.group != null) {
                        console.log("群组创建成功:", response.group.name)
                        onGroupCreated(response.group)
                    } else {
                        setError(response?.message ?: "创建失败")
                    }
                },
                recover = { error ->
                    setError("创建失败: ${error.message}")
                },
            )
        } finally {
            setLoading(false)
        }
    }
}

private fun joinGroupFromDialog(
    scope: kotlinx.coroutines.CoroutineScope,
    appState: WebAppState,
    invitationCode: String,
    setLoading: (Boolean) -> Unit,
    setError: (String) -> Unit,
    onGroupJoined: (Group) -> Unit,
) {
    scope.launch {
        setLoading(true)
        try {
            recoverSuspendNonCancellation(
                block = {
                    val response = appState.currentUser?.let { user ->
                        ApiClient.joinGroup(user.id, invitationCode)
                    }

                    if (response != null && response.success && response.group != null) {
                        console.log("加入群组成功:", response.group.name)
                        onGroupJoined(response.group)
                    } else {
                        setError(response?.message ?: "加入失败")
                    }
                },
                recover = { error ->
                    setError("加入失败: ${error.message}")
                },
            )
        } finally {
            setLoading(false)
        }
    }
}

@Composable
private fun GroupMembersDialogCard(
    title: String,
    closeLabel: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.vh)
            backgroundColor(Color("rgba(74, 64, 56, 0.5)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
            property("backdrop-filter", "blur(4px)")
        }
        onClick { onDismiss() }
    }) {
        Div({
            style {
                backgroundColor(Color(SilkColors.surfaceElevated))
                borderRadius(12.px)
                padding(20.px)
                width(400.px)
                maxWidth(90.vw)
                maxHeight(70.vh)
                property("overflow-y", "auto")
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.15)")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({
                style {
                    margin(0.px, 0.px, 16.px, 0.px)
                    color(Color(SilkColors.textPrimary))
                    fontSize(16.px)
                    property("font-weight", "600")
                }
            }) {
                Text(title)
            }

            content()

            Button({
                style {
                    width(100.percent)
                    marginTop(16.px)
                    backgroundColor(Color(SilkColors.textSecondary))
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(8.px)
                    padding(10.px)
                    property("cursor", "pointer")
                    fontSize(13.px)
                    property("font-weight", "500")
                }
                onClick { onDismiss() }
            }) {
                Text(closeLabel)
            }
        }
    }
}

@Composable
private fun GroupMembersDialogContent(
    members: List<GroupMember>,
    groupHostId: String,
    currentUserId: String,
    contactIds: Set<String>,
    isLoading: Boolean,
    noMembersLabel: String,
    onMemberClick: (GroupMember) -> Unit,
    hostLabel: String,
    currentUserLabel: String,
) {
    when {
        isLoading -> GroupMembersDialogMessage("加载中...")
        members.isEmpty() -> GroupMembersDialogMessage(noMembersLabel)
        else -> {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "8px")
                }
            }) {
                members.forEach { member ->
                    GroupMemberRow(
                        member = member,
                        displayState = buildGroupMemberDisplayState(
                            member = member,
                            groupHostId = groupHostId,
                            currentUserId = currentUserId,
                            contactIds = contactIds,
                        ),
                        hostLabel = hostLabel,
                        currentUserLabel = currentUserLabel,
                        onMemberClick = onMemberClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupMembersDialogMessage(message: String) {
    Div({
        style {
            property("text-align", "center")
            padding(20.px)
            color(Color(SilkColors.textSecondary))
        }
    }) {
        Text(message)
    }
}

private data class GroupMemberDisplayState(
    val isHost: Boolean,
    val isCurrentUser: Boolean,
    val isContact: Boolean,
    val isSilkAI: Boolean,
    val avatarColor: String,
    val avatarText: String,
    val statusText: String,
    val actionHint: String?,
)

private enum class GroupMemberAvatarKind {
    AI,
    HOST,
    CONTACT,
    DEFAULT,
}

private fun buildGroupMemberDisplayState(
    member: GroupMember,
    groupHostId: String,
    currentUserId: String,
    contactIds: Set<String>,
): GroupMemberDisplayState {
    val isHost = member.id == groupHostId
    val isCurrentUser = member.id == currentUserId
    val isContact = member.id in contactIds
    val isSilkAI = isAgentUserId(member.id)
    val avatarKind = resolveGroupMemberAvatarKind(
        isSilkAI = isSilkAI,
        isHost = isHost,
        isContact = isContact,
    )

    return GroupMemberDisplayState(
        isHost = isHost,
        isCurrentUser = isCurrentUser,
        isContact = isContact,
        isSilkAI = isSilkAI,
        avatarColor = avatarColorFor(avatarKind),
        avatarText = avatarTextFor(avatarKind, member.fullName),
        statusText = memberStatusText(
            isSilkAI = isSilkAI,
            isCurrentUser = isCurrentUser,
            isContact = isContact,
        ),
        actionHint = memberActionHint(
            isCurrentUser = isCurrentUser,
            isSilkAI = isSilkAI,
            isContact = isContact,
        ),
    )
}

private fun resolveGroupMemberAvatarKind(
    isSilkAI: Boolean,
    isHost: Boolean,
    isContact: Boolean,
): GroupMemberAvatarKind = when {
    isSilkAI -> GroupMemberAvatarKind.AI
    isHost -> GroupMemberAvatarKind.HOST
    isContact -> GroupMemberAvatarKind.CONTACT
    else -> GroupMemberAvatarKind.DEFAULT
}

private fun avatarColorFor(kind: GroupMemberAvatarKind): String = when (kind) {
    GroupMemberAvatarKind.AI -> SilkColors.info
    GroupMemberAvatarKind.HOST -> SilkColors.primary
    GroupMemberAvatarKind.CONTACT -> SilkColors.success
    GroupMemberAvatarKind.DEFAULT -> SilkColors.textSecondary
}

private fun avatarTextFor(kind: GroupMemberAvatarKind, fullName: String): String = when (kind) {
    GroupMemberAvatarKind.AI -> "🤖"
    GroupMemberAvatarKind.HOST -> "👑"
    GroupMemberAvatarKind.CONTACT -> "✓"
    GroupMemberAvatarKind.DEFAULT -> fullName.firstOrNull()?.toString() ?: "?"
}

private fun memberStatusText(
    isSilkAI: Boolean,
    isCurrentUser: Boolean,
    isContact: Boolean,
): String = when {
    isSilkAI -> "AI 助手"
    isCurrentUser -> "当前用户"
    isContact -> "联系人 · 点击聊天"
    else -> "点击添加联系人"
}

private fun memberActionHint(
    isCurrentUser: Boolean,
    isSilkAI: Boolean,
    isContact: Boolean,
): String? = when {
    isCurrentUser || isSilkAI -> null
    isContact -> "💬"
    else -> "➕"
}

@Composable
private fun GroupMemberRow(
    member: GroupMember,
    displayState: GroupMemberDisplayState,
    hostLabel: String,
    currentUserLabel: String,
    onMemberClick: (GroupMember) -> Unit,
) {
    val isClickable = displayState.actionHint != null
    val background = if (displayState.isHost) "rgba(201, 168, 108, 0.1)" else SilkColors.surface
    val fontWeight = if (displayState.isHost) "600" else "400"

    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            padding(10.px, 12.px)
            backgroundColor(Color(background))
            borderRadius(8.px)
            if (isClickable) {
                property("cursor", "pointer")
            }
        }
        if (isClickable) {
            onClick { onMemberClick(member) }
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "10px")
            }
        }) {
            Div({
                style {
                    width(32.px)
                    height(32.px)
                    borderRadius(6.px)
                    backgroundColor(Color(displayState.avatarColor))
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.Center)
                    alignItems(AlignItems.Center)
                    color(Color.white)
                    fontSize(14.px)
                }
            }) {
                Text(displayState.avatarText)
            }

            Div {
                Div({
                    style {
                        fontSize(13.px)
                        color(Color(SilkColors.textPrimary))
                        property("font-weight", fontWeight)
                    }
                }) {
                    Text(member.fullName)
                    GroupMemberBadge(isVisible = displayState.isHost, text = hostLabel, color = SilkColors.primary)
                    GroupMemberBadge(
                        isVisible = displayState.isCurrentUser,
                        text = currentUserLabel,
                        color = SilkColors.textSecondary,
                    )
                }
                Div({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textSecondary))
                        marginTop(2.px)
                    }
                }) {
                    Text(displayState.statusText)
                }
            }
        }

        displayState.actionHint?.let { hint ->
            Div({
                style {
                    fontSize(16.px)
                }
            }) {
                Text(hint)
            }
        }
    }
}

@Composable
private fun GroupMemberBadge(
    isVisible: Boolean,
    text: String,
    color: String,
) {
    if (!isVisible) {
        return
    }

    Span({
        style {
            fontSize(11.px)
            color(Color(color))
            marginLeft(4.px)
        }
    }) {
        Text(text)
    }
}
