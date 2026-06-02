package com.silk.android

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class LoginFormState(
    val isLogin: Boolean,
    val loginName: String,
    val password: String,
    val fullName: String,
    val phoneNumber: String,
    val errorMessage: String,
    val isLoading: Boolean,
) {
    val submitEnabled: Boolean =
        !isLoading && loginName.isNotBlank() && password.isNotBlank() &&
            (isLogin || (fullName.isNotBlank() && phoneNumber.isNotBlank()))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(appState: AppState) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    
    // 检查是否是意外到达登录页（用户没有明确退出登录）
    // 如果是，自动恢复到群组列表页面
    LaunchedEffect(Unit) {
        println("🔍 [LoginScreen] 检查是否需要恢复会话...")
        val restored = appState.checkAndRestoreSession()
        if (restored) {
            println("✅ [LoginScreen] 会话已恢复，跳转到群组列表")
        }
    }
    
    var isLogin by remember { mutableStateOf(true) }
    var loginName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    // 升级相关状态
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf<ApkDownloader.DownloadState>(ApkDownloader.DownloadState.Idle) }

    val formState = LoginFormState(
        isLogin = isLogin,
        loginName = loginName,
        password = password,
        fullName = fullName,
        phoneNumber = phoneNumber,
        errorMessage = errorMessage,
        isLoading = isLoading,
    )

    LoginScreenContent(
        formState = formState,
        focusManager = focusManager,
        onLoginNameChange = {
            loginName = it
            errorMessage = ""
        },
        onPasswordChange = {
            password = it
            errorMessage = ""
        },
        onFullNameChange = {
            fullName = it
            errorMessage = ""
        },
        onPhoneNumberChange = {
            phoneNumber = it
            errorMessage = ""
        },
        onSubmit = {
            scope.launch {
                isLoading = true
                errorMessage = ""
                try {
                    val response = if (isLogin) {
                        ApiClient.login(loginName, password)
                    } else {
                        ApiClient.register(loginName, fullName, phoneNumber, password)
                    }
                    if (response.success && response.user != null) {
                        println("${if (isLogin) "登录" else "注册"}成功: ${response.user.fullName}")
                        appState.setUser(response.user)
                    } else {
                        errorMessage = response.message
                    }
                } finally {
                    isLoading = false
                }
            }
        },
        onToggleMode = {
            if (!isLoading) {
                isLogin = !isLogin
                errorMessage = ""
            }
        },
        onShowUpgradeDialog = { showUpgradeDialog = true },
    )

    LoginUpgradeDialog(
        showUpgradeDialog = showUpgradeDialog,
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
                    if (state is ApkDownloader.DownloadState.Success) {
                        ApkDownloader.installApk(context, state.file)?.let { message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun LoginScreenContent(
    formState: LoginFormState,
    focusManager: androidx.compose.ui.focus.FocusManager,
    onLoginNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onFullNameChange: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleMode: () -> Unit,
    onShowUpgradeDialog: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SilkColors.background,
                        SilkColors.secondary.copy(alpha = 0.3f),
                        SilkColors.background,
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LoginBrandHeader()
            LoginFormCard(
                formState = formState,
                focusManager = focusManager,
                onLoginNameChange = onLoginNameChange,
                onPasswordChange = onPasswordChange,
                onFullNameChange = onFullNameChange,
                onPhoneNumberChange = onPhoneNumberChange,
                onSubmit = onSubmit,
                onToggleMode = onToggleMode,
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = onShowUpgradeDialog,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SilkColors.success),
                modifier = Modifier.fillMaxWidth(0.6f),
            ) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("检查更新")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Silk © 2026",
                style = MaterialTheme.typography.bodySmall,
                color = SilkColors.textLight,
            )
        }
    }
}

@Composable
private fun LoginBrandHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = 32.dp),
    ) {
        Text(
            text = "SILK",
            style = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp,
            ),
            color = SilkColors.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "智能协作平台",
            style = MaterialTheme.typography.bodyLarge,
            color = SilkColors.textSecondary,
        )
    }
}

@Composable
private fun LoginFormCard(
    formState: LoginFormState,
    focusManager: androidx.compose.ui.focus.FocusManager,
    onLoginNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onFullNameChange: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleMode: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = MaterialTheme.shapes.large,
                ambientColor = SilkColors.primary.copy(alpha = 0.1f),
                spotColor = SilkColors.primary.copy(alpha = 0.2f),
            ),
        colors = CardDefaults.cardColors(containerColor = SilkColors.surfaceElevated),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (formState.isLogin) "欢迎回来" else "创建账户",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = SilkColors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Divider(
                color = SilkColors.divider,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedTextField(
                value = formState.loginName,
                onValueChange = onLoginNameChange,
                label = { Text("登录名") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !formState.isLoading,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
            )
            OutlinedTextField(
                value = formState.password,
                onValueChange = onPasswordChange,
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !formState.isLoading,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (formState.isLogin) ImeAction.Done else ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = {
                        if (formState.isLogin) {
                            focusManager.clearFocus()
                        }
                    },
                ),
            )
            LoginRegistrationFields(
                formState = formState,
                focusManager = focusManager,
                onFullNameChange = onFullNameChange,
                onPhoneNumberChange = onPhoneNumberChange,
            )
            if (formState.errorMessage.isNotEmpty()) {
                Text(
                    text = formState.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = formState.submitEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SilkColors.primary,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    disabledContainerColor = SilkColors.primary.copy(alpha = 0.5f),
                    disabledContentColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (formState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (formState.isLoading) "处理中..." else if (formState.isLogin) "登录" else "注册",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(
                onClick = onToggleMode,
                modifier = Modifier.fillMaxWidth(),
                enabled = !formState.isLoading,
                colors = ButtonDefaults.textButtonColors(contentColor = SilkColors.primary),
            ) {
                Text(
                    text = if (formState.isLogin) "没有账号？点击注册" else "已有账号？点击登录",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun LoginRegistrationFields(
    formState: LoginFormState,
    focusManager: androidx.compose.ui.focus.FocusManager,
    onFullNameChange: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
) {
    if (formState.isLogin) return

    OutlinedTextField(
        value = formState.fullName,
        onValueChange = onFullNameChange,
        label = { Text("姓名") },
        modifier = Modifier.fillMaxWidth(),
        enabled = !formState.isLoading,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
        ),
    )

    OutlinedTextField(
        value = formState.phoneNumber,
        onValueChange = onPhoneNumberChange,
        label = { Text("手机号") },
        modifier = Modifier.fillMaxWidth(),
        enabled = !formState.isLoading,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { focusManager.clearFocus() },
        ),
    )
}

@Composable
private fun LoginUpgradeDialog(
    showUpgradeDialog: Boolean,
    downloadState: ApkDownloader.DownloadState,
    onDismiss: () -> Unit,
    onStartDownload: () -> Unit,
) {
    if (!showUpgradeDialog) return

    UpgradeDialog(
        downloadState = downloadState,
        onDismiss = onDismiss,
        onStartDownload = onStartDownload,
    )
}
