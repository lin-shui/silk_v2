package com.silk.backend.agents.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class DeviceEnrollmentStatus { ACTIVE, REVOKED }

@Serializable
enum class AgentInstanceStatus { PENDING, ACTIVE, SUSPENDED, REVOKED }

@Serializable
enum class AgentBindingStatus { PENDING, ACTIVE, DISABLED, REVOKED }

@Serializable
enum class AgentPairingState {
    CREATED,
    USER_PENDING,
    USER_APPROVED,
    DEVICE_PROOF_PENDING,
    ENROLLED,
    CONSUMED,
    EXPIRED,
    REJECTED,
    CANCELLED,
    FAILED,
}

@Serializable
enum class AgentPairingKind { DEVICE_ENROLLMENT, ADD_AGENT }

@Serializable
enum class AgentTransportAdapter { ACP, CC_CONNECT }

@Serializable
enum class AgentCapability {
    PROMPT,
    STREAM,
    CANCEL,
    QUESTION_RESPONSE,
    PERMISSION_RESPONSE,
    SESSION_RESUME,
    READ_FILE,
    WRITE_FILE,
    RUN_COMMAND,
    EXECUTION_POLICY_V1,
    READ_WORKSPACE,
    WRITE_WORKSPACE,
    IMAGE_INPUT,
    IMAGE_OUTPUT,
}

internal object AgentCapabilityPolicy {
    private val directBridgeCapabilities = setOf(
        AgentCapability.PROMPT,
        AgentCapability.STREAM,
        AgentCapability.CANCEL,
        AgentCapability.QUESTION_RESPONSE,
        AgentCapability.PERMISSION_RESPONSE,
        AgentCapability.SESSION_RESUME,
        AgentCapability.READ_FILE,
        AgentCapability.WRITE_FILE,
        AgentCapability.RUN_COMMAND,
        AgentCapability.EXECUTION_POLICY_V1,
        AgentCapability.READ_WORKSPACE,
        AgentCapability.WRITE_WORKSPACE,
        AgentCapability.IMAGE_INPUT,
        AgentCapability.IMAGE_OUTPUT,
    )

    fun effective(agentType: String, reported: Set<AgentCapability>): Set<AgentCapability> = when (agentType) {
        "claude-code", "codex" -> reported intersect directBridgeCapabilities
        else -> emptySet()
    }
}

@Serializable
enum class AgentBindingTargetType { ROOM, WORKSPACE }

@Serializable
enum class AgentBindingMessageScope { TEAM, WORKSPACE }

@Serializable
enum class AgentTriggerPolicy { ALL, MENTION, EVENT }

@Serializable
enum class AgentPermission {
    READ_MESSAGE,
    SEND_MESSAGE,
    READ_FILE,
    WRITE_FILE,
    RUN_COMMAND,
    READ_WORKSPACE,
    WRITE_WORKSPACE,
}

@Serializable
data class CreateAgentPairingRequest(
    val protocolVersion: Int,
    val keyAlgorithm: String,
    val publicKey: String,
    val accountLoginName: String = "",
    val connectionOrigin: String = "",
    val verificationOrigin: String? = null,
    val deviceName: String,
    val platform: String,
    val agentType: String,
    val agentDisplayName: String,
    val transportAdapter: AgentTransportAdapter,
    val connectorVersion: String,
    val capabilities: Set<AgentCapability> = emptySet(),
)

@Serializable
data class CreateAgentPairingResponse(
    val pairingId: String,
    val devicePollSecret: String,
    val serverOrigin: String,
    val verificationUri: String,
    val userCode: String,
    val expiresAtEpochMs: Long,
    val agentWebSocketUri: String,
)

@Serializable
data class CreateTrustedDeviceAgentRequest(
    val protocolVersion: Int,
    val deviceId: String,
    val requestId: String,
    val timestampEpochMs: Long,
    val agentType: String,
    val agentDisplayName: String,
    val transportAdapter: AgentTransportAdapter,
    val connectorVersion: String,
    val capabilities: Set<AgentCapability> = emptySet(),
    val verificationOrigin: String? = null,
    val signature: String,
)

@Serializable
data class AgentPairingCodeRequest(val userCode: String)

@Serializable
data class AgentPairingApprovalRequest(
    val userCode: String,
    val approve: Boolean = true,
)

@Serializable
data class AgentPairingPreviewResponse(
    val pairingId: String,
    val pairingKind: AgentPairingKind,
    val state: AgentPairingState,
    val deviceId: String? = null,
    val deviceName: String,
    val platform: String,
    val publicKeyFingerprint: String,
    val agentType: String,
    val agentDisplayName: String,
    val transportAdapter: AgentTransportAdapter,
    val connectorVersion: String,
    val capabilities: Set<AgentCapability>,
    val expiresAtEpochMs: Long,
)

