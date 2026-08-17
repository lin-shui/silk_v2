package com.silk.backend

import com.silk.backend.routes.resolveAgentServerOrigin
import com.silk.backend.routes.resolveAgentVerificationOrigin
import com.silk.backend.routes.pairingVerificationUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentPairingOriginTest {
    @Test
    fun `client connection origin replaces internal listener fallback when unconfigured`() {
        assertEquals(
            "http://124.222.226.225:8006",
            resolveAgentServerOrigin(
                connectionOrigin = "HTTP://124.222.226.225:8006/",
                configuredOrigin = null,
            ),
        )
    }

    @Test
    fun `configured canonical origin overrides a connection alias`() {
        assertEquals(
            "https://silk.example.com",
            resolveAgentServerOrigin(
                connectionOrigin = "http://10.0.0.2:8006",
                configuredOrigin = "https://SILK.example.com:443/",
            ),
        )
    }

    @Test
    fun `verification origin supports split Web deployments without guessing a port`() {
        assertEquals(
            "http://124.222.226.225:8005",
            resolveAgentVerificationOrigin(
                requestedOrigin = "http://124.222.226.225:8005",
                serverOrigin = "http://124.222.226.225:8006",
                configuredOrigin = null,
            ),
        )
        assertEquals(
            "http://124.222.226.225:8006",
            resolveAgentVerificationOrigin(
                requestedOrigin = null,
                serverOrigin = "http://124.222.226.225:8006",
                configuredOrigin = null,
            ),
        )
    }

    @Test
    fun `origin validation rejects credentials paths and non-http schemes`() {
        assertFailsWith<IllegalArgumentException> {
            resolveAgentServerOrigin("https://user@example.com", configuredOrigin = null)
        }
        assertFailsWith<IllegalArgumentException> {
            resolveAgentServerOrigin("https://example.com/api", configuredOrigin = null)
        }
        assertFailsWith<IllegalArgumentException> {
            resolveAgentServerOrigin("file:///tmp/silk", configuredOrigin = null)
        }
    }

    @Test
    fun `verification link carries only the short code in a browser fragment`() {
        assertEquals(
            "https://silk.example.com/device#code=ABCD-5678",
            pairingVerificationUri("https://silk.example.com/", "abcd5678"),
        )
        assertFailsWith<IllegalArgumentException> {
            pairingVerificationUri("https://silk.example.com", "short")
        }
    }
}
