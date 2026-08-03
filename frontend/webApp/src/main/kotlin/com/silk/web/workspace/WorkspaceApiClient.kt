package com.silk.web.workspace

import com.silk.web.backendHttpOrigin
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit

@Serializable
data class WorkspaceDto(
    val workspaceId: String,
    val roomId: String,
    val ownerId: String,
    val name: String,
    val workingDir: String = "",
    val agentType: String = "claude-code",
    val visibility: String = "PRIVATE",
)

private val workspaceJson = Json { ignoreUnknownKeys = true }

suspend fun fetchWorkspaces(roomId: String, authToken: String): List<WorkspaceDto> {
    return try {
        val headers = Headers()
        headers.append("Authorization", "Bearer $authToken")
        val url = "${backendHttpOrigin()}/api/rooms/$roomId/workspaces"
        val response = window.fetch(
            url,
            RequestInit(
                method = "GET",
                headers = headers,
            )
        ).await()
        if (!response.ok) return emptyList()
        val body = response.text().await()
        workspaceJson.decodeFromString(body)
    } catch (e: Exception) {
        console.log("fetchWorkspaces failed:", e.message)
        emptyList()
    }
}
