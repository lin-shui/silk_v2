package com.silk.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnowledgeBaseSceneLogicTest {
    @Test
    fun spaceOptionsKeepPersonalFirstAndFilterWorkflowGroups() {
        val options = buildKnowledgeSpaceOptions(
            listOf(
                Group(id = "wf-1", name = "wf_hidden", invitationCode = "", hostId = "u1"),
                Group(id = "g-2", name = "Beta Team", invitationCode = "", hostId = "u1"),
                Group(id = "g-1", name = "Alpha Team", invitationCode = "", hostId = "u1"),
            )
        )

        assertEquals(listOf(PERSONAL_SPACE_ID, "g-1", "g-2"), options.map { it.id })
    }

    @Test
    fun topicFilteringKeepsPersonalAndTeamSpacesIsolated() {
        val topics = listOf(
            KBTopicItem(id = "p1", name = "Personal", ownerId = "u1"),
            KBTopicItem(
                id = "t1",
                name = "Team A",
                ownerId = "u1",
                spaceType = KnowledgeSpaceType.TEAM,
                groupId = "g-1",
            ),
            KBTopicItem(
                id = "t2",
                name = "Team B",
                ownerId = "u1",
                spaceType = KnowledgeSpaceType.TEAM,
                groupId = "g-2",
            ),
        )

        assertEquals(listOf("p1"), filterTopicsForSpace(topics, PERSONAL_SPACE_ID).map { it.id })
        assertEquals(listOf("t1"), filterTopicsForSpace(topics, "g-1").map { it.id })
    }

    @Test
    fun writeAccessHonorsOwnerLockAndTeamMembership() {
        val groups = listOf(
            Group(id = "g-1", name = "Team", invitationCode = "", hostId = "host")
        )
        val teamTopic = KBTopicItem(
            id = "topic",
            name = "Shared",
            ownerId = "owner",
            spaceType = KnowledgeSpaceType.TEAM,
            groupId = "g-1",
        )
        val lockedTopic = teamTopic.copy(
            accessPolicy = KBAccessPolicy(writeLocked = true)
        )

        assertTrue(canWriteKnowledgeTopic(teamTopic, "owner", groups))
        assertTrue(canWriteKnowledgeTopic(teamTopic, "member", groups))
        assertFalse(canWriteKnowledgeTopic(lockedTopic, "member", groups))
        assertFalse(canWriteKnowledgeTopic(teamTopic, "outsider", emptyList()))
    }

    @Test
    fun manageAccessHonorsOwnerExplicitManagersAndTeamHost() {
        val groups = listOf(
            Group(id = "g-1", name = "Team", invitationCode = "", hostId = "host")
        )
        val topic = KBTopicItem(
            id = "topic",
            name = "Shared",
            ownerId = "owner",
            spaceType = KnowledgeSpaceType.TEAM,
            groupId = "g-1",
            accessPolicy = KBAccessPolicy(manageUserIds = listOf("manager")),
        )

        assertTrue(canManageKnowledgeTopic(topic, "owner", groups))
        assertTrue(canManageKnowledgeTopic(topic, "manager", groups))
        assertTrue(canManageKnowledgeTopic(topic, "host", groups))
        assertFalse(canManageKnowledgeTopic(topic, "member", groups))
    }

    @Test
    fun knowledgeUserIdCsvHelpersTrimDedupeAndRoundTrip() {
        val parsed = csvToKnowledgeUserIds(" alice, bob ,, alice ,carol ")

        assertEquals(listOf("alice", "bob", "carol"), parsed)
        assertEquals("alice, bob, carol", knowledgeUserIdsToCsv(parsed))
    }
}
