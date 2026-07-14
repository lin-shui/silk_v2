package com.silk.backend.kb

import com.silk.backend.TestWorkspace
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBMemoryType
import com.silk.backend.models.KBSourceType
import com.silk.backend.models.KnowledgeBaseContextSelection
import com.silk.backend.models.KnowledgeSpaceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeBasePromptContextTest {
    private val owner = "owner"

    @Test
    fun `manual references take priority over all other reference types`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "工程实践", project = "silk", userId = owner)
            val entry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id, title = "Kotlin 编码规范", content = "使用 Kotlin 编码规范",
                    tags = listOf("kotlin"), userId = owner,
                )
            )
            // 创建一个 auto 候选也会匹配的内容
            manager.createEntry(
                topicId = topic.id, title = "Kotlin 最佳实践", content = "Kotlin 开发的最佳实践",
                tags = listOf("kotlin"), userId = owner,
            )

            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "请根据 [[kb:${entry.id}|编码规范]] 回答 Kotlin 相关问题",
                userId = owner,
                knowledgeBaseManager = manager,
                autoCandidateLimit = 5,
            )

            assertNotNull(ctx.promptBlock, "有引用时应生成 prompt block")
            assertTrue(ctx.promptBlock!!.contains("用户显式引用"), "manual 引用应出现在 prompt 首段")
            assertTrue(ctx.promptBlock!!.contains("编码规范"), "manual 引用的 label 应在 prompt 中")
            assertEquals(1, ctx.diagnostics.manualReferenceCount, "应有 1 条 manual 引用")
            assertTrue(ctx.diagnostics.autoCandidateCount >= 1, "auto 候选不应被 manual 抑制")
        }
    }

    @Test
    fun `pinned references appear after manual but before auto references`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "项目规范", project = "silk", userId = owner)
            val pinnedEntry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id, title = "Git 工作流", content = "团队 Git 工作流规范",
                    tags = listOf("git"), userId = owner,
                )
            )
            // 创建一个 auto 候选项（查询词需要能匹配到内容）
            manager.createEntry(
                topicId = topic.id, title = "Go 编码规范", content = "Go 语言的编码约定",
                tags = listOf("go"), userId = owner,
            )

            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "编码约定",
                userId = owner,
                knowledgeBaseManager = manager,
                autoCandidateLimit = 5,
                selection = KnowledgeBaseContextSelection(
                    pinnedEntryIds = listOf(pinnedEntry.id),
                ),
            )

            assertNotNull(ctx.promptBlock)
            val promptText = ctx.promptBlock!!
            val pinnedIdx = promptText.indexOf("用户固定上下文")
            val autoIdx = promptText.indexOf("自动补充候选")
            assertTrue(pinnedIdx >= 0, "应有固定上下文段")
            assertTrue(autoIdx >= 0, "应有自动补充候选段")
            assertTrue(pinnedIdx < autoIdx, "固定上下文应在自动补充之前")
            assertTrue(promptText.contains("Git 工作流"), "固定条目应展示")
            assertEquals(1, ctx.diagnostics.pinnedReferenceCount)
        }
    }

    @Test
    fun `auto references appear after pinned but before memory references`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "技术文档", project = "silk", userId = owner)
            manager.createEntry(
                topicId = topic.id, title = "Spring Boot 指南", content = "Spring Boot 开发入门",
                tags = listOf("spring"), userId = owner,
            )
            // 创建一条匹配的 memory
            manager.captureExplicitMemory(
                userId = owner, content = "喜欢用 Spring Boot 开发", title = "Preference: Spring Boot",
                type = KBMemoryType.PREFERENCE,
            )

            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "Spring Boot",
                userId = owner,
                knowledgeBaseManager = manager,
                autoCandidateLimit = 5,
                memoryCandidateLimit = 5,
            )

            assertNotNull(ctx.promptBlock)
            val promptText = ctx.promptBlock!!
            val autoIdx = promptText.indexOf("自动补充候选")
            val memoryIdx = promptText.indexOf("用户长期记忆")
            assertTrue(autoIdx >= 0, "应有自动补充候选段")
            assertTrue(memoryIdx >= 0, "应有记忆段")
            assertTrue(autoIdx < memoryIdx, "自动补充应在记忆之前")
            assertTrue(ctx.diagnostics.autoCandidateCount >= 1, "应有至少 1 条自动候选")
            assertTrue(ctx.diagnostics.memoryReferenceCount >= 1, "应有至少 1 条记忆")
        }
    }

    @Test
    fun `memory is excluded when memoryEnabled is false`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            manager.captureExplicitMemory(
                userId = owner, content = "喜欢 Kotlin 语言", title = "Preference: Kotlin",
                type = KBMemoryType.PREFERENCE,
            )

            val ctxEnabled = resolveKnowledgeBasePromptContext(
                rawInput = "Kotlin", userId = owner,
                knowledgeBaseManager = manager,
                memoryEnabled = true, memoryCandidateLimit = 5,
            )
            assertTrue(ctxEnabled.diagnostics.memoryReferenceCount >= 1, "memoryEnabled=true 时应返回记忆")

            val ctxDisabled = resolveKnowledgeBasePromptContext(
                rawInput = "Kotlin", userId = owner,
                knowledgeBaseManager = manager,
                memoryEnabled = false, memoryCandidateLimit = 5,
            )
            assertEquals(0, ctxDisabled.diagnostics.memoryReferenceCount, "memoryEnabled=false 时应排除记忆")
            assertNull(ctxDisabled.promptBlock, "没有引用时不应生成 prompt block")
        }
    }

    @Test
    fun `excluded entries are filtered from auto references`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "通用", project = "silk", userId = owner)
            val excludedEntry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id, title = "排除项", content = "这条内容不应该出现在上下文中",
                    tags = emptyList(), userId = owner,
                )
            )
            manager.createEntry(
                topicId = topic.id, title = "保留项", content = "保留的内容",
                tags = emptyList(), userId = owner,
            )

            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "内容",
                userId = owner,
                knowledgeBaseManager = manager,
                autoCandidateLimit = 5,
                selection = KnowledgeBaseContextSelection(
                    excludedEntryIds = listOf(excludedEntry.id),
                ),
            )

            assertNotNull(ctx.promptBlock)
            assertTrue(ctx.promptBlock!!.contains("保留项"), "保留项应出现在自动候选")
            assertTrue(!ctx.promptBlock!!.contains("排除项"), "排除项不应出现在自动候选")
            assertEquals(1, ctx.diagnostics.excludedReferenceCount, "应有 1 条被排除")
        }
    }

    @Test
    fun `manual reference resolves to correct label and replaces inline token`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "测试", project = "silk", userId = owner)
            val entry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id, title = "测试指南", content = "这是测试指南的内容",
                    tags = emptyList(), userId = owner,
                )
            )

            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "请参照 [[kb:${entry.id}|测试指南]] 执行",
                userId = owner,
                knowledgeBaseManager = manager,
            )

            assertEquals("请参照 《测试指南》 执行", ctx.resolvedUserInput, "manual 引用应替换为《label》格式")
            assertEquals(1, ctx.diagnostics.manualReferenceCount)
            assertNotNull(ctx.promptBlock)
            assertTrue(ctx.promptBlock!!.contains("测试指南"))
        }
    }

    @Test
    fun `manual reference without label falls back to entry title`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "测试", project = "silk", userId = owner)
            val entry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id, title = "无标签条目", content = "没有显式标签的引用",
                    tags = emptyList(), userId = owner,
                )
            )

            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "参考 [[kb:${entry.id}]]",
                userId = owner,
                knowledgeBaseManager = manager,
            )

            assertEquals("参考 《无标签条目》", ctx.resolvedUserInput)
        }
    }

    @Test
    fun `pinned entries are not duplicated in auto references`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "技术", project = "silk", userId = owner)
            val pinned = assertNotNull(
                manager.createEntry(
                    topicId = topic.id, title = "Kotlin 入门", content = "Kotlin 入门指南",
                    tags = listOf("kotlin"), userId = owner,
                )
            )

            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "Kotlin",
                userId = owner,
                knowledgeBaseManager = manager,
                autoCandidateLimit = 5,
                selection = KnowledgeBaseContextSelection(
                    pinnedEntryIds = listOf(pinned.id),
                ),
            )

            assertEquals(1, ctx.diagnostics.pinnedReferenceCount, "pin 应有 1 条")
            assertEquals(0, ctx.diagnostics.autoCandidateCount, "pin 过的条目不应再出现在 auto 中")
        }
    }

    @Test
    fun `group memory is included when preferredGroupId is provided`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            // hostId 自动成为群组成员，不需要重复 addUserToGroup
            val group = com.silk.backend.database.GroupRepository.createGroup("Prompt Context Group", hostId = owner)
            assertNotNull(group)

            // 创建个人记忆
            manager.captureExplicitMemory(
                userId = owner, content = "用户偏好使用中文", title = "语言偏好",
                type = KBMemoryType.PREFERENCE,
            )
            // 创建群组记忆
            manager.captureExplicitGroupMemory(
                userId = owner, groupId = group.id,
                content = "团队技术栈偏好",
                title = "团队技术",
                type = KBMemoryType.PREFERENCE,
                key = "group_tech_stack",
            )

            val ctxWithGroup = resolveKnowledgeBasePromptContext(
                rawInput = "偏好", userId = owner,
                knowledgeBaseManager = manager,
                preferredGroupId = group.id,
                memoryCandidateLimit = 5,
            )
            assertTrue(ctxWithGroup.diagnostics.memoryReferenceCount >= 1, "有 groupId 时应返回记忆")

            val ctxWithoutGroup = resolveKnowledgeBasePromptContext(
                rawInput = "偏好", userId = owner,
                knowledgeBaseManager = manager,
                preferredGroupId = null,
                memoryCandidateLimit = 5,
            )
            assertTrue(ctxWithoutGroup.diagnostics.memoryReferenceCount >= 1, "无 groupId 也应返回个人记忆")
        }
    }

    @Test
    fun `memory prompt header emphasizes current input priority`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            manager.captureExplicitMemory(
                userId = owner, content = "用户偏好使用中文", title = "语言偏好",
                type = KBMemoryType.PREFERENCE,
            )

            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "偏好", userId = owner,
                knowledgeBaseManager = manager,
                memoryCandidateLimit = 5,
            )

            assertNotNull(ctx.promptBlock)
            assertTrue(ctx.promptBlock!!.contains("必须以本轮要求为准"), "memory prompt 应包含'当前输入优先'指示")
        }
    }

    @Test
    fun `no references returns null promptBlock`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)

            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "你好", userId = owner,
                knowledgeBaseManager = manager,
            )

            assertNull(ctx.promptBlock, "没有引用时 promptBlock 应为 null")
            assertEquals("你好", ctx.resolvedUserInput, "原始输入不变")
            assertEquals(0, ctx.diagnostics.manualReferenceCount)
            assertEquals(0, ctx.diagnostics.pinnedReferenceCount)
            assertEquals(0, ctx.diagnostics.autoCandidateCount)
            assertEquals(0, ctx.diagnostics.memoryReferenceCount)
        }
    }

    @Test
    fun `excluded space ids filter auto references`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            // hostId 自动成为群组成员
            val group = com.silk.backend.database.GroupRepository.createGroup("Space Filter Group", hostId = owner)
            assertNotNull(group)

            // 团队空间 - 标题和内容包含"编码规范"
            val teamTopic = manager.createTopic(
                name = "团队文档", project = "silk", userId = owner,
                spaceType = KnowledgeSpaceType.TEAM, groupId = group.id,
            )
            manager.createEntry(
                topicId = teamTopic.id, title = "团队规范", content = "团队的编码规范",
                tags = emptyList(), userId = owner,
            )
            // 个人空间 - 标题不含"规范"但内容匹配，用于验证空间排除
            val personalTopic = manager.createTopic(
                name = "个人文档", project = "silk", userId = owner,
                spaceType = KnowledgeSpaceType.PERSONAL,
            )
            manager.createEntry(
                topicId = personalTopic.id, title = "个人笔记", content = "个人学习笔记规范",
                tags = emptyList(), userId = owner,
            )

            // 先确认不排除时两条都能搜到
            val allCtx = resolveKnowledgeBasePromptContext(
                rawInput = "规范", userId = owner,
                knowledgeBaseManager = manager,
                autoCandidateLimit = 5,
            )
            assertNotNull(allCtx.promptBlock)
            assertTrue(allCtx.promptBlock!!.contains("团队规范"), "不排除空间时团队条目应出现")

            // 排除个人空间
            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "规范", userId = owner,
                knowledgeBaseManager = manager,
                autoCandidateLimit = 5,
                selection = KnowledgeBaseContextSelection(
                    excludedSpaceIds = listOf(PERSONAL_KB_SPACE_ID),
                ),
            )

            assertNotNull(ctx.promptBlock)
            assertTrue(ctx.promptBlock!!.contains("团队规范"), "团队空间条目不应被排除")
            assertEquals(1, ctx.diagnostics.excludedSpaceCount, "应有 1 个空间被排除")
        }
    }

    @Test
    fun `diagnostics counts all reference categories correctly`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "综合", project = "silk", userId = owner)
            val manualEntry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id, title = "手动引用", content = "手动引用的内容",
                    tags = emptyList(), userId = owner,
                )
            )
            val pinnedEntry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id, title = "固定条目", content = "固定的内容",
                    tags = emptyList(), userId = owner,
                )
            )
            // 创建一条 auto 候选
            manager.createEntry(
                topicId = topic.id, title = "自动候选", content = "自动匹配的内容 Kotlin",
                tags = listOf("kotlin"), userId = owner,
            )
            manager.captureExplicitMemory(
                userId = owner, content = "喜欢 Kotlin", title = "偏好 Kotlin",
                type = KBMemoryType.PREFERENCE,
            )

            val excludedEntry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id, title = "排除项", content = "排除项",
                    tags = emptyList(), userId = owner,
                )
            )

            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "根据 [[kb:${manualEntry.id}|手动]] 和固定内容回答 Kotlin 问题",
                userId = owner,
                knowledgeBaseManager = manager,
                autoCandidateLimit = 5,
                memoryCandidateLimit = 5,
                selection = KnowledgeBaseContextSelection(
                    pinnedEntryIds = listOf(pinnedEntry.id),
                    excludedEntryIds = listOf(excludedEntry.id),
                ),
            )

            assertEquals(1, ctx.diagnostics.manualReferenceCount)
            assertEquals(1, ctx.diagnostics.pinnedReferenceCount)
            assertEquals(1, ctx.diagnostics.excludedReferenceCount)
            assertTrue(ctx.diagnostics.autoCandidateCount >= 1, "应有自动候选")
            assertTrue(ctx.diagnostics.memoryReferenceCount >= 1, "应有记忆引用")
        }
    }

    @Test
    fun `user input with only inaccessible references returns fallback label and no prompt block`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "私有", project = "silk", userId = "other")
            val entry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id, title = "私有条目", content = "仅 other 可读",
                    tags = emptyList(), userId = "other",
                )
            )

            val ctx = resolveKnowledgeBasePromptContext(
                rawInput = "参考 [[kb:${entry.id}]]",
                userId = owner,
                knowledgeBaseManager = manager,
            )

            assertTrue(ctx.resolvedUserInput.contains("知识库文档"), "无权限时 label 回退到通用格式")
            assertNull(ctx.promptBlock, "无权限的 manual 引用不应生成 prompt block")
        }
    }
}
