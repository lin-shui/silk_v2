package com.silk.backend.kb

import com.silk.backend.ai.AvailableReferenceSeed
import com.silk.backend.database.GroupRepository
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBTopic
import com.silk.backend.models.KnowledgeBaseContextSelection
import com.silk.backend.models.KnowledgeSpaceType

data class KnowledgeBasePromptContext(
    val resolvedUserInput: String,
    val promptBlock: String? = null,
    val availableReferences: List<AvailableReferenceSeed> = emptyList(),
    val diagnostics: KnowledgeBaseContextDiagnostics = KnowledgeBaseContextDiagnostics(),
)

data class KnowledgeBaseContextDiagnostics(
    val manualReferenceCount: Int = 0,
    val pinnedReferenceCount: Int = 0,
    val autoCandidateCount: Int = 0,
    val memoryReferenceCount: Int = 0,
    val excludedReferenceCount: Int = 0,
    val excludedSpaceCount: Int = 0,
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
    memoryCandidateLimit: Int = 5,
    memoryEnabled: Boolean = true,
    selection: KnowledgeBaseContextSelection = KnowledgeBaseContextSelection(),
): KnowledgeBasePromptContext {
    val tokens = parseKnowledgeBaseReferenceTokens(rawInput)
    val resolvedByEntryId = resolveManualKnowledgeBaseReferences(tokens, userId, knowledgeBaseManager)
    val resolvedUserInput = tokens.fold(rawInput) { acc, token ->
        val label = resolvedByEntryId[token.entryId]?.label ?: (token.label ?: "知识库文档 ${token.entryId}")
        acc.replace(token.fullMatch, "《$label》")
    }
    val manualEntryIds = resolvedByEntryId.keys.toSet()
    val excludedEntryIds = normalizeExcludedEntryIds(selection, manualEntryIds)
    val excludedSpaceIds = normalizeExcludedSpaceIds(selection)
    val pinnedReferences = resolvePinnedKnowledgeBaseReferences(
        selection = selection,
        manualEntryIds = manualEntryIds,
        excludedEntryIds = excludedEntryIds,
        userId = userId,
        knowledgeBaseManager = knowledgeBaseManager,
    )
    val autoReferences = resolveAutoKnowledgeBaseReferences(
        knowledgeBaseManager = knowledgeBaseManager,
        userId = userId,
        preferredGroupId = preferredGroupId,
        query = resolvedUserInput,
        autoCandidateLimit = autoCandidateLimit,
        excludedEntryIds = manualEntryIds + pinnedReferences.map { it.entry.id } + excludedEntryIds,
        excludedSpaceIds = excludedSpaceIds,
    )
    val memoryReferences = if (memoryEnabled) {
        resolveMemoryKnowledgeBaseReferences(
            knowledgeBaseManager = knowledgeBaseManager,
            userId = userId,
            query = resolvedUserInput,
            memoryCandidateLimit = memoryCandidateLimit,
        )
    } else {
        emptyList()
    }
    val hasNoReferences = resolvedByEntryId.isEmpty() &&
        pinnedReferences.isEmpty() &&
        autoReferences.isEmpty() &&
        memoryReferences.isEmpty()
    if (hasNoReferences) {
        return KnowledgeBasePromptContext(resolvedUserInput = resolvedUserInput)
    }
    val references = buildKnowledgeBaseReferenceSeeds(
        manualReferences = resolvedByEntryId.values.toList(),
        pinnedReferences = pinnedReferences,
        autoReferences = autoReferences,
        memoryReferences = memoryReferences,
    )
    val promptBlock = buildKnowledgeBasePromptBlock(
        manualReferences = resolvedByEntryId.values.toList(),
        pinnedReferences = pinnedReferences,
        autoReferences = autoReferences,
        memoryReferences = memoryReferences,
    )
    return KnowledgeBasePromptContext(
        resolvedUserInput = resolvedUserInput,
        promptBlock = promptBlock,
        availableReferences = references,
        diagnostics = KnowledgeBaseContextDiagnostics(
            manualReferenceCount = resolvedByEntryId.size,
            pinnedReferenceCount = pinnedReferences.size,
            autoCandidateCount = autoReferences.size,
            memoryReferenceCount = memoryReferences.size,
            excludedReferenceCount = excludedEntryIds.size,
            excludedSpaceCount = excludedSpaceIds.size,
        ),
    )
}

