package com.silk.backend.agents.auth

import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import java.util.concurrent.ConcurrentHashMap

/** Tracks one device socket and independently revocable logical Agent streams. */
internal object AgentHostConnectionRegistry {
    private data class LogicalStream(
        val deviceId: String,
        val session: WebSocketSession,
        val disconnect: suspend (String) -> Unit,
    )

    private val devices = ConcurrentHashMap<String, WebSocketSession>()
    private val streams = ConcurrentHashMap<String, LogicalStream>()

    fun registerDevice(deviceId: String, session: WebSocketSession): WebSocketSession? =
        devices.put(deviceId, session)

    fun unregisterDevice(deviceId: String, session: WebSocketSession) {
        if (devices.remove(deviceId, session)) {
            streams.entries.removeIf { it.value.session === session }
        }
    }

    fun registerAgent(
        deviceId: String,
        agentInstanceId: String,
        session: WebSocketSession,
        disconnect: suspend (String) -> Unit,
    ): (suspend (String) -> Unit)? =
        streams.put(agentInstanceId, LogicalStream(deviceId, session, disconnect))?.disconnect

    fun unregisterAgent(agentInstanceId: String, session: WebSocketSession) {
        streams.computeIfPresent(agentInstanceId) { _, stream ->
            if (stream.session === session) null else stream
        }
    }

    fun isDeviceConnected(deviceId: String): Boolean = devices.containsKey(deviceId)

    fun isAgentConnected(agentInstanceId: String): Boolean = streams.containsKey(agentInstanceId)

    suspend fun disconnectAgent(agentInstanceId: String) {
        streams.remove(agentInstanceId)?.disconnect?.invoke("agent revoked")
    }

    suspend fun disconnectDevice(deviceId: String) {
        val session = devices.remove(deviceId)
        streams.entries.removeIf { it.value.deviceId == deviceId }
        if (session != null) {
            runCatching {
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device revoked"))
            }
        }
    }

    internal fun clearForTest() {
        devices.clear()
        streams.clear()
    }
}
