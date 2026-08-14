package com.silk.backend

import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.acp.InMemoryAcpTransport
import com.silk.backend.agents.adapters.codex.CodexDescriptor
import com.silk.backend.agents.auth.AgentAuthProtocol
import com.silk.backend.agents.auth.AgentBindingMessageScope
import com.silk.backend.agents.auth.AgentBindingStatus
import com.silk.backend.agents.auth.AgentBindingTargetType
import com.silk.backend.agents.auth.AgentCapability
import com.silk.backend.agents.auth.AgentInstanceStatus
import com.silk.backend.agents.auth.AgentTransportAdapter
import com.silk.backend.agents.auth.AgentTriggerPolicy
import com.silk.backend.agents.auth.DeviceEnrollmentStatus
import com.silk.backend.agents.core.AgentRegistry
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.database.AgentBindings
import com.silk.backend.database.AgentDevices
import com.silk.backend.database.AgentInstances
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MemberRole
import com.silk.backend.database.UserRepository
import com.silk.shared.models.RoomKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentBindingTeamRoutingContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun cleanupRuntime() {
        AgentRuntime.clearForTest()
        AcpRegistry.clearForTest()
    }

    @Test
    fun `active room mention binding forwards a team prompt to its exact agent`() = runBlocking {
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            TestWorkspace().use {
                AgentRegistry.register(CodexDescriptor)
                val owner = createUser("team-agent-owner", "Team Agent Owner", "13800007793")
                val sender = createUser("team-agent-sender", "Team Agent Sender", "13800007794")
                val room = GroupRepository.createGroup("Bound TEAM Agent", owner.id, RoomKind.CHAT)
                    ?: error("failed to create room")
                assertTrue(GroupRepository.addUserToGroup(room.id, sender.id, MemberRole.GUEST))
                val agentId = seedAgentAndBinding(owner.id, room.id)
                val transport = InMemoryAcpTransport()
                AcpRegistry.put(
                    userId = owner.id,
                    agentType = "codex",
                    client = AcpClient(transport, clientScope),
                    remoteIp = "127.0.0.1",
                    authenticationMode = AcpRegistry.AuthenticationMode.DEVICE_SIGNATURE,
                    agentInstanceId = agentId,
                    capabilities = setOf(
                        AgentCapability.PROMPT,
                        AgentCapability.EXECUTION_POLICY_V1,
                    ),
                )

                val server = ChatServer("group_${room.id}", roomKind = RoomKind.CHAT)
                server.broadcast(
                    Message(
                        id = "team-prompt",
                        userId = sender.id,
                        userName = sender.fullName,
                        content = "@codex hello team",
                        timestamp = System.currentTimeMillis(),
                        scope = MessageScope.TEAM,
                    )
                )

                val newRequest = json.parseToJsonElement(withTimeout(2_000) { transport.readClientSent() }).jsonObject
                assertEquals("session/new", newRequest["method"]?.jsonPrimitive?.content)
                assertEquals("", newRequest["params"]!!.jsonObject["cwd"]?.jsonPrimitive?.content)
                transport.pushFromServer(
                    """{"jsonrpc":"2.0","id":${newRequest["id"]!!.jsonPrimitive.long},"result":{"sessionId":"team-session"}}"""
                )
                val promptRequest = json.parseToJsonElement(withTimeout(2_000) { transport.readClientSent() }).jsonObject
                assertEquals("session/prompt", promptRequest["method"]?.jsonPrimitive?.content)
                val promptParams = promptRequest["params"]!!.jsonObject
                val promptBlocks = promptParams["prompt"]!!.jsonArray
                assertTrue(promptBlocks.any { it.jsonObject["text"]?.jsonPrimitive?.content == "hello team" })
                val executionPolicy = promptParams["_silk"]!!.jsonObject["executionPolicy"]!!.jsonObject
                assertEquals("false", executionPolicy["readFile"]!!.jsonPrimitive.content)
                assertEquals("false", executionPolicy["writeFile"]!!.jsonPrimitive.content)
                assertEquals("false", executionPolicy["runCommand"]!!.jsonPrimitive.content)
                transport.pushFromServer(
                    """{"jsonrpc":"2.0","id":${promptRequest["id"]!!.jsonPrimitive.long},"result":{"stopReason":"end_turn"}}"""
                )
                withTimeout(2_000) {
                    while (AgentRuntime.snapshotState(owner.id, "room:${room.id}")?.running == true) delay(10)
                }
            }
        } finally {
            clientScope.cancel()
        }
    }

    @Test
    fun `team new resets the bound session without forwarding a vendor prompt`() = runBlocking {
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            TestWorkspace().use {
                AgentRegistry.register(CodexDescriptor)
                val owner = createUser("team-new-owner", "Team New Owner", "13800007797")
                val sender = createUser("team-new-sender", "Team New Sender", "13800007798")
                val room = GroupRepository.createGroup("Bound TEAM New", owner.id, RoomKind.CHAT)
                    ?: error("failed to create room")
                assertTrue(GroupRepository.addUserToGroup(room.id, sender.id, MemberRole.GUEST))
                val agentId = seedAgentAndBinding(owner.id, room.id)
                val transport = InMemoryAcpTransport()
                AcpRegistry.put(
                    userId = owner.id,
                    agentType = "codex",
                    client = AcpClient(transport, clientScope),
                    remoteIp = "127.0.0.1",
                    authenticationMode = AcpRegistry.AuthenticationMode.DEVICE_SIGNATURE,
                    agentInstanceId = agentId,
                    capabilities = setOf(
                        AgentCapability.PROMPT,
                        AgentCapability.EXECUTION_POLICY_V1,
                    ),
                )

                val server = ChatServer("group_${room.id}", roomKind = RoomKind.CHAT)
                server.broadcast(
                    Message(
                        id = "team-new",
                        userId = sender.id,
                        userName = sender.fullName,
                        content = "@codex /new",
                        timestamp = System.currentTimeMillis(),
                        scope = MessageScope.TEAM,
                    )
                )

                assertNull(withTimeoutOrNull(250) { transport.readClientSent() })
                val state = AgentRuntime.snapshotState(owner.id, "room:${room.id}")
                assertNotNull(state)
                assertEquals("codex", state.agentType)
                assertFalse(state.running)
            }
        } finally {
            clientScope.cancel()
        }
    }

    @Test
    fun `team stop cancels the running bound agent session`() = runBlocking {
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            TestWorkspace().use {
                AgentRegistry.register(CodexDescriptor)
                val owner = createUser("team-cancel-owner", "Team Cancel Owner", "13800007795")
                val sender = createUser("team-cancel-sender", "Team Cancel Sender", "13800007796")
                val room = GroupRepository.createGroup("Bound TEAM Cancel", owner.id, RoomKind.CHAT)
                    ?: error("failed to create room")
                assertTrue(GroupRepository.addUserToGroup(room.id, sender.id, MemberRole.GUEST))
                val agentId = seedAgentAndBinding(owner.id, room.id)
                val transport = InMemoryAcpTransport()
                AcpRegistry.put(
                    userId = owner.id,
                    agentType = "codex",
                    client = AcpClient(transport, clientScope),
                    remoteIp = "127.0.0.1",
                    authenticationMode = AcpRegistry.AuthenticationMode.DEVICE_SIGNATURE,
                    agentInstanceId = agentId,
                    capabilities = setOf(
                        AgentCapability.PROMPT,
                        AgentCapability.CANCEL,
                        AgentCapability.EXECUTION_POLICY_V1,
                    ),
                )

                val server = ChatServer("group_${room.id}", roomKind = RoomKind.CHAT)
                server.broadcast(
                    Message(
                        id = "team-cancel-prompt",
                        userId = sender.id,
                        userName = sender.fullName,
                        content = "@codex keep working",
                        timestamp = System.currentTimeMillis(),
                        scope = MessageScope.TEAM,
                    )
                )

                val newRequest = json.parseToJsonElement(withTimeout(2_000) { transport.readClientSent() }).jsonObject
                transport.pushFromServer(
                    """{"jsonrpc":"2.0","id":${newRequest["id"]!!.jsonPrimitive.long},"result":{"sessionId":"team-cancel-session"}}"""
                )
                val promptRequest = json.parseToJsonElement(withTimeout(2_000) { transport.readClientSent() }).jsonObject
                assertEquals("session/prompt", promptRequest["method"]?.jsonPrimitive?.content)

                server.broadcast(
                    Message(
                        id = "team-stop",
                        userId = sender.id,
                        userName = sender.fullName,
                        content = "",
                        timestamp = System.currentTimeMillis(),
                        type = MessageType.STOP_GENERATE,
                        scope = MessageScope.TEAM,
                    )
                )

                val cancel = json.parseToJsonElement(withTimeout(2_000) { transport.readClientSent() }).jsonObject
                assertEquals("session/cancel", cancel["method"]?.jsonPrimitive?.content)
                assertEquals(
                    "team-cancel-session",
                    cancel["params"]?.jsonObject?.get("sessionId")?.jsonPrimitive?.content,
                )
                withTimeout(2_000) {
                    while (AgentRuntime.snapshotState(owner.id, "room:${room.id}")?.running == true) delay(10)
                }
            }
        } finally {
            clientScope.cancel()
        }
    }

    @Test
    fun `same-type bindings route distinct mentions to distinct agent instances`() = runBlocking {
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            TestWorkspace().use {
                AgentRegistry.register(CodexDescriptor)
                val owner = createUser("team-multi-owner", "Team Multi Owner", "13800007799")
                val sender = createUser("team-multi-sender", "Team Multi Sender", "13800007800")
                val room = GroupRepository.createGroup("Bound TEAM Multi", owner.id, RoomKind.CHAT)
                    ?: error("failed to create room")
                assertTrue(GroupRepository.addUserToGroup(room.id, sender.id, MemberRole.GUEST))
                val linuxAgent = seedAgentAndBinding(owner.id, room.id, "linux", "codex-linux")
                val windowsAgent = seedAgentAndBinding(owner.id, room.id, "windows", "codex-windows")
                val linuxTransport = InMemoryAcpTransport()
                val windowsTransport = InMemoryAcpTransport()
                AcpRegistry.put(
                    userId = owner.id,
                    agentType = "codex",
                    client = AcpClient(linuxTransport, clientScope),
                    remoteIp = "10.0.0.1",
                    agentInstanceId = linuxAgent,
                    capabilities = setOf(AgentCapability.PROMPT, AgentCapability.EXECUTION_POLICY_V1),
                )
                AcpRegistry.put(
                    userId = owner.id,
                    agentType = "codex",
                    client = AcpClient(windowsTransport, clientScope),
                    remoteIp = "10.0.0.2",
                    agentInstanceId = windowsAgent,
                    capabilities = setOf(AgentCapability.PROMPT, AgentCapability.EXECUTION_POLICY_V1),
                )

                val server = ChatServer("group_${room.id}", roomKind = RoomKind.CHAT)
                server.broadcast(
                    Message(
                        id = "team-multi-linux",
                        userId = sender.id,
                        userName = sender.fullName,
                        content = "@codex-linux only linux",
                        timestamp = System.currentTimeMillis(),
                        scope = MessageScope.TEAM,
                    )
                )

                val linuxNew = json.parseToJsonElement(withTimeout(2_000) { linuxTransport.readClientSent() }).jsonObject
                assertEquals("session/new", linuxNew["method"]?.jsonPrimitive?.content)
                assertNull(withTimeoutOrNull(250) { windowsTransport.readClientSent() })
                linuxTransport.pushFromServer(
                    """{"jsonrpc":"2.0","id":${linuxNew["id"]!!.jsonPrimitive.long},"result":{"sessionId":"linux-session"}}"""
                )
                val linuxPrompt = json.parseToJsonElement(withTimeout(2_000) { linuxTransport.readClientSent() }).jsonObject
                assertEquals("session/prompt", linuxPrompt["method"]?.jsonPrimitive?.content)
                assertTrue(
                    linuxPrompt["params"]!!.jsonObject["prompt"]!!.jsonArray.any {
                        it.jsonObject["text"]?.jsonPrimitive?.content == "only linux"
                    }
                )
                linuxTransport.pushFromServer(
                    """{"jsonrpc":"2.0","id":${linuxPrompt["id"]!!.jsonPrimitive.long},"result":{"stopReason":"end_turn"}}"""
                )
            }
        } finally {
            clientScope.cancel()
        }
    }

    private fun createUser(loginName: String, fullName: String, phoneNumber: String) =
        UserRepository.createUser(
            loginName = loginName,
            fullName = fullName,
            phoneNumber = phoneNumber,
            passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
        ) ?: error("failed to create user")

    private fun seedAgentAndBinding(
        ownerId: String,
        roomId: String,
        suffix: String = "default",
        mentionAlias: String = "",
    ): String {
        val now = LocalDateTime.now()
        val deviceId = "team-device-$suffix"
        val agentId = "team-agent-$suffix"
        transaction {
            AgentDevices.insert { row ->
                row[AgentDevices.id] = deviceId
                row[AgentDevices.userId] = ownerId
                row[AgentDevices.publicKey] = "team-public-key-$suffix"
                row[AgentDevices.keyAlgorithm] = AgentAuthProtocol.KEY_ALGORITHM
                row[AgentDevices.fingerprint] = "team-fingerprint-$suffix"
                row[AgentDevices.displayName] = "Team Device $suffix"
                row[AgentDevices.status] = DeviceEnrollmentStatus.ACTIVE.name
                row[AgentDevices.platform] = "test"
                row[AgentDevices.authenticationOrigin] = "https://silk.example.com"
                row[AgentDevices.createdAt] = now
            }
            AgentInstances.insert { row ->
                row[AgentInstances.id] = agentId
                row[AgentInstances.userId] = ownerId
                row[AgentInstances.deviceId] = deviceId
                row[AgentInstances.agentType] = "codex"
                row[AgentInstances.transportAdapter] = AgentTransportAdapter.ACP.name
                row[AgentInstances.displayName] = "Team Codex $suffix"
                row[AgentInstances.connectorVersion] = "test"
                row[AgentInstances.capabilitiesJson] = "[\"PROMPT\",\"EXECUTION_POLICY_V1\"]"
                row[AgentInstances.status] = AgentInstanceStatus.ACTIVE.name
                row[AgentInstances.createdAt] = now
            }
            AgentBindings.insert { row ->
                row[AgentBindings.id] = "team-binding-$suffix"
                row[AgentBindings.agentInstanceId] = agentId
                row[AgentBindings.targetType] = AgentBindingTargetType.ROOM.name
                row[AgentBindings.targetId] = roomId
                row[AgentBindings.messageScope] = AgentBindingMessageScope.TEAM.name
                row[AgentBindings.triggerPolicy] = AgentTriggerPolicy.MENTION.name
                row[AgentBindings.mentionAlias] = mentionAlias
                row[AgentBindings.permissionsJson] = "[\"READ_MESSAGE\",\"SEND_MESSAGE\"]"
                row[AgentBindings.status] = AgentBindingStatus.ACTIVE.name
                row[AgentBindings.createdBy] = ownerId
                row[AgentBindings.ownerId] = ownerId
                row[AgentBindings.createdAt] = now
            }
        }
        return agentId
    }
}
