package com.silk.web

import com.silk.shared.models.RoomKind
import com.silk.shared.models.RoomSummaryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationSceneLogicTest {
    @Test
    fun silkPrivateRoomsStayAheadOfMoreRecentRooms() {
        val rooms = listOf(
            room("chat", RoomKind.CHAT, lastMessageAt = 300L),
            room("silk", RoomKind.SILK_PRIVATE, lastMessageAt = 100L),
            room("workflow", RoomKind.WORKFLOW, lastMessageAt = 200L),
        )

        assertEquals(listOf("silk", "chat", "workflow"), orderRoomsForNavigation(rooms).map { it.roomId })
    }

    @Test
    fun newlyCreatedRoomStaysImmediatelyBelowSilkRoom() {
        val rooms = listOf(
            room("old-chat", RoomKind.CHAT, lastMessageAt = 300L),
            room("silk", RoomKind.SILK_PRIVATE, lastMessageAt = 100L),
            room("new-chat", RoomKind.CHAT, createdAtEpochMs = 400L),
        )

        assertEquals(listOf("silk", "new-chat", "old-chat"), orderRoomsForNavigation(rooms).map { it.roomId })
    }

    @Test
    fun websocketActivityMovesCurrentRoomImmediatelyBelowSilkRoom() {
        val rooms = listOf(
            room("silk", RoomKind.SILK_PRIVATE, lastMessageAt = 500L),
            room("newer-chat", RoomKind.CHAT, lastMessageAt = 400L),
            room("current-chat", RoomKind.CHAT, lastMessageAt = 100L),
        )

        val updated = rooms.withRoomActivity("current-chat", 600L)

        assertEquals(listOf("silk", "current-chat", "newer-chat"), orderRoomsForNavigation(updated).map { it.roomId })
    }

    @Test
    fun workflowTreeExpansionDefaultsOpenAndRestoresStoredChoice() {
        assertTrue(parseStoredWorkflowRoomExpanded(null))
        assertTrue(parseStoredWorkflowRoomExpanded("true"))
        assertFalse(parseStoredWorkflowRoomExpanded("false"))
        assertTrue(parseStoredWorkflowRoomExpanded("invalid"))
    }

    @Test
    fun roomMenuActionsFollowRoomKindAndMembershipRole() {
        assertEquals(
            listOf(RoomMenuAction.INVITE, RoomMenuAction.RENAME, RoomMenuAction.DELETE),
            availableRoomMenuActions(room("owner", RoomKind.CHAT, role = "OWNER")),
        )
        assertEquals(
            listOf(RoomMenuAction.INVITE, RoomMenuAction.LEAVE),
            availableRoomMenuActions(room("member", RoomKind.WORKFLOW, role = "MEMBER")),
        )
        assertTrue(availableRoomMenuActions(room("silk", RoomKind.SILK_PRIVATE, role = "OWNER")).isEmpty())
    }

    private fun room(
        id: String,
        kind: RoomKind,
        lastMessageAt: Long = 0L,
        createdAtEpochMs: Long = 0L,
        role: String = "MEMBER",
    ) = RoomSummaryDto(
        roomId = id,
        roomKind = kind,
        name = id,
        ownerId = "owner",
        ownerDisplayName = "Owner",
        role = role,
        createdAt = "2026-08-05T00:00:00Z",
        createdAtEpochMs = createdAtEpochMs,
        lastMessageAt = lastMessageAt,
    )
}
