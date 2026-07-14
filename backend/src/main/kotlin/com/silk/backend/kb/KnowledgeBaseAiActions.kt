package com.silk.backend.kb

import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBEntrySource
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBSourceType
import com.silk.backend.models.KBTopic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val kbActionJson = Json { ignoreUnknownKeys = true }
private val kbActionBlockRegex = Regex("""```silk_kb_action\s*([\s\S]*)```""")
private val jsonCodeBlockRegex = Regex("""```json\s*([\s\S]*)```""")
private val thinkingBlockRegex = Regex("""<!--THINKING-->[\s\S]*?<!--END_THINKING-->""")

@Serializable
data class KnowledgeBaseAiActionEnvelope(
    val actions: List<KnowledgeBaseAiAction> = emptyList(),
)

@Serializable
data class KnowledgeBaseAiAction(
    val operation: KnowledgeBaseAiOperation,
    val topicId: String? = null,
    val topicName: String? = null,
    val entryId: String? = null,
    val entryTitle: String? = null,
    val title: String? = null,
    val content: String? = null,
    val tags: List<String> = emptyList(),
    val status: KBEntryStatus? = null,
    val sourceType: KBSourceType? = null,
)

@Serializable
enum class KnowledgeBaseAiOperation {
    @SerialName("create_entry")
    CREATE_ENTRY,

    @SerialName("update_entry")
    UPDATE_ENTRY,
}

data class KnowledgeBaseAiExecutionRequest(
    val userId: String,
    val preferredGroupId: String? = null,
    val sourceGroupId: String? = null,
    val workflowId: String? = null,
    val recentMessageIds: List<String> = emptyList(),
)

sealed interface KnowledgeBaseAiExecutionResult {
    val action: KnowledgeBaseAiAction
    val message: String

    data class Success(
        override val action: KnowledgeBaseAiAction,
        override val message: String,
        val entry: KBEntry,
        val topic: KBTopic,
    ) : KnowledgeBaseAiExecutionResult

    data class Failure(
        override val action: KnowledgeBaseAiAction,
        override val message: String,
    ) : KnowledgeBaseAiExecutionResult
}

data class KnowledgeBaseAiParseResult(
    val cleanedContent: String,
    val actions: List<KnowledgeBaseAiAction>,
)

/**
 * Strip <!--THINKING-->...<!--END_THINKING--> blocks from display content.
 * This is a display-level cleanup, separate from action parsing logic.
 */
fun stripThinkingBlocks(content: String): String {
    return thinkingBlockRegex.replace(content, "").trim()
}

fun extractKnowledgeBaseAiActions(content: String): KnowledgeBaseAiParseResult {
    val actions = mutableListOf<KnowledgeBaseAiAction>()

    // Step 1: Try silk_kb_action blocks first (primary format)
    // Parse from original content (don't strip thinking blocks here —
    // they carry visible assistant text that must be preserved in cleanedContent)
    val extractFromRegex: (Regex) -> Unit = { regex ->
        regex.findAll(content).forEach { match ->
            val payload = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (payload.isBlank()) return@forEach
            runCatching {
                when {
                    payload.startsWith("[") -> kbActionJson.decodeFromString<List<KnowledgeBaseAiAction>>(payload)
                    payload.startsWith("{") && payload.contains("\"actions\"") ->
                        kbActionJson.decodeFromString<KnowledgeBaseAiActionEnvelope>(payload).actions
                    payload.startsWith("{") -> listOf(kbActionJson.decodeFromString<KnowledgeBaseAiAction>(payload))
                    else -> emptyList()
                }
            }.getOrNull()?.let(actions::addAll)
        }
    }

    extractFromRegex(kbActionBlockRegex)

    // Step 2: If no actions found from silk_kb_action blocks, try ```json blocks as fallback
    if (actions.isEmpty()) {
        extractFromRegex(jsonCodeBlockRegex)
    }

    if (actions.isEmpty()) {
        return KnowledgeBaseAiParseResult(cleanedContent = content, actions = emptyList())
    }

    // Step 3: Clean both types of code blocks from the content for display
    val cleaned = kbActionBlockRegex.replace(content, "")
        .let { jsonCodeBlockRegex.replace(it, "") }
        .replace(Regex("""\n{3,}"""), "\n\n").trim()
    return KnowledgeBaseAiParseResult(cleanedContent = cleaned, actions = actions)
}

