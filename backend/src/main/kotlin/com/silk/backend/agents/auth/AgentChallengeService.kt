package com.silk.backend.agents.auth

import com.silk.backend.database.AgentConnectionChallenges
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.ktor.websocket.close

internal data class IssuedAgentChallenge(
    val challengeId: String,
    val nonce: String,
    val serverOrigin: String,
    val deviceId: String,
    val agentInstanceId: String,
    val expiresAtEpochMs: Long,
    val serverTimeEpochMs: Long,
)

internal data class StoredAgentChallenge(
    val challenge: IssuedAgentChallenge,
    val publicKey: String,
)

internal interface AgentChallengeStore {
    fun put(value: StoredAgentChallenge)
    fun consume(challengeId: String, consumedAtEpochMs: Long): StoredAgentChallenge?
    fun remove(challengeId: String)
    fun prune(expiredBeforeEpochMs: Long)
}

internal class InMemoryAgentChallengeStore : AgentChallengeStore {
    private val pending = ConcurrentHashMap<String, StoredAgentChallenge>()

    override fun put(value: StoredAgentChallenge) {
        pending[value.challenge.challengeId] = value
    }

    override fun consume(challengeId: String, consumedAtEpochMs: Long): StoredAgentChallenge? =
        pending.remove(challengeId)

    override fun remove(challengeId: String) {
        pending.remove(challengeId)
    }

    override fun prune(expiredBeforeEpochMs: Long) {
        pending.entries.removeIf { (_, value) ->
            value.challenge.expiresAtEpochMs < expiredBeforeEpochMs
        }
    }
}

/** Atomic database store used by production sockets so challenges work across backend nodes. */
internal object DatabaseAgentChallengeStore : AgentChallengeStore {
    override fun put(value: StoredAgentChallenge) {
        val challenge = value.challenge
        transaction {
            AgentConnectionChallenges.insert { row ->
                row[id] = challenge.challengeId
                row[nonce] = challenge.nonce
                row[serverOrigin] = challenge.serverOrigin
                row[deviceId] = challenge.deviceId
                row[agentInstanceId] = challenge.agentInstanceId
                row[publicKey] = value.publicKey
                row[createdAt] = challenge.serverTimeEpochMs.toUtcDateTime()
                row[expiresAt] = challenge.expiresAtEpochMs.toUtcDateTime()
                row[consumedAt] = null
            }
        }
    }

    override fun consume(challengeId: String, consumedAtEpochMs: Long): StoredAgentChallenge? = transaction {
        val row = AgentConnectionChallenges.select {
            (AgentConnectionChallenges.id eq challengeId) and
                AgentConnectionChallenges.consumedAt.isNull()
        }.singleOrNull() ?: return@transaction null
        val updated = AgentConnectionChallenges.update({
            (AgentConnectionChallenges.id eq challengeId) and
                AgentConnectionChallenges.consumedAt.isNull()
        }) { value ->
            value[consumedAt] = consumedAtEpochMs.toUtcDateTime()
        }
        if (updated != 1) return@transaction null
        StoredAgentChallenge(
            challenge = IssuedAgentChallenge(
                challengeId = row[AgentConnectionChallenges.id],
                nonce = row[AgentConnectionChallenges.nonce],
                serverOrigin = row[AgentConnectionChallenges.serverOrigin],
                deviceId = row[AgentConnectionChallenges.deviceId],
                agentInstanceId = row[AgentConnectionChallenges.agentInstanceId],
                expiresAtEpochMs = row[AgentConnectionChallenges.expiresAt].toEpochMillis(),
                serverTimeEpochMs = row[AgentConnectionChallenges.createdAt].toEpochMillis(),
            ),
            publicKey = row[AgentConnectionChallenges.publicKey],
        )
    }

    override fun remove(challengeId: String) {
        transaction {
            AgentConnectionChallenges.deleteWhere { AgentConnectionChallenges.id eq challengeId }
        }
    }

    override fun prune(expiredBeforeEpochMs: Long) {
        transaction {
            AgentConnectionChallenges.deleteWhere {
                AgentConnectionChallenges.expiresAt less expiredBeforeEpochMs.toUtcDateTime()
            }
        }
    }

    private fun Long.toUtcDateTime(): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneOffset.UTC)

    private fun LocalDateTime.toEpochMillis(): Long = toInstant(ZoneOffset.UTC).toEpochMilli()
}

