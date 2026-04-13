package com.silk.backend

import com.silk.backend.database.AuthResponse
import com.silk.backend.database.CreateGroupRequest
import com.silk.backend.database.DeleteUserTodoRequest
import com.silk.backend.database.GroupMembersResponse
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.GroupResponse
import com.silk.backend.database.JoinGroupRequest
import com.silk.backend.database.Language
import com.silk.backend.database.LoginRequest
import com.silk.backend.database.RecallMessageRequest
import com.silk.backend.database.RegisterRequest
import com.silk.backend.database.SimpleResponse
import com.silk.backend.database.UpdateUserSettingsRequest
import com.silk.backend.database.UpdateUserTodoRequest
import com.silk.backend.database.UserTodoItemDto
import com.silk.backend.database.UserTodosResponse
import com.silk.backend.database.UserSettingsResponse
import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import com.silk.backend.todos.UserTodoStore
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendHttpContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `auth and settings routes keep core contract stable`() {
        TestWorkspace().use {
            testApplication {
                application { module() }

                val registerResponse = client.post("/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            RegisterRequest(
                                loginName = "alice",
                                fullName = "Alice Chen",
                                phoneNumber = "13800000001",
                                password = "secret123"
                            )
                        )
                    )
                }
                assertEquals(HttpStatusCode.OK, registerResponse.status)
                val registerBody = registerResponse.decode<AuthResponse>()
                assertTrue(registerBody.success)
                val user = assertNotNull(registerBody.user)

                val duplicateRegister = client.post("/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            RegisterRequest(
                                loginName = "alice",
                                fullName = "Another Alice",
                                phoneNumber = "13800000002",
                                password = "secret123"
                            )
                        )
                    )
                }.decode<AuthResponse>()
                assertFalse(duplicateRegister.success)
                assertEquals("该登录名已被使用", duplicateRegister.message)

                val loginResponse = client.post("/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            LoginRequest(
                                loginName = "13800000001",
                                password = "secret123"
                            )
                        )
                    )
                }
                val loginBody = loginResponse.decode<AuthResponse>()
                assertTrue(loginBody.success)
                assertEquals(user.id, loginBody.user?.id)

                val validateBody = client.get("/auth/validate/${user.id}")
                    .decode<AuthResponse>()
                assertTrue(validateBody.success)
                assertEquals("Alice Chen", validateBody.user?.fullName)

                val defaultSettings = client.get("/users/${user.id}/settings")
                    .decode<UserSettingsResponse>()
                assertTrue(defaultSettings.success)
                assertEquals(Language.CHINESE, defaultSettings.settings?.language)

                val updateSettingsResponse = client.put("/users/${user.id}/settings") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            UpdateUserSettingsRequest(
                                userId = user.id,
                                language = Language.ENGLISH,
                                defaultAgentInstruction = "Answer briefly."
                            )
                        )
                    )
                }
                assertEquals(HttpStatusCode.OK, updateSettingsResponse.status)
                val updatedSettings = updateSettingsResponse.decode<UserSettingsResponse>()
                assertTrue(updatedSettings.success)
                assertEquals(Language.ENGLISH, updatedSettings.settings?.language)
                assertEquals("Answer briefly.", updatedSettings.settings?.defaultAgentInstruction)
            }
        }
    }

    @Test
    fun `group routes preserve create join and member listing flow`() {
        TestWorkspace().use { workspace ->
            testApplication {
                application { module() }

                val host = registerUser("host", "Host User", "13800000011")
                val guest = registerUser("guest", "Guest User", "13800000012")

                val createGroupResponse = client.post("/groups/create") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            CreateGroupRequest(
                                userId = host.id,
                                groupName = "CI Fast Group"
                            )
                        )
                    )
                }
                assertEquals(HttpStatusCode.OK, createGroupResponse.status)
                val createGroupBody = createGroupResponse.decode<GroupResponse>()
                assertTrue(createGroupBody.success)
                val group = assertNotNull(createGroupBody.group)
                assertTrue(File(workspace.chatHistoryDir, "group_${group.id}").isDirectory)

                val joinGroupBody = client.post("/groups/join") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            JoinGroupRequest(
                                userId = guest.id,
                                invitationCode = group.invitationCode
                            )
                        )
                    )
                }.decode<GroupResponse>()
                assertTrue(joinGroupBody.success)
                assertEquals(group.id, joinGroupBody.group?.id)

                val hostGroups = client.get("/groups/user/${host.id}")
                    .decode<GroupResponse>()
                assertTrue(hostGroups.success)
                assertEquals(listOf(group.id), hostGroups.groups?.map { it.id })

                val guestGroups = client.get("/groups/user/${guest.id}")
                    .decode<GroupResponse>()
                assertTrue(guestGroups.success)
                assertEquals(listOf(group.id), guestGroups.groups?.map { it.id })

                val membersBody = client.get("/groups/${group.id}/members")
                    .decode<GroupMembersResponse>()
                assertTrue(membersBody.success)
                assertEquals(setOf(host.id, guest.id), membersBody.members.map { it.id }.toSet())
            }
        }
    }

    @Test
    fun `user todo routes preserve list update and delete contract`() {
        TestWorkspace().use {
            testApplication {
                application { module() }

                val userId = "todo-route-user"
                val item = UserTodoItemDto(
                    id = "todo-1",
                    title = "检查 nightly 构建",
                    createdAt = 1_000L,
                    updatedAt = 1_000L
                )
                UserTodoStore.save(userId, listOf(item))

                val listBeforeUpdate = client.get("/api/user-todos/$userId")
                    .decode<UserTodosResponse>()
                assertTrue(listBeforeUpdate.success)
                assertEquals(listOf(item.id), listBeforeUpdate.items.map { it.id })

                val updated = client.put("/api/user-todos/item") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            UpdateUserTodoRequest(
                                userId = userId,
                                itemId = item.id,
                                done = true,
                                lifecycleState = "done"
                            )
                        )
                    )
                }.decode<UserTodosResponse>()
                assertTrue(updated.success)
                assertEquals(1, updated.items.size)
                assertTrue(updated.items.single().done)
                assertEquals("done", updated.items.single().lifecycleState)
                assertNotNull(updated.items.single().closedAt)

                val deleted = client.delete("/api/user-todos/item") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            DeleteUserTodoRequest(
                                userId = userId,
                                itemId = item.id
                            )
                        )
                    )
                }.decode<UserTodosResponse>()
                assertTrue(deleted.success)
                assertTrue(deleted.items.isEmpty())
                assertTrue(UserTodoStore.load(userId).isEmpty())
                assertNull(UserTodoStore.load(userId).firstOrNull())
            }
        }
    }

    @Test
    fun `message recall route removes sender message from isolated history`() {
        TestWorkspace().use { workspace ->
            val group = createGroupForTest("Recall Route Group")
            seedGroupHistory(
                group.id,
                listOf(
                    chatEntry(
                        messageId = "msg-1",
                        senderId = "recall-owner",
                        senderName = "Recall Owner",
                        content = "需要撤回的普通消息",
                        timestamp = 1_000L
                    )
                )
            )

            testApplication {
                application { module() }

                val recallResponse = client.post("/api/messages/recall") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            RecallMessageRequest(
                                groupId = group.id,
                                messageId = "msg-1",
                                userId = "recall-owner"
                            )
                        )
                    )
                }
                assertEquals(HttpStatusCode.OK, recallResponse.status)
                val recallBody = recallResponse.decode<SimpleResponse>()
                assertTrue(recallBody.success)
                assertEquals("撤回成功", recallBody.message)

                val historyFile = File(workspace.chatHistoryDir, "group_${group.id}/chat_history.json")
                assertTrue(historyFile.isFile)
                val remainingMessages = loadGroupHistory(group.id).messages
                assertTrue(remainingMessages.isEmpty())
            }
        }
    }

    @Test
    fun `message recall route rejects non sender and keeps message intact`() {
        TestWorkspace().use {
            val group = createGroupForTest("Recall Permission Group")
            seedGroupHistory(
                group.id,
                listOf(
                    chatEntry(
                        messageId = "msg-2",
                        senderId = "message-owner",
                        senderName = "Message Owner",
                        content = "只有发送者可以撤回",
                        timestamp = 2_000L
                    )
                )
            )

            testApplication {
                application { module() }

                val recallResponse = client.post("/api/messages/recall") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            RecallMessageRequest(
                                groupId = group.id,
                                messageId = "msg-2",
                                userId = "other-user"
                            )
                        )
                    )
                }
                assertEquals(HttpStatusCode.OK, recallResponse.status)
                val recallBody = recallResponse.decode<SimpleResponse>()
                assertFalse(recallBody.success)
                assertEquals("只能撤回自己发送的消息", recallBody.message)

                val remainingMessages = loadGroupHistory(group.id).messages
                assertEquals(listOf("msg-2"), remainingMessages.map { it.messageId })
            }
        }
    }

    @Test
    fun `message recall route also removes silk reply for silk prompt`() {
        TestWorkspace().use {
            val group = createGroupForTest("Recall Silk Group")
            seedGroupHistory(
                group.id,
                listOf(
                    chatEntry(
                        messageId = "user-msg",
                        senderId = "silk-caller",
                        senderName = "Silk Caller",
                        content = "@silk 帮我总结今天的讨论",
                        timestamp = 3_000L
                    ),
                    chatEntry(
                        messageId = "silk-reply",
                        senderId = SilkAgent.AGENT_ID,
                        senderName = SilkAgent.AGENT_NAME,
                        content = "这是 Silk 的回复",
                        timestamp = 3_100L
                    ),
                    chatEntry(
                        messageId = "after-msg",
                        senderId = "other-user",
                        senderName = "Other User",
                        content = "后续正常消息",
                        timestamp = 3_200L
                    )
                )
            )

            testApplication {
                application { module() }

                val recallResponse = client.post("/api/messages/recall") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            RecallMessageRequest(
                                groupId = group.id,
                                messageId = "user-msg",
                                userId = "silk-caller"
                            )
                        )
                    )
                }
                assertEquals(HttpStatusCode.OK, recallResponse.status)
                val recallBody = recallResponse.decode<SimpleResponse>()
                assertTrue(recallBody.success)
                assertEquals("撤回成功", recallBody.message)

                val remainingMessages = loadGroupHistory(group.id).messages
                assertEquals(listOf("after-msg"), remainingMessages.map { it.messageId })
            }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.registerUser(
        loginName: String,
        fullName: String,
        phoneNumber: String
    ) = client.post("/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(
            json.encodeToString(
                RegisterRequest(
                    loginName = loginName,
                    fullName = fullName,
                    phoneNumber = phoneNumber,
                    password = "secret123"
                )
            )
        )
    }.decode<AuthResponse>().user!!

    private fun createGroupForTest(groupName: String) =
        assertNotNull(GroupRepository.createGroup(groupName, hostId = "host-user"))

    private fun seedGroupHistory(groupId: String, entries: List<ChatHistoryEntry>) {
        ChatHistoryManager().saveChatHistory(
            sessionName = "group_$groupId",
            chatHistory = ChatHistory(
                sessionId = "session-$groupId",
                messages = entries.toMutableList()
            )
        )
    }

    private fun loadGroupHistory(groupId: String) =
        assertNotNull(ChatHistoryManager().loadChatHistory("group_$groupId"))

    private fun chatEntry(
        messageId: String,
        senderId: String,
        senderName: String,
        content: String,
        timestamp: Long
    ) = ChatHistoryEntry(
        messageId = messageId,
        senderId = senderId,
        senderName = senderName,
        content = content,
        timestamp = timestamp,
        messageType = "TEXT"
    )

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())
}
