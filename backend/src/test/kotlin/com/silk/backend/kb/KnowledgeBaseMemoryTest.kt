package com.silk.backend.kb

import com.silk.backend.TestWorkspace
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBEntrySource
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBMemoryMetadata
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

    @Test
    fun `detect tech stack preference from user input`() {
        val captures = detectAutoMemoryCaptures("我们项目主要用 Kotlin 和 Spring Boot，数据库用 PostgreSQL")

        val techStack = captures.firstOrNull { it.key == "tech_stack_preference" }
        assertNotNull(techStack)
        assertEquals(KBMemoryType.PREFERENCE, techStack.type)
        assertTrue(techStack.content.contains("Kotlin"))
        assertTrue(techStack.content.contains("Spring"))
        assertTrue(techStack.content.contains("PostgreSQL"))
    }

    @Test
    fun `detect tech stack preference with single stack`() {
        val captures = detectAutoMemoryCaptures("我现在主要用 React 开发前端")

        val techStack = captures.firstOrNull { it.key == "tech_stack_preference" }
        assertNotNull(techStack)
        assertTrue(techStack.content.contains("React"))
    }

    @Test
    fun `tech stack without usage context is not captured`() {
        val captures = detectAutoMemoryCaptures("Kotlin 是一门很好的语言")

        assertNull(captures.firstOrNull { it.key == "tech_stack_preference" })
    }

    @Test
    fun `detect output format preference`() {
        val captures = detectAutoMemoryCaptures("以后默认用表格形式回答")

        val format = captures.firstOrNull { it.key == "output_format_preference" }
        assertNotNull(format)
        assertEquals(KBMemoryType.PREFERENCE, format.type)
        assertEquals("输出格式偏好：表格", format.content)
    }

    @Test
    fun `detect output format preference for lists`() {
        val captures = detectAutoMemoryCaptures("请用列表形式输出结果")

        val format = captures.firstOrNull { it.key == "output_format_preference" }
        assertNotNull(format)
        assertEquals("输出格式偏好：列表", format.content)
    }

    @Test
    fun `sensitive content is rejected from auto memory`() {
        assertTrue(containsSensitiveContent("我的密码是: mysecret123"))
        assertTrue(containsSensitiveContent("api key: sk-abc123def456ghi789jkl012mno345pqr678stu"))
        assertTrue(containsSensitiveContent("token=ghp_abcdef1234567890abcdef1234567890"))
        assertTrue(detectAutoMemoryCaptures("记住密码：admin123，以后请用中文回答").isEmpty())
    }

    @Test
    fun `normal content is not considered sensitive`() {
        assertFalse(containsSensitiveContent("请用中文回答，代码示例用 Kotlin"))
        assertFalse(containsSensitiveContent("我们项目技术栈是 React + Go"))
        assertFalse(containsSensitiveContent("以后默认用表格形式输出"))
    }

    @Test
    fun `combined auto detection captures multiple categories`() {
        val captures = detectAutoMemoryCaptures(
            "以后请用中文回答，代码用 Kotlin，我们主要用 Spring Boot 开发后端，输出用表格格式"
        )

        val keys = captures.map { it.key }.toSet()
        assertTrue(keys.contains("response_language"))
        assertTrue(keys.contains("code_language_preference"))
        assertTrue(keys.contains("tech_stack_preference"))
        assertTrue(keys.contains("output_format_preference"))
    }

    // ──────────────────────────────────────────────
    // Phase 3: Merge And Conflict Handling
    // ──────────────────────────────────────────────

    @Test
    fun `archiveOldVersion archives content when different`() {
        val entry = makeMemoryEntry("请默认用中文回答", "response_language", explicit = false)
        val archivedMeta = archiveOldVersion(entry, "请默认用英文回答")

        assertNotNull(archivedMeta)
        assertEquals("response_language", archivedMeta.key)
        assertEquals(1, archivedMeta.archivedVersions.size)
        assertEquals("请默认用中文回答", archivedMeta.archivedVersions[0].content)
        assertEquals("被新偏好覆盖", archivedMeta.archivedVersions[0].reason)
    }

    @Test
    fun `archiveOldVersion skips when content same`() {
        val entry = makeMemoryEntry("请默认用中文回答", "response_language", explicit = false)
        val archivedMeta = archiveOldVersion(entry, "请默认用中文回答")

        assertNotNull(archivedMeta)
        assertTrue(archivedMeta.archivedVersions.isEmpty(), "相同内容不应归档")
        assertEquals("response_language", archivedMeta.key)
    }

    @Test
    fun `memoryContentSimilarity high for near duplicates`() {
        val sim = memoryContentSimilarity("请默认用中文回答", "请默认用中文回答问题")
        assertTrue(sim >= 0.6, message = "中高相似度：$sim")
    }

    @Test
    fun `memoryContentSimilarity low for different content`() {
        val sim = memoryContentSimilarity("请默认用中文回答", "技术栈偏好：Kotlin")
        assertTrue(sim < 0.3, message = "低相似度：$sim")
    }

    @Test
    fun `mergeNearDuplicateMemories merges similar entries`() {
        val now = System.currentTimeMillis()
        val entries = mutableListOf(
            makeMemoryEntry("请默认用中文回答", "key1", type = KBMemoryType.PROCEDURAL, createdAt = now),
            makeMemoryEntry("请默认用中文回答问题", "key2", type = KBMemoryType.PROCEDURAL, createdAt = now + 1000),
        )

        val removedIds = mergeNearDuplicateMemories(entries)

        assertEquals(1, removedIds.size, "应该合并 1 对近重复")
        assertEquals(1, entries.size, "合并后只剩 1 条")
        val kept = entries.single()
        assertEquals(1, kept.memory?.archivedVersions?.size, "被合并的条目归档到保留条目中")
    }

    @Test
    fun `mergeNearDuplicateMemories skips different types`() {
        val entries = mutableListOf(
            makeMemoryEntry("请默认用中文回答", "key1", type = KBMemoryType.PROCEDURAL),
            makeMemoryEntry("请默认用中文回答", "key2", type = KBMemoryType.PREFERENCE),
        )

        val removedIds = mergeNearDuplicateMemories(entries)
        assertEquals(0, removedIds.size, "不同类型的记忆不合并")
        assertEquals(2, entries.size)
    }

    @Test
    fun `applyTTLDecay archives old episodic memory`() {
        val veryOld = System.currentTimeMillis() - (EPISODIC_TTL_MS + 86400000L)
        val entries = mutableListOf(
            makeMemoryEntry("旧记忆内容", "old_key", type = KBMemoryType.EPISODIC, createdAt = veryOld),
        )

        val (archivedIds, _) = applyTTLDecay(entries)

        assertEquals(1, archivedIds.size, "过期 EPISODIC 记忆应被归档")
        assertEquals(KBEntryStatus.ARCHIVED, entries.single().status)
    }

    @Test
    fun `applyTTLDecay preserves recent episodic memory`() {
        val now = System.currentTimeMillis()
        val entries = mutableListOf(
            makeMemoryEntry("近期记忆", "recent_key", type = KBMemoryType.EPISODIC, createdAt = now),
        )

        val (archivedIds, _) = applyTTLDecay(entries)

        assertEquals(0, archivedIds.size, "近期记忆不应归档")
        assertEquals(KBEntryStatus.PUBLISHED, entries.single().status)
    }

    @Test
    fun `applyTTLDecay preserves profile memory regardless of age`() {
        val veryOld = System.currentTimeMillis() - (EPISODIC_TTL_MS + 86400000L)
        val entries = mutableListOf(
            makeMemoryEntry("我叫张三", "profile_name", type = KBMemoryType.PROFILE, createdAt = veryOld),
        )

        val (archivedIds, _) = applyTTLDecay(entries)
        assertEquals(0, archivedIds.size, "PROFILE 记忆永不过期")
        assertEquals(KBEntryStatus.PUBLISHED, entries.single().status)
    }

    @Test
    fun `recencyScore gives high score for recent memories`() {
        val now = System.currentTimeMillis()
        val recentMeta = KBMemoryMetadata(type = KBMemoryType.EPISODIC, capturedAt = now, lastAccessedAt = now - 1800000)

        val score = recencyScore(recentMeta, now)
        assertTrue(score >= 6.0, "近几小时的记忆应有高 recency 分：$score")
    }

    @Test
    fun `recencyScore gives low score for old memories`() {
        val now = System.currentTimeMillis()
        val oldMeta = KBMemoryMetadata(
            type = KBMemoryType.EPISODIC,
            capturedAt = now - (EPISODIC_TTL_MS + 86400000L),
            lastAccessedAt = 0L,
        )

        val score = recencyScore(oldMeta, now)
        assertTrue(score < 2.0, "旧记忆的 recency 分应很低：$score")
    }

    @Test
    fun `markMemoryAccessed increments counter and updates timestamp`() {
        val meta = KBMemoryMetadata(type = KBMemoryType.PREFERENCE, capturedAt = 1000L, lastAccessedAt = 1000L, accessedCount = 0)
        val updated = markMemoryAccessed(meta)

        assertEquals(1, updated.accessedCount)
        assertTrue(updated.lastAccessedAt > 1000L)
    }

    @Test
    fun `consolidateMemories combines merge and TTL decay`() {
        val now = System.currentTimeMillis()
        val veryOld = now - (EPISODIC_TTL_MS + 86400000L)
        val entries = mutableListOf(
            makeMemoryEntry("请默认用中文回答", "key1", type = KBMemoryType.PROCEDURAL, createdAt = now),
            makeMemoryEntry("请默认用中文回答问题", "key2", type = KBMemoryType.PROCEDURAL, createdAt = now + 1000),
            makeMemoryEntry("旧的事件记忆", "old_episodic", type = KBMemoryType.EPISODIC, createdAt = veryOld),
        )

        val report = consolidateMemories(entries)

        assertTrue(report.mergedPairs >= 1, "应合并近重复")
        assertTrue(report.expiredRemoved >= 1, "应过期归档")
        assertTrue(report.totalAfter < report.totalBefore, "合并后条目应减少")
    }

    @Test
    fun `consolidateMemoryStore runs on user memory topic`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)

            manager.captureExplicitMemory("owner", "请默认用中文回答", "Procedure: 中文", KBMemoryType.PROCEDURAL)
            manager.captureExplicitMemory("owner", "请默认用中文回答问题", "Procedure: 中文2", KBMemoryType.PROCEDURAL)

            assertEquals(2, manager.listMemoryEntries("owner").size, "合并前应有 2 条")

            val report = manager.consolidateMemoryStore("owner")

            assertTrue(report.mergedPairs >= 1, "应合并近重复记忆")
            assertTrue(report.totalAfter < report.totalBefore, "合并后条目数应减少")
            assertEquals(1, manager.listMemoryEntries("owner").size, "合并后应有 1 条")
        }
    }

    @Test
    fun `searchMemoryEntriesForContext applies recency boost`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)

            manager.captureExplicitMemory("owner", "我叫张三", "Profile: 张三", KBMemoryType.PROFILE)
            manager.captureExplicitMemory("owner", "喜欢 Kotlin 语言", "Preference: Kotlin", KBMemoryType.PREFERENCE)

            val results = manager.searchMemoryEntriesForContext("owner", "张三", limit = 5)
            assertTrue(results.isNotEmpty(), "应匹配到记忆")
            assertTrue(results.any { it.entry.content.contains("张三") }, "应找到用户画像记忆")
        }
    }

    private fun makeMemoryEntry(
        content: String,
        key: String,
        type: KBMemoryType = KBMemoryType.PROCEDURAL,
        explicit: Boolean = false,
        createdAt: Long = System.currentTimeMillis(),
    ): KBEntry {
        val defaultMeta = defaultMemoryMetadata(type, key, explicit = explicit).copy(capturedAt = createdAt)
        return KBEntry(
            id = "test_entry_${(1000..9999).random()}",
            topicId = "test_topic",
            title = "Test: $content",
            content = content,
            ownerId = "test_user",
            status = KBEntryStatus.PUBLISHED,
            source = KBEntrySource(sourceType = KBSourceType.CHAT),
            memory = defaultMeta,
            createdBy = "test_user",
            updatedBy = "test_user",
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }
}
