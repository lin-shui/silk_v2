package com.silk.backend.agents.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitChangesAssemblerTest {
    private val sampleStatus = """
        {"success":true,"isGitRepo":true,"cwd":"/repo",
         "files":[
           {"path":"Main.kt","oldPath":null,"status":"modified","additions":12,"deletions":4,"binary":false},
           {"path":"new.kt","oldPath":"old.kt","status":"renamed","additions":0,"deletions":0,"binary":false},
           {"path":"img.png","oldPath":null,"status":"added","additions":0,"deletions":0,"binary":true}
         ]}
    """.trimIndent()

    @Test
    fun decodesBridgeStatusIntoResponse() {
        val raw = Json.parseToJsonElement(sampleStatus)
        val resp = GitChangesAssembler.assembleChanges(connected = true, supported = true, raw = raw)
        assertTrue(resp.success)
        assertTrue(resp.connected)
        assertTrue(resp.isGitRepo)
        assertEquals("/repo", resp.cwd)
        assertEquals(3, resp.files.size)
        assertEquals("Main.kt", resp.files[0].path)
        assertEquals("modified", resp.files[0].status)
        assertEquals(12, resp.files[0].additions)
        assertEquals("old.kt", resp.files[1].oldPath)
        assertTrue(resp.files[2].binary)
        assertNull(resp.reason)
    }

    @Test
    fun notConnectedShortCircuits() {
        val resp = GitChangesAssembler.assembleChanges(connected = false, supported = true, raw = null)
        assertFalse(resp.success)
        assertFalse(resp.connected)
        assertTrue(resp.files.isEmpty())
    }

    @Test
    fun unsupportedShortCircuits() {
        val resp = GitChangesAssembler.assembleChanges(connected = true, supported = false, raw = null)
        assertFalse(resp.success)
        assertFalse(resp.supported)
    }

    @Test
    fun diffNotConnectedShortCircuits() {
        val resp = GitChangesAssembler.assembleDiff(connected = false, supported = true, raw = null)
        assertFalse(resp.success)
        assertFalse(resp.connected)
    }
}
