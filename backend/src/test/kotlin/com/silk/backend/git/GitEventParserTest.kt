package com.silk.backend.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitEventParserTest {
    @Test
    fun `parses supported issue pull request and check run actions`() {
        val issue = """
            {"action":"opened","repository":{"full_name":"octo/demo"},"issue":{"number":7,"title":"Bug","html_url":"https://github.com/octo/demo/issues/7","state":"open","labels":[{"name":"bug"}]},"sender":{"login":"alice"}}
        """.trimIndent().toByteArray()
        val parsed = GitEventParser.parse("issues", null, issue, "room", "delivery")!!
        assertEquals(7, parsed.issueNumber)
        assertEquals("Bug", parsed.title)
        assertEquals("octo/demo", parsed.repository)
        assertEquals("issues/opened", parsed.summary.substringBefore(" |"))

        val pr = """
            {"action":"ready_for_review","repository":{"full_name":"octo/demo"},"pull_request":{"number":8,"title":"PR","html_url":"https://github.com/octo/demo/pull/8","head":{"ref":"feature"},"base":{"ref":"main"}}}
        """.trimIndent().toByteArray()
        assertEquals(8, GitEventParser.parse("pull_request", null, pr)?.issueNumber)

        val check = """
            {"action":"completed","repository":{"full_name":"octo/demo"},"check_run":{"name":"build","head_sha":"abc","conclusion":"failure","html_url":"https://github.com/octo/demo/runs/1"}}
        """.trimIndent().toByteArray()
        assertEquals("check_run/completed", GitEventParser.parse("check_run", null, check)?.summary?.substringBefore(" |"))
    }

    @Test
    fun `ignores unsupported actions malformed payloads and oversized bodies`() {
        val body = "{" + "\"action\":\"synchronize\",\"repository\":{\"full_name\":\"octo/demo\"}}"
        assertNull(GitEventParser.parse("pull_request", null, body.toByteArray()))
        assertNull(GitEventParser.parse("push", "created", body.toByteArray()))
        assertNull(GitEventParser.parse("issues", "opened", "not-json".toByteArray()))
        assertNull(GitEventParser.parse("issues", "opened", ByteArray(GitEventParser.MAX_BODY_BYTES + 1)))
    }
}
