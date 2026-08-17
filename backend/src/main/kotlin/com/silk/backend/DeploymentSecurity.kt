package com.silk.backend

import java.net.URI

internal data class DeploymentSecurity(
    val production: Boolean,
    val backendOrigin: String?,
    val webOrigin: String?,
    val allowedCorsOrigins: List<String>,
) {
    fun validate() {
        if (!production) return
        requireHttpsOrigin("BACKEND_BASE_URL", backendOrigin)
        requireHttpsOrigin("BACKEND_WEB_APP_BASE_URL", webOrigin)
        require(allowedCorsOrigins.isNotEmpty()) {
            "SILK_CORS_ALLOWED_ORIGINS is required in production"
        }
        allowedCorsOrigins.forEach { requireHttpsOrigin("SILK_CORS_ALLOWED_ORIGINS", it) }
    }

    companion object {
        fun load(): DeploymentSecurity {
            val mode = configuredValue("SILK_ENV", "silk.environment")
                ?: configuredValue("SILK_DEPLOYMENT_MODE", "silk.deploymentMode")
            val production = mode.equals("production", ignoreCase = true)
            return DeploymentSecurity(
                production = production,
                backendOrigin = configuredValue("BACKEND_BASE_URL", "silk.backendBaseUrl"),
                webOrigin = configuredValue("BACKEND_WEB_APP_BASE_URL", "silk.webAppBaseUrl"),
                allowedCorsOrigins = configuredValue("SILK_CORS_ALLOWED_ORIGINS", "silk.corsAllowedOrigins")
                    ?.split(',')
                    ?.map { it.trim().trimEnd('/') }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    .orEmpty(),
            ).also { it.validate() }
        }

        private fun configuredValue(environmentName: String, propertyName: String): String? =
            System.getProperty(propertyName)?.trim()?.takeIf { it.isNotEmpty() }
                ?: System.getenv(environmentName)?.trim()?.takeIf { it.isNotEmpty() }
                ?: EnvLoader.get(environmentName)

        private fun requireHttpsOrigin(name: String, value: String?) {
            val uri = runCatching { URI(value.orEmpty()) }.getOrNull()
            require(
                uri != null && uri.scheme.equals("https", ignoreCase = true) &&
                    !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                    (uri.path.isNullOrBlank() || uri.path == "/"),
            ) { "$name must be a credential-free HTTPS origin in production" }
        }
    }
}
