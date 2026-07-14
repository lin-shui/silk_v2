package com.silk.backend.kb

import com.silk.backend.ai.AIConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 嵌入向量缓存文件格式。
 */
@Serializable
data class KbEmbeddingStore(
    val version: Int = 1,
    val model: String = "",
    val entries: Map<String, List<Float>> = emptyMap(),  // entryId → vector
    val updatedAt: Long = 0L,
)

/**
 * 嵌入生成器接口。
 * 支持不同的后端（Voyage AI、OpenAI、本地模型等）。
 */
interface EmbeddingProvider {
    /** 生成单条文本的嵌入向量。 */
    suspend fun generateEmbedding(text: String): List<Float>

    /** 批量生成多条文本的嵌入向量。响应顺序与输入一致。 */
    suspend fun generateEmbeddings(texts: List<String>): List<List<Float>>
}

/**
 * OpenAI 兼容的嵌入 API 客户端。
 * 兼容 Voyage AI、OpenAI text-embedding-*、或其他兼容端点。
 */
class OpenAiCompatibleEmbeddingProvider(
    private val apiKey: String = AIConfig.EMBEDDING_API_KEY,
    private val apiUrl: String = AIConfig.EMBEDDING_API_URL,
    private val model: String = AIConfig.EMBEDDING_MODEL,
) : EmbeddingProvider {

    private val logger = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingProvider::class.java)
    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generateEmbedding(text: String): List<Float> {
        return generateEmbeddings(listOf(text)).first()
    }

    override suspend fun generateEmbeddings(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) return emptyList()
        require(apiKey.isNotBlank()) { "EMBEDDING_API_KEY 未配置。请在 .env 中设置 EMBEDDING_API_KEY" }

        val requestBody = json.encodeToString(
            EmbeddingRequest(
                input = texts.map { it.take(8_000) },  // 截断到 8K 字符
                model = model,
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) {
            "Embedding API 返回 ${response.statusCode()}: ${response.body().take(500)}"
        }

        val parsed = json.decodeFromString<EmbeddingResponse>(response.body())
        require(parsed.data.size == texts.size) {
            "嵌入 API 返回 ${parsed.data.size} 个结果，期望 ${texts.size}"
        }

        return parsed.data
            .sortedBy { it.index }
            .map { it.embedding }
    }

    @Serializable
    private data class EmbeddingRequest(
        val input: List<String>,
        val model: String,
    )

    @Serializable
    private data class EmbeddingResponse(
        val data: List<EmbeddingData>,
        val model: String = "",
        val usage: EmbeddingUsage? = null,
    )

    @Serializable
    private data class EmbeddingData(
        val embedding: List<Float>,
        val index: Int,
    )

    @Serializable
    @Suppress("ConstructorParameterNaming")
    private data class EmbeddingUsage(
        val prompt_tokens: Int = 0,
        val total_tokens: Int = 0,
    )
}

/**
 * 无操作嵌入提供者——当嵌入 API 未配置时使用，返回零向量。
 * 语义搜索将退化为纯关键词匹配。
 */
class NoOpEmbeddingProvider : EmbeddingProvider {
    override suspend fun generateEmbedding(text: String): List<Float> = emptyList()
    override suspend fun generateEmbeddings(texts: List<String>): List<List<Float>> =
        texts.map { emptyList() }
}

// ── 余弦相似度工具 ──────────────────────────────────────────────────

/**
 * 计算两个向量间的余弦相似度。
 * @return [0, 1] 范围内的相似度，0=无关，1=完全相同。任一向量为空时返回 0。
 */
fun cosineSimilarity(a: List<Float>, b: List<Float>): Double {
    if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0
    var dotProduct = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dotProduct += a[i].toDouble() * b[i].toDouble()
        normA += a[i].toDouble() * a[i].toDouble()
        normB += b[i].toDouble() * b[i].toDouble()
    }
    val denom = sqrt(normA) * sqrt(normB)
    return if (denom == 0.0) 0.0 else (dotProduct / denom).coerceIn(0.0, 1.0)
}

/**
 * 对一批向量执行 L2 归一化。
 */
internal fun l2Normalize(vectors: List<List<Float>>): List<List<Float>> {
    return vectors.map { vec ->
        val norm = sqrt(vec.sumOf { it.toDouble() * it.toDouble() })
        if (norm == 0.0) vec else vec.map { (it.toDouble() / norm).toFloat() }
    }
}

/**
 * 管理 KB 条目的嵌入缓存。
 * 嵌入存储在 `{baseDir}/kb_embeddings.json` 侧边文件。
 */
class KbEmbeddingCache(private val baseDir: String) {

    private val logger = LoggerFactory.getLogger(KbEmbeddingCache::class.java)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val cacheFile get() = File("$baseDir/kb_embeddings.json")

    @Synchronized
    fun load(): KbEmbeddingStore {
        if (!cacheFile.exists()) return KbEmbeddingStore()
        return try {
            json.decodeFromString(cacheFile.readText())
        } catch (e: java.io.IOException) {
            logger.error("Failed to load embedding cache: {}", e.message)
            KbEmbeddingStore()
        } catch (e: kotlinx.serialization.SerializationException) {
            logger.error("Failed to deserialize embedding cache: {}", e.message)
            KbEmbeddingStore()
        }
    }

    @Synchronized
    fun save(store: KbEmbeddingStore) {
        File(baseDir).mkdirs()
        val tmp = File("${cacheFile.path}.tmp")
        tmp.writeText(json.encodeToString(store))
        tmp.renameTo(cacheFile)
    }

    /**
     * 更新单条 entry 的嵌入。
     */
    @Synchronized
    fun updateEntryEmbedding(entryId: String, embedding: List<Float>) {
        val store = load()
        val updated = store.copy(
            entries = store.entries + (entryId to embedding),
            updatedAt = System.currentTimeMillis(),
        )
        save(updated)
    }

    /**
     * 批量更新多条 entry 的嵌入。
     */
    @Synchronized
    fun updateBatchEmbeddings(updates: Map<String, List<Float>>) {
        if (updates.isEmpty()) return
        val store = load()
        val updated = store.copy(
            entries = store.entries + updates,
            updatedAt = System.currentTimeMillis(),
        )
        save(updated)
    }

    /**
     * 移除已删除条目的嵌入。
     */
    @Synchronized
    fun removeEntryEmbedding(entryId: String) {
        val store = load()
        if (entryId !in store.entries) return
        val updated = store.copy(
            entries = store.entries - entryId,
            updatedAt = System.currentTimeMillis(),
        )
        save(updated)
    }

    /**
     * 获取所有已缓存的嵌入。
     */
    @Synchronized
    fun getAllEmbeddings(): Map<String, List<Float>> = load().entries
}
