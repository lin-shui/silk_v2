package com.silk.backend.agents.auth

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private const val BRIDGE_SOCKET_TIMEOUT_MS = 10_000L

private val bridgeAuthJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal data class AuthenticatedAgentSocket(
    val identity: ActiveAgentIdentity,
    val connectionMode: String?,
)

/**
 * Authenticates a direct Bridge on the same device-signature contract as
 * /agent-connect. The ACP route calls this before registering the transport,
 * so a pre-authenticated socket can never reach business RPC handling.
 */
internal suspend fun DefaultWebSocketServerSession.authenticateAgentSocket(
    challengeService: AgentChallengeService,
): AuthenticatedAgentSocket? {
    val firstFrame = withTimeoutOrNull(BRIDGE_SOCKET_TIMEOUT_MS) {
        incoming.receiveCatching().getOrNull()
    }
    val hello = decodeBridgeFrame<AgentSocketHello>(firstFrame)
    if (hello == null || hello.type != "hello" || hello.protocolVersion != AgentAuthProtocol.VERSION) {
        rejectBridgeSocket("INVALID_HELLO", "Invalid agent hello")
        return null
    }
    val identity = AgentAuthRepository.findActiveIdentity(hello.deviceId, hello.agentInstanceId)
    if (identity == null) {
        rejectBridgeSocket("DEVICE_OR_AGENT_REVOKED", "Device or Agent is not active")
        return null
    }
    val challenge = challengeService.issue(
        serverOrigin = identity.authenticationOrigin,
        deviceId = hello.deviceId,
        agentInstanceId = hello.agentInstanceId,
        publicKey = identity.publicKey,
    )
    send(
        Frame.Text(
            bridgeAuthJson.encodeToString(
                AgentSocketChallenge(
                    challengeId = challenge.challengeId,
                    nonce = challenge.nonce,
                    serverOrigin = challenge.serverOrigin,
                    expiresAtEpochMs = challenge.expiresAtEpochMs,
                    serverTimeEpochMs = challenge.serverTimeEpochMs,
                ),
            ),
        ),
    )
    val authFrame = withTimeoutOrNull(BRIDGE_SOCKET_TIMEOUT_MS) {
        incoming.receiveCatching().getOrNull()
    }
    val auth = decodeBridgeFrame<AgentSocketAuthenticate>(authFrame)
    if (!auth.matches(challenge.challengeId, hello)) {
        challengeService.remove(challenge.challengeId)
        rejectBridgeSocket("INVALID_DEVICE_PROOF", "Device authentication failed")
        return null
    }
    val authRequest = requireNotNull(auth)
    val verified = challengeService.consumeAndVerify(
        challengeId = authRequest.challengeId,
        serverOrigin = identity.authenticationOrigin,
        deviceId = authRequest.deviceId,
        agentInstanceId = authRequest.agentInstanceId,
        timestampEpochMs = authRequest.timestampEpochMs,
        signature = authRequest.signature,
    )
    val currentIdentity = AgentAuthRepository.findActiveIdentity(authRequest.deviceId, authRequest.agentInstanceId)
    if (verified == null || currentIdentity == null) {
        rejectBridgeSocket("INVALID_DEVICE_PROOF", "Device authentication failed")
        return null
    }
    AgentAuthRepository.touchAuthenticated(currentIdentity, call.request.local.remoteAddress)
    send(
        Frame.Text(
            bridgeAuthJson.encodeToString(
                AgentSocketAuthenticated(
                    connectionId = java.util.UUID.randomUUID().toString(),
                    deviceId = currentIdentity.deviceId,
                    agentInstanceId = currentIdentity.agentInstanceId,
                    capabilities = currentIdentity.capabilities,
                    serverTimeEpochMs = System.currentTimeMillis(),
                    connectionMode = hello.connectionMode,
                ),
            ),
        ),
    )
    return AuthenticatedAgentSocket(currentIdentity, hello.connectionMode)
}

private fun AgentSocketAuthenticate?.matches(expectedChallengeId: String, hello: AgentSocketHello): Boolean {
    if (this == null) return false
    if (type != "authenticate") return false
    if (protocolVersion != AgentAuthProtocol.VERSION) return false
    if (challengeId != expectedChallengeId) return false
    if (deviceId != hello.deviceId) return false
    return agentInstanceId == hello.agentInstanceId
}

private suspend fun DefaultWebSocketServerSession.rejectBridgeSocket(error: String, message: String) {
    send(
        Frame.Text(
            bridgeAuthJson.encodeToString(AgentSocketError(error = error, message = message)),
        ),
    )
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, error))
}

private inline fun <reified T> decodeBridgeFrame(frame: Frame?): T? {
    if (frame !is Frame.Text) return null
    return runCatching { bridgeAuthJson.decodeFromString<T>(frame.readText()) }.getOrNull()
}

/** Separate from /agent-connect so auth health and ACP transport never evict each other. */
internal object AgentBridgeConnectionRegistry {
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
        sessions.entries.filter { it.key.endsWith("::$agentInstanceId") }.forEach { entry ->
            runCatching {
                entry.value.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "agent revoked"))
            }
            sessions.remove(entry.key, entry.value)
        }
    }

    suspend fun disconnectDevice(deviceId: String) {
        sessions.entries.filter { it.key.startsWith("$deviceId::") }.forEach { entry ->
            runCatching {
                entry.value.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device revoked"))
            }
            sessions.remove(entry.key, entry.value)
        }
    }

    internal fun clearForTest() {
        sessions.clear()
    }
}
