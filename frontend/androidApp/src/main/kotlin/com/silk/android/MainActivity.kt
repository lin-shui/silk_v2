package com.silk.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope

/**
 * Silk 颜色定义 - 与 Web 前端保持一致
 * 温暖的香槟金色调，优雅的丝绸质感
 */
object SilkColors {
    // 主色调 - 温暖的香槟金
    val primary = Color(0xFFC9A86C)
    val primaryDark = Color(0xFFA8894D)
    val primaryLight = Color(0xFFE0CDA0)
    
    // 次要色调 - 奶油丝绸
    val secondary = Color(0xFFE8D5B5)
    val secondaryDark = Color(0xFFD4C4A0)
    
    // 背景色 - 温暖的奶白色
    val background = Color(0xFFFDF8F0)
    val surface = Color(0xFFFFFBF5)
    val surfaceElevated = Color(0xFFFFFFFF)
    val cardBackground = Color(0xFFFFFFFF)
    
    // 文字颜色
    val textPrimary = Color(0xFF4A4038)
    val textSecondary = Color(0xFF8A7B6A)
    val textLight = Color(0xFFB8A890)
    
    // 功能色
    val success = Color(0xFF7DAE6C)
    val warning = Color(0xFFE8B86C)
    val error = Color(0xFFD97B7B)
    val info = Color(0xFF7BA8C9)
    
    // 边框和分隔线
    val border = Color(0xFFE8E0D4)
    val divider = Color(0xFFF0E8DC)
}

/**
 * Silk 主题色彩方案
 */
private val SilkColorScheme = lightColorScheme(
    // 主色调
    primary = SilkColors.primary,
    onPrimary = Color.White,
    primaryContainer = SilkColors.primaryLight,
    onPrimaryContainer = SilkColors.textPrimary,
    
    // 次要色调
    secondary = SilkColors.secondary,
    onSecondary = SilkColors.textPrimary,
    secondaryContainer = SilkColors.secondaryDark,
    onSecondaryContainer = SilkColors.textPrimary,
    
    // 第三色调
    tertiary = SilkColors.warning,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF3E0),
    onTertiaryContainer = SilkColors.textPrimary,
    
    // 背景和表面
    background = SilkColors.background,
    onBackground = SilkColors.textPrimary,
    surface = SilkColors.surface,
    onSurface = SilkColors.textPrimary,
    surfaceVariant = SilkColors.secondary,
    onSurfaceVariant = SilkColors.textSecondary,
    
    // 轮廓
    outline = SilkColors.border,
    outlineVariant = SilkColors.divider,
    
    // 错误色
    error = SilkColors.error,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = SilkColors.textPrimary,
    
    // 其他
    inverseSurface = SilkColors.textPrimary,
    inverseOnSurface = SilkColors.background,
    inversePrimary = SilkColors.primaryLight,
    scrim = Color(0x994A4038)
)

class MainActivity : ComponentActivity() {
    // 用于检查当前场景的引用
    private var currentAppState: AppState? = null
    
    // 返回键处理回调
    private lateinit var backCallback: OnBackPressedCallback
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 注册返回键回调 - 这是最可靠的方式，优先于所有其他返回处理
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val appState = currentAppState
                println("🔙 [OnBackPressedCallback] 返回键被按下，当前场景: ${appState?.currentScene}")
                
                // 在群组列表页面时，完全阻止返回操作
                if (appState != null && appState.currentScene == Scene.GROUP_LIST && appState.currentUser != null) {
                    println("🚫 [OnBackPressedCallback] 在群组列表页面，忽略返回")
                    // 什么都不做，阻止返回
                    return
                }
                
                // 在登录页面也阻止返回（防止退出应用）
                if (appState != null && appState.currentScene == Scene.LOGIN) {
                    println("🚫 [OnBackPressedCallback] 在登录页面，忽略返回")
                    return
                }
                
                // 其他页面，执行正常返回
                if (appState != null && appState.navigateBack()) {
                    println("✅ [OnBackPressedCallback] 执行返回导航")
                } else {
                    println("🚫 [OnBackPressedCallback] 无法返回，保持当前页面")
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        
        setContent {
            SilkTheme {
                SilkApp(this, lifecycleScope) { appState ->
                    currentAppState = appState
                    println("📱 [MainActivity] AppState 已设置，当前场景: ${appState.currentScene}")
                }
            }
        }
    }
}

@Composable
fun SilkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SilkColorScheme,
        typography = Typography(
            // 可以自定义字体，这里使用默认字体但调整字重
            displayLarge = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Light,
                color = SilkColors.textPrimary
            ),
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.SemiBold,
                color = SilkColors.textPrimary
            ),
            titleLarge = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Medium,
                color = SilkColors.textPrimary
            ),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(
                color = SilkColors.textPrimary
            ),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(
                color = SilkColors.textSecondary
            ),
            labelLarge = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold
            )
        ),
        content = content
    )
}

