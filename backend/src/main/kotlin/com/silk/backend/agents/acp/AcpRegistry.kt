// backend/src/main/kotlin/com/silk/backend/agents/acp/AcpRegistry.kt
package com.silk.backend.agents.acp

import com.silk.backend.agents.auth.AgentCapability
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Authenticated AgentInstance → AcpClient index.
 *
 * `agentType` is only an adapter/protocol classification. It is deliberately
 * not a connection key: two devices owned by one user may run the same type
 * at the same time. The old type-based helpers remain only for legacy
 * workspace callers and return a value only when the type is unambiguous.
 */
object AcpRegistry {

    enum class AuthenticationMode { DEVICE_SIGNATURE }

    data class ConnectionIdentity(
        val userId: String,
        val agentType: String,
        val authenticationMode: AuthenticationMode,
        val agentInstanceId: String? = null,
        val capabilities: Set<AgentCapability> = emptySet(),
    )

    private data class Entry(
        val userId: String,
        val agentType: String,
        val client: AcpClient,
        val remoteIp: String?,
        val identity: ConnectionIdentity,
    )

    /** key is `instance::<agentInstanceId>` for authenticated Agents. */
    private val entries = ConcurrentHashMap<String, Entry>()
    private val typeIndex = ConcurrentHashMap<String, MutableSet<String>>()

    private fun legacyKey(userId: String, agentType: String) = "legacy::$userId::$agentType"
    private fun instanceKey(agentInstanceId: String) = "instance::$agentInstanceId"
    private fun typeKey(userId: String, agentType: String) = "$userId::$agentType"

    private fun candidates(userId: String, agentType: String): List<Entry> =
        typeIndex[typeKey(userId, agentType)]
            ?.mapNotNull(entries::get)
            .orEmpty()

    /**
     * 注册新 client。带 AgentInstance ID 时只替换该实例的旧连接；不同设备上的
     * 同类 Agent 会并存。无实例 ID 的旧连接才使用 legacy type key。
     */
    fun put(
        userId: String,
        agentType: String,
        client: AcpClient,
        remoteIp: String?,
        authenticationMode: AuthenticationMode = AuthenticationMode.DEVICE_SIGNATURE,
        agentInstanceId: String? = null,
        capabilities: Set<AgentCapability> = emptySet(),
    ): AcpClient? {
        val identity = ConnectionIdentity(
            userId = userId,
            agentType = agentType,
            authenticationMode = authenticationMode,
            agentInstanceId = agentInstanceId,
            capabilities = capabilities,
        )
        val key = agentInstanceId?.takeIf(String::isNotBlank)?.let(::instanceKey)
            ?: legacyKey(userId, agentType)
        val previous = entries.put(key, Entry(userId, agentType, client, remoteIp, identity))
        typeIndex.computeIfAbsent(typeKey(userId, agentType)) { ConcurrentHashMap.newKeySet() }.add(key)
        if (previous != null && (previous.userId != userId || previous.agentType != agentType)) {
            typeIndex[typeKey(previous.userId, previous.agentType)]?.remove(key)
        }
        return previous?.client
    }

    /** 接受一个 Ktor WebSocket 连接并按 AgentInstance 注册。 */
    suspend fun acceptConnection(
        userId: String,
        agentType: String,
        session: WebSocketSession,
        remoteIp: String?,
        scope: CoroutineScope,
        authenticationMode: AuthenticationMode = AuthenticationMode.DEVICE_SIGNATURE,
        agentInstanceId: String? = null,
        capabilities: Set<AgentCapability> = emptySet(),
    ): AcpClient? {
        val transport = AcpWebSocketTransport(session)
        val client = AcpClient(transport, scope)
        val evicted = put(
            userId = userId,
            agentType = agentType,
            client = client,
            remoteIp = remoteIp,
            authenticationMode = authenticationMode,
            agentInstanceId = agentInstanceId,
            capabilities = capabilities,
        )
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
        candidates(userId, agentType).singleOrNull()?.client

    fun getByInstance(agentInstanceId: String): AcpClient? =
        entries[instanceKey(agentInstanceId)]?.client

    fun isConnected(userId: String, agentType: String): Boolean =
        candidates(userId, agentType).isNotEmpty()

    fun isConnectedInstance(agentInstanceId: String): Boolean =
        entries.containsKey(instanceKey(agentInstanceId))

    fun getRemoteIp(userId: String, agentType: String): String? =
        candidates(userId, agentType).singleOrNull()?.remoteIp
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeIp(it) }

    fun getRemoteIpByInstance(agentInstanceId: String): String? =
        entries[instanceKey(agentInstanceId)]?.remoteIp
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeIp(it) }

    fun authenticationMode(userId: String, agentType: String): AuthenticationMode? =
        candidates(userId, agentType).singleOrNull()?.identity?.authenticationMode

    fun connectionIdentity(userId: String, agentType: String): ConnectionIdentity? =
        candidates(userId, agentType).singleOrNull()?.identity

    fun connectionIdentity(agentInstanceId: String): ConnectionIdentity? =
        entries[instanceKey(agentInstanceId)]?.identity

    /** 把 IPv6 loopback / wildcard 转成易读形式。 */
    private fun normalizeIp(ip: String): String = when (ip) {
        "0:0:0:0:0:0:0:1", "::1" -> "127.0.0.1 (本机)"
        "0:0:0:0:0:0:0:0", "::" -> "0.0.0.0"
        else -> ip
    }

    fun unregister(userId: String, agentType: String, client: AcpClient? = null) {
        val keys = typeIndex[typeKey(userId, agentType)].orEmpty().toList()
        keys.forEach { entryKey ->
            entries.computeIfPresent(entryKey) { _, entry ->
                if (client == null || entry.client === client) {
                    typeIndex[typeKey(entry.userId, entry.agentType)]?.remove(entryKey)
                    null
                } else {
                    entry
                }
            }
        }
    }

    fun unregister(agentInstanceId: String, client: AcpClient? = null) {
        val entryKey = instanceKey(agentInstanceId)
        entries.computeIfPresent(entryKey) { _, entry ->
            if (client == null || entry.client === client) {
                typeIndex[typeKey(entry.userId, entry.agentType)]?.remove(entryKey)
                null
            } else {
                entry
            }
        }
    }

    fun listConnected(userId: String): List<String> {
        return entries.values.asSequence()
            .filter { it.userId == userId }
            .map { it.agentType }
            .distinct()
            .sorted()
            .toList()
    }

    fun listConnectedInstances(userId: String): List<ConnectionIdentity> = entries.values.asSequence()
        .filter { it.userId == userId }
        .map { it.identity }
        .sortedBy { it.agentInstanceId.orEmpty() }
        .toList()

    /**
     * 关闭并移除该 user 下所有 agentType 的 ACP 连接。返回关闭数。
     * Used when every direct Agent connection for an account must be closed.
     */
    suspend fun disconnect(userId: String): Int {
        val toClose = entries.entries.filter { it.value.userId == userId }.toList()
        for (entry in toClose) {
            try {
                entry.value.client.close("connection revoked")
            } catch (_: Exception) {
                // ignore — adapter 端可能已先关
            }
            unregisterEntry(entry.key, entry.value)
        }
        return toClose.size
    }

    private fun unregisterEntry(key: String, entry: Entry) {
        entries.remove(key, entry)
        typeIndex[typeKey(entry.userId, entry.agentType)]?.remove(key)
    }

    /** 仅供测试使用 */
    internal fun clearForTest() {
        entries.clear()
        typeIndex.clear()
    }
}
