// backend/src/main/kotlin/com/silk/backend/ai/dsh/DshSdkClient.kt
package com.silk.backend.ai.dsh

import com.silk.backend.ai.AIConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** dsh SDK 会话事件（协议层归一化，供上层映射到 Silk 消息契约）。 */
sealed class DshEvent {
    /** 正文增量（assistant/chunk text-delta） */
    data class TextDelta(val text: String) : DshEvent()

    /** 思考增量（assistant/chunk reasoning-delta） */
    data class ThinkingDelta(val text: String) : DshEvent()

    /** 工具调用开始（tool/call） */
    data class ToolCall(val name: String?, val input: String?) : DshEvent()

    /** 工具结果（tool/result） */
    data class ToolResult(val content: String?) : DshEvent()

    /** 整条 assistant 消息（assistant/message，提交态） */
    data class AssistantMessage(val text: String) : DshEvent()

    /** turn 结束（turn/end，reason.kind） */
    data class TurnEnd(val reason: String?) : DshEvent()
}

/**
 * 解析 dsh `session.event` 通知的 (event.type, event.data) 为 [DshEvent]。
 * 纯函数，供单测覆盖；未知事件返回 null（上层忽略）。
 */
@Suppress("CyclomaticComplexMethod") // 事件分派的 when 分支多，逻辑本身为纯映射
fun parseDshEvent(type: String, data: JsonObject): DshEvent? {
    return when (type) {
        "assistant/chunk" -> {
            val chunk = data["chunk"]?.jsonObject ?: return null
            val kind = chunk["type"]?.jsonPrimitive?.contentOrNull ?: return null
            val text = chunk["text"]?.jsonPrimitive?.contentOrNull
                ?: chunk["reasoning"]?.jsonPrimitive?.contentOrNull
                ?: return null
            when (kind) {
                "text-delta" -> DshEvent.TextDelta(text)
                "reasoning-delta" -> DshEvent.ThinkingDelta(text)
                else -> null
            }
        }
        "assistant/message" -> {
            val message = data["message"]?.jsonObject ?: return null
            val text = message["content"]?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("") ?: ""
            DshEvent.AssistantMessage(text)
        }
        "tool/call" -> {
            val tool = data["tool"]?.jsonObject
                ?: data["toolCall"]?.jsonObject
                ?: data["request"]?.jsonObject
            DshEvent.ToolCall(
                name = tool?.get("name")?.jsonPrimitive?.contentOrNull,
                input = tool?.get("input")?.let { renderToolValue(it) }
                    ?: tool?.get("arguments")?.let { renderToolValue(it) },
            )
        }
        "tool/result" -> {
            DshEvent.ToolResult(
                content = data["content"]?.let { renderToolValue(it) }
                    ?: data["result"]?.let { renderToolValue(it) },
            )
        }
        "turn/end" -> {
            val reason = data["reason"]?.jsonObject?.get("kind")?.jsonPrimitive?.contentOrNull
                ?: data["reason"]?.jsonPrimitive?.contentOrNull
            DshEvent.TurnEnd(reason)
        }
        else -> null
    }
}

private fun renderToolValue(element: kotlinx.serialization.json.JsonElement): String? = when (element) {
    is kotlinx.serialization.json.JsonPrimitive -> element.contentOrNull
    else -> element.toString()
}

/**
 * 组装 runtime 启动命令：配置了 Landlock 沙箱启动器时，
 * 包装为 `python3 dsh_sandbox.py <sessionCwd> <runtimeRoot> -- <argv...>`。
 */
internal fun buildLaunchCommand(
    launchCommand: List<String>,
    sandboxScript: String?,
    sessionCwd: String,
    runtimeRoot: String,
): List<String> {
    if (sandboxScript.isNullOrBlank()) return launchCommand
    return listOf("python3", sandboxScript, sessionCwd, runtimeRoot, "--") + launchCommand
}

/**
 * DeepSeek Harness SDK JSON-RPC 客户端（stdio NDJSON）。
 *
 * 对应 dsh 的 `dsh-sdk-jsonrpc-server`：initialize / session/prompt / shutdown +
 * server→client 的 session.event / session.status 通知。
 *
 * 已知限制（dsh 0.1.0-rc）：无 per-prompt 结果（靠 session.status idle 判定 turn 结束）、
 * 无 cancel/close（停止靠杀进程）、跨进程不自动恢复 JSONL 会话。
 */
