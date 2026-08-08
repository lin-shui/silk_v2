package com.silk.backend.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GitIngestionModeResolverTest {
    @Test
    fun `auto uses polling when webhook url is absent`() {
        val selection = GitIngestionModeResolver.resolve(
            preference = GitIngestionPreference.AUTO,
            explicitWebhookBaseUrl = null,
            legacyBackendBaseUrl = "https://backend.example",
        )

        assertEquals(GitIngestionMode.POLLING, selection.mode)
        assertNull(selection.callbackBase)
    }

    @Test
    fun `auto uses only an explicit valid https webhook url`() {
        val selection = GitIngestionModeResolver.resolve(
            preference = GitIngestionPreference.AUTO,
            explicitWebhookBaseUrl = "https://silk.example/",
        )

        assertEquals(GitIngestionMode.WEBHOOK, selection.mode)
        assertEquals("https://silk.example", selection.callbackBase)
    }

    @Test
    fun `auto falls back to polling for invalid webhook url`() {
        val selection = GitIngestionModeResolver.resolve(
            preference = GitIngestionPreference.AUTO,
            explicitWebhookBaseUrl = "http://silk.example",
        )

        assertEquals(GitIngestionMode.POLLING, selection.mode)
        assertNotNull(selection.warning)
    }

    @Test
    fun `webhook url must not point to local or private network`() {
        assertEquals(false, GitIngestionModeResolver.isUsableWebhookBase("https://localhost:8006"))
        assertEquals(false, GitIngestionModeResolver.isUsableWebhookBase("https://127.0.0.1"))
        assertEquals(false, GitIngestionModeResolver.isUsableWebhookBase("https://192.168.1.10"))
        assertEquals(false, GitIngestionModeResolver.isUsableWebhookBase("https://10.0.0.2"))
        assertEquals(false, GitIngestionModeResolver.isUsableWebhookBase("https://[::1]"))
        assertEquals(false, GitIngestionModeResolver.isUsableWebhookBase("https://[fe80::1]"))
    }

    @Test
    fun `forced webhook keeps legacy backend url compatibility`() {
        val selection = GitIngestionModeResolver.resolve(
            preference = GitIngestionPreference.WEBHOOK,
            explicitWebhookBaseUrl = null,
            legacyBackendBaseUrl = "https://legacy.example",
        )

        assertEquals(GitIngestionMode.WEBHOOK, selection.mode)
        assertEquals("https://legacy.example", selection.callbackBase)
    }
}
