package com.silk.backend.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitPollingParserTest {

    // ── inferAction ──────────────────────────────────────────────────────────

    @Test
    fun `opened when created after cursor and state is open`() {
        val cursor = ms("2026-08-01T10:00:00Z")
        val item = issue(state = "open", createdAt = "2026-08-01T11:00:00Z", updatedAt = "2026-08-01T11:00:00Z")
        assertEquals("opened", GitEventParser.inferAction(item, cursor))
    }

    @Test
    fun `not opened when created before cursor`() {
        val cursor = ms("2026-08-01T12:00:00Z")
        val item = issue(state = "open", createdAt = "2026-08-01T10:00:00Z", updatedAt = "2026-08-01T12:30:00Z")
        assertEquals("updated", GitEventParser.inferAction(item, cursor))
    }

    @Test
    fun `reopened when issue has state_reason reopened`() {
        val cursor = ms("2026-08-01T10:00:00Z")
        val item = issue(state = "open", createdAt = "2026-07-01T10:00:00Z", updatedAt = "2026-08-01T11:00:00Z",
            stateReason = "reopened")
        assertEquals("reopened", GitEventParser.inferAction(item, cursor))
    }

    @Test
    fun `PR reopened degrades to updated because PRs have no state_reason`() {
        val cursor = ms("2026-08-01T10:00:00Z")
        // Pull requests don't expose state_reason meaningfully
        val item = pr(state = "open", createdAt = "2026-07-01T10:00:00Z", updatedAt = "2026-08-01T11:00:00Z",
            stateReason = "reopened")
        // has pull_request key → reopened branch is skipped
        assertEquals("updated", GitEventParser.inferAction(item, cursor))
    }

    @Test
    fun `merged when PR has merged_at set`() {
        val cursor = ms("2026-08-01T10:00:00Z")
        val item = pr(state = "closed", createdAt = "2026-07-01T10:00:00Z", updatedAt = "2026-08-01T11:00:00Z",
            mergedAt = "2026-08-01T10:30:00Z")
        assertEquals("merged", GitEventParser.inferAction(item, cursor))
    }

    @Test
    fun `closed when closed_at is within poll window`() {
        val cursor = ms("2026-08-01T10:00:00Z")
        val item = issue(state = "closed", createdAt = "2026-07-01T10:00:00Z", updatedAt = "2026-08-01T11:00:00Z",
            closedAt = "2026-08-01T10:30:00Z")
        assertEquals("closed", GitEventParser.inferAction(item, cursor))
    }

    @Test
    fun `updated for closed issue that got a comment after poll cursor`() {
        val cursor = ms("2026-08-01T10:00:00Z")
        // closed_at is before cursor — already-closed issue just got a comment
        val item = issue(state = "closed", createdAt = "2026-07-01T10:00:00Z", updatedAt = "2026-08-01T11:00:00Z",
            closedAt = "2026-07-15T10:00:00Z")
        assertEquals("updated", GitEventParser.inferAction(item, cursor))
    }

    // ── shouldBroadcast ──────────────────────────────────────────────────────

    @Test
    fun `shouldBroadcast returns false for updated only`() {
        assertFalse(GitEventParser.shouldBroadcast("updated"))
        assertTrue(GitEventParser.shouldBroadcast("opened"))
        assertTrue(GitEventParser.shouldBroadcast("closed"))
        assertTrue(GitEventParser.shouldBroadcast("reopened"))
        assertTrue(GitEventParser.shouldBroadcast("merged"))
    }

    // ── pollDeliveryId ───────────────────────────────────────────────────────

    @Test
    fun `pollDeliveryId is stable and changes with updatedAt`() {
        val a = GitEventParser.pollDeliveryId("issues", 42, "2026-08-01T10:00:00Z")
        val b = GitEventParser.pollDeliveryId("issues", 42, "2026-08-01T10:00:00Z")
        val c = GitEventParser.pollDeliveryId("issues", 42, "2026-08-01T11:00:00Z")
        assertEquals(a, b)
        assertTrue(a != c)
    }

    // ── parseIssueList ───────────────────────────────────────────────────────

    @Test
    fun `parseIssueList skips items with invalid number`() {
        val items = listOf(issue(number = 0))
        val records = GitEventParser.parseIssueList(items, "room", ms("2026-08-01T10:00:00Z"))
        assertTrue(records.isEmpty())
    }

    @Test
    fun `parseIssueList assigns correct event type for PRs vs issues`() {
        val cursor = ms("2026-08-01T10:00:00Z")
        val items = listOf(
            issue(number = 1, state = "open", createdAt = "2026-08-01T11:00:00Z", updatedAt = "2026-08-01T11:00:00Z"),
            pr(number = 2, state = "open", createdAt = "2026-08-01T11:00:00Z", updatedAt = "2026-08-01T11:00:00Z"),
        )
        val records = GitEventParser.parseIssueList(items, "room", cursor)
        assertEquals(2, records.size)
        assertEquals("issues", records[0].event)
        assertEquals("pull_request", records[1].event)
        assertEquals("opened", records[0].action)
        assertEquals("opened", records[1].action)
    }

    @Test
    fun `parseIssueList handles malformed items gracefully`() {
        val cursor = ms("2026-08-01T10:00:00Z")
        val items = listOf(
            issue(number = 0), // invalid — skipped
            issue(number = 1, state = "open", createdAt = "2026-08-01T11:00:00Z", updatedAt = "2026-08-01T11:00:00Z"),
        )
        val records = GitEventParser.parseIssueList(items, "room", cursor)
        assertEquals(1, records.size)
        assertEquals(1, records[0].issueNumber)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun ms(iso: String): Long = java.time.Instant.parse(iso).toEpochMilli()

    private fun issue(
        number: Int = 1,
        state: String = "open",
        createdAt: String = "2026-07-01T10:00:00Z",
        updatedAt: String = "2026-08-01T10:00:00Z",
        closedAt: String? = null,
        stateReason: String? = null,
    ) = GitHubIssueListItem(
        number = number,
        title = "Test issue $number",
        html_url = "https://github.com/octo/demo/issues/$number",
        state = state,
        state_reason = stateReason,
        created_at = createdAt,
        updated_at = updatedAt,
        closed_at = closedAt,
        pull_request = null,
    )

    private fun pr(
        number: Int = 2,
        state: String = "open",
        createdAt: String = "2026-07-01T10:00:00Z",
        updatedAt: String = "2026-08-01T10:00:00Z",
        closedAt: String? = null,
        mergedAt: String? = null,
        stateReason: String? = null,
    ) = GitHubIssueListItem(
        number = number,
        title = "Test PR $number",
        html_url = "https://github.com/octo/demo/pull/$number",
        state = state,
        state_reason = stateReason,
        created_at = createdAt,
        updated_at = updatedAt,
        closed_at = closedAt,
        pull_request = GitHubPrRef(url = "https://api.github.com/repos/octo/demo/pulls/$number", merged_at = mergedAt),
    )
}
