package com.silk.backend.kb

import com.silk.backend.ai.KnowledgeBaseWorkspaceEntry
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBTopic
import com.silk.backend.models.KBEntryStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    /** Line-level diff chunks for inline diff review in the frontend. */
    val diffChunks: List<DiffChunk> = emptyList(),
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

/**
 * Result from runAgent: the display text (without silk_kb_action blocks) and the
 * extracted KB actions.  Keeping them together avoids the problem of re-parsing
 * already-cleaned text to re-discover actions that were stripped by
 * DirectModelAgent.finalizeAgentResponse internally.
 */
data class KbCopilotAgentResult(
    val displayText: String,
    val actions: List<KnowledgeBaseAiAction>,
)

/**
 * Stream event emitted during copilot execution.
 * Used by the SSE endpoint to push real-time status to the frontend.
 */
@Serializable
data class CopilotStreamEvent(
    val event: String, // "thinking" | "text" | "draft" | "applied" | "error" | "done"
    val data: String = "",
)

/**
 * A single chunk in a line-level diff between original and AI-suggested content.
 * Used by the frontend to render inline diff with accept/reject controls.
 */
@Serializable
data class DiffChunk(
    val type: String, // "unchanged" | "deleted" | "inserted" | "modified"
    val originalText: String = "",
    val newText: String = "",
    val lineStart: Int = 0,
    val lineEnd: Int = 0,
)

@Suppress("CyclomaticComplexMethod")
suspend fun executeKnowledgeBaseCopilot(
    manager: KnowledgeBaseManager,
    request: KnowledgeBaseCopilotRequest,
    runAgent: suspend (KnowledgeBaseCopilotAgentInput) -> KbCopilotAgentResult,
    /** Optional streaming callback: first param is event type, second is data. */
    onStreamEvent: (suspend (CopilotStreamEvent) -> Unit)? = null,
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
        onStreamEvent?.invoke(CopilotStreamEvent(event = "error", data = "Topic not found for entry"))
        onStreamEvent?.invoke(CopilotStreamEvent(event = "done"))
        return KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = "",
            message = "Topic not found for entry",
        )
    }
    if (entry == null && topic == null) {
        onStreamEvent?.invoke(CopilotStreamEvent(event = "error", data = "Please select an entry or topic first"))
        onStreamEvent?.invoke(CopilotStreamEvent(event = "done"))
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

    onStreamEvent?.invoke(CopilotStreamEvent(event = "thinking", data = "🤔 AI 正在分析指令…"))
    val agentResult = runAgent(
        KnowledgeBaseCopilotAgentInput(
            systemPrompt = systemPrompt,
            userPrompt = fullUserPrompt,
            workspaceEntries = buildKnowledgeBaseWorkspaceEntries(manager, request.userId),
        )
    )
    val displayText = stripThinkingBlocks(agentResult.displayText)
    onStreamEvent?.invoke(CopilotStreamEvent(event = "text", data = displayText))

    // Build a KnowledgeBaseAiParseResult from the agent's pre-extracted actions
    // for backward compatibility with handleUpdateEntry / handleCreateEntryDraft
    val parsed = KnowledgeBaseAiParseResult(
        cleanedContent = agentResult.displayText,
        actions = agentResult.actions,
    )

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
                val result = handleCreateEntryDraft(manager, request, topic, parsed, createAction)
                emitStreamEventsForResult(result, onStreamEvent)
                return result
            }
            onStreamEvent?.invoke(CopilotStreamEvent(event = "error", data = "AI did not return a KB update draft"))
            onStreamEvent?.invoke(CopilotStreamEvent(event = "done"))
            return KnowledgeBaseCopilotResponse(
                success = false,
                assistantReply = agentResult.displayText,
                message = "AI did not return a KB update draft",
            )
        }
        val result = handleUpdateEntry(manager, request, entry, topic, parsed, updateAction)
        emitStreamEventsForResult(result, onStreamEvent)
        return result
    }

    // Topic-level: look for CREATE_ENTRY action
    val createAction = parsed.actions.firstOrNull {
        it.operation == KnowledgeBaseAiOperation.CREATE_ENTRY
    }
    if (createAction == null) {
        onStreamEvent?.invoke(CopilotStreamEvent(event = "error", data = "AI did not return a KB entry draft"))
        onStreamEvent?.invoke(CopilotStreamEvent(event = "done"))
        return KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = parsed.cleanedContent,
            message = "AI did not return a KB entry draft",
        )
    }
    val result = handleCreateEntryDraft(manager, request, topic!!, parsed, createAction)
    emitStreamEventsForResult(result, onStreamEvent)
    return result
}

