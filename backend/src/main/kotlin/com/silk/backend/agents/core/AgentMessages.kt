// backend/src/main/kotlin/com/silk/backend/agents/core/AgentMessages.kt
package com.silk.backend.agents.core

import com.silk.backend.Message
import com.silk.backend.MessageCategory
import com.silk.backend.MessageType
import java.util.UUID

object AgentMessages {

    fun system(content: String, agentUserId: String, agentName: String) = Message(
        id = UUID.randomUUID().toString(),
        userId = agentUserId,
        userName = agentName,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = MessageType.SYSTEM,
    )

    fun status(
        content: String,
        agentUserId: String,
        agentName: String,
        stableId: String? = null,
    ) = Message(
        id = stableId ?: UUID.randomUUID().toString(),
        userId = agentUserId,
        userName = agentName,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = MessageType.TEXT,
        isTransient = true,
        isIncremental = false,
        category = MessageCategory.AGENT_STATUS,
    )

    fun streaming(
        accumulated: String,
        agentUserId: String,
        agentName: String,
        agentType: String,
    ) = Message(
        id = "agent_streaming_$agentType",
        userId = agentUserId,
        userName = agentName,
        content = accumulated,
        timestamp = System.currentTimeMillis(),
        type = MessageType.TEXT,
        isTransient = true,
        isIncremental = false,
    )

    fun final(
        content: String,
        agentUserId: String,
        agentName: String,
    ) = Message(
        id = UUID.randomUUID().toString(),
        userId = agentUserId,
        userName = agentName,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = MessageType.TEXT,
    )
}
