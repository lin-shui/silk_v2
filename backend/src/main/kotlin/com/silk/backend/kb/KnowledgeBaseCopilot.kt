package com.silk.backend.kb

import com.silk.backend.ai.KnowledgeBaseWorkspaceEntry
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBTopic
import com.silk.backend.models.KBEntryStatus
import kotlinx.serialization.Serializable

/**
 * A single turn in a multi-turn KB Copilot conversation.
 */
@Serializable
data class ConversationTurn(
    val role: String, // "user" or "assistant"
    val content: String,
)

@Serializable
data class KnowledgeBaseCopilotRequest(
    val userId: String,
    val entryId: String? = null,
    val topicId: String? = null,
    val instruction: String,
    val applyChanges: Boolean = false,
    /** Previous conversation turns for multi-turn refinement. */
    val conversationHistory: List<ConversationTurn> = emptyList(),
)

@Serializable
data class KnowledgeBaseCopilotDraft(
    val entryId: String? = null,
    val topicId: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
)

@Serializable
data class KnowledgeBaseCopilotResponse(
    val success: Boolean,
    val assistantReply: String,
    val draft: KnowledgeBaseCopilotDraft? = null,
    val appliedEntry: KBEntry? = null,
    val message: String = "",
)

data class KnowledgeBaseCopilotAgentInput(
    val systemPrompt: String,
    val userPrompt: String,
    val workspaceEntries: List<KnowledgeBaseWorkspaceEntry>,
)

@Suppress("CyclomaticComplexMethod")
suspend fun executeKnowledgeBaseCopilot(
    manager: KnowledgeBaseManager,
    request: KnowledgeBaseCopilotRequest,
    runAgent: suspend (KnowledgeBaseCopilotAgentInput) -> String,
): KnowledgeBaseCopilotResponse {
    val normalizedInstruction = request.instruction.trim()
    if (request.userId.isBlank() || normalizedInstruction.isBlank()) {
        return KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = "",
            message = "Missing required copilot fields",
        )
    }

    // Determine mode: entry-level or topic-level
    val entry = request.entryId?.takeIf { it.isNotBlank() }
        ?.let { manager.getEntry(it, request.userId) }
    val topic = when {
        entry != null -> manager.getTopic(entry.topicId, request.userId)
        !request.topicId.isNullOrBlank() -> manager.getTopic(request.topicId, request.userId)
        else -> null
    }

    if (entry != null && topic == null) {
        return KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = "",
            message = "Topic not found for entry",
        )
    }
    if (entry == null && topic == null) {
        return KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = "",
            message = "Please select an entry or topic first",
        )
    }

    val systemPrompt = if (entry != null && topic != null) {
        buildKnowledgeBaseCopilotSystemPrompt(topic, entry)
    } else {
        buildKnowledgeBaseTopicCopilotSystemPrompt(topic!!)
    }

    // Inject conversation history into user prompt for multi-turn refinement
    val fullUserPrompt = buildString {
        val history = request.conversationHistory
        if (history.isNotEmpty()) {
            appendLine("以下是本次对话的先前轮次，请结合上下文理解当前需求：")
            appendLine()
            for ((index, turn) in history.withIndex()) {
                when (turn.role) {
                    "user" -> appendLine("用户第${index + 1}轮: ${turn.content}")
                    "assistant" -> appendLine("AI第${index + 1}轮: ${turn.content}")
                    else -> appendLine("第${index + 1}轮 [${turn.role}]: ${turn.content}")
                }
                appendLine()
            }
            appendLine("---")
            appendLine("以上是对话历史。请基于历史上下文响应当前最新的用户指令。")
            appendLine("仍然必须输出 ```silk_kb_action 代码块（update_entry 或 create_entry），与先前轮次无关，仅针对当前轮的需求。")
            appendLine()
        }
        appendLine("【当前用户指令】")
        append(normalizedInstruction)
    }

    val rawReply = runAgent(
        KnowledgeBaseCopilotAgentInput(
            systemPrompt = systemPrompt,
            userPrompt = fullUserPrompt,
            workspaceEntries = buildKnowledgeBaseWorkspaceEntries(manager, request.userId),
        )
    )
    val parsed = extractKnowledgeBaseAiActions(rawReply)

    if (entry != null && topic != null) {
        // Entry-level: look for UPDATE_ENTRY action for this entry
        val updateAction = parsed.actions.firstOrNull { candidate ->
            candidate.operation == KnowledgeBaseAiOperation.UPDATE_ENTRY &&
                (candidate.entryId.isNullOrBlank() || candidate.entryId == entry.id)
        }
        if (updateAction == null) {
            // Still check for CREATE_ENTRY if user explicitly wanted that
            val createAction = parsed.actions.firstOrNull { it.operation == KnowledgeBaseAiOperation.CREATE_ENTRY }
            if (createAction != null) {
                return handleCreateEntryDraft(manager, request, topic, parsed, createAction)
            }
            return KnowledgeBaseCopilotResponse(
                success = false,
                assistantReply = parsed.cleanedContent,
                message = "AI did not return a KB update draft",
            )
        }
        return handleUpdateEntry(manager, request, entry, topic, parsed, updateAction)
    }

    // Topic-level: look for CREATE_ENTRY action
    val createAction = parsed.actions.firstOrNull {
        it.operation == KnowledgeBaseAiOperation.CREATE_ENTRY
    }
    if (createAction == null) {
        return KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = parsed.cleanedContent,
            message = "AI did not return a KB entry draft",
        )
    }
    return handleCreateEntryDraft(manager, request, topic!!, parsed, createAction)
}

