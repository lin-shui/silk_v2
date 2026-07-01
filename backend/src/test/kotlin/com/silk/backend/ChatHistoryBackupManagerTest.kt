package com.silk.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChatHistoryBackupManagerTest {

    @Test
    fun `listBackups skips invalid metadata files`() {
        val backupDir = createInvalidMetadataDir("manual")
        try {
            val beforeCount = ChatHistoryBackupManager.listBackups(ChatHistoryBackupManager.BackupType.MANUAL).size

            File(backupDir, "_backup_metadata.json").writeText("{not-json")

            val afterCount = ChatHistoryBackupManager.listBackups(ChatHistoryBackupManager.BackupType.MANUAL).size
            assertEquals(beforeCount, afterCount)
        } finally {
            backupDir.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `restoreFromBackup returns false for invalid metadata`() {
        val backupDir = createInvalidMetadataDir("manual")
        try {
            File(backupDir, "_backup_metadata.json").writeText("{not-json")
            assertFalse(ChatHistoryBackupManager.restoreFromBackup(backupDir.absolutePath))
        } finally {
            backupDir.parentFile?.deleteRecursively()
        }
    }

    private fun createInvalidMetadataDir(type: String): File {
        val dateDir = File("chat_history_backup/$type/2099-12-31-${System.nanoTime()}")
        val backupDir = File(dateDir, "group_invalid_metadata")
        backupDir.mkdirs()
        return backupDir
    }
}
