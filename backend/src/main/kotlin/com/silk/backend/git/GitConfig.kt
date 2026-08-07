package com.silk.backend.git

import com.silk.backend.EnvLoader

object GitConfig {
    private fun value(name: String): String? = EnvLoader.get(name) ?: System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }

    val webhookBaseUrl: String?
        get() = value("GITHUB_WEBHOOK_BASE_URL") ?: value("BACKEND_BASE_URL")

    val githubApiBaseUrl: String
        get() = value("GITHUB_API_BASE_URL") ?: "https://api.github.com"

    val apiTimeoutMs: Long
        get() = value("GITHUB_API_TIMEOUT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 15_000L

    val webhookSecretBytes: Int get() = 32

    /** Polling interval in seconds. Floor of 20 guards against secondary rate limits. */
    val pollIntervalSeconds: Int
        get() = (value("GITHUB_POLL_INTERVAL_SECONDS")?.toIntOrNull()?.takeIf { it > 0 } ?: 60)
            .coerceAtLeast(20)
}