fun buildKnowledgeBaseWorkspaceEntries(
    manager: KnowledgeBaseManager,
    userId: String,
): List<KnowledgeBaseWorkspaceEntry> {
    if (userId.isBlank()) return emptyList()
    return manager.listTopics(userId)
        .asSequence()
        .flatMap { topic ->
            manager.listEntries(topic.id, userId)
                .asSequence()
                .filter { it.status != KBEntryStatus.DELETED }
                .map { entry ->
                    KnowledgeBaseWorkspaceEntry(
                        topicId = topic.id,
                        topicName = topic.name,
                        topicProject = topic.project,
                        entryId = entry.id,
                        entryTitle = entry.title,
                        content = entry.content,
                        status = entry.status.name,
                        spaceLabel = buildKnowledgeBaseWorkspaceSpaceLabel(topic),
                    )
                }
        }
        .toList()
}

private fun buildKnowledgeBaseWorkspaceSpaceLabel(topic: KBTopic): String {
    return when {
        topic.spaceType == com.silk.backend.models.KnowledgeSpaceType.TEAM && !topic.groupId.isNullOrBlank() ->
            "团队:${topic.groupId}"
        topic.spaceType == com.silk.backend.models.KnowledgeSpaceType.TEAM -> "团队"
        else -> "个人"
    }
}

private fun buildKnowledgeBaseCopilotSystemPrompt(topic: KBTopic, entry: KBEntry): String = buildString {
    appendLine("你是 Silk KB Copilot，负责协助用户修改当前知识库文档。")
    appendLine("你只能更新当前条目，不能创建新条目，也不能改其他条目的 entryId/topicId。")
    appendLine()
    appendLine("## 当前条目")
    appendLine("- Topic: ${topic.name}")
    appendLine("- Topic ID: ${topic.id}")
    appendLine("- Entry: ${entry.title}")
    appendLine("- Entry ID: ${entry.id}")
    appendLine("- Status: ${entry.status.name}")
    appendLine("- Tags: ${entry.tags.joinToString(", ").ifBlank { "(none)" }}")
    appendLine()
    appendLine("## 输出要求")
    appendLine("1. 先用 1-3 句中文总结你会如何修改。")
    appendLine("2. 然后必须输出一个 ```silk_kb_action 代码块。")
    appendLine("3. 代码块内只允许一个 JSON 对象，operation 固定为 update_entry。")
    appendLine("4. JSON 中的 topicId 必须为 ${topic.id}，entryId 必须为 ${entry.id}。")
    appendLine("5. title/content/tags 必须提供修改后的完整结果；content 使用完整 Markdown，而不是 diff。")
    appendLine("6. 如果你判断当前文档无需改动，也要输出 update_entry，并保留原文内容。")
    appendLine()
    appendLine("## 当前文档 Markdown")
    appendLine("```markdown")
    appendLine(entry.content.ifBlank { "(empty document)" })
    appendLine("```")
}

private fun buildKnowledgeBaseTopicCopilotSystemPrompt(topic: KBTopic): String = buildString {
    appendLine("你是 Silk KB Copilot，负责协助用户在知识库主题中创建新文档。")
    appendLine("你的职责是根据用户的指令，在当前主题中创建知识库条目，不能修改已有条目。")
    appendLine()
    appendLine("## 当前主题")
    appendLine("- Topic: ${topic.name}")
    appendLine("- Topic ID: ${topic.id}")
    appendLine("- Project: ${topic.project}")
    appendLine("- Space: ${buildKnowledgeBaseWorkspaceSpaceLabel(topic)}")
    appendLine()
    appendLine("## 输出要求")
    appendLine("1. 先用 1-3 句中文解释你准备创建什么样的文档。")
    appendLine("2. 然后必须输出一个 ```silk_kb_action 代码块。")
    appendLine("3. 代码块内只允许一个 JSON 对象，operation 固定为 create_entry。")
    appendLine("4. JSON 中的 topicId 必须为 ${topic.id}。")
    appendLine("5. title/content/tags 必须提供完整的文档内容。")
    appendLine("6. status 为 \"CANDIDATE\"，方便用户确认后再发布。")
    appendLine("7. content 使用完整 Markdown。")
    appendLine()
    appendLine("## 当前主题已有条目")
    appendLine("请在 workspace entries 中查阅当前主题的已有条目，避免创建重复内容。")
}

