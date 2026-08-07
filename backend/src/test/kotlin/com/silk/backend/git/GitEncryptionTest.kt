package com.silk.backend.git

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class GitEncryptionTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `AES GCM round trip uses versioned envelope`() {
        val encrypted = GitEncryption.encrypt("private PAT", key)
        assertEquals("v1", encrypted.substringBefore(':'))
        assertEquals("private PAT", GitEncryption.decrypt(encrypted, key))
    }

    @Test
    fun `encryptions use a fresh nonce`() {
        val first = GitEncryption.encrypt("same", key)
        val second = GitEncryption.encrypt("same", key)
        assertNotEquals(first, second)
    }

    @Test
    fun `invalid key and corrupted ciphertext fail closed`() {
        assertFailsWith<GitEncryption.ConfigurationException> { GitEncryption.encrypt("x", ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { GitEncryption.decrypt("v1:${Base64.getEncoder().encodeToString(ByteArray(20))}", key) }
        assertFailsWith<IllegalArgumentException> { GitEncryption.decrypt(GitEncryption.encrypt("x", key), ByteArray(32) { 1 }) }
    }

    @Test
    fun `environment key must be exactly 32 decoded bytes`() {
        assertEquals(32, GitEncryption.keyFromBase64(Base64.getEncoder().encodeToString(key)).size)
        assertFailsWith<GitEncryption.ConfigurationException> { GitEncryption.keyFromBase64(null) }
        assertFailsWith<GitEncryption.ConfigurationException> {
            GitEncryption.keyFromBase64(Base64.getEncoder().encodeToString(ByteArray(16)))
        }
    }
}
