package com.silk.backend.agents.auth

import com.silk.backend.database.AgentBindings
import com.silk.backend.database.AgentBindingAuditEvents
import com.silk.backend.database.AgentDevices
import com.silk.backend.database.AgentInstances
import com.silk.backend.database.AgentPairingRequests
import com.silk.backend.database.AgentSecurityEvents
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.sql.SQLException
import java.util.UUID

internal class AgentAuthException(
    val errorCode: String,
    override val message: String,
) : IllegalStateException(message)

internal data class PairingCreation(
    val record: AgentPairingRecord,
    val userCode: String,
    val devicePollSecret: String,
)

internal data class AgentPairingRecord(
    val pairingId: String,
    val pairingKind: AgentPairingKind,
    val trustedDeviceRequestId: String?,
    val protocolVersion: Int,
    val publicKey: String,
    val publicKeyFingerprint: String,
    val keyAlgorithm: String,
    val deviceName: String,
    val platform: String,
    val agentType: String,
    val agentDisplayName: String,
    val transportAdapter: AgentTransportAdapter,
    val connectorVersion: String,
    val capabilities: Set<AgentCapability>,
    val intendedOwnerId: String?,
    val serverOrigin: String,
    val devicePollSecretHash: String,
    val state: AgentPairingState,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val approvedBy: String?,
    val deviceId: String?,
    val agentInstanceId: String?,
    val proofChallengeId: String?,
    val proofNonce: String?,
    val proofExpiresAt: LocalDateTime?,
)

internal data class ActiveDeviceIdentity(
    val userId: String,
    val deviceId: String,
    val publicKey: String,
    val publicKeyFingerprint: String,
    val keyAlgorithm: String,
    val displayName: String,
    val platform: String,
    val authenticationOrigin: String,
)

internal data class ActiveAgentIdentity(
    val userId: String,
    val deviceId: String,
    val agentInstanceId: String,
    val publicKey: String,
    val agentType: String,
    val capabilities: Set<AgentCapability>,
    val authenticationOrigin: String,
)

/** Transactional persistence for device enrollment, Agent instances, bindings, and pairing requests. */
@Suppress("TooManyFunctions", "LargeClass")
internal object AgentAuthRepository {
    private const val PAIRING_LIFETIME_MINUTES = 5L
    private const val PAIRING_RETENTION_DAYS = 7L
    private const val PROOF_LIFETIME_SECONDS = 90L
    private val json = Json { ignoreUnknownKeys = true }

    fun createPairing(
        request: CreateAgentPairingRequest,
        intendedOwnerId: String,
        normalizedPublicKey: String,
        canonicalAgentType: String,
        serverOrigin: String,
        now: LocalDateTime = nowUtc(),
    ): PairingCreation {
        pruneExpiredPairings(now.minusDays(PAIRING_RETENTION_DAYS))
        repeat(5) {
            val pairingId = UUID.randomUUID().toString()
            val userCode = AgentAuthProtocol.randomUserCode()
            val pollSecret = AgentAuthProtocol.randomOpaqueSecret()
            val expiresAt = now.plusMinutes(PAIRING_LIFETIME_MINUTES)
            val inserted = runCatching {
                transaction {
                    AgentPairingRequests.insert { row ->
                        row[id] = pairingId
                        row[requestKind] = AgentPairingKind.DEVICE_ENROLLMENT.name
                        row[trustedDeviceRequestId] = null
                        row[protocolVersion] = request.protocolVersion
                        row[publicKey] = normalizedPublicKey
                        row[publicKeyFingerprint] = AgentAuthProtocol.fingerprint(normalizedPublicKey)
                        row[keyAlgorithm] = request.keyAlgorithm
                        row[requestedDeviceName] = request.deviceName.trim()
                        row[requestedPlatform] = request.platform.trim().lowercase()
                        row[requestedAgentType] = canonicalAgentType
                        row[requestedAgentDisplayName] = request.agentDisplayName.trim()
                        row[requestedTransportAdapter] = request.transportAdapter.name
                        row[requestedConnectorVersion] = request.connectorVersion.trim()
                        row[requestedCapabilitiesJson] = json.encodeToString(request.capabilities)
                        row[AgentPairingRequests.intendedOwnerId] = intendedOwnerId
                        row[AgentPairingRequests.serverOrigin] = serverOrigin
                        row[userCodeHash] = hashUserCode(userCode)
                        row[devicePollSecretHash] = AgentAuthProtocol.sha256Hex(pollSecret)
                        row[state] = AgentPairingState.USER_PENDING.name
                        row[createdAt] = now
                        row[AgentPairingRequests.expiresAt] = expiresAt
                    }
                }
            }.isSuccess
            if (inserted) {
                val record = findById(pairingId)
                    ?: throw AgentAuthException("PAIRING_CREATE_FAILED", "Pairing request was not persisted")
                return PairingCreation(record, userCode, pollSecret)
            }
        }
        throw AgentAuthException("PAIRING_CREATE_FAILED", "Could not allocate a unique pairing request")
    }

