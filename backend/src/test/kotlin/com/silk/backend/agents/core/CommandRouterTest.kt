// backend/src/test/kotlin/com/silk/backend/agents/core/CommandRouterTest.kt
package com.silk.backend.agents.core

import com.silk.backend.agents.adapters.claudecode.ClaudeCodeDescriptor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandRouterTest {

    @BeforeTest
    fun setup() {
        AgentRegistry.clearForTest()
        AgentRegistry.register(ClaudeCodeDescriptor)
    }

    @Test
    fun `route slash agents returns ListAgents`() {
        val r = CommandRouter.route("/agents", "u1", "g1", null)
        assertIs<CommandRouter.RouteResult.ListAgents>(r)
    }

    @Test
    fun `route use none returns UseAgent null`() {
        val r = CommandRouter.route("/use none", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.UseAgent>(r)
        assertNull(r.agentType)
    }

    @Test
    fun `route use claude-code returns UseAgent`() {
        val r = CommandRouter.route("/use claude-code", "u1", "g1", null)
        assertIs<CommandRouter.RouteResult.UseAgent>(r)
        assertEquals("claude-code", r.agentType)
    }

    @Test
    fun `route use alias cc returns UseAgent`() {
        val r = CommandRouter.route("/use cc", "u1", "g1", null)
        assertIs<CommandRouter.RouteResult.UseAgent>(r)
        assertEquals("claude-code", r.agentType)
    }

    @Test
    fun `route trigger cc returns TriggerAgent`() {
        val r = CommandRouter.route("/cc", "u1", "g1", null)
        assertIs<CommandRouter.RouteResult.TriggerAgent>(r)
        assertEquals("claude-code", r.agentType)
    }

    @Test
    fun `route at alias cc returns AtAgent`() {
        val r = CommandRouter.route("@cc hello world", "u1", "g1", null)
        assertIs<CommandRouter.RouteResult.AtAgent>(r)
        assertEquals("claude-code", r.agentType)
        assertEquals("hello world", r.remainingText)
    }

    @Test
    fun `route at unregistered returns PassThrough`() {
        val r = CommandRouter.route("@unknown hi", "u1", "g1", null)
        assertIs<CommandRouter.RouteResult.PassThrough>(r)
    }

    @Test
    fun `route cancel returns Command Cancel`() {
        val r = CommandRouter.route("/cancel", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Command>(r)
        assertIs<SilkCommand.Cancel>(r.cmd)
    }

    @Test
    fun `route exit returns Command Exit`() {
        val r = CommandRouter.route("/exit", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Command>(r)
        assertIs<SilkCommand.Exit>(r.cmd)
    }

    @Test
    fun `route new returns Command New`() {
        val r = CommandRouter.route("/new", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Command>(r)
        assertIs<SilkCommand.New>(r.cmd)
    }

    @Test
    fun `route cd path returns Command Cd`() {
        val r = CommandRouter.route("/cd /tmp", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Command>(r)
        val cmd = r.cmd as SilkCommand.Cd
        assertEquals("/tmp", cmd.path)
    }

    @Test
    fun `route session returns Command SessionList`() {
        val r = CommandRouter.route("/session", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Command>(r)
        assertIs<SilkCommand.SessionList>(r.cmd)
    }

    @Test
    fun `route session prefix returns Command SessionLoad`() {
        val r = CommandRouter.route("/session abc123", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Command>(r)
        val cmd = r.cmd as SilkCommand.SessionLoad
        assertEquals("abc123", cmd.sessionIdPrefix)
    }

    @Test
    fun `route status returns Command Status`() {
        val r = CommandRouter.route("/status", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Command>(r)
        assertIs<SilkCommand.Status>(r.cmd)
    }

    @Test
    fun `route queue returns Command Queue false`() {
        val r = CommandRouter.route("/queue", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Command>(r)
        val cmd = r.cmd as SilkCommand.Queue
        assertEquals(false, cmd.clear)
    }

    @Test
    fun `route queue clear returns Command Queue true`() {
        val r = CommandRouter.route("/queue clear", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Command>(r)
        val cmd = r.cmd as SilkCommand.Queue
        assertEquals(true, cmd.clear)
    }

    @Test
    fun `route help returns Command Help`() {
        val r = CommandRouter.route("/help", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Command>(r)
        assertIs<SilkCommand.Help>(r.cmd)
    }

    @Test
    fun `route compact returns Command Compact`() {
        val r = CommandRouter.route("/compact", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Command>(r)
        assertIs<SilkCommand.Compact>(r.cmd)
    }

    @Test
    fun `route plain text with currentAgent returns Prompt`() {
        val r = CommandRouter.route("hello world", "u1", "g1", "claude-code")
        assertIs<CommandRouter.RouteResult.Prompt>(r)
        assertEquals("hello world", r.text)
    }

    @Test
    fun `route plain text without currentAgent returns PassThrough`() {
        val r = CommandRouter.route("hello world", "u1", "g1", null)
        assertIs<CommandRouter.RouteResult.PassThrough>(r)
    }
}
