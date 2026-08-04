package com.silk.shared

import com.silk.shared.models.Message
import com.silk.shared.models.MessageScope
import com.silk.shared.models.MessageCategory
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageScopeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun newFieldsSerializeCorrectly() {
        val msg = Message(
            id = "msg1", userId = "u1", userName = "Alice",
            content = "hello", timestamp = 0L,
            scope = MessageScope.WORKSPACE, workspaceId = "ws_abc"
        )
        val encoded = json.encodeToString(Message.serializer(), msg)
        assertTrue(encoded.contains("\"scope\":\"WORKSPACE\""), "scope field missing from JSON")
        assertTrue(encoded.contains("\"workspaceId\":\"ws_abc\""), "workspaceId field missing from JSON")
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(MessageScope.WORKSPACE, decoded.scope)
        assertEquals("ws_abc", decoded.workspaceId)
    }

    @Test
    fun legacyMessageDeserializesWithDefaults() {
        val legacy = """{"id":"m1","userId":"u1","userName":"Alice","content":"hi","timestamp":0}"""
        val msg = json.decodeFromString(Message.serializer(), legacy)
        assertEquals(MessageScope.TEAM, msg.scope)
        assertNull(msg.workspaceId)
    }

    @Test
    fun transientMessagesRemainIndependentPerWorkspaceStream() {
        val client = ChatClient("ws://example.invalid")
        client.handleMessage(json.encodeToString(
            Message.serializer(),
            Message(
                id = "stream-1",
                userId = "claude_ai_agent",
                userName = "Claude",
                content = "first",
                timestamp = 1L,
                isTransient = true,
                isIncremental = true,
                scope = MessageScope.WORKSPACE,
                workspaceId = "ws-1",
            ),
        ))
        client.handleMessage(json.encodeToString(
            Message.serializer(),
            Message(
                id = "stream-2",
                userId = "claude_ai_agent",
                userName = "Claude",
                content = "second",
                timestamp = 2L,
                isTransient = true,
                scope = MessageScope.WORKSPACE,
                workspaceId = "ws-2",
            ),
        ))

        assertEquals("first", client.transientMessages.value["WORKSPACE:ws-1"]?.content)
        assertEquals("second", client.transientMessages.value["WORKSPACE:ws-2"]?.content)

        client.handleMessage(json.encodeToString(
            Message.serializer(),
            Message(
                id = "final-1",
                userId = "claude_ai_agent",
                userName = "Claude",
                content = "done",
                timestamp = 3L,
                scope = MessageScope.WORKSPACE,
                workspaceId = "ws-1",
            ),
        ))

        assertNull(client.transientMessages.value["WORKSPACE:ws-1"])
        assertEquals("second", client.transientMessages.value["WORKSPACE:ws-2"]?.content)
    }

    @Test
    fun clearStatusOnlyClearsItsOwnWorkspaceStream() {
        val client = ChatClient("ws://example.invalid")
        fun status(id: String, workspaceId: String, content: String) = Message(
            id = id,
            userId = "claude_ai_agent",
            userName = "Claude",
            content = content,
            timestamp = 1L,
            isTransient = true,
            category = MessageCategory.AGENT_STATUS,
            scope = MessageScope.WORKSPACE,
            workspaceId = workspaceId,
        )

        client.handleMessage(json.encodeToString(Message.serializer(), status("s1", "ws-1", "running")))
        client.handleMessage(json.encodeToString(Message.serializer(), status("s2", "ws-2", "running")))
        client.handleMessage(json.encodeToString(Message.serializer(), status("clear", "ws-1", "CLEAR_STATUS")))

        assertTrue(client.statusMessagesByStream.value["WORKSPACE:ws-1"].isNullOrEmpty())
        assertEquals("running", client.statusMessagesByStream.value["WORKSPACE:ws-2"]?.single()?.content)
    }
}
