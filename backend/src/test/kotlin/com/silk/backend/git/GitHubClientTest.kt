package com.silk.backend.git

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
}
