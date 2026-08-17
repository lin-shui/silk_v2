package com.silk.web

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceEnrollmentStatus { ACTIVE, REVOKED }

@Serializable
enum class ManagedAgentStatus { PENDING, ACTIVE, SUSPENDED, REVOKED }

@Serializable
enum class ManagedBindingStatus { PENDING, ACTIVE, DISABLED, REVOKED }

@Serializable
enum class PairingState {
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
enum class PairingKind { DEVICE_ENROLLMENT, ADD_AGENT }

@Serializable
enum class ManagedTransportAdapter { ACP, CC_CONNECT }

@Serializable
enum class ManagedAgentCapability {
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

@Serializable
enum class BindingTargetType { ROOM, WORKSPACE }

@Serializable
enum class BindingMessageScope { TEAM, WORKSPACE }

@Serializable
enum class BindingTriggerPolicy { ALL, MENTION, EVENT }

@Serializable
enum class BindingPermission {
    READ_MESSAGE,
    SEND_MESSAGE,
    READ_FILE,
    WRITE_FILE,
    RUN_COMMAND,
    READ_WORKSPACE,
    WRITE_WORKSPACE,
}

@Serializable
data class PairingCodeRequest(val userCode: String)

@Serializable
data class PairingApprovalRequest(
    val userCode: String,
    val approve: Boolean,
)

@Serializable
data class PairingPreviewDto(
    val pairingId: String,
    val pairingKind: PairingKind = PairingKind.DEVICE_ENROLLMENT,
    val state: PairingState,
    val deviceId: String? = null,
    val deviceName: String,
    val platform: String,
    val publicKeyFingerprint: String,
    val agentType: String,
    val agentDisplayName: String,
    val transportAdapter: ManagedTransportAdapter,
    val connectorVersion: String,
    val capabilities: Set<ManagedAgentCapability> = emptySet(),
    val expiresAtEpochMs: Long,
)

@Serializable
data class PairingApprovalDto(
    val pairingId: String,
    val state: PairingState,
    val expiresAtEpochMs: Long,
)

@Serializable
data class ManagedDeviceDto(
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
data class ManagedAgentDto(
    val agentInstanceId: String,
    val deviceId: String,
    val agentType: String,
    val transportAdapter: ManagedTransportAdapter,
    val displayName: String,
    val connectorVersion: String,
    val capabilities: Set<ManagedAgentCapability> = emptySet(),
    val status: ManagedAgentStatus,
    val createdAtEpochMs: Long,
    val lastSeenAtEpochMs: Long? = null,
    val revokedAtEpochMs: Long? = null,
    val connected: Boolean = false,
)

@Serializable
data class UpdateManagedResourceNameRequest(
    val displayName: String,
)

@Serializable
data class ManagedBindingDto(
    val bindingId: String,
    val agentInstanceId: String,
    val agentType: String,
    val agentDisplayName: String,
    val agentDeviceDisplayName: String = "",
    val targetType: BindingTargetType,
    val targetId: String,
    val messageScope: BindingMessageScope,
    val triggerPolicy: BindingTriggerPolicy,
    val mentionAlias: String = "",
    val permissions: Set<BindingPermission> = emptySet(),
    val status: ManagedBindingStatus,
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
data class CreateManagedBindingRequest(
    val agentInstanceId: String,
    val targetType: BindingTargetType,
    val targetId: String,
    val messageScope: BindingMessageScope,
    val triggerPolicy: BindingTriggerPolicy,
    val mentionAlias: String = "",
    val permissions: Set<BindingPermission> = setOf(
        BindingPermission.READ_MESSAGE,
        BindingPermission.SEND_MESSAGE,
    ),
)

@Serializable
data class ManagedBindingApprovalRequest(val approve: Boolean)

@Serializable
data class ManagedDeviceListResponse(val devices: List<ManagedDeviceDto> = emptyList())

@Serializable
data class ManagedAgentListResponse(val agents: List<ManagedAgentDto> = emptyList())

@Serializable
data class ManagedBindingListResponse(val bindings: List<ManagedBindingDto> = emptyList())

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

@Serializable
internal data class AgentApiErrorResponse(
    val success: Boolean = false,
    val error: String = "AGENT_REQUEST_FAILED",
    val message: String = "请求失败",
)

internal data class BindingTargetOption(
    val type: BindingTargetType,
    val id: String,
    val label: String,
    val canManage: Boolean,
)

internal fun compatibleMessageScope(targetType: BindingTargetType): BindingMessageScope = when (targetType) {
    BindingTargetType.ROOM -> BindingMessageScope.TEAM
    BindingTargetType.WORKSPACE -> BindingMessageScope.WORKSPACE
}

internal fun defaultBindingPermissions(): Set<BindingPermission> = setOf(
    BindingPermission.READ_MESSAGE,
    BindingPermission.SEND_MESSAGE,
)

internal fun availableBindingPermissions(targetType: BindingTargetType): List<BindingPermission> = when (targetType) {
    BindingTargetType.ROOM -> listOf(BindingPermission.READ_MESSAGE, BindingPermission.SEND_MESSAGE)
    BindingTargetType.WORKSPACE -> listOf(
        BindingPermission.READ_MESSAGE,
        BindingPermission.SEND_MESSAGE,
        BindingPermission.READ_FILE,
        BindingPermission.WRITE_FILE,
        BindingPermission.RUN_COMMAND,
        BindingPermission.READ_WORKSPACE,
        BindingPermission.WRITE_WORKSPACE,
    )
}

internal fun updateBindingPermissionSelection(
    current: Set<BindingPermission>,
    permission: BindingPermission,
    enabled: Boolean,
): Set<BindingPermission> = when {
    enabled && permission in setOf(BindingPermission.WRITE_FILE, BindingPermission.RUN_COMMAND) ->
        current + permission + BindingPermission.READ_FILE
    enabled -> current + permission
    permission == BindingPermission.READ_FILE ->
        current - setOf(
            BindingPermission.READ_FILE,
            BindingPermission.WRITE_FILE,
            BindingPermission.RUN_COMMAND,
        )
    else -> current - permission
}

internal fun bindingPermissionLabel(permission: BindingPermission): String = when (permission) {
    BindingPermission.READ_MESSAGE -> "读取消息"
    BindingPermission.SEND_MESSAGE -> "发送消息"
    BindingPermission.READ_FILE -> "读取文件"
    BindingPermission.WRITE_FILE -> "写入文件"
    BindingPermission.RUN_COMMAND -> "运行命令"
    BindingPermission.READ_WORKSPACE -> "读取工作区"
    BindingPermission.WRITE_WORKSPACE -> "修改工作区"
}

@Suppress("CyclomaticComplexMethod")
internal fun agentCapabilityLabel(capability: ManagedAgentCapability): String = when (capability) {
    ManagedAgentCapability.PROMPT -> "发送提示"
    ManagedAgentCapability.STREAM -> "流式响应"
    ManagedAgentCapability.CANCEL -> "停止生成"
    ManagedAgentCapability.QUESTION_RESPONSE -> "回答问题"
    ManagedAgentCapability.PERMISSION_RESPONSE -> "权限确认"
    ManagedAgentCapability.SESSION_RESUME -> "恢复会话"
    ManagedAgentCapability.READ_FILE -> "读取文件"
    ManagedAgentCapability.WRITE_FILE -> "写入文件"
    ManagedAgentCapability.RUN_COMMAND -> "运行命令"
    ManagedAgentCapability.EXECUTION_POLICY_V1 -> "执行策略 V1"
    ManagedAgentCapability.READ_WORKSPACE -> "读取工作区"
    ManagedAgentCapability.WRITE_WORKSPACE -> "修改工作区"
    ManagedAgentCapability.IMAGE_INPUT -> "图片输入"
    ManagedAgentCapability.IMAGE_OUTPUT -> "图片输出"
}

internal fun defaultManagedAgentMentionAlias(agentType: String): String {
    val candidate = agentType.lowercase()
        .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
        .take(32)
        .trimEnd('-', '_')
    return when (agentType.lowercase()) {
        "claude-code" -> "cc"
        "codex" -> "codex"
        else -> candidate.takeIf {
            it.matches(Regex("[a-z0-9](?:[a-z0-9_-]{0,31})")) && it !in setOf("silk", "system", "agent")
        } ?: "agent1"
    }
}

internal fun formatManagedAgentMentionAlias(value: String): String = value
    .removePrefix("@")
    .lowercase()
    .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
    .take(32)

internal fun isManagedAgentMentionAliasValid(value: String): Boolean {
    val normalized = formatManagedAgentMentionAlias(value)
    return normalized == value.trim().removePrefix("@").lowercase() &&
        normalized.matches(Regex("[a-z0-9](?:[a-z0-9_-]{0,31})")) &&
        normalized !in setOf("silk", "system", "agent")
}

internal fun managedAgentMentionConflictMessage(
    bindings: List<ManagedBindingDto>,
    targetType: BindingTargetType,
    targetId: String,
    mentionAlias: String,
    excludingBindingId: String? = null,
): String? {
    if (targetType != BindingTargetType.ROOM || targetId.isBlank() ||
        !isManagedAgentMentionAliasValid(mentionAlias)
    ) {
        return null
    }
    val normalized = formatManagedAgentMentionAlias(mentionAlias)
    val usedAliases = bindings.asSequence()
        .filter { it.bindingId != excludingBindingId }
        .filter { it.targetType == BindingTargetType.ROOM && it.targetId == targetId }
        .filter { it.messageScope == BindingMessageScope.TEAM }
        .filter {
            it.status in setOf(
                ManagedBindingStatus.PENDING,
                ManagedBindingStatus.ACTIVE,
                ManagedBindingStatus.DISABLED,
            )
        }
        .map { formatManagedAgentMentionAlias(it.mentionAlias) }
        .toSet()
    if (normalized !in usedAliases) return null

    val suggestion = nextAvailableManagedAgentMentionAlias(normalized, usedAliases)
    return "提及词 @$normalized 已被这个 Room 中的另一个 Agent 使用，请改用 @$suggestion 或其他唯一提及词。"
}

private fun nextAvailableManagedAgentMentionAlias(
    requestedAlias: String,
    usedAliases: Set<String>,
): String {
    val base = requestedAlias.trimEnd { it in '0'..'9' }.ifBlank { "agent" }
    for (index in 1..9_999) {
        val suffix = index.toString()
        val prefix = base.take(32 - suffix.length).trimEnd('-', '_').ifBlank { "a" }
        val candidate = "$prefix$suffix"
        if (candidate !in usedAliases && isManagedAgentMentionAliasValid(candidate)) return candidate
    }
    return "agent9999"
}

internal fun normalizePairingCode(value: String): String = value
    .uppercase()
    .filter { it in 'A'..'Z' || it in '0'..'9' }
    .take(8)

internal fun formatPairingCode(value: String): String = normalizePairingCode(value).let { normalized ->
    if (normalized.length <= 4) normalized else "${normalized.take(4)}-${normalized.drop(4)}"
}

internal fun pairingCodeFromFragment(fragment: String): String? {
    val rawCode = fragment.removePrefix("#")
        .split('&')
        .mapNotNull { parameter ->
            val parts = parameter.split('=', limit = 2)
            parts.takeIf { it.size == 2 && it[0] == "code" }?.get(1)
        }
        .firstOrNull()
        ?: return null
    val normalized = rawCode.uppercase().replace("-", "")
    val pairingAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return formatPairingCode(normalized).takeIf {
        normalized.length == 8 && normalized.all(pairingAlphabet::contains)
    }
}

internal fun fingerprintSuffix(fingerprint: String): String =
    fingerprint.takeLast(16).chunked(4).joinToString(":")

internal fun canBindRoomRole(role: String): Boolean = role.uppercase() in setOf("OWNER", "OPERATOR")

internal fun bindingStatusLabel(status: ManagedBindingStatus): String = when (status) {
    ManagedBindingStatus.PENDING -> "待审批"
    ManagedBindingStatus.ACTIVE -> "已启用"
    ManagedBindingStatus.DISABLED -> "已停用"
    ManagedBindingStatus.REVOKED -> "已撤销"
}

internal fun bindingApprovalSummary(binding: ManagedBindingDto): String? {
    if (binding.status != ManagedBindingStatus.PENDING) return null
    return when {
        binding.agentOwnerApprovedAtEpochMs == null && binding.targetApprovedAtEpochMs == null ->
            "等待 Agent 所有者与目标管理员批准"
        binding.agentOwnerApprovedAtEpochMs == null -> "等待 Agent 所有者批准"
        binding.targetApprovedAtEpochMs == null -> "等待目标管理员批准"
        else -> null
    }
}

internal fun pairingKindLabel(kind: PairingKind): String = when (kind) {
    PairingKind.DEVICE_ENROLLMENT -> "新设备"
    PairingKind.ADD_AGENT -> "新增 Agent"
}
