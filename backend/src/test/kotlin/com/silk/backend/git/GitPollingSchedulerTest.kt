package com.silk.backend.git

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitPollingSchedulerTest {
    @Test
    fun `scheduler owns process lease until stopped`() = runTest {
        val root = Files.createTempDirectory("git-polling-scheduler").toFile()
        val store = GitEventStore(root.absolutePath)
        val client = GitHubClient(HttpClient(MockEngine { error("No GitHub request expected") }))
        val service = GitPollingService(store, client, onEvent = {})
        val scheduler = GitPollingScheduler(store, service, scanIntervalMs = 60_000)

        try {
            scheduler.start()
            assertFalse(canAcquire(store), "scheduler must hold the polling lease")
            scheduler.stop()
            assertTrue(canAcquire(store), "scheduler must release the polling lease on stop")
        } finally {
            scheduler.close()
            client.close()
            root.deleteRecursively()
        }
    }

    private fun canAcquire(store: GitEventStore): Boolean {
        val channel = FileChannel.open(
            store.pollingLeasePath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        return try {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            lock?.release()
            lock != null
        } finally {
            channel.close()
        }
    }
}
