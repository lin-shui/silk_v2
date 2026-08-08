package com.silk.backend.git

import java.net.URI

enum class GitIngestionPreference { AUTO, WEBHOOK, POLLING }

data class GitIngestionSelection(
    val mode: GitIngestionMode,
    val callbackBase: String? = null,
    val warning: String? = null,
)

/** Resolves the server-side delivery mode; clients never choose it. */
object GitIngestionModeResolver {
    fun resolve(
        preference: GitIngestionPreference = GitConfig.ingestionPreference,
        explicitWebhookBaseUrl: String? = GitConfig.explicitWebhookBaseUrl,
        legacyBackendBaseUrl: String? = GitConfig.legacyBackendBaseUrl,
    ): GitIngestionSelection = when (preference) {
        GitIngestionPreference.POLLING -> GitIngestionSelection(GitIngestionMode.POLLING)
        GitIngestionPreference.WEBHOOK -> {
            val callback = explicitWebhookBaseUrl ?: legacyBackendBaseUrl
            if (callback != null && isUsableWebhookBase(callback)) {
                GitIngestionSelection(GitIngestionMode.WEBHOOK, callback.trimEnd('/'))
            } else {
                GitIngestionSelection(
                    mode = GitIngestionMode.WEBHOOK,
                    warning = "Webhook 模式需要公开 HTTPS callback URL",
                )
            }
        }
        GitIngestionPreference.AUTO -> {
            when {
                explicitWebhookBaseUrl == null -> GitIngestionSelection(GitIngestionMode.POLLING)
                isUsableWebhookBase(explicitWebhookBaseUrl) ->
                    GitIngestionSelection(GitIngestionMode.WEBHOOK, explicitWebhookBaseUrl.trimEnd('/'))
                else -> GitIngestionSelection(
                    mode = GitIngestionMode.POLLING,
                    warning = "GITHUB_WEBHOOK_BASE_URL 不是有效 HTTPS 地址，已回退 Polling",
                )
            }
        }
    }

    fun isUsableWebhookBase(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            isPublicHost(uri.host) &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null
    }.getOrDefault(false)

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private fun isPublicHost(rawHost: String): Boolean {
        val host = rawHost.lowercase().trim('[', ']').trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (
            host == "::" || host == "::1" || host == "0:0:0:0:0:0:0:1" ||
            host.startsWith("fc") || host.startsWith("fd") ||
            host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
        ) return false
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return true
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 0 || first == 10 || first == 127 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 168 -> false
            first >= 224 -> false
            else -> true
        }
    }
}
