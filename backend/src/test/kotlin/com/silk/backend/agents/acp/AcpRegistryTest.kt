package com.silk.backend.agents.acp

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcpRegistryTest {

    @BeforeTest
    fun reset() = AcpRegistry.clearForTest()

    @AfterTest
    fun cleanup() = AcpRegistry.clearForTest()

    @Test
    fun `put and get by userId+agentType`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        AcpRegistry.put("user1", "claude-code", client, remoteIp = "127.0.0.1")
        assertEquals(client, AcpRegistry.get("user1", "claude-code"))
        assertTrue(AcpRegistry.isConnected("user1", "claude-code"))
        assertEquals("127.0.0.1", AcpRegistry.getRemoteIp("user1", "claude-code"))
        assertEquals(listOf("claude-code"), AcpRegistry.listConnected("user1"))
    }

    @Test
    fun `unregister removes entry`() = runTest {
        val transport = InMemoryAcpTransport()
        val client = AcpClient(transport, scope = backgroundScope)
        AcpRegistry.put("user1", "claude-code", client, remoteIp = null)
        AcpRegistry.unregister("user1", "claude-code")
        assertNull(AcpRegistry.get("user1", "claude-code"))
        assertTrue(AcpRegistry.listConnected("user1").isEmpty())
    }

    @Test
    fun `conditional unregister does not remove replacement client`() = runTest {
        val previous = AcpClient(InMemoryAcpTransport(), scope = backgroundScope)
        val replacement = AcpClient(InMemoryAcpTransport(), scope = backgroundScope)
        AcpRegistry.put("user1", "claude-code", previous, remoteIp = null)
        AcpRegistry.put("user1", "claude-code", replacement, remoteIp = null)

        AcpRegistry.unregister("user1", "claude-code", previous)

        assertEquals(replacement, AcpRegistry.get("user1", "claude-code"))
    }

    @Test
    fun `put evicts previous client for same user+agent`() = runTest {
        val t1 = InMemoryAcpTransport()
        val c1 = AcpClient(t1, scope = backgroundScope)
        val t2 = InMemoryAcpTransport()
        val c2 = AcpClient(t2, scope = backgroundScope)
        AcpRegistry.put("u", "claude-code", c1, remoteIp = null)
        val evicted = AcpRegistry.put("u", "claude-code", c2, remoteIp = null)
        assertEquals(c1, evicted, "old client must be returned for caller to close")
        assertEquals(c2, AcpRegistry.get("u", "claude-code"))
    }

    @Test
    fun `same agent type on different instances remains independently addressable`() = runTest {
        val first = AcpClient(InMemoryAcpTransport(), scope = backgroundScope)
        val second = AcpClient(InMemoryAcpTransport(), scope = backgroundScope)
        AcpRegistry.put("u", "claude-code", first, remoteIp = "10.0.0.1", agentInstanceId = "instance-1")
        AcpRegistry.put("u", "claude-code", second, remoteIp = "10.0.0.2", agentInstanceId = "instance-2")

        assertEquals(first, AcpRegistry.getByInstance("instance-1"))
        assertEquals(second, AcpRegistry.getByInstance("instance-2"))
        assertNull(AcpRegistry.get("u", "claude-code"), "type-only lookup must not guess between instances")
        assertEquals(2, AcpRegistry.listConnectedInstances("u").size)
        assertTrue(AcpRegistry.isConnected("u", "claude-code"))

        AcpRegistry.unregister("instance-1")
        assertNull(AcpRegistry.getByInstance("instance-1"))
        assertEquals(second, AcpRegistry.getByInstance("instance-2"))
        assertTrue(AcpRegistry.isConnected("u", "claude-code"))
    }

    @Test
    fun `registry defaults to device signature authentication`() = runTest {
        val signed = AcpClient(InMemoryAcpTransport(), scope = backgroundScope)
        AcpRegistry.put("u", "codex", signed, remoteIp = null)

        assertEquals(
            AcpRegistry.AuthenticationMode.DEVICE_SIGNATURE,
            AcpRegistry.authenticationMode("u", "codex"),
        )
    }
}
