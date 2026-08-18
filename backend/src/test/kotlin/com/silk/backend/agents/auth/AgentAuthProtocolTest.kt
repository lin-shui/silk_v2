package com.silk.backend.agents.auth

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentAuthProtocolTest {
    @Test
    fun `capability refresh canonical payload is stable and sorted`() {
        val payload = AgentAuthProtocol.canonicalAgentCapabilityRefresh(
            serverOrigin = "https://silk.example.com",
            deviceId = "device-1",
            agentInstanceId = "agent-1",
            agentType = "codex",
            connectorVersion = "0.4.12",
            capabilities = setOf(AgentCapability.EXECUTION_POLICY_V2, AgentCapability.PROMPT, AgentCapability.STREAM),
            timestampEpochMs = 1_000L,
        )

        assertEquals(
            listOf(
                "silk-agent-capability-refresh/v1",
                "https://silk.example.com",
                "1",
                "device-1",
                "agent-1",
                "codex",
                "0.4.12",
                "EXECUTION_POLICY_V2,PROMPT,STREAM",
                "1000",
            ).joinToString("\n"),
            payload.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `trusted device Agent request has stable sorted canonical payload`() {
        val payload = AgentAuthProtocol.canonicalTrustedDeviceAgentRequest(
            serverOrigin = "https://silk.example.com",
            requestId = "request-1234567890123456789012",
            deviceId = "device-1",
            agentType = "codex",
            agentDisplayName = "Codex",
            transportAdapter = AgentTransportAdapter.ACP,
            connectorVersion = "0.2.0",
            capabilities = setOf(AgentCapability.STREAM, AgentCapability.CANCEL, AgentCapability.PROMPT),
            timestampEpochMs = 1_000L,
        )

        assertEquals(
            listOf(
                "silk-agent-add/v1",
                "https://silk.example.com",
                "1",
                "request-1234567890123456789012",
                "device-1",
                "codex",
                "Codex",
                "ACP",
                "0.2.0",
                "CANCEL,PROMPT,STREAM",
                "1000",
            ).joinToString("\n"),
            payload.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `raw Ed25519 key verifies canonical pairing payload and rejects mutation`() {
        val keyPair = keyPair()
        val publicKey = rawPublicKey(keyPair)
        val payload = AgentAuthProtocol.canonicalPairingProof(
            serverOrigin = "https://silk.example.com",
            pairingId = "pairing-1",
            challengeId = "challenge-1",
            nonce = "nonce-1",
            deviceId = "device-1",
            agentInstanceId = "agent-1",
            publicKeyFingerprint = AgentAuthProtocol.fingerprint(publicKey),
            timestampEpochMs = 1_000L,
        )
        val signature = sign(keyPair, payload)

        assertTrue(AgentAuthProtocol.verifySignature(publicKey, payload, signature))
        assertFalse(AgentAuthProtocol.verifySignature(publicKey, payload + 0x01, signature))
    }

    @Test
    fun `connection challenge is one time origin bound and expires`() {
        var now = 10_000L
        val keyPair = keyPair()
        val publicKey = rawPublicKey(keyPair)
        val service = AgentChallengeService(
            clock = { now },
            challengeLifetimeMs = 1_000L,
            clockSkewMs = 2_000L,
        )
        val challenge = service.issue(
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
        val signature = sign(keyPair, payload)

        assertTrue(
            service.consumeAndVerify(
                challengeId = challenge.challengeId,
                serverOrigin = challenge.serverOrigin,
                deviceId = challenge.deviceId,
                agentInstanceId = challenge.agentInstanceId,
                timestampEpochMs = now,
                signature = signature,
            ) != null
        )
        assertNull(
            service.consumeAndVerify(
                challengeId = challenge.challengeId,
                serverOrigin = challenge.serverOrigin,
                deviceId = challenge.deviceId,
                agentInstanceId = challenge.agentInstanceId,
                timestampEpochMs = now,
                signature = signature,
            )
        )

        val originBound = service.issue(
            serverOrigin = "https://silk.example.com",
            deviceId = "device-1",
            agentInstanceId = "agent-1",
            publicKey = publicKey,
        )
        val originPayload = AgentAuthProtocol.canonicalAgentAuthentication(
            serverOrigin = originBound.serverOrigin,
            challengeId = originBound.challengeId,
            nonce = originBound.nonce,
            deviceId = originBound.deviceId,
            agentInstanceId = originBound.agentInstanceId,
            timestampEpochMs = now,
        )
        assertNull(
            service.consumeAndVerify(
                challengeId = originBound.challengeId,
                serverOrigin = "https://attacker.example.com",
                deviceId = originBound.deviceId,
                agentInstanceId = originBound.agentInstanceId,
                timestampEpochMs = now,
                signature = sign(keyPair, originPayload),
            )
        )
        assertNull(
            service.consumeAndVerify(
                challengeId = originBound.challengeId,
                serverOrigin = originBound.serverOrigin,
                deviceId = originBound.deviceId,
                agentInstanceId = originBound.agentInstanceId,
                timestampEpochMs = now,
                signature = sign(keyPair, originPayload),
            )
        )

        val expiring = service.issue(
            serverOrigin = "https://silk.example.com",
            deviceId = "device-1",
            agentInstanceId = "agent-1",
            publicKey = publicKey,
        )
        now += 1_001L
        val expiredPayload = AgentAuthProtocol.canonicalAgentAuthentication(
            serverOrigin = expiring.serverOrigin,
            challengeId = expiring.challengeId,
            nonce = expiring.nonce,
            deviceId = expiring.deviceId,
            agentInstanceId = expiring.agentInstanceId,
            timestampEpochMs = now,
        )
        assertNull(
            service.consumeAndVerify(
                challengeId = expiring.challengeId,
                serverOrigin = expiring.serverOrigin,
                deviceId = expiring.deviceId,
                agentInstanceId = expiring.agentInstanceId,
                timestampEpochMs = now,
                signature = sign(keyPair, expiredPayload),
            )
        )
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun rawPublicKey(keyPair: KeyPair): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(keyPair.public.encoded.takeLast(32).toByteArray())

    private fun sign(keyPair: KeyPair, payload: ByteArray): String {
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }
}
