// backend/src/test/kotlin/com/silk/backend/agents/core/GroupAgentContextTest.kt
package com.silk.backend.agents.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupAgentContextTest {

    @Test
    fun `getOrCreateSession returns distinct sessions per agentType`() {
        val ctx = GroupAgentContext("u1", "g1")
        val s1 = ctx.getOrCreateSession("claude-code")
        val s2 = ctx.getOrCreateSession("codex")
        val s1again = ctx.getOrCreateSession("claude-code")
        assertEquals(s1, s1again)
        assertEquals("claude-code", s1.agentType)
        assertEquals("codex", s2.agentType)
        assertEquals(2, ctx.sessions.size)
    }

    @Test
    fun `checkAnyRunning returns empty when idle`() = runTest {
        val ctx = GroupAgentContext("u1", "g1")
        ctx.getOrCreateSession("claude-code")
        assertTrue(ctx.checkAnyRunning().isEmpty())
    }

    @Test
    fun `checkAnyRunning returns blocking agents`() = runTest {
        val ctx = GroupAgentContext("u1", "g1")
        val s1 = ctx.getOrCreateSession("claude-code")
        ctx.getOrCreateSession("codex")
        s1.running = true
        val running = ctx.checkAnyRunning()
        assertEquals(listOf("claude-code"), running)
    }

    @Test
    fun `removeSession removes the session`() {
        val ctx = GroupAgentContext("u1", "g1")
        ctx.getOrCreateSession("claude-code")
        val removed = ctx.removeSession("claude-code")
        assertNotNull(removed)
        assertEquals("claude-code", removed.agentType)
        assertNull(ctx.removeSession("claude-code"))
    }

    @Test
    fun `workingDir is shared across sessions`() {
        val ctx = GroupAgentContext("u1", "g1", workingDir = "/home/user")
        ctx.getOrCreateSession("claude-code")
        ctx.getOrCreateSession("codex")
        assertEquals("/home/user", ctx.workingDir)
    }
}
