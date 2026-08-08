@file:Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")

package com.silk.backend.git

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import org.slf4j.LoggerFactory

data class GitPollOutcome(
    val polled: Boolean,
    val notModified: Boolean = false,
    val eventCount: Int = 0,
    val error: String? = null,
)

data class GitPollingBaseline(
    val state: GitPollingState,
    val snapshots: List<GitResourceSnapshot>,
)

/** Converts repository-level GitHub snapshots into the existing normalized event pipeline. */
class GitPollingService(
    private val store: GitEventStore,
    private val githubClient: GitHubClient,
    private val onEvent: suspend (GitEventRecord) -> Unit,
    private val encryptionKeyProvider: () -> ByteArray = { GitEncryption.configuredKey() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val intervalMs: Long = GitConfig.pollIntervalMs,
    private val overlapMs: Long = 60_000L,
    private val maxPagesPerPoll: Int = 20,
    private val jitter: (Long) -> Long = { base ->
        val spread = (base / 10).coerceAtLeast(1L)
        Random.nextLong(-spread, spread + 1)
    },
) {
    private val logger = LoggerFactory.getLogger(GitPollingService::class.java)
    private data class TokenRateBudget(val remaining: Int?, val resetAt: Long?)

    private val tokenRateBudgets = mutableMapOf<String, TokenRateBudget>()

    suspend fun reconcileConfiguredMode(binding: RoomGitBinding, selection: GitIngestionSelection): Boolean {
        if (binding.ingestionMode == selection.mode) {
            if (selection.mode != GitIngestionMode.WEBHOOK || selection.callbackBase == null) return true
            val expectedCallback = webhookCallback(selection.callbackBase, binding.roomId)
            if (binding.webhookUrl?.trimEnd('/') == expectedCallback) return true
            return migrateToWebhook(binding, selection.callbackBase)
        }
        return when (selection.mode) {
            GitIngestionMode.POLLING -> migrateToPolling(binding)
            GitIngestionMode.WEBHOOK -> selection.callbackBase?.let { migrateToWebhook(binding, it) } ?: false
        }
    }

    suspend fun prepareBaseline(ref: GitHubRepositoryRef, token: String): GitPollingBaseline {
        val startedAt = now()
        val baselineItems = mutableListOf<GitHubIssueListItem>()
        var pagesRead = 0
        var nextPage: Int? = 1
        var rateLimitLimit: Int? = null
        var rateLimitRemaining: Int? = null
        var rateLimitResetAt: Long? = null
        while (nextPage != null && pagesRead < maxPagesPerPoll.coerceAtLeast(1)) {
            val requestedPage = nextPage
            val response = githubClient.listIssues(ref, token, direction = "desc", page = requestedPage)
            baselineItems += response.items
            rememberTokenRateBudget(token, response.rateLimitRemaining, response.rateLimitResetAt)
            rateLimitLimit = response.rateLimitLimit ?: rateLimitLimit
            rateLimitRemaining = minNullable(rateLimitRemaining, response.rateLimitRemaining)
            rateLimitResetAt = maxNullable(rateLimitResetAt, response.rateLimitResetAt)
            pagesRead++
            nextPage = response.nextPage?.takeIf { it > requestedPage }
        }
        val baselineStart = Instant.ofEpochMilli(startedAt)
        val snapshots = baselineItems
            .filter { item -> parseInstant(item.updated_at)?.isBefore(baselineStart) == true }
            .map { it.toSnapshot() }
        val state = GitPollingState(
            cursor = instantString(startedAt),
            lastPollAt = startedAt,
            lastSuccessfulPollAt = startedAt,
            nextPollAt = nextRegularPoll(startedAt, rateLimitLimit, rateLimitRemaining),
            rateLimitLimit = rateLimitLimit,
            rateLimitRemaining = rateLimitRemaining,
            rateLimitResetAt = rateLimitResetAt,
            pagesToPoll = pagesRead.coerceAtLeast(1),
            baselineCompleted = true,
        )
        return GitPollingBaseline(state, snapshots)
    }

    suspend fun pollOnce(binding: RoomGitBinding, force: Boolean = false): GitPollOutcome {
        if (binding.ingestionMode != GitIngestionMode.POLLING) return GitPollOutcome(polled = false)
        val currentBinding = store.getBinding(binding.roomId)
            ?.takeIf { it.ingestionMode == GitIngestionMode.POLLING }
            ?: return GitPollOutcome(polled = false)
        val state = store.getPollingState(binding.roomId)
        val currentTime = now()
        if (!force && state.nextPollAt != null && state.nextPollAt > currentTime) {
            return GitPollOutcome(polled = false)
        }

        var tokenForFailure: String? = null
        return try {
            val key = encryptionKeyProvider()
            val token = GitEncryption.decrypt(currentBinding.tokenEncrypted, key)
            tokenForFailure = token
            if (!force && isTokenRateBlocked(token, currentTime)) return GitPollOutcome(polled = false)
            if (!state.baselineCompleted || state.cursor == null) {
                val baseline = prepareBaseline(GitHubRepositoryRef(binding.owner, binding.repo), token)
                if (store.putPollingBaselineIfCurrent(currentBinding, baseline.state, baseline.snapshots)) {
                    GitPollOutcome(polled = true)
                } else {
                    GitPollOutcome(polled = false)
                }
            } else {
                pollInitializedBinding(currentBinding, token, state, currentTime)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordFailure(currentBinding, state, currentTime, tokenForFailure, error)
        }
    }

    suspend fun drainPending(roomId: String? = null) {
        store.pendingEvents(roomId).forEach { event ->
            try {
                onEvent(event)
                store.markEventDelivered(event.roomId, event.deliveryId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@forEach
            }
        }
    }

    private suspend fun migrateToPolling(binding: RoomGitBinding): Boolean = try {
        val encryptionKey = encryptionKeyProvider()
        val token = GitEncryption.decrypt(binding.tokenEncrypted, encryptionKey)
        val ref = GitHubRepositoryRef(binding.owner, binding.repo)
        val baseline = prepareBaseline(ref, token)
        val migrated = binding.copy(
            hookId = null,
            webhookUrl = null,
            webhookSecretEncrypted = null,
            updatedAt = now(),
            status = GitBindingStatus.ACTIVE,
            ingestionMode = GitIngestionMode.POLLING,
        )
        check(store.putPollingBindingIfCurrent(binding, migrated, baseline.state, baseline.snapshots)) {
            "GitHub binding changed during migration"
        }
        binding.hookId?.let { hookId ->
            runCatching { githubClient.deleteHook(ref, hookId, token) }
                .onFailure { error ->
                    logger.warn("Unable to remove GitHub webhook while switching room {} to polling: {}", binding.roomId, error.message)
                }
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun migrateToWebhook(binding: RoomGitBinding, callbackBase: String): Boolean = try {
        val encryptionKey = encryptionKeyProvider()
        val token = GitEncryption.decrypt(binding.tokenEncrypted, encryptionKey)
        val ref = GitHubRepositoryRef(binding.owner, binding.repo)
        val previousHookId = binding.hookId
        val secretBytes = ByteArray(GitConfig.webhookSecretBytes).also(SecureRandom()::nextBytes)
        val secret = Base64.getEncoder().encodeToString(secretBytes)
        val callbackUrl = webhookCallback(callbackBase, binding.roomId)
        val hook = githubClient.reconcileHook(ref, token, callbackUrl, secret)
        var committed = false
        try {
            val catchUp = pollOnce(binding, force = true)
            check(catchUp.error == null) { catchUp.error.orEmpty() }
            val latestBinding = store.getBinding(binding.roomId) ?: binding
            val migrated = latestBinding.copy(
                hookId = hook.hook.id,
                webhookUrl = callbackUrl,
                webhookSecretEncrypted = GitEncryption.encrypt(secret, encryptionKey),
                updatedAt = now(),
                status = GitBindingStatus.ACTIVE,
                ingestionMode = GitIngestionMode.WEBHOOK,
            )
            check(store.putWebhookBindingIfCurrent(latestBinding, migrated)) { "GitHub binding changed during migration" }
            committed = true
            if (previousHookId != null && previousHookId != hook.hook.id) {
                runCatching { githubClient.deleteHook(ref, previousHookId, token) }
                    .onFailure { error ->
                        logger.warn("Unable to remove previous GitHub webhook while changing callback for room {}: {}", binding.roomId, error.message)
                    }
            }
        } finally {
            if (!committed && hook.created) runCatching { githubClient.deleteHook(ref, hook.hook.id, token) }
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun pollInitializedBinding(
        binding: RoomGitBinding,
        token: String,
        state: GitPollingState,
        currentTime: Long,
    ): GitPollOutcome {
        drainPending(binding.roomId)
        val ref = GitHubRepositoryRef(binding.owner, binding.repo)
        val since = overlapCursor(state.cursor ?: instantString(currentTime))
        val etags = state.etagByRequest.toMutableMap()
        val items = mutableListOf<GitHubIssueListItem>()
        var page = 1
        var targetPages = state.pagesToPoll.coerceIn(1, maxPagesPerPoll)
        var allNotModified = true
        var rateLimitLimit: Int? = null
        var rateLimitRemaining: Int? = null
        var rateLimitResetAt: Long? = null

        while (page <= targetPages && page <= maxPagesPerPoll) {
            val requestKey = requestKey(ref, since, page)
            val response = githubClient.listIssues(
                ref = ref,
                token = token,
                since = since,
                direction = "asc",
                page = page,
                etag = etags[requestKey],
            )
            rememberTokenRateBudget(token, response.rateLimitRemaining, response.rateLimitResetAt)
            allNotModified = allNotModified && response.notModified
            if (!response.notModified) items += response.items
            response.etag?.let { etags[requestKey] = it }
            rateLimitLimit = response.rateLimitLimit ?: rateLimitLimit
            rateLimitRemaining = minNullable(rateLimitRemaining, response.rateLimitRemaining)
            rateLimitResetAt = maxNullable(rateLimitResetAt, response.rateLimitResetAt)
            if (response.nextPage != null) targetPages = max(targetPages, min(response.nextPage, maxPagesPerPoll))
            page++
        }

        val oldSnapshots = store.getResourceSnapshots(binding.roomId).associateBy { it.resourceKey }
        val mergedSnapshots = oldSnapshots.toMutableMap()
        val generatedEvents = mutableListOf<GitEventRecord>()
        val uniqueItems = items
            .filter { it.number > 0 && it.updated_at.isNotBlank() }
            .associateBy { resourceKey(it) }
            .values
            .sortedBy { it.updated_at }

        for (item in uniqueItems) {
            val key = resourceKey(item)
            val previous = oldSnapshots[key]
            val current = enrichSnapshot(ref, token, item, previous)
            mergedSnapshots[key] = current
            val action = inferAction(previous, current, state.cursor) ?: continue
            generatedEvents += current.toEvent(binding, item, action, currentTime)
        }

        val nextCursor = newestCursor(state.cursor, uniqueItems.map { it.updated_at })
        val cursorChanged = nextCursor != state.cursor
        val effectiveRateLimit = rateLimitLimit ?: state.rateLimitLimit
        val effectiveRemaining = rateLimitRemaining ?: state.rateLimitRemaining
        val effectiveResetAt = rateLimitResetAt ?: state.rateLimitResetAt
        val nextState = state.copy(
            cursor = nextCursor,
            etagByRequest = etags.entries.toList().takeLast(MAX_ETAGS).associate { it.key to it.value },
            lastPollAt = currentTime,
            lastSuccessfulPollAt = currentTime,
            consecutiveFailures = 0,
            nextPollAt = nextRegularPoll(currentTime, effectiveRateLimit, effectiveRemaining),
            rateLimitLimit = effectiveRateLimit,
            rateLimitRemaining = effectiveRemaining,
            rateLimitResetAt = effectiveResetAt,
            pagesToPoll = if (cursorChanged) 1 else targetPages,
            errorMessage = null,
        )
        val snapshots = mergedSnapshots.values.sortedByDescending { it.updatedAt }.take(MAX_SNAPSHOTS)
        val accepted = store.recordPollingBatch(binding, nextState, snapshots, generatedEvents)
            ?: return GitPollOutcome(polled = false)
        drainPending(binding.roomId)
        return GitPollOutcome(polled = true, notModified = allNotModified, eventCount = accepted.size)
    }

    private suspend fun enrichSnapshot(
        ref: GitHubRepositoryRef,
        token: String,
        item: GitHubIssueListItem,
        previous: GitResourceSnapshot?,
    ): GitResourceSnapshot {
        if (item.pull_request == null || previous?.updatedAt == item.updated_at) return item.toSnapshot()
        return try {
            githubClient.getPullRequest(ref, item.number, token).toSnapshot()
        } catch (error: GitHubApiException) {
            if (error.status == HttpStatusCode.Forbidden || error.status == HttpStatusCode.NotFound) item.toSnapshot() else throw error
        }
    }

    private fun recordFailure(
        binding: RoomGitBinding,
        state: GitPollingState,
        currentTime: Long,
        token: String?,
        error: Exception,
    ): GitPollOutcome {
        val failures = state.consecutiveFailures + 1
        val apiError = error as? GitHubApiException
        rememberTokenRateBudget(token, apiError?.rateLimitRemaining, apiError?.rateLimitResetAt)
        val message = when {
            error is GitEncryption.ConfigurationException -> "GitHub 加密密钥不可用"
            apiError?.status == HttpStatusCode.Unauthorized -> "GitHub PAT 已失效"
            apiError?.status == HttpStatusCode.Forbidden && apiError.rateLimitRemaining == 0 -> "GitHub API 配额已用尽"
            apiError?.status == HttpStatusCode.Forbidden -> "GitHub PAT 权限不足"
            apiError?.status == HttpStatusCode.TooManyRequests -> "GitHub API 请求受限"
            else -> "GitHub 同步暂时失败"
        }
        val exponential = min(MAX_BACKOFF_MS, 60_000L * (1L shl min(failures - 1, 8)))
        val nextAttempt = listOfNotNull(
            currentTime + exponential,
            apiError?.retryAfterMs?.let { currentTime + it },
            apiError?.rateLimitResetAt,
        ).maxOrNull() ?: currentTime + exponential
        val nextStatus = if (failures >= ERROR_THRESHOLD || apiError?.status == HttpStatusCode.Unauthorized) {
            GitBindingStatus.ERROR
        } else {
            binding.status
        }
        store.recordPollingFailure(
            binding,
            state.copy(
                lastPollAt = currentTime,
                consecutiveFailures = failures,
                nextPollAt = nextAttempt,
                rateLimitRemaining = apiError?.rateLimitRemaining ?: state.rateLimitRemaining,
                rateLimitResetAt = apiError?.rateLimitResetAt ?: state.rateLimitResetAt,
                errorMessage = message,
            ),
            nextStatus,
        )
        return GitPollOutcome(polled = true, error = message)
    }

    private fun inferAction(
        previous: GitResourceSnapshot?,
        current: GitResourceSnapshot,
        baselineCursor: String?,
    ): String? {
        if (previous == null) {
            val created = parseInstant(current.createdAt)
            val baseline = parseInstant(baselineCursor)
            return if (created != null && baseline != null && !created.isBefore(baseline)) "opened" else "updated"
        }
        if (previous.state != current.state) {
            return when {
                previous.state == "open" && current.state == "closed" -> "closed"
                previous.state == "closed" && current.state == "open" -> "reopened"
                else -> "updated"
            }
        }
        if (previous.draft == true && current.draft == false) return "ready_for_review"
        return if (previous.meaningfulFields() != current.meaningfulFields()) "updated" else null
    }

    private fun GitResourceSnapshot.toEvent(
        binding: RoomGitBinding,
        item: GitHubIssueListItem,
        action: String,
        timestamp: Long,
    ): GitEventRecord {
        val eventName = if (kind == "pull_request") "pull_request" else "issues"
        val semanticKey = "${binding.owner}/${binding.repo}:$kind:$number:$action:$updatedAt"
        val summaryText = buildString {
            append(eventName).append('/').append(action)
            if (title.isNotBlank()) append(" | ").append(title)
            if (item.user.login.isNotBlank()) append(" | by ").append(item.user.login)
            if (state.isNotBlank()) append(" | state=").append(state)
            if (merged == true) append(" | merged=true")
            if (labels.isNotEmpty()) append(" | labels=").append(labels.joinToString(", "))
        }.take(MAX_EVENT_FIELD)
        return GitEventRecord(
            deliveryId = "poll_${stableHash(semanticKey)}",
            roomId = binding.roomId,
            event = eventName,
            action = action,
            repository = "${binding.owner}/${binding.repo}",
            issueNumber = number,
            title = title.take(MAX_EVENT_FIELD),
            htmlUrl = htmlUrl.take(MAX_EVENT_FIELD),
            summary = summaryText,
            createdAt = timestamp,
            source = GitEventSource.POLLING,
            dedupeKey = semanticKey,
            delivered = false,
        )
    }

    private fun GitHubIssueListItem.toSnapshot(): GitResourceSnapshot = GitResourceSnapshot(
        resourceKey = resourceKey(this),
        kind = if (pull_request == null) "issues" else "pull_request",
        number = number,
        state = state,
        draft = draft,
        title = title.take(MAX_EVENT_FIELD),
        labels = labels.map { it.name }.sorted(),
        commentCount = comments,
        updatedAt = updated_at,
        createdAt = created_at,
        htmlUrl = html_url,
    )

    private fun GitHubPullRequestResponse.toSnapshot(): GitResourceSnapshot = GitResourceSnapshot(
        resourceKey = "pull_request:$number",
        kind = "pull_request",
        number = number,
        state = state,
        draft = draft,
        merged = merged_at != null,
        title = title.take(MAX_EVENT_FIELD),
        labels = labels.map { it.name }.sorted(),
        commentCount = comments,
        headSha = head.sha,
        updatedAt = updated_at,
        createdAt = created_at,
        htmlUrl = html_url,
    )

    private fun GitResourceSnapshot.meaningfulFields(): List<Any?> = listOf(
        state,
        draft,
        merged,
        title,
        labels,
        commentCount,
        headSha,
        htmlUrl,
    )

    private fun resourceKey(item: GitHubIssueListItem): String =
        if (item.pull_request == null) "issues:${item.number}" else "pull_request:${item.number}"

    private fun requestKey(ref: GitHubRepositoryRef, since: String, page: Int): String =
        "${ref.fullName}|$since|asc|$page"

    private fun webhookCallback(callbackBase: String, roomId: String): String =
        callbackBase.trimEnd('/') + "/api/git/webhook/$roomId"

    private fun overlapCursor(cursor: String): String {
        val instant = parseInstant(cursor) ?: Instant.ofEpochMilli(now())
        return instant.minusMillis(overlapMs).truncatedTo(ChronoUnit.SECONDS).toString()
    }

    private fun newestCursor(current: String?, candidates: List<String>): String? =
        (listOfNotNull(current) + candidates)
            .mapNotNull(::parseInstant)
            .maxOrNull()
            ?.truncatedTo(ChronoUnit.SECONDS)
            ?.toString()

    private fun nextRegularPoll(currentTime: Long, limit: Int?, remaining: Int?): Long {
        val base = if (limit != null && remaining != null && remaining * 5 < limit) {
            max(intervalMs, LOW_QUOTA_INTERVAL_MS)
        } else {
            intervalMs
        }
        return currentTime + base + jitter(base)
    }

    private fun instantString(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun parseInstant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun minNullable(left: Int?, right: Int?): Int? = when {
        left == null -> right
        right == null -> left
        else -> min(left, right)
    }

    private fun maxNullable(left: Long?, right: Long?): Long? = when {
        left == null -> right
        right == null -> left
        else -> max(left, right)
    }

    private fun stableHash(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
        .take(24)

    private fun rememberTokenRateBudget(token: String?, remaining: Int?, resetAt: Long?) {
        if (token == null || (remaining == null && resetAt == null)) return
        synchronized(tokenRateBudgets) {
            val key = stableHash("token:$token")
            val previous = tokenRateBudgets[key]
            tokenRateBudgets[key] = TokenRateBudget(
                remaining = remaining ?: previous?.remaining,
                resetAt = resetAt ?: previous?.resetAt,
            )
        }
    }

    private fun isTokenRateBlocked(token: String, currentTime: Long): Boolean = synchronized(tokenRateBudgets) {
        val key = stableHash("token:$token")
        val budget = tokenRateBudgets[key] ?: return@synchronized false
        if (budget.resetAt != null && budget.resetAt <= currentTime) {
            tokenRateBudgets.remove(key)
            return@synchronized false
        }
        budget.remaining == 0
    }

    private companion object {
        const val MAX_ETAGS = 100
        const val MAX_SNAPSHOTS = 5_000
        const val MAX_EVENT_FIELD = 500
        const val ERROR_THRESHOLD = 3
        const val LOW_QUOTA_INTERVAL_MS = 5 * 60_000L
        const val MAX_BACKOFF_MS = 30 * 60_000L
    }
}
