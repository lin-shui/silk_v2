package com.silk.backend.card

import com.silk.backend.Message
import com.silk.backend.MessageType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardReplyRouterTest {

    @Test
    fun `register and route reply to handler`() = runBlocking {
        CardReplyRouter.clear()
        var receivedReply: CardReplyPayload? = null
        val handler = object : CardReplyHandler {
            override suspend fun onCardReply(
                reply: CardReplyPayload,
                sessionName: String,
                broadcastFn: suspend (Message) -> Unit,
            ) {
                receivedReply = reply
            }
        }

        CardReplyRouter.register("card_123", handler)

        val reply = CardReplyPayload(cardId = "card_123", action = "confirm", inputs = mapOf("name" to "test"))
        CardReplyRouter.route("group_1", reply) { }

        assertEquals("confirm", receivedReply?.action)
        assertEquals("test", receivedReply?.inputs?.get("name"))
    }

    @Test
    fun `unregister removes handler`() = runBlocking {
        CardReplyRouter.clear()
        var called = false
        val handler = object : CardReplyHandler {
            override suspend fun onCardReply(
                reply: CardReplyPayload,
                sessionName: String,
                broadcastFn: suspend (Message) -> Unit,
            ) {
                called = true
            }
        }

        CardReplyRouter.register("card_456", handler)
        CardReplyRouter.unregister("card_456")

        val reply = CardReplyPayload(cardId = "card_456", action = "ok")
        val expired = CardReplyRouter.route("group_1", reply) { }

        assertTrue(!called, "Handler should not be called after unregister")
        assertTrue(expired, "route should return true (expired) when handler not found")
    }

    @Test
    fun `route returns expired when no handler registered`() = runBlocking {
        CardReplyRouter.clear()
        val reply = CardReplyPayload(cardId = "nonexistent", action = "ok")
        val expired = CardReplyRouter.route("group_1", reply) { }
        assertTrue(expired)
    }
}
