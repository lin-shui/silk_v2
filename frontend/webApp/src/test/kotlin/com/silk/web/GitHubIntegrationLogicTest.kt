package com.silk.web

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
}
