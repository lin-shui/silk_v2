package com.silk.backend.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.io.BufferedReader
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val STREAM_IDLE_TIMEOUT_MS = 30000L
private const val MAX_STREAM_EMPTY_LINES = 5

internal suspend fun callStreamingDiagnosisApi(
    httpClient: HttpClient,
    json: Json,
    apiKey: String,
    prompt: String,
    logger: Logger,
    onChunk: suspend (String) -> Unit
): String {
    logger.info("🌐 准备发送 API 请求...")
    logger.info("   模型: ${AIConfig.MODEL}")

    val startTime = System.currentTimeMillis()
    logger.info("🚀 发送请求时间: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(startTime))}")
    val response = executeStreamingRequest(httpClient, buildStreamingApiRequest(json, apiKey, prompt), startTime, logger)
    logStreamingResponse(startTime, response.statusCode(), logger)
    ensureStreamingSuccess(response.statusCode(), logger)
    return readStreamingResponseBody(response, json, logger, onChunk)
}

internal suspend fun streamQuickResponseLines(
    lines: List<String>,
    json: Json,
    callback: suspend (content: String, isComplete: Boolean) -> Unit
) {
    val streamState = QuickResponseStreamState()

    for (line in lines) {
        if (consumeQuickResponseLine(line, json, streamState, callback)) {
            break
        }
    }

    flushQuickResponseTail(streamState, callback)
    if (streamState.isDone) {
        callback(streamState.accumulatedContent.toString(), true)
    }
}

private fun buildStreamingApiRequest(
    json: Json,
    apiKey: String,
    prompt: String
): HttpRequest {
    val requestBody = ApiRequest(
        model = AIConfig.MODEL,
        messages = listOf(ApiMessage(role = "user", content = prompt)),
        temperature = 0.7,
        maxTokens = 65536,
        stream = true
    )

    return HttpRequest.newBuilder()
        .uri(URI.create(AIConfig.requireApiBaseUrl()))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(requestBody)))
        .timeout(Duration.ofMillis(AIConfig.TIMEOUT))
        .build()
}

private fun executeStreamingRequest(
    httpClient: HttpClient,
    request: HttpRequest,
    startTime: Long,
    logger: Logger
): HttpResponse<java.io.InputStream> = try {
    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
} catch (e: IOException) {
    val elapsed = System.currentTimeMillis() - startTime
    logger.error("❌ HTTP 请求失败 (耗时 ${elapsed}ms): ${e.message}", e)
    throw e
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    val elapsed = System.currentTimeMillis() - startTime
    logger.error("❌ HTTP 请求失败 (耗时 ${elapsed}ms): ${e.message}", e)
    throw e
}

private fun logStreamingResponse(startTime: Long, statusCode: Int, logger: Logger) {
    val requestDuration = System.currentTimeMillis() - startTime
    logger.info("✅ 收到 HTTP 响应 (耗时 ${requestDuration}ms)")
    logger.info("   状态码: $statusCode")
}

private fun ensureStreamingSuccess(statusCode: Int, logger: Logger) {
    if (statusCode == 200) {
        return
    }
    logger.error("❌ API 返回错误状态码: $statusCode")
    error("API 调用失败：$statusCode")
}

