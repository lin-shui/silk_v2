package com.silk.backend.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserSearchRankingTest {
    @Test
    fun `single character queries are accepted and exact login ranks first`() {
        val results = rankUsersForSearch(
            users = listOf(
                user("maya", "Maya"),
                user("amy", "Amy"),
                user("a", "Single Letter"),
            ),
            query = "a",
        )

        assertEquals("a", results.first().loginName)
        assertEquals(setOf("a", "amy", "maya"), results.map { it.loginName }.toSet())
    }

    @Test
    fun `search normalizes separators and matches phone prefixes`() {
        val users = listOf(
            user("zhang-san", "Zhang San", "13800138000"),
            user("other", "Other User", "13900139000"),
        )

        assertEquals("zhang-san", rankUsersForSearch(users, "zhang san").single().loginName)
        assertEquals("zhang-san", rankUsersForSearch(users, "1380").single().loginName)
    }

    @Test
    fun `phone searches do not use typo matching`() {
        val users = listOf(
            user("member", "Room Member", "13800000022"),
            user("outsider", "Room Outsider", "13800000023"),
        )

        assertEquals("member", rankUsersForSearch(users, "13800000022").single().loginName)
    }

    @Test
    fun `fuzzy search supports missing and transposed characters`() {
        val users = listOf(
            user("alice", "Alice Chen"),
            user("bob", "Bob Li"),
        )

        assertEquals("alice", rankUsersForSearch(users, "alce").single().loginName)
        assertEquals("alice", rankUsersForSearch(users, "alcie").single().loginName)
        assertTrue(rankUsersForSearch(users, "zzzz").isEmpty())
    }

    private fun user(loginName: String, fullName: String, phone: String = "") = User(
        id = loginName,
        loginName = loginName,
        fullName = fullName,
        phoneNumber = phone,
    )
}
