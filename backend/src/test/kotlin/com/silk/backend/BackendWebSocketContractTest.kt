package com.silk.backend

import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MarkReadRequest
import com.silk.backend.database.SimpleResponse
import com.silk.backend.database.UnreadCountResponse
import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import com.silk.backend.testsupport.HttpOnlyWebPageDownloaderOverride
import com.silk.backend.testsupport.LocalWebContentServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
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
    fun `chat websocket ingests local html and pdf urls broadcasts file messages and replays them from history`() {
        TestWorkspace().use { workspace ->
            val group = createGroupForTest("WebSocket URL Ingestion Group")
            assertTrue(GroupRepository.addUserToGroup(group.id, "guest-user"))
            assertTrue(GroupRepository.addUserToGroup(group.id, "late-user"))
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>CI URL HTML Smoke</title>
                </head>
                <body>
                    <header>ignore this header</header>
                    <main>
                        <p>This websocket fast validation smoke covers URL ingestion without any internet dependency.</p>
                        <p>It verifies the message entry path from chat text to local download, file persistence, and status echo.</p>
                    </main>
                </body>
                </html>
            """.trimIndent()
            val pdfBytes = LocalWebContentServer.createPdfBytes(
                title = "CI URL PDF Smoke",
                contentLine = "CI URL PDF smoke content for websocket validation."
            )
            val localWeb = LocalWebContentServer(
                html = html,
                pdfBytes = pdfBytes,
                htmlPath = "/docs/chat-ci-smoke",
                pdfPath = "/docs/chat-ci-smoke.pdf"
            )
            val httpOnlyDownloader = HttpOnlyWebPageDownloaderOverride()

            try {
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
                    hostSession.receiveHistory()
                    guestSession.receiveHistory()

                    val urlMessage = Message(
                        id = "url-live-1",
                        userId = "host-user",
                        userName = "HostUser",
                        content = "Please ingest ${localWeb.htmlUrl} and ${localWeb.pdfUrl}",
                        timestamp = 20_000L
                    )
                    hostSession.send(Frame.Text(json.encodeToString(urlMessage)))

                    val (hostReceived, guestReceived) = coroutineScope {
                        val hostDeferred = async {
                            hostSession.receiveMessagesUntil { it.content == "CLEAR_STATUS" }
                        }
                        val guestDeferred = async {
                            guestSession.receiveMessagesUntil { it.content == "CLEAR_STATUS" }
                        }
                        hostDeferred.await() to guestDeferred.await()
                    }
                    val hostExpectedSequence = listOf(
                        urlMessage.content,
                        "🌐 正在下载: ${localWeb.htmlUrl}",
                        "📄 已下载网页: CI URL HTML Smoke",
                        "FILE",
                        "🌐 正在下载: ${localWeb.pdfUrl}",
                        "📄 已下载PDF: CI URL PDF Smoke",
                        "FILE",
                        "CLEAR_STATUS"
                    )
                    // Guest-side transient status updates are best-effort UI hints rather than a strict contract.
                    val guestExpectedSequence = listOf(
                        urlMessage.content,
                        "📄 已下载网页: CI URL HTML Smoke",
                        "FILE",
                        "📄 已下载PDF: CI URL PDF Smoke",
                        "FILE",
                        "CLEAR_STATUS"
                    )

                    assertEquals("url-live-1", hostReceived.first().id)
                    assertEquals("url-live-1", guestReceived.first().id)
                    assertContainsInOrder(
                        actual = hostReceived.map(::messageMarker),
                        expected = hostExpectedSequence
                    )
                    assertContainsInOrder(
                        actual = guestReceived.map(::messageMarker),
                        expected = guestExpectedSequence
                    )

                    val hostFileMessages = hostReceived.filter { it.type == MessageType.FILE }
                    val guestFileMessages = guestReceived.filter { it.type == MessageType.FILE }
                    assertEquals(2, hostFileMessages.size)
                    assertEquals(2, guestFileMessages.size)
                    assertEquals(hostFileMessages.map { it.id }, guestFileMessages.map { it.id })

                    val uploadsDir = File(workspace.chatHistoryDir, "group_${group.id}/uploads")
                    waitForCondition {
                        val files = uploadsDir.listFiles()?.filter { it.name != "processed_urls.txt" } ?: emptyList()
                        File(uploadsDir, "processed_urls.txt").isFile && files.size == 3
                    }

                    val processedUrls = File(uploadsDir, "processed_urls.txt")
                        .readLines()
                        .filter { it.isNotBlank() }
                    assertEquals(
                        listOf(localWeb.htmlUrl.lowercase(), localWeb.pdfUrl.lowercase()),
                        processedUrls
                    )

                    val savedFiles = uploadsDir.listFiles()
                        ?.filter { it.name != "processed_urls.txt" }
                        .orEmpty()
                    assertEquals(3, savedFiles.size)

                    val htmlFile = assertNotNull(savedFiles.firstOrNull { it.extension == "html" })
                    assertTrue(htmlFile.readText().contains("websocket fast validation smoke covers URL ingestion"))

                    val pdfFile = assertNotNull(savedFiles.firstOrNull { it.extension == "pdf" })
                    assertEquals(pdfBytes.size, pdfFile.readBytes().size)

                    val pdfTextFile = assertNotNull(savedFiles.firstOrNull { it.name.endsWith("_text.txt") })
                    assertTrue(pdfTextFile.readText().contains("CI URL PDF smoke content for websocket validation."))

                    val downloadableFiles = savedFiles
                        .filter { it.extension == "html" || it.extension == "pdf" }
                        .sortedBy { it.name }
                    assertEquals(2, downloadableFiles.size)

                    val hostFilePayloads = hostFileMessages
                        .map(::parseFilePayload)
                        .sortedBy { it.fileName }
                    assertEquals(
                        downloadableFiles.map { it.name },
                        hostFilePayloads.map { it.fileName }
                    )
                    assertEquals(
                        downloadableFiles.map { it.length() },
                        hostFilePayloads.map { it.fileSize }
                    )
                    assertEquals(
                        downloadableFiles.map { buildFileDownloadUrl(group.id, it.name) },
                        hostFilePayloads.map { it.downloadUrl }
                    )
                    assertEquals(
                        hostFilePayloads,
                        guestFileMessages.map(::parseFilePayload).sortedBy { it.fileName }
                    )

                    val duplicateMessage = Message(
                        id = "url-live-2",
                        userId = "host-user",
                        userName = "HostUser",
                        content = "Repeat ${localWeb.htmlUrl} and ${localWeb.pdfUrl}",
                        timestamp = 21_000L
                    )
                    hostSession.send(Frame.Text(json.encodeToString(duplicateMessage)))

                    val duplicateHostBroadcast = hostSession.receiveMessage()
                    val duplicateGuestBroadcast = guestSession.receiveMessage()
                    assertEquals("url-live-2", duplicateHostBroadcast.id)
                    assertEquals("url-live-2", duplicateGuestBroadcast.id)
                    assertEquals(duplicateMessage.content, duplicateHostBroadcast.content)
                    assertEquals(duplicateMessage.content, duplicateGuestBroadcast.content)
                    assertNull(hostSession.receiveMessageOrNull(1_000))
                    assertNull(guestSession.receiveMessageOrNull(1_000))

                    delay(300)
                    val filesAfterDuplicate = uploadsDir.listFiles()
                        ?.filter { it.name != "processed_urls.txt" }
                        .orEmpty()
                    assertEquals(3, filesAfterDuplicate.size)
                    assertFalse(
                        File(uploadsDir, "processed_urls.txt")
                            .readLines()
                            .filter { it.isNotBlank() }
                            .size > 2
                    )

                    val persistedHistory = assertNotNull(
                        ChatHistoryManager().loadChatHistory("group_${group.id}")
                    )
                    assertEquals(
                        listOf(
                            "url-live-1",
                            hostFileMessages[0].id,
                            hostFileMessages[1].id,
                            "url-live-2"
                        ),
                        persistedHistory.messages.takeLast(4).map { it.messageId }
                    )
                    assertEquals(
                        listOf(
                            MessageType.TEXT.name,
                            MessageType.FILE.name,
                            MessageType.FILE.name,
                            MessageType.TEXT.name
                        ),
                        persistedHistory.messages.takeLast(4).map { it.messageType }
                    )

                    val lateSession = wsClient.connectChat(
                        userId = "late-user",
                        userName = "LateUser",
                        groupId = group.id
                    )
                    val lateReplay = lateSession.receiveHistory()
                    assertEquals(
                        persistedHistory.messages.takeLast(4).map { it.messageId },
                        lateReplay.map { it.id }
                    )
                    assertEquals(
                        listOf(
                            MessageType.TEXT,
                            MessageType.FILE,
                            MessageType.FILE,
                            MessageType.TEXT
                        ),
                        lateReplay.map { it.type }
                    )
                    assertEquals(
                        hostFileMessages.map(::parseFilePayload),
                        lateReplay.filter { it.type == MessageType.FILE }.map(::parseFilePayload)
                    )
                }
            } finally {
                httpOnlyDownloader.close()
                localWeb.close()
            }
        }
    }

    @Test
    fun `chat websocket reports local url failures without persisting processed urls or files`() {
        TestWorkspace().use { workspace ->
            val group = createGroupForTest("WebSocket URL Failure Group")
            val localWeb = LocalWebContentServer(
                html = "<html><head><title>unused</title></head><body>unused</body></html>",
                pdfBytes = LocalWebContentServer.createPdfBytes()
            )
            val httpOnlyDownloader = HttpOnlyWebPageDownloaderOverride()

            try {
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
                    hostSession.receiveHistory()

                    val failedUrlMessage = Message(
                        id = "url-fail-1",
                        userId = "host-user",
                        userName = "HostUser",
                        content = "Try ${localWeb.unsupportedUrl} and ${localWeb.missingUrl}",
                        timestamp = 22_000L
                    )
                    hostSession.send(Frame.Text(json.encodeToString(failedUrlMessage)))

                    val received = hostSession.receiveMessagesUntil { it.content == "CLEAR_STATUS" }
                    val receivedContents = received.map { it.content }
                    assertEquals("url-fail-1", received.first().id)
                    assertContainsInOrder(
                        actual = receivedContents,
                        expected = listOf(
                            failedUrlMessage.content,
                            "🌐 正在下载: ${localWeb.unsupportedUrl}",
                            "⚠️ 无法下载: ${localWeb.unsupportedUrl}",
                            "🌐 正在下载: ${localWeb.missingUrl}",
                            "⚠️ 无法下载: ${localWeb.missingUrl}",
                            "CLEAR_STATUS"
                        )
                    )

                    assertUploadsRemainEmpty(workspace, group.id)
                }
            } finally {
                httpOnlyDownloader.close()
                localWeb.close()
            }
        }
    }

    @Test
    fun `chat websocket reports corrupt pdf failure without persisting processed urls or files`() {
        TestWorkspace().use { workspace ->
            val group = createGroupForTest("WebSocket Corrupt PDF Failure Group")
            val localWeb = LocalWebContentServer(
                html = "<html><head><title>unused</title></head><body>unused</body></html>",
                pdfBytes = LocalWebContentServer.createPdfBytes()
            )
            val httpOnlyDownloader = HttpOnlyWebPageDownloaderOverride()

            try {
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
                    hostSession.receiveHistory()

                    val failedUrlMessage = Message(
                        id = "url-fail-2",
                        userId = "host-user",
                        userName = "HostUser",
                        content = "Try ${localWeb.corruptPdfUrl}",
                        timestamp = 23_000L
                    )
                    hostSession.send(Frame.Text(json.encodeToString(failedUrlMessage)))

                    val received = hostSession.receiveMessagesUntil { it.content == "CLEAR_STATUS" }
                    val receivedContents = received.map { it.content }
                    assertEquals("url-fail-2", received.first().id)
                    assertContainsInOrder(
                        actual = receivedContents,
                        expected = listOf(
                            failedUrlMessage.content,
                            "🌐 正在下载: ${localWeb.corruptPdfUrl}",
                            "⚠️ 无法下载: ${localWeb.corruptPdfUrl}",
                            "CLEAR_STATUS"
                        )
                    )

                    assertUploadsRemainEmpty(workspace, group.id)
                }
            } finally {
                httpOnlyDownloader.close()
                localWeb.close()
            }
        }
    }

    @Test
    fun `chat websocket reports timeout and connection refused without persisting processed urls or files`() {
        TestWorkspace().use { workspace ->
            val group = createGroupForTest("WebSocket Timeout Failure Group")
            val localWeb = LocalWebContentServer(
                html = "<html><head><title>unused</title></head><body>unused</body></html>",
                pdfBytes = LocalWebContentServer.createPdfBytes()
            )
            val httpOnlyDownloader = HttpOnlyWebPageDownloaderOverride(
                connectTimeoutMillis = 100,
                readTimeoutMillis = 100
            )

            try {
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
                    hostSession.receiveHistory()

                    val failedUrlMessage = Message(
                        id = "url-fail-3",
                        userId = "host-user",
                        userName = "HostUser",
                        content = "Try ${localWeb.slowHtmlUrl} and ${localWeb.refusedUrl}",
                        timestamp = 24_000L
                    )
                    hostSession.send(Frame.Text(json.encodeToString(failedUrlMessage)))

                    val received = hostSession.receiveMessagesUntil { it.content == "CLEAR_STATUS" }
                    val receivedContents = received.map { it.content }
                    assertEquals("url-fail-3", received.first().id)
                    assertContainsInOrder(
                        actual = receivedContents,
                        expected = listOf(
                            failedUrlMessage.content,
                            "🌐 正在下载: ${localWeb.slowHtmlUrl}",
                            "⚠️ 无法下载: ${localWeb.slowHtmlUrl}",
                            "🌐 正在下载: ${localWeb.refusedUrl}",
                            "⚠️ 无法下载: ${localWeb.refusedUrl}",
                            "CLEAR_STATUS"
                        )
                    )

                    assertUploadsRemainEmpty(workspace, group.id)
                }
            } finally {
                httpOnlyDownloader.close()
                localWeb.close()
            }
        }
    }

    private fun createGroupForTest(groupName: String) =
        assertNotNull(GroupRepository.createGroup(groupName, hostId = "host-user"))

    private suspend fun assertUploadsRemainEmpty(workspace: TestWorkspace, groupId: String) {
        val uploadsDir = File(workspace.chatHistoryDir, "group_$groupId/uploads")
        delay(300)
        assertFalse(File(uploadsDir, "processed_urls.txt").exists())
        val savedFiles = uploadsDir.listFiles()
            ?.filter { it.name != "processed_urls.txt" }
            .orEmpty()
        assertTrue(savedFiles.isEmpty())
    }

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

    private fun messageMarker(message: Message): String =
        if (message.type == MessageType.FILE) "FILE" else message.content

    private fun parseFilePayload(message: Message): FileMessagePayload {
        assertEquals(MessageType.FILE, message.type)
        return json.decodeFromString(message.content)
    }

    private suspend fun HttpClient.connectChat(
        userId: String,
        userName: String,
        groupId: String
    ): DefaultClientWebSocketSession = webSocketSession {
        url("/chat?userId=$userId&userName=$userName&groupId=$groupId")
    }

    private suspend fun DefaultClientWebSocketSession.receiveMessagesUntil(
        stopWhen: (Message) -> Boolean
    ): List<Message> = buildList {
        withTimeout(8_000) {
            while (true) {
                val message = receiveMessage()
                add(message)
                if (stopWhen(message)) {
                    break
                }
            }
        }
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

    private suspend fun DefaultClientWebSocketSession.receiveMessageOrNull(timeoutMillis: Long): Message? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remainingMillis = deadline - System.currentTimeMillis()
            if (remainingMillis <= 0) {
                return null
            }
            val message = receiveRawMessage(remainingMillis) ?: return null
            if (!message.isHistoryEndMarker()) {
                return message
            }
        }
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

    private suspend fun waitForCondition(
        timeoutMillis: Long = 5_000,
        intervalMillis: Long = 50,
        condition: () -> Boolean
    ) {
        withTimeout(timeoutMillis) {
            while (!condition()) {
                delay(intervalMillis)
            }
        }
    }

    private fun assertContainsInOrder(actual: List<String>, expected: List<String>) {
        var cursor = 0
        expected.forEach { target ->
            while (cursor < actual.size && actual[cursor] != target) {
                cursor++
            }
            assertTrue(cursor < actual.size, "Expected '$target' in order within $actual")
            cursor++
        }
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())
}
