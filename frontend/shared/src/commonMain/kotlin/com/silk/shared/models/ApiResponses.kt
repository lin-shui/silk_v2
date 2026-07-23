package com.silk.shared.models

import kotlinx.serialization.Serializable

/**
 * 通用简单响应
 */
@Serializable
data class SimpleResponse(
    val success: Boolean,
    val message: String
)

/**
 * 退出群组响应
 */
@Serializable
data class LeaveGroupResponse(
    val success: Boolean,
    val message: String,
    val groupDeleted: Boolean = false
)

/**
 * 代码审查（Source Control）：工作树 vs HEAD。字段名与后端 DTO 逐字一致。
 */
@Serializable
data class GitFileChange(
    val path: String,
    val oldPath: String? = null,
    val status: String,          // wire word: added|modified|deleted|renamed|copied|untracked|unmerged|type_changed
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
    val files: List<GitFileChange> = emptyList(),
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

/** UI-side status classification for badge/color (not serialized). */
enum class GitFileStatus {
    ADDED, MODIFIED, DELETED, RENAMED, COPIED, UNTRACKED, UNMERGED, TYPE_CHANGED, UNKNOWN;

    companion object {
        fun fromWire(s: String): GitFileStatus = when (s) {
            "added" -> ADDED
            "modified" -> MODIFIED
            "deleted" -> DELETED
            "renamed" -> RENAMED
            "copied" -> COPIED
            "untracked" -> UNTRACKED
            "unmerged" -> UNMERGED
            "type_changed" -> TYPE_CHANGED
            else -> UNKNOWN
        }
    }
}
