package com.silk.backend.agents.auth

import com.silk.backend.routes.AgentAuthRateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentAuthRateLimiterTest {
    @Test
    fun `limits each key and resets after the window`() {
        var now = 1_000L
        val limiter = AgentAuthRateLimiter(clock = { now }, windowMs = 100L)

        assertTrue(limiter.allow("pairing:one", 2))
        assertTrue(limiter.allow("pairing:one", 2))
        assertFalse(limiter.allow("pairing:one", 2))
        assertTrue(limiter.allow("pairing:two", 2))

        now += 100L
        assertTrue(limiter.allow("pairing:one", 2))
        assertEquals(1, limiter.bucketCountForTest())
    }

    @Test
    fun `periodic cleanup removes stale attacker keys`() {
        var now = 5_000L
        val limiter = AgentAuthRateLimiter(clock = { now }, windowMs = 60L)

        repeat(200) { index ->
            assertTrue(limiter.allow("proof:source:$index", 1))
        }
        assertEquals(200, limiter.bucketCountForTest())

        now += 60L
        assertTrue(limiter.allow("current", 1))
        assertEquals(1, limiter.bucketCountForTest())
    }

    @Test
    fun `bucket cap rejects new attacker keys without blocking existing keys`() {
        val limiter = AgentAuthRateLimiter(windowMs = 60_000L, maxBuckets = 3)

        assertTrue(limiter.allow("one", 2))
        assertTrue(limiter.allow("two", 2))
        assertTrue(limiter.allow("three", 2))
        assertFalse(limiter.allow("four", 2))
        assertTrue(limiter.allow("one", 2))
        assertEquals(3, limiter.bucketCountForTest())
    }
}
