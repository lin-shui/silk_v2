package com.silk.backend.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.coroutineContext

/**
 * Anthropic Messages API 客户端
 * 封装与 Anthropic API 的 HTTP 通信、消息格式转换、SSE 流式解析。
 * 输入/输出复用 DirectModelAgent 内部的 Message / ToolCall / Tool 格式，
 * 由本类在边界处做格式转换，上游无需感知 Anthropic 协议细节。
 */
class AnthropicClient(
    private val apiKey: String = AIConfig.ANTHROPIC_API_KEY,
    private val model: String = AIConfig.ANTHROPIC_MODEL,
    private val baseUrl: String = AIConfig.ANTHROPIC_API_BASE_URL
) {
    private val logger = LoggerFactory.getLogger(AnthropicClient::class.java)
    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(AIConfig.TIMEOUT))
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    data class Citation(
        val title: String,
        val url: String,
        val text: String
    )

    data class AnthropicStreamResult(
        val content: String,
        val toolCalls: List<ToolCall>? = null,
        val citations: List<Citation>? = null
    )

    // ── 公开 API ─────────────────────────────────────────────────────────

    /**
     * 调用 Anthropic Messages API（流式），在流中检测 tool_use。
     *
     * @param systemPrompt 顶层 system 参数
     * @param messages 内部 Message 列表（含 system 角色会被移入 systemPrompt）
     * @param tools 内部 Tool 定义（含 web_search 特殊处理）
     * @param callback 流式回调 (stepType, content, isComplete)
     * @return 聚合后的最终文本 + toolCalls
     */
    suspend fun streamCompletion(
        systemPrompt: String,
        messages: List<Message>,
        tools: List<Tool>?,
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit
    ): AnthropicStreamResult {
        // 0. 前置校验
        require(apiKey.isNotBlank()) {
            "ANTHROPIC_API_KEY 未配置。请在 .env 中设置 ANTHROPIC_API_KEY=your_key"
        }

        // 1. 转换 system prompt + messages → Anthropic 格式
        val mergedSystem = buildString {
            appendLine(systemPrompt)
            // 从 messages 中提取 system 消息
            val systemParts = messages.filter { it.role == "system" }.mapNotNull { it.content }
            if (systemParts.isNotEmpty()) {
                appendLine()
                systemParts.forEach { appendLine(it) }
            }
        }

        // 只保留 user / assistant / tool 角色
        val filteredMessages = messages.filter { it.role != "system" }
        val apiMessages = filteredMessages.map { msg -> convertMessage(msg) }

        // 2. 转换 tools → Anthropic 格式
        val apiTools = if (tools != null) tools.mapNotNull { convertTool(it) } else null
        logger.info("[Anthropic] Request: tools={}, model={}, messages={}",
            apiTools?.size ?: 0, model, filteredMessages.size)
        logger.info("[Anthropic] system_prompt_preview: {}",
            mergedSystem.trim().take(300).replace('\n', ' '))
        if (!apiTools.isNullOrEmpty()) {
            logger.info("[Anthropic] tools_json: {}",
                JsonArray(apiTools).toString().take(500))
        }
        logger.info("[Anthropic] last_user_msg: {}",
            filteredMessages.lastOrNull()?.let {
                if (it.role == "user") it.content?.take(100) else "not user"
            } ?: "empty")

        // 3. 构建请求体
        val bodyObj = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("stream", true)
            put("system", mergedSystem.trim())
            put("messages", JsonArray(apiMessages))
            if (!apiTools.isNullOrEmpty()) {
                put("tools", JsonArray(apiTools))
            }
        }
        val requestBody = bodyObj.toString()

        // 4. 发送 HTTP 请求
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .timeout(Duration.ofMillis(AIConfig.TIMEOUT))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            val errorBody = try {
                response.body().bufferedReader().readText().take(2000)
            } catch (_: Exception) { "" }
            logger.error("❌ [Anthropic] API 失败: ${response.statusCode()}, body=$errorBody")
            error("Anthropic API 调用失败: ${response.statusCode()}")
        }

        // 5. 解析 SSE 流
        return parseStream(response, callback)
    }

    // ── SSE 流式解析 ──────────────────────────────────────────────────────

    private suspend fun parseStream(
        response: HttpResponse<java.io.InputStream>,
        callback: suspend (String, String, Boolean) -> Unit
    ): AnthropicStreamResult {
        val fullText = StringBuilder()
        var lastSentLength = 0
        val sendThreshold = 30

        // tool_use 收集（key = block index）
        val pendingToolUses = mutableMapOf<Int, PendingToolUse>()

        // 记录完成的 tool_use（过滤掉服务端工具，如 web_search）
        val completedToolCalls = mutableListOf<ToolCall>()

        // 收集引用元数据（来自服务端 web_search 的结果）
        val collectedCitations = mutableListOf<Citation>()

        // 流式读取带 watchdog 超时保护（强制关闭 InputStream 以中断 blocking readLine）
        val inputStream = response.body()
        coroutineScope {
            val watchdog = launch(Dispatchers.IO) {
                delay(120_000L) // 2 分钟无新数据则强制关闭
                logger.warn("⚠️ [Anthropic] SSE 流读取超时 (120s)，强制关闭连接")
                inputStream.close()
            }
            try {
                withContext(Dispatchers.IO) {
                    inputStream.bufferedReader().use { reader ->
                        var line: String? = reader.readLine()

                        while (line != null) {
                            ensureActive()
                            lastSentLength = processStreamingLine(
                                line = line,
                                fullText = fullText,
                                pendingToolUses = pendingToolUses,
                                completedToolCalls = completedToolCalls,
                                collectedCitations = collectedCitations,
                                lastSentLength = lastSentLength,
                                sendThreshold = sendThreshold,
                                callback = callback,
                            )
                            line = reader.readLine()
                        }
                    }
                }
            } finally {
                watchdog.cancel()
            }
        }

        // 发送剩余文本
        if (fullText.length > lastSentLength) {
            val remaining = fullText.substring(lastSentLength)
            if (remaining.isNotEmpty()) {
                callback("streaming_incremental", remaining, false)
            }
        }

        return AnthropicStreamResult(
            content = fullText.toString(),
            toolCalls = completedToolCalls.ifEmpty { null },
            citations = collectedCitations.ifEmpty { null }
        )
    }

    private suspend fun processStreamingLine(
        line: String,
        fullText: StringBuilder,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
        completedToolCalls: MutableList<ToolCall>,
        collectedCitations: MutableList<Citation>,
        lastSentLength: Int,
        sendThreshold: Int,
        callback: suspend (String, String, Boolean) -> Unit,
    ): Int {
        if (!line.startsWith("data: ")) return lastSentLength

        val data = line.removePrefix("data: ").trim()
        if (data.isEmpty() || data == "{}") return lastSentLength

        val handled = tryHandleStreamingEvent(
            data = data,
            fullText = fullText,
            pendingToolUses = pendingToolUses,
            completedToolCalls = completedToolCalls,
            collectedCitations = collectedCitations,
        )
        if (!handled) return lastSentLength

        return emitIncrementalChunkIfNeeded(
            fullText = fullText,
            lastSentLength = lastSentLength,
            sendThreshold = sendThreshold,
            callback = callback,
        )
    }

    private fun tryHandleStreamingEvent(
        data: String,
        fullText: StringBuilder,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
        completedToolCalls: MutableList<ToolCall>,
        collectedCitations: MutableList<Citation>,
    ): Boolean {
        return try {
            handleEvent(data, fullText, pendingToolUses, completedToolCalls, collectedCitations)
            true
        } catch (parseError: SerializationException) {
            logger.debug("⚠️ [Anthropic] 解析事件失败: ${parseError.message}")
            false
        } catch (parseError: IllegalArgumentException) {
            logger.debug("⚠️ [Anthropic] 解析事件失败: ${parseError.message}")
            false
        } catch (parseError: IllegalStateException) {
            logger.debug("⚠️ [Anthropic] 解析事件失败: ${parseError.message}")
            false
        }
    }

    private suspend fun emitIncrementalChunkIfNeeded(
        fullText: StringBuilder,
        lastSentLength: Int,
        sendThreshold: Int,
        callback: suspend (String, String, Boolean) -> Unit,
    ): Int {
        if (fullText.length - lastSentLength < sendThreshold) return lastSentLength

        val chunk = fullText.substring(lastSentLength)
        callback("streaming_incremental", chunk, false)
        return fullText.length
    }

    private fun handleEvent(
        data: String,
        fullText: StringBuilder,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
        completedToolCalls: MutableList<ToolCall>,
        collectedCitations: MutableList<Citation>,
    ) {
        val obj = json.parseToJsonElement(data).jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: ""

        when (type) {
            "content_block_start" -> handleContentBlockStart(
                obj = obj,
                fullText = fullText,
                pendingToolUses = pendingToolUses,
                collectedCitations = collectedCitations,
            )
            "content_block_delta" -> handleContentBlockDelta(
                obj = obj,
                fullText = fullText,
                pendingToolUses = pendingToolUses,
            )
            "content_block_stop" -> handleContentBlockStop(
                obj = obj,
                pendingToolUses = pendingToolUses,
                completedToolCalls = completedToolCalls,
            )

            "message_delta" -> {
                val usage = obj["usage"]?.jsonObject
                if (usage != null) {
                    val inputTokens = usage["input_tokens"]?.jsonPrimitive?.int ?: 0
                    val outputTokens = usage["output_tokens"]?.jsonPrimitive?.int ?: 0
                    logger.info("📊 [Anthropic] 用量: input=$inputTokens, output=$outputTokens")
                }
            }
        }
    }

    private fun handleContentBlockStart(
        obj: JsonObject,
        fullText: StringBuilder,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
        collectedCitations: MutableList<Citation>,
    ) {
        val index = obj["index"]?.jsonPrimitive?.int ?: return
        val block = obj["content_block"]?.jsonObject ?: return
        val blockType = block["type"]?.jsonPrimitive?.content
        logger.info("[Anthropic SSE] content_block_start index={}, type={}", index, blockType)

        when (blockType) {
            "tool_use", "server_tool_use" -> handleToolUseBlockStart(index, block, blockType, pendingToolUses)
            "text" -> collectTextBlockCitations(block, collectedCitations)
            "web_search_tool_result" -> handleWebSearchToolResultBlock(block, fullText, collectedCitations)
            "thinking" -> Unit
            else -> logger.warn("[Anthropic] 未知 content_block 类型: {}", blockType)
        }
    }

    private fun handleToolUseBlockStart(
        index: Int,
        block: JsonObject,
        blockType: String?,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
    ) {
        val id = block["id"]?.jsonPrimitive?.content ?: ""
        val name = block["name"]?.jsonPrimitive?.content ?: ""
        logger.info("[Anthropic] {}: id={}, name={}", blockType, id, name)
        if (blockType == "server_tool_use") {
            logger.info("[Anthropic] 服务端工具调用: {}", name)
            return
        }
        pendingToolUses[index] = PendingToolUse(id = id, name = name, arguments = StringBuilder())
    }

    private fun collectTextBlockCitations(
        block: JsonObject,
        collectedCitations: MutableList<Citation>,
    ) {
        val citationsArray = block["citations"]?.jsonArray
        if (citationsArray != null) {
            logger.info("[Anthropic] text block 发现 citations, count={}", citationsArray.size)
        }
        collectedCitations += citationsArray.toCitations()
    }

    private fun handleWebSearchToolResultBlock(
        block: JsonObject,
        fullText: StringBuilder,
        collectedCitations: MutableList<Citation>,
    ) {
        logger.info("[Anthropic] web_search_tool_result block keys: {}", block.keys.joinToString(", "))
        val content = extractTextContent(block)
        if (content.isNotEmpty()) {
            fullText.append(content)
        }

        val citations = block["citations"]?.jsonArray?.toCitations()
        if (!citations.isNullOrEmpty()) {
            logger.info("[Anthropic] web_search_tool_result: citations={}", citations.size)
            collectedCitations += citations
            return
        }

        val fallbackCitations = block["content"]?.jsonArray.toSearchResultCitations()
        if (fallbackCitations.isNotEmpty()) {
            collectedCitations += fallbackCitations
            logger.info("[Anthropic] web_search_tool_result: 从 content 提取 {} 条引用", fallbackCitations.size)
        }
    }

    private fun handleContentBlockDelta(
        obj: JsonObject,
        fullText: StringBuilder,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
    ) {
        val index = obj["index"]?.jsonPrimitive?.int ?: return
        val delta = obj["delta"]?.jsonObject ?: return
        val deltaType = delta["type"]?.jsonPrimitive?.content

        when (deltaType) {
            "text_delta" -> appendDeltaText(fullText, delta["text"]?.jsonPrimitive?.content ?: "")
            "input_json_delta" -> {
                val partial = delta["partial_json"]?.jsonPrimitive?.content ?: ""
                pendingToolUses[index]?.arguments?.append(partial)
            }
            "content_delta" -> appendDeltaText(
                fullText,
                delta["text"]?.jsonPrimitive?.content ?: delta["content"]?.jsonPrimitive?.content ?: "",
            )
            else -> {
                val text = delta["text"]?.jsonPrimitive?.content
                    ?: delta["content"]?.jsonPrimitive?.content ?: ""
                if (text.isNotEmpty()) {
                    logger.info("[Anthropic] 非标准 delta '{}' 含文本: {}...", deltaType, text.take(80))
                    fullText.append(text)
                }
            }
        }
    }

    private fun appendDeltaText(fullText: StringBuilder, text: String) {
        if (text.isNotEmpty()) {
            fullText.append(text)
        }
    }

    private fun handleContentBlockStop(
        obj: JsonObject,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
        completedToolCalls: MutableList<ToolCall>,
    ) {
        val index = obj["index"]?.jsonPrimitive?.int ?: return
        val pending = pendingToolUses.remove(index) ?: return
        if (pending.name == "web_search") {
            logger.info("[Anthropic] Claude 使用了 web_search")
            return
        }

        completedToolCalls.add(
            ToolCall(
                id = pending.id,
                type = "function",
                function = ToolFunction(
                    name = pending.name,
                    arguments = parseToolArguments(pending.arguments.toString().trim())
                )
            )
        )
    }

    private fun parseToolArguments(rawArgs: String): String {
        return try {
            json.parseToJsonElement(rawArgs).toString()
        } catch (_: Exception) {
            val fixed = repairJsonArgs(rawArgs)
            if (fixed != null) json.parseToJsonElement(fixed).toString() else "{}"
        }
    }

    private fun JsonArray?.toCitations(): List<Citation> {
        if (this == null) return emptyList()
        return map { citElem ->
            val citation = citElem.jsonObject
            Citation(
                title = citation["title"]?.jsonPrimitive?.content ?: "",
                url = citation["url"]?.jsonPrimitive?.content ?: "",
                text = citation["text"]?.jsonPrimitive?.content ?: "",
            )
        }
    }

    private fun JsonArray?.toSearchResultCitations(): List<Citation> {
        if (this == null) return emptyList()
        val citations = mutableListOf<Citation>()
        forEachIndexed { index, result ->
            val searchResult = runCatching { result.jsonObject }.getOrNull() ?: return@forEachIndexed
            val title = searchResult["title"]?.jsonPrimitive?.content ?: ""
            val url = searchResult["url"]?.jsonPrimitive?.content ?: return@forEachIndexed
            citations.add(
                Citation(
                    title = title.ifEmpty { "搜索结果 #${index + 1}" },
                    url = url,
                    text = title.ifEmpty { url }
                )
            )
        }
        return citations
    }

    // ── 消息格式转换 ──────────────────────────────────────────────────────

    /**
     * 将内部 Message（OpenAI-like）转换为 Anthropic API 消息 JSON 元素。
     */
    private fun convertMessage(msg: Message): JsonElement {
        return when (msg.role) {
            "user" -> convertUserMessage(msg)
            "assistant" -> convertAssistantMessage(msg)
            "tool" -> convertToolMessage(msg)
            else -> basicAnthropicMessage(role = "user", content = msg.content)
        }
    }

    private fun convertUserMessage(msg: Message): JsonElement {
        if (msg.toolCallId == null) {
            return basicAnthropicMessage(role = "user", content = msg.content)
        }

        return buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", msg.toolCallId)
                    put("content", msg.content ?: "")
                })
            })
        }
    }

    private fun convertAssistantMessage(msg: Message): JsonElement {
        val toolCalls = msg.toolCalls
        if (toolCalls.isNullOrEmpty()) {
            return basicAnthropicMessage(role = "assistant", content = msg.content)
        }

        val contentBlocks = buildJsonArray {
            if (!msg.content.isNullOrBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", msg.content)
                })
            }
            toolCalls.forEach { toolCall ->
                add(convertAssistantToolUseBlock(toolCall))
            }
        }

        return buildJsonObject {
            put("role", "assistant")
            put("content", contentBlocks)
        }
    }

    private fun convertAssistantToolUseBlock(toolCall: ToolCall): JsonObject {
        return buildJsonObject {
            put("type", "tool_use")
            put("id", toolCall.id)
            put("name", toolCall.function.name)
            put("input", parseToolCallInput(toolCall.function.arguments))
        }
    }

    private fun parseToolCallInput(arguments: String): JsonElement {
        return try {
            json.parseToJsonElement(arguments)
        } catch (_: Exception) {
            buildJsonObject { }
        }
    }

    private fun convertToolMessage(msg: Message): JsonElement {
        return buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", msg.toolCallId ?: "")
                    put("content", msg.content ?: "")
                })
            })
        }
    }

    private fun basicAnthropicMessage(role: String, content: String?): JsonObject {
        return buildJsonObject {
            put("role", role)
            put("content", content ?: "")
        }
    }

    /**
     * 将内部 Tool 定义转换为 Anthropic 格式。
     * 特殊处理 web_search（使用 Anthropic 原生 web_search_20260209 类型）。
     * 返回 null 表示跳过（如 execute_command 默认不暴露给 Claude）。
     */
    private fun convertTool(tool: Tool): JsonObject? {
        val name = tool.function.name

        // execute_command 不暴露给多租户 @silk
        if (name == "execute_command") return null

        // web_search 使用 Anthropic 原生服务器端工具类型
        if (name == "web_search") {
            return buildJsonObject {
                put("type", "web_search_20260209")
                put("name", "web_search")
                put("max_uses", 5)
            }
        }

        return buildJsonObject {
            put("name", name)
            put("description", tool.function.description)
            put("input_schema", tool.function.parameters)
        }
    }

    // ── 工具函数 ──────────────────────────────────────────────────────────

    private data class PendingToolUse(
        val id: String,
        val name: String,
        val arguments: StringBuilder
    )

    /** 尝试修复残缺 JSON arguments */
    private fun repairJsonArgs(raw: String): String? {
        if (raw.isBlank()) return "{}"
        var s = raw.trim()
        if (!s.startsWith("{")) s = "{$s"
        s = s.replace(Regex(",\\s*\"[^\"]*$"), "")
        val unclosedString = Regex(":\\s*\"([^\"]*)$")
        if (unclosedString.containsMatchIn(s)) s = "$s\""
        if (!s.endsWith("}")) s = "$s}"
        s = s.replace(Regex("\"\\s*\"(?=[a-zA-Z_])")) { "\",\"" }
        return try {
            json.parseToJsonElement(s).toString()
        } catch (_: Exception) {
            null
        }
    }

    /** 从各种类型的 content 字段中提取文本 */
    private fun extractTextContent(block: JsonObject): String {
        // 尝试直接字符串
        block["text"]?.jsonPrimitive?.content?.let { return it }
        block["content"]?.jsonPrimitive?.content?.let { return it }
        // 尝试 content 为数组（如 content block 数组）
        block["content"]?.jsonArray?.let { arr ->
            val texts = arr.mapNotNull { elem ->
                val obj = try { elem.jsonObject } catch (_: Exception) { null } ?: return@mapNotNull null
                obj["text"]?.jsonPrimitive?.content ?: obj["content"]?.jsonPrimitive?.content
            }
            if (texts.isNotEmpty()) return texts.joinToString("\n")
        }
        return ""
    }

    // ── 测试辅助 ──────────────────────────────────────────────────────────

    internal fun convertMessageForTest(msg: Message): JsonElement =
        convertMessage(msg)
}
