package com.silk.android

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silk.shared.i18n.Strings
import com.silk.shared.i18n.getStrings
import com.silk.shared.models.Language
import kotlinx.coroutines.launch

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(appState: AppState) {
    val scope = rememberCoroutineScope()
    val user = appState.currentUser ?: return

    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var pendingRequests by remember { mutableStateOf<List<ContactRequest>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showRequestDetailDialog by remember { mutableStateOf<ContactRequest?>(null) }

    var userLanguage by remember { mutableStateOf<Language>(Language.CHINESE) }

    val loadContacts = {
        scope.launch {
            loadContactsForUser(
                userId = user.id,
                setLoading = { isLoading = it },
                onLoaded = { loadedContacts, loadedRequests ->
                    contacts = loadedContacts
                    pendingRequests = loadedRequests
                },
            )
        }
    }

    LaunchedEffect(user.id) {
        loadUserLanguage(user.id) { userLanguage = it }
    }

    LaunchedEffect(user.id) {
        loadContacts()
    }

    val strings = getStrings(userLanguage)

    Scaffold(
        topBar = {
            ContactsTopBar(
                strings = strings,
                onBack = { appState.navigateTo(Scene.GROUP_LIST) },
                onAddContact = { showAddContactDialog = true },
                onLogout = appState::logout,
            )
        }
    ) { padding ->
        ContactsBody(
            padding = padding,
            strings = strings,
            contacts = contacts,
            pendingRequests = pendingRequests,
            isLoading = isLoading,
            onAddContact = { showAddContactDialog = true },
            onPendingRequestClick = { showRequestDetailDialog = it },
            onContactClick = { contact ->
                scope.launch {
                    startPrivateChat(
                        currentUser = appState.currentUser,
                        contact = contact,
                        onGroupReady = appState::selectGroup,
                    )
                }
            },
        )

        ContactsDialogs(
            showAddContactDialog = showAddContactDialog,
            requestDetail = showRequestDetailDialog,
            appState = appState,
            strings = strings,
            onDismissAddContact = { showAddContactDialog = false },
            onContactAdded = {
                showAddContactDialog = false
                loadContacts()
            },
            onDismissRequestDetail = { showRequestDetailDialog = null },
            onRequestHandled = {
                showRequestDetailDialog = null
                loadContacts()
            },
        )
    }
}

@Composable
private fun ContactsTopBar(
    strings: com.silk.shared.i18n.Strings,
    onBack: () -> Unit,
    onAddContact: () -> Unit,
    onLogout: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            SilkColors.primary,
                            SilkColors.primaryDark,
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContactsTopBarTitle(
                    title = strings.contactsTitle,
                    onBack = onBack,
                )
                ContactsTopBarActions(
                    strings = strings,
                    onAddContact = onAddContact,
                    onLogout = onLogout,
                )
            }
        }
    }
}

@Composable
private fun ContactsTopBarTitle(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconButton(
            onClick = onBack,
            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
            color = Color.White,
        )
    }
}

@Composable
private fun ContactsTopBarActions(
    strings: com.silk.shared.i18n.Strings,
    onAddContact: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = onAddContact,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = Color.White.copy(alpha = 0.2f),
                contentColor = Color.White,
            ),
        ) {
            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(strings.addContactButton)
        }

        TextButton(
            onClick = onLogout,
            colors = ButtonDefaults.textButtonColors(
                contentColor = Color.White.copy(alpha = 0.9f),
            ),
        ) {
            Text(strings.logoutButton)
        }
    }
}

@Composable
private fun ContactsBody(
    padding: PaddingValues,
    strings: com.silk.shared.i18n.Strings,
    contacts: List<Contact>,
    pendingRequests: List<ContactRequest>,
    isLoading: Boolean,
    onAddContact: () -> Unit,
    onPendingRequestClick: (ContactRequest) -> Unit,
    onContactClick: (Contact) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SilkColors.background,
                        SilkColors.secondary.copy(alpha = 0.2f),
                        SilkColors.background,
                    )
                )
            )
            .padding(padding)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = SilkColors.primary,
            )
        } else {
            ContactsListContent(
                strings = strings,
                contacts = contacts,
                pendingRequests = pendingRequests,
                onAddContact = onAddContact,
                onPendingRequestClick = onPendingRequestClick,
                onContactClick = onContactClick,
            )
        }
    }
}

