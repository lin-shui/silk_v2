@file:Suppress("TooGenericExceptionCaught")

package com.silk.web

import androidx.compose.runtime.Composable
import kotlinx.browser.window

/** Room-level actions shared by regular chats and Workflow Team Channels. */
@Composable
internal fun ConversationRoomHeaderActions(
    inviteLabel: String,
    membersLabel: String,
    isExporting: Boolean,
    exportHint: String?,
    showMembershipActions: Boolean = true,
    onOpenFiles: () -> Unit,
    onExport: () -> Unit,
    onChooseVault: () -> Unit,
    onInvite: () -> Unit,
    onMembers: () -> Unit,
) {
    ConversationHeaderActionButton(label = "查看会话文件", icon = "📁", onClick = onOpenFiles)
    ConversationHeaderActionButton(
        label = if (isExporting) "正在导出" else "导出聊天",
        icon = "📝",
        enabled = !isExporting,
        onClick = onExport,
    )
    exportHint?.let { hint -> ConversationHeaderStatus(hint) }
    if (ObsidianVaultManager.isSupported()) {
        ConversationHeaderActionButton(
            label = "重新选择 Obsidian Vault 目录",
            icon = "📂",
            onClick = onChooseVault,
        )
    }
    if (showMembershipActions) {
        ConversationHeaderActionButton(
            label = inviteLabel,
            text = inviteLabel,
            onClick = onInvite,
        )
        ConversationHeaderActionButton(
            label = membersLabel,
            text = membersLabel,
            onClick = onMembers,
        )
    }
}

internal suspend fun exportConversationMarkdown(
    groupId: String,
    groupName: String,
    userId: String,
    onHint: (String) -> Unit,
) {
    onHint("正在导出...")
    try {
        var vaultHandle: dynamic = null
        if (ObsidianVaultManager.isSupported()) {
            vaultHandle = ObsidianVaultManager.getCachedHandleIfValid()
            if (vaultHandle == null) {
                onHint("请选择 Obsidian Vault 目录...")
                vaultHandle = ObsidianVaultManager.pickVaultDirectory()
            }
        }

        onHint("正在获取聊天记录...")
        val result = ApiClient.exportGroupMarkdown(groupId, userId)
        if (!result.success) {
            onHint("导出失败：${result.message}")
            window.alert("导出失败：${result.message}")
            return
        }
        val fileName = result.fileName.ifBlank { "silk_group_$groupId.md" }
        if (vaultHandle != null) {
            onHint("正在写入 Vault...")
            try {
                val relativePath = ObsidianVaultManager.saveToVault(
                    vaultHandle,
                    groupName,
                    result.markdown,
                    fileName,
                )
                onHint("已导出: $relativePath")
            } catch (error: Throwable) {
                console.warn("Vault 写入失败，回退到下载:", error)
                downloadAsFile(result.markdown, fileName)
                onHint("Vault写入失败，已下载：$fileName")
            }
        } else {
            downloadAsFile(result.markdown, fileName)
            onHint("导出成功：$fileName")
        }
    } catch (error: Throwable) {
        val message = error.message ?: error.toString()
        if (message.contains("abort", ignoreCase = true)) {
            onHint("已取消")
        } else {
            console.error("导出异常:", error)
            onHint("导出异常: $message")
            window.alert("导出失败: $message")
        }
    }
}

internal suspend fun chooseConversationVault(onHint: (String) -> Unit) {
    try {
        ObsidianVaultManager.clearCachedHandle()
        ObsidianVaultManager.pickVaultDirectory()
        onHint("Vault 目录已更新")
    } catch (error: Exception) {
        if (error.message?.contains("abort", ignoreCase = true) != true) {
            onHint("更换目录失败: ${error.message}")
        }
    }
}
