package com.silk.backend.git

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitEventSummaryServiceTest {
    private val service = GitEventSummaryService()

    @Test
    fun `summarizes pull requests and failed checks only`() {
        assertTrue(service.shouldSummarize(event("pull_request", "pull_request/opened")))
        assertTrue(service.shouldSummarize(event("check_run", "check_run/completed | conclusion=timed_out")))
        assertFalse(service.shouldSummarize(event("check_run", "check_run/completed | conclusion=success")))
        assertFalse(service.shouldSummarize(event("issues", "issues/opened")))
    }

    @Test
    fun `summary prompt contains bounded event fields`() {
        val prompt = service.buildPrompt(event("pull_request", "pull_request/opened"))
        assertTrue(prompt.contains("octo/demo"))
        assertTrue(prompt.contains("Improve login"))
        assertTrue(prompt.length <= 2_000)
    }

    private fun event(kind: String, summary: String) = GitEventRecord(
        deliveryId = "delivery",
        roomId = "room",
        event = kind,
        action = "completed",
        repository = "octo/demo",
        issueNumber = 8,
        title = "Improve login",
        htmlUrl = "https://github.com/octo/demo/pull/8",
        summary = summary,
        createdAt = 1,
    )
}
