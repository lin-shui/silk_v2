package com.silk.web

import com.silk.shared.models.KnowledgeBaseContextSelection
import com.silk.shared.models.Message
import com.silk.shared.models.MessageCategory
import com.silk.shared.models.MessageReference
import com.silk.shared.models.MessageType
import com.silk.shared.models.SILK_AGENT_USER_ID
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
    fun captureSourceTypeTreatsAgentMessagesAsAiResponses() {
        val aiMessage = Message(
            id = "msg-ai",
            userId = SILK_AGENT_USER_ID,
            userName = "Silk",
            content = "这是 AI 回答",
            timestamp = 1L,
        )
        val userMessage = aiMessage.copy(id = "msg-user", userId = "user-1", userName = "Alice")

        assertEquals(KBSourceType.AI_RESPONSE, messageKnowledgeCaptureSourceType(aiMessage, KBSourceType.CHAT))
        assertEquals(KBSourceType.AI_RESPONSE, messageKnowledgeCaptureSourceType(aiMessage, KBSourceType.WORKFLOW))
        assertEquals(KBSourceType.CHAT, messageKnowledgeCaptureSourceType(userMessage, KBSourceType.CHAT))
        assertEquals(KBSourceType.WORKFLOW, messageKnowledgeCaptureSourceType(userMessage, KBSourceType.WORKFLOW))
    }

    @Test
    fun provenanceHelpersExposeReadableSourceLabelsAndDetails() {
        val groups = listOf(
            Group(id = "group-1", name = "架构组", invitationCode = "", hostId = "host"),
        )
        val entry = KBEntryItem(
            id = "entry-1",
            title = "AI 总结",
            source = KBEntrySource(
                sourceType = KBSourceType.AI_RESPONSE,
                sourceGroupId = "group-1",
                workflowId = "wf-7",
                messageIds = listOf("msg-1", "msg-2", "msg-3", "msg-4"),
                confidence = 0.83,
            ),
            createdBy = "owner",
            updatedBy = "editor",
        )

        assertEquals("AI 回答", knowledgeSourceLabel(KBSourceType.AI_RESPONSE))
        assertEquals("AI", knowledgeSourceShortLabel(KBSourceType.AI_RESPONSE))
        assertEquals(
            listOf(
                "来源群组" to "架构组 (group-1)",
                "工作流" to "wf-7",
                "消息" to "msg-1, msg-2, msg-3 等 4 条",
                "置信度" to "83%",
                "创建人" to "owner",
                "更新人" to "editor",
            ),
            knowledgeSourceDetails(entry, groups),
        )
    }

    @Test
    fun knowledgeContextSelectionTogglesStayMutuallyExclusive() {
        val pinned = togglePinnedKnowledgeBaseEntry(
            KnowledgeBaseContextSelection(excludedSpaceIds = listOf("group-1")),
            "entry-1",
        )
        assertEquals(listOf("entry-1"), pinned.pinnedEntryIds)
        assertTrue(pinned.excludedEntryIds.isEmpty())
        assertEquals(listOf("group-1"), pinned.excludedSpaceIds)
        assertTrue(hasKnowledgeBaseContextSelection(pinned))

        val excluded = toggleExcludedKnowledgeBaseEntry(pinned, "entry-1")
        assertTrue(excluded.pinnedEntryIds.isEmpty())
        assertEquals(listOf("entry-1"), excluded.excludedEntryIds)
        assertEquals(listOf("group-1"), excluded.excludedSpaceIds)

        val cleared = toggleExcludedKnowledgeBaseEntry(excluded, "entry-1")
        assertTrue(hasKnowledgeBaseContextSelection(cleared))
    }

    @Test
    fun knowledgeContextSelectionCanExcludeWholeSpaceForNextTurn() {
        val excluded = toggleExcludedKnowledgeBaseSpace(KnowledgeBaseContextSelection(), "group-1")

        assertEquals(listOf("group-1"), excluded.excludedSpaceIds)
        assertTrue(hasKnowledgeBaseContextSelection(excluded))

        val restored = toggleExcludedKnowledgeBaseSpace(excluded, "group-1")
        assertEquals(KnowledgeBaseContextSelection(), restored)
    }

    @Test
    fun latestKnowledgeBaseContextSelectionRestoresLastExplicitUserPreference() {
        val messages = listOf(
            Message(
                id = "m1",
                userId = "user-1",
                userName = "Alice",
                content = "first",
                timestamp = 1L,
                kbContextSelection = KnowledgeBaseContextSelection(pinnedEntryIds = listOf("entry-1")),
            ),
            Message(
                id = "m2",
                userId = SILK_AGENT_USER_ID,
                userName = "Silk",
                content = "ignored",
                timestamp = 2L,
                kbContextSelection = KnowledgeBaseContextSelection(excludedEntryIds = listOf("ignored")),
            ),
            Message(
                id = "m3",
                userId = "user-1",
                userName = "Alice",
                content = "second",
                timestamp = 3L,
                kbContextSelection = KnowledgeBaseContextSelection(excludedEntryIds = listOf("entry-2")),
            ),
        )

        assertEquals(
            KnowledgeBaseContextSelection(excludedEntryIds = listOf("entry-2")),
            latestKnowledgeBaseContextSelection(messages, "user-1"),
        )
    }

    @Test
    fun latestKnowledgeBaseContextSelectionKeepsExplicitClearState() {
        val messages = listOf(
            Message(
                id = "m1",
                userId = "user-1",
                userName = "Alice",
                content = "pin",
                timestamp = 1L,
                kbContextSelection = KnowledgeBaseContextSelection(pinnedEntryIds = listOf("entry-1")),
            ),
            Message(
                id = "m2",
                userId = "user-1",
                userName = "Alice",
                content = "clear",
                timestamp = 2L,
                type = MessageType.TEXT,
                kbContextSelection = KnowledgeBaseContextSelection(),
            ),
        )

        assertEquals(KnowledgeBaseContextSelection(), latestKnowledgeBaseContextSelection(messages, "user-1"))
    }

    @Test
    fun persistentExcludedSpacesMergeIntoRestoredKnowledgeContextSelection() {
        assertEquals(
            KnowledgeBaseContextSelection(excludedSpaceIds = listOf("group-1")),
            mergeKnowledgeBaseContextSelectionWithPersistentSpaces(
                restoredSelection = null,
                persistentExcludedSpaceIds = listOf("group-1"),
            ),
        )

        assertEquals(
            KnowledgeBaseContextSelection(
                pinnedEntryIds = listOf("entry-1"),
                excludedEntryIds = listOf("entry-2"),
                excludedSpaceIds = listOf("group-1", "group-2"),
            ),
            mergeKnowledgeBaseContextSelectionWithPersistentSpaces(
                restoredSelection = KnowledgeBaseContextSelection(
                    pinnedEntryIds = listOf("entry-1"),
                    excludedEntryIds = listOf("entry-2"),
                    excludedSpaceIds = listOf("group-2"),
                ),
                persistentExcludedSpaceIds = listOf("group-1"),
            ),
        )
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
