package com.silk.backend

import com.silk.backend.auth.JwtProvider
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.GroupResponse
import com.silk.backend.database.JoinGroupRequest
import com.silk.backend.database.User
import com.silk.backend.database.UserRepository
import com.silk.backend.workflow.WorkflowManager
import com.silk.backend.workspace.WorkspaceManager
import com.silk.backend.workspace.WorkspaceVisibility
import com.silk.shared.models.CreateRoomRequest
import com.silk.shared.models.CreateRoomResponse
import com.silk.shared.models.RenameRoomRequest
import com.silk.shared.models.RoomActionResponse
import com.silk.shared.models.RoomKind
import com.silk.shared.models.RoomSummaryDto
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomRoutesContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `unified room routes create and discover chat workflow and cc connect rooms`() {
        TestWorkspace().use { workspace ->
            testApplication {
                application { module() }

                val owner = createTestUser("unified-owner", "Unified Owner", "13800000013")
                val outsider = createTestUser("unified-outsider", "Unified Outsider", "13800000014")
                val ownerToken = JwtProvider.generateAccessToken(owner.id)
                val outsiderToken = JwtProvider.generateAccessToken(outsider.id)

                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/rooms/visible").status)

                val chat = client.post("/api/rooms") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(CreateRoomRequest("Product Chat", RoomKind.CHAT)))
                }.decode<CreateRoomResponse>()
                assertEquals(RoomKind.CHAT, chat.room.roomKind)
                assertTrue(chat.room.invitationCode.isNotBlank())
                assertTrue(chat.room.createdAtEpochMs > 0L)
                assertNull(chat.ccConnectToken)

                val workflow = client.post("/api/rooms") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(CreateRoomRequest("Project Alpha", RoomKind.WORKFLOW)))
                }.decode<CreateRoomResponse>()
                assertEquals(RoomKind.WORKFLOW, workflow.room.roomKind)
                assertNotNull(workflow.room.workflowId)
                assertEquals(RoomKind.WORKFLOW, GroupRepository.findGroupById(workflow.room.roomId)?.roomKind)
                assertTrue(
                    WorkspaceManager(workspace.workflowDir.absolutePath)
                        .listRoomWorkspaces(workflow.room.roomId)
                        .isEmpty()
                )

                val ccConnect = client.post("/api/rooms") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            CreateRoomRequest(
                                name = "Claude Automation",
                                roomKind = RoomKind.CHAT,
                                integrationType = "ccconnect",
                            )
                        )
                    )
                }.decode<CreateRoomResponse>()
                assertEquals(RoomKind.CHAT, ccConnect.room.roomKind)
                assertNotNull(ccConnect.ccConnectToken)

                assertTrue(GroupRepository.touchRoom(chat.room.roomId, 1_700_000_001_000L))
                assertTrue(GroupRepository.touchRoom(ccConnect.room.roomId, 1_700_000_002_000L))
                assertTrue(GroupRepository.touchRoom(workflow.room.roomId, 1_700_000_003_000L))
                assertTrue(GroupRepository.updateGroupName(chat.room.roomId, "Renamed Product Chat"))
                assertTrue(GroupRepository.updateGroupName(workflow.room.roomId, "Canonical Project Alpha"))

                val visible = client.get("/api/rooms/visible") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                }.decode<List<RoomSummaryDto>>()
                assertEquals(3, visible.size)
                assertEquals(
                    listOf(workflow.room.roomId, ccConnect.room.roomId, chat.room.roomId),
                    visible.map { it.roomId },
                )
                assertEquals(1_700_000_003_000L, visible.first().lastMessageAt)
                assertEquals("Canonical Project Alpha", visible.first().name)
                assertEquals(setOf(RoomKind.CHAT, RoomKind.WORKFLOW), visible.map { it.roomKind }.toSet())
                assertEquals("CC_CONNECT", visible.single { it.roomId == ccConnect.room.roomId }.integration?.type)

                val outsiderVisible = client.get("/api/rooms/visible") {
                    header(HttpHeaders.Authorization, "Bearer $outsiderToken")
                }.decode<List<RoomSummaryDto>>()
                assertTrue(outsiderVisible.isEmpty())

                val joinedWorkflow = client.post("/groups/join") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            JoinGroupRequest(outsider.id, workflow.room.invitationCode)
                        )
                    )
                }.decode<GroupResponse>()
                assertTrue(joinedWorkflow.success)
                assertEquals(workflow.room.roomId, joinedWorkflow.group?.id)

                val forbiddenSilk = client.post("/api/rooms") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(CreateRoomRequest("Fake Silk", RoomKind.SILK_PRIVATE)))
                }
                assertEquals(HttpStatusCode.Forbidden, forbiddenSilk.status)
            }
        }
    }

    @Test
    fun `unified room management enforces owner and member actions`() {
        TestWorkspace().use { workspace ->
            testApplication {
                application { module() }

                val owner = createTestUser("room-owner", "Room Owner", "13800000015")
                val member = createTestUser("room-member", "Room Member", "13800000016")
                val ownerToken = JwtProvider.generateAccessToken(owner.id)
                val memberToken = JwtProvider.generateAccessToken(member.id)

                val chat = client.post("/api/rooms") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(CreateRoomRequest("Manage Chat", RoomKind.CHAT)))
                }.decode<CreateRoomResponse>()
                assertTrue(
                    client.post("/groups/join") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(JoinGroupRequest(member.id, chat.room.invitationCode)))
                    }.decode<GroupResponse>().success
                )

                val forbiddenRename = client.put("/api/rooms/${chat.room.roomId}") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(RenameRoomRequest("Member Rename")))
                }
                assertEquals(HttpStatusCode.Forbidden, forbiddenRename.status)
                assertTrue(!forbiddenRename.decode<RoomActionResponse>().success)

                val renamed = client.put("/api/rooms/${chat.room.roomId}") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(RenameRoomRequest("Renamed Chat")))
                }.decode<RoomActionResponse>()
                assertTrue(renamed.success)
                assertEquals("Renamed Chat", renamed.room?.name)
                assertEquals("Renamed Chat", GroupRepository.findGroupById(chat.room.roomId)?.name)

                val ownerLeave = client.post("/api/rooms/${chat.room.roomId}/leave") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                assertEquals(HttpStatusCode.Conflict, ownerLeave.status)

                val forbiddenDelete = client.delete("/api/rooms/${chat.room.roomId}") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                assertEquals(HttpStatusCode.Forbidden, forbiddenDelete.status)

                val left = client.post("/api/rooms/${chat.room.roomId}/leave") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }.decode<RoomActionResponse>()
                assertTrue(left.success)
                assertTrue(!GroupRepository.isUserInGroup(chat.room.roomId, member.id))

                val workflow = client.post("/api/rooms") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(CreateRoomRequest("Managed Workflow", RoomKind.WORKFLOW)))
                }.decode<CreateRoomResponse>()
                assertTrue(
                    client.post("/groups/join") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(JoinGroupRequest(member.id, workflow.room.invitationCode)))
                    }.decode<GroupResponse>().success
                )
                val workspaceManager = WorkspaceManager(workspace.workflowDir.absolutePath)
                val memberWorkspace = workspaceManager.createWorkspace(
                    roomId = workflow.room.roomId,
                    ownerId = member.id,
                    name = "Member Shared Workspace",
                    visibility = WorkspaceVisibility.SHARED,
                )
                assertTrue(workspaceManager.updateCopilots(memberWorkspace.workspaceId, listOf(owner.id)))

                val renamedWorkflow = client.put("/api/rooms/${workflow.room.roomId}") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(RenameRoomRequest("Renamed Workflow")))
                }.decode<RoomActionResponse>()
                assertTrue(renamedWorkflow.success)
                assertEquals(
                    "Renamed Workflow",
                    WorkflowManager(workspace.workflowDir.absolutePath)
                        .getWorkflowByGroupId(workflow.room.roomId)
                        ?.name,
                )

                val removedWorkflowMember = client.delete(
                    "/api/workflows/${workflow.room.workflowId}/members/${member.id}"
                ) {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                }
                assertEquals(HttpStatusCode.OK, removedWorkflowMember.status)
                assertTrue(!GroupRepository.isUserInGroup(workflow.room.roomId, member.id))
                val privateWorkspace = workspaceManager.getWorkspace(memberWorkspace.workspaceId)
                assertEquals(WorkspaceVisibility.PRIVATE, privateWorkspace?.visibility)
                assertTrue(privateWorkspace?.copilots.orEmpty().isEmpty())

                val deleted = client.delete("/api/rooms/${workflow.room.roomId}") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }.decode<RoomActionResponse>()
                assertTrue(deleted.success)
                assertNull(GroupRepository.findGroupById(workflow.room.roomId))
                assertNull(
                    WorkflowManager(workspace.workflowDir.absolutePath)
                        .getWorkflowByGroupId(workflow.room.roomId)
                )
            }
        }
    }

    private fun createTestUser(loginName: String, fullName: String, phoneNumber: String): User {
        return UserRepository.createUser(
            loginName = loginName,
            fullName = fullName,
            phoneNumber = phoneNumber,
            passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
        ) ?: error("Failed to create test user: $loginName")
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())
}
