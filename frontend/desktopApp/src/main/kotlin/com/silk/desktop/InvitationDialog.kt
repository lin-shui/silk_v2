package com.silk.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitationDialog(
    group: Group,
    onDismiss: () -> Unit
) {
    var showSuccessMessage by remember { mutableStateOf<String?>(null) }
    
    // 生成邀请消息
    val invitationMessage = buildString {
        append("🎉 邀请您加入群组\n\n")
        append("群组名称：${group.name}\n")
        append("邀请码：${group.invitationCode}\n\n")
        append("━━━━━━━━━━━━━━━━━\n\n")
        append("📱 如何加入：\n")
        append("1. 下载并打开 Silk 应用\n")
        append("2. 注册或登录账号\n")
        append("3. 在群组列表点击「加入群组」\n")
        append("4. 输入邀请码：${group.invitationCode}\n\n")
        append("期待您的加入！")
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("邀请入群")
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 邀请信息预览
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "群组名称",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Text(
                            text = "邀请码",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = group.invitationCode,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                // 成功提示
                if (showSuccessMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = showSuccessMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                
                // 分享方式
                Text(
                    text = "选择分享方式：",
                    style = MaterialTheme.typography.labelMedium
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // COPY - 只复制邀请码
                    Button(
                        onClick = {
                            copyToClipboard(group.invitationCode)
                            showSuccessMessage = "✅ 邀请码已复制: ${group.invitationCode}"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("📋 COPY（复制邀请码）")
                    }
                    
                    // Invite Message - 复制完整邀请消息
                    OutlinedButton(
                        onClick = {
                            copyToClipboard(invitationMessage)
                            showSuccessMessage = "✅ 完整邀请消息已复制到剪贴板"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Message, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("💬 Invite Message（复制完整消息）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * 复制文本到剪贴板
 */
private fun copyToClipboard(text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, null)
        println("✅ 已复制到剪贴板")
    } catch (e: HeadlessException) {
        println("❌ 复制失败: ${e.message}")
    } catch (e: IllegalStateException) {
        println("❌ 复制失败: ${e.message}")
    } catch (e: SecurityException) {
        println("❌ 复制失败: ${e.message}")
    }
}