    @Suppress("ThrowsCount", "CyclomaticComplexMethod")
    fun createAgentAddition(
        request: CreateTrustedDeviceAgentRequest,
        device: ActiveDeviceIdentity,
        canonicalAgentType: String,
        serverOrigin: String,
        now: LocalDateTime = nowUtc(),
    ): PairingCreation {
        pruneExpiredPairings(now.minusDays(PAIRING_RETENTION_DAYS))
        if (findByTrustedDeviceRequestId(request.requestId) != null) {
            throw AgentAuthException("AGENT_REQUEST_REPLAYED", "This Agent request was already used")
        }
        repeat(5) {
            val pairingId = UUID.randomUUID().toString()
            val agentInstanceId = UUID.randomUUID().toString()
            val userCode = AgentAuthProtocol.randomUserCode()
            val pollSecret = AgentAuthProtocol.randomOpaqueSecret()
            val expiresAt = now.plusMinutes(PAIRING_LIFETIME_MINUTES)
            val result = runCatching {
                transaction {
                    if (AgentPairingRequests.select {
                            AgentPairingRequests.trustedDeviceRequestId eq request.requestId
                        }.any()
                    ) {
                        throw AgentAuthException("AGENT_REQUEST_REPLAYED", "This Agent request was already used")
                    }
                    if (hasActiveAgentType(device.deviceId, canonicalAgentType)) {
                        throw AgentAuthException(
                            "AGENT_ALREADY_ENROLLED",
                            "This Agent type is already active on the trusted device",
                        )
                    }
                    AgentPairingRequests.insert { row ->
                        row[id] = pairingId
                        row[requestKind] = AgentPairingKind.ADD_AGENT.name
                        row[trustedDeviceRequestId] = request.requestId
                        row[protocolVersion] = request.protocolVersion
                        row[publicKey] = device.publicKey
                        row[publicKeyFingerprint] = device.publicKeyFingerprint
                        row[keyAlgorithm] = device.keyAlgorithm
                        row[requestedDeviceName] = device.displayName
                        row[requestedPlatform] = device.platform
                        row[requestedAgentType] = canonicalAgentType
                        row[requestedAgentDisplayName] = request.agentDisplayName.trim()
                        row[requestedTransportAdapter] = request.transportAdapter.name
                        row[requestedConnectorVersion] = request.connectorVersion.trim()
                        row[requestedCapabilitiesJson] = json.encodeToString(request.capabilities)
                        row[intendedOwnerId] = device.userId
                        row[AgentPairingRequests.serverOrigin] = serverOrigin
                        row[userCodeHash] = hashUserCode(userCode)
                        row[devicePollSecretHash] = AgentAuthProtocol.sha256Hex(pollSecret)
                        row[state] = AgentPairingState.USER_PENDING.name
                        row[createdAt] = now
                        row[AgentPairingRequests.expiresAt] = expiresAt
                        row[approvedBy] = device.userId
                        row[AgentPairingRequests.deviceId] = device.deviceId
                        row[AgentPairingRequests.agentInstanceId] = agentInstanceId
                    }
                }
            }
            result.exceptionOrNull()?.let { error ->
                if (error is AgentAuthException) throw error
            }
            if (result.isSuccess) {
                val record = findById(pairingId)
                    ?: throw AgentAuthException("PAIRING_CREATE_FAILED", "Agent request was not persisted")
                return PairingCreation(record, userCode, pollSecret)
            }
            if (findByTrustedDeviceRequestId(request.requestId) != null) {
                throw AgentAuthException("AGENT_REQUEST_REPLAYED", "This Agent request was already used")
            }
        }
        throw AgentAuthException("PAIRING_CREATE_FAILED", "Could not allocate a unique Agent request")
    }

    fun findByUserCode(userCode: String, now: LocalDateTime = nowUtc()): AgentPairingRecord? {
        val normalized = AgentAuthProtocol.normalizeUserCode(userCode)
        if (normalized.length != 8) return null
        val record = transaction {
            AgentPairingRequests.select { AgentPairingRequests.userCodeHash eq hashUserCode(normalized) }
                .singleOrNull()
                ?.toPairingRecord()
        } ?: return null
        return expireIfNeeded(record, now)
    }

    fun findForDevicePoll(
        pairingId: String,
        pollSecret: String,
        now: LocalDateTime = nowUtc(),
    ): AgentPairingRecord? {
        val record = findById(pairingId) ?: return null
        if (!AgentAuthProtocol.secretsEqual(record.devicePollSecretHash, pollSecret)) return null
        return expireIfNeeded(record, now)
    }

    @Suppress("ThrowsCount", "CyclomaticComplexMethod")
    fun decidePairing(
        userId: String,
        userCode: String,
        approve: Boolean,
        now: LocalDateTime = nowUtc(),
    ): AgentPairingRecord {
        val pairing = findByUserCode(userCode, now)
            ?: throw AgentAuthException("PAIRING_NOT_FOUND", "Pairing code is invalid")
        if (pairing.intendedOwnerId == null || pairing.intendedOwnerId != userId) {
            throw AgentAuthException("PAIRING_NOT_FOUND", "Pairing code is invalid")
        }
        if (pairing.state == AgentPairingState.EXPIRED) {
            throw AgentAuthException("PAIRING_EXPIRED", "Pairing request has expired")
        }
        if (pairing.approvedBy != null && pairing.approvedBy != userId) {
            throw AgentAuthException("PAIRING_NOT_FOUND", "Pairing code is invalid")
        }
        if (pairing.state != AgentPairingState.USER_PENDING) {
            if (pairing.approvedBy == userId && pairing.state in setOf(
                    AgentPairingState.DEVICE_PROOF_PENDING,
                    AgentPairingState.CONSUMED,
                    AgentPairingState.REJECTED,
                )
            ) {
                return pairing
            }
            throw AgentAuthException("PAIRING_INVALID_STATE", "Pairing request cannot be decided in its current state")
        }

        if (!approve) {
            val updated = transaction {
                val count = AgentPairingRequests.update({
                    (AgentPairingRequests.id eq pairing.pairingId) and
                        (AgentPairingRequests.state eq AgentPairingState.USER_PENDING.name)
                }) { row ->
                    row[state] = AgentPairingState.REJECTED.name
                    row[approvedBy] = userId
                    row[approvedAt] = now
                }
                if (count == 1) {
                    recordSecurityEvent(
                        userId = userId,
                        actorId = userId,
                        action = AgentSecurityEventAction.PAIRING_REJECTED,
                        metadata = buildJsonObject { put("pairingId", pairing.pairingId) },
                        now = now,
                    )
                }
                count
            }
            if (updated != 1) {
                throw AgentAuthException("PAIRING_RACE", "Pairing request changed while it was being rejected")
            }
            return requireNotNull(findById(pairing.pairingId))
        }

        if (pairing.pairingKind == AgentPairingKind.ADD_AGENT) {
            return approveAgentAddition(pairing, userId, now)
        }

        val duplicatePublicKey = transaction {
            AgentDevices.select { AgentDevices.publicKey eq pairing.publicKey }.any()
        }
        if (duplicatePublicKey) {
            throw AgentAuthException(
                "DEVICE_ALREADY_ENROLLED",
                "This device key is already enrolled; adding another Agent requires the trusted-device flow",
            )
        }

        val proofExpiresAt = minOf(pairing.expiresAt, now.plusSeconds(PROOF_LIFETIME_SECONDS))
        val updated = transaction {
            AgentPairingRequests.update({
                (AgentPairingRequests.id eq pairing.pairingId) and
                    (AgentPairingRequests.state eq AgentPairingState.USER_PENDING.name)
            }) { row ->
                row[state] = AgentPairingState.DEVICE_PROOF_PENDING.name
                row[approvedBy] = userId
                row[approvedAt] = now
                row[deviceId] = UUID.randomUUID().toString()
                row[agentInstanceId] = UUID.randomUUID().toString()
                row[proofChallengeId] = UUID.randomUUID().toString()
                row[proofNonce] = AgentAuthProtocol.randomOpaqueSecret()
                row[AgentPairingRequests.proofExpiresAt] = proofExpiresAt
            }
        }
        if (updated != 1) {
            throw AgentAuthException("PAIRING_RACE", "Pairing request changed while it was being approved")
        }
        return requireNotNull(findById(pairing.pairingId))
    }

