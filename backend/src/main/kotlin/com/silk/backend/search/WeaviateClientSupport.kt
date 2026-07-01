package com.silk.backend.search

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger

private val englishKeywordRegex = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
private val chineseCharsRegex = Regex("([\\u4e00-\\u9fa5])\\s+([\\u4e00-\\u9fa5])")
private val whitespaceRegex = Regex("\\s+")
private val chineseQueryRegex = Regex("[\\u4e00-\\u9fa5]+")

internal fun extractEnglishKeywords(query: String): List<String> =
    englishKeywordRegex.findAll(query)
        .map { it.value }
        .filter { it.length >= 3 }
        .toList()

internal fun cleanTextForSearch(text: String): String {
    var result = text.replace(chineseCharsRegex, "$1$2")
    var previous = ""
    while (previous != result) {
        previous = result
        result = result.replace(chineseCharsRegex, "$1$2")
    }
    return result.replace(whitespaceRegex, " ").trim()
}

internal fun tokenizeChineseQuery(query: String): String {
    val result = StringBuilder()
    var lastEnd = 0

    for (match in chineseQueryRegex.findAll(query)) {
        if (match.range.first > lastEnd) {
            result.append(query.substring(lastEnd, match.range.first))
        }

        val chars = match.value.toList()
        result.append(chars.joinToString(" "))
        if (chars.size >= 2) {
            result.append(" ")
            for (index in 0 until chars.size - 1) {
                result.append("${chars[index]}${chars[index + 1]} ")
            }
        }

        lastEnd = match.range.last + 1
    }

    if (lastEnd < query.length) {
        result.append(query.substring(lastEnd))
    }

    return result.toString().replace(whitespaceRegex, " ").trim()
}

internal fun sanitizeDocumentForIndexing(document: IndexDocument): IndexDocument =
    document.copy(
        content = cleanTextForSearch(document.content),
        title = document.title?.let(::cleanTextForSearch),
        summary = document.summary?.let(::cleanTextForSearch)
    )

internal fun buildIndexDocumentJson(document: IndexDocument, participants: List<String>): String {
    val participantsJson = participants.joinToString(",") { "\"$it\"" }
    val tagsJson = document.tags.joinToString(",") { "\"$it\"" }
    return buildString {
        append("{")
        append("\"class\":\"SilkContext\",")
        append("\"properties\":{")
        append("\"content\":\"${escapeJson(document.content)}\",")
        document.title?.let { append("\"title\":\"${escapeJson(it)}\",") }
        document.summary?.let { append("\"summary\":\"${escapeJson(it)}\",") }
        append("\"sourceType\":\"${document.sourceType}\",")
        document.fileType?.let { append("\"fileType\":\"$it\",") }
        append("\"sessionId\":\"${document.sessionId}\",")
        append("\"participants\":[$participantsJson],")
        document.authorId?.let { append("\"authorId\":\"$it\",") }
        document.authorName?.let { append("\"authorName\":\"${escapeJson(it)}\",") }
        document.filePath?.let { append("\"filePath\":\"${escapeJson(it)}\",") }
        document.sourceUrl?.let { append("\"sourceUrl\":\"$it\",") }
        document.timestamp?.let { append("\"timestamp\":\"$it\",") }
        document.messageId?.let { append("\"messageId\":\"${it}\",") }
        if (document.tags.isNotEmpty()) {
            append("\"tags\":[$tagsJson],")
        }
        append("\"chunkIndex\":${document.chunkIndex},")
        append("\"totalChunks\":${document.totalChunks},")
        append("\"importance\":${document.importance}")
        append("}}")
    }
}

internal fun escapeJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

internal fun escapeGraphQL(text: String): String = text
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

internal suspend fun logIndexDocumentResult(
    logger: Logger,
    title: String?,
    response: HttpResponse
): Boolean {
    val success = response.status.isSuccess()
    if (success) {
        logger.info("✅ [Weaviate] 索引成功: {}", title)
        return true
    }

    val responseBody = response.bodyAsText()
    logger.error("❌ [Weaviate] 索引失败: {} - {}", response.status, responseBody)
    return false
}

internal fun buildUserSessionsQuery(userId: String): String = """
    {
        Get {
            SilkSession(
                where: {
                    path: ["participants"],
                    operator: ContainsAny,
                    valueTextArray: ["$userId"]
                }
                limit: 100
            ) {
                sessionId
                sessionName
                participants
                ownerId
                lastActiveAt
                isArchived
                _additional { id }
            }
        }
    }
    """.trimIndent()