@Composable
fun SilkApp(
    activity: MainActivity,
    scope: kotlinx.coroutines.CoroutineScope,
    onAppStateReady: (AppState) -> Unit = {}
) {
    val appState = remember { AppState(activity, scope) }
    val showUpdateDialog by appState.versionChecker.showUpdateDialog.collectAsState()
    val newVersion by appState.versionChecker.newVersionAvailable.collectAsState()
    val showDownloadDialog by appState.versionChecker.showDownloadDialog.collectAsState()
    val downloadState by appState.versionChecker.downloadState.collectAsState()

    ObserveSilkApp(appState = appState, onAppStateReady = onAppStateReady)
    SilkAppBackHandler(appState = appState)
    SilkVersionDialogs(
        appState = appState,
        showUpdateDialog = showUpdateDialog,
        newVersion = newVersion,
        showDownloadDialog = showDownloadDialog,
        downloadState = downloadState,
    )
    SilkAppContent(appState = appState)
}

@Composable
private fun ObserveSilkApp(
    appState: AppState,
    onAppStateReady: (AppState) -> Unit,
) {
    LaunchedEffect(appState) {
        onAppStateReady(appState)
    }

    LaunchedEffect(appState.versionChecker.showUpdateDialog, appState.versionChecker.newVersionAvailable) {
        println(
            "🔔 [MainActivity] 更新对话框状态: " +
                "showUpdateDialog=${appState.versionChecker.showUpdateDialog.value}, " +
                "newVersion=${appState.versionChecker.newVersionAvailable.value?.versionName ?: "null"}"
        )
    }

    DisposableEffect(Unit) {
        onDispose { appState.destroy() }
    }
}

@Composable
private fun SilkAppBackHandler(appState: AppState) {
    BackHandler(enabled = true) {
        println("🔙 [Compose BackHandler] 返回事件，场景: ${appState.currentScene}")
        when (appState.currentScene) {
            Scene.LOGIN,
            Scene.GROUP_LIST -> Unit
            else -> appState.navigateBack()
        }
    }
}

@Composable
private fun SilkVersionDialogs(
    appState: AppState,
    showUpdateDialog: Boolean,
    newVersion: AppVersionInfo?,
    showDownloadDialog: Boolean,
    downloadState: ApkDownloader.DownloadState,
) {
    if (showUpdateDialog && newVersion != null) {
        UpdateDialog(
            versionInfo = newVersion,
            onDownload = { appState.versionChecker.startDownload() },
            onSkip = { appState.versionChecker.skipThisVersion() },
            onLater = { appState.versionChecker.remindLater() },
        )
    }

    if (showDownloadDialog) {
        DownloadProgressDialog(
            downloadState = downloadState,
            onDismiss = { appState.versionChecker.dismissDownloadDialog() },
        )
    }
}

@Composable
private fun SilkAppContent(appState: AppState) {
    when {
        appState.isValidating -> SilkValidationScreen()
        appState.currentScene == Scene.LOGIN -> LoginScreen(appState)
        else -> SilkAuthenticatedContent(appState)
    }
}

@Composable
private fun SilkValidationScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = "正在验证用户信息...",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SilkAuthenticatedContent(appState: AppState) {
    val showBottomNav = appState.currentScene != Scene.CHAT_ROOM &&
        appState.currentScene != Scene.WORKFLOW_CHAT

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                SilkBottomNav(appState)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            SilkSceneHost(appState = appState)
        }
    }
}

@Composable
private fun SilkSceneHost(appState: AppState) {
    when (appState.currentTab) {
        NavTab.SILK -> SilkTabScene(appState)
        NavTab.WORKFLOW -> WorkflowTabScene(appState)
        NavTab.KNOWLEDGE_BASE -> KnowledgeBaseScreen(appState)
        NavTab.AUDIO_DUPLEX -> AudioDuplexScreen()
    }
}

@Composable
private fun SilkTabScene(appState: AppState) {
    when (appState.currentScene) {
        Scene.GROUP_LIST -> GroupListScreen(appState)
        Scene.CONTACTS -> ContactsScreen(appState)
        Scene.CHAT_ROOM -> ChatScreen(appState)
        Scene.SETTINGS -> SettingsScreen(appState)
        else -> GroupListScreen(appState)
    }
}

@Composable
private fun WorkflowTabScene(appState: AppState) {
    when (appState.currentScene) {
        Scene.WORKFLOW_CHAT -> WorkflowChatScreen(appState)
        else -> WorkflowScreen(appState)
    }
}

