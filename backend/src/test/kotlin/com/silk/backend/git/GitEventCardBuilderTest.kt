package com.silk.backend.git

import kotlin.test.Test
import kotlin.test.assertTrue

class GitEventCardBuilderTest {
    @Test
    fun `issue card includes link and replayable issue action without credentials`() {
        val content = GitEventCardBuilder.build(
            GitEventRecord(
                deliveryId = "delivery",
                roomId = "room",
                event = "issues",
                action = "opened",
                repository = "octo/demo",
                issueNumber = 7,
                title = "Fix login",
                htmlUrl = "https://github.com/octo/demo/issues/7",
                summary = "issues/opened | by alice",
                createdAt = 1,
            )
        )
        assertTrue(content.contains("github:issue-to-workspace:room:7"))
        assertTrue(content.contains("https://github.com/octo/demo/issues/7"))
        assertTrue(!content.contains("token", ignoreCase = true))
    }

    @Test
    fun `summary card keeps the original event link and bounded summary`() {
        val event = GitEventRecord(
            deliveryId = "delivery",
            roomId = "room",
            event = "pull_request",
            action = "opened",
            repository = "octo/demo",
            issueNumber = 8,
            title = "Improve login",
            htmlUrl = "https://github.com/octo/demo/pull/8",
            summary = "pull_request/opened",
            createdAt = 1,
        )
        val content = GitEventCardBuilder.buildSummary(event, "建议先补充回归测试")
        assertTrue(content.contains("GitHub AI"))
        assertTrue(content.contains("建议先补充回归测试"))
        assertTrue(content.contains("https://github.com/octo/demo/pull/8"))
    }
}
