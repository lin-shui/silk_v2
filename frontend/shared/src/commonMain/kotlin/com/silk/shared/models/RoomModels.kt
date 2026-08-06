package com.silk.shared.models

import kotlinx.serialization.Serializable

@Serializable
enum class RoomKind {
    CHAT,
    WORKFLOW,
    SILK_PRIVATE,
}

@Serializable
data class RoomIntegrationSummaryDto(
    val type: String,
    val label: String = "",
    val agentType: String = "",
    val connected: Boolean = false,
)

@Serializable
data class RoomSummaryDto(
    val roomId: String,
    val roomKind: RoomKind,
    val name: String,
    val invitationCode: String = "",
    val ownerId: String,
    val ownerDisplayName: String,
    val role: String,
    val workflowId: String? = null,
    val unreadCount: Int = 0,
    val createdAt: String,
    val createdAtEpochMs: Long = 0L,
    val updatedAt: Long = 0L,
    val lastMessageAt: Long = 0L,
    val integration: RoomIntegrationSummaryDto? = null,
)

val RoomSummaryDto.latestActivityAt: Long
    get() = lastMessageAt.takeIf { it > 0L }
        ?: createdAtEpochMs.takeIf { it > 0L }
        ?: updatedAt

fun Iterable<RoomSummaryDto>.sortedByLatestActivity(): List<RoomSummaryDto> = sortedWith(
    compareByDescending<RoomSummaryDto> { it.latestActivityAt }
        .thenBy { it.name.lowercase() }
)

@Serializable
data class CreateRoomRequest(
    val name: String,
    val roomKind: RoomKind,
    val description: String = "",
    val integrationType: String? = null,
)

@Serializable
data class CreateRoomResponse(
    val room: RoomSummaryDto,
    val ccConnectToken: String? = null,
)

@Serializable
data class RenameRoomRequest(
    val name: String,
)

@Serializable
data class RoomActionResponse(
    val success: Boolean,
    val message: String,
    val room: RoomSummaryDto? = null,
)
