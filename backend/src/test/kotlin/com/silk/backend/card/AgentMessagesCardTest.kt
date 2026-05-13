package com.silk.backend.card

import com.silk.backend.MessageCategory
import com.silk.backend.MessageType
import com.silk.backend.agents.core.AgentMessages
import com.silk.backend.agents.core.QuestionOption
import com.silk.backend.agents.core.StructuredQuestion
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentMessagesCardTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun singleQ(question: String, vararg options: Pair<String, String>) =
        listOf(StructuredQuestion(
            question = question,
            options = options.map { (l, d) -> QuestionOption(l, d) },
        ))

    private fun multiQ(vararg questions: StructuredQuestion) = questions.toList()

    // ---- Basic metadata ----

    @Test
    fun `questionCard produces CARD message with correct metadata`() {
        val msg = AgentMessages.questionCard(
            questions = singleQ("Pick one", "A" to "Option A", "B" to "Option B"),
            requestId = "req-001",
            agentUserId = "agent_user",
            agentName = "TestAgent",
        )

        assertEquals("agent_question_req-001", msg.id)
        assertEquals(MessageType.CARD, msg.type)
        assertEquals(MessageCategory.AGENT_QUESTION, msg.category)
        assertEquals("agent_user", msg.userId)
        assertEquals("TestAgent", msg.userName)
        assertFalse(msg.isTransient)
    }

    @Test
    fun `questionCard content is valid CardContent JSON`() {
        val msg = AgentMessages.questionCard(
            questions = singleQ("Use method A?", "Yes" to "", "No" to ""),
            requestId = "req-002",
            agentUserId = "agent_user",
            agentName = "CC",
        )

        val card = json.decodeFromString<CardContent>(msg.content)
        assertTrue(card.header.title.contains("CC"))
        assertEquals("gold", card.header.template)
        assertFalse(card.disabled)
    }

    // ---- Single question with options ----

    @Test
    fun `single question card shows per-option buttons`() {
        val msg = AgentMessages.questionCard(
            questions = singleQ("Pick language", "Kotlin" to "JVM", "Python" to "Scripting", "Go" to "Systems"),
            requestId = "req-003",
            agentUserId = "agent_user",
            agentName = "Agent",
        )

        val card = json.decodeFromString<CardContent>(msg.content)
        val buttons = card.elements.filter { it.tag == "button" }
        // 3 option buttons + 1 custom submit button
        assertEquals(4, buttons.size)
        assertEquals("Kotlin", buttons[0].text)
        assertEquals("Python", buttons[1].text)
        assertEquals("Go", buttons[2].text)
        // First button is PRIMARY
        assertEquals("primary", buttons[0].type)
        assertEquals("default", buttons[1].type)
    }

    @Test
    fun `button value encodes question index and answer label`() {
        val msg = AgentMessages.questionCard(
            questions = singleQ("Pick", "Yes" to "Do it"),
            requestId = "req-btn",
            agentUserId = "a",
            agentName = "A",
        )

        val card = json.decodeFromString<CardContent>(msg.content)
        val optButton = card.elements.first { it.tag == "button" && it.text == "Yes" }
        assertTrue(optButton.value!!.startsWith("__opt__0__"), "Value should encode qi=0: ${optButton.value}")
        assertTrue(optButton.value!!.contains("Yes - Do it"))
    }

    @Test
    fun `questionCard contains custom input field for current question`() {
        val msg = AgentMessages.questionCard(
            questions = singleQ("Proceed?"),
            requestId = "req-004",
            agentUserId = "agent_user",
            agentName = "Agent",
        )

        val card = json.decodeFromString<CardContent>(msg.content)
        val inputs = card.elements.filter { it.tag == "text_input" }
        assertEquals(1, inputs.size)
        assertEquals("custom_answer_0", inputs[0].name)
    }

    // ---- Multi-question display ----

    @Test
    fun `multi-question card shows progress in title`() {
        val qs = multiQ(
            StructuredQuestion("Q1", options = listOf(QuestionOption("A"), QuestionOption("B"))),
            StructuredQuestion("Q2", options = listOf(QuestionOption("C"), QuestionOption("D"))),
        )
        val msg = AgentMessages.questionCard(
            questions = qs,
            requestId = "req-multi",
            agentUserId = "a",
            agentName = "Agent",
            currentIndex = 0,
            answers = emptyMap(),
        )
        val card = json.decodeFromString<CardContent>(msg.content)
        assertTrue(card.header.title.contains("1/2"), "Title should show progress: ${card.header.title}")
    }

    @Test
    fun `multi-question card only shows buttons for current question`() {
        val qs = multiQ(
            StructuredQuestion("Q1", options = listOf(QuestionOption("A"), QuestionOption("B"))),
            StructuredQuestion("Q2", options = listOf(QuestionOption("C"), QuestionOption("D"))),
        )
        val msg = AgentMessages.questionCard(
            questions = qs,
            requestId = "req-multi2",
            agentUserId = "a",
            agentName = "Agent",
            currentIndex = 0,
            answers = emptyMap(),
        )
        val card = json.decodeFromString<CardContent>(msg.content)
        val buttons = card.elements.filter { it.tag == "button" }
        // Only Q1's 2 option buttons + 1 custom submit = 3
        assertEquals(3, buttons.size)
        assertEquals("A", buttons[0].text)
        assertEquals("B", buttons[1].text)
    }

    @Test
    fun `multi-question card with partial answers shows answered and next question`() {
        val qs = multiQ(
            StructuredQuestion("Q1", options = listOf(QuestionOption("A"), QuestionOption("B"))),
            StructuredQuestion("Q2", options = listOf(QuestionOption("C"), QuestionOption("D"))),
        )
        val msg = AgentMessages.questionCard(
            questions = qs,
            requestId = "req-partial",
            agentUserId = "a",
            agentName = "Agent",
            currentIndex = 1,
            answers = mapOf(0 to "A"),
        )
        val card = json.decodeFromString<CardContent>(msg.content)
        val buttons = card.elements.filter { it.tag == "button" }
        // Q2's 2 option buttons + 1 custom submit = 3
        assertEquals(3, buttons.size)
        assertEquals("C", buttons[0].text)
        // Title shows 2/2 (second question)
        assertTrue(card.header.title.contains("2/2"))
    }

    // ---- Completed card ----

    @Test
    fun `questionCardCompleted produces disabled green card`() {
        val qs = listOf(StructuredQuestion("Q1"), StructuredQuestion("Q2"))
        val msg = AgentMessages.questionCardCompleted(
            questions = qs,
            answers = mapOf(0 to "answer1", 1 to "answer2"),
            requestId = "req-done",
            agentUserId = "a",
            agentName = "Agent",
        )
        val card = json.decodeFromString<CardContent>(msg.content)
        assertTrue(card.disabled)
        assertEquals("green", card.header.template)
        assertEquals("edit", msg.action)
    }

    // ---- String fallback (no options) ----

    @Test
    fun `question without options shows question text and custom input only`() {
        val msg = AgentMessages.questionCard(
            questions = listOf(StructuredQuestion("Please describe your problem")),
            requestId = "req-text",
            agentUserId = "a",
            agentName = "Agent",
        )
        val card = json.decodeFromString<CardContent>(msg.content)
        val buttons = card.elements.filter { it.tag == "button" }
        // No option buttons, only 1 custom submit
        assertEquals(1, buttons.size)
        assertTrue(buttons[0].value!!.startsWith("__custom__"))
    }

    // ---- cleanAnswerText unit tests ----

    @Test
    fun `cleanAnswerText strips __opt__ prefix correctly`() {
        assertEquals(
            "Python - 简洁易用，AI/脚本常用",
            AgentMessages.cleanAnswerText("__opt__0__Python - 简洁易用，AI/脚本常用"),
        )
        assertEquals(
            "晴天 - 阳光明媚",
            AgentMessages.cleanAnswerText("__opt__2__晴天 - 阳光明媚"),
        )
        // Two-digit index
        assertEquals(
            "Some option",
            AgentMessages.cleanAnswerText("__opt__12__Some option"),
        )
    }

    @Test
    fun `cleanAnswerText handles __custom__ prefix`() {
        assertEquals("（自定义回答）", AgentMessages.cleanAnswerText("__custom__0"))
        assertEquals("（自定义回答）", AgentMessages.cleanAnswerText("__custom__2"))
    }

    @Test
    fun `cleanAnswerText passes through normal text unchanged`() {
        assertEquals("Python - 简洁易用", AgentMessages.cleanAnswerText("Python - 简洁易用"))
        assertEquals("hello", AgentMessages.cleanAnswerText("hello"))
        assertEquals("", AgentMessages.cleanAnswerText(""))
    }

    // ---- Card content must NOT contain __opt__ or __custom__ in display text ----

    @Test
    fun `intermediate card with raw answers does not show __opt__ prefix in display`() {
        val qs = multiQ(
            StructuredQuestion("你喜欢什么语言？", options = listOf(
                QuestionOption("Python", "简洁易用"),
                QuestionOption("Kotlin", "JVM 生态"),
            )),
            StructuredQuestion("今天心情如何？", options = listOf(
                QuestionOption("开心"),
                QuestionOption("一般"),
            )),
        )

        // Simulate: Q1 answered with raw button value (worst case: prefix NOT stripped at storage)
        val rawAnswer = "__opt__0__Python - 简洁易用"
        val msg = AgentMessages.questionCard(
            questions = qs,
            requestId = "req-display",
            agentUserId = "a",
            agentName = "Agent",
            currentIndex = 1,
            answers = mapOf(0 to rawAnswer),
        )

        val card = json.decodeFromString<CardContent>(msg.content)
        val allText = card.elements.filter { it.tag == "text" }.joinToString("\n") { it.content ?: "" }

        // Must NOT contain the internal prefix
        assertFalse(allText.contains("__opt__"), "Card text should not contain __opt__ prefix. Got:\n$allText")
        // Must contain the clean answer
        assertTrue(allText.contains("Python - 简洁易用"), "Card text should contain clean answer. Got:\n$allText")
        // Must show "已选择" with clean text
        assertTrue(allText.contains("已选择: Python - 简洁易用"), "Should show '已选择: Python - 简洁易用'. Got:\n$allText")
    }

    @Test
    fun `completed card with raw answers does not show __opt__ prefix`() {
        val qs = listOf(
            StructuredQuestion("你喜欢什么语言？"),
            StructuredQuestion("今天天气怎么样？"),
            StructuredQuestion("补充说明"),
        )

        // Simulate worst case: all answers still have raw prefixes
        val answers = mapOf(
            0 to "__opt__0__Python - 简洁易用",
            1 to "__opt__1__晴天 - 阳光明媚",
            2 to "__custom__2",
        )
        val msg = AgentMessages.questionCardCompleted(
            questions = qs,
            answers = answers,
            requestId = "req-complete",
            agentUserId = "a",
            agentName = "Agent",
        )

        val card = json.decodeFromString<CardContent>(msg.content)
        val allText = card.elements.filter { it.tag == "text" }.joinToString("\n") { it.content ?: "" }

        assertFalse(allText.contains("__opt__"), "Completed card must not contain __opt__. Got:\n$allText")
        assertFalse(allText.contains("__custom__"), "Completed card must not contain __custom__. Got:\n$allText")
        assertTrue(allText.contains("Python - 简洁易用"), "Should contain clean answer")
        assertTrue(allText.contains("晴天 - 阳光明媚"), "Should contain clean answer")
        assertTrue(allText.contains("（自定义回答）"), "Custom answer should show placeholder")
        // Full question text preserved (issue 2 fix)
        assertTrue(allText.contains("你喜欢什么语言？"), "Should show full question text")
        assertTrue(allText.contains("今天天气怎么样？"), "Should show full question text")
    }
}
