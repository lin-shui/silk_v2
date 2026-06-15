package com.silk.android

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import android.app.Activity
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(appState: AppState) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val activity = LocalContext.current as? Activity
    
    var loginName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    // 华为 OAuth WebView 状态
    var showHuaweiWebView by remember { mutableStateOf(false) }
    var huaweiOAuthState by remember { mutableStateOf("") }
    var huaweiOAuthUrl by remember { mutableStateOf("") }
    var huaweiOAuthRedirectUri by remember { mutableStateOf("") }
    // true=登录模式, false=绑定模式（老账号迁移）
    var huaweiOAuthMode by remember { mutableStateOf(true) }
    
    // 防止重复处理回调（shouldOverrideUrlLoading 和 onPageStarted 都会触发）
    var huaweiOAuthHandled = false
    
    // 绑定引导对话框
    var showBindPrompt by remember { mutableStateOf(false) }
    // 等待绑定的已登录用户
    var pendingUser by remember { mutableStateOf<User?>(null) }

    // 华为 OAuth 回调处理器
    fun handleHuaweiOAuthCallback(url: String) {
        if (huaweiOAuthHandled) return  // 已处理过，跳过重复回调
        println("🔑 [华为OAuth] 回调URL: $url")
        if (!url.contains("code=")) {
            println("❌ [华为OAuth] URL中没有code参数，可能是错误")
            showHuaweiWebView = false
            errorMessage = "华为登录失败，请重试"
            return
        }
        // 提取 code 参数（URL解码）
        val rawCode = try {
            val u = java.net.URL(url)
            val query = u.query ?: ""
            query.split("&").firstOrNull { it.startsWith("code=") }?.substringAfter("code=")
        } catch (e: Exception) {
            """code=([^&]+)""".toRegex().find(url)?.groupValues?.get(1)
        }
        if (rawCode.isNullOrBlank()) {
            println("❌ [华为OAuth] 无法提取code")
            showHuaweiWebView = false
            errorMessage = "获取授权码失败"
            return
        }
        val code = try { java.net.URLDecoder.decode(rawCode, "UTF-8") } catch (e: Exception) { rawCode }
        println("🔑 [华为OAuth] 获取到code (len=${code.length})，开始登录...")
        huaweiOAuthHandled = true
        showHuaweiWebView = false
        
        scope.launch {
            isLoading = true
            errorMessage = ""
            try {
                if (huaweiOAuthMode) {
                    // 登录模式
                    val response = ApiClient.huaweiWebLogin(code, huaweiOAuthRedirectUri)
                    if (response.success && response.user != null) {
                        println("✅ [华为登录] 成功: ${response.user.fullName}, isNewUser=${response.isNewUser}")
                        appState.setHuaweiSession(
                            user = response.user,
                            accessToken = response.accessToken ?: "",
                            refreshToken = response.refreshToken ?: "",
                            isNewUser = response.isNewUser
                        )
                    } else {
                        errorMessage = response.message.ifBlank { "华为账号登录失败" }
                    }
                } else {
                    // 绑定模式（老账号迁移）
                    val response = ApiClient.huaweiBind(code, huaweiOAuthRedirectUri)
                    if (response.success) {
                        println("✅ [华为绑定] 成功")
                        errorMessage = ""
                        showBindPrompt = false
                        // 绑定成功后导航到主界面
                        val user = pendingUser
                        if (user != null) {
                            appState.setUser(user)
                            pendingUser = null
                        }
                        Toast.makeText(context, "华为账号绑定成功", Toast.LENGTH_SHORT).show()
                    } else {
                        errorMessage = response.message.ifBlank { "华为账号绑定失败" }
                    }
                }
            } catch (e: Exception) {
                println("❌ [华为登录] 失败: ${e.message}")
                errorMessage = "华为登录失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
    
    // 检查是否是意外到达登录页（用户没有明确退出登录）
    // 如果是，自动恢复到群组列表页面
    LaunchedEffect(Unit) {
        println("🔍 [LoginScreen] 检查是否需要恢复会话...")
        val restored = appState.checkAndRestoreSession()
        if (restored) {
            println("✅ [LoginScreen] 会话已恢复，跳转到群组列表")
        }
    }
    
    // 升级相关状态
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf<ApkDownloader.DownloadState>(ApkDownloader.DownloadState.Idle) }
    
    // Silk 渐变背景
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SilkColors.background,
                        SilkColors.secondary.copy(alpha = 0.3f),
                        SilkColors.background
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
            verticalArrangement = Arrangement.Center
        ) {
            // Logo 区域
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                Text(
                    text = "SILK",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp
                    ),
                    color = SilkColors.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "智能协作平台",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SilkColors.textSecondary
                )
            }
            
            // 登录卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 8.dp,
                        shape = MaterialTheme.shapes.large,
                        ambientColor = SilkColors.primary.copy(alpha = 0.1f),
                        spotColor = SilkColors.primary.copy(alpha = 0.2f)
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = SilkColors.surfaceElevated
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 标题
                    Text(
                        text = "欢迎回来",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SilkColors.textPrimary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    
                    Divider(
                        color = SilkColors.divider,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    
                    // 登录名
                    OutlinedTextField(
                        value = loginName,
                        onValueChange = { loginName = it; errorMessage = "" },
                        label = { Text("登录名") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        )
                    )
                    
                    // 密码
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = "" },
                        label = { Text("密码") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        )
                    )
                    
                    // 错误提示
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 登录按钮 - Silk 金色风格
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                errorMessage = ""
                                
                                try {
                                    val response = ApiClient.login(loginName, password)
                                    
                                    if (response.success && response.user != null) {
                                        println("登录成功: ${response.user.fullName}")
                                        pendingUser = response.user
                                        showBindPrompt = true  // 停留在此页，先问是否绑定
                                    } else {
                                        errorMessage = response.message
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "登录失败: ${e.message}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = !isLoading && loginName.isNotBlank() && password.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SilkColors.primary,
                            contentColor = androidx.compose.ui.graphics.Color.White,
                            disabledContainerColor = SilkColors.primary.copy(alpha = 0.5f),
                            disabledContentColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = androidx.compose.ui.graphics.Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isLoading) "登录中..." else "登录",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    
                    // 分隔线
                    Divider(
                        color = SilkColors.divider,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    
                    // 华为账号登录按钮
                    Button(
                        onClick = {
                            // 使用 WebView 方式进行华为 OAuth 登录
                            val clientId = BuildConfig.HUAWEI_OAUTH_CLIENT_ID.ifBlank { "117992035" }
                            huaweiOAuthRedirectUri = "https://${BuildConfig.BACKEND_HOST}:${BuildConfig.FRONTEND_HTTP_PORT}"
                            val state = (1..32).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                            huaweiOAuthState = state
                            val authUrl = buildString {
                                append("https://oauth-login.cloud.huawei.com/oauth2/v3/authorize")
                                append("?client_id=").append(java.net.URLEncoder.encode(clientId, "UTF-8"))
                                append("&response_type=code")
                                append("&redirect_uri=").append(java.net.URLEncoder.encode(huaweiOAuthRedirectUri, "UTF-8"))
                                append("&scope=openid+profile")
                                append("&state=").append(state)
                            }
                            println("🔑 [华为OAuth] 打开 WebView: $authUrl")
                            huaweiOAuthUrl = authUrl
                            huaweiOAuthMode = true  // 登录模式
                            showHuaweiWebView = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFCF0A2C),  // 华为红
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFCF0A2C).copy(alpha = 0.5f),
                            disabledContentColor = Color.White.copy(alpha = 0.7f)
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isLoading) "华为登录中..." else "使用华为帐号登录",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            
            // 升级按钮
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = { showUpgradeDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SilkColors.success
                ),
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Icon(
                    Icons.Default.SystemUpdate, 
                    contentDescription = null, 
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("检查更新")
            }
            
            // 底部版权信息
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Silk © 2026",
                style = MaterialTheme.typography.bodySmall,
                color = SilkColors.textLight
            )
        }
        
        // 升级对话框
        if (showUpgradeDialog) {
            UpgradeDialog(
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
                            
                            // 下载成功后自动安装
                            if (state is ApkDownloader.DownloadState.Success) {
                                try {
                                    ApkDownloader.installApk(context, state.file)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "启动安装失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            )
        }
        
        // 华为新用户昵称设置对话框
        if (appState.showNicknameDialog) {
            NicknameSetupDialog(
                currentName = appState.nicknameToSet,
                onConfirm = { newName ->
                    scope.launch {
                        val user = appState.currentUser ?: return@launch
                        try {
                            val response = ApiClient.updateUserProfile(user.id, newName)
                            if (response.success) {
                                appState.updateCurrentUserNickname(newName)
                                appState.showNicknameDialog = false
                                appState.navigateTo(Scene.GROUP_LIST)
                            } else {
                                Toast.makeText(context, "昵称设置失败: ${response.message}", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "昵称设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDismiss = {
                    // 即使不设置昵称，也允许进入主界面
                    appState.showNicknameDialog = false
                    appState.navigateTo(Scene.GROUP_LIST)
                }
            )
        }
        
        // 华为 OAuth WebView 对话框
        if (showHuaweiWebView && huaweiOAuthUrl.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = {
                    showHuaweiWebView = false
                    huaweiOAuthHandled = false
                },
                title = {
                    Text(
                        text = "华为帐号登录",
                        fontWeight = FontWeight.Bold,
                        color = SilkColors.primary
                    )
                },
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(480.dp)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            println("🔑 [华为OAuth] WebView加载: $url")
                                            if (url != null && url.contains("code=")) {
                                                handleHuaweiOAuthCallback(url)
                                            }
                                        }
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val url = request?.url?.toString() ?: return false
                                            println("🔑 [华为OAuth] 拦截URL: $url")
                                            if (url.contains("code=")) {
                                                handleHuaweiOAuthCallback(url)
                                                return true
                                            }
                                            return false
                                        }
                                    }
                                    loadUrl(huaweiOAuthUrl)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showHuaweiWebView = false; huaweiOAuthHandled = false }) {
                        Text("取消", color = SilkColors.textSecondary)
                    }
                }
            )
        }
        
        // 华为账号绑定引导对话框
        if (showBindPrompt) {
            AlertDialog(
                onDismissRequest = {
                    showBindPrompt = false
                    val user = pendingUser
                    if (user != null) {
                        appState.setUser(user)
                        pendingUser = null
                    }
                },
                title = {
                    Text(
                        text = "绑定华为账号",
                        fontWeight = FontWeight.Bold,
                        color = SilkColors.primary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "将华为账号绑定到当前账户后，下次可直接点击\"使用华为帐号登录\"一键登录，无需再输入密码。",
                            style = MaterialTheme.typography.bodySmall,
                            color = SilkColors.textSecondary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBindPrompt = false
                            // 以绑定模式打开华为 OAuth WebView
                            val clientId = BuildConfig.HUAWEI_OAUTH_CLIENT_ID.ifBlank { "117992035" }
                            huaweiOAuthRedirectUri = "https://${BuildConfig.BACKEND_HOST}:${BuildConfig.FRONTEND_HTTP_PORT}"
                            val state = (1..32).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                            huaweiOAuthState = state
                            huaweiOAuthMode = false  // 绑定模式
                            val authUrl = buildString {
                                append("https://oauth-login.cloud.huawei.com/oauth2/v3/authorize")
                                append("?client_id=").append(java.net.URLEncoder.encode(clientId, "UTF-8"))
                                append("&response_type=code")
                                append("&redirect_uri=").append(java.net.URLEncoder.encode(huaweiOAuthRedirectUri, "UTF-8"))
                                append("&scope=openid+profile")
                                append("&state=").append(state)
                            }
                            huaweiOAuthUrl = authUrl
                            showHuaweiWebView = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFCF0A2C)  // 华为红
                        )
                    ) {
                        Text("立即绑定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showBindPrompt = false
                        val user = pendingUser
                        if (user != null) {
                            appState.setUser(user)
                            pendingUser = null
                        }
                    }) {
                        Text("稍后再说", color = SilkColors.textSecondary)
                    }
                }
            )
        }
    }
}

/**
 * 华为新用户昵称设置对话框
 * 首次通过华为账号登录后，让用户设置/确认显示昵称
 */
@Composable
fun NicknameSetupDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var nickname by remember { mutableStateOf(currentName) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "设置昵称",
                fontWeight = FontWeight.Bold,
                color = SilkColors.primary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "请设置您的显示昵称，好友将看到此名称",
                    style = MaterialTheme.typography.bodySmall,
                    color = SilkColors.textSecondary
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it; errorMessage = "" },
                    label = { Text("昵称") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                    singleLine = true
                )
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = nickname.trim()
                    if (trimmed.isBlank()) {
                        errorMessage = "昵称不能为空"
                        return@Button
                    }
                    if (trimmed.length > 50) {
                        errorMessage = "昵称不能超过50个字符"
                        return@Button
                    }
                    isSaving = true
                    onConfirm(trimmed)
                },
                enabled = !isSaving && nickname.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SilkColors.primary,
                    contentColor = Color.White
                )
            ) {
                Text(if (isSaving) "保存中..." else "确认")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
                Text("跳过", color = SilkColors.textSecondary)
            }
        }
    )
}

