package com.silk.backend

import com.silk.backend.agents.auth.AgentAuthProtocol
import com.silk.backend.agents.auth.AgentAccessMode
import com.silk.backend.agents.auth.AgentAuthErrorResponse
import com.silk.backend.agents.auth.AgentBindingListResponse
import com.silk.backend.agents.auth.AgentBindingDto
import com.silk.backend.agents.auth.AgentBindingMessageScope
import com.silk.backend.agents.auth.AgentBindingTargetType
import com.silk.backend.agents.auth.AgentCapability
import com.silk.backend.agents.auth.AgentDeviceListResponse
import com.silk.backend.agents.auth.AgentInstanceListResponse
import com.silk.backend.agents.auth.AgentInstanceStatus
import com.silk.backend.agents.auth.AgentRuntimePermissionMode
import com.silk.backend.agents.auth.AgentRevocationCleanupResponse
import com.silk.backend.agents.auth.AgentSecurityEventAction
import com.silk.backend.agents.auth.AgentSecurityEventListResponse
import com.silk.backend.agents.auth.AgentPairingApprovalRequest
import com.silk.backend.agents.auth.AgentPairingApprovalResponse
import com.silk.backend.agents.auth.AgentPairingCodeRequest
import com.silk.backend.agents.auth.AgentPairingKind
import com.silk.backend.agents.auth.AgentPairingPreviewResponse
import com.silk.backend.agents.auth.AgentPairingState
import com.silk.backend.agents.auth.AgentPairingStatusResponse
import com.silk.backend.agents.auth.AgentPermission
import com.silk.backend.agents.auth.AgentSocketAuthenticated
import com.silk.backend.agents.auth.AgentSocketAuthenticate
import com.silk.backend.agents.auth.AgentSocketChallenge
import com.silk.backend.agents.auth.AgentSocketHello
import com.silk.backend.agents.auth.AgentSocketError
import com.silk.backend.agents.auth.AGENT_HOST_CONNECTION_MODE
import com.silk.backend.agents.auth.AgentHostOpen
import com.silk.backend.agents.auth.AgentHostOpened
import com.silk.backend.agents.auth.AgentHostRpc
import com.silk.backend.agents.auth.AgentTransportAdapter
import com.silk.backend.agents.auth.CompleteAgentPairingRequest
import com.silk.backend.agents.auth.CompleteAgentPairingResponse
import com.silk.backend.agents.auth.CreateAgentPairingRequest
import com.silk.backend.agents.auth.CreateAgentBindingRequest
import com.silk.backend.agents.auth.CreateAgentPairingResponse
import com.silk.backend.agents.auth.CreateTrustedDeviceAgentRequest
import com.silk.backend.agents.auth.UpdateAgentBindingRequest
import com.silk.backend.agents.auth.UpdateAgentRuntimePermissionModeRequest
import com.silk.backend.agents.auth.UpdateAgentResourceNameRequest
import com.silk.backend.agents.auth.DeviceEnrollmentStatus
import com.silk.backend.auth.JwtProvider
import com.silk.backend.database.CcSettingsResponse
import com.silk.backend.database.UserRepository
import com.silk.backend.database.GroupRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.mindrot.jbcrypt.BCrypt
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("LargeClass") // One end-to-end lifecycle keeps pairing, reconnect, multiplexing, and revocation state coupled.
class AgentAuthRouteContractTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `browser approval enrolls one unbound agent and enables signed reconnect and revocation`() {
        System.setProperty("silk.backendBaseUrl", "http://silk.test:8006")
        System.setProperty("silk.webAppBaseUrl", "http://silk.test:8005")
        try {
            TestWorkspace().use {
                val user = UserRepository.createUser(
                    loginName = "agent-owner",
                    fullName = "Agent Owner",
                    phoneNumber = "13800007771",
                    passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
                ) ?: error("Failed to create user")
                val accessToken = JwtProvider.generateAccessToken(user.id)
                val otherUser = UserRepository.createUser(
                    loginName = "agent-other",
                    fullName = "Agent Other",
                    phoneNumber = "13800007772",
                    passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
                ) ?: error("Failed to create second user")
                val otherAccessToken = JwtProvider.generateAccessToken(otherUser.id)
                val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                val publicKey = rawPublicKey(keyPair)

                testApplication {
                    application { module() }

                    val create = client.post("/api/agent-pairings") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.encodeToString(
                                CreateAgentPairingRequest(
                                    protocolVersion = AgentAuthProtocol.VERSION,
                                    keyAlgorithm = AgentAuthProtocol.KEY_ALGORITHM,
                                    publicKey = publicKey,
                                    accountLoginName = user.loginName,
                                    connectionOrigin = "http://agent-alias.test:18006",
                                    deviceName = "dev-server-01",
                                    platform = "linux",
                                    agentType = "codex",
                                    agentDisplayName = "Codex",
                                    transportAdapter = AgentTransportAdapter.ACP,
                                    connectorVersion = "0.1.0",
                                    capabilities = setOf(
                                        AgentCapability.PROMPT,
                                        AgentCapability.STREAM,
                                        AgentCapability.CANCEL,
                                    ),
                                )
                            )
                        )
                    }
                    assertEquals(HttpStatusCode.OK, create.status)
                    val created = create.decode<CreateAgentPairingResponse>()
                    assertEquals("http://silk.test:8006", created.serverOrigin)
                    assertEquals(
                        "http://silk.test:8005/device#code=${created.userCode}",
                        created.verificationUri,
                    )
                    assertEquals("ws://silk.test:8006/agent-connect", created.agentWebSocketUri)

                    val unauthorizedPreview = client.post("/api/agent-pairings/preview") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(AgentPairingCodeRequest(created.userCode)))
                    }
                    assertEquals(HttpStatusCode.Unauthorized, unauthorizedPreview.status)

                    val wrongAccountPreview = client.post("/api/agent-pairings/preview") {
                        bearer(otherAccessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(AgentPairingCodeRequest(created.userCode)))
                    }
                    assertEquals(HttpStatusCode.NotFound, wrongAccountPreview.status)

                    val wrongAccountApproval = client.post("/api/agent-pairings/approve") {
                        bearer(otherAccessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(AgentPairingApprovalRequest(created.userCode)))
                    }
                    assertEquals(HttpStatusCode.NotFound, wrongAccountApproval.status)

                    val stillPending = client.get("/api/agent-pairings/${created.pairingId}/status") {
                        header(AgentAuthProtocol.DEVICE_POLL_SECRET_HEADER, created.devicePollSecret)
                    }.decode<AgentPairingStatusResponse>()
                    assertEquals(AgentPairingState.USER_PENDING, stillPending.state)

                    val preview = client.post("/api/agent-pairings/preview") {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(AgentPairingCodeRequest(created.userCode)))
                    }.decode<AgentPairingPreviewResponse>()
                    assertEquals(AgentPairingKind.DEVICE_ENROLLMENT, preview.pairingKind)
                    assertEquals("dev-server-01", preview.deviceName)
                    assertEquals("codex", preview.agentType)
                    assertTrue(preview.publicKeyFingerprint.startsWith("SHA256:"))

                    val approved = client.post("/api/agent-pairings/approve") {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(AgentPairingApprovalRequest(created.userCode)))
                    }.decode<AgentPairingApprovalResponse>()
                    assertEquals(AgentPairingState.DEVICE_PROOF_PENDING, approved.state)

                    val wrongPoll = client.get("/api/agent-pairings/${created.pairingId}/status") {
                        header(AgentAuthProtocol.DEVICE_POLL_SECRET_HEADER, "wrong-secret")
                    }
                    assertEquals(HttpStatusCode.Unauthorized, wrongPoll.status)

                    val status = client.get("/api/agent-pairings/${created.pairingId}/status") {
                        header(AgentAuthProtocol.DEVICE_POLL_SECRET_HEADER, created.devicePollSecret)
                    }.decode<AgentPairingStatusResponse>()
                    val proof = assertNotNull(status.proofChallenge)
                    val proofTimestamp = System.currentTimeMillis()
                    val proofPayload = AgentAuthProtocol.canonicalPairingProof(
                        serverOrigin = proof.serverOrigin,
                        pairingId = created.pairingId,
                        challengeId = proof.challengeId,
                        nonce = proof.nonce,
                        deviceId = proof.deviceId,
                        agentInstanceId = proof.agentInstanceId,
                        publicKeyFingerprint = proof.publicKeyFingerprint,
                        timestampEpochMs = proofTimestamp,
                    )
                    val proofRequest = CompleteAgentPairingRequest(
                        challengeId = proof.challengeId,
                        timestampEpochMs = proofTimestamp,
                        signature = sign(keyPair, proofPayload),
                    )
                    val completedResponse = client.post("/api/agent-pairings/${created.pairingId}/proof") {
                        header(AgentAuthProtocol.DEVICE_POLL_SECRET_HEADER, created.devicePollSecret)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(proofRequest))
                    }
                    assertEquals(HttpStatusCode.OK, completedResponse.status)
                    val completed = completedResponse.decode<CompleteAgentPairingResponse>()
                    assertEquals(AgentPairingState.CONSUMED, completed.state)

                    val legacyToken = "retired-direct-bridge-token"
                    val legacySettings = client.get("/users/${user.id}/cc-settings") {
                        bearer(accessToken)
                    }.decode<CcSettingsResponse>()
                    assertTrue(legacySettings.success)
                    val retiredGeneration = client.post("/users/${user.id}/cc-settings/generate-token") {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                    assertEquals(HttpStatusCode.Gone, retiredGeneration.status)
                    val legacySession = createClient { install(WebSockets) }.webSocketSession {
                        url("/agent-bridge?agentType=codex&token=$legacyToken")
                    }
                    val legacyClose = withTimeout(5_000L) { legacySession.closeReason.await() }
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, legacyClose?.code)

                    val replay = client.post("/api/agent-pairings/${created.pairingId}/proof") {
                        header(AgentAuthProtocol.DEVICE_POLL_SECRET_HEADER, created.devicePollSecret)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(proofRequest))
                    }
                    assertEquals(HttpStatusCode.Conflict, replay.status)

                    val devices = client.get("/api/agent-devices") { bearer(accessToken) }
                        .decode<AgentDeviceListResponse>()
                    assertEquals(listOf(completed.deviceId), devices.devices.map { it.deviceId })
                    assertEquals(DeviceEnrollmentStatus.ACTIVE, devices.devices.single().status)
                    val agents = client.get("/api/agent-instances") { bearer(accessToken) }
                        .decode<AgentInstanceListResponse>()
                    assertEquals(listOf(completed.agentInstanceId), agents.agents.map { it.agentInstanceId })
                    assertEquals(AgentInstanceStatus.ACTIVE, agents.agents.single().status)
                    assertEquals(
                        AgentRuntimePermissionMode.NATIVE_DEFAULT,
                        agents.agents.single().runtimePermissionMode,
                    )

                    val otherCannotUpdateAgentPermission = client.put(
                        "/api/agent-instances/${completed.agentInstanceId}/permission-mode",
                    ) {
                        bearer(otherAccessToken)
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.encodeToString(
                                UpdateAgentRuntimePermissionModeRequest(AgentRuntimePermissionMode.AUTOMATIC),
                            ),
                        )
                    }
                    assertEquals(HttpStatusCode.NotFound, otherCannotUpdateAgentPermission.status)
                    val updateAgentPermission = client.put(
                        "/api/agent-instances/${completed.agentInstanceId}/permission-mode",
                    ) {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.encodeToString(
                                UpdateAgentRuntimePermissionModeRequest(AgentRuntimePermissionMode.AUTOMATIC),
                            ),
                        )
                    }
                    assertEquals(HttpStatusCode.OK, updateAgentPermission.status)
                    val agentsAfterPermissionUpdate = client.get("/api/agent-instances") { bearer(accessToken) }
                        .decode<AgentInstanceListResponse>()
                    assertEquals(
                        AgentRuntimePermissionMode.AUTOMATIC,
                        agentsAfterPermissionUpdate.agents.single().runtimePermissionMode,
                    )

                    val otherCannotRename = client.put("/api/agent-devices/${completed.deviceId}") {
                        bearer(otherAccessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(UpdateAgentResourceNameRequest("Other device")))
                    }
                    assertEquals(HttpStatusCode.NotFound, otherCannotRename.status)
                    val blankAgentName = client.put("/api/agent-instances/${completed.agentInstanceId}") {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(UpdateAgentResourceNameRequest("  ")))
                    }
                    assertEquals(HttpStatusCode.BadRequest, blankAgentName.status)
                    val renameDevice = client.put("/api/agent-devices/${completed.deviceId}") {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(UpdateAgentResourceNameRequest("Development laptop")))
                    }
                    assertEquals(HttpStatusCode.OK, renameDevice.status)
                    val renameAgent = client.put("/api/agent-instances/${completed.agentInstanceId}") {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(UpdateAgentResourceNameRequest("Codex on laptop")))
                    }
                    assertEquals(HttpStatusCode.OK, renameAgent.status)
                    val renamedDevices = client.get("/api/agent-devices") { bearer(accessToken) }
                        .decode<AgentDeviceListResponse>()
                    val renamedAgents = client.get("/api/agent-instances") { bearer(accessToken) }
                        .decode<AgentInstanceListResponse>()
                    assertEquals("Development laptop", renamedDevices.devices.single().displayName)
                    assertEquals("Codex on laptop", renamedAgents.agents.single().displayName)

                    val bindings = client.get("/api/agent-bindings") { bearer(accessToken) }
                        .decode<AgentBindingListResponse>()
                    assertTrue(bindings.bindings.isEmpty())

                    val additionCapabilities = setOf(
                        AgentCapability.PROMPT,
                        AgentCapability.STREAM,
                        AgentCapability.CANCEL,
                        AgentCapability.QUESTION_RESPONSE,
                    )
                    val tamperedRequestId = "trusted-device-request-tampered-001"
                    val tamperedTimestamp = System.currentTimeMillis()
                    val tamperedPayload = AgentAuthProtocol.canonicalTrustedDeviceAgentRequest(
                        serverOrigin = "http://silk.test:8006",
                        requestId = tamperedRequestId,
                        deviceId = completed.deviceId,
                        agentType = "claude-code",
                        agentDisplayName = "Claude Code",
                        transportAdapter = AgentTransportAdapter.ACP,
                        connectorVersion = "0.2.0",
                        capabilities = additionCapabilities,
                        timestampEpochMs = tamperedTimestamp,
                    )
                    val tampered = client.post("/api/agent-pairings/agents") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.encodeToString(
                                CreateTrustedDeviceAgentRequest(
                                    protocolVersion = AgentAuthProtocol.VERSION,
                                    deviceId = completed.deviceId,
                                    requestId = tamperedRequestId,
                                    timestampEpochMs = tamperedTimestamp,
                                    agentType = "claude-code",
                                    agentDisplayName = "Tampered Claude",
                                    transportAdapter = AgentTransportAdapter.ACP,
                                    connectorVersion = "0.2.0",
                                    capabilities = additionCapabilities,
                                    signature = sign(keyPair, tamperedPayload),
                                )
                            )
                        )
                    }
                    assertEquals(HttpStatusCode.Unauthorized, tampered.status)

                    val unknownDevice = client.post("/api/agent-pairings/agents") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.encodeToString(
                                CreateTrustedDeviceAgentRequest(
                                    protocolVersion = AgentAuthProtocol.VERSION,
                                    deviceId = "unknown-device-id",
                                    requestId = "unknown-device-request-000001",
                                    timestampEpochMs = System.currentTimeMillis(),
                                    agentType = "claude-code",
                                    agentDisplayName = "Unknown Device Claude",
                                    transportAdapter = AgentTransportAdapter.ACP,
                                    connectorVersion = "0.2.0",
                                    capabilities = additionCapabilities,
                                    verificationOrigin = "not-an-origin",
                                    signature = "invalid-signature",
                                )
                            )
                        )
                    }
                    assertEquals(HttpStatusCode.Unauthorized, unknownDevice.status)
                    assertEquals(
                        "INVALID_DEVICE_SIGNATURE",
                        unknownDevice.decode<AgentAuthErrorResponse>().error,
                    )

                    val additionRequestId = "trusted-device-request-valid-000001"
                    val additionTimestamp = System.currentTimeMillis()
                    val additionPayload = AgentAuthProtocol.canonicalTrustedDeviceAgentRequest(
                        serverOrigin = "http://silk.test:8006",
                        requestId = additionRequestId,
                        deviceId = completed.deviceId,
                        agentType = "claude-code",
                        agentDisplayName = "Claude Code",
                        transportAdapter = AgentTransportAdapter.ACP,
                        connectorVersion = "0.2.0",
                        capabilities = additionCapabilities,
                        timestampEpochMs = additionTimestamp,
                    )
                    val additionRequest = CreateTrustedDeviceAgentRequest(
                        protocolVersion = AgentAuthProtocol.VERSION,
                        deviceId = completed.deviceId,
                        requestId = additionRequestId,
                        timestampEpochMs = additionTimestamp,
                        agentType = "claude-code",
                        agentDisplayName = "Claude Code",
                        transportAdapter = AgentTransportAdapter.ACP,
                        connectorVersion = "0.2.0",
                        capabilities = additionCapabilities,
                        signature = sign(keyPair, additionPayload),
                    )
                    val additionCreated = client.post("/api/agent-pairings/agents") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(additionRequest))
                    }.decode<CreateAgentPairingResponse>()
                    assertEquals(
                        "http://silk.test:8005/device#code=${additionCreated.userCode}",
                        additionCreated.verificationUri,
                    )

                    // Both requests can be pending, but approving one must prevent the other
                    // from creating a second ACTIVE Agent of the same type on this device.
                    val competingRequestId = "trusted-device-request-competing-0001"
                    val competingTimestamp = System.currentTimeMillis()
                    val competingPayload = AgentAuthProtocol.canonicalTrustedDeviceAgentRequest(
                        serverOrigin = "http://silk.test:8006",
                        requestId = competingRequestId,
                        deviceId = completed.deviceId,
                        agentType = "claude-code",
                        agentDisplayName = "Competing Claude",
                        transportAdapter = AgentTransportAdapter.ACP,
                        connectorVersion = "0.2.0",
                        capabilities = additionCapabilities,
                        timestampEpochMs = competingTimestamp,
                    )
                    val competingCreated = client.post("/api/agent-pairings/agents") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.encodeToString(
                                CreateTrustedDeviceAgentRequest(
                                    protocolVersion = AgentAuthProtocol.VERSION,
                                    deviceId = completed.deviceId,
                                    requestId = competingRequestId,
                                    timestampEpochMs = competingTimestamp,
                                    agentType = "claude-code",
                                    agentDisplayName = "Competing Claude",
                                    transportAdapter = AgentTransportAdapter.ACP,
                                    connectorVersion = "0.2.0",
                                    capabilities = additionCapabilities,
                                    signature = sign(keyPair, competingPayload),
                                )
                            )
                        )
                    }.decode<CreateAgentPairingResponse>()

                    val wrongOwnerPreview = client.post("/api/agent-pairings/preview") {
                        bearer(otherAccessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(AgentPairingCodeRequest(additionCreated.userCode)))
                    }
                    assertEquals(HttpStatusCode.NotFound, wrongOwnerPreview.status)
                    val additionPreview = client.post("/api/agent-pairings/preview") {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(AgentPairingCodeRequest(additionCreated.userCode)))
                    }.decode<AgentPairingPreviewResponse>()
                    assertEquals(AgentPairingKind.ADD_AGENT, additionPreview.pairingKind)
                    assertEquals(completed.deviceId, additionPreview.deviceId)
                    assertEquals("claude-code", additionPreview.agentType)

                    val additionApproved = client.post("/api/agent-pairings/approve") {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(AgentPairingApprovalRequest(additionCreated.userCode)))
                    }.decode<AgentPairingApprovalResponse>()
                    assertEquals(AgentPairingState.CONSUMED, additionApproved.state)
                    val competingApproval = client.post("/api/agent-pairings/approve") {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(AgentPairingApprovalRequest(competingCreated.userCode)))
                    }
                    assertEquals(HttpStatusCode.Conflict, competingApproval.status)
                    assertEquals(
                        "AGENT_ALREADY_ENROLLED",
                        competingApproval.decode<AgentAuthErrorResponse>().error,
                    )
                    val additionStatus = client.get("/api/agent-pairings/${additionCreated.pairingId}/status") {
                        header(AgentAuthProtocol.DEVICE_POLL_SECRET_HEADER, additionCreated.devicePollSecret)
                    }.decode<AgentPairingStatusResponse>()
                    assertEquals(AgentPairingState.CONSUMED, additionStatus.state)
                    assertEquals(completed.deviceId, additionStatus.deviceId)
                    val addedAgentId = assertNotNull(additionStatus.agentInstanceId)

                    val additionReplay = client.post("/api/agent-pairings/agents") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(additionRequest))
                    }
                    assertEquals(HttpStatusCode.Conflict, additionReplay.status)
                    val agentsAfterAddition = client.get("/api/agent-instances") { bearer(accessToken) }
                        .decode<AgentInstanceListResponse>()
                    assertEquals(
                        setOf(completed.agentInstanceId, addedAgentId),
                        agentsAfterAddition.agents.map { it.agentInstanceId }.toSet(),
                    )

                    val room = assertNotNull(GroupRepository.createGroup("Agent Binding Room", user.id))
                    val binding = client.post("/api/agent-bindings") {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.encodeToString(
                                CreateAgentBindingRequest(
                                    agentInstanceId = completed.agentInstanceId,
                                    targetType = AgentBindingTargetType.ROOM,
                                    targetId = room.id,
                                    messageScope = AgentBindingMessageScope.TEAM,
                                    triggerPolicy = com.silk.backend.agents.auth.AgentTriggerPolicy.MENTION,
                                )
                            )
                        )
                    }
                    assertEquals(HttpStatusCode.Created, binding.status)
                    val createdBinding = binding.decode<AgentBindingDto>()
                    assertEquals(room.id, createdBinding.targetId)
                    assertEquals(AgentAccessMode.CHAT_ONLY, createdBinding.accessMode)
                    val updatedBinding = client.put("/api/agent-bindings/${createdBinding.bindingId}") {
                        bearer(accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.encodeToString(
                                UpdateAgentBindingRequest(
                                    agentInstanceId = addedAgentId,
                                    targetType = AgentBindingTargetType.ROOM,
                                    targetId = room.id,
                                    messageScope = AgentBindingMessageScope.TEAM,
                                    triggerPolicy = com.silk.backend.agents.auth.AgentTriggerPolicy.ALL,
                                    permissions = setOf(
                                        com.silk.backend.agents.auth.AgentPermission.READ_MESSAGE,
                                        com.silk.backend.agents.auth.AgentPermission.SEND_MESSAGE,
                                    ),
                                )
                            )
                        )
                    }
                    assertEquals(HttpStatusCode.OK, updatedBinding.status)
                    assertEquals(addedAgentId, updatedBinding.decode<AgentBindingDto>().agentInstanceId)
                    assertTrue(
                        com.silk.backend.agents.auth.AgentAuthRepository.hasActiveAgentBinding(
                            userId = user.id,
                            agentType = "claude-code",
                            targetType = AgentBindingTargetType.ROOM,
                            targetId = room.id,
                            permission = AgentPermission.SEND_MESSAGE,
                        )
                    )
                    val revokeBinding = client.delete("/api/agent-bindings/${createdBinding.bindingId}") {
                        bearer(accessToken)
                    }
                    assertEquals(HttpStatusCode.OK, revokeBinding.status)
                    assertFalse(
                        com.silk.backend.agents.auth.AgentAuthRepository.hasActiveAgentBinding(
                            userId = user.id,
                            agentType = "claude-code",
                            targetType = AgentBindingTargetType.ROOM,
                            targetId = room.id,
                            permission = AgentPermission.SEND_MESSAGE,
                        )
                    )

                    val hostSession = authenticateHostWebSocket(
                        httpClient = createClient { install(WebSockets) },
                        keyPair = keyPair,
                        deviceId = completed.deviceId,
                        authenticationAgentId = completed.agentInstanceId,
                    )
                    openHostAgent(
                        session = hostSession,
                        agentInstanceId = completed.agentInstanceId,
                        agentType = "codex",
                        refresh = true,
                        keyPair = keyPair,
                        deviceId = completed.deviceId,
                        serverOrigin = created.serverOrigin,
                    )
                    val refreshedAgents = client.get("/api/agent-instances") { bearer(accessToken) }
                        .decode<AgentInstanceListResponse>()
                    assertTrue(
                        refreshedAgents.agents.first { it.agentInstanceId == completed.agentInstanceId }
                            .capabilities.contains(AgentCapability.EXECUTION_POLICY_V2),
                    )
                    val legacyCapabilities = openHostAgent(hostSession, completed.agentInstanceId, "codex")
                    assertFalse(legacyCapabilities.contains(AgentCapability.EXECUTION_POLICY_V2))
                    openHostAgent(hostSession, addedAgentId, "claude-code")
                    val multiplexedAgents = client.get("/api/agent-instances") { bearer(accessToken) }
                        .decode<AgentInstanceListResponse>()
                    assertTrue(multiplexedAgents.agents.all { it.connected })
                    val replacementHostSession = authenticateHostWebSocket(
                        httpClient = createClient { install(WebSockets) },
                        keyPair = keyPair,
                        deviceId = completed.deviceId,
                        authenticationAgentId = addedAgentId,
                    )
                    openHostAgent(replacementHostSession, completed.agentInstanceId, "codex")
                    openHostAgent(replacementHostSession, addedAgentId, "claude-code")
                    val replacedHostClose = assertNotNull(
                        withTimeout(5_000L) { hostSession.closeReason.await() },
                    )
                    assertEquals(CloseReason.Codes.NORMAL.code, replacedHostClose.code)
                    val agentsAfterHostReplacement = client.get("/api/agent-instances") { bearer(accessToken) }
                        .decode<AgentInstanceListResponse>()
                    assertTrue(agentsAfterHostReplacement.agents.all { it.connected })
                    replacementHostSession.close()

                    val bridgeSession = authenticateBridgeWebSocket(
                        httpClient = createClient { install(WebSockets) },
                        keyPair = keyPair,
                        deviceId = completed.deviceId,
                        agentInstanceId = completed.agentInstanceId,
                        agentType = "codex",
                    )
                    val addedBridgeSession = authenticateBridgeWebSocket(
                        httpClient = createClient { install(WebSockets) },
                        keyPair = keyPair,
                        deviceId = completed.deviceId,
                        agentInstanceId = addedAgentId,
                        agentType = "claude-code",
                    )

                    val connectedDevices = client.get("/api/agent-devices") { bearer(accessToken) }
                        .decode<AgentDeviceListResponse>()
                    assertTrue(connectedDevices.devices.single().connected)
                    val connectedAgents = client.get("/api/agent-instances") { bearer(accessToken) }
                        .decode<AgentInstanceListResponse>()
                    assertTrue(connectedAgents.agents.all { it.connected })

                    authenticateWebSocket(
                        httpClient = createClient { install(WebSockets) },
                        keyPair = keyPair,
                        deviceId = completed.deviceId,
                        agentInstanceId = completed.agentInstanceId,
                    )

                    val revokeAgent = client.delete("/api/agent-instances/${completed.agentInstanceId}") {
                        bearer(accessToken)
                    }
                    assertEquals(HttpStatusCode.OK, revokeAgent.status)
                    val bridgeCloseReason = assertNotNull(
                        withTimeout(5_000L) { bridgeSession.closeReason.await() },
                    )
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, bridgeCloseReason.code)
                    assertEquals("agent revoked", bridgeCloseReason.message)
                    val afterAgentRevoke = client.get("/api/agent-instances") { bearer(accessToken) }
                        .decode<AgentInstanceListResponse>()
                    val revokedAgent = afterAgentRevoke.agents.first { it.agentInstanceId == completed.agentInstanceId }
                    val survivingAgent = afterAgentRevoke.agents.first { it.agentInstanceId == addedAgentId }
                    assertEquals(AgentInstanceStatus.REVOKED, revokedAgent.status)
                    assertEquals(false, revokedAgent.connected)
                    assertEquals(AgentInstanceStatus.ACTIVE, survivingAgent.status)
                    assertTrue(survivingAgent.connected)
                    val deviceAfterAgentRevoke = client.get("/api/agent-devices") { bearer(accessToken) }
                        .decode<AgentDeviceListResponse>()
                    assertEquals(DeviceEnrollmentStatus.ACTIVE, deviceAfterAgentRevoke.devices.single().status)

                    val rejectedSession = createClient { install(WebSockets) }.webSocketSession {
                        url("/agent-connect")
                    }
                    rejectedSession.send(
                        Frame.Text(
                            json.encodeToString(
                                AgentSocketHello(
                                    protocolVersion = AgentAuthProtocol.VERSION,
                                    deviceId = completed.deviceId,
                                    agentInstanceId = completed.agentInstanceId,
                                )
                            )
                        )
                    )
                    val revokedError = json.decodeFromString<AgentSocketError>(rejectedSession.receiveText())
                    assertEquals("DEVICE_OR_AGENT_REVOKED", revokedError.error)

                    val revokeDevice = client.delete("/api/agent-devices/${completed.deviceId}") {
                        bearer(accessToken)
                    }
                    assertEquals(HttpStatusCode.OK, revokeDevice.status)
                    val addedBridgeCloseReason = assertNotNull(
                        withTimeout(5_000L) { addedBridgeSession.closeReason.await() },
                    )
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, addedBridgeCloseReason.code)
                    assertEquals("device revoked", addedBridgeCloseReason.message)
                    val afterDeviceRevoke = client.get("/api/agent-devices") { bearer(accessToken) }
                        .decode<AgentDeviceListResponse>()
                    assertEquals(DeviceEnrollmentStatus.REVOKED, afterDeviceRevoke.devices.single().status)
                    val cleanup = client.post("/api/agent-revocation-history/cleanup") {
                        bearer(accessToken)
                    }
                    assertEquals(HttpStatusCode.OK, cleanup.status)
                    val cleanupResult = cleanup.decode<AgentRevocationCleanupResponse>()
                    assertEquals(90L, cleanupResult.retentionDays)
                    assertEquals(0, cleanupResult.deletedDevices)
                    assertEquals(0, cleanupResult.deletedAgents)
                    val securityEvents = client.get("/api/agent-security-events") { bearer(accessToken) }
                        .decode<AgentSecurityEventListResponse>()
                    assertTrue(securityEvents.events.any { it.action == AgentSecurityEventAction.DEVICE_ENROLLED })
                    assertTrue(securityEvents.events.any { it.action == AgentSecurityEventAction.AGENT_ENROLLED })
                    assertTrue(securityEvents.events.any { it.action == AgentSecurityEventAction.AGENT_REVOKED })
                    assertTrue(securityEvents.events.any { it.action == AgentSecurityEventAction.DEVICE_REVOKED })
                    assertTrue(
                        securityEvents.events.any {
                            it.action == AgentSecurityEventAction.AGENT_RUNTIME_PERMISSION_CHANGED
                        },
                    )
                }
            }
        } finally {
            System.clearProperty("silk.backendBaseUrl")
            System.clearProperty("silk.webAppBaseUrl")
        }
    }

    private suspend fun authenticateWebSocket(
        httpClient: HttpClient,
        keyPair: KeyPair,
        deviceId: String,
        agentInstanceId: String,
    ) {
        val session = httpClient.webSocketSession { url("/agent-connect") }
        session.send(
            Frame.Text(
                json.encodeToString(
                    AgentSocketHello(
                        protocolVersion = AgentAuthProtocol.VERSION,
                        deviceId = deviceId,
                        agentInstanceId = agentInstanceId,
                    )
                )
            )
        )
        val challenge = json.decodeFromString<AgentSocketChallenge>(session.receiveText())
        val timestamp = System.currentTimeMillis()
        val payload = AgentAuthProtocol.canonicalAgentAuthentication(
            serverOrigin = challenge.serverOrigin,
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            deviceId = deviceId,
            agentInstanceId = agentInstanceId,
            timestampEpochMs = timestamp,
        )
        session.send(
            Frame.Text(
                json.encodeToString(
                    AgentSocketAuthenticate(
                        protocolVersion = AgentAuthProtocol.VERSION,
                        challengeId = challenge.challengeId,
                        deviceId = deviceId,
                        agentInstanceId = agentInstanceId,
                        timestampEpochMs = timestamp,
                        signature = sign(keyPair, payload),
                    )
                )
            )
        )
        val authenticated = json.decodeFromString<AgentSocketAuthenticated>(session.receiveText())
        assertEquals(deviceId, authenticated.deviceId)
        assertEquals(agentInstanceId, authenticated.agentInstanceId)
        assertEquals(
            setOf(
                AgentCapability.PROMPT,
                AgentCapability.STREAM,
                AgentCapability.CANCEL,
                AgentCapability.EXECUTION_POLICY_V2,
            ),
            authenticated.capabilities,
        )
        session.close()
    }

    private suspend fun authenticateBridgeWebSocket(
        httpClient: HttpClient,
        keyPair: KeyPair,
        deviceId: String,
        agentInstanceId: String,
        agentType: String,
    ): DefaultClientWebSocketSession {
        val session = httpClient.webSocketSession { url("/agent-bridge?agentType=$agentType") }
        session.send(
            Frame.Text(
                json.encodeToString(
                    AgentSocketHello(
                        protocolVersion = AgentAuthProtocol.VERSION,
                        deviceId = deviceId,
                        agentInstanceId = agentInstanceId,
                    ),
                ),
            ),
        )
        val challenge = json.decodeFromString<AgentSocketChallenge>(session.receiveText())
        val timestamp = challenge.serverTimeEpochMs
        val payload = AgentAuthProtocol.canonicalAgentAuthentication(
            serverOrigin = challenge.serverOrigin,
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            deviceId = deviceId,
            agentInstanceId = agentInstanceId,
            timestampEpochMs = timestamp,
        )
        session.send(
            Frame.Text(
                json.encodeToString(
                    AgentSocketAuthenticate(
                        protocolVersion = AgentAuthProtocol.VERSION,
                        challengeId = challenge.challengeId,
                        deviceId = deviceId,
                        agentInstanceId = agentInstanceId,
                        timestampEpochMs = timestamp,
                        signature = sign(keyPair, payload),
                    ),
                ),
            ),
        )
        val authenticated = json.decodeFromString<AgentSocketAuthenticated>(session.receiveText())
        assertEquals(agentInstanceId, authenticated.agentInstanceId)

        val initialize = json.parseToJsonElement(session.receiveText()).jsonObject
        assertEquals("initialize", initialize["method"]?.jsonPrimitive?.content)
        val requestId = assertNotNull(initialize["id"]?.jsonPrimitive?.long)
        session.send(
            Frame.Text(
                """{"jsonrpc":"2.0","id":$requestId,"result":{"protocolVersion":"0.2","agentCapabilities":{}}}""",
            ),
        )
        return session
    }

    private suspend fun authenticateHostWebSocket(
        httpClient: HttpClient,
        keyPair: KeyPair,
        deviceId: String,
        authenticationAgentId: String,
    ): DefaultClientWebSocketSession {
        val session = httpClient.webSocketSession { url("/agent-connect") }
        session.send(
            Frame.Text(
                json.encodeToString(
                    AgentSocketHello(
                        protocolVersion = AgentAuthProtocol.VERSION,
                        deviceId = deviceId,
                        agentInstanceId = authenticationAgentId,
                        connectionMode = AGENT_HOST_CONNECTION_MODE,
                    ),
                ),
            ),
        )
        val challenge = json.decodeFromString<AgentSocketChallenge>(session.receiveText())
        val timestamp = challenge.serverTimeEpochMs
        val payload = AgentAuthProtocol.canonicalAgentAuthentication(
            serverOrigin = challenge.serverOrigin,
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            deviceId = deviceId,
            agentInstanceId = authenticationAgentId,
            timestampEpochMs = timestamp,
        )
        session.send(
            Frame.Text(
                json.encodeToString(
                    AgentSocketAuthenticate(
                        protocolVersion = AgentAuthProtocol.VERSION,
                        challengeId = challenge.challengeId,
                        deviceId = deviceId,
                        agentInstanceId = authenticationAgentId,
                        timestampEpochMs = timestamp,
                        signature = sign(keyPair, payload),
                    ),
                ),
            ),
        )
        val authenticated = json.decodeFromString<AgentSocketAuthenticated>(session.receiveText())
        assertEquals(AGENT_HOST_CONNECTION_MODE, authenticated.connectionMode)
        return session
    }

    private suspend fun openHostAgent(
        session: DefaultClientWebSocketSession,
        agentInstanceId: String,
        agentType: String,
        refresh: Boolean = false,
        keyPair: KeyPair? = null,
        deviceId: String? = null,
        serverOrigin: String? = null,
    ): Set<AgentCapability> {
        val open = if (refresh) {
            val signingKey = requireNotNull(keyPair)
            val signingDeviceId = requireNotNull(deviceId)
            val signingOrigin = requireNotNull(serverOrigin)
            val capabilities = setOf(
                AgentCapability.PROMPT,
                AgentCapability.STREAM,
                AgentCapability.CANCEL,
                AgentCapability.EXECUTION_POLICY_V1,
                AgentCapability.EXECUTION_POLICY_V2,
            )
            val timestamp = System.currentTimeMillis()
            val payload = AgentAuthProtocol.canonicalAgentCapabilityRefresh(
                serverOrigin = signingOrigin,
                deviceId = signingDeviceId,
                agentInstanceId = agentInstanceId,
                agentType = agentType,
                connectorVersion = "0.4.12",
                capabilities = capabilities,
                timestampEpochMs = timestamp,
            )
            AgentHostOpen(
                agentInstanceId = agentInstanceId,
                agentType = agentType,
                connectorVersion = "0.4.12",
                capabilities = capabilities,
                timestampEpochMs = timestamp,
                signature = sign(signingKey, payload),
            )
        } else {
            AgentHostOpen(agentInstanceId = agentInstanceId, agentType = agentType)
        }
        session.send(
            Frame.Text(
                json.encodeToString(
                    open,
                ),
            ),
        )
        val initializeEnvelope = json.decodeFromString<AgentHostRpc>(session.receiveText())
        assertEquals(agentInstanceId, initializeEnvelope.agentInstanceId)
        assertEquals("initialize", initializeEnvelope.payload["method"]?.jsonPrimitive?.content)
        val requestId = assertNotNull(initializeEnvelope.payload["id"]?.jsonPrimitive?.long)
        val responsePayload = json.parseToJsonElement(
            """{"jsonrpc":"2.0","id":$requestId,"result":{"protocolVersion":"0.2","agentCapabilities":{}}}""",
        ).jsonObject
        session.send(
            Frame.Text(
                json.encodeToString(
                    AgentHostRpc(agentInstanceId = agentInstanceId, payload = responsePayload),
                ),
            ),
        )
        val opened = json.decodeFromString<AgentHostOpened>(session.receiveText())
        assertEquals(agentInstanceId, opened.agentInstanceId)
        assertEquals(agentType, opened.agentType)
        return opened.capabilities
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveText(): String {
        val frame = withTimeout(5_000L) { incoming.receive() }
        return (frame as Frame.Text).readText()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun rawPublicKey(keyPair: KeyPair): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(keyPair.public.encoded.takeLast(32).toByteArray())

    private fun sign(keyPair: KeyPair, payload: ByteArray): String {
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())
}
