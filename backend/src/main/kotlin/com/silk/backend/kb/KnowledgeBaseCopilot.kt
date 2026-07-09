package com.silk.backend.kb

import com.silk.backend.ai.KnowledgeBaseWorkspaceEntry
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBTopic
import com.silk.backend.models.KBEntryStatus
import kotlinx.serialization.Serializable

@Serializable
data class KnowledgeBaseCopilotRequest(
    val userId: String,
    val entryId: String,
    val instruction: String,
    val applyChanges: Boolean = false,
)

@Serializable
data class KnowledgeBaseCopilotDraft(
    val entryId: String,
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

suspend fun executeKnowledgeBaseCopilot(
    manager: KnowledgeBaseManager,
    request: KnowledgeBaseCopilotRequest,
    runAgent: suspend (KnowledgeBaseCopilotAgentInput) -> String,
): KnowledgeBaseCopilotResponse {
    val normalizedInstruction = request.instruction.trim()
    if (request.userId.isBlank() || request.entryId.isBlank() || normalizedInstruction.isBlank()) {
        return KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = "",
            message = "Missing required copilot fields",
        )
    }

    val entry = manager.getEntry(request.entryId, request.userId)
        ?: return KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = "",
            message = "Entry not found",
        )
    val topic = manager.getTopic(entry.topicId, request.userId)
        ?: return KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = "",
            message = "Topic not found",
        )

    val rawReply = runAgent(
        KnowledgeBaseCopilotAgentInput(
            systemPrompt = buildKnowledgeBaseCopilotSystemPrompt(topic, entry),
            userPrompt = normalizedInstruction,
            workspaceEntries = buildKnowledgeBaseWorkspaceEntries(manager, request.userId),
        )
    )
    val parsed = extractKnowledgeBaseAiActions(rawReply)
    val action = parsed.actions.firstOrNull { candidate ->
        candidate.operation == KnowledgeBaseAiOperation.UPDATE_ENTRY &&
            (candidate.entryId.isNullOrBlank() || candidate.entryId == entry.id)
    }

    if (action == null) {
        return KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = parsed.cleanedContent,
            message = "AI did not return a KB update draft",
        )
    }

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
