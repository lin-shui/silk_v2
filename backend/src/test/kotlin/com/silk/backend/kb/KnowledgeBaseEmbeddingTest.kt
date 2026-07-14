package com.silk.backend.kb

import com.silk.backend.TestWorkspace
import com.silk.backend.models.KBEntryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 测试用嵌入提供者：对"kotlin"查询返回 [1,0,0] 向量（类似 [0,1,0] 的条目匹配度高），
 * 对其他查询返回固定向量用于区分相关性。
 */
internal class TestEmbeddingProvider : EmbeddingProvider {
    /** 按查询文本返回可预测的固定向量。 */
    override suspend fun generateEmbedding(text: String): List<Float> {
        return when {
            text.contains("kotlin", ignoreCase = true) -> listOf(1f, 0f, 0f)
            text.contains("python", ignoreCase = true) -> listOf(0f, 1f, 0f)
            text.contains("javascript", ignoreCase = true) -> listOf(0f, 0f, 1f)
            else -> listOf(1f, 1f, 1f)
        }
    }

    override suspend fun generateEmbeddings(texts: List<String>): List<List<Float>> {
        return texts.map { generateEmbedding(it) }
    }
}

/**
 * 另一个测试嵌入提供者：为每个文本返回与众不同的正交向量，
 * 用于验证混合搜索中向量分确实影响排序。
 */
internal class DistinctEmbeddingProvider : EmbeddingProvider {
    private var counter = 0
    override suspend fun generateEmbedding(text: String): List<Float> {
        counter++
        // 每个文本获得不同方向的单位向量
        return when (counter % 3) {
            0 -> listOf(1f, 0f, 0f)
            1 -> listOf(0f, 1f, 0f)
            2 -> listOf(0f, 0f, 1f)
            else -> listOf(1f, 1f, 1f)
        }
    }

    override suspend fun generateEmbeddings(texts: List<String>): List<List<Float>> {
        return texts.map { generateEmbedding(it) }
    }
}

class KnowledgeBaseEmbeddingTest {

    // ── cosineSimilarity ──

    @Test
    fun `cosine similarity of identical vectors is 1`() {
        val a = listOf(1f, 2f, 3f)
        val b = listOf(1f, 2f, 3f)
        assertEquals(1.0, cosineSimilarity(a, b), 0.0001)
    }

    @Test
    fun `cosine similarity of opposite vectors is 0`() {
        val a = listOf(1f, 0f, 0f)
        val b = listOf(0f, 1f, 0f)
        assertEquals(0.0, cosineSimilarity(a, b), 0.0001)
    }

    @Test
    fun `cosine similarity of empty vectors is 0`() {
        assertEquals(0.0, cosineSimilarity(emptyList(), emptyList()), 0.0001)
        assertEquals(0.0, cosineSimilarity(listOf(1f), emptyList()), 0.0001)
        assertEquals(0.0, cosineSimilarity(emptyList(), listOf(1f)), 0.0001)
    }

    @Test
    fun `cosine similarity of mismatched dimension vectors is 0`() {
        assertEquals(0.0, cosineSimilarity(listOf(1f, 2f), listOf(1f, 2f, 3f)), 0.0001)
    }

    @Test
    fun `cosine similarity returns value in 0 to 1 range`() {
        val a = listOf(1f, 2f, 3f)
        val b = listOf(4f, 5f, 6f)
        val sim = cosineSimilarity(a, b)
        assertTrue(sim in 0.0..1.0, "Cosine similarity should be in [0, 1], got $sim")
    }

    // ── l2Normalize ──

    @Test
    fun `l2 normalize produces unit vectors`() {
        val vectors = listOf(
            listOf(3f, 4f),
            listOf(1f, 2f, 3f),
        )
        val normalized = l2Normalize(vectors)
        assertEquals(2, normalized.size)
        assertEquals(2, normalized[0].size)
        assertEquals(3, normalized[1].size)

        // Check L2 norm ≈ 1.0 for each
        for (vec in normalized) {
            val norm = kotlin.math.sqrt(vec.sumOf { it.toDouble() * it.toDouble() })
            assertEquals(1.0, norm, 0.0001)
        }
    }

