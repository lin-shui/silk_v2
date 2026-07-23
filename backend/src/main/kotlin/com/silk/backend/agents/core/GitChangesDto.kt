package com.silk.backend.agents.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class GitFileChangeDto(
    val path: String,
    val oldPath: String? = null,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val binary: Boolean = false,
)

@Serializable
data class GitChangesResponse(
    val success: Boolean,
    val connected: Boolean = true,
    val supported: Boolean = true,
    val isGitRepo: Boolean = true,
    val cwd: String = "",
    val branch: String = "",      // current branch; "" on detached HEAD or older bridge
    val head: String = "",        // short HEAD commit; "" if unavailable
    val files: List<GitFileChangeDto> = emptyList(),
    val message: String = "",
    val reason: String? = null,   // cc-connect 专属空态："ccconnect"
)

@Serializable
data class GitFileDiffResponse(
    val success: Boolean,
    val connected: Boolean = true,
    val supported: Boolean = true,
    val isGitRepo: Boolean = true,
    val filePath: String = "",
    val patch: String = "",
    val isBinary: Boolean = false,
    val truncated: Boolean = false,
    val message: String = "",
)

/** Pure mapping from bridge JSON (+ connection facts) to HTTP DTOs — kept side-effect free for unit testing. */
object GitChangesAssembler {
    private val json = Json { ignoreUnknownKeys = true }

    fun assembleChanges(connected: Boolean, supported: Boolean, raw: JsonElement?): GitChangesResponse {
        if (!connected) return GitChangesResponse(success = false, connected = false, message = "agent not connected")
        if (!supported) return GitChangesResponse(success = false, supported = false, message = "bridge does not support code review")
        if (raw == null) return GitChangesResponse(success = false, message = "no result")
        return json.decodeFromJsonElement(GitChangesResponse.serializer(), raw)
            .copy(connected = true, supported = true)
    }

    fun assembleDiff(connected: Boolean, supported: Boolean, raw: JsonElement?): GitFileDiffResponse {
        if (!connected) return GitFileDiffResponse(success = false, connected = false, message = "agent not connected")
        if (!supported) return GitFileDiffResponse(success = false, supported = false, message = "bridge does not support code review")
        if (raw == null) return GitFileDiffResponse(success = false, message = "no result")
        return json.decodeFromJsonElement(GitFileDiffResponse.serializer(), raw)
            .copy(connected = true, supported = true)
    }
}
