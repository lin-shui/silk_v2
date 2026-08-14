package com.silk.backend

import com.silk.backend.agents.auth.AgentAuthProtocol
import com.silk.backend.agents.auth.AgentBindingApprovalRequest
import com.silk.backend.agents.auth.AgentBindingDto
import com.silk.backend.agents.auth.AgentBindingListResponse
import com.silk.backend.agents.auth.AgentBindingMessageScope
import com.silk.backend.agents.auth.AgentBindingStatus
import com.silk.backend.agents.auth.AgentBindingTargetType
import com.silk.backend.agents.auth.AgentInstanceStatus
import com.silk.backend.agents.auth.AgentPermission
import com.silk.backend.agents.auth.AgentTransportAdapter
import com.silk.backend.agents.auth.AgentTriggerPolicy
import com.silk.backend.agents.auth.CreateAgentBindingRequest
import com.silk.backend.agents.auth.DeviceEnrollmentStatus
import com.silk.backend.agents.auth.UpdateAgentBindingRequest
import com.silk.backend.auth.JwtProvider
import com.silk.backend.database.AgentDevices
import com.silk.backend.database.AgentInstances
import com.silk.backend.database.AgentBindingAuditEvents
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MemberRole
import com.silk.backend.database.UserRepository
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
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentBindingApprovalRouteContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `cross owner binding requires both approvals and target manager can revoke it`() {
        TestWorkspace().use {
            val agentOwner = createUser("binding-agent-owner", "Binding Agent Owner", "13800007781")
            val roomManager = createUser("binding-room-manager", "Binding Room Manager", "13800007782")
            val room = assertNotNull(GroupRepository.createGroup("Cross Owner Agent Binding", roomManager.id))
            assertTrue(GroupRepository.addUserToGroup(room.id, agentOwner.id, MemberRole.GUEST))
            val agentInstanceId = seedActiveAgent(agentOwner.id)
            val ownerToken = JwtProvider.generateAccessToken(agentOwner.id)
            val managerToken = JwtProvider.generateAccessToken(roomManager.id)

            testApplication {
                application { module() }
                val createRequest = CreateAgentBindingRequest(
                    agentInstanceId = agentInstanceId,
                    targetType = AgentBindingTargetType.ROOM,
                    targetId = room.id,
                    messageScope = AgentBindingMessageScope.TEAM,
                    triggerPolicy = AgentTriggerPolicy.MENTION,
                )
                val createdResponse = client.post("/api/agent-bindings") {
                    bearer(ownerToken)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(createRequest))
                }
                assertEquals(HttpStatusCode.Created, createdResponse.status)
                val created = createdResponse.decode<AgentBindingDto>()
                assertPendingOwnerRequest(created, agentOwner.id)

                val managerView = client.get("/api/agent-bindings") { bearer(managerToken) }
                    .decode<AgentBindingListResponse>().bindings.single { it.bindingId == created.bindingId }
                assertTrue(managerView.canApproveAsTargetManager)
                assertFalse(managerView.canApproveAsAgentOwner)

                approve(managerToken, created.bindingId).also {
                    assertEquals(AgentBindingStatus.ACTIVE, it.status)
                }
                val updated = update(ownerToken, created.bindingId, createRequest).decode<AgentBindingDto>()
                assertEquals(AgentBindingStatus.PENDING, updated.status)
                assertEquals(null, updated.targetApprovedBy)
                assertBindingActive(agentOwner.id, room.id, expected = false)

                approve(managerToken, created.bindingId).also {
                    assertEquals(AgentBindingStatus.ACTIVE, it.status)
                }
                assertBindingActive(agentOwner.id, room.id, expected = true)

                val revoked = client.delete("/api/agent-bindings/${created.bindingId}") { bearer(managerToken) }
                assertEquals(HttpStatusCode.OK, revoked.status)
                assertBindingActive(agentOwner.id, room.id, expected = false)

                val recreated = client.post("/api/agent-bindings") {
                    bearer(ownerToken)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(createRequest))
                }.decode<AgentBindingDto>()
                assertEquals(created.bindingId, recreated.bindingId)
                assertPendingOwnerRequest(recreated, agentOwner.id)

                val rejected = client.post("/api/agent-bindings/${recreated.bindingId}/approval") {
                    bearer(managerToken)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(AgentBindingApprovalRequest(approve = false)))
                }.decode<AgentBindingDto>()
                assertEquals(AgentBindingStatus.REVOKED, rejected.status)
                assertEquals(roomManager.id, rejected.revokedBy)

                val managerInitiated = client.post("/api/agent-bindings") {
                    bearer(managerToken)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(createRequest))
                }.decode<AgentBindingDto>()
                assertEquals(AgentBindingStatus.PENDING, managerInitiated.status)
                assertEquals(null, managerInitiated.agentOwnerApprovedBy)
                assertEquals(roomManager.id, managerInitiated.targetApprovedBy)
                val ownerView = client.get("/api/agent-bindings") { bearer(ownerToken) }
                    .decode<AgentBindingListResponse>().bindings.single { it.bindingId == managerInitiated.bindingId }
                assertTrue(ownerView.canApproveAsAgentOwner)
                approve(ownerToken, managerInitiated.bindingId).also {
                    assertEquals(AgentBindingStatus.ACTIVE, it.status)
                }
                val auditActions = transaction {
                    AgentBindingAuditEvents.select {
                        AgentBindingAuditEvents.bindingId eq created.bindingId
                    }.map { it[AgentBindingAuditEvents.action] }
                }
                assertTrue("REJECTED" in auditActions)
                assertTrue("PREVIOUS_REVISION" in auditActions)
                assertTrue("REOPENED" in auditActions)
            }
        }
    }

    @Test
    fun `room mention aliases are validated and unique across agent instances`() {
        TestWorkspace().use {
            val owner = createUser("binding-alias-owner", "Binding Alias Owner", "13800007801")
            val room = assertNotNull(GroupRepository.createGroup("Alias Room", owner.id))
            val firstAgent = seedActiveAgent(owner.id, "first")
            val secondAgent = seedActiveAgent(owner.id, "second")
            val token = JwtProvider.generateAccessToken(owner.id)

            testApplication {
                application { module() }
                fun request(agentInstanceId: String, alias: String) = CreateAgentBindingRequest(
                    agentInstanceId = agentInstanceId,
                    targetType = AgentBindingTargetType.ROOM,
                    targetId = room.id,
                    messageScope = AgentBindingMessageScope.TEAM,
                    triggerPolicy = AgentTriggerPolicy.MENTION,
                    mentionAlias = alias,
                )

                val created = client.post("/api/agent-bindings") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(request(firstAgent, "cc-one")))
                }
                assertEquals(HttpStatusCode.Created, created.status)
                assertEquals("cc-one", created.decode<AgentBindingDto>().mentionAlias)

                val duplicate = client.post("/api/agent-bindings") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(request(secondAgent, "cc-one")))
                }
                assertEquals(HttpStatusCode.Conflict, duplicate.status)
                assertTrue(duplicate.bodyAsText().contains("MENTION_ALIAS_TAKEN"))

                val invalid = client.post("/api/agent-bindings") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(request(secondAgent, "Silk")))
                }
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                assertTrue(invalid.bodyAsText().contains("INVALID_MENTION_ALIAS"))
            }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.approve(
        token: String,
        bindingId: String,
    ): AgentBindingDto = client.post("/api/agent-bindings/$bindingId/approval") {
        bearer(token)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(AgentBindingApprovalRequest(approve = true)))
    }.decode()

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.update(
        token: String,
        bindingId: String,
        request: CreateAgentBindingRequest,
    ): HttpResponse = client.put("/api/agent-bindings/$bindingId") {
        bearer(token)
        contentType(ContentType.Application.Json)
        setBody(
            json.encodeToString(
                UpdateAgentBindingRequest(
                    agentInstanceId = request.agentInstanceId,
                    targetType = request.targetType,
                    targetId = request.targetId,
                    messageScope = request.messageScope,
                    triggerPolicy = AgentTriggerPolicy.ALL,
                )
            )
        )
    }

    private fun assertPendingOwnerRequest(binding: AgentBindingDto, ownerId: String) {
        assertEquals(AgentBindingStatus.PENDING, binding.status)
        assertEquals(ownerId, binding.ownerId)
        assertEquals(ownerId, binding.agentOwnerApprovedBy)
        assertEquals(null, binding.targetApprovedBy)
        assertTrue(binding.canRevoke)
    }

    private fun assertBindingActive(userId: String, roomId: String, expected: Boolean) {
        val active = com.silk.backend.agents.auth.AgentAuthRepository.hasActiveAgentBinding(
            userId = userId,
            agentType = "codex",
            targetType = AgentBindingTargetType.ROOM,
            targetId = roomId,
            permission = AgentPermission.SEND_MESSAGE,
        )
        assertEquals(expected, active)
    }

    private fun createUser(loginName: String, fullName: String, phoneNumber: String) =
        requireNotNull(
            UserRepository.createUser(
                loginName = loginName,
                fullName = fullName,
                phoneNumber = phoneNumber,
                passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
            )
        )

    private fun seedActiveAgent(userId: String, suffix: String = "default"): String {
        val deviceId = "device-$userId-$suffix"
        val agentInstanceId = "agent-$userId-$suffix"
        val now = LocalDateTime.now()
        transaction {
            AgentDevices.insert { row ->
                row[AgentDevices.id] = deviceId
                row[AgentDevices.userId] = userId
                row[AgentDevices.publicKey] = "public-key-$userId-$suffix"
                row[AgentDevices.keyAlgorithm] = AgentAuthProtocol.KEY_ALGORITHM
                row[AgentDevices.fingerprint] = "fingerprint-$userId-$suffix"
                row[AgentDevices.displayName] = "Test Device"
                row[AgentDevices.status] = DeviceEnrollmentStatus.ACTIVE.name
                row[AgentDevices.platform] = "test"
                row[AgentDevices.authenticationOrigin] = "https://silk.example.com"
                row[AgentDevices.createdAt] = now
            }
            AgentInstances.insert { row ->
                row[AgentInstances.id] = agentInstanceId
                row[AgentInstances.userId] = userId
                row[AgentInstances.deviceId] = deviceId
                row[AgentInstances.agentType] = "codex"
                row[AgentInstances.transportAdapter] = AgentTransportAdapter.ACP.name
                row[AgentInstances.displayName] = "Test Codex"
                row[AgentInstances.connectorVersion] = "test"
                row[AgentInstances.capabilitiesJson] = "[\"PROMPT\"]"
                row[AgentInstances.status] = AgentInstanceStatus.ACTIVE.name
                row[AgentInstances.createdAt] = now
            }
        }
        return agentInstanceId
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())
}
