package com.silk.backend.routes

import com.silk.backend.EnvLoader
import com.silk.backend.agents.auth.AgentAuthErrorResponse
import com.silk.backend.agents.auth.AgentAuthProtocol
import com.silk.backend.agents.auth.AgentAuthException
import com.silk.backend.agents.auth.AgentAuthRepository
import com.silk.backend.agents.auth.AgentBindingListResponse
import com.silk.backend.agents.auth.AgentBindingApprovalRequest
import com.silk.backend.agents.auth.AgentBindingDto
import com.silk.backend.agents.auth.AgentBindingMessageScope
import com.silk.backend.agents.auth.AgentBindingStatus
import com.silk.backend.agents.auth.AgentBindingTargetType
import com.silk.backend.agents.auth.CreateAgentBindingRequest
import com.silk.backend.agents.auth.AgentChallengeService
import com.silk.backend.agents.auth.DatabaseAgentChallengeStore
import com.silk.backend.agents.auth.AgentConnectionRegistry
import com.silk.backend.agents.auth.AgentBridgeConnectionRegistry
import com.silk.backend.agents.auth.AgentHostConnectionRegistry
import com.silk.backend.agents.auth.authenticateAgentSocket
import com.silk.backend.agents.auth.AGENT_HOST_CONNECTION_MODE
import com.silk.backend.agents.auth.ActiveAgentIdentity
import com.silk.backend.agents.auth.AgentHostClose
import com.silk.backend.agents.auth.AgentHostOpen
import com.silk.backend.agents.auth.AgentHostOpened
import com.silk.backend.agents.auth.AgentHostRpc
import com.silk.backend.agents.auth.AgentDeviceListResponse
import com.silk.backend.agents.auth.AgentInstanceListResponse
import com.silk.backend.agents.auth.AgentSecurityEventListResponse
import com.silk.backend.agents.auth.AgentManagementActionResponse
import com.silk.backend.agents.auth.AgentRevocationCleanupResponse
import com.silk.backend.agents.auth.AgentPairingRecord
import com.silk.backend.agents.auth.AgentPairingApprovalRequest
import com.silk.backend.agents.auth.AgentPairingApprovalResponse
import com.silk.backend.agents.auth.AgentPairingPreviewResponse
import com.silk.backend.agents.auth.AgentPairingState
import com.silk.backend.agents.auth.AgentPairingStatusResponse
import com.silk.backend.agents.auth.AgentSocketError
import com.silk.backend.agents.auth.AgentSocketHeartbeat
import com.silk.backend.agents.auth.AgentSocketHeartbeatAck
import com.silk.backend.agents.auth.AgentTransportAdapter
import com.silk.backend.agents.auth.CompleteAgentPairingRequest
import com.silk.backend.agents.auth.CompleteAgentPairingResponse
import com.silk.backend.agents.auth.CreateAgentPairingRequest
import com.silk.backend.agents.auth.CreateAgentPairingResponse
import com.silk.backend.agents.auth.CreateTrustedDeviceAgentRequest
import com.silk.backend.agents.auth.UpdateAgentBindingRequest
import com.silk.backend.agents.auth.DeviceEnrollmentStatus
import com.silk.backend.agents.auth.AgentInstanceStatus
import com.silk.backend.agents.auth.PairingDeviceProofChallenge
import com.silk.backend.agents.auth.asEpochMillis
import com.silk.backend.agents.core.AgentRegistry
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.acp.AcpMultiplexedTransport
import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.acp.ClientCapabilities
import com.silk.backend.agents.acp.FsCapability
import com.silk.backend.agents.acp.InitializeParams
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MemberRole
import com.silk.backend.database.UserRepository
import com.silk.backend.workspace.WorkspaceManager
import com.silk.backend.workspace.WorkspaceLifecycleState
import com.silk.backend.workspace.WorkspaceVisibility
import com.silk.shared.models.RoomKind
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.net.URI
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

private const val PAIRING_CODE_ATTEMPT_LIMIT = 10
private const val PAIRING_CREATE_LIMIT = 10
private const val PAIRING_PROOF_LIMIT = 5
private const val AGENT_ADDITION_CREATE_LIMIT = 10
private const val RATE_WINDOW_MS = 60_000L
private const val RATE_LIMIT_MAX_BUCKETS = 10_000
private const val TIMESTAMP_WINDOW_MS = 120_000L

private val agentAuthJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
private val agentChallengeService = AgentChallengeService(store = DatabaseAgentChallengeStore)
internal val agentBridgeChallengeService = AgentChallengeService(store = DatabaseAgentChallengeStore)
private val agentAuthRateLimiter = AgentAuthRateLimiter()

