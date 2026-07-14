package com.silk.backend

import com.silk.backend.auth.JwtProvider
import com.silk.backend.database.GroupRepository
import com.silk.backend.kb.KnowledgeBaseManager
import com.silk.backend.models.KBAccessPolicy
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBMemoryType
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBSourceType
import com.silk.backend.models.KBTopic
import com.silk.backend.models.KnowledgeSpaceType
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class KnowledgeBaseRouteContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val authenticatedUserHeader = "X-Silk-Authenticated-User-Id"

    @Test
    fun `export route requires caller access to the entry`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "Private Topic", project = "", userId = "owner")
            val entry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id,
                    title = "Private Entry",
                    content = "secret",
                    tags = emptyList(),
                    userId = "owner",
                )
            )

            testApplication {
                application { module() }

                val missingUser = client.get("/api/kb/entries/${entry.id}/export")
                assertEquals(HttpStatusCode.BadRequest, missingUser.status)

                val guest = client.get("/api/kb/entries/${entry.id}/export?userId=guest")
                assertEquals(HttpStatusCode.NotFound, guest.status)

                val owner = client.get("/api/kb/entries/${entry.id}/export?userId=owner")
                assertEquals(HttpStatusCode.OK, owner.status)
                assertTrue(owner.bodyAsText().contains("Private Entry"))
            }
        }
    }

    @Test
    fun `kb routes accept authenticated user header without query userId`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "Header Topic", project = "", userId = "owner")

            testApplication {
                application { module() }

                val response = client.get("/api/kb/topics") {
                    header(authenticatedUserHeader, "owner")
                }

                assertEquals(HttpStatusCode.OK, response.status)
                val topics = json.decodeFromString(
                    ListSerializer(KBTopic.serializer()),
                    response.bodyAsText(),
                )
                assertEquals(listOf(topic.id), topics.map { it.id })
            }
        }
    }

    @Test
    fun `kb routes accept jwt bearer tokens for topic listing and creation`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            manager.createTopic(name = "Existing Topic", project = "", userId = "owner")
            val accessToken = JwtProvider.generateAccessToken("owner")

            testApplication {
                application { module() }

                val listResponse = client.get("/api/kb/topics") {
                    header("Authorization", "Bearer $accessToken")
                }
                assertEquals(HttpStatusCode.OK, listResponse.status)
                val listedTopics = json.decodeFromString(
                    ListSerializer(KBTopic.serializer()),
                    listResponse.bodyAsText(),
                )
                assertEquals(listOf("Existing Topic"), listedTopics.map { it.name })

                val createResponse = client.post("/api/kb/topics") {
                    header("Authorization", "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "userId":"owner",
                          "name":"Created With Jwt",
                          "project":"silk"
                        }
                        """.trimIndent()
                    )
                }
                assertEquals(HttpStatusCode.Created, createResponse.status)
                val created = json.decodeFromString(KBTopic.serializer(), createResponse.bodyAsText())
                assertEquals("Created With Jwt", created.name)
                assertEquals("silk", created.project)
            }
        }
    }

    @Test
    fun `team topic routes only expose member readable data and reject locked writes`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val group = assertNotNull(GroupRepository.createGroup("KB Route Group", hostId = "host"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "owner"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "member"))
            val topic = manager.createTopic(
                name = "Shared Topic",
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

            testApplication {
                application { module() }

                val memberTopics = json.decodeFromString(
                    ListSerializer(KBTopic.serializer()),
                    client.get("/api/kb/topics?userId=member").bodyAsText(),
                )
                assertEquals(listOf(topic.id), memberTopics.map { it.id })

                val outsiderTopics = json.decodeFromString(
                    ListSerializer(KBTopic.serializer()),
                    client.get("/api/kb/topics?userId=outsider").bodyAsText(),
                )
                assertTrue(outsiderTopics.isEmpty())

                val memberEntries = client.get("/api/kb/entries?topicId=${topic.id}&userId=member")
                assertEquals(HttpStatusCode.OK, memberEntries.status)
                assertTrue(memberEntries.bodyAsText().contains(entry.id))

                val lockedUpdate = client.put("/api/kb/entries/${entry.id}") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":"member","content":"attempted edit"}""")
                }
                assertEquals(HttpStatusCode.NotFound, lockedUpdate.status)
            }
        }
    }

    @Test
    fun `topic update route only allows managers and persists share policy`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val group = assertNotNull(GroupRepository.createGroup("KB Topic Route Group", hostId = "host"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "owner"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "member"))
            val topic = manager.createTopic(
                name = "Original Topic",
                project = "silk",
                userId = "owner",
                spaceType = KnowledgeSpaceType.TEAM,
                groupId = group.id,
            )

            testApplication {
                application { module() }

                val memberUpdate = client.put("/api/kb/topics/${topic.id}") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":"member","name":"Member Rename"}""")
                }
                assertEquals(HttpStatusCode.NotFound, memberUpdate.status)

                val hostUpdate = client.put("/api/kb/topics/${topic.id}") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "userId":"host",
                          "name":"Managed Topic",
                          "project":"silk-v2",
                          "accessPolicy":{
                            "readUserIds":["guest"],
                            "writeUserIds":["writer"],
                            "manageUserIds":["host"],
                            "writeLocked":true,
                            "teamMembersCanWrite":false
                          }
                        }
                        """.trimIndent()
                    )
                }
                assertEquals(HttpStatusCode.OK, hostUpdate.status)
                val hostBody = hostUpdate.bodyAsText()
                val updated = json.decodeFromString(KBTopic.serializer(), hostBody)
                assertEquals("Managed Topic", updated.name)
                assertEquals("silk-v2", updated.project)
                assertTrue(updated.accessPolicy.writeLocked)
                assertFalse(updated.accessPolicy.teamMembersCanWrite)
                assertTrue(updated.accessPolicy.readUserIds.contains("guest"))

                val guestTopics = json.decodeFromString(
                    ListSerializer(KBTopic.serializer()),
                    client.get("/api/kb/topics?userId=guest").bodyAsText(),
                )
                assertEquals(listOf(topic.id), guestTopics.map { it.id })
            }
        }
    }

    @Test
    fun `capture route stores candidate entry with source metadata`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "Capture Topic", project = "", userId = "owner")

            testApplication {
                application { module() }

                val response = client.post("/api/kb/captures") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "userId":"owner",
                          "topicId":"${topic.id}",
                          "title":"阶段总结",
                          "content":"本轮工作流完成了 ACL 与 Context Tray 联调。",
                          "tags":["workflow","summary"],
                          "source":{
                            "sourceType":"WORKFLOW",
                            "sourceGroupId":"group-1",
                            "workflowId":"wf-42",
                            "messageIds":["msg-1","msg-2"],
                            "confidence":0.81
                          }
                        }
                        """.trimIndent()
                    )
                }

                assertEquals(HttpStatusCode.Created, response.status)
                val created = json.decodeFromString(KBEntry.serializer(), response.bodyAsText())
                assertEquals(KBEntryStatus.CANDIDATE, created.status)
                assertEquals(KBSourceType.WORKFLOW, created.source.sourceType)
                assertEquals("wf-42", created.source.workflowId)
                assertEquals(listOf("msg-1", "msg-2"), created.source.messageIds)

                val stored = manager.getEntry(created.id, "owner")
                assertNotNull(stored)
                assertEquals(KBEntryStatus.CANDIDATE, stored.status)
            }
        }
    }

    @Test
    fun `memory routes create list and delete explicit memory entries`() {
        TestWorkspace().use { _ ->
            testApplication {
                application { module() }

                val createdResponse = client.post("/api/kb/memory") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "userId":"owner",
                          "content":"记住 我喜欢 Kotlin 和 Ktor",
                          "memoryType":"PREFERENCE"
                        }
                        """.trimIndent()
                    )
                }
                assertEquals(HttpStatusCode.Created, createdResponse.status)
                val created = json.decodeFromString(KBEntry.serializer(), createdResponse.bodyAsText())
                assertEquals(KBMemoryType.PREFERENCE, created.memory?.type)
                assertTrue(created.tags.contains("memory"))

                val memoryListResponse = client.get("/api/kb/memory?userId=owner")
                assertEquals(HttpStatusCode.OK, memoryListResponse.status)
                val memoryEntries = json.decodeFromString(
                    ListSerializer(KBEntry.serializer()),
                    memoryListResponse.bodyAsText(),
                )
                assertEquals(listOf(created.id), memoryEntries.map { it.id })

                val topicsResponse = client.get("/api/kb/topics?userId=owner")
                val visibleTopics = json.decodeFromString(
                    ListSerializer(KBTopic.serializer()),
                    topicsResponse.bodyAsText(),
                )
                assertTrue(visibleTopics.none { it.project == "memory" })

                val deleteResponse = client.delete("/api/kb/memory/${created.id}?userId=owner")
                assertEquals(HttpStatusCode.OK, deleteResponse.status)

                val emptyListResponse = client.get("/api/kb/memory?userId=owner")
                val emptyList = json.decodeFromString(
                    ListSerializer(KBEntry.serializer()),
                    emptyListResponse.bodyAsText(),
                )
                assertTrue(emptyList.isEmpty())
            }
        }
    }

    @Test
    fun `context preferences route persists memory toggles`() {
        TestWorkspace().use { _ ->
            testApplication {
                application { module() }

                val updateResponse = client.put("/api/kb/context-preferences") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "userId":"owner",
                          "excludedSpaceIds":["g1"],
                          "memoryEnabled":false,
                          "autoCaptureEnabled":true,
                          "ephemeralSessionEnabled":true
                        }
                        """.trimIndent()
                    )
                }
                assertEquals(HttpStatusCode.OK, updateResponse.status)
                val updated = json.parseToJsonElement(updateResponse.bodyAsText()).jsonObject
                assertEquals(false, updated["memoryEnabled"]?.jsonPrimitive?.booleanOrNull)
                assertEquals(true, updated["autoCaptureEnabled"]?.jsonPrimitive?.booleanOrNull)
                assertEquals(true, updated["ephemeralSessionEnabled"]?.jsonPrimitive?.booleanOrNull)

                val getResponse = client.get("/api/kb/context-preferences?userId=owner")
                val restored = json.parseToJsonElement(getResponse.bodyAsText()).jsonObject
                assertEquals(listOf("g1"), restored["excludedSpaceIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull })
                assertEquals(false, restored["memoryEnabled"]?.jsonPrimitive?.booleanOrNull)
            }
        }
    }

    @Test
    fun `capture route keeps chat and workflow captures as candidate even when published is requested`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "Capture Policy Topic", project = "", userId = "owner")

            testApplication {
                application { module() }

                val response = client.post("/api/kb/captures") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "userId":"owner",
                          "topicId":"${topic.id}",
                          "title":"聊天纪要",
                          "content":"这条聊天沉淀不应直接发布。",
                          "status":"PUBLISHED",
                          "source":{
                            "sourceType":"CHAT",
                            "sourceGroupId":"group-1",
                            "messageIds":["msg-1"]
                          }
                        }
                        """.trimIndent()
                    )
                }

                assertEquals(HttpStatusCode.Created, response.status)
                val created = json.decodeFromString(KBEntry.serializer(), response.bodyAsText())
                assertEquals(KBSourceType.CHAT, created.source.sourceType)
                assertEquals(KBEntryStatus.CANDIDATE, created.status)
            }
        }
    }

    @Test
    fun `capture route allows meeting captures to opt into published status`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val group = assertNotNull(GroupRepository.createGroup("KB Meeting Route Group", hostId = "host"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "owner"))
            val topic = manager.createTopic(
                name = "Meeting Topic",
                project = "silk",
                userId = "owner",
                spaceType = KnowledgeSpaceType.TEAM,
                groupId = group.id,
            )

            testApplication {
                application { module() }

                val response = client.post("/api/kb/captures") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "userId":"owner",
                          "topicId":"${topic.id}",
                          "title":"例会纪要",
                          "content":"会议决定下周启用 KB candidate inbox 批处理。",
                          "status":"PUBLISHED",
                          "tags":["meeting","minutes"],
                          "source":{
                            "sourceType":"MEETING",
                            "sourceGroupId":"${group.id}",
                            "confidence":0.94
                          }
                        }
                        """.trimIndent()
                    )
                }

                assertEquals(HttpStatusCode.Created, response.status)
                val created = json.decodeFromString(KBEntry.serializer(), response.bodyAsText())
                assertEquals(KBSourceType.MEETING, created.source.sourceType)
                assertEquals(KBEntryStatus.PUBLISHED, created.status)
                assertEquals(group.id, created.source.sourceGroupId)
            }
        }
    }

    @Test
    fun `kb routes reject mismatched authenticated user and payload userId`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "Mismatch Topic", project = "", userId = "owner")

            testApplication {
                application { module() }

                val response = client.post("/api/kb/captures") {
                    header(authenticatedUserHeader, "owner")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "userId":"other",
                          "topicId":"${topic.id}",
                          "title":"例会纪要",
                          "content":"认证用户和 payload 用户必须一致。",
                          "source":{
                            "sourceType":"MEETING"
                          }
                        }
                        """.trimIndent()
                    )
                }

                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
        }
    }

    @Test
    fun `entry update route can publish captured candidates`() {
        TestWorkspace().use { workspace ->
            val manager = KnowledgeBaseManager(baseDir = workspace.knowledgeBaseDir.absolutePath)
            val topic = manager.createTopic(name = "Inbox Topic", project = "", userId = "owner")
            val entry = assertNotNull(
                manager.createEntry(
                    topicId = topic.id,
                    title = "候选条目",
                    content = "待整理",
                    tags = listOf("candidate"),
                    userId = "owner",
                    status = KBEntryStatus.CANDIDATE,
                )
            )

            testApplication {
                application { module() }

                val response = client.put("/api/kb/entries/${entry.id}") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":"owner","status":"PUBLISHED","title":"正式条目"}""")
                }

                assertEquals(HttpStatusCode.OK, response.status)
                val updated = json.decodeFromString(KBEntry.serializer(), response.bodyAsText())
                assertEquals("正式条目", updated.title)
                assertEquals(KBEntryStatus.PUBLISHED, updated.status)

                val stored = assertNotNull(manager.getEntry(entry.id, "owner"))
                assertEquals(KBEntryStatus.PUBLISHED, stored.status)
            }
        }
    }

}