    @Test
    fun `l2 normalize handles zero vectors`() {
        val vectors = listOf(listOf(0f, 0f, 0f))
        val normalized = l2Normalize(vectors)
        assertEquals(1, normalized.size)
        // Zero vector should remain zero vector
        normalized[0].forEach { assertEquals(0f, it) }
    }

    @Test
    fun `l2 normalize handles empty input`() {
        val normalized = l2Normalize(emptyList())
        assertTrue(normalized.isEmpty())
    }

    // ── KbEmbeddingCache ──

    @Test
    fun `embedding cache load returns empty store for missing file`() {
        TestWorkspace().use { workspace ->
            val cache = KbEmbeddingCache(workspace.knowledgeBaseDir.absolutePath)
            val store = cache.load()
            assertTrue(store.entries.isEmpty())
            assertEquals(1, store.version)
        }
    }

    @Test
    fun `embedding cache save and load roundtrip`() {
        TestWorkspace().use { workspace ->
            val cache = KbEmbeddingCache(workspace.knowledgeBaseDir.absolutePath)
            val embedding = listOf(0.1f, 0.2f, 0.3f)

            cache.updateEntryEmbedding("entry1", embedding)

            val loaded = cache.load()
            assertEquals(1, loaded.entries.size)
            assertEquals(embedding, loaded.entries["entry1"])
        }
    }

    @Test
    fun `embedding cache batch update`() {
        TestWorkspace().use { workspace ->
            val cache = KbEmbeddingCache(workspace.knowledgeBaseDir.absolutePath)
            val updates = mapOf(
                "entry1" to listOf(1f, 2f),
                "entry2" to listOf(3f, 4f),
            )

            cache.updateBatchEmbeddings(updates)

            val all = cache.getAllEmbeddings()
            assertEquals(2, all.size)
            assertEquals(listOf(1f, 2f), all["entry1"])
            assertEquals(listOf(3f, 4f), all["entry2"])
        }
    }

    @Test
    fun `embedding cache remove entry`() {
        TestWorkspace().use { workspace ->
            val cache = KbEmbeddingCache(workspace.knowledgeBaseDir.absolutePath)
            cache.updateEntryEmbedding("entry1", listOf(1f))
            cache.updateEntryEmbedding("entry2", listOf(2f))

            cache.removeEntryEmbedding("entry1")

            val all = cache.getAllEmbeddings()
            assertEquals(1, all.size)
            assertFalse("entry1" in all)
            assertTrue("entry2" in all)
        }
    }

    @Test
    fun `embedding cache remove non-existent entry does nothing`() {
        TestWorkspace().use { workspace ->
            val cache = KbEmbeddingCache(workspace.knowledgeBaseDir.absolutePath)
            cache.updateEntryEmbedding("entry1", listOf(1f))
            cache.removeEntryEmbedding("non-existent")
            assertEquals(1, cache.getAllEmbeddings().size)
        }
    }

    @Test
    fun `embedding cache persists across instances`() {
        TestWorkspace().use { workspace ->
            val dir = workspace.knowledgeBaseDir.absolutePath
            val cache1 = KbEmbeddingCache(dir)
            cache1.updateEntryEmbedding("persistent", listOf(42f))

            val cache2 = KbEmbeddingCache(dir)
            val all = cache2.getAllEmbeddings()
            assertEquals(1, all.size)
            assertEquals(listOf(42f), all["persistent"])
        }
    }

    // ── NoOpEmbeddingProvider ──

    @Test
    fun `no op embedding provider returns empty lists`() = kotlinx.coroutines.runBlocking {
        val provider = NoOpEmbeddingProvider()
        assertTrue(provider.generateEmbedding("hello").isEmpty())
        assertTrue(provider.generateEmbeddings(listOf("a", "b")).all { it.isEmpty() })
        assertTrue(provider.generateEmbeddings(emptyList()).isEmpty())
    }

    // ── OpenAiCompatibleEmbeddingProvider (construction only, not network) ──

    @Test
    fun `openai compatible provider construction with defaults`() {
        // Should not throw; key is blank so generateEmbedding will throw
        val provider = OpenAiCompatibleEmbeddingProvider(
            apiKey = "",
            apiUrl = "http://localhost:9999/v1/embeddings",
            model = "test-model",
        )
        // Verify provider is created (NoOpEmbeddingProvider would not have apiUrl)
        assertTrue(provider is OpenAiCompatibleEmbeddingProvider)
    }

