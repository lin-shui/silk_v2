package com.silk.backend.agents.auth

import com.silk.backend.TestWorkspace
import com.silk.backend.database.AgentBindings
import com.silk.backend.database.AgentBindingAuditEvents
import com.silk.backend.database.AgentDeviceRevocationTombstones
import com.silk.backend.database.AgentDevices
import com.silk.backend.database.AgentInstances
import com.silk.backend.database.AgentSecurityEvents
import com.silk.backend.database.UserRepository
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentRevocationCleanupTest {
    @Test
    fun `expired revoked device history is purged but audit and key tombstone remain`() {
        TestWorkspace().use {
            val owner = UserRepository.createUser(
                loginName = "cleanup-owner",
                fullName = "Cleanup Owner",
                phoneNumber = "13800009991",
                passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
            ) ?: error("Failed to create owner")
            val now = LocalDateTime.of(2026, 8, 14, 0, 0)
            val revokedAt = now.minusDays(91)
            val deviceId = "device-cleanup"
            val agentId = "agent-cleanup"
            val bindingId = "binding-cleanup"
            val activeDeviceId = "device-active"
            val standaloneAgentId = "agent-standalone"
            val standaloneBindingId = "binding-standalone"
            val publicKey = "cleanup-public-key"
            val fingerprint = "SHA256:cleanup-fingerprint"

            transaction {
                AgentDevices.insert { row ->
                    row[id] = deviceId
                    row[userId] = owner.id
                    row[AgentDevices.publicKey] = publicKey
                    row[keyAlgorithm] = AgentAuthProtocol.KEY_ALGORITHM
                    row[AgentDevices.fingerprint] = fingerprint
                    row[displayName] = "Old device"
                    row[status] = DeviceEnrollmentStatus.REVOKED.name
                    row[platform] = "linux"
                    row[authenticationOrigin] = "https://silk.example"
                    row[createdAt] = revokedAt.minusDays(1)
                    row[AgentDevices.revokedAt] = revokedAt
                }
                AgentInstances.insert { row ->
                    row[id] = agentId
                    row[userId] = owner.id
                    row[AgentInstances.deviceId] = deviceId
                    row[agentType] = "codex"
                    row[transportAdapter] = AgentTransportAdapter.ACP.name
                    row[displayName] = "Old Codex"
                    row[connectorVersion] = "0.4.0"
                    row[capabilitiesJson] = "[]"
                    row[status] = AgentInstanceStatus.REVOKED.name
                    row[createdAt] = revokedAt.minusDays(1)
                    row[AgentInstances.revokedAt] = revokedAt
                }
                AgentBindings.insert { row ->
                    row[id] = bindingId
                    row[agentInstanceId] = agentId
                    row[targetType] = AgentBindingTargetType.ROOM.name
                    row[targetId] = "room-cleanup"
                    row[messageScope] = AgentBindingMessageScope.TEAM.name
                    row[triggerPolicy] = AgentTriggerPolicy.MENTION.name
                    row[permissionsJson] = "[\"READ_MESSAGE\"]"
                    row[status] = AgentBindingStatus.REVOKED.name
                    row[createdBy] = owner.id
                    row[ownerId] = owner.id
                    row[createdAt] = revokedAt.minusDays(1)
                    row[revokedBy] = owner.id
                    row[AgentBindings.revokedAt] = revokedAt
                }
                AgentBindingAuditEvents.insert { row ->
                    row[id] = "audit-cleanup"
                    row[AgentBindingAuditEvents.bindingId] = bindingId
                    row[actorId] = owner.id
                    row[action] = "DEVICE_REVOKED"
                    row[snapshotJson] = "{}"
                    row[createdAt] = revokedAt
                }
                AgentSecurityEvents.insert { row ->
                    row[userId] = owner.id
                    row[actorId] = owner.id
                    row[action] = AgentSecurityEventAction.DEVICE_REVOKED.name
                    row[AgentSecurityEvents.deviceId] = deviceId
                    row[agentInstanceId] = null
                    row[metadataJson] = "{}"
                    row[createdAt] = revokedAt
                }
                AgentDevices.insert { row ->
                    row[id] = activeDeviceId
                    row[userId] = owner.id
                    row[AgentDevices.publicKey] = "active-public-key"
                    row[keyAlgorithm] = AgentAuthProtocol.KEY_ALGORITHM
                    row[AgentDevices.fingerprint] = "SHA256:active-fingerprint"
                    row[displayName] = "Active device"
                    row[status] = DeviceEnrollmentStatus.ACTIVE.name
                    row[platform] = "linux"
                    row[authenticationOrigin] = "https://silk.example"
                    row[createdAt] = revokedAt.minusDays(1)
                }
                AgentInstances.insert { row ->
                    row[id] = standaloneAgentId
                    row[userId] = owner.id
                    row[AgentInstances.deviceId] = activeDeviceId
                    row[agentType] = "codex"
                    row[transportAdapter] = AgentTransportAdapter.ACP.name
                    row[displayName] = "Old standalone Codex"
                    row[connectorVersion] = "0.4.0"
                    row[capabilitiesJson] = "[]"
                    row[status] = AgentInstanceStatus.REVOKED.name
                    row[createdAt] = revokedAt.minusDays(1)
                    row[AgentInstances.revokedAt] = revokedAt
                }
                AgentBindings.insert { row ->
                    row[id] = standaloneBindingId
                    row[agentInstanceId] = standaloneAgentId
                    row[targetType] = AgentBindingTargetType.ROOM.name
                    row[targetId] = "room-cleanup-standalone"
                    row[messageScope] = AgentBindingMessageScope.TEAM.name
                    row[triggerPolicy] = AgentTriggerPolicy.MENTION.name
                    row[permissionsJson] = "[\"READ_MESSAGE\"]"
                    row[status] = AgentBindingStatus.REVOKED.name
                    row[createdBy] = owner.id
                    row[ownerId] = owner.id
                    row[createdAt] = revokedAt.minusDays(1)
                    row[revokedBy] = owner.id
                    row[AgentBindings.revokedAt] = revokedAt
                }
            }

            val result = AgentAuthRepository.cleanupRevokedRecords(owner.id, now)
            assertEquals(90L, result.retentionDays)
            assertEquals(1, result.deletedDevices)
            assertEquals(2, result.deletedAgents)
            assertEquals(2, result.deletedBindings)

            transaction {
                assertEquals(1L, AgentDevices.selectAll().count())
                assertEquals(0L, AgentInstances.selectAll().count())
                assertEquals(0L, AgentBindings.selectAll().count())
                assertEquals(1L, AgentBindingAuditEvents.selectAll().count())
                assertEquals(1L, AgentSecurityEvents.selectAll().count())
                assertTrue(AgentDeviceRevocationTombstones.selectAll().count() == 1L)
            }

            val request = CreateAgentPairingRequest(
                protocolVersion = AgentAuthProtocol.VERSION,
                keyAlgorithm = AgentAuthProtocol.KEY_ALGORITHM,
                publicKey = publicKey,
                accountLoginName = owner.loginName,
                connectionOrigin = "https://silk.example",
                deviceName = "Old device",
                platform = "linux",
                agentType = "codex",
                agentDisplayName = "Codex",
                transportAdapter = AgentTransportAdapter.ACP,
                connectorVersion = "0.4.0",
            )
            val error = assertFailsWith<AgentAuthException> {
                AgentAuthRepository.createPairing(
                    request = request,
                    intendedOwnerId = owner.id,
                    normalizedPublicKey = publicKey,
                    canonicalAgentType = "codex",
                    serverOrigin = "https://silk.example",
                    now = now,
                )
            }
            assertEquals("DEVICE_KEY_REVOKED", error.errorCode)
        }
    }

    @Test
    fun `revocation retention configuration is bounded`() {
        System.setProperty("silk.agentRevokedRetentionDays", "1")
        try {
            assertEquals(7L, AgentAuthRepository.configuredRevocationRetentionDays())
        } finally {
            System.clearProperty("silk.agentRevokedRetentionDays")
        }
    }
}
