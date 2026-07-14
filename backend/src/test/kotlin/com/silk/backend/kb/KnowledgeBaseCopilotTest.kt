package com.silk.backend.kb

import com.silk.backend.TestWorkspace
import com.silk.backend.models.KBEntryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class KnowledgeBaseCopilotTest {
    @Test
    fun `copilot preview returns normalized draft without persisting changes`() = runTest {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "工程沉淀", project = "silk", userId = "owner")
            val entry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id,
                    title = "发布流程",
                    content = "旧内容",
                    tags = listOf("release"),
                    userId = "owner",
                )
            )

            val response = executeKnowledgeBaseCopilot(
                manager = manager,
                request = KnowledgeBaseCopilotRequest(
                    userId = "owner",
                    entryId = entry.id,
                    instruction = "补充灰度发布步骤",
                    applyChanges = false,
                ),
                runAgent = {
                    val raw = """
                    我会补齐灰度发布步骤并整理标签。

                    ```silk_kb_action
                    {
                      "operation": "update_entry",
                      "entryId": "${entry.id}",
                      "topicId": "${topic.id}",
                      "title": "发布流程 v2",
                      "content": "先灰度，再全量。",
                      "tags": ["release", "rollout"]
                    }
                    ```
                    """.trimIndent()
                    val parsed = extractKnowledgeBaseAiActions(raw)
                    KbCopilotAgentResult(
                        displayText = parsed.cleanedContent,
                        actions = parsed.actions,
                    )
                },
            )

            assertTrue(response.success)
            assertEquals("发布流程 v2", response.draft?.title)
            assertEquals(listOf("release", "rollout"), response.draft?.tags)
            assertEquals("旧内容", manager.getEntry(entry.id, "owner")?.content)
        }
    }

    @Test
    fun `copilot apply updates current entry through kb action pipeline`() = runTest {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "项目沉淀", project = "silk", userId = "owner")
            val entry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id,
                    title = "知识库计划",
                    content = "待补充",
                    tags = listOf("kb"),
                    userId = "owner",
                    status = KBEntryStatus.CANDIDATE,
                )
            )

            val response = executeKnowledgeBaseCopilot(
                manager = manager,
                request = KnowledgeBaseCopilotRequest(
                    userId = "owner",
                    entryId = entry.id,
                    instruction = "把这份计划整理成更清晰的文档",
                    applyChanges = true,
                ),
                runAgent = {
                    val raw = """
                    已按你的要求重写结构。

                    ```silk_kb_action
                    {
                      "operation": "update_entry",
                      "entryId": "${entry.id}",
                      "topicId": "${topic.id}",
                      "title": "知识库计划（整理版）",
                      "content": "# 目标\n- 补齐 KB Copilot\n",
                      "tags": ["kb", "copilot"]
                    }
                    ```
                    """.trimIndent()
                    val parsed = extractKnowledgeBaseAiActions(raw)
                    KbCopilotAgentResult(
                        displayText = parsed.cleanedContent,
                        actions = parsed.actions,
                    )
                },
            )

            assertTrue(response.success)
            assertEquals("知识库计划（整理版）", response.appliedEntry?.title)
            val stored = assertNotNull(manager.getEntry(entry.id, "owner"))
            assertEquals("# 目标\n- 补齐 KB Copilot", stored.content)
            assertEquals(listOf("kb", "copilot"), stored.tags)
        }
    }
}
