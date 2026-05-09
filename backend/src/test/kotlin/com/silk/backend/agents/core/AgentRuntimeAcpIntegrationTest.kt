// backend/src/test/kotlin/com/silk/backend/agents/core/AgentRuntimeAcpIntegrationTest.kt
package com.silk.backend.agents.core

import com.silk.backend.Message
import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.acp.InMemoryAcpTransport
import com.silk.backend.agents.adapters.claudecode.ClaudeCodeDescriptor
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRuntimeAcpIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        AgentRegistry.clearForTest()
        AgentRuntime.clearForTest()
        AcpRegistry.clearForTest()
        AgentRegistry.register(ClaudeCodeDescriptor)
    }

    @Test
    fun `prompt lifecycle sends sessionNew and sessionPrompt`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        AcpRegistry.put("u1", "claude-code", client, remoteIp = "127.0.0.1")

        AgentRuntime.autoActivateForWorkflow("u1", "g1", "claude-code")

        val deferred = async {
            AgentRuntime.handleIfActive(
                userId = "u1",
                groupId = "g1",
                text = "hello",
                userName = "Alice",
                broadcastFn = {},
            )
        }

        // 1. 读 sessionNew 请求
        val newReq = json.parseToJsonElement(transport.readClientSent()).jsonObject
        assertEquals("session/new", newReq["method"]!!.jsonPrimitive.content)
        val newId = newReq["id"]!!.jsonPrimitive.long
        val newParams = newReq["params"]!!.jsonObject
        assertTrue(newParams["cwd"]!!.jsonPrimitive.content.isNotEmpty())

        // 2. 响应 sessionNew
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$newId,"result":{"sessionId":"sess-abc"}}"""
        )

        // 3. 读 sessionPrompt 请求
        val promptReq = json.parseToJsonElement(transport.readClientSent()).jsonObject
        assertEquals("session/prompt", promptReq["method"]!!.jsonPrimitive.content)
        val promptId = promptReq["id"]!!.jsonPrimitive.long
        val promptParams = promptReq["params"]!!.jsonObject
        assertEquals("sess-abc", promptParams["sessionId"]!!.jsonPrimitive.content)
        val promptBlocks = promptParams["prompt"]!!.jsonArray
        assertTrue(promptBlocks.any { it.jsonObject["text"]?.jsonPrimitive?.content == "hello" })

        // 4. 响应 sessionPrompt (end_turn)
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$promptId,"result":{"stopReason":"end_turn"}}"""
        )

        val handled = deferred.await()
        assertTrue(handled)
    }

    @Test
    fun `prompt without bridge connection shows not connected message`() = runTest {
        AgentRuntime.autoActivateForWorkflow("u1", "g1", "claude-code")

        val messages = mutableListOf<Message>()
        val handled = AgentRuntime.handleIfActive(
            userId = "u1",
            groupId = "g1",
            text = "hello",
            userName = "Alice",
            broadcastFn = { messages.add(it) },
        )
        assertTrue(handled)
        assertTrue(messages.any { it.content.contains("未连接") })
    }

    @Test
    fun `cancel sends sessionCancel notification`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        AcpRegistry.put("u1", "claude-code", client, remoteIp = "127.0.0.1")

        AgentRuntime.autoActivateForWorkflow("u1", "g1", "claude-code")

        // Start a prompt in background
        val promptDeferred = async {
            AgentRuntime.handleIfActive(
                userId = "u1",
                groupId = "g1",
                text = "hello",
                userName = "Alice",
                broadcastFn = {},
            )
        }

        // Wait for sessionNew request
        val newReq = json.parseToJsonElement(transport.readClientSent()).jsonObject
        val newId = newReq["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$newId,"result":{"sessionId":"sess-abc"}}"""
        )

        // Wait for sessionPrompt request (but don't respond yet)
        val promptReq = json.parseToJsonElement(transport.readClientSent()).jsonObject
        assertEquals("session/prompt", promptReq["method"]!!.jsonPrimitive.content)

        // Cancel the active session
        val cancelled = AgentRuntime.cancelIfActive("u1", "g1") {}
        assertTrue(cancelled)

        // Read the sessionCancel notification
        val cancelSent = json.parseToJsonElement(transport.readClientSent()).jsonObject
        assertEquals("session/cancel", cancelSent["method"]!!.jsonPrimitive.content)
        assertTrue(cancelSent["id"] == null, "cancel must be notification without id")
        assertEquals("sess-abc", cancelSent["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)

        // Complete the prompt so the background deferred can finish
        val promptId = promptReq["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$promptId,"result":{"stopReason":"cancelled"}}"""
        )
        promptDeferred.await()
    }

    @Test
    fun `handleAgentDisconnect clears running state`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        AcpRegistry.put("u1", "claude-code", client, remoteIp = "127.0.0.1")

        AgentRuntime.autoActivateForWorkflow("u1", "g1", "claude-code")

        // Start a prompt
        val promptDeferred = async {
            AgentRuntime.handleIfActive(
                userId = "u1",
                groupId = "g1",
                text = "hello",
                userName = "Alice",
                broadcastFn = {},
            )
        }

        // Complete sessionNew
        val newReq = json.parseToJsonElement(transport.readClientSent()).jsonObject
        val newId = newReq["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$newId,"result":{"sessionId":"sess-abc"}}"""
        )

        // Wait for sessionPrompt
        val promptReq = json.parseToJsonElement(transport.readClientSent()).jsonObject

        // Simulate bridge disconnect
        AgentRuntime.handleAgentDisconnect("u1", "claude-code")

        // Complete prompt so deferred can finish
        val promptId = promptReq["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$promptId,"result":{"stopReason":"end_turn"}}"""
        )
        promptDeferred.await()
    }

    @Test
    fun `stopReason max_tokens shows warning`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        AcpRegistry.put("u1", "claude-code", client, remoteIp = "127.0.0.1")

        AgentRuntime.autoActivateForWorkflow("u1", "g1", "claude-code")

        val messages = mutableListOf<Message>()
        val deferred = async {
            AgentRuntime.handleIfActive(
                userId = "u1",
                groupId = "g1",
                text = "hello",
                userName = "Alice",
                broadcastFn = { messages.add(it) },
            )
        }

        val newReq = json.parseToJsonElement(transport.readClientSent()).jsonObject
        val newId = newReq["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$newId,"result":{"sessionId":"sess-abc"}}"""
        )

        val promptReq = json.parseToJsonElement(transport.readClientSent()).jsonObject
        val promptId = promptReq["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$promptId,"result":{"stopReason":"max_tokens"}}"""
        )

        deferred.await()
        // handlePrompt returns immediately; wait for background prompt coroutine to finish
        AgentRuntime.snapshotState("u1", "g1")  // ensure context exists
        awaitSessionIdle("u1", "g1", "claude-code")
        assertTrue(messages.any { it.content.contains("token") })
    }

    @Test
    fun `stopReason refusal shows refusal message`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        AcpRegistry.put("u1", "claude-code", client, remoteIp = "127.0.0.1")

        AgentRuntime.autoActivateForWorkflow("u1", "g1", "claude-code")

        val messages = mutableListOf<Message>()
        val deferred = async {
            AgentRuntime.handleIfActive(
                userId = "u1",
                groupId = "g1",
                text = "hello",
                userName = "Alice",
                broadcastFn = { messages.add(it) },
            )
        }

        val newReq = json.parseToJsonElement(transport.readClientSent()).jsonObject
        val newId = newReq["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$newId,"result":{"sessionId":"sess-abc"}}"""
        )

        val promptReq = json.parseToJsonElement(transport.readClientSent()).jsonObject
        val promptId = promptReq["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$promptId,"result":{"stopReason":"refusal"}}"""
        )

        deferred.await()
        // handlePrompt returns immediately; wait for background prompt coroutine to finish
        awaitSessionIdle("u1", "g1", "claude-code")
        assertTrue(messages.any { it.content.contains("拒绝") })
    }

    /** Wait for the background prompt coroutine to complete (session.running becomes false). */
    private suspend fun awaitSessionIdle(userId: String, groupId: String, agentType: String, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = AgentRuntime.snapshotState(userId, groupId)
            if (state == null || !state.running) return
            kotlinx.coroutines.delay(10)
        }
        throw AssertionError("Session did not become idle within ${timeoutMs}ms")
    }
}
