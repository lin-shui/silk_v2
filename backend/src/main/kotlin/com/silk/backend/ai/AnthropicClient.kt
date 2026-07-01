package com.silk.backend.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
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
@Suppress("TooGenericExceptionCaught", "TooGenericExceptionThrown")
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
        val citations: List<Citation>? = null,
        val contentBlocks: List<ContentBlock>? = null  // 结构化 content block（按 index 排序）
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
        require(!apiKey.isBlank()) {
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
            // Enable extended thinking so the model returns thinking blocks,
            // allowing the frontend to display a collapsible "思考过程" section
            // matching cc-connect's display format.
            put("thinking", buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", 1024)
            })
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

        // 结构化 content block 追踪
        val streamingBlocks = mutableMapOf<Int, ContentBlock>()
        var currentThinkingIndex = -1

        // 当 block 状态变化时，emit 完整 block 列表到前端
        suspend fun emitBlocksState() {
            val jsonStr = buildJsonArray {
                streamingBlocks.entries.sortedBy { it.key }.forEach { (_, block) ->
                    addJsonObject {
                        put("index", block.index)
                        put("type", block.type)
                        put("content", block.content)
                        put("isComplete", block.isComplete)
                        put("toolName", block.toolName)
                        put("toolId", block.toolId)
                    }
                }
            }.toString()
            callback("blocks_state", jsonStr, false)
        }

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

                            if (line.startsWith("data: ")) {
                                val data = line.removePrefix("data: ").trim()
                                if (data.isEmpty() || data == "{}") {
                                    line = reader.readLine()
                                    continue
                                }
                                try {
                                    handleEvent(
                                        data = data,
                                        fullText = fullText,
                                        pendingToolUses = pendingToolUses,
                                        completedToolCalls = completedToolCalls,
                                        collectedCitations = collectedCitations,
                                        streamingBlocks = streamingBlocks,
                                        currentThinkingIndexRef = { currentThinkingIndex },
                                        setCurrentThinkingIndex = { currentThinkingIndex = it },
                                        onBlockChanged = ::emitBlocksState
                                    )
                                    // 将累积文本通过流式回调逐段发送
                                    if (fullText.length - lastSentLength >= sendThreshold) {
                                        val chunk = fullText.substring(lastSentLength)
                                        lastSentLength = fullText.length
                                        callback("streaming_incremental", chunk, false)
                                    }
                                } catch (e: Exception) {
                                    logger.debug("⚠️ [Anthropic] 解析事件失败: ${e.message}")
                                }
                            }

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

        // 发送最终 block 状态
        emitBlocksState()

        val sortedBlocks = streamingBlocks.entries
            .sortedBy { it.key }
            .map { it.value }
            .ifEmpty { null }

        return AnthropicStreamResult(
            content = fullText.toString(),
            toolCalls = completedToolCalls.ifEmpty { null },
            citations = collectedCitations.ifEmpty { null },
            contentBlocks = sortedBlocks
        )
    }

    @Suppress("UnusedParameter")
    private suspend fun handleEvent(
        data: String,
        fullText: StringBuilder,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
        completedToolCalls: MutableList<ToolCall>,
        collectedCitations: MutableList<Citation>,
        streamingBlocks: MutableMap<Int, ContentBlock>,
        currentThinkingIndexRef: () -> Int,
        setCurrentThinkingIndex: (Int) -> Unit,
        onBlockChanged: suspend () -> Unit
    ) {
        val obj = json.parseToJsonElement(data).jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: ""

        when (type) {
            "content_block_start" -> handleContentBlockStart(
                obj, fullText, pendingToolUses, collectedCitations,
                streamingBlocks, setCurrentThinkingIndex, onBlockChanged
            )
            "content_block_delta" -> handleContentBlockDelta(
                obj, fullText, pendingToolUses, streamingBlocks, onBlockChanged
            )
            "content_block_stop" -> handleContentBlockStop(
                obj, pendingToolUses, completedToolCalls, streamingBlocks, onBlockChanged
            )
            "message_delta" -> handleMessageDelta(obj)
        }
    }

    @Suppress("UnusedParameter")
    private suspend fun handleContentBlockStart(
        obj: JsonObject,
        fullText: StringBuilder,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
        collectedCitations: MutableList<Citation>,
        streamingBlocks: MutableMap<Int, ContentBlock>,
        setCurrentThinkingIndex: (Int) -> Unit,
        onBlockChanged: suspend () -> Unit
    ) {
        val index = obj["index"]?.jsonPrimitive?.int ?: return
        val block = obj["content_block"]?.jsonObject ?: return
        val blockType = block["type"]?.jsonPrimitive?.content
        logger.info("[Anthropic SSE] content_block_start index={}, type={}", index, blockType)

        when (blockType) {
            "tool_use", "server_tool_use" ->
                handleToolUseBlockStart(index, block, blockType, pendingToolUses, streamingBlocks, onBlockChanged)
            "text" ->
                handleTextBlockStart(index, block, fullText, collectedCitations, streamingBlocks, onBlockChanged)
            "web_search_tool_result" ->
                handleWebSearchResultBlockStart(index, block, fullText, collectedCitations, streamingBlocks, onBlockChanged)
            "thinking" -> {
                setCurrentThinkingIndex(index)
                val initial = block["thinking"]?.jsonPrimitive?.content ?: ""
                streamingBlocks[index] = ContentBlock(
                    index = index, type = "thinking",
                    content = initial, isComplete = false
                )
                // thinking 内容不加入 fullText（纯文本版不包含思考过程）
                onBlockChanged()
            }
            else -> {
                logger.warn("[Anthropic] 未知 content_block 类型: {}", blockType)
            }
        }
    }

    private suspend fun handleToolUseBlockStart(
        index: Int,
        block: JsonObject,
        blockType: String,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
        streamingBlocks: MutableMap<Int, ContentBlock>,
        onBlockChanged: suspend () -> Unit
    ) {
        val id = block["id"]?.jsonPrimitive?.content ?: ""
        val name = block["name"]?.jsonPrimitive?.content ?: ""
        logger.info("[Anthropic] {}: id={}, name={}", blockType, id, name)
        // 服务端工具不加入 pendingToolUses（Anthropic 自行处理）
        if (blockType == "server_tool_use") {
            logger.info("[Anthropic] 服务端工具调用: {}", name)
        } else {
            pendingToolUses[index] = PendingToolUse(id = id, name = name, arguments = StringBuilder())
        }
        // 注入结构化 block（工具调用在 stream 中展示为 card）
        streamingBlocks[index] = ContentBlock(
            index = index, type = "tool_use",
            content = "", isComplete = false,
            toolName = name, toolId = id
        )
        onBlockChanged()
    }

    private suspend fun handleTextBlockStart(
        index: Int,
        block: JsonObject,
        fullText: StringBuilder,
        collectedCitations: MutableList<Citation>,
        streamingBlocks: MutableMap<Int, ContentBlock>,
        onBlockChanged: suspend () -> Unit
    ) {
        // 服务端 web_search 的引用元数据嵌入在 text content block 中
        streamingBlocks[index] = ContentBlock(
            index = index, type = "text",
            content = "", isComplete = false
        )
        val citationsArray = block["citations"]?.jsonArray
        if (citationsArray != null) {
            logger.info("[Anthropic] text block 发现 citations, count={}", citationsArray.size)
        }
        citationsArray?.forEach { citElem ->
            collectedCitations.add(citationFromJson(citElem.jsonObject))
        }
        // text block 可能初始就有内容（如引用嵌入的搜索结果片段）
        val initialText = block["text"]?.jsonPrimitive?.content ?: ""
        if (initialText.isNotEmpty()) {
            streamingBlocks[index] = streamingBlocks[index]!!.copy(content = initialText)
            fullText.append(initialText)
            onBlockChanged()
        }
    }

    private suspend fun handleWebSearchResultBlockStart(
        index: Int,
        block: JsonObject,
        fullText: StringBuilder,
        collectedCitations: MutableList<Citation>,
        streamingBlocks: MutableMap<Int, ContentBlock>,
        onBlockChanged: suspend () -> Unit
    ) {
        logger.info("[Anthropic] web_search_tool_result block keys: {}",
            block.keys.joinToString(", "))
        streamingBlocks[index] = ContentBlock(
            index = index, type = "tool_use",
            content = "", isComplete = false,
            toolName = "web_search", toolId = ""
        )
        val content = extractTextContent(block)
        if (content.isNotEmpty()) {
            streamingBlocks[index] = streamingBlocks[index]!!.copy(content = content)
            fullText.append(content)
            onBlockChanged()
        }
        collectWebSearchCitations(block, collectedCitations)
    }

    /** 从 web_search_tool_result block 提取引用（优先 citations 字段，否则从 content 数组提取）。 */
    private fun collectWebSearchCitations(block: JsonObject, collectedCitations: MutableList<Citation>) {
        // 优先从 citations/sources 字段提取引用
        val citationsArray = block["citations"]?.jsonArray
        if (citationsArray != null) {
            logger.info("[Anthropic] web_search_tool_result: citations={}", citationsArray.size)
            citationsArray.forEach { citElem ->
                collectedCitations.add(citationFromJson(citElem.jsonObject))
            }
            return
        }
        // DeepSeek 等代理：从 web_search_result 数组中提取 URL 作为引用
        val searchResults = block["content"]?.jsonArray ?: return
        var count = 0
        for (result in searchResults) {
            val resultObj = try { result.jsonObject } catch (_: Exception) { null } ?: continue
            val title = resultObj["title"]?.jsonPrimitive?.content ?: ""
            val url = resultObj["url"]?.jsonPrimitive?.content ?: ""
            if (url.isNotEmpty()) {
                collectedCitations.add(Citation(
                    title = title.ifEmpty { "搜索结果 #${count + 1}" },
                    url = url,
                    text = title.ifEmpty { url }
                ))
                count++
            }
        }
        logger.info("[Anthropic] web_search_tool_result: 从 content 提取 {} 条引用", count)
    }

    /** 从 citation JSON 对象构造 Citation（title/url/text 缺失时为空串）。 */
    private fun citationFromJson(cit: JsonObject): Citation = Citation(
        title = cit["title"]?.jsonPrimitive?.content ?: "",
        url = cit["url"]?.jsonPrimitive?.content ?: "",
        text = cit["text"]?.jsonPrimitive?.content ?: "",
    )

    private suspend fun handleContentBlockDelta(
        obj: JsonObject,
        fullText: StringBuilder,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
        streamingBlocks: MutableMap<Int, ContentBlock>,
        onBlockChanged: suspend () -> Unit
    ) {
        val index = obj["index"]?.jsonPrimitive?.int ?: return
        val delta = obj["delta"]?.jsonObject ?: return
        val deltaType = delta["type"]?.jsonPrimitive?.content

        when (deltaType) {
            "text_delta" -> {
                val text = delta["text"]?.jsonPrimitive?.content ?: ""
                fullText.append(text)
                // 更新结构化 block
                appendToTypedBlock(index, "text", text, streamingBlocks, onBlockChanged)
            }
            "input_json_delta" -> {
                val partial = delta["partial_json"]?.jsonPrimitive?.content ?: ""
                pendingToolUses[index]?.arguments?.append(partial)
            }
            "thinking_delta" -> {
                val text = delta["thinking"]?.jsonPrimitive?.content ?: ""
                appendToTypedBlock(index, "thinking", text, streamingBlocks, onBlockChanged)
            }
            // web_search_tool_result 等块的 delta 可能含文本
            "content_delta" ->
                handleContentDelta(index, delta, fullText, streamingBlocks, onBlockChanged)
            else ->
                handleUnknownDelta(deltaType, delta, fullText)
        }
    }

    /** 把 text 追加到 streamingBlocks[index] 上（仅当该 block 存在且类型匹配），并通知前端。 */
    private suspend fun appendToTypedBlock(
        index: Int,
        expectedType: String,
        text: String,
        streamingBlocks: MutableMap<Int, ContentBlock>,
        onBlockChanged: suspend () -> Unit
    ) {
        val existing = streamingBlocks[index]
        if (existing != null && existing.type == expectedType) {
            streamingBlocks[index] = existing.copy(content = existing.content + text)
            onBlockChanged()
        }
    }

    private suspend fun handleContentDelta(
        index: Int,
        delta: JsonObject,
        fullText: StringBuilder,
        streamingBlocks: MutableMap<Int, ContentBlock>,
        onBlockChanged: suspend () -> Unit
    ) {
        val text = delta["text"]?.jsonPrimitive?.content ?: delta["content"]?.jsonPrimitive?.content ?: ""
        if (text.isEmpty()) return
        fullText.append(text)
        val existing = streamingBlocks[index] ?: return
        streamingBlocks[index] = existing.copy(content = existing.content + text)
        onBlockChanged()
    }

    private fun handleUnknownDelta(deltaType: String?, delta: JsonObject, fullText: StringBuilder) {
        // 尝试从任何 delta 中提取文本
        val text = delta["text"]?.jsonPrimitive?.content
            ?: delta["content"]?.jsonPrimitive?.content ?: ""
        if (text.isNotEmpty()) {
            logger.info("[Anthropic] 非标准 delta '{}' 含文本: {}...", deltaType, text.take(80))
            fullText.append(text)
        }
    }

    private suspend fun handleContentBlockStop(
        obj: JsonObject,
        pendingToolUses: MutableMap<Int, PendingToolUse>,
        completedToolCalls: MutableList<ToolCall>,
        streamingBlocks: MutableMap<Int, ContentBlock>,
        onBlockChanged: suspend () -> Unit
    ) {
        val index = obj["index"]?.jsonPrimitive?.int ?: return

        val pending = pendingToolUses.remove(index)
        if (pending == null) {
            // 非 tool_use block（text/thinking），仅标记完成
            markBlockComplete(index, streamingBlocks, onBlockChanged)
            return
        }

        // web_search 是服务端工具，Anthropic 自行处理，我们无需响应
        if (pending.name == "web_search") {
            logger.info("[Anthropic] Claude 使用了 web_search")
            markBlockComplete(index, streamingBlocks, onBlockChanged)
            return
        }

        val rawArgs = pending.arguments.toString().trim()
        // 修复/验证 JSON
        val validArgs = try {
            json.parseToJsonElement(rawArgs).toString()
        } catch (_: Exception) {
            // 尝试修复残缺 JSON
            val fixed = repairJsonArgs(rawArgs)
            if (fixed != null) json.parseToJsonElement(fixed).toString() else "{}"
        }

        // 从工具参数生成人类可读的描述（匹配 cc-connect 的显示效果）
        val toolSummary = generateToolDescription(pending.name, validArgs)

        // 标记 block 完成并设置描述内容，通知前端
        val existing = streamingBlocks[index]
        if (existing != null) {
            streamingBlocks[index] = existing.copy(
                isComplete = true,
                content = toolSummary
            )
            onBlockChanged()
        }

        completedToolCalls.add(
            ToolCall(
                id = pending.id,
                type = "function",
                function = ToolFunction(
                    name = pending.name,
                    arguments = validArgs
                )
            )
        )
    }

    /** 标记 streamingBlocks[index] 为完成并通知前端（若该 block 存在）。 */
    private suspend fun markBlockComplete(
        index: Int,
        streamingBlocks: MutableMap<Int, ContentBlock>,
        onBlockChanged: suspend () -> Unit
    ) {
        val existing = streamingBlocks[index] ?: return
        streamingBlocks[index] = existing.copy(isComplete = true)
        onBlockChanged()
    }

    private fun handleMessageDelta(obj: JsonObject) {
        val usage = obj["usage"]?.jsonObject ?: return
        val inputTokens = usage["input_tokens"]?.jsonPrimitive?.int ?: 0
        val outputTokens = usage["output_tokens"]?.jsonPrimitive?.int ?: 0
        logger.info("📊 [Anthropic] 用量: input=$inputTokens, output=$outputTokens")
    }

    // ── 消息格式转换 ──────────────────────────────────────────────────────

    /**
     * 将内部 Message（OpenAI-like）转换为 Anthropic API 消息 JSON 元素。
     */
    private fun convertMessage(msg: Message): JsonElement {
        return when (msg.role) {
            "user" -> convertUserMessage(msg)
            "assistant" -> convertAssistantMessage(msg)
            "tool" -> toolResultMessage(msg.tool_call_id ?: "", msg.content ?: "")
            else -> simpleRoleMessage("user", msg.content ?: "") // fallback：user 角色
        }
    }

    /** 普通文本消息（role + 字符串 content）。 */
    private fun simpleRoleMessage(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    /** Anthropic 的 tool_result（包装为 user 角色）。 */
    private fun toolResultMessage(toolUseId: String, content: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", toolUseId)
                put("content", content)
            })
        })
    }

    private fun convertUserMessage(msg: Message): JsonObject {
        // 区分普通 user 消息和 tool_result
        return if (msg.tool_call_id != null) {
            toolResultMessage(msg.tool_call_id, msg.content ?: "")
        } else {
            simpleRoleMessage("user", msg.content ?: "")
        }
    }

    private fun convertAssistantMessage(msg: Message): JsonObject {
        if (msg.tool_calls.isNullOrEmpty()) {
            return simpleRoleMessage("assistant", msg.content ?: "")
        }
        // assistant 消息含 tool_use
        val contentBlocks = buildJsonArray {
            if (!msg.content.isNullOrBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", msg.content)
                })
            }
            for (tc in msg.tool_calls) {
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", tc.id)
                    put("name", tc.function.name)
                    // arguments 是 JSON 字符串，需解析为对象
                    val argsObj = try {
                        json.parseToJsonElement(tc.function.arguments)
                    } catch (_: Exception) {
                        buildJsonObject { }
                    }
                    put("input", argsObj)
                })
            }
        }
        return buildJsonObject {
            put("role", "assistant")
            put("content", contentBlocks)
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

    /**
     * 从工具名称和 JSON arguments 中生成人类可读的描述摘要。
     * 匹配前端的 extractToolSummary 逻辑，使 tool_use content block
     * 的 content 字段包含可读的描述（类似 cc-connect 的显示效果）。
     */
    private fun generateToolDescription(name: String, argumentsJson: String): String {
        val argsObj = try {
            json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) { return "" }

        return when {
            // bash / execute_command: 显示命令内容
            isAnyName(name, "bash", "execute_command", "ExecuteCommand") ->
                truncateWithEllipsis(argsObj["command"]?.jsonPrimitive?.content ?: "", 80)

            // Read / Write / Edit: 显示文件路径的最后部分
            isAnyName(name, "Read", "Write", "Edit") ->
                fileNameFromArgs(argsObj)

            // grep / glob: 显示搜索/匹配模式
            isAnyName(name, "grep", "glob") ->
                truncateWithEllipsis(argsObj["pattern"]?.jsonPrimitive?.content ?: "", 60)

            // web_search: 显示搜索查询
            name.equals("web_search", true) ->
                argsObj["query"]?.jsonPrimitive?.content ?: ""

            // 其它工具：尝试提取第一个有意义的字段值
            else -> firstFieldValue(argsObj)
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

// ── 工具描述纯函数辅助（无实例状态，置于文件作用域以控制类体积）──────────────

/** name 是否（忽略大小写）等于给定候选名之一。 */
private fun isAnyName(name: String, vararg candidates: String): Boolean =
    candidates.any { name.equals(it, true) }

/** 超过 max 长度时截断并追加省略号，否则原样返回（空串原样返回）。 */
private fun truncateWithEllipsis(value: String, max: Int): String =
    if (value.length > max) value.take(max) + "..." else value

/** 从 file_path/path 取文件名（最后一段）。 */
private fun fileNameFromArgs(argsObj: JsonObject): String {
    val path = argsObj["file_path"]?.jsonPrimitive?.content
        ?: argsObj["path"]?.jsonPrimitive?.content ?: ""
    if (path.isEmpty()) return ""
    val idx = path.lastIndexOf("/")
    return if (idx >= 0) path.substring(idx + 1) else path
}

/** 提取第一个字段值，过长则截断追加省略号；无值返回空串。 */
private fun firstFieldValue(argsObj: JsonObject): String {
    val firstValue = argsObj.values.firstOrNull()?.jsonPrimitive?.content
    return if (firstValue != null && firstValue.length <= 80) firstValue
    else firstValue?.take(80)?.let { "$it..." } ?: ""
}
