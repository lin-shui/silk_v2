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
        assertContains(prompt, "[available:数字]")
        assertContains(prompt, "引用标记必须放在相关内容的句末或段末")
        assertContains(prompt, "禁止堆砌大量引用标记")
    }

    @Test
    fun `brave search results are exposed as citation references`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()

        val result = agent.parseBraveSearchResponseForTest(
            responseBody = """
                {
                  "web": {
                    "results": [
                      {
                        "title": "Silk 引用格式",
                        "description": "用于验证网络搜索来源引用。",
                        "url": "https://example.com/silk-citation"
                      }
                    ]
                  }
                }
            """.trimIndent(),
            query = "Silk citation"
        )

        assertContains(result, "[citation:1]")
        val refs = agent.referencesForTest()
        assertEquals(1, refs.size)
        assertEquals("citation", refs.first().kind)
        assertEquals(1, refs.first().index)
        assertEquals("https://example.com/silk-citation", refs.first().url)
    }

    @Test
    fun `final content gets fallback citation markers when model omits them`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()
        agent.parseBraveSearchResponseForTest(
            responseBody = """
                {
                  "web": {
                    "results": [
                      {
                        "title": "来源标题",
                        "description": "来源摘要",
                        "url": "https://example.com/source"
                      }
                    ]
                  }
                }
            """.trimIndent(),
            query = "source"
        )

        val content = agent.ensureCitationMarkersForTest("这是没有引用标记的回答。")

        assertContains(content, "[citation:1]")
    }

    @Test
    fun `autocli json output exposes first url for clickable references`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        val url = agent.extractFirstUrlFromJsonTextForTest(
            """
                [
                  {
                    "title": "视频标题",
                    "url": "https://www.bilibili.com/video/BV123",
                    "snippet": "摘要"
                  }
                ]
            """.trimIndent()
        )

        assertEquals("https://www.bilibili.com/video/BV123", url)
    }

    @Test
    fun `evidence formatter filters spammy autocli results`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()

        val formatted = agent.formatEvidenceForModelForTest(
            query = "上虞马家埠拆迁",
            sourceLabel = "via AutoCLI",
            rawJson = """
                [
                  {
                    "title": "优化霸屏【TG电报∶@AK5537】 facebook ads",
                    "url": "https://spam.example/ad",
                    "snippet": "TG飞机 代投 开户"
                  },
                  {
                    "title": "白云棠涌旧改142亩安置补偿方案发布",
                    "url": "https://www.bilibili.com/video/BV1YXoTBJE5P",
                    "snippet": "旧改安置补偿方案发布"
                  }
                ]
            """.trimIndent()
        )

        assertTrue(!formatted.contains("@AK5537"))
        assertContains(formatted, "[citation:1]")
        assertEquals("https://www.bilibili.com/video/BV1YXoTBJE5P", agent.referencesForTest().first().url)
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
    fun `final references only include sources cited by content`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()
        agent.registerCitationForTest("未使用来源", "https://example.com/unused")
        agent.registerCitationForTest("已使用来源", "https://example.com/used")

        val refs = agent.citedReferencesForTest("这里只引用第二个来源。[citation:2]")

        assertEquals(1, refs.size)
        assertEquals(2, refs.first().index)
        assertEquals("https://example.com/used", refs.first().url)
    }

    @Test
    fun `final cited references are renumbered from one`() {
        val agent = DirectModelAgent(sessionId = "test_session")
        agent.resetReferencesForTest()
        agent.registerCitationForTest("未使用来源", "https://example.com/unused")
        agent.registerCitationForTest("已使用来源", "https://example.com/used")

        val response = agent.finalizeCitationsForTest("这里只引用第二个来源。[citation:2]")

        assertContains(response.content, "[citation:1]")
        assertTrue(!response.content.contains("[citation:2]"))
        assertEquals(1, response.references.size)
        assertEquals(1, response.references.first().index)
        assertEquals("https://example.com/used", response.references.first().url)
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
