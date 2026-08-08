package com.silk.web

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubIntegrationLogicTest {
    @Test
    fun parsesIssueActionOnlyForTheCurrentRoom() {
        assertEquals(
            42,
            parseGithubIssueAction("github:issue-to-workspace:room-1:42", "room-1"),
        )
        assertNull(parseGithubIssueAction("github:issue-to-workspace:room-2:42", "room-1"))
        assertNull(parseGithubIssueAction("github:issue-to-workspace:room-1:0", "room-1"))
    }

    @Test
    fun rejectsMalformedOrUnrelatedCardActions() {
        assertNull(parseGithubIssueAction("github:url:https://github.com/a/b/issues/1", "room-1"))
        assertNull(parseGithubIssueAction("github:issue-to-workspace:room-1", "room-1"))
        assertNull(parseGithubIssueAction("github:issue-to-workspace::42", "room-1"))
    }

    @Test
    fun parsesPollingBindingStatusAndKeepsOldResponsesCompatible() {
        val json = Json { ignoreUnknownKeys = true }
        val polling = json.decodeFromString<GitBindingSummary>(
            """{"enabled":true,"owner":"octo","repo":"demo","events":["issues","pull_request"],"ingestionMode":"POLLING","lastSuccessfulPollAt":1786147200000,"syncError":"GitHub 同步暂时失败","status":"ERROR"}"""
        )
        assertEquals("POLLING", polling.ingestionMode)
        assertEquals(1_786_147_200_000L, polling.lastSuccessfulPollAt)
        assertEquals("GitHub 同步暂时失败", polling.syncError)

        val legacy = json.decodeFromString<GitBindingSummary>("""{"enabled":true,"owner":"octo","repo":"demo"}""")
        assertNull(legacy.ingestionMode)
        assertNull(legacy.lastSuccessfulPollAt)
    }
}
