package com.silk.backend.ccconnect

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class CcConnectConnectionMeta(
    val groupId: String,
    val project: String,
    val agentType: String,
    val cwd: String = "",
    val mode: String? = null,
    val model: String? = null,
    val availableModes: List<CcModeOption>? = null,
    val availableModels: List<CcModelOption>? = null,
)

data class CcConnectConnection(
    val session: WebSocketSession,
    val meta: CcConnectConnectionMeta,
)

/**
 * Maps raw cc-connect agent_type values to short trigger names used as @-prefix in chat.
 * E.g. "claudecode" → "claude", "gemini-cli" → "gemini".
 * Unknown types are returned as-is (lowercased).
 */
fun agentTriggerName(agentType: String): String {
    val lower = agentType.lowercase().trim()
    return when {
        lower.startsWith("claude") -> "claude"
        lower.startsWith("cursor") -> "cursor"
        lower.startsWith("gemini") -> "gemini"
        lower.startsWith("codex")  -> "codex"
        lower.startsWith("copilot") -> "copilot"
        lower.isBlank() -> "cc"
        else -> lower
    }
}

@Suppress("TooGenericExceptionCaught", "SwallowedException")
object CcConnectRegistry {

    private val logger = LoggerFactory.getLogger(CcConnectRegistry::class.java)
    private val connections = ConcurrentHashMap<String, CcConnectConnection>()

    // ── 请求中/排队状态追踪 ──
    // Claude Code CLI 不支持并发请求，必须等当前请求完成后再发下一个。
    private val processing = ConcurrentHashMap<String, Boolean>()
    private val processingTimestamps = ConcurrentHashMap<String, Long>()
    private val pendingQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<UserMessage>>()

    /** 处理中超时阈值（毫秒），超过此时间未收到响应则自动清除忙碌状态. */
    private const val PROCESSING_TIMEOUT_MS = 600_000L // 10 分钟

    // ── 等待用户输入状态（AI 提问后保持会话） ──
    private val waitingForInput = ConcurrentHashMap<String, Boolean>()
    private val waitingForInputTimestamps = ConcurrentHashMap<String, Long>()
    private const val WAITING_TIMEOUT_MS = 300_000L // 5 分钟

    fun setWaitingForInput(groupId: String) {
        waitingForInput[groupId] = true
        waitingForInputTimestamps[groupId] = System.currentTimeMillis()
        logger.info("[CcConnect][{}] setWaitingForInput", groupId)
    }

    fun clearWaitingForInput(groupId: String) {
        waitingForInput.remove(groupId)
        waitingForInputTimestamps.remove(groupId)
    }

    fun isWaitingForInput(groupId: String): Boolean {
        val ts = waitingForInputTimestamps[groupId] ?: return false
        if (System.currentTimeMillis() - ts > WAITING_TIMEOUT_MS) {
            logger.warn("[CcConnect][{}] waitingForInput timeout ({}s ago), clearing", groupId, (System.currentTimeMillis() - ts) / 1000)
            waitingForInput.remove(groupId)
            waitingForInputTimestamps.remove(groupId)
            return false
        }
        return waitingForInput.getOrDefault(groupId, false)
    }

    /**
     * 直接发送回答到 adapter，不经过 processing 检查/排队。
     * 用于 waitingForInput 阶段：用户回答必须直达已存在的会话。
     */
    suspend fun forwardAnswer(groupId: String, userMessage: UserMessage): Boolean {
        val conn = connections[groupId] ?: return false
        return try {
            clearWaitingForInput(groupId)
            val json = protocolJson.encodeToString(UserMessage.serializer(), userMessage)
            conn.session.send(Frame.Text(json))
            logger.info("[CcConnect][{}] forwardAnswer sent, msgId={}", groupId, userMessage.msgId)
            true
        } catch (e: ClosedSendChannelException) {
            logger.warn("[CcConnect][{}] forwardAnswer failed (closed): msgId={}", groupId, userMessage.msgId)
            unregister(groupId)
            false
        } catch (e: Exception) {
            logger.warn("[CcConnect][{}] forwardAnswer failed: msgId={}, err={}", groupId, userMessage.msgId, e.message)
            false
        }
    }