@Serializable
data class AgentPairingApprovalResponse(
    val pairingId: String,
    val state: AgentPairingState,
    val expiresAtEpochMs: Long,
)

@Serializable
data class PairingDeviceProofChallenge(
    val challengeId: String,
    val nonce: String,
    val serverOrigin: String,
    val deviceId: String,
    val agentInstanceId: String,
    val publicKeyFingerprint: String,
    val expiresAtEpochMs: Long,
    val serverTimeEpochMs: Long,
)

@Serializable
data class AgentPairingStatusResponse(
    val pairingId: String,
    val state: AgentPairingState,
    val expiresAtEpochMs: Long,
    val proofChallenge: PairingDeviceProofChallenge? = null,
    val deviceId: String? = null,
    val agentInstanceId: String? = null,
    val serverTimeEpochMs: Long,
)

@Serializable
data class CompleteAgentPairingRequest(
    val challengeId: String,
    val timestampEpochMs: Long,
    val signature: String,
)

@Serializable
data class CompleteAgentPairingResponse(
    val pairingId: String,
    val state: AgentPairingState,
    val deviceId: String,
    val agentInstanceId: String,
)

@Serializable
data class AgentDeviceDto(
    val deviceId: String,
    val displayName: String,
    val publicKeyFingerprint: String,
    val keyAlgorithm: String,
    val platform: String,
    val status: DeviceEnrollmentStatus,
    val createdAtEpochMs: Long,
    val lastSeenAtEpochMs: Long? = null,
    val lastSeenIp: String? = null,
    val revokedAtEpochMs: Long? = null,
    val connected: Boolean = false,
)

@Serializable
data class AgentInstanceDto(
    val agentInstanceId: String,
    val deviceId: String,
    val agentType: String,
    val transportAdapter: AgentTransportAdapter,
    val displayName: String,
    val connectorVersion: String,
    val capabilities: Set<AgentCapability>,
    val status: AgentInstanceStatus,
    val createdAtEpochMs: Long,
    val lastSeenAtEpochMs: Long? = null,
    val revokedAtEpochMs: Long? = null,
    val connected: Boolean = false,
)

@Serializable
data class UpdateAgentResourceNameRequest(
    val displayName: String,
)

@Serializable
data class AgentBindingDto(
    val bindingId: String,
    val agentInstanceId: String,
    val agentType: String,
    val agentDisplayName: String,
    val agentDeviceDisplayName: String = "",
    val targetType: AgentBindingTargetType,
    val targetId: String,
    val messageScope: AgentBindingMessageScope,
    val triggerPolicy: AgentTriggerPolicy,
    val mentionAlias: String = "",
    val permissions: Set<AgentPermission>,
    val status: AgentBindingStatus,
    val createdBy: String,
    val ownerId: String,
    val agentOwnerApprovedBy: String? = null,
    val agentOwnerApprovedAtEpochMs: Long? = null,
    val targetApprovedBy: String? = null,
    val targetApprovedAtEpochMs: Long? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long? = null,
    val revokedBy: String? = null,
    val revokedAtEpochMs: Long? = null,
    val canApproveAsAgentOwner: Boolean = false,
    val canApproveAsTargetManager: Boolean = false,
    val canEdit: Boolean = false,
    val canRevoke: Boolean = false,
)

@Serializable
data class CreateAgentBindingRequest(
    val agentInstanceId: String,
    val targetType: AgentBindingTargetType,
    val targetId: String,
    val messageScope: AgentBindingMessageScope,
    val triggerPolicy: AgentTriggerPolicy,
    val mentionAlias: String = "",
    val permissions: Set<AgentPermission> = setOf(
        AgentPermission.READ_MESSAGE,
        AgentPermission.SEND_MESSAGE,
    ),
)

@Serializable
data class UpdateAgentBindingRequest(
    val agentInstanceId: String,
    val targetType: AgentBindingTargetType,
    val targetId: String,
    val messageScope: AgentBindingMessageScope,
    val triggerPolicy: AgentTriggerPolicy,
    val mentionAlias: String = "",
    val permissions: Set<AgentPermission> = setOf(
        AgentPermission.READ_MESSAGE,
        AgentPermission.SEND_MESSAGE,
    ),
)

@Serializable
data class AgentBindingApprovalRequest(val approve: Boolean = true)

@Serializable
data class AgentDeviceListResponse(val devices: List<AgentDeviceDto>)

@Serializable
data class AgentInstanceListResponse(val agents: List<AgentInstanceDto>)

@Serializable
data class AgentBindingListResponse(val bindings: List<AgentBindingDto>)

