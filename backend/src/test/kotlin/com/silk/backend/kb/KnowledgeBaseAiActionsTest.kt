package com.silk.backend.kb

import com.silk.backend.TestWorkspace
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBSourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KnowledgeBaseAiActionsTest {
    @Test
    fun `extractKnowledgeBaseAiActions strips code block from assistant text`() {
        val parsed = extractKnowledgeBaseAiActions(
            """
            已帮你整理成知识库候选。

            ```silk_kb_action
            {
              "operation": "create_entry",
              "topicName": "工程沉淀",
              "title": "本轮总结",
              "content": "整理后的内容"
            }
            ```
            """.trimIndent()
        )

        assertEquals("已帮你整理成知识库候选。", parsed.cleanedContent)
        assertEquals(1, parsed.actions.size)
        assertEquals(KnowledgeBaseAiOperation.CREATE_ENTRY, parsed.actions.single().operation)
        assertEquals("工程沉淀", parsed.actions.single().topicName)
    }

    @Test
    fun `create_entry action writes candidate chat capture into writable topic`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "工程沉淀", project = "silk", userId = "owner")

            val results = executeKnowledgeBaseAiActions(
                manager = manager,
                request = KnowledgeBaseAiExecutionRequest(
                    userId = "owner",
                    recentMessageIds = listOf("msg-1", "msg-2"),
                ),
                actions = listOf(
                    KnowledgeBaseAiAction(
                        operation = KnowledgeBaseAiOperation.CREATE_ENTRY,
                        topicName = "工程沉淀",
                        title = "对话总结",
                        content = "这轮完成了 KB AI 操作闭环。",
                        tags = listOf("kb", "agent"),
                        sourceType = KBSourceType.CHAT,
                        status = KBEntryStatus.PUBLISHED,
                    )
                ),
            )

            val success = assertIs<KnowledgeBaseAiExecutionResult.Success>(results.single())
            val stored = assertNotNull(manager.getEntry(success.entry.id, "owner"))
            assertEquals(topic.id, stored.topicId)
            assertEquals(KBEntryStatus.CANDIDATE, stored.status)
            assertEquals(KBSourceType.CHAT, stored.source.sourceType)
            assertEquals(listOf("msg-1", "msg-2"), stored.source.messageIds)
        }
    }

    @Test
    fun `update_entry action resolves existing writable entry by id`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "Skill", project = "silk", userId = "owner")
            val entry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id,
                    title = "部署技能",
                    content = "旧内容",
                    tags = listOf("skill"),
                    userId = "owner",
                )
            )

            val results = executeKnowledgeBaseAiActions(
                manager = manager,
                request = KnowledgeBaseAiExecutionRequest(userId = "owner"),
                actions = listOf(
                    KnowledgeBaseAiAction(
                        operation = KnowledgeBaseAiOperation.UPDATE_ENTRY,
                        entryId = entry.id,
                        title = "部署技能 v2",
                        content = "新增了 KB 自动总结和执行约束。",
                        tags = listOf("skill", "kb"),
                    )
                ),
            )

            val success = assertIs<KnowledgeBaseAiExecutionResult.Success>(results.single())
            assertEquals("部署技能 v2", success.entry.title)
            assertTrue(success.entry.tags.contains("kb"))
            assertEquals("新增了 KB 自动总结和执行约束。", success.entry.content)
        }
    }

    @Test
    fun `workflow request defaults create_entry to workflow candidate provenance`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "项目沉淀", project = "silk", userId = "owner")

            val results = executeKnowledgeBaseAiActions(
                manager = manager,
                request = KnowledgeBaseAiExecutionRequest(
                    userId = "owner",
                    preferredGroupId = "group-7",
                    sourceGroupId = "group-7",
                    workflowId = "wf-7",
                    recentMessageIds = listOf("msg-9", "msg-10"),
                ),
                actions = listOf(
                    KnowledgeBaseAiAction(
                        operation = KnowledgeBaseAiOperation.CREATE_ENTRY,
                        topicId = topic.id,
                        title = "工作流总结",
                        content = "整理了 workflow agent 的 KB 后处理。",
                    )
                ),
            )

            val success = assertIs<KnowledgeBaseAiExecutionResult.Success>(results.single())
            assertEquals(KBEntryStatus.CANDIDATE, success.entry.status)
            assertEquals(KBSourceType.WORKFLOW, success.entry.source.sourceType)
            assertEquals("wf-7", success.entry.source.workflowId)
            assertEquals("group-7", success.entry.source.sourceGroupId)
            assertEquals(listOf("msg-9", "msg-10"), success.entry.source.messageIds)
        }
    }
}