@Composable
private fun ContactsListContent(
    strings: com.silk.shared.i18n.Strings,
    contacts: List<Contact>,
    pendingRequests: List<ContactRequest>,
    onAddContact: () -> Unit,
    onPendingRequestClick: (ContactRequest) -> Unit,
    onContactClick: (Contact) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (pendingRequests.isNotEmpty()) {
            item {
                ContactsPendingRequestsHeader(
                    title = strings.pendingRequestsTitle.replace("{count}", pendingRequests.size.toString()),
                )
            }
            items(pendingRequests) { request ->
                PendingRequestCard(
                    request = request,
                    strings = strings,
                    onClick = { onPendingRequestClick(request) },
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        item {
            ContactsListHeader(
                title = strings.myContactsWithCount.replace("{count}", contacts.size.toString()),
            )
        }

        if (contacts.isEmpty()) {
            item {
                EmptyContactsCard(
                    emptyText = strings.noContactsYet,
                    addButtonText = strings.addFirstContact,
                    onAddContact = onAddContact,
                )
            }
        } else {
            items(contacts) { contact ->
                ContactCard(
                    contact = contact,
                    onClick = { onContactClick(contact) },
                )
            }
        }
    }
}

@Composable
private fun ContactsPendingRequestsHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = SilkColors.textPrimary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ContactsListHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = SilkColors.textPrimary,
        )
    }
}

@Composable
private fun EmptyContactsCard(
    emptyText: String,
    addButtonText: String,
    onAddContact: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SilkColors.surfaceElevated),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "👤",
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = SilkColors.textSecondary,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAddContact,
                colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(addButtonText)
            }
        }
    }
}

@Composable
private fun ContactsDialogs(
    showAddContactDialog: Boolean,
    requestDetail: ContactRequest?,
    appState: AppState,
    strings: com.silk.shared.i18n.Strings,
    onDismissAddContact: () -> Unit,
    onContactAdded: () -> Unit,
    onDismissRequestDetail: () -> Unit,
    onRequestHandled: () -> Unit,
) {
    if (showAddContactDialog) {
        AddContactDialog(
            appState = appState,
            strings = strings,
            onDismiss = onDismissAddContact,
            onContactAdded = onContactAdded,
        )
    }

    requestDetail?.let { request ->
        RequestDetailDialog(
            request = request,
            appState = appState,
            strings = strings,
            onDismiss = onDismissRequestDetail,
            onHandled = onRequestHandled,
        )
    }
}

@Composable
fun ContactCard(contact: Contact, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = SilkColors.surfaceElevated
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头像
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(SilkColors.primaryLight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.contactName.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // 信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.contactName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SilkColors.textPrimary
                )
                Text(
                    text = contact.contactPhone,
                    style = MaterialTheme.typography.bodySmall,
                    color = SilkColors.textSecondary
                )
            }
            
            // 聊天图标
            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = "私聊",
                tint = SilkColors.primary
            )
        }
    }
}

