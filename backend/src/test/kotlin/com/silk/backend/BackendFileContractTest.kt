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
                    downloadUrl = buildFileDownloadUrl(group.id, uploadBody.fileId)
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
                val lateReplay = lateSession.receiveMessageUntilType(MessageType.FILE)
                assertEquals(hostFileMessage.id, lateReplay.id)
                assertEquals(expectedPayload, parseFilePayload(lateReplay))
                assertEquals(host.fullName, lateReplay.userName)
                assertNull(lateSession.receiveMessageOrNull(500))
            }
        }
    }

    @Test
    fun `file payload preserves special characters and exposes encoded download urls`() {
        TestWorkspace().use { workspace ->
            val host = assertNotNull(
                UserRepository.createUser(
                    loginName = "file-special-host",
                    fullName = "File Special Host",
                    phoneNumber = "13800000211",
                    passwordHash = "hash"
                )
            )
            val guest = assertNotNull(
                UserRepository.createUser(
                    loginName = "file-special-guest",
                    fullName = "File Special Guest",
                    phoneNumber = "13800000212",
                    passwordHash = "hash"
                )
            )
            val lateUser = assertNotNull(
                UserRepository.createUser(
                    loginName = "file-special-late",
                    fullName = "File Special Late",
                    phoneNumber = "13800000213",
                    passwordHash = "hash"
                )
            )
            val group = assertNotNull(
                GroupRepository.createGroup(
                    name = "File Special Character Group",
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

                val specialFileName = """care plan #1 ? "final".txt"""
                val uploadResponse = uploadFile(
                    sessionId = group.id,
                    userId = host.id,
                    fileName = specialFileName,
                    content = "special filename payload"
                )
                assertEquals(HttpStatusCode.OK, uploadResponse.status)
                val uploadBody = uploadResponse.decode<FileUploadResponse>()
                val expectedDownloadUrl = buildFileDownloadUrl(group.id, uploadBody.fileId)
                assertEquals(specialFileName, uploadBody.fileId)
                assertEquals(expectedDownloadUrl, uploadBody.downloadUrl)

                val hostFileMessage = hostSession.receiveMessageUntilType(MessageType.FILE)
                val guestFileMessage = guestSession.receiveMessageUntilType(MessageType.FILE)
                val expectedPayload = FileMessagePayload(
                    fileName = specialFileName,
                    fileSize = "special filename payload".toByteArray().size.toLong(),
                    downloadUrl = expectedDownloadUrl
                )
                assertEquals(expectedPayload, parseFilePayload(hostFileMessage))
                assertEquals(expectedPayload, parseFilePayload(guestFileMessage))
                assertTrue(hostFileMessage.content.contains("\\\"final\\\""))
                assertTrue(hostFileMessage.content.contains("%23"))
                assertTrue(hostFileMessage.content.contains("%3F"))

                val persistedFile = File(
                    workspace.chatHistoryDir,
                    "group_${group.id}/uploads/${uploadBody.fileId}"
                )
                assertTrue(persistedFile.isFile)
                assertEquals("special filename payload", persistedFile.readText())

                val listBody = client.get("/api/files/list/${group.id}")
                    .decode<FileListResponse>()
                assertEquals(listOf(uploadBody.fileId), listBody.files.map { it.fileId })
                assertEquals(listOf(expectedDownloadUrl), listBody.files.map { it.downloadUrl })

                val downloadResponse = client.get(uploadBody.downloadUrl)
                assertEquals(HttpStatusCode.OK, downloadResponse.status)
                assertEquals("special filename payload", downloadResponse.bodyAsText())

                val lateSession = wsClient.connectChat(
                    userId = lateUser.id,
                    userName = lateUser.fullName,
                    groupId = group.id
                )
                val lateReplay = lateSession.receiveMessageUntilType(MessageType.FILE)
                assertEquals(expectedPayload, parseFilePayload(lateReplay))
                assertEquals(hostFileMessage.id, lateReplay.id)
                assertNull(lateSession.receiveMessageOrNull(500))
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
                assertEquals(buildFileDownloadUrl(sessionId, "release-notes.txt"), firstBody.downloadUrl)
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

                val downloadResponse = client.get(firstBody.downloadUrl)
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
                    append(
                        HttpHeaders.ContentDisposition,
                        "filename=\"${fileName.escapeMultipartHeaderValue()}\""
                    )
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

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): Message {
        while (true) {
            val frame = withTimeout(5_000) { incoming.receive() }
            val message = when (frame) {
                is Frame.Text -> json.decodeFromString<Message>(frame.readText())
                else -> error("Expected text frame but received $frame")
            }
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
            val frame = withTimeoutOrNull(remainingMillis) { incoming.receive() } ?: return null
            val message = when (frame) {
                is Frame.Text -> json.decodeFromString<Message>(frame.readText())
                else -> error("Expected text frame but received $frame")
            }
            if (!message.isHistoryEndMarker()) {
                return message
            }
        }
    }

    private fun Message.isHistoryEndMarker(): Boolean =
        isTransient && type == MessageType.SYSTEM && content == "__history_end__"

    private fun parseFilePayload(message: Message): FileMessagePayload {
        assertEquals(MessageType.FILE, message.type)
        return json.decodeFromString(message.content)
    }

    private fun String.escapeMultipartHeaderValue(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    private suspend inline fun <reified T> HttpResponse.decode(): T =
        json.decodeFromString(bodyAsText())
}
