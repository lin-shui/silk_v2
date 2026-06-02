package com.silk.android

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class KBTopicItem(
    val id: String,
    val name: String,
    val project: String = "",
    val ownerId: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class KBEntryItem(
    val id: String,
    val topicId: String = "",
    val title: String,
    val content: String = "",
    val tags: List<String> = emptyList(),
    val ownerId: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

private enum class KBSubPage { TOPICS, ENTRIES, EDITOR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseScreen(appState: AppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var subPage by remember { mutableStateOf(KBSubPage.TOPICS) }
    var topics by remember { mutableStateOf<List<KBTopicItem>>(emptyList()) }
    var selectedTopic by remember { mutableStateOf<KBTopicItem?>(null) }
    var entries by remember { mutableStateOf<List<KBEntryItem>>(emptyList()) }
    var selectedEntry by remember { mutableStateOf<KBEntryItem?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    var showCreateTopicDialog by remember { mutableStateOf(false) }
    var newTopicName by remember { mutableStateOf("") }
    var newTopicProject by remember { mutableStateOf("") }
    var showCreateEntryDialog by remember { mutableStateOf(false) }
    var newEntryTitle by remember { mutableStateOf("") }
    var editorContent by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(user.id) {
        isLoading = true
        topics = ApiClient.getKBTopics(user.id)
        isLoading = false
    }

    BackHandler(enabled = subPage != KBSubPage.TOPICS) {
        when (subPage) {
            KBSubPage.EDITOR -> {
                subPage = KBSubPage.ENTRIES
                selectedEntry = null
                editorContent = ""
            }
            KBSubPage.ENTRIES -> {
                subPage = KBSubPage.TOPICS
                selectedTopic = null
                entries = emptyList()
            }
            KBSubPage.TOPICS -> Unit
        }
    }

    KnowledgeBasePageHost(
        subPage = subPage,
        topics = topics,
        selectedTopic = selectedTopic,
        entries = entries,
        selectedEntry = selectedEntry,
        isLoading = isLoading,
        editorContent = editorContent,
        isSaving = isSaving,
        onShowCreateTopic = { showCreateTopicDialog = true },
        onTopicSelected = { topic ->
            selectedTopic = topic
            scope.launch { entries = ApiClient.getKBEntries(topic.id, user.id) }
            subPage = KBSubPage.ENTRIES
        },
        onBackToTopics = {
            subPage = KBSubPage.TOPICS
            selectedTopic = null
            entries = emptyList()
        },
        onShowCreateEntry = { showCreateEntryDialog = true },
        onEntrySelected = { entry ->
            selectedEntry = entry
            editorContent = entry.content
            subPage = KBSubPage.EDITOR
        },
        onBackToEntries = {
            subPage = KBSubPage.ENTRIES
            selectedEntry = null
            editorContent = ""
        },
        onEditorContentChange = { editorContent = it },
        onSaveEntry = {
            val currentEntry = selectedEntry ?: return@KnowledgeBasePageHost
            val currentTopic = selectedTopic ?: return@KnowledgeBasePageHost
            scope.launch {
                isSaving = true
                ApiClient.updateKBEntry(currentEntry.id, null, editorContent, null, user.id)
                entries = ApiClient.getKBEntries(currentTopic.id, user.id)
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                isSaving = false
            }
        },
    )

    KnowledgeBaseDialogs(
        showCreateTopicDialog = showCreateTopicDialog,
        newTopicName = newTopicName,
        newTopicProject = newTopicProject,
        onTopicNameChange = { newTopicName = it },
        onTopicProjectChange = { newTopicProject = it },
        onDismissCreateTopic = {
            showCreateTopicDialog = false
            newTopicName = ""
            newTopicProject = ""
        },
        onConfirmCreateTopic = {
            if (newTopicName.isNotBlank()) {
                scope.launch {
                    ApiClient.createKBTopic(newTopicName.trim(), newTopicProject.trim(), user.id)
                    topics = ApiClient.getKBTopics(user.id)
                    showCreateTopicDialog = false
                    newTopicName = ""
                    newTopicProject = ""
                }
            }
        },
        showCreateEntryDialog = showCreateEntryDialog,
        selectedTopic = selectedTopic,
        newEntryTitle = newEntryTitle,
        onEntryTitleChange = { newEntryTitle = it },
        onDismissCreateEntry = {
            showCreateEntryDialog = false
            newEntryTitle = ""
        },
        onConfirmCreateEntry = {
            val currentTopic = selectedTopic ?: return@KnowledgeBaseDialogs
            if (newEntryTitle.isNotBlank()) {
                scope.launch {
                    val entry = ApiClient.createKBEntry(
                        currentTopic.id,
                        newEntryTitle.trim(),
                        "",
                        emptyList(),
                        user.id,
                    )
                    if (entry != null) {
                        entries = ApiClient.getKBEntries(currentTopic.id, user.id)
                        selectedEntry = entry
                        editorContent = entry.content
                        subPage = KBSubPage.EDITOR
                    }
                    showCreateEntryDialog = false
                    newEntryTitle = ""
                }
            }
        },
    )
}

@Composable
private fun KnowledgeBasePageHost(
    subPage: KBSubPage,
    topics: List<KBTopicItem>,
    selectedTopic: KBTopicItem?,
    entries: List<KBEntryItem>,
    selectedEntry: KBEntryItem?,
    isLoading: Boolean,
    editorContent: String,
    isSaving: Boolean,
    onShowCreateTopic: () -> Unit,
    onTopicSelected: (KBTopicItem) -> Unit,
    onBackToTopics: () -> Unit,
    onShowCreateEntry: () -> Unit,
    onEntrySelected: (KBEntryItem) -> Unit,
    onBackToEntries: () -> Unit,
    onEditorContentChange: (String) -> Unit,
    onSaveEntry: () -> Unit,
) {
    when (subPage) {
        KBSubPage.TOPICS -> KnowledgeBaseTopicsPage(
            topics = topics,
            isLoading = isLoading,
            onShowCreateTopic = onShowCreateTopic,
            onTopicSelected = onTopicSelected,
        )
        KBSubPage.ENTRIES -> KnowledgeBaseEntriesPage(
            selectedTopic = selectedTopic,
            entries = entries,
            onBack = onBackToTopics,
            onShowCreateEntry = onShowCreateEntry,
            onEntrySelected = onEntrySelected,
        )
        KBSubPage.EDITOR -> KnowledgeBaseEditorPage(
            selectedEntry = selectedEntry,
            editorContent = editorContent,
            isSaving = isSaving,
            onBack = onBackToEntries,
            onEditorContentChange = onEditorContentChange,
            onSave = onSaveEntry,
        )
    }
}

@Composable
private fun KnowledgeBaseTopicsPage(
    topics: List<KBTopicItem>,
    isLoading: Boolean,
    onShowCreateTopic: () -> Unit,
    onTopicSelected: (KBTopicItem) -> Unit,
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onShowCreateTopic,
                containerColor = SilkColors.primary,
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建主题")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("知识库", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
            Divider(color = SilkColors.divider)
            when {
                isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                topics.isEmpty() -> KnowledgeBaseEmptyState(
                    icon = "📚",
                    title = "暂无主题",
                    subtitle = "点击右下角 + 创建第一个主题",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(topics) { topic ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onTopicSelected(topic) },
                            colors = CardDefaults.cardColors(containerColor = SilkColors.cardBackground),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    topic.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (topic.project.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        topic.project,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SilkColors.textLight,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeBaseEntriesPage(
    selectedTopic: KBTopicItem?,
    entries: List<KBEntryItem>,
    onBack: () -> Unit,
    onShowCreateEntry: () -> Unit,
    onEntrySelected: (KBEntryItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedTopic?.name ?: "条目") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SilkColors.surface,
                    titleContentColor = SilkColors.textPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onShowCreateEntry,
                containerColor = SilkColors.primary,
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建条目")
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                KnowledgeBaseEmptyState(
                    icon = "📝",
                    title = "暂无条目",
                    subtitle = "点击右下角 + 创建条目",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onEntrySelected(entry) },
                        colors = CardDefaults.cardColors(containerColor = SilkColors.cardBackground),
                    ) {
                        Text(
                            entry.title,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeBaseEditorPage(
    selectedEntry: KBEntryItem?,
    editorContent: String,
    isSaving: Boolean,
    onBack: () -> Unit,
    onEditorContentChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedEntry?.title ?: "编辑") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onSave, enabled = !isSaving) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "保存",
                            tint = if (isSaving) SilkColors.textLight else SilkColors.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SilkColors.surface,
                    titleContentColor = SilkColors.textPrimary,
                ),
            )
        }
    ) { padding ->
        OutlinedTextField(
            value = editorContent,
            onValueChange = onEditorContentChange,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            placeholder = { Text("在这里输入 Markdown 内容...") },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun KnowledgeBaseEmptyState(
    icon: String,
    title: String,
    subtitle: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(title, color = SilkColors.textSecondary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = SilkColors.textLight, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun KnowledgeBaseDialogs(
    showCreateTopicDialog: Boolean,
    newTopicName: String,
    newTopicProject: String,
    onTopicNameChange: (String) -> Unit,
    onTopicProjectChange: (String) -> Unit,
    onDismissCreateTopic: () -> Unit,
    onConfirmCreateTopic: () -> Unit,
    showCreateEntryDialog: Boolean,
    selectedTopic: KBTopicItem?,
    newEntryTitle: String,
    onEntryTitleChange: (String) -> Unit,
    onDismissCreateEntry: () -> Unit,
    onConfirmCreateEntry: () -> Unit,
) {
    if (showCreateTopicDialog) {
        AlertDialog(
            onDismissRequest = onDismissCreateTopic,
            title = { Text("创建主题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTopicName,
                        onValueChange = onTopicNameChange,
                        label = { Text("主题名称") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newTopicProject,
                        onValueChange = onTopicProjectChange,
                        label = { Text("所属项目（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmCreateTopic,
                    colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCreateTopic) {
                    Text("取消")
                }
            },
        )
    }

    if (showCreateEntryDialog && selectedTopic != null) {
        AlertDialog(
            onDismissRequest = onDismissCreateEntry,
            title = { Text("创建条目") },
            text = {
                OutlinedTextField(
                    value = newEntryTitle,
                    onValueChange = onEntryTitleChange,
                    label = { Text("条目标题") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmCreateEntry,
                    colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCreateEntry) {
                    Text("取消")
                }
            },
        )
    }
}
