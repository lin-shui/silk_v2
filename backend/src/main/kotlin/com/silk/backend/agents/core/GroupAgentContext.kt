// backend/src/main/kotlin/com/silk/backend/agents/core/GroupAgentContext.kt
package com.silk.backend.agents.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * per-(userId, groupId) 上下文。
 * - workingDir 被该 group 下所有 agent 共享
 * - currentAgentType 是 /use 指针；null = 普通 Silk AI
 * - sessions 索引该 group 下所有 agent 的 AgentSession
 */
class GroupAgentContext(
    val userId: String,
    val groupId: String,
    @Volatile var workingDir: String = System.getProperty("user.dir") ?: "/",
    @Volatile var currentAgentType: String? = null,
    val sessions: ConcurrentHashMap<String, AgentSession> = ConcurrentHashMap(),
) {
    /** Scope for background prompt coroutines; cancelled when this context is cleaned up. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    /** 原子检查所有 session 是否有 running 任务。返回阻塞中的 agentType 列表。 */
    suspend fun checkAnyRunning(): List<String> = mutex.withLock {
        sessions.filterValues { it.running }.keys.toList()
    }

    /** 获取或创建指定 agentType 的 session。 */
    fun getOrCreateSession(agentType: String): AgentSession {
        return sessions.getOrPut(agentType) {
            AgentSession(userId = userId, groupId = groupId, agentType = agentType)
        }
    }

    /** 移除指定 agentType 的 session（/exit 时用）。 */
    fun removeSession(agentType: String): AgentSession? = sessions.remove(agentType)
}
