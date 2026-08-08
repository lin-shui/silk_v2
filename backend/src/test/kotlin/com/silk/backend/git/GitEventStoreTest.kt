package com.silk.backend.git

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `semantic key deduplicates polling and webhook events in both directions`() {
        val dir = Files.createTempDirectory("git-store-cross-source").toFile()
        try {
            val store = GitEventStore(dir.absolutePath)
            val binding = RoomGitBinding(
                roomId = "room",
                owner = "octo",
                repo = "demo",
                tokenEncrypted = "v1:token",
                createdBy = "u1",
                createdAt = 1,
                updatedAt = 1,
                ingestionMode = GitIngestionMode.POLLING,
            )
            store.putPollingBinding(binding, GitPollingState(baselineCompleted = true), emptyList())
            val fromPolling = GitEventRecord(
                "poll-1", "room", "issues", "closed", "octo/demo", 1,
                createdAt = 100, source = GitEventSource.POLLING, dedupeKey = "shared-1", delivered = false,
            )
            assertEquals(1, store.recordPollingBatch(binding, GitPollingState(), emptyList(), listOf(fromPolling))?.size)
            assertFalse(store.recordDelivery("room", "webhook-1", fromPolling.copy(deliveryId = "webhook-1")))

            val fromWebhook = fromPolling.copy(deliveryId = "webhook-2", dedupeKey = "shared-2")
            assertTrue(store.recordDelivery("room", "webhook-2", fromWebhook))
            assertTrue(
                store.recordPollingBatch(
                    store.getBinding("room")!!,
                    GitPollingState(),
                    emptyList(),
                    listOf(fromWebhook.copy(deliveryId = "poll-2")),
                )?.isEmpty() == true
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `pending polling events are not evicted by the history limit`() {
        val dir = Files.createTempDirectory("git-store-pending-limit").toFile()
        try {
            val store = GitEventStore(dir.absolutePath)
            val binding = RoomGitBinding(
                roomId = "room",
                owner = "octo",
                repo = "demo",
                tokenEncrypted = "v1:token",
                createdBy = "u1",
                createdAt = 1,
                updatedAt = 1,
                ingestionMode = GitIngestionMode.POLLING,
            )
            store.putPollingBinding(binding, GitPollingState(baselineCompleted = true), emptyList())
            val events = (1..1_100).map { number ->
                GitEventRecord(
                    deliveryId = "poll-$number",
                    roomId = "room",
                    event = "issues",
                    action = "updated",
                    repository = "octo/demo",
                    issueNumber = number,
                    createdAt = number.toLong(),
                    source = GitEventSource.POLLING,
                    dedupeKey = "event-$number",
                    delivered = false,
                )
            }

            assertEquals(1_100, store.recordPollingBatch(binding, GitPollingState(), emptyList(), events)?.size)
            assertEquals(1_100, store.pendingEvents("room").size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stale polling request cannot overwrite a replacement binding`() {
        val dir = Files.createTempDirectory("git-store-stale-poll").toFile()
        try {
            val store = GitEventStore(dir.absolutePath)
            val original = RoomGitBinding(
                roomId = "room",
                owner = "octo",
                repo = "old",
                tokenEncrypted = "v1:old-token",
                createdBy = "u1",
                createdAt = 1,
                updatedAt = 1,
                ingestionMode = GitIngestionMode.POLLING,
            )
            store.putPollingBinding(original, GitPollingState(baselineCompleted = true), emptyList())
            store.putBinding(original.copy(repo = "new", tokenEncrypted = "v1:new-token", updatedAt = 2))

            assertNull(store.recordPollingBatch(original, GitPollingState(cursor = "stale"), emptyList(), emptyList()))
            assertFalse(
                store.recordPollingFailure(
                    original,
                    GitPollingState(errorMessage = "stale"),
                    GitBindingStatus.ERROR,
                )
            )
            assertEquals("new", store.getBinding("room")?.repo)
            assertEquals(null, store.getPollingState("room").errorMessage)

            val polling = original.copy(repo = "demo", updatedAt = 3)
            store.putPollingBinding(polling, GitPollingState(baselineCompleted = true), emptyList())
            store.putBinding(polling.copy(hookId = 9, webhookUrl = "https://silk.test/hook", updatedAt = 3))
            assertNull(store.recordPollingBatch(polling, GitPollingState(cursor = "stale"), emptyList(), emptyList()))
        } finally {
            dir.deleteRecursively()
        }
    }
}