@Serializable
enum class AgentSecurityEventAction {
    PAIRING_REJECTED,
    DEVICE_ENROLLED,
    AGENT_ENROLLED,
    DEVICE_REVOKED,
    AGENT_REVOKED,
}

@Serializable
data class AgentSecurityEventDto(
    val sequence: Long,
    val action: AgentSecurityEventAction,
    val actorId: String? = null,
    val deviceId: String? = null,
    val agentInstanceId: String? = null,
    val metadata: JsonObject,
    val createdAtEpochMs: Long,
)

@Serializable
data class AgentSecurityEventListResponse(val events: List<AgentSecurityEventDto>)

@Serializable
data class AgentManagementActionResponse(
    val success: Boolean,
    val message: String,
)

@Serializable
data class AgentRevocationCleanupResponse(
    val success: Boolean = true,
    val message: String,
    val retentionDays: Long,
    val deletedDevices: Int,
    val deletedAgents: Int,
    val deletedBindings: Int,
)

internal val RESERVED_AGENT_MENTION_ALIASES = setOf("silk", "system", "agent")

internal fun defaultAgentMentionAlias(agentType: String): String {
    val candidate = when (agentType.lowercase()) {
        "claude-code" -> "cc"
        "codex" -> "codex"
        else -> agentType.lowercase()
            .replace(Regex("[^a-z0-9_-]"), "-")
            .trim('-', '_')
            .take(32)
            .trimEnd('-', '_')
    }
    return candidate.takeIf {
        it.matches(Regex("[a-z0-9](?:[a-z0-9_-]{0,31})")) && it !in RESERVED_AGENT_MENTION_ALIASES
    } ?: "agent1"
}

internal fun normalizeAgentMentionAlias(value: String): String? {
    val normalized = value.trim().lowercase()
    if (normalized.isBlank() || normalized.length > 32) return null
    if (!normalized.matches(Regex("[a-z0-9](?:[a-z0-9_-]{0,31})"))) return null
    if (normalized in RESERVED_AGENT_MENTION_ALIASES) return null
    return normalized
}

@Serializable
data class AgentAuthErrorResponse(
    val success: Boolean = false,
    val error: String,
    val message: String,
)

@Serializable
data class AgentSocketHello(
    val type: String = "hello",
    val protocolVersion: Int,
    val deviceId: String,
    val agentInstanceId: String,
    val connectionMode: String? = null,
)

@Serializable
data class AgentSocketChallenge(
    val type: String = "challenge",
    val protocolVersion: Int = AgentAuthProtocol.VERSION,
    val challengeId: String,
    val nonce: String,
    val serverOrigin: String,
    val expiresAtEpochMs: Long,
    val serverTimeEpochMs: Long,
)

@Serializable
data class AgentSocketAuthenticate(
    val type: String = "authenticate",
    val protocolVersion: Int,
    val challengeId: String,
    val deviceId: String,
    val agentInstanceId: String,
    val timestampEpochMs: Long,
    val signature: String,
)

@Serializable
data class AgentSocketAuthenticated(
    val type: String = "authenticated",
    val connectionId: String,
    val deviceId: String,
    val agentInstanceId: String,
    val capabilities: Set<AgentCapability>,
    val serverTimeEpochMs: Long,
    val connectionMode: String? = null,
)

@Serializable
data class AgentSocketHeartbeat(val type: String = "heartbeat", val timestampEpochMs: Long)

@Serializable
data class AgentSocketHeartbeatAck(
    val type: String = "heartbeat_ack",
    val serverTimeEpochMs: Long,
)

@Serializable
data class AgentSocketError(
    val type: String = "error",
    val error: String,
    val message: String,
    val agentInstanceId: String? = null,
)

const val AGENT_HOST_CONNECTION_MODE = "HOST_MULTIPLEXED_V1"

@Serializable
data class AgentHostOpen(
    val type: String = "agent_open",
    val protocolVersion: Int = AgentAuthProtocol.VERSION,
    val agentInstanceId: String,
    val agentType: String,
)

@Serializable
data class AgentHostOpened(
    val type: String = "agent_opened",
    val protocolVersion: Int = AgentAuthProtocol.VERSION,
    val agentInstanceId: String,
    val agentType: String,
    val capabilities: Set<AgentCapability>,
)

@Serializable
data class AgentHostRpc(
    val type: String = "agent_rpc",
    val protocolVersion: Int = AgentAuthProtocol.VERSION,
    val agentInstanceId: String,
    val payload: JsonObject,
)

@Serializable
data class AgentHostClose(
    val type: String = "agent_close",
    val protocolVersion: Int = AgentAuthProtocol.VERSION,
    val agentInstanceId: String,
    val reason: String = "normal",
)
