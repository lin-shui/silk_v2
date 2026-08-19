package com.silk.backend.agents.auth

import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.acp.SilkExecutionPolicy

internal data class AgentBindingAuthorization(
    val allowed: Boolean,
    val errorCode: String? = null,
    val message: String? = null,
    val binding: AgentBindingDto? = null,
    val agentInstanceId: String? = null,
    val executionPolicy: SilkExecutionPolicy? = null,
)

/**
 * Central authorization gate for every backend operation routed to an authenticated direct Agent.
 * New callers must pass [agentInstanceId]. The type-only path is retained for old workspace
 * APIs and resolves through the target's active binding only when that produces one unambiguous
 * connected instance.
 */
internal object AgentBindingAuthorizationService {
    @Suppress("CyclomaticComplexMethod")
    fun authorize(
        userId: String,
        agentType: String,
        agentInstanceId: String? = null,
        targetType: AgentBindingTargetType,
        targetId: String,
        messageScope: AgentBindingMessageScope,
        requiredPermissions: Set<AgentPermission>,
        requiredCapabilities: Set<AgentCapability> = emptySet(),
    ): AgentBindingAuthorization {
        val connection = if (agentInstanceId.isNullOrBlank()) {
            // A type-only caller is legacy API surface. Resolve it through the
            // active binding first so two same-type instances cannot make the
            // choice ambiguous or accidentally select the wrong device.
            val boundInstances = AgentAuthRepository.listActiveBindingsForTarget(
                targetType = targetType,
                targetId = targetId,
                messageScope = messageScope,
            ).asSequence()
                .filter { it.ownerId == userId && it.agentType == agentType }
                .map { it.agentInstanceId }
                .filter(AcpRegistry::isConnectedInstance)
                .distinct()
                .toList()
            when {
                boundInstances.size == 1 -> AcpRegistry.connectionIdentity(boundInstances.single())
                boundInstances.size > 1 -> return denied(
                    "AGENT_INSTANCE_AMBIGUOUS",
                    "Multiple connected Agent instances match this binding; specify agentInstanceId",
                )
                else -> AcpRegistry.connectionIdentity(userId, agentType)
            }
        } else {
            AcpRegistry.connectionIdentity(agentInstanceId)
        }
            ?: return denied("AGENT_NOT_CONNECTED", "Agent is not connected")
        if (connection.userId != userId || connection.agentType != agentType) {
            return denied("AGENT_IDENTITY_MISMATCH", "Authenticated Agent identity does not match the binding")
        }
        val resolvedAgentInstanceId = connection.agentInstanceId
            ?: return denied("AGENT_IDENTITY_MISSING", "Authenticated Agent identity is unavailable")
        val binding = AgentAuthRepository.findActiveBinding(
            agentInstanceId = resolvedAgentInstanceId,
            targetType = targetType,
            targetId = targetId,
            messageScope = messageScope,
        ) ?: return denied("AGENT_NOT_BOUND", "Agent has no active binding for this target")
        if (!binding.permissions.containsAll(requiredPermissions)) {
            return denied("PERMISSION_DENIED", "Agent binding does not grant the required permissions")
        }
        val runtimePermissionMode = AgentAuthRepository.findAgentRuntimePermissionMode(resolvedAgentInstanceId)
        if (runtimePermissionMode == AgentRuntimePermissionMode.READ_ONLY && requiredPermissions.any {
                it in setOf(
                    AgentPermission.WRITE_FILE,
                    AgentPermission.RUN_COMMAND,
                )
            }
        ) {
            return denied("PERMISSION_DENIED", "Agent runtime permission mode is read-only")
        }
        if (!connection.capabilities.containsAll(requiredCapabilities)) {
            return denied("CAPABILITY_DENIED", "Agent did not declare the required capabilities")
        }
        val effectiveAccessMode = when {
            binding.accessMode == AgentAccessMode.CHAT_ONLY -> AgentAccessMode.CHAT_ONLY
            binding.accessMode == AgentAccessMode.READ_ONLY ||
                runtimePermissionMode == AgentRuntimePermissionMode.READ_ONLY -> AgentAccessMode.READ_ONLY
            binding.accessMode == AgentAccessMode.APPROVAL_REQUIRED ||
                runtimePermissionMode == AgentRuntimePermissionMode.APPROVAL_REQUIRED ->
                AgentAccessMode.APPROVAL_REQUIRED
            else -> AgentAccessMode.AUTONOMOUS
        }
        return AgentBindingAuthorization(
            allowed = true,
            binding = binding,
            agentInstanceId = resolvedAgentInstanceId,
            executionPolicy = SilkExecutionPolicy(
                accessMode = effectiveAccessMode,
                agentPermissionMode = runtimePermissionMode,
                readFile = AgentPermission.READ_FILE in binding.permissions &&
                    AgentCapability.READ_FILE in connection.capabilities,
                writeFile = AgentPermission.WRITE_FILE in binding.permissions &&
                    AgentCapability.WRITE_FILE in connection.capabilities &&
                    runtimePermissionMode != AgentRuntimePermissionMode.READ_ONLY,
                runCommand = AgentPermission.RUN_COMMAND in binding.permissions &&
                    AgentCapability.RUN_COMMAND in connection.capabilities &&
                    runtimePermissionMode != AgentRuntimePermissionMode.READ_ONLY,
            ),
        )
    }

    private fun denied(errorCode: String, message: String) = AgentBindingAuthorization(
        allowed = false,
        errorCode = errorCode,
        message = message,
    )
}
