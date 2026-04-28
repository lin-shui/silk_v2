// backend/src/main/kotlin/com/silk/backend/agents/core/AcpUpdateMapper.kt
package com.silk.backend.agents.core

import com.silk.backend.Message
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 将 ACP `session/update` 通知中的 `update` 字段映射为 silk Message。
 *
 * update 结构：
 * ```json
 * {
 *   "sessionUpdate": "agent_message_chunk",
 *   "content": {"type":"text","text":"hello"}
 * }
 * ```
 */
object AcpUpdateMapper {

    fun map(
        update: JsonObject,
        descriptor: AgentDescriptor,
        agentType: String,
        accumulated: StringBuilder,
    ): Message? {
        val kind = update["sessionUpdate"]?.jsonPrimitive?.contentOrNull ?: return null

        return when (kind) {
            "agent_message_chunk" -> {
                val content = update["content"]?.jsonObject
                val text = content?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                accumulated.append(text)
                AgentMessages.streaming(
                    accumulated = accumulated.toString(),
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                    agentType = agentType,
                )
            }
            "agent_thought_chunk" -> {
                val content = update["content"]?.jsonObject
                val text = content?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                AgentMessages.status(
                    content = text,
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                    stableId = "agent_thought_${agentType}",
                )
            }
            "tool_call" -> {
                val tool = update["tool"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val title = update["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val toolCallId = update["toolCallId"]?.jsonPrimitive?.contentOrNull ?: ""
                AgentMessages.status(
                    content = "🔧 $tool: $title",
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                    stableId = "agent_tool_${toolCallId}",
                )
            }
            "tool_call_update" -> {
                val toolCallId = update["toolCallId"]?.jsonPrimitive?.contentOrNull ?: ""
                val content = update["content"]?.jsonObject
                val text = content?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                AgentMessages.status(
                    content = text,
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                    stableId = "agent_tool_${toolCallId}",
                )
            }
            "plan" -> {
                val planText = update["plan"]?.jsonPrimitive?.contentOrNull ?: ""
                AgentMessages.status(
                    content = "📋 计划: $planText",
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                    stableId = "agent_plan_${agentType}",
                )
            }
            "available_commands_update" -> {
                // adapter 可选消费，Plan C 暂静默
                null
            }
            else -> {
                // 未知 kind，静默丢弃
                null
            }
        }
    }
}