@Composable
fun SilkBottomNav(appState: AppState) {
    NavigationBar(
        containerColor = SilkColors.surface,
        contentColor = SilkColors.textPrimary,
        tonalElevation = 2.dp
    ) {
        NavigationBarItem(
            selected = appState.currentTab == NavTab.SILK,
            onClick = { appState.currentTab = NavTab.SILK },
            icon = { Icon(Icons.Default.Chat, contentDescription = "Silk") },
            label = { Text("Silk", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = SilkColors.primary,
                selectedTextColor = SilkColors.primary,
                unselectedIconColor = SilkColors.textLight,
                unselectedTextColor = SilkColors.textLight,
                indicatorColor = SilkColors.primaryLight.copy(alpha = 0.3f)
            )
        )
        NavigationBarItem(
            selected = appState.currentTab == NavTab.WORKFLOW,
            onClick = { appState.currentTab = NavTab.WORKFLOW },
            icon = { Icon(Icons.Default.AccountTree, contentDescription = "工作流") },
            label = { Text("工作流", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = SilkColors.primary,
                selectedTextColor = SilkColors.primary,
                unselectedIconColor = SilkColors.textLight,
                unselectedTextColor = SilkColors.textLight,
                indicatorColor = SilkColors.primaryLight.copy(alpha = 0.3f)
            )
        )
        NavigationBarItem(
            selected = appState.currentTab == NavTab.KNOWLEDGE_BASE,
            onClick = { appState.currentTab = NavTab.KNOWLEDGE_BASE },
            icon = { Icon(Icons.Default.MenuBook, contentDescription = "知识库") },
            label = { Text("知识库", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = SilkColors.primary,
                selectedTextColor = SilkColors.primary,
                unselectedIconColor = SilkColors.textLight,
                unselectedTextColor = SilkColors.textLight,
                indicatorColor = SilkColors.primaryLight.copy(alpha = 0.3f)
            )
        )
        NavigationBarItem(
            selected = appState.currentTab == NavTab.AUDIO_DUPLEX,
            onClick = { appState.currentTab = NavTab.AUDIO_DUPLEX },
            icon = { Text("📞", fontSize = 20.sp) },
            label = { Text("音频双工", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = SilkColors.primary,
                selectedTextColor = SilkColors.primary,
                unselectedIconColor = SilkColors.textLight,
                unselectedTextColor = SilkColors.textLight,
                indicatorColor = SilkColors.primaryLight.copy(alpha = 0.3f)
            )
        )
    }
}

/**
 * 版本更新提示对话框
 */
@Composable
fun UpdateDialog(
    versionInfo: AppVersionInfo,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = SilkColors.surface,
        titleContentColor = SilkColors.textPrimary,
        textContentColor = SilkColors.textSecondary,
        title = {
            Text(
                text = "🎉 发现新版本",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "新版本 ${versionInfo.versionName} 已发布！",
                    style = MaterialTheme.typography.bodyLarge
                )
                
                val fileSizeMB = versionInfo.fileSize / 1024.0 / 1024.0
                Text(
                    text = "文件大小: %.1f MB".format(fileSizeMB),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SilkColors.textSecondary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "建议更新以获得最佳体验",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SilkColors.primary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDownload,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SilkColors.primary,
                    contentColor = Color.White
                )
            ) {
                Text("立即更新")
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onSkip) {
                    Text(
                        text = "跳过此版本",
                        color = SilkColors.textSecondary
                    )
                }
                TextButton(onClick = onLater) {
                    Text(
                        text = "稍后提醒",
                        color = SilkColors.textSecondary
                    )
                }
            }
        }
    )
}

/**
 * 下载进度对话框
 */
@Composable
fun DownloadProgressDialog(
    downloadState: ApkDownloader.DownloadState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            // 下载中不允许关闭
            if (downloadState !is ApkDownloader.DownloadState.Downloading) {
                onDismiss()
            }
        },
        containerColor = SilkColors.surface,
        titleContentColor = SilkColors.textPrimary,
        textContentColor = SilkColors.textSecondary,
        title = {
            Text(
                text = when (downloadState) {
                    is ApkDownloader.DownloadState.Downloading -> "📥 下载更新"
                    is ApkDownloader.DownloadState.Success -> "✅ 下载完成"
                    is ApkDownloader.DownloadState.Error -> "❌ 下载失败"
                    else -> "下载更新"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (downloadState) {
                    is ApkDownloader.DownloadState.Downloading -> {
                        Text(
                            text = downloadState.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (downloadState.progress >= 0) {
                            LinearProgressIndicator(
                                progress = downloadState.progress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                                color = SilkColors.primary
                            )
                            Text(
                                text = "${downloadState.progress}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = SilkColors.textSecondary
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = SilkColors.primary
                            )
                        }
                    }
                    is ApkDownloader.DownloadState.Success -> {
                        Text(
                            text = "下载完成，正在启动安装...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SilkColors.success
                        )
                    }
                    is ApkDownloader.DownloadState.Error -> {
                        Text(
                            text = downloadState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SilkColors.error
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (downloadState is ApkDownloader.DownloadState.Error || 
                downloadState is ApkDownloader.DownloadState.Success) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SilkColors.primary,
                        contentColor = Color.White
                    )
                ) {
                    Text("关闭")
                }
            }
        },
        dismissButton = {}
    )
}
