package com.silk.backend.git

import com.silk.backend.EnvLoader

object GitConfig {
    private fun value(name: String): String? = EnvLoader.get(name) ?: System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }

    val explicitWebhookBaseUrl: String?
        get() = value("GITHUB_WEBHOOK_BASE_URL")

    val legacyBackendBaseUrl: String?
        get() = value("BACKEND_BASE_URL")

    val ingestionPreference: GitIngestionPreference
        get() = when (value("GITHUB_INGESTION_MODE")?.uppercase()) {
            "WEBHOOK" -> GitIngestionPreference.WEBHOOK
            "POLLING" -> GitIngestionPreference.POLLING
            else -> GitIngestionPreference.AUTO
        }

    val githubApiBaseUrl: String
        get() = value("GITHUB_API_BASE_URL") ?: "https://api.github.com"

    val apiTimeoutMs: Long
        get() = value("GITHUB_API_TIMEOUT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 15_000L

    val pollIntervalMs: Long
        get() = (value("GITHUB_POLL_INTERVAL_SECONDS")?.toLongOrNull()?.takeIf { it >= 60 } ?: 120L) * 1_000L

    val webhookSecretBytes: Int get() = 32
}
