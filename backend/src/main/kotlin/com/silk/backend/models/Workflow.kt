package com.silk.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val description: String = "",
    val ownerId: String,
    val groupId: String = "",
    val agentType: String = "claude_code",
    val createdAt: Long,
    val updatedAt: Long
)
