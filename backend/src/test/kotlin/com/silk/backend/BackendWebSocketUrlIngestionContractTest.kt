package com.silk.backend

import com.silk.backend.database.GroupRepository
import com.silk.backend.testsupport.HttpOnlyWebPageDownloaderOverride
import com.silk.backend.testsupport.LocalWebContentServer
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendWebSocketUrlIngestionContractTest {
    @Test
    fun `chat websocket ingests local html and pdf urls broadcasts file messages and replays them from history`() {
        TestWorkspace().use { workspace ->
            val group = createGroupForBackendWebSocketTest("WebSocket URL Ingestion Group")
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
                contentLine = "CI URL PDF smoke content for websocket validation.",
            )
            val localWeb = LocalWebContentServer(
                html = html,
                pdfBytes = pdfBytes,
                htmlPath = "/docs/chat-ci-smoke",
                pdfPath = "/docs/chat-ci-smoke.pdf",
            )
            val httpOnlyDownloader = HttpOnlyWebPageDownloaderOverride()

            try {
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
                    hostSession.receiveBackendHistory()
                    guestSession.receiveBackendHistory()

                    val urlMessage = Message(
                        id = "url-live-1",
                        userId = "host-user",
                        userName = "HostUser",
                        content = "Please ingest ${localWeb.htmlUrl} and ${localWeb.pdfUrl}",
                        timestamp = 20_000L,
                    )
                    hostSession.send(Frame.Text(backendWebSocketJson.encodeToString(urlMessage)))

                    val (hostReceived, guestReceived) = coroutineScope {
                        val hostDeferred = async {
                            hostSession.receiveBackendMessagesUntil { it.content == "CLEAR_STATUS" }
                        }
                        val guestDeferred = async {
                            guestSession.receiveBackendMessagesUntil { it.content == "CLEAR_STATUS" }
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
                        "CLEAR_STATUS",
                    )
                    val guestExpectedSequence = listOf(
                        urlMessage.content,
                        "📄 已下载网页: CI URL HTML Smoke",
                        "FILE",
                        "📄 已下载PDF: CI URL PDF Smoke",
                        "FILE",
                        "CLEAR_STATUS",
                    )

                    assertEquals("url-live-1", hostReceived.first().id)
                    assertEquals("url-live-1", guestReceived.first().id)
                    assertBackendWebSocketMessagesContainInOrder(
                        actual = hostReceived.map(::backendWebSocketMessageMarker),
                        expected = hostExpectedSequence,
                    )
                    assertBackendWebSocketMessagesContainInOrder(
                        actual = guestReceived.map(::backendWebSocketMessageMarker),
                        expected = guestExpectedSequence,
                    )

                    val hostFileMessages = hostReceived.filter { it.type == MessageType.FILE }
                    val guestFileMessages = guestReceived.filter { it.type == MessageType.FILE }
                    assertEquals(2, hostFileMessages.size)
                    assertEquals(2, guestFileMessages.size)
                    assertEquals(hostFileMessages.map { it.id }, guestFileMessages.map { it.id })

                    val uploadsDir = File(workspace.chatHistoryDir, "group_${group.id}/uploads")
                    waitForBackendWebSocketCondition {
                        val files = uploadsDir.listFiles()?.filter { it.name != "processed_urls.txt" } ?: emptyList()
                        File(uploadsDir, "processed_urls.txt").isFile && files.size == 3
                    }

                    val processedUrls = File(uploadsDir, "processed_urls.txt")
                        .readLines()
                        .filter { it.isNotBlank() }
                    assertEquals(
                        listOf(localWeb.htmlUrl.lowercase(), localWeb.pdfUrl.lowercase()),
                        processedUrls,
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
                        .map(::parseBackendWebSocketFilePayload)
                        .sortedBy { it.fileName }
                    assertEquals(
                        downloadableFiles.map { it.name },
                        hostFilePayloads.map { it.fileName },
                    )
                    assertEquals(
                        downloadableFiles.map { it.length() },
                        hostFilePayloads.map { it.fileSize },
                    )
                    assertEquals(
                        downloadableFiles.map { buildFileDownloadUrl(group.id, it.name) },
                        hostFilePayloads.map { it.downloadUrl },
                    )
                    assertEquals(
                        hostFilePayloads,
                        guestFileMessages.map(::parseBackendWebSocketFilePayload).sortedBy { it.fileName },
                    )

                    val duplicateMessage = Message(
                        id = "url-live-2",
                        userId = "host-user",
                        userName = "HostUser",
                        content = "Repeat ${localWeb.htmlUrl} and ${localWeb.pdfUrl}",
                        timestamp = 21_000L,
                    )
                    hostSession.send(Frame.Text(backendWebSocketJson.encodeToString(duplicateMessage)))

                    val duplicateHostBroadcast = hostSession.receiveBackendMessage()
                    val duplicateGuestBroadcast = guestSession.receiveBackendMessage()
                    assertEquals("url-live-2", duplicateHostBroadcast.id)
                    assertEquals("url-live-2", duplicateGuestBroadcast.id)
                    assertEquals(duplicateMessage.content, duplicateHostBroadcast.content)
                    assertEquals(duplicateMessage.content, duplicateGuestBroadcast.content)
                    assertNull(hostSession.receiveBackendMessageOrNull(1_000))
                    assertNull(guestSession.receiveBackendMessageOrNull(1_000))

                    delay(300)
                    val filesAfterDuplicate = uploadsDir.listFiles()
                        ?.filter { it.name != "processed_urls.txt" }
                        .orEmpty()
                    assertEquals(3, filesAfterDuplicate.size)
                    assertFalse(
                        File(uploadsDir, "processed_urls.txt")
                            .readLines()
                            .filter { it.isNotBlank() }
                            .size > 2,
                    )

                    val persistedHistory = assertNotNull(
                        ChatHistoryManager().loadChatHistory("group_${group.id}"),
                    )
                    assertEquals(
                        listOf(
                            "url-live-1",
                            hostFileMessages[0].id,
                            hostFileMessages[1].id,
                            "url-live-2",
                        ),
                        persistedHistory.messages.takeLast(4).map { it.messageId },
                    )
                    assertEquals(
                        listOf(
                            MessageType.TEXT.name,
                            MessageType.FILE.name,
                            MessageType.FILE.name,
                            MessageType.TEXT.name,
                        ),
                        persistedHistory.messages.takeLast(4).map { it.messageType },
                    )

                    val lateSession = wsClient.connectBackendChat(
                        userId = "late-user",
                        userName = "LateUser",
                        groupId = group.id,
                    )
                    val lateReplay = lateSession.receiveBackendHistory()
                    assertEquals(
                        persistedHistory.messages.takeLast(4).map { it.messageId },
                        lateReplay.map { it.id },
                    )
                    assertEquals(
                        listOf(
                            MessageType.TEXT,
                            MessageType.FILE,
                            MessageType.FILE,
                            MessageType.TEXT,
                        ),
                        lateReplay.map { it.type },
                    )
                    assertEquals(
                        hostFileMessages.map(::parseBackendWebSocketFilePayload),
                        lateReplay.filter { it.type == MessageType.FILE }.map(::parseBackendWebSocketFilePayload),
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
            val group = createGroupForBackendWebSocketTest("WebSocket URL Failure Group")
            val localWeb = LocalWebContentServer(
                html = "<html><head><title>unused</title></head><body>unused</body></html>",
                pdfBytes = LocalWebContentServer.createPdfBytes(),
            )
            val httpOnlyDownloader = HttpOnlyWebPageDownloaderOverride()

            try {
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
                    hostSession.receiveBackendHistory()

                    val failedUrlMessage = Message(
                        id = "url-fail-1",
                        userId = "host-user",
                        userName = "HostUser",
                        content = "Try ${localWeb.unsupportedUrl} and ${localWeb.missingUrl}",
                        timestamp = 22_000L,
                    )
                    hostSession.send(Frame.Text(backendWebSocketJson.encodeToString(failedUrlMessage)))

                    val received = hostSession.receiveBackendMessagesUntil { it.content == "CLEAR_STATUS" }
                    val receivedContents = received.map { it.content }
                    assertEquals("url-fail-1", received.first().id)
                    assertBackendWebSocketMessagesContainInOrder(
                        actual = receivedContents,
                        expected = listOf(
                            failedUrlMessage.content,
                            "🌐 正在下载: ${localWeb.unsupportedUrl}",
                            "⚠️ 无法下载: ${localWeb.unsupportedUrl}",
                            "🌐 正在下载: ${localWeb.missingUrl}",
                            "⚠️ 无法下载: ${localWeb.missingUrl}",
                            "CLEAR_STATUS",
                        ),
                    )

                    assertBackendWebSocketUploadsRemainEmpty(workspace, group.id)
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
            val group = createGroupForBackendWebSocketTest("WebSocket Corrupt PDF Failure Group")
            val localWeb = LocalWebContentServer(
                html = "<html><head><title>unused</title></head><body>unused</body></html>",
                pdfBytes = LocalWebContentServer.createPdfBytes(),
            )
            val httpOnlyDownloader = HttpOnlyWebPageDownloaderOverride()

            try {
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
                    hostSession.receiveBackendHistory()

                    val failedUrlMessage = Message(
                        id = "url-fail-2",
                        userId = "host-user",
                        userName = "HostUser",
                        content = "Try ${localWeb.corruptPdfUrl}",
                        timestamp = 23_000L,
                    )
                    hostSession.send(Frame.Text(backendWebSocketJson.encodeToString(failedUrlMessage)))

                    val received = hostSession.receiveBackendMessagesUntil { it.content == "CLEAR_STATUS" }
                    val receivedContents = received.map { it.content }
                    assertEquals("url-fail-2", received.first().id)
                    assertBackendWebSocketMessagesContainInOrder(
                        actual = receivedContents,
                        expected = listOf(
                            failedUrlMessage.content,
                            "🌐 正在下载: ${localWeb.corruptPdfUrl}",
                            "⚠️ 无法下载: ${localWeb.corruptPdfUrl}",
                            "CLEAR_STATUS",
                        ),
                    )

                    assertBackendWebSocketUploadsRemainEmpty(workspace, group.id)
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
            val group = createGroupForBackendWebSocketTest("WebSocket Timeout Failure Group")
            val localWeb = LocalWebContentServer(
                html = "<html><head><title>unused</title></head><body>unused</body></html>",
                pdfBytes = LocalWebContentServer.createPdfBytes(),
            )
            val httpOnlyDownloader = HttpOnlyWebPageDownloaderOverride(
                connectTimeoutMillis = 100,
                readTimeoutMillis = 100,
            )

            try {
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
                    hostSession.receiveBackendHistory()

                    val failedUrlMessage = Message(
                        id = "url-fail-3",
                        userId = "host-user",
                        userName = "HostUser",
                        content = "Try ${localWeb.slowHtmlUrl} and ${localWeb.refusedUrl}",
                        timestamp = 24_000L,
                    )
                    hostSession.send(Frame.Text(backendWebSocketJson.encodeToString(failedUrlMessage)))

                    val received = hostSession.receiveBackendMessagesUntil { it.content == "CLEAR_STATUS" }
                    val receivedContents = received.map { it.content }
                    assertEquals("url-fail-3", received.first().id)
                    assertBackendWebSocketMessagesContainInOrder(
                        actual = receivedContents,
                        expected = listOf(
                            failedUrlMessage.content,
                            "🌐 正在下载: ${localWeb.slowHtmlUrl}",
                            "⚠️ 无法下载: ${localWeb.slowHtmlUrl}",
                            "🌐 正在下载: ${localWeb.refusedUrl}",
                            "⚠️ 无法下载: ${localWeb.refusedUrl}",
                            "CLEAR_STATUS",
                        ),
                    )

                    assertBackendWebSocketUploadsRemainEmpty(workspace, group.id)
                }
            } finally {
                httpOnlyDownloader.close()
                localWeb.close()
            }
        }
    }
}
