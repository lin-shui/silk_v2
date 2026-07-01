package com.silk.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(appState: AppState) {
    var isLogin by remember { mutableStateOf(true) } // true=登录, false=注册
    var loginName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val canSubmit = canSubmitAuthForm(
        isLogin = isLogin,
        loginName = loginName,
        password = password,
        fullName = fullName,
        phoneNumber = phoneNumber,
        isLoading = isLoading
    )
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Silk") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .width(400.dp)
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                LoginCardContent(
                    isLogin = isLogin,
                    loginName = loginName,
                    password = password,
                    fullName = fullName,
                    phoneNumber = phoneNumber,
                    errorMessage = errorMessage,
                    isLoading = isLoading,
                    canSubmit = canSubmit,
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
                            val response = submitDesktopAuth(
                                isLogin = isLogin,
                                loginName = loginName,
                                password = password,
                                fullName = fullName,
                                phoneNumber = phoneNumber
                            )

                            if (response.success && response.user != null) {
                                println("✅ ${authActionLabel(isLogin)}成功: ${response.user.fullName}")
                                appState.setUser(response.user)
                            } else {
                                errorMessage = response.message
                            }
                            isLoading = false
                        }
                    },
                    onToggleMode = {
                        isLogin = !isLogin
                        errorMessage = ""
                    }
                )
            }
        }
    }
}

@Composable
private fun LoginCardContent(
    isLogin: Boolean,
    loginName: String,
    password: String,
    fullName: String,
    phoneNumber: String,
    errorMessage: String,
    isLoading: Boolean,
    canSubmit: Boolean,
    onLoginNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onFullNameChange: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleMode: () -> Unit
) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = authActionLabel(isLogin),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        CredentialFields(
            loginName = loginName,
            password = password,
            isLoading = isLoading,
            onLoginNameChange = onLoginNameChange,
            onPasswordChange = onPasswordChange
        )

        if (!isLogin) {
            RegistrationFields(
                fullName = fullName,
                phoneNumber = phoneNumber,
                isLoading = isLoading,
                onFullNameChange = onFullNameChange,
                onPhoneNumberChange = onPhoneNumberChange
            )
        }

        if (errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        AuthSubmitButton(
            isLogin = isLogin,
            isLoading = isLoading,
            enabled = canSubmit,
            onClick = onSubmit
        )

        TextButton(
            onClick = onToggleMode,
            enabled = !isLoading
        ) {
            Text(toggleAuthModeLabel(isLogin))
        }
    }
}

@Composable
private fun CredentialFields(
    loginName: String,
    password: String,
    isLoading: Boolean,
    onLoginNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit
) {
    OutlinedTextField(
        value = loginName,
        onValueChange = onLoginNameChange,
        label = { Text("登录名") },
        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !isLoading
    )

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("密码") },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !isLoading
    )
}

@Composable
private fun RegistrationFields(
    fullName: String,
    phoneNumber: String,
    isLoading: Boolean,
    onFullNameChange: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit
) {
    OutlinedTextField(
        value = fullName,
        onValueChange = onFullNameChange,
        label = { Text("姓名") },
        leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !isLoading
    )

    OutlinedTextField(
        value = phoneNumber,
        onValueChange = onPhoneNumberChange,
        label = { Text("手机号") },
        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !isLoading
    )
}

@Composable
private fun AuthSubmitButton(
    isLogin: Boolean,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(authActionLabel(isLogin))
    }
}

private fun canSubmitAuthForm(
    isLogin: Boolean,
    loginName: String,
    password: String,
    fullName: String,
    phoneNumber: String,
    isLoading: Boolean
): Boolean {
    if (isLoading || loginName.isBlank() || password.isBlank()) {
        return false
    }

    return isLogin || (fullName.isNotBlank() && phoneNumber.isNotBlank())
}

private suspend fun submitDesktopAuth(
    isLogin: Boolean,
    loginName: String,
    password: String,
    fullName: String,
    phoneNumber: String
): AuthResponse = withContext(Dispatchers.IO) {
    if (isLogin) {
        ApiClient.login(loginName, password)
    } else {
        ApiClient.register(loginName, fullName, phoneNumber, password)
    }
}

private fun authActionLabel(isLogin: Boolean): String {
    return if (isLogin) "登录" else "注册"
}

private fun toggleAuthModeLabel(isLogin: Boolean): String {
    return if (isLogin) "没有账号？点击注册" else "已有账号？点击登录"
}
