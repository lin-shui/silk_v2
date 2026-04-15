package com.silk.android

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseScreen(appState: AppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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

    Row(modifier = Modifier.fillMaxSize()) {
        // Left panel: topics + entries
        Column(
            modifier = Modifier.width(280.dp).fillMaxHeight()
        ) {
            // Topics header
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("知识库", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { showCreateTopicDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "创建主题", tint = SilkColors.primary)
                }
            }
            Divider(color = SilkColors.divider)

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().weight(0.4f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(0.4f)) {
                    items(topics) { topic ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedTopic = topic; selectedEntry = null; editorContent = ""
                                scope.launch { entries = ApiClient.getKBEntries(topic.id, user.id) }
                            },
                            color = if (selectedTopic?.id == topic.id) SilkColors.primaryLight.copy(alpha = 0.3f) else Color.Transparent
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(topic.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (topic.project.isNotBlank()) {
                                    Text(topic.project, style = MaterialTheme.typography.bodySmall, color = SilkColors.textLight)
                                }
                            }
                        }
                        Divider(color = SilkColors.divider)
                    }
                }
            }

            Divider(thickness = 2.dp, color = SilkColors.border)

            // Entries header
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selectedTopic?.name ?: "条目", style = MaterialTheme.typography.labelLarge)
                if (selectedTopic != null) {
                    IconButton(onClick = { showCreateEntryDialog = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "创建条目", tint = SilkColors.primary)
                    }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(0.6f)) {
                if (selectedTopic == null) {
                    item {
                        Text("请先选择主题", modifier = Modifier.padding(16.dp), color = SilkColors.textLight, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    items(entries) { entry ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedEntry = entry; editorContent = entry.content
                            },
                            color = if (selectedEntry?.id == entry.id) SilkColors.primaryLight.copy(alpha = 0.2f) else Color.Transparent
                        ) {
                            Text(entry.title, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        Divider(color = SilkColors.divider)
                    }
                }
            }
        }

        Divider(
            color = SilkColors.border,
            modifier = Modifier.fillMaxHeight().width(1.dp)
        )

        // Right panel: editor
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (selectedEntry != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(selectedEntry!!.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                ApiClient.updateKBEntry(selectedEntry!!.id, null, editorContent, null, user.id)
                                entries = ApiClient.getKBEntries(selectedTopic!!.id, user.id)
                                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                isSaving = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
                        enabled = !isSaving
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isSaving) "保存中..." else "保存")
                    }
                }
                Divider(color = SilkColors.divider)
                OutlinedTextField(
                    value = editorContent,
                    onValueChange = { editorContent = it },
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    placeholder = { Text("在这里输入 Markdown 内容...") },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent
                    )
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📚", fontSize = 48.sp)
                        Spacer(Modifier.height(16.dp))
                        Text("选择或创建条目开始编辑", color = SilkColors.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        Text("内容将自动归类到 Obsidian 知识库", color = SilkColors.textLight, style = MaterialTheme.typography.bodySmall)
                    }
                }
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