internal class AgentChallengeService(
    private val clock: () -> Long = System::currentTimeMillis,
    private val challengeLifetimeMs: Long = 30_000L,
    private val clockSkewMs: Long = 120_000L,
    private val store: AgentChallengeStore = InMemoryAgentChallengeStore(),
) {
    fun issue(
        serverOrigin: String,
        deviceId: String,
        agentInstanceId: String,
        publicKey: String,
    ): IssuedAgentChallenge {
        val now = clock()
        val challenge = IssuedAgentChallenge(
            challengeId = UUID.randomUUID().toString(),
            nonce = AgentAuthProtocol.randomOpaqueSecret(),
            serverOrigin = serverOrigin,
            deviceId = deviceId,
            agentInstanceId = agentInstanceId,
            expiresAtEpochMs = now + challengeLifetimeMs,
            serverTimeEpochMs = now,
        )
        store.put(StoredAgentChallenge(challenge, publicKey))
        store.prune(now)
        return challenge
    }

    /** Consumes a challenge before verification, so even a failed attempt cannot be replayed. */
    fun consumeAndVerify(
        challengeId: String,
        serverOrigin: String,
        deviceId: String,
        agentInstanceId: String,
        timestampEpochMs: Long,
        signature: String,
    ): IssuedAgentChallenge? {
        val entry = store.consume(challengeId, clock()) ?: return null
        val challenge = entry.challenge
        val now = clock()
        if (!challenge.matches(serverOrigin, deviceId, agentInstanceId, timestampEpochMs, now)) {
            return null
        }
        val payload = AgentAuthProtocol.canonicalAgentAuthentication(
            serverOrigin = serverOrigin,
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            deviceId = deviceId,
            agentInstanceId = agentInstanceId,
            timestampEpochMs = timestampEpochMs,
        )
        return if (AgentAuthProtocol.verifySignature(entry.publicKey, payload, signature)) {
            challenge
        } else {
            null
        }
    }

    fun remove(challengeId: String) {
        store.remove(challengeId)
    }

    private fun IssuedAgentChallenge.matches(
        requestedOrigin: String,
        requestedDeviceId: String,
        requestedAgentInstanceId: String,
        requestedTimestampEpochMs: Long,
        nowEpochMs: Long,
    ): Boolean = serverOrigin == requestedOrigin &&
        deviceId == requestedDeviceId &&
        agentInstanceId == requestedAgentInstanceId &&
        expiresAtEpochMs >= nowEpochMs &&
        kotlin.math.abs(nowEpochMs - requestedTimestampEpochMs) <= clockSkewMs
}

internal object AgentConnectionRegistry {
    private val sessions = ConcurrentHashMap<String, io.ktor.websocket.WebSocketSession>()

    fun registerAndReturnPrevious(
        connectionId: String,
        session: io.ktor.websocket.WebSocketSession,
    ): io.ktor.websocket.WebSocketSession? = sessions.put(connectionId, session)

    fun unregister(connectionId: String, session: io.ktor.websocket.WebSocketSession) {
        sessions.remove(connectionId, session)
    }

    fun isAgentConnected(agentInstanceId: String): Boolean =
        sessions.keys.any { it.endsWith("::$agentInstanceId") }

    fun isDeviceConnected(deviceId: String): Boolean =
        sessions.keys.any { it.startsWith("$deviceId::") }

    suspend fun disconnectAgent(agentInstanceId: String) {
        val matches = sessions.entries.filter { it.key.endsWith("::$agentInstanceId") }
        matches.forEach { entry ->
            runCatching {
                entry.value.close(
                    io.ktor.websocket.CloseReason(
                        io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                        "agent revoked",
                    )
                )
            }
            sessions.remove(entry.key, entry.value)
        }
    }

    suspend fun disconnectDevice(deviceId: String) {
        val matches = sessions.entries.filter { it.key.startsWith("$deviceId::") }
        matches.forEach { entry ->
            runCatching {
                entry.value.close(
                    io.ktor.websocket.CloseReason(
                        io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                        "device revoked",
                    )
                )
            }
            sessions.remove(entry.key, entry.value)
        }
    }

    internal fun clearForTest() {
        sessions.clear()
    }
}