private fun parseKnowledgeBaseReferenceTokens(rawInput: String): List<KnowledgeBaseReferenceToken> {
    return kbReferenceRegex.findAll(rawInput).map { match ->
        KnowledgeBaseReferenceToken(
            fullMatch = match.value,
            entryId = match.groupValues[1],
            label = match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }.toList()
}

private fun resolveManualKnowledgeBaseReferences(
    tokens: List<KnowledgeBaseReferenceToken>,
    userId: String,
    knowledgeBaseManager: KnowledgeBaseManager,
): LinkedHashMap<String, ResolvedKnowledgeBaseReference> {
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
    return LinkedHashMap(resolvedByEntryId)
}

private fun normalizeExcludedEntryIds(
    selection: KnowledgeBaseContextSelection,
    manualEntryIds: Set<String>,
): Set<String> {
    return selection.excludedEntryIds.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filterNot { it in manualEntryIds }
        .toSet()
}

private fun normalizeExcludedSpaceIds(
    selection: KnowledgeBaseContextSelection,
): Set<String> {
    return selection.excludedSpaceIds.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
}

private fun resolvePinnedKnowledgeBaseReferences(
    selection: KnowledgeBaseContextSelection,
    manualEntryIds: Set<String>,
    excludedEntryIds: Set<String>,
    userId: String,
    knowledgeBaseManager: KnowledgeBaseManager,
): List<ResolvedKnowledgeBaseReference> {
    return selection.pinnedEntryIds.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .filterNot { it in manualEntryIds || it in excludedEntryIds }
        .mapNotNull { entryId ->
            val entry = knowledgeBaseManager.getEntry(entryId, userId) ?: return@mapNotNull null
            if (entry.status != KBEntryStatus.PUBLISHED) return@mapNotNull null
            val topic = knowledgeBaseManager.getTopic(entry.topicId, userId) ?: return@mapNotNull null
            ResolvedKnowledgeBaseReference(
                entry = entry,
                topic = topic,
                label = entry.title,
            )
        }
        .toList()
}

private fun resolveAutoKnowledgeBaseReferences(
    knowledgeBaseManager: KnowledgeBaseManager,
    userId: String,
    preferredGroupId: String?,
    query: String,
    autoCandidateLimit: Int,
    excludedEntryIds: Set<String>,
    excludedSpaceIds: Set<String>,
): List<AutoKnowledgeBaseReference> {
    return knowledgeBaseManager.searchEntriesForContext(
        userId = userId,
        query = query,
        preferredGroupId = preferredGroupId,
        limit = autoCandidateLimit,
        excludedEntryIds = excludedEntryIds,
        excludedSpaceIds = excludedSpaceIds,
    ).map { hit ->
        AutoKnowledgeBaseReference(
            entry = hit.entry,
            topic = hit.topic,
            reasons = hit.reasons,
        )
    }
}

private fun resolveMemoryKnowledgeBaseReferences(
    knowledgeBaseManager: KnowledgeBaseManager,
    userId: String,
    query: String,
    memoryCandidateLimit: Int,
): List<ResolvedKnowledgeBaseReference> {
    return knowledgeBaseManager.searchMemoryEntriesForContext(
        userId = userId,
        query = query,
        limit = memoryCandidateLimit,
    ).map { hit ->
        ResolvedKnowledgeBaseReference(
            entry = hit.entry,
            topic = hit.topic,
            label = hit.entry.title,
        )
    }
}

private fun buildKnowledgeBaseReferenceSeeds(
    manualReferences: List<ResolvedKnowledgeBaseReference>,
    pinnedReferences: List<ResolvedKnowledgeBaseReference>,
    autoReferences: List<AutoKnowledgeBaseReference>,
    memoryReferences: List<ResolvedKnowledgeBaseReference>,
): List<AvailableReferenceSeed> {
    return manualReferences.map { resolved ->
        AvailableReferenceSeed(
            title = "${resolved.topic.name} / ${resolved.entry.title}",
            snippet = buildKnowledgeBaseSnippet(resolved.entry),
            path = buildKnowledgeBasePath(resolved.topic.id, resolved.entry.id),
            origin = "manual",
            reason = "用户手动引用",
            spaceId = knowledgeBaseSpaceId(resolved.topic),
            spaceLabel = knowledgeBaseSpaceLabel(resolved.topic),
        )
    } + pinnedReferences.map { resolved ->
        AvailableReferenceSeed(
            title = "${resolved.topic.name} / ${resolved.entry.title}",
            snippet = buildKnowledgeBaseSnippet(resolved.entry),
            path = buildKnowledgeBasePath(resolved.topic.id, resolved.entry.id),
            origin = "pin",
            reason = "用户固定到本轮上下文",
            spaceId = knowledgeBaseSpaceId(resolved.topic),
            spaceLabel = knowledgeBaseSpaceLabel(resolved.topic),
        )
    } + autoReferences.map { resolved ->
        AvailableReferenceSeed(
            title = "${resolved.topic.name} / ${resolved.entry.title}",
            snippet = buildKnowledgeBaseSnippet(resolved.entry),
            path = buildKnowledgeBasePath(resolved.topic.id, resolved.entry.id),
            origin = "auto",
            reason = resolved.reasons.joinToString("、"),
            spaceId = knowledgeBaseSpaceId(resolved.topic),
            spaceLabel = knowledgeBaseSpaceLabel(resolved.topic),
        )
    } + memoryReferences.map { resolved ->
        AvailableReferenceSeed(
            title = "Memory / ${resolved.entry.title}",
            snippet = buildKnowledgeBaseSnippet(resolved.entry),
            path = buildKnowledgeBasePath(resolved.topic.id, resolved.entry.id),
            origin = "memory",
            reason = buildMemoryReason(resolved.entry),
            spaceId = "memory:${resolved.entry.ownerId}",
            spaceLabel = "长期记忆",
        )
    }
}

internal fun buildKnowledgeBasePath(topicId: String, entryId: String): String =
    "kb://$topicId/$entryId"

private fun knowledgeBaseSpaceId(topic: KBTopic): String {
    return when (topic.spaceType) {
        KnowledgeSpaceType.TEAM -> topic.groupId?.takeIf { it.isNotBlank() } ?: PERSONAL_KB_SPACE_ID
        KnowledgeSpaceType.PERSONAL -> PERSONAL_KB_SPACE_ID
    }
}

private fun knowledgeBaseSpaceLabel(topic: KBTopic): String {
    return when (topic.spaceType) {
        KnowledgeSpaceType.PERSONAL -> "个人空间"
        KnowledgeSpaceType.TEAM -> {
            val groupName = topic.groupId?.let { GroupRepository.findGroupById(it)?.name }
            groupName?.takeIf { it.isNotBlank() } ?: "团队空间"
        }
    }
}

private fun buildKnowledgeBasePromptBlock(
    manualReferences: List<ResolvedKnowledgeBaseReference>,
    pinnedReferences: List<ResolvedKnowledgeBaseReference>,
    autoReferences: List<AutoKnowledgeBaseReference>,
    memoryReferences: List<ResolvedKnowledgeBaseReference>,
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

    if (pinnedReferences.isNotEmpty()) {
        appendLine("### 用户固定上下文")
        pinnedReferences.forEach { resolved ->
            val marker = "[available:${nextIndex++}]"
            appendReferenceBlock(
                marker = marker,
                topic = resolved.topic,
                entry = resolved.entry,
                reasons = listOf("用户固定到本轮上下文"),
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

    if (memoryReferences.isNotEmpty()) {
        appendLine("### 用户长期记忆")
        appendLine(MEMORY_PROMPT_HEADER)
        memoryReferences.forEach { resolved ->
            val marker = "[available:${nextIndex++}]"
            appendReferenceBlock(
                marker = marker,
                topic = resolved.topic,
                entry = resolved.entry,
                reasons = listOf(buildMemoryReason(resolved.entry)),
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
