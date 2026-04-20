package com.silk.backend

import com.silk.backend.database.GroupRepository
import com.silk.backend.database.SimpleResponse
import com.silk.backend.database.UserRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import com.silk.backend.routes.FileListResponse
import com.silk.backend.routes.FileUploadResponse
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendFileContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `file upload broadcasts stable file payload to live chat and history replay`() {
        TestWorkspace().use { workspace ->
            val host = assertNotNull(
                UserRepository.createUser(
                    loginName = "file-host",
                    fullName = "File Host",
                    phoneNumber = "13800000201",
                    passwordHash = "hash"
                )
            )
            val guest = assertNotNull(
                UserRepository.createUser(
                    loginName = "file-guest",
                    fullName = "File Guest",
                    phoneNumber = "13800000202",
                    passwordHash = "hash"
                )
            )
            val lateUser = assertNotNull(
                UserRepository.createUser(
                    loginName = "file-late",
                    fullName = "File Late",
                    phoneNumber = "13800000203",
                    passwordHash = "hash"
                )
            )
            val group = assertNotNull(
                GroupRepository.createGroup(
                    name = "File Upload Broadcast Group",
                    hostId = host.id
                )
            )
            assertTrue(GroupRepository.addUserToGroup(group.id, guest.id))
            assertTrue(GroupRepository.addUserToGroup(group.id, lateUser.id))

            testApplication {
                application { module() }

                val wsClient = createClient {
                    install(WebSockets)
                }
                val hostSession = wsClient.connectChat(
                    userId = host.id,
                    userName = host.fullName,
                    groupId = group.id
                )
                val guestSession = wsClient.connectChat(
                    userId = guest.id,
                    userName = guest.fullName,
                    groupId = group.id
                )

                val uploadResponse = uploadFile(
                    sessionId = group.id,
                    userId = host.id,
                    fileName = "release notes.md",
                    content = "payload from upload contract"
                )
                assertEquals(HttpStatusCode.OK, uploadResponse.status)
                val uploadBody = uploadResponse.decode<FileUploadResponse>()

                val hostFileMessage = hostSession.receiveMessageUntilType(MessageType.FILE)
                val guestFileMessage = guestSession.receiveMessageUntilType(MessageType.FILE)
                assertEquals(hostFileMessage.id, guestFileMessage.id)
                assertEquals(host.fullName, hostFileMessage.userName)
                assertEquals(host.fullName, guestFileMessage.userName)

                val expectedPayload = FileMessagePayload(
                    fileName = "release notes.md",
                    fileSize = "payload from upload contract".toByteArray().size.toLong(),
                    downloadUrl = "/api/files/download/${group.id}/${uploadBody.fileId}"
                )
                assertEquals(expectedPayload, parseFilePayload(hostFileMessage))
                assertEquals(expectedPayload, parseFilePayload(guestFileMessage))

                val persistedFile = File(
                    workspace.chatHistoryDir,
                    "group_${group.id}/uploads/${uploadBody.fileId}"
                )
                assertTrue(persistedFile.isFile)
                assertEquals("payload from upload contract", persistedFile.readText())

                val persistedHistory = assertNotNull(
                    ChatHistoryManager().loadChatHistory("group_${group.id}")
                )
                val fileEntry = assertNotNull(
                    persistedHistory.messages.lastOrNull { it.messageId == hostFileMessage.id }
                )
                assertEquals(MessageType.FILE.name, fileEntry.messageType)
                assertEquals(hostFileMessage.content, fileEntry.content)

                val lateSession = wsClient.connectChat(
                    userId = lateUser.id,
                    userName = lateUser.fullName,
                    groupId = group.id
                )
                val lateHistory = lateSession.receiveHistory()
                val lateReplay = assertNotNull(lateHistory.firstOrNull { it.type == MessageType.FILE })
                assertEquals(hostFileMessage.id, lateReplay.id)
                assertEquals(expectedPayload, parseFilePayload(lateReplay))
                assertEquals(host.fullName, lateReplay.userName)
            }
        }
    }

    @Test
    fun `file routes preserve upload list download and delete contract in isolated workspace`() {
        TestWorkspace().use { workspace ->
            testApplication {
                application { module() }

                val sessionId = "ci-files-smoke"

                val firstUpload = uploadFile(
                    sessionId = sessionId,
                    userId = "file-user",
                    fileName = "release-notes.txt",
                    content = "first file content"
                )
                assertEquals(HttpStatusCode.OK, firstUpload.status)
                val firstBody = firstUpload.decode<FileUploadResponse>()
                assertTrue(firstBody.success)
                assertEquals("release-notes.txt", firstBody.fileId)
                assertEquals("/api/files/download/$sessionId/release-notes.txt", firstBody.downloadUrl)
                assertTrue(firstBody.filePath.startsWith(workspace.chatHistoryDir.absolutePath))

                val secondUpload = uploadFile(
                    sessionId = sessionId,
                    userId = "file-user",
                    fileName = "release-notes.txt",
                    content = "second file content"
                )
                assertEquals(HttpStatusCode.OK, secondUpload.status)
                val secondBody = secondUpload.decode<FileUploadResponse>()
                assertTrue(secondBody.success)
                assertEquals("release-notes(1).txt", secondBody.fileId)

                val uploadsDir = File(workspace.chatHistoryDir, "group_$sessionId/uploads")
                assertTrue(File(uploadsDir, "release-notes.txt").isFile)
                assertTrue(File(uploadsDir, "release-notes(1).txt").isFile)
                File(uploadsDir, "processed_urls.txt").writeText(
                    """
                    https://example.com/one

                    https://example.com/two
                    """.trimIndent()
                )

                val listResponse = client.get("/api/files/list/$sessionId")
                assertEquals(HttpStatusCode.OK, listResponse.status)
                val listBody = listResponse.decode<FileListResponse>()
                assertEquals(sessionId, listBody.sessionId)
                assertEquals(2, listBody.totalCount)
                assertEquals(
                    setOf("release-notes.txt", "release-notes(1).txt"),
                    listBody.files.map { it.fileId }.toSet()
                )
                assertEquals(
                    listOf("https://example.com/one", "https://example.com/two"),
                    listBody.processedUrls
                )

                val downloadResponse = client.get("/api/files/download/$sessionId/${firstBody.fileId}")
                assertEquals(HttpStatusCode.OK, downloadResponse.status)
                assertEquals("first file content", downloadResponse.bodyAsText())
                val contentDisposition = assertNotNull(
                    downloadResponse.headers[HttpHeaders.ContentDisposition]
                )
                assertTrue(contentDisposition.contains("release-notes.txt"))

                val deleteResponse = client.delete("/api/files/$sessionId/${firstBody.fileId}")
                assertEquals(HttpStatusCode.OK, deleteResponse.status)
                val deleteBody = deleteResponse.decode<SimpleResponse>()
                assertTrue(deleteBody.success)
                assertEquals("File deleted", deleteBody.message)
                assertFalse(File(uploadsDir, firstBody.fileId).exists())
                assertTrue(File(uploadsDir, secondBody.fileId).isFile)

                val listAfterDelete = client.get("/api/files/list/$sessionId")
                    .decode<FileListResponse>()
                assertEquals(1, listAfterDelete.totalCount)
                assertEquals(listOf(secondBody.fileId), listAfterDelete.files.map { it.fileId })
            }
        }
    }

    private suspend fun ApplicationTestBuilder.uploadFile(
        sessionId: String,
        userId: String,
        fileName: String,
        content: String
    ): HttpResponse = client.submitFormWithBinaryData(
        url = "/api/files/upload",
        formData = formData {
            append("sessionId", sessionId)
            append("userId", userId)
            append(
                key = "file",
                value = content.toByteArray(),
                headers = Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                }
            )
        }
    )

    private suspend fun HttpClient.connectChat(
        userId: String,
        userName: String,
        groupId: String
    ): DefaultClientWebSocketSession = webSocketSession {
        url("/chat?userId=$userId&userName=$userName&groupId=$groupId")
    }

    private suspend fun DefaultClientWebSocketSession.receiveHistory(): List<Message> = buildList {
        withTimeout(5_000) {
            while (true) {
                val msg = receiveMessage()
                if (msg.isTransient && msg.type == MessageType.SYSTEM && msg.content == "__history_end__") break
                add(msg)
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveMessageUntilType(type: MessageType): Message =
        withTimeout(5_000) {
            while (true) {
                val message = receiveMessage()
                if (message.type == type) {
                    return@withTimeout message
                }
            }
            error("unreachable")
        }

    private val frameBuffer = ArrayDeque<Message>()

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): Message {
        if (frameBuffer.isNotEmpty()) return frameBuffer.removeFirst()
        val frame = withTimeout(5_000) { incoming.receive() }
        return when (frame) {
            is Frame.Text -> {
                val text = frame.readText()
                if (text.startsWith("[")) {
                    val batch: List<Message> = json.decodeFromString(text)
                    if (batch.isEmpty()) error("Empty batch frame")
                    batch.drop(1).forEach { frameBuffer.addLast(it) }
                    batch.first()
                } else {
                    json.decodeFromString(text)
                }
            }
            else -> error("Expected text frame but received $frame")
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveMessageOrNull(timeoutMillis: Long): Message? {
        if (frameBuffer.isNotEmpty()) return frameBuffer.removeFirst()
        val frame = withTimeoutOrNull(timeoutMillis) { incoming.receive() } ?: return null
        return when (frame) {
            is Frame.Text -> {
                val text = frame.readText()
                if (text.startsWith("[")) {
                    val batch: List<Message> = json.decodeFromString(text)
                    if (batch.isEmpty()) return null
                    batch.drop(1).forEach { frameBuffer.addLast(it) }
                    batch.first()
                } else {
                    json.decodeFromString(text)
                }
            }
            else -> error("Expected text frame but received $frame")
        }
    }

    private fun parseFilePayload(message: Message): FileMessagePayload {
        assertEquals(MessageType.FILE, message.type)
        return json.decodeFromString(message.content)
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T =
        json.decodeFromString(bodyAsText())
}
