package com.silk.backend

import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatHistoryManagerRepliesTest {

    @Test
    fun `findAgentRepliesAfterMessage returns contiguous agent replies only`() {
        withTempChatHistory { manager, sessionName ->
            manager.saveChatHistory(
                sessionName,
                ChatHistory(
                    sessionId = "session-1",
                    messages = mutableListOf(
                        chatEntry("user-1", "alice", "Alice", "请总结一下", 1_000L),
                        chatEntry("agent-1", SilkAgent.AGENT_ID, "Silk", "第一段回复", 2_000L),
                        chatEntry("agent-2", SilkAgent.AGENT_ID, "Silk", "第二段回复", 3_000L),
                        chatEntry("bob-1", "bob", "Bob", "插话", 4_000L),
                        chatEntry("agent-3", SilkAgent.AGENT_ID, "Silk", "不应被包含", 5_000L),
                    )
                )
            )

            assertEquals(
                listOf("agent-1", "agent-2"),
                manager.findAgentRepliesAfterMessage(sessionName, "user-1")
            )
        }
    }

    @Test
    fun `findAgentRepliesAfterMessage stops when agent reply exceeds five minute window`() {
        withTempChatHistory { manager, sessionName ->
            manager.saveChatHistory(
                sessionName,
                ChatHistory(
                    sessionId = "session-2",
                    messages = mutableListOf(
                        chatEntry("user-1", "alice", "Alice", "@silk 处理一下", 1_000L),
                        chatEntry("agent-1", SilkAgent.AGENT_ID, "Silk", "即时回复", 2_000L),
                        chatEntry("agent-2", SilkAgent.AGENT_ID, "Silk", "超时回复", 2_000L + 5 * 60 * 1000),
                    )
                )
            )

            assertEquals(
                listOf("agent-1"),
                manager.findAgentRepliesAfterMessage(sessionName, "user-1")
            )
        }
    }

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
        val root = createTempDirectory("silk-agent-replies-test").toFile()
        val previousDir = System.getProperty("silk.chatHistoryDir")
        try {
            System.setProperty("silk.chatHistoryDir", root.absolutePath)
            val manager = ChatHistoryManager()
            val sessionName = "group_agent-replies"
            manager.createSession(sessionName)
            block(manager, sessionName)
        } finally {
            if (previousDir == null) {
                System.clearProperty("silk.chatHistoryDir")
            } else {
                System.setProperty("silk.chatHistoryDir", previousDir)
            }
            root.deleteRecursively()
        }
    }
}
