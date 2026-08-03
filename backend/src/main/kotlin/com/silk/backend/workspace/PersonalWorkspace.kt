package com.silk.backend.workspace

import com.silk.backend.models.AgentSessionState
import kotlinx.serialization.Serializable

@Serializable
enum class WorkspaceVisibility { SHARED, PRIVATE }

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
    val agentSessions: Map<String, AgentSessionState> = emptyMap(),
    val permissionMode: String = "",
    val visibility: WorkspaceVisibility = WorkspaceVisibility.PRIVATE,
    val copilots: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class WorkspaceStore(val workspaces: MutableList<PersonalWorkspace> = mutableListOf())
