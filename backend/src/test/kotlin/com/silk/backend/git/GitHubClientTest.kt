package com.silk.backend.git

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.Headers
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubClientTest {
    @Test
    fun `reconcile uses exact callback and sends least event set`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val engine = MockEngine { request ->
            requests += request.method.value to request.url.toString()
            assertEquals("Bearer pat", request.headers[HttpHeaders.Authorization])
            assertEquals("application/vnd.github+json", request.headers[HttpHeaders.Accept])
            assertEquals("2022-11-28", request.headers["X-GitHub-Api-Version"])
            when {
                request.url.encodedPath.endsWith("/hooks") -> respond(
                    "[{\"id\":12,\"active\":true,\"events\":[\"issues\"],\"config\":{\"url\":\"https://silk.test/api/git/webhook/room\"}}]",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.method.value == "PATCH" -> respond(
                    "{\"id\":12,\"active\":true,\"events\":[\"issues\",\"pull_request\",\"check_run\"],\"config\":{\"url\":\"https://silk.test/api/git/webhook/room\"}}",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        }
        val client = GitHubClient(http, "https://api.github.test", maxRetries = 0)
        val result = client.reconcileHook(
            GitHubRepositoryRef("octo", "demo"),
            "pat",
            "https://silk.test/api/git/webhook/room",
            "secret",
        )
        assertEquals(12, result.hook.id)
        assertTrue(!result.created)
        assertEquals(listOf("GET" to "https://api.github.test/repos/octo/demo/hooks", "PATCH" to "https://api.github.test/repos/octo/demo/hooks/12"), requests)
        client.close()
    }

    @Test
    fun `issue polling sends conditional header and exposes pagination and rate limits`() = runTest {
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            assertTrue(request.url.toString().contains("state=all&sort=updated&direction=asc"))
            assertTrue(request.url.toString().contains("since=2026-08-08T00:00:00Z"))
            when (requestCount) {
                1 -> respond(
                    """[{"id":1,"number":7,"title":"Bug","state":"open","html_url":"https://github.com/octo/demo/issues/7","created_at":"2026-08-08T00:00:00Z","updated_at":"2026-08-08T00:01:00Z"}]""",
                    HttpStatusCode.OK,
                    Headers.build {
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        append(HttpHeaders.ETag, "\"etag-1\"")
                        append(HttpHeaders.Link, "<https://api.github.test/repos/octo/demo/issues?page=2>; rel=\"next\"")
                        append("X-RateLimit-Limit", "5000")
                        append("X-RateLimit-Remaining", "4999")
                        append("X-RateLimit-Reset", "1800000000")
                    },
                )
                2 -> {
                    assertEquals("\"etag-1\"", request.headers[HttpHeaders.IfNoneMatch])
                    respond("", HttpStatusCode.NotModified)
                }
                else -> error("unexpected request")
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        }
        val client = GitHubClient(http, "https://api.github.test", maxRetries = 0)
        val first = client.listIssues(
            GitHubRepositoryRef("octo", "demo"),
            "pat",
            since = "2026-08-08T00:00:00Z",
        )
        assertEquals(7, first.items.single().number)
        assertEquals(2, first.nextPage)
        assertEquals(5000, first.rateLimitLimit)
        assertEquals(4_999, first.rateLimitRemaining)
        assertEquals(1_800_000_000_000L, first.rateLimitResetAt)

        val second = client.listIssues(
            GitHubRepositoryRef("octo", "demo"),
            "pat",
            since = "2026-08-08T00:00:00Z",
            etag = first.etag,
        )
        assertTrue(second.notModified)
        assertTrue(second.items.isEmpty())
        client.close()
    }
}