/** Phase 2 device enrollment, public-key proof, management, and authenticated health WebSocket. */
@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
fun Route.agentAuthRoutes() {
    get("/device") {
        val indexFile = resolveWebIndexFile()
        if (indexFile == null) {
            call.respond(
                io.ktor.http.HttpStatusCode.ServiceUnavailable,
                "Silk Web application is not built",
            )
            return@get
        }
        call.respondFile(indexFile)
    }

    post("/api/agent-pairings") {
        val remote = call.request.local.remoteAddress
        if (!agentAuthRateLimiter.allow("create:$remote", PAIRING_CREATE_LIMIT)) {
            call.respondAgentError(io.ktor.http.HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Too many pairing requests")
            return@post
        }
        val request = runCatching { call.receive<CreateAgentPairingRequest>() }.getOrElse {
            call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid pairing request")
            return@post
        }
        val canonicalAgentType = AgentRegistry.get(request.agentType.trim())?.agentType
        when {
            request.protocolVersion != AgentAuthProtocol.VERSION -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "UNSUPPORTED_PROTOCOL", "Unsupported pairing protocol")
                return@post
            }
            request.keyAlgorithm != AgentAuthProtocol.KEY_ALGORITHM -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "UNSUPPORTED_KEY", "Only Ed25519 device keys are supported")
                return@post
            }
            request.transportAdapter != AgentTransportAdapter.ACP -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "CC_CONNECT_DEFERRED", "cc-connect enrollment is planned for a later phase")
                return@post
            }
            canonicalAgentType == null -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "UNKNOWN_AGENT", "Unknown Agent type")
                return@post
            }
            canonicalAgentType !in setOf("claude-code", "codex") -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "AGENT_NOT_AVAILABLE", "This Agent is not available in the direct Bridge phase")
                return@post
            }
            request.capabilities.size > 32 -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_CAPABILITIES", "Too many requested capabilities")
                return@post
            }
            request.accountLoginName.isBlank() || request.connectionOrigin.isBlank() -> {
                call.respondAgentError(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    "PAIRING_CLIENT_UPGRADE_REQUIRED",
                    "Initial pairing requires silk-agent 0.4.1 or newer and an explicit target account",
                )
                return@post
            }
            request.accountLoginName != request.accountLoginName.trim() ||
                request.accountLoginName.length > 128 || !request.accountLoginName.isSafeMetadata() -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_ACCOUNT", "Target account is invalid")
                return@post
            }
            request.deviceName.isBlank() || request.deviceName.length > 256 ||
                !request.deviceName.isSafeMetadata() ||
                request.platform.isBlank() || request.platform.length > 64 ||
                !request.platform.isSafeMetadata() ||
                request.agentDisplayName.isBlank() || request.agentDisplayName.length > 256 ||
                !request.agentDisplayName.isSafeMetadata() ||
                request.connectorVersion.isBlank() || request.connectorVersion.length > 64 ||
                !request.connectorVersion.isSafeMetadata() -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_METADATA", "Device and Agent metadata is invalid")
                return@post
            }
        }
        val normalizedPublicKey = runCatching { AgentAuthProtocol.normalizePublicKey(request.publicKey) }
            .getOrElse {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_PUBLIC_KEY", "publicKey must be a raw Ed25519 key")
                return@post
            }
        val intendedOwner = UserRepository.findUserByLoginName(request.accountLoginName)
        if (intendedOwner == null) {
            call.respondAgentError(io.ktor.http.HttpStatusCode.NotFound, "INVALID_ACCOUNT", "Target account is invalid")
            return@post
        }
        val serverOrigin = runCatching { resolveAgentServerOrigin(request.connectionOrigin) }.getOrElse {
            call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_SERVER_ORIGIN", "Silk server origin is invalid")
            return@post
        }
        val verificationBase = runCatching {
            resolveAgentVerificationOrigin(request.verificationOrigin, serverOrigin)
        }.getOrElse {
            call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_VERIFICATION_ORIGIN", "Silk Web origin is invalid")
            return@post
        }
        val created = runCatching {
            AgentAuthRepository.createPairing(
                request = request,
                intendedOwnerId = intendedOwner.id,
                normalizedPublicKey = normalizedPublicKey,
                canonicalAgentType = requireNotNull(canonicalAgentType),
                serverOrigin = serverOrigin,
            )
        }.getOrElse { error ->
            val authError = error as? AgentAuthException
            call.respondAgentError(
                io.ktor.http.HttpStatusCode.Conflict,
                authError?.errorCode ?: "PAIRING_CREATE_FAILED",
                authError?.message ?: "Could not create pairing request",
            )
            return@post
        }
        call.respond(
            CreateAgentPairingResponse(
                pairingId = created.record.pairingId,
                devicePollSecret = created.devicePollSecret,
                serverOrigin = serverOrigin,
                verificationUri = pairingVerificationUri(verificationBase, created.userCode),
                userCode = created.userCode,
                expiresAtEpochMs = created.record.expiresAt.asEpochMillis(),
                agentWebSocketUri = toWebSocketOrigin(serverOrigin) + "/agent-connect",
            )
        )
    }

    post("/api/agent-pairings/agents") {
        val remote = call.request.local.remoteAddress
        if (!agentAuthRateLimiter.allow("add-agent:$remote", AGENT_ADDITION_CREATE_LIMIT)) {
            call.respondAgentError(io.ktor.http.HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Too many Agent requests")
            return@post
        }
        val request = runCatching { call.receive<CreateTrustedDeviceAgentRequest>() }.getOrElse {
            call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid trusted-device Agent request")
            return@post
        }
        val canonicalAgentType = AgentRegistry.get(request.agentType.trim())?.agentType
        when {
            request.protocolVersion != AgentAuthProtocol.VERSION -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "UNSUPPORTED_PROTOCOL", "Unsupported Agent request protocol")
                return@post
            }
            request.transportAdapter != AgentTransportAdapter.ACP -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "CC_CONNECT_DEFERRED", "cc-connect enrollment is planned for a later phase")
                return@post
            }
            canonicalAgentType == null || canonicalAgentType !in setOf("claude-code", "codex") -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "AGENT_NOT_AVAILABLE", "This Agent is not available in the direct Bridge phase")
                return@post
            }
            request.agentType != canonicalAgentType || request.capabilities.size > 32 -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_AGENT_REQUEST", "Agent type or capabilities are invalid")
                return@post
            }
            !request.requestId.isSafeRequestId() || request.deviceId.isBlank() || request.deviceId.length > 128 -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_AGENT_REQUEST", "Agent request identity is invalid")
                return@post
            }
            request.agentDisplayName.isBlank() || request.agentDisplayName != request.agentDisplayName.trim() ||
                request.agentDisplayName.length > 256 ||
                !request.agentDisplayName.isSafeMetadata() ||
                request.connectorVersion.isBlank() || request.connectorVersion != request.connectorVersion.trim() ||
                request.connectorVersion.length > 64 ||
                !request.connectorVersion.isSafeMetadata() -> {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_METADATA", "Agent metadata is invalid")
                return@post
            }
        }
        val now = System.currentTimeMillis()
        if (request.timestampEpochMs < now - TIMESTAMP_WINDOW_MS ||
            request.timestampEpochMs > now + TIMESTAMP_WINDOW_MS
        ) {
            call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "INVALID_DEVICE_SIGNATURE", "Trusted-device signature is invalid or expired")
            return@post
        }
        val device = AgentAuthRepository.findActiveDevice(request.deviceId)
            ?: run {
                call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "INVALID_DEVICE_SIGNATURE", "Trusted-device signature is invalid or expired")
                return@post
            }
        val serverOrigin = device.authenticationOrigin
        val payload = AgentAuthProtocol.canonicalTrustedDeviceAgentRequest(
            serverOrigin = serverOrigin,
            requestId = request.requestId,
            deviceId = request.deviceId,
            agentType = requireNotNull(canonicalAgentType),
            agentDisplayName = request.agentDisplayName,
            transportAdapter = request.transportAdapter,
            connectorVersion = request.connectorVersion,
            capabilities = request.capabilities,
            timestampEpochMs = request.timestampEpochMs,
        )
        if (!AgentAuthProtocol.verifySignature(device.publicKey, payload, request.signature)) {
            call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "INVALID_DEVICE_SIGNATURE", "Trusted-device signature is invalid or expired")
            return@post
        }
        val verificationBase = runCatching {
            resolveAgentVerificationOrigin(request.verificationOrigin, serverOrigin)
        }.getOrElse {
            call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_VERIFICATION_ORIGIN", "Silk Web origin is invalid")
            return@post
        }
        val created = runCatching {
            AgentAuthRepository.createAgentAddition(
                request = request,
                device = device,
                canonicalAgentType = canonicalAgentType,
                serverOrigin = serverOrigin,
            )
        }.getOrElse { error ->
            val authError = error as? AgentAuthException
            call.respondAgentError(
                io.ktor.http.HttpStatusCode.Conflict,
                authError?.errorCode ?: "AGENT_REQUEST_FAILED",
                authError?.message ?: "Could not create trusted-device Agent request",
            )
            return@post
        }
        call.respond(
            CreateAgentPairingResponse(
                pairingId = created.record.pairingId,
                devicePollSecret = created.devicePollSecret,
                serverOrigin = serverOrigin,
                verificationUri = pairingVerificationUri(verificationBase, created.userCode),
                userCode = created.userCode,
                expiresAtEpochMs = created.record.expiresAt.asEpochMillis(),
                agentWebSocketUri = toWebSocketOrigin(serverOrigin) + "/agent-connect",
            )
        )
    }

    get("/api/agent-pairings/{pairingId}/status") {
        val pairingId = call.parameters["pairingId"].orEmpty()
        val secret = call.request.headers[AgentAuthProtocol.DEVICE_POLL_SECRET_HEADER].orEmpty()
        if (pairingId.isBlank() || secret.isBlank()) {
            call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "INVALID_PAIRING_CREDENTIAL", "Pairing status credential is invalid")
            return@get
        }
        val record = AgentAuthRepository.findForDevicePoll(pairingId, secret)
            ?: run {
                call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "INVALID_PAIRING_CREDENTIAL", "Pairing status credential is invalid")
                return@get
            }
        call.respond(record.toStatusResponse())
    }

    post("/api/agent-pairings/{pairingId}/proof") {
        val pairingId = call.parameters["pairingId"].orEmpty()
        if (!agentAuthRateLimiter.allow(
                "proof:${call.request.local.remoteAddress}:$pairingId",
                PAIRING_PROOF_LIMIT,
            )
        ) {
            call.respondAgentError(io.ktor.http.HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Too many device proof attempts")
            return@post
        }
        val secret = call.request.headers[AgentAuthProtocol.DEVICE_POLL_SECRET_HEADER].orEmpty()
        val request = runCatching { call.receive<CompleteAgentPairingRequest>() }.getOrElse {
            call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid device proof")
            return@post
        }
        val record = AgentAuthRepository.findForDevicePoll(pairingId, secret)
        if (record == null) {
            call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "INVALID_PAIRING_CREDENTIAL", "Pairing proof credential is invalid")
            return@post
        }
        if (!record.isWaitingForDeviceProof()) {
            call.respondAgentError(io.ktor.http.HttpStatusCode.Conflict, "PAIRING_INVALID_STATE", "Pairing is not waiting for device proof")
            return@post
        }
        val proofChallengeId = requireNotNull(record.proofChallengeId)
        val proofNonce = requireNotNull(record.proofNonce)
        val proofExpiresAt = requireNotNull(record.proofExpiresAt)
        val now = System.currentTimeMillis()
        if (proofExpiresAt.asEpochMillis() < now ||
            kotlin.math.abs(now - request.timestampEpochMs) > TIMESTAMP_WINDOW_MS ||
            request.challengeId != proofChallengeId
        ) {
            call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "INVALID_DEVICE_PROOF", "Device proof is invalid or expired")
            return@post
        }
        val payload = AgentAuthProtocol.canonicalPairingProof(
            serverOrigin = record.serverOrigin,
            pairingId = record.pairingId,
            challengeId = proofChallengeId,
            nonce = proofNonce,
            deviceId = requireNotNull(record.deviceId),
            agentInstanceId = requireNotNull(record.agentInstanceId),
            publicKeyFingerprint = record.publicKeyFingerprint,
            timestampEpochMs = request.timestampEpochMs,
        )
        if (!AgentAuthProtocol.verifySignature(record.publicKey, payload, request.signature)) {
            call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "INVALID_DEVICE_PROOF", "Device proof is invalid or expired")
            return@post
        }
        val enrolled = runCatching { AgentAuthRepository.completeEnrollment(record) }.getOrElse { error ->
            val authError = error as? AgentAuthException
            call.respondAgentError(
                io.ktor.http.HttpStatusCode.Conflict,
                authError?.errorCode ?: "PAIRING_PROOF_FAILED",
                authError?.message ?: "Pairing proof could not be completed",
            )
            return@post
        }
        call.respond(
            CompleteAgentPairingResponse(
                pairingId = enrolled.pairingId,
                state = enrolled.state,
                deviceId = requireNotNull(enrolled.deviceId),
                agentInstanceId = requireNotNull(enrolled.agentInstanceId),
            )
        )
    }

    authenticate("jwt-auth") {
        post("/api/agent-pairings/preview") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@post call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            if (!agentAuthRateLimiter.allow(
                    "code:${call.request.local.remoteAddress}:$userId",
                    PAIRING_CODE_ATTEMPT_LIMIT,
                )
            ) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Too many pairing code attempts")
                return@post
            }
            val request = runCatching { call.receive<com.silk.backend.agents.auth.AgentPairingCodeRequest>() }.getOrElse {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid pairing code")
                return@post
            }
            val record = AgentAuthRepository.findByUserCode(request.userCode)
            if (record == null || record.state == AgentPairingState.EXPIRED) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.NotFound, "PAIRING_NOT_FOUND", "Pairing code is invalid or expired")
                return@post
            }
            if (!record.isVisibleToPairingOwner(userId)) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.NotFound, "PAIRING_NOT_FOUND", "Pairing code is invalid or expired")
                return@post
            }
            call.respond(record.toPreviewResponse())
        }

        post("/api/agent-pairings/approve") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@post call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            if (!agentAuthRateLimiter.allow(
                    "code:${call.request.local.remoteAddress}:$userId",
                    PAIRING_CODE_ATTEMPT_LIMIT,
                )
            ) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Too many pairing code attempts")
                return@post
            }
            val request = runCatching { call.receive<AgentPairingApprovalRequest>() }.getOrElse {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid pairing approval")
                return@post
            }
            val result = runCatching { AgentAuthRepository.decidePairing(userId, request.userCode, request.approve) }
                .getOrElse { error ->
                    val authError = error as? AgentAuthException
                    val status = when (authError?.errorCode) {
                        "PAIRING_NOT_FOUND" -> io.ktor.http.HttpStatusCode.NotFound
                        "DEVICE_ALREADY_ENROLLED", "AGENT_ALREADY_ENROLLED", "DEVICE_NOT_ACTIVE",
                        "PAIRING_INVALID_STATE" -> io.ktor.http.HttpStatusCode.Conflict
                        else -> io.ktor.http.HttpStatusCode.BadRequest
                    }
                    call.respondAgentError(status, authError?.errorCode ?: "PAIRING_APPROVAL_FAILED", authError?.message ?: "Pairing approval failed")
                    return@post
                }
            call.respond(
                AgentPairingApprovalResponse(
                    pairingId = result.pairingId,
                    state = result.state,
                    expiresAtEpochMs = result.expiresAt.asEpochMillis(),
                )
            )
        }

        get("/api/agent-devices") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@get call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            val devices = AgentAuthRepository.listDevices(userId).map { device ->
                device.copy(
                    connected = AgentConnectionRegistry.isDeviceConnected(device.deviceId) ||
                        AgentBridgeConnectionRegistry.isDeviceConnected(device.deviceId) ||
                        AgentHostConnectionRegistry.isDeviceConnected(device.deviceId)
                )
            }
            call.respond(AgentDeviceListResponse(devices))
        }

        delete("/api/agent-devices/{deviceId}") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@delete call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            val deviceId = call.parameters["deviceId"].orEmpty()
            val agentIds = AgentAuthRepository.revokeDevice(userId, deviceId)
            if (agentIds == null) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.NotFound, "DEVICE_NOT_FOUND", "Device not found")
                return@delete
            }
            AgentConnectionRegistry.disconnectDevice(deviceId)
            AgentBridgeConnectionRegistry.disconnectDevice(deviceId)
            AgentHostConnectionRegistry.disconnectDevice(deviceId)
            call.respond(AgentManagementActionResponse(true, "Device revoked"))
        }

        get("/api/agent-instances") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@get call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            val agents = AgentAuthRepository.listAgents(userId).map { agent ->
                agent.copy(
                    connected = AgentConnectionRegistry.isAgentConnected(agent.agentInstanceId) ||
                        AgentBridgeConnectionRegistry.isAgentConnected(agent.agentInstanceId) ||
                        AgentHostConnectionRegistry.isAgentConnected(agent.agentInstanceId)
                )
            }
            call.respond(AgentInstanceListResponse(agents))
        }

        get("/api/agent-security-events") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@get call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
            call.respond(AgentSecurityEventListResponse(AgentAuthRepository.listSecurityEvents(userId, limit)))
        }

        post("/api/agent-revocation-history/cleanup") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@post call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            val result = AgentAuthRepository.cleanupRevokedRecords(userId = userId)
            call.respond(
                AgentRevocationCleanupResponse(
                    message = "已清理超过 ${result.retentionDays} 天的撤销历史",
                    retentionDays = result.retentionDays,
                    deletedDevices = result.deletedDevices,
                    deletedAgents = result.deletedAgents,
                    deletedBindings = result.deletedBindings,
                )
            )
        }

        delete("/api/agent-instances/{agentInstanceId}") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@delete call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            val agentInstanceId = call.parameters["agentInstanceId"].orEmpty()
            if (!AgentAuthRepository.revokeAgent(userId, agentInstanceId)) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.NotFound, "AGENT_NOT_FOUND", "Agent not found")
                return@delete
            }
            AgentConnectionRegistry.disconnectAgent(agentInstanceId)
            AgentBridgeConnectionRegistry.disconnectAgent(agentInstanceId)
            AgentHostConnectionRegistry.disconnectAgent(agentInstanceId)
            call.respond(AgentManagementActionResponse(true, "Agent revoked"))
        }

        get("/api/agent-bindings") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@get call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            val bindings = AgentAuthRepository.listBindings().mapNotNull { binding ->
                binding.visibleTo(userId)
            }
            call.respond(AgentBindingListResponse(bindings))
        }

        post("/api/agent-bindings") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@post call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            val request = runCatching { call.receive<CreateAgentBindingRequest>() }.getOrElse {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid Agent binding")
                return@post
            }
            if (request.targetId.isBlank()) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_TARGET", "Binding target is required")
                return@post
            }
            if (!request.hasCompatibleMessageScope()) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_MESSAGE_SCOPE", "Binding messageScope does not match targetType")
                return@post
            }
            val ownerId = AgentAuthRepository.findActiveAgentOwner(request.agentInstanceId)
            if (ownerId == null) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.NotFound, "AGENT_NOT_FOUND", "Agent is not active")
                return@post
            }
            val targetAccess = bindingTargetAccess(userId, request.targetType, request.targetId)
            if (!targetAccess.canRequest || ownerId != userId && !targetAccess.canManage) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.Forbidden, "BINDING_NOT_ALLOWED", "User cannot request this Agent binding")
                return@post
            }
            val binding = runCatching {
                AgentAuthRepository.createBinding(
                    userId = userId,
                    request = request,
                    approveAsAgentOwner = ownerId == userId,
                    approveAsTargetManager = targetAccess.canManage,
                )
                }
                .getOrElse { error ->
                    val authError = error as? AgentAuthException
                    if (authError == null) {
                        call.application.environment.log.error("Failed to create Agent binding", error)
                        call.respondAgentError(io.ktor.http.HttpStatusCode.InternalServerError, "BINDING_CREATE_FAILED", "Could not create Agent binding")
                        return@post
                    }
                    val status = when (authError.errorCode) {
                        "AGENT_NOT_FOUND" -> io.ktor.http.HttpStatusCode.NotFound
                        "INVALID_MENTION_ALIAS" -> io.ktor.http.HttpStatusCode.BadRequest
                        else -> io.ktor.http.HttpStatusCode.Conflict
                    }
                    call.respondAgentError(status, authError.errorCode, authError.message)
                    return@post
                }
            call.respond(io.ktor.http.HttpStatusCode.Created, requireNotNull(binding.visibleTo(userId)))
        }

        put("/api/agent-bindings/{bindingId}") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@put call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            val bindingId = call.parameters["bindingId"].orEmpty()
            val request = runCatching { call.receive<UpdateAgentBindingRequest>() }.getOrElse {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid Agent binding")
                return@put
            }
            if (request.targetId.isBlank()) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_TARGET", "Binding target is required")
                return@put
            }
            if (!request.hasCompatibleMessageScope()) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_MESSAGE_SCOPE", "Binding messageScope does not match targetType")
                return@put
            }
            val current = AgentAuthRepository.findBinding(bindingId)
            if (current == null || current.createdBy != userId) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.NotFound, "BINDING_NOT_FOUND", "Editable Agent binding not found")
                return@put
            }
            val ownerId = AgentAuthRepository.findActiveAgentOwner(request.agentInstanceId)
            if (ownerId == null) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.NotFound, "AGENT_NOT_FOUND", "Agent is not active")
                return@put
            }
            val targetAccess = bindingTargetAccess(userId, request.targetType, request.targetId)
            if (!targetAccess.canRequest || ownerId != userId && !targetAccess.canManage) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.Forbidden, "BINDING_NOT_ALLOWED", "User cannot update this Agent binding")
                return@put
            }
            val binding = runCatching {
                AgentAuthRepository.updateBinding(
                    userId = userId,
                    bindingId = bindingId,
                    request = request,
                    approveAsAgentOwner = ownerId == userId,
                    approveAsTargetManager = targetAccess.canManage,
                )
            }.getOrElse { error ->
                val authError = error as? AgentAuthException
                if (authError == null) {
                    call.application.environment.log.error("Failed to update Agent binding", error)
                    call.respondAgentError(io.ktor.http.HttpStatusCode.InternalServerError, "BINDING_UPDATE_FAILED", "Could not update Agent binding")
                    return@put
                }
                val status = when (authError.errorCode) {
                    "AGENT_NOT_FOUND", "BINDING_NOT_FOUND" -> io.ktor.http.HttpStatusCode.NotFound
                    "INVALID_MENTION_ALIAS" -> io.ktor.http.HttpStatusCode.BadRequest
                    else -> io.ktor.http.HttpStatusCode.Conflict
                }
                call.respondAgentError(status, authError.errorCode, authError.message)
                return@put
            }
            call.respond(requireNotNull(binding.visibleTo(userId)))
        }

        post("/api/agent-bindings/{bindingId}/approval") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@post call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            val bindingId = call.parameters["bindingId"].orEmpty()
            val request = runCatching { call.receive<AgentBindingApprovalRequest>() }.getOrElse {
                call.respondAgentError(io.ktor.http.HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid Agent binding approval")
                return@post
            }
            val current = AgentAuthRepository.findBinding(bindingId)
            if (current == null) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.NotFound, "BINDING_NOT_FOUND", "Agent binding not found")
                return@post
            }
            val targetAccess = bindingTargetAccess(userId, current.targetType, current.targetId)
            val ownsAgent = current.ownerId == userId
            if (!ownsAgent && !targetAccess.canManage) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.Forbidden, "BINDING_APPROVAL_NOT_ALLOWED", "User cannot approve this Agent binding")
                return@post
            }
            val binding = runCatching {
                AgentAuthRepository.decideBindingApproval(
                    userId = userId,
                    bindingId = bindingId,
                    approve = request.approve,
                    approveAsAgentOwner = ownsAgent,
                    approveAsTargetManager = targetAccess.canManage,
                )
            }.getOrElse { error ->
                val authError = error as? AgentAuthException
                val status = if (authError?.errorCode == "BINDING_NOT_PENDING") {
                    io.ktor.http.HttpStatusCode.Conflict
                } else {
                    io.ktor.http.HttpStatusCode.Forbidden
                }
                call.respondAgentError(status, authError?.errorCode ?: "BINDING_APPROVAL_FAILED", authError?.message ?: "Could not approve Agent binding")
                return@post
            }
            call.respond(requireNotNull(binding.visibleTo(userId)))
        }

        delete("/api/agent-bindings/{bindingId}") {
            val userId = call.principal<UserIdPrincipal>()?.name
                ?: return@delete call.respondAgentError(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Login required")
            val bindingId = call.parameters["bindingId"].orEmpty()
            val binding = AgentAuthRepository.findBinding(bindingId)
            val canManageTarget = binding?.let {
                bindingTargetAccess(userId, it.targetType, it.targetId).canManage
            } ?: false
            if (!AgentAuthRepository.revokeBinding(userId, bindingId, canManageTarget)) {
                call.respondAgentError(io.ktor.http.HttpStatusCode.NotFound, "BINDING_NOT_FOUND", "Binding not found")
                return@delete
            }
            call.respond(AgentManagementActionResponse(true, "Binding revoked"))
        }
    }

    webSocket("/agent-connect") {
        val authenticated = authenticateAgentSocket(agentChallengeService) ?: return@webSocket
        val currentIdentity = authenticated.identity
        if (authenticated.connectionMode == AGENT_HOST_CONNECTION_MODE) {
            runMultiplexedAgentHost(currentIdentity)
            return@webSocket
        }
        val connectionKey = "${currentIdentity.deviceId}::${currentIdentity.agentInstanceId}"
        val replaced = AgentConnectionRegistry.registerAndReturnPrevious(connectionKey, this)
        runCatching { replaced?.close(CloseReason(CloseReason.Codes.NORMAL, "replaced by newer connection")) }
        try {
            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val type = runCatching {
                        agentAuthJson.parseToJsonElement(frame.readText()).jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    if (type == "heartbeat") {
                        val heartbeat = runCatching { agentAuthJson.decodeFromString<AgentSocketHeartbeat>(frame.readText()) }.getOrNull()
                        if (heartbeat != null) {
                            send(
                                Frame.Text(
                                    agentAuthJson.encodeToString(
                                        AgentSocketHeartbeatAck(serverTimeEpochMs = System.currentTimeMillis())
                                    )
                                )
                            )
                        }
                    } else {
                        send(Frame.Text(agentAuthJson.encodeToString(AgentSocketError(error = "NOT_IMPLEMENTED", message = "Agent RPC is not enabled in the authentication phase"))))
                    }
                }
            }
        } finally {
            AgentConnectionRegistry.unregister(connectionKey, this)
        }
    }
}

