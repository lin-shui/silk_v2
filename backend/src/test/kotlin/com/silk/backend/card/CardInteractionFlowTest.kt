package com.silk.backend.card

import com.silk.backend.Message
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the card interaction flow:
 *   backend builds card -> frontend parses -> user clicks -> frontend sends reply -> backend routes
 */
class CardInteractionFlowTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- JSON contract tests (guard against field-naming regressions) ----

    @Test
    fun `CardReplyPayload serializes cardId as camelCase`() {
        val payload = CardReplyPayload(cardId = "card-001", action = "confirm", inputs = mapOf("k" to "v"))
        val serialized = json.encodeToString(CardReplyPayload.serializer(), payload)
        assertTrue(serialized.contains("\"cardId\""), "JSON key must be camelCase 'cardId', got: $serialized")
        assertFalse(serialized.contains("\"card_id\""), "JSON key must NOT be snake_case 'card_id'")
    }

    @Test
    fun `CardReplyPayload deserializes camelCase JSON`() {
        val raw = """{"cardId":"card-002","action":"yes","inputs":{"name":"test"}}"""
        val reply = json.decodeFromString<CardReplyPayload>(raw)
        assertEquals("card-002", reply.cardId)
        assertEquals("yes", reply.action)
        assertEquals("test", reply.inputs["name"])
    }

    @Test
    fun `CardReplyPayload with empty inputs`() {
        val raw = """{"cardId":"card-003","action":"ok"}"""
        val reply = json.decodeFromString<CardReplyPayload>(raw)
        assertEquals("card-003", reply.cardId)
        assertEquals("ok", reply.action)
        assertTrue(reply.inputs.isEmpty())
    }

    // ---- Router dispatch ----

    @Test
    fun `registered handler receives card reply`() = runBlocking {
        CardReplyRouter.clear()

        var handlerReceived: CardReplyPayload? = null
        CardReplyRouter.register("card-flow-001", object : CardReplyHandler {
            override suspend fun onCardReply(
                reply: CardReplyPayload,
                sessionName: String,
                broadcastFn: suspend (Message) -> Unit,
            ) {
                handlerReceived = reply
            }
        })

        val reply = CardReplyPayload(cardId = "card-flow-001", action = "__opt__0__Option A")
        val expired = CardReplyRouter.route("group_test", reply) { }
        assertFalse(expired, "Handler is registered, should not be expired")
        assertEquals("__opt__0__Option A", handlerReceived?.action)
        assertEquals("card-flow-001", handlerReceived?.cardId)
    }

    @Test
    fun `expired card returns true and does not call handler`() = runBlocking {
        CardReplyRouter.clear()

        // No handler registered — simulate clicking a card after server restart
        val reply = CardReplyPayload(cardId = "card-expired", action = "click")
        val expired = CardReplyRouter.route("group_test", reply) { }
        assertTrue(expired, "Should return true when no handler is registered")
    }

    @Test
    fun `card disable flow - buildDisabled produces correct JSON`() {
        // Simulates what happens after a card reply: backend sends disabled card via action="edit"
        val disabledJson = CardBuilder("已回答", template = "green")
            .addText("你选择了：Option A")
            .buildDisabled()

        val card = json.decodeFromString<CardContent>(disabledJson)
        assertTrue(card.disabled)
        assertEquals("green", card.header.template)
        assertEquals("已回答", card.header.title)
        assertEquals("你选择了：Option A", card.elements[0].content)
    }
}
