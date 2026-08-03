package com.silk.shared

import com.silk.shared.models.Message
import com.silk.shared.models.MessageScope
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
}
