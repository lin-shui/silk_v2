package com.silk.backend.agents.auth

import com.silk.backend.TestWorkspace
import com.silk.backend.database.AgentPairingRequests
import com.silk.backend.database.UserRepository
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentPairingRetentionTest {
    @Test
    fun `new pairing prunes records older than retention without deleting recent records`() {
        TestWorkspace().use {
            val user = UserRepository.createUser(
                loginName = "pairing-retention-owner",
                fullName = "Pairing Retention Owner",
                phoneNumber = "13800007991",
                passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
            ) ?: error("failed to create user")
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val old = AgentAuthRepository.createPairing(
                request = request(),
                intendedOwnerId = user.id,
                normalizedPublicKey = RAW_TEST_KEY,
                canonicalAgentType = "codex",
                serverOrigin = "https://silk.example.com",
                now = now.minusDays(8),
            )
            val recent = AgentAuthRepository.createPairing(
                request = request(),
                intendedOwnerId = user.id,
                normalizedPublicKey = RAW_TEST_KEY,
                canonicalAgentType = "codex",
                serverOrigin = "https://silk.example.com",
                now = now.minusDays(1),
            )
            val current = AgentAuthRepository.createPairing(
                request = request(),
                intendedOwnerId = user.id,
                normalizedPublicKey = RAW_TEST_KEY,
                canonicalAgentType = "codex",
                serverOrigin = "https://silk.example.com",
                now = now,
            )

            val ids = transaction {
                AgentPairingRequests.selectAll().map { it[AgentPairingRequests.id] }.toSet()
            }
            assertFalse(old.record.pairingId in ids)
            assertTrue(recent.record.pairingId in ids)
            assertTrue(current.record.pairingId in ids)
        }
    }

    private fun request() = CreateAgentPairingRequest(
        protocolVersion = AgentAuthProtocol.VERSION,
        keyAlgorithm = AgentAuthProtocol.KEY_ALGORITHM,
        publicKey = RAW_TEST_KEY,
        accountLoginName = "pairing-retention-owner",
        connectionOrigin = "https://silk.example.com",
        deviceName = "retention-test",
        platform = "linux",
        agentType = "codex",
        agentDisplayName = "Codex",
        transportAdapter = AgentTransportAdapter.ACP,
        connectorVersion = "test",
        capabilities = emptySet(),
    )

    companion object {
        private const val RAW_TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
