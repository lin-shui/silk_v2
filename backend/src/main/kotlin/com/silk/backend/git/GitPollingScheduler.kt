package com.silk.backend.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Background scheduler that polls GitHub for Issue/PR updates on behalf of
 * POLLING-mode bindings.  All requests are serial to avoid secondary rate
 * limits.  Each binding's failure is isolated — one bad PAT does not stop
 * polling for other rooms.
 */
class GitPollingScheduler(
    private val store: GitEventStore,
    private val client: GitHubClient = GitHubClient(),
    private val encryptionKeyProvider: () -> ByteArray = { GitEncryption.configuredKey() },
    private val onEvent: suspend (GitEventRecord) -> Unit = {},
    private val intervalProvider: () -> Long = { GitConfig.pollIntervalSeconds.toLong() * 1_000 },
) {
    private val logger = LoggerFactory.getLogger(GitPollingScheduler::class.java)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        val key = runCatching { encryptionKeyProvider() }.getOrElse {
            logger.warn("SILK_ENCRYPTION_KEY not configured — GitHub polling will not start")
            return
        }
        logger.info("GitHub polling scheduler started (interval={}s)", GitConfig.pollIntervalSeconds)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { pollAllBindings(key) }
                    .onFailure { logger.warn("Polling cycle error: {}", it.message) }
                delay(intervalProvider())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun pollAllBindings(key: ByteArray) {
        val bindings = store.listPollableBindings()
        for (binding in bindings) {
            runCatching { pollBinding(binding, key) }
                .onFailure { logger.warn("Polling failed for room {}: {}", binding.roomId, it.message) }
            // Small gap between bindings guards against secondary rate limits
            delay(INTER_BINDING_DELAY_MS)
        }
    }

    private suspend fun pollBinding(binding: RoomGitBinding, key: ByteArray) {
        val token = runCatching { GitEncryption.decrypt(binding.tokenEncrypted, key) }.getOrElse {
            logger.warn("Cannot decrypt PAT for room {} — skipping", binding.roomId)
            return
        }
        val ref = GitHubRepositoryRef(binding.owner, binding.repo)
        val cursor = binding.pollCursor ?: System.currentTimeMillis()

        val result = try {
            client.listIssuesAndPulls(ref, token, cursor, binding.issuesEtag)
        } catch (e: GitHubApiException) {
            if (e.status.value == 401 || e.status.value == 403) {
                logger.warn("PAT for room {} is invalid ({}) — marking ERROR", binding.roomId, e.status.value)
                store.updateBindingStatus(binding.roomId, GitBindingStatus.ERROR)
            } else {
                logger.warn("GitHub API error for room {}: {}", binding.roomId, e.message)
            }
            return
        }

        when (result) {
            is ConditionalGetResult.NotModified -> {
                store.updatePollState(binding.roomId, cursor = null, etag = null, polledAt = System.currentTimeMillis())
            }
            is ConditionalGetResult.Modified -> {
                val records = GitEventParser.parseIssueList(result.items, binding.roomId, cursor)
                for (record in records) {
                    val isNew = store.recordDelivery(binding.roomId, record.deliveryId, record)
                    if (isNew && GitEventParser.shouldBroadcast(record.action)) {
                        runCatching { onEvent(record) }
                            .onFailure { logger.warn("Event broadcast failed for room {}: {}", binding.roomId, it.message) }
                    }
                }
                // Cursor = max updated_at across the whole batch, not per-record
                val maxUpdated = result.items.mapNotNull { item ->
                    runCatching { java.time.Instant.parse(item.updated_at).toEpochMilli() }.getOrNull()
                }.maxOrNull()
                store.updatePollState(
                    roomId = binding.roomId,
                    cursor = maxUpdated?.takeIf { it > cursor },
                    etag = result.etag,
                    polledAt = System.currentTimeMillis(),
                )
            }
        }
    }

    private companion object {
        const val INTER_BINDING_DELAY_MS = 200L
    }
}