    @Test
    fun `cosine similarity is symmetric`() {
        val a = listOf(1f, 2f, 3f, 4f, 5f)
        val b = listOf(5f, 4f, 3f, 2f, 1f)
        val simAB = cosineSimilarity(a, b)
        val simBA = cosineSimilarity(b, a)
        assertEquals(simAB, simBA, 0.0001, "Cosine similarity should be symmetric")
    }

    @Test
    fun `cosine similarity of parallel vectors is closer to 1 than 0`() {
        val a = listOf(1f, 2f, 3f)
        val b = listOf(2f, 4f, 6f) // 2× a
        val sim = cosineSimilarity(a, b)
        assertEquals(1.0, sim, 0.0001, "Parallel scaled vectors should have similarity 1")
    }

    // ── 混合搜索集成测试 ──

    @Test
    fun `hybrid search falls back to keyword only when embedding disabled`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(
                baseDir = workspace.knowledgeBaseDir.absolutePath,
                embeddingProvider = NoOpEmbeddingProvider(),
            )
            val topic = manager.createTopic(
                name = "Test",
                project = "test",
                userId = "owner",
            )
            val entry = manager.createEntry(
                topicId = topic.id,
                title = "Kotlin Guide",
                content = "Kotlin is a modern programming language",
                tags = listOf("kotlin", "guide"),
                userId = "owner",
            )
            assertNotNull(entry)

            val results = manager.searchEntriesForContext(
                userId = "owner",
                query = "Kotlin",
                limit = 5,
            )
            assertTrue(results.isNotEmpty(), "Should find the entry by keyword match")
            assertTrue(results.any { it.entry.id == entry.id }, "Should find the Kotlin entry")
        }
    }

    @Test
    fun `search uses cached embedding when available via direct cache`() {
        TestWorkspace().use { workspace ->
            val provider = TestEmbeddingProvider()
            val manager = KnowledgeBaseManager(
                baseDir = workspace.knowledgeBaseDir.absolutePath,
                embeddingProvider = provider,
            )
            val topic = manager.createTopic(name = "Tech", project = "tech", userId = "owner")
            val entry = manager.createEntry(
                topicId = topic.id,
                title = "Kotlin Language",
                content = "Kotlin is great for modern development",
                tags = listOf("kotlin"),
                userId = "owner",
            )
            assertNotNull(entry)

            // Manually set embedding in cache to simulate having it
            manager.embeddingCache.updateEntryEmbedding(entry.id, listOf(1f, 0f, 0f))

            // Search with a term that will match the query embedding provider
            val results = manager.searchEntriesForContext(
                userId = "owner",
                query = "Kotlin",
                limit = 5,
            )
            assertTrue(results.isNotEmpty(), "Should find entry by hybrid search")
            assertTrue(results.any { it.entry.id == entry.id }, "Should find the Kotlin entry")
        }
    }

    @Test
    fun `direct cache remove entry via embeddingCache`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(
                baseDir = workspace.knowledgeBaseDir.absolutePath,
            )
            val topic = manager.createTopic(name = "Test", project = "test", userId = "owner")
            val entry = manager.createEntry(
                topicId = topic.id,
                title = "Temp",
                content = "Temporary entry",
                tags = emptyList(),
                userId = "owner",
            )
            assertNotNull(entry)

            // Simulate existing embedding
            manager.embeddingCache.updateEntryEmbedding(entry.id, listOf(1f, 2f, 3f))
            assertTrue(manager.embeddingCache.getAllEmbeddings().containsKey(entry.id))

            // Directly remove from cache
            manager.embeddingCache.removeEntryEmbedding(entry.id)
            assertFalse(manager.embeddingCache.getAllEmbeddings().containsKey(entry.id))
        }
    }

    @Test
    fun `embedding cache batch update from manager`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(
                baseDir = workspace.knowledgeBaseDir.absolutePath,
            )
            val updates = mapOf(
                "e1" to listOf(0.1f, 0.2f),
                "e2" to listOf(0.3f, 0.4f),
            )
            manager.embeddingCache.updateBatchEmbeddings(updates)
            val all = manager.embeddingCache.getAllEmbeddings()
            assertEquals(2, all.size)
            assertEquals(listOf(0.1f, 0.2f), all["e1"])
            assertEquals(listOf(0.3f, 0.4f), all["e2"])
        }
    }
}
