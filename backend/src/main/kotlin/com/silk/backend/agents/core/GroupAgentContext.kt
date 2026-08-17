// backend/src/main/kotlin/com/silk/backend/agents/core/GroupAgentContext.kt
package com.silk.backend.agents.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * per-(userId, workspaceId) 上下文。
 * - workingDir 被该 workspace 下所有 agent 共享
 * - currentAgentType/currentAgentInstanceId 是 /use 或 Binding 指针；null = 普通 Silk AI
 * - sessions 以 AgentInstance 优先索引，legacy workspace 仍可用 agentType
 */
class GroupAgentContext(
    val userId: String,
    val workspaceId: String,
    /** The room/group ID associated with this workspace. Populated by autoActivateForWorkspace. */
    @Volatile var roomId: String = "",
    @Volatile var workingDir: String = System.getProperty("user.dir") ?: "/",
    @Volatile var currentAgentType: String? = null,
    @Volatile var currentAgentInstanceId: String? = null,
    val sessions: ConcurrentHashMap<String, AgentSession> = ConcurrentHashMap(),
) {
    /** Scope for background prompt coroutines; cancelled when this context is cleaned up. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    /** 原子检查所有 session 是否有 running 任务。返回阻塞中的 agentType 列表。 */
    suspend fun checkAnyRunning(): List<String> = mutex.withLock {
        sessions.filterValues { it.running }.keys.toList()
    }

    /** 获取或创建指定 AgentInstance 的 session；无实例时保留 legacy type key。 */
    fun getOrCreateSession(agentType: String, agentInstanceId: String? = null): AgentSession {
        val key = agentInstanceId?.takeIf(String::isNotBlank) ?: agentType
        return sessions.getOrPut(key) {
            AgentSession(
                userId = userId,
                groupId = workspaceId,
                agentType = agentType,
                agentInstanceId = agentInstanceId?.takeIf(String::isNotBlank),
            )
        }
    }

    /** 移除指定 agentType 的 session（/exit 时用）。 */
    fun removeSession(agentType: String): AgentSession? = sessions.remove(agentType)
}
