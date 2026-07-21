package com.silk.backend.ai

import com.silk.backend.EnvLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Claude CLI 进程客户端。
 * 通过 PTY（伪终端）启动 claude CLI 子进程。
 *
 * pty_chat.py 使用 claude -p --include-partial-messages，
 * 让 claude 逐 token 写入 PTY，实现真正的实时流式。
 */
@Suppress("TooGenericExceptionCaught")
class ClaudeProcessClient(
    /** 群组标识（如 group_<uuid>），用于日志/workspace 路径 */
    private val groupId: String,
    /** 进程工作目录（群组隔离） */
    private val workspaceDir: String,
) {
    private val logger = LoggerFactory.getLogger(ClaudeProcessClient::class.java)

    /** 从 ~/.claude/settings.json 读取环境变量注入子进程 */
    private val claudeEnvVars: Map<String, String> by lazy {
        val settingsFile = java.io.File(System.getProperty("user.home"), ".claude/settings.json")
        if (!settingsFile.exists()) return@lazy emptyMap()
        try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(settingsFile.readText().replace(Regex(",\\s*}"), "}")).jsonObject
            val envObj = root["env"]?.jsonObject ?: return@lazy emptyMap()
            envObj.mapValues { (_, value) -> value.jsonPrimitive.content }
        } catch (e: Exception) {
            logger.warn("Failed to read claude settings.json: ${e.message}")
            emptyMap()
        }
    }

    private val claudePath = AIConfig.CLAUDE_CLI_PATH

    /** PTY 桥接脚本路径 */
    private val ptyChatPath: String by lazy {
        resolvePtyChatPath()
    }

    /**
     * 从 CWD 向上查找 cc_bridge/pty_chat.py。
     */
    private fun resolvePtyChatPath(): String {
        // 优先找 backend/scripts/pty_chat.py（新标准位置），再找 cc_bridge/pty_chat.py（旧位置）
        val targets = listOf("backend/scripts/pty_chat.py", "cc_bridge/pty_chat.py")
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            for (target in targets) {
                val candidate = File(dir, target)
                if (candidate.exists()) return candidate.absolutePath
            }
            dir = dir.parentFile ?: break
        }
        // Fallback: 从 workspace 相对路径找
        for (target in targets) {
            val fallback = File(workspaceDir, "../../../$target").absoluteFile
            if (fallback.exists()) return fallback.absolutePath
        }
        // 兜底
        return File("backend/scripts/pty_chat.py").absolutePath
    }

    /** 单次响应超时 */
    private val responseTimeoutMs = AIConfig.CLAUDE_CLI_RESPONSE_TIMEOUT_MS

    /** 每批发送的字符数阈值 */
    private val sendThreshold = 30

    /**
     * 发送 prompt 到 claude CLI 并流式读取回复。
     *
     * @param fullPrompt 包含 system prompt + 对话历史 + 当前消息的完整 prompt
     * @param callback 流式回调 (stepType, content, isComplete)
     * @return 完整回复文本
     */
    suspend fun streamCompletion(
        fullPrompt: String,
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit,
    ): String {
        val callId = UUID.randomUUID().toString().take(8)

        // 将 prompt 写入 workspaceDir 内的临时文件
        // 必须在 workspace 内——pty_chat.py 会用 Landlock 将子进程限制在该目录
        val workspaceFile = java.io.File(workspaceDir).also { it.mkdirs() }
        val promptFile = java.io.File(workspaceFile, ".prompt.md").apply {
            writeText(fullPrompt)
        }

        // 传 absWorkspaceDir 给 pty_chat.py 做 Landlock 沙箱根目录
        // 使用绝对路径避免子进程 CWD 变化后路径不可达
        val absWorkspaceDir = workspaceFile.absolutePath
        val cmd = buildList {
            add("python3")
            add(ptyChatPath)
            if (claudePath != "claude") {
                add("--claude-path")
                add(claudePath)
            }
            add(promptFile.absolutePath)
            add(absWorkspaceDir)
        }

        logger.info("[ClaudeProcessClient-{}] 启动 claude PTY: groupId={}, promptLen={}, script={}",
            callId, groupId, fullPrompt.length, ptyChatPath)

        return withContext(Dispatchers.IO) {
            val env = buildClaudeProcessEnv()
            val processBuilder = ProcessBuilder(cmd)
                .directory(File(absWorkspaceDir))
                .redirectErrorStream(false)
            processBuilder.environment().putAll(env)

            val process = processBuilder.start()

            try {
                coroutineScope {
                    // 读取 stderr（Landlock 警告等），仅打日志不流入聊天
                    val stderrLogger = launch {
                        try {
                            process.errorStream.bufferedReader().forEachLine { line ->
                                if (line.isNotBlank()) {
                                    logger.info("[ClaudeProcessClient-{}] stderr: {}", callId, line.trim())
                                }
                            }
                        } catch (_: java.io.IOException) { /* process destroyed */ }
                    }

                    // 看门狗：超时强行关闭
                    val watchdog = launch {
                        delay(responseTimeoutMs)
                        logger.warn("[ClaudeProcessClient-{}] 响应超时 ({}ms)，强制关闭进程", callId, responseTimeoutMs)
                        process.destroyForcibly()
                    }

                    try {
                        val result = streamProcessOutput(process, callback)

                        val exitCode = process.waitFor()

                        if (exitCode == 0) {
                            logger.info("[ClaudeProcessClient-{}] 完成: groupId={}, chars={}", callId, groupId, result.length)
                        } else {
                            logger.warn("[ClaudeProcessClient-{}] 退出码非零: exitCode={}, groupId={}, output={}",
                                callId, exitCode, groupId, result.take(500))
                        }

                        result
                    } finally {
                        watchdog.cancel()
                        stderrLogger.cancel()
                    }
                }
            } catch (e: CancellationException) {
                process.destroyForcibly()
                throw e
            } catch (e: Exception) {
                process.destroyForcibly()
                logger.error("[ClaudeProcessClient-{}] 异常: {}", callId, e.message)
                throw e
            } finally {
                promptFile.delete()
            }
        }
    }

    /**
     * 构建 claude PTY 子进程的环境变量（与原内联逻辑等价）。
     */
    private fun buildClaudeProcessEnv(): MutableMap<String, String> {
        val env = mutableMapOf("PYTHONUNBUFFERED" to "1")
        // Inject claude env vars from ~/.claude/settings.json (claude -p doesn't auto-read them)
        env.putAll(claudeEnvVars)
        // Defense-in-depth: inject strict settings path so pty_chat.py
        // always enforces --settings regardless of wrapper bypass.
        val strictSettingsFile = java.io.File(
            System.getProperty("user.home"), ".claude/settings-strict.json"
        )
        if (strictSettingsFile.exists()) {
            env["CLAUDE_STRICT_SETTINGS"] = strictSettingsFile.absolutePath
        }
        // 注入代理环境变量，确保 claude CLI 子进程能访问 Anthropic API
        listOf("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy",
               "CLAUDE_HTTPS_PROXY", "CLAUDE_HTTP_PROXY", "NO_PROXY", "no_proxy").forEach { key ->
            (System.getenv(key) ?: EnvLoader.get(key))?.takeIf { it.isNotBlank() }?.let { env[key] = it }
        }
        return env
    }

    /**
     * 读取子进程 stdout，按阈值流式回调增量文本，返回累计的完整文本。
     * 与原内联读取循环等价：清理 ANSI/回车，处理跨 read 边界的不完整 UTF-8 尾字节，
     * 累计长度达到 sendThreshold 时回调增量，结束后回调剩余文本。
     */
    private suspend fun streamProcessOutput(
        process: Process,
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit,
    ): String {
        val output = StringBuilder()
        var lastSentLength = 0
        var overflow = ByteArray(0)
        var lastReceiveTime = System.currentTimeMillis()
        var hasToolMarker = false

        // 使用非阻塞读取 + 超时检测，避免 tool 调用后无后续输出导致永久挂起
        val inputStream = process.inputStream
        val timeoutAfterToolMs = 30_000L  // 30 秒无新输出则主动断开（防止 Claude 进程永久挂起）
        var shouldStop = false

        while (!shouldStop) {
            // 带超时的非阻塞读取
            val avail = try {
                inputStream.available()
            } catch (e: java.io.IOException) {
                logger.warn("[ClaudeProcessClient] 检查 available 异常: {}", e.message)
                break
            }

            if (avail > 0) {
                val readBuf = ByteArray(minOf(avail, 4096))
                val bytesRead = try {
                    inputStream.read(readBuf)
                } catch (e: java.io.IOException) {
                    logger.warn("[ClaudeProcessClient] 读取流异常: {}", e.message)
                    break
                }
                if (bytesRead == -1) break
                currentCoroutineContext().ensureActive()
                lastReceiveTime = System.currentTimeMillis()

                val merged = if (overflow.isNotEmpty()) {
                    ByteArray(overflow.size + bytesRead).apply {
                        overflow.copyInto(this)
                        readBuf.copyInto(this, overflow.size, 0, bytesRead)
                    }
                } else {
                    readBuf.copyOfRange(0, bytesRead)
                }

                val decoded = merged.decodeToString()
                overflow = captureTrailingPartial(decoded, merged)

                val cleaned = decoded
                    .replace("\r", "")
                    .replace(Regex("\\e\\[[\\d;?<>]*[a-zA-Z]"), "")
                    .replace(Regex("\\e\\][^\\e\\x07]*[\\e\\x07]"), "")
                    .replace(Regex("\\e[()][a-zA-Z]"), "")

                if (cleaned.isNotBlank()) {
                    output.append(cleaned)
                    // 检测 tool 标记
                    if (!hasToolMarker && (cleaned.contains("<!--TOOL") || cleaned.contains("<!--END_TOOL-->"))) {
                        hasToolMarker = true
                    }

                    if (output.length - lastSentLength >= sendThreshold) {
                        val delta = output.substring(lastSentLength)
                        lastSentLength = output.length
                        callback("streaming_incremental", delta, false)
                    }
                }
            } else {
                // 无数据可用：检查空闲超时（超过 60 秒无输出则断开，避免进程永久挂起）
                val idle = System.currentTimeMillis() - lastReceiveTime
                if (idle > timeoutAfterToolMs) {
                    logger.warn("[ClaudeProcessClient] {}ms 无新输出，主动断开 (hasToolMarker={})", idle, hasToolMarker)
                    shouldStop = true
                }
                // 短暂休眠避免 busy-wait
                if (!shouldStop) {
                    kotlinx.coroutines.delay(500)
                }
            }
        }

        // 发送剩余文本
        if (output.length > lastSentLength) {
            val remaining = output.substring(lastSentLength)
            if (remaining.isNotBlank()) {
                callback("streaming_incremental", remaining, false)
            }
        }

        return output.toString()
    }

    /**
     * 检查解码后的字符串末尾是否有因为跨 read 边界截断而导致的不完整 UTF-8 序列。
     * 如有，从原始字节中提取不完整尾部字节返回；否则返回空数组。
     */
    private fun captureTrailingPartial(decoded: String, raw: ByteArray): ByteArray {
        if (!decoded.endsWith('�')) return ByteArray(0)
        var i = raw.size - 1
        while (i >= 0 && (raw[i].toInt() and 0xC0) == 0x80) {
            i--
        }
        if (i < 0 || (raw[i].toInt() and 0xC0) != 0xC0) return ByteArray(0)
        return raw.copyOfRange(i, raw.size)
    }
}
