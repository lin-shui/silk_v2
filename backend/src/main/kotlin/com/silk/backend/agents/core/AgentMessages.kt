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

    fun question(
        content: String,
        requestId: String,
        agentUserId: String,
        agentName: String,
    ) = Message(
        id = "agent_question_$requestId",
        userId = agentUserId,
        userName = agentName,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = MessageType.TEXT,
        isTransient = false,
        category = MessageCategory.AGENT_QUESTION,
    )

    /** 将问题列表格式化为展示文本。供 AcpUpdateMapper 和重连恢复共用。 */
    fun formatQuestionText(questions: List<String>): String = buildString {
        appendLine("💬 Claude Code 想问你：")
        appendLine()
        if (questions.size == 1) {
            appendLine(questions[0])
        } else {
            questions.forEachIndexed { i, q ->
                appendLine("${i + 1}. $q")
            }
        }
        appendLine()
        append("⏳ 等待你的回答...")
    }
}
