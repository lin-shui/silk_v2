package com.silk.desktop

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silk.shared.i18n.getStrings
import com.silk.shared.models.Language
import com.silk.shared.models.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(appState: AppState) {
    val scope = rememberCoroutineScope()
    val user = appState.currentUser ?: return
    
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    
    // Local state for editing
    var selectedLanguage by remember { mutableStateOf<Language>(Language.CHINESE) }
    var defaultInstruction by remember { mutableStateOf("") }
    
    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            val result = loadDesktopSettings(user.id)
            selectedLanguage = result.language
            defaultInstruction = result.defaultInstruction
            isLoading = false
        }
    }
    
    val strings = getStrings(selectedLanguage)
    
    Scaffold(
        topBar = {
            SettingsTopBar(
                title = strings.settingsTitle,
                onBack = { appState.navigateBack() }
            )
        }
    ) { padding ->
        SettingsContent(
            padding = padding,
            strings = strings,
            selectedLanguage = selectedLanguage,
            defaultInstruction = defaultInstruction,
            isLoading = isLoading,
            isSaving = isSaving,
            saveMessage = saveMessage,
            onLanguageSelected = { selectedLanguage = it },
            onInstructionChange = { defaultInstruction = it },
            onCancel = { appState.navigateBack() },
            onSave = {
                if (isSaving) {
                    return@SettingsContent
                }

                scope.launch {
                    isSaving = true
                    saveMessage = null
                    val saved = saveDesktopSettings(
                        userId = user.id,
                        language = selectedLanguage,
                        defaultInstruction = defaultInstruction
                    )
                    saveMessage = if (saved) strings.settingsSaved else strings.settingsSaveError
                    isSaving = false
                }
            }
        )
    }
}

private data class DesktopSettingsLoadResult(
    val language: Language,
    val defaultInstruction: String
)

private const val DEFAULT_DESKTOP_AGENT_INSTRUCTION = "You are a helpful technical research assistant. "

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
    title: String,
    onBack: () -> Unit
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Text(title)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
private fun SettingsContent(
    padding: androidx.compose.foundation.layout.PaddingValues,
    strings: com.silk.shared.i18n.Strings,
    selectedLanguage: Language,
    defaultInstruction: String,
    isLoading: Boolean,
    isSaving: Boolean,
    saveMessage: String?,
    onLanguageSelected: (Language) -> Unit,
    onInstructionChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                LanguageSettingsSection(
                    strings = strings,
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = onLanguageSelected
                )
                DefaultInstructionSection(
                    label = strings.defaultAgentInstructionLabel,
                    value = defaultInstruction,
                    onValueChange = onInstructionChange
                )
                SettingsSaveMessageCard(saveMessage = saveMessage)
                SettingsActionRow(
                    strings = strings,
                    isSaving = isSaving,
                    onCancel = onCancel,
                    onSave = onSave
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSettingsSection(
    strings: com.silk.shared.i18n.Strings,
    selectedLanguage: Language,
    onLanguageSelected: (Language) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(strings.languageLabel)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilterChip(
                selected = selectedLanguage == Language.ENGLISH,
                onClick = { onLanguageSelected(Language.ENGLISH) },
                label = { Text(strings.languageEnglish) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = selectedLanguage == Language.CHINESE,
                onClick = { onLanguageSelected(Language.CHINESE) },
                label = { Text(strings.languageChinese) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DefaultInstructionSection(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            maxLines = 10,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold
        )
    )
}

@Composable
private fun SettingsSaveMessageCard(saveMessage: String?) {
    if (saveMessage == null) {
        return
    }

    val isSuccess = isSuccessSettingsMessage(saveMessage)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSuccess) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = saveMessage,
            modifier = Modifier.padding(16.dp),
            color = if (isSuccess) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }
        )
    }
}

@Composable
private fun SettingsActionRow(
    strings: com.silk.shared.i18n.Strings,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        ) {
            Text(strings.cancelButton)
        }

        Button(
            onClick = onSave,
            modifier = Modifier.weight(1f),
            enabled = !isSaving
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(strings.saveButton)
        }
    }
}

private suspend fun loadDesktopSettings(userId: String): DesktopSettingsLoadResult {
    val response = withContext(Dispatchers.IO) {
        ApiClient.getUserSettings(userId)
    }
    val loadedSettings = response.settings
    return if (response.success && loadedSettings != null) {
        DesktopSettingsLoadResult(
            language = loadedSettings.language,
            defaultInstruction = loadedSettings.defaultAgentInstruction
        )
    } else {
        DesktopSettingsLoadResult(
            language = Language.CHINESE,
            defaultInstruction = DEFAULT_DESKTOP_AGENT_INSTRUCTION
        )
    }
}

private suspend fun saveDesktopSettings(
    userId: String,
    language: Language,
    defaultInstruction: String
): Boolean = withContext(Dispatchers.IO) {
    ApiClient.updateUserSettings(
        userId = userId,
        language = language,
        defaultAgentInstruction = defaultInstruction
    ).success
}

private fun isSuccessSettingsMessage(message: String): Boolean {
    return message.contains("成功") || message.contains("success")
}
