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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silk.shared.i18n.Strings
import com.silk.shared.i18n.getStrings
import com.silk.shared.models.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(appState: AppState) {
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    
    // Language and strings
    var userLanguage by remember { mutableStateOf<Language>(Language.CHINESE) }
    
    LaunchedEffect(appState.currentUser?.id) {
        userLanguage = loadGroupListLanguage(appState.currentUser?.id)
    }
    
    val strings = getStrings(userLanguage)
    
    LaunchedEffect(appState.currentScene) {
        if (appState.currentScene != Scene.GROUP_LIST) {
            return@LaunchedEffect
        }

        scope.launch {
            isLoading = true
            val result = loadGroupListData(appState.currentUser)
            groups = result.groups
            isLoading = false
        }
    }
    
    Scaffold(
        topBar = {
            GroupListTopBar(
                currentUserName = appState.currentUser?.fullName.orEmpty(),
                onOpenSettings = { appState.navigateTo(Scene.SETTINGS) },
                onLogout = { appState.logout() }
            )
        },
        floatingActionButton = {
            CreateGroupFab(onClick = { showCreateDialog = true })
        }
    ) { padding ->
        GroupListContent(
            padding = padding,
            groups = groups,
            isLoading = isLoading,
            currentUserId = appState.currentUser?.id,
            onCreateClick = { showCreateDialog = true },
            onJoinClick = { showJoinDialog = true },
            onSelectGroup = { appState.selectGroup(it) }
        )
    }

    GroupListDialogs(
        appState = appState,
        strings = strings,
        showCreateDialog = showCreateDialog,
        showJoinDialog = showJoinDialog,
        onDismissCreate = { showCreateDialog = false },
        onDismissJoin = { showJoinDialog = false },
        onGroupCreated = { newGroup ->
            groups = groups + newGroup
            showCreateDialog = false
        },
        onGroupJoined = { newGroup ->
            groups = groups + newGroup
            showJoinDialog = false
        }
    )
}

private data class GroupListLoadResult(
    val groups: List<Group>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupListTopBar(
    currentUserName: String,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text("群组列表")
                Text(
                    text = currentUserName,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
            IconButton(onClick = onLogout) {
                Icon(Icons.Default.ExitToApp, contentDescription = "登出")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
private fun CreateGroupFab(onClick: () -> Unit) {
    FloatingActionButton(onClick = onClick) {
        Icon(Icons.Default.Add, contentDescription = "创建群组")
    }
}

@Composable
private fun GroupListContent(
    padding: PaddingValues,
    groups: List<Group>,
    isLoading: Boolean,
    currentUserId: String?,
    onCreateClick: () -> Unit,
    onJoinClick: () -> Unit,
    onSelectGroup: (Group) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            groups.isEmpty() -> EmptyGroupState(
                onCreateClick = onCreateClick,
                onJoinClick = onJoinClick
            )
            else -> GroupList(groups, currentUserId, onSelectGroup)
        }
    }
}

@Composable
private fun GroupList(
    groups: List<Group>,
    currentUserId: String?,
    onSelectGroup: (Group) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(groups) { group ->
            GroupCard(
                group = group,
                isHost = group.hostId == currentUserId,
                onClick = { onSelectGroup(group) }
            )
        }
    }
}

@Composable
private fun GroupListDialogs(
    appState: AppState,
    strings: Strings,
    showCreateDialog: Boolean,
    showJoinDialog: Boolean,
    onDismissCreate: () -> Unit,
    onDismissJoin: () -> Unit,
    onGroupCreated: (Group) -> Unit,
    onGroupJoined: (Group) -> Unit
) {
    if (showCreateDialog) {
        CreateGroupDialog(
            appState = appState,
            strings = strings,
            onDismiss = onDismissCreate,
            onGroupCreated = onGroupCreated
        )
    }

    if (showJoinDialog) {
        JoinGroupDialog(
            appState = appState,
            strings = strings,
            onDismiss = onDismissJoin,
            onGroupJoined = onGroupJoined
        )
    }
}

private suspend fun loadGroupListLanguage(userId: String?): Language {
    if (userId == null) {
        return Language.CHINESE
    }

    val response = withContext(Dispatchers.IO) {
        ApiClient.getUserSettings(userId)
    }
    val settings = response.settings
    return if (response.success && settings != null) settings.language else Language.CHINESE
}

private suspend fun loadGroupListData(currentUser: User?): GroupListLoadResult {
    val response = withContext(Dispatchers.IO) {
        currentUser?.let { user ->
            ApiClient.getUserGroups(user.id)
        }
    }

    return if (response != null && response.success) {
        val groups = response.groups.orEmpty()
        println("✅ 加载了 ${groups.size} 个群组")
        GroupListLoadResult(groups = groups)
    } else {
        println("❌ 加载群组失败: ${response?.message}")
        GroupListLoadResult(groups = emptyList())
    }
}

@Composable
fun EmptyGroupState(
    onCreateClick: () -> Unit,
    onJoinClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "您还没有加入任何群组",
            style = MaterialTheme.typography.titleMedium
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "创建一个新群组或加入现有群组",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onCreateClick) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("创建群组")
            }
            
            OutlinedButton(onClick = onJoinClick) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("加入群组")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCard(
    group: Group,
    isHost: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 群组图标
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // 群组信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium
                )
                
                Text(
                    text = if (isHost) "群主" else "成员",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = "邀请码: ${group.invitationCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            
            // 进入箭头
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "进入群组",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Suppress("TooGenericExceptionCaught")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupDialog(
    appState: AppState,
    strings: Strings,
    onDismiss: () -> Unit,
    onGroupCreated: (Group) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val userName = appState.currentUser?.fullName ?: ""
    val previewName = if (groupName.isNotBlank()) "${userName}'s $groupName" else ""
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.createGroupTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it; errorMessage = "" },
                    label = { Text(strings.groupName) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoading
                )
                
                if (previewName.isNotEmpty()) {
                    Text(
                        text = "${strings.fullName}: $previewName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
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
                        errorMessage = ""

                        val response = withContext(Dispatchers.IO) {
                            appState.currentUser?.let { user ->
                                ApiClient.createGroup(user.id, groupName)
                            }
                        }

                        if (response != null && response.success && response.group != null) {
                            println("✅ 群组创建成功: ${response.group.name}")
                            onGroupCreated(response.group)
                        } else {
                            errorMessage = response?.message ?: "创建失败"
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading && groupName.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
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

@Suppress("TooGenericExceptionCaught")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinGroupDialog(
    appState: AppState,
    strings: Strings,
    onDismiss: () -> Unit,
    onGroupJoined: (Group) -> Unit
) {
    var invitationCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.joinGroupTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = invitationCode,
                    onValueChange = { 
                        invitationCode = it.uppercase().take(6)
                        errorMessage = ""
                    },
                    label = { Text(strings.invitationCode) },
                    placeholder = { Text(strings.invitationCodePlaceholder) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoading
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
                        errorMessage = ""

                        val response = withContext(Dispatchers.IO) {
                            appState.currentUser?.let { user ->
                                ApiClient.joinGroup(user.id, invitationCode)
                            }
                        }

                        if (response != null && response.success && response.group != null) {
                            println("✅ 加入群组成功: ${response.group.name}")
                            onGroupJoined(response.group)
                        } else {
                            errorMessage = response?.message ?: "加入失败"
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading && invitationCode.length == 6
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
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
