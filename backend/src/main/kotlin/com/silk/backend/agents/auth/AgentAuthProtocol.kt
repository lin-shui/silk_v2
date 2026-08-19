package com.silk.backend.agents.auth

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Stable v1 wire and signature rules shared by the backend and silk-agent Host. */
object AgentAuthProtocol {
    const val VERSION = 1
    const val KEY_ALGORITHM = "Ed25519"
    const val DEVICE_POLL_SECRET_HEADER = "X-Silk-Device-Poll-Secret"

    private const val RAW_ED25519_PUBLIC_KEY_SIZE = 32
    private val ed25519X509Prefix = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
        0x70, 0x03, 0x21, 0x00,
    )
    private val secureRandom = SecureRandom()
    private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val base64UrlDecoder = Base64.getUrlDecoder()

    fun decodePublicKey(encoded: String): ByteArray {
        require(!encoded.contains('=')) { "publicKey must be unpadded base64url" }
        val raw = runCatching { base64UrlDecoder.decode(encoded) }
            .getOrElse { throw IllegalArgumentException("publicKey must be base64url") }
        require(raw.size == RAW_ED25519_PUBLIC_KEY_SIZE) {
            "publicKey must contain a raw 32-byte Ed25519 key"
        }
        return raw
    }

    fun normalizePublicKey(encoded: String): String = base64UrlEncoder.encodeToString(decodePublicKey(encoded))

    fun fingerprint(encoded: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(decodePublicKey(encoded))
        return "SHA256:${base64UrlEncoder.encodeToString(digest)}"
    }

    fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    fun secretsEqual(expectedHex: String, rawSecret: String): Boolean = MessageDigest.isEqual(
        expectedHex.toByteArray(Charsets.US_ASCII),
        sha256Hex(rawSecret).toByteArray(Charsets.US_ASCII),
    )

    fun randomOpaqueSecret(byteCount: Int = 32): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return base64UrlEncoder.encodeToString(bytes)
    }

    fun randomUserCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val chars = CharArray(8) { alphabet[secureRandom.nextInt(alphabet.length)] }
        return "${chars.concatToString(0, 4)}-${chars.concatToString(4, 8)}"
    }

    fun normalizeUserCode(value: String): String = value
        .trim()
        .uppercase()
        .replace(Regex("[^A-Z0-9]"), "")

    fun canonicalPairingProof(
        serverOrigin: String,
        pairingId: String,
        challengeId: String,
        nonce: String,
        deviceId: String,
        agentInstanceId: String,
        publicKeyFingerprint: String,
        timestampEpochMs: Long,
    ): ByteArray = canonicalLines(
        "silk-device-pairing/v1",
        serverOrigin,
        VERSION.toString(),
        pairingId,
        challengeId,
        nonce,
        deviceId,
        agentInstanceId,
        publicKeyFingerprint,
        timestampEpochMs.toString(),
    )

    fun canonicalAgentAuthentication(
        serverOrigin: String,
        challengeId: String,
        nonce: String,
        deviceId: String,
        agentInstanceId: String,
        timestampEpochMs: Long,
    ): ByteArray = canonicalLines(
        "silk-device-auth/v1",
        serverOrigin,
        VERSION.toString(),
        challengeId,
        nonce,
        deviceId,
        agentInstanceId,
        timestampEpochMs.toString(),
    )

    fun canonicalTrustedDeviceAgentRequest(
        serverOrigin: String,
        requestId: String,
        deviceId: String,
        agentType: String,
        agentDisplayName: String,
        transportAdapter: AgentTransportAdapter,
        connectorVersion: String,
        capabilities: Set<AgentCapability>,
        timestampEpochMs: Long,
    ): ByteArray = canonicalLines(
        "silk-agent-add/v1",
        serverOrigin,
        VERSION.toString(),
        requestId,
        deviceId,
        agentType,
        agentDisplayName,
        transportAdapter.name,
        connectorVersion,
        capabilities.map { it.name }.sorted().joinToString(","),
        timestampEpochMs.toString(),
    )

    fun canonicalAgentCapabilityRefresh(
        serverOrigin: String,
        deviceId: String,
        agentInstanceId: String,
        agentType: String,
        connectorVersion: String,
        capabilities: Set<AgentCapability>,
        timestampEpochMs: Long,
    ): ByteArray = canonicalLines(
        "silk-agent-capability-refresh/v1",
        serverOrigin,
        VERSION.toString(),
        deviceId,
        agentInstanceId,
        agentType,
        connectorVersion,
        capabilities.map { it.name }.sorted().joinToString(","),
        timestampEpochMs.toString(),
    )

    fun verifySignature(publicKey: String, payload: ByteArray, encodedSignature: String): Boolean {
        val signatureBytes = runCatching { base64UrlDecoder.decode(encodedSignature) }.getOrNull() ?: return false
        if (signatureBytes.size != 64) return false
        return runCatching {
            Signature.getInstance(KEY_ALGORITHM).run {
                initVerify(toJavaPublicKey(publicKey))
                update(payload)
                verify(signatureBytes)
            }
        }.getOrDefault(false)
    }

    private fun toJavaPublicKey(encoded: String): PublicKey {
        val x509 = ed25519X509Prefix + decodePublicKey(encoded)
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(x509))
    }

    private fun canonicalLines(vararg values: String): ByteArray {
        require(values.none { it.contains('\n') || it.contains('\r') }) {
            "canonical signature fields must not contain line breaks"
        }
        return values.joinToString(separator = "\n").toByteArray(Charsets.UTF_8)
    }
}
