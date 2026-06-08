package com.silk.backend.agents.acp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcpClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `initialize sends request and parses response`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)

        val deferred = async {
            client.initialize(
                InitializeParams(
                    protocolVersion = "0.2",
                    clientCapabilities = ClientCapabilities(
                        fs = FsCapability(readTextFile = true, writeTextFile = true),
                        terminal = false,
                    ),
                )
            )
        }

        // 1) 读 client 写出去的请求
        val sentLine = transport.readClientSent()
        val sentObj = json.parseToJsonElement(sentLine).jsonObject
        assertEquals("2.0", sentObj["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("initialize", sentObj["method"]!!.jsonPrimitive.content)
        val sentId = sentObj["id"]!!.jsonPrimitive.long
        assertTrue(sentId > 0)

        // 2) 模拟服务端响应
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$sentId,"result":{"protocolVersion":"0.2","agentCapabilities":{"loadSession":true,"promptCapabilities":{"image":false,"audio":false,"embeddedContext":false},"_silk":{"compact":true,"listLocalSessions":true,"setCwd":false}}}}"""
        )

        val result = deferred.await()
        assertEquals("0.2", result.protocolVersion)
        assertEquals(true, result.agentCapabilities.loadSession)
        assertEquals(true, result.agentCapabilities.silkExtensions.compact)
        assertEquals(false, result.agentCapabilities.silkExtensions.setCwd)
    }

    @Test
    fun `sessionNew sends correct request and returns sessionId`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)

        val deferred = async {
            client.sessionNew(cwd = "/home/u/proj")
        }

        val sent = json.parseToJsonElement(transport.readClientSent()).jsonObject
        assertEquals("session/new", sent["method"]!!.jsonPrimitive.content)
        val id = sent["id"]!!.jsonPrimitive.long
        val params = sent["params"]!!.jsonObject
        assertEquals("/home/u/proj", params["cwd"]!!.jsonPrimitive.content)

        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$id,"result":{"sessionId":"sess-abc"}}"""
        )
        val result = deferred.await()
        assertEquals("sess-abc", result.sessionId)
    }

    @Test
    fun `sessionPrompt returns stopReason`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        val deferred = async {
            client.sessionPrompt("sess-1", listOf(ContentBlock.Text("hello")))
        }
        val sent = json.parseToJsonElement(transport.readClientSent()).jsonObject
        assertEquals("session/prompt", sent["method"]!!.jsonPrimitive.content)
        val id = sent["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$id,"result":{"stopReason":"end_turn"}}"""
        )
        assertEquals(StopReason.END_TURN, deferred.await().stopReason)
    }

    @Test
    fun `sessionCancel sends notification with no id`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        client.sessionCancel("sess-1")
        val sent = json.parseToJsonElement(transport.readClientSent()).jsonObject
        assertEquals("session/cancel", sent["method"]!!.jsonPrimitive.content)
        assertTrue(sent["id"] == null, "notification must not contain id")
        assertEquals("sess-1", sent["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `callExtension throws on method_not_found`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        val deferred = async {
            try {
                client.callExtension("_silk/compact", kotlinx.serialization.json.buildJsonObject {})
                null
            } catch (e: AcpRpcException) {
                e
            }
        }
        val id = json.parseToJsonElement(transport.readClientSent()).jsonObject["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"method not found"}}"""
        )
        val ex = deferred.await()
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, ex!!.rpcError.code)
    }

    @Test
    fun `onSessionUpdate handler receives notifications`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        val received = mutableListOf<SessionUpdateNotification>()
        client.onSessionUpdate("s1") { received.add(it) }

        transport.pushFromServer(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hi"}}}}"""
        )

        // 给 receiveLoop 一个 tick 处理
        kotlinx.coroutines.yield()

        assertEquals(1, received.size)
        assertEquals("s1", received[0].sessionId)
    }

    @Test
    fun `onPermissionRequest handler answers server`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        client.onPermissionRequest("s1") { req ->
            PermissionResponse(
                outcome = kotlinx.serialization.json.buildJsonObject {
                    put("kind", kotlinx.serialization.json.JsonPrimitive("selected"))
                    put("optionId", kotlinx.serialization.json.JsonPrimitive("approve"))
                }
            )
        }
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":42,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"name":"bash"},"options":[]}}"""
        )
        val replyLine = transport.readClientSent()
        val reply = json.parseToJsonElement(replyLine).jsonObject
        assertEquals(42L, reply["id"]!!.jsonPrimitive.long)
        val outcome = reply["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("selected", outcome["kind"]!!.jsonPrimitive.content)
        assertEquals("approve", outcome["optionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `transport close fails pending requests`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        val deferred = async {
            try { client.sessionNew(cwd = "/x"); null }
            catch (e: Exception) { e }
        }
        transport.readClientSent()  // drain the request
        transport.close("test")
        val ex = deferred.await()
        assertTrue(ex is IllegalStateException, "expected IllegalStateException, got $ex")
    }

    @Test
    fun `malformed JSON does not kill receive loop`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)

        // push garbage, then a valid request flow must still succeed
        transport.pushFromServer("{this is not valid json")

        val deferred = async {
            client.sessionNew(cwd = "/x")
        }
        val sent = json.parseToJsonElement(transport.readClientSent()).jsonObject
        val id = sent["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$id,"result":{"sessionId":"s-ok"}}"""
        )
        assertEquals("s-ok", deferred.await().sessionId)
    }

    @Test
    fun `handler throwing does not kill receive loop`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        client.onSessionUpdate("s1") { throw RuntimeException("boom") }

        // first notification — handler throws; loop must survive
        transport.pushFromServer(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk"}}}"""
        )
        kotlinx.coroutines.yield()

        // subsequent RPC must still work
        val deferred = async { client.sessionNew(cwd = "/y") }
        val sent = json.parseToJsonElement(transport.readClientSent()).jsonObject
        val id = sent["id"]!!.jsonPrimitive.long
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$id,"result":{"sessionId":"s-survived"}}"""
        )
        assertEquals("s-survived", deferred.await().sessionId)
    }

    @Test
    fun `multi-session handlers dispatch independently`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        val receivedA = mutableListOf<SessionUpdateNotification>()
        val receivedB = mutableListOf<SessionUpdateNotification>()
        client.onSessionUpdate("sess-A") { receivedA.add(it) }
        client.onSessionUpdate("sess-B") { receivedB.add(it) }

        transport.pushFromServer(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-A","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"a1"}}}}"""
        )
        transport.pushFromServer(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-B","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"b1"}}}}"""
        )
        transport.pushFromServer(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-A","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"a2"}}}}"""
        )
        kotlinx.coroutines.yield()

        assertEquals(2, receivedA.size)
        assertEquals(1, receivedB.size)
        assertEquals("sess-A", receivedA[0].sessionId)
        assertEquals("sess-A", receivedA[1].sessionId)
        assertEquals("sess-B", receivedB[0].sessionId)
    }

    @Test
    fun `removeHandlers stops delivery for that session`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        val received = mutableListOf<SessionUpdateNotification>()
        client.onSessionUpdate("sess-X") { received.add(it) }

        transport.pushFromServer(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-X","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"first"}}}}"""
        )
        kotlinx.coroutines.yield()
        assertEquals(1, received.size)

        client.removeHandlers("sess-X")

        transport.pushFromServer(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-X","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"second"}}}}"""
        )
        kotlinx.coroutines.yield()
        assertEquals(1, received.size, "notification after removeHandlers should be silently dropped")
    }

    @Test
    fun `permission request for unregistered session returns error`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        // No handler registered for "sess-unknown"

        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":50,"method":"session/request_permission","params":{"sessionId":"sess-unknown","toolCall":{"name":"bash"},"options":[]}}"""
        )
        val reply = json.parseToJsonElement(transport.readClientSent()).jsonObject
        assertEquals(50L, reply["id"]!!.jsonPrimitive.long)
        assertTrue(reply["error"] != null, "should return error for unregistered session")
    }

    @Test
    fun `call times out when server does not respond`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)

        // 发送 sessionNew 但不推送任何 response → 应超时
        assertFailsWith<TimeoutCancellationException> {
            kotlinx.coroutines.withTimeout(AcpClient.DEFAULT_TIMEOUT_MS + 1_000) {
                client.sessionNew(cwd = "/tmp")
            }
        }
    }

    @Test
    fun `call succeeds when response arrives before timeout`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)

        val deferred = async {
            client.sessionNew(cwd = "/tmp")
        }

        val sentLine = transport.readClientSent()
        val sentId = json.parseToJsonElement(sentLine).jsonObject["id"]!!.jsonPrimitive.long

        // 立即响应 → 不应超时
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$sentId,"result":{"sessionId":"test-session-1"}}"""
        )

        val result = deferred.await()
        assertEquals("test-session-1", result.sessionId)
    }

    @Test
    fun `pending map is cleaned up after timeout`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)

        // 让 sessionNew 超时
        try {
            kotlinx.coroutines.withTimeout(AcpClient.DEFAULT_TIMEOUT_MS + 1_000) {
                client.sessionNew(cwd = "/tmp")
            }
        } catch (_: TimeoutCancellationException) {
            // expected
        }

        // 超时后应该还能正常发请求（pending map 没泄漏，transport 没坏）
        val deferred = async {
            client.initialize(
                InitializeParams(
                    protocolVersion = "0.2",
                    clientCapabilities = ClientCapabilities(
                        fs = FsCapability(readTextFile = true, writeTextFile = true),
                        terminal = false,
                    ),
                )
            )
        }

        val sentLine = transport.readClientSent()
        // 跳过超时请求的 sent（可能已在 channel 中）
        val secondLine = try { transport.readClientSent() } catch (_: Exception) { sentLine }
        val obj = json.parseToJsonElement(secondLine).jsonObject
        val sentId = obj["id"]!!.jsonPrimitive.long

        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":$sentId,"result":{"protocolVersion":"0.2","agentCapabilities":{"loadSession":false,"promptCapabilities":{"image":false,"audio":false,"embeddedContext":false}}}}"""
        )

        val result = deferred.await()
        assertEquals("0.2", result.protocolVersion)
    }

    @Test
    fun `onPermissionRequest dispatches by sessionId`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        client.onPermissionRequest("sess-P") { req ->
            PermissionResponse(
                outcome = kotlinx.serialization.json.buildJsonObject {
                    put("kind", kotlinx.serialization.json.JsonPrimitive("selected"))
                    put("optionId", kotlinx.serialization.json.JsonPrimitive("approve"))
                }
            )
        }
        transport.pushFromServer(
            """{"jsonrpc":"2.0","id":99,"method":"session/request_permission","params":{"sessionId":"sess-P","toolCall":{"name":"bash"},"options":[]}}"""
        )
        val replyLine = transport.readClientSent()
        val reply = json.parseToJsonElement(replyLine).jsonObject
        assertEquals(99L, reply["id"]!!.jsonPrimitive.long)
        val outcome = reply["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("selected", outcome["kind"]!!.jsonPrimitive.content)
        assertEquals("approve", outcome["optionId"]!!.jsonPrimitive.content)
    }
}
