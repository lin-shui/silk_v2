package com.silk.backend.agents.auth

import com.silk.backend.TestWorkspace
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentChallengePersistenceTest {
    @Test
    fun `challenge issued on one node is consumed once on another node`() {
        TestWorkspace().use {
            val now = 10_000L
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val publicKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(pair.public.encoded.takeLast(32).toByteArray())
            val issuer = AgentChallengeService(clock = { now }, store = DatabaseAgentChallengeStore)
            val verifier = AgentChallengeService(clock = { now }, store = DatabaseAgentChallengeStore)
            val challenge = issuer.issue(
                serverOrigin = "https://silk.example.com",
                deviceId = "device-1",
                agentInstanceId = "agent-1",
                publicKey = publicKey,
            )
            val payload = AgentAuthProtocol.canonicalAgentAuthentication(
                serverOrigin = challenge.serverOrigin,
                challengeId = challenge.challengeId,
                nonce = challenge.nonce,
                deviceId = challenge.deviceId,
                agentInstanceId = challenge.agentInstanceId,
                timestampEpochMs = now,
            )
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(pair.private)
            signer.update(payload)
            val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())

            assertNotNull(
                verifier.consumeAndVerify(
                    challengeId = challenge.challengeId,
                    serverOrigin = challenge.serverOrigin,
                    deviceId = challenge.deviceId,
                    agentInstanceId = challenge.agentInstanceId,
                    timestampEpochMs = now,
                    signature = signature,
                ),
            )
            assertNull(
                issuer.consumeAndVerify(
                    challengeId = challenge.challengeId,
                    serverOrigin = challenge.serverOrigin,
                    deviceId = challenge.deviceId,
                    agentInstanceId = challenge.agentInstanceId,
                    timestampEpochMs = now,
                    signature = signature,
                ),
            )
        }
    }
}
