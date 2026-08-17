package com.silk.backend.workspace

import com.silk.backend.models.AgentSessionState
import kotlinx.serialization.Serializable

@Serializable
enum class WorkspaceVisibility { SHARED, PRIVATE }

@Serializable
enum class WorkspaceLifecycleState { ACTIVE, ARCHIVED }

@Serializable
data class PersonalWorkspace(
    val workspaceId: String,
    val roomId: String,
    val ownerId: String,
    val name: String,
    val workingDir: String = "",
    val agentType: String = "claude-code",
    val cliSessionId: String? = null,
    val sessionStarted: Boolean = false,
    val activeAgent: String = "",
    val activeAgentInstanceId: String = "",
    val agentSessions: Map<String, AgentSessionState> = emptyMap(),
    val permissionMode: String = "",
    val visibility: WorkspaceVisibility = WorkspaceVisibility.PRIVATE,
    val lastSharedName: String? = null,
    val copilots: List<String> = emptyList(),
    val lifecycleState: WorkspaceLifecycleState = WorkspaceLifecycleState.ACTIVE,
    val archivedAt: Long? = null,
    val linkedGithubRef: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class RecentWorkingDirectory(
    val roomId: String,
    val ownerId: String,
    val agentInstanceId: String,
    val workingDir: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class WorkspaceStore(
    val workspaces: MutableList<PersonalWorkspace> = mutableListOf(),
    val recentWorkingDirectories: MutableList<RecentWorkingDirectory> = mutableListOf(),
)
