package com.silk.backend.kb

import com.silk.backend.TestWorkspace
import com.silk.backend.database.GroupRepository
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBMemoryType
import com.silk.backend.models.KnowledgeBaseContextSelection
import com.silk.backend.models.KnowledgeSpaceType
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

        val context = resolveKnowledgeBasePromptContext(
            rawInput = "请根据 [[kb:${entry.id}|知识库引用协议]] 给我总结一下",
            userId = userId,
            knowledgeBaseManager = manager,
        )

        assertEquals("请根据 《知识库引用协议》 给我总结一下", context.resolvedUserInput)
        assertEquals(1, context.availableReferences.size)
        assertEquals("kb://${topic.id}/${entry.id}", context.availableReferences.single().path)
        assertEquals("manual", context.availableReferences.single().origin)
        assertEquals("用户手动引用", context.availableReferences.single().reason)
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

        val context = resolveKnowledgeBasePromptContext(
            rawInput = "看看 [[kb:${entry.id}]]",
            userId = "guest",
            knowledgeBaseManager = manager,
        )

        assertTrue(context.availableReferences.isEmpty())
        assertEquals("看看 《知识库文档 ${entry.id}》", context.resolvedUserInput)
        assertEquals(null, context.promptBlock)
    }

    @Test
    fun `resolver auto injects accessible published entries and keeps manual references first`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val group = assertNotNull(GroupRepository.createGroup("KB Context Team", hostId = "host"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "owner"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "member"))

            val personalTopic = manager.createTopic(name = "个人流程", project = "silk", userId = "member")
            val manualEntry = assertNotNull(
                manager.createEntry(
                    topicId = personalTopic.id,
                    title = "手动引用文档",
                    content = "这份文档由用户手动指定。",
                    tags = listOf("手动"),
                    userId = "member",
                )
            )

            val teamTopic = manager.createTopic(
                name = "Workflow Team",
                project = "workflow",
                userId = "owner",
                spaceType = KnowledgeSpaceType.TEAM,
                groupId = group.id,
            )
            val autoEntry = assertNotNull(
                manager.createEntry(
                    topicId = teamTopic.id,
                    title = "工作流状态持久化",
                    content = "workflow 状态会按 group 保存，并在重连时恢复。",
                    tags = listOf("workflow", "状态"),
                    userId = "owner",
                )
            )
            manager.createEntry(
                topicId = teamTopic.id,
                title = "候选草稿",
                content = "不应自动进入上下文。",
                tags = listOf("workflow"),
                userId = "owner",
                status = KBEntryStatus.CANDIDATE,
            )
            val hiddenTopic = manager.createTopic(name = "隐私", project = "", userId = "owner")
            manager.createEntry(
                topicId = hiddenTopic.id,
                title = "私有工作流说明",
                content = "无权限用户不该看到这条。",
                tags = listOf("workflow"),
                userId = "owner",
            )

            val context = resolveKnowledgeBasePromptContext(
                rawInput = "请结合 [[kb:${manualEntry.id}|手动文档]] 和 workflow 状态持久化给我总结一下",
                userId = "member",
                knowledgeBaseManager = manager,
                preferredGroupId = group.id,
            )

            assertEquals(2, context.availableReferences.size)
            assertEquals("kb://${personalTopic.id}/${manualEntry.id}", context.availableReferences[0].path)
            assertEquals("kb://${teamTopic.id}/${autoEntry.id}", context.availableReferences[1].path)
            assertEquals("manual", context.availableReferences[0].origin)
            assertEquals("auto", context.availableReferences[1].origin)
            assertEquals("用户手动引用", context.availableReferences[0].reason)
            assertTrue(context.availableReferences[1].reason.orEmpty().contains("当前团队空间"))
            assertEquals(1, context.diagnostics.manualReferenceCount)
            assertEquals(1, context.diagnostics.autoCandidateCount)
            assertContains(context.promptBlock.orEmpty(), "### 用户显式引用")
            assertContains(context.promptBlock.orEmpty(), "### 自动补充候选")
            assertContains(context.promptBlock.orEmpty(), "加入原因:")
            assertContains(context.promptBlock.orEmpty(), "当前团队空间")
            assertTrue(context.promptBlock.orEmpty().contains(autoEntry.title))
            assertTrue(!context.promptBlock.orEmpty().contains("候选草稿"))
            assertTrue(!context.promptBlock.orEmpty().contains("私有工作流说明"))
        }
    }

    @Test
    fun `resolver honors pinned and excluded context selections`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val group = assertNotNull(GroupRepository.createGroup("KB Pin Team", hostId = "host"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "owner"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "member"))

            val teamTopic = manager.createTopic(
                name = "Workflow Team",
                project = "workflow",
                userId = "owner",
                spaceType = KnowledgeSpaceType.TEAM,
                groupId = group.id,
            )
            val pinnedEntry = assertNotNull(
                manager.createEntry(
                    topicId = teamTopic.id,
                    title = "固定文档",
                    content = "固定内容",
                    tags = listOf("workflow"),
                    userId = "owner",
                )
            )
            val excludedEntry = assertNotNull(
                manager.createEntry(
                    topicId = teamTopic.id,
                    title = "自动候选文档",
                    content = "工作流自动召回内容",
                    tags = listOf("workflow"),
                    userId = "owner",
                )
            )

            val context = resolveKnowledgeBasePromptContext(
                rawInput = "请总结 workflow 自动召回策略",
                userId = "member",
                knowledgeBaseManager = manager,
                preferredGroupId = group.id,
                selection = KnowledgeBaseContextSelection(
                    pinnedEntryIds = listOf(pinnedEntry.id),
                    excludedEntryIds = listOf(excludedEntry.id),
                ),
            )

            assertEquals(1, context.diagnostics.pinnedReferenceCount)
            assertEquals(1, context.diagnostics.excludedReferenceCount)
            assertEquals(1, context.availableReferences.size)
            assertEquals("pin", context.availableReferences.single().origin)
            assertEquals("kb://${teamTopic.id}/${pinnedEntry.id}", context.availableReferences.single().path)
            assertContains(context.promptBlock.orEmpty(), "### 用户固定上下文")
            assertTrue(!context.promptBlock.orEmpty().contains(excludedEntry.title))
        }
    }

    @Test
    fun `resolver injects related memory entries when memory is enabled`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val saved = manager.captureExplicitMemory(
                userId = "owner",
                content = "我喜欢 Kotlin 和 Ktor",
                title = "Preference: Kotlin stack",
                type = KBMemoryType.PREFERENCE,
            )

            val context = resolveKnowledgeBasePromptContext(
                rawInput = "请按我常用的 Kotlin 技术栈给建议",
                userId = "owner",
                knowledgeBaseManager = manager,
                memoryEnabled = true,
            )

            assertEquals(1, context.diagnostics.memoryReferenceCount)
            assertEquals("memory", context.availableReferences.single().origin)
            assertContains(context.promptBlock.orEmpty(), "### 用户长期记忆")
            assertContains(context.promptBlock.orEmpty(), saved.content)
        }
    }

    @Test
    fun `resolver skips memory entries when memory is disabled`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            manager.captureExplicitMemory(
                userId = "owner",
                content = "请默认用中文回答",
                title = "Procedure: 中文回答",
                type = KBMemoryType.PROCEDURAL,
            )

            val context = resolveKnowledgeBasePromptContext(
                rawInput = "总结一下今天的工作",
                userId = "owner",
                knowledgeBaseManager = manager,
                memoryEnabled = false,
            )

            assertEquals(0, context.diagnostics.memoryReferenceCount)
            assertTrue(context.availableReferences.isEmpty())
            assertNull(context.promptBlock)
        }
    }
}
