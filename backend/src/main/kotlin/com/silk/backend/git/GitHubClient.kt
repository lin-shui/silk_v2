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
import java.net.URI

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
data class GitHubPullRequestMarker(
    val url: String = "",
    val html_url: String = "",
)

@Serializable
data class GitHubIssueListItem(
    val id: Long = 0,
    val number: Int = 0,
    val title: String = "",
    val body: String? = null,
    val html_url: String = "",
    val state: String = "",
    val user: GitHubUser = GitHubUser(),
    val labels: List<GitHubLabel> = emptyList(),
    val comments: Int = 0,
    val created_at: String = "",
    val updated_at: String = "",
    val closed_at: String? = null,
    val draft: Boolean? = null,
    val pull_request: GitHubPullRequestMarker? = null,
)

@Serializable
data class GitHubBranch(
    val ref: String = "",
    val sha: String = "",
)

@Serializable
data class GitHubPullRequestResponse(
    val id: Long = 0,
    val number: Int = 0,
    val title: String = "",
    val body: String? = null,
    val html_url: String = "",
    val state: String = "",
    val user: GitHubUser = GitHubUser(),
    val labels: List<GitHubLabel> = emptyList(),
    val comments: Int = 0,
    val created_at: String = "",
    val updated_at: String = "",
    val closed_at: String? = null,
    val merged_at: String? = null,
    val draft: Boolean? = null,
    val head: GitHubBranch = GitHubBranch(),
    val base: GitHubBranch = GitHubBranch(),
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

class GitHubApiException(
    val status: HttpStatusCode,
    message: String,
    val rateLimitRemaining: Int? = null,
    val rateLimitResetAt: Long? = null,
    val retryAfterMs: Long? = null,
) : RuntimeException(message)

data class GitHubHookReconcileResult(val hook: GitHubHook, val created: Boolean)

data class GitHubIssuesPage(
    val items: List<GitHubIssueListItem>,
    val etag: String? = null,
    val nextPage: Int? = null,
    val status: HttpStatusCode = HttpStatusCode.OK,
    val rateLimitLimit: Int? = null,
    val rateLimitRemaining: Int? = null,
    val rateLimitResetAt: Long? = null,
    val notModified: Boolean = false,
)

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

    suspend fun listIssues(
        ref: GitHubRepositoryRef,
        token: String,
        since: String? = null,
        direction: String = "asc",
        page: Int = 1,
        etag: String? = null,
    ): GitHubIssuesPage {
        val query = buildString {
            append("state=all&sort=updated&direction=").append(direction)
            append("&per_page=100&page=").append(page)
            since?.let { append("&since=").append(it) }
        }
        val response = request(
            "GET",
            "/repos/${ref.owner}/${ref.repo}/issues?$query",
            token,
            allowNotModified = true,
        ) {
            etag?.let { header(HttpHeaders.IfNoneMatch, it) }
        }
        return if (response.status == HttpStatusCode.NotModified) {
            pageResult(response, emptyList(), notModified = true)
        } else {
            pageResult(response, response.body(), notModified = false)
        }
    }

    suspend fun getPullRequest(ref: GitHubRepositoryRef, pullNumber: Int, token: String): GitHubPullRequestResponse =
        request("GET", "/repos/${ref.owner}/${ref.repo}/pulls/$pullNumber", token).body()

    override fun close() = client.close()

    private fun hookRequest(callbackUrl: String, secret: String) = GitHubHookRequest(
        config = GitHubHookConfig(url = callbackUrl, content_type = "json", insecure_ssl = "0", secret = secret),
    )

    private suspend fun request(
        method: String,
        path: String,
        token: String,
        retries: Int = maxRetries,
        allowNotModified: Boolean = false,
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
            if (response.status.value in 200..299 || (allowNotModified && response.status == HttpStatusCode.NotModified)) {
                return response
            }
            val retryable = response.status.value in 500..599
            if (!retryable || attempt >= retries) {
                throw GitHubApiException(
                    status = response.status,
                    message = "GitHub API request failed (${response.status.value})",
                    rateLimitRemaining = response.headers["X-RateLimit-Remaining"]?.toIntOrNull(),
                    rateLimitResetAt = response.headers["X-RateLimit-Reset"]?.toLongOrNull()?.times(1_000),
                    retryAfterMs = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1_000),
                )
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

    private fun pageResult(
        response: HttpResponse,
        items: List<GitHubIssueListItem>,
        notModified: Boolean,
    ): GitHubIssuesPage = GitHubIssuesPage(
        items = items,
        etag = response.headers[HttpHeaders.ETag],
        nextPage = parseNextPage(response.headers[HttpHeaders.Link]),
        status = response.status,
        rateLimitLimit = response.headers["X-RateLimit-Limit"]?.toIntOrNull(),
        rateLimitRemaining = response.headers["X-RateLimit-Remaining"]?.toIntOrNull(),
        rateLimitResetAt = response.headers["X-RateLimit-Reset"]?.toLongOrNull()?.times(1_000),
        notModified = notModified,
    )

    private fun parseNextPage(link: String?): Int? = link
        ?.split(',')
        ?.firstOrNull { it.contains("rel=\"next\"") }
        ?.substringAfter('<')
        ?.substringBefore('>')
        ?.let { runCatching { URI(it).getQueryParameter("page")?.toIntOrNull() }.getOrNull() }

    private fun URI.getQueryParameter(name: String): String? = rawQuery
        ?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")

    private fun url(path: String): String = apiBaseUrl.trimEnd('/') + path
}
