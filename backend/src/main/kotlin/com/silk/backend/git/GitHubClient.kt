@file:Suppress("ConstructorParameterNaming")

package com.silk.backend.git

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.io.Closeable

@Serializable
data class GitHubRepositoryInfo(
    val full_name: String = "",
    val html_url: String = "",
)

@Serializable
data class GitHubHookConfig(
    val url: String = "",
    val content_type: String = "json",
    val insecure_ssl: String = "0",
    val secret: String? = null,
)

@Serializable
data class GitHubHook(
    val id: Long = 0,
    val active: Boolean = false,
    val events: List<String> = emptyList(),
    val config: GitHubHookConfig = GitHubHookConfig(),
)

@Serializable
data class GitHubHookRequest(
    val name: String = "web",
    val active: Boolean = true,
    val events: List<String> = GitHubClient.EVENTS,
    val config: GitHubHookConfig,
)

@Serializable
data class GitHubIssueResponse(
    val number: Int = 0,
    val title: String = "",
    val body: String? = null,
    val html_url: String = "",
    val state: String = "",
    val user: GitHubUser = GitHubUser(),
    val labels: List<GitHubLabel> = emptyList(),
)

@Serializable
data class GitHubUser(val login: String = "")

@Serializable
data class GitHubLabel(val name: String = "")

@Serializable
data class GitHubCommentResponse(
    val body: String? = null,
    val user: GitHubUser = GitHubUser(),
    val created_at: String = "",
)

@Serializable
data class GitHubIssueListItem(
    val number: Int = 0,
    val title: String = "",
    val html_url: String = "",
    val state: String = "",
    val state_reason: String? = null,
    val created_at: String = "",
    val updated_at: String = "",
    val closed_at: String? = null,
    val user: GitHubUser = GitHubUser(),
    val labels: List<GitHubLabel> = emptyList(),
    /** Present on items that are pull requests. */
    val pull_request: GitHubPrRef? = null,
)

@Serializable
data class GitHubPrRef(
    val url: String = "",
    val merged_at: String? = null,
)

/** Result of a conditional GET. `request()` is NOT involved; this path is polling-only. */
sealed interface ConditionalGetResult {
    data class Modified(val etag: String?, val items: List<GitHubIssueListItem>) : ConditionalGetResult
    /** GitHub returned 304 — no quota consumed. */
    data object NotModified : ConditionalGetResult
}

class GitHubApiException(val status: HttpStatusCode, message: String) : RuntimeException(message)

data class GitHubHookReconcileResult(val hook: GitHubHook, val created: Boolean)