    @Suppress("ThrowsCount")
    fun completeEnrollment(
        pairing: AgentPairingRecord,
        now: LocalDateTime = nowUtc(),
    ): AgentPairingRecord {
        if (pairing.pairingKind != AgentPairingKind.DEVICE_ENROLLMENT) {
            throw AgentAuthException("PAIRING_INVALID_STATE", "Agent addition does not use device enrollment proof")
        }
        val userId = pairing.approvedBy
            ?: throw AgentAuthException("PAIRING_NOT_APPROVED", "Pairing request has not been approved")
        if (pairing.intendedOwnerId != userId) {
            throw AgentAuthException("PAIRING_NOT_APPROVED", "Pairing request owner does not match approval")
        }
        val deviceId = pairing.deviceId
            ?: throw AgentAuthException("PAIRING_INVALID_STATE", "Pairing request is missing device identity")
        val agentInstanceId = pairing.agentInstanceId
            ?: throw AgentAuthException("PAIRING_INVALID_STATE", "Pairing request is missing Agent identity")

        transaction {
            val current = AgentPairingRequests.select { AgentPairingRequests.id eq pairing.pairingId }
                .singleOrNull()
                ?.toPairingRecord()
                ?: throw AgentAuthException("PAIRING_NOT_FOUND", "Pairing request does not exist")
            if (current.state != AgentPairingState.DEVICE_PROOF_PENDING) {
                throw AgentAuthException("PAIRING_PROOF_REPLAYED", "Pairing proof was already consumed")
            }
            if (current.proofExpiresAt == null || !current.proofExpiresAt.isAfter(now)) {
                AgentPairingRequests.update({ AgentPairingRequests.id eq pairing.pairingId }) {
                    it[state] = AgentPairingState.EXPIRED.name
                }
                throw AgentAuthException("PAIRING_PROOF_EXPIRED", "Pairing proof challenge has expired")
            }

            AgentDevices.insert { row ->
                row[id] = deviceId
                row[AgentDevices.userId] = userId
                row[publicKey] = pairing.publicKey
                row[keyAlgorithm] = pairing.keyAlgorithm
                row[fingerprint] = pairing.publicKeyFingerprint
                row[displayName] = pairing.deviceName
                row[status] = DeviceEnrollmentStatus.ACTIVE.name
                row[platform] = pairing.platform
                row[authenticationOrigin] = pairing.serverOrigin
                row[createdAt] = now
            }
            AgentInstances.insert { row ->
                row[id] = agentInstanceId
                row[AgentInstances.userId] = userId
                row[AgentInstances.deviceId] = deviceId
                row[agentType] = pairing.agentType
                row[transportAdapter] = pairing.transportAdapter.name
                row[displayName] = pairing.agentDisplayName
                row[connectorVersion] = pairing.connectorVersion
                row[capabilitiesJson] = json.encodeToString(pairing.capabilities)
                row[status] = AgentInstanceStatus.ACTIVE.name
                row[createdAt] = now
            }
            AgentPairingRequests.update({
                (AgentPairingRequests.id eq pairing.pairingId) and
                    (AgentPairingRequests.state eq AgentPairingState.DEVICE_PROOF_PENDING.name)
            }) { row ->
                row[state] = AgentPairingState.CONSUMED.name
                row[consumedAt] = now
            }
            recordSecurityEvent(
                userId = userId,
                actorId = userId,
                action = AgentSecurityEventAction.DEVICE_ENROLLED,
                deviceId = deviceId,
                metadata = buildJsonObject { put("fingerprint", pairing.publicKeyFingerprint) },
                now = now,
            )
            recordSecurityEvent(
                userId = userId,
                actorId = userId,
                action = AgentSecurityEventAction.AGENT_ENROLLED,
                deviceId = deviceId,
                agentInstanceId = agentInstanceId,
                metadata = buildJsonObject { put("agentType", pairing.agentType) },
                now = now,
            )
        }
        return requireNotNull(findById(pairing.pairingId))
    }

    fun findActiveDevice(deviceId: String): ActiveDeviceIdentity? = transaction {
        AgentDevices.select {
            (AgentDevices.id eq deviceId) and
                (AgentDevices.status eq DeviceEnrollmentStatus.ACTIVE.name)
        }.singleOrNull()?.let { row ->
            val authenticationOrigin = row[AgentDevices.authenticationOrigin] ?: return@transaction null
            ActiveDeviceIdentity(
                userId = row[AgentDevices.userId],
                deviceId = row[AgentDevices.id],
                publicKey = row[AgentDevices.publicKey],
                publicKeyFingerprint = row[AgentDevices.fingerprint],
                keyAlgorithm = row[AgentDevices.keyAlgorithm],
                displayName = row[AgentDevices.displayName],
                platform = row[AgentDevices.platform],
                authenticationOrigin = authenticationOrigin,
            )
        }
    }

    fun findActiveIdentity(deviceId: String, agentInstanceId: String): ActiveAgentIdentity? = transaction {
        (AgentInstances innerJoin AgentDevices)
            .select {
                (AgentInstances.id eq agentInstanceId) and
                    (AgentInstances.deviceId eq deviceId) and
                    (AgentInstances.status eq AgentInstanceStatus.ACTIVE.name) and
                    (AgentDevices.status eq DeviceEnrollmentStatus.ACTIVE.name) and
                    (AgentInstances.userId eq AgentDevices.userId)
            }
            .singleOrNull()
            ?.let { row ->
                val authenticationOrigin = row[AgentDevices.authenticationOrigin] ?: return@transaction null
                ActiveAgentIdentity(
                    userId = row[AgentInstances.userId],
                    deviceId = row[AgentDevices.id],
                    agentInstanceId = row[AgentInstances.id],
                    publicKey = row[AgentDevices.publicKey],
                    agentType = row[AgentInstances.agentType],
                    capabilities = AgentCapabilityPolicy.effective(
                        row[AgentInstances.agentType],
                        decodeCapabilities(row[AgentInstances.capabilitiesJson]),
                    ),
                    authenticationOrigin = authenticationOrigin,
                )
            }
    }

    fun touchAuthenticated(identity: ActiveAgentIdentity, remoteIp: String?, now: LocalDateTime = nowUtc()) {
        transaction {
            AgentDevices.update({ AgentDevices.id eq identity.deviceId }) { row ->
                row[lastSeenAt] = now
                row[lastSeenIp] = remoteIp?.take(128)
            }
            AgentInstances.update({ AgentInstances.id eq identity.agentInstanceId }) { row ->
                row[lastSeenAt] = now
            }
        }
    }

