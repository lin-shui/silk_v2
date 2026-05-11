// backend/src/test/kotlin/com/silk/backend/agents/core/AgentSessionTest.kt
package com.silk.backend.agents.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSessionTest {

    @Test
    fun `queue add and remove operations`() {
        val session = AgentSession("u1", "g1", "claude-code")
        assertTrue(session.messageQueue.isEmpty())

        session.messageQueue.add(QueuedMessage("hello", "u1", "Alice"))
        session.messageQueue.add(QueuedMessage("world", "u2", "Bob"))
        assertEquals(2, session.messageQueue.size)

        val first = session.messageQueue.pollFirst()
        assertEquals("hello", first?.text)
        assertEquals(1, session.messageQueue.size)
    }

    @Test
    fun `running flag can be set`() {
        val session = AgentSession("u1", "g1", "claude-code")
        assertFalse(session.running)
        session.running = true
        assertTrue(session.running)
    }

    @Test
    fun `acpSessionId can be assigned`() {
        val session = AgentSession("u1", "g1", "claude-code")
        assertNull(session.acpSessionId)
        session.acpSessionId = "sess-abc"
        assertEquals("sess-abc", session.acpSessionId)
    }

    @Test
    fun `cancelled flag defaults to false`() {
        val session = AgentSession("u1", "g1", "claude-code")
        assertFalse(session.cancelled)
    }

    @Test
    fun `currentRequestId defaults to null`() {
        val session = AgentSession("u1", "g1", "claude-code")
        assertNull(session.currentRequestId)
    }
}
