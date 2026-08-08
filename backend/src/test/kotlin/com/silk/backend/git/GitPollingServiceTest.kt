package com.silk.backend.git

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitPollingServiceTest {
    @Test
    fun `baseline reads existing resources across issue list pages`() = runTest {
        val root = Files.createTempDirectory("git-polling-baseline-pages").toFile()
        val key = ByteArray(32) { 2 }
        val clock = 1_786_147_200_000L
        val pages = mutableListOf<Int>()
        val engine = MockEngine { request ->
            val page = request.url.parameters["page"]!!.toInt()
            pages += page
            if (page == 1) {
                respondJson(
                    """[{"id":1,"number":1,"title":"Existing issue","state":"open","html_url":"https://github.com/octo/demo/issues/1","created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-07T23:59:00Z"}]""",
                    link = "<https://api.github.test/repos/octo/demo/issues?page=2>; rel=\"next\"",
                )
            } else {
                respondJson(
                    """[{"id":2,"number":2,"title":"Existing PR","state":"closed","html_url":"https://github.com/octo/demo/pull/2","created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-07T23:58:00Z","pull_request":{"url":"https://api.github.test/repos/octo/demo/pulls/2"}}]""",
                )
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = GitHubClient(http, "https://api.github.test", maxRetries = 0)
        val service = GitPollingService(
            store = GitEventStore(root.absolutePath),
            githubClient = client,
            onEvent = {},
            encryptionKeyProvider = { key },
            now = { clock },
            maxPagesPerPoll = 2,
            jitter = { 0 },
        )

        val baseline = service.prepareBaseline(GitHubRepositoryRef("octo", "demo"), "pat")

        assertEquals(listOf(1, 2), pages)
        assertEquals(setOf("issues:1", "pull_request:2"), baseline.snapshots.map { it.resourceKey }.toSet())
        assertEquals(2, baseline.state.pagesToPoll)
        client.close()
        root.deleteRecursively()
    }

    @Test
    fun `baseline is silent and later issue and pull request changes share event pipeline`() = runTest {
        val root = Files.createTempDirectory("git-polling-service").toFile()
        val key = ByteArray(32) { it.toByte() }
        var clock = 1_786_147_200_000L // 2026-08-08T00:00:00Z
        var listRequest = 0
        val conditionalHeaders = mutableListOf<String?>()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/pulls/2") -> respondJson(
                    """{"id":22,"number":2,"title":"Add polling","state":"open","html_url":"https://github.com/octo/demo/pull/2","created_at":"2026-08-08T00:01:30Z","updated_at":"2026-08-08T00:01:30Z","draft":false,"head":{"ref":"polling","sha":"abc"},"base":{"ref":"main","sha":"def"}}"""
                )
                request.url.encodedPath.endsWith("/issues") -> {
                    listRequest++
                    conditionalHeaders += request.headers[HttpHeaders.IfNoneMatch]
                    when (listRequest) {
                        1 -> respondJson(
                            """[
                                {"id":1,"number":1,"title":"Existing","state":"open","html_url":"https://github.com/octo/demo/issues/1","comments":0,"created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-07T23:59:00Z"},
                                {"id":3,"number":3,"title":"During baseline","state":"open","html_url":"https://github.com/octo/demo/issues/3","comments":0,"created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-08T00:00:01Z"}
                            ]""".trimIndent(),
                            etag = "\"baseline\"",
                        )
                        2 -> respondJson(
                            """[
                                {"id":1,"number":1,"title":"Existing","state":"closed","html_url":"https://github.com/octo/demo/issues/1","comments":0,"created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-08T00:01:00Z"},
                                {"id":2,"number":2,"title":"Add polling","state":"open","html_url":"https://github.com/octo/demo/pull/2","comments":0,"created_at":"2026-08-08T00:01:30Z","updated_at":"2026-08-08T00:01:30Z","pull_request":{"url":"https://api.github.test/repos/octo/demo/pulls/2"}},
                                {"id":3,"number":3,"title":"During baseline","state":"open","html_url":"https://github.com/octo/demo/issues/3","comments":0,"created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-08T00:00:01Z"}
                            ]""".trimIndent(),
                            etag = "\"changes\"",
                        )
                        3 -> respondJson("[]", etag = "\"quiet\"")
                        4 -> respond("", HttpStatusCode.NotModified)
                        else -> error("unexpected issue list request $listRequest")
                    }
                }
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        }
        val client = GitHubClient(http, "https://api.github.test", maxRetries = 0)
        val store = GitEventStore(root.absolutePath, now = { clock })
        val delivered = mutableListOf<GitEventRecord>()
        val service = GitPollingService(
            store = store,
            githubClient = client,
            onEvent = { delivered += it },
            encryptionKeyProvider = { key },
            now = { clock },
            intervalMs = 120_000,
            jitter = { 0 },
        )
        val ref = GitHubRepositoryRef("octo", "demo")
        val baseline = service.prepareBaseline(ref, "pat")
        assertEquals(listOf("issues:1"), baseline.snapshots.map { it.resourceKey })
        val binding = RoomGitBinding(
            roomId = "room",
            owner = "octo",
            repo = "demo",
            tokenEncrypted = GitEncryption.encrypt("pat", key),
            createdBy = "owner",
            createdAt = clock,
            updatedAt = clock,
            ingestionMode = GitIngestionMode.POLLING,
        )
        store.putPollingBinding(binding, baseline.state, baseline.snapshots)
        assertTrue(store.listEvents("room").isEmpty())

        clock += 120_000
        val changed = service.pollOnce(binding, force = true)
        assertEquals(3, changed.eventCount)
        assertEquals(setOf("closed", "opened", "updated"), delivered.map { it.action }.toSet())
        assertEquals(setOf("issues", "pull_request"), delivered.map { it.event }.toSet())
        assertTrue(store.pendingEvents("room").isEmpty())
        assertTrue(store.listEvents("room").all { it.source == GitEventSource.POLLING && it.delivered })

        clock += 120_000
        val quiet200 = service.pollOnce(binding, force = true)
        assertEquals(0, quiet200.eventCount)
        assertTrue(!quiet200.notModified)

        clock += 120_000
        val quiet304 = service.pollOnce(binding, force = true)
        assertTrue(quiet304.notModified)
        assertEquals("\"quiet\"", conditionalHeaders.last())
        assertNull(quiet304.error)
        client.close()
        root.deleteRecursively()
    }

    @Test
    fun `rate limit failure keeps cursor and honors reset`() = runTest {
        val root = Files.createTempDirectory("git-polling-rate").toFile()
        val key = ByteArray(32) { 3 }
        val clock = 1_786_147_200_000L
        val resetAtSeconds = clock / 1_000 + 600
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(
                "",
                HttpStatusCode.TooManyRequests,
                Headers.build {
                    append("X-RateLimit-Remaining", "0")
                    append("X-RateLimit-Reset", resetAtSeconds.toString())
                    append(HttpHeaders.RetryAfter, "300")
                },
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = GitHubClient(http, "https://api.github.test", maxRetries = 0)
        val store = GitEventStore(root.absolutePath, now = { clock })
        val initial = GitPollingState(
            cursor = "2026-08-08T00:00:00Z",
            nextPollAt = clock,
            baselineCompleted = true,
        )
        val binding = RoomGitBinding(
            roomId = "room",
            owner = "octo",
            repo = "demo",
            tokenEncrypted = GitEncryption.encrypt("pat", key),
            createdBy = "owner",
            createdAt = clock,
            updatedAt = clock,
            ingestionMode = GitIngestionMode.POLLING,
        )
        store.putPollingBinding(binding, initial, emptyList())
        val service = GitPollingService(
            store,
            client,
            onEvent = {},
            encryptionKeyProvider = { key },
            now = { clock },
            jitter = { 0 },
        )

        val result = service.pollOnce(binding, force = true)
        assertEquals("GitHub API 请求受限", result.error)
        assertEquals(initial.cursor, store.getPollingState("room").cursor)
        assertTrue(store.getPollingState("room").nextPollAt!! >= resetAtSeconds * 1_000)

        val secondBinding = binding.copy(roomId = "room-2")
        store.putPollingBinding(secondBinding, initial, emptyList())
        val sharedTokenBlocked = service.pollOnce(secondBinding)
        assertFalse(sharedTokenBlocked.polled)
        assertEquals(1, requests, "a PAT at zero quota must not trigger another repository request")
        client.close()
        root.deleteRecursively()
    }

    @Test
    fun `polling to webhook migration creates hook before final catch up`() = runTest {
        val root = Files.createTempDirectory("git-polling-migrate-webhook").toFile()
        val key = ByteArray(32) { 4 }
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls += "${request.method.value} ${request.url.encodedPath}"
            when {
                request.url.encodedPath.endsWith("/hooks") && request.method == HttpMethod.Get -> respondJson("[]")
                request.url.encodedPath.endsWith("/hooks") && request.method == HttpMethod.Post -> respondJson(
                    """{"id":42,"active":true,"events":["issues","pull_request","check_run"],"config":{"url":"https://silk.example/api/git/webhook/room"}}"""
                )
                request.url.encodedPath.endsWith("/issues") -> respondJson("[]", etag = "\"caught-up\"")
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        }
        val client = GitHubClient(http, "https://api.github.test", maxRetries = 0)
        val store = GitEventStore(root.absolutePath)
        val binding = RoomGitBinding(
            roomId = "room",
            owner = "octo",
            repo = "demo",
            tokenEncrypted = GitEncryption.encrypt("pat", key),
            createdBy = "owner",
            createdAt = 1,
            updatedAt = 1,
            ingestionMode = GitIngestionMode.POLLING,
        )
        store.putPollingBinding(
            binding,
            GitPollingState(cursor = "2026-08-08T00:00:00Z", baselineCompleted = true),
            emptyList(),
        )
        val service = GitPollingService(
            store,
            client,
            onEvent = {},
            encryptionKeyProvider = { key },
            now = { 1_786_147_320_000L },
            jitter = { 0 },
        )

        assertTrue(
            service.reconcileConfiguredMode(
                binding,
                GitIngestionSelection(GitIngestionMode.WEBHOOK, "https://silk.example"),
            )
        )
        assertEquals(
            listOf("GET /repos/octo/demo/hooks", "POST /repos/octo/demo/hooks", "GET /repos/octo/demo/issues"),
            calls,
        )
        assertEquals(GitIngestionMode.WEBHOOK, store.getBinding("room")?.ingestionMode)
        assertEquals(42, store.getBinding("room")?.hookId)
        assertFalse(store.getPollingState("room").baselineCompleted)
        client.close()
        root.deleteRecursively()
    }

    @Test
    fun `webhook callback changes replace and clean up the previous hook`() = runTest {
        val root = Files.createTempDirectory("git-polling-callback-migration").toFile()
        val key = ByteArray(32) { 5 }
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls += "${request.method.value} ${request.url.encodedPath}"
            when {
                request.url.encodedPath.endsWith("/hooks") && request.method == HttpMethod.Get -> respondJson("[]")
                request.url.encodedPath.endsWith("/hooks") && request.method == HttpMethod.Post -> respondJson(
                    """{"id":42,"active":true,"events":["issues","pull_request","check_run"],"config":{"url":"https://new.example/api/git/webhook/room"}}"""
                )
                request.url.encodedPath.endsWith("/hooks/7") && request.method == HttpMethod.Delete ->
                    respond("", HttpStatusCode.NoContent)
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        }
        val client = GitHubClient(http, "https://api.github.test", maxRetries = 0)
        val store = GitEventStore(root.absolutePath)
        val binding = RoomGitBinding(
            roomId = "room",
            owner = "octo",
            repo = "demo",
            hookId = 7,
            webhookUrl = "https://old.example/api/git/webhook/room",
            tokenEncrypted = GitEncryption.encrypt("pat", key),
            webhookSecretEncrypted = GitEncryption.encrypt("old-secret", key),
            createdBy = "owner",
            createdAt = 1,
            updatedAt = 1,
            ingestionMode = GitIngestionMode.WEBHOOK,
        )
        store.putWebhookBinding(binding)
        val service = GitPollingService(store, client, onEvent = {}, encryptionKeyProvider = { key })

        assertTrue(service.reconcileConfiguredMode(binding, GitIngestionSelection(GitIngestionMode.WEBHOOK, "https://new.example")))
        assertEquals(
            listOf(
                "GET /repos/octo/demo/hooks",
                "POST /repos/octo/demo/hooks",
                "DELETE /repos/octo/demo/hooks/7",
            ),
            calls,
        )
        assertEquals(42, store.getBinding("room")?.hookId)
        assertEquals("https://new.example/api/git/webhook/room", store.getBinding("room")?.webhookUrl)
        client.close()
        root.deleteRecursively()
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        body: String,
        etag: String? = null,
        link: String? = null,
    ) = respond(
        body,
        HttpStatusCode.OK,
        Headers.build {
            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            etag?.let { append(HttpHeaders.ETag, it) }
            link?.let { append(HttpHeaders.Link, it) }
            append("X-RateLimit-Limit", "5000")
            append("X-RateLimit-Remaining", "4999")
            append("X-RateLimit-Reset", "1800000000")
        },
    )
}
