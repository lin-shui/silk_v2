package com.silk.shared

import com.silk.shared.models.RoomKind
import com.silk.shared.models.RoomSummaryDto
import com.silk.shared.models.sortedByLatestActivity
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomModelsTest {
    @Test
    fun newEmptyRoomUsesCreationTimeAsLatestActivity() {
        val rooms = listOf(
            room("new-empty", "Empty", createdAtEpochMs = 400L),
            room("older-message", "Older", lastMessageAt = 100L),
            room("latest-message", "Latest", lastMessageAt = 300L),
            room("middle-message", "Middle", lastMessageAt = 200L),
        )

        assertEquals(
            listOf("new-empty", "latest-message", "middle-message", "older-message"),
            rooms.sortedByLatestActivity().map { it.roomId },
        )
    }

    @Test
    fun metadataUpdatesDoNotOverrideMessageRecency() {
        val rooms = listOf(
            room("renamed-older", "Renamed", updatedAt = 1_000L, lastMessageAt = 100L),
            room("latest", "Latest", updatedAt = 200L, lastMessageAt = 200L),
        )

        assertEquals(listOf("latest", "renamed-older"), rooms.sortedByLatestActivity().map { it.roomId })
    }

    private fun room(
        id: String,
        name: String,
        createdAtEpochMs: Long = 0L,
        updatedAt: Long = 0L,
        lastMessageAt: Long = 0L,
    ) = RoomSummaryDto(
        roomId = id,
        roomKind = RoomKind.CHAT,
        name = name,
        ownerId = "owner",
        ownerDisplayName = "Owner",
        role = "MEMBER",
        createdAt = "2026-08-05T00:00:00Z",
        createdAtEpochMs = createdAtEpochMs,
        updatedAt = updatedAt,
        lastMessageAt = lastMessageAt,
    )
}
