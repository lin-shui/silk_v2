package com.silk.backend.agents.auth

import com.silk.backend.TestWorkspace
import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.acp.InMemoryAcpTransport
import com.silk.backend.database.AgentBindings
import com.silk.backend.database.AgentDevices
import com.silk.backend.database.AgentInstances
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.UserRepository
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentBindingAuthorizationTest {
    @AfterTest
    fun clearConnections() = AcpRegistry.clearForTest()

    @Test
    fun `device signed authorization is pinned to the connected agent instance`() = runTest {
        TestWorkspace().use {
            val owner = UserRepository.createUser(
                loginName = "binding-auth-owner",
                fullName = "Binding Auth Owner",
                phoneNumber = "13800007791",
                passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
            ) ?: error("failed to create user")
            val room = GroupRepository.createGroup("Binding Auth Room", owner.id)
                ?: error("failed to create room")
            val first = seedAgent(owner.id, "first")
            val second = seedAgent(owner.id, "second")
            seedBinding(first, owner.id, room.id)

            val firstClient = AcpClient(InMemoryAcpTransport(), backgroundScope)
            AcpRegistry.put(
                userId = owner.id,
                agentType = "codex",
                client = firstClient,
                remoteIp = null,
                authenticationMode = AcpRegistry.AuthenticationMode.DEVICE_SIGNATURE,
                agentInstanceId = second,
                capabilities = setOf(AgentCapability.PROMPT),
            )
            val wrongInstance = AgentBindingAuthorizationService.authorize(
                userId = owner.id,
                agentType = "codex",
                targetType = AgentBindingTargetType.ROOM,
                targetId = room.id,
                messageScope = AgentBindingMessageScope.TEAM,
                requiredPermissions = setOf(AgentPermission.READ_MESSAGE, AgentPermission.SEND_MESSAGE),
                requiredCapabilities = setOf(AgentCapability.PROMPT),
            )
            assertEquals("AGENT_NOT_BOUND", wrongInstance.errorCode)

            AcpRegistry.put(
                userId = owner.id,
                agentType = "codex",
                client = AcpClient(InMemoryAcpTransport(), backgroundScope),
                remoteIp = null,
                authenticationMode = AcpRegistry.AuthenticationMode.DEVICE_SIGNATURE,
                agentInstanceId = first,
                capabilities = setOf(AgentCapability.PROMPT),
            )
            val oldAdapter = AgentBindingAuthorizationService.authorize(
                userId = owner.id,
                agentType = "codex",
                targetType = AgentBindingTargetType.ROOM,
                targetId = room.id,
                messageScope = AgentBindingMessageScope.TEAM,
                requiredPermissions = setOf(AgentPermission.READ_MESSAGE, AgentPermission.SEND_MESSAGE),
                requiredCapabilities = setOf(
                    AgentCapability.PROMPT,
                    AgentCapability.EXECUTION_POLICY_V1,
                ),
            )
            assertEquals("CAPABILITY_DENIED", oldAdapter.errorCode)

            AcpRegistry.put(
                userId = owner.id,
                agentType = "codex",
                client = AcpClient(InMemoryAcpTransport(), backgroundScope),
                remoteIp = null,
                authenticationMode = AcpRegistry.AuthenticationMode.DEVICE_SIGNATURE,
                agentInstanceId = first,
                capabilities = setOf(
                    AgentCapability.PROMPT,
                    AgentCapability.EXECUTION_POLICY_V1,
                ),
            )
            val authorized = AgentBindingAuthorizationService.authorize(
                userId = owner.id,
                agentType = "codex",
                targetType = AgentBindingTargetType.ROOM,
                targetId = room.id,
                messageScope = AgentBindingMessageScope.TEAM,
                requiredPermissions = setOf(AgentPermission.READ_MESSAGE, AgentPermission.SEND_MESSAGE),
                requiredCapabilities = setOf(
                    AgentCapability.PROMPT,
                    AgentCapability.EXECUTION_POLICY_V1,
                ),
            )
            assertTrue(authorized.allowed)
            assertEquals(false, authorized.executionPolicy?.readFile)
            assertEquals(false, authorized.executionPolicy?.writeFile)
            assertEquals(false, authorized.executionPolicy?.runCommand)
        }
    }

    @Test
    fun `file RPC requires both binding permission and declared capability`() = runTest {
        TestWorkspace().use {
            val owner = UserRepository.createUser(
                loginName = "binding-auth-files",
                fullName = "Binding Auth Files",
                phoneNumber = "13800007792",
                passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
            ) ?: error("failed to create user")
            val workspaceId = "workspace-files"
            val agentId = seedAgent(owner.id, "files")
            seedWorkspaceBinding(agentId, owner.id, workspaceId)
            AcpRegistry.put(
                userId = owner.id,
                agentType = "codex",
                client = AcpClient(InMemoryAcpTransport(), backgroundScope),
                remoteIp = null,
                authenticationMode = AcpRegistry.AuthenticationMode.DEVICE_SIGNATURE,
                agentInstanceId = agentId,
                capabilities = setOf(AgentCapability.PROMPT),
            )
            val denied = AgentBindingAuthorizationService.authorize(
                userId = owner.id,
                agentType = "codex",
                targetType = AgentBindingTargetType.WORKSPACE,
                targetId = workspaceId,
                messageScope = AgentBindingMessageScope.WORKSPACE,
                requiredPermissions = setOf(AgentPermission.READ_FILE),
                requiredCapabilities = setOf(AgentCapability.READ_FILE),
            )
            assertEquals("CAPABILITY_DENIED", denied.errorCode)

            AcpRegistry.put(
                userId = owner.id,
                agentType = "codex",
                client = AcpClient(InMemoryAcpTransport(), backgroundScope),
                remoteIp = null,
                authenticationMode = AcpRegistry.AuthenticationMode.DEVICE_SIGNATURE,
                agentInstanceId = agentId,
                capabilities = setOf(AgentCapability.PROMPT, AgentCapability.READ_FILE),
            )
            val authorized = AgentBindingAuthorizationService.authorize(
                userId = owner.id,
                agentType = "codex",
                targetType = AgentBindingTargetType.WORKSPACE,
                targetId = workspaceId,
                messageScope = AgentBindingMessageScope.WORKSPACE,
                requiredPermissions = setOf(AgentPermission.READ_FILE),
                requiredCapabilities = setOf(AgentCapability.READ_FILE),
            )
            assertTrue(authorized.allowed)
            assertEquals(true, authorized.executionPolicy?.readFile)
            assertEquals(false, authorized.executionPolicy?.writeFile)
            assertEquals(false, authorized.executionPolicy?.runCommand)

            assertTrue(
                AgentAuthRepository.updateAgentRuntimePermissionMode(
                    owner.id,
                    agentId,
                    AgentRuntimePermissionMode.READ_ONLY,
                ),
            )
            val readOnly = AgentBindingAuthorizationService.authorize(
                userId = owner.id,
                agentType = "codex",
                agentInstanceId = agentId,
                targetType = AgentBindingTargetType.WORKSPACE,
                targetId = workspaceId,
                messageScope = AgentBindingMessageScope.WORKSPACE,
                requiredPermissions = setOf(AgentPermission.READ_FILE),
                requiredCapabilities = setOf(AgentCapability.READ_FILE),
            )
            assertTrue(readOnly.allowed)
            assertEquals(AgentAccessMode.READ_ONLY, readOnly.executionPolicy?.accessMode)
            assertEquals(AgentRuntimePermissionMode.READ_ONLY, readOnly.executionPolicy?.agentPermissionMode)
            assertEquals(true, readOnly.executionPolicy?.readFile)
            assertEquals(false, readOnly.executionPolicy?.writeFile)
            assertEquals(false, readOnly.executionPolicy?.runCommand)

            AgentAuthRepository.updateAgentRuntimePermissionMode(
                owner.id,
                agentId,
                AgentRuntimePermissionMode.AUTOMATIC,
            )
            val workspaceApprovalWins = AgentBindingAuthorizationService.authorize(
                userId = owner.id,
                agentType = "codex",
                agentInstanceId = agentId,
                targetType = AgentBindingTargetType.WORKSPACE,
                targetId = workspaceId,
                messageScope = AgentBindingMessageScope.WORKSPACE,
                requiredPermissions = setOf(AgentPermission.READ_FILE),
                requiredCapabilities = setOf(AgentCapability.READ_FILE),
            )
            assertEquals(AgentAccessMode.APPROVAL_REQUIRED, workspaceApprovalWins.executionPolicy?.accessMode)
            assertEquals(AgentRuntimePermissionMode.AUTOMATIC, workspaceApprovalWins.executionPolicy?.agentPermissionMode)
        }
    }

    private fun seedAgent(userId: String, suffix: String): String {
        val now = LocalDateTime.now()
        val deviceId = "device-$suffix"
        val agentId = "agent-$suffix"
        transaction {
            AgentDevices.insert { row ->
                row[AgentDevices.id] = deviceId
                row[AgentDevices.userId] = userId
                row[AgentDevices.publicKey] = "public-$suffix"
                row[AgentDevices.keyAlgorithm] = AgentAuthProtocol.KEY_ALGORITHM
                row[AgentDevices.fingerprint] = "fingerprint-$suffix"
                row[AgentDevices.displayName] = "Test Device $suffix"
                row[AgentDevices.status] = DeviceEnrollmentStatus.ACTIVE.name
                row[AgentDevices.platform] = "test"
                row[AgentDevices.authenticationOrigin] = "https://silk.example.com"
                row[AgentDevices.createdAt] = now
            }
            AgentInstances.insert { row ->
                row[AgentInstances.id] = agentId
                row[AgentInstances.userId] = userId
                row[AgentInstances.deviceId] = deviceId
                row[AgentInstances.agentType] = "codex"
                row[AgentInstances.transportAdapter] = AgentTransportAdapter.ACP.name
                row[AgentInstances.displayName] = "Test Codex $suffix"
                row[AgentInstances.connectorVersion] = "test"
                row[AgentInstances.capabilitiesJson] = "[\"PROMPT\",\"READ_FILE\"]"
                row[AgentInstances.status] = AgentInstanceStatus.ACTIVE.name
                row[AgentInstances.createdAt] = now
            }
        }
        return agentId
    }

    private fun seedBinding(agentId: String, ownerId: String, roomId: String) {
        val now = LocalDateTime.now()
        transaction {
            AgentBindings.insert { row ->
                row[AgentBindings.id] = "binding-room"
                row[AgentBindings.agentInstanceId] = agentId
                row[AgentBindings.targetType] = AgentBindingTargetType.ROOM.name
                row[AgentBindings.targetId] = roomId
                row[AgentBindings.messageScope] = AgentBindingMessageScope.TEAM.name
                row[AgentBindings.triggerPolicy] = AgentTriggerPolicy.MENTION.name
                row[AgentBindings.permissionsJson] = "[\"READ_MESSAGE\",\"SEND_MESSAGE\"]"
                row[AgentBindings.status] = AgentBindingStatus.ACTIVE.name
                row[AgentBindings.createdBy] = ownerId
                row[AgentBindings.createdAt] = now
            }
        }
    }

    private fun seedWorkspaceBinding(agentId: String, ownerId: String, workspaceId: String) {
        val now = LocalDateTime.now()
        transaction {
            AgentBindings.insert { row ->
                row[AgentBindings.id] = "binding-workspace"
                row[AgentBindings.agentInstanceId] = agentId
                row[AgentBindings.targetType] = AgentBindingTargetType.WORKSPACE.name
                row[AgentBindings.targetId] = workspaceId
                row[AgentBindings.messageScope] = AgentBindingMessageScope.WORKSPACE.name
                row[AgentBindings.triggerPolicy] = AgentTriggerPolicy.ALL.name
                row[AgentBindings.permissionsJson] =
                    "[\"READ_MESSAGE\",\"SEND_MESSAGE\",\"READ_FILE\",\"WRITE_FILE\",\"RUN_COMMAND\"]"
                row[AgentBindings.status] = AgentBindingStatus.ACTIVE.name
                row[AgentBindings.createdBy] = ownerId
                row[AgentBindings.createdAt] = now
            }
        }
    }
}