class DshSdkClient(
    private val launchCommand: List<String>,
    private val runtimeCwd: File,
    private val sessionRoot: String,
    private val sessionCwd: String,
    private val provider: String = AIConfig.DSH_PROVIDER,
    private val model: String = AIConfig.DSH_MODEL,
    private val apiKey: String = AIConfig.DEEPSEEK_API_KEY,
    private val baseUrl: String = AIConfig.DEEPSEEK_BASE_URL,
    private val promptTimeoutMs: Long = AIConfig.DSH_PROMPT_TIMEOUT_MS,
    private val sandboxScript: String = AIConfig.DSH_SANDBOX_SCRIPT,
    private val idleTimeoutMs: Long = AIConfig.DSH_IDLE_TIMEOUT_MS,
) {
    private val logger = LoggerFactory.getLogger(DshSdkClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    /** 沙箱启动器绝对路径（相对 JVM cwd 解析，runtime 工作目录可能是 harness 仓库） */
    private val sandboxLauncher: String? by lazy {
        val raw = sandboxScript.takeIf { it.isNotBlank() } ?: return@lazy null
        // cwd 可能是仓库根（backend/scripts/...）或 backend 模块目录（scripts/...）
        val candidates = listOf(
            File(raw),
            File(raw.removePrefix("backend/")),
            File("..", raw),
        )
        candidates.firstOrNull { it.isFile }?.absolutePath
            ?: run {
                logger.warn("[DshSdkClient] 找不到 dsh 沙箱脚本 {}，将不沙箱运行", raw)
                null
            }
    }

    private var process: Process? = null
    private var scope: CoroutineScope? = null
    private val pendingRpc = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val notifications = Channel<JsonObject>(Channel.UNLIMITED)
    private var nextId = 0L
    @Volatile private var started = false
    @Volatile private var closed = false
    private var idleCloseJob: Job? = null

    /** 启动 runtime 子进程并完成 initialize 握手。幂等。 */
    suspend fun start() {
        if (closed) {
            // 允许在 killProcess()/close() 之后重启（SDK 无 cancel，取消即重建进程）
            closed = false
            started = false
        }
        if (started) return
        require(launchCommand.isNotEmpty()) { "DSH_RUNTIME_CMD 未配置" }
        require(apiKey.isNotBlank()) { "DEEPSEEK_API_KEY 未配置" }

        idleCloseJob?.cancel()
        val pb = ProcessBuilder(
            buildLaunchCommand(launchCommand, sandboxLauncher, sessionCwd, runtimeCwd.absolutePath),
        )
            .directory(runtimeCwd)
            .redirectErrorStream(false)
        pb.environment()["DEEPSEEK_API_KEY"] = apiKey
        if (baseUrl.isNotBlank()) pb.environment()["DEEPSEEK_BASE_URL"] = baseUrl
        pb.environment()["DSH_SESSION_ROOT"] = sessionRoot
        pb.environment()["DSH_CWD"] = sessionCwd
        if (sandboxLauncher != null) {
            // tsx 默认在 /tmp 建磁盘缓存；Landlock 下 /tmp 不可写，禁用缓存避免启动失败
            pb.environment()["TSX_DISABLE_CACHE"] = "1"
        }

        val proc = pb.start()
        process = proc
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = jobScope
        jobScope.launch { readLoop(proc) }
        jobScope.launch { logStderr(proc) }
        started = true

        rpc(
            "initialize",
            buildJsonObject {
                put("cwd", sessionCwd)
                put("provider", provider)
                put("model", model)
            },
        )
        logger.info("[DshSdkClient] runtime ready: cwd={}, provider={}, model={}", sessionCwd, provider, model)
    }

    /**
     * 向指定 session 发送 prompt，消费事件直到该 session idle，返回累计正文。
     * @param onEvent 每收到一个可识别事件回调一次（thinking/tool/text/turn 等）。
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // 单轮事件循环：事件分派 + idle 判定
    suspend fun prompt(
        sessionId: String,
        text: String,
        onEvent: suspend (DshEvent) -> Unit = {},
    ): String {
        start()
        idleCloseJob?.cancel()
        rpc(
            "session/prompt",
            buildJsonObject {
                put("sessionId", sessionId)
                put(
                    "contentBlocks",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", text) })
                    },
                )
            },
        )

        try {
            val accumulated = StringBuilder()
            var lastAssistantText: String? = null
            withTimeout(promptTimeoutMs) {
                while (true) {
                    val frame = notifications.receive()
                    val method = frame["method"]?.jsonPrimitive?.contentOrNull ?: continue
                    val params = frame["params"]?.jsonObject ?: continue
                    val sid = params["sessionId"]?.jsonPrimitive?.contentOrNull
                    when (method) {
                        "session.event" -> {
                            if (sid != sessionId) continue
                            val eventObj = params["event"]?.jsonObject ?: continue
                            val type = eventObj["type"]?.jsonPrimitive?.contentOrNull ?: continue
                            val data = eventObj["data"]?.jsonObject ?: JsonObject(emptyMap())
                            val event = parseDshEvent(type, data) ?: continue
                            when (event) {
                                is DshEvent.TextDelta -> accumulated.append(event.text)
                                is DshEvent.AssistantMessage -> lastAssistantText = event.text
                                else -> {}
                            }
                            onEvent(event)
                        }
                        "session.status" -> {
                            if (sid == sessionId && params["status"]?.jsonPrimitive?.contentOrNull == "idle") {
                                break
                            }
                        }
                        else -> {}
                    }
                }
            }
            return if (accumulated.isNotEmpty()) accumulated.toString() else lastAssistantText.orEmpty()
        } catch (e: CancellationException) {
            // SDK 无 per-prompt cancel：调用方取消（STOP_GENERATE）或超时都只能杀进程，
            // 下次 prompt 会重建 runtime（Silk 每次都会喂完整历史，上下文不依赖 dsh 会话）。
            killProcess()
            closed = false
            throw e
        } finally {
            if (!closed) scheduleIdleClose()
        }
    }

    /** 优雅关闭：shutdown → 取消协程 → 杀进程。幂等。 */
    suspend fun close() {
        if (closed) return
        closed = true
        idleCloseJob?.cancel()
        runCatching { rpc("shutdown", buildJsonObject {}) }
        killProcess()
    }

    /** 仅供测试：runtime 子进程是否存活。 */
    internal fun runtimeAlive(): Boolean = process?.isAlive == true

    /** 终止 runtime 进程并复位，允许下次 prompt 重启。 */
    private suspend fun killProcess() {
        idleCloseJob?.cancel()
        scope?.cancel()
        process?.destroy()
        withContext(Dispatchers.IO) { process?.waitFor(2, TimeUnit.SECONDS) }
        process?.destroyForcibly()
        process = null
        scope = null
        pendingRpc.clear()
        while (notifications.tryReceive().isSuccess) {
            // 清空旧进程残留通知，避免重启后串台
        }
        started = false
    }

    /** 单轮结束后启动空闲回收：超过 idleTimeoutMs 无新 prompt 则关闭 runtime。 */
    private fun scheduleIdleClose() {
        val s = scope ?: return
        idleCloseJob = s.launch {
            delay(idleTimeoutMs)
            close()
        }
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val id = ++nextId
        val future = CompletableFuture<JsonObject>()
        pendingRpc[id] = future
        sendFrame(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString(),
        )
        return withContext(Dispatchers.IO) {
            future.get(30, TimeUnit.SECONDS)
        }.also {
            it["error"]?.let { err ->
                throw IllegalStateException("dsh RPC $method 失败: $err")
            }
        }
    }

    private fun sendFrame(line: String) {
        val proc = process ?: error("dsh runtime 未启动")
        synchronized(this) {
            proc.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
        }
    }

    @Suppress("LoopWithTooManyJumpStatements", "TooGenericExceptionCaught")
    private suspend fun readLoop(proc: Process) {
        val reader: BufferedReader = proc.inputStream.bufferedReader(Charsets.UTF_8)
        try {
            for (line in reader.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: continue
                val id = obj["id"]?.jsonPrimitive?.longOrNull
                if (id != null) {
                    pendingRpc.remove(id)?.complete(obj)
                } else {
                    notifications.send(obj)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("[DshSdkClient] stdout 读取结束: {}", e.message)
        } finally {
            // 进程退出后唤醒所有 pending RPC，避免悬挂
            pendingRpc.keys.toList().forEach { pendingRpc.remove(it)?.completeExceptionally(IllegalStateException("dsh runtime 已退出")) }
        }
    }

    private suspend fun logStderr(proc: Process) {
        proc.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            logger.debug("[DshSdkClient] runtime stderr: {}", line)
        }
    }
}
