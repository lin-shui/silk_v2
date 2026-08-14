package com.silk.backend

import com.silk.backend.auth.AuthService
import com.silk.backend.database.DatabaseFactory
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.Language
import com.silk.backend.database.LoginRequest
import com.silk.backend.database.UserSettingsRepository
import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import com.silk.shared.models.RoomKind
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
    fun `group creation timestamps advance within one backend process`() {
        TestWorkspace().use {
            val first = assertNotNull(GroupRepository.createGroup("First timestamp room", "owner"))
            Thread.sleep(10)
            val second = assertNotNull(GroupRepository.createGroup("Second timestamp room", "owner"))

            assertTrue(second.updatedAt > first.updatedAt)
            assertTrue(java.time.LocalDateTime.parse(second.createdAt) > java.time.LocalDateTime.parse(first.createdAt))
        }
    }

    @Test
    fun `database init preserves existing auth settings and group data`() {
        withTempRuntime { root ->
            val dbFile = File(root, "legacy-silk.db")
            seedLegacyDatabase(dbFile)

            System.setProperty("silk.databasePath", dbFile.absolutePath)
            System.setProperty("silk.chatHistoryDir", File(root, "chat_history").absolutePath)
            ChatHistoryManager().saveChatHistory(
                "group_legacy-group-id",
                ChatHistory(
                    sessionId = "legacy-room-recency",
                    messages = mutableListOf(
                        chatEntry("legacy-recency-1", "legacy-user-id", "Legacy User", "older", 4_000L),
                        chatEntry("legacy-recency-2", "legacy-user-id", "Legacy User", "latest", 5_000L),
                    ),
                ),
            )

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

            assertNull(readRetiredBridgeToken(dbFile, user.id))

            val group = assertNotNull(GroupRepository.findGroupById("legacy-group-id"))
            assertEquals("Legacy Group", group.name)
            assertEquals(user.id, group.hostId)
            assertEquals(RoomKind.CHAT, group.roomKind)
            assertTrue(group.updatedAt > 0L)
            assertEquals(5_000L, group.lastMessageAt)
            assertTrue(GroupRepository.isUserInGroup(group.id, user.id))
            assertEquals(listOf(group.id), GroupRepository.getUserGroups(user.id).map { it.id })

            assertTrue(GroupRepository.updateGroupName(group.id, "Renamed Legacy Group"))
            assertEquals(5_000L, GroupRepository.findGroupById(group.id)?.lastMessageAt)
            assertTrue(GroupRepository.touchRoom(group.id, 6_000L))
            assertEquals(6_000L, GroupRepository.findGroupById(group.id)?.lastMessageAt)

            DatabaseFactory.init()

            val loginAfterSecondInit = AuthService.login(LoginRequest("legacy-user", "legacy-secret"))
            assertTrue(loginAfterSecondInit.success, loginAfterSecondInit.message)
            assertNull(readRetiredBridgeToken(dbFile, user.id))
            assertEquals(listOf(group.id), GroupRepository.getUserGroups(user.id).map { it.id })
            assertEquals(6_000L, GroupRepository.findGroupById(group.id)?.lastMessageAt)
        }
    }

    @Test
    fun `database init backfills explicit room kinds without trusting workflow name prefixes`() {
        withTempRuntime { root ->
            val dbFile = File(root, "legacy-room-kinds.db")
            seedLegacyDatabase(dbFile)
            seedLegacyRoomKinds(dbFile)
            val workflowDir = File(root, "workflows").apply { mkdirs() }
            File(workflowDir, "workflow_store.json").writeText(
                """{"workflows":[{"groupId":"workflow-metadata-room","name":"Migrated Workflow"},{"groupId":"workflow-name-collision-room","name":"Legacy Group"}]}"""
            )
            File(workflowDir, "workspace_store.json").writeText(
                """{"workspaces":[{"roomId":"workspace-metadata-room"}]}"""
            )

            System.setProperty("silk.databasePath", dbFile.absolutePath)
            System.setProperty("silk.workflowDir", workflowDir.absolutePath)

            DatabaseFactory.init()

            assertEquals(RoomKind.WORKFLOW, GroupRepository.findGroupById("workflow-metadata-room")?.roomKind)
            assertEquals("Project Alpha", GroupRepository.findGroupById("workflow-metadata-room")?.name)
            assertEquals(RoomKind.WORKFLOW, GroupRepository.findGroupById("workflow-name-collision-room")?.roomKind)
            assertEquals("Legacy Group (1)", GroupRepository.findGroupById("workflow-name-collision-room")?.name)
            assertEquals(RoomKind.WORKFLOW, GroupRepository.findGroupById("workspace-metadata-room")?.roomKind)
            assertEquals(RoomKind.SILK_PRIVATE, GroupRepository.findGroupById("silk-private-room")?.roomKind)
            assertEquals(RoomKind.CHAT, GroupRepository.findGroupById("wf_orphan-room")?.roomKind)
            assertEquals(RoomKind.CHAT, GroupRepository.findGroupById("cc-connect-room")?.roomKind)

            DatabaseFactory.init()
            assertEquals(RoomKind.WORKFLOW, GroupRepository.findGroupById("workflow-metadata-room")?.roomKind)
            assertEquals("Project Alpha", GroupRepository.findGroupById("workflow-metadata-room")?.name)
            assertEquals("Legacy Group (1)", GroupRepository.findGroupById("workflow-name-collision-room")?.name)
            assertEquals(RoomKind.SILK_PRIVATE, GroupRepository.findGroupById("silk-private-room")?.roomKind)

            assertTrue(GroupRepository.updateGroupName("workflow-metadata-room", "Canonical Project Alpha"))
            DatabaseFactory.init()
            assertEquals("Canonical Project Alpha", GroupRepository.findGroupById("workflow-metadata-room")?.name)
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
                        cc_bridge_token VARCHAR(64),
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
                        user_id, language, default_agent_instruction, cc_bridge_token, updated_at
                    ) VALUES (
                        'legacy-user-id',
                        'ENGLISH',
                        'Keep existing instruction.',
                        'legacy-direct-bridge-token',
                        '2024-01-04 03:04:05'
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun readRetiredBridgeToken(dbFile: File, userId: String): String? =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            readRetiredBridgeToken(connection, userId)
        }

    private fun readRetiredBridgeToken(connection: java.sql.Connection, userId: String): String? =
        connection.prepareStatement(
            "SELECT cc_bridge_token FROM user_settings WHERE user_id = ?",
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) else null
            }
        }

    private fun seedLegacyRoomKinds(dbFile: File) {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    Triple("workflow-metadata-room", "Project Alpha", "WF0001"),
                    Triple("workspace-metadata-room", "Workspace Backfill", "WF0002"),
                    Triple("silk-private-room", "[Silk] Legacy User", "WF0003"),
                    Triple("wf_orphan-room", "wf_orphan", "WF0004"),
                    Triple("cc-connect-room", "Claude Automation", "WF0005"),
                    Triple("workflow-name-collision-room", "wf_collision", "WF0006"),
                ).forEach { (id, name, code) ->
                    statement.executeUpdate(
                        """
                        INSERT INTO groups (id, name, invitation_code, host_id, created_at)
                        VALUES ('$id', '$name', '$code', 'legacy-user-id', '2024-01-03 03:04:05')
                        """.trimIndent()
                    )
                }
                statement.executeUpdate(
                    """
                    INSERT INTO group_members (group_id, user_id, role, joined_at)
                    VALUES ('silk-private-room', 'legacy-user-id', 'HOST', '2024-01-03 03:04:06')
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO group_members (group_id, user_id, role, joined_at)
                    VALUES ('silk-private-room', 'silk_ai_agent', 'GUEST', '2024-01-03 03:04:07')
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
        val previousWorkflowDir = System.getProperty("silk.workflowDir")

        try {
            System.setProperty("silk.workflowDir", File(root, "workflows").absolutePath)
            block(root)
        } finally {
            restoreProperty("silk.databasePath", previousDatabasePath)
            restoreProperty("silk.chatHistoryDir", previousChatHistoryDir)
            restoreProperty("silk.userTodoBaseDir", previousUserTodoBaseDir)
            restoreProperty("silk.workflowDir", previousWorkflowDir)
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