    fun listDevices(userId: String): List<AgentDeviceDto> = transaction {
        AgentDevices.select { AgentDevices.userId eq userId }
            .map { it.toDeviceDto() }
            .sortedByDescending { it.createdAtEpochMs }
    }

    fun listAgents(userId: String): List<AgentInstanceDto> = transaction {
        AgentInstances.select { AgentInstances.userId eq userId }
            .map { it.toAgentDto() }
            .sortedByDescending { it.createdAtEpochMs }
    }

    fun listBindings(): List<AgentBindingDto> = transaction {
        (AgentBindings innerJoin AgentInstances)
            .select { AgentBindings.status inList AgentBindingStatus.entries.map { it.name } }
            .map { it.toBindingDto() }
            .sortedByDescending { it.createdAtEpochMs }
    }

    fun findBinding(bindingId: String): AgentBindingDto? = transaction {
        (AgentBindings innerJoin AgentInstances)
            .select { AgentBindings.id eq bindingId }
            .singleOrNull()
            ?.toBindingDto()
    }

    fun findActiveBinding(
        agentInstanceId: String,
        targetType: AgentBindingTargetType,
        targetId: String,
        messageScope: AgentBindingMessageScope,
    ): AgentBindingDto? = transaction {
        (AgentBindings innerJoin AgentInstances)
            .select {
                (AgentBindings.agentInstanceId eq agentInstanceId) and
                    (AgentBindings.targetType eq targetType.name) and
                    (AgentBindings.targetId eq targetId) and
                    (AgentBindings.messageScope eq messageScope.name) and
                    (AgentBindings.status eq AgentBindingStatus.ACTIVE.name) and
                    (AgentInstances.status eq AgentInstanceStatus.ACTIVE.name)
            }
            .singleOrNull()
            ?.toBindingDto()
    }

    fun listActiveBindingsForTarget(
        targetType: AgentBindingTargetType,
        targetId: String,
        messageScope: AgentBindingMessageScope,
    ): List<AgentBindingDto> = transaction {
        (AgentBindings innerJoin AgentInstances)
            .select {
                (AgentBindings.targetType eq targetType.name) and
                    (AgentBindings.targetId eq targetId) and
                    (AgentBindings.messageScope eq messageScope.name) and
                    (AgentBindings.status eq AgentBindingStatus.ACTIVE.name) and
                    (AgentInstances.status eq AgentInstanceStatus.ACTIVE.name)
            }
            .map { it.toBindingDto() }
    }

