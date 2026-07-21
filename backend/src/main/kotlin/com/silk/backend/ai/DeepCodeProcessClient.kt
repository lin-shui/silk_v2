package com.silk.backend.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.UUID

/**
 * deepcode-cli 进程客户端。
 *
 * 调用 `deepcode --headless --prompt "..."` 以非交互模式执行。
 *
 * deepcode stdout 协议（我们在 deepcode-cli headless 模式中扩展的输出格式）：
 *   - `<!--THINKING-->` ... `<!--END_THINKING-->`  → 模型思考内容
 *   - `<!--TOOL name="WebSearch"-->` ... `<!--END_TOOL-->` → 工具调用与结果
 *   - 其他为正文（流式增量回调给前端）
 *
 * 转换为 Silk 统一 callback（与 AnthropicClient 一致的结构化 block 协议）：
 *   - "blocks_state"          → 思考/工具/正文的结构化 block 列表（前端折叠展示）
 *   - "streaming_incremental" → 正文流式增量
 *
 * block 索引规则（保证按出现时间递增）：
 *   - 每次 NORMAL 段开始时分配新 text block（首次或从 thinking/tool 切回）
 *   - THINKING 标记：分配新 thinking block
 *   - TOOL 标记：分配新 tool_use block
 *   - END_*：当前 block 标记为完成
 *   - 前端按 index 排序即得到真实时间顺序（思考/工具/正文交错时的正确位置）
 */
