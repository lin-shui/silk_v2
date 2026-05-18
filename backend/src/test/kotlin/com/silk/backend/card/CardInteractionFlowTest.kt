package com.silk.backend.card

import com.silk.backend.Message
import com.silk.backend.MessageType
import com.silk.backend.agents.core.AgentMessages
import com.silk.backend.agents.core.QuestionOption
import com.silk.backend.agents.core.StructuredQuestion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the card interaction flow end-to-end:
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

    // ---- End-to-end card flow ----

    @Test
    fun `full card interaction flow - option button click`() = runBlocking {
        CardReplyRouter.clear()

        // 1. Backend builds a question card with structured options
        val questions = listOf(StructuredQuestion(
            question = "Pick one",
            options = listOf(QuestionOption("Option A"), QuestionOption("Option B")),
        ))
        val cardMsg = AgentMessages.questionCard(
            questions = questions,
            requestId = "flow-001",
            agentUserId = "agent",
            agentName = "CC",
        )
        assertEquals(MessageType.CARD, cardMsg.type)
        val cardId = cardMsg.id  // "agent_question_flow-001"

        // 2. Register a handler (simulating what AgentRuntime does)
        var handlerReceived: CardReplyPayload? = null
        CardReplyRouter.register(cardId, object : CardReplyHandler {
            override suspend fun onCardReply(
                reply: CardReplyPayload,
                sessionName: String,
                broadcastFn: suspend (Message) -> Unit,
            ) {
                handlerReceived = reply
            }
        })

        // 3. Simulate frontend sending CARD_REPLY (clicking option button)
        val reply = CardReplyPayload(cardId = cardId, action = "__opt__0__Option A")

        // 4. Route the reply
        val expired = CardReplyRouter.route("group_test", reply) { }
        assertFalse(expired, "Handler is registered, should not be expired")
        assertEquals("__opt__0__Option A", handlerReceived?.action)
        assertEquals(cardId, handlerReceived?.cardId)
    }

    @Test
    fun `full card interaction flow - custom answer via __custom__`() = runBlocking {
        CardReplyRouter.clear()

        val questions = listOf(StructuredQuestion(
            question = "Pick",
            options = listOf(QuestionOption("Yes"), QuestionOption("No")),
        ))
        val cardMsg = AgentMessages.questionCard(
            questions = questions,
            requestId = "flow-002",
            agentUserId = "agent",
            agentName = "CC",
        )
        val cardId = cardMsg.id

        var handlerReceived: CardReplyPayload? = null
        CardReplyRouter.register(cardId, object : CardReplyHandler {
            override suspend fun onCardReply(
                reply: CardReplyPayload,
                sessionName: String,
                broadcastFn: suspend (Message) -> Unit,
            ) {
                handlerReceived = reply
            }
        })

        // User types custom answer and clicks submit
        val reply = CardReplyPayload(
            cardId = cardId,
            action = "__custom__0",
            inputs = mapOf("custom_answer_0" to "I have a different idea"),
        )
        val expired = CardReplyRouter.route("group_test", reply) { }
        assertFalse(expired)
        assertEquals("__custom__0", handlerReceived?.action)
        assertEquals("I have a different idea", handlerReceived?.inputs?.get("custom_answer_0"))
    }

    @Test
    fun `expired card returns true and does not call handler`() = runBlocking {
        CardReplyRouter.clear()

        // No handler registered — simulate clicking a card after server restart
        val reply = CardReplyPayload(cardId = "agent_question_expired", action = "click")
        val expired = CardReplyRouter.route("group_test", reply) { }
        assertTrue(expired, "Should return expired when no handler is registered")
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

    @Test
    fun `multi-question flow - card refreshes after partial answer`() {
        val qs = listOf(
            StructuredQuestion("Q1", options = listOf(QuestionOption("A"), QuestionOption("B"))),
            StructuredQuestion("Q2", options = listOf(QuestionOption("C"), QuestionOption("D"))),
        )

        // First card: Q1 is current
        val msg1 = AgentMessages.questionCard(
            questions = qs, requestId = "flow-multi", agentUserId = "a", agentName = "Agent",
            currentIndex = 0, answers = emptyMap(),
        )
        val card1 = json.decodeFromString<CardContent>(msg1.content)
        assertTrue(card1.header.title.contains("1/2"))

        // After Q1 answered, refresh card for Q2
        val msg2 = AgentMessages.questionCard(
            questions = qs, requestId = "flow-multi", agentUserId = "a", agentName = "Agent",
            currentIndex = 1, answers = mapOf(0 to "A"),
        )
        val card2 = json.decodeFromString<CardContent>(msg2.content)
        assertTrue(card2.header.title.contains("2/2"))
        // Buttons should be for Q2 options
        val buttons = card2.elements.filter { it.tag == "button" && !it.value!!.startsWith("__custom__") }
        assertEquals(2, buttons.size)
        assertEquals("C", buttons[0].text)
        assertEquals("D", buttons[1].text)
    }
}
