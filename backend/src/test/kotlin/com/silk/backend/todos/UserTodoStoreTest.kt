package com.silk.backend.todos

import com.silk.backend.TestWorkspace
import com.silk.backend.database.UserTodoItemDto
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserTodoStoreTest {
    @Test
    fun `done item reopens only when newer evidence arrives`() {
        TestWorkspace().use {
            val userId = "todo-user-done"
            val closedAt = 10_000L
            val existing = UserTodoItemDto(
                id = "done-1",
                title = "整理会议纪要",
                createdAt = 1_000L,
                updatedAt = closedAt,
                done = true,
                taskKind = "short_term_instance",
                lifecycleState = "done",
                closedAt = closedAt,
                lastEvidenceAt = 9_000L
            )
            UserTodoStore.save(userId, listOf(existing))

            UserTodoStore.replaceUndoneWithExtracted(
                userId,
                listOf(
                    ExtractedTodoDraft(
                        title = "整理会议纪要",
                        evidenceAt = closedAt
                    )
                )
            )
            val unchanged = UserTodoStore.load(userId).single()
            assertTrue(unchanged.done)
            assertEquals("done", unchanged.lifecycleState)
            assertEquals(0, unchanged.reopenCount)

            UserTodoStore.replaceUndoneWithExtracted(
                userId,
                listOf(
                    ExtractedTodoDraft(
                        title = "整理会议纪要",
                        evidenceAt = closedAt + 1
                    )
                )
            )
            val reopened = UserTodoStore.load(userId).single()
            assertFalse(reopened.done)
            assertEquals("active", reopened.lifecycleState)
            assertNull(reopened.closedAt)
            assertEquals(1, reopened.reopenCount)
            assertEquals(closedAt + 1, reopened.lastEvidenceAt)
        }
    }

    @Test
    fun `cancelled item needs explicit intent before reopening`() {
        TestWorkspace().use {
            val userId = "todo-user-cancelled"
            val closedAt = 20_000L
            UserTodoStore.save(
                userId,
                listOf(
                    UserTodoItemDto(
                        id = "cancelled-1",
                        title = "给客户回电话",
                        createdAt = 1_000L,
                        updatedAt = closedAt,
                        done = true,
                        taskKind = "short_term_instance",
                        lifecycleState = "cancelled",
                        closedAt = closedAt,
                        lastEvidenceAt = 19_000L
                    )
                )
            )

            UserTodoStore.replaceUndoneWithExtracted(
                userId,
                listOf(
                    ExtractedTodoDraft(
                        title = "给客户回电话",
                        evidenceAt = closedAt + 10,
                        explicitIntent = false
                    )
                )
            )
            val stillCancelled = UserTodoStore.load(userId).single()
            assertEquals("cancelled", stillCancelled.lifecycleState)
            assertTrue(stillCancelled.done)

            UserTodoStore.replaceUndoneWithExtracted(
                userId,
                listOf(
                    ExtractedTodoDraft(
                        title = "给客户回电话",
                        evidenceAt = closedAt + 20,
                        explicitIntent = true
                    )
                )
            )
            val reopened = UserTodoStore.load(userId).single()
            assertEquals("active", reopened.lifecycleState)
            assertFalse(reopened.done)
            assertEquals(1, reopened.reopenCount)
            assertTrue(reopened.explicitIntent)
        }
    }

    @Test
    fun `dedupe merges alarm items by logical key`() {
        TestWorkspace().use {
            val userId = "todo-user-dedupe"
            UserTodoStore.save(
                userId,
                listOf(
                    UserTodoItemDto(
                        id = "alarm-1",
                        title = "提醒我早上七点起床",
                        actionType = "alarm",
                        createdAt = 1_000L,
                        updatedAt = 2_000L
                    ),
                    UserTodoItemDto(
                        id = "alarm-2",
                        title = "七点起床闹钟",
                        actionType = "alarm",
                        actionDetail = "07:00",
                        createdAt = 1_500L,
                        updatedAt = 2_500L
                    )
                )
            )

            UserTodoStore.dedupeByLogicalKeyInPlace(userId)

            val merged = UserTodoStore.load(userId)
            assertEquals(1, merged.size)
            assertEquals("alarm", merged.single().actionType)
            assertEquals("07:00", merged.single().actionDetail)
        }
    }

    @Test
    fun `dedupe merges contained normalized titles`() {
        TestWorkspace().use {
            val userId = "todo-user-contained-dedupe"
            UserTodoStore.save(
                userId,
                listOf(
                    UserTodoItemDto(
                        id = "todo-1",
                        title = "提醒我整理会议纪要",
                        createdAt = 1_000L,
                        updatedAt = 2_000L
                    ),
                    UserTodoItemDto(
                        id = "todo-2",
                        title = "整理会议纪要",
                        actionDetail = "发给产品组",
                        createdAt = 1_500L,
                        updatedAt = 2_500L
                    )
                )
            )

            UserTodoStore.dedupeByLogicalKeyInPlace(userId)

            val merged = UserTodoStore.load(userId)
            assertEquals(1, merged.size)
            assertEquals("整理会议纪要", merged.single().title)
            assertEquals("发给产品组", merged.single().actionDetail)
        }
    }

    @Test
    fun `monthly template instantiates one task for today`() {
        TestWorkspace().use {
            val userId = "todo-user-template"
            val today = LocalDate.now()

            UserTodoStore.replaceUndoneWithExtracted(
                userId,
                listOf(
                    ExtractedTodoDraft(
                        title = "月度对账",
                        taskKind = "long_term_template",
                        repeatRule = "monthly",
                        repeatAnchor = today.dayOfMonth.toString()
                    )
                )
            )

            val items = UserTodoStore.load(userId)
            assertEquals(2, items.size)
            val template = items.single { it.taskKind == "long_term_template" }
            val instance = items.single { it.taskKind == "short_term_instance" }
            assertEquals("monthly", template.repeatRule)
            assertEquals(today.toString(), instance.dateBucket)
            assertEquals(template.id, instance.templateId)
            assertEquals("active", instance.lifecycleState)
            assertNotNull(instance.lastEvidenceAt)
        }
    }

    @Test
    fun `mergeExtracted skips duplicate logical keys and invalid titles`() {
        TestWorkspace().use {
            val userId = "todo-user-merge-extracted"
            UserTodoStore.save(
                userId,
                listOf(
                    UserTodoItemDto(
                        id = "existing-1",
                        title = "整理合同",
                        createdAt = 1_000L,
                        updatedAt = 2_000L,
                    )
                )
            )

            UserTodoStore.mergeExtracted(
                userId,
                listOf(
                    ExtractedTodoDraft(title = "  整理合同  "),
                    ExtractedTodoDraft(title = "   "),
                    ExtractedTodoDraft(title = "安排演示", actionType = "calendar", actionDetail = "2026-06-12 09:00"),
                )
            )

            val items = UserTodoStore.load(userId)
            assertEquals(2, items.size)
            assertEquals(1, items.count { it.title == "整理合同" })
            assertNotNull(items.singleOrNull { it.title == "安排演示" })
        }
    }

    @Test
    fun `updateItem keeps cancelled lifecycle when marking done`() {
        TestWorkspace().use {
            val userId = "todo-user-update-cancelled"
            val item = UserTodoItemDto(
                id = "todo-1",
                title = "联系供应商",
                createdAt = 1_000L,
                updatedAt = 2_000L,
                done = true,
                lifecycleState = "cancelled",
                closedAt = 2_000L,
            )
            UserTodoStore.save(userId, listOf(item))

            assertTrue(UserTodoStore.updateItem(userId, item.id, done = true))

            val updated = UserTodoStore.load(userId).single()
            assertTrue(updated.done)
            assertEquals("cancelled", updated.lifecycleState)
            assertEquals(2_000L, updated.closedAt)
        }
    }

    @Test
    fun `updateItem clears optional fields on blank and explicit clear`() {
        TestWorkspace().use {
            val userId = "todo-user-update-clear"
            val item = UserTodoItemDto(
                id = "todo-2",
                title = "安排演示",
                actionType = "calendar",
                actionDetail = "2026-06-12 09:00",
                createdAt = 1_000L,
                updatedAt = 2_000L,
                reminderId = 99L,
                repeatRule = "monthly",
                repeatAnchor = "12",
                templateId = "tpl-1",
                dateBucket = "2026-06-12",
            )
            UserTodoStore.save(userId, listOf(item))

            assertTrue(
                UserTodoStore.updateItem(
                    userId = userId,
                    itemId = item.id,
                    actionType = "  ",
                    actionDetail = "  ",
                    clearReminderId = true,
                    repeatRule = "  ",
                    repeatAnchor = "  ",
                    templateId = "  ",
                    dateBucket = "  ",
                )
            )

            val updated = UserTodoStore.load(userId).single()
            assertNull(updated.actionType)
            assertNull(updated.actionDetail)
            assertNull(updated.reminderId)
            assertNull(updated.repeatRule)
            assertNull(updated.repeatAnchor)
            assertNull(updated.templateId)
            assertNull(updated.dateBucket)
        }
    }

    @Test
    fun `load returns empty list for corrupt payload`() {
        TestWorkspace().use {
            val userId = "todo-user-corrupt"
            val todoDir = File(System.getProperty("silk.userTodoBaseDir"))
            todoDir.mkdirs()
            File(todoDir, "$userId.json").writeText("""{"userId":"$userId","items":["""")

            assertTrue(UserTodoStore.load(userId).isEmpty())
        }
    }
}
