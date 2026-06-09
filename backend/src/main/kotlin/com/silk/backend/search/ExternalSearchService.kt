package com.silk.backend.search

import com.silk.backend.ai.AIConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * 外部搜索引擎服务
 * 支持多种搜索引擎 API：
 * 1. Bing Search API (国内可访问，推荐)
 * 2. SerpAPI (Google 结果，需付费)
 * 3. DuckDuckGo (免费但国内可能不通)
 */
class ExternalSearchService {
    private val logger = LoggerFactory.getLogger(ExternalSearchService::class.java)
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        engine {
            requestTimeout = 30000
            // 优化连接配置以提升稳定性
            endpoint {
                connectTimeout = 15000
                keepAliveTime = 10000
                socketTimeout = 30000
            }
        }
    }
    
    companion object {
        // 搜索 API Key 统一从 AIConfig 读取（AIConfig 从 .env 加载）
        private val BING_API_KEY: String get() = AIConfig.BING_API_KEY
        private val SERPAPI_KEY: String get() = AIConfig.SERPAPI_KEY
        private val SEARXNG_URL: String get() = AIConfig.SEARXNG_URL
        const val BING_SEARCH_URL: String = "https://api.bing.microsoft.com/v7.0/search"
        const val SERPAPI_URL: String = "https://serpapi.com/search"
        
        // Wikipedia API（完全免费，国内可访问）
        const val WIKIPEDIA_API_URL: String = "https://zh.wikipedia.org/w/api.php"
        const val WIKIPEDIA_EN_API_URL: String = "https://en.wikipedia.org/w/api.php"
        
        // DuckDuckGo Instant Answer API（无需 API Key，但国内可能不通）
        const val DUCKDUCKGO_URL: String = "https://api.duckduckgo.com/"
        
        // 搜索超时 - SearXNG 聚合多个引擎可能需要更长时间
        const val SEARCH_TIMEOUT_MS = 15000L  // 15秒超时，SearXNG 聚合多引擎需等待
        const val MAX_RESULTS = 5
    }

    private data class SearchAttempt(
        val startLog: String,
        val successLabel: String,
        val failureLabel: String,
        val isEnabled: () -> Boolean = { true },
        val search: suspend (String, Int) -> ExternalSearchResults,
    )
    
    /**
     * 执行外部搜索
     * 优先级（SearXNG 优先）：
     * 1. SearXNG (自托管，最高优先级)
     * 2. SerpAPI (Google 结果)
     * 3. Bing (需API Key，国内可访问)
     * 4. Wikipedia (免费，国内稳定)
     * 5. DuckDuckGo (免费，可能超时)
     */
    suspend fun search(query: String, limit: Int = MAX_RESULTS): ExternalSearchResults {
        val startTime = System.currentTimeMillis()
        return recoverSearchFailure(
            onFailure = { error ->
                logger.error("❌ 外部搜索异常: ${error.message}")
                ExternalSearchResults(
                    success = false,
                    source = "error",
                    results = emptyList(),
                    searchTimeMs = System.currentTimeMillis() - startTime,
                    error = error.message,
                )
            }
        ) {
            var successfulResult: ExternalSearchResults? = null
            for (attempt in buildSearchAttempts()) {
                successfulResult = runSearchAttempt(attempt, query, limit, startTime)
                if (successfulResult != null) break
            }
            successfulResult?.let { return@recoverSearchFailure it }

            logAllSearchesFailed(startTime)
            ExternalSearchResults(
                success = false,
                source = "none",
                results = emptyList(),
                searchTimeMs = System.currentTimeMillis() - startTime,
                error = "所有搜索引擎都无法获取结果"
            )
        }
    }

    private fun buildSearchAttempts(): List<SearchAttempt> = listOf(
        SearchAttempt(
            startLog = "🔍 [1/5] 尝试 SearXNG (自托管) - 最高优先级",
            successLabel = "SearXNG",
            failureLabel = "SearXNG",
            isEnabled = { SEARXNG_URL.isNotBlank() },
            search = ::searchWithSearXNG,
        ),
        SearchAttempt(
            startLog = "🔍 [2/5] 尝试 SerpAPI (Google 搜索)",
            successLabel = "SerpAPI",
            failureLabel = "SerpAPI",
            isEnabled = { SERPAPI_KEY.isNotBlank() },
            search = ::searchWithSerpAPI,
        ),
        SearchAttempt(
            startLog = "🔍 [3/5] 尝试 Bing Search API",
            successLabel = "Bing",
            failureLabel = "Bing",
            isEnabled = { BING_API_KEY.isNotBlank() },
            search = ::searchWithBing,
        ),
        SearchAttempt(
            startLog = "🔍 [4/5] 尝试 Wikipedia API（免费，稳定）",
            successLabel = "Wikipedia",
            failureLabel = "Wikipedia",
            search = ::searchWithWikipedia,
        ),
        SearchAttempt(
            startLog = "🔍 [5/5] 尝试 DuckDuckGo API（免费）",
            successLabel = "DuckDuckGo",
            failureLabel = "DuckDuckGo",
            search = ::searchWithDuckDuckGo,
        ),
    )

    private suspend fun runSearchAttempt(
        attempt: SearchAttempt,
        query: String,
        limit: Int,
        startTime: Long,
    ): ExternalSearchResults? {
        if (!attempt.isEnabled()) return null

        logger.info(attempt.startLog)
        return recoverSearchFailure(
            onFailure = { error ->
                logger.warn("⚠️ {} 失败: {}", attempt.failureLabel, error.message?.take(50))
                null
            }
        ) {
            val result = attempt.search(query, limit)
            if (result.success && result.results.isNotEmpty()) {
                logger.info("✅ {} 搜索成功 ({}ms)", attempt.successLabel, System.currentTimeMillis() - startTime)
                result
            } else {
                null
            }
        }
    }

    private fun logAllSearchesFailed(startTime: Long) {
        logger.warn("❌ 所有外部搜索引擎都无法获取结果 (总耗时: {}ms)", System.currentTimeMillis() - startTime)
    }

    private suspend fun <T> recoverSearchFailure(
        onFailure: suspend (Throwable) -> T,
        block: suspend () -> T,
    ): T {
        return runCatching { block() }.getOrElse { error ->
            if (error is CancellationException) throw error
            onFailure(error)
        }
    }
    
    /**
     * 使用 Wikipedia API 搜索（完全免费，国内可访问）
     * 优先搜索中文维基百科，如果没结果再搜索英文
     */
    private suspend fun searchWithWikipedia(query: String, limit: Int): ExternalSearchResults {
        val startTime = System.currentTimeMillis()
        
        return withTimeout(SEARCH_TIMEOUT_MS) {
            recoverSearchFailure(
                onFailure = { error ->
                    logger.error("❌ [Wikipedia] 搜索失败: ${error.message}")
                    ExternalSearchResults(
                        success = false,
                        source = "Wikipedia",
                        results = emptyList(),
                        searchTimeMs = System.currentTimeMillis() - startTime,
                        error = error.message,
                    )
                }
            ) {
                // 先尝试中文维基百科
                logger.info("🔍 [Wikipedia] 搜索中文维基: $query")
                var results = searchWikipediaLanguage(query, WIKIPEDIA_API_URL, "zh", limit)
                
                // 如果中文没结果，尝试英文
                if (results.isEmpty()) {
                    logger.info("🔍 [Wikipedia] 中文无结果，尝试英文维基")
                    results = searchWikipediaLanguage(query, WIKIPEDIA_EN_API_URL, "en", limit)
                }
                
                if (results.isNotEmpty()) {
                    logger.info("📚 [Wikipedia] 搜索成功: 找到 ${results.size} 条结果")
                }
                
                ExternalSearchResults(
                    success = results.isNotEmpty(),
                    source = "Wikipedia",
                    results = results,
                    searchTimeMs = System.currentTimeMillis() - startTime,
                )
            }
        }
    }
    
    /**
     * 搜索指定语言的维基百科
     */
    private suspend fun searchWikipediaLanguage(
        query: String, 
        apiUrl: String, 
        lang: String,
        limit: Int
    ): List<ExternalSearchResult> {
        // 第一步：搜索相关页面
        val searchResponse = client.get(apiUrl) {
            parameter("action", "query")
            parameter("list", "search")
            parameter("srsearch", query)
            parameter("srlimit", limit)
            parameter("format", "json")
            parameter("utf8", "1")
        }
        
        val searchBody = searchResponse.bodyAsText()
        val searchJson = json.parseToJsonElement(searchBody).jsonObject
        
        val searchResults = searchJson["query"]?.jsonObject
            ?.get("search")?.jsonArray ?: return emptyList()
        
        if (searchResults.isEmpty()) return emptyList()
        
        // 第二步：获取页面摘要
        val titles = searchResults.take(limit).mapNotNull { 
            it.jsonObject["title"]?.jsonPrimitive?.content 
        }
        
        if (titles.isEmpty()) return emptyList()
        
        val summaryResponse = client.get(apiUrl) {
            parameter("action", "query")
            parameter("titles", titles.joinToString("|"))
            parameter("prop", "extracts|info")
            parameter("exintro", "1")
            parameter("explaintext", "1")
            parameter("exsentences", "3")
            parameter("inprop", "url")
            parameter("format", "json")
            parameter("utf8", "1")
        }
        
        val summaryBody = summaryResponse.bodyAsText()
        val summaryJson = json.parseToJsonElement(summaryBody).jsonObject
        
        val pages = summaryJson["query"]?.jsonObject
            ?.get("pages")?.jsonObject ?: return emptyList()
        
        return pages.entries.mapNotNull { (_, pageValue) ->
            val page = pageValue.jsonObject
            val title = page["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val extract = page["extract"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val url = page["fullurl"]?.jsonPrimitive?.content 
                ?: "https://${lang}.wikipedia.org/wiki/${title.replace(" ", "_")}"
            
            if (extract.isBlank()) return@mapNotNull null
            
            ExternalSearchResult(
                title = title,
                snippet = extract.take(500),
                url = url,
                source = "Wikipedia ($lang)"
            )
        }
    }
    
    /**
     * 使用 Bing Search API（国内可访问，推荐）
     * 需要 Azure 订阅和 Bing Search API Key
     */
    private suspend fun searchWithBing(query: String, limit: Int): ExternalSearchResults {
        val startTime = System.currentTimeMillis()
        
        return withTimeout(SEARCH_TIMEOUT_MS) {
            recoverSearchFailure(
                onFailure = { error ->
                    logger.error("❌ [Bing] 搜索失败: ${error.message}")
                    ExternalSearchResults(
                        success = false,
                        source = "Bing",
                        results = emptyList(),
                        searchTimeMs = System.currentTimeMillis() - startTime,
                        error = error.message,
                    )
                }
            ) {
                logger.info("🔍 [Bing] 搜索: $query")
                
                val response = client.get(BING_SEARCH_URL) {
                    parameter("q", query)
                    parameter("count", limit)
                    parameter("mkt", "zh-CN")  // 中文市场
                    parameter("setLang", "zh-Hans")
                    header("Ocp-Apim-Subscription-Key", BING_API_KEY)
                }
                
                val body = response.bodyAsText()
                logger.info("🔍 [Bing] 响应: ${body.take(500)}...")
                
                val bingResult = json.decodeFromString<BingSearchResponse>(body)
                
                val results = bingResult.webPages?.value?.take(limit)?.map { item ->
                    ExternalSearchResult(
                        title = item.name ?: "无标题",
                        snippet = item.snippet ?: "",
                        url = item.url ?: "",
                        source = "Bing"
                    )
                } ?: emptyList()
                
                logger.info("🔍 [Bing] 搜索成功: 找到 ${results.size} 条结果")
                
                ExternalSearchResults(
                    success = results.isNotEmpty(),
                    source = "Bing",
                    results = results,
                    searchTimeMs = System.currentTimeMillis() - startTime,
                )
            }
        }
    }
    
    /**
     * 使用 SerpAPI 搜索（Google 结果）
     */
    private suspend fun searchWithSerpAPI(query: String, limit: Int): ExternalSearchResults {
        val startTime = System.currentTimeMillis()
        
        return withTimeout(SEARCH_TIMEOUT_MS) {
            recoverSearchFailure(
                onFailure = { error ->
                    logger.warn("⚠️ SerpAPI 搜索失败，尝试 DuckDuckGo: ${error.message}")
                    searchWithDuckDuckGo(query, limit)
                }
            ) {
                val response = client.get(SERPAPI_URL) {
                    parameter("q", query)
                    parameter("api_key", SERPAPI_KEY)
                    parameter("engine", "google")
                    parameter("num", limit)
                    parameter("hl", "zh-CN")  // 中文结果
                }
                
                val body = response.bodyAsText()
                val serpResult = json.decodeFromString<SerpAPIResponse>(body)
                
                val results = serpResult.organicResults?.take(limit)?.map { item ->
                    ExternalSearchResult(
                        title = item.title ?: "无标题",
                        snippet = item.snippet ?: "",
                        url = item.link ?: "",
                        source = "Google (via SerpAPI)"
                    )
                } ?: emptyList()
                
                logger.info("🌐 SerpAPI 搜索成功: 找到 ${results.size} 条结果")
                
                ExternalSearchResults(
                    success = true,
                    source = "SerpAPI (Google)",
                    results = results,
                    searchTimeMs = System.currentTimeMillis() - startTime,
                )
            }
        }
    }
    
    /**
     * 使用 SearXNG 搜索（自托管搜索引擎，最高优先级）
     * SearXNG JSON API: GET {searxng_url}/search?q=QUERY&format=json&categories=general
     */
    private suspend fun searchWithSearXNG(query: String, limit: Int): ExternalSearchResults {
        val startTime = System.currentTimeMillis()
        
        return withTimeout(SEARCH_TIMEOUT_MS) {
            recoverSearchFailure(
                onFailure = { error ->
                    logger.error("❌ [SearXNG] 搜索失败: ${error.message}")
                    ExternalSearchResults(
                        success = false,
                        source = "SearXNG",
                        results = emptyList(),
                        searchTimeMs = System.currentTimeMillis() - startTime,
                        error = error.message,
                    )
                }
            ) {
                val searchUrl = "${SEARXNG_URL.trimEnd('/')}/search"
                logger.info("🔍 [SearXNG] 搜索: $query (url=$searchUrl)")
                
                val response = client.get(searchUrl) {
                    parameter("q", query)
                    parameter("format", "json")
                    parameter("categories", "general")
                    parameter("language", "zh-CN")
                    parameter("pageno", "1")
                }
                
                val body = response.bodyAsText()
                val searxngResult = json.decodeFromString<SearXNGResponse>(body)
                
                val results = searxngResult.results?.take(limit)?.map { item ->
                    ExternalSearchResult(
                        title = item.title ?: "无标题",
                        snippet = item.content ?: item.snippet ?: "",
                        url = item.url ?: "",
                        source = "SearXNG (${item.engine ?: "unknown"})"
                    )
                } ?: emptyList()
                
                logger.info("🔍 [SearXNG] 搜索成功: 找到 ${results.size} 条结果")
                
                ExternalSearchResults(
                    success = results.isNotEmpty(),
                    source = "SearXNG",
                    results = results,
                    searchTimeMs = System.currentTimeMillis() - startTime,
                )
            }
        }
    }
    
    /**
     * 使用 DuckDuckGo Instant Answer API（免费）
     */
    private suspend fun searchWithDuckDuckGo(query: String, limit: Int): ExternalSearchResults {
        val startTime = System.currentTimeMillis()
        
        return withTimeout(SEARCH_TIMEOUT_MS) {
            recoverSearchFailure(
                onFailure = { error ->
                    logger.error("❌ DuckDuckGo 搜索失败: ${error.message}")
                    ExternalSearchResults(
                        success = false,
                        source = "DuckDuckGo",
                        results = emptyList(),
                        searchTimeMs = System.currentTimeMillis() - startTime,
                        error = error.message,
                    )
                }
            ) {
                val response = client.get(DUCKDUCKGO_URL) {
                    parameter("q", query)
                    parameter("format", "json")
                    parameter("no_html", "1")
                    parameter("skip_disambig", "1")
                }
                
                val body = response.bodyAsText()
                val ddgResult = json.decodeFromString<DuckDuckGoResponse>(body)
                
                val results = mutableListOf<ExternalSearchResult>()
                
                // 主要结果（Abstract）
                if (ddgResult.abstract?.isNotBlank() == true) {
                    results.add(ExternalSearchResult(
                        title = ddgResult.heading ?: "搜索结果",
                        snippet = ddgResult.abstract,
                        url = ddgResult.abstractUrl ?: "",
                        source = "DuckDuckGo (Abstract)"
                    ))
                }
                
                // 相关主题
                ddgResult.relatedTopics?.take(limit - results.size)?.forEach { topic ->
                    if (topic.text?.isNotBlank() == true) {
                        results.add(ExternalSearchResult(
                            title = topic.text.take(50) + if (topic.text.length > 50) "..." else "",
                            snippet = topic.text,
                            url = topic.firstUrl ?: "",
                            source = "DuckDuckGo (Related)"
                        ))
                    }
                }
                
                logger.info("🦆 DuckDuckGo 搜索成功: 找到 ${results.size} 条结果")
                
                ExternalSearchResults(
                    success = true,
                    source = "DuckDuckGo",
                    results = results.take(limit),
                    searchTimeMs = System.currentTimeMillis() - startTime,
                )
            }
        }
    }
    
    fun close() {
        client.close()
    }
}

