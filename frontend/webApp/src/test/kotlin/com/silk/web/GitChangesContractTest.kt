package com.silk.web

import com.silk.shared.models.GitChangesResponse
import com.silk.shared.models.GitFileDiffResponse
import com.silk.shared.models.GitFileStatus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitChangesContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesBackendChangesJson() {
        val payload = """
            {"success":true,"connected":true,"supported":true,"isGitRepo":true,"cwd":"/repo",
             "files":[{"path":"Main.kt","oldPath":null,"status":"modified","additions":12,"deletions":4,"binary":false}]}
        """.trimIndent()
        val resp = json.decodeFromString(GitChangesResponse.serializer(), payload)
        assertTrue(resp.success)
        assertEquals(1, resp.files.size)
        assertEquals("modified", resp.files[0].status)
        assertEquals(12, resp.files[0].additions)
        assertNull(resp.reason)
    }

    @Test
    fun decodesCcConnectReason() {
        val payload = """{"success":false,"connected":false,"reason":"ccconnect"}"""
        val resp = json.decodeFromString(GitChangesResponse.serializer(), payload)
        assertFalse(resp.connected)
        assertEquals("ccconnect", resp.reason)
    }

    @Test
    fun decodesDiffJson() {
        val payload =
            """{"success":true,"isGitRepo":true,"filePath":"a.kt","patch":"@@ -1 +1 @@\n-x\n+y\n","isBinary":false,"truncated":false}"""
        val resp = json.decodeFromString(GitFileDiffResponse.serializer(), payload)
        assertTrue(resp.success)
        assertEquals("a.kt", resp.filePath)
        assertTrue(resp.patch.contains("+y"))
        assertFalse(resp.isBinary)
    }

    @Test
    fun mapsWireStatusToEnum() {
        assertEquals(GitFileStatus.UNTRACKED, GitFileStatus.fromWire("untracked"))
        assertEquals(GitFileStatus.RENAMED, GitFileStatus.fromWire("renamed"))
        assertEquals(GitFileStatus.UNKNOWN, GitFileStatus.fromWire("bogus"))
    }
}