@Composable
fun PendingRequestCard(request: ContactRequest, strings: com.silk.shared.i18n.Strings, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = SilkColors.surfaceElevated.copy(alpha = 0.8f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = SilkColors.textSecondary.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头像（灰色表示待处理）
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Gray),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = request.fromUserName.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // 信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.fromUserName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SilkColors.textSecondary
                )
                Text(
                    text = strings.wantsToAddYouAsContact,
                    style = MaterialTheme.typography.bodySmall,
                    color = SilkColors.textSecondary
                )
            }
            
            // 待处理标签
            Surface(
                color = SilkColors.primary,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = strings.pendingStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER", "CyclomaticComplexMethod")
@Composable
fun AddContactDialog(
    appState: AppState,
    strings: com.silk.shared.i18n.Strings,
    onDismiss: () -> Unit,
    onContactAdded: () -> Unit  // 保留以便将来刷新列表
) {
    val scope = rememberCoroutineScope()
    var phoneNumber by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var foundUser by remember { mutableStateOf<User?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { AddContactDialogTitle(title = strings.addContact) },
        text = {
            AddContactDialogContent(
                phoneNumber = phoneNumber,
                errorMessage = errorMessage,
                successMessage = successMessage,
                isLoading = isLoading,
                foundUser = foundUser,
                strings = strings,
                onPhoneNumberChange = {
                    phoneNumber = it
                    errorMessage = ""
                    successMessage = ""
                    foundUser = null
                },
                onSearch = {
                    scope.launch {
                        performContactSearch(
                            phoneNumber = phoneNumber,
                            setLoading = { isLoading = it },
                            clearMessages = {
                                errorMessage = ""
                                successMessage = ""
                                foundUser = null
                            },
                            onUserFound = { foundUser = it },
                            onError = { errorMessage = it },
                        )
                    }
                },
                onSendRequest = { user ->
                    scope.launch {
                        performSendContactRequest(
                            currentUser = appState.currentUser,
                            targetUser = user,
                            successMessage = strings.contactRequestSent,
                            setLoading = { isLoading = it },
                            onSuccess = {
                                successMessage = it
                                foundUser = null
                                onContactAdded()
                            },
                            onError = { errorMessage = it },
                        )
                    }
                },
            )
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.closeButton)
            }
        }
    )
}

@Composable
private fun AddContactDialogTitle(title: String) {
    Text(
        title,
        fontWeight = FontWeight.Bold,
        color = SilkColors.primary,
    )
}

@Composable
private fun AddContactDialogContent(
    phoneNumber: String,
    errorMessage: String,
    successMessage: String,
    isLoading: Boolean,
    foundUser: User?,
    strings: com.silk.shared.i18n.Strings,
    onPhoneNumberChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSendRequest: (User) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AddContactPhoneField(
            phoneNumber = phoneNumber,
            label = strings.phoneNumberLabel,
            placeholder = strings.phoneNumberPlaceholder,
            isLoading = isLoading,
            onValueChange = onPhoneNumberChange,
        )
        AddContactSearchButton(
            isLoading = isLoading,
            enabled = phoneNumber.isNotBlank(),
            loadingText = strings.loading,
            buttonText = strings.searchButton,
            onClick = onSearch,
        )
        foundUser?.let { user ->
            AddContactFoundUserCard(
                user = user,
                isLoading = isLoading,
                buttonText = if (isLoading) strings.sendingRequest else strings.sendAddRequestButton,
                onSendRequest = { onSendRequest(user) },
            )
        }
        AddContactStatusMessage(
            message = errorMessage,
            color = SilkColors.error,
        )
        AddContactStatusMessage(
            message = successMessage,
            color = SilkColors.success,
        )
    }
}

@Composable
private fun AddContactPhoneField(
    phoneNumber: String,
    label: String,
    placeholder: String,
    isLoading: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = phoneNumber,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        enabled = !isLoading,
    )
}

@Composable
private fun AddContactSearchButton(
    isLoading: Boolean,
    enabled: Boolean,
    loadingText: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading && enabled,
        colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(if (isLoading) loadingText else buttonText)
    }
}

@Composable
private fun AddContactFoundUserCard(
    user: User,
    isLoading: Boolean,
    buttonText: String,
    onSendRequest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = SilkColors.primary.copy(alpha = 0.1f),
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = SilkColors.primary,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            AddContactFoundUserHeader(user = user)
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onSendRequest,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun AddContactFoundUserHeader(user: User) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(SilkColors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = user.fullName.firstOrNull()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.fullName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = SilkColors.textPrimary,
            )
            Text(
                text = user.phoneNumber,
                style = MaterialTheme.typography.bodySmall,
                color = SilkColors.textSecondary,
            )
        }
    }
}

