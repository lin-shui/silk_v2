package com.silk.backend.git

import kotlinx.serialization.Serializable

/** The only provider supported by the Phase 3 MVP. */
@Serializable
enum class GitProvider { GITHUB }

@Serializable
enum class GitIngestionMode { WEBHOOK, POLLING }

@Serializable
enum class GitEventSource { WEBHOOK, POLLING }

@Serializable
data class RoomGitBinding(
    val roomId: String,
    val provider: GitProvider = GitProvider.GITHUB,
    val owner: String,
    val repo: String,
    val hookId: Long? = null,
    val webhookUrl: String? = null,
    val tokenEncrypted: String,
    val webhookSecretEncrypted: String? = null,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastDeliveryAt: Long? = null,
    val status: GitBindingStatus = GitBindingStatus.ACTIVE,
    val ingestionMode: GitIngestionMode = GitIngestionMode.WEBHOOK,
)

@Serializable
enum class GitBindingStatus { ACTIVE, ERROR }

/** Persisted event fields are intentionally limited to card/context data. */
@Serializable
data class GitEventRecord(
    val deliveryId: String,
    val roomId: String,
    val event: String,
    val action: String,
    val repository: String,
    val issueNumber: Int? = null,
    val title: String = "",
    val htmlUrl: String = "",
    val summary: String = "",
    val createdAt: Long,
    val source: GitEventSource = GitEventSource.WEBHOOK,
    val dedupeKey: String = "",
    val delivered: Boolean = true,
)

@Serializable
data class GitPollingState(
    val cursor: String? = null,
    val etagByRequest: Map<String, String> = emptyMap(),
    val lastPollAt: Long? = null,
    val lastSuccessfulPollAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val nextPollAt: Long? = null,
    val rateLimitLimit: Int? = null,
    val rateLimitRemaining: Int? = null,
    val rateLimitResetAt: Long? = null,
    val pagesToPoll: Int = 1,
    val baselineCompleted: Boolean = false,
    val errorMessage: String? = null,
)

@Serializable
data class GitResourceSnapshot(
    val resourceKey: String,
    val kind: String,
    val number: Int,
    val state: String = "",
    val draft: Boolean? = null,
    val merged: Boolean? = null,
    val title: String = "",
    val labels: List<String> = emptyList(),
    val commentCount: Int = 0,
    val headSha: String? = null,
    val updatedAt: String = "",
    val createdAt: String = "",
    val htmlUrl: String = "",
)

@Serializable
data class GitIntegrationStoreData(
    val bindings: List<RoomGitBinding> = emptyList(),
    val events: List<GitEventRecord> = emptyList(),
    /** Keys are roomId + deliveryId or roomId + semantic dedupe key; values are first-seen epoch millis. */
    val processedDeliveryIds: Map<String, Long> = emptyMap(),
    val pollingStates: Map<String, GitPollingState> = emptyMap(),
    val resourceSnapshots: Map<String, List<GitResourceSnapshot>> = emptyMap(),
)

@Serializable
data class GitBindingDto(
    val enabled: Boolean,
    val provider: GitProvider? = null,
    val owner: String? = null,
    val repo: String? = null,
    val events: List<String> = listOf("issues", "pull_request"),
    val ingestionMode: GitIngestionMode? = null,
    val lastDeliveryAt: Long? = null,
    val lastSuccessfulPollAt: Long? = null,
    val syncError: String? = null,
    val status: GitBindingStatus? = null,
)

@Serializable
data class GitBindingRequest(
    val provider: GitProvider = GitProvider.GITHUB,
    val repositoryUrl: String,
    val token: String,
)

@Serializable
data class GitErrorResponse(
    val errorCode: String,
    val message: String,
)

data class ParsedGitEvent(
    val event: String,
    val action: String,
    val repository: String,
    val issueNumber: Int? = null,
    val title: String = "",
    val htmlUrl: String = "",
    val summary: String = "",
)

fun RoomGitBinding.toDto(pollingState: GitPollingState? = null): GitBindingDto = GitBindingDto(
    enabled = true,
    provider = provider,
    owner = owner,
    repo = repo,
    events = if (ingestionMode == GitIngestionMode.WEBHOOK) {
        listOf("issues", "pull_request", "check_run")
    } else {
        listOf("issues", "pull_request")
    },
    ingestionMode = ingestionMode,
    lastDeliveryAt = lastDeliveryAt,
    lastSuccessfulPollAt = pollingState?.lastSuccessfulPollAt,
    syncError = pollingState?.errorMessage,
    status = status,
)
