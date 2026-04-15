package com.silk.android

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val updatedAt: Long = 0
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
    val updatedAt: Long = 0
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
            else -> {}
        }
    }

    when (subPage) {
        KBSubPage.TOPICS -> {
            Scaffold(
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { showCreateTopicDialog = true },
                        containerColor = SilkColors.primary,
                        contentColor = Color.White
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "创建主题")
                    }
                }
            ) { padding ->
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("知识库", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                    Divider(color = SilkColors.divider)

                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (topics.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📚", fontSize = 48.sp)
                                Spacer(Modifier.height(16.dp))
                                Text("暂无主题", color = SilkColors.textSecondary, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text("点击右下角 + 创建第一个主题", color = SilkColors.textLight, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(topics) { topic ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedTopic = topic
                                        scope.launch { entries = ApiClient.getKBEntries(topic.id, user.id) }
                                        subPage = KBSubPage.ENTRIES
                                    },
                                    colors = CardDefaults.cardColors(containerColor = SilkColors.cardBackground)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(topic.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                        if (topic.project.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(topic.project, style = MaterialTheme.typography.bodySmall, color = SilkColors.textLight)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        KBSubPage.ENTRIES -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(selectedTopic?.name ?: "条目") },
                        navigationIcon = {
                            IconButton(onClick = {
                                subPage = KBSubPage.TOPICS
                                selectedTopic = null
                                entries = emptyList()
                            }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = SilkColors.surface,
                            titleContentColor = SilkColors.textPrimary
                        )
                    )
                },
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { showCreateEntryDialog = true },
                        containerColor = SilkColors.primary,
                        contentColor = Color.White
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "创建条目")
                    }
                }
            ) { padding ->
                if (entries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📝", fontSize = 48.sp)
                            Spacer(Modifier.height(16.dp))
                            Text("暂无条目", color = SilkColors.textSecondary)
                            Spacer(Modifier.height(8.dp))
                            Text("点击右下角 + 创建条目", color = SilkColors.textLight, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(entries) { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedEntry = entry
                                    editorContent = entry.content
                                    subPage = KBSubPage.EDITOR
                                },
                                colors = CardDefaults.cardColors(containerColor = SilkColors.cardBackground)
                            ) {
                                Text(
                                    entry.title,
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }
        }

        KBSubPage.EDITOR -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(selectedEntry?.title ?: "编辑") },
                        navigationIcon = {
                            IconButton(onClick = {
                                subPage = KBSubPage.ENTRIES
                                selectedEntry = null
                                editorContent = ""
                            }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        isSaving = true
                                        ApiClient.updateKBEntry(selectedEntry!!.id, null, editorContent, null, user.id)
                                        entries = ApiClient.getKBEntries(selectedTopic!!.id, user.id)
                                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                        isSaving = false
                                    }
                                },
                                enabled = !isSaving
                            ) {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = "保存",
                                    tint = if (isSaving) SilkColors.textLight else SilkColors.primary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = SilkColors.surface,
                            titleContentColor = SilkColors.textPrimary
                        )
                    )
                }
            ) { padding ->
                OutlinedTextField(
                    value = editorContent,
                    onValueChange = { editorContent = it },
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
                    placeholder = { Text("在这里输入 Markdown 内容...") },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent
                    )
                )
            }
        }
    }

    if (showCreateTopicDialog) {
        AlertDialog(
            onDismissRequest = { showCreateTopicDialog = false; newTopicName = ""; newTopicProject = "" },
            title = { Text("创建主题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newTopicName, onValueChange = { newTopicName = it }, label = { Text("主题名称") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newTopicProject, onValueChange = { newTopicProject = it }, label = { Text("所属项目（可选）") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newTopicName.isNotBlank()) {
                        scope.launch {
                            ApiClient.createKBTopic(newTopicName.trim(), newTopicProject.trim(), user.id)
                            topics = ApiClient.getKBTopics(user.id)
                            showCreateTopicDialog = false; newTopicName = ""; newTopicProject = ""
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary)) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateTopicDialog = false }) { Text("取消") } }
        )
    }

    if (showCreateEntryDialog && selectedTopic != null) {
        AlertDialog(
            onDismissRequest = { showCreateEntryDialog = false; newEntryTitle = "" },
            title = { Text("创建条目") },
            text = {
                OutlinedTextField(value = newEntryTitle, onValueChange = { newEntryTitle = it }, label = { Text("条目标题") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = {
                    if (newEntryTitle.isNotBlank()) {
                        scope.launch {
                            val entry = ApiClient.createKBEntry(selectedTopic!!.id, newEntryTitle.trim(), "", emptyList(), user.id)
                            if (entry != null) {
                                entries = ApiClient.getKBEntries(selectedTopic!!.id, user.id)
                                selectedEntry = entry; editorContent = entry.content
                                subPage = KBSubPage.EDITOR
                            }
                            showCreateEntryDialog = false; newEntryTitle = ""
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary)) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateEntryDialog = false }) { Text("取消") } }
        )
    }
}
