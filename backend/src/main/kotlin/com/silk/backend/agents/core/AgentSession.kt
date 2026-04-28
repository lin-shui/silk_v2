// backend/src/main/kotlin/com/silk/backend/agents/core/AgentSession.kt
package com.silk.backend.agents.core

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * per-(userId, groupId, agentType) 状态机。
 * 不含 workingDir —— cwd 在 GroupAgentContext 共享。
 */
class AgentSession(
    val userId: String,
    val groupId: String,
    val agentType: String,
    @Volatile var acpSessionId: String? = null,
    @Volatile var running: Boolean = false,
    @Volatile var cancelled: Boolean = false,
    @Volatile var currentRequestId: String? = null,
    val messageQueue: ConcurrentLinkedDeque<QueuedMessage> = ConcurrentLinkedDeque(),
    val startedAt: Long = System.currentTimeMillis(),
)

data class QueuedMessage(val text: String, val userId: String, val userName: String)