// ==================== 数据模型 ====================

/**
 * 外部搜索结果
 */
@Serializable
data class ExternalSearchResult(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String
)

/**
 * 外部搜索结果集
 */
@Serializable
data class ExternalSearchResults(
    val success: Boolean,
    val source: String,
    val results: List<ExternalSearchResult>,
    val searchTimeMs: Long,
    val error: String? = null
)

// ==================== API 响应模型 ====================

/**
 * SerpAPI 响应
 */
@Serializable
data class SerpAPIResponse(
    @SerialName("organic_results")
    val organicResults: List<SerpAPIOrganicResult>? = null
)

@Serializable
data class SerpAPIOrganicResult(
    val title: String? = null,
    val snippet: String? = null,
    val link: String? = null
)

/**
 * DuckDuckGo 响应
 */
@Serializable
data class DuckDuckGoResponse(
    @SerialName("Abstract")
    val abstract: String? = null,
    @SerialName("AbstractText")
    val abstractText: String? = null,
    @SerialName("AbstractURL")
    val abstractUrl: String? = null,
    @SerialName("Heading")
    val heading: String? = null,
    @SerialName("RelatedTopics")
    val relatedTopics: List<DuckDuckGoTopic>? = null
)

@Serializable
data class DuckDuckGoTopic(
    @SerialName("Text")
    val text: String? = null,
    @SerialName("FirstURL")
    val firstUrl: String? = null
)

/**
 * Bing Search API 响应
 */
@Serializable
data class BingSearchResponse(
    val webPages: BingWebPages? = null
)

@Serializable
data class BingWebPages(
    val value: List<BingWebPage>? = null,
    val totalEstimatedMatches: Long? = null
)

@Serializable
data class BingWebPage(
    val name: String? = null,
    val url: String? = null,
    val snippet: String? = null,
    val displayUrl: String? = null
)

/**
 * SearXNG JSON API 响应
 * API: GET /search?q=QUERY&format=json
 */
@Serializable
data class SearXNGResponse(
    val query: String? = null,
    @SerialName("number_of_results")
    val numberOfResults: Int? = null,
    val results: List<SearXNGResult>? = null
)

@Serializable
data class SearXNGResult(
    val title: String? = null,
    val url: String? = null,
    val content: String? = null,
    val snippet: String? = null,
    val engine: String? = null,
    val score: Double? = null
)