/**
 * Emit stream events (draft/applied/error/done) based on the copilot response.
 */
private suspend fun emitStreamEventsForResult(
    result: KnowledgeBaseCopilotResponse,
    onStreamEvent: (suspend (CopilotStreamEvent) -> Unit)?,
) {
    if (onStreamEvent == null) return
    if (result.draft != null) {
        val draftJson = kotlinx.serialization.json.Json.encodeToString(
            KnowledgeBaseCopilotDraft.serializer(), result.draft
        )
        onStreamEvent(CopilotStreamEvent(event = "draft", data = draftJson))
    }
    if (result.appliedEntry != null) {
        val entryJson = kotlinx.serialization.json.Json.encodeToString(
            com.silk.backend.models.KBEntry.serializer(), result.appliedEntry
        )
        onStreamEvent(CopilotStreamEvent(event = "applied", data = entryJson))
    }
    if (!result.success) {
        onStreamEvent(CopilotStreamEvent(event = "error", data = result.message))
    }
    onStreamEvent(CopilotStreamEvent(event = "done"))
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

/**
 * Compute a line-level diff between original and new content using LCS (Longest Common Subsequence).
 * Returns a list of DiffChunks that can be rendered by the frontend for inline diff review.
 */
@Suppress("CyclomaticComplexMethod")
fun computeLineDiff(original: String, newContent: String): List<DiffChunk> {
    val origLines = original.split("\n")
    val newLines = newContent.split("\n")

    if (original == newContent) {
        return listOf(DiffChunk(
            type = "unchanged",
            originalText = original,
            newText = newContent,
            lineStart = 0,
            lineEnd = (origLines.size - 1).coerceAtLeast(0),
        ))
    }

    // Build LCS table
    val m = origLines.size
    val n = newLines.size
    val dp = Array(m + 1) { IntArray(n + 1) }

    for (i in 1..m) {
        for (j in 1..n) {
            dp[i][j] = if (origLines[i - 1] == newLines[j - 1]) {
                dp[i - 1][j - 1] + 1
            } else {
                maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    // Backtrack to build diff in reverse order
    val reverseChunks = mutableListOf<DiffChunk>()
    var i = m
    var j = n

    while (i > 0 || j > 0) {
        when {
            i > 0 && j > 0 && origLines[i - 1] == newLines[j - 1] -> {
                reverseChunks.add(DiffChunk(
                    type = "unchanged",
                    originalText = origLines[i - 1],
                    newText = newLines[j - 1],
                    lineStart = i - 1,
                    lineEnd = i - 1,
                ))
                i--
                j--
            }
            j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                reverseChunks.add(DiffChunk(
                    type = "inserted",
                    originalText = "",
                    newText = newLines[j - 1],
                    lineStart = i,
                    lineEnd = i,
                ))
                j--
            }
            i > 0 -> {
                reverseChunks.add(DiffChunk(
                    type = "deleted",
                    originalText = origLines[i - 1],
                    newText = "",
                    lineStart = i - 1,
                    lineEnd = i - 1,
                ))
                i--
            }
        }
    }

    // Reverse to chronological order, then merge consecutive same-type chunks
    val orderedChunks = reverseChunks.asReversed()
    return mergeDiffChunks(orderedChunks).filterNot { chunk ->
        // Filter out unchanged chunks with empty text (e.g. blank lines, trailing newlines)
        // so the diff review UI doesn't show confusing empty "未更改内容" sections.
        chunk.type == "unchanged" && chunk.originalText.isEmpty()
    }
}

/**
 * Merge consecutive same-type diff chunks into larger chunks for cleaner display.
 */
private fun mergeDiffChunks(chunks: List<DiffChunk>): List<DiffChunk> {
    if (chunks.isEmpty()) return emptyList()

    val merged = mutableListOf<DiffChunk>()
    var current = chunks[0]

    for (idx in 1 until chunks.size) {
        val next = chunks[idx]
        if (current.type == next.type) {
            // Merge same-type chunks: join text with newlines, extend line range
            current = current.copy(
                originalText = buildString {
                    if (current.originalText.isNotEmpty()) append(current.originalText)
                    if (current.originalText.isNotEmpty() && next.originalText.isNotEmpty()) append("\n")
                    if (next.originalText.isNotEmpty()) append(next.originalText)
                },
                newText = buildString {
                    if (current.newText.isNotEmpty()) append(current.newText)
                    if (current.newText.isNotEmpty() && next.newText.isNotEmpty()) append("\n")
                    if (next.newText.isNotEmpty()) append(next.newText)
                },
                lineEnd = next.lineEnd,
            )
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged
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
    appendLine("1. 先用中文总结你的修改方案（最多3句），不要使用 <!--THINKING--> 标签，这段总结会直接显示给用户。")
    appendLine("2. 然后必须输出一个 ```silk_kb_action 代码块，代码块必须使用 silk_kb_action 语言标记，不要用 json 或其他标记。")
    appendLine("3. 代码块内只允许一个 JSON 对象，operation 字段必须为 \"update_entry\"。")
    appendLine("4. JSON 必须包含 operation、topicId、entryId、title、content、tags 等字段。")
    appendLine("5. JSON 中的 topicId 必须为 \"${topic.id}\"，entryId 必须为 \"${entry.id}\"。")
    appendLine("6. title/content/tags 必须提供修改后的完整结果；content 使用完整 Markdown，而不是 diff。")
    appendLine("7. 如果你判断当前文档无需改动，也要输出 update_entry，并保留原文内容。")
    appendLine()
    appendLine("## 输出示例")
    appendLine("```")
    appendLine("总结内容...")
    appendLine()
    appendLine("```silk_kb_action")
    appendLine("{")
    appendLine("  \"operation\": \"update_entry\",")
    appendLine("  \"topicId\": \"${topic.id}\",")
    appendLine("  \"entryId\": \"${entry.id}\",")
    appendLine("  \"title\": \"${entry.title}\",")
    appendLine("  \"content\": \"更新后的完整 Markdown 内容\",")
    appendLine("  \"tags\": [\"tag1\", \"tag2\"]")
    appendLine("}")
    appendLine("```")
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
    appendLine("1. 先用中文解释你准备创建什么样的文档（最多3句），不要使用 <!--THINKING--> 标签，这段解释会直接显示给用户。")
    appendLine("2. 然后必须输出一个 ```silk_kb_action 代码块，代码块必须使用 silk_kb_action 语言标记，不要用 json 或其他标记。")
    appendLine("3. 代码块内只允许一个 JSON 对象，operation 字段必须为 \"create_entry\"。")
    appendLine("4. JSON 必须包含 operation、topicId、title、content、tags、status 等字段。")
    appendLine("5. JSON 中的 topicId 必须为 \"${topic.id}\"。")
    appendLine("6. title/content/tags 必须提供完整的文档内容。")
    appendLine("7. status 为 \"CANDIDATE\"，方便用户确认后再发布。")
    appendLine("8. content 使用完整 Markdown。")
    appendLine()
    appendLine("## 输出示例")
    appendLine("```")
    appendLine("解释内容...")
    appendLine()
    appendLine("```silk_kb_action")
    appendLine("{")
    appendLine("  \"operation\": \"create_entry\",")
    appendLine("  \"topicId\": \"${topic.id}\",")
    appendLine("  \"title\": \"新条目标题\",")
    appendLine("  \"content\": \"完整的 Markdown 内容\",")
    appendLine("  \"tags\": [\"tag1\", \"tag2\"],")
    appendLine("  \"status\": \"CANDIDATE\"")
    appendLine("}")
    appendLine("```")
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
    // Compute line-level diff between original and suggested content
    val diffChunks = computeLineDiff(entry.content, normalizedDraft.content)
    val draftWithDiff = normalizedDraft.copy(diffChunks = diffChunks)

    if (!request.applyChanges) {
        return KnowledgeBaseCopilotResponse(
            success = true,
            assistantReply = parsed.cleanedContent,
            draft = draftWithDiff,
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
            draft = draftWithDiff,
            appliedEntry = applyResult.entry,
            message = applyResult.message,
        )
        is KnowledgeBaseAiExecutionResult.Failure -> KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = parsed.cleanedContent,
            draft = draftWithDiff,
            message = applyResult.message,
        )
        null -> KnowledgeBaseCopilotResponse(
            success = false,
            assistantReply = parsed.cleanedContent,
            draft = draftWithDiff,
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
