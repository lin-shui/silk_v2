package com.silk.backend.kb

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KnowledgeBaseReferenceResolverTest {
    @Test
    fun `resolver injects referenced knowledge base entries into prompt context`() {
        val manager = KnowledgeBaseManager(baseDir = createTempDirectory("kb-ref-test").resolve("store").toString())
        val userId = "u1"
        val topic = manager.createTopic(name = "架构", project = "silk", userId = userId)
        val entry = manager.createEntry(
            topicId = topic.id,
            title = "引用协议",
            content = "知识库引用格式为 [[kb:id|标题]]，回答时要携带 available 标记。",
            tags = listOf("协议", "知识库"),
            userId = userId,
        )
        assertNotNull(entry)

        val context = KnowledgeBaseReferenceResolver.resolvePromptContext(
            rawInput = "请根据 [[kb:${entry.id}|知识库引用协议]] 给我总结一下",
            userId = userId,
            knowledgeBaseManager = manager,
        )

        assertEquals("请根据 《知识库引用协议》 给我总结一下", context.resolvedUserInput)
        assertEquals(1, context.availableReferences.size)
        assertEquals("kb://${topic.id}/${entry.id}", context.availableReferences.single().path)
        assertContains(context.promptBlock.orEmpty(), "[available:1] ${topic.name} / ${entry.title}")
        assertContains(context.promptBlock.orEmpty(), "知识库引用格式为 [[kb:id|标题]]")
    }

    @Test
    fun `resolver ignores references owned by another user`() {
        val manager = KnowledgeBaseManager(baseDir = createTempDirectory("kb-ref-test").resolve("store").toString())
        val topic = manager.createTopic(name = "私有", project = "", userId = "owner")
        val entry = manager.createEntry(
            topicId = topic.id,
            title = "私有文档",
            content = "only owner can read",
            tags = emptyList(),
            userId = "owner",
        )
        assertNotNull(entry)

        val context = KnowledgeBaseReferenceResolver.resolvePromptContext(
            rawInput = "看看 [[kb:${entry.id}]]",
            userId = "guest",
            knowledgeBaseManager = manager,
        )

        assertTrue(context.availableReferences.isEmpty())
        assertEquals("看看 《知识库文档 ${entry.id}》", context.resolvedUserInput)
        assertEquals(null, context.promptBlock)
    }
}
