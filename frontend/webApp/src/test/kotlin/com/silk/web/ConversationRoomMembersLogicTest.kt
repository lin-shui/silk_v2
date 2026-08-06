package com.silk.web

import com.silk.shared.i18n.Strings_ZH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationRoomMembersLogicTest {
    @Test
    fun singleCharacterQueryCanBeSearched() {
        assertFalse(isRoomMemberSearchDisabled(query = "a", busy = false))
        assertTrue(isRoomMemberSearchDisabled(query = " ", busy = false))
        assertTrue(isRoomMemberSearchDisabled(query = "a", busy = true))
    }

    @Test
    fun candidateEmptyStateWaitsForSearchBeforeReportingNoResults() {
        assertEquals(
            Strings_ZH.noContactsToAdd,
            roomMemberCandidateEmptyMessage(Strings_ZH, query = "", searchAttempted = false),
        )
        assertEquals(
            Strings_ZH.memberSearchPrompt,
            roomMemberCandidateEmptyMessage(Strings_ZH, query = "a", searchAttempted = false),
        )
        assertEquals(
            Strings_ZH.noAddableUsers,
            roomMemberCandidateEmptyMessage(Strings_ZH, query = "a", searchAttempted = true),
        )
    }
}
