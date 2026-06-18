package com.silk.backend

import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MarkReadRequest
import com.silk.backend.database.SimpleResponse
import com.silk.backend.database.UnreadCountResponse
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendWebSocketContractTest {
    @Test
    fun `chat websocket replays recent history broadcasts live messages and updates unread flow`() {
        TestWorkspace().use {
            val group = createGroupForBackendWebSocketTest("WebSocket Contract Group")
            assertTrue(GroupRepository.addUserToGroup(group.id, "guest-user"))
            seedBackendWebSocketHistory(
                group.id,
                (1..52).map { index ->
                    backendWebSocketChatEntry(
                        messageId = "history-$index",
                        senderId = "seed-user",
                        senderName = "SeedUser",
                        content = "history payload $index",
                        timestamp = index.toLong(),
                    )
                },
            )

            testApplication {
                application { module() }

                val wsClient = createClient {
                    install(WebSockets)
                }

                val hostSession = wsClient.connectBackendChat(
                    userId = "host-user",
                    userName = "HostUser",
                    groupId = group.id,
                )
                val guestSession = wsClient.connectBackendChat(
                    userId = "guest-user",
                    userName = "GuestUser",
                    groupId = group.id,
                )

                val hostReplay = hostSession.receiveBackendHistory()
                val guestReplay = guestSession.receiveBackendHistory()
                val expectedReplayIds = (3..52).map { "history-$it" }
                assertEquals(expectedReplayIds, hostReplay.map { it.id })
                assertEquals(expectedReplayIds, guestReplay.map { it.id })

                val sessionData = assertNotNull(
                    ChatHistoryManager().loadSessionData("group_${group.id}"),
                )
                assertEquals(
                    setOf("host-user", "guest-user", SilkAgent.AGENT_ID),
                    sessionData.members.filter { it.isOnline }.map { it.userId }.toSet(),
                )

                val liveMessage = Message(
                    id = "live-1",
                    userId = "host-user",
                    userName = "HostUser",
                    content = "fast validation websocket message",
                    timestamp = 10_000L,
                )
                hostSession.send(Frame.Text(backendWebSocketJson.encodeToString(liveMessage)))

                val hostBroadcast = hostSession.receiveBackendMessage()
                val guestBroadcast = guestSession.receiveBackendMessage()
                assertEquals("live-1", hostBroadcast.id)
                assertEquals("live-1", guestBroadcast.id)
                assertEquals("fast validation websocket message", guestBroadcast.content)

                val persistedHistory = assertNotNull(
                    ChatHistoryManager().loadChatHistory("group_${group.id}"),
                )
                assertEquals(53, persistedHistory.messages.size)
                assertEquals("live-1", persistedHistory.messages.last().messageId)

                val guestUnread = client.get("/api/unread/guest-user")
                    .decodeBackendWebSocketBody<UnreadCountResponse>()
                assertTrue(guestUnread.success)
                assertEquals(1, guestUnread.unreadCounts[group.id] ?: 0)

                val hostUnread = client.get("/api/unread/host-user")
                    .decodeBackendWebSocketBody<UnreadCountResponse>()
                assertTrue(hostUnread.success)
                assertEquals(0, hostUnread.unreadCounts[group.id] ?: 0)

                val markRead = client.post("/api/unread/mark-read") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        backendWebSocketJson.encodeToString(
                            MarkReadRequest(
                                userId = "guest-user",
                                groupId = group.id,
                            ),
                        ),
                    )
                }.decodeBackendWebSocketBody<SimpleResponse>()
                assertTrue(markRead.success)

                val guestUnreadAfterMarkRead = client.get("/api/unread/guest-user")
                    .decodeBackendWebSocketBody<UnreadCountResponse>()
                assertEquals(0, guestUnreadAfterMarkRead.unreadCounts[group.id] ?: 0)
            }
        }
    }

    @Test
    fun `chat websocket rejects non member before joining group session`() {
        TestWorkspace().use {
            val group = createGroupForBackendWebSocketTest("WebSocket Auth Group")

            testApplication {
                application { module() }

                val wsClient = createClient {
                    install(WebSockets)
                }

                val intruderSession = wsClient.connectBackendChat(
                    userId = "intruder-user",
                    userName = "Intruder",
                    groupId = group.id,
                )
                val closeReason = withTimeout(5_000) { intruderSession.closeReason.await() }
                assertNotNull(closeReason)
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.code)
                assertEquals("Not authorized for this group", closeReason.message)
                assertNull(ChatHistoryManager().loadSessionData("group_${group.id}"))
            }
        }
    }
}
