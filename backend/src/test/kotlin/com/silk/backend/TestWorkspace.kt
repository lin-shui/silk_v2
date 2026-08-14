package com.silk.backend

import com.silk.backend.database.ContactRequests
import com.silk.backend.database.Contacts
import com.silk.backend.database.CcConnectTokens
import com.silk.backend.database.AgentBindings
import com.silk.backend.database.AgentBindingAuditEvents
import com.silk.backend.database.AgentDevices
import com.silk.backend.database.AgentDeviceRevocationTombstones
import com.silk.backend.database.AgentInstances
import com.silk.backend.database.AgentPairingRequests
import com.silk.backend.database.AgentConnectionChallenges
import com.silk.backend.database.AgentSecurityEvents
import com.silk.backend.database.GroupMembers
import com.silk.backend.database.Groups
import com.silk.backend.database.RefreshTokensTable
import com.silk.backend.database.UserSettingsTable
import com.silk.backend.database.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.io.path.createTempDirectory

internal class TestWorkspace : AutoCloseable {
    private val rootDir = createTempDirectory("silk-backend-test").toFile()
    private val dbFile = File(rootDir, "silk-test.db")
    val chatHistoryDir = File(rootDir, "chat_history")
    val userTodoDir = File(chatHistoryDir, "user_todos")
    val knowledgeBaseDir = File(rootDir, "knowledge_base")
    val workflowDir = File(rootDir, "workflows")

    init {
        System.setProperty("silk.databasePath", dbFile.absolutePath)
        System.setProperty("silk.chatHistoryDir", chatHistoryDir.absolutePath)
        System.setProperty("silk.userTodoBaseDir", userTodoDir.absolutePath)
        System.setProperty("silk.kbDir", knowledgeBaseDir.absolutePath)
        System.setProperty("silk.workflowDir", workflowDir.absolutePath)

        val database = Database.connect(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC"
        )
        transaction(database) {
            SchemaUtils.create(
                Users,
                Groups,
                GroupMembers,
                Contacts,
                ContactRequests,
                UserSettingsTable,
                CcConnectTokens,
                RefreshTokensTable,
                AgentDevices,
                AgentDeviceRevocationTombstones,
                AgentInstances,
                AgentBindings,
                AgentBindingAuditEvents,
                AgentPairingRequests,
                AgentConnectionChallenges,
                AgentSecurityEvents,
            )
        }
    }

    override fun close() {
        System.clearProperty("silk.databasePath")
        System.clearProperty("silk.chatHistoryDir")
        System.clearProperty("silk.userTodoBaseDir")
        System.clearProperty("silk.kbDir")
        System.clearProperty("silk.workflowDir")
        rootDir.deleteRecursively()
    }
}
