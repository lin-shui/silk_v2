// backend/src/main/kotlin/com/silk/backend/agents/core/AcpUpdateMapper.kt
package com.silk.backend.agents.core

import com.silk.backend.Message
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

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

    private val logger = LoggerFactory.getLogger(AcpUpdateMapper::class.java)

    fun map(
        update: JsonObject,
        descriptor: AgentDescriptor,
        agentType: String,
        accumulated: StringBuilder,
    ): Message? {
        val kind = update["sessionUpdate"]?.jsonPrimitive?.contentOrNull ?: return null

        return when (kind) {
            "agent_message_chunk" -> mapAgentMessageChunk(update, descriptor, agentType, accumulated)
            "agent_thought_chunk" -> mapAgentThoughtChunk(update, descriptor, agentType)
            "tool_call" -> mapToolCall(update, descriptor)
            "tool_call_update" -> mapToolCallUpdate(update, descriptor)
            "plan" -> mapPlan(update, descriptor, agentType)
            "ask_user_question" -> mapQuestionCard(update, descriptor)
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

    private fun mapAgentMessageChunk(
        update: JsonObject,
        descriptor: AgentDescriptor,
        agentType: String,
        accumulated: StringBuilder,
    ): Message {
        val text = update.contentText()
        accumulated.append(text)
        return AgentMessages.streaming(
            accumulated = accumulated.toString(),
            agentUserId = descriptor.agentUserId,
            agentName = descriptor.displayName,
            agentType = agentType,
        )
    }

    private fun mapAgentThoughtChunk(
        update: JsonObject,
        descriptor: AgentDescriptor,
        agentType: String,
    ): Message {
        return AgentMessages.status(
            content = update.contentText(),
            agentUserId = descriptor.agentUserId,
            agentName = descriptor.displayName,
            stableId = "agent_thought_${agentType}",
        )
    }

    private fun mapToolCall(
        update: JsonObject,
        descriptor: AgentDescriptor,
    ): Message {
        val tool = update["tool"]?.jsonPrimitive?.contentOrNull ?: "tool"
        val title = update["title"]?.jsonPrimitive?.contentOrNull ?: ""
        val toolCallId = update["toolCallId"]?.jsonPrimitive?.contentOrNull ?: ""
        return AgentMessages.status(
            content = "🔧 $tool: $title",
            agentUserId = descriptor.agentUserId,
            agentName = descriptor.displayName,
            stableId = "agent_tool_${toolCallId}",
        )
    }

    private fun mapToolCallUpdate(
        update: JsonObject,
        descriptor: AgentDescriptor,
    ): Message {
        val toolCallId = update["toolCallId"]?.jsonPrimitive?.contentOrNull ?: ""
        return AgentMessages.status(
            content = update.contentText(),
            agentUserId = descriptor.agentUserId,
            agentName = descriptor.displayName,
            stableId = "agent_tool_${toolCallId}",
        )
    }

    private fun mapPlan(
        update: JsonObject,
        descriptor: AgentDescriptor,
        agentType: String,
    ): Message {
        val planText = update["plan"]?.jsonPrimitive?.contentOrNull ?: ""
        return AgentMessages.status(
            content = "📋 计划: $planText",
            agentUserId = descriptor.agentUserId,
            agentName = descriptor.displayName,
            stableId = "agent_plan_${agentType}",
        )
    }

    private fun mapQuestionCard(
        update: JsonObject,
        descriptor: AgentDescriptor,
    ): Message? {
        logger.info("[AcpUpdateMapper] ask_user_question 原始数据: {}", update)
        val requestId = (update["requestId"] as? JsonPrimitive)?.contentOrNull ?: return null
        val structuredQuestions = update["questions"]?.jsonArray
            ?.mapNotNull(::toStructuredQuestion)
            .orEmpty()
        if (structuredQuestions.isEmpty()) return null

        logger.info(
            "[AcpUpdateMapper] 解析到 requestId={}, questionCount={}, firstQ={}",
            requestId, structuredQuestions.size, structuredQuestions[0].question.take(60),
        )
        return AgentMessages.questionCard(
            questions = structuredQuestions,
            requestId = requestId,
            agentUserId = descriptor.agentUserId,
            agentName = descriptor.displayName,
            currentIndex = 0,
            answers = emptyMap(),
        )
    }

    private fun toStructuredQuestion(element: JsonElement): StructuredQuestion? {
        return when (element) {
            is JsonObject -> StructuredQuestion(
                question = element["question"]?.jsonPrimitive?.contentOrNull ?: "",
                header = element["header"]?.jsonPrimitive?.contentOrNull ?: "",
                options = element["options"]?.jsonArray?.mapNotNull(::toQuestionOption).orEmpty(),
            )
            is JsonPrimitive -> element.contentOrNull?.let { text ->
                StructuredQuestion(question = text)
            }
            else -> null
        }
    }

    private fun toQuestionOption(element: JsonElement): QuestionOption? {
        val option = element as? JsonObject ?: return null
        return QuestionOption(
            label = option["label"]?.jsonPrimitive?.contentOrNull ?: "",
            description = option["description"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    private fun JsonObject.contentText(): String {
        return this["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
    }
}
