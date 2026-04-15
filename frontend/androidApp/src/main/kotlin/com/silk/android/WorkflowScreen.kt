package com.silk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowItem(
    val id: String,
    val name: String,
    val description: String = "",
    val ownerId: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowScreen(appState: AppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()

    var workflows by remember { mutableStateOf<List<WorkflowItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(user.id) {
        isLoading = true
        workflows = ApiClient.getWorkflows(user.id)
        isLoading = false
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = SilkColors.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建工作流")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("工作流", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
            Divider(color = SilkColors.divider)

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (workflows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔗", fontSize = 48.sp)
                        Spacer(Modifier.height(16.dp))
                        Text("工作流功能开发中", color = SilkColors.textSecondary, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("可先创建工作流名称，后续将支持多机器人编排", color = SilkColors.textLight, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(workflows) { wf ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SilkColors.cardBackground)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(wf.name, style = MaterialTheme.typography.bodyLarge)
                                IconButton(onClick = {
                                    scope.launch {
                                        ApiClient.deleteWorkflow(wf.id, user.id)
                                        workflows = ApiClient.getWorkflows(user.id)
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = SilkColors.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false; newName = "" },
            title = { Text("创建工作流") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("工作流名称") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            scope.launch {
                                ApiClient.createWorkflow(newName.trim(), "", user.id)
                                workflows = ApiClient.getWorkflows(user.id)
                                showCreateDialog = false; newName = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary)
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newName = "" }) { Text("取消") }
            }
        )
    }
}
