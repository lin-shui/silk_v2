// backend/src/main/kotlin/com/silk/backend/agents/core/AgentMessages.kt
package com.silk.backend.agents.core

import com.silk.backend.Message
import com.silk.backend.MessageCategory
import com.silk.backend.MessageType
import com.silk.backend.card.CardBuilder
import com.silk.backend.card.ButtonType
import java.util.UUID

object AgentMessages {

    /** 清理答案文本中的内部编码前缀，用于前端展示。 */
    fun cleanAnswerText(raw: String): String = when {
        raw.startsWith("__opt__") -> {
            // "__opt__0__Python - 简洁易用" → "Python - 简洁易用"
            val afterPrefix = raw.removePrefix("__opt__")
            val idx = afterPrefix.indexOf("__")
            if (idx >= 0) afterPrefix.substring(idx + 2) else raw
        }
        raw.startsWith("__custom__") -> "（自定义回答）"
        else -> raw
    }

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

    /**
     * 构建 AskUserQuestion 的交互卡片消息。
     *
     * 多问题场景下按顺序逐题展示：
     * - 已回答的：灰色文本展示答案
     * - 当前题：交互式，每个 option 一个按钮 + 自定义输入框
     * - 后续题：仅标题预览
     *
     * 单问题时直接展示。每次回答后通过 action="edit" 刷新同一张卡片。
     */
    fun questionCard(
        questions: List<StructuredQuestion>,
        requestId: String,
        agentUserId: String,
        agentName: String,
        currentIndex: Int = 0,
        answers: Map<Int, String> = emptyMap(),
    ): Message {
        val total = questions.size
        val answeredCount = answers.size

        // Title with progress for multi-question
        val title = if (total > 1) {
            "💬 $agentName 提问（${answeredCount + 1}/$total）"
        } else {
            "💬 $agentName 提问"
        }
        val builder = CardBuilder(title, template = "gold")

        for ((qi, sq) in questions.withIndex()) {
            val prefix = if (total > 1) "问题 ${qi + 1}/$total: " else ""

            when {
                qi in answers -> {
                    // Already answered — gray display with full question
                    val cleanAnswer = cleanAnswerText(answers[qi] ?: "")
                    builder.addText("$prefix${sq.question}\n已选择: $cleanAnswer")
                }
                qi == currentIndex -> {
                    // Current question — interactive
                    val headerLine = if (sq.header.isNotEmpty()) "$prefix${sq.header}\n\n${sq.question}" else "$prefix${sq.question}"
                    builder.addText(headerLine)

                    // Option descriptions
                    if (sq.options.isNotEmpty()) {
                        val descLines = sq.options.map { opt ->
                            if (opt.description.isNotEmpty()) "${opt.label}: ${opt.description}" else opt.label
                        }
                        builder.addText(descLines.joinToString("\n"))
                    }

                    builder.addDivider()

                    // Per-option buttons
                    sq.options.forEachIndexed { i, opt ->
                        val answerDisplay = if (opt.description.isNotEmpty()) "${opt.label} - ${opt.description}" else opt.label
                        val buttonValue = "__opt__${qi}__${answerDisplay}"
                        builder.addButton(
                            text = opt.label,
                            value = buttonValue,
                            type = if (i == 0) ButtonType.PRIMARY else ButtonType.DEFAULT,
                        )
                    }

                    // Custom answer input
                    builder.addDivider()
                    builder.addTextInput("custom_answer_$qi", placeholder = "输入自定义回答...")
                    builder.addButton("提交自定义回答", value = "__custom__$qi", type = ButtonType.DEFAULT)
                }
                else -> {
                    // Future question — preview with full question
                    builder.addText("$prefix${sq.question}\n待回答")
                }
            }

            if (qi < total - 1) builder.addDivider()
        }

        return Message(
            id = "agent_question_$requestId",
            userId = agentUserId,
            userName = agentName,
            content = builder.build(),
            timestamp = System.currentTimeMillis(),
            type = MessageType.CARD,
            isTransient = false,
            category = MessageCategory.AGENT_QUESTION,
        )
    }

    /** 构建已完成的卡片（所有问题已回答）。 */
    fun questionCardCompleted(
        questions: List<StructuredQuestion>,
        answers: Map<Int, String>,
        requestId: String,
        agentUserId: String,
        agentName: String,
    ): Message {
        val builder = CardBuilder("✓ 已回答", template = "green")
        questions.forEachIndexed { qi, sq ->
            val rawAnswer = answers[qi] ?: "—"
            val cleanAnswer = cleanAnswerText(rawAnswer)
            val displayAnswer = if (cleanAnswer.length > 80) cleanAnswer.take(80) + "..." else cleanAnswer
            builder.addText("${sq.question}\n回答: $displayAnswer")
        }
        return Message(
            id = "agent_question_$requestId",
            userId = agentUserId,
            userName = agentName,
            content = builder.buildDisabled(),
            timestamp = System.currentTimeMillis(),
            type = MessageType.CARD,
            action = "edit",
        )
    }

    /** 将问题列表格式化为展示文本。供重连恢复等场景共用。 */
    fun formatQuestionText(questions: List<StructuredQuestion>): String = buildString {
        appendLine("💬 Claude Code 想问你：")
        appendLine()
        if (questions.size == 1) {
            val sq = questions[0]
            if (sq.header.isNotEmpty()) appendLine(sq.header)
            appendLine(sq.question)
            sq.options.forEachIndexed { i, opt ->
                appendLine("  ${i + 1}. ${opt.label}${if (opt.description.isNotEmpty()) " — ${opt.description}" else ""}")
            }
        } else {
            questions.forEachIndexed { i, sq ->
                appendLine("${i + 1}. ${sq.question}")
            }
        }
        appendLine()
        append("⏳ 等待你的回答...")
    }
}
