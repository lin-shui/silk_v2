package com.silk.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silk.shared.models.Message
import java.awt.Desktop
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
/**
 * 消息包装组件
 * 使用SelectionContainer提供文本选择和复制功能
 * 使用remember和LaunchedEffect确保正确的生命周期，避免ID冲突
 */
@Suppress("UnusedParameter")
@Composable
fun MessageWithContextMenu(
    content: @Composable () -> Unit,
    message: Message
) {
    // 使用DisposableEffect确保每个消息的SelectionContainer正确管理
    DisposableEffect(message.id) {
        onDispose {
            // 清理工作
        }
    }
    
    // 使用key确保唯一性
    key(message.id) {
        SelectionContainer {
            content()
        }
    }
}

/**
 * 复制消息到剪贴板
 */
@Suppress("TooGenericExceptionCaught")
fun copyMessageToClipboard(text: String) {
    val selection = StringSelection(text)

    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
        println("✅ 消息已复制到剪贴板")
    } catch (e: HeadlessException) {
        println("❌ 复制失败: ${e.message}")
    } catch (e: IllegalStateException) {
        println("❌ 复制失败: ${e.message}")
    } catch (e: SecurityException) {
        println("❌ 复制失败: ${e.message}")
    }
}

/**
 * 转发消息到微信
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
fun forwardToWeChat(text: String) {
    val osName = System.getProperty("os.name").lowercase()

    copyMessageToClipboard(text)

    when {
        osName.contains("mac") -> {
            if (launchCommand(arrayOf("open", "-a", "WeChat"))) {
                println("✅ 已打开微信，消息已复制到剪贴板")
            } else {
                println("⚠️ 请手动打开微信，消息已复制到剪贴板")
            }
        }
        osName.contains("win") -> {
            if (launchCommand(arrayOf("cmd", "/c", "start", "WeChat"))) {
                println("✅ 已打开微信，消息已复制到剪贴板")
            } else {
                println("⚠️ 请手动打开微信，消息已复制到剪贴板")
            }
        }
        else -> {
            println("ℹ️ 消息已复制到剪贴板，请手动打开微信粘贴")
        }
    }
}

/**
 * 转发消息到SMS
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException", "NestedBlockDepth")
fun forwardToSMS(text: String) {
    val osName = System.getProperty("os.name").lowercase()

    if (!osName.contains("mac")) {
        copyMessageToClipboard(text)
        println("ℹ️ SMS功能仅支持macOS，消息已复制到剪贴板")
        return
    }

    val encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8)
    val smsUrl = "sms:&body=$encodedText"

    if (browseUri(smsUrl) || launchCommand(arrayOf("open", smsUrl))) {
        println("✅ 已打开短信应用")
    } else {
        println("❌ 打开短信应用失败")
        copyMessageToClipboard(text)
    }
}

private fun launchCommand(command: Array<String>): Boolean {
    return try {
        Runtime.getRuntime().exec(command)
        true
    } catch (e: IOException) {
        logSystemActionFailure("启动命令 ${command.joinToString(" ")}", e)
        false
    } catch (e: SecurityException) {
        logSystemActionFailure("启动命令 ${command.joinToString(" ")}", e)
        false
    }
}

private fun browseUri(uri: String): Boolean {
    if (!Desktop.isDesktopSupported()) {
        return false
    }

    val desktop = try {
        Desktop.getDesktop()
    } catch (e: HeadlessException) {
        logSystemActionFailure("获取桌面环境", e)
        return false
    } catch (e: UnsupportedOperationException) {
        logSystemActionFailure("获取桌面环境", e)
        return false
    } catch (e: SecurityException) {
        logSystemActionFailure("获取桌面环境", e)
        return false
    }

    if (!desktop.isSupported(Desktop.Action.BROWSE)) {
        return false
    }

    val targetUri = try {
        URI(uri)
    } catch (e: URISyntaxException) {
        logSystemActionFailure("解析 URI $uri", e)
        return false
    }

    return try {
        desktop.browse(targetUri)
        true
    } catch (e: IOException) {
        logSystemActionFailure("打开 URI $uri", e)
        false
    } catch (e: SecurityException) {
        logSystemActionFailure("打开 URI $uri", e)
        false
    } catch (e: UnsupportedOperationException) {
        logSystemActionFailure("打开 URI $uri", e)
        false
    } catch (e: IllegalArgumentException) {
        logSystemActionFailure("打开 URI $uri", e)
        false
    }
}

private fun logSystemActionFailure(action: String, error: Throwable) {
    println("⚠️ $action 失败: ${error.message}")
}

/**
 * 消息操作工具栏（悬浮显示）
 */
@Composable
fun MessageActionBar(
    message: Message,
    onCopy: () -> Unit,
    onForwardWeChat: () -> Unit,
    onForwardSMS: () -> Unit
) {
    var showSuccessMessage by remember { mutableStateOf<String?>(null) }
    
    Box {
        // 鼠标悬浮时显示操作按钮
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 复制按钮
            IconButton(
                onClick = {
                    copyMessageToClipboard(message.content)
                    showSuccessMessage = "已复制"
                    onCopy()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(18.dp)
                )
            }
            
            // 转发到微信
            IconButton(
                onClick = {
                    forwardToWeChat(message.content)
                    showSuccessMessage = "已转发到微信"
                    onForwardWeChat()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Message,
                    contentDescription = "转发到微信",
                    modifier = Modifier.size(18.dp)
                )
            }
            
            // 转发到SMS
            IconButton(
                onClick = {
                    forwardToSMS(message.content)
                    showSuccessMessage = "已转发到短信"
                    onForwardSMS()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Sms,
                    contentDescription = "SMS转发",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        
        // 成功提示
        if (showSuccessMessage != null) {
            LaunchedEffect(showSuccessMessage) {
                kotlinx.coroutines.delay(2000)
                showSuccessMessage = null
            }
        }
    }
}
