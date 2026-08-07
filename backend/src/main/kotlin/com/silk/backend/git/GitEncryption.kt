@file:Suppress("TooGenericExceptionCaught", "ThrowsCount", "UseRequire")

package com.silk.backend.git

import com.silk.backend.EnvLoader
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-GCM envelope used for credentials stored in git_integration_store.json. */
object GitEncryption {
    private const val VERSION = "v1"
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val secureRandom = SecureRandom()

    class ConfigurationException(message: String) : IllegalStateException(message)

    fun keyFromBase64(value: String?): ByteArray {
        if (value.isNullOrBlank()) {
            throw ConfigurationException("SILK_ENCRYPTION_KEY is not configured")
        }
        val decoded = try {
            Base64.getDecoder().decode(value.trim())
        } catch (_: IllegalArgumentException) {
            throw ConfigurationException("SILK_ENCRYPTION_KEY must be valid Base64")
        }
        if (decoded.size != KEY_BYTES) {
            throw ConfigurationException("SILK_ENCRYPTION_KEY must decode to 32 bytes")
        }
        return decoded
    }

    fun configuredKey(): ByteArray = keyFromBase64(
        EnvLoader.get("SILK_ENCRYPTION_KEY") ?: System.getenv("SILK_ENCRYPTION_KEY")
    )

    fun encrypt(plaintext: String, key: ByteArray = configuredKey()): String {
        validateKey(key)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "$VERSION:${Base64.getEncoder().encodeToString(nonce + ciphertext)}"
    }

    fun decrypt(envelope: String, key: ByteArray = configuredKey()): String {
        validateKey(key)
        val separator = envelope.indexOf(':')
        if (separator <= 0 || envelope.substring(0, separator) != VERSION) {
            throw IllegalArgumentException("Unsupported encrypted value version")
        }
        val payload = try {
            Base64.getDecoder().decode(envelope.substring(separator + 1))
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid encrypted value")
        }
        if (payload.size <= NONCE_BYTES + TAG_BITS / 8) {
            throw IllegalArgumentException("Invalid encrypted value")
        }
        val nonce = payload.copyOfRange(0, NONCE_BYTES)
        val ciphertext = payload.copyOfRange(NONCE_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return try {
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("Unable to decrypt encrypted value", e)
        }
    }

    private fun validateKey(key: ByteArray) {
        if (key.size != KEY_BYTES) {
            throw ConfigurationException("Encryption key must be 32 bytes")
        }
    }
}
