package com.silk.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silk.shared.i18n.*
import com.silk.shared.models.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // CC Bridge state
    var ccBridgeToken by remember { mutableStateOf<String?>(null) }
    var ccBridgeConnected by remember { mutableStateOf(false) }
    var ccBridgeIp by remember { mutableStateOf<String?>(null) }
    var ccTokenVisible by remember { mutableStateOf(false) }
    var ccIsGenerating by remember { mutableStateOf(false) }
    var ccIsTesting by remember { mutableStateOf(false) }
    var ccTestResult by remember { mutableStateOf<String?>(null) }
    var ccTestGeneration by remember { mutableStateOf(0) }
    var ccShowRegenerateDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Shared logic for generating/regenerating bridge token
    val doGenerateToken: () -> Unit = {
        if (!ccIsGenerating) {
            scope.launch {
                ccIsGenerating = true
                val resp = ApiClient.generateBridgeToken(user.id)
                if (resp.success) {
                    ccBridgeToken = resp.ccBridgeToken
                    ccBridgeConnected = resp.bridgeConnected
                    ccBridgeIp = resp.bridgeIp
                }
                ccIsGenerating = false
            }
        }
    }
    
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
                // Load CC Bridge settings
                val ccResponse = ApiClient.getCcSettings(user.id)
                if (ccResponse.success) {
                    ccBridgeToken = ccResponse.ccBridgeToken
                    ccBridgeConnected = ccResponse.bridgeConnected
                    ccBridgeIp = ccResponse.bridgeIp
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

                    // Claude Code Bridge settings
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = strings.ccSettingsTitle,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = SilkColors.textPrimary
                            )

                            if (ccBridgeToken == null) {
                                // No token yet
                                Text(
                                    text = strings.ccBridgeNotConfigured,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray
                                )
                                Button(
                                    onClick = { doGenerateToken() },
                                    enabled = !ccIsGenerating,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SilkColors.primary
                                    )
                                ) {
                                    if (ccIsGenerating) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(strings.ccGenerateToken)
                                }
                            } else {
                                // Connection status
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (ccBridgeConnected) Color(0xFF4CAF50)
                                                else Color.Gray
                                            )
                                    )
                                    Text(
                                        text = if (ccBridgeConnected) strings.ccBridgeConnected
                                               else strings.ccBridgeDisconnected,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (ccBridgeConnected) Color(0xFF4CAF50)
                                                else Color.Gray
                                    )
                                }

                                // Bridge IP (when connected)
                                if (ccBridgeConnected && ccBridgeIp != null) {
                                    Text(
                                        text = "${strings.ccBridgeIpLabel} ${ccBridgeIp}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }

                                // Refresh status
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            if (!ccIsTesting) {
                                                scope.launch {
                                                    ccIsTesting = true
                                                    ccTestResult = null
                                                    val gen = ++ccTestGeneration
                                                    val resp = ApiClient.getBridgeStatus(user.id)
                                                    ccBridgeConnected = resp.bridgeConnected
                                                    ccBridgeIp = resp.bridgeIp
                                                    ccTestResult = if (resp.bridgeConnected) strings.ccTestSuccess
                                                                   else strings.ccTestFailed
                                                    ccIsTesting = false
                                                    delay(10_000)
                                                    if (ccTestGeneration == gen) {
                                                        ccTestResult = null
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !ccIsTesting
                                    ) {
                                        if (ccIsTesting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                        Text(
                                            if (ccIsTesting) strings.ccRefreshingStatus
                                            else strings.ccRefreshStatus
                                        )
                                    }
                                    ccTestResult?.let { result ->
                                        Text(
                                            text = result,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (result == strings.ccTestSuccess) Color(0xFF4CAF50)
                                                    else Color(0xFFF44336)
                                        )
                                    }
                                }

                                // Token display + copy
                                Text(
                                    text = strings.ccBridgeTokenLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.Gray
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (ccTokenVisible) ccBridgeToken ?: ""
                                               else "••••••••••••••••",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { ccTokenVisible = !ccTokenVisible },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("token", ccBridgeToken))
                                            Toast.makeText(context, strings.ccTokenCopied, Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Text(strings.ccCopyToken)
                                    }
                                }

                                // Regenerate button
                                OutlinedButton(
                                    onClick = { ccShowRegenerateDialog = true },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFFF44336)
                                    )
                                ) {
                                    Text(strings.ccRegenerateToken)
                                }

                                // Help text
                                Text(
                                    text = strings.ccBridgeHelp,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    // Regenerate confirmation dialog
                    if (ccShowRegenerateDialog) {
                        AlertDialog(
                            onDismissRequest = { ccShowRegenerateDialog = false },
                            title = { Text(strings.ccRegenerateToken) },
                            text = { Text(strings.ccRegenerateConfirm) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        ccShowRegenerateDialog = false
                                        doGenerateToken()
                                    }
                                ) {
                                    Text(strings.ccGenerateToken, color = Color(0xFFF44336))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { ccShowRegenerateDialog = false }) {
                                    Text(strings.cancelButton)
                                }
                            }
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
                }
            }
        }
    }
}
