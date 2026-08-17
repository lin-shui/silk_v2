package com.silk.backend.agents.acp

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcpMultiplexedTransportTest {
    @Test
    fun `routes each logical stream independently and closes idempotently`() = runTest {
        val firstOutbound = mutableListOf<String>()
        val secondOutbound = mutableListOf<String>()
        val closeReasons = mutableListOf<String>()
        val first = AcpMultiplexedTransport(
            sendToHost = { firstOutbound += it },
            onClose = { closeReasons += it },
        )
        val second = AcpMultiplexedTransport(sendToHost = { secondOutbound += it })

        first.send("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
        second.send("""{"jsonrpc":"2.0","id":2,"method":"initialize"}""")
        assertEquals(listOf("""{"jsonrpc":"2.0","id":1,"method":"initialize"}"""), firstOutbound)
        assertEquals(listOf("""{"jsonrpc":"2.0","id":2,"method":"initialize"}"""), secondOutbound)

        val firstIncoming = async { first.incoming.first() }
        val secondIncoming = async { second.incoming.first() }
        first.acceptFromHost("""{"jsonrpc":"2.0","id":1,"result":{}}""")
        second.acceptFromHost("""{"jsonrpc":"2.0","id":2,"result":{}}""")
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", firstIncoming.await())
        assertEquals("""{"jsonrpc":"2.0","id":2,"result":{}}""", secondIncoming.await())

        first.close("revoked")
        first.close("duplicate")
        assertEquals(listOf("revoked"), closeReasons)
        assertTrue(first.isClosed)
        assertFailsWith<IllegalStateException> {
            first.send("""{"jsonrpc":"2.0","id":3}""")
        }
    }

    @Test
    fun `host initiated close does not echo another close envelope`() = runTest {
        var closeNotifications = 0
        val transport = AcpMultiplexedTransport(
            sendToHost = {},
            onClose = { closeNotifications++ },
        )

        transport.closeFromHost("Host stopped Agent")

        assertTrue(transport.isClosed)
        assertEquals(0, closeNotifications)
    }
}