    /** 指定群组是否正在等待 agent 响应。 */
    fun isProcessing(groupId: String): Boolean {
        val ts = processingTimestamps[groupId]
        if (ts != null && System.currentTimeMillis() - ts > PROCESSING_TIMEOUT_MS) {
            logger.warn("[CcConnect][{}] processing timeout ({}s ago), clearing", groupId, (System.currentTimeMillis() - ts) / 1000)
            clearPending(groupId)
            return false
        }
        return processing.getOrDefault(groupId, false)
    }

    /** 处理中/排队操作的锁，防止 markIdle 与 forwardToAdapter 之间的竞态条件。 */
    private val stateLock = Any()

    /**
     * 转发用户消息到 cc-connect 适配器。
     * 若 agent 正忙则排队等待，不发送（避免 Claude Code CLI "上一个请求仍在处理中" 错误）。
     * 返回 true 表示已发送或已排队，false 表示连接不存在。
     */
    suspend fun forwardToAdapter(groupId: String, userMessage: UserMessage): Boolean {
        if (!connections.containsKey(groupId)) {
            logger.warn("[CcConnect][{}] forwardToAdapter: no connection, dropping msgId={}", groupId, userMessage.msgId)
            return false
        }

        val shouldSend = synchronized(stateLock) {
            val wasAlreadyBusy = processing.putIfAbsent(groupId, true) != null
            if (!wasAlreadyBusy) {
                processingTimestamps[groupId] = System.currentTimeMillis()
                logger.info("[CcConnect][{}] processing={} → sending directly, msgId={}", groupId, true, userMessage.msgId)
                true
            } else {
                pendingQueues.getOrPut(groupId) { ConcurrentLinkedQueue() }.add(userMessage)
                logger.info("[CcConnect][{}] processing={} → queued msgId={}, queueSize={}", groupId, true, userMessage.msgId, pendingQueues[groupId]?.size)
                false
            }
        }

        if (shouldSend) {
            try {
                val json = protocolJson.encodeToString(UserMessage.serializer(), userMessage)
                connections[groupId]?.session?.send(Frame.Text(json))
                logger.info("[CcConnect][{}] sent to cc-connect, msgId={}", groupId, userMessage.msgId)
            } catch (e: ClosedSendChannelException) {
                logger.warn("[CcConnect][{}] send failed (closed): msgId={}", groupId, userMessage.msgId)
                unregister(groupId)
            } catch (e: Exception) {
                logger.warn("[CcConnect][{}] send failed: msgId={}, err={}", groupId, userMessage.msgId, e.message)
                markIdle(groupId)
            }
        }
        return true
    }