    fun findActiveAgentOwner(agentInstanceId: String): String? = transaction {
        AgentInstances.select {
            (AgentInstances.id eq agentInstanceId) and
                (AgentInstances.status eq AgentInstanceStatus.ACTIVE.name)
        }.singleOrNull()?.get(AgentInstances.userId)
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    fun createBinding(
        userId: String,
        request: CreateAgentBindingRequest,
        approveAsAgentOwner: Boolean,
        approveAsTargetManager: Boolean,
        now: LocalDateTime = nowUtc(),
    ): AgentBindingDto {
        val ownerId = findActiveAgentOwner(request.agentInstanceId)
            ?: throw AgentAuthException("AGENT_NOT_FOUND", "Agent is not active")
        if (!approveAsAgentOwner && !approveAsTargetManager) {
            throw AgentAuthException("BINDING_NOT_ALLOWED", "User cannot request this Agent binding")
        }
        val bindingStatus = if (approveAsAgentOwner && approveAsTargetManager) {
            AgentBindingStatus.ACTIVE
        } else {
            AgentBindingStatus.PENDING
        }
        var bindingId = UUID.randomUUID().toString()
        try {
            transaction {
                val equivalent = AgentBindings.select {
                    (AgentBindings.agentInstanceId eq request.agentInstanceId) and
                        (AgentBindings.targetType eq request.targetType.name) and
                        (AgentBindings.targetId eq request.targetId.trim()) and
                        (AgentBindings.messageScope eq request.messageScope.name)
                }.singleOrNull()
                if (equivalent != null) {
                    val reusable = equivalent[AgentBindings.status] in setOf(
                        AgentBindingStatus.DISABLED.name,
                        AgentBindingStatus.REVOKED.name,
                    )
                    if (!reusable) {
                        throw AgentAuthException("BINDING_ALREADY_EXISTS", "An equivalent Agent binding already exists")
                    }
                    bindingId = equivalent[AgentBindings.id]
                    recordBindingAudit(bindingId, userId, "PREVIOUS_REVISION", now)
                    AgentBindings.update({ AgentBindings.id eq bindingId }) { row ->
                        row[triggerPolicy] = request.triggerPolicy.name
                        row[permissionsJson] = json.encodeToString(request.permissions)
                        row[status] = bindingStatus.name
                        row[createdBy] = userId
                        row[AgentBindings.ownerId] = ownerId
                        row[agentOwnerApprovedBy] = userId.takeIf { approveAsAgentOwner }
                        row[agentOwnerApprovedAt] = now.takeIf { approveAsAgentOwner }
                        row[targetApprovedBy] = userId.takeIf { approveAsTargetManager }
                        row[targetApprovedAt] = now.takeIf { approveAsTargetManager }
                        row[createdAt] = now
                        row[updatedAt] = now
                        row[revokedBy] = null
                        row[revokedAt] = null
                    }
                    recordBindingAudit(bindingId, userId, "REOPENED", now)
                } else {
                    AgentBindings.insert { row ->
                        row[id] = bindingId
                        row[agentInstanceId] = request.agentInstanceId
                        row[targetType] = request.targetType.name
                        row[targetId] = request.targetId.trim()
                        row[messageScope] = request.messageScope.name
                        row[triggerPolicy] = request.triggerPolicy.name
                        row[permissionsJson] = json.encodeToString(request.permissions)
                        row[status] = bindingStatus.name
                        row[createdBy] = userId
                        row[AgentBindings.ownerId] = ownerId
                        row[agentOwnerApprovedBy] = userId.takeIf { approveAsAgentOwner }
                        row[agentOwnerApprovedAt] = now.takeIf { approveAsAgentOwner }
                        row[targetApprovedBy] = userId.takeIf { approveAsTargetManager }
                        row[targetApprovedAt] = now.takeIf { approveAsTargetManager }
                        row[createdAt] = now
                        row[updatedAt] = now
                    }
                    recordBindingAudit(bindingId, userId, "CREATED", now)
                }
            }
        } catch (error: AgentAuthException) {
            throw error
        } catch (error: Exception) {
            if (isBindingConstraintViolation(error)) {
                throw AgentAuthException("BINDING_ALREADY_EXISTS", "An equivalent Agent binding already exists")
            }
            throw error
        }
        return requireNotNull(findBinding(bindingId))
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    fun updateBinding(
        userId: String,
        bindingId: String,
        request: UpdateAgentBindingRequest,
        approveAsAgentOwner: Boolean,
        approveAsTargetManager: Boolean,
        now: LocalDateTime = nowUtc(),
    ): AgentBindingDto {
        val ownerId = findActiveAgentOwner(request.agentInstanceId)
            ?: throw AgentAuthException("AGENT_NOT_FOUND", "Agent is not active")
        if (!approveAsAgentOwner && !approveAsTargetManager) {
            throw AgentAuthException("BINDING_NOT_ALLOWED", "User cannot update this Agent binding")
        }
        val bindingStatus = if (approveAsAgentOwner && approveAsTargetManager) {
            AgentBindingStatus.ACTIVE
        } else {
            AgentBindingStatus.PENDING
        }

        val updated = try {
            transaction {
                val editable = AgentBindings.select {
                    (AgentBindings.id eq bindingId) and
                        (AgentBindings.createdBy eq userId) and
                        (AgentBindings.status inList listOf(
                            AgentBindingStatus.PENDING.name,
                            AgentBindingStatus.ACTIVE.name,
                        ))
                }.any()
                if (editable) recordBindingAudit(bindingId, userId, "PREVIOUS_REVISION", now)
                val count = AgentBindings.update({
                    (AgentBindings.id eq bindingId) and
                        (AgentBindings.createdBy eq userId) and
                        (AgentBindings.status inList listOf(
                            AgentBindingStatus.PENDING.name,
                            AgentBindingStatus.ACTIVE.name,
                        ))
                }) { row ->
                    row[agentInstanceId] = request.agentInstanceId
                    row[targetType] = request.targetType.name
                    row[targetId] = request.targetId.trim()
                    row[messageScope] = request.messageScope.name
                    row[triggerPolicy] = request.triggerPolicy.name
                    row[permissionsJson] = json.encodeToString(request.permissions)
                    row[status] = bindingStatus.name
                    row[AgentBindings.ownerId] = ownerId
                    row[agentOwnerApprovedBy] = userId.takeIf { approveAsAgentOwner }
                    row[agentOwnerApprovedAt] = now.takeIf { approveAsAgentOwner }
                    row[targetApprovedBy] = userId.takeIf { approveAsTargetManager }
                    row[targetApprovedAt] = now.takeIf { approveAsTargetManager }
                    row[updatedAt] = now
                    row[revokedBy] = null
                    row[revokedAt] = null
                }
                if (count == 1) recordBindingAudit(bindingId, userId, "UPDATED", now)
                count
            }
        } catch (error: Exception) {
            if (isBindingConstraintViolation(error)) {
                throw AgentAuthException("BINDING_ALREADY_EXISTS", "An equivalent Agent binding already exists")
            }
            throw error
        }
        if (updated != 1) {
            throw AgentAuthException("BINDING_NOT_FOUND", "Editable Agent binding not found")
        }
        return requireNotNull(findBinding(bindingId))
    }

    @Suppress("ThrowsCount")
    fun decideBindingApproval(
        userId: String,
        bindingId: String,
        approve: Boolean,
        approveAsAgentOwner: Boolean,
        approveAsTargetManager: Boolean,
        now: LocalDateTime = nowUtc(),
    ): AgentBindingDto {
        if (!approveAsAgentOwner && !approveAsTargetManager) {
            throw AgentAuthException("BINDING_APPROVAL_NOT_ALLOWED", "User cannot approve this Agent binding")
        }
        val updated = transaction {
            // Serialize decisions for the same binding across application nodes. Without this
            // write lock, the owner and target manager can both read the same incomplete row,
            // write their approvals, and leave a fully-approved binding stuck in PENDING.
            val locked = AgentBindings.update({
                (AgentBindings.id eq bindingId) and
                    (AgentBindings.status eq AgentBindingStatus.PENDING.name)
            }) { row ->
                row[status] = AgentBindingStatus.PENDING.name
            }
            if (locked != 1) {
                throw AgentAuthException("BINDING_NOT_PENDING", "Pending Agent binding not found")
            }
            val current = AgentBindings.select {
                (AgentBindings.id eq bindingId) and
                    (AgentBindings.status eq AgentBindingStatus.PENDING.name)
            }.singleOrNull() ?: throw AgentAuthException("BINDING_NOT_PENDING", "Pending Agent binding not found")

            val count = if (approve) {
                approvePendingBinding(current, bindingId, userId, approveAsAgentOwner, approveAsTargetManager, now)
            } else {
                rejectPendingBinding(bindingId, userId, now)
            }
            if (count == 1) {
                recordBindingAudit(bindingId, userId, if (approve) "APPROVED" else "REJECTED", now)
            }
            count
        }
        if (updated != 1) {
            throw AgentAuthException("BINDING_APPROVAL_RACE", "Agent binding changed while it was being approved")
        }
        return requireNotNull(findBinding(bindingId))
    }

    private fun approvePendingBinding(
        current: ResultRow,
        bindingId: String,
        userId: String,
        approveAsAgentOwner: Boolean,
        approveAsTargetManager: Boolean,
        now: LocalDateTime,
    ): Int {
        val ownerApproval = current[AgentBindings.agentOwnerApprovedBy]
            ?: userId.takeIf { approveAsAgentOwner }
        val targetApproval = current[AgentBindings.targetApprovedBy]
            ?: userId.takeIf { approveAsTargetManager }
        return AgentBindings.update({
            (AgentBindings.id eq bindingId) and
                (AgentBindings.status eq AgentBindingStatus.PENDING.name)
        }) { row ->
            if (current[AgentBindings.agentOwnerApprovedBy] == null && approveAsAgentOwner) {
                row[agentOwnerApprovedBy] = userId
                row[agentOwnerApprovedAt] = now
            }
            if (current[AgentBindings.targetApprovedBy] == null && approveAsTargetManager) {
                row[targetApprovedBy] = userId
                row[targetApprovedAt] = now
            }
            row[status] = if (ownerApproval != null && targetApproval != null) {
                AgentBindingStatus.ACTIVE.name
            } else {
                AgentBindingStatus.PENDING.name
            }
            row[updatedAt] = now
        }
    }

    private fun rejectPendingBinding(bindingId: String, userId: String, now: LocalDateTime): Int =
        AgentBindings.update({
            (AgentBindings.id eq bindingId) and
                (AgentBindings.status eq AgentBindingStatus.PENDING.name)
        }) { row ->
            row[status] = AgentBindingStatus.REVOKED.name
            row[revokedBy] = userId
            row[revokedAt] = now
            row[updatedAt] = now
        }

    fun hasActiveAgentBinding(
        userId: String,
        agentType: String,
        targetType: AgentBindingTargetType,
        targetId: String,
        permission: AgentPermission,
    ): Boolean = transaction {
        AgentBindings.innerJoin(AgentInstances).select {
            (AgentInstances.userId eq userId) and
                (AgentInstances.agentType eq agentType) and
                (AgentInstances.status eq AgentInstanceStatus.ACTIVE.name) and
                (AgentBindings.targetType eq targetType.name) and
                (AgentBindings.targetId eq targetId) and
                (AgentBindings.status eq AgentBindingStatus.ACTIVE.name)
        }.any { row ->
            decodePermissions(row[AgentBindings.permissionsJson]).contains(permission)
        }
    }

    fun revokeBinding(
        userId: String,
        bindingId: String,
        canManageTarget: Boolean,
        now: LocalDateTime = nowUtc(),
    ): Boolean = transaction {
        val binding = (AgentBindings innerJoin AgentInstances)
            .select {
                (AgentBindings.id eq bindingId) and (AgentBindings.status inList listOf(
                    AgentBindingStatus.PENDING.name,
                    AgentBindingStatus.ACTIVE.name,
                    AgentBindingStatus.DISABLED.name,
                ))
            }.singleOrNull() ?: return@transaction false
        val ownsAgent = binding[AgentInstances.userId] == userId
        if (!ownsAgent && !canManageTarget) return@transaction false
        val updated = AgentBindings.update({
            (AgentBindings.id eq bindingId) and (AgentBindings.status inList listOf(
                AgentBindingStatus.PENDING.name,
                AgentBindingStatus.ACTIVE.name,
                AgentBindingStatus.DISABLED.name,
            ))
        }) { row ->
            row[status] = AgentBindingStatus.REVOKED.name
            row[revokedBy] = userId
            row[revokedAt] = now
            row[updatedAt] = now
        }
        if (updated == 1) recordBindingAudit(bindingId, userId, "REVOKED", now)
        updated == 1
    }

    fun revokeDevice(userId: String, deviceId: String, now: LocalDateTime = nowUtc()): List<String>? = transaction {
        val owned = AgentDevices.select {
            (AgentDevices.id eq deviceId) and (AgentDevices.userId eq userId)
        }.any()
        if (!owned) return@transaction null
        val agentIds = AgentInstances.select { AgentInstances.deviceId eq deviceId }
            .map { it[AgentInstances.id] }
        val bindingIds = if (agentIds.isEmpty()) {
            emptyList()
        } else {
            AgentBindings.select { AgentBindings.agentInstanceId inList agentIds }
                .map { it[AgentBindings.id] }
        }
        AgentDevices.update({ AgentDevices.id eq deviceId }) { row ->
            row[status] = DeviceEnrollmentStatus.REVOKED.name
            row[revokedAt] = now
        }
        AgentInstances.update({ AgentInstances.deviceId eq deviceId }) { row ->
            row[status] = AgentInstanceStatus.REVOKED.name
            row[revokedAt] = now
        }
        if (agentIds.isNotEmpty()) {
            AgentBindings.update({ AgentBindings.agentInstanceId inList agentIds }) { row ->
                row[status] = AgentBindingStatus.REVOKED.name
                row[revokedBy] = userId
                row[revokedAt] = now
                row[updatedAt] = now
            }
            bindingIds.forEach { recordBindingAudit(it, userId, "DEVICE_REVOKED", now) }
        }
        recordSecurityEvent(
            userId = userId,
            actorId = userId,
            action = AgentSecurityEventAction.DEVICE_REVOKED,
            deviceId = deviceId,
            metadata = buildJsonObject { put("revokedAgentCount", agentIds.size) },
            now = now,
        )
        agentIds
    }

    fun revokeAgent(userId: String, agentInstanceId: String, now: LocalDateTime = nowUtc()): Boolean = transaction {
        val owned = AgentInstances.select {
            (AgentInstances.id eq agentInstanceId) and (AgentInstances.userId eq userId)
        }.any()
        if (!owned) return@transaction false
        val bindingIds = AgentBindings.select { AgentBindings.agentInstanceId eq agentInstanceId }
            .map { it[AgentBindings.id] }
        AgentInstances.update({ AgentInstances.id eq agentInstanceId }) { row ->
            row[status] = AgentInstanceStatus.REVOKED.name
            row[revokedAt] = now
        }
        AgentBindings.update({ AgentBindings.agentInstanceId eq agentInstanceId }) { row ->
            row[status] = AgentBindingStatus.REVOKED.name
            row[revokedBy] = userId
            row[revokedAt] = now
            row[updatedAt] = now
        }
        bindingIds.forEach { recordBindingAudit(it, userId, "AGENT_REVOKED", now) }
        recordSecurityEvent(
            userId = userId,
            actorId = userId,
            action = AgentSecurityEventAction.AGENT_REVOKED,
            agentInstanceId = agentInstanceId,
            metadata = buildJsonObject { put("revokedBindingCount", bindingIds.size) },
            now = now,
        )
        true
    }

    private fun findById(pairingId: String): AgentPairingRecord? = transaction {
        AgentPairingRequests.select { AgentPairingRequests.id eq pairingId }
            .singleOrNull()
            ?.toPairingRecord()
    }

    private fun isBindingConstraintViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is SQLException && current.sqlState == "23505") return true
            val message = current.message?.lowercase().orEmpty()
            if ("unique constraint" in message || "duplicate key" in message) return true
            current = current.cause
        }
        return false
    }

    private fun findByTrustedDeviceRequestId(requestId: String): AgentPairingRecord? = transaction {
        AgentPairingRequests.select { AgentPairingRequests.trustedDeviceRequestId eq requestId }
            .singleOrNull()
            ?.toPairingRecord()
    }

    @Suppress("ThrowsCount")
    private fun approveAgentAddition(
        pairing: AgentPairingRecord,
        userId: String,
        now: LocalDateTime,
    ): AgentPairingRecord {
        val deviceId = requireNotNull(pairing.deviceId)
        val agentInstanceId = requireNotNull(pairing.agentInstanceId)
        transaction {
            // Updating the owning device serializes same-device approvals across backend nodes.
            // PostgreSQL takes a row lock even when the status value is unchanged.
            val activeDeviceLocks = AgentDevices.update({
                (AgentDevices.id eq deviceId) and
                    (AgentDevices.userId eq userId) and
                    (AgentDevices.publicKey eq pairing.publicKey) and
                    (AgentDevices.status eq DeviceEnrollmentStatus.ACTIVE.name)
            }) { row ->
                row[status] = DeviceEnrollmentStatus.ACTIVE.name
            }
            if (activeDeviceLocks != 1) {
                throw AgentAuthException("DEVICE_NOT_ACTIVE", "Trusted device is no longer active")
            }
            if (hasActiveAgentType(deviceId, pairing.agentType)) {
                throw AgentAuthException(
                    "AGENT_ALREADY_ENROLLED",
                    "This Agent type is already active on the trusted device",
                )
            }
            AgentInstances.insert { row ->
                row[id] = agentInstanceId
                row[AgentInstances.userId] = userId
                row[AgentInstances.deviceId] = deviceId
                row[agentType] = pairing.agentType
                row[transportAdapter] = pairing.transportAdapter.name
                row[displayName] = pairing.agentDisplayName
                row[connectorVersion] = pairing.connectorVersion
                row[capabilitiesJson] = json.encodeToString(pairing.capabilities)
                row[status] = AgentInstanceStatus.ACTIVE.name
                row[createdAt] = now
            }
            val updated = AgentPairingRequests.update({
                (AgentPairingRequests.id eq pairing.pairingId) and
                    (AgentPairingRequests.state eq AgentPairingState.USER_PENDING.name)
            }) { row ->
                row[state] = AgentPairingState.CONSUMED.name
                row[approvedAt] = now
                row[consumedAt] = now
            }
            if (updated != 1) {
                throw AgentAuthException("PAIRING_RACE", "Agent request changed while it was being approved")
            }
            recordSecurityEvent(
                userId = userId,
                actorId = userId,
                action = AgentSecurityEventAction.AGENT_ENROLLED,
                deviceId = deviceId,
                agentInstanceId = agentInstanceId,
                metadata = buildJsonObject { put("agentType", pairing.agentType) },
                now = now,
            )
        }
        return requireNotNull(findById(pairing.pairingId))
    }

    private fun hasActiveAgentType(deviceId: String, agentType: String): Boolean = AgentInstances.select {
        (AgentInstances.deviceId eq deviceId) and
            (AgentInstances.agentType eq agentType) and
            (AgentInstances.status eq AgentInstanceStatus.ACTIVE.name)
    }.any()

    private fun expireIfNeeded(record: AgentPairingRecord, now: LocalDateTime): AgentPairingRecord {
        if (record.state in TERMINAL_PAIRING_STATES || record.expiresAt.isAfter(now)) return record
        transaction {
            AgentPairingRequests.update({
                (AgentPairingRequests.id eq record.pairingId) and
                    (AgentPairingRequests.state eq record.state.name)
            }) { it[state] = AgentPairingState.EXPIRED.name }
        }
        return record.copy(state = AgentPairingState.EXPIRED)
    }

    private fun recordBindingAudit(
        bindingId: String,
        actorId: String,
        action: String,
        now: LocalDateTime,
    ) {
        val snapshot = (AgentBindings innerJoin AgentInstances)
            .select { AgentBindings.id eq bindingId }
            .singleOrNull()
            ?.toBindingDto()
            ?: return
        AgentBindingAuditEvents.insert { row ->
            row[id] = UUID.randomUUID().toString()
            row[AgentBindingAuditEvents.bindingId] = bindingId
            row[AgentBindingAuditEvents.actorId] = actorId
            row[AgentBindingAuditEvents.action] = action
            row[snapshotJson] = json.encodeToString(snapshot)
            row[createdAt] = now
        }
    }

    fun latestSecurityEventSequence(): Long = transaction {
        AgentSecurityEvents.selectAll()
            .orderBy(AgentSecurityEvents.sequence, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(AgentSecurityEvents.sequence)
            ?: 0L
    }

    fun listSecurityEventsAfter(sequence: Long, limit: Int = 200): List<AgentSecurityEventDto> = transaction {
        AgentSecurityEvents.select { AgentSecurityEvents.sequence greater sequence }
            .orderBy(AgentSecurityEvents.sequence, SortOrder.ASC)
            .limit(limit.coerceIn(1, 1_000))
            .map { it.toSecurityEventDto() }
    }

    fun listSecurityEvents(userId: String, limit: Int = 200): List<AgentSecurityEventDto> = transaction {
        AgentSecurityEvents.select { AgentSecurityEvents.userId eq userId }
            .orderBy(AgentSecurityEvents.sequence, SortOrder.DESC)
            .limit(limit.coerceIn(1, 500))
            .map { it.toSecurityEventDto() }
    }

    private fun recordSecurityEvent(
        userId: String,
        actorId: String?,
        action: AgentSecurityEventAction,
        deviceId: String? = null,
        agentInstanceId: String? = null,
        metadata: JsonObject = buildJsonObject {},
        now: LocalDateTime,
    ) {
        AgentSecurityEvents.insert { row ->
            row[AgentSecurityEvents.userId] = userId
            row[AgentSecurityEvents.actorId] = actorId
            row[AgentSecurityEvents.action] = action.name
            row[AgentSecurityEvents.deviceId] = deviceId
            row[AgentSecurityEvents.agentInstanceId] = agentInstanceId
            row[metadataJson] = metadata.toString()
            row[createdAt] = now
        }
    }

    private fun ResultRow.toSecurityEventDto(): AgentSecurityEventDto = AgentSecurityEventDto(
        sequence = this[AgentSecurityEvents.sequence],
        action = AgentSecurityEventAction.valueOf(this[AgentSecurityEvents.action]),
        actorId = this[AgentSecurityEvents.actorId],
        deviceId = this[AgentSecurityEvents.deviceId],
        agentInstanceId = this[AgentSecurityEvents.agentInstanceId],
        metadata = runCatching { json.parseToJsonElement(this[AgentSecurityEvents.metadataJson]) as JsonObject }
            .getOrDefault(buildJsonObject {}),
        createdAtEpochMs = this[AgentSecurityEvents.createdAt].toEpochMillis(),
    )

    private fun ResultRow.toPairingRecord() = AgentPairingRecord(
        pairingId = this[AgentPairingRequests.id],
        pairingKind = AgentPairingKind.valueOf(this[AgentPairingRequests.requestKind]),
        trustedDeviceRequestId = this[AgentPairingRequests.trustedDeviceRequestId],
        protocolVersion = this[AgentPairingRequests.protocolVersion],
        publicKey = this[AgentPairingRequests.publicKey],
        publicKeyFingerprint = this[AgentPairingRequests.publicKeyFingerprint],
        keyAlgorithm = this[AgentPairingRequests.keyAlgorithm],
        deviceName = this[AgentPairingRequests.requestedDeviceName],
        platform = this[AgentPairingRequests.requestedPlatform],
        agentType = this[AgentPairingRequests.requestedAgentType],
        agentDisplayName = this[AgentPairingRequests.requestedAgentDisplayName],
        transportAdapter = AgentTransportAdapter.valueOf(this[AgentPairingRequests.requestedTransportAdapter]),
        connectorVersion = this[AgentPairingRequests.requestedConnectorVersion],
        capabilities = decodeCapabilities(this[AgentPairingRequests.requestedCapabilitiesJson]),
        intendedOwnerId = this[AgentPairingRequests.intendedOwnerId],
        serverOrigin = this[AgentPairingRequests.serverOrigin],
        devicePollSecretHash = this[AgentPairingRequests.devicePollSecretHash],
        state = AgentPairingState.valueOf(this[AgentPairingRequests.state]),
        createdAt = this[AgentPairingRequests.createdAt],
        expiresAt = this[AgentPairingRequests.expiresAt],
        approvedBy = this[AgentPairingRequests.approvedBy],
        deviceId = this[AgentPairingRequests.deviceId],
        agentInstanceId = this[AgentPairingRequests.agentInstanceId],
        proofChallengeId = this[AgentPairingRequests.proofChallengeId],
        proofNonce = this[AgentPairingRequests.proofNonce],
        proofExpiresAt = this[AgentPairingRequests.proofExpiresAt],
    )

    private fun ResultRow.toDeviceDto() = AgentDeviceDto(
        deviceId = this[AgentDevices.id],
        displayName = this[AgentDevices.displayName],
        publicKeyFingerprint = this[AgentDevices.fingerprint],
        keyAlgorithm = this[AgentDevices.keyAlgorithm],
        platform = this[AgentDevices.platform],
        status = DeviceEnrollmentStatus.valueOf(this[AgentDevices.status]),
        createdAtEpochMs = this[AgentDevices.createdAt].toEpochMillis(),
        lastSeenAtEpochMs = this[AgentDevices.lastSeenAt]?.toEpochMillis(),
        lastSeenIp = this[AgentDevices.lastSeenIp],
        revokedAtEpochMs = this[AgentDevices.revokedAt]?.toEpochMillis(),
    )

    private fun ResultRow.toAgentDto() = AgentInstanceDto(
        agentInstanceId = this[AgentInstances.id],
        deviceId = this[AgentInstances.deviceId],
        agentType = this[AgentInstances.agentType],
        transportAdapter = AgentTransportAdapter.valueOf(this[AgentInstances.transportAdapter]),
        displayName = this[AgentInstances.displayName],
        connectorVersion = this[AgentInstances.connectorVersion],
        capabilities = AgentCapabilityPolicy.effective(
            this[AgentInstances.agentType],
            decodeCapabilities(this[AgentInstances.capabilitiesJson]),
        ),
        status = AgentInstanceStatus.valueOf(this[AgentInstances.status]),
        createdAtEpochMs = this[AgentInstances.createdAt].toEpochMillis(),
        lastSeenAtEpochMs = this[AgentInstances.lastSeenAt]?.toEpochMillis(),
        revokedAtEpochMs = this[AgentInstances.revokedAt]?.toEpochMillis(),
    )

    private fun ResultRow.toBindingDto() = AgentBindingDto(
        bindingId = this[AgentBindings.id],
        agentInstanceId = this[AgentBindings.agentInstanceId],
        agentType = this[AgentInstances.agentType],
        agentDisplayName = this[AgentInstances.displayName],
        targetType = AgentBindingTargetType.valueOf(this[AgentBindings.targetType]),
        targetId = this[AgentBindings.targetId],
        messageScope = AgentBindingMessageScope.valueOf(this[AgentBindings.messageScope]),
        triggerPolicy = AgentTriggerPolicy.valueOf(this[AgentBindings.triggerPolicy]),
        permissions = runCatching {
            json.decodeFromString<Set<AgentPermission>>(this[AgentBindings.permissionsJson])
        }.getOrDefault(emptySet()),
        status = AgentBindingStatus.valueOf(this[AgentBindings.status]),
        createdBy = this[AgentBindings.createdBy],
        ownerId = this[AgentBindings.ownerId] ?: this[AgentInstances.userId],
        agentOwnerApprovedBy = this[AgentBindings.agentOwnerApprovedBy],
        agentOwnerApprovedAtEpochMs = this[AgentBindings.agentOwnerApprovedAt]?.toEpochMillis(),
        targetApprovedBy = this[AgentBindings.targetApprovedBy],
        targetApprovedAtEpochMs = this[AgentBindings.targetApprovedAt]?.toEpochMillis(),
        createdAtEpochMs = this[AgentBindings.createdAt].toEpochMillis(),
        updatedAtEpochMs = this[AgentBindings.updatedAt]?.toEpochMillis(),
        revokedBy = this[AgentBindings.revokedBy],
        revokedAtEpochMs = this[AgentBindings.revokedAt]?.toEpochMillis(),
    )

    private fun decodeCapabilities(value: String): Set<AgentCapability> = runCatching {
        json.decodeFromString<Set<AgentCapability>>(value)
    }.getOrDefault(emptySet())

    private fun decodePermissions(value: String): Set<AgentPermission> = runCatching {
        json.decodeFromString<Set<AgentPermission>>(value)
    }.getOrDefault(emptySet())

    private fun hashUserCode(value: String): String = AgentAuthProtocol.sha256Hex(
        AgentAuthProtocol.normalizeUserCode(value),
    )

    private fun pruneExpiredPairings(expiredBefore: LocalDateTime) {
        transaction {
            AgentPairingRequests.deleteWhere {
                AgentPairingRequests.expiresAt less expiredBefore
            }
        }
    }

    private fun LocalDateTime.toEpochMillis(): Long = toInstant(ZoneOffset.UTC).toEpochMilli()

    private val TERMINAL_PAIRING_STATES = setOf(
        AgentPairingState.CONSUMED,
        AgentPairingState.EXPIRED,
        AgentPairingState.REJECTED,
        AgentPairingState.CANCELLED,
        AgentPairingState.FAILED,
    )
}

internal fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

internal fun LocalDateTime.asEpochMillis(): Long = toInstant(ZoneOffset.UTC).toEpochMilli()
