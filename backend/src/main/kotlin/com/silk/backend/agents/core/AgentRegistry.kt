// backend/src/main/kotlin/com/silk/backend/agents/core/AgentRegistry.kt
package com.silk.backend.agents.core

import java.util.concurrent.ConcurrentHashMap

/**
 * 注册所有 AgentDescriptor。
 * agentType 和 aliases 都映射到同一个 descriptor 实例。
 */
object AgentRegistry {
    private val descriptors = ConcurrentHashMap<String, AgentDescriptor>()

    fun register(descriptor: AgentDescriptor) {
        descriptors[descriptor.agentType] = descriptor
        for (alias in descriptor.aliases) {
            descriptors[alias] = descriptor
        }
    }

    fun get(name: String): AgentDescriptor? = descriptors[name]

    /** 按 agentType 去重返回。 */
    fun list(): List<AgentDescriptor> =
        descriptors.values.distinctBy { it.agentType }

    fun isRegistered(name: String): Boolean = descriptors.containsKey(name)

    /** 按 agentType 精确查找（不走 alias）。 */
    fun getByType(agentType: String): AgentDescriptor? {
        val d = descriptors[agentType] ?: return null
        return if (d.agentType == agentType) d else null
    }

    /** 仅供测试。 */
    internal fun clearForTest() {
        descriptors.clear()
    }
}
