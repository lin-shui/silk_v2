package com.silk.backend.git

import kotlinx.serialization.Serializable

/** The only provider supported by the Phase 3 MVP. */
@Serializable
enum class GitProvider { GITHUB }

@Serializable
data class RoomGitBinding(
    val roomId: String,
    val provider: GitProvider = GitProvider.GITHUB,
    val owner: String,
    val repo: String,
    val hookId: Long? = null,
    val webhookUrl: String,
    val tokenEncrypted: String,
    val webhookSecretEncrypted: String,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastDeliveryAt: Long? = null,
    val status: GitBindingStatus = GitBindingStatus.ACTIVE,
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
)

@Serializable
data class GitIntegrationStoreData(
    val bindings: List<RoomGitBinding> = emptyList(),
    val events: List<GitEventRecord> = emptyList(),
    /** Key is roomId + deliveryId, value is the first-seen epoch millis. */
    val processedDeliveryIds: Map<String, Long> = emptyMap(),
)

@Serializable
data class GitBindingDto(
    val enabled: Boolean,
    val provider: GitProvider? = null,
    val owner: String? = null,
    val repo: String? = null,
    val events: List<String> = listOf("issues", "pull_request", "check_run"),
    val lastDeliveryAt: Long? = null,
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

fun RoomGitBinding.toDto(): GitBindingDto = GitBindingDto(
    enabled = status == GitBindingStatus.ACTIVE,
    provider = provider,
    owner = owner,
    repo = repo,
    lastDeliveryAt = lastDeliveryAt,
    status = status,
)
