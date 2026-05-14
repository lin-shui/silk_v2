package com.silk.backend.ccconnect

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class CcConnectConnectionMeta(
    val groupId: String,
    val project: String,
    val agentType: String,
    val cwd: String = "",
)

data class CcConnectConnection(
    val session: WebSocketSession,
    val meta: CcConnectConnectionMeta,
)

object CcConnectRegistry {

    private val logger = LoggerFactory.getLogger(CcConnectRegistry::class.java)
    private val connections = ConcurrentHashMap<String, CcConnectConnection>()

    suspend fun register(groupId: String, session: WebSocketSession, meta: CcConnectConnectionMeta) {
        val old = connections.put(groupId, CcConnectConnection(session, meta))
        if (old != null) {
            logger.info("[CcConnect] replacing stale connection for groupId={}", groupId)
            try { old.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "replaced by new connection")) } catch (_: Exception) {}
        }
        logger.info("[CcConnect] registered: groupId={}, project={}, agent={}", groupId, meta.project, meta.agentType)
    }

    fun unregister(groupId: String) {
        val removed = connections.remove(groupId)
        if (removed != null) {
            logger.info("[CcConnect] unregistered: groupId={}", groupId)
        }
    }

    fun isConnected(groupId: String): Boolean = connections.containsKey(groupId)

    fun getConnectionInfo(groupId: String): CcConnectConnectionMeta? = connections[groupId]?.meta

    suspend fun forwardToAdapter(groupId: String, userMessage: UserMessage) {
        val conn = connections[groupId] ?: return
        try {
            val json = protocolJson.encodeToString(UserMessage.serializer(), userMessage)
            conn.session.send(Frame.Text(json))
        } catch (e: ClosedSendChannelException) {
            logger.warn("[CcConnect] send failed (closed): groupId={}", groupId)
            unregister(groupId)
        } catch (e: Exception) {
            logger.warn("[CcConnect] send failed: groupId={}, err={}", groupId, e.message)
        }
    }
}