private data class AgentHostLogicalStream(
    val identity: ActiveAgentIdentity,
    val transport: AcpMultiplexedTransport,
    val client: AcpClient,
    val scope: CoroutineScope,
)

@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.runMultiplexedAgentHost(
    authenticatedIdentity: ActiveAgentIdentity,
) = coroutineScope {
    val streams = ConcurrentHashMap<String, AgentHostLogicalStream>()
    val openMutexes = ConcurrentHashMap<String, Mutex>()
    val sendMutex = Mutex()
    val openingSupervisor = SupervisorJob(coroutineContext[Job])
    val openingScope = CoroutineScope(coroutineContext + openingSupervisor)

    suspend fun sendEncoded(encoded: String) {
        withTimeout(10_000L) {
            sendMutex.withLock { send(Frame.Text(encoded)) }
        }
    }

    suspend fun sendError(error: String, message: String, agentInstanceId: String? = null) {
        sendEncoded(
            agentAuthJson.encodeToString(
                AgentSocketError(error = error, message = message, agentInstanceId = agentInstanceId),
            ),
        )
    }

    suspend fun closeLogicalStream(
        agentInstanceId: String,
        reason: String,
        notifyHost: Boolean,
        expected: AgentHostLogicalStream? = null,
    ) {
        val removed: AgentHostLogicalStream? = if (expected == null) {
            streams.remove(agentInstanceId)
        } else if (streams.remove(agentInstanceId, expected)) {
            expected
        } else {
            null
        }
        val stream = removed ?: return
        AgentHostConnectionRegistry.unregisterAgent(agentInstanceId, this@runMultiplexedAgentHost)
        AcpRegistry.unregister(stream.identity.agentInstanceId, stream.client)
        stream.transport.closeFromHost(reason)
        stream.scope.cancel()
        if (!AcpRegistry.isConnectedInstance(stream.identity.agentInstanceId)) {
            AgentRuntime.handleAgentDisconnect(
                stream.identity.userId,
                stream.identity.agentType,
                stream.identity.agentInstanceId,
            )
        }
        if (notifyHost) {
            sendEncoded(
                agentAuthJson.encodeToString(
                    AgentHostClose(agentInstanceId = agentInstanceId, reason = reason),
                ),
            )
        }
    }

    suspend fun openLogicalStream(request: AgentHostOpen) {
        if (request.protocolVersion != AgentAuthProtocol.VERSION) {
            sendError("UNSUPPORTED_PROTOCOL", "Unsupported Host protocol version", request.agentInstanceId)
            return
        }
        val candidate = AgentAuthRepository.findActiveIdentity(
            authenticatedIdentity.deviceId,
            request.agentInstanceId,
        )
        if (candidate == null || candidate.userId != authenticatedIdentity.userId) {
            sendError("AGENT_NOT_AVAILABLE", "Agent is not active on this device", request.agentInstanceId)
            return
        }
        if (candidate.agentType != request.agentType || !AgentRegistry.isRegistered(request.agentType)) {
            sendError("AGENT_TYPE_MISMATCH", "Agent type does not match enrollment", request.agentInstanceId)
            return
        }
        openMutexes.computeIfAbsent(request.agentInstanceId) { Mutex() }.withLock {
            val identity = AgentAuthRepository.findActiveIdentity(
                authenticatedIdentity.deviceId,
                request.agentInstanceId,
            )
            if (identity == null || identity.userId != authenticatedIdentity.userId) {
                sendError("AGENT_NOT_AVAILABLE", "Agent is not active on this device", request.agentInstanceId)
                return@withLock
            }
            if (identity.agentType != request.agentType) {
                sendError("AGENT_TYPE_MISMATCH", "Agent type does not match enrollment", request.agentInstanceId)
                return@withLock
            }
            closeLogicalStream(request.agentInstanceId, "reopened by Host", notifyHost = false)

            val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            lateinit var stream: AgentHostLogicalStream
            lateinit var transport: AcpMultiplexedTransport
            transport = AcpMultiplexedTransport(
                sendToHost = { line ->
                    val payload = agentAuthJson.parseToJsonElement(line).jsonObject
                    sendEncoded(
                        agentAuthJson.encodeToString(
                            AgentHostRpc(agentInstanceId = identity.agentInstanceId, payload = payload),
                        ),
                    )
                },
                onClose = { reason ->
                    closeLogicalStream(identity.agentInstanceId, reason, notifyHost = true, expected = stream)
                },
            )
            val client = AcpClient(transport, streamScope)
            stream = AgentHostLogicalStream(identity, transport, client, streamScope)
            streams[identity.agentInstanceId] = stream
            val evicted = AcpRegistry.put(
                userId = identity.userId,
                agentType = identity.agentType,
                client = client,
                remoteIp = call.request.local.remoteAddress,
                authenticationMode = AcpRegistry.AuthenticationMode.DEVICE_SIGNATURE,
                agentInstanceId = identity.agentInstanceId,
                capabilities = identity.capabilities,
            )
            runCatching { evicted?.close("replaced by the same AgentInstance connection") }
            val previousDisconnect = AgentHostConnectionRegistry.registerAgent(
                deviceId = identity.deviceId,
                agentInstanceId = identity.agentInstanceId,
                session = this@runMultiplexedAgentHost,
            ) { reason ->
                closeLogicalStream(identity.agentInstanceId, reason, notifyHost = true, expected = stream)
            }
            runCatching { previousDisconnect?.invoke("replaced by newer logical stream") }

            try {
                client.initialize(
                    InitializeParams(
                        protocolVersion = "0.2",
                        clientCapabilities = ClientCapabilities(
                            fs = FsCapability(readTextFile = true, writeTextFile = true),
                            terminal = false,
                        ),
                    ),
                )
                AgentAuthRepository.touchAuthenticated(identity, call.request.local.remoteAddress)
                if (streams[identity.agentInstanceId] !== stream) return@withLock
                sendEncoded(
                    agentAuthJson.encodeToString(
                        AgentHostOpened(
                            agentInstanceId = identity.agentInstanceId,
                            agentType = identity.agentType,
                            capabilities = identity.capabilities,
                        ),
                    ),
                )
            } catch (error: CancellationException) {
                closeLogicalStream(
                    identity.agentInstanceId,
                    "Host connection closed",
                    notifyHost = false,
                    expected = stream,
                )
                throw error
            } catch (error: Exception) {
                closeLogicalStream(
                    identity.agentInstanceId,
                    "initialize failed",
                    notifyHost = false,
                    expected = stream,
                )
                sendError(
                    "AGENT_INITIALIZE_FAILED",
                    error.message?.take(256) ?: "Agent initialization failed",
                    identity.agentInstanceId,
                )
            }
        }
    }

    val replaced = AgentHostConnectionRegistry.registerDevice(authenticatedIdentity.deviceId, this@runMultiplexedAgentHost)
    runCatching { replaced?.close(CloseReason(CloseReason.Codes.NORMAL, "replaced by newer Host connection")) }
    try {
        incoming.consumeEach { frame ->
            if (frame !is Frame.Text) return@consumeEach
            val text = frame.readText()
            val type = runCatching {
                agentAuthJson.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            when (type) {
                "heartbeat" -> {
                    val heartbeat = runCatching { agentAuthJson.decodeFromString<AgentSocketHeartbeat>(text) }.getOrNull()
                    if (heartbeat != null) {
                        sendEncoded(
                            agentAuthJson.encodeToString(
                                AgentSocketHeartbeatAck(serverTimeEpochMs = System.currentTimeMillis()),
                            ),
                        )
                    }
                }
                "agent_open" -> {
                    val request = runCatching { agentAuthJson.decodeFromString<AgentHostOpen>(text) }.getOrNull()
                    if (request == null) {
                        sendError("INVALID_AGENT_ENVELOPE", "Invalid agent_open envelope")
                    } else {
                        openingScope.launch { openLogicalStream(request) }
                    }
                }
                "agent_rpc" -> {
                    val request = runCatching { agentAuthJson.decodeFromString<AgentHostRpc>(text) }.getOrNull()
                    val stream = request?.let { streams[it.agentInstanceId] }
                    if (request == null || request.protocolVersion != AgentAuthProtocol.VERSION) {
                        sendError("INVALID_AGENT_ENVELOPE", "Invalid agent_rpc envelope", request?.agentInstanceId)
                    } else if (stream == null) {
                        sendError("AGENT_STREAM_NOT_OPEN", "Agent logical stream is not open", request.agentInstanceId)
                    } else if (!stream.transport.tryAcceptFromHost(request.payload.toString())) {
                        closeLogicalStream(request.agentInstanceId, "logical stream backpressure", notifyHost = true)
                    }
                }
                "agent_close" -> {
                    val request = runCatching { agentAuthJson.decodeFromString<AgentHostClose>(text) }.getOrNull()
                    if (request == null || request.protocolVersion != AgentAuthProtocol.VERSION) {
                        sendError("INVALID_AGENT_ENVELOPE", "Invalid agent_close envelope", request?.agentInstanceId)
                    } else {
                        closeLogicalStream(request.agentInstanceId, request.reason.take(256), notifyHost = false)
                    }
                }
                else -> sendError("INVALID_AGENT_ENVELOPE", "Unsupported Host envelope type")
            }
        }
    } finally {
        openingSupervisor.cancelAndJoin()
        AgentHostConnectionRegistry.unregisterDevice(authenticatedIdentity.deviceId, this@runMultiplexedAgentHost)
        streams.keys.toList().forEach { agentInstanceId ->
            closeLogicalStream(agentInstanceId, "Host connection closed", notifyHost = false)
        }
    }
}

private data class BindingTargetAccess(
    val canRequest: Boolean,
    val canManage: Boolean,
)

private fun bindingTargetAccess(
    userId: String,
    targetType: AgentBindingTargetType,
    targetId: String,
): BindingTargetAccess = when (targetType) {
    AgentBindingTargetType.ROOM -> {
        val room = GroupRepository.findGroupById(targetId)
        val role = GroupRepository.getMemberRole(targetId, userId)
        val roomEligible = room != null && room.roomKind != RoomKind.SILK_PRIVATE && role != null
        BindingTargetAccess(
            canRequest = roomEligible,
            canManage = roomEligible && role in setOf(MemberRole.HOST, MemberRole.OPERATOR),
        )
    }
    AgentBindingTargetType.WORKSPACE -> {
        val workspace = WorkspaceManager().getWorkspace(targetId)
        val roomMember = workspace != null && GroupRepository.isUserInGroup(workspace.roomId, userId)
        val active = workspace?.lifecycleState == WorkspaceLifecycleState.ACTIVE
        val ownsWorkspace = workspace?.ownerId == userId
        BindingTargetAccess(
            canRequest = roomMember && active && (ownsWorkspace || workspace?.visibility == WorkspaceVisibility.SHARED),
            canManage = roomMember && active && ownsWorkspace,
        )
    }
}

private fun AgentBindingDto.visibleTo(userId: String): AgentBindingDto? {
    val ownsAgent = ownerId == userId
    val targetAccess = bindingTargetAccess(userId, targetType, targetId)
    val created = createdBy == userId
    if (!ownsAgent && !targetAccess.canManage && !created) return null
    val actionable = status in setOf(AgentBindingStatus.PENDING, AgentBindingStatus.ACTIVE)
    return copy(
        canApproveAsAgentOwner = status == AgentBindingStatus.PENDING &&
            ownsAgent && agentOwnerApprovedAtEpochMs == null,
        canApproveAsTargetManager = status == AgentBindingStatus.PENDING &&
            targetAccess.canManage && targetApprovedAtEpochMs == null,
        canEdit = actionable && created && (ownsAgent || targetAccess.canManage),
        canRevoke = actionable && (ownsAgent || targetAccess.canManage),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondAgentError(
    status: io.ktor.http.HttpStatusCode,
    error: String,
    message: String,
) {
    respond(status, AgentAuthErrorResponse(error = error, message = message))
}

private fun AgentPairingRecord.toPreviewResponse() = AgentPairingPreviewResponse(
    pairingId = pairingId,
    pairingKind = pairingKind,
    state = state,
    deviceId = deviceId,
    deviceName = deviceName,
    platform = platform,
    publicKeyFingerprint = publicKeyFingerprint,
    agentType = agentType,
    agentDisplayName = agentDisplayName,
    transportAdapter = transportAdapter,
    connectorVersion = connectorVersion,
    capabilities = capabilities,
    expiresAtEpochMs = expiresAt.asEpochMillis(),
)

private fun AgentPairingRecord.toStatusResponse(): AgentPairingStatusResponse {
    val challenge = if (isWaitingForDeviceProof()) {
        val challengeId = requireNotNull(proofChallengeId)
        val nonce = requireNotNull(proofNonce)
        val enrolledDeviceId = requireNotNull(deviceId)
        val enrolledAgentInstanceId = requireNotNull(agentInstanceId)
        val challengeExpiresAt = requireNotNull(proofExpiresAt)
        PairingDeviceProofChallenge(
            challengeId = challengeId,
            nonce = nonce,
            serverOrigin = serverOrigin,
            deviceId = enrolledDeviceId,
            agentInstanceId = enrolledAgentInstanceId,
            publicKeyFingerprint = publicKeyFingerprint,
            expiresAtEpochMs = challengeExpiresAt.asEpochMillis(),
            serverTimeEpochMs = System.currentTimeMillis(),
        )
    } else {
        null
    }
    return AgentPairingStatusResponse(
        pairingId = pairingId,
        state = state,
        expiresAtEpochMs = expiresAt.asEpochMillis(),
        proofChallenge = challenge,
        deviceId = deviceId,
        agentInstanceId = agentInstanceId,
        serverTimeEpochMs = System.currentTimeMillis(),
    )
}

private fun AgentPairingRecord.isWaitingForDeviceProof(): Boolean =
    state == AgentPairingState.DEVICE_PROOF_PENDING &&
        proofChallengeId != null &&
        proofNonce != null &&
        proofExpiresAt != null &&
        deviceId != null &&
        agentInstanceId != null

internal class AgentAuthRateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = RATE_WINDOW_MS,
    private val maxBuckets: Int = RATE_LIMIT_MAX_BUCKETS,
) {
    private data class Bucket(var windowStart: Long, var count: Int)
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private var lastCleanupAt = clock()

    @Synchronized
    fun allow(key: String, maxCount: Int): Boolean {
        require(maxCount > 0) { "Rate limit must be positive" }
        require(maxBuckets > 0) { "Rate-limit bucket cap must be positive" }
        val now = clock()
        if (now - lastCleanupAt >= windowMs) {
            buckets.entries.removeIf { (_, bucket) -> now - bucket.windowStart >= windowMs }
            lastCleanupAt = now
        }
        if (!buckets.containsKey(key) && buckets.size >= maxBuckets) return false
        val bucket = buckets.compute(key) { _, current ->
            if (current == null || now - current.windowStart >= windowMs) {
                Bucket(now, 1)
            } else {
                current.count++
                current
            }
        } ?: return false
        return bucket.count <= maxCount
    }

    internal fun bucketCountForTest(): Int = buckets.size
}

internal fun resolveAgentServerOrigin(
    connectionOrigin: String,
    configuredOrigin: String? = configuredValue("BACKEND_BASE_URL"),
): String = configuredOrigin?.let(::normalizeOrigin)
        ?: normalizeOrigin(connectionOrigin)

internal fun resolveAgentVerificationOrigin(
    requestedOrigin: String?,
    serverOrigin: String,
    configuredOrigin: String? = configuredValue("BACKEND_WEB_APP_BASE_URL"),
): String = configuredOrigin?.let(::normalizeOrigin)
        ?: requestedOrigin?.takeIf { it.isNotBlank() }?.let(::normalizeOrigin)
        ?: serverOrigin

internal fun pairingVerificationUri(verificationOrigin: String, userCode: String): String {
    val normalizedCode = AgentAuthProtocol.normalizeUserCode(userCode)
    require(normalizedCode.length == 8) { "Silk pairing code must contain eight characters" }
    val formattedCode = "${normalizedCode.take(4)}-${normalizedCode.drop(4)}"
    return "${verificationOrigin.trimEnd('/')}/device#code=$formattedCode"
}

private fun configuredValue(key: String): String? = listOf(
    System.getProperty(
        when (key) {
            "BACKEND_BASE_URL" -> "silk.backendBaseUrl"
            "BACKEND_WEB_APP_BASE_URL" -> "silk.webAppBaseUrl"
            else -> "silk.${key.lowercase()}"
        }
    ),
    System.getenv(key),
    EnvLoader.get(key),
).firstOrNull { !it.isNullOrBlank() }?.trim()

private fun normalizeOrigin(value: String): String {
    val uri = URI(value.trim())
    require(uri.scheme?.lowercase() in setOf("http", "https")) { "Silk origin must use HTTP or HTTPS" }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Silk origin must not contain credentials or query" }
    require(uri.path.isNullOrBlank() || uri.path == "/") { "Silk origin must not contain a path" }
    val scheme = uri.scheme.lowercase()
    val host = uri.host?.lowercase() ?: throw IllegalArgumentException("Silk origin has no host")
    val defaultPort = if (scheme == "https") 443 else 80
    val port = if (uri.port < 0 || uri.port == defaultPort) "" else ":${uri.port}"
    return "$scheme://$host$port"
}

private fun toWebSocketOrigin(origin: String): String = when {
    origin.startsWith("https://") -> "wss://${origin.removePrefix("https://")}"
    origin.startsWith("http://") -> "ws://${origin.removePrefix("http://")}"
    else -> throw IllegalArgumentException("Unsupported Silk origin")
}

private fun resolveWebIndexFile(): File? = listOf(
    File("static/index.html"),
    File("backend/static/index.html"),
    File("../frontend/webApp/build/dist/js/productionExecutable/index.html"),
    File("frontend/webApp/build/dist/js/productionExecutable/index.html"),
).firstOrNull { it.isFile }

private fun String.isSafeMetadata(): Boolean = all { character ->
    !character.isISOControl() && character != '\n' && character != '\r'
}

private fun AgentPairingRecord.isVisibleToPairingOwner(userId: String): Boolean =
    intendedOwnerId == userId && (approvedBy == null || approvedBy == userId)

private fun String.isSafeRequestId(): Boolean = length in 22..128 && all { character ->
    character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
        character == '-' || character == '_'
}

private fun CreateAgentBindingRequest.hasCompatibleMessageScope(): Boolean = when (targetType) {
    AgentBindingTargetType.ROOM -> messageScope == AgentBindingMessageScope.TEAM
    AgentBindingTargetType.WORKSPACE -> messageScope == AgentBindingMessageScope.WORKSPACE
}

private fun UpdateAgentBindingRequest.hasCompatibleMessageScope(): Boolean = when (targetType) {
    AgentBindingTargetType.ROOM -> messageScope == AgentBindingMessageScope.TEAM
    AgentBindingTargetType.WORKSPACE -> messageScope == AgentBindingMessageScope.WORKSPACE
}
