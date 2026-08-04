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
import com.silk.backend.database.User
import com.silk.backend.database.UserRepository
import com.silk.backend.database.UserTodoItemDto
import com.silk.backend.database.UserTodosResponse
import com.silk.backend.database.UserSettingsResponse
import com.silk.backend.auth.JwtProvider
import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import com.silk.backend.models.Workflow
import com.silk.backend.routes.WorkspaceDto
import com.silk.backend.routes.WorkflowMemberCandidatesResponse
import com.silk.backend.routes.WorkflowRoomMembersResponse
import com.silk.backend.routes.WorkflowSummaryDto
import com.silk.backend.todos.UserTodoStore
import com.silk.backend.workflow.WorkflowManager
import com.silk.backend.workspace.WorkspaceLifecycleState
import com.silk.backend.workspace.WorkspaceManager
import com.silk.backend.workspace.WorkspaceVisibility
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mindrot.jbcrypt.BCrypt
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

                // 验证注册已禁用
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
                assertFalse(registerBody.success)
                assertEquals("注册已关闭，请使用华为帐号登录", registerBody.message)
                assertNull(registerBody.user)

                // 直接创建测试用户（绕过已禁用的注册API）
                val user = createTestUser("alice", "Alice Chen", "13800000001")
                assertNotNull(user)

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
                assertEquals("注册已关闭，请使用华为帐号登录", duplicateRegister.message)

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

                // 直接创建测试用户（不通过已禁用的注册API）
                val host = createTestUser("host", "Host User", "13800000011")
                val guest = createTestUser("guest", "Guest User", "13800000012")

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
    fun `workflow routes bind mutations to jwt caller and redact runtime state from members`() {
        TestWorkspace().use { workspace ->
            testApplication {
                application { module() }

                val ownerId = "workflow-route-owner"
                val memberId = "workflow-route-member"
                val ownerToken = JwtProvider.generateAccessToken(ownerId)
                val memberToken = JwtProvider.generateAccessToken(memberId)
                val createBody = """{"userId":"$ownerId","name":"Workflow ACL","agentType":"silk_chat"}"""

                val unauthenticated = client.post("/api/workflows") {
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
                assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)

                val mismatched = client.post("/api/workflows") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
                assertEquals(HttpStatusCode.Forbidden, mismatched.status)

                val createdResponse = client.post("/api/workflows") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
                assertEquals(HttpStatusCode.Created, createdResponse.status)
                val created = createdResponse.decode<Workflow>()
                assertTrue(GroupRepository.addUserToGroup(created.groupId, memberId))

                val manager = WorkflowManager(workspace.workflowDir.absolutePath)
                manager.updateWorkingDir(created.groupId, "/private/owner/project")
                manager.updateSessionState(created.groupId, "private-session", sessionStarted = true)

                val memberView = client.get("/api/workflows/by-group/${created.groupId}") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                }.decode<Workflow>()
                assertEquals(created.id, memberView.id)
                assertEquals("", memberView.workingDir)
                assertEquals("", memberView.sessionId)
                assertFalse(memberView.sessionStarted)

                val memberRename = client.put("/api/workflows/${created.id}") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":"$ownerId","name":"Hijacked"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, memberRename.status)

                val memberDelete = client.delete("/api/workflows/${created.id}?userId=$ownerId") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                }
                assertEquals(HttpStatusCode.Forbidden, memberDelete.status)

                val ownerDelete = client.delete("/api/workflows/${created.id}?userId=$ownerId") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                }
                assertEquals(HttpStatusCode.OK, ownerDelete.status)
            }
        }
    }

    @Test
    fun `workflow room owner manages members and members discover the shared room`() {
        TestWorkspace().use { workspace ->
            testApplication {
                application { module() }

                val owner = createTestUser("room-owner", "Room Owner", "13800000021")
                val member = createTestUser("room-member", "Room Member", "13800000022")
                val outsider = createTestUser("room-outsider", "Room Outsider", "13800000023")
                val group = createGroupForTest("wf_member_contract", owner.id)
                val workflow = WorkflowManager(workspace.workflowDir.absolutePath).createWorkflow(
                    name = "Member Contract",
                    description = "",
                    userId = owner.id,
                    groupId = group.id,
                )
                val ownerToken = JwtProvider.generateAccessToken(owner.id)
                val memberToken = JwtProvider.generateAccessToken(member.id)
                val outsiderToken = JwtProvider.generateAccessToken(outsider.id)

                val ownerVisible = client.get("/api/workflows/visible") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                }.decode<List<WorkflowSummaryDto>>()
                assertEquals(listOf(workflow.id), ownerVisible.map { it.id })
                assertEquals("OWNER", ownerVisible.single().role)

                val beforeAdd = client.get("/api/workflows/visible") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                }.decode<List<WorkflowSummaryDto>>()
                assertTrue(beforeAdd.isEmpty())

                val candidates = client.get(
                    "/api/workflows/${workflow.id}/members/candidates?query=13800000022"
                ) {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                }.decode<WorkflowMemberCandidatesResponse>()
                assertEquals(listOf(member.id), candidates.candidates.map { it.id })

                val forbiddenAdd = client.post("/api/workflows/${workflow.id}/members") {
                    header(HttpHeaders.Authorization, "Bearer $outsiderToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":"${member.id}"}""")
                }
                assertEquals(HttpStatusCode.NotFound, forbiddenAdd.status)

                val added = client.post("/api/workflows/${workflow.id}/members") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":"${member.id}"}""")
                }
                assertEquals(HttpStatusCode.Created, added.status)
                assertEquals(2, added.decode<WorkflowRoomMembersResponse>().members.size)

                val memberVisible = client.get("/api/workflows/visible") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                }.decode<List<WorkflowSummaryDto>>()
                assertEquals(listOf(workflow.id), memberVisible.map { it.id })
                assertEquals("MEMBER", memberVisible.single().role)

                val workspaceManager = WorkspaceManager(workspace.workflowDir.absolutePath)
                val personalWorkspace = workspaceManager.createWorkspace(
                    roomId = group.id,
                    ownerId = owner.id,
                    name = "shared",
                    visibility = WorkspaceVisibility.SHARED,
                )
                assertTrue(workspaceManager.updateCopilots(personalWorkspace.workspaceId, listOf(member.id)))

                val removed = client.delete("/api/workflows/${workflow.id}/members/${member.id}") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                }
                assertEquals(HttpStatusCode.OK, removed.status)
                assertFalse(GroupRepository.isUserInGroup(group.id, member.id))
                assertTrue(
                    WorkspaceManager(workspace.workflowDir.absolutePath)
                        .getWorkspace(personalWorkspace.workspaceId)?.copilots.orEmpty().isEmpty()
                )

                val afterRemove = client.get("/api/workflows/visible") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                }.decode<List<WorkflowSummaryDto>>()
                assertTrue(afterRemove.isEmpty())
            }
        }
    }

    @Test
    fun `workspace routes expose safe discovery lifecycle and deletion contracts`() {
        TestWorkspace().use { workspace ->
            val owner = createTestUser("workspace-owner", "Alice Owner", "13800000031")
            val observer = createTestUser("workspace-observer", "Bob Observer", "13800000032")
            val group = createGroupForTest("Workspace Contract Room", owner.id)
            assertTrue(GroupRepository.addUserToGroup(group.id, observer.id))
            WorkflowManager(workspace.workflowDir.absolutePath).createWorkflow(
                name = "Workspace Contract",
                description = "",
                userId = owner.id,
                groupId = group.id,
                agentType = "claude_code",
                taskFocus = "",
            )
            val manager = WorkspaceManager(workspace.workflowDir.absolutePath)
            val active = manager.createWorkspace(
                roomId = group.id,
                ownerId = owner.id,
                name = "Auth refactor",
                workingDir = "/private/alice/auth",
                visibility = WorkspaceVisibility.SHARED,
            )

            testApplication {
                application { module() }
                val ownerToken = JwtProvider.generateAccessToken(owner.id)
                val observerToken = JwtProvider.generateAccessToken(observer.id)

                val ownerList = client.get("/api/rooms/${group.id}/workspaces") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                }.decode<List<WorkspaceDto>>()
                assertEquals(1, ownerList.size)
                assertEquals("Alice Owner", ownerList.single().ownerDisplayName)
                assertEquals("/private/alice/auth", ownerList.single().workingDir)
                assertEquals("OWNER", ownerList.single().role)
                assertEquals("OFFLINE", ownerList.single().activity.state.name)

                val observerList = client.get("/api/rooms/${group.id}/workspaces") {
                    header(HttpHeaders.Authorization, "Bearer $observerToken")
                }.decode<List<WorkspaceDto>>()
                assertEquals(1, observerList.size)
                assertEquals("", observerList.single().workingDir)
                assertTrue(observerList.single().copilots.isEmpty())
                assertEquals("OBSERVER", observerList.single().role)

                val privateWithCopilot = client.patch(
                    "/api/rooms/${group.id}/workspaces/${active.workspaceId}"
                ) {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"visibility":"PRIVATE","copilots":["${observer.id}"]}""")
                }
                assertEquals(HttpStatusCode.BadRequest, privateWithCopilot.status)

                val deleteWhileActive = client.delete(
                    "/api/rooms/${group.id}/workspaces/${active.workspaceId}"
                ) {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                }
                assertEquals(HttpStatusCode.Conflict, deleteWhileActive.status)

                val observerArchive = client.patch(
                    "/api/rooms/${group.id}/workspaces/${active.workspaceId}"
                ) {
                    header(HttpHeaders.Authorization, "Bearer $observerToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"lifecycleState":"ARCHIVED"}""")
                }
                assertEquals(HttpStatusCode.NotFound, observerArchive.status)

                val archived = client.patch(
                    "/api/rooms/${group.id}/workspaces/${active.workspaceId}"
                ) {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"lifecycleState":"ARCHIVED"}""")
                }.decode<WorkspaceDto>()
                assertEquals(WorkspaceLifecycleState.ARCHIVED, archived.lifecycleState)

                val deleteArchived = client.delete(
                    "/api/rooms/${group.id}/workspaces/${active.workspaceId}"
                ) {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                }
                assertEquals(HttpStatusCode.NoContent, deleteArchived.status)
            }
        }
    }

    @Test
    fun `message recall route removes sender message from isolated history`() {
        TestWorkspace().use { workspace ->
            val callerId = "recall-owner"
            val group = createGroupForTest("Recall Route Group", callerId)
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
                    header(HttpHeaders.Authorization, "Bearer ${JwtProvider.generateAccessToken(callerId)}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            RecallMessageRequest(
                                groupId = group.id,
                                messageId = "msg-1",
                                userId = callerId
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
            val callerId = "other-user"
            val group = createGroupForTest("Recall Permission Group", callerId)
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
                    header(HttpHeaders.Authorization, "Bearer ${JwtProvider.generateAccessToken(callerId)}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            RecallMessageRequest(
                                groupId = group.id,
                                messageId = "msg-2",
                                userId = callerId
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
            val callerId = "silk-caller"
            val group = createGroupForTest("Recall Silk Group", callerId)
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
                    header(HttpHeaders.Authorization, "Bearer ${JwtProvider.generateAccessToken(callerId)}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            RecallMessageRequest(
                                groupId = group.id,
                                messageId = "user-msg",
                                userId = callerId
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

    /**
     * 创建测试用户（直接通过Repository，因为注册API已禁用）
     */
    private fun createTestUser(
        loginName: String,
        fullName: String,
        phoneNumber: String
    ): User {
        val passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt())
        return UserRepository.createUser(
            loginName = loginName,
            fullName = fullName,
            phoneNumber = phoneNumber,
            passwordHash = passwordHash
        ) ?: error("Failed to create test user: $loginName")
    }

    private fun createGroupForTest(groupName: String, hostId: String) =
        assertNotNull(GroupRepository.createGroup(groupName, hostId = hostId))

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