/** Small REST client with centralized auth headers and bounded transient retries. */
class GitHubClient(
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = GitConfig.apiTimeoutMs
            connectTimeoutMillis = GitConfig.apiTimeoutMs
            socketTimeoutMillis = GitConfig.apiTimeoutMs
        }
    },
    private val apiBaseUrl: String = GitConfig.githubApiBaseUrl,
    private val maxRetries: Int = 2,
) : Closeable {
    companion object {
        val EVENTS = listOf("issues", "pull_request", "check_run")
        private const val API_VERSION = "2022-11-28"
    }

    suspend fun getRepository(ref: GitHubRepositoryRef, token: String): GitHubRepositoryInfo =
        request("GET", "/repos/${ref.owner}/${ref.repo}", token).body()

    suspend fun listHooks(ref: GitHubRepositoryRef, token: String): List<GitHubHook> =
        request("GET", "/repos/${ref.owner}/${ref.repo}/hooks", token).body()

    suspend fun createHook(ref: GitHubRepositoryRef, token: String, callbackUrl: String, secret: String): GitHubHook =
        request("POST", "/repos/${ref.owner}/${ref.repo}/hooks", token) {
            setBody(hookRequest(callbackUrl, secret))
        }.body()

    suspend fun updateHook(ref: GitHubRepositoryRef, hookId: Long, token: String, callbackUrl: String, secret: String): GitHubHook =
        request("PATCH", "/repos/${ref.owner}/${ref.repo}/hooks/$hookId", token) {
            setBody(hookRequest(callbackUrl, secret))
        }.body()

    suspend fun deleteHook(ref: GitHubRepositoryRef, hookId: Long, token: String) {
        val response = request("DELETE", "/repos/${ref.owner}/${ref.repo}/hooks/$hookId", token, retries = 0)
        if (response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.NotFound) {
            throw GitHubApiException(response.status, "GitHub webhook deletion failed")
        }
    }

    suspend fun reconcileHook(ref: GitHubRepositoryRef, token: String, callbackUrl: String, secret: String): GitHubHookReconcileResult {
        val existing = listHooks(ref, token).firstOrNull { it.config.url.trimEnd('/') == callbackUrl.trimEnd('/') }
        return if (existing == null) {
            GitHubHookReconcileResult(createHook(ref, token, callbackUrl, secret), created = true)
        } else {
            GitHubHookReconcileResult(updateHook(ref, existing.id, token, callbackUrl, secret), created = false)
        }
    }

    suspend fun getIssue(ref: GitHubRepositoryRef, issueNumber: Int, token: String): GitHubIssueResponse =
        request("GET", "/repos/${ref.owner}/${ref.repo}/issues/$issueNumber", token).body()

    suspend fun listIssueComments(ref: GitHubRepositoryRef, issueNumber: Int, token: String): List<GitHubCommentResponse> =
        request("GET", "/repos/${ref.owner}/${ref.repo}/issues/$issueNumber/comments?per_page=20", token).body()

    override fun close() = client.close()

    /**
     * Polling-only conditional GET for /issues (returns both issues and PRs).
     * Uses a separate code path so the existing [request] method and all Hook
     * calls are completely unaffected.  A 304 response costs no primary quota.
     */
    suspend fun listIssuesAndPulls(
        ref: GitHubRepositoryRef,
        token: String,
        sinceEpochMs: Long,
        etag: String? = null,
    ): ConditionalGetResult {
        require(token.isNotBlank()) { "GitHub token must not be blank" }
        val since = java.time.Instant.ofEpochMilli(sinceEpochMs)
            .toString()          // ISO-8601, e.g. 2026-08-07T10:00:00Z
        val path = "/repos/${ref.owner}/${ref.repo}/issues" +
            "?since=$since&state=all&sort=updated&direction=desc&per_page=50"
        val response = client.get(url(path)) {
            headers(token)
            if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
        }
        if (response.status == HttpStatusCode.NotModified) return ConditionalGetResult.NotModified
        if (response.status.value !in 200..299) {
            throw GitHubApiException(response.status, "GitHub issues list failed (${response.status.value})")
        }
        val newEtag = response.headers[HttpHeaders.ETag]
        val items = response.body<List<GitHubIssueListItem>>()
        return ConditionalGetResult.Modified(newEtag, items)
    }

    private fun hookRequest(callbackUrl: String, secret: String) = GitHubHookRequest(
        config = GitHubHookConfig(url = callbackUrl, content_type = "json", insecure_ssl = "0", secret = secret),
    )

    private suspend fun request(
        method: String,
        path: String,
        token: String,
        retries: Int = maxRetries,
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        require(token.isNotBlank()) { "GitHub token must not be blank" }
        var attempt = 0
        while (true) {
            val response = when (method) {
                "GET" -> client.get(url(path)) { headers(token); configure() }
                "POST" -> client.post(url(path)) { headers(token); contentType(ContentType.Application.Json); configure() }
                "PATCH" -> client.patch(url(path)) { headers(token); contentType(ContentType.Application.Json); configure() }
                "PUT" -> client.put(url(path)) { headers(token); contentType(ContentType.Application.Json); configure() }
                "DELETE" -> client.delete(url(path)) { headers(token); configure() }
                else -> error("Unsupported GitHub HTTP method")
            }
            if (response.status.value in 200..299) return response
            val retryable = response.status.value == 429 || response.status.value in 500..599
            if (!retryable || attempt >= retries) {
                throw GitHubApiException(response.status, "GitHub API request failed (${response.status.value})")
            }
            attempt++
            delay(100L * attempt)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.headers(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", API_VERSION)
    }

    private fun url(path: String): String = apiBaseUrl.trimEnd('/') + path
}
