package com.silk.backend.kb

import com.silk.backend.ai.DirectModelAgent
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBTopic

object KnowledgeBaseReferenceResolver {
    data class PromptContext(
        val resolvedUserInput: String,
        val promptBlock: String? = null,
        val availableReferences: List<DirectModelAgent.AvailableReferenceSeed> = emptyList(),
    )

    private data class ReferenceToken(
        val fullMatch: String,
        val entryId: String,
        val label: String?,
    )

    private data class ResolvedReference(
        val entry: KBEntry,
        val topic: KBTopic,
        val label: String,
    )

    private val kbReferenceRegex = Regex("""\[\[kb:([A-Za-z0-9_\-]+)(?:\|([^\]]+))?\]\]""")

    fun resolvePromptContext(
        rawInput: String,
        userId: String,
        knowledgeBaseManager: KnowledgeBaseManager,
    ): PromptContext {
        val tokens = kbReferenceRegex.findAll(rawInput).map { match ->
            ReferenceToken(
                fullMatch = match.value,
                entryId = match.groupValues[1],
                label = match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }.toList()
        if (tokens.isEmpty()) {
            return PromptContext(resolvedUserInput = rawInput)
        }

        val resolvedByEntryId = linkedMapOf<String, ResolvedReference>()
        tokens.forEach { token ->
            if (resolvedByEntryId.containsKey(token.entryId)) return@forEach
            val entry = knowledgeBaseManager.getEntry(token.entryId, userId) ?: return@forEach
            val topic = knowledgeBaseManager.getTopic(entry.topicId, userId) ?: return@forEach
            resolvedByEntryId[token.entryId] = ResolvedReference(
                entry = entry,
                topic = topic,
                label = token.label ?: entry.title,
            )
        }

        val resolvedUserInput = tokens.fold(rawInput) { acc, token ->
            val label = resolvedByEntryId[token.entryId]?.label ?: (token.label ?: "知识库文档 ${token.entryId}")
            acc.replace(token.fullMatch, "《$label》")
        }
        if (resolvedByEntryId.isEmpty()) {
            return PromptContext(resolvedUserInput = resolvedUserInput)
        }

        val references = resolvedByEntryId.values.map { resolved ->
            DirectModelAgent.AvailableReferenceSeed(
                title = "${resolved.topic.name} / ${resolved.entry.title}",
                snippet = buildKnowledgeBaseSnippet(resolved.entry),
                path = buildKnowledgeBasePath(resolved.topic.id, resolved.entry.id),
            )
        }
        val promptBlock = buildKnowledgeBasePromptBlock(resolvedByEntryId.values.toList())
        return PromptContext(
            resolvedUserInput = resolvedUserInput,
            promptBlock = promptBlock,
            availableReferences = references,
        )
    }

    internal fun buildKnowledgeBasePath(topicId: String, entryId: String): String =
        "kb://$topicId/$entryId"

    private fun buildKnowledgeBasePromptBlock(
        references: List<ResolvedReference>,
    ): String = buildString {
        appendLine("## 用户显式引用的知识库文档")
        appendLine("以下文档已被用户明确放入本轮上下文。")
        appendLine("如果你的回答使用了这些文档中的事实、定义或步骤，请在相关句末使用对应的 [available:数字] 标记。")
        appendLine()
        references.forEachIndexed { index, resolved ->
            val marker = "[available:${index + 1}]"
            appendLine("$marker ${resolved.topic.name} / ${resolved.entry.title}")
            if (resolved.entry.tags.isNotEmpty()) {
                appendLine("标签: ${resolved.entry.tags.joinToString(", ")}")
            }
            appendLine("内容:")
            appendLine(truncateKnowledgeBaseContent(resolved.entry.content))
            appendLine()
        }
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
}
