package com.silk.backend.git

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object GitHubWebhookVerifier {
    private const val PREFIX = "sha256="

    fun signature(body: ByteArray, secret: String): String =
        signature(body, secret.toByteArray(Charsets.UTF_8))

    fun signature(body: ByteArray, secret: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return PREFIX + mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    fun verify(body: ByteArray, header: String?, secret: String): Boolean =
        verify(body, header, secret.toByteArray(Charsets.UTF_8))

    fun verify(body: ByteArray, header: String?, secret: ByteArray): Boolean {
        if (header == null || !header.startsWith(PREFIX) || header.length != PREFIX.length + 64) return false
        val supplied = header.substring(PREFIX.length)
        if (!supplied.all { it in "0123456789abcdefABCDEF" }) return false
        val expected = signature(body, secret).substring(PREFIX.length)
        return MessageDigest.isEqual(
            supplied.lowercase().toByteArray(Charsets.US_ASCII),
            expected.toByteArray(Charsets.US_ASCII),
        )
    }
}