private suspend fun handleUpdateEntry(
    manager: KnowledgeBaseManager,
    request: KnowledgeBaseCopilotRequest,
    entry: KBEntry,
    topic: KBTopic,
    parsed: KnowledgeBaseAiParseResult,
    action: KnowledgeBaseAiAction,
): KnowledgeBaseCopilotResponse {
    val normalizedDraft = normalizeCopilotDraft(entry, topic, action)
    if (!request.applyChanges) {
        return KnowledgeBaseCopilotResponse(
            success = true,
            assistantReply = parsed.cleanedContent,
            draft = normalizedDraft,
            message = "已生成 KB 修改草稿",
        )
    }

    val applyResult = executeKnowledgeBaseAiActions(
        manager = manager,
        request = KnowledgeBaseAiExecutionRequest(userId = request.userId),
        actions = listOf(
            action.copy(
                topicId = topic.id,
                entryId = entry.id,
                title = normalizedDraft.title,
                content = normalizedDraft.content,
                tags = normalizedDraft.tags,
                status = entry.status.takeUnless { it == KBEntryStatus.DELETED },
            )
        ),
    ).firstOrNull()

    return when (applyResult) {
        is KnowledgeBaseAiExecutionResult.Success -> KnowledgeBaseCopilotResponse(
            success = true,
            assistantReply = parsed.cleanedContent,
            draft = normalizedDraft,
            appliedEntry = applyResult.entry,
            message = applyResult.message,
        )
        is KnowledgeBaseAiExecutionResult.Failure -> KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = parsed.cleanedContent,
            draft = normalizedDraft,
            message = applyResult.message,
        )
        null -> KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = parsed.cleanedContent,
            draft = normalizedDraft,
            message = "KB Copilot apply failed",
        )
    }
}

private suspend fun handleCreateEntryDraft(
    manager: KnowledgeBaseManager,
    request: KnowledgeBaseCopilotRequest,
    topic: KBTopic,
    parsed: KnowledgeBaseAiParseResult,
    action: KnowledgeBaseAiAction,
): KnowledgeBaseCopilotResponse {
    val title = action.title?.trim().orEmpty()
    val content = action.content?.trim().orEmpty()
    if (title.isBlank() || content.isBlank()) {
        return KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = parsed.cleanedContent,
            message = "AI did not return a complete entry",
        )
    }
    val tags = action.tags.map(String::trim).filter(String::isNotBlank).distinct()
    val draft = KnowledgeBaseCopilotDraft(
        entryId = null,
        topicId = topic.id,
        title = title,
        content = content,
        tags = tags,
    )

    if (!request.applyChanges) {
        return KnowledgeBaseCopilotResponse(
            success = true,
            assistantReply = parsed.cleanedContent,
            draft = draft,
            message = "已生成 KB 新建草稿",
        )
    }

    val applyResult = executeKnowledgeBaseAiActions(
        manager = manager,
        request = KnowledgeBaseAiExecutionRequest(userId = request.userId),
        actions = listOf(
            action.copy(
                topicId = topic.id,
                title = title,
                content = content,
                tags = tags,
                status = KBEntryStatus.CANDIDATE,
            )
        ),
    ).firstOrNull()

    return when (applyResult) {
        is KnowledgeBaseAiExecutionResult.Success -> KnowledgeBaseCopilotResponse(
            success = true,
            assistantReply = parsed.cleanedContent,
            draft = draft,
            appliedEntry = applyResult.entry,
            message = applyResult.message,
        )
        is KnowledgeBaseAiExecutionResult.Failure -> KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = parsed.cleanedContent,
            draft = draft,
            message = applyResult.message,
        )
        null -> KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = parsed.cleanedContent,
            draft = draft,
            message = "KB Copilot apply failed",
        )
    }
}

private fun normalizeCopilotDraft(
    entry: KBEntry,
    topic: KBTopic,
    action: KnowledgeBaseAiAction,
): KnowledgeBaseCopilotDraft {
    val normalizedTags = action.tags
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    return KnowledgeBaseCopilotDraft(
        entryId = entry.id,
        topicId = topic.id,
        title = action.title?.trim().takeUnless { it.isNullOrBlank() } ?: entry.title,
        content = action.content?.trim().takeUnless { it.isNullOrBlank() } ?: entry.content,
        tags = if (normalizedTags.isEmpty()) entry.tags else normalizedTags,
    )
}
