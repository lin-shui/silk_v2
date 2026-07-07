package com.silk.backend.kb

import com.silk.backend.TestWorkspace
import com.silk.backend.models.KBMemoryType
import com.silk.backend.models.KBSourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeBaseMemoryTest {
    @Test
    fun `detect auto memory captures low risk preferences`() {
        val captures = detectAutoMemoryCaptures("以后请用中文回答，代码示例用 Kotlin，解释尽量简洁一点")

        assertEquals(setOf("response_language", "code_language_preference", "response_style"), captures.map { it.key }.toSet())
        assertEquals(KBMemoryType.PROCEDURAL, captures.first { it.key == "response_language" }.type)
        assertEquals("请默认用中文回答", captures.first { it.key == "response_language" }.content)
        assertEquals("代码示例优先使用 Kotlin", captures.first { it.key == "code_language_preference" }.content)
        assertEquals("回答风格偏好：简洁", captures.first { it.key == "response_style" }.content)
    }

    @Test
    fun `detect auto memory ignores explicit remember commands`() {
        assertTrue(detectAutoMemoryCaptures("记住 以后请用中文回答").isEmpty())
    }

    @Test
    fun `detect explicit memory captures natural chinese remember phrasing`() {
        val capture = detectExplicitMemoryCapture("你要记住我叫张三")

        assertNotNull(capture)
        assertEquals("我叫张三", capture.content)
        assertEquals(KBMemoryType.PROFILE, capture.type)
    }

    @Test
    fun `detect explicit memory captures remember without separator`() {
        val capture = detectExplicitMemoryCapture("记住我喜欢 Kotlin")

        assertNotNull(capture)
        assertEquals("我喜欢 Kotlin", capture.content)
        assertEquals(KBMemoryType.PREFERENCE, capture.type)
    }

    @Test
    fun `auto memory capture upserts by key without overriding explicit memory`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)

            val first = manager.captureAutoMemory(
                userId = "owner",
                content = "请默认用中文回答",
                title = "Procedure: 默认用中文回答",
                type = KBMemoryType.PROCEDURAL,
                key = "response_language",
            )
            assertNotNull(first)
            assertFalse(first.memory?.explicit ?: true)
            assertEquals(KBSourceType.AI_RESPONSE, first.source.sourceType)

            val updated = manager.captureAutoMemory(
                userId = "owner",
                content = "请默认用英文回答",
                title = "Procedure: 默认用英文回答",
                type = KBMemoryType.PROCEDURAL,
                key = "response_language",
            )
            assertNotNull(updated)
            assertEquals(first.id, updated.id)
            assertEquals("请默认用英文回答", updated.content)

            val explicit = manager.captureExplicitMemory(
                userId = "owner",
                content = "请默认用中文回答",
                title = "Procedure: 默认用中文回答",
                type = KBMemoryType.PROCEDURAL,
                key = "response_language",
            )
            assertTrue(explicit.memory?.explicit == true)

            val skipped = manager.captureAutoMemory(
                userId = "owner",
                content = "请默认用英文回答",
                title = "Procedure: 默认用英文回答",
                type = KBMemoryType.PROCEDURAL,
                key = "response_language",
            )
            assertNotNull(skipped)
            assertEquals(explicit.id, skipped.id)
            assertEquals("请默认用中文回答", skipped.content)

            val stored = manager.listMemoryEntries("owner").singleOrNull()
            assertNotNull(stored)
            assertEquals(explicit.id, stored.id)
            assertEquals("请默认用中文回答", stored.content)
            assertTrue(stored.memory?.explicit == true)
        }
    }

    @Test
    fun `response style auto memory requires instruction context`() {
        val capture = detectAutoMemoryCaptures("这个 bug 解释得很详细")
            .firstOrNull { it.key == "response_style" }

        assertNull(capture)
    }
}
