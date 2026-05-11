// backend/src/main/kotlin/com/silk/backend/agents/acp/AcpRegistry.kt
package com.silk.backend.agents.acp

import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * (userId, agentType) → AcpClient 索引。
 * Plan A 阶段只是骨架；连接生命周期管理（accept/挤旧/通知 framework）放到 Plan B/C。
 */
object AcpRegistry {

    private data class Entry(val client: AcpClient, val remoteIp: String?)

    /** key = "${userId}::${agentType}" */
    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(userId: String, agentType: String) = "${userId}::${agentType}"

    /**
     * 注册新 client。如果同 (userId, agentType) 已有旧 client，**返回旧 client**让调用方负责 close。
     * Plan A 不直接 close，避免与 framework 层职责重叠。
     */
    fun put(userId: String, agentType: String, client: AcpClient, remoteIp: String?): AcpClient? {
        val previous = entries.put(key(userId, agentType), Entry(client, remoteIp))
        return previous?.client
    }

    /**
     * 接受一个 Ktor WebSocket 连接，包装为 AcpClient 并注册。
     * 如果同 (userId, agentType) 已有旧 client，返回旧 client 供调用方关闭。
     */
    suspend fun acceptConnection(
        userId: String,
        agentType: String,
        session: WebSocketSession,
        remoteIp: String?,
        scope: CoroutineScope,
    ): AcpClient? {
        val transport = AcpWebSocketTransport(session)
        val client = AcpClient(transport, scope)
        val evicted = put(userId, agentType, client, remoteIp)
        if (evicted != null) {
            try {
                evicted.close("evicted by new connection")
            } catch (_: Exception) {
                // ignore
            }
        }
        return client
    }

    fun get(userId: String, agentType: String): AcpClient? =
        entries[key(userId, agentType)]?.client

    fun isConnected(userId: String, agentType: String): Boolean =
        entries.containsKey(key(userId, agentType))

    fun getRemoteIp(userId: String, agentType: String): String? =
        entries[key(userId, agentType)]?.remoteIp
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeIp(it) }

    /** 把 IPv6 loopback / wildcard 转成易读形式。 */
    private fun normalizeIp(ip: String): String = when (ip) {
        "0:0:0:0:0:0:0:1", "::1" -> "127.0.0.1 (本机)"
        "0:0:0:0:0:0:0:0", "::" -> "0.0.0.0"
        else -> ip
    }

    fun unregister(userId: String, agentType: String) {
        entries.remove(key(userId, agentType))
    }

    fun listConnected(userId: String): List<String> {
        val prefix = "${userId}::"
        return entries.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    /**
     * 关闭并移除该 user 下所有 agentType 的 ACP 连接。返回关闭数。
     * 用于 token 重新生成等强制踢连接的场景。
     */
    suspend fun disconnect(userId: String): Int {
        val prefix = "${userId}::"
        val toClose = entries.entries.filter { it.key.startsWith(prefix) }.toList()
        for (entry in toClose) {
            try {
                entry.value.client.close("token regenerated")
            } catch (_: Exception) {
                // ignore — adapter 端可能已先关
            }
            entries.remove(entry.key)
        }
        return toClose.size
    }

    /** 仅供测试使用 */
    internal fun clearForTest() {
        entries.clear()
    }
}
