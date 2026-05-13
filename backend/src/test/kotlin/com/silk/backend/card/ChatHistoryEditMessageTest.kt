package com.silk.backend.card

import com.silk.backend.ChatHistoryManager
import com.silk.backend.Message
import com.silk.backend.MessageType
import com.silk.backend.models.ChatHistoryEntry
import com.silk.backend.models.ChatHistory
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChatHistoryEditMessageTest {

    @Test
    fun `editMessage replaces existing message by id`() {
        withTempChatHistory { manager, sessionName ->
            // Seed a session with two messages
            val original = ChatHistory(
                sessionId = "s1",
                messages = mutableListOf(
                    chatEntry("msg-1", "alice", "Alice", "original content", 1000L),
                    chatEntry("msg-2", "bob", "Bob", "second message", 2000L),
                ),
            )
            manager.saveChatHistory(sessionName, original)

            // Edit msg-1 via editMessage
            manager.editMessage(
                sessionName,
                Message(
                    id = "msg-1",
                    userId = "alice",
                    userName = "Alice",
                    content = "updated content",
                    timestamp = 3000L,
                    type = MessageType.CARD,
                    action = "edit",
                ),
            )

            val history = assertNotNull(manager.loadChatHistory(sessionName))
            assertEquals(2, history.messages.size)
            assertEquals("updated content", history.messages[0].content)
            assertEquals("CARD", history.messages[0].messageType)
            // Second message untouched
            assertEquals("second message", history.messages[1].content)
        }
    }

    @Test
    fun `editMessage appends when message id not found`() {
        withTempChatHistory { manager, sessionName ->
            val original = ChatHistory(
                sessionId = "s1",
                messages = mutableListOf(
                    chatEntry("msg-1", "alice", "Alice", "existing", 1000L),
                ),
            )
            manager.saveChatHistory(sessionName, original)

            manager.editMessage(
                sessionName,
                Message(
                    id = "msg-nonexistent",
                    userId = "bob",
                    userName = "Bob",
                    content = "fallback append",
                    timestamp = 2000L,
                    action = "edit",
                ),
            )

            val history = assertNotNull(manager.loadChatHistory(sessionName))
            assertEquals(2, history.messages.size)
            assertEquals("msg-1", history.messages[0].messageId)
            assertEquals("msg-nonexistent", history.messages[1].messageId)
            assertEquals("fallback append", history.messages[1].content)
        }
    }

    @Test
    fun `editMessage does nothing when history is missing`() {
        withTempChatHistory { manager, _ ->
            // No session created — editMessage should silently return
            manager.editMessage(
                "nonexistent_session",
                Message(
                    id = "msg-1",
                    userId = "alice",
                    userName = "Alice",
                    content = "content",
                    timestamp = 1000L,
                    action = "edit",
                ),
            )
            // No exception thrown, no file created
            assertEquals(null, manager.loadChatHistory("nonexistent_session"))
        }
    }

    // --- helpers ---

    private fun chatEntry(
        messageId: String,
        senderId: String,
        senderName: String,
        content: String,
        timestamp: Long,
    ) = ChatHistoryEntry(
        messageId = messageId,
        senderId = senderId,
        senderName = senderName,
        content = content,
        timestamp = timestamp,
        messageType = MessageType.TEXT.name,
    )

    private fun withTempChatHistory(block: (ChatHistoryManager, String) -> Unit) {
        val root = createTempDirectory("silk-edit-msg-test").toFile()
        val previousDir = System.getProperty("silk.chatHistoryDir")
        try {
            System.setProperty("silk.chatHistoryDir", root.absolutePath)
            val manager = ChatHistoryManager()
            val sessionName = "group_test-edit"
            manager.createSession(sessionName)
            block(manager, sessionName)
        } finally {
            if (previousDir == null) System.clearProperty("silk.chatHistoryDir")
            else System.setProperty("silk.chatHistoryDir", previousDir)
            root.deleteRecursively()
        }
    }
}
