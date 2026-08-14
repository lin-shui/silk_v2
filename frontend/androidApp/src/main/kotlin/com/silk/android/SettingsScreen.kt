package com.silk.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silk.shared.i18n.getStrings
import com.silk.shared.models.Language
import com.silk.shared.models.UserSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(appState: AppState) {
    val scope = rememberCoroutineScope()
    val user = appState.currentUser ?: return
    
    var settings by remember { mutableStateOf<UserSettings?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    
    // Local state for editing
    var selectedLanguage by remember { mutableStateOf<Language>(Language.CHINESE) }
    var defaultInstruction by remember { mutableStateOf("") }

    // Load settings on mount
    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            try {
                val response = ApiClient.getUserSettings(user.id)
                if (response.success && response.settings != null) {
                    settings = response.settings!!
                    selectedLanguage = response.settings!!.language
                    defaultInstruction = response.settings!!.defaultAgentInstruction
                } else {
                    // Use defaults
                    selectedLanguage = Language.CHINESE
                    defaultInstruction = "You are a helpful technical research assistant. "
                }
            } catch (e: Exception) {
                println("加载设置失败: $e")
                // Use defaults on error
                selectedLanguage = Language.CHINESE
                defaultInstruction = "You are a helpful technical research assistant. "
            } finally {
                isLoading = false
            }
        }
    }
    
    // Get strings based on selected language
    val strings = getStrings(selectedLanguage)
    
    Scaffold(
        topBar = {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            IconButton(
                                onClick = { appState.navigateBack() },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                            
                            Text(
                                text = strings.settingsTitle,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                ),
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
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
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Language selector
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = strings.languageLabel,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = SilkColors.textPrimary
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FilterChip(
                                selected = selectedLanguage == Language.ENGLISH,
                                onClick = { selectedLanguage = Language.ENGLISH },
                                label = { Text(strings.languageEnglish) },
                                modifier = Modifier.weight(1f)
                            )
                            
                            FilterChip(
                                selected = selectedLanguage == Language.CHINESE,
                                onClick = { selectedLanguage = Language.CHINESE },
                                label = { Text(strings.languageChinese) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    
                    // Default agent instruction
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = strings.defaultAgentInstructionLabel,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = SilkColors.textPrimary
                        )
                        
                        OutlinedTextField(
                            value = defaultInstruction,
                            onValueChange = { defaultInstruction = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 5,
                            maxLines = 10,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SilkColors.primary,
                                unfocusedBorderColor = SilkColors.border
                            )
                        )
                    }

                    // Save message
                    if (saveMessage != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (saveMessage?.contains("成功") == true || saveMessage?.contains("success") == true)
                                    Color(0xFFE8F5E9)
                                else
                                    Color(0xFFFFEBEE)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = saveMessage ?: "",
                                modifier = Modifier.padding(16.dp),
                                color = if (saveMessage?.contains("成功") == true || saveMessage?.contains("success") == true)
                                    Color(0xFF2E7D32)
                                else
                                    Color(0xFFC62828)
                            )
                        }
                    }
                    
                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { appState.navigateBack() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SilkColors.textPrimary
                            )
                        ) {
                            Text(strings.cancelButton)
                        }
                        
                        Button(
                            onClick = {
                                if (!isSaving) {
                                    scope.launch {
                                        isSaving = true
                                        saveMessage = null
                                        try {
                                            val response = ApiClient.updateUserSettings(
                                                userId = user.id,
                                                language = selectedLanguage,
                                                defaultAgentInstruction = defaultInstruction
                                            )
                                            if (response.success) {
                                                settings = response.settings
                                                saveMessage = strings.settingsSaved
                                            } else {
                                                saveMessage = strings.settingsSaveError
                                            }
                                        } catch (e: Exception) {
                                            println("保存设置失败: $e")
                                            saveMessage = strings.settingsSaveError
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SilkColors.primary
                            )
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(strings.saveButton)
                        }
                    }
                    
                    // 分隔线
                    Divider(
                        color = SilkColors.divider,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    
                    // 退出登录
                    var showLogoutConfirm by remember { mutableStateOf(false) }
                    
                    if (showLogoutConfirm) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFF8F8)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "确认退出登录？",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { showLogoutConfirm = false }
                                    ) {
                                        Text("取消")
                                    }
                                    Button(
                                        onClick = {
                                            showLogoutConfirm = false
                                            scope.launch {
                                                ApiClient.logout()
                                                appState.logout()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Text("退出登录")
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showLogoutConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("退出登录")
                        }
                    }
                    
                    // ─── 注销账号 ───
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    var isDeleting by remember { mutableStateOf(false) }
                    var deleteMessage by remember { mutableStateOf<String?>(null) }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF8F8)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "危险区域",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "注销账号将删除您的所有数据（包括群组、联系人、聊天记录等），且无法恢复。该操作不可撤销。",
                                style = MaterialTheme.typography.bodySmall,
                                color = SilkColors.textSecondary
                            )
                            
                            deleteMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (msg.contains("成功")) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                            }
                            
                            if (!showDeleteConfirm) {
                                Button(
                                    onClick = {
                                        showDeleteConfirm = true
                                        deleteMessage = null
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("注销账号")
                                }
                            } else {
                                Text(
                                    text = "确认注销？此操作不可撤销！",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            showDeleteConfirm = false
                                            deleteMessage = null
                                        }
                                    ) {
                                        Text("取消")
                                    }
                                    Button(
                                        onClick = {
                                            if (!isDeleting) {
                                                scope.launch {
                                                    isDeleting = true
                                                    try {
                                                        val response = ApiClient.deleteAccount(user.id)
                                                        if (response.success) {
                                                            deleteMessage = "账号已注销"
                                                            delay(1500)
                                                            appState.logout()
                                                        } else {
                                                            deleteMessage = response.message
                                                        }
                                                    } catch (e: Exception) {
                                                        deleteMessage = "注销失败: ${e.message}"
                                                    } finally {
                                                        isDeleting = false
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !isDeleting,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        if (isDeleting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = Color.White,
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text("确认注销")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