private suspend fun readStreamingResponseBody(
    response: HttpResponse<java.io.InputStream>,
    json: Json,
    logger: Logger,
    onChunk: suspend (String) -> Unit
): String {
    val streamState = StreamingReadState()

    try {
        withTimeout(AIConfig.TIMEOUT + 10000) {
            response.body().bufferedReader().use { reader ->
                while (readStreamingLine(reader, json, logger, streamState, onChunk)) {
                    delay(1)
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        logger.error("❌ 流式读取总超时（70秒），当前已接收 ${streamState.fullText.length} 字符", e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        failStreamingRead(e, logger)
    } catch (e: IllegalStateException) {
        failStreamingRead(e, logger)
    } catch (e: IllegalArgumentException) {
        failStreamingRead(e, logger)
    }

    return streamState.fullText.toString()
}

private fun failStreamingRead(error: Throwable, logger: Logger): Nothing {
    logger.error("❌ 流式读取异常: ${error.message}", error)
    throw error
}

private suspend fun readStreamingLine(
    reader: BufferedReader,
    json: Json,
    logger: Logger,
    streamState: StreamingReadState,
    onChunk: suspend (String) -> Unit
): Boolean {
    if (System.currentTimeMillis() - streamState.lastDataTime > STREAM_IDLE_TIMEOUT_MS) {
        return false
    }

    val line = readStreamingLineOrNull(reader, logger) ?: return false
    streamState.lineCount++

    if (line.trim().isEmpty()) {
        streamState.emptyLineCount++
        return streamState.emptyLineCount <= MAX_STREAM_EMPTY_LINES
    }

    streamState.emptyLineCount = 0
    if (!line.startsWith("data: ")) {
        return true
    }

    streamState.lastDataTime = System.currentTimeMillis()
    streamState.dataChunkCount++
    return consumeStreamingDataLine(line.substring(6).trim(), json, streamState, onChunk)
}

private fun readStreamingLineOrNull(reader: BufferedReader, logger: Logger): String? = try {
    reader.readLine()
} catch (e: IOException) {
    logger.warn("⚠️ 读取行失败: ${e.message}", e)
    null
}

private suspend fun consumeStreamingDataLine(
    jsonData: String,
    json: Json,
    streamState: StreamingReadState,
    onChunk: suspend (String) -> Unit
): Boolean {
    if (jsonData == "[DONE]") {
        return false
    }

    parseStreamingContent(json, jsonData)?.let { content ->
        streamState.fullText.append(content)
        onChunk(content)
    }
    return true
}

private fun parseStreamingContent(json: Json, jsonData: String): String? = try {
    val streamResponse = json.decodeFromString<StreamResponse>(jsonData)
    streamResponse.choices.firstOrNull()?.delta?.content
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private suspend fun consumeQuickResponseLine(
    line: String,
    json: Json,
    streamState: QuickResponseStreamState,
    callback: suspend (content: String, isComplete: Boolean) -> Unit
): Boolean {
    if (!line.startsWith("data: ")) {
        return false
    }

    val data = line.removePrefix("data: ").trim()
    if (data == "[DONE]") {
        streamState.isDone = true
        return true
    }

    parseQuickResponseChunk(json, data)?.let { content ->
        streamState.accumulatedContent.append(content)
        emitQuickResponseIncrementIfNeeded(streamState, callback)
    }
    return false
}

private fun parseQuickResponseChunk(json: Json, data: String): String? = try {
    val streamResponse = json.decodeFromString<StreamResponse>(data)
    streamResponse.choices.firstOrNull()?.delta?.reasoning?.takeIf { it.isNotEmpty() }
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private suspend fun emitQuickResponseIncrementIfNeeded(
    streamState: QuickResponseStreamState,
    callback: suspend (content: String, isComplete: Boolean) -> Unit
) {
    val newlineCount = streamState.accumulatedContent.count { it == '\n' }
    if (newlineCount < 3 || streamState.accumulatedContent.length <= streamState.lastSentContent.length) {
        return
    }

    val incrementalContent = streamState.accumulatedContent.substring(streamState.lastSentContent.length)
    callback(incrementalContent, false)
    streamState.lastSentContent = streamState.accumulatedContent.toString()
    delay(50)
}

private suspend fun flushQuickResponseTail(
    streamState: QuickResponseStreamState,
    callback: suspend (content: String, isComplete: Boolean) -> Unit
) {
    if (streamState.accumulatedContent.length <= streamState.lastSentContent.length) {
        return
    }

    val finalIncrement = streamState.accumulatedContent.substring(streamState.lastSentContent.length)
    if (finalIncrement.isNotEmpty()) {
        callback(finalIncrement, false)
        delay(50)
    }
}

private class StreamingReadState(
    val fullText: StringBuilder = StringBuilder(),
    var lastDataTime: Long = System.currentTimeMillis(),
    var emptyLineCount: Int = 0,
    var lineCount: Int = 0,
    var dataChunkCount: Int = 0
)

private class QuickResponseStreamState(
    val accumulatedContent: StringBuilder = StringBuilder(),
    var lastSentContent: String = "",
    var isDone: Boolean = false
)
