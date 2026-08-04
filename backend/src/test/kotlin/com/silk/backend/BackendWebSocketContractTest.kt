package com.silk.backend

import com.silk.backend.auth.JwtProvider
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MarkReadRequest
import com.silk.backend.database.SimpleResponse
import com.silk.backend.database.UnreadCountResponse
import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import com.silk.backend.models.Workflow
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.testsupport.HttpOnlyWebPageDownloaderOverride
import com.silk.backend.testsupport.LocalWebContentServer
import com.silk.backend.workflow.WorkflowManager
import com.silk.backend.workspace.WorkspaceManager
import com.silk.backend.workspace.WorkspaceVisibility
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendWebSocketContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val frameBuffers = ConcurrentHashMap<DefaultClientWebSocketSession, ArrayDeque<Message>>()

    @Test
    fun `chat websocket replays recent history broadcasts live messages and updates unread flow`() {
        TestWorkspace().use {
            val group = createGroupForTest("WebSocket Contract Group")
            assertTrue(GroupRepository.addUserToGroup(group.id, "guest-user"))
            seedGroupHistory(
                group.id,
                (1..52).map { index ->
                    chatEntry(
                        messageId = "history-$index",
                        senderId = "seed-user",
                        senderName = "SeedUser",
                        content = "history payload $index",
                        timestamp = index.toLong()
                    )
                }
            )

            testApplication {
                application { module() }

                val wsClient = createClient {
                    install(WebSockets)
                }

                val hostSession = wsClient.connectChat(
                    userId = "host-user",
                    userName = "HostUser",
                    groupId = group.id
                )
                val guestSession = wsClient.connectChat(
                    userId = "guest-user",
                    userName = "GuestUser",
                    groupId = group.id
                )

                val hostReplay = hostSession.receiveHistory()
                val guestReplay = guestSession.receiveHistory()
                val expectedReplayIds = (3..52).map { "history-$it" }
                assertEquals(expectedReplayIds, hostReplay.map { it.id })
                assertEquals(expectedReplayIds, guestReplay.map { it.id })

                val sessionData = assertNotNull(
                    ChatHistoryManager().loadSessionData("group_${group.id}")
                )
                assertEquals(
                    setOf("host-user", "guest-user", SilkAgent.AGENT_ID),
                    sessionData.members.filter { it.isOnline }.map { it.userId }.toSet()
                )

                val liveMessage = Message(
                    id = "live-1",
                    userId = "host-user",
                    userName = "HostUser",
                    content = "fast validation websocket message",
                    timestamp = 10_000L
                )
                hostSession.send(Frame.Text(json.encodeToString(liveMessage)))

                val hostBroadcast = hostSession.receiveMessage()
                val guestBroadcast = guestSession.receiveMessage()
                assertEquals("live-1", hostBroadcast.id)
                assertEquals("live-1", guestBroadcast.id)
                assertEquals("fast validation websocket message", guestBroadcast.content)

                val persistedHistory = assertNotNull(
                    ChatHistoryManager().loadChatHistory("group_${group.id}")
                )
                assertEquals(53, persistedHistory.messages.size)
                assertEquals("live-1", persistedHistory.messages.last().messageId)

                val guestUnread = client.get("/api/unread/guest-user")
                    .decode<UnreadCountResponse>()
                assertTrue(guestUnread.success)
                assertEquals(1, guestUnread.unreadCounts[group.id] ?: 0)

                val hostUnread = client.get("/api/unread/host-user")
                    .decode<UnreadCountResponse>()
                assertTrue(hostUnread.success)
                assertEquals(0, hostUnread.unreadCounts[group.id] ?: 0)

                val markRead = client.post("/api/unread/mark-read") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            MarkReadRequest(
                                userId = "guest-user",
                                groupId = group.id
                            )
                        )
                    )
                }.decode<SimpleResponse>()
                assertTrue(markRead.success)

                val guestUnreadAfterMarkRead = client.get("/api/unread/guest-user")
                    .decode<UnreadCountResponse>()
                assertEquals(0, guestUnreadAfterMarkRead.unreadCounts[group.id] ?: 0)

            }
        }
    }

    @Test
    fun `chat websocket rejects non member before joining group session`() {
        TestWorkspace().use {
            val group = createGroupForTest("WebSocket Auth Group")

            testApplication {
                application { module() }

                val wsClient = createClient {
                    install(WebSockets)
                }

                val intruderSession = wsClient.connectChat(
                    userId = "intruder-user",
                    userName = "Intruder",
                    groupId = group.id
                )
                val closeReason = withTimeout(5_000) { intruderSession.closeReason.await() }
                assertNotNull(closeReason)
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.code)
                assertEquals("Not authorized for this group", closeReason.message)
                assertNull(ChatHistoryManager().loadSessionData("group_${group.id}"))
            }
        }
    }

    @Test
    fun `workflow websocket rejects authenticated non member before workspace activation`() {
        TestWorkspace().use {
            testApplication {
                application { module() }

                val createResponse = client.post("/api/workflows") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${JwtProvider.generateAccessToken("workflow-owner")}",
                    )
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"userId":"workflow-owner","name":"Membership Contract","agentType":"silk_chat"}"""
                    )
                }
                assertEquals(HttpStatusCode.Created, createResponse.status)
                val workflow = createResponse.decode<Workflow>()

                val wsClient = createClient { install(WebSockets) }
                val intruderSession = wsClient.connectChat(
                    userId = "ignored-client-id",
                    userName = "Intruder",
                    groupId = workflow.groupId,
                    token = JwtProvider.generateAccessToken("workflow-intruder"),
                )

                val closeReason = withTimeout(5_000) { intruderSession.closeReason.await() }
                assertNotNull(closeReason)
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.code)
                assertEquals("room membership required", closeReason.message)
                assertNull(ChatHistoryManager().loadSessionData("group_${workflow.groupId}"))
            }
        }
    }

    @Test
    fun `workflow websocket isolates workspace streams and reauthorizes copilot control`() {
        TestWorkspace().use { workspace ->
            val aliceId = "phase2-alice"
            val bobId = "phase2-bob"
            val group = assertNotNull(GroupRepository.createGroup("Phase 2 Multi User", aliceId))
            assertTrue(GroupRepository.addUserToGroup(group.id, bobId))
            WorkflowManager(workspace.workflowDir.absolutePath).createWorkflow(
                name = "Phase 2 Multi User",
                description = "",
                userId = aliceId,
                groupId = group.id,
                agentType = "claude_code",
                taskFocus = "",
            )
            val workspaceManager = WorkspaceManager(workspace.workflowDir.absolutePath)
            val alicePrivate = workspaceManager.createWorkspace(
                roomId = group.id,
                ownerId = aliceId,
                name = "private-auth",
                workingDir = workspace.workflowDir.absolutePath,
            )
            val aliceSecond = workspaceManager.createWorkspace(
                roomId = group.id,
                ownerId = aliceId,
                name = "second-workspace",
                workingDir = workspace.workflowDir.absolutePath,
            )

            try {
                testApplication {
                    application { module() }
                    val wsClient = createClient { install(WebSockets) }
                    val aliceSession = wsClient.connectChat(
                        userId = "ignored-alice",
                        userName = "Alice",
                        groupId = group.id,
                        token = JwtProvider.generateAccessToken(aliceId),
                    )
                    val bobSession = wsClient.connectChat(
                        userId = "ignored-bob",
                        userName = "Bob",
                        groupId = group.id,
                        token = JwtProvider.generateAccessToken(bobId),
                    )
                    assertTrue(aliceSession.receiveHistory().isEmpty())
                    assertTrue(bobSession.receiveHistory().isEmpty())

                    aliceSession.send(Frame.Text(json.encodeToString(workspaceText(
                        id = "alice-private",
                        userId = aliceId,
                        workspaceId = alicePrivate.workspaceId,
                    ))))
                    assertNotNull(aliceSession.receiveMatching { it.id == "alice-private" })
                    assertNull(bobSession.receiveMatching(600) { it.id == "alice-private" })

                    workspaceManager.updateVisibility(alicePrivate.workspaceId, WorkspaceVisibility.SHARED)
                    aliceSession.send(Frame.Text(json.encodeToString(workspaceText(
                        id = "alice-shared",
                        userId = aliceId,
                        workspaceId = alicePrivate.workspaceId,
                    ))))
                    assertNotNull(aliceSession.receiveMatching { it.id == "alice-shared" })
                    assertNotNull(bobSession.receiveMatching { it.id == "alice-shared" })

                    val bobReplaySession = wsClient.connectChat(
                        userId = "ignored-bob-replay",
                        userName = "Bob",
                        groupId = group.id,
                        token = JwtProvider.generateAccessToken(bobId),
                    )
                    val bobReplay = bobReplaySession.receiveHistory()
                    assertTrue(bobReplay.any { it.id == "alice-shared" })
                    assertTrue(bobReplay.none { it.id == "alice-private" })

                    bobSession.send(Frame.Text(json.encodeToString(workspaceText(
                        id = "observer-denied",
                        userId = bobId,
                        workspaceId = alicePrivate.workspaceId,
                    ))))
                    assertNull(bobSession.receiveMatching(600) { it.id == "observer-denied" })
                    assertNull(aliceSession.receiveMatching(600) { it.id == "observer-denied" })

                    bobSession.send(Frame.Text(json.encodeToString(
                        workspaceText("observer-stop", bobId, alicePrivate.workspaceId)
                            .copy(type = MessageType.STOP_GENERATE)
                    )))
                    bobSession.send(Frame.Text(json.encodeToString(
                        workspaceText("observer-card", bobId, alicePrivate.workspaceId)
                            .copy(type = MessageType.CARD_REPLY, content = "{}")
                    )))

                    val observerBarrier = Message(
                        id = "observer-barrier",
                        userId = bobId,
                        userName = "Bob",
                        content = "observer authorization barrier",
                        timestamp = 1L,
                        type = MessageType.SYSTEM,
                        scope = MessageScope.TEAM,
                    )
                    bobSession.send(Frame.Text(json.encodeToString(observerBarrier)))
                    assertNotNull(bobSession.receiveMatching { it.id == "observer-barrier" })
                    assertNotNull(aliceSession.receiveMatching { it.id == "observer-barrier" })

                    workspaceManager.updateCopilots(alicePrivate.workspaceId, listOf(bobId))
                    bobSession.send(Frame.Text(json.encodeToString(workspaceText(
                        id = "copilot-accepted",
                        userId = bobId,
                        workspaceId = alicePrivate.workspaceId,
                    ))))
                    assertNotNull(bobSession.receiveMatching { it.id == "copilot-accepted" })
                    assertNotNull(aliceSession.receiveMatching { it.id == "copilot-accepted" })
                    assertNotNull(AgentRuntime.snapshotState(aliceId, alicePrivate.workspaceId))
                    assertNull(AgentRuntime.snapshotState(bobId, alicePrivate.workspaceId))

                    workspaceManager.updateCopilots(alicePrivate.workspaceId, emptyList())
                    bobSession.send(Frame.Text(json.encodeToString(workspaceText(
                        id = "copilot-revoked",
                        userId = bobId,
                        workspaceId = alicePrivate.workspaceId,
                    ))))
                    assertNull(bobSession.receiveMatching(600) { it.id == "copilot-revoked" })
                    assertNull(aliceSession.receiveMatching(600) { it.id == "copilot-revoked" })

                    val teamMessage = Message(
                        id = "team-visible",
                        userId = bobId,
                        userName = "Bob",
                        content = "team coordination",
                        timestamp = 1L,
                        isTransient = true,
                        scope = MessageScope.TEAM,
                    )
                    bobSession.send(Frame.Text(json.encodeToString(teamMessage)))
                    assertNotNull(bobSession.receiveMatching { it.id == "team-visible" })
                    assertNotNull(aliceSession.receiveMatching { it.id == "team-visible" })

                    val aliceSecondSession = wsClient.connectChat(
                        userId = "ignored-alice-second",
                        userName = "Alice",
                        groupId = group.id,
                        token = JwtProvider.generateAccessToken(aliceId),
                    )
                    aliceSecondSession.receiveHistory()
                    aliceSession.send(Frame.Text(json.encodeToString(workspaceText(
                        id = "alice-workspace-one",
                        userId = aliceId,
                        workspaceId = alicePrivate.workspaceId,
                    ))))
                    aliceSecondSession.send(Frame.Text(json.encodeToString(workspaceText(
                        id = "alice-workspace-two",
                        userId = aliceId,
                        workspaceId = aliceSecond.workspaceId,
                    ))))
                    assertNotNull(aliceSession.receiveMatching { it.id == "alice-workspace-one" })
                    assertNotNull(aliceSecondSession.receiveMatching { it.id == "alice-workspace-two" })

                    val persisted = assertNotNull(
                        ChatHistoryManager().loadChatHistory("group_${group.id}")
                    ).messages
                    val workspaceOneEntry = assertNotNull(
                        persisted.firstOrNull { it.messageId == "alice-workspace-one" },
                        "workspace one missing; persisted=${persisted.map { it.messageId }}",
                    )
                    val workspaceTwoEntry = assertNotNull(
                        persisted.firstOrNull { it.messageId == "alice-workspace-two" },
                        "workspace two missing; persisted=${persisted.map { it.messageId }}",
                    )
                    assertEquals(
                        alicePrivate.workspaceId,
                        workspaceOneEntry.workspaceId,
                    )
                    assertEquals(
                        aliceSecond.workspaceId,
                        workspaceTwoEntry.workspaceId,
                    )
                    assertTrue(persisted.none { it.messageId in setOf("observer-denied", "copilot-revoked") })

                    aliceSecondSession.close()
                    bobReplaySession.close()
                    bobSession.close()
                    aliceSession.close()
                }
            } finally {
                AgentRuntime.clearForTest()
            }
        }
    }

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

    private fun workspaceText(id: String, userId: String, workspaceId: String) = Message(
        id = id,
        userId = userId,
        userName = userId,
        content = id,
        timestamp = 1L,
        scope = MessageScope.WORKSPACE,
        workspaceId = workspaceId,
    )

    private suspend fun HttpClient.connectChat(
        userId: String,
        userName: String,
        groupId: String,
        token: String? = null,
    ): DefaultClientWebSocketSession = webSocketSession {
        val tokenParameter = token?.let { "&token=$it" }.orEmpty()
        url("/chat?userId=$userId&userName=$userName&groupId=$groupId$tokenParameter")
    }

    private suspend fun DefaultClientWebSocketSession.receiveHistory(): List<Message> = buildList {
        withTimeout(5_000) {
            while (true) {
                val message = receiveRawMessage(5_000) ?: error("Timed out waiting for history replay")
                if (message.isHistoryEndMarker()) {
                    break
                }
                add(message)
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): Message {
        while (true) {
            val message = receiveRawMessage(5_000) ?: error("Timed out waiting for websocket message")
            if (!message.isHistoryEndMarker()) {
                return message
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveMatching(
        timeoutMillis: Long = 5_000,
        predicate: (Message) -> Boolean,
    ): Message? = withTimeoutOrNull(timeoutMillis) {
        var matched: Message? = null
        while (matched == null) {
            val message = receiveRawMessage(timeoutMillis) ?: return@withTimeoutOrNull null
            if (!message.isHistoryEndMarker() && predicate(message)) matched = message
        }
        matched
    }

    private suspend fun DefaultClientWebSocketSession.receiveRawMessage(timeoutMillis: Long): Message? {
        val frameBuffer = frameBuffers.getOrPut(this) { ArrayDeque() }
        if (frameBuffer.isNotEmpty()) {
            return frameBuffer.removeFirst()
        }

        val frame = withTimeoutOrNull(timeoutMillis) { incoming.receive() } ?: return null
        return when (frame) {
            is Frame.Text -> parseFrameText(this, frame.readText())
            else -> error("Expected text frame but received $frame")
        }
    }

    private fun parseFrameText(
        session: DefaultClientWebSocketSession,
        text: String
    ): Message {
        if (!text.startsWith("[")) {
            return json.decodeFromString(text)
        }

        val batch: List<Message> = json.decodeFromString(text)
        if (batch.isEmpty()) {
            error("Empty batch frame")
        }

        val frameBuffer = frameBuffers.getOrPut(session) { ArrayDeque() }
        batch.drop(1).forEach(frameBuffer::addLast)
        return batch.first()
    }

    private fun Message.isHistoryEndMarker(): Boolean =
        isTransient && type == MessageType.SYSTEM && content == "__history_end__"

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())
}
