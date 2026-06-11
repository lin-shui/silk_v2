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

@Composable
fun GroupListScene(appState: WebAppState) {
    val scope = rememberCoroutineScope()
    
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    
    // 删除模式相关状态
    var isDeleteMode by remember { mutableStateOf(false) }
    var selectedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isDeleting by remember { mutableStateOf(false) }
    
    // 下拉菜单状态
    var showMenu by remember { mutableStateOf(false) }
    
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
            height(100.vh)
            width(100.vw)
            property("overflow", "hidden")
            property("background", SilkColors.backgroundGradient)
            fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
        }
    }) {
        // 顶部导航 - 丝滑金色渐变
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
                    property("flex", "0 0 35%")
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
            
            // 右侧下拉菜单
            Div({
                style {
                    position(Position.Relative)
                }
            }) {
                // 菜单触发按钮
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
                }) {
                    Text("☰")
                }
                
                // 下拉菜单
                if (showMenu) {
                    // 点击遮罩关闭
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
                        // 菜单项列表
                        if (isDeleteMode) {
                            Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { isDeleteMode = false; selectedGroups = emptySet(); showMenu = false } }) { Text("🔙  ${strings.cancelButton}") }
                            if (selectedGroups.isNotEmpty()) {
                                Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color("#e74c3c")); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { scope.launch { isDeleting = true; val userId = appState.currentUser?.id ?: ""; selectedGroups.forEach { groupId -> val group = groups.find { it.id == groupId }; if (group != null && group.hostId == userId) ApiClient.deleteGroup(groupId, userId) else ApiClient.leaveGroup(groupId, userId) }; val r2 = ApiClient.getUserGroups(userId); if (r2.success) groups = r2.groups ?: emptyList(); isDeleting = false; isDeleteMode = false; selectedGroups = emptySet(); showMenu = false } } }) { Text("🗑  ${strings.exitButton} (${selectedGroups.size})") }
                            }
                        } else {
                            Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { isDeleteMode = true; showMenu = false } }) { Text("➖  ${strings.exitButton}") }
                            Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { showCreateDialog = true; showMenu = false } }) { Text("➕  ${strings.createButton}") }
                        }
                        Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { showJoinDialog = true; showMenu = false } }) { Text("🔗  ${strings.joinButton}") }
                        Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { appState.navigateTo(Scene.CONTACTS); showMenu = false } }) { Text("👤  ${strings.contactsButton}") }
                        Div({ style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }; onClick { showMenu = false; scope.launch { val uid = appState.currentUser?.id ?: return@launch; val r = ApiClient.startSilkPrivateChat(uid); if (r.success && r.group != null) appState.selectGroup(r.group!!) } } }) { Text("🤖  ${strings.chatWithSilk}") }
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
                            onMembersClick = {
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
                            }
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
                onClick { showCreateDialog = true }
            }) {
                Text("+")
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
        
        if (showJoinDialog) {
            JoinGroupDialog(
                appState = appState,
                strings = strings,
                onDismiss = { showJoinDialog = false },
                onGroupJoined = { newGroup ->
                    groups = groups + newGroup
                    showJoinDialog = false
                }
            )
        }
        
        // 成员列表对话框
        if (showMembersDialog && selectedGroupForMembers != null) {
            GroupMembersListDialog(
                group = selectedGroupForMembers!!,
                members = groupMembers,
                contacts = contacts,
                currentUserId = appState.currentUser?.id ?: "",
                isLoading = isLoadingMembers,
                strings = strings,
                onMemberClick = { member ->
                    // 检查是否是联系人
                    val isContact = contacts.any { it.contactId == member.id }
                    if (isContact) {
                        // 是联系人，跳转到与该联系人的对话
                        scope.launch {
                            showMembersDialog = false
                            val userId = appState.currentUser?.id ?: return@launch
                            val response = ApiClient.startPrivateChat(userId, member.id)
                            if (response.success && response.group != null) {
                                appState.selectGroup(response.group!!)
                            } else {
                                console.log("无法创建对话: ${response.message}")
                            }
                        }
                    } else {
                        // 不是联系人，发送联系人请求
                        scope.launch {
                            val userId = appState.currentUser?.id ?: return@launch
                            val response = ApiClient.sendContactRequestById(userId, member.id)
                            if (response.success) {
                                console.log("联系人请求已发送给 ${member.fullName}")
                            } else {
                                console.log("发送失败: ${response.message}")
                            }
                        }
                    }
                },
                onDismiss = { 
                    showMembersDialog = false
                    selectedGroupForMembers = null
                }
            )
        }
    }
}

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
        style {
            backgroundColor(
                when {
                    isSelected -> Color("#FFEBEE")
                    hasUnread -> Color("#FFF8E1")  // 淡黄色背景表示有未读
                    else -> Color(SilkColors.surfaceElevated)
                }
            )
            borderRadius(8.px)
            padding(10.px, 14.px)
            marginBottom(8.px)
            property("box-shadow", 
                if (hasUnread) "0 2px 8px rgba(255, 152, 0, 0.3)" else "0 1px 4px rgba(169, 137, 77, 0.06)"
            )
            property("cursor", "pointer")
            property("transition", "all 0.2s ease")
            property("border", 
                when {
                    isSelected -> "2px solid #e74c3c"
                    hasUnread -> "2px solid #FF9800"  // 橙色边框
                    else -> "1px solid ${SilkColors.border}"
                }
            )
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
                // ✅ 未读指示器（红点 + 数字）
                if (hasUnread && !isDeleteMode) {
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
                        Text(if (unreadCount > 99) "99+" else unreadCount.toString())
                    }
                }
                
                // 群名
                Span({
                    style {
                        fontSize(14.px)
                        property("font-weight", if (hasUnread) "700" else "600")
                        color(Color(if (hasUnread) "#E65100" else SilkColors.textPrimary))
                    }
                }) {
                    Text(group.name)
                }
                
                // 邀请码（小字体）
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textSecondary))
                        property("letter-spacing", "1px")
                    }
                }) {
                    Text("[${group.invitationCode}]")
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
            
            // 右侧按钮区域
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "8px")
                }
            }) {
                // ✅ 未读提示文字
                if (hasUnread && !isDeleteMode) {
                    Span({
                        style {
                            fontSize(11.px)
                            color(Color("#FF5722"))
                            property("font-weight", "bold")
                        }
                    }) {
                        Text(strings.newMessage)
                    }
                }
                
                // 成员按钮（非删除模式下显示）
                if (!isDeleteMode && onMembersClick != null) {
                    Button({
                        style {
                            padding(4.px, 10.px)
                            backgroundColor(Color(SilkColors.secondary))
                            color(Color(SilkColors.textPrimary))
                            border { width(0.px) }
                            borderRadius(4.px)
                            property("cursor", "pointer")
                            fontSize(12.px)
                        }
                        onClick { 
                            it.stopPropagation()
                            onMembersClick() 
                        }
                    }) {
                        Text("👥")
                    }
                }
                
                // 删除模式下显示选择指示器
                if (isDeleteMode) {
                    Div({
                        style {
                            width(22.px)
                            height(22.px)
                            borderRadius(50.percent)
                            backgroundColor(if (isSelected) Color("#e74c3c") else Color("#ddd"))
                            display(DisplayStyle.Flex)
                            justifyContent(JustifyContent.Center)
                            alignItems(AlignItems.Center)
                            color(Color.white)
                            fontSize(14.px)
                            property("font-weight", "bold")
                        }
                    }) {
                        if (isSelected) {
                            Text("✓")
                        }
                    }
                }
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
    }
}

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
                Text(strings.joinGroupTitle)
            }
            
            Div({ style { marginBottom(20.px) } }) {
                Label { 
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color(SilkColors.textSecondary))
                            property("letter-spacing", "0.5px")
                        }
                    }) {
                        Text(strings.invitationCode)
                    }
                }
                Input(InputType.Text) {
                    value(invitationCode)
                    onInput { 
                        invitationCode = it.value.uppercase().take(6)
                        errorMessage = ""
                    }
                    style {
                        width(100.percent)
                        padding(14.px)
                        fontSize(16.px)
                        marginTop(8.px)
                        border { 
                            width(1.px)
                            style(LineStyle.Solid)
                            color(Color(SilkColors.border)) 
                        }
                        borderRadius(8.px)
                        property("box-sizing", "border-box")
                        property("text-transform", "uppercase")
                        property("letter-spacing", "4px")
                        property("text-align", "center")
                        property("background", SilkColors.surface)
                        property("color", SilkColors.textPrimary)
                        fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                    }
                    attr("placeholder", strings.invitationCodePlaceholder)
                    attr("maxlength", "6")
                }
            }
            
            if (errorMessage.isNotEmpty()) {
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
                    Text(errorMessage)
                }
            }
            
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
                    Text(strings.cancelButton)
                }
                
                Button({
                    style {
                        padding(12.px, 20.px)
                        property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", if (isLoading || invitationCode.length != 6) "not-allowed" else "pointer")
                        property("opacity", if (isLoading || invitationCode.length != 6) "0.6" else "1")
                        fontSize(14.px)
                        property("font-weight", "600")
                        property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.25)")
                    }
                    onClick {
                        if (!isLoading && invitationCode.length == 6) {
                            scope.launch {
                                isLoading = true
                                try {
                                    val response = appState.currentUser?.let { user ->
                                        ApiClient.joinGroup(user.id, invitationCode)
                                    }
                                    
                                    if (response != null && response.success && response.group != null) {
                                        console.log("加入群组成功:", response.group.name)
                                        onGroupJoined(response.group)
                                    } else {
                                        errorMessage = response?.message ?: "加入失败"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "加入失败: ${e.message}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    }
                }) {
                    Text(if (isLoading) strings.joining else strings.joinButton)
                }
            }
        }
    }
}

