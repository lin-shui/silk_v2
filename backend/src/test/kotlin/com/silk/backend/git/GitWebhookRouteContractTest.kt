package com.silk.backend.git

import com.silk.backend.MessageScope
import com.silk.backend.MessageType
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
    fun `inactive binding rejects webhook regardless of valid signature`() = testApplication {
        val root = Files.createTempDirectory("git-webhook-inactive").toFile()
        val key = ByteArray(32) { it.toByte() }
        val store = GitEventStore(root.absolutePath)
        val secret = "webhook-secret"
        store.putBinding(
            RoomGitBinding(
                roomId = "room",
                owner = "octo",
                repo = "demo",
                hookId = 7,
                webhookUrl = "https://silk.test/api/git/webhook/room",
                tokenEncrypted = GitEncryption.encrypt("pat", key),
                webhookSecretEncrypted = GitEncryption.encrypt(secret, key),
                createdBy = "owner",
                createdAt = 1,
                updatedAt = 1,
                status = GitBindingStatus.ERROR,
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
        val response = client.post("/api/git/webhook/room") {
            contentType(ContentType.Application.Json)
            header("X-Hub-Signature-256", GitHubWebhookVerifier.signature(body, secret))
            header("X-GitHub-Event", "issues")
            header("X-GitHub-Delivery", "delivery-inactive")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, store.listEvents("room").size)
        root.deleteRecursively()
    }

    @Test
    fun `hook id mismatch rejects webhook even with valid signature`() = testApplication {
        val root = Files.createTempDirectory("git-webhook-hookid").toFile()
        val key = ByteArray(32) { it.toByte() }
        val store = GitEventStore(root.absolutePath)
        val secret = "webhook-secret"
        store.putBinding(
            RoomGitBinding(
                roomId = "room",
                owner = "octo",
                repo = "demo",
                hookId = 42,
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
        val body = """{"action":"opened","repository":{"full_name":"octo/demo"},"issue":{"number":2,"title":"Bug2","html_url":"https://github.com/octo/demo/issues/2"}}""".toByteArray()
        val wrongHookId = client.post("/api/git/webhook/room") {
            contentType(ContentType.Application.Json)
            header("X-Hub-Signature-256", GitHubWebhookVerifier.signature(body, secret))
            header("X-GitHub-Event", "issues")
            header("X-GitHub-Delivery", "delivery-hookid")
            header("X-GitHub-Hook-ID", "99")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongHookId.status)
        assertEquals(0, store.listEvents("room").size)

        val correctHookId = client.post("/api/git/webhook/room") {
            contentType(ContentType.Application.Json)
            header("X-Hub-Signature-256", GitHubWebhookVerifier.signature(body, secret))
            header("X-GitHub-Event", "issues")
            header("X-GitHub-Delivery", "delivery-hookid-ok")
            header("X-GitHub-Hook-ID", "42")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Accepted, correctHookId.status)
        assertEquals(1, store.listEvents("room").size)
        root.deleteRecursively()
    }

    @Test
    fun `valid webhook dispatches event to onEvent with TEAM scope card`() = testApplication {
        val root = Files.createTempDirectory("git-webhook-scope").toFile()
        val key = ByteArray(32) { it.toByte() }
        val store = GitEventStore(root.absolutePath)
        val secret = "webhook-secret"
        store.putBinding(
            RoomGitBinding(
                roomId = "room",
                owner = "octo",
                repo = "demo",
                hookId = null,
                webhookUrl = "https://silk.test/api/git/webhook/room",
                tokenEncrypted = GitEncryption.encrypt("pat", key),
                webhookSecretEncrypted = GitEncryption.encrypt(secret, key),
                createdBy = "owner",
                createdAt = 1,
                updatedAt = 1,
            )
        )
        val capturedEvents = mutableListOf<GitEventRecord>()
        application {
            routing {
                gitRoutes(
                    workflowManager = WorkflowManager(root.absolutePath),
                    workspaceManager = WorkspaceManager(root.absolutePath),
                    store = store,
                    encryptionKeyProvider = { key },
                    onEvent = { event -> capturedEvents.add(event) },
                )
            }
        }
        val body = """{"action":"opened","repository":{"full_name":"octo/demo"},"issue":{"number":3,"title":"Scope Test","html_url":"https://github.com/octo/demo/issues/3"}}""".toByteArray()
        val response = client.post("/api/git/webhook/room") {
            contentType(ContentType.Application.Json)
            header("X-Hub-Signature-256", GitHubWebhookVerifier.signature(body, secret))
            header("X-GitHub-Event", "issues")
            header("X-GitHub-Delivery", "delivery-scope")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        // Give the async callback a moment to run
        kotlinx.coroutines.delay(200)
        assertEquals(1, capturedEvents.size)
        val message = GitEventBroadcaster.message(capturedEvents.first())
        assertEquals(com.silk.backend.MessageScope.TEAM, message.scope)
        assertEquals(com.silk.backend.MessageType.CARD, message.type)
        assertEquals("github_bot", message.userId)
        root.deleteRecursively()
    }

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
