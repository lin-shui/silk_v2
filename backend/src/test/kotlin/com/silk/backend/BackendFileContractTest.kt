package com.silk.backend

import com.silk.backend.database.SimpleResponse
import com.silk.backend.routes.FileListResponse
import com.silk.backend.routes.FileUploadResponse
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackendFileContractTest {
    private val json = Json { ignoreUnknownKeys = true }

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

    private suspend inline fun <reified T> HttpResponse.decode(): T =
        json.decodeFromString(bodyAsText())
}