fun executeKnowledgeBaseAiActions(
    manager: KnowledgeBaseManager,
    request: KnowledgeBaseAiExecutionRequest,
    actions: List<KnowledgeBaseAiAction>,
): List<KnowledgeBaseAiExecutionResult> {
    if (request.userId.isBlank() || actions.isEmpty()) return emptyList()
    return actions.map { action ->
        when (action.operation) {
            KnowledgeBaseAiOperation.CREATE_ENTRY -> createEntryFromAction(manager, request, action)
            KnowledgeBaseAiOperation.UPDATE_ENTRY -> updateEntryFromAction(manager, request, action)
        }
    }
}

fun buildKnowledgeBaseActionSummary(results: List<KnowledgeBaseAiExecutionResult>): String {
    if (results.isEmpty()) return ""
    return buildString {
        appendLine()
        appendLine()
        appendLine("KB 执行结果:")
        results.forEach { result ->
            when (result) {
                is KnowledgeBaseAiExecutionResult.Success -> appendLine("- ${result.message}")
                is KnowledgeBaseAiExecutionResult.Failure -> appendLine("- 未执行：${result.message}")
            }
        }
    }.trimEnd()
}

private fun createEntryFromAction(
    manager: KnowledgeBaseManager,
    request: KnowledgeBaseAiExecutionRequest,
    action: KnowledgeBaseAiAction,
): KnowledgeBaseAiExecutionResult {
    val topic = resolveWritableTopic(
        manager = manager,
        userId = request.userId,
        topicId = action.topicId,
        topicName = action.topicName,
        preferredGroupId = request.preferredGroupId,
    ) ?: return KnowledgeBaseAiExecutionResult.Failure(action, "找不到可写的知识库主题，请先在 KB 中确认主题名称或改为提供 topicId。")

    val title = action.title?.trim().orEmpty()
    val content = action.content?.trim().orEmpty()
    if (title.isBlank() || content.isBlank()) {
        return KnowledgeBaseAiExecutionResult.Failure(action, "KB 操作缺少标题或正文，已忽略。")
    }

    val sourceType = action.sourceType ?: defaultSourceTypeForRequest(request)
    val created = manager.createEntry(
        topicId = topic.id,
        title = title,
        content = content,
        tags = action.tags.map(String::trim).filter(String::isNotBlank).distinct(),
        userId = request.userId,
        status = forcedStatusForSource(sourceType, action.status),
        source = KBEntrySource(
            sourceType = sourceType,
            sourceGroupId = request.sourceGroupId,
            workflowId = request.workflowId,
            messageIds = request.recentMessageIds,
        ),
    ) ?: return KnowledgeBaseAiExecutionResult.Failure(action, "创建 KB 条目失败，目标主题可能已不存在或当前用户无写权限。")

    return KnowledgeBaseAiExecutionResult.Success(
        action = action,
        message = "已创建 KB 条目《${created.title}》到主题《${topic.name}》。",
        entry = created,
        topic = topic,
    )
}

