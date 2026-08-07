package com.silk.backend.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubRepositoryRefTest {
    @Test
    fun `normalizes canonical GitHub URLs`() {
        assertEquals("octo/demo", GitHubRepositoryRef.parse("https://github.com/octo/demo/")?.fullName)
        assertEquals("https://github.com/octo/demo", GitHubRepositoryRef.normalize("https://github.com/octo/demo.git"))
    }

    @Test
    fun `rejects hosts paths and URL components outside the contract`() {
        listOf(
            "http://github.com/octo/demo",
            "https://api.github.com/octo/demo",
            "https://github.com/octo/demo/issues",
            "https://github.com/octo/demo?x=1",
            "https://github.com/octo/demo#readme",
            "https://github.com//demo",
            "https://github.com/octo/",
            "https://github.com/octo/demo/extra",
        ).forEach { assertNull(GitHubRepositoryRef.parse(it), it) }
    }
}
