@file:Suppress("MatchingDeclarationName")

package com.silk.web.workspace

import com.silk.web.backendHttpOrigin
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response

@Serializable
data class WorkspaceActivityDto(
    val state: String = "OFFLINE",
    val updatedAt: Long = 0L,
)

@Serializable
data class WorkspaceDto(
    val workspaceId: String,
    val roomId: String,
    val ownerId: String,
    val ownerDisplayName: String = "Room member",
    val name: String,
    val workingDir: String = "",
    val agentType: String = "claude-code",
    val visibility: String = "PRIVATE",
    val copilots: List<String> = emptyList(),
    val role: String = "OBSERVER",
    val lifecycleState: String = "ACTIVE",
    val activity: WorkspaceActivityDto = WorkspaceActivityDto(),
    val createdAt: Long = 0L,
    val recentActivityAt: Long = 0L,
    val linkedGithubRef: String? = null,
    val historyOnly: Boolean = false,
)

@Serializable
data class IssueToWorkspaceResponse(
    val workspace: WorkspaceDto,
    val issueSummary: String,
)

@Serializable
private data class CreateWorkspaceRequest(
    val name: String,
    val workingDir: String,
    val agentType: String,
    val visibility: String,
)

@Serializable
private data class IssueToWorkspaceRequest(
    val issueNumber: Int,
    val name: String? = null,
    val workingDir: String,
    val agentType: String,
    val visibility: String,
)

@Serializable
private data class PatchWorkspaceRequest(
    val name: String? = null,
    val visibility: String? = null,
    val copilots: List<String>? = null,
    val lifecycleState: String? = null,
)

@Serializable
private data class WorkspaceErrorPayload(
    val errorCode: String = "WORKSPACE_REQUEST_FAILED",
    val message: String = "Workspace request failed",
    val path: String? = null,
    val bridgeId: String? = null,
)

class WorkspaceApiException(
    val status: Int,
    val errorCode: String,
    override val message: String,
    val path: String? = null,
    val bridgeId: String? = null,
) : Exception(message)

private val workspaceJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

private fun authHeaders(authToken: String, jsonBody: Boolean = false): Headers = Headers().apply {
    append("Authorization", "Bearer $authToken")
    if (jsonBody) append("Content-Type", "application/json")
}

private suspend fun Response.requireSuccess(): Response {
    if (ok) return this
    val rawBody = text().await()
    val payload = runCatching { workspaceJson.decodeFromString<WorkspaceErrorPayload>(rawBody) }
        .getOrElse {
            WorkspaceErrorPayload(message = rawBody.takeIf { body -> body.isNotBlank() } ?: "Workspace request failed")
        }
    throw WorkspaceApiException(
        status = status.toInt(),
        errorCode = payload.errorCode,
        message = payload.message,
        path = payload.path,
        bridgeId = payload.bridgeId,
    )
}

suspend fun fetchWorkspaces(roomId: String, authToken: String): List<WorkspaceDto> {
    val response = window.fetch(
        "${backendHttpOrigin()}/api/rooms/$roomId/workspaces",
        RequestInit(method = "GET", headers = authHeaders(authToken)),
    ).await().requireSuccess()
    return workspaceJson.decodeFromString(response.text().await())
}

suspend fun createWorkspace(
    roomId: String,
    authToken: String,
    name: String,
    workingDir: String,
    agentType: String,
    visibility: String,
): WorkspaceDto {
    val response = window.fetch(
        "${backendHttpOrigin()}/api/rooms/$roomId/workspaces",
        RequestInit(
            method = "POST",
            headers = authHeaders(authToken, jsonBody = true),
            body = workspaceJson.encodeToString(
                CreateWorkspaceRequest(name, workingDir, agentType, visibility)
            ),
        ),
    ).await().requireSuccess()
    return workspaceJson.decodeFromString(response.text().await())
}

suspend fun createWorkspaceFromGithubIssue(
    roomId: String,
    authToken: String,
    issueNumber: Int,
    workingDir: String,
    agentType: String = "claude-code",
    visibility: String = "PRIVATE",
    name: String? = null,
): IssueToWorkspaceResponse {
    val response = window.fetch(
        "${backendHttpOrigin()}/api/rooms/$roomId/git/issue-to-workspace",
        RequestInit(
            method = "POST",
            headers = authHeaders(authToken, jsonBody = true),
            body = workspaceJson.encodeToString(
                IssueToWorkspaceRequest(issueNumber, name, workingDir, agentType, visibility)
            ),
        ),
    ).await().requireSuccess()
    return workspaceJson.decodeFromString(response.text().await())
}

suspend fun patchWorkspace(
    roomId: String,
    workspaceId: String,
    authToken: String,
    name: String? = null,
    visibility: String? = null,
    copilots: List<String>? = null,
    lifecycleState: String? = null,
): WorkspaceDto {
    val response = window.fetch(
        "${backendHttpOrigin()}/api/rooms/$roomId/workspaces/$workspaceId",
        RequestInit(
            method = "PATCH",
            headers = authHeaders(authToken, jsonBody = true),
            body = workspaceJson.encodeToString(
                PatchWorkspaceRequest(name, visibility, copilots, lifecycleState)
            ),
        ),
    ).await().requireSuccess()
    return workspaceJson.decodeFromString(response.text().await())
}

suspend fun deleteWorkspace(roomId: String, workspaceId: String, authToken: String) {
    window.fetch(
        "${backendHttpOrigin()}/api/rooms/$roomId/workspaces/$workspaceId",
        RequestInit(method = "DELETE", headers = authHeaders(authToken)),
    ).await().requireSuccess()
}
