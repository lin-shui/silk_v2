package com.silk.android

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silk.shared.i18n.Strings
import com.silk.shared.i18n.getStrings
import com.silk.shared.models.isAgentUserId
import com.silk.shared.models.Language
import kotlinx.coroutines.launch

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "UnusedPrivateProperty")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(appState: AppState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    
    // 升级相关状态
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf<ApkDownloader.DownloadState>(ApkDownloader.DownloadState.Idle) }
    
    // 删除模式相关状态
    var isDeleteMode by remember { mutableStateOf(false) }
    var selectedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isDeleting by remember { mutableStateOf(false) }
    
    // 顶部栏下拉菜单
    var showTopBarMenu by remember { mutableStateOf(false) }
    
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
    
    // Load user language preference
    LaunchedEffect(appState.currentUser?.id) {
        appState.currentUser?.let { user ->
            scope.launch {
                try {
                    val response = ApiClient.getUserSettings(user.id)
                    if (response.success && response.settings != null) {
                        userLanguage = response.settings!!.language
                    }
                } catch (e: Exception) {
                    println("Failed to load user settings: $e")
                }
            }
        }
    }
    
    val strings = getStrings(userLanguage)
    
    // 加载群组列表和未读数（每次进入 GROUP_LIST 场景时刷新）
    LaunchedEffect(appState.currentScene) {
        if (appState.currentScene == Scene.GROUP_LIST) {
            isLoading = true
            try {
                val response = appState.currentUser?.let { user ->
                    ApiClient.getUserGroups(user.id)
                }

                if (response != null && response.success) {
                    // 过滤掉工作流自动创建的关联群组（命名约定为 wf_ 前缀），
                    // 它们只通过工作流 Tab 访问，不在 Silk 群组列表中显示
                    groups = (response.groups ?: emptyList()).filterNot { it.name.startsWith("wf_") }
                    println("✅ 加载了 ${groups.size} 个群组")

                    // 加载未读消息数
                    appState.currentUser?.let { user ->
                        val unreadResponse = ApiClient.getUnreadCounts(user.id)
                        if (unreadResponse.success) {
                            unreadCounts = unreadResponse.unreadCounts
                            println("✅ 未读消息: $unreadCounts")
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
                }
            } catch (e: Exception) {
                println("❌ 加载群组异常: ${e.message}")
            } finally {
                isLoading = false
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
    
    Scaffold(
        topBar = {
            // Silk 风格顶部导航 - 金色渐变
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    SilkColors.primary,
                                    SilkColors.primaryDark
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
                        Column {
                            Text(
                                text = "SILK",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 4.sp
                                ),
                                color = Color.White
                            )
                            Text(
                                text = appState.currentUser?.fullName ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isDeleteMode) {
                                IconButton(
                                    onClick = { 
                                        isDeleteMode = false
                                        selectedGroups = emptySet()
                                    },
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "取消", modifier = Modifier.size(22.dp))
                                }
                                if (selectedGroups.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            if (!isDeleting) {
                                                scope.launch {
                                                    isDeleting = true
                                                    val userId = appState.currentUser?.id ?: return@launch
                                                    selectedGroups.forEach { groupId ->
                                                        ApiClient.leaveGroup(groupId, userId)
                                                    }
                                                    val r = ApiClient.getUserGroups(userId)
                                                    if (r.success) groups = (r.groups ?: emptyList()).filterNot { it.name.startsWith("wf_") }
                                                    isDeleting = false; isDeleteMode = false; selectedGroups = emptySet()
                                                    Toast.makeText(context, "已退出 ${selectedGroups.size} 个群组", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        colors = IconButtonDefaults.iconButtonColors(
                                            contentColor = if (isDeleting) Color.Gray else Color(0xFFe74c3c)
                                        )
                                    ) {
                                        if (isDeleting) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.Check, contentDescription = "确认退出", modifier = Modifier.size(22.dp))
                                        }
                                    }
                                }
                                Text(text = "已选${selectedGroups.size}个", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                // 🤖 与 Silk 对话按钮
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            val userId = appState.currentUser?.id ?: return@launch
                                            val response = ApiClient.startSilkPrivateChat(userId)
                                            if (response.success && response.group != null) {
                                                appState.selectGroup(response.group!!)
                                            } else {
                                                Toast.makeText(context, response.message, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF7BA8C9))
                                ) {
                                    Icon(Icons.Default.SmartToy, contentDescription = "与 Silk 对话", modifier = Modifier.size(22.dp))
                                }
                                // ☰ 下拉菜单
                                Box {
                                    IconButton(
                                        onClick = { showTopBarMenu = true },
                                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White.copy(alpha = 0.9f))
                                    ) {
                                        Icon(Icons.Default.Menu, contentDescription = "更多", modifier = Modifier.size(22.dp))
                                    }
                                    DropdownMenu(
                                        expanded = showTopBarMenu,
                                        onDismissRequest = { showTopBarMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("退出/删除群组") },
                                            onClick = { showTopBarMenu = false; isDeleteMode = true },
                                            leadingIcon = { Icon(Icons.Default.Remove, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("创建群组") },
                                            onClick = { showTopBarMenu = false; showCreateDialog = true },
                                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("加入群组") },
                                            onClick = { showTopBarMenu = false; showJoinDialog = true },
                                            leadingIcon = { Icon(Icons.Default.GroupAdd, contentDescription = null) }
                                        )
                                        Divider()
                                        DropdownMenuItem(
                                            text = { Text("升级") },
                                            onClick = { showTopBarMenu = false; showUpgradeDialog = true },
                                            leadingIcon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("联系人") },
                                            onClick = { showTopBarMenu = false; appState.navigateTo(Scene.CONTACTS) },
                                            leadingIcon = { Icon(Icons.Default.Contacts, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("设置") },
                                            onClick = { showTopBarMenu = false; appState.navigateTo(Scene.SETTINGS) },
                                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
                                        )
                                        Divider()
                                        DropdownMenuItem(
                                            text = { Text("退出登录", color = Color(0xFFe74c3c)) },
                                            onClick = { showTopBarMenu = false; appState.logout() },
                                            leadingIcon = { Icon(Icons.Default.Logout, contentDescription = null, tint = Color(0xFFe74c3c)) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        // Silk 风格背景
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            SilkColors.background,
                            SilkColors.secondary.copy(alpha = 0.2f),
                            SilkColors.background
                        )
                    )
                )
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = SilkColors.primary
                    )
                }
                groups.isEmpty() -> {
                    // Silk 风格空状态
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "🧵",
                            style = MaterialTheme.typography.displayLarge
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "您还没有加入任何群组",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = SilkColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "创建一个新群组或加入现有群组",
                            style = MaterialTheme.typography.bodyLarge,
                            color = SilkColors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(40.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Button(
                                onClick = { showCreateDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SilkColors.primary
                                )
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("创建群组")
                            }
                            OutlinedButton(
                                onClick = { showJoinDialog = true },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = SilkColors.primary
                                )
                            ) {
                                Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("加入群组")
                            }
                        }
                    }
                }
                else -> {
                    // 群组列表（Silk AI → CC-Connect → Silk Groups）
                    val silkPrivateGroups = groups.filter { it.name.startsWith("[Silk]") }
                    val ccGroups = groups.filter { ccConnectStatus.containsKey(it.id) }
                    val silkNormalGroups = groups.filter {
                        !it.name.startsWith("[Silk]") && !ccConnectStatus.containsKey(it.id)
                    }

                    @Composable
                    fun renderGroupCard(group: Group, ccInfo: CcConnectTokenInfo?) {
                        val isSelected = group.id in selectedGroups
                        val unreadCount = unreadCounts[group.id] ?: 0
                        GroupCard(
                            group = group,
                            isHost = group.hostId == appState.currentUser?.id,
                            isDeleteMode = isDeleteMode,
                            isSelected = isSelected,
                            unreadCount = unreadCount,
                            ccConnectInfo = ccInfo,
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

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 删除模式提示
                        if (isDeleteMode) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFFFFF3E0)
                                    )
                                ) {
                                    Text(
                                        text = "点击选择要退出的群组",
                                        modifier = Modifier.padding(12.dp),
                                        color = Color(0xFFE65100),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        // --- Section 1: Silk 专属对话 ---
                        if (silkPrivateGroups.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "Silk AI",
                                    color = SilkColors.primary
                                )
                            }
                            items(silkPrivateGroups) { group ->
                                renderGroupCard(
                                    group = group,
                                    ccInfo = ccConnectStatus[group.id]
                                )
                            }
                        }

                        // --- Section 2: CC-Connect 群组 ---
                        if (ccGroups.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "CC-Connect",
                                    color = Color(0xFF2E7D32)
                                )
                            }
                            items(ccGroups) { group ->
                                renderGroupCard(
                                    group = group,
                                    ccInfo = ccConnectStatus[group.id]
                                )
                            }
                        }

                        // --- Section 3: Silk 普通群组 ---
                        if (silkNormalGroups.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "Silk Groups",
                                    color = SilkColors.textSecondary
                                )
                            }
                            items(silkNormalGroups) { group ->
                                renderGroupCard(
                                    group = group,
                                    ccInfo = null
                                )
                            }
                        }

                        // 添加一个加入群组的按钮（非删除模式才显示）
                        if (!isDeleteMode) {
                            item {
                                OutlinedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showJoinDialog = true }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "+ 加入其他群组",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // 创建群组对话框
        if (showCreateDialog) {
            CreateGroupDialog(
                appState = appState,
                strings = strings,
                onDismiss = { showCreateDialog = false },
                onGroupCreated = { newGroup ->
                    groups = groups + newGroup
                },
                onCcConnectCreated = { newGroup ->
                    scope.launch {
                        val currentUserId = appState.currentUser?.id ?: return@launch
                        val info = ApiClient.getCcConnectTokenInfo(newGroup.id, currentUserId)
                        if (info != null && info.success) {
                            ccConnectStatus = ccConnectStatus + (newGroup.id to info)
                        }
                    }
                }
            )
        }
        
        // 加入群组对话框
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
        
        // 升级对话框
        if (showUpgradeDialog) {
            UpgradeDialog(
                downloadState = downloadState,
                onDismiss = { 
                    if (downloadState !is ApkDownloader.DownloadState.Downloading) {
                        showUpgradeDialog = false
                        downloadState = ApkDownloader.DownloadState.Idle
                    }
                },
                onStartDownload = {
                    scope.launch {
                        ApkDownloader.downloadApk(context) { state ->
                            downloadState = state
                            
                            // 下载成功后自动安装
                            if (state is ApkDownloader.DownloadState.Success) {
                                try {
                                    ApkDownloader.installApk(context, state.file)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "启动安装失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
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
                                Toast.makeText(context, "无法创建对话: ${response.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        // 不是联系人，发送联系人请求
                        scope.launch {
                            val userId = appState.currentUser?.id ?: return@launch
                            val response = ApiClient.sendContactRequestById(userId, member.id)
                            if (response.success) {
                                Toast.makeText(context, "联系人请求已发送给 ${member.fullName}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "发送失败: ${response.message}", Toast.LENGTH_SHORT).show()
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

@Suppress("CyclomaticComplexMethod", "UnusedParameter")
@Composable
fun GroupCard(
    group: Group,
    isHost: Boolean,
    isDeleteMode: Boolean = false,
    isSelected: Boolean = false,
    unreadCount: Int = 0,
    ccConnectInfo: CcConnectTokenInfo? = null,
    onClick: () -> Unit,
    onMembersClick: (() -> Unit)? = null
) {
    val hasUnread = unreadCount > 0
    val isCcConnect = ccConnectInfo != null
    val ccConnected = ccConnectInfo?.connected == true

    val badgeName = if (isCcConnect) {
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
    } else ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = if (hasUnread) 6.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> Color(0xFFFFEBEE)
                hasUnread -> Color(0xFFFFF8E1)
                else -> SilkColors.surfaceElevated
            }
        ),
        shape = MaterialTheme.shapes.small,
        border = when {
            isSelected -> androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFe74c3c))
            hasUnread -> androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFF9800))
            else -> null
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：群名 + badge + 邀请码
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 未读指示器
                    if (hasUnread && !isDeleteMode) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Color(0xFFFF5722), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // 群名
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (hasUnread) Color(0xFFE65100) else SilkColors.textPrimary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // cc-connect status badge
                    if (isCcConnect) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = if (ccConnected) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                        ) {
                            Text(
                                text = if (ccConnected) badgeName else "$badgeName (offline)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ccConnected) Color(0xFF2E7D32) else Color(0xFFE65100),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 邀请码（Silk 专属对话不显示）
                    if (!group.name.startsWith("[Silk]")) {
                        Text(
                            text = "[${group.invitationCode}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = SilkColors.textSecondary,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // 右侧按钮区域
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasUnread && !isDeleteMode) {
                        Text(
                            text = "新消息",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFF5722),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 👥 成员按钮（Silk 专属对话不显示）
                    if (!isDeleteMode && onMembersClick != null && !group.name.startsWith("[Silk]")) {
                        Surface(
                            onClick = { onMembersClick() },
                            color = SilkColors.secondary.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "👥",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (isDeleteMode) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    color = if (isSelected) Color(0xFFe74c3c) else Color.LightGray,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "已选择",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // cc-connect 工作目录（第二行）
            val cwdText = ccConnectInfo?.cwd
            if (isCcConnect && ccConnected && !cwdText.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (hasUnread && !isDeleteMode) 34.dp else 12.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📁",
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = cwdText,
                        style = MaterialTheme.typography.labelSmall,
                        color = SilkColors.textSecondary.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CreateGroupDialog(
    appState: AppState,
    strings: com.silk.shared.i18n.Strings,
    onDismiss: () -> Unit,
    onGroupCreated: (Group) -> Unit,
    onCcConnectCreated: ((Group) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var groupName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isCcConnect by remember { mutableStateOf(false) }
    var generatedToken by remember { mutableStateOf<String?>(null) }
    var tokenCopied by remember { mutableStateOf(false) }

    val userName = appState.currentUser?.fullName ?: ""
    val previewName = if (groupName.isNotBlank()) "$userName's $groupName" else ""

    if (generatedToken != null) {
        // Token display phase
        AlertDialog(
            onDismissRequest = { },
            title = { Text("cc-connect Token", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = generatedToken!!,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Text(
                        text = "Paste this token into cc-connect's config.toml:",
                        style = MaterialTheme.typography.bodySmall,
                        color = SilkColors.textSecondary
                    )
                    Surface(
                        color = SilkColors.secondary.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = """[[projects.platforms]]
type = "silk"
[projects.platforms.options]
server = "wss://your-server:15003/ccconnect-bridge"
token  = "${generatedToken}"""",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary)
                ) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = {
                    tokenCopied = true
                    // Android clipboard copy
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cc-connect token", generatedToken))
                }) { Text(if (tokenCopied) "Copied!" else "Copy Token") }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(strings.createGroupTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Group type selector
                    Text(
                        text = "Type",
                        style = MaterialTheme.typography.labelMedium,
                        color = SilkColors.textSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isCcConnect,
                            onClick = { isCcConnect = false },
                            label = { Text("Normal", fontSize = 13.sp) }
                        )
                        FilterChip(
                            selected = isCcConnect,
                            onClick = { isCcConnect = true },
                            label = { Text("cc-connect", fontSize = 13.sp) }
                        )
                    }

                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it; errorMessage = "" },
                        label = { Text(strings.groupName) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        singleLine = true
                    )

                    if (previewName.isNotEmpty() && !isCcConnect) {
                        Text(
                            text = "${strings.fullName}: $previewName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isCcConnect) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "A token will be generated for connecting cc-connect to this group. Paste it into your cc-connect config.toml.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SilkColors.textSecondary,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
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
                                        onCcConnectCreated?.invoke(response.group)
                                    } else {
                                        onDismiss()
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
                    },
                    enabled = !isLoading && groupName.isNotBlank()
                ) {
                    Text(if (isLoading) strings.creating else strings.createButton)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !isLoading) {
                    Text(strings.cancelButton)
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Divider(
            modifier = Modifier.weight(1f),
            color = color.copy(alpha = 0.3f),
            thickness = 1.dp
        )
    }
}

@Suppress("TooGenericExceptionCaught")
@Composable
fun JoinGroupDialog(
    appState: AppState,
    strings: com.silk.shared.i18n.Strings,
    onDismiss: () -> Unit,
    onGroupJoined: (Group) -> Unit
) {
    val scope = rememberCoroutineScope()
    var invitationCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.joinGroupTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = invitationCode,
                    onValueChange = {
                        invitationCode = it.uppercase().take(6)
                        errorMessage = ""
                    },
                    label = { Text(strings.invitationCode) },
                    placeholder = { Text(strings.invitationCodePlaceholder) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    singleLine = true
                )
                
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        try {
                            val response = appState.currentUser?.let { user ->
                                ApiClient.joinGroup(user.id, invitationCode)
                            }
                            
                            if (response != null && response.success && response.group != null) {
                                println("加入群组成功: ${response.group.name}")
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
                },
                enabled = !isLoading && invitationCode.length == 6
            ) {
                Text(if (isLoading) strings.joining else strings.joinButton)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(strings.cancelButton)
            }
        }
    )
}

/**
 * 升级对话框
 */
@Composable
fun UpgradeDialog(
    downloadState: ApkDownloader.DownloadState,
    onDismiss: () -> Unit,
    onStartDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "应用升级",
                fontWeight = FontWeight.Bold,
                color = SilkColors.primary
            ) 
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (downloadState) {
                    is ApkDownloader.DownloadState.Idle -> {
                        Text("点击下载按钮获取最新版本的 Silk 应用")
                        Text(
                            "下载完成后将自动启动安装程序",
                            style = MaterialTheme.typography.bodySmall,
                            color = SilkColors.textSecondary
                        )
                    }
                    is ApkDownloader.DownloadState.Downloading -> {
                        Text(downloadState.message)
                        if (downloadState.progress >= 0) {
                            LinearProgressIndicator(
                                progress = downloadState.progress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                                color = SilkColors.primary
                            )
                            Text(
                                "${downloadState.progress}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = SilkColors.textSecondary
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = SilkColors.primary
                            )
                        }
                    }
                    is ApkDownloader.DownloadState.Success -> {
                        Text("✅ 下载完成！")
                        Text(
                            "正在启动安装程序...",
                            style = MaterialTheme.typography.bodySmall,
                            color = SilkColors.success
                        )
                    }
                    is ApkDownloader.DownloadState.Error -> {
                        Text(
                            "❌ ${downloadState.message}",
                            color = SilkColors.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (downloadState) {
                is ApkDownloader.DownloadState.Idle,
                is ApkDownloader.DownloadState.Error -> {
                    Button(
                        onClick = onStartDownload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SilkColors.primary
                        )
                    ) {
                        Text("下载更新")
                    }
                }
                is ApkDownloader.DownloadState.Downloading -> {
                    // 下载中不显示确认按钮
                }
                is ApkDownloader.DownloadState.Success -> {
                    Button(onClick = onDismiss) {
                        Text("完成")
                    }
                }
            }
        },
        dismissButton = {
            if (downloadState !is ApkDownloader.DownloadState.Downloading) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
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
    onMemberClick: (GroupMember) -> Unit,
    onDismiss: () -> Unit
) {
    val contactIds = contacts.map { it.contactId }.toSet()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "👥 ${group.name}",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            ) 
        },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = SilkColors.primary)
                }
            } else if (members.isEmpty()) {
                Text("暂无成员", color = SilkColors.textSecondary)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(members) { member ->
                        val isHost = member.id == group.hostId
                        val isCurrentUser = member.id == currentUserId
                        val isContact = member.id in contactIds
                        val isSilkAI = isAgentUserId(member.id)
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (!isCurrentUser && !isSilkAI) {
                                        Modifier.clickable { onMemberClick(member) }
                                    } else {
                                        Modifier
                                    }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isHost) SilkColors.primary.copy(alpha = 0.1f) 
                                    else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 头像
                                    Surface(
                                        modifier = Modifier.size(32.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = when {
                                            isSilkAI -> SilkColors.info
                                            isHost -> SilkColors.primary
                                            isContact -> SilkColors.success
                                            else -> SilkColors.textSecondary
                                        }
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Text(
                                                text = when {
                                                    isSilkAI -> "🤖"
                                                    isHost -> "👑"
                                                    isContact -> "✓"
                                                    else -> member.fullName.firstOrNull()?.toString() ?: "?"
                                                },
                                                color = Color.White,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                    
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = member.fullName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isHost) FontWeight.Bold else FontWeight.Normal
                                            )
                                            if (isHost) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "(群主)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = SilkColors.primary
                                                )
                                            }
                                            if (isCurrentUser) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "(我)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = SilkColors.textSecondary
                                                )
                                            }
                                        }
                                        Text(
                                            text = when {
                                                isSilkAI -> "AI 助手"
                                                isCurrentUser -> "当前用户"
                                                isContact -> "联系人 · 点击聊天"
                                                else -> "点击添加联系人"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SilkColors.textSecondary
                                        )
                                    }
                                }
                                
                                if (!isCurrentUser && !isSilkAI) {
                                    Text(
                                        text = if (isContact) "💬" else "➕",
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

