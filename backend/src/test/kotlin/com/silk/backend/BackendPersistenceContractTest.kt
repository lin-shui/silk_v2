package com.silk.backend

import com.silk.backend.auth.AuthService
import com.silk.backend.database.DatabaseFactory
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.Language
import com.silk.backend.database.LoginRequest
import com.silk.backend.database.UserSettingsRepository
import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import org.mindrot.jbcrypt.BCrypt
import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendPersistenceContractTest {
    @Test
    fun `database init preserves existing auth settings and group data`() {
        withTempRuntime { root ->
            val dbFile = File(root, "legacy-silk.db")
            seedLegacyDatabase(dbFile)

            System.setProperty("silk.databasePath", dbFile.absolutePath)
            System.setProperty("silk.chatHistoryDir", File(root, "chat_history").absolutePath)

            DatabaseFactory.init()

            val login = AuthService.login(LoginRequest("legacy-user", "legacy-secret"))
            assertTrue(login.success, login.message)
            val user = assertNotNull(login.user)
            assertEquals("legacy-user-id", user.id)
            assertEquals("Legacy User", user.fullName)

            val phoneLogin = AuthService.login(LoginRequest("13800009999", "legacy-secret"))
            assertTrue(phoneLogin.success, phoneLogin.message)
            assertEquals(user.id, phoneLogin.user?.id)

            val settings = UserSettingsRepository.getUserSettings(user.id)
            assertEquals(Language.ENGLISH, settings.language)
            assertEquals("Keep existing instruction.", settings.defaultAgentInstruction)

            val bridgeToken = UserSettingsRepository.generateBridgeToken(user.id)
            assertEquals(bridgeToken, UserSettingsRepository.getBridgeToken(user.id))
            assertEquals(user.id, UserSettingsRepository.findUserIdByBridgeToken(bridgeToken))

            val group = assertNotNull(GroupRepository.findGroupById("legacy-group-id"))
            assertEquals("Legacy Group", group.name)
            assertEquals(user.id, group.hostId)
            assertTrue(GroupRepository.isUserInGroup(group.id, user.id))
            assertEquals(listOf(group.id), GroupRepository.getUserGroups(user.id).map { it.id })

            DatabaseFactory.init()

            val loginAfterSecondInit = AuthService.login(LoginRequest("legacy-user", "legacy-secret"))
            assertTrue(loginAfterSecondInit.success, loginAfterSecondInit.message)
            assertEquals(bridgeToken, UserSettingsRepository.getBridgeToken(user.id))
            assertEquals(listOf(group.id), GroupRepository.getUserGroups(user.id).map { it.id })
        }
    }

    @Test
    fun `chat history startup paths recover metadata and append without overwriting messages`() {
        withTempRuntime { root ->
            val chatHistoryDir = File(root, "chat_history")
            System.setProperty("silk.chatHistoryDir", chatHistoryDir.absolutePath)

            val manager = ChatHistoryManager()
            val sessionName = "group_legacy-history"
            val originalHistory = ChatHistory(
                sessionId = "legacy-session-id",
                messages = mutableListOf(
                    chatEntry("legacy-msg-1", "alice", "Alice", "first persisted message", 1_000L),
                    chatEntry("legacy-msg-2", "bob", "Bob", "second persisted message", 2_000L)
                ),
                rolePrompt = "Keep the existing role prompt."
            )
            manager.saveChatHistory(sessionName, originalHistory)

            val historyFile = File(chatHistoryDir, "$sessionName/chat_history.json")
            val historyBeforeStartup = historyFile.readText()

            val recoveredSession = assertNotNull(manager.ensureSessionExists(sessionName))
            assertEquals("legacy-session-id", recoveredSession.sessionId)
            assertEquals(historyBeforeStartup, historyFile.readText())

            manager.addMessage(
                sessionName,
                Message(
                    id = "new-msg",
                    userId = "carol",
                    userName = "Carol",
                    content = "message after restart",
                    timestamp = 3_000L
                )
            )

            val updatedHistory = assertNotNull(manager.loadChatHistory(sessionName))
            assertEquals(
                listOf("legacy-msg-1", "legacy-msg-2", "new-msg"),
                updatedHistory.messages.map { it.messageId }
            )
            assertEquals("Keep the existing role prompt.", updatedHistory.rolePrompt)
        }
    }

    @Test
    fun `chat history manager refuses to overwrite unreadable existing history`() {
        withTempRuntime { root ->
            val chatHistoryDir = File(root, "chat_history")
            System.setProperty("silk.chatHistoryDir", chatHistoryDir.absolutePath)

            val sessionName = "group_corrupt-history"
            val sessionDir = File(chatHistoryDir, sessionName)
            sessionDir.mkdirs()
            val historyFile = File(sessionDir, "chat_history.json")
            val corruptPayload = """{"sessionId":"legacy-session","messages":["""
            historyFile.writeText(corruptPayload)

            ChatHistoryManager().addMessage(
                sessionName,
                Message(
                    id = "must-not-overwrite",
                    userId = "alice",
                    userName = "Alice",
                    content = "this must not replace the corrupt file",
                    timestamp = 4_000L
                )
            )

            assertEquals(corruptPayload, historyFile.readText())
            assertTrue(
                sessionDir.listFiles()
                    .orEmpty()
                    .any { it.name.startsWith("chat_history.corrupted_") && it.name.endsWith(".json") },
                "Corrupt history should be backed up before writes are rejected"
            )
            assertNull(ChatHistoryManager().loadChatHistory(sessionName))
            assertEquals(corruptPayload, historyFile.readText())
        }
    }

    private fun seedLegacyDatabase(dbFile: File) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE users (
                        id VARCHAR(128) PRIMARY KEY,
                        login_name VARCHAR(128) NOT NULL,
                        full_name VARCHAR(256) NOT NULL,
                        phone_number VARCHAR(20) NOT NULL,
                        password_hash VARCHAR(256) NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE groups (
                        id VARCHAR(128) PRIMARY KEY,
                        name VARCHAR(256) NOT NULL,
                        invitation_code VARCHAR(32) NOT NULL,
                        host_id VARCHAR(128) NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE group_members (
                        group_id VARCHAR(128) NOT NULL,
                        user_id VARCHAR(128) NOT NULL,
                        role VARCHAR(20) NOT NULL,
                        joined_at TEXT NOT NULL,
                        PRIMARY KEY (group_id, user_id)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE user_settings (
                        user_id VARCHAR(128) PRIMARY KEY,
                        language VARCHAR(20) NOT NULL,
                        default_agent_instruction TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                val passwordHash = BCrypt.hashpw("legacy-secret", BCrypt.gensalt(12))
                statement.executeUpdate(
                    """
                    INSERT INTO users (
                        id, login_name, full_name, phone_number, password_hash, created_at
                    ) VALUES (
                        'legacy-user-id',
                        'legacy-user',
                        'Legacy User',
                        '13800009999',
                        '$passwordHash',
                        '2024-01-02 03:04:05'
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO groups (
                        id, name, invitation_code, host_id, created_at
                    ) VALUES (
                        'legacy-group-id',
                        'Legacy Group',
                        'ABC123',
                        'legacy-user-id',
                        '2024-01-03 03:04:05'
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO group_members (
                        group_id, user_id, role, joined_at
                    ) VALUES (
                        'legacy-group-id',
                        'legacy-user-id',
                        'HOST',
                        '2024-01-03 03:04:06'
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO user_settings (
                        user_id, language, default_agent_instruction, updated_at
                    ) VALUES (
                        'legacy-user-id',
                        'ENGLISH',
                        'Keep existing instruction.',
                        '2024-01-04 03:04:05'
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun chatEntry(
        messageId: String,
        senderId: String,
        senderName: String,
        content: String,
        timestamp: Long
    ) = ChatHistoryEntry(
        messageId = messageId,
        senderId = senderId,
        senderName = senderName,
        content = content,
        timestamp = timestamp,
        messageType = MessageType.TEXT.name
    )

    private fun withTempRuntime(block: (File) -> Unit) {
        val root = createTempDirectory("silk-persistence-contract").toFile()
        val previousDatabasePath = System.getProperty("silk.databasePath")
        val previousChatHistoryDir = System.getProperty("silk.chatHistoryDir")
        val previousUserTodoBaseDir = System.getProperty("silk.userTodoBaseDir")

        try {
            block(root)
        } finally {
            restoreProperty("silk.databasePath", previousDatabasePath)
            restoreProperty("silk.chatHistoryDir", previousChatHistoryDir)
            restoreProperty("silk.userTodoBaseDir", previousUserTodoBaseDir)
            root.deleteRecursively()
        }
    }

    private fun restoreProperty(key: String, value: String?) {
        if (value == null) {
            System.clearProperty(key)
        } else {
            System.setProperty(key, value)
        }
    }
}