@Suppress("TooGenericExceptionCaught")
class DeepCodeProcessClient(
    private val groupId: String,
) {
    private val logger = LoggerFactory.getLogger(DeepCodeProcessClient::class.java)

    /** 标记解析状态 */
    private enum class ParseState { NORMAL, IN_THINKING, IN_TOOL }

    /** 累计正文达到此字符数时触发一次增量回调 */
    private companion object {
        const val SEND_THRESHOLD = 12
        val MARKER_PATTERN = Regex("""<!--(THINKING|END_THINKING|TOOL[^>]*|END_TOOL)-->""")
        val SKILL_NAMES_NOISE = Regex("""\{"skillNames"\s*:\s*\[[^\]]*\]\s*\}""")

        /** 过滤 deepcode 内部 JSON 噪声，例如 `{"skillNames": []}` 是 deepcode 的 skill
         *  匹配系统的内部输出，对用户不可见，应当剔除。 */
        fun stripInternalNoise(text: String): String =
            SKILL_NAMES_NOISE.replace(text, "")
    }

    suspend fun streamCompletion(
        fullPrompt: String,
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit,
    ): String {
        val callId = UUID.randomUUID().toString().take(8)
        val deepcodePath = AIConfig.DEEPCODE_CLI_PATH
        val timeoutMs = AIConfig.DEEPCODE_CLI_TIMEOUT_MS
        val cmd = listOf("stdbuf", "-oL", deepcodePath, "--headless", "--prompt", fullPrompt)

        logger.info(
            "[DeepCodeProcessClient-{}] 启动 deepcode: groupId={}, promptLen={}, binary={}",
            callId, groupId, fullPrompt.length, deepcodePath,
        )

        return withContext(Dispatchers.IO) {
            val processBuilder = ProcessBuilder(cmd).redirectErrorStream(false)
            processBuilder.environment().putAll(System.getenv())
            val process = processBuilder.start()

            try {
                coroutineScope {
                    // stderr 只做日志
                    val stderrHandler = launch {
                        try {
                            process.errorStream.bufferedReader().forEachLine { line ->
                                val t = line.trim()
                                if (t.isNotBlank()) {
                                    logger.info("[DeepCodeProcessClient-{}] stderr: {}", callId, t)
                                }
                            }
                        } catch (_: IOException) {
                            // stream closed is fine
                        }
                    }

                    // 总超时 watchdog
                    val watchdog = launch {
                        delay(timeoutMs)
                        logger.warn("[DeepCodeProcessClient-{}] 响应超时 ({}ms)，强制关闭进程", callId, timeoutMs)
                        process.destroyForcibly()
                    }

                    try {
                        val result = streamReadOutput(process, callback, callId)
                        val exitCode = process.waitFor()
                        if (exitCode == 0) {
                            logger.info(
                                "[DeepCodeProcessClient-{}] 完成: groupId={}, chars={}",
                                callId, groupId, result.length,
                            )
                        } else {
                            logger.warn(
                                "[DeepCodeProcessClient-{}] 退出码非零: exitCode={}, groupId={}",
                                callId, exitCode, groupId,
                            )
                        }
                        result
                    } finally {
                        watchdog.cancel()
                        stderrHandler.cancel()
                    }
                }
            } catch (e: CancellationException) {
                process.destroyForcibly()
                throw e
            } catch (e: Exception) {
                process.destroyForcibly()
                logger.error("[DeepCodeProcessClient-{}] 异常: {}", callId, e.message)
                throw e
            }
        }
    }

    /**
     * 阻塞式逐段读取 stdout。每读到一段就交给 [StreamState.feed] 解析并发出 callback。
     *
     * 不使用 `available()` + `isAlive` 的轮询模式（race condition：进程退出时管道里
     * 可能仍有未读数据）。改为：阻塞 `read()` 直到返回 -1（EOF）。
     */
    private suspend fun streamReadOutput(
        process: Process,
        callback: suspend (String, String, Boolean) -> Unit,
        callId: String,
    ): String {
        val state = StreamState(callback)
        val buf = ByteArray(4096)
        val stream = process.inputStream
        var reading = true

        while (reading) {
            val bytesRead = try {
                stream.read(buf)
            } catch (e: IOException) {
                logger.warn("[DeepCodeProcessClient-{}] 读取流异常: {}", callId, e.message)
                null
            }
            reading = bytesRead != null && bytesRead >= 0
            if (bytesRead != null && bytesRead > 0) {
                state.feed(buf.decodeToString(0, bytesRead))
            }
        }
        state.flushRemaining()
        return state.textOutput.toString()
    }

    /**
     * 解析状态机：累积原始字节、识别标记、维护结构化 block 列表并通过 callback emit。
     *
     * 解析规则见类顶部文档。`feed` 可被多次调用，每次传入一段新到达的 stdout chunk。
     */
    private class StreamState(
        private val callback: suspend (String, String, Boolean) -> Unit,
    ) {
        val textOutput = StringBuilder()
        private val rawBuf = StringBuilder()
        private var parseState = ParseState.NORMAL
        private var lastSentLength = 0

        // 结构化 block 跟踪
        private val streamingBlocks = mutableMapOf<Int, ContentBlock>()
        private var nextBlockIndex = 0
        private var currentTextIndex = -1
        private var currentThinkingIndex = -1
        private var currentToolIndex = -1

        suspend fun feed(chunk: String) {
            rawBuf.append(chunk)
            parseBuffer()
            emitTextIncrementals()
        }

        /** 流结束时调用：处理 rawBuf 中残留的不完整标记，emit 最终 block 状态 */
        suspend fun flushRemaining() {
            parseBuffer()
            if (textOutput.length > lastSentLength) {
                val remaining = textOutput.substring(lastSentLength)
                if (remaining.isNotBlank()) {
                    callback("streaming_incremental", remaining, false)
                }
                lastSentLength = textOutput.length
            }
            streamingBlocks.forEach { (idx, block) ->
                streamingBlocks[idx] = block.copy(isComplete = true)
            }
            emitBlocksState()
        }

        /** 流式正文增量回调（每累计 SEND_THRESHOLD 字符触发一次） */
        private suspend fun emitTextIncrementals() {
            while (textOutput.length - lastSentLength >= SEND_THRESHOLD) {
                val delta = textOutput.substring(lastSentLength, lastSentLength + SEND_THRESHOLD)
                lastSentLength += SEND_THRESHOLD
                if (delta.isNotBlank()) {
                    callback("streaming_incremental", delta, false)
                }
            }
        }

        /**
         * 从 rawBuf 提取标记或文本段，按状态分发到 appendXxx。
         * 处理完后清空 rawBuf，但保留末尾可能不完整的标记前缀（如 `<!--TH`）。
         */
        private suspend fun parseBuffer() {
            if (rawBuf.isEmpty()) return
            val str = rawBuf.toString()
            var pos = 0

            while (true) {
                val match = MARKER_PATTERN.find(str, pos)
                if (match == null) {
                    dispatchText(str.substring(pos))
                    break
                }
                dispatchText(str.substring(pos, match.range.first))
                applyMarker(match.groupValues[1])
                pos = match.range.last + 1
            }

            // 保留末尾不完整的标记前缀
            rawBuf.clear()
            val lastOpen = str.lastIndexOf("<!--")
            val lastClose = str.lastIndexOf("-->")
            if (lastOpen >= 0 && lastOpen > lastClose) {
                rawBuf.append(str.substring(lastOpen))
            }
        }

        /** 根据当前 parseState 把一段文本追加到对应的 buffer */
        private suspend fun dispatchText(text: String) {
            if (text.isEmpty()) return
            when (parseState) {
                ParseState.NORMAL -> appendText(text)
                ParseState.IN_THINKING -> appendToBlock(currentThinkingIndex, text)
                ParseState.IN_TOOL -> appendToBlock(currentToolIndex, text)
            }
        }

        /** 应用一个标记，可能切换 parseState 并创建/关闭 block */
        private suspend fun applyMarker(marker: String) {
            when {
                marker == "THINKING" && parseState == ParseState.NORMAL ->
                    startThinkingBlock()
                marker.startsWith("TOOL") && parseState == ParseState.NORMAL ->
                    startToolBlock(extractToolName(marker))
                marker == "END_THINKING" && parseState == ParseState.IN_THINKING ->
                    endBlock(currentThinkingIndex).also { currentThinkingIndex = -1; parseState = ParseState.NORMAL }
                marker == "END_TOOL" && parseState == ParseState.IN_TOOL ->
                    endBlock(currentToolIndex).also { currentToolIndex = -1; parseState = ParseState.NORMAL }
                // 其他不匹配的标记忽略
            }
        }

        private fun extractToolName(marker: String): String {
            val nameMatch = Regex("""name="([^"]*)"""").find(marker)
            return nameMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "tool"
        }

        private fun closeCurrentTextBlock() {
            if (currentTextIndex >= 0) {
                streamingBlocks[currentTextIndex] =
                    streamingBlocks[currentTextIndex]!!.copy(isComplete = true)
                currentTextIndex = -1
            }
        }

        private suspend fun startThinkingBlock() {
            parseState = ParseState.IN_THINKING
            closeCurrentTextBlock()
            val idx = nextBlockIndex++
            currentThinkingIndex = idx
            streamingBlocks[idx] = ContentBlock(
                index = idx, type = "thinking",
                content = "", isComplete = false,
            )
            emitBlocksState()
        }

        private suspend fun startToolBlock(name: String) {
            parseState = ParseState.IN_TOOL
            closeCurrentTextBlock()
            val idx = nextBlockIndex++
            currentToolIndex = idx
            streamingBlocks[idx] = ContentBlock(
                index = idx, type = "tool_use",
                content = "", isComplete = false,
                toolName = name, toolId = "deepcode_$idx",
            )
            emitBlocksState()
        }

        private suspend fun endBlock(idx: Int) {
            if (idx >= 0) {
                streamingBlocks[idx] = streamingBlocks[idx]!!.copy(isComplete = true)
                emitBlocksState()
            }
        }

        private suspend fun appendText(text: String) {
            val cleaned = DeepCodeProcessClient.stripInternalNoise(text)
            if (cleaned.isEmpty()) return
            textOutput.append(cleaned)
            if (currentTextIndex < 0) {
                val idx = nextBlockIndex++
                currentTextIndex = idx
                streamingBlocks[idx] = ContentBlock(
                    index = idx, type = "text",
                    content = "", isComplete = false,
                )
            }
            appendToBlock(currentTextIndex, cleaned)
        }

        private suspend fun appendToBlock(idx: Int, text: String) {
            if (idx < 0) return
            val block = streamingBlocks[idx] ?: return
            streamingBlocks[idx] = block.copy(content = block.content + text)
            emitBlocksState()
        }

        private suspend fun emitBlocksState() {
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
    }
}
