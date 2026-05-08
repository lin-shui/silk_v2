// backend/src/test/kotlin/com/silk/backend/agents/core/AcpUpdateMapperTest.kt
package com.silk.backend.agents.core

import com.silk.backend.agents.adapters.claudecode.ClaudeCodeDescriptor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpUpdateMapperTest {

    private val descriptor = ClaudeCodeDescriptor

    @Test
    fun `agent_message_chunk maps to streaming message`() {
        val sb = StringBuilder()
        val msg = AcpUpdateMapper.map(
            update = buildJsonObject {
                put("sessionUpdate", "agent_message_chunk")
                putJsonObject("content") {
                    put("type", "text")
                    put("text", "hello")
                }
            },
            descriptor = descriptor,
            agentType = "claude-code",
            accumulated = sb,
        )
        assertNotNull(msg)
        assertEquals("hello", msg.content)
        assertEquals("claudecode_ai_agent", msg.userId)
        assertFalse(msg.isIncremental)
    }

    @Test
    fun `agent_message_chunk accumulates text`() {
        val sb = StringBuilder()
        AcpUpdateMapper.map(
            update = buildJsonObject {
                put("sessionUpdate", "agent_message_chunk")
                putJsonObject("content") { put("type", "text"); put("text", "hel") }
            },
            descriptor = descriptor,
            agentType = "claude-code",
            accumulated = sb,
        )
        AcpUpdateMapper.map(
            update = buildJsonObject {
                put("sessionUpdate", "agent_message_chunk")
                putJsonObject("content") { put("type", "text"); put("text", "lo") }
            },
            descriptor = descriptor,
            agentType = "claude-code",
            accumulated = sb,
        )
        assertEquals("hello", sb.toString())
    }

    @Test
    fun `agent_thought_chunk maps to status message`() {
        val sb = StringBuilder()
        val msg = AcpUpdateMapper.map(
            update = buildJsonObject {
                put("sessionUpdate", "agent_thought_chunk")
                putJsonObject("content") {
                    put("type", "text")
                    put("text", "thinking...")
                }
            },
            descriptor = descriptor,
            agentType = "claude-code",
            accumulated = sb,
        )
        assertNotNull(msg)
        assertEquals("thinking...", msg.content)
        assertTrue(msg.isTransient)
    }

    @Test
    fun `tool_call maps to status message`() {
        val sb = StringBuilder()
        val msg = AcpUpdateMapper.map(
            update = buildJsonObject {
                put("sessionUpdate", "tool_call")
                put("tool", "bash")
                put("title", "ls -la")
                put("toolCallId", "tc1")
            },
            descriptor = descriptor,
            agentType = "claude-code",
            accumulated = sb,
        )
        assertNotNull(msg)
        assertEquals("🔧 bash: ls -la", msg.content)
    }

    @Test
    fun `tool_call_update maps to status message`() {
        val sb = StringBuilder()
        val msg = AcpUpdateMapper.map(
            update = buildJsonObject {
                put("sessionUpdate", "tool_call_update")
                put("toolCallId", "tc1")
                putJsonObject("content") {
                    put("type", "text")
                    put("text", "output here")
                }
            },
            descriptor = descriptor,
            agentType = "claude-code",
            accumulated = sb,
        )
        assertNotNull(msg)
        assertEquals("output here", msg.content)
    }

    @Test
    fun `plan maps to status message`() {
        val sb = StringBuilder()
        val msg = AcpUpdateMapper.map(
            update = buildJsonObject {
                put("sessionUpdate", "plan")
                put("plan", "step 1: analyze")
            },
            descriptor = descriptor,
            agentType = "claude-code",
            accumulated = sb,
        )
        assertNotNull(msg)
        assertEquals("📋 计划: step 1: analyze", msg.content)
    }

    @Test
    fun `available_commands_update returns null`() {
        val sb = StringBuilder()
        val msg = AcpUpdateMapper.map(
            update = buildJsonObject {
                put("sessionUpdate", "available_commands_update")
            },
            descriptor = descriptor,
            agentType = "claude-code",
            accumulated = sb,
        )
        assertNull(msg)
    }

    @Test
    fun `unknown kind returns null`() {
        val sb = StringBuilder()
        val msg = AcpUpdateMapper.map(
            update = buildJsonObject {
                put("sessionUpdate", "unknown_kind")
            },
            descriptor = descriptor,
            agentType = "claude-code",
            accumulated = sb,
        )
        assertNull(msg)
    }

    @Test
    fun `missing sessionUpdate returns null`() {
        val sb = StringBuilder()
        val msg = AcpUpdateMapper.map(
            update = buildJsonObject {},
            descriptor = descriptor,
            agentType = "claude-code",
            accumulated = sb,
        )
        assertNull(msg)
    }
}
