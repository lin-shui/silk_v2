package com.silk.backend.git

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubWebhookVerifierTest {
    @Test
    fun `verifies the original bytes and rejects malformed signatures`() {
        val body = "{\"action\":\"opened\"}".toByteArray()
        val signature = GitHubWebhookVerifier.signature(body, "secret")
        assertTrue(GitHubWebhookVerifier.verify(body, signature, "secret"))
        assertTrue(GitHubWebhookVerifier.verify(body, "sha256=${signature.removePrefix("sha256=").uppercase()}", "secret"))
        assertFalse(GitHubWebhookVerifier.verify(body + 1, signature, "secret"))
        assertFalse(GitHubWebhookVerifier.verify(body, null, "secret"))
        assertFalse(GitHubWebhookVerifier.verify(body, "sha1=$signature", "secret"))
        assertFalse(GitHubWebhookVerifier.verify(body, "sha256=not-hex", "secret"))
    }
}
