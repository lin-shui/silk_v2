// backend/src/test/kotlin/com/silk/backend/agents/core/AgentRuntimeTest.kt
package com.silk.backend.agents.core

import com.silk.backend.Message
import com.silk.backend.MessageType
import com.silk.backend.agents.adapters.claudecode.ClaudeCodeDescriptor
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRuntimeTest {

    @BeforeTest
    fun setup() {
        AgentRegistry.clearForTest()
        AgentRuntime.clearForTest()
        // Re-register because AgentRuntime.init only runs once (object singleton)
        AgentRegistry.register(ClaudeCodeDescriptor)
    }

    @Test
    fun `isAgentMessage returns true for claude code agent message`() {
        val msg = Message(
            id = "1",
            userId = "silk_ai_agent",
            userName = "Claude",
            content = "hi",
            timestamp = 0L,
            type = MessageType.TEXT,
        )
        assertTrue(AgentRuntime.isAgentMessage(msg))
    }

    @Test
    fun `isAgentMessage returns false for regular user message`() {
        val msg = Message(
            id = "1",
            userId = "user123",
            userName = "Alice",
            content = "hi",
            timestamp = 0L,
            type = MessageType.TEXT,
        )
        assertFalse(AgentRuntime.isAgentMessage(msg))
    }

    @Test
    fun `listRegisteredAgents includes claude-code`() {
        val agents = AgentRuntime.listRegisteredAgents()
        assertTrue(agents.any { it.agentType == "claude-code" })
    }

    @Test
    fun `handleIfActive with slash cc trigger sets currentAgentType`() = runTest {
        val messages = mutableListOf<Message>()
        val handled = AgentRuntime.handleIfActive(
            userId = "u1",
            groupId = "g1",
            text = "/cc",
            userName = "Alice",
            broadcastFn = { messages.add(it) },
        )
        assertTrue(handled)
        assertTrue(messages.any { it.type == MessageType.SYSTEM })
    }

    @Test
    fun `handleIfActive with plain text when no agent active returns false`() = runTest {
        val handled = AgentRuntime.handleIfActive(
            userId = "u1",
            groupId = "g1",
            text = "hello world",
            userName = "Alice",
            broadcastFn = {},
        )
        assertFalse(handled)
    }

    @Test
    fun `handleIfActive with at cc routes without changing currentAgentType`() = runTest {
        val messages = mutableListOf<Message>()
        // First activate with /cc
        AgentRuntime.handleIfActive("u1", "g1", "/cc", "Alice") { messages.add(it) }

        // Then @cc hello should route
        val handled = AgentRuntime.handleIfActive(
            userId = "u1",
            groupId = "g1",
            text = "@cc hello",
            userName = "Alice",
            broadcastFn = { messages.add(it) },
        )
        assertTrue(handled)
    }

    @Test
    fun `cancelIfActive returns false when no agent active`() = runTest {
        val cancelled = AgentRuntime.cancelIfActive("u1", "g1") {}
        assertFalse(cancelled)
    }

    @Test
    fun `autoActivateForWorkflow sets currentAgentType`() = runTest {
        AgentRuntime.autoActivateForWorkflow("u1", "g1", "claude-code")
        // After activation, handleIfActive with plain text should return true
        val messages = mutableListOf<Message>()
        val handled = AgentRuntime.handleIfActive(
            userId = "u1",
            groupId = "g1",
            text = "hello",
            userName = "Alice",
            broadcastFn = { messages.add(it) },
        )
        assertTrue(handled)
    }
}
