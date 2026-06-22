package com.silk.backend.kb

import com.silk.backend.ai.DirectModelAgent
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBTopic

data class KnowledgeBasePromptContext(
    val resolvedUserInput: String,
    val promptBlock: String? = null,
    val availableReferences: List<DirectModelAgent.AvailableReferenceSeed> = emptyList(),
    val diagnostics: KnowledgeBaseContextDiagnostics = KnowledgeBaseContextDiagnostics(),
)

data class KnowledgeBaseContextDiagnostics(
    val manualReferenceCount: Int = 0,
    val autoCandidateCount: Int = 0,
)

private data class KnowledgeBaseReferenceToken(
    val fullMatch: String,
    val entryId: String,
    val label: String?,
)

private data class ResolvedKnowledgeBaseReference(
    val entry: KBEntry,
    val topic: KBTopic,
    val label: String,
)

private data class AutoKnowledgeBaseReference(
    val entry: KBEntry,
    val topic: KBTopic,
    val reasons: List<String>,
)

private val kbReferenceRegex = Regex("""\[\[kb:([A-Za-z0-9_\-]+)(?:\|([^\]]+))?\]\]""")

fun resolveKnowledgeBasePromptContext(
    rawInput: String,
    userId: String,
    knowledgeBaseManager: KnowledgeBaseManager,
    preferredGroupId: String? = null,
    autoCandidateLimit: Int = 3,
): KnowledgeBasePromptContext {
    val tokens = kbReferenceRegex.findAll(rawInput).map { match ->
        KnowledgeBaseReferenceToken(
            fullMatch = match.value,
            entryId = match.groupValues[1],
            label = match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }.toList()
    val resolvedByEntryId = linkedMapOf<String, ResolvedKnowledgeBaseReference>()
    tokens.forEach { token ->
        if (resolvedByEntryId.containsKey(token.entryId)) return@forEach
        val entry = knowledgeBaseManager.getEntry(token.entryId, userId) ?: return@forEach
        val topic = knowledgeBaseManager.getTopic(entry.topicId, userId) ?: return@forEach
        resolvedByEntryId[token.entryId] = ResolvedKnowledgeBaseReference(
            entry = entry,
            topic = topic,
            label = token.label ?: entry.title,
        )
    }

    val resolvedUserInput = tokens.fold(rawInput) { acc, token ->
        val label = resolvedByEntryId[token.entryId]?.label ?: (token.label ?: "知识库文档 ${token.entryId}")
        acc.replace(token.fullMatch, "《$label》")
    }

    val autoReferences = knowledgeBaseManager.searchEntriesForContext(
        userId = userId,
        query = resolvedUserInput,
        preferredGroupId = preferredGroupId,
        limit = autoCandidateLimit,
        excludedEntryIds = resolvedByEntryId.keys,
    ).map { hit ->
        AutoKnowledgeBaseReference(
            entry = hit.entry,
            topic = hit.topic,
            reasons = hit.reasons,
        )
    }

    if (resolvedByEntryId.isEmpty() && autoReferences.isEmpty()) {
        return KnowledgeBasePromptContext(resolvedUserInput = resolvedUserInput)
    }

    val references = resolvedByEntryId.values.map { resolved ->
        DirectModelAgent.AvailableReferenceSeed(
            title = "${resolved.topic.name} / ${resolved.entry.title}",
            snippet = buildKnowledgeBaseSnippet(resolved.entry),
            path = buildKnowledgeBasePath(resolved.topic.id, resolved.entry.id),
            origin = "manual",
            reason = "用户手动引用",
        )
    } + autoReferences.map { resolved ->
        DirectModelAgent.AvailableReferenceSeed(
            title = "${resolved.topic.name} / ${resolved.entry.title}",
            snippet = buildKnowledgeBaseSnippet(resolved.entry),
            path = buildKnowledgeBasePath(resolved.topic.id, resolved.entry.id),
            origin = "auto",
            reason = resolved.reasons.joinToString("、"),
        )
    }
    val promptBlock = buildKnowledgeBasePromptBlock(
        manualReferences = resolvedByEntryId.values.toList(),
        autoReferences = autoReferences,
    )
    return KnowledgeBasePromptContext(
        resolvedUserInput = resolvedUserInput,
        promptBlock = promptBlock,
        availableReferences = references,
        diagnostics = KnowledgeBaseContextDiagnostics(
            manualReferenceCount = resolvedByEntryId.size,
            autoCandidateCount = autoReferences.size,
        ),
    )
}

internal fun buildKnowledgeBasePath(topicId: String, entryId: String): String =
    "kb://$topicId/$entryId"

private fun buildKnowledgeBasePromptBlock(
    manualReferences: List<ResolvedKnowledgeBaseReference>,
    autoReferences: List<AutoKnowledgeBaseReference>,
): String = buildString {
    appendLine("## 知识库上下文")
    appendLine("以下知识库文档已进入本轮上下文。")
    appendLine("如果你的回答使用了这些文档中的事实、定义或步骤，请在相关句末使用对应的 [available:数字] 标记。")
    appendLine()
    var nextIndex = 1

    if (manualReferences.isNotEmpty()) {
        appendLine("### 用户显式引用")
        manualReferences.forEach { resolved ->
            val marker = "[available:${nextIndex++}]"
            appendReferenceBlock(
                marker = marker,
                topic = resolved.topic,
                entry = resolved.entry,
                reasons = listOf("用户手动引用"),
            )
        }
    }

    if (autoReferences.isNotEmpty()) {
        appendLine("### 自动补充候选")
        autoReferences.forEach { resolved ->
            val marker = "[available:${nextIndex++}]"
            appendReferenceBlock(
                marker = marker,
                topic = resolved.topic,
                entry = resolved.entry,
                reasons = resolved.reasons,
            )
        }
    }
}

private fun StringBuilder.appendReferenceBlock(
    marker: String,
    topic: KBTopic,
    entry: KBEntry,
    reasons: List<String>,
) {
    appendLine("$marker ${topic.name} / ${entry.title}")
    if (reasons.isNotEmpty()) {
        appendLine("加入原因: ${reasons.joinToString("、")}")
    }
    if (entry.tags.isNotEmpty()) {
        appendLine("标签: ${entry.tags.joinToString(", ")}")
    }
    appendLine("内容:")
    appendLine(truncateKnowledgeBaseContent(entry.content))
    appendLine()
}

private fun buildKnowledgeBaseSnippet(entry: KBEntry): String {
    return truncateKnowledgeBaseContent(entry.content, maxChars = 180)
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun truncateKnowledgeBaseContent(content: String, maxChars: Int = 8_000): String {
    val normalized = content.trim()
    if (normalized.length <= maxChars) {
        return normalized
    }
    return normalized.take(maxChars) + "\n...(已截断)"
}
