package com.silk.web

import com.silk.shared.models.KnowledgeBaseContextSelection
import com.silk.shared.models.Message
import com.silk.shared.models.MessageCategory
import com.silk.shared.models.MessageReference
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

    @Test
    fun entryFilterAndStatusActionsExposeMinimalInboxFlow() {
        val candidate = KBEntryItem(id = "c1", title = "Candidate", status = KBEntryStatus.CANDIDATE)
        val published = KBEntryItem(id = "p1", title = "Published", status = KBEntryStatus.PUBLISHED)
        val archived = KBEntryItem(id = "a1", title = "Archived", status = KBEntryStatus.ARCHIVED)
        val entries = listOf(candidate, published, archived)

        assertEquals(listOf("c1"), filterKnowledgeEntries(entries, KnowledgeEntryFilter.CANDIDATE).map { it.id })
        assertEquals(listOf("p1"), filterKnowledgeEntries(entries, KnowledgeEntryFilter.PUBLISHED).map { it.id })
        assertEquals(listOf("a1"), filterKnowledgeEntries(entries, KnowledgeEntryFilter.ARCHIVED).map { it.id })
        assertEquals(KnowledgeEntryFilter.CANDIDATE, knowledgeFilterForStatus(KBEntryStatus.CANDIDATE))
        assertEquals(KnowledgeEntryFilter.PUBLISHED, knowledgeFilterForStatus(KBEntryStatus.PUBLISHED))
        assertEquals("发布" to KBEntryStatus.PUBLISHED, knowledgeStatusAction(candidate))
        assertEquals("归档" to KBEntryStatus.ARCHIVED, knowledgeStatusAction(published))
        assertEquals("重新发布" to KBEntryStatus.PUBLISHED, knowledgeStatusAction(archived))
    }

    @Test
    fun candidateInboxHelpersSupportSelectionAndMerge() {
        val selection = toggleKnowledgeEntrySelection(emptySet(), "c1")
        assertEquals(setOf("c1"), selection)
        assertEquals(emptySet(), toggleKnowledgeEntrySelection(selection, "c1"))

        val mergedContent = mergeKnowledgeEntryContent(
            targetContent = "已发布内容",
            candidateTitle = "候选纪要",
            candidateContent = "补充结论",
        )
        assertTrue(mergedContent.contains("已发布内容"))
        assertTrue(mergedContent.contains("## 合并自：候选纪要"))
        assertTrue(mergedContent.contains("补充结论"))

        assertEquals(
            listOf("meeting", "decision", "summary"),
            mergeKnowledgeEntryTags(
                targetTags = listOf("meeting", "decision"),
                candidateTags = listOf("summary", "meeting"),
            ),
        )

        val batchMergedContent = mergeKnowledgeEntriesContent(
            targetContent = "现有文档",
            candidateEntries = listOf(
                KBEntryItem(id = "c1", title = "候选一", content = "第一段"),
                KBEntryItem(id = "c2", title = "候选二", content = "第二段"),
            ),
        )
        assertTrue(batchMergedContent.contains("## 合并自：候选一"))
        assertTrue(batchMergedContent.contains("## 合并自：候选二"))

        assertEquals(
            listOf("meeting", "decision", "summary", "review"),
            mergeKnowledgeEntriesTags(
                targetTags = listOf("meeting", "decision"),
                candidateEntries = listOf(
                    KBEntryItem(id = "c1", title = "候选一", tags = listOf("summary", "meeting")),
                    KBEntryItem(id = "c2", title = "候选二", tags = listOf("review", "decision")),
                ),
            ),
        )
    }

    @Test
    fun meetingCaptureHelpersNormalizeTagsConfidenceAndTeamSource() {
        val teamTopic = KBTopicItem(
            id = "topic",
            name = "架构组",
            ownerId = "owner",
            spaceType = KnowledgeSpaceType.TEAM,
            groupId = "group-1",
        )

        assertEquals("架构组 会议纪要", buildDefaultMeetingCaptureTitle(teamTopic))
        assertEquals(listOf("meeting", "minutes", "decision"), parseKnowledgeCaptureTags("meeting, minutes，decision, meeting"))
        assertEquals(0.95, parseKnowledgeCaptureConfidence("0.95"))
        assertEquals(1.0, parseKnowledgeCaptureConfidence("1.5"))

        val source = buildMeetingCaptureSource(teamTopic, "0.82")
        assertEquals(KBSourceType.MEETING, source.sourceType)
        assertEquals("group-1", source.sourceGroupId)
        assertEquals(0.82, source.confidence)
    }

    @Test
    fun knowledgeContextSelectionTogglesStayMutuallyExclusive() {
        val pinned = togglePinnedKnowledgeBaseEntry(KnowledgeBaseContextSelection(), "entry-1")
        assertEquals(listOf("entry-1"), pinned.pinnedEntryIds)
        assertTrue(pinned.excludedEntryIds.isEmpty())
        assertTrue(hasKnowledgeBaseContextSelection(pinned))

        val excluded = toggleExcludedKnowledgeBaseEntry(pinned, "entry-1")
        assertTrue(excluded.pinnedEntryIds.isEmpty())
        assertEquals(listOf("entry-1"), excluded.excludedEntryIds)

        val cleared = toggleExcludedKnowledgeBaseEntry(excluded, "entry-1")
        assertFalse(hasKnowledgeBaseContextSelection(cleared))
    }

    @Test
    fun workflowStatusListHidesKnowledgeBaseContextDiagnostics() {
        val contextStatus = Message(
            id = "status-1",
            userId = "agent",
            userName = "Silk",
            content = "已为本轮准备 2 条知识库上下文",
            timestamp = 1L,
            category = MessageCategory.AGENT_STATUS,
            references = listOf(
                MessageReference(
                    kind = "available",
                    index = 1,
                    title = "KB Entry",
                    path = "kb://topic-1/entry-1",
                )
            ),
        )
        val regularStatus = Message(
            id = "status-2",
            userId = "agent",
            userName = "Silk",
            content = "Agent 正在整理回答",
            timestamp = 2L,
            category = MessageCategory.AGENT_STATUS,
        )

        assertEquals(listOf(regularStatus), filterNonKnowledgeBaseContextStatusMessages(listOf(contextStatus, regularStatus)))
    }
}
