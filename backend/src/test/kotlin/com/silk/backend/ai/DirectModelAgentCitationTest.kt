package com.silk.backend.ai

import com.silk.backend.models.ChatHistoryEntry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectModelAgentCitationTest {
    @Test
    fun `citation guidelines include required marker policy`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        val prompt = agent.citationGuidelinesForTest("你是 Silk。")

        assertContains(prompt, "[citation:数字]")
        assertContains(prompt, "每个重要观点都必须标注来源引用")
        assertContains(prompt, "参考来源列表（必须遵守）")
        assertContains(prompt, "URL 必须是完整的 https:// 链接")
    }

    @Test
    fun `explicitly registered citations appear in references`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()

        val idx = agent.registerCitationForTest("测试来源", "https://example.com/test")
        assertEquals(1, idx)

        val refs = agent.referencesForTest()
        assertEquals(1, refs.size)
        assertEquals("citation", refs.first().kind)
        assertEquals(1, refs.first().index)
        assertEquals("https://example.com/test", refs.first().url)
    }

    @Test
    fun `final content gets fallback citation markers when model omits them`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()
        agent.registerCitationForTest("来源标题", "https://example.com/source")

        val content = agent.ensureCitationMarkersForTest("这是没有引用标记的回答。")

        assertContains(content, "[citation:1]")
    }

    @Test
    fun `citation verifier removes nonexistent markers and keeps valid ones`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()
        agent.registerCitationForTest("有效来源", "https://example.com/valid")

        val content = agent.ensureCitationMarkersForTest("有效观点 [citation:1] 错误观点 [citation:9]")

        assertContains(content, "[citation:1]")
        assertTrue(!content.contains("[citation:9]"))
    }

    @Test
    fun `citation verifier keeps mixed available and citation markers with per kind indexes`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()
        agent.registerAvailableForTest("知识库文档", "kb://topic-1/entry-1")
        agent.registerCitationForTest("网络来源", "https://example.com/valid")

        val content = agent.ensureCitationMarkersForTest(
            "本地资料 [available:1] 网络资料 [citation:1] 无效 [available:9]"
        )

        assertContains(content, "[available:1]")
        assertContains(content, "[citation:1]")
        assertTrue(!content.contains("[available:9]"))
    }

    @Test
    fun `final references only include sources cited by content`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()
        agent.registerCitationForTest("未使用来源", "https://example.com/unused")
        agent.registerCitationForTest("已使用来源", "https://example.com/used")

        val refs = agent.citedReferencesForTest("这里只引用第二个来源。[citation:2]")

        assertEquals(1, refs.size)
        assertEquals(1, refs.first().index)
        assertEquals("https://example.com/used", refs.first().url)
    }

    @Test
    fun `final cited references are renumbered from one`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()
        agent.registerCitationForTest("未使用来源", "https://example.com/unused")
        agent.registerCitationForTest("已使用来源", "https://example.com/used")

        val response = agent.finalizeCitationsForTest("这里只引用第二个来源。[citation:2]")

        // stripCitationMarkers 已移除正文中的 [citation:N] 标记
        assertTrue(!response.content.contains("[citation:1]"))
        assertTrue(!response.content.contains("[citation:2]"))
        assertEquals(1, response.references.size)
        assertEquals(1, response.references.first().index)
        assertEquals("https://example.com/used", response.references.first().url)
    }

    @Test
    fun `missing cited references become placeholders after registered ones`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()
        agent.registerCitationForTest("已注册来源", "https://example.com/used")

        val references = agent.citedReferencesForTest(
            "先引用真实来源[citation:1]，再引用缺失来源[citation:3]。"
        )

        assertEquals(2, references.size)
        assertEquals("https://example.com/used", references.first().url)
        assertEquals("citation", references.last().kind)
        assertEquals(2, references.last().index)
        assertEquals("来源 3", references.last().title)
    }

    @Test
    fun `old chat history entries decode with empty references`() {
        val json = Json { ignoreUnknownKeys = true }
        val entry = json.decodeFromString<ChatHistoryEntry>(
            """
                {
                  "messageId": "m1",
                  "senderId": "u1",
                  "senderName": "用户",
                  "content": "旧消息",
                  "timestamp": 1,
                  "messageType": "TEXT"
                }
            """.trimIndent()
        )

        assertTrue(entry.references.isEmpty())
    }
}
