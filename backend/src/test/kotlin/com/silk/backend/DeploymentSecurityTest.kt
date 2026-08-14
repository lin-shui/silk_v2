package com.silk.backend

import kotlin.test.Test
import kotlin.test.assertFailsWith

class DeploymentSecurityTest {
    @Test
    fun `production requires https origins and explicit cors allowlist`() {
        assertFailsWith<IllegalArgumentException> {
            DeploymentSecurity(
                production = true,
                backendOrigin = "http://silk.example.com",
                webOrigin = "https://silk.example.com",
                allowedCorsOrigins = listOf("https://silk.example.com"),
            ).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            DeploymentSecurity(
                production = true,
                backendOrigin = "https://api.silk.example.com",
                webOrigin = "https://silk.example.com",
                allowedCorsOrigins = emptyList(),
            ).validate()
        }

        DeploymentSecurity(
            production = true,
            backendOrigin = "https://api.silk.example.com",
            webOrigin = "https://silk.example.com",
            allowedCorsOrigins = listOf("https://silk.example.com"),
        ).validate()
    }

    @Test
    fun `development permits explicit insecure origins`() {
        DeploymentSecurity(
            production = false,
            backendOrigin = "http://127.0.0.1:8006",
            webOrigin = "http://127.0.0.1:8005",
            allowedCorsOrigins = emptyList(),
        ).validate()
    }
}
