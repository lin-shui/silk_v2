package com.silk.backend.git

import com.silk.backend.routes.gitRoutes
import com.silk.backend.workflow.WorkflowManager
import com.silk.backend.workspace.WorkspaceManager
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GitWebhookRouteContractTest {
    @Test
    fun `webhook requires HMAC and deduplicates delivery without JWT`() = testApplication {
        val root = Files.createTempDirectory("git-webhook").toFile()
        val key = ByteArray(32) { it.toByte() }
        val store = GitEventStore(root.absolutePath)
        val secret = "webhook-secret"
        store.putBinding(
            RoomGitBinding(
                roomId = "room",
                owner = "octo",
                repo = "demo",
                hookId = 12,
                webhookUrl = "https://silk.test/api/git/webhook/room",
                tokenEncrypted = GitEncryption.encrypt("pat", key),
                webhookSecretEncrypted = GitEncryption.encrypt(secret, key),
                createdBy = "owner",
                createdAt = 1,
                updatedAt = 1,
            )
        )
        application {
            routing {
                gitRoutes(
                    workflowManager = WorkflowManager(root.absolutePath),
                    workspaceManager = WorkspaceManager(root.absolutePath),
                    store = store,
                    encryptionKeyProvider = { key },
                )
            }
        }
        val body = """{"action":"opened","repository":{"full_name":"octo/demo"},"issue":{"number":1,"title":"Bug","html_url":"https://github.com/octo/demo/issues/1"}}""".toByteArray()
        val signature = GitHubWebhookVerifier.signature(body, secret)
        val first = client.post("/api/git/webhook/room") {
            contentType(ContentType.Application.Json)
            header("X-Hub-Signature-256", signature)
            header("X-GitHub-Event", "issues")
            header("X-GitHub-Delivery", "delivery-1")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Accepted, first.status)
        assertEquals(1, store.listEvents("room").size)

        val duplicate = client.post("/api/git/webhook/room") {
            contentType(ContentType.Application.Json)
            header("X-Hub-Signature-256", signature)
            header("X-GitHub-Event", "issues")
            header("X-GitHub-Delivery", "delivery-1")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Accepted, duplicate.status)
        assertEquals(1, store.listEvents("room").size)

        val invalid = client.post("/api/git/webhook/room") {
            contentType(ContentType.Application.Json)
            header("X-Hub-Signature-256", GitHubWebhookVerifier.signature(body, "wrong"))
            header("X-GitHub-Event", "issues")
            header("X-GitHub-Delivery", "delivery-2")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        assertEquals(1, store.listEvents("room").size)
        root.deleteRecursively()
    }
}
