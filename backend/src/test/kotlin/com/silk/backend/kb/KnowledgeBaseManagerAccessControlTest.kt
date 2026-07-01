package com.silk.backend.kb

import com.silk.backend.TestWorkspace
import com.silk.backend.database.GroupRepository
import com.silk.backend.models.KBAccessPolicy
import com.silk.backend.models.KnowledgeSpaceType
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class KnowledgeBaseManagerAccessControlTest {
    @Test
    fun `legacy store rows load as personal topics owned by creator`() {
        val baseDir = createTempDirectory("kb-legacy-store").resolve("store").toFile()
        baseDir.mkdirs()
        File(baseDir, "kb_store.json").writeText(
            """
            {
              "topics": [
                {
                  "id": "topic-1",
                  "name": "Legacy Topic",
                  "project": "",
                  "ownerId": "owner",
                  "createdAt": 1,
                  "updatedAt": 1
                }
              ],
              "entries": [
                {
                  "id": "entry-1",
                  "topicId": "topic-1",
                  "title": "Legacy Entry",
                  "content": "legacy content",
                  "tags": [],
                  "ownerId": "owner",
                  "createdAt": 1,
                  "updatedAt": 1
                }
              ]
            }
            """.trimIndent()
        )

        val manager = KnowledgeBaseManager(baseDir = baseDir.absolutePath)
        val topic = manager.listTopics("owner").single()
        val entry = manager.getEntry("entry-1", "owner")

        assertEquals(KnowledgeSpaceType.PERSONAL, topic.spaceType)
        assertEquals("owner", topic.createdBy)
        assertEquals("owner", topic.updatedBy)
        assertNotNull(entry)
        assertEquals("owner", entry.createdBy)
        assertNull(manager.getEntry("entry-1", "guest"))
    }

    @Test
    fun `team topics allow members to read while write lock blocks non managers`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val group = assertNotNull(GroupRepository.createGroup("KB ACL Group", hostId = "host"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "owner"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "member"))

            val topic = manager.createTopic(
                name = "Team Topic",
                project = "silk",
                userId = "owner",
                spaceType = KnowledgeSpaceType.TEAM,
                groupId = group.id,
                accessPolicy = KBAccessPolicy(writeLocked = true),
            )
            val entry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id,
                    title = "Shared Entry",
                    content = "team readable",
                    tags = emptyList(),
                    userId = "owner",
                )
            )

            assertTrue(manager.canReadTopic(topic, "member"))
            assertFalse(manager.canWriteTopic(topic, "member"))
            assertTrue(manager.canManageTopic(topic, "host"))
            assertNotNull(manager.getEntry(entry.id, "member"))
            assertNull(
                manager.updateEntry(
                    entryId = entry.id,
                    title = null,
                    content = "member edit",
                    tags = null,
                    status = null,
                    userId = "member",
                )
            )
            assertTrue(manager.deleteEntry(entry.id, "host"))
        }
    }

    @Test
    fun `team host can update topic access policy and grant personal access`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val group = assertNotNull(GroupRepository.createGroup("KB Share Group", hostId = "host"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "owner"))

            val topic = manager.createTopic(
                name = "Shared Topic",
                project = "silk",
                userId = "owner",
                spaceType = KnowledgeSpaceType.TEAM,
                groupId = group.id,
            )

            val updated = assertNotNull(
                manager.updateTopic(
                    topicId = topic.id,
                    userId = "host",
                    name = "Shared Topic v2",
                    project = "silk-v2",
                    accessPolicy = KBAccessPolicy(
                        readUserIds = listOf("reader"),
                        writeUserIds = listOf("writer"),
                        manageUserIds = listOf("manager"),
                        writeLocked = true,
                        teamMembersCanWrite = false,
                    ),
                )
            )

            assertEquals("Shared Topic v2", updated.name)
            assertEquals("silk-v2", updated.project)
            assertTrue(updated.accessPolicy.writeLocked)
            assertFalse(updated.accessPolicy.teamMembersCanWrite)
            assertContentEquals(listOf("reader"), updated.accessPolicy.readUserIds)
            assertTrue(manager.canReadTopic(updated, "reader"))
            assertTrue(manager.canManageTopic(updated, "manager"))
            assertFalse(manager.canWriteTopic(updated, "writer"))
            assertNull(
                manager.updateTopic(
                    topicId = topic.id,
                    userId = "outsider",
                    name = "nope",
                    project = null,
                    accessPolicy = null,
                )
            )
        }
    }
}