    /**
     * 标记 agent 已空闲（收到 status:idle 或非 turn reply 时调用）。
     * 如果有排队消息，自动发送下一條。
     *
     * 注意：必须在锁内原子完成 "清除 processing → 出队" 两步，
     * 防止 [forwardToAdapter] 在中间插入新消息导致并发发送。
     */
    suspend fun markIdle(groupId: String) {
        val wasProcessing = processing[groupId] == true

        val next = synchronized(stateLock) {
            processing.remove(groupId)
            processingTimestamps.remove(groupId)

            val queue = pendingQueues[groupId]
            if (queue != null) {
                val msg = queue.poll()
                if (msg != null) {
                    processing[groupId] = true
                    processingTimestamps[groupId] = System.currentTimeMillis()
                    logger.info("[CcConnect][{}] markIdle: dequeued msgId={}, queueSize={}", groupId, msg.msgId, queue.size)
                    msg
                } else {
                    pendingQueues.remove(groupId)
                    null
                }
            } else {
                null
            }
        }

        logger.info("[CcConnect][{}] markIdle called (wasProcessing={}, dequeued={})", groupId, wasProcessing, next != null)

        if (next != null) {
            val conn = connections[groupId]
            if (conn == null) {
                logger.warn("[CcConnect][{}] markIdle: no connection for dequeue", groupId)
                synchronized(stateLock) {
                    processing.remove(groupId)
                    processingTimestamps.remove(groupId)
                }
                return
            }
            try {
                val json = protocolJson.encodeToString(UserMessage.serializer(), next)
                conn.session.send(Frame.Text(json))
                logger.info("[CcConnect][{}] dequeued message sent to cc-connect, msgId={}", groupId, next.msgId)
            } catch (e: Exception) {
                logger.warn("[CcConnect][{}] dequeue send failed: msgId={}, err={}", groupId, next.msgId, e.message)
                synchronized(stateLock) {
                    processing.remove(groupId)
                    processingTimestamps.remove(groupId)
                }
            }
        }
    }

    fun clearPending(groupId: String) {
        processing.remove(groupId)
        processingTimestamps.remove(groupId)
        pendingQueues.remove(groupId)
        clearWaitingForInput(groupId)
        logger.info("[CcConnect][{}] clearPending", groupId)
    }

    suspend fun register(groupId: String, session: WebSocketSession, meta: CcConnectConnectionMeta) {
        val old = connections.put(groupId, CcConnectConnection(session, meta))
        clearPending(groupId)
        if (old != null) {
            logger.info("[CcConnect][{}] replacing stale connection for groupId", groupId)
            try { old.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "replaced by new connection")) } catch (_: Exception) {}
        }
        logger.info("[CcConnect][{}] registered: project={}, agent={}", groupId, meta.project, meta.agentType)
    }

    /**
     * 取消注册指定 session 的连接。
     * 如果传入了 session 参数，会校验当前记录的连接是否仍指向该 session，
     * 避免旧协程的 finally 块在连接被替换后误删新连接。
     */
    fun unregister(groupId: String, session: WebSocketSession? = null) {
        if (session != null) {
            val current = connections[groupId]
            if (current == null || current.session !== session) {
                clearPending(groupId)
                logger.info("[CcConnect][{}] unregister skipped (session replaced), current={}", groupId, current != null)
                return
            }
        }
        connections.remove(groupId)
        clearPending(groupId)
        logger.info("[CcConnect][{}] unregistered", groupId)
    }

    fun isConnected(groupId: String): Boolean = connections.containsKey(groupId)

    fun getConnectionInfo(groupId: String): CcConnectConnectionMeta? = connections[groupId]?.meta

    fun updateMetadata(groupId: String, metadata: MetadataMessage) {
        val conn = connections[groupId] ?: return
        val updated = conn.meta.copy(
            mode = metadata.mode ?: conn.meta.mode,
            model = metadata.model ?: conn.meta.model,
            availableModes = metadata.availableModes ?: conn.meta.availableModes,
            availableModels = metadata.availableModels ?: conn.meta.availableModels,
        )
        connections[groupId] = conn.copy(meta = updated)
        logger.info("[CcConnect][{}] metadata updated: mode={}, model={}", groupId, updated.mode, updated.model)
    }

    suspend fun sendCommand(groupId: String, text: String) {
        val conn = connections[groupId] ?: return
        try {
            val json = protocolJson.encodeToString(CommandMessage.serializer(), CommandMessage(text = text))
            conn.session.send(Frame.Text(json))
        } catch (e: ClosedSendChannelException) {
            logger.warn("[CcConnect][{}] sendCommand failed (closed)", groupId)
            unregister(groupId)
        } catch (e: Exception) {
            logger.warn("[CcConnect][{}] sendCommand failed: err={}", groupId, e.message)
        }
    }
}