/**
 * 群组成员列表对话框（在群列表页面使用）
 */
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
            // 标题
            H3({
                style {
                    margin(0.px, 0.px, 16.px, 0.px)
                    color(Color(SilkColors.textPrimary))
                    fontSize(16.px)
                    property("font-weight", "600")
                }
            }) {
                Text("👥 ${strings.groupMembersTitle} ${group.name}")
            }
            
            if (isLoading) {
                Div({
                    style {
                        property("text-align", "center")
                        padding(20.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text("加载中...")
                }
            } else if (members.isEmpty()) {
                Div({
                    style {
                        property("text-align", "center")
                        padding(20.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text(strings.noMembers)
                }
            } else {
                // 成员列表
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "8px")
                    }
                }) {
                    members.forEach { member ->
                        val isHost = member.id == group.hostId
                        val isCurrentUser = member.id == currentUserId
                        val isContact = member.id in contactIds
                        val isSilkAI = isAgentUserId(member.id)
                        
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.SpaceBetween)
                                alignItems(AlignItems.Center)
                                padding(10.px, 12.px)
                                backgroundColor(
                                    if (isHost) Color("rgba(201, 168, 108, 0.1)") 
                                    else Color(SilkColors.surface)
                                )
                                borderRadius(8.px)
                                if (!isCurrentUser && !isSilkAI) {
                                    property("cursor", "pointer")
                                }
                            }
                            if (!isCurrentUser && !isSilkAI) {
                                onClick { onMemberClick(member) }
                            }
                        }) {
                            // 左侧：头像 + 信息
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "10px")
                                }
                            }) {
                                // 头像
                                Div({
                                    style {
                                        width(32.px)
                                        height(32.px)
                                        borderRadius(6.px)
                                        backgroundColor(
                                            when {
                                                isSilkAI -> Color(SilkColors.info)
                                                isHost -> Color(SilkColors.primary)
                                                isContact -> Color(SilkColors.success)
                                                else -> Color(SilkColors.textSecondary)
                                            }
                                        )
                                        display(DisplayStyle.Flex)
                                        justifyContent(JustifyContent.Center)
                                        alignItems(AlignItems.Center)
                                        color(Color.white)
                                        fontSize(14.px)
                                    }
                                }) {
                                    Text(
                                        when {
                                            isSilkAI -> "🤖"
                                            isHost -> "👑"
                                            isContact -> "✓"
                                            else -> member.fullName.firstOrNull()?.toString() ?: "?"
                                        }
                                    )
                                }
                                
                                // 名字和状态
                                Div {
                                    Div({
                                        style {
                                            fontSize(13.px)
                                            color(Color(SilkColors.textPrimary))
                                            property("font-weight", if (isHost) "600" else "400")
                                        }
                                    }) {
                                        Text(member.fullName)
                                        if (isHost) {
                                            Span({
                                                style {
                                                    fontSize(11.px)
                                                    color(Color(SilkColors.primary))
                                                    marginLeft(4.px)
                                                }
                                            }) {
                                                Text(strings.host)
                                            }
                                        }
                                        if (isCurrentUser) {
                                            Span({
                                                style {
                                                    fontSize(11.px)
                                                    color(Color(SilkColors.textSecondary))
                                                    marginLeft(4.px)
                                                }
                                            }) {
                                                Text(strings.me)
                                            }
                                        }
                                    }
                                    Div({
                                        style {
                                            fontSize(11.px)
                                            color(Color(SilkColors.textSecondary))
                                            marginTop(2.px)
                                        }
                                    }) {
                                        Text(
                                            when {
                                                isSilkAI -> "AI 助手"
                                                isCurrentUser -> "当前用户"
                                                isContact -> "联系人 · 点击聊天"
                                                else -> "点击添加联系人"
                                            }
                                        )
                                    }
                                }
                            }
                            
                            // 右侧操作提示
                            if (!isCurrentUser && !isSilkAI) {
                                Div({
                                    style {
                                        fontSize(16.px)
                                    }
                                }) {
                                    Text(if (isContact) "💬" else "➕")
                                }
                            }
                        }
                    }
                }
            }
            
            // 关闭按钮
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
                Text(strings.closeButton)
            }
        }
    }
}
