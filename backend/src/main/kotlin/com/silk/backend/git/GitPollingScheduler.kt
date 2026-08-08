package com.silk.backend.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/** Serialized polling loop guarded by an OS file lease for the Workflow storage directory. */
class GitPollingScheduler(
    private val store: GitEventStore,
    private val service: GitPollingService,
    private val scanIntervalMs: Long = 5_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val logger = LoggerFactory.getLogger(GitPollingScheduler::class.java)
    private var job: Job? = null
    private var leaseChannel: FileChannel? = null
    private var lease: FileLock? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        if (!acquireLease()) {
            logger.warn("GitHub polling is already active for storage directory {}", store.pollingLeasePath().parent)
            return
        }
        job = scope.launch {
            try {
                service.drainPending()
                val selection = GitIngestionModeResolver.resolve()
                selection.warning?.let { logger.warn("GitHub ingestion mode: {}", it) }
                store.listBindings().forEach { binding ->
                    val migrated = service.reconcileConfiguredMode(binding, selection)
                    if (!migrated) {
                        logger.warn(
                            "Unable to migrate GitHub ingestion mode for room {} from {} to {}",
                            binding.roomId,
                            binding.ingestionMode,
                            selection.mode,
                        )
                    }
                }
                while (isActive) {
                    store.listBindings()
                        .filter { it.ingestionMode == GitIngestionMode.POLLING }
                        .forEach { binding ->
                            runCatching { service.pollOnce(binding) }
                                .onFailure { logger.warn("GitHub polling failed for room {}: {}", binding.roomId, it.message) }
                        }
                    delay(scanIntervalMs)
                }
            } finally {
                releaseLease()
            }
        }
    }

    suspend fun stop() {
        val running = synchronized(this) { job.also { job = null } }
        running?.cancelAndJoin()
        releaseLease()
    }

    override fun close() {
        runBlocking { stop() }
    }

    @Synchronized
    private fun acquireLease(): Boolean {
        val channel = FileChannel.open(
            store.pollingLeasePath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val acquired = runCatching { channel.tryLock() }.getOrNull()
        if (acquired == null) {
            channel.close()
            return false
        }
        leaseChannel = channel
        lease = acquired
        return true
    }

    @Synchronized
    private fun releaseLease() {
        runCatching { lease?.release() }
        runCatching { leaseChannel?.close() }
        lease = null
        leaseChannel = null
    }
}
