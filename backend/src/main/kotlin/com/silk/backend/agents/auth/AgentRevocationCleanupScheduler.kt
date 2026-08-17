package com.silk.backend.agents.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Periodically removes expired revoked rows while preserving audit/tombstone data. */
internal class AgentRevocationCleanupScheduler(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val cleanup: () -> AgentRevocationCleanupResult = {
        AgentAuthRepository.cleanupRevokedRecords()
    },
) {
    private val logger = LoggerFactory.getLogger(AgentRevocationCleanupScheduler::class.java)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            runOnce()
            while (isActive) {
                delay(intervalMs)
                runOnce()
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }

    private fun runOnce() {
        runCatching { cleanup() }
            .onSuccess { result ->
                if (result.deletedDevices > 0 || result.deletedAgents > 0 || result.deletedBindings > 0) {
                    logger.info(
                        "Purged revoked Agent history: devices={}, agents={}, bindings={}, retentionDays={}",
                        result.deletedDevices,
                        result.deletedAgents,
                        result.deletedBindings,
                        result.retentionDays,
                    )
                }
            }
            .onFailure { error -> logger.warn("Revoked Agent history cleanup failed: {}", error.message) }
    }

    private companion object {
        const val DEFAULT_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    }
}
