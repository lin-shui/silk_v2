package com.silk.backend.agents.auth

import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.acp.SilkExecutionPolicy

internal data class AgentBindingAuthorization(
    val allowed: Boolean,
    val errorCode: String? = null,
    val message: String? = null,
    val binding: AgentBindingDto? = null,
    val executionPolicy: SilkExecutionPolicy? = null,
)

/** Central authorization gate for every backend operation routed to an authenticated direct Agent. */
internal object AgentBindingAuthorizationService {
    fun authorize(
        userId: String,
        agentType: String,
        targetType: AgentBindingTargetType,
        targetId: String,
        messageScope: AgentBindingMessageScope,
        requiredPermissions: Set<AgentPermission>,
        requiredCapabilities: Set<AgentCapability> = emptySet(),
    ): AgentBindingAuthorization {
        val connection = AcpRegistry.connectionIdentity(userId, agentType)
            ?: return denied("AGENT_NOT_CONNECTED", "Agent is not connected")
        val agentInstanceId = connection.agentInstanceId
            ?: return denied("AGENT_IDENTITY_MISSING", "Authenticated Agent identity is unavailable")
        val binding = AgentAuthRepository.findActiveBinding(
            agentInstanceId = agentInstanceId,
            targetType = targetType,
            targetId = targetId,
            messageScope = messageScope,
        ) ?: return denied("AGENT_NOT_BOUND", "Agent has no active binding for this target")
        if (!binding.permissions.containsAll(requiredPermissions)) {
            return denied("PERMISSION_DENIED", "Agent binding does not grant the required permissions")
        }
        if (!connection.capabilities.containsAll(requiredCapabilities)) {
            return denied("CAPABILITY_DENIED", "Agent did not declare the required capabilities")
        }
        return AgentBindingAuthorization(
            allowed = true,
            binding = binding,
            executionPolicy = SilkExecutionPolicy(
                readFile = AgentPermission.READ_FILE in binding.permissions &&
                    AgentCapability.READ_FILE in connection.capabilities,
                writeFile = AgentPermission.WRITE_FILE in binding.permissions &&
                    AgentCapability.WRITE_FILE in connection.capabilities,
                runCommand = AgentPermission.RUN_COMMAND in binding.permissions &&
                    AgentCapability.RUN_COMMAND in connection.capabilities,
            ),
        )
    }

    private fun denied(errorCode: String, message: String) = AgentBindingAuthorization(
        allowed = false,
        errorCode = errorCode,
        message = message,
    )
}
