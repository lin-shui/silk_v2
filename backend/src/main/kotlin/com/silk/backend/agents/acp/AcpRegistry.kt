// backend/src/main/kotlin/com/silk/backend/agents/acp/AcpRegistry.kt
package com.silk.backend.agents.acp

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

    fun get(userId: String, agentType: String): AcpClient? =
        entries[key(userId, agentType)]?.client

    fun isConnected(userId: String, agentType: String): Boolean =
        entries.containsKey(key(userId, agentType))

    fun getRemoteIp(userId: String, agentType: String): String? =
        entries[key(userId, agentType)]?.remoteIp

    fun unregister(userId: String, agentType: String) {
        entries.remove(key(userId, agentType))
    }

    fun listConnected(userId: String): List<String> {
        val prefix = "${userId}::"
        return entries.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    /** 仅供测试使用 */
    internal fun clearForTest() {
        entries.clear()
    }
}