private fun updateEntryFromAction(
    manager: KnowledgeBaseManager,
    request: KnowledgeBaseAiExecutionRequest,
    action: KnowledgeBaseAiAction,
): KnowledgeBaseAiExecutionResult {
    val entry = resolveWritableEntry(
        manager = manager,
        userId = request.userId,
        entryId = action.entryId,
        entryTitle = action.entryTitle,
        topicId = action.topicId,
        topicName = action.topicName,
        preferredGroupId = request.preferredGroupId,
    ) ?: return KnowledgeBaseAiExecutionResult.Failure(action, "找不到可写的知识库条目，请让 AI 先查阅 manifest 后再提供 entryId。")

    val updated = manager.updateEntry(
        entryId = entry.id,
        topicId = action.topicId?.takeIf { it.isNotBlank() && it != entry.topicId },
        title = action.title?.trim()?.takeIf { it.isNotEmpty() },
        content = action.content?.trim()?.takeIf { it.isNotEmpty() },
        tags = action.tags.takeIf { it.isNotEmpty() }?.map(String::trim)?.filter(String::isNotBlank)?.distinct(),
        status = action.status,
        userId = request.userId,
    ) ?: return KnowledgeBaseAiExecutionResult.Failure(action, "更新 KB 条目失败，目标条目可能已不存在或当前用户无写权限。")

    val topic = manager.getTopic(updated.topicId, request.userId)
        ?: return KnowledgeBaseAiExecutionResult.Failure(action, "KB 条目已更新，但读取最新主题失败。")

    return KnowledgeBaseAiExecutionResult.Success(
        action = action,
        message = "已更新 KB 条目《${updated.title}》。",
        entry = updated,
        topic = topic,
    )
}

private fun forcedStatusForSource(sourceType: KBSourceType, requestedStatus: KBEntryStatus?): KBEntryStatus {
    return when (sourceType) {
        KBSourceType.CHAT, KBSourceType.AI_RESPONSE, KBSourceType.WORKFLOW -> KBEntryStatus.CANDIDATE
        else -> requestedStatus ?: KBEntryStatus.CANDIDATE
    }
}

private fun defaultSourceTypeForRequest(request: KnowledgeBaseAiExecutionRequest): KBSourceType {
    return if (!request.workflowId.isNullOrBlank()) KBSourceType.WORKFLOW else KBSourceType.CHAT
}

private fun resolveWritableTopic(
    manager: KnowledgeBaseManager,
    userId: String,
    topicId: String?,
    topicName: String?,
    preferredGroupId: String?,
): KBTopic? {
    val accessibleTopics = manager.listTopics(userId).filter { manager.canWriteTopic(it, userId) }
    topicId?.trim()?.takeIf { it.isNotEmpty() }?.let { requestedId ->
        accessibleTopics.firstOrNull { it.id == requestedId }?.let { return it }
    }
    val normalizedName = topicName?.normalizeKbLookupKey() ?: return null
    val matched = accessibleTopics.filter { it.name.normalizeKbLookupKey() == normalizedName }
    return matched
        .sortedWith(compareByDescending<KBTopic> { it.groupId == preferredGroupId }.thenByDescending { it.updatedAt })
        .firstOrNull()
}

private fun resolveWritableEntry(
    manager: KnowledgeBaseManager,
    userId: String,
    entryId: String?,
    entryTitle: String?,
    topicId: String?,
    topicName: String?,
    preferredGroupId: String?,
): KBEntry? {
    entryId?.trim()?.takeIf { it.isNotEmpty() }?.let { requestedId ->
        manager.getEntry(requestedId, userId)?.let { entry ->
            val topic = manager.getTopic(entry.topicId, userId)
            if (topic != null && manager.canWriteTopic(topic, userId)) return entry
        }
    }

    val candidateTopics = buildList {
        resolveWritableTopic(manager, userId, topicId, topicName, preferredGroupId)?.let(::add)
        if (isEmpty()) {
            addAll(manager.listTopics(userId).filter { manager.canWriteTopic(it, userId) })
        }
    }
    val normalizedTitle = entryTitle?.normalizeKbLookupKey() ?: return null
    return candidateTopics.asSequence()
        .flatMap { topic -> manager.listEntries(topic.id, userId).asSequence() }
        .filter { it.status != KBEntryStatus.DELETED }
        .sortedByDescending { it.updatedAt }
        .firstOrNull { it.title.normalizeKbLookupKey() == normalizedTitle }
}

private fun String.normalizeKbLookupKey(): String {
    return lowercase().replace(Regex("""\s+"""), " ").trim()
}
