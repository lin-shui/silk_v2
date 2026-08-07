package com.silk.backend.git

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitEventStoreTest {
    @Test
    fun `missing store is disabled and binding survives restart`() {
        val dir = Files.createTempDirectory("git-store").toFile()
        try {
            assertFalse(GitEventStore(dir.absolutePath).getBinding("room") != null)
            val binding = RoomGitBinding("room", owner = "octo", repo = "demo", webhookUrl = "https://silk.test/api/git/webhook/room", tokenEncrypted = "v1:token", webhookSecretEncrypted = "v1:secret", createdBy = "u1", createdAt = 1, updatedAt = 1)
            GitEventStore(dir.absolutePath).putBinding(binding)
            assertEquals(binding, GitEventStore(dir.absolutePath).getBinding("room"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `delivery is idempotent and event is stored in one write`() {
        val dir = Files.createTempDirectory("git-store").toFile()
        try {
            val store = GitEventStore(dir.absolutePath)
            val event = GitEventRecord("d1", "room", "issues", "opened", "octo/demo", 1, "Title", "https://github.com/octo/demo/issues/1", "summary", 100)
            assertTrue(store.recordDelivery("room", "d1", event))
            assertFalse(store.recordDelivery("room", "d1", event))
            assertEquals(1, store.listEvents("room").size)
            assertTrue(store.hasProcessedDelivery("room", "d1"))
            store.removeBinding("room")
            assertEquals(0, store.listEvents("room").size)
            assertFalse(store.hasProcessedDelivery("room", "d1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `delivery IDs expire and are bounded`() {
        val dir = Files.createTempDirectory("git-store").toFile()
        try {
            var time = 100_000L
            val store = GitEventStore(dir.absolutePath, now = { time }, deliveryTtlMs = 10, maxDeliveryIds = 2)
            assertTrue(store.recordDelivery("room", "old"))
            time = 105_000L
            assertTrue(store.recordDelivery("room", "new-1"))
            assertTrue(store.recordDelivery("room", "new-2"))
            assertFalse(store.hasProcessedDelivery("room", "old"))
            assertTrue(store.hasProcessedDelivery("room", "new-1"))
            assertTrue(store.hasProcessedDelivery("room", "new-2"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
