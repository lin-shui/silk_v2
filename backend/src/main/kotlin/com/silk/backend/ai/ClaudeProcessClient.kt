package com.silk.backend.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Claude CLI 进程客户端。
 * 通过 PTY（伪终端）启动 claude CLI 子进程，确保输出是行缓冲的流式文本。
 *
 * 因为 claude CLI（Node.js）在 stdout 是管道（pipe）时会启用块缓冲，
 * 导致整个响应一次性输出。PTY 让 claude CLI 认为自己在终端中运行，
 * 从而获得实时的行缓冲输出。
 *
 * 实现方式：通过 `pty_chat.py` Python 脚本创建 PTY 并在其中运行 claude。
 *
 * 每次调用生成唯一 [sessionId]（UUID），claude 不持久化跨轮会话；
 * 上下文连续性由调用方（[DirectModelAgent]）在 prompt 中构建完整对话历史实现。
 */
class ClaudeProcessClient(
    /** 群组标识（如 group_<uuid>），用于日志/workspace 路径 */
    private val groupId: String,
    /** 进程工作目录（群组隔离） */
    private val workspaceDir: String,
) {
    private val logger = LoggerFactory.getLogger(ClaudeProcessClient::class.java)

    private val claudePath = AIConfig.CLAUDE_CLI_PATH

    /** PTY 桥接脚本路径 */
    private val ptyChatPath: String by lazy {
        resolvePtyChatPath()
    }

    /**
     * 从 CWD 向上查找 cc_bridge/pty_chat.py。
     * Gradle :backend:run 的 CWD 是 backend/，直接启动的 CWD 是项目根目录。
     * 向上查找可适配两种启动方式。
     */
    private fun resolvePtyChatPath(): String {
        val target = "cc_bridge/pty_chat.py"
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            val candidate = File(dir, target)
            if (candidate.exists()) return candidate.absolutePath
            dir = dir.parentFile ?: break
        }
        return File(workspaceDir, "../../../cc_bridge/pty_chat.py").absoluteFile.absolutePath
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

        // 将 prompt 写入临时文件（避免 shell 转义和命令行长度限制）
        val promptFile = File.createTempFile("silk-prompt-", ".txt").apply {
            writeText(fullPrompt)
            deleteOnExit()
        }

        // 通过 PTY 桥接脚本运行 claude
        val cmd = buildList {
            add("python3")
            add(ptyChatPath)
            add(promptFile.absolutePath)
        }

        logger.info("[ClaudeProcessClient-{}] 启动 claude PTY: groupId={}, promptLen={}, script={}",
            callId, groupId, fullPrompt.length, ptyChatPath)

        return withContext(Dispatchers.IO) {
            val env = mapOf("PYTHONUNBUFFERED" to "1")
            val processBuilder = ProcessBuilder(cmd)
                .directory(File(workspaceDir))
                .redirectErrorStream(true)
            processBuilder.environment().putAll(env)

            val process = processBuilder.start()

            try {
                coroutineScope {
                    // 看门狗：超时强行关闭
                    val watchdog = launch {
                        delay(responseTimeoutMs)
                        logger.warn("[ClaudeProcessClient-{}] 响应超时 ({}ms)，强制关闭进程", callId, responseTimeoutMs)
                        process.destroyForcibly()
                    }

                    try {
                        val output = StringBuilder()
                        var lastSentLength = 0
                        // 用于跨 read 边界保留不完整 UTF-8 尾部的溢出字节
                        var overflow = ByteArray(0)

                        // 使用 InputStream.read(byte[]) 直接读取 pipe，
                        // 避免 InputStreamReader / BufferedReader 内部的大缓冲区
                        // 将 pipe 数据一次性吞掉导致无流式效果。
                        val buf = ByteArray(256)

                        while (true) {
                            val bytesRead = process.inputStream.read(buf)
                            if (bytesRead == -1) break
                            ensureActive()

                            // 合并且解码（lenient UTF-8：跨边界截断的字符以 � 替代）
                            val merged = if (overflow.isNotEmpty()) {
                                ByteArray(overflow.size + bytesRead).apply {
                                    overflow.copyInto(this)
                                    buf.copyInto(this, overflow.size, 0, bytesRead)
                                }
                            } else {
                                buf.copyOfRange(0, bytesRead)
                            }

                            val decoded = merged.decodeToString()
                            // 检查末尾是否有不完整的 UTF-8 序列，保留到下次
                            overflow = captureTrailingPartial(decoded, merged)

                            val cleaned = decoded
                                .replace("\r", "")
                                // CSI: \e[<params><letter>（含 ? / < / > 等私有标记）
                                .replace(Regex("\\e\\[[\\d;?<>]*[a-zA-Z]"), "")
                                // OSC: \e]<string>(\e\|\x07)
                                .replace(Regex("\\e\\][^\\e\\x07]*[\\e\\x07]"), "")
                                // 字符集切换
                                .replace(Regex("\\e[()][a-zA-Z]"), "")

                            if (cleaned.isNotBlank()) {
                                output.append(cleaned)

                                if (output.length - lastSentLength >= sendThreshold) {
                                    val delta = output.substring(lastSentLength)
                                    lastSentLength = output.length
                                    callback("streaming_incremental", delta, false)
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

                        val exitCode = process.waitFor()
                        val result = output.toString()

                        if (exitCode == 0) {
                            logger.info("[ClaudeProcessClient-{}] 完成: groupId={}, chars={}", callId, groupId, result.length)
                        } else {
                            logger.warn("[ClaudeProcessClient-{}] 退出码非零: exitCode={}, groupId={}, output={}",
                                callId, exitCode, groupId, result.take(500))
                        }

                        result
                    } finally {
                        watchdog.cancel()
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
                // 清理临时文件
                promptFile.delete()
            }
        }
    }

    /**
     * 检查解码后的字符串末尾是否有因为跨 read 边界截断而导致的不完整 UTF-8 序列。
     * 如有，从原始字节中提取不完整尾部字节返回；否则返回空数组。
     *
     * 原理：当 [decoded] 末尾出现 �（REPLACEMENT CHARACTER）时，
     * 说明 [raw] 末尾有不完整多字节序列。扫描 raw 尾部找到第一个不完整序列的起始位置。
     */
    private fun captureTrailingPartial(decoded: String, raw: ByteArray): ByteArray {
        if (!decoded.endsWith('�')) return ByteArray(0)
        // 从 raw 末尾向前扫描，找到不完整 UTF-8 序列的起始字节
        var i = raw.size - 1
        while (i >= 0 && (raw[i].toInt() and 0xC0) == 0x80) {
            i--
        }
        if (i < 0 || (raw[i].toInt() and 0xC0) != 0xC0) return ByteArray(0)
        return raw.copyOfRange(i, raw.size)
    }
}