internal fun parseUserSessions(result: JsonObject): List<SessionInfo> {
    val sessions = result["data"]?.jsonObject
        ?.get("Get")?.jsonObject
        ?.get("SilkSession")?.jsonArray
        ?: return emptyList()

    return sessions.mapNotNull { obj ->
        val sessionObj = obj.jsonObject
        SessionInfo(
            sessionId = sessionObj["sessionId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
            sessionName = sessionObj["sessionName"]?.jsonPrimitive?.content,
            participants = sessionObj["participants"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            ownerId = sessionObj["ownerId"]?.jsonPrimitive?.content,
            isArchived = sessionObj["isArchived"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }
}

internal fun buildDeleteMessageQuery(sessionId: String, messageId: String): String = """
    {
        Get {
            SilkContext(
                where: {
                    operator: And,
                    operands: [
                        { path: ["sessionId"], operator: Equal, valueText: "$sessionId" },
                        { path: ["messageId"], operator: Equal, valueText: "$messageId" }
                    ]
                }
                limit: 1
            ) {
                _additional { id }
            }
        }
    }
    """.trimIndent()

internal fun parseWeaviateObjectId(result: JsonObject): String? =
    result["data"]?.jsonObject
        ?.get("Get")?.jsonObject
        ?.get("SilkContext")?.jsonArray
        ?.firstOrNull()?.jsonObject
        ?.get("_additional")?.jsonObject
        ?.get("id")?.jsonPrimitive?.content

internal suspend fun performHybridSearch(
    httpClient: HttpClient,
    json: Json,
    logger: Logger,
    baseUrl: String,
    query: String,
    whereFilter: String?,
    limit: Int,
    searchType: String,
    startTime: Long
): SearchResults {
    val tokenizedQuery = tokenizeChineseQuery(query)
    logger.debug(
        "🔍 [Weaviate] 执行搜索: query='{}' -> tokenized='{}', type={}, limit={}",
        query,
        tokenizedQuery,
        searchType,
        limit
    )

    val graphqlQuery = buildHybridSearchQuery(tokenizedQuery, whereFilter, limit)
    logger.debug("🔍 [Weaviate] GraphQL Query:\n{}", graphqlQuery)

    val response = httpClient.post("$baseUrl/v1/graphql") {
        contentType(ContentType.Application.Json)
        setBody(GraphQLRequest(query = graphqlQuery))
    }

    val responseText = response.bodyAsText()
    logger.debug("🔍 [Weaviate] 响应: {}...", responseText.take(500))

    val result = json.decodeFromString<GraphQLResponse>(responseText)
    if (result.errors?.isNotEmpty() == true) {
        logger.error("❌ [Weaviate] GraphQL 错误: {}", result.errors)
    }

    val documents = result.data
        ?.get
        ?.silkContext
        .orEmpty()
        .map(::toSearchDocument)
        .take(limit)

    logger.debug("🔍 [Weaviate] 返回结果数: {}", documents.size)
    return SearchResults(
        documents = documents,
        totalCount = documents.size,
        queryTimeMs = System.currentTimeMillis() - startTime,
        searchType = searchType
    )
}

private fun buildHybridSearchQuery(
    tokenizedQuery: String,
    whereFilter: String?,
    limit: Int
): String {
    val whereClause = whereFilter?.let { "\n                    where: $it" }.orEmpty()
    val scoreField = if (tokenizedQuery.isNotBlank()) "\n                        score" else ""
    val bm25Clause = if (tokenizedQuery.isNotBlank()) {
        """
                    bm25: {
                        query: "${escapeGraphQL(tokenizedQuery)}"
                    }
        """.trimIndent()
    } else {
        ""
    }

    val queryBody = buildString {
        if (bm25Clause.isNotBlank()) {
            append("\n                    ")
            append(bm25Clause.replace("\n", "\n                    "))
        }
        append(whereClause)
        append("\n                    limit: $limit")
    }

    return """
        {
            Get {
                SilkContext($queryBody
                ) {
                    content
                    title
                    sourceType
                    fileType
                    sessionId
                    filePath
                    timestamp
                    authorId
                    authorName
                    importance
                    _additional {
                        id$scoreField
                    }
                }
            }
        }
        """.trimIndent()
}

private fun toSearchDocument(obj: SilkContextObject): SearchDocument =
    SearchDocument(
        id = obj.additional?.id ?: "",
        content = obj.content ?: "",
        title = obj.title,
        sourceType = obj.sourceType ?: "UNKNOWN",
        fileType = obj.fileType,
        sessionId = obj.sessionId ?: "",
        filePath = obj.filePath,
        sourceUrl = obj.sourceUrl,
        timestamp = obj.timestamp,
        authorId = obj.authorId,
        authorName = obj.authorName,
        chunkIndex = obj.chunkIndex ?: 0,
        totalChunks = obj.totalChunks ?: 1,
        tags = obj.tags ?: emptyList(),
        score = obj.additional?.score ?: 1.0f,
        importance = obj.importance ?: 0.5f
    )