@Composable
private fun AddContactStatusMessage(
    message: String,
    color: Color,
) {
    if (message.isEmpty()) return

    Text(
        text = message,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private suspend fun performContactSearch(
    phoneNumber: String,
    setLoading: (Boolean) -> Unit,
    clearMessages: () -> Unit,
    onUserFound: (User) -> Unit,
    onError: (String) -> Unit,
) {
    if (phoneNumber.isBlank()) return

    setLoading(true)
    clearMessages()
    try {
        val result = ApiClient.searchUserByPhone(phoneNumber)
        val user = result.user
        if (result.found && user != null) {
            onUserFound(user)
        } else {
            onError(result.message.ifEmpty { "未找到用户" })
        }
    } finally {
        setLoading(false)
    }
}

private suspend fun loadUserLanguage(
    userId: String,
    onLoaded: (Language) -> Unit,
) {
    val response = ApiClient.getUserSettings(userId)
    if (response.success && response.settings != null) {
        onLoaded(response.settings!!.language)
    } else if (response.message.isNotBlank()) {
        println("Failed to load user settings: ${response.message}")
    }
}

private suspend fun loadContactsForUser(
    userId: String,
    setLoading: (Boolean) -> Unit,
    onLoaded: (List<Contact>, List<ContactRequest>) -> Unit,
) {
    setLoading(true)
    try {
        val response = ApiClient.getContacts(userId)
        if (response.success) {
            val contacts = response.contacts ?: emptyList()
            val requests = response.pendingRequests ?: emptyList()
            onLoaded(contacts, requests)
            println("✅ 加载了${contacts.size}个联系人，${requests.size}个待处理请求")
        } else if (response.message.isNotBlank()) {
            println("❌ 加载联系人失败: ${response.message}")
        }
    } finally {
        setLoading(false)
    }
}

private suspend fun startPrivateChat(
    currentUser: User?,
    contact: Contact,
    onGroupReady: (Group) -> Unit,
) {
    val response = currentUser?.let { user ->
        ApiClient.startPrivateChat(user.id, contact.contactId)
    }

    if (response != null && response.success && response.group != null) {
        onGroupReady(response.group)
    } else {
        println("创建私聊失败: ${response?.message}")
    }
}

private suspend fun performSendContactRequest(
    currentUser: User?,
    targetUser: User,
    successMessage: String,
    setLoading: (Boolean) -> Unit,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit,
) {
    setLoading(true)
    try {
        val response = currentUser?.let { user ->
            ApiClient.sendContactRequest(user.id, targetUser.phoneNumber)
        }
        if (response != null && response.success) {
            onSuccess(successMessage)
        } else {
            onError(response?.message ?: "发送失败")
        }
    } finally {
        setLoading(false)
    }
}

@Composable
fun RequestDetailDialog(
    request: ContactRequest,
    appState: AppState,
    strings: com.silk.shared.i18n.Strings,
    onDismiss: () -> Unit,
    onHandled: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                strings.contactRequestTitle,
                fontWeight = FontWeight.Bold,
                color = SilkColors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 用户头像
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(SilkColors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = request.fromUserName.firstOrNull()?.toString() ?: "?",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                // 用户信息
                Text(
                    text = request.fromUserName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = SilkColors.textPrimary
                )
                
                Text(
                    text = request.fromUserPhone,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SilkColors.textSecondary
                )
                
                Text(
                    text = strings.wantsToAddYouAsContact,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SilkColors.textSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        val response = appState.currentUser?.let { user ->
                            ApiClient.handleContactRequest(request.id, user.id, true)
                        }
                        if (response?.success == true) {
                            onHandled()
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SilkColors.primary
                )
            ) {
                Text(if (isLoading) strings.loading else strings.acceptButton)
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            val response = appState.currentUser?.let { user ->
                                ApiClient.handleContactRequest(request.id, user.id, false)
                            }
                            if (response?.success == true) {
                                onHandled()
                            }
                            isLoading = false
                        }
                    },
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) strings.loading else strings.rejectButton, color = SilkColors.error)
                }
                
                TextButton(onClick = onDismiss, enabled = !isLoading) {
                    Text(strings.cancelButton)
                }
            }
        }
    )
}
