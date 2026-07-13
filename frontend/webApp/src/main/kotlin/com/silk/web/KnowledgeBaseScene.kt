package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.NoLiveLiterals
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.Position
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.bottom
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.flexGrow
import org.jetbrains.compose.web.css.fontFamily
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.left
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginLeft
import org.jetbrains.compose.web.css.maxHeight
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.minWidth
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.position
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.right
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.top
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import kotlin.random.Random

internal enum class KnowledgeEditorMode(val label: String, val compactLabel: String) {
    EDIT("编辑", "编"),
    PREVIEW("预览", "预"),
    SPLIT("分栏", "双"),
}

internal enum class KnowledgeEditorModeSwitchPresentation {
    FULL,
    COMPACT,
    SELECT,
}

internal enum class KnowledgeEntryFilter(val label: String) {
    ALL("全部"),
    CANDIDATE("候选"),
    PUBLISHED("已发布"),
    ARCHIVED("已归档"),
}

internal const val PERSONAL_SPACE_ID = "__personal__"
private const val KNOWLEDGE_TOPIC_SIDEBAR_WIDTH_KEY = "silk_kb_topic_sidebar_width"
private const val KNOWLEDGE_ENTRY_SIDEBAR_WIDTH_KEY = "silk_kb_entry_sidebar_width"
private const val KNOWLEDGE_EDITOR_SPLIT_RATIO_KEY = "silk_kb_editor_split_ratio"
internal const val KNOWLEDGE_TOPIC_SIDEBAR_DEFAULT_WIDTH = 260.0
internal const val KNOWLEDGE_ENTRY_SIDEBAR_DEFAULT_WIDTH = 240.0
internal const val KNOWLEDGE_EDITOR_SPLIT_DEFAULT_RATIO = 0.5
internal const val KNOWLEDGE_SIDEBAR_MIN_WIDTH = 200.0
internal const val KNOWLEDGE_SIDEBAR_MAX_WIDTH = 420.0
internal const val KNOWLEDGE_EDITOR_SPLIT_MIN_RATIO = 0.25
internal const val KNOWLEDGE_EDITOR_SPLIT_MAX_RATIO = 0.75
internal const val KNOWLEDGE_COPILOT_SIDEBAR_DEFAULT_WIDTH = 380.0
internal const val KNOWLEDGE_COPILOT_SIDEBAR_MIN_WIDTH = 320.0
internal const val KNOWLEDGE_COPILOT_SIDEBAR_MAX_WIDTH = 520.0
private const val KNOWLEDGE_COPILOT_SIDEBAR_WIDTH_KEY = "silk_kb_copilot_sidebar_width"
private const val KNOWLEDGE_COPILOT_DEFAULT_PROMPT = ""

internal fun clampKnowledgeSidebarWidth(width: Double): Double =
    width.coerceIn(KNOWLEDGE_SIDEBAR_MIN_WIDTH, KNOWLEDGE_SIDEBAR_MAX_WIDTH)

internal fun clampKnowledgeCopilotSidebarWidth(width: Double): Double =
    width.coerceIn(KNOWLEDGE_COPILOT_SIDEBAR_MIN_WIDTH, KNOWLEDGE_COPILOT_SIDEBAR_MAX_WIDTH)

internal fun clampKnowledgeEditorSplitRatio(ratio: Double): Double =
    ratio.coerceIn(KNOWLEDGE_EDITOR_SPLIT_MIN_RATIO, KNOWLEDGE_EDITOR_SPLIT_MAX_RATIO)

internal fun parseStoredKnowledgeSidebarWidth(raw: String?, defaultWidth: Double): Double =
    raw?.toDoubleOrNull()?.let(::clampKnowledgeSidebarWidth) ?: defaultWidth

internal fun parseStoredKnowledgeEditorSplitRatio(raw: String?, defaultRatio: Double): Double =
    raw?.toDoubleOrNull()?.let(::clampKnowledgeEditorSplitRatio) ?: defaultRatio

internal fun knowledgeEditorModeSwitchPresentation(availableEditorWidthPx: Double): KnowledgeEditorModeSwitchPresentation {
    return when {
        availableEditorWidthPx < 460.0 -> KnowledgeEditorModeSwitchPresentation.SELECT
        availableEditorWidthPx < 620.0 -> KnowledgeEditorModeSwitchPresentation.COMPACT
        else -> KnowledgeEditorModeSwitchPresentation.FULL
    }
}

private fun persistKnowledgePaneNumber(key: String, value: Double) {
    localStorage.setItem(key, value.toString())
}

private data class KnowledgeHeaderSecondaryAction(
    val label: String,
    val enabled: Boolean = true,
    val background: String = SilkColors.textSecondary,
    val onClick: () -> Unit,
)

private fun knowledgePercent(value: Double): String =
    "${((value * 1000).toInt() / 10.0)}%"

internal data class KnowledgeSpaceOption(
    val id: String,
    val label: String,
    val type: KnowledgeSpaceType,
    val group: Group? = null,
)

internal fun buildKnowledgeSpaceOptions(groups: List<Group>): List<KnowledgeSpaceOption> {
    val teamOptions = groups
        .filterNot { it.name.startsWith("wf_") }
        .sortedBy { it.name.lowercase() }
        .map { group ->
            KnowledgeSpaceOption(
                id = group.id,
                label = group.name,
                type = KnowledgeSpaceType.TEAM,
                group = group,
            )
        }
    return listOf(
        KnowledgeSpaceOption(
            id = PERSONAL_SPACE_ID,
            label = "个人",
            type = KnowledgeSpaceType.PERSONAL,
        )
    ) + teamOptions
}

internal fun filterTopicsForSpace(topics: List<KBTopicItem>, selectedSpaceId: String): List<KBTopicItem> {
    return topics.filter { topic ->
        when (selectedSpaceId) {
            PERSONAL_SPACE_ID -> topic.spaceType == KnowledgeSpaceType.PERSONAL
            else -> topic.spaceType == KnowledgeSpaceType.TEAM && topic.groupId == selectedSpaceId
        }
    }
}

internal fun filterTopicsByQuery(topics: List<KBTopicItem>, query: String): List<KBTopicItem> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return topics
    return topics.filter { topic ->
        topic.name.lowercase().contains(normalizedQuery) ||
            topic.project.lowercase().contains(normalizedQuery)
    }
}

internal fun canWriteKnowledgeTopic(topic: KBTopicItem, userId: String, groups: List<Group>): Boolean {
    if (topic.ownerId == userId) return true
    if (userId in topic.accessPolicy.manageUserIds) return true
    if (topic.accessPolicy.writeLocked) return false
    if (userId in topic.accessPolicy.writeUserIds) return true
    if (topic.spaceType != KnowledgeSpaceType.TEAM || !topic.accessPolicy.teamMembersCanWrite) return false
    return groups.any { it.id == topic.groupId }
}

internal fun canManageKnowledgeTopic(topic: KBTopicItem, userId: String, groups: List<Group>): Boolean {
    if (topic.ownerId == userId) return true
    if (userId in topic.accessPolicy.manageUserIds) return true
    return topic.spaceType == KnowledgeSpaceType.TEAM &&
        groups.any { it.id == topic.groupId && it.hostId == userId }
}

internal fun csvToKnowledgeUserIds(raw: String): List<String> {
    return raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

internal fun knowledgeUserIdsToCsv(userIds: List<String>): String = userIds.joinToString(", ")

internal fun topicSpaceLabel(topic: KBTopicItem, groups: List<Group>): String {
    return when (topic.spaceType) {
        KnowledgeSpaceType.PERSONAL -> "个人"
        KnowledgeSpaceType.TEAM -> groups.find { it.id == topic.groupId }?.name ?: "团队"
    }
}

internal fun topicPermissionLabel(topic: KBTopicItem, userId: String, groups: List<Group>): String {
    return if (canWriteKnowledgeTopic(topic, userId, groups)) "可编辑" else "只读"
}

internal fun knowledgeSourceLabel(sourceType: KBSourceType): String {
    return when (sourceType) {
        KBSourceType.MANUAL -> "手动创建"
        KBSourceType.CHAT -> "聊天沉淀"
        KBSourceType.AI_RESPONSE -> "AI 回答"
        KBSourceType.WORKFLOW -> "工作流沉淀"
        KBSourceType.MEETING -> "会议沉淀"
        KBSourceType.FILE -> "文件导入"
        KBSourceType.URL -> "URL 导入"
    }
}

internal fun knowledgeSourceShortLabel(sourceType: KBSourceType): String {
    return when (sourceType) {
        KBSourceType.MANUAL -> "手动"
        KBSourceType.CHAT -> "聊天"
        KBSourceType.AI_RESPONSE -> "AI"
        KBSourceType.WORKFLOW -> "工作流"
        KBSourceType.MEETING -> "会议"
        KBSourceType.FILE -> "文件"
        KBSourceType.URL -> "URL"
    }
}

internal fun knowledgeMemoryTypeLabel(type: KBMemoryType): String {
    return when (type) {
        KBMemoryType.PROFILE -> "身份画像"
        KBMemoryType.PREFERENCE -> "偏好"
        KBMemoryType.EPISODIC -> "经历"
        KBMemoryType.PROCEDURAL -> "做事方式"
    }
}

internal fun sortKnowledgeMemoryEntries(entries: List<KBEntryItem>): List<KBEntryItem> {
    return entries.sortedWith(
        compareByDescending<KBEntryItem> { it.memory?.capturedAt ?: it.updatedAt }
            .thenByDescending { it.updatedAt }
    )
}

internal fun knowledgeMemoryStatusLabel(preferences: KnowledgeBaseContextPreferences): String {
    return if (preferences.memoryEnabled) "长期记忆已启用" else "长期记忆已关闭"
}

private val opaqueKnowledgeIdRegex = Regex(
    pattern = """^(?:[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}|msg[-_][a-z0-9-]+|wf[-_][a-z0-9-]+|group[-_][a-z0-9-]+)$""",
    option = RegexOption.IGNORE_CASE,
)

internal fun isOpaqueKnowledgeIdentifier(raw: String): Boolean {
    val normalized = raw.trim()
    if (normalized.isBlank()) return true
    return opaqueKnowledgeIdRegex.matches(normalized)
}

private fun knowledgeUserLabel(userId: String, currentUserId: String): String? {
    val normalized = userId.trim()
    if (normalized.isBlank()) return null
    if (normalized == currentUserId) return "我"
    return normalized.takeIf { !isOpaqueKnowledgeIdentifier(it) }
}

private fun knowledgeWorkflowLabel(workflowId: String): String {
    val normalized = workflowId.trim()
    if (normalized.isBlank()) return ""
    return if (isOpaqueKnowledgeIdentifier(normalized)) "已关联工作流" else normalized
}

internal enum class KnowledgeSourceMessageJumpKind {
    CHAT,
    WORKFLOW,
}

internal data class KnowledgeSourceMessageJump(
    val kind: KnowledgeSourceMessageJumpKind,
    val targetId: String,
    val messageId: String,
)

internal fun knowledgeSourceMessageJump(entry: KBEntryItem): KnowledgeSourceMessageJump? {
    val messageId = entry.source.messageIds.firstOrNull()?.trim().orEmpty()
    if (messageId.isBlank()) return null
    entry.source.workflowId?.trim()?.takeIf { it.isNotBlank() }?.let { workflowId ->
        return KnowledgeSourceMessageJump(
            kind = KnowledgeSourceMessageJumpKind.WORKFLOW,
            targetId = workflowId,
            messageId = messageId,
        )
    }
    entry.source.sourceGroupId?.trim()?.takeIf { it.isNotBlank() }?.let { groupId ->
        return KnowledgeSourceMessageJump(
            kind = KnowledgeSourceMessageJumpKind.CHAT,
            targetId = groupId,
            messageId = messageId,
        )
    }
    return null
}

internal data class KnowledgeEntryDragPayload(
    val entryId: String,
    val sourceTopicId: String,
)

internal fun knowledgeRowDomId(prefix: String, rawId: String): String =
    "$prefix-${rawId.hashCode().toUInt().toString(16)}"

internal fun knowledgeEntryDropTargetTopicId(
    dragPayload: KnowledgeEntryDragPayload?,
    topics: List<KBTopicItem>,
    targetTopicId: String,
): String? {
    val payload = dragPayload ?: return null
    val sourceTopic = topics.find { it.id == payload.sourceTopicId } ?: return null
    val targetTopic = topics.find { it.id == targetTopicId } ?: return null
    return targetTopic.id.takeIf { canMoveKnowledgeEntryToTopic(sourceTopic, targetTopic) }
}

internal fun knowledgeSourceDetails(
    entry: KBEntryItem,
    groups: List<Group>,
    currentUserId: String,
): List<Pair<String, String>> {
    val details = mutableListOf<Pair<String, String>>()
    entry.source.sourceGroupId?.takeIf { it.isNotBlank() }?.let { groupId ->
        val groupName = groups.find { it.id == groupId }?.name
        if (!groupName.isNullOrBlank()) {
            details += "来源群组" to groupName
        }
    }
    entry.source.workflowId?.takeIf { it.isNotBlank() }?.let { workflowId ->
        val workflowLabel = knowledgeWorkflowLabel(workflowId)
        if (workflowLabel.isNotBlank()) {
            details += "工作流" to workflowLabel
        }
    }
    if (entry.source.messageIds.isNotEmpty()) {
        details += "来源消息" to "共 ${entry.source.messageIds.size} 条"
    }
    entry.source.confidence?.let { confidence ->
        details += "置信度" to "${(confidence * 100).toInt()}%"
    }
    knowledgeUserLabel(entry.createdBy, currentUserId)?.let { createdBy ->
        details += "创建人" to createdBy
    }
    knowledgeUserLabel(entry.updatedBy, currentUserId)
        ?.takeIf { it != knowledgeUserLabel(entry.createdBy, currentUserId) }
        ?.let { updatedBy ->
            details += "更新人" to updatedBy
        }
    return details
}

internal fun canMoveKnowledgeEntryToTopic(sourceTopic: KBTopicItem, targetTopic: KBTopicItem): Boolean {
    if (sourceTopic.id == targetTopic.id) return false
    if (sourceTopic.spaceType != targetTopic.spaceType) return false
    return when (sourceTopic.spaceType) {
        KnowledgeSpaceType.PERSONAL -> true
        KnowledgeSpaceType.TEAM -> sourceTopic.groupId == targetTopic.groupId
    }
}

internal fun knowledgeMoveTargetTopics(topics: List<KBTopicItem>, sourceTopic: KBTopicItem?): List<KBTopicItem> {
    val topic = sourceTopic ?: return emptyList()
    return topics.filter { canMoveKnowledgeEntryToTopic(topic, it) }
}

private fun knowledgeMergeTargetTopics(topics: List<KBTopicItem>, sourceTopic: KBTopicItem?): List<KBTopicItem> {
    val topic = sourceTopic ?: return emptyList()
    return buildList {
        add(topic)
        addAll(topics.filter { canMoveKnowledgeEntryToTopic(topic, it) })
    }
}

private data class KnowledgeMergeTargetOption(
    val entry: KBEntryItem,
    val topic: KBTopicItem,
)

internal fun filterKnowledgeEntries(entries: List<KBEntryItem>, filter: KnowledgeEntryFilter): List<KBEntryItem> {
    return when (filter) {
        KnowledgeEntryFilter.ALL -> entries
        KnowledgeEntryFilter.CANDIDATE -> entries.filter { it.status == KBEntryStatus.CANDIDATE }
        KnowledgeEntryFilter.PUBLISHED -> entries.filter { it.status == KBEntryStatus.PUBLISHED }
        KnowledgeEntryFilter.ARCHIVED -> entries.filter { it.status == KBEntryStatus.ARCHIVED }
    }
}

internal fun filterKnowledgeEntriesByQuery(entries: List<KBEntryItem>, query: String): List<KBEntryItem> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return entries
    return entries.filter { entry ->
        entry.title.lowercase().contains(normalizedQuery) ||
            entry.content.lowercase().contains(normalizedQuery) ||
            entry.tags.any { it.lowercase().contains(normalizedQuery) } ||
            knowledgeSourceLabel(entry.source.sourceType).lowercase().contains(normalizedQuery)
    }
}

internal fun knowledgeFilterForStatus(status: KBEntryStatus): KnowledgeEntryFilter {
    return when (status) {
        KBEntryStatus.CANDIDATE -> KnowledgeEntryFilter.CANDIDATE
        KBEntryStatus.PUBLISHED -> KnowledgeEntryFilter.PUBLISHED
        KBEntryStatus.ARCHIVED -> KnowledgeEntryFilter.ARCHIVED
        KBEntryStatus.DELETED -> KnowledgeEntryFilter.ALL
    }
}

internal fun knowledgeStatusAction(entry: KBEntryItem): Pair<String, KBEntryStatus>? {
    return when (entry.status) {
        KBEntryStatus.CANDIDATE -> "发布" to KBEntryStatus.PUBLISHED
        KBEntryStatus.PUBLISHED -> "归档" to KBEntryStatus.ARCHIVED
        KBEntryStatus.ARCHIVED -> "重新发布" to KBEntryStatus.PUBLISHED
        KBEntryStatus.DELETED -> null
    }
}

internal fun toggleKnowledgeEntrySelection(selectedEntryIds: Set<String>, entryId: String): Set<String> {
    return if (entryId in selectedEntryIds) {
        selectedEntryIds - entryId
    } else {
        selectedEntryIds + entryId
    }
}

internal fun mergeKnowledgeEntryContent(
    targetContent: String,
    candidateTitle: String,
    candidateContent: String,
): String {
    val trimmedTarget = targetContent.trim()
    val trimmedCandidate = candidateContent.trim()
    if (trimmedTarget.isBlank()) return trimmedCandidate
    if (trimmedCandidate.isBlank()) return trimmedTarget
    return buildString {
        append(trimmedTarget)
        append("\n\n---\n\n")
        append("## 合并自：")
        append(candidateTitle.ifBlank { "候选条目" })
        append("\n\n")
        append(trimmedCandidate)
    }
}

internal fun mergeKnowledgeEntryTags(targetTags: List<String>, candidateTags: List<String>): List<String> {
    return (targetTags + candidateTags)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

internal fun mergeKnowledgeEntriesContent(
    targetContent: String,
    candidateEntries: List<KBEntryItem>,
): String {
    return candidateEntries.fold(targetContent) { merged, candidate ->
        mergeKnowledgeEntryContent(
            targetContent = merged,
            candidateTitle = candidate.title,
            candidateContent = candidate.content,
        )
    }
}

internal fun mergeKnowledgeEntriesTags(
    targetTags: List<String>,
    candidateEntries: List<KBEntryItem>,
): List<String> {
    return mergeKnowledgeEntryTags(
        targetTags = targetTags,
        candidateTags = candidateEntries.flatMap { it.tags },
    )
}

@Composable
private fun TopicSidebar(
    widthPx: Double,
    spaceOptions: List<KnowledgeSpaceOption>,
    selectedSpaceId: String,
    searchQuery: String,
    topics: List<KBTopicItem>,
    isLoading: Boolean,
    selectedTopic: KBTopicItem?,
    userId: String,
    groups: List<Group>,
    isManageMode: Boolean,
    memoryPreferences: KnowledgeBaseContextPreferences,
    activeDragPayload: KnowledgeEntryDragPayload?,
    activeDropTopicId: String?,
    showGroupFilesView: Boolean = false,
    isTeamSpace: Boolean = false,
    onToggleManageMode: () -> Unit,
    onManageMemory: () -> Unit,
    onCreateTopic: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSpaceSelect: (KnowledgeSpaceOption) -> Unit,
    onTopicSelect: (KBTopicItem) -> Unit,
    onManageTopic: (KBTopicItem) -> Unit,
    onDeleteTopic: (KBTopicItem) -> Unit,
    onEntryDragHoverTopicChange: (String?) -> Unit,
    onEntryDropToTopic: (String) -> Unit,
    onCollapse: () -> Unit = {},
    onGroupFilesSelect: () -> Unit = {},
) {
    val hasManageableTopic = topics.any { canManageKnowledgeTopic(it, userId, groups) }
    Div({
        style {
            width(widthPx.px)
            minWidth(KNOWLEDGE_SIDEBAR_MIN_WIDTH.px)
            property("flex-shrink", "0")
            property("border-right", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            backgroundColor(Color(SilkColors.surface))
        }
    }) {
        KnowledgeColumnHeader(
            title = "知识空间",
            actionLabel = "+",
            onCollapse = onCollapse,
            onAction = onCreateTopic,
            secondaryAction = if (hasManageableTopic) {
                KnowledgeHeaderSecondaryAction(
                    label = if (isManageMode) "完成" else "管理",
                    background = if (isManageMode) SilkColors.primaryDark else SilkColors.textSecondary,
                    onClick = onToggleManageMode,
                )
            } else {
                null
            },
        )
        KnowledgeSpaceTabs(
            options = spaceOptions,
            selectedSpaceId = selectedSpaceId,
            onSpaceSelect = onSpaceSelect,
        )
        KnowledgeMemoryQuickPanel(
            preferences = memoryPreferences,
            onManageMemory = onManageMemory,
        )
        KnowledgeSearchField(
            value = searchQuery,
            placeholder = "搜索主题 / 项目",
            onValueChange = onSearchQueryChange,
        )
        TopicSidebarContent(
            topics = topics,
            isLoading = isLoading,
            selectedTopic = selectedTopic,
            searchQuery = searchQuery,
            userId = userId,
            groups = groups,
            isManageMode = isManageMode,
            activeDragPayload = activeDragPayload,
            activeDropTopicId = activeDropTopicId,
            onTopicSelect = onTopicSelect,
            onManageTopic = onManageTopic,
            onDeleteTopic = onDeleteTopic,
            onEntryDragHoverTopicChange = onEntryDragHoverTopicChange,
            onEntryDropToTopic = onEntryDropToTopic,
        )
        // ── 群文件特殊主题 ──
        if (isTeamSpace) {
            GroupFilesTopicRow(
                isSelected = showGroupFilesView,
                onClick = {
                    onGroupFilesSelect()
                },
            )
        }
    }
}

@Composable
private fun KnowledgeMemoryQuickPanel(
    preferences: KnowledgeBaseContextPreferences,
    onManageMemory: () -> Unit,
) {
    Div({
        style {
            padding(12.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "8px")
            backgroundColor(Color("#FCF7EE"))
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                property("gap", "8px")
            }
        }) {
            Div({
                style {
                    fontSize(13.px)
                    fontWeight("600")
                    color(Color(SilkColors.textPrimary))
                }
            }) { Text("KB Memory") }
            KnowledgeInlineActionButton(
                label = "管理",
                background = SilkColors.primaryDark,
                onClick = onManageMemory,
            )
        }
        Div({
            style {
                fontSize(12.px)
                color(Color(SilkColors.textSecondary))
                property("line-height", "1.5")
            }
        }) {
            Text(knowledgeMemoryStatusLabel(preferences))
        }
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "6px")
                property("flex-wrap", "wrap")
            }
        }) {
            KnowledgeBadge(
                if (preferences.memoryEnabled) "注入开启" else "注入关闭",
                if (preferences.memoryEnabled) SilkColors.success else SilkColors.warning,
            )
            if (preferences.autoCaptureEnabled) {
                KnowledgeBadge("自动记忆", SilkColors.info)
            }
            if (preferences.ephemeralSessionEnabled) {
                KnowledgeBadge("会话记忆", SilkColors.primary)
            }
        }
    }
}

@Composable
private fun TopicSidebarContent(
    topics: List<KBTopicItem>,
    isLoading: Boolean,
    selectedTopic: KBTopicItem?,
    searchQuery: String,
    userId: String,
    groups: List<Group>,
    isManageMode: Boolean,
    onTopicSelect: (KBTopicItem) -> Unit,
    onManageTopic: (KBTopicItem) -> Unit,
    onDeleteTopic: (KBTopicItem) -> Unit,
    activeDragPayload: KnowledgeEntryDragPayload?,
    activeDropTopicId: String?,
    onEntryDragHoverTopicChange: (String?) -> Unit,
    onEntryDropToTopic: (String) -> Unit,
) {
    Div({ style { property("flex", "1"); property("overflow-y", "auto") } }) {
        if (isLoading) {
            KnowledgeCenteredMessage("加载中...", SilkColors.textSecondary, 16.px)
        } else if (topics.isEmpty()) {
            KnowledgeCenteredMessage(
                if (searchQuery.isBlank()) "当前空间还没有主题" else "没有匹配的主题",
                SilkColors.textLight,
                20.px,
            )
        } else {
            topics.forEach { topic ->
                val canAcceptDrop = knowledgeEntryDropTargetTopicId(
                    dragPayload = activeDragPayload,
                    topics = topics,
                    targetTopicId = topic.id,
                ) != null
                TopicRow(
                    topic = topic,
                    isSelected = selectedTopic?.id == topic.id,
                    spaceLabel = topicSpaceLabel(topic, groups),
                    permissionLabel = topicPermissionLabel(topic, userId, groups),
                    isDropTarget = activeDropTopicId == topic.id,
                    canAcceptDrop = canAcceptDrop,
                    showManageActions = isManageMode && canManageKnowledgeTopic(topic, userId, groups),
                    onClick = { onTopicSelect(topic) },
                    onManageTopic = { onManageTopic(topic) },
                    onDeleteTopic = { onDeleteTopic(topic) },
                )
                KnowledgeTopicDropTargetEffect(
                    elementId = knowledgeRowDomId("kb-topic-row", topic.id),
                    enabled = canAcceptDrop,
                    onHoverChange = { isHovering ->
                        onEntryDragHoverTopicChange(topic.id.takeIf { isHovering })
                    },
                    onDrop = { onEntryDropToTopic(topic.id) },
                )
            }
        }
    }
}

@Composable
private fun KnowledgeSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Div({
        style {
            padding(10.px, 12.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
        }
    }) {
        Input(InputType.Text) {
            value(value)
            attr("placeholder", placeholder)
            onInput { onValueChange(it.value) }
            style {
                width(100.percent)
                padding(10.px, 12.px)
                fontSize(13.px)
                borderRadius(10.px)
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                backgroundColor(Color("#FFFDF8"))
                color(Color(SilkColors.textPrimary))
                property("box-sizing", "border-box")
                property("outline", "none")
            }
        }
    }
}

@Composable
private fun KnowledgeSpaceTabs(
    options: List<KnowledgeSpaceOption>,
    selectedSpaceId: String,
    onSpaceSelect: (KnowledgeSpaceOption) -> Unit,
) {
    Div({
        style {
            padding(10.px, 12.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "8px")
        }
    }) {
        options.forEach { option ->
            Button({
                style {
                    backgroundColor(
                        Color(
                            if (selectedSpaceId == option.id) SilkColors.primary else SilkColors.surfaceElevated
                        )
                    )
                    color(Color(if (selectedSpaceId == option.id) "#FFFFFF" else SilkColors.textSecondary))
                    border(0.px)
                    borderRadius(8.px)
                    padding(8.px, 12.px)
                    property("cursor", "pointer")
                    property("text-align", "left")
                    fontSize(13.px)
                }
                onClick { onSpaceSelect(option) }
            }) { Text(option.label) }
        }
    }
}

@Composable
private fun TopicRow(
    topic: KBTopicItem,
    isSelected: Boolean,
    spaceLabel: String,
    permissionLabel: String,
    isDropTarget: Boolean,
    canAcceptDrop: Boolean,
    showManageActions: Boolean,
    onClick: () -> Unit,
    onManageTopic: () -> Unit,
    onDeleteTopic: () -> Unit,
) {
    val rowDomId = remember(topic.id) { knowledgeRowDomId("kb-topic-row", topic.id) }
    Div({
        attr("id", rowDomId)
        style {
            padding(10.px, 14.px)
            property("cursor", "pointer")
            when {
                isDropTarget -> backgroundColor(Color("rgba(82,164,117,0.18)"))
                isSelected -> backgroundColor(Color("rgba(201,168,108,0.15)"))
            }
            property("border-bottom", "1px solid ${SilkColors.border}")
            if (canAcceptDrop) {
                property("transition", "background-color 120ms ease, box-shadow 120ms ease")
            }
            if (isDropTarget) {
                property("box-shadow", "inset 0 0 0 2px rgba(82,164,117,0.45)")
            }
        }
        onClick { onClick() }
    }) {
        Div({
            style {
                fontSize(14.px)
                color(Color(SilkColors.textPrimary))
                fontWeight(if (isSelected) "600" else "400")
            }
        }) { Text(topic.name) }
        if (showManageActions) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "6px")
                    marginTop(8.px)
                    property("flex-wrap", "wrap")
                }
            }) {
                KnowledgeInlineActionButton(
                    label = "权限",
                    background = SilkColors.textSecondary,
                    onClick = onManageTopic,
                )
                KnowledgeInlineActionButton(
                    label = "删除",
                    background = "#8F3D3A",
                    onClick = onDeleteTopic,
                )
            }
        }
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "6px")
                marginTop(6.px)
                property("flex-wrap", "wrap")
            }
        }) {
            KnowledgeBadge(spaceLabel, SilkColors.info)
            KnowledgeBadge(permissionLabel, if (permissionLabel == "可编辑") SilkColors.success else SilkColors.warning)
            if (canAcceptDrop) {
                KnowledgeBadge("可放置", SilkColors.success)
            }
        }
        if (topic.project.isNotBlank()) {
            Div({
                style {
                    fontSize(11.px)
                    color(Color(SilkColors.textLight))
                    marginTop(6.px)
                }
            }) { Text(topic.project) }
        }
    }
}

@Composable
private fun EntrySidebar(
    widthPx: Double,
    selectedTopic: KBTopicItem?,
    searchQuery: String,
    entries: List<KBEntryItem>,
    selectedEntry: KBEntryItem?,
    selectedFilter: KnowledgeEntryFilter,
    canCreateEntry: Boolean,
    canDragEntries: Boolean,
    selectedCandidateEntryIds: Set<String>,
    canBatchMergeCandidates: Boolean,
    onCollapse: () -> Unit = {},
    onFilterChange: (KnowledgeEntryFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCreateEntry: () -> Unit,
    onMeetingCapture: () -> Unit,
    onToggleSelectAllCandidates: () -> Unit,
    onBatchMergeCandidates: () -> Unit,
    onBatchPublishCandidates: () -> Unit,
    onBatchArchiveCandidates: () -> Unit,
    onToggleCandidateSelection: (String) -> Unit,
    onEntrySelect: (KBEntryItem) -> Unit,
    onEntryDragStart: (KBEntryItem) -> Unit,
    onEntryDragEnd: () -> Unit,
) {
    Div({
        style {
            width(widthPx.px)
            minWidth(KNOWLEDGE_SIDEBAR_MIN_WIDTH.px)
            property("flex-shrink", "0")
            property("border-right", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            backgroundColor(Color(SilkColors.surfaceElevated))
        }
    }) {
        KnowledgeColumnHeader(
            title = selectedTopic?.name ?: "条目",
            actionLabel = if (selectedTopic != null) "+" else null,
            actionEnabled = canCreateEntry,
            onCollapse = onCollapse,
            onAction = onCreateEntry,
        )

        EntryFilterTabs(
            selectedFilter = selectedFilter,
            onFilterChange = onFilterChange,
            showMeetingCaptureAction = selectedTopic != null,
            onMeetingCapture = onMeetingCapture,
            selectedCandidateCount = selectedCandidateEntryIds.size,
            showCandidateInboxActions = selectedTopic != null && canCreateEntry && entries.isNotEmpty() && selectedFilter == KnowledgeEntryFilter.CANDIDATE,
            allCandidatesSelected = entries.isNotEmpty() && entries.all { it.id in selectedCandidateEntryIds },
            onToggleSelectAllCandidates = onToggleSelectAllCandidates,
            canBatchMergeCandidates = canBatchMergeCandidates,
            onBatchMergeCandidates = onBatchMergeCandidates,
            onBatchPublishCandidates = onBatchPublishCandidates,
            onBatchArchiveCandidates = onBatchArchiveCandidates,
        )
        KnowledgeSearchField(
            value = searchQuery,
            placeholder = "搜索标题 / 标签 / 内容",
            onValueChange = onSearchQueryChange,
        )
        EntrySidebarContent(
            selectedTopic = selectedTopic,
            entries = entries,
            selectedEntry = selectedEntry,
            searchQuery = searchQuery,
            selectedCandidateEntryIds = selectedCandidateEntryIds,
            showCandidateSelection = canCreateEntry && selectedFilter == KnowledgeEntryFilter.CANDIDATE,
            canDragEntries = canDragEntries,
            onToggleCandidateSelection = onToggleCandidateSelection,
            onEntrySelect = onEntrySelect,
            onEntryDragStart = onEntryDragStart,
            onEntryDragEnd = onEntryDragEnd,
        )
    }
}

@Composable
private fun GroupFilesTopicRow(
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Div({
        style {
            padding(10.px, 14.px)
            property("cursor", "pointer")
            backgroundColor(Color(if (isSelected) "rgba(201,168,108,0.15)" else "transparent"))
            property("border-bottom", "1px solid ${SilkColors.border}")
            property("border-left", if (isSelected) "3px solid ${SilkColors.primary}" else "3px solid transparent")
        }
        onClick { onClick() }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "8px")
                alignItems(AlignItems.Center)
            }
        }) {
            Span({ style { fontSize(16.px) } }) { Text("📁") }
            Span({
                style {
                    fontSize(14.px)
                    color(Color(SilkColors.textPrimary))
                    fontWeight(if (isSelected) "600" else "400")
                }
            }) { Text("群文件") }
        }
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "6px")
                marginTop(6.px)
                property("flex-wrap", "wrap")
            }
        }) {
            KnowledgeBadge("团队", SilkColors.info)
        }
    }
}

@Composable
private fun GroupFilesContent(
    groupFilesResponse: ApiClient.GroupAssetsResponse?,
    isLoading: Boolean,
    onCreateEntryFromFile: ((ApiClient.GroupAssetFile) -> Unit)?,
    onPreviewFile: ((ApiClient.GroupAssetFile) -> Unit)? = null,
    widthPx: Double = 240.0,
) {
    Div({
        style {
            width(widthPx.px)
            minWidth(KNOWLEDGE_SIDEBAR_MIN_WIDTH.px)
            property("flex-shrink", "0")
            property("border-right", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            backgroundColor(Color(SilkColors.surfaceElevated))
        }
    }) {
        KnowledgeColumnHeader(
            title = "群文件",
            actionLabel = null,
            onAction = {},
        )
        Div({
            style {
                property("flex", "1")
                property("overflow-y", "auto")
            }
        }) {
        if (isLoading) {
            KnowledgeCenteredMessage("加载群文件中...", SilkColors.textSecondary, 16.px)
        } else if (groupFilesResponse == null) {
            KnowledgeCenteredMessage("未找到群文件数据", SilkColors.textLight, 20.px)
        } else {
            // Files section
            Div({
                style {
                    padding(8.px, 12.px)
                    fontSize(12.px)
                    fontWeight("600")
                    color(Color(SilkColors.textSecondary))
                    backgroundColor(Color("#F0F4FF"))
                    property("border-bottom", "1px solid ${SilkColors.border}")
                }
            }) { Text("群文件 (${groupFilesResponse.files.size})") }

            if (groupFilesResponse.files.isEmpty()) {
                KnowledgeCenteredMessage("该群还没有上传文件", SilkColors.textLight, 20.px)
            } else {
                groupFilesResponse.files.forEach { file ->
                    GroupAssetFileRow(
                        file = file,
                        onCreateEntry = onCreateEntryFromFile?.let { { it(file) } },
                        onPreview = onPreviewFile?.let { { it(file) } },
                    )
                }
            }
        }
    }
}  // closes big else
}

@Composable
internal fun GroupAssetEntryRow(
    entry: KBEntryItem,
    onPreviewFile: ((KBFileRef) -> Unit)? = null,
) {
    Div({
        style {
            padding(10.px, 14.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            fontSize(13.px)
        }
    }) {
        Div({
            style {
                fontWeight("600")
                color(Color(SilkColors.textPrimary))
            }
        }) { Text(entry.title) }
        if (entry.source.sourceType == KBSourceType.FILE && entry.source.fileRef != null) {
            val fileRef = entry.source.fileRef!!
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "4px")
                    marginTop(4.px)
                    fontSize(11.px)
                    color(Color(SilkColors.info))
                    property("align-items", "center")
                }
            }) {
                Span({ }) { Text("📎 ${fileRef.fileName}") }
                // Preview button for supported media types
                if (onPreviewFile != null && isPreviewableMimeType(fileRef.mimeType)) {
                    Span({
                        style {
                            property("cursor", "pointer")
                            property("text-decoration", "underline")
                            marginLeft(4.px)
                            color(Color(SilkColors.success))
                            fontWeight("600")
                        }
                        onClick { onPreviewFile(fileRef) }
                    }) { Text("预览") }
                }
                Span({
                    style {
                        property("cursor", "pointer")
                        property("text-decoration", "underline")
                        marginLeft(4.px)
                    }
                    onClick {
                        window.open(fileRef.downloadUrl, "_blank")
                    }
                }) { Text("查看文件") }
            }
        }
        Div({
            style {
                fontSize(11.px)
                color(Color(SilkColors.textLight))
                marginTop(4.px)
            }
        }) {
            Text(knowledgeSourceShortLabel(entry.source.sourceType))
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun GroupAssetFileRow(
    file: ApiClient.GroupAssetFile,
    onCreateEntry: (() -> Unit)?,
    onPreview: (() -> Unit)? = null,
) {
    val fileIcon = when {
        file.mimeType.startsWith("image/") -> "🖼️"
        file.mimeType.startsWith("video/") -> "🎬"
        file.mimeType.startsWith("audio/") -> "🎵"
        file.mimeType.contains("pdf") -> "📄"
        else -> "📁"
    }
    Div({
        style {
            padding(10.px, 14.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "6px")
                    alignItems(AlignItems.Center)
                    property("flex", "1")
                    property("min-width", "0")
                }
            }) {
                Span({ style { fontSize(14.px) } }) { Text(fileIcon) }
                Span({
                    style {
                        fontSize(13.px)
                        color(Color(SilkColors.textPrimary))
                        property("overflow", "hidden")
                        property("text-overflow", "ellipsis")
                        property("white-space", "nowrap")
                    }
                }) { Text(file.fileName) }
            }
        }
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "6px")
                marginTop(6.px)
                property("flex-wrap", "wrap")
                property("align-items", "center")
            }
        }) {
            // Source type badge
            val sourceLabel = when (file.sourceType) {
                "kb_entry_file" -> "条目附件"
                "url_extracted" -> "网页提取"
                else -> "上传"
            }
            val sourceColor = when (file.sourceType) {
                "kb_entry_file" -> SilkColors.primary
                "url_extracted" -> "#8B5CF6"
                else -> SilkColors.textSecondary
            }
            KnowledgeBadge(sourceLabel, sourceColor)

            Span({
                style {
                    fontSize(11.px)
                    color(Color(SilkColors.textLight))
                }
            }) { Text(formatKnowledgeFileSize(file.fileSize)) }
            if (file.hasLinkedEntry) {
                KnowledgeBadge("已关联", SilkColors.success)
            }
            // Preview action for supported media types
            if (onPreview != null && isPreviewableMimeType(file.mimeType)) {
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.success))
                        property("cursor", "pointer")
                        property("text-decoration", "underline")
                        fontWeight("600")
                    }
                    onClick { onPreview() }
                }) { Text("预览") }
            }
            if (file.downloadUrl.isNotBlank()) {
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.info))
                        property("cursor", "pointer")
                        property("text-decoration", "underline")
                    }
                    onClick { window.open(file.downloadUrl, "_blank") }
                }) { Text("下载") }
            }
            if (onCreateEntry != null) {
                KnowledgeInlineActionButton(
                    label = "创建 KB 文档",
                    background = SilkColors.primary,
                    onClick = onCreateEntry,
                )
            }
        }
    }
}

internal fun formatKnowledgeFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> {
            val mb = bytes.toDouble() / (1024 * 1024)
            "${(mb * 10).toLong() / 10.0} MB"
        }
        else -> {
            val gb = bytes.toDouble() / (1024 * 1024 * 1024)
            "${(gb * 100).toLong() / 100.0} GB"
        }
    }
}

@Composable
private fun KnowledgeHorizontalResizeHandle(
    storageHint: String,
    onDragDelta: (Double) -> Unit,
) {
    val handleId = remember { "kb-resize-$storageHint-${Random.nextInt(1_000_000)}" }
    val latestOnDragDelta by rememberUpdatedState(onDragDelta)

    DisposableEffect(handleId) {
        val element = document.getElementById(handleId)
        if (element == null) {
            onDispose { }
        } else {
            var lastClientX: Double? = null
            lateinit var mouseMoveListener: (Event) -> Unit
            lateinit var mouseUpListener: (Event) -> Unit

            mouseMoveListener = { event ->
                val mouseEvent = event as? MouseEvent
                if (mouseEvent != null) {
                    val previousX = lastClientX ?: mouseEvent.clientX.toDouble()
                    val currentX = mouseEvent.clientX.toDouble()
                    latestOnDragDelta(currentX - previousX)
                    lastClientX = currentX
                    mouseEvent.preventDefault()
                }
            }
            mouseUpListener = {
                lastClientX = null
                document.body?.style?.removeProperty("cursor")
                document.body?.style?.removeProperty("user-select")
                document.removeEventListener("mousemove", mouseMoveListener)
                document.removeEventListener("mouseup", mouseUpListener)
            }
            val mouseDownListener: (Event) -> Unit = { event ->
                val mouseEvent = event as? MouseEvent
                if (mouseEvent != null) {
                    lastClientX = mouseEvent.clientX.toDouble()
                    document.body?.style?.setProperty("cursor", "col-resize")
                    document.body?.style?.setProperty("user-select", "none")
                    document.addEventListener("mousemove", mouseMoveListener)
                    document.addEventListener("mouseup", mouseUpListener)
                    mouseEvent.preventDefault()
                }
            }

            element.addEventListener("mousedown", mouseDownListener)
            onDispose {
                lastClientX = null
                document.body?.style?.removeProperty("cursor")
                document.body?.style?.removeProperty("user-select")
                document.removeEventListener("mousemove", mouseMoveListener)
                document.removeEventListener("mouseup", mouseUpListener)
                element.removeEventListener("mousedown", mouseDownListener)
            }
        }
    }

    Div({
        attr("id", handleId)
        attr("role", "separator")
        style {
            width(10.px)
            minWidth(10.px)
            property("flex-shrink", "0")
            property("cursor", "col-resize")
            backgroundColor(Color("rgba(201,168,108,0.08)"))
            property("border-left", "1px solid ${SilkColors.border}")
            property("border-right", "1px solid ${SilkColors.border}")
            property("transition", "background-color 120ms ease")
        }
    })
}

@Composable
@NoLiveLiterals
private fun KnowledgeEntryDragSourceEffect(
    elementId: String,
    enabled: Boolean,
    entryId: String,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
) {
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)

    DisposableEffect(elementId, enabled, entryId) {
        val element = document.getElementById(elementId)
        if (!enabled || element == null) {
            onDispose { }
        } else {
            val dragStartListener: (Event) -> Unit = { event ->
                val transfer = event.asDynamic().dataTransfer
                if (transfer != null) {
                    transfer.effectAllowed = "move"
                    transfer.setData("text/plain", entryId)
                }
                latestOnDragStart()
            }
            val dragEndListener: (Event) -> Unit = {
                latestOnDragEnd()
            }
            element.addEventListener("dragstart", dragStartListener)
            element.addEventListener("dragend", dragEndListener)
            onDispose {
                element.removeEventListener("dragstart", dragStartListener)
                element.removeEventListener("dragend", dragEndListener)
            }
        }
    }
}

@Composable
@NoLiveLiterals
private fun KnowledgeTopicDropTargetEffect(
    elementId: String,
    enabled: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onDrop: () -> Unit,
) {
    val latestOnHoverChange by rememberUpdatedState(onHoverChange)
    val latestOnDrop by rememberUpdatedState(onDrop)

    DisposableEffect(elementId, enabled) {
        val element = document.getElementById(elementId)
        if (!enabled || element == null) {
            onDispose { }
        } else {
            val dragEnterListener: (Event) -> Unit = { event ->
                event.preventDefault()
                latestOnHoverChange(true)
            }
            val dragOverListener: (Event) -> Unit = { event ->
                event.preventDefault()
                val transfer = event.asDynamic().dataTransfer
                if (transfer != null) {
                    transfer.dropEffect = "move"
                }
                latestOnHoverChange(true)
            }
            val dragLeaveListener: (Event) -> Unit = {
                latestOnHoverChange(false)
            }
            val dropListener: (Event) -> Unit = { event ->
                event.preventDefault()
                latestOnHoverChange(false)
                latestOnDrop()
            }
            element.addEventListener("dragenter", dragEnterListener)
            element.addEventListener("dragover", dragOverListener)
            element.addEventListener("dragleave", dragLeaveListener)
            element.addEventListener("drop", dropListener)
            onDispose {
                element.removeEventListener("dragenter", dragEnterListener)
                element.removeEventListener("dragover", dragOverListener)
                element.removeEventListener("dragleave", dragLeaveListener)
                element.removeEventListener("drop", dropListener)
            }
        }
    }
}

@Composable
private fun EntryFilterTabs(
    selectedFilter: KnowledgeEntryFilter,
    onFilterChange: (KnowledgeEntryFilter) -> Unit,
    showMeetingCaptureAction: Boolean,
    onMeetingCapture: () -> Unit,
    selectedCandidateCount: Int,
    showCandidateInboxActions: Boolean,
    allCandidatesSelected: Boolean,
    onToggleSelectAllCandidates: () -> Unit,
    canBatchMergeCandidates: Boolean,
    onBatchMergeCandidates: () -> Unit,
    onBatchPublishCandidates: () -> Unit,
    onBatchArchiveCandidates: () -> Unit,
) {
    Div({
        style {
            padding(10.px, 12.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            property("gap", "8px")
            flexDirection(FlexDirection.Column)
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                property("gap", "8px")
                property("flex-wrap", "wrap")
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "6px")
                    property("flex-wrap", "wrap")
                }
            }) {
                KnowledgeEntryFilter.entries.forEach { filter ->
                    KnowledgeToggleButton(
                        label = filter.label,
                        selected = selectedFilter == filter,
                        onClick = { onFilterChange(filter) },
                    )
                }
            }
            if (showMeetingCaptureAction) {
                KnowledgeToolbarButton(
                    label = "会议入库",
                    background = SilkColors.info,
                    onClick = onMeetingCapture,
                )
            }
        }
        if (showCandidateInboxActions) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "8px")
                }
            }) {
                Div({
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text("候选收件箱：已选 $selectedCandidateCount 条")
                }
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("gap", "6px")
                        property("flex-wrap", "wrap")
                    }
                }) {
                    KnowledgeToolbarButton(
                        label = if (allCandidatesSelected) "取消全选" else "全选",
                        background = SilkColors.textSecondary,
                        onClick = onToggleSelectAllCandidates,
                    )
                    KnowledgeToolbarButton(
                        label = "批量并入",
                        background = SilkColors.info,
                        enabled = canBatchMergeCandidates,
                        onClick = onBatchMergeCandidates,
                    )
                    KnowledgeToolbarButton(
                        label = "批量发布",
                        background = SilkColors.success,
                        enabled = selectedCandidateCount > 0,
                        onClick = onBatchPublishCandidates,
                    )
                    KnowledgeToolbarButton(
                        label = "批量归档",
                        background = SilkColors.warning,
                        enabled = selectedCandidateCount > 0,
                        onClick = onBatchArchiveCandidates,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntrySidebarContent(
    selectedTopic: KBTopicItem?,
    entries: List<KBEntryItem>,
    selectedEntry: KBEntryItem?,
    searchQuery: String,
    selectedCandidateEntryIds: Set<String>,
    showCandidateSelection: Boolean,
    canDragEntries: Boolean,
    onToggleCandidateSelection: (String) -> Unit,
    onEntrySelect: (KBEntryItem) -> Unit,
    onEntryDragStart: (KBEntryItem) -> Unit,
    onEntryDragEnd: () -> Unit,
) {
    Div({ style { property("flex", "1"); property("overflow-y", "auto") } }) {
        when {
            selectedTopic == null -> KnowledgeListEmptyState("请先选择主题")
            entries.isEmpty() -> KnowledgeListEmptyState(if (searchQuery.isBlank()) "暂无条目" else "没有匹配的条目")
            else -> entries.forEach { entry ->
                EntryRow(
                    entry = entry,
                    isSelected = selectedEntry?.id == entry.id,
                    isCandidateSelected = entry.id in selectedCandidateEntryIds,
                    showCandidateSelection = showCandidateSelection,
                    canDrag = canDragEntries,
                    onToggleCandidateSelection = { onToggleCandidateSelection(entry.id) },
                    onClick = { onEntrySelect(entry) },
                    onDragStart = { onEntryDragStart(entry) },
                    onDragEnd = onEntryDragEnd,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: KBEntryItem,
    isSelected: Boolean,
    isCandidateSelected: Boolean,
    showCandidateSelection: Boolean,
    canDrag: Boolean,
    onToggleCandidateSelection: () -> Unit,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
) {
    val rowDomId = remember(entry.id) { knowledgeRowDomId("kb-entry-row", entry.id) }
    Div({
        attr("id", rowDomId)
        if (canDrag) {
            attr("draggable", "true")
            attr("title", "拖到左侧其他主题即可移动")
        }
        style {
            padding(10.px, 14.px)
            property("cursor", "pointer")
            if (isSelected) backgroundColor(Color("rgba(201,168,108,0.1)"))
            property("border-bottom", "1px solid ${SilkColors.border}")
        }
        onClick { onClick() }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "8px")
            }
        }) {
            if (showCandidateSelection) {
                Button({
                    style {
                        backgroundColor(Color(if (isCandidateSelected) SilkColors.primaryDark else "#FFFFFF"))
                        color(Color(if (isCandidateSelected) "#FFFFFF" else SilkColors.textSecondary))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(999.px)
                        padding(2.px, 8.px)
                        fontSize(11.px)
                        property("cursor", "pointer")
                        property("flex-shrink", "0")
                    }
                    onClick {
                        it.stopPropagation()
                        onToggleCandidateSelection()
                    }
                }) {
                    Text(if (isCandidateSelected) "已选" else "选择")
                }
            }
            Div({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textPrimary))
                    fontWeight(if (isSelected) "600" else "400")
                }
            }) { Text(entry.title) }
        }
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "6px")
                marginTop(6.px)
                property("flex-wrap", "wrap")
            }
        }) {
            KnowledgeBadge(entry.status.name.lowercase(), SilkColors.primaryDark)
            KnowledgeBadge(knowledgeSourceShortLabel(entry.source.sourceType), SilkColors.primary)
        }
    }
    KnowledgeEntryDragSourceEffect(
        elementId = rowDomId,
        enabled = canDrag,
        entryId = entry.id,
        onDragStart = onDragStart,
        onDragEnd = onDragEnd,
    )
}

@Composable
private fun KnowledgeEditorPane(
    selectedTopic: KBTopicItem?,
    selectedEntry: KBEntryItem?,
    editorTitle: String,
    editorContent: String,
    isSaving: Boolean,
    saveMessage: String,
    editorMode: KnowledgeEditorMode,
    editorSplitRatio: Double,
    availableEditorWidthPx: Double,
    canEdit: Boolean,
    savedTitle: String,
    spaceLabel: String?,
    permissionLabel: String?,
    currentUserId: String,
    groups: List<Group>,
    onOpenSourceGroup: ((Group) -> Unit)?,
    onOpenSourceWorkflow: ((String) -> Unit)?,
    onOpenSourceMessage: ((KnowledgeSourceMessageJump) -> Unit)?,
    onTitleChange: (String) -> Unit,
    onResetTitle: () -> Unit,
    onContentChange: (String) -> Unit,
    onEditorModeChange: (KnowledgeEditorMode) -> Unit,
    onEditorSplitRatioChange: (Double) -> Unit,
    onMoveEntry: (() -> Unit)?,
    onDeleteEntry: (() -> Unit)?,
    onOpenCopilot: (() -> Unit)?,
    onSave: () -> Unit,
    onStatusAction: (() -> Unit)?,
    statusActionLabel: String?,
    onMergeCandidate: (() -> Unit)?,
    onExport: () -> Unit,
    onCopyReference: () -> Unit,
) {
    Div({
        style {
            property("flex", "1")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            minWidth(320.px)
            property("min-height", "0")
            property("overflow", "hidden")
        }
    }) {
        if (selectedEntry == null) {
            EmptyEditorState(
                selectedTopic = selectedTopic,
                canEdit = canEdit,
                onOpenCopilot = onOpenCopilot,
            )
        } else {
            KnowledgeEditorToolbar(
                title = editorTitle,
                savedTitle = savedTitle,
                availableEditorWidthPx = availableEditorWidthPx,
                isSaving = isSaving,
                saveMessage = saveMessage,
                editorMode = editorMode,
                canEdit = canEdit,
                onTitleChange = onTitleChange,
                onResetTitle = onResetTitle,
                onEditorModeChange = onEditorModeChange,
                onMoveEntry = onMoveEntry,
                onDeleteEntry = onDeleteEntry,
                onOpenCopilot = onOpenCopilot,
                onSave = onSave,
                onStatusAction = onStatusAction,
                statusActionLabel = statusActionLabel,
                onMergeCandidate = onMergeCandidate,
                onExport = onExport,
                onCopyReference = onCopyReference,
            )
            KnowledgeEntryMetaBar(
                topic = selectedTopic,
                entry = selectedEntry,
                spaceLabel = spaceLabel,
                permissionLabel = permissionLabel,
                currentUserId = currentUserId,
                groups = groups,
                onOpenSourceGroup = onOpenSourceGroup,
                onOpenSourceWorkflow = onOpenSourceWorkflow,
                onOpenSourceMessage = onOpenSourceMessage,
            )
            // Inline file preview for entries with fileRef
            if (selectedEntry.source.sourceType == KBSourceType.FILE &&
                selectedEntry.source.fileRef != null
            ) {
                val fileRef = selectedEntry.source.fileRef!!
                Div({
                    style {
                        padding(12.px, 16.px)
                        property("border-bottom", "1px solid ${SilkColors.border}")
                    }
                }) {
                    KnowledgeFilePreview(
                        downloadUrl = fileRef.downloadUrl,
                        fileName = fileRef.fileName,
                        mimeType = fileRef.mimeType,
                        fileSize = fileRef.fileSize,
                        maxHeightPx = 400,
                    )
                }
            }
            KnowledgeMarkdownWorkspace(
                content = editorContent,
                onContentChange = onContentChange,
                editorMode = editorMode,
                splitRatio = editorSplitRatio,
                availableWidthPx = availableEditorWidthPx,
                readOnly = !canEdit,
                onSave = onSave,
                onSplitRatioChange = onEditorSplitRatioChange,
            )
        }
    }
}

@Composable
private fun KnowledgeMarkdownWorkspace(
    content: String,
    onContentChange: (String) -> Unit,
    editorMode: KnowledgeEditorMode,
    splitRatio: Double,
    availableWidthPx: Double,
    readOnly: Boolean,
    onSave: () -> Unit,
    onSplitRatioChange: (Double) -> Unit,
) {
    Div({
        style {
            property("flex", "1")
            display(DisplayStyle.Flex)
            flexDirection(if (editorMode == KnowledgeEditorMode.SPLIT) FlexDirection.Row else FlexDirection.Column)
            backgroundColor(Color(SilkColors.background))
            minWidth(0.px)
            property("min-height", "0")
            property("overflow", "hidden")
            position(Position.Relative)
        }
    }) {
        if (editorMode != KnowledgeEditorMode.PREVIEW) {
            Div({
                style {
                    if (editorMode == KnowledgeEditorMode.SPLIT) {
                        property("flex", "0 0 ${knowledgePercent(splitRatio)}")
                    } else {
                        flexGrow(1)
                    }
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    minWidth(240.px)
                    property("min-height", "0")
                    property("overflow", "hidden")
                }
            }) {
                MarkdownSourcePane(
                    content = content,
                    onContentChange = onContentChange,
                    onSave = onSave,
                    readOnly = readOnly,
                    isSplit = editorMode == KnowledgeEditorMode.SPLIT,
                )
            }
        }
        if (editorMode == KnowledgeEditorMode.SPLIT) {
            KnowledgeHorizontalResizeHandle(storageHint = "editor-split") { deltaPx ->
                val availableWidth = availableWidthPx.coerceAtLeast(480.0)
                onSplitRatioChange(splitRatio + (deltaPx / availableWidth))
            }
        }
        if (editorMode != KnowledgeEditorMode.EDIT) {
            Div({
                style {
                    if (editorMode == KnowledgeEditorMode.SPLIT) {
                        property("flex", "0 0 ${knowledgePercent(1 - splitRatio)}")
                    } else {
                        flexGrow(1)
                    }
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    minWidth(240.px)
                    property("min-height", "0")
                    property("overflow", "hidden")
                }
            }) {
                MarkdownPreviewPane(
                    content = content,
                    isSplit = editorMode == KnowledgeEditorMode.SPLIT,
                )
            }
        }
    }
}

private fun shouldSaveKnowledgeEntry(event: org.jetbrains.compose.web.events.SyntheticKeyboardEvent): Boolean {
    return (event.metaKey || event.ctrlKey) && event.key.equals("s", ignoreCase = true)
}

@Composable
private fun MarkdownSourcePane(
    content: String,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    readOnly: Boolean,
    isSplit: Boolean,
) {
    Div({
        style {
            flexGrow(1)
            minWidth(0.px)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("min-height", "0")
            property("overflow", "hidden")
            position(Position.Relative)
            property("z-index", "0")
            if (isSplit) {
                property("border-right", "1px solid ${SilkColors.border}")
            }
        }
    }) {
        KnowledgePaneHeader(
            title = "Markdown",
            detail = "Cmd/Ctrl+S 保存",
        )
        TextArea {
            value(content)
            onInput {
                if (!readOnly) {
                    onContentChange(it.value)
                }
            }
            onKeyDown { event ->
                if (!readOnly && shouldSaveKnowledgeEntry(event)) {
                    event.preventDefault()
                    onSave()
                }
            }
            attr("placeholder", "在这里输入 Markdown 内容...")
            if (readOnly) {
                attr("readonly", "true")
            }
            style {
                flexGrow(1)
                minWidth(0.px)
                property("min-height", "0")
                width(100.percent)
                height(100.percent)
                display(DisplayStyle.Block)
                border(0.px)
                borderRadius(0.px)
                padding(16.px)
                fontSize(14.px)
                property("line-height", "1.7")
                fontFamily("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
                backgroundColor(Color("#FFFDF8"))
                color(Color(SilkColors.textPrimary))
                property("box-sizing", "border-box")
                property("resize", "none")
                property("outline", "none")
                position(Position.Relative)
                property("z-index", "1")
                if (readOnly) {
                    property("cursor", "not-allowed")
                }
                property("white-space", "pre-wrap")
                property("overflow-y", "auto")
            }
        }
    }
}

@Composable
private fun MarkdownPreviewPane(
    content: String,
    isSplit: Boolean,
) {
    Div({
        style {
            flexGrow(1)
            minWidth(0.px)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("min-height", "0")
            property("overflow", "hidden")
            position(Position.Relative)
            backgroundColor(Color(SilkColors.surface))
        }
    }) {
        KnowledgePaneHeader(
            title = "预览",
            detail = if (content.isBlank()) "支持公式、代码、表格" else null,
        )
        Div({
            style {
                flexGrow(1)
                minWidth(0.px)
                padding(16.px)
                property("overflow-y", "auto")
                if (!isSplit) {
                    property("border-top", "1px solid ${SilkColors.border}")
                }
            }
        }) {
            if (content.isBlank()) {
                KnowledgeCenteredMessage("输入 Markdown 后这里会实时渲染", SilkColors.textLight, 24.px)
            } else {
                MarkdownContent(content = content)
            }
        }
    }
}

@Composable
private fun KnowledgePaneHeader(title: String, detail: String?) {
    Div({
        style {
            padding(10.px, 16.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            backgroundColor(Color(SilkColors.surfaceElevated))
        }
    }) {
        Span({
            style {
                fontSize(12.px)
                fontWeight("500")
                color(Color(SilkColors.textSecondary))
            }
        }) { Text(title) }
        if (!detail.isNullOrBlank()) {
            Span({
                style {
                    fontSize(12.px)
                    color(Color(SilkColors.textLight))
                }
            }) { Text(detail) }
        }
    }
}

@Composable
private fun KnowledgeEditorToolbar(
    title: String,
    savedTitle: String,
    availableEditorWidthPx: Double,
    isSaving: Boolean,
    saveMessage: String,
    editorMode: KnowledgeEditorMode,
    canEdit: Boolean,
    onTitleChange: (String) -> Unit,
    onResetTitle: () -> Unit,
    onEditorModeChange: (KnowledgeEditorMode) -> Unit,
    onMoveEntry: (() -> Unit)?,
    onDeleteEntry: (() -> Unit)?,
    onOpenCopilot: (() -> Unit)?,
    onSave: () -> Unit,
    onStatusAction: (() -> Unit)?,
    statusActionLabel: String?,
    onMergeCandidate: (() -> Unit)?,
    onExport: () -> Unit,
    onCopyReference: () -> Unit,
) {
    val modeSwitchPresentation = knowledgeEditorModeSwitchPresentation(availableEditorWidthPx)
    var isEditingTitle by remember(savedTitle, canEdit) { mutableStateOf(false) }
    Div({
        style {
            padding(8.px, 16.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            property("flex-wrap", "wrap")
            property("gap", "12px")
            backgroundColor(Color(SilkColors.surfaceElevated))
        }
    }) {
        if (isEditingTitle && canEdit) {
            Div({
                style {
                    property("flex", "1 1 260px")
                    minWidth(220.px)
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "8px")
                    property("flex-wrap", "wrap")
                }
            }) {
                Input(InputType.Text) {
                    value(title)
                    attr("placeholder", "条目标题")
                    if (isSaving) {
                        attr("disabled", "true")
                    }
                    onInput { onTitleChange(it.value) }
                    onKeyDown { event ->
                        when {
                            event.key == "Enter" -> {
                                event.preventDefault()
                                isEditingTitle = false
                            }
                            event.key == "Escape" -> {
                                event.preventDefault()
                                onResetTitle()
                                isEditingTitle = false
                            }
                        }
                    }
                    style {
                        property("flex", "1 1 220px")
                        minWidth(180.px)
                        fontSize(16.px)
                        fontWeight("600")
                        color(Color(SilkColors.textPrimary))
                        backgroundColor(Color("#FFFFFF"))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(8.px)
                        padding(8.px, 12.px)
                        property("outline", "none")
                    }
                }
                KnowledgeInlineActionButton(
                    label = "完成",
                    background = SilkColors.primaryDark,
                    onClick = { isEditingTitle = false },
                )
                KnowledgeInlineActionButton(
                    label = "取消",
                    background = SilkColors.textSecondary,
                    onClick = {
                        onResetTitle()
                        isEditingTitle = false
                    },
                )
            }
        } else {
            val titleStatusText = when {
                !canEdit -> "只读条目"
                title != savedTitle -> "标题已修改，记得保存"
                else -> null
            }
            Div({
                style {
                    property("flex", "1 1 260px")
                    minWidth(180.px)
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "4px")
                    property("cursor", if (canEdit) "text" else "default")
                }
                if (canEdit) {
                    onClick { isEditingTitle = true }
                }
            }) {
                Span({
                    style {
                        fontSize(18.px)
                        fontWeight("600")
                        color(Color(SilkColors.textPrimary))
                    }
                }) { Text(title.ifBlank { "未命名条目" }) }
                titleStatusText?.let { statusText ->
                    Span({
                        style {
                            fontSize(12.px)
                            color(Color(if (title != savedTitle) SilkColors.warning else SilkColors.textLight))
                        }
                    }) {
                        Text(statusText)
                    }
                }
            }
        }
        KnowledgeEditorToolbarActions(
            title = title,
            isSaving = isSaving,
            saveMessage = saveMessage,
            editorMode = editorMode,
            modeSwitchPresentation = modeSwitchPresentation,
            canEdit = canEdit,
            onEditorModeChange = onEditorModeChange,
            onMoveEntry = onMoveEntry,
            onDeleteEntry = onDeleteEntry,
            onOpenCopilot = onOpenCopilot,
            onSave = onSave,
            onStatusAction = onStatusAction,
            statusActionLabel = statusActionLabel,
            onMergeCandidate = onMergeCandidate,
            onExport = onExport,
            onCopyReference = onCopyReference,
        )
    }
}

@Composable
private fun KnowledgeEditorToolbarActions(
    title: String,
    isSaving: Boolean,
    saveMessage: String,
    editorMode: KnowledgeEditorMode,
    modeSwitchPresentation: KnowledgeEditorModeSwitchPresentation,
    canEdit: Boolean,
    onEditorModeChange: (KnowledgeEditorMode) -> Unit,
    onMoveEntry: (() -> Unit)?,
    onDeleteEntry: (() -> Unit)?,
    onOpenCopilot: (() -> Unit)?,
    onSave: () -> Unit,
    onStatusAction: (() -> Unit)?,
    statusActionLabel: String?,
    onMergeCandidate: (() -> Unit)?,
    onExport: () -> Unit,
    onCopyReference: () -> Unit,
) {
    val canSave = canEdit && !isSaving && title.trim().isNotBlank()
    Div({
        style {
            display(DisplayStyle.Flex)
            property("gap", "12px")
            alignItems(AlignItems.Center)
            property("flex", "1 1 360px")
            justifyContent(JustifyContent.FlexEnd)
            property("flex-wrap", "wrap")
        }
    }) {
        KnowledgeEditorModeSwitch(
            selectedMode = editorMode,
            presentation = modeSwitchPresentation,
            onModeChange = onEditorModeChange,
        )
        if (saveMessage.isNotEmpty()) {
            Span({ style { fontSize(12.px); color(Color(SilkColors.success)) } }) { Text(saveMessage) }
        }
        KnowledgeToolbarButton(
            label = "AI 协作",
            background = SilkColors.info,
            enabled = canEdit && !isSaving && onOpenCopilot != null,
            onClick = { onOpenCopilot?.invoke() },
        )
        KnowledgeToolbarButton(
            label = "复制引用",
            background = SilkColors.primaryDark,
            onClick = onCopyReference,
        )
        KnowledgeToolbarButton(
            label = if (!canEdit) "只读" else if (isSaving) "保存中..." else "保存",
            background = SilkColors.primary,
            enabled = canSave,
            onClick = onSave,
        )
        KnowledgeEditorActionMenu(
            isSaving = isSaving,
            canEdit = canEdit,
            onMoveEntry = onMoveEntry,
            onMergeCandidate = onMergeCandidate,
            onStatusAction = onStatusAction,
            statusActionLabel = statusActionLabel,
            onDeleteEntry = onDeleteEntry,
            onExport = onExport,
        )
    }
}

@Composable
private fun KnowledgeEditorActionMenu(
    isSaving: Boolean,
    canEdit: Boolean,
    onMoveEntry: (() -> Unit)?,
    onMergeCandidate: (() -> Unit)?,
    onStatusAction: (() -> Unit)?,
    statusActionLabel: String?,
    onDeleteEntry: (() -> Unit)?,
    onExport: () -> Unit,
) {
    var showMenu by remember(onMoveEntry, onMergeCandidate, onStatusAction, onDeleteEntry) { mutableStateOf(false) }
    val menuAnchorId = remember { "kb-editor-menu-${Random.nextInt(1_000_000)}" }
    val menuItems = remember(isSaving, canEdit, statusActionLabel, onMoveEntry, onMergeCandidate, onStatusAction, onDeleteEntry, onExport) {
        buildKnowledgeEditorMenuItems(
            isSaving = isSaving,
            canEdit = canEdit,
            statusActionLabel = statusActionLabel,
            onMoveEntry = onMoveEntry,
            onMergeCandidate = onMergeCandidate,
            onStatusAction = onStatusAction,
            onDeleteEntry = onDeleteEntry,
            onExport = onExport,
        )
    }
    if (showMenu) {
        DisposableEffect(menuAnchorId) {
            val mouseDownListener: (Event) -> Unit = mouseDownListener@{ event ->
                val targetNode = event.target as? org.w3c.dom.Node ?: return@mouseDownListener
                val menuRoot = document.getElementById(menuAnchorId)
                if (menuRoot?.contains(targetNode) != true) {
                    showMenu = false
                }
            }
            val keyDownListener: (Event) -> Unit = { event ->
                if ((event as? KeyboardEvent)?.key == "Escape") {
                    showMenu = false
                }
            }
            document.addEventListener("mousedown", mouseDownListener)
            document.addEventListener("keydown", keyDownListener)
            onDispose {
                document.removeEventListener("mousedown", mouseDownListener)
                document.removeEventListener("keydown", keyDownListener)
            }
        }
    }
    Div({
        attr("id", menuAnchorId)
        style {
            position(Position.Relative)
        }
    }) {
        KnowledgeToolbarButton(
            label = if (showMenu) "收起菜单" else "菜单",
            background = SilkColors.info,
            onClick = { showMenu = !showMenu },
        )
        if (showMenu) {
            Div({
                style {
                    position(Position.Absolute)
                    top(38.px)
                    right(0.px)
                    backgroundColor(Color("#FFFFFF"))
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    borderRadius(10.px)
                    padding(6.px)
                    property("min-width", "170px")
                    property("box-shadow", "0 12px 32px rgba(0,0,0,0.12)")
                    property("z-index", "20")
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "4px")
                }
            }) {
                menuItems.forEach { item ->
                    KnowledgeMenuActionRow(item.label, textColor = item.textColor, enabled = item.enabled) {
                        showMenu = false
                        item.onClick()
                    }
                }
            }
        }
    }
}

private data class KnowledgeEditorMenuItem(
    val label: String,
    val enabled: Boolean,
    val textColor: String = "#1F2A36",
    val onClick: () -> Unit,
)

private fun buildKnowledgeEditorMenuItems(
    isSaving: Boolean,
    canEdit: Boolean,
    statusActionLabel: String?,
    onMoveEntry: (() -> Unit)?,
    onMergeCandidate: (() -> Unit)?,
    onStatusAction: (() -> Unit)?,
    onDeleteEntry: (() -> Unit)?,
    onExport: () -> Unit,
): List<KnowledgeEditorMenuItem> = buildList {
    onMoveEntry?.let { add(KnowledgeEditorMenuItem("移动到主题", canEdit && !isSaving, onClick = it)) }
    onMergeCandidate?.let { add(KnowledgeEditorMenuItem("并入其他文档", canEdit && !isSaving, onClick = it)) }
    if (statusActionLabel != null && onStatusAction != null) {
        add(KnowledgeEditorMenuItem(statusActionLabel, canEdit && !isSaving, onClick = onStatusAction))
    }
    add(KnowledgeEditorMenuItem("导出 Obsidian", enabled = true, onClick = onExport))
    onDeleteEntry?.let {
        add(KnowledgeEditorMenuItem("删除条目", enabled = !isSaving, textColor = "#B94A48", onClick = it))
    }
}

@Composable
private fun KnowledgeEntryMetaBar(
    topic: KBTopicItem?,
    entry: KBEntryItem,
    spaceLabel: String?,
    permissionLabel: String?,
    currentUserId: String,
    groups: List<Group>,
    onOpenSourceGroup: ((Group) -> Unit)? = null,
    onOpenSourceWorkflow: ((String) -> Unit)? = null,
    onOpenSourceMessage: ((KnowledgeSourceMessageJump) -> Unit)? = null,
) {
    val sourceGroup = entry.source.sourceGroupId?.let { groupId -> groups.find { it.id == groupId } }
    val sourceMessageJump = knowledgeSourceMessageJump(entry)
    val sourceDetails = knowledgeSourceDetails(entry, groups, currentUserId)
    Div({
        style {
            padding(10.px, 16.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            property("gap", "8px")
            flexDirection(FlexDirection.Column)
            backgroundColor(Color(SilkColors.surface))
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "8px")
                property("flex-wrap", "wrap")
            }
        }) {
            spaceLabel?.let { KnowledgeBadge(it, SilkColors.info) }
            permissionLabel?.let {
                KnowledgeBadge(it, if (it == "可编辑") SilkColors.success else SilkColors.warning)
            }
            KnowledgeBadge(entry.status.name.lowercase(), SilkColors.primaryDark)
            topic?.project?.takeIf { it.isNotBlank() }?.let { project ->
                KnowledgeBadge(project, SilkColors.textSecondary)
            }
            KnowledgeBadge(knowledgeSourceLabel(entry.source.sourceType), SilkColors.primary)
        }
        val chipActions = linkedMapOf<String, () -> Unit>()
        if (sourceGroup != null && onOpenSourceGroup != null) {
            chipActions["来源群组"] = { onOpenSourceGroup(sourceGroup) }
        }
        entry.source.workflowId?.takeIf { it.isNotBlank() }?.let { workflowId ->
            if (onOpenSourceWorkflow != null) {
                chipActions["工作流"] = { onOpenSourceWorkflow(workflowId) }
            }
        }
        if (sourceMessageJump != null && onOpenSourceMessage != null) {
            chipActions["来源消息"] = { onOpenSourceMessage(sourceMessageJump) }
        }
        KnowledgeSourceDetailChips(
            sourceDetails = sourceDetails,
            chipActions = chipActions,
        )
    }
}

@Composable
private fun KnowledgeSourceDetailChips(
    sourceDetails: List<Pair<String, String>>,
    chipActions: Map<String, () -> Unit> = emptyMap(),
) {
    if (sourceDetails.isEmpty()) return
    Div({
        style {
            display(DisplayStyle.Flex)
            property("gap", "8px")
            property("flex-wrap", "wrap")
        }
    }) {
        sourceDetails.forEach { (label, value) ->
            val action = chipActions[label]
            Div({
                style {
                    backgroundColor(Color("#FFFFFF"))
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    borderRadius(8.px)
                    padding(6.px, 10.px)
                    property("display", "inline-flex")
                    property("gap", "6px")
                    alignItems(AlignItems.Center)
                    fontSize(12.px)
                    property("cursor", if (action != null) "pointer" else "default")
                }
                if (action != null) {
                    attr("title", value)
                    onClick { action() }
                }
            }) {
                Span({
                    style {
                        color(Color(SilkColors.textSecondary))
                        fontWeight("600")
                    }
                }) { Text("$label:") }
                Span({
                    style {
                        color(Color(if (action != null) SilkColors.info else SilkColors.textPrimary))
                        if (action != null) {
                            property("text-decoration", "underline")
                            fontWeight("600")
                        }
                    }
                }) { Text(value) }
            }
        }
    }
}

@Composable
private fun KnowledgeBadge(label: String, background: String) {
    Span({
        style {
            backgroundColor(Color(background))
            color(Color.white)
            borderRadius(999.px)
            padding(4.px, 10.px)
            fontSize(11.px)
            fontWeight("600")
            property("display", "inline-flex")
            property("align-items", "center")
        }
    }) { Text(label) }
}

@Composable
private fun KnowledgeEditorModeSwitch(
    selectedMode: KnowledgeEditorMode,
    presentation: KnowledgeEditorModeSwitchPresentation,
    onModeChange: (KnowledgeEditorMode) -> Unit,
) {
    if (presentation == KnowledgeEditorModeSwitchPresentation.SELECT) {
        org.jetbrains.compose.web.dom.Select({
            style {
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                borderRadius(7.px)
                backgroundColor(Color(SilkColors.surface))
                color(Color(SilkColors.textPrimary))
                padding(6.px, 10.px)
                fontSize(12.px)
                property("flex", "0 0 108px")
                property("min-width", "108px")
                property("max-width", "108px")
            }
            attr("aria-label", "编辑器模式")
            attr("value", selectedMode.name)
            onChange { event ->
                event.value?.let { selected ->
                    KnowledgeEditorMode.entries.firstOrNull { it.name == selected }?.let(onModeChange)
                }
            }
        }) {
            KnowledgeEditorMode.entries.forEach { mode ->
                org.jetbrains.compose.web.dom.Option(value = mode.name) {
                    Text(mode.label)
                }
            }
        }
        return
    }
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("overflow", "hidden")
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            borderRadius(7.px)
            backgroundColor(Color(SilkColors.surface))
            property("flex", "1 1 180px")
            property("min-width", "0")
        }
    }) {
        KnowledgeEditorMode.entries.forEach { mode ->
            Button({
                attr("title", mode.label)
                style {
                    backgroundColor(Color(if (selectedMode == mode) SilkColors.primary else SilkColors.surface))
                    color(Color(if (selectedMode == mode) "#FFFFFF" else SilkColors.textSecondary))
                    border(0.px)
                    borderRadius(0.px)
                    padding(6.px, if (presentation == KnowledgeEditorModeSwitchPresentation.COMPACT) 8.px else 12.px)
                    property("cursor", "pointer")
                    fontSize(12.px)
                    fontWeight(if (selectedMode == mode) "600" else "500")
                    property("flex", "1 1 0")
                    property("min-width", "0")
                    property("white-space", "nowrap")
                }
                onClick { onModeChange(mode) }
            }) {
                Text(
                    if (presentation == KnowledgeEditorModeSwitchPresentation.COMPACT) {
                        mode.compactLabel
                    } else {
                        mode.label
                    }
                )
            }
        }
    }
}

@Composable
private fun KnowledgeToolbarButton(label: String, background: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button({
        style {
            backgroundColor(Color(if (enabled) background else SilkColors.border))
            color(Color.white)
            border(0.px)
            borderRadius(6.px)
            padding(6.px, 14.px)
            property("cursor", if (enabled) "pointer" else "not-allowed")
            fontSize(13.px)
        }
        if (!enabled) {
            attr("disabled", "true")
        }
        onClick {
            if (enabled) {
                onClick()
            }
        }
    }) { Text(label) }
}

@Composable
private fun EmptyEditorState(
    selectedTopic: KBTopicItem? = null,
    canEdit: Boolean = false,
    onOpenCopilot: (() -> Unit)? = null,
) {
    Div({
        style {
            property("flex", "1")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            flexDirection(FlexDirection.Column)
            property("gap", "12px")
        }
    }) {
        Span({ style { fontSize(48.px); marginBottom(8.px) } }) { Text("\uD83D\uDCDA") }
        if (selectedTopic != null) {
            Span({
                style { fontSize(18.px); color(Color(SilkColors.textPrimary)); fontWeight("500") }
            }) { Text("主题：${selectedTopic.name}") }
            Span({
                style { fontSize(14.px); color(Color(SilkColors.textSecondary)); property("text-align", "center"); property("max-width", "400px"); property("line-height", "1.6") }
            }) { Text("选择一个条目开始编辑，或使用 AI 协作在主题中创建新文档") }
            if (canEdit && onOpenCopilot != null) {
                KnowledgeToolbarButton(
                    label = "AI 协作 — 创建新条目",
                    background = SilkColors.info,
                    enabled = true,
                    onClick = onOpenCopilot,
                )
            }
        } else {
            Span({
                style { fontSize(18.px); color(Color(SilkColors.textSecondary)) }
            }) { Text("选择或创建主题开始编辑") }
        }
        Span({
            style { fontSize(14.px); color(Color(SilkColors.textLight)); marginTop(8.px) }
        }) { Text("内容将自动归类到 Silk 知识库") }
    }
}

@Composable
private fun CreateTopicDialog(
    topicName: String,
    topicProject: String,
    spaceOptions: List<KnowledgeSpaceOption>,
    selectedSpaceId: String,
    onTopicNameChange: (String) -> Unit,
    onTopicProjectChange: (String) -> Unit,
    onSpaceChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalDialog(title = "创建主题", onDismiss = onDismiss) {
        LabeledInput("主题名称", topicName) { value -> onTopicNameChange(value) }
        LabeledInput("所属项目（可选）", topicProject) { value -> onTopicProjectChange(value) }
        Div({
            style {
                marginTop(12.px)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "8px")
            }
        }) {
            Span({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    fontWeight("600")
                }
            }) { Text("目标空间") }
            KnowledgeSpaceTabs(
                options = spaceOptions,
                selectedSpaceId = selectedSpaceId,
                onSpaceSelect = { onSpaceChange(it.id) },
            )
        }
        DialogActions(
            onCancel = onDismiss,
            onConfirm = onConfirm,
            confirmLabel = "创建",
        )
    }
}

/**
 * 已选用户 chips 展示
 */
@Composable
@Suppress("CyclomaticComplexMethod")
private fun TopicAccessDialog(
    topicName: String,
    topicProject: String,
    isTeamTopic: Boolean,
    readUserIds: List<String>,
    writeUserIds: List<String>,
    manageUserIds: List<String>,
    writeLocked: Boolean,
    teamMembersCanWrite: Boolean,
    onTopicNameChange: (String) -> Unit,
    onTopicProjectChange: (String) -> Unit,
    onReadUserIdsChange: (List<String>) -> Unit,
    onWriteUserIdsChange: (List<String>) -> Unit,
    onManageUserIdsChange: (List<String>) -> Unit,
    onWriteLockedChange: (Boolean) -> Unit,
    onTeamMembersCanWriteChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<UserSearchItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var showResults by remember { mutableStateOf(false) }
    // 缓存已选用户的名称
    val selectedUserNames = remember(readUserIds, writeUserIds, manageUserIds) {
        mutableMapOf<String, String>().apply {
            searchResults.forEach {
                if (it.id in readUserIds || it.id in writeUserIds || it.id in manageUserIds) {
                    put(it.id, it.fullName)
                }
            }
        }
    }
    // 所有已选用户的并集（去重）
    val allSelectedUserIds = remember(readUserIds, writeUserIds, manageUserIds) {
        (readUserIds + writeUserIds + manageUserIds).distinct()
    }

    // 添加用户并指定角色
    fun addUserWithRole(userId: String, userName: String, role: String) {
        selectedUserNames[userId] = userName
        when (role) {
            "read" -> onReadUserIdsChange(readUserIds + userId)
            "write" -> onWriteUserIdsChange(writeUserIds + userId)
            "manage" -> onManageUserIdsChange(manageUserIds + userId)
        }
    }

    // 移除用户（从所有角色中移除）
    fun removeUser(userId: String) {
        onReadUserIdsChange(readUserIds.filter { it != userId })
        onWriteUserIdsChange(writeUserIds.filter { it != userId })
        onManageUserIdsChange(manageUserIds.filter { it != userId })
    }

    // 切换角色
    fun toggleRole(userId: String, role: String) {
        when (role) {
            "read" -> {
                onReadUserIdsChange(
                    if (userId in readUserIds) readUserIds.filter { it != userId }
                    else readUserIds + userId
                )
            }
            "write" -> {
                onWriteUserIdsChange(
                    if (userId in writeUserIds) writeUserIds.filter { it != userId }
                    else writeUserIds + userId
                )
            }
            "manage" -> {
                onManageUserIdsChange(
                    if (userId in manageUserIds) manageUserIds.filter { it != userId }
                    else manageUserIds + userId
                )
            }
        }
    }

    ModalDialog(title = "主题权限", onDismiss = onDismiss) {
        LabeledInput("主题名称", topicName, onTopicNameChange)
        LabeledInput("所属项目（可选）", topicProject, onTopicProjectChange)

        // ── 统一搜索框 ──
        Div({
            style {
                marginBottom(12.px)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "6px")
            }
        }) {
            Span({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    fontWeight("600")
                }
            }) { Text("添加成员") }
            Div({
                style { position(Position.Relative) }
            }) {
                Input(InputType.Text) {
                    attr("placeholder", "搜索用户名称添加权限...")
                    value(searchQuery)
                    onInput { event ->
                        val value = (event.target as? org.w3c.dom.HTMLInputElement)?.value ?: ""
                        searchQuery = value
                        if (value.length >= 2) {
                            isSearching = true
                            showResults = true
                            scope.launch {
                                val response = ApiClient.searchUsersByName(value)
                                searchResults = if (response.success) {
                                    response.users.filter { it.id !in allSelectedUserIds }
                                } else {
                                    emptyList()
                                }
                                isSearching = false
                            }
                        } else {
                            searchResults = emptyList()
                            showResults = false
                        }
                    }
                    onFocus { showResults = searchResults.isNotEmpty() }
                    onBlur {
                        scope.launch {
                            kotlinx.coroutines.delay(200)
                            showResults = false
                        }
                    }
                    style {
                        width(100.percent)
                        padding(8.px, 10.px)
                        fontSize(13.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(6.px)
                        property("outline", "none")
                        property("boxSizing", "border-box")
                    }
                }
                // 搜索结果下拉（带角色选择）
                if (showResults && searchQuery.length >= 2) {
                    Div({
                        style {
                            position(Position.Absolute)
                            top(100.percent)
                            left(0.px)
                            right(0.px)
                            backgroundColor(Color("#FFFFFF"))
                            border(1.px, LineStyle.Solid, Color(SilkColors.border))
                            borderRadius(6.px)
                            property("boxShadow", "0 4px 12px rgba(0,0,0,0.12)")
                            property("zIndex", "100")
                            maxHeight(220.px)
                            property("overflow-y", "auto")
                        }
                    }) {
                        if (isSearching) {
                            Span({ style { padding(8.px, 12.px); fontSize(13.px); color(Color(SilkColors.textLight)) } }) {
                                Text("搜索中...")
                            }
                        } else if (searchResults.isEmpty()) {
                            Span({ style { padding(8.px, 12.px); fontSize(13.px); color(Color(SilkColors.textLight)) } }) {
                                Text("未找到匹配用户")
                            }
                        } else {
                            searchResults.forEach { user ->
                                Div({
                                    style {
                                        padding(8.px, 12.px)
                                        property("border-bottom", "1px solid ${SilkColors.border}")
                                    }
                                }) {
                                    Div({
                                        style {
                                            fontSize(13.px); fontWeight("500"); marginBottom(4.px)
                                        }
                                    }) {
                                        Span({}) { Text(user.fullName) }
                                        Span({ style { marginLeft(8.px); fontSize(12.px); color(Color(SilkColors.textLight)) } }) {
                                            Text("(${user.loginName})")
                                        }
                                    }
                                    Div({
                                        style {
                                            display(DisplayStyle.Flex); property("gap", "4px")
                                        }
                                    }) {
                                        listOf(
                                            "read" to "👁 只读",
                                            "write" to "✏ 可写",
                                            "manage" to "⚙ 管理",
                                        ).forEach { (role, label) ->
                                            Button({
                                                style {
                                                    fontSize(11.px)
                                                    padding(3.px, 8.px)
                                                    borderRadius(4.px)
                                                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                                                    backgroundColor(Color("#F5F5F5"))
                                                    color(Color(SilkColors.textSecondary))
                                                    property("cursor", "pointer")
                                                }
                                                onClick {
                                                    addUserWithRole(user.id, user.fullName, role)
                                                    searchQuery = ""
                                                    searchResults = emptyList()
                                                    showResults = false
                                                }
                                            }) { Text(label) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 已有成员 ──
        if (allSelectedUserIds.isNotEmpty()) {
            Div({
                style {
                    marginBottom(8.px)
                    fontSize(13.px)
                    fontWeight("600")
                    color(Color(SilkColors.textSecondary))
                }
            }) { Text("已有成员") }
            Div({
                style {
                    marginBottom(12.px)
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "4px")
                    padding(8.px)
                    borderRadius(6.px)
                    backgroundColor(Color("#F9F9F9"))
                }
            }) {
                allSelectedUserIds.forEach { userId ->
                    val displayName = selectedUserNames[userId] ?: userId.take(8) + "..."
                    val isRead = userId in readUserIds
                    val isWrite = userId in writeUserIds
                    val isManage = userId in manageUserIds
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.Center)
                            property("gap", "6px")
                            padding(6.px, 8.px)
                            borderRadius(6.px)
                            backgroundColor(Color("#FFFFFF"))
                        }
                    }) {
                        // 用户头像/名称
                        Span({
                            style {
                                fontSize(13.px)
                                fontWeight("500")
                                minWidth(80.px)
                                color(Color(SilkColors.textPrimary))
                            }
                        }) { Text(displayName) }
                        // 角色 toggle 按钮
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                property("flex", "1")
                                property("gap", "4px")
                            }
                        }) {
                            RoleToggleChip(label = "👁", selected = isRead, onClick = { toggleRole(userId, "read") })
                            RoleToggleChip(label = "✏", selected = isWrite, onClick = { toggleRole(userId, "write") })
                            RoleToggleChip(label = "⚙", selected = isManage, onClick = { toggleRole(userId, "manage") })
                        }
                        // 移除按钮
                        Span({
                            style {
                                fontSize(16.px)
                                color(Color("#CCCCCC"))
                                property("cursor", "pointer")
                                property("flex-shrink", "0")
                            }
                            attr("title", "移除此用户")
                            onClick { removeUser(userId) }
                        }) { Text("✕") }
                    }
                }
            }
        }

        KnowledgeBooleanSetting(
            label = "锁定写入",
            description = "开启后，普通团队成员不会继承写权限。",
            value = writeLocked,
            onChange = onWriteLockedChange,
        )
        if (isTeamTopic) {
            KnowledgeBooleanSetting(
                label = "团队成员可写",
                description = "关闭后，只有显式写用户和 manager 可编辑。",
                value = teamMembersCanWrite,
                onChange = onTeamMembersCanWriteChange,
            )
        }
        DialogActions(
            onCancel = onDismiss,
            onConfirm = onConfirm,
            confirmLabel = "保存权限",
        )
    }
}

@Composable
private fun RoleToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Div({
        style {
            padding(2.px, 8.px)
            borderRadius(10.px)
            fontSize(12.px)
            property("cursor", "pointer")
            backgroundColor(Color(if (selected) "#E8F0FE" else "#F0F0F0"))
            color(Color(if (selected) "#1A73E8" else SilkColors.textLight))
            fontWeight(if (selected) "600" else "400")
            property("transition", "all 0.15s")
        }
        onClick { onClick() }
    }) {
        Text(label)
    }
}

@Composable
private fun KnowledgeBooleanSetting(
    label: String,
    description: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Div({
        style {
            marginBottom(12.px)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "8px")
        }
    }) {
        Span({
            style {
                fontSize(13.px)
                color(Color(SilkColors.textSecondary))
                fontWeight("600")
            }
        }) { Text(label) }
        Span({
            style {
                fontSize(12.px)
                color(Color(SilkColors.textLight))
            }
        }) { Text(description) }
        Div({ style { display(DisplayStyle.Flex); property("gap", "8px") } }) {
            KnowledgeToggleButton(
                label = "开启",
                selected = value,
                onClick = { onChange(true) },
            )
            KnowledgeToggleButton(
                label = "关闭",
                selected = !value,
                onClick = { onChange(false) },
            )
        }
    }
}

@Composable
private fun KnowledgeToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button({
        style {
            backgroundColor(Color(if (selected) SilkColors.primary else SilkColors.surface))
            color(Color(if (selected) "#FFFFFF" else SilkColors.textSecondary))
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            borderRadius(6.px)
            padding(6.px, 12.px)
            property("cursor", "pointer")
            fontSize(12.px)
        }
        onClick { onClick() }
    }) { Text(label) }
}

@Composable
private fun CreateEntryDialog(
    entryTitle: String,
    onEntryTitleChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalDialog(title = "创建条目", onDismiss = onDismiss) {
        LabeledInput("条目标题", entryTitle) { value -> onEntryTitleChange(value) }
        DialogActions(
            onCancel = onDismiss,
            onConfirm = onConfirm,
            confirmLabel = "创建",
        )
    }
}

@Composable
private fun KnowledgeCopilotDialog(
    entry: KBEntryItem,
    instruction: String,
    applyChanges: Boolean,
    isRunning: Boolean,
    feedbackMessage: String,
    assistantReply: String,
    draft: KnowledgeBaseCopilotDraft?,
    onInstructionChange: (String) -> Unit,
    onApplyChangesChange: (Boolean) -> Unit,
    onApplyDraftToEditor: () -> Unit,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
) {
    ModalDialog(title = "KB Copilot", onDismiss = onDismiss) {
        Div({
            style {
                padding(10.px, 12.px)
                borderRadius(10.px)
                backgroundColor(Color("#F7F4EA"))
                marginBottom(12.px)
            }
        }) {
            Span({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    fontWeight("600")
                }
            }) { Text("当前条目") }
            Div({ style { marginTop(6.px) } }) {
                Text(entry.title)
            }
        }
        Div({
            style {
                marginBottom(12.px)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "8px")
            }
        }) {
            Span({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    fontWeight("600")
                }
            }) { Text("让 AI 怎么改") }
            TextArea {
                value(instruction)
                onInput { onInstructionChange(it.value) }
                attr("placeholder", "例如：把这篇文档整理成更清晰的结构，并补上发布后的验证步骤")
                style {
                    width(100.percent)
                    height(144.px)
                    borderRadius(8.px)
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    padding(12.px)
                    fontSize(14.px)
                    property("line-height", "1.6")
                    property("box-sizing", "border-box")
                    property("resize", "vertical")
                }
            }
        }
        KnowledgeBooleanSetting(
            label = "直接写回知识库",
            description = "开启后，Copilot 会通过 KB action 直接更新当前条目；关闭则只生成草稿，先填回编辑器由你确认。",
            value = applyChanges,
            onChange = onApplyChangesChange,
        )
        if (feedbackMessage.isNotBlank()) {
            Div({
                style {
                    marginBottom(12.px)
                    padding(10.px, 12.px)
                    borderRadius(8.px)
                    backgroundColor(Color("#F4FAF6"))
                    color(Color(SilkColors.textSecondary))
                    fontSize(13.px)
                }
            }) {
                Text(feedbackMessage)
            }
        }
        if (assistantReply.isNotBlank()) {
            Div({
                style {
                    marginBottom(12.px)
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "8px")
                }
            }) {
                Span({
                    style {
                        fontSize(13.px)
                        color(Color(SilkColors.textSecondary))
                        fontWeight("600")
                    }
                }) { Text("AI 说明") }
                Div({
                    style {
                        property("max-height", "180px")
                        property("overflow-y", "auto")
                        padding(12.px)
                        borderRadius(8.px)
                        backgroundColor(Color("#FFFDF8"))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    }
                }) {
                    MarkdownContent(assistantReply)
                }
            }
        }
        draft?.let { draftValue ->
            Div({
                style {
                    marginBottom(12.px)
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "8px")
                }
            }) {
                Span({
                    style {
                        fontSize(13.px)
                        color(Color(SilkColors.textSecondary))
                        fontWeight("600")
                    }
                }) { Text("草稿预览") }
                Div({
                    style {
                        padding(12.px)
                        borderRadius(8.px)
                        backgroundColor(Color("#FFFFFF"))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    }
                }) {
                    Div({ style { marginBottom(8.px); fontWeight("600") } }) { Text(draftValue.title) }
                    if (draftValue.tags.isNotEmpty()) {
                        Div({ style { marginBottom(8.px); fontSize(12.px); color(Color(SilkColors.textSecondary)) } }) {
                            Text("标签: ${draftValue.tags.joinToString(", ")}")
                        }
                    }
                    Div({
                        style {
                            property("max-height", "180px")
                            property("overflow-y", "auto")
                            backgroundColor(Color("#FFFDF8"))
                            borderRadius(8.px)
                            padding(12.px)
                        }
                    }) {
                        MarkdownContent(draftValue.content)
                    }
                }
                if (!applyChanges) {
                    KnowledgeToolbarButton(
                        label = "在编辑器中显示修改",
                        background = SilkColors.primaryDark,
                        enabled = !isRunning && draft?.diffChunks?.isNotEmpty() == true,
                        onClick = onApplyDraftToEditor,
                    )
                }
            }
        }
        DialogActions(
            onCancel = onDismiss,
            onConfirm = onRun,
            confirmLabel = if (isRunning) "执行中..." else if (applyChanges) "执行并写回" else "生成草稿",
            confirmEnabled = !isRunning && instruction.trim().isNotBlank(),
        )
    }
}

/**
 * Build the final content string from diff chunks based on accept/reject decisions.
 */
private fun buildFinalContentFromDiff(
    chunks: List<DiffChunk>,
    accepted: Set<Int>,
): String {
    val parts = chunks.mapIndexed { index, chunk ->
        when (chunk.type) {
            "unchanged" -> chunk.originalText
            "deleted" -> if (index in accepted) "" else chunk.originalText
            "inserted" -> if (index in accepted) chunk.newText else ""
            "modified" -> if (index in accepted) chunk.newText else chunk.originalText
            else -> chunk.originalText
        }
    }
    return parts.filter { it.isNotEmpty() }.joinToString("\n")
}

/**
 * Build a change summary from diff chunks.
 */
private fun buildChangeSummary(chunks: List<DiffChunk>): String {
    val added = chunks.count { it.type == "inserted" }
    val deleted = chunks.count { it.type == "deleted" }
    val modified = chunks.count { it.type == "modified" }
    val parts = mutableListOf<String>()
    if (added > 0) parts.add("新增 $added 个内容块")
    if (deleted > 0) parts.add("删除 $deleted 个内容块")
    if (modified > 0) parts.add("修改 $modified 个内容块")
    return parts.joinToString("；")
}

/**
 * Diff review pane: shows AI-suggested changes as inline diff chunks with accept/reject controls.
 * Replaces the normal Markdown source editor during diff review mode.
 * Actions (accept all / apply / cancel) are controlled from the sidebar REVIEW state.
 */
@Composable
@Suppress("CyclomaticComplexMethod")
private fun DiffReviewPane(
    chunks: List<DiffChunk>,
    accepted: Set<Int>,
    rejected: Set<Int>,
    onAcceptChunk: (Int) -> Unit,
    onRejectChunk: (Int) -> Unit,
) {
    val totalChunks = chunks.size
    val decidedCount = accepted.size + rejected.size

    Div({
        style {
            property("flex", "1")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            backgroundColor(Color("#FFFFFF"))
            property("min-height", "0")
            property("overflow", "hidden")
        }
    }) {
        // Simple header — action buttons moved to sidebar REVIEW state
        Div({
            style {
                padding(10.px, 16.px)
                property("border-bottom", "1px solid ${SilkColors.border}")
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                property("flex-shrink", "0")
                backgroundColor(Color(SilkColors.surfaceElevated))
            }
        }) {
            Span({
                style {
                    fontSize(15.px)
                    fontWeight("600")
                    color(Color(SilkColors.textPrimary))
                }
            }) { Text("修改审查") }
            Span({
                style {
                    fontSize(12.px)
                    color(Color(SilkColors.textSecondary))
                }
            }) {
                Text("已处理 $decidedCount / $totalChunks 处")
            }
        }

        // Scrollable diff content
        Div({
            style {
                property("flex", "1")
                property("overflow-y", "auto")
                padding(8.px, 0.px)
            }
        }) {
            if (chunks.isEmpty()) {
                Div({
                    style {
                        padding(24.px)
                        fontSize(14.px)
                        color(Color(SilkColors.textLight))
                        property("text-align", "center")
                    }
                }) { Text("没有内容差异") }
            } else {
                chunks.forEachIndexed { index, chunk ->
                    DiffChunkRow(
                        chunk = chunk,
                        index = index,
                        isAccepted = index in accepted,
                        isRejected = index in rejected,
                        onAccept = { onAcceptChunk(index) },
                        onReject = { onRejectChunk(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffChunkRow(
    chunk: DiffChunk,
    index: Int,
    isAccepted: Boolean,
    isRejected: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val bgColor = when {
        isAccepted -> "rgba(82,164,117,0.12)"
        isRejected -> "rgba(200,80,70,0.10)"
        chunk.type == "inserted" -> "rgba(82,164,117,0.06)"
        chunk.type == "deleted" -> "rgba(200,80,70,0.06)"
        chunk.type == "modified" -> "rgba(230,180,60,0.12)"
        else -> "transparent"
    }
    val borderColor = when {
        isAccepted -> "#52A475"
        isRejected -> "#C85046"
        chunk.type == "inserted" -> "#52A475"
        chunk.type == "deleted" -> "#C85046"
        chunk.type == "modified" -> "#E6B43C"
        else -> "transparent"
    }
    val typeLabel = when (chunk.type) {
        "unchanged" -> "未更改"
        "deleted" -> "已删除"
        "inserted" -> "新增"
        "modified" -> "已修改"
        else -> chunk.type
    }
    // For unchanged chunks, show a collapsible section (default collapsed)
    if (chunk.type == "unchanged") {
        var expanded by remember { mutableStateOf(false) }
        Div({
            style {
                property("border-bottom", "1px solid ${SilkColors.border}")
            }
        }) {
            // Clickable header to toggle expansion
            Div({
                style {
                    padding(4.px, 16.px)
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "6px")
                    property("cursor", "pointer")
                    property("user-select", "none")
                    backgroundColor(Color(if (expanded) "rgba(0,0,0,0.02)" else "transparent"))
                    property("transition", "background-color 150ms ease")
                }
                onClick { expanded = !expanded }
            }) {
                Span({
                    style {
                        fontSize(10.px)
                        color(Color(SilkColors.textLight))
                        property("transition", "transform 150ms ease")
                        property("display", "inline-block")
                        property("transform", if (expanded) "rotate(90deg)" else "rotate(0deg)")
                    }
                }) { Text("▶") }
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textLight))
                    }
                }) {
                    Text("⋯ 未更改内容 (L${chunk.lineStart + 1}-L${chunk.lineEnd + 1})")
                }
                Span({
                    style {
                        fontSize(10.px)
                        color(Color(SilkColors.textLight))
                        property("opacity", "0.6")
                        marginLeft(4.px)
                    }
                }) {
                    Text(if (expanded) "点击折叠" else "点击展开")
                }
            }
            // Expandable content area
            if (expanded) {
                Div({
                    style {
                        padding(6.px, 16.px, 10.px, 24.px)
                        fontSize(13.px)
                        color(Color(SilkColors.textSecondary))
                        property("line-height", "1.6")
                        property("opacity", "0.65")
                        property("max-height", "300px")
                        property("overflow-y", "auto")
                        property("border-top", "1px solid ${SilkColors.border}")
                    }
                }) {
                    if (chunk.originalText.isNotBlank()) {
                        MarkdownContent(chunk.originalText)
                    } else if (chunk.newText.isNotBlank()) {
                        MarkdownContent(chunk.newText)
                    } else {
                        Text("(空)")
                    }
                }
            }
        }
        return
    }

    Div({
        style {
            padding(8.px, 16.px)
            backgroundColor(Color(bgColor))
            property("border-left", "3px solid $borderColor")
            property("border-bottom", "1px solid ${SilkColors.border}")
            property("transition", "background-color 150ms ease")
        }
    }) {
        // Header row: type label + line range + actions
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                marginBottom(6.px)
                property("gap", "8px")
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "6px")
                }
            }) {
                Span({
                    style {
                        fontSize(11.px)
                        fontWeight("600")
                        color(Color(borderColor))
                        padding(2.px, 6.px)
                        borderRadius(4.px)
                        backgroundColor(Color("#FFFFFF"))
                        property("border", "1px solid $borderColor")
                    }
                }) { Text(typeLabel) }
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textLight))
                    }
                }) {
                    Text("L${chunk.lineStart + 1}-L${chunk.lineEnd + 1}")
                }
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "4px")
                }
            }) {
                if (!isAccepted) {
                    Button({
                        style {
                            backgroundColor(Color("#52A475"))
                            color(Color.white)
                            border(0.px)
                            borderRadius(4.px)
                            padding(3.px, 8.px)
                            fontSize(11.px)
                            property("cursor", "pointer")
                        }
                        onClick {
                            it.stopPropagation()
                            onAccept()
                        }
                    }) {
                        Text(
                            if (chunk.type == "deleted") "✓ 确认删除"
                            else "✓ 接受"
                        )
                    }
                }
                if (!isRejected) {
                    Button({
                        style {
                            backgroundColor(Color("#C85046"))
                            color(Color.white)
                            border(0.px)
                            borderRadius(4.px)
                            padding(3.px, 8.px)
                            fontSize(11.px)
                            property("cursor", "pointer")
                        }
                        onClick {
                            it.stopPropagation()
                            onReject()
                        }
                    }) {
                        Text(
                            if (chunk.type == "inserted") "✗ 跳过新增"
                            else "✗ 拒绝"
                        )
                    }
                }
                if (isAccepted || isRejected) {
                    Span({
                        style {
                            fontSize(11.px)
                            fontWeight("600")
                            color(Color(if (isAccepted) "#52A475" else "#C85046"))
                            padding(3.px, 6.px)
                        }
                    }) {
                        Text(if (isAccepted) "✓ 已接受" else "✗ 已拒绝")
                    }
                }
            }
        }

        // Visual diff content — render each type with distinct visual style
        when (chunk.type) {
            "inserted" -> {
                // Green-highlighted addition block
                Div({
                    style {
                        backgroundColor(Color("rgba(82,164,117,0.08)"))
                        borderRadius(4.px)
                        border(1.px, LineStyle.Solid, Color("#52A475"))
                        property("overflow", "hidden")
                    }
                }) {
                    Div({
                        style {
                            padding(4.px, 8.px)
                            backgroundColor(Color("#52A475"))
                            color(Color.white)
                            fontSize(11.px)
                            fontWeight("600")
                        }
                    }) { Text("新增内容") }
                    Div({
                        style {
                            padding(8.px)
                            fontSize(14.px)
                            color(Color(SilkColors.textPrimary))
                            property("line-height", "1.7")
                            property("max-height", "200px")
                            property("overflow-y", "auto")
                        }
                    }) {
                        if (chunk.newText.isNotBlank()) {
                            MarkdownContent(chunk.newText)
                        } else {
                            Text("(空)")
                        }
                    }
                }
            }
            "deleted" -> {
                // Red-highlighted deletion block
                Div({
                    style {
                        backgroundColor(Color("rgba(200,80,70,0.08)"))
                        borderRadius(4.px)
                        border(1.px, LineStyle.Solid, Color("#C85046"))
                        property("overflow", "hidden")
                    }
                }) {
                    Div({
                        style {
                            padding(4.px, 8.px)
                            backgroundColor(Color("#C85046"))
                            color(Color.white)
                            fontSize(11.px)
                            fontWeight("600")
                        }
                    }) { Text("删除内容") }
                    Div({
                        style {
                            padding(8.px)
                            fontSize(14.px)
                            color(Color(SilkColors.textPrimary))
                            property("line-height", "1.7")
                            property("max-height", "200px")
                            property("overflow-y", "auto")
                            property("text-decoration", "line-through")
                            property("opacity", "0.7")
                        }
                    }) {
                        if (chunk.originalText.isNotBlank()) {
                            MarkdownContent(chunk.originalText)
                        } else {
                            Text("(空)")
                        }
                    }
                }
            }
            "modified" -> {
                // Side-by-side visual diff: original (red) → new (green)
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "6px")
                    }
                }) {
                    // Original content (red)
                    Div({
                        style {
                            backgroundColor(Color("rgba(200,80,70,0.08)"))
                            borderRadius(4.px)
                            border(1.px, LineStyle.Solid, Color("#C85046"))
                            property("overflow", "hidden")
                        }
                    }) {
                        Div({
                            style {
                                padding(3.px, 8.px)
                                backgroundColor(Color("#C85046"))
                                color(Color.white)
                                fontSize(11.px)
                                fontWeight("600")
                            }
                        }) { Text("修改前") }
                        Div({
                            style {
                                padding(8.px)
                                fontSize(14.px)
                                color(Color(SilkColors.textPrimary))
                                property("line-height", "1.7")
                                property("max-height", "150px")
                                property("overflow-y", "auto")
                                property("text-decoration", "line-through")
                                property("opacity", "0.7")
                            }
                        }) {
                            if (chunk.originalText.isNotBlank()) {
                                MarkdownContent(chunk.originalText)
                            } else {
                                Text("(空)")
                            }
                        }
                    }
                    // Arrow indicator
                    Div({
                        style {
                            fontSize(13.px)
                            color(Color(SilkColors.textSecondary))
                            property("text-align", "center")
                            property("line-height", "1")
                        }
                    }) { Text("↓ 修改为 ↓") }
                    // New content (green)
                    Div({
                        style {
                            backgroundColor(Color("rgba(82,164,117,0.08)"))
                            borderRadius(4.px)
                            border(1.px, LineStyle.Solid, Color("#52A475"))
                            property("overflow", "hidden")
                        }
                    }) {
                        Div({
                            style {
                                padding(3.px, 8.px)
                                backgroundColor(Color("#52A475"))
                                color(Color.white)
                                fontSize(11.px)
                                fontWeight("600")
                            }
                        }) { Text("修改后") }
                        Div({
                            style {
                                padding(8.px)
                                fontSize(14.px)
                                color(Color(SilkColors.textPrimary))
                                property("line-height", "1.7")
                                property("max-height", "150px")
                                property("overflow-y", "auto")
                            }
                        }) {
                            if (chunk.newText.isNotBlank()) {
                                MarkdownContent(chunk.newText)
                            } else {
                                Text("(空)")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mode of the KB Copilot sidebar.
 */
private enum class CopilotMode {
    /** Operating on a single entry (edit/rewrite) */
    ENTRY,
    /** Operating on the current topic/space (create new entry, summarize, etc.) */
    TOPIC,
}

/**
 * Three-state flow for the Copilot sidebar.
 */
private enum class CopilotSidebarState {
    /** User inputs modification requirements */
    INPUT,
    /** AI returns a preview of the generated changes */
    PREVIEW,
    /** User reviews diff chunks and accepts/rejects each */
    REVIEW,
}

/**
 * Three-state KB Copilot sidebar: INPUT → PREVIEW → REVIEW.
 *
 * Rules:
 * - One primary button per page.
 * - At most two visible buttons per area.
 * - Conversation history hidden by default (click "查看过程" to expand).
 * - "直接写回知识库" toggle removed (all drafts go through review).
 * - Low-frequency operations in "⋯" menu.
 */
@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun KnowledgeCopilotSidebar(
    entry: KBEntryItem?,
    topic: KBTopicItem?,
    instruction: String,
    isRunning: Boolean,
    feedbackMessage: String,
    assistantReply: String,
    draft: KnowledgeBaseCopilotDraft?,
    sidebarWidth: Double,
    copilotMode: CopilotMode,
    sidebarState: CopilotSidebarState,
    diffChunks: List<DiffChunk>,
    acceptedChunkIndices: Set<Int>,
    rejectedChunkIndices: Set<Int>,
    onInstructionChange: (String) -> Unit,
    onRun: () -> Unit,
    onClose: () -> Unit,
    onBackToInput: () -> Unit,
    onStartReview: () -> Unit,
    onAcceptChunk: (Int) -> Unit,
    onRejectChunk: (Int) -> Unit,
    onAcceptAll: () -> Unit,
    onApplyChanges: () -> Unit,
    onCancelReview: () -> Unit,
    onClearConversation: () -> Unit,
) {
    Div({
        style {
            width(sidebarWidth.px)
            minWidth(KNOWLEDGE_COPILOT_SIDEBAR_MIN_WIDTH.px)
            property("flex", "0 0 auto")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            backgroundColor(Color("#FFFFFF"))
            property("border-left", "1px solid ${SilkColors.border}")
            property("min-height", "0")
            property("overflow", "hidden")
            property("box-sizing", "border-box")
        }
    }) {
        when (sidebarState) {
            CopilotSidebarState.INPUT -> CopilotInputState(
                entry = entry,
                topic = topic,
                instruction = instruction,
                isRunning = isRunning,
                feedbackMessage = feedbackMessage,
                copilotMode = copilotMode,
                sidebarWidth = sidebarWidth,
                onInstructionChange = onInstructionChange,
                onRun = onRun,
                onClose = onClose,
                onClearConversation = onClearConversation,
            )
            CopilotSidebarState.PREVIEW -> CopilotPreviewState(
                assistantReply = assistantReply,
                draft = draft,
                isRunning = isRunning,
                feedbackMessage = feedbackMessage,
                onBackToInput = onBackToInput,
                onStartReview = onStartReview,
                onRun = onRun,
                onInstructionChange = onInstructionChange,
                instruction = instruction,
                onClose = onClose,
            )
            CopilotSidebarState.REVIEW -> CopilotReviewState(
                diffChunks = diffChunks,
                acceptedChunkIndices = acceptedChunkIndices,
                rejectedChunkIndices = rejectedChunkIndices,
                onAcceptChunk = onAcceptChunk,
                onRejectChunk = onRejectChunk,
                onAcceptAll = onAcceptAll,
                onApplyChanges = onApplyChanges,
                onCancelReview = onCancelReview,
                onClose = onClose,
            )
        }
    }
}

// ==================== State 1: Input ====================

@Composable
@Suppress("UNUSED_PARAMETER", "CyclomaticComplexMethod")
private fun CopilotInputState(
    entry: KBEntryItem?,
    topic: KBTopicItem?,
    instruction: String,
    isRunning: Boolean,
    feedbackMessage: String,
    copilotMode: CopilotMode,
    @Suppress("UNUSED_PARAMETER") sidebarWidth: Double,
    onInstructionChange: (String) -> Unit,
    onRun: () -> Unit,
    onClose: () -> Unit,
    onClearConversation: () -> Unit,
) {
    // "⋯" menu state
    var showMenu by remember { mutableStateOf(false) }
    val menuAnchorId = remember { "kb-copilot-menu-${Random.nextInt(1_000_000)}" }

    if (showMenu) {
        DisposableEffect(menuAnchorId) {
            val listener: (Event) -> Unit = { event ->
                val target = event.target as? org.w3c.dom.Node
                val root = document.getElementById(menuAnchorId)
                if (root?.contains(target) != true) showMenu = false
            }
            val keyListener: (Event) -> Unit = { event ->
                if ((event as? KeyboardEvent)?.key == "Escape") showMenu = false
            }
            document.addEventListener("mousedown", listener)
            document.addEventListener("keydown", keyListener)
            onDispose {
                document.removeEventListener("mousedown", listener)
                document.removeEventListener("keydown", keyListener)
            }
        }
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            height(100.percent)
            property("min-height", "0")
            property("overflow", "hidden")
        }
    }) {
        // Header
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                padding(12.px, 16.px)
                property("border-bottom", "1px solid ${SilkColors.border}")
                property("flex-shrink", "0")
            }
        }) {
            Span({
                style {
                    fontSize(14.px)
                    fontWeight("600")
                    color(Color(SilkColors.textPrimary))
                }
            }) { Text("KB Copilot") }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "4px")
                    alignItems(AlignItems.Center)
                }
            }) {
                // "⋯" menu button
                Div({ attr("id", menuAnchorId); style { position(Position.Relative) } }) {
                    Button({
                        style {
                            property("background", "none")
                            border(0.px)
                            property("cursor", "pointer")
                            fontSize(18.px)
                            color(Color(SilkColors.textSecondary))
                            padding(4.px, 8.px)
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.Center)
                            justifyContent(JustifyContent.Center)
                            property("line-height", "1")
                            property("letter-spacing", "2px")
                        }
                        onClick { showMenu = !showMenu }
                    }) { Text("⋯") }
                    if (showMenu) {
                        Div({
                            style {
                                position(Position.Absolute)
                                top(32.px)
                                right(0.px)
                                backgroundColor(Color("#FFFFFF"))
                                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                                borderRadius(8.px)
                                padding(4.px, 0.px)
                                property("min-width", "150px")
                                property("box-shadow", "0 8px 24px rgba(0,0,0,0.10)")
                                property("z-index", "30")
                                display(DisplayStyle.Flex)
                                flexDirection(FlexDirection.Column)
                            }
                        }) {
                            CopilotMenuRow("清空会话") { showMenu = false; onClearConversation() }
                            CopilotMenuRow("查看历史对话") { showMenu = false }
                            CopilotMenuRow("Copilot 设置") { showMenu = false }
                            CopilotMenuRow("反馈问题") { showMenu = false }
                        }
                    }
                }
                Button({
                    style {
                        property("background", "none")
                        border(0.px)
                        property("cursor", "pointer")
                        fontSize(18.px)
                        color(Color(SilkColors.textSecondary))
                        padding(4.px)
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        justifyContent(JustifyContent.Center)
                        property("line-height", "1")
                    }
                    onClick { onClose() }
                }) { Text("✕") }
            }
        }
        // Current entry context
        Div({
            style {
                padding(10.px, 16.px)
                backgroundColor(Color("#F7F4EA"))
                property("flex-shrink", "0")
            }
        }) {
            Div({ style { fontSize(11.px); color(Color(SilkColors.textSecondary)); fontWeight("600"); marginBottom(4.px) } }) {
                Text("正在编辑")
            }
            Div({ style { fontSize(13.px); color(Color(SilkColors.textPrimary)); property("word-break", "break-all") } }) {
                when (copilotMode) {
                    CopilotMode.ENTRY -> Text(entry?.title?.ifBlank { "未命名" } ?: "")
                    CopilotMode.TOPIC -> Text(topic?.name?.ifBlank { "未命名" } ?: "")
                }
            }
        }
        // Scrollable input area
        Div({
            style {
                property("flex", "1")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("overflow-y", "auto")
                padding(12.px, 16.px)
                property("gap", "12px")
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "6px")
                    property("flex-shrink", "0")
                }
            }) {
                Span({
                    style {
                        fontSize(13.px)
                        color(Color(SilkColors.textSecondary))
                        fontWeight("600")
                    }
                }) {
                    Text("你想怎么修改这篇文档？")
                }
                TextArea {
                    value(instruction)
                    onInput { onInstructionChange(it.value) }
                    onKeyDown { event ->
                        if (event.key == "Enter" && (event.ctrlKey || event.metaKey)) {
                            event.preventDefault()
                            onRun()
                        }
                    }
                    attr("placeholder", if (copilotMode == CopilotMode.TOPIC) "例如：创建一个关于部署流程的指南" else "例如：补充 Markdown 表格示例并统一各级标题结构")
                    style {
                        width(100.percent)
                        height(120.px)
                        borderRadius(8.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(10.px)
                        fontSize(13.px)
                        property("line-height", "1.5")
                        property("box-sizing", "border-box")
                        property("resize", "vertical")
                        property("outline", "none")
                        fontFamily("inherit")
                    }
                }
            }
            // Single primary button
            Div({
                style { property("flex-shrink", "0") }
            }) {
                KnowledgeToolbarButton(
                    label = if (isRunning) "执行中..." else "生成修改",
                    background = SilkColors.primary,
                    enabled = !isRunning && instruction.trim().isNotBlank(),
                    onClick = onRun,
                )
            }
            // Feedback message (error/success)
            if (feedbackMessage.isNotBlank()) {
                Div({
                    style {
                        padding(8.px, 10.px)
                        borderRadius(6.px)
                        backgroundColor(Color("#F4FAF6"))
                        color(Color(SilkColors.textSecondary))
                        fontSize(12.px)
                        property("flex-shrink", "0")
                    }
                }) {
                    Text(feedbackMessage)
                }
            }
        }
    }
}

// ==================== State 2: Preview ====================

@Composable
private fun CopilotPreviewState(
    assistantReply: String,
    draft: KnowledgeBaseCopilotDraft?,
    isRunning: Boolean,
    feedbackMessage: String,
    onBackToInput: () -> Unit,
    onStartReview: () -> Unit,
    onRun: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onInstructionChange: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") instruction: String,
    onClose: () -> Unit,
) {
    // Build change summary from diff chunks
    val changeSummary = remember(draft?.diffChunks) {
        draft?.diffChunks?.let { buildChangeSummary(it) } ?: ""
    }
    // First paragraph of AI reply as brief description
    val briefDescription = remember(assistantReply) {
        assistantReply.trim().lines().firstOrNull()?.take(120) ?: ""
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            height(100.percent)
            property("min-height", "0")
            property("overflow", "hidden")
        }
    }) {
        // Header with back button
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                padding(12.px, 16.px)
                property("border-bottom", "1px solid ${SilkColors.border}")
                property("flex-shrink", "0")
            }
        }) {
            Button({
                style {
                    property("background", "none")
                    border(0.px)
                    property("cursor", "pointer")
                    fontSize(13.px)
                    color(Color(SilkColors.primaryDark))
                    padding(0.px)
                    fontWeight("500")
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "4px")
                }
                onClick { onBackToInput() }
            }) {
                Text("← 返回修改要求")
            }
            Button({
                style {
                    property("background", "none")
                    border(0.px)
                    property("cursor", "pointer")
                    fontSize(18.px)
                    color(Color(SilkColors.textSecondary))
                    padding(4.px)
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.Center)
                    property("line-height", "1")
                }
                onClick { onClose() }
            }) { Text("✕") }
        }
        // Scrollable content
        Div({
            style {
                property("flex", "1")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("overflow-y", "auto")
                padding(12.px, 16.px)
                property("gap", "12px")
            }
        }) {
            // Result header
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "4px")
                    property("flex-shrink", "0")
                }
            }) {
                Span({
                    style {
                        fontSize(15.px)
                        fontWeight("600")
                        color(Color(SilkColors.textPrimary))
                    }
                }) { Text("已生成修改") }
                if (briefDescription.isNotBlank()) {
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color(SilkColors.textSecondary))
                            property("line-height", "1.5")
                        }
                    }) { Text(briefDescription) }
                }
            }
            // Change summary
            if (changeSummary.isNotBlank()) {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "6px")
                        property("flex-shrink", "0")
                    }
                }) {
                    Span({
                        style {
                            fontSize(12.px)
                            color(Color(SilkColors.textSecondary))
                            fontWeight("600")
                        }
                    }) { Text("变更摘要") }
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color(SilkColors.textPrimary))
                            property("line-height", "1.6")
                        }
                    }) { Text(changeSummary) }
                }
            }
            // Compact preview
            draft?.let { draftValue ->
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "6px")
                        property("flex-shrink", "0")
                    }
                }) {
                    Span({
                        style {
                            fontSize(12.px)
                            color(Color(SilkColors.textSecondary))
                            fontWeight("600")
                        }
                    }) { Text("修改预览") }
                    Div({
                        style {
                            padding(10.px)
                            borderRadius(8.px)
                            backgroundColor(Color("#FFFFFF"))
                            border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        }
                    }) {
                        Div({ style { fontSize(14.px); fontWeight("600"); marginBottom(6.px) } }) {
                            Text(draftValue.title)
                        }
                        Div({
                            style {
                                property("max-height", "140px")
                                property("overflow-y", "auto")
                                backgroundColor(Color("#FFFDF8"))
                                borderRadius(6.px)
                                padding(8.px)
                                fontSize(13.px)
                                property("line-height", "1.5")
                                property("color", SilkColors.textSecondary)
                            }
                        }) {
                            // Show only first 300 chars as a teaser
                            val teaser = draftValue.content.take(300)
                            Text(teaser + if (draftValue.content.length > 300) "…" else "")
                        }
                    }
                }
            }
            // Feedback message
            if (feedbackMessage.isNotBlank()) {
                Div({
                    style {
                        padding(8.px, 10.px)
                        borderRadius(6.px)
                        backgroundColor(Color("#F4FAF6"))
                        color(Color(SilkColors.textSecondary))
                        fontSize(12.px)
                        property("flex-shrink", "0")
                    }
                }) {
                    Text(feedbackMessage)
                }
            }
            // Buttons
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "6px")
                    property("flex-shrink", "0")
                    marginTop(4.px)
                }
            }) {
                // Primary: start review
                KnowledgeToolbarButton(
                    label = "查看并审阅修改",
                    background = SilkColors.primary,
                    enabled = !isRunning && draft?.diffChunks?.isNotEmpty() == true,
                    onClick = onStartReview,
                )
                // Secondary: regenerate
                Button({
                    style {
                        property("background", "none")
                        border(0.px)
                        property("cursor", if (isRunning) "not-allowed" else "pointer")
                        fontSize(13.px)
                        color(Color(SilkColors.textSecondary))
                        padding(6.px, 14.px)
                        property("text-decoration", "underline")
                    }
                    if (isRunning) {
                        attr("disabled", "true")
                    }
                    onClick {
                        if (!isRunning) onRun()
                    }
                }) {
                    Text("重新生成")
                }
            }
        }
    }
}

// ==================== State 3: Review ====================

@Composable
private fun CopilotReviewState(
    diffChunks: List<DiffChunk>,
    acceptedChunkIndices: Set<Int>,
    rejectedChunkIndices: Set<Int>,
    onAcceptChunk: (Int) -> Unit,
    onRejectChunk: (Int) -> Unit,
    onAcceptAll: () -> Unit,
    onApplyChanges: () -> Unit,
    onCancelReview: () -> Unit,
    onClose: () -> Unit,
) {
    val totalChunks = diffChunks.size
    val decidedCount = acceptedChunkIndices.size + rejectedChunkIndices.size
    val allDecided = decidedCount >= totalChunks && totalChunks > 0
    val acceptedCount = acceptedChunkIndices.size
    val rejectedCount = rejectedChunkIndices.size

    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            height(100.percent)
            property("min-height", "0")
            property("overflow", "hidden")
        }
    }) {
        // Header
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                padding(12.px, 16.px)
                property("border-bottom", "1px solid ${SilkColors.border}")
                property("flex-shrink", "0")
            }
        }) {
            Span({
                style {
                    fontSize(14.px)
                    fontWeight("600")
                    color(Color(SilkColors.textPrimary))
                }
            }) { Text("修改审阅") }
            Button({
                style {
                    property("background", "none")
                    border(0.px)
                    property("cursor", "pointer")
                    fontSize(18.px)
                    color(Color(SilkColors.textSecondary))
                    padding(4.px)
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.Center)
                    property("line-height", "1")
                }
                onClick { onClose() }
            }) { Text("✕") }
        }
        // Scrollable content
        Div({
            style {
                property("flex", "1")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("overflow-y", "auto")
                padding(12.px, 16.px)
                property("gap", "12px")
            }
        }) {
            // Progress
            Div({
                style {
                    padding(8.px, 12.px)
                    borderRadius(8.px)
                    backgroundColor(Color("#F8F8FA"))
                    fontSize(13.px)
                    color(Color(SilkColors.textPrimary))
                    fontWeight("500")
                    property("flex-shrink", "0")
                }
            }) {
                Text("已处理 $decidedCount / $totalChunks 处")
            }

            if (!allDecided) {
                // Current modification context
                if (diffChunks.isNotEmpty()) {
                    val currentUndecidedIndex = diffChunks.indices.firstOrNull { idx ->
                        idx !in acceptedChunkIndices && idx !in rejectedChunkIndices
                    }
                    if (currentUndecidedIndex != null) {
                        val currentChunk = diffChunks[currentUndecidedIndex]
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                flexDirection(FlexDirection.Column)
                                property("gap", "6px")
                                property("flex-shrink", "0")
                            }
                        }) {
                            Span({
                                style {
                                    fontSize(12.px)
                                    color(Color(SilkColors.textSecondary))
                                    fontWeight("600")
                                }
                            }) { Text("当前修改") }
                            Div({
                                style {
                                    padding(8.px, 10.px)
                                    borderRadius(6.px)
                                    backgroundColor(Color("#FFFDF8"))
                                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                                    fontSize(13.px)
                                    property("line-height", "1.5")
                                    property("max-height", "100px")
                                    property("overflow-y", "auto")
                                }
                            }) {
                                val typeLabel = when (currentChunk.type) {
                                    "inserted" -> "新增内容"
                                    "deleted" -> "删除内容"
                                    "modified" -> "修改内容"
                                    else -> "内容变更"
                                }
                                Text("$typeLabel (L${currentChunk.lineStart + 1}-L${currentChunk.lineEnd + 1})")
                            }
                        }
                        // Chunk-level actions
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                flexDirection(FlexDirection.Column)
                                property("gap", "6px")
                                property("flex-shrink", "0")
                            }
                        }) {
                            KnowledgeToolbarButton(
                                label = if (currentChunk.type == "deleted") "接受此删除" else "接受此修改",
                                background = SilkColors.success,
                                enabled = true,
                                onClick = { onAcceptChunk(currentUndecidedIndex) },
                            )
                            if (currentChunk.type != "unchanged") {
                                KnowledgeToolbarButton(
                                    label = if (currentChunk.type == "inserted") "跳过此新增" else "跳过此修改",
                                    background = SilkColors.textSecondary,
                                    enabled = true,
                                    onClick = { onRejectChunk(currentUndecidedIndex) },
                                )
                            }
                        }
                    }
                }
                // Global actions divider
                Div({
                    style {
                        property("border-top", "1px solid ${SilkColors.border}")
                        marginTop(4.px)
                        property("flex-shrink", "0")
                    }
                }) {}
                // Global actions
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "6px")
                        property("flex-shrink", "0")
                    }
                }) {
                    KnowledgeToolbarButton(
                        label = "接受全部",
                        background = SilkColors.primary,
                        enabled = acceptedChunkIndices.size < totalChunks,
                        onClick = onAcceptAll,
                    )
                    Button({
                        style {
                            property("background", "none")
                            border(0.px)
                            property("cursor", "pointer")
                            fontSize(13.px)
                            color(Color(SilkColors.error))
                            padding(6.px, 14.px)
                        }
                        onClick { onCancelReview() }
                    }) {
                        Text("放弃全部修改")
                    }
                }
            } else {
                // All decided: final confirmation
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "8px")
                        property("flex-shrink", "0")
                    }
                }) {
                    Div({
                        style {
                            padding(8.px, 12.px)
                            borderRadius(8.px)
                            backgroundColor(Color("#F4FAF6"))
                            fontSize(13.px)
                            color(Color(SilkColors.textPrimary))
                            property("line-height", "1.6")
                        }
                    }) {
                        Text("已选择 $acceptedCount 项修改" +
                            if (rejectedCount > 0) "，跳过 $rejectedCount 项" else "")
                    }
                    KnowledgeToolbarButton(
                        label = "应用到文档",
                        background = SilkColors.primary,
                        enabled = acceptedCount > 0,
                        onClick = onApplyChanges,
                    )
                    Button({
                        style {
                            property("background", "none")
                            border(0.px)
                            property("cursor", "pointer")
                            fontSize(13.px)
                            color(Color(SilkColors.textSecondary))
                            padding(6.px, 14.px)
                        }
                        onClick { onCancelReview() }
                    }) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

// ==================== Shared Components ====================

@Composable
private fun CopilotMenuRow(label: String, onClick: () -> Unit) {
    Div({
        style {
            padding(8.px, 14.px)
            fontSize(13.px)
            color(Color(SilkColors.textPrimary))
            property("cursor", "pointer")
            property("white-space", "nowrap")
            property("transition", "background-color 100ms ease")
        }
        onClick { onClick() }
        onMouseOver {
            val el = it.currentTarget.asDynamic()
            el.style.backgroundColor = SilkColors.background
            Unit
        }
        onMouseOut {
            val el = it.currentTarget.asDynamic()
            el.style.backgroundColor = "transparent"
            Unit
        }
    }) {
        Text(label)
    }
}

@Composable
private fun MoveKnowledgeEntryDialog(
    entryTitle: String,
    targetTopics: List<KBTopicItem>,
    selectedTargetTopicId: String,
    isSaving: Boolean,
    onSelectedTargetTopicIdChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalDialog(title = "移动条目", onDismiss = onDismiss) {
        Div({
            style {
                fontSize(13.px)
                color(Color(SilkColors.textSecondary))
                marginBottom(12.px)
            }
        }) { Text("把“$entryTitle”移动到另一个主题。仅支持同一知识空间内移动。") }
        Div({
            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "8px")
                marginBottom(12.px)
            }
        }) {
            targetTopics.forEach { topic ->
                KnowledgeToggleButton(
                    label = topic.name,
                    selected = selectedTargetTopicId == topic.id,
                    onClick = { onSelectedTargetTopicIdChange(topic.id) },
                )
            }
        }
        DialogActions(
            onCancel = onDismiss,
            onConfirm = onConfirm,
            confirmLabel = if (isSaving) "移动中..." else "确认移动",
            confirmEnabled = !isSaving && selectedTargetTopicId.isNotBlank(),
        )
    }
}

@Composable
private fun ConfirmKnowledgeDeleteDialog(
    title: String,
    description: String,
    confirmLabel: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalDialog(title = title, onDismiss = onDismiss) {
        Div({
            style {
                fontSize(13.px)
                color(Color(SilkColors.textSecondary))
                marginBottom(12.px)
                property("line-height", "1.6")
            }
        }) { Text(description) }
        DialogActions(
            onCancel = onDismiss,
            onConfirm = onConfirm,
            confirmLabel = if (isSaving) "$confirmLabel..." else confirmLabel,
            confirmEnabled = !isSaving,
        )
    }
}

@Composable
private fun CreateEntryFromFileDialog(
    file: ApiClient.GroupAssetFile,
    topics: List<KBTopicItem>,
    selectedTopicId: String,
    isSaving: Boolean,
    onTopicSelectionChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalDialog(title = "从文件创建 KB 文档", onDismiss = onDismiss) {
        Div({
            style {
                fontSize(13.px)
                color(Color(SilkColors.textSecondary))
                marginBottom(8.px)
            }
        }) { Text("文件: ${file.fileName}") }
        Div({
            style {
                fontSize(13.px)
                color(Color(SilkColors.textSecondary))
                marginBottom(16.px)
                property("line-height", "1.6")
            }
        }) { Text("请选择目标主题：") }
        if (topics.isEmpty()) {
            Div({
                style {
                    padding(12.px)
                    fontSize(13.px)
                    color(Color(SilkColors.warning))
                    backgroundColor(Color("#FFF8EE"))
                    borderRadius(8.px)
                    marginBottom(16.px)
                }
            }) { Text("当前空间没有可写入的主题，请先创建主题。") }
        } else {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "4px")
                    marginBottom(16.px)
                    property("max-height", "240px")
                    property("overflow-y", "auto")
                }
            }) {
                topics.forEach { topic ->
                    val isSelected = topic.id == selectedTopicId
                    Div({
                        style {
                            padding(10.px, 14.px)
                            borderRadius(8.px)
                            property("cursor", "pointer")
                            backgroundColor(
                                if (isSelected) Color("#E8F0FE") else Color("transparent")
                            )
                            property("transition", "background-color 0.15s")
                        }
                        onClick { onTopicSelectionChange(topic.id) }
                    }) {
                        Span({
                            style {
                                fontSize(14.px)
                                fontWeight(if (isSelected) "600" else "400")
                                color(Color(SilkColors.textPrimary))
                            }
                        }) { Text(topic.name) }
                        if (topic.project.isNotBlank()) {
                            Div({
                                style {
                                    fontSize(12.px)
                                    color(Color(SilkColors.textLight))
                                    marginTop(2.px)
                                }
                            }) { Text(topic.project) }
                        }
                    }
                }
            }
        }
        DialogActions(
            onCancel = onDismiss,
            onConfirm = onConfirm,
            confirmLabel = if (isSaving) "创建中..." else "创建候选条目",
            confirmEnabled = !isSaving && topics.isNotEmpty(),
        )
    }
}

@Composable
private fun KnowledgeColumnHeader(
    title: String,
    actionLabel: String?,
    actionEnabled: Boolean = true,
    secondaryAction: KnowledgeHeaderSecondaryAction? = null,
    onCollapse: (() -> Unit)? = null,
    onAction: () -> Unit,
) {
    Div({
        style {
            padding(12.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
        }
    }) {
        Span({
            style { fontSize(16.px); fontWeight("bold"); color(Color(SilkColors.textPrimary)) }
        }) { Text(title) }
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "6px")
                alignItems(AlignItems.Center)
            }
        }) {
            if (onCollapse != null) {
                Button({
                    attr("title", "收起列表")
                    style {
                        backgroundColor(Color(SilkColors.surfaceElevated))
                        color(Color(SilkColors.textSecondary))
                        property("border", "1px solid ${SilkColors.border}")
                        borderRadius(6.px)
                        padding(4.px, 10.px)
                        property("cursor", "pointer")
                        fontSize(12.px)
                        property("white-space", "nowrap")
                    }
                    onClick {
                        it.stopPropagation()
                        onCollapse()
                    }
                }) { Text("\u00AB") }
            }
            secondaryAction?.let { action ->
                KnowledgeInlineActionButton(
                    label = action.label,
                    background = action.background,
                    enabled = action.enabled,
                    onClick = action.onClick,
                )
            }
            if (actionLabel != null) {
                KnowledgeInlineActionButton(
                    label = actionLabel,
                    background = SilkColors.primary,
                    enabled = actionEnabled,
                    onClick = onAction,
                )
            }
        }
    }
}

@Composable
private fun KnowledgeInlineActionButton(
    label: String,
    background: String,
    enabled: Boolean = true,
    title: String? = null,
    onClick: () -> Unit,
) {
    Button({
        style {
            backgroundColor(Color(if (enabled) background else SilkColors.border))
            color(Color.white)
            border(0.px)
            borderRadius(6.px)
            padding(4.px, 10.px)
            property("cursor", if (enabled) "pointer" else "not-allowed")
            fontSize(12.px)
            property("white-space", "nowrap")
        }
        if (title != null) {
            attr("title", title)
        }
        if (!enabled) {
            attr("disabled", "true")
        }
        onClick {
            it.stopPropagation()
            if (enabled) {
                onClick()
            }
        }
    }) { Text(label) }
}

@Composable
private fun KnowledgeMenuActionRow(
    label: String,
    textColor: String = SilkColors.textPrimary,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Div({
        style {
            padding(8.px, 10.px)
            borderRadius(8.px)
            property("cursor", if (enabled) "pointer" else "not-allowed")
            color(Color(if (enabled) textColor else SilkColors.textLight))
            fontSize(13.px)
            fontWeight("600")
            backgroundColor(Color(if (enabled) SilkColors.surface else "#F4F1EA"))
        }
        onClick {
            if (enabled) {
                onClick()
            }
        }
    }) { Text(label) }
}

@Composable
private fun KnowledgeListEmptyState(message: String) {
    KnowledgeCenteredMessage(message, SilkColors.textLight, 20.px)
}

@Composable
private fun KnowledgeCenteredMessage(message: String, color: String, paddingSize: org.jetbrains.compose.web.css.CSSNumeric) {
    Div({
        style {
            padding(paddingSize)
            property("text-align", "center")
            this.color(Color(color))
        }
    }) { Text(message) }
}

private fun resetTopicDialog(
    onVisibilityChange: (Boolean) -> Unit,
    onNameChange: (String) -> Unit,
    onProjectChange: (String) -> Unit,
) {
    onVisibilityChange(false)
    onNameChange("")
    onProjectChange("")
}

private fun resetTopicAccessDialog(
    onVisibilityChange: (Boolean) -> Unit,
    onTopicNameChange: (String) -> Unit,
    onTopicProjectChange: (String) -> Unit,
    onReadUserIdsChange: (List<String>) -> Unit,
    onWriteUserIdsChange: (List<String>) -> Unit,
    onManageUserIdsChange: (List<String>) -> Unit,
    onWriteLockedChange: (Boolean) -> Unit,
    onTeamMembersCanWriteChange: (Boolean) -> Unit,
) {
    onVisibilityChange(false)
    onTopicNameChange("")
    onTopicProjectChange("")
    onReadUserIdsChange(emptyList())
    onWriteUserIdsChange(emptyList())
    onManageUserIdsChange(emptyList())
    onWriteLockedChange(false)
    onTeamMembersCanWriteChange(true)
}

private fun resetEntryDialog(
    onVisibilityChange: (Boolean) -> Unit,
    onTitleChange: (String) -> Unit,
) {
    onVisibilityChange(false)
    onTitleChange("")
}

private suspend fun loadKnowledgeEntries(
    topic: KBTopicItem,
    userId: String,
    onSelectedTopicChange: (KBTopicItem?) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
) {
    onSelectedTopicChange(topic)
    onSelectedEntryChange(null)
    onEditorContentChange("")
    onEntriesChange(ApiClient.getKBEntries(topic.id, userId))
}

private suspend fun navigateToKnowledgeBaseTarget(
    target: KnowledgeBaseNavigationTarget,
    userId: String,
    existingTopics: List<KBTopicItem>,
    onTopicsChange: (List<KBTopicItem>) -> Unit,
    onSelectedTopicChange: (KBTopicItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
): Boolean {
    val entry = ApiClient.getKBEntry(target.entryId, userId) ?: return false
    val topics = if (existingTopics.any { it.id == entry.topicId }) {
        existingTopics
    } else {
        ApiClient.getKBTopics(userId).also(onTopicsChange)
    }
    val topic = topics.find { it.id == entry.topicId } ?: return false
    val entries = ApiClient.getKBEntries(topic.id, userId)
    onSelectedTopicChange(topic)
    onEntriesChange(entries)
    val selected = entries.find { it.id == entry.id } ?: entry
    onSelectedEntryChange(selected)
    onEditorContentChange(selected.content)
    return true
}

private fun loadKnowledgeEntry(
    entry: KBEntryItem,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
) {
    onSelectedEntryChange(entry)
    onEditorContentChange(entry.content)
}

private suspend fun saveKnowledgeEntry(
    entry: KBEntryItem?,
    topic: KBTopicItem?,
    editorTitle: String,
    editorContent: String,
    userId: String,
    onSavingChange: (Boolean) -> Unit,
    onSaveMessageChange: (String) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorTitleChange: (String) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
) {
    if (entry == null || topic == null || editorTitle.trim().isBlank()) return
    onSavingChange(true)
    onSaveMessageChange("")
    val updated = ApiClient.updateKBEntry(
        entryId = entry.id,
        title = editorTitle.trim(),
        content = editorContent,
        tags = null,
        userId = userId,
    )
    if (updated != null) {
        onSelectedEntryChange(updated)
        onEditorTitleChange(updated.title)
        onEntriesChange(ApiClient.getKBEntries(topic.id, userId))
        onSaveMessageChange("已保存")
    }
    onSavingChange(false)
}

private suspend fun updateKnowledgeEntryStatus(
    entry: KBEntryItem?,
    topic: KBTopicItem?,
    userId: String,
    status: KBEntryStatus,
    onSavingChange: (Boolean) -> Unit,
    onSaveMessageChange: (String) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
) {
    if (entry == null || topic == null) return
    onSavingChange(true)
    onSaveMessageChange("")
    val updated = ApiClient.updateKBEntry(
        entryId = entry.id,
        title = null,
        content = null,
        tags = null,
        userId = userId,
        status = status,
    )
    if (updated != null) {
        onSelectedEntryChange(updated)
        onEntriesChange(ApiClient.getKBEntries(topic.id, userId))
        onSaveMessageChange(
            when (status) {
                KBEntryStatus.PUBLISHED -> "已发布"
                KBEntryStatus.ARCHIVED -> "已归档"
                KBEntryStatus.CANDIDATE -> "已转为候选"
                KBEntryStatus.DELETED -> "状态已更新"
            }
        )
    }
    onSavingChange(false)
}

private suspend fun bulkUpdateKnowledgeEntryStatus(
    entryIds: Set<String>,
    topic: KBTopicItem?,
    userId: String,
    status: KBEntryStatus,
    currentSelectedEntryId: String?,
    onSavingChange: (Boolean) -> Unit,
    onSaveMessageChange: (String) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onEntryFilterChange: (KnowledgeEntryFilter) -> Unit,
    onSelectedCandidateEntryIdsChange: (Set<String>) -> Unit,
) {
    if (topic == null || entryIds.isEmpty()) return
    onSavingChange(true)
    onSaveMessageChange("")
    var successCount = 0
    entryIds.forEach { entryId ->
        val updated = ApiClient.updateKBEntry(
            entryId = entryId,
            title = null,
            content = null,
            tags = null,
            userId = userId,
            status = status,
        )
        if (updated != null) {
            successCount += 1
        }
    }
    val refreshedEntries = ApiClient.getKBEntries(topic.id, userId)
    onEntriesChange(refreshedEntries)
    val refreshedSelectedEntry = currentSelectedEntryId?.let { selectedId ->
        refreshedEntries.find { it.id == selectedId }
    }
    onSelectedEntryChange(refreshedSelectedEntry)
    onEditorContentChange(refreshedSelectedEntry?.content.orEmpty())
    onSelectedCandidateEntryIdsChange(emptySet())
    if (successCount > 0) {
        onEntryFilterChange(knowledgeFilterForStatus(status))
        onSaveMessageChange(
            when (status) {
                KBEntryStatus.PUBLISHED -> "已批量发布 $successCount 条候选"
                KBEntryStatus.ARCHIVED -> "已批量归档 $successCount 条候选"
                KBEntryStatus.CANDIDATE -> "已批量转回候选 $successCount 条"
                KBEntryStatus.DELETED -> "已批量更新 $successCount 条"
            }
        )
    }
    onSavingChange(false)
}

private suspend fun mergeCandidateIntoKnowledgeEntry(
    candidateEntry: KBEntryItem?,
    sourceTopic: KBTopicItem?,
    targetOption: KnowledgeMergeTargetOption?,
    userId: String,
    onSavingChange: (Boolean) -> Unit,
    onSaveMessageChange: (String) -> Unit,
    onSelectedTopicChange: (KBTopicItem?) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onEntryFilterChange: (KnowledgeEntryFilter) -> Unit,
    onDialogVisibilityChange: (Boolean) -> Unit,
) {
    if (sourceTopic == null || candidateEntry == null || candidateEntry.status != KBEntryStatus.CANDIDATE) return
    val targetOptionValue = targetOption ?: return
    val targetEntry = targetOptionValue.entry
    onSavingChange(true)
    onSaveMessageChange("")
    val mergedTarget = ApiClient.updateKBEntry(
        entryId = targetEntry.id,
        title = null,
        content = mergeKnowledgeEntryContent(
            targetContent = targetEntry.content,
            candidateTitle = candidateEntry.title,
            candidateContent = candidateEntry.content,
        ),
        tags = mergeKnowledgeEntryTags(targetEntry.tags, candidateEntry.tags),
        userId = userId,
    )
    if (mergedTarget == null) {
        onSavingChange(false)
        return
    }
    val archivedCandidate = ApiClient.updateKBEntry(
        entryId = candidateEntry.id,
        title = null,
        content = null,
        tags = null,
        userId = userId,
        status = KBEntryStatus.ARCHIVED,
    )
    if (archivedCandidate == null) {
        onSavingChange(false)
        return
    }
    val refreshedEntries = ApiClient.getKBEntries(targetOptionValue.topic.id, userId)
    val refreshedTarget = refreshedEntries.find { it.id == targetEntry.id } ?: mergedTarget
    onSelectedTopicChange(targetOptionValue.topic)
    onEntriesChange(refreshedEntries)
    onSelectedEntryChange(refreshedTarget)
    onEditorContentChange(refreshedTarget.content)
    onEntryFilterChange(knowledgeFilterForStatus(refreshedTarget.status))
    onSaveMessageChange("已并入“${targetOptionValue.topic.name} / ${refreshedTarget.title}”，原候选已归档")
    onSavingChange(false)
    onDialogVisibilityChange(false)
}

private suspend fun bulkMergeCandidatesIntoKnowledgeEntry(
    selectedCandidateEntryIds: Set<String>,
    sourceTopic: KBTopicItem?,
    candidateEntries: List<KBEntryItem>,
    targetOption: KnowledgeMergeTargetOption?,
    userId: String,
    onSavingChange: (Boolean) -> Unit,
    onSaveMessageChange: (String) -> Unit,
    onSelectedTopicChange: (KBTopicItem?) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onEntryFilterChange: (KnowledgeEntryFilter) -> Unit,
    onSelectedCandidateEntryIdsChange: (Set<String>) -> Unit,
    onDialogVisibilityChange: (Boolean) -> Unit,
) {
    if (sourceTopic == null || selectedCandidateEntryIds.isEmpty()) return
    val targetOptionValue = targetOption ?: return
    val targetEntry = targetOptionValue.entry
    val candidatesToMerge = candidateEntries
        .filter { it.id in selectedCandidateEntryIds && it.id != targetEntry.id && it.status == KBEntryStatus.CANDIDATE }
    if (candidatesToMerge.isEmpty()) return
    onSavingChange(true)
    onSaveMessageChange("")
    val mergedTarget = ApiClient.updateKBEntry(
        entryId = targetEntry.id,
        title = null,
        content = mergeKnowledgeEntriesContent(
            targetContent = targetEntry.content,
            candidateEntries = candidatesToMerge,
        ),
        tags = mergeKnowledgeEntriesTags(targetTags = targetEntry.tags, candidateEntries = candidatesToMerge),
        userId = userId,
    )
    if (mergedTarget == null) {
        onSavingChange(false)
        return
    }
    var archivedCount = 0
    candidatesToMerge.forEach { candidate ->
        val archived = ApiClient.updateKBEntry(
            entryId = candidate.id,
            title = null,
            content = null,
            tags = null,
            userId = userId,
            status = KBEntryStatus.ARCHIVED,
        )
        if (archived != null) {
            archivedCount += 1
        }
    }
    val refreshedEntries = ApiClient.getKBEntries(targetOptionValue.topic.id, userId)
    val refreshedTarget = refreshedEntries.find { it.id == targetEntry.id } ?: mergedTarget
    onSelectedTopicChange(targetOptionValue.topic)
    onEntriesChange(refreshedEntries)
    onSelectedEntryChange(refreshedTarget)
    onEditorContentChange(refreshedTarget.content)
    onEntryFilterChange(knowledgeFilterForStatus(refreshedTarget.status))
    onSelectedCandidateEntryIdsChange(emptySet())
    if (archivedCount > 0) {
        onSaveMessageChange("已并入 $archivedCount 条候选到“${targetOptionValue.topic.name} / ${refreshedTarget.title}”")
    }
    onSavingChange(false)
    onDialogVisibilityChange(false)
}

private suspend fun loadKnowledgeMergeTargetOptions(
    topics: List<KBTopicItem>,
    sourceTopic: KBTopicItem?,
    userId: String,
    excludedEntryIds: Set<String>,
): List<KnowledgeMergeTargetOption> {
    val mergeTopics = knowledgeMergeTargetTopics(topics, sourceTopic)
    if (mergeTopics.isEmpty()) return emptyList()
    return mergeTopics.flatMap { topic ->
        ApiClient.getKBEntries(topic.id, userId)
            .filter { entry -> entry.id !in excludedEntryIds && entry.status != KBEntryStatus.DELETED }
            .map { entry -> KnowledgeMergeTargetOption(entry = entry, topic = topic) }
    }.sortedWith(
        compareBy<KnowledgeMergeTargetOption> { it.topic.id != sourceTopic?.id }
            .thenBy { it.topic.name.lowercase() }
            .thenBy { it.entry.title.lowercase() }
    )
}

private suspend fun moveKnowledgeEntryToTopic(
    entry: KBEntryItem?,
    sourceTopic: KBTopicItem?,
    targetTopicId: String,
    topics: List<KBTopicItem>,
    userId: String,
    onSavingChange: (Boolean) -> Unit,
    onSaveMessageChange: (String) -> Unit,
    onTopicsChange: (List<KBTopicItem>) -> Unit,
    onSelectedTopicChange: (KBTopicItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onSelectedSpaceIdChange: (String) -> Unit,
    onDialogVisibilityChange: (Boolean) -> Unit,
) {
    if (entry == null || sourceTopic == null || targetTopicId.isBlank()) return
    val targetTopic = topics.find { it.id == targetTopicId } ?: return
    if (!canMoveKnowledgeEntryToTopic(sourceTopic, targetTopic)) return
    onSavingChange(true)
    onSaveMessageChange("")
    val updated = ApiClient.updateKBEntry(
        entryId = entry.id,
        topicId = targetTopic.id,
        title = null,
        content = null,
        tags = null,
        userId = userId,
    )
    if (updated != null) {
        val refreshedTopics = ApiClient.getKBTopics(userId)
        val refreshedEntries = ApiClient.getKBEntries(targetTopic.id, userId)
        val refreshedTargetTopic = refreshedTopics.find { it.id == targetTopic.id } ?: targetTopic
        val refreshedEntry = refreshedEntries.find { it.id == updated.id } ?: updated
        onTopicsChange(refreshedTopics)
        onSelectedTopicChange(refreshedTargetTopic)
        onEntriesChange(refreshedEntries)
        onSelectedEntryChange(refreshedEntry)
        onEditorContentChange(refreshedEntry.content)
        onSelectedSpaceIdChange(defaultKnowledgeSpaceIdForTopic(refreshedTargetTopic))
        onSaveMessageChange("已移动到主题“${refreshedTargetTopic.name}”")
        onDialogVisibilityChange(false)
    }
    onSavingChange(false)
}

private suspend fun deleteKnowledgeEntry(
    entry: KBEntryItem?,
    topic: KBTopicItem?,
    userId: String,
    onSavingChange: (Boolean) -> Unit,
    onSaveMessageChange: (String) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onDialogVisibilityChange: (Boolean) -> Unit,
) {
    if (entry == null || topic == null) return
    onSavingChange(true)
    onSaveMessageChange("")
    val response = ApiClient.deleteKBEntry(entry.id, userId)
    if (response.success) {
        val refreshedEntries = ApiClient.getKBEntries(topic.id, userId)
        val nextEntry = refreshedEntries.firstOrNull()
        onEntriesChange(refreshedEntries)
        onSelectedEntryChange(nextEntry)
        onEditorContentChange(nextEntry?.content.orEmpty())
        onSaveMessageChange("条目已删除")
        onDialogVisibilityChange(false)
    }
    onSavingChange(false)
}

private suspend fun deleteKnowledgeTopic(
    topic: KBTopicItem?,
    userId: String,
    onSavingChange: (Boolean) -> Unit,
    onSaveMessageChange: (String) -> Unit,
    onTopicsChange: (List<KBTopicItem>) -> Unit,
    onSelectedTopicChange: (KBTopicItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onSelectedSpaceIdChange: (String) -> Unit,
    onDialogVisibilityChange: (Boolean) -> Unit,
) {
    if (topic == null) return
    onSavingChange(true)
    onSaveMessageChange("")
    val response = ApiClient.deleteKBTopic(topic.id, userId)
    if (response.success) {
        val refreshedTopics = ApiClient.getKBTopics(userId)
        onTopicsChange(refreshedTopics)
        onSelectedTopicChange(null)
        onEntriesChange(emptyList())
        onSelectedEntryChange(null)
        onEditorContentChange("")
        onSelectedSpaceIdChange(defaultKnowledgeSpaceIdForTopic(null))
        onSaveMessageChange("主题已删除")
        onDialogVisibilityChange(false)
    }
    onSavingChange(false)
}

private suspend fun exportKnowledgeEntry(
    entry: KBEntryItem?,
    topic: KBTopicItem?,
    userId: String,
    onSaveMessageChange: (String) -> Unit,
) {
    if (entry == null) return
    val exported = ApiClient.exportKBEntry(entry.id, userId) ?: return
    if (ObsidianVaultManager.isSupported()) {
        recoverSuspendNonCancellation(
            block = {
                val handle = ObsidianVaultManager.getCachedHandleIfValid()
                    ?: ObsidianVaultManager.pickVaultDirectory()
                ObsidianVaultManager.saveToVault(
                    handle,
                    topic?.name ?: "General",
                    exported.markdown,
                    exported.fileName,
                )
                onSaveMessageChange("已导出到 Obsidian")
            },
            recover = { error ->
                console.error("Obsidian export failed:", error)
                downloadAsFile(exported.markdown, exported.fileName)
                onSaveMessageChange("已下载文件")
            },
        )
    } else {
        downloadAsFile(exported.markdown, exported.fileName)
        onSaveMessageChange("已下载文件")
    }
}

private suspend fun createKnowledgeTopic(
    topicName: String,
    topicProject: String,
    userId: String,
    spaceOptions: List<KnowledgeSpaceOption>,
    selectedSpaceId: String,
    onTopicsChange: (List<KBTopicItem>) -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
    onNameChange: (String) -> Unit,
    onProjectChange: (String) -> Unit,
) {
    if (topicName.isBlank()) return
    val selectedSpace = spaceOptions.find { it.id == selectedSpaceId }
        ?: KnowledgeSpaceOption(
            id = PERSONAL_SPACE_ID,
            label = "个人",
            type = KnowledgeSpaceType.PERSONAL,
        )
    ApiClient.createKBTopic(
        name = topicName.trim(),
        project = topicProject.trim(),
        userId = userId,
        spaceType = selectedSpace.type,
        groupId = selectedSpace.group?.id,
        accessPolicy = KBAccessPolicy(
            teamMembersCanWrite = selectedSpace.type == KnowledgeSpaceType.TEAM,
        ),
    )
    onTopicsChange(ApiClient.getKBTopics(userId))
    resetTopicDialog(onVisibilityChange, onNameChange, onProjectChange)
}

private suspend fun createKnowledgeEntry(
    topic: KBTopicItem?,
    entryTitle: String,
    userId: String,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
    onTitleChange: (String) -> Unit,
) {
    if (topic == null || entryTitle.isBlank()) return
    val entry = ApiClient.createKBEntry(topic.id, entryTitle.trim(), "", emptyList(), userId)
    if (entry != null) {
        onEntriesChange(ApiClient.getKBEntries(topic.id, userId))
        loadKnowledgeEntry(entry, onSelectedEntryChange, onEditorContentChange)
    }
    resetEntryDialog(onVisibilityChange, onTitleChange)
}

private fun defaultKnowledgeSpaceIdForTopic(topic: KBTopicItem?): String {
    return when (topic?.spaceType) {
        KnowledgeSpaceType.TEAM -> topic.groupId ?: PERSONAL_SPACE_ID
        else -> PERSONAL_SPACE_ID
    }
}

private suspend fun submitMeetingKnowledgeCapture(
    userId: String,
    topics: List<KBTopicItem>,
    selectedSpaceId: String,
    selectedTopicId: String,
    title: String,
    content: String,
    tagsText: String,
    status: KBEntryStatus,
    confidenceText: String,
    onSavingChange: (Boolean) -> Unit,
    onResultMessageChange: (String?) -> Unit,
    onSelectedSpaceIdChange: (String) -> Unit,
    onSelectedTopicChange: (KBTopicItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onEntryFilterChange: (KnowledgeEntryFilter) -> Unit,
    onSaveMessageChange: (String) -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
) {
    val topic = topics.find { it.id == selectedTopicId } ?: run {
        onResultMessageChange("请选择目标主题")
        return
    }
    onSavingChange(true)
    onResultMessageChange(null)
    val created = ApiClient.captureKBEntry(
        topicId = topic.id,
        title = title.trim(),
        content = content.trim(),
        tags = parseKnowledgeCaptureTags(tagsText),
        userId = userId,
        source = buildMeetingCaptureSource(topic, confidenceText),
        status = status,
    )
    if (created == null) {
        onSavingChange(false)
        onResultMessageChange("会议纪要入库失败")
        return
    }

    val refreshedEntries = ApiClient.getKBEntries(topic.id, userId)
    val selectedEntry = refreshedEntries.find { it.id == created.id } ?: created
    onSelectedSpaceIdChange(defaultKnowledgeSpaceIdForTopic(topic).ifBlank { selectedSpaceId })
    onSelectedTopicChange(topic)
    onEntriesChange(refreshedEntries)
    onSelectedEntryChange(selectedEntry)
    onEditorContentChange(selectedEntry.content)
    onEntryFilterChange(knowledgeFilterForStatus(selectedEntry.status))
    onSaveMessageChange(
        if (selectedEntry.status == KBEntryStatus.PUBLISHED) "会议纪要已发布到知识库"
        else "会议纪要已作为候选入库"
    )
    onSavingChange(false)
    onVisibilityChange(false)
}

private fun isPreviewableMimeType(mimeType: String): Boolean {
    return mimeType.startsWith("image/") ||
        mimeType.startsWith("audio/") ||
        mimeType.startsWith("video/") ||
        mimeType == "application/pdf"
}

private suspend fun runKnowledgeBaseCopilot(
    entry: KBEntryItem?,
    topic: KBTopicItem?,
    userId: String,
    instruction: String,
    applyChanges: Boolean,
    conversationHistory: List<ConversationTurn>,
    onRunningChange: (Boolean) -> Unit,
    onFeedbackChange: (String) -> Unit,
    onReplyChange: (String) -> Unit,
    onDraftChange: (KnowledgeBaseCopilotDraft?) -> Unit,
    onConversationHistoryChange: (List<ConversationTurn>) -> Unit,
    onInstructionChange: (String) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onEditorTitleChange: (String) -> Unit,
    onEditorContentChange: (String) -> Unit,
) {
    val normalizedInstruction = instruction.trim()
    if (normalizedInstruction.isBlank()) {
        onFeedbackChange("请先输入要让 AI 执行的编辑要求")
        return
    }
    onRunningChange(true)
    onFeedbackChange("")
    onReplyChange("")

    // Use SSE streaming for real-time typing effect
    var collectedReply = ""
    var finalDraft: KnowledgeBaseCopilotDraft? = null
    var finalAppliedEntry: KBEntryItem? = null
    var success = false
    var message = ""

    val response = ApiClient.streamKBCopilot(
        userId = userId,
        entryId = entry?.id,
        topicId = topic?.id,
        instruction = normalizedInstruction,
        applyChanges = applyChanges,
        conversationHistory = conversationHistory,
        onEvent = { event, data ->
            when (event) {
                "thinking" -> {
                    onFeedbackChange("AI 正在思考…")
                }
                "text" -> {
                    collectedReply += data
                    onReplyChange(collectedReply)
                }
                "draft" -> {
                    try {
                        val parsed = Json.decodeFromString<KnowledgeBaseCopilotDraft>(data)
                        finalDraft = parsed
                        onDraftChange(parsed)
                        onFeedbackChange("已生成草稿，可继续修改或应用到编辑器")
                    } catch (e: Exception) {
                        console.log("解析流式 draft 失败:", e)
                    }
                }
                "applied" -> {
                    try {
                        val parsed = Json.decodeFromString<KBEntryItem>(data)
                        finalAppliedEntry = parsed
                    } catch (e: Exception) {
                        console.log("解析流式 appliedEntry 失败:", e)
                    }
                }
                "error" -> {
                    message = data
                    onFeedbackChange(data)
                }
                "done" -> {
                    success = true
                }
            }
        },
    )

    if (response == null) {
        onRunningChange(false)
        onFeedbackChange("KB Copilot 调用失败")
        return
    }

    // Build conversation history from the streamed response
    val assistantContent = collectedReply.ifBlank { response.assistantReply.ifBlank { response.message } }
    val updatedHistory = conversationHistory + listOf(
        ConversationTurn(role = "user", content = normalizedInstruction),
        ConversationTurn(role = "assistant", content = assistantContent),
    )
    onConversationHistoryChange(updatedHistory)
    onInstructionChange("")

    // Use result from SSE events or fall back to response object
    val effectiveDraft = finalDraft ?: response.draft
    val effectiveApplied = finalAppliedEntry ?: response.appliedEntry
    val effectiveSuccess = success || response.success
    val effectiveMessage = message.ifBlank { response.message }

    onDraftChange(effectiveDraft)
    if (effectiveSuccess && effectiveApplied != null) {
        onSelectedEntryChange(effectiveApplied)
        onEntriesChange(ApiClient.getKBEntries(effectiveApplied.topicId, userId))
        onEditorTitleChange(effectiveApplied.title)
        onEditorContentChange(effectiveApplied.content)
    }
    onFeedbackChange(effectiveMessage.ifBlank {
        if (effectiveSuccess) "KB Copilot 已生成草稿，可继续修改" else "KB Copilot 未生成可用草稿"
    })
    onRunningChange(false)
}

@Composable
@Suppress("CyclomaticComplexMethod")
fun KnowledgeBaseScene(appState: WebAppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()

    var userGroups by remember(user.id) { mutableStateOf<List<Group>>(emptyList()) }
    var topics by remember { mutableStateOf<List<KBTopicItem>>(emptyList()) }
    var selectedTopic by remember { mutableStateOf<KBTopicItem?>(null) }
    var entries by remember { mutableStateOf<List<KBEntryItem>>(emptyList()) }
    var selectedEntry by remember { mutableStateOf<KBEntryItem?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedSpaceId by remember(user.id) { mutableStateOf(PERSONAL_SPACE_ID) }
    var topicSearchQuery by remember { mutableStateOf("") }
    var entrySearchQuery by remember(selectedTopic?.id) { mutableStateOf("") }
    var memoryPreferences by remember(user.id) { mutableStateOf(KnowledgeBaseContextPreferences(userId = user.id)) }
    var memoryEntries by remember(user.id) { mutableStateOf<List<KBEntryItem>>(emptyList()) }
    var groupMemoryEntries by remember(user.id) { mutableStateOf<List<KBEntryItem>>(emptyList()) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var isMemoryLoading by remember { mutableStateOf(false) }
    var isMemorySaving by remember { mutableStateOf(false) }
    var memoryDraftTitle by remember { mutableStateOf("") }
    var memoryDraftContent by remember { mutableStateOf("") }
    var memoryDraftType by remember { mutableStateOf(KBMemoryType.PREFERENCE) }
    var memoryFeedback by remember { mutableStateOf("") }
    // "personal" or "group"; only relevant when selectedSpaceId is a group space
    var memoryActiveTab by remember { mutableStateOf("personal") }
    // Group files view for team spaces (shown as a special topic in sidebar)
    var showGroupFilesView by remember(selectedSpaceId) { mutableStateOf(false) }
    var groupFilesResponse by remember { mutableStateOf<ApiClient.GroupAssetsResponse?>(null) }
    var isGroupFilesLoading by remember { mutableStateOf(false) }
    var showCreateEntryFromFileDialog by remember { mutableStateOf(false) }
    var createEntryFromFileFile by remember { mutableStateOf<ApiClient.GroupAssetFile?>(null) }
    var createEntryFromFileTopicId by remember { mutableStateOf("") }
    // File preview overlay state
    var previewFile by remember { mutableStateOf<ApiClient.GroupAssetFile?>(null) }

    var showCreateTopicDialog by remember { mutableStateOf(false) }
    var newTopicName by remember { mutableStateOf("") }
    var newTopicProject by remember { mutableStateOf("") }
    var newTopicSpaceId by remember { mutableStateOf(PERSONAL_SPACE_ID) }

    var showCreateEntryDialog by remember { mutableStateOf(false) }
    var newEntryTitle by remember { mutableStateOf("") }
    var showMeetingCaptureDialog by remember { mutableStateOf(false) }
    var meetingCaptureSpaceId by remember { mutableStateOf(PERSONAL_SPACE_ID) }
    var meetingCaptureTopicId by remember { mutableStateOf("") }
    var meetingCaptureTitle by remember { mutableStateOf("") }
    var meetingCaptureContent by remember { mutableStateOf("") }
    var meetingCaptureTagsText by remember { mutableStateOf("meeting, minutes") }
    var meetingCaptureStatus by remember { mutableStateOf(KBEntryStatus.CANDIDATE) }
    var meetingCaptureConfidenceText by remember { mutableStateOf("0.90") }
    var isMeetingCaptureSaving by remember { mutableStateOf(false) }
    var meetingCaptureResultMessage by remember { mutableStateOf<String?>(null) }
    var selectedCandidateEntryIds by remember(selectedTopic?.id) { mutableStateOf<Set<String>>(emptySet()) }
    var showMergeCandidateDialog by remember { mutableStateOf(false) }
    var mergeTargetEntryId by remember { mutableStateOf("") }
    var isMergeCandidateSaving by remember { mutableStateOf(false) }
    var showBatchMergeCandidatesDialog by remember { mutableStateOf(false) }
    var batchMergeTargetEntryId by remember { mutableStateOf("") }
    var isBatchMergeSaving by remember { mutableStateOf(false) }
    var mergeTargetOptions by remember(selectedTopic?.id) { mutableStateOf<List<KnowledgeMergeTargetOption>>(emptyList()) }
    var isMergeTargetsLoading by remember(selectedTopic?.id) { mutableStateOf(false) }
    var showCopilotDialog by remember { mutableStateOf(false) }
    var showCopilotSidebar by remember { mutableStateOf(false) }
    var copilotSidebarWidth by remember(user.id) {
        mutableStateOf(
            parseStoredKnowledgeSidebarWidth(
                raw = localStorage.getItem(KNOWLEDGE_COPILOT_SIDEBAR_WIDTH_KEY),
                defaultWidth = KNOWLEDGE_COPILOT_SIDEBAR_DEFAULT_WIDTH,
            )
        )
    }
    var copilotInstruction by remember(selectedEntry?.id) { mutableStateOf(KNOWLEDGE_COPILOT_DEFAULT_PROMPT) }
    var isCopilotRunning by remember { mutableStateOf(false) }
    var copilotFeedback by remember { mutableStateOf("") }
    var copilotReply by remember { mutableStateOf("") }
    var copilotDraft by remember { mutableStateOf<KnowledgeBaseCopilotDraft?>(null) }
    var copilotConversationHistory by remember { mutableStateOf<List<ConversationTurn>>(emptyList()) }
    // Diff review mode states
    var showDiffReview by remember { mutableStateOf(false) }
    var diffChunks by remember { mutableStateOf<List<DiffChunk>>(emptyList()) }
    var acceptedChunkIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var rejectedChunkIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var diffOriginalContent by remember { mutableStateOf("") }
    var diffTargetTitle by remember { mutableStateOf("") }
    // Derive sidebar state from draft and diff review
    val effectiveSidebarState: CopilotSidebarState = when {
        showDiffReview -> CopilotSidebarState.REVIEW
        copilotDraft != null -> CopilotSidebarState.PREVIEW
        else -> CopilotSidebarState.INPUT
    }
    var activeDragPayload by remember { mutableStateOf<KnowledgeEntryDragPayload?>(null) }
    var activeDropTopicId by remember { mutableStateOf<String?>(null) }
    var showTopicManageMode by remember(selectedSpaceId) { mutableStateOf(false) }
    var showMoveEntryDialog by remember { mutableStateOf(false) }
    var moveTargetTopicId by remember { mutableStateOf("") }
    var isMoveEntrySaving by remember { mutableStateOf(false) }
    var showDeleteEntryDialog by remember { mutableStateOf(false) }
    var isDeleteEntrySaving by remember { mutableStateOf(false) }
    var showDeleteTopicDialog by remember { mutableStateOf(false) }
    var isDeleteTopicSaving by remember { mutableStateOf(false) }
    var showTopicAccessDialog by remember { mutableStateOf(false) }
    var topicsSidebarCollapsed by remember { mutableStateOf(LayoutPrefs.getBool("kb_sidebar_collapsed", false)) }
    var entrySidebarCollapsed by remember { mutableStateOf(LayoutPrefs.getBool("kb_entry_sidebar_collapsed", false)) }
    var editableTopicName by remember { mutableStateOf("") }
    var editableTopicProject by remember { mutableStateOf("") }
    var editableReadUserIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var editableWriteUserIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var editableManageUserIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var editableWriteLocked by remember { mutableStateOf(false) }
    var editableTeamMembersCanWrite by remember { mutableStateOf(true) }

    var editorContent by remember { mutableStateOf("") }
    var editorTitle by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("") }
    var editorMode by remember { mutableStateOf(KnowledgeEditorMode.SPLIT) }
    var topicSidebarWidth by remember(user.id) {
        mutableStateOf(
            parseStoredKnowledgeSidebarWidth(
                raw = localStorage.getItem(KNOWLEDGE_TOPIC_SIDEBAR_WIDTH_KEY),
                defaultWidth = KNOWLEDGE_TOPIC_SIDEBAR_DEFAULT_WIDTH,
            )
        )
    }
    var entrySidebarWidth by remember(user.id) {
        mutableStateOf(
            parseStoredKnowledgeSidebarWidth(
                raw = localStorage.getItem(KNOWLEDGE_ENTRY_SIDEBAR_WIDTH_KEY),
                defaultWidth = KNOWLEDGE_ENTRY_SIDEBAR_DEFAULT_WIDTH,
            )
        )
    }
    var editorSplitRatio by remember(user.id) {
        mutableStateOf(
            parseStoredKnowledgeEditorSplitRatio(
                raw = localStorage.getItem(KNOWLEDGE_EDITOR_SPLIT_RATIO_KEY),
                defaultRatio = KNOWLEDGE_EDITOR_SPLIT_DEFAULT_RATIO,
            )
        )
    }
    var entryFilter by remember { mutableStateOf(KnowledgeEntryFilter.ALL) }

    LaunchedEffect(user.id) {
        isLoading = true
        val groupsResponse = ApiClient.getUserGroups(user.id)
        userGroups = (groupsResponse.groups ?: emptyList()).filterNot { it.name.startsWith("wf_") }
        topics = ApiClient.getKBTopics(user.id)
        memoryPreferences = ApiClient.getKBContextPreferences(user.id)
        isLoading = false
    }

    val spaceOptions = remember(userGroups) { buildKnowledgeSpaceOptions(userGroups) }
    val filteredTopics = remember(topics, selectedSpaceId, topicSearchQuery) {
        filterTopicsByQuery(
            topics = filterTopicsForSpace(topics, selectedSpaceId),
            query = topicSearchQuery,
        )
    }
    val filteredEntries = remember(entries, entryFilter, entrySearchQuery) {
        filterKnowledgeEntriesByQuery(
            entries = filterKnowledgeEntries(entries, entryFilter),
            query = entrySearchQuery,
        )
    }
    val canEditSelectedTopic = selectedTopic?.let { canWriteKnowledgeTopic(it, user.id, userGroups) } ?: false
    val canManageSelectedTopic = selectedTopic?.let { canManageKnowledgeTopic(it, user.id, userGroups) } ?: false
    val selectedTopicSpaceLabel = selectedTopic?.let { topicSpaceLabel(it, userGroups) }
    val selectedTopicPermissionLabel = selectedTopic?.let { topicPermissionLabel(it, user.id, userGroups) }
    val selectedEntryStatusAction = selectedEntry?.let(::knowledgeStatusAction)
    val moveTargetTopics = remember(topics, selectedTopic?.id) {
        knowledgeMoveTargetTopics(topics, selectedTopic)
    }
    val batchMergeCandidateEntries = remember(entries, selectedCandidateEntryIds) {
        entries.filter { it.id in selectedCandidateEntryIds && it.status == KBEntryStatus.CANDIDATE }
    }

    LaunchedEffect(selectedSpaceId, topics) {
        if (selectedTopic != null && filteredTopics.none { it.id == selectedTopic?.id }) {
            selectedTopic = null
            selectedEntry = null
            editorTitle = ""
            entries = emptyList()
            editorContent = ""
        }
    }

    LaunchedEffect(selectedEntry?.id) {
        editorTitle = selectedEntry?.title.orEmpty()
        // Close copilot sidebar when switching to a different entry
        showCopilotSidebar = false
    }

    LaunchedEffect(entries, entryFilter, selectedTopic?.id) {
        val validCandidateIds = entries
            .filter { it.status == KBEntryStatus.CANDIDATE }
            .map { it.id }
            .toSet()
        selectedCandidateEntryIds = selectedCandidateEntryIds.intersect(validCandidateIds)
        if (entryFilter != KnowledgeEntryFilter.CANDIDATE && selectedCandidateEntryIds.isNotEmpty()) {
            selectedCandidateEntryIds = emptySet()
        }
        if (showMergeCandidateDialog && mergeTargetOptions.none { it.entry.id == mergeTargetEntryId }) {
            mergeTargetEntryId = mergeTargetOptions.firstOrNull()?.entry?.id.orEmpty()
        }
        if (showBatchMergeCandidatesDialog && mergeTargetOptions.none { it.entry.id == batchMergeTargetEntryId }) {
            batchMergeTargetEntryId = mergeTargetOptions.firstOrNull()?.entry?.id.orEmpty()
        }
        if (showMoveEntryDialog && moveTargetTopics.none { it.id == moveTargetTopicId }) {
            moveTargetTopicId = moveTargetTopics.firstOrNull()?.id.orEmpty()
        }
    }

    val kbNavigationTarget = appState.knowledgeBaseNavigationTarget
    LaunchedEffect(user.id, kbNavigationTarget?.requestId) {
        val target = kbNavigationTarget ?: return@LaunchedEffect
        val handled = navigateToKnowledgeBaseTarget(
            target = target,
            userId = user.id,
            existingTopics = topics,
            onTopicsChange = { topics = it },
            onSelectedTopicChange = { selectedTopic = it },
            onEntriesChange = { entries = it },
            onSelectedEntryChange = { selectedEntry = it },
            onEditorContentChange = { editorContent = it },
        )
        if (handled) {
            selectedTopic?.let { topic ->
                selectedSpaceId = if (topic.spaceType == KnowledgeSpaceType.PERSONAL) {
                    PERSONAL_SPACE_ID
                } else {
                    topic.groupId ?: PERSONAL_SPACE_ID
                }
            }
            saveMessage = "已打开引用文档"
        }
        appState.consumeKnowledgeBaseNavigationTarget(target.requestId)
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            height(100.percent)
            width(100.percent)
            property("overflow-x", "auto")
            property("overflow-y", "hidden")
            property("background", SilkColors.backgroundGradient)
        }
    }) {
        if (topicsSidebarCollapsed) {
            ReopenBar(onExpand = {
                topicsSidebarCollapsed = false
                LayoutPrefs.setBool("kb_sidebar_collapsed", false)
            })
        } else {
            TopicSidebar(
                widthPx = topicSidebarWidth,
                spaceOptions = spaceOptions,
                selectedSpaceId = selectedSpaceId,
                searchQuery = topicSearchQuery,
                topics = filteredTopics,
                isLoading = isLoading,
                selectedTopic = selectedTopic,
                isManageMode = showTopicManageMode,
                memoryPreferences = memoryPreferences,
                showGroupFilesView = showGroupFilesView,
                isTeamSpace = selectedSpaceId != PERSONAL_SPACE_ID,
                onCollapse = {
                    topicsSidebarCollapsed = true
                    LayoutPrefs.setBool("kb_sidebar_collapsed", true)
                },
                onGroupFilesSelect = {
                    val isNowGroupFiles = !showGroupFilesView
                    showGroupFilesView = isNowGroupFiles
                    if (isNowGroupFiles && selectedSpaceId != PERSONAL_SPACE_ID) {
                        scope.launch {
                            isGroupFilesLoading = true
                            groupFilesResponse = ApiClient.getGroupAssets(selectedSpaceId, user.id)
                            isGroupFilesLoading = false
                        }
                    }
                },
                onCreateTopic = {
                    newTopicSpaceId = selectedSpaceId
                    showCreateTopicDialog = true
                },
                onToggleManageMode = { showTopicManageMode = !showTopicManageMode },
                onManageMemory = {
                    showMemoryDialog = true
                    memoryActiveTab = if (selectedSpaceId != PERSONAL_SPACE_ID) "group" else "personal"
                    scope.launch {
                        isMemoryLoading = true
                        memoryFeedback = ""
                        memoryPreferences = ApiClient.getKBContextPreferences(user.id)
                        memoryEntries = sortKnowledgeMemoryEntries(ApiClient.listKBMemoryEntries(user.id))
                        val groupId = selectedSpaceId.takeIf { it != PERSONAL_SPACE_ID }
                        groupMemoryEntries = if (groupId != null) {
                            sortKnowledgeMemoryEntries(ApiClient.listKBMemoryEntries(user.id, groupId = groupId))
                        } else {
                            emptyList()
                        }
                        isMemoryLoading = false
                    }
                },
                onSearchQueryChange = { topicSearchQuery = it },
                onSpaceSelect = { selectedSpace ->
                    selectedSpaceId = selectedSpace.id
                    showGroupFilesView = false
                    groupFilesResponse = null
                },
                userId = user.id,
                groups = userGroups,
                onManageTopic = { topic ->
                    editableTopicName = topic.name
                    editableTopicProject = topic.project
                    editableReadUserIds = topic.accessPolicy.readUserIds.toList()
                    editableWriteUserIds = topic.accessPolicy.writeUserIds.toList()
                    editableManageUserIds = topic.accessPolicy.manageUserIds.toList()
                    editableWriteLocked = topic.accessPolicy.writeLocked
                    editableTeamMembersCanWrite = topic.accessPolicy.teamMembersCanWrite
                    selectedTopic = topic
                    showTopicAccessDialog = true
                },
                onDeleteTopic = { topic ->
                    selectedTopic = topic
                    showDeleteTopicDialog = true
                },
                activeDragPayload = activeDragPayload,
                activeDropTopicId = activeDropTopicId,
                onTopicSelect = { topic ->
                    showGroupFilesView = false
                    groupFilesResponse = null
                    scope.launch {
                        loadKnowledgeEntries(
                            topic = topic,
                            userId = user.id,
                            onSelectedTopicChange = { selectedTopic = it },
                            onSelectedEntryChange = { selectedEntry = it },
                            onEditorContentChange = { editorContent = it },
                            onEntriesChange = { entries = it },
                        )
                    }
                },
                onEntryDragHoverTopicChange = { topicId ->
                    activeDropTopicId = topicId
                },
                onEntryDropToTopic = { targetTopicId ->
                    val dragPayload = activeDragPayload
                    val draggedEntry = entries.find { it.id == dragPayload?.entryId }
                    val sourceTopic = selectedTopic
                    activeDropTopicId = null
                    activeDragPayload = null
                    if (draggedEntry != null && sourceTopic != null) {
                        scope.launch {
                            moveKnowledgeEntryToTopic(
                                entry = draggedEntry,
                                sourceTopic = sourceTopic,
                                targetTopicId = targetTopicId,
                                topics = topics,
                                userId = user.id,
                                onSavingChange = { isMoveEntrySaving = it },
                            onSaveMessageChange = { saveMessage = it },
                            onTopicsChange = { topics = it },
                            onSelectedTopicChange = { selectedTopic = it },
                            onEntriesChange = { entries = it },
                            onSelectedEntryChange = { selectedEntry = it },
                            onEditorContentChange = { editorContent = it },
                            onSelectedSpaceIdChange = { selectedSpaceId = it },
                            onDialogVisibilityChange = { showMoveEntryDialog = it },
                        )
                    }
                }
            },
        )
        KnowledgeHorizontalResizeHandle(storageHint = "topic-sidebar") { deltaPx ->
            topicSidebarWidth = clampKnowledgeSidebarWidth(topicSidebarWidth + deltaPx)
            persistKnowledgePaneNumber(KNOWLEDGE_TOPIC_SIDEBAR_WIDTH_KEY, topicSidebarWidth)
        }
        }
        if (entrySidebarCollapsed) {
            ReopenBar(onExpand = {
                entrySidebarCollapsed = false
                LayoutPrefs.setBool("kb_entry_sidebar_collapsed", false)
            })
        } else if (showGroupFilesView) {
            GroupFilesContent(
                groupFilesResponse = groupFilesResponse,
                isLoading = isGroupFilesLoading,
                widthPx = entrySidebarWidth,
                onCreateEntryFromFile = { file ->
                    createEntryFromFileFile = file
                    createEntryFromFileTopicId = filteredTopics.firstOrNull()?.id.orEmpty()
                    showCreateEntryFromFileDialog = true
                },
                onPreviewFile = { file -> previewFile = file },
            )
        } else {
            EntrySidebar(
                widthPx = entrySidebarWidth,
                selectedTopic = selectedTopic,
                searchQuery = entrySearchQuery,
                entries = filteredEntries,
                selectedEntry = selectedEntry,
                selectedFilter = entryFilter,
                canCreateEntry = canEditSelectedTopic,
                canDragEntries = canEditSelectedTopic && selectedTopic != null && moveTargetTopics.isNotEmpty(),
                selectedCandidateEntryIds = selectedCandidateEntryIds,
                canBatchMergeCandidates = selectedCandidateEntryIds.isNotEmpty(),
                onCollapse = {
                    entrySidebarCollapsed = true
                    LayoutPrefs.setBool("kb_entry_sidebar_collapsed", true)
                },
                onFilterChange = { entryFilter = it },
                onSearchQueryChange = { entrySearchQuery = it },
                onCreateEntry = { showCreateEntryDialog = true },
                onMeetingCapture = {
                    val topic = selectedTopic ?: return@EntrySidebar
                    meetingCaptureSpaceId = defaultKnowledgeSpaceIdForTopic(topic)
                    meetingCaptureTopicId = topic.id
                    meetingCaptureTitle = buildDefaultMeetingCaptureTitle(topic)
                    meetingCaptureContent = ""
                    meetingCaptureTagsText = "meeting, minutes"
                    meetingCaptureStatus = KBEntryStatus.CANDIDATE
                    meetingCaptureConfidenceText = "0.90"
                    meetingCaptureResultMessage = null
                    showMeetingCaptureDialog = true
                },
                onToggleSelectAllCandidates = {
                    selectedCandidateEntryIds =
                        if (selectedCandidateEntryIds.size == filteredEntries.size) emptySet()
                        else filteredEntries.map { it.id }.toSet()
                },
                onBatchMergeCandidates = {
                    showBatchMergeCandidatesDialog = true
                    scope.launch {
                        isMergeTargetsLoading = true
                        mergeTargetOptions = loadKnowledgeMergeTargetOptions(
                            topics = topics,
                            sourceTopic = selectedTopic,
                            userId = user.id,
                            excludedEntryIds = selectedCandidateEntryIds,
                        )
                        batchMergeTargetEntryId = mergeTargetOptions.firstOrNull()?.entry?.id.orEmpty()
                        isMergeTargetsLoading = false
                    }
                },
                onBatchPublishCandidates = {
                    scope.launch {
                        bulkUpdateKnowledgeEntryStatus(
                            entryIds = selectedCandidateEntryIds,
                            topic = selectedTopic,
                            userId = user.id,
                            status = KBEntryStatus.PUBLISHED,
                            currentSelectedEntryId = selectedEntry?.id,
                            onSavingChange = { isSaving = it },
                            onSaveMessageChange = { saveMessage = it },
                            onSelectedEntryChange = { selectedEntry = it },
                            onEntriesChange = { entries = it },
                            onEditorContentChange = { editorContent = it },
                            onEntryFilterChange = { entryFilter = it },
                            onSelectedCandidateEntryIdsChange = { selectedCandidateEntryIds = it },
                        )
                    }
                },
                onBatchArchiveCandidates = {
                    scope.launch {
                        bulkUpdateKnowledgeEntryStatus(
                            entryIds = selectedCandidateEntryIds,
                            topic = selectedTopic,
                            userId = user.id,
                            status = KBEntryStatus.ARCHIVED,
                            currentSelectedEntryId = selectedEntry?.id,
                            onSavingChange = { isSaving = it },
                            onSaveMessageChange = { saveMessage = it },
                            onSelectedEntryChange = { selectedEntry = it },
                            onEntriesChange = { entries = it },
                            onEditorContentChange = { editorContent = it },
                            onEntryFilterChange = { entryFilter = it },
                            onSelectedCandidateEntryIdsChange = { selectedCandidateEntryIds = it },
                        )
                    }
                },
                onToggleCandidateSelection = { entryId ->
                    selectedCandidateEntryIds = toggleKnowledgeEntrySelection(selectedCandidateEntryIds, entryId)
                },
                onEntrySelect = { entry ->
                    loadKnowledgeEntry(
                        entry = entry,
                        onSelectedEntryChange = { selectedEntry = it },
                        onEditorContentChange = { editorContent = it },
                    )
                },
                onEntryDragStart = { entry ->
                    selectedTopic?.let { sourceTopic ->
                        activeDragPayload = KnowledgeEntryDragPayload(
                            entryId = entry.id,
                            sourceTopicId = sourceTopic.id,
                        )
                    }
                },
                onEntryDragEnd = {
                    activeDragPayload = null
                    activeDropTopicId = null
                },
            )
        }
        if (!entrySidebarCollapsed) {
            KnowledgeHorizontalResizeHandle(storageHint = "entry-sidebar") { deltaPx ->
                entrySidebarWidth = clampKnowledgeSidebarWidth(entrySidebarWidth + deltaPx)
                persistKnowledgePaneNumber(KNOWLEDGE_ENTRY_SIDEBAR_WIDTH_KEY, entrySidebarWidth)
            }
        }
        val effectiveEntrySidebarWidth = if (entrySidebarCollapsed) 0.0 else entrySidebarWidth
        val effectiveTopicSidebarWidth = if (topicsSidebarCollapsed) 0.0 else topicSidebarWidth
        val baseEditorWidth = window.innerWidth.toDouble() - effectiveTopicSidebarWidth - effectiveEntrySidebarWidth - 20.0
        // Copilot sidebar + resize handle (10px) + gap (8px)
        val copilotSidebarOffset = if (showCopilotSidebar) (copilotSidebarWidth + 18.0) else 0.0

        // Determine copilot mode: entry-level or topic-level
        val currentCopilotMode = when {
            selectedEntry != null && canEditSelectedTopic -> CopilotMode.ENTRY
            selectedTopic != null && canEditSelectedTopic -> CopilotMode.TOPIC
            else -> null
        }

        Div({
            style {
                property("flex", "1")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Row)
                minWidth(320.px)
                property("min-height", "0")
                property("overflow", "hidden")
            }
        }) {
            if (showDiffReview) {
                // Diff review mode: show diff chunks without action buttons (controlled from sidebar)
                DiffReviewPane(
                    chunks = diffChunks,
                    accepted = acceptedChunkIndices,
                    rejected = rejectedChunkIndices,
                    onAcceptChunk = { index ->
                        acceptedChunkIndices = acceptedChunkIndices + index
                        rejectedChunkIndices = rejectedChunkIndices - index
                    },
                    onRejectChunk = { index ->
                        rejectedChunkIndices = rejectedChunkIndices + index
                        acceptedChunkIndices = acceptedChunkIndices - index
                    },
                )
            } else {
                KnowledgeEditorPane(
                    selectedTopic = selectedTopic,
                    selectedEntry = selectedEntry,
                    editorTitle = editorTitle,
                    savedTitle = selectedEntry?.title.orEmpty(),
                    editorContent = editorContent,
                    isSaving = isSaving,
                    saveMessage = saveMessage,
                    editorMode = editorMode,
                    editorSplitRatio = editorSplitRatio,
                    availableEditorWidthPx = baseEditorWidth - copilotSidebarOffset,
                canEdit = canEditSelectedTopic,
                spaceLabel = selectedTopicSpaceLabel,
                permissionLabel = selectedTopicPermissionLabel,
                currentUserId = user.id,
                groups = userGroups,
                onOpenSourceGroup = { group -> appState.openChatGroup(group) },
                onOpenSourceWorkflow = { workflowId -> appState.openWorkflow(workflowId) },
                onOpenSourceMessage = { jump ->
                    when (jump.kind) {
                        KnowledgeSourceMessageJumpKind.CHAT -> {
                            userGroups.find { it.id == jump.targetId }?.let { group ->
                                appState.openChatGroup(group = group, messageId = jump.messageId)
                            }
                        }
                        KnowledgeSourceMessageJumpKind.WORKFLOW -> {
                            appState.openWorkflow(workflowId = jump.targetId, messageId = jump.messageId)
                        }
                    }
                },
                onTitleChange = {
                    editorTitle = it
                    if (saveMessage.isNotEmpty()) {
                        saveMessage = ""
                    }
                },
                onResetTitle = {
                    editorTitle = selectedEntry?.title.orEmpty()
                    if (saveMessage.isNotEmpty()) {
                        saveMessage = ""
                    }
                },
                onContentChange = {
                    editorContent = it
                    if (saveMessage.isNotEmpty()) {
                        saveMessage = ""
                    }
                },
                onEditorModeChange = { editorMode = it },
                onEditorSplitRatioChange = { nextRatio ->
                    editorSplitRatio = clampKnowledgeEditorSplitRatio(nextRatio)
                    persistKnowledgePaneNumber(KNOWLEDGE_EDITOR_SPLIT_RATIO_KEY, editorSplitRatio)
                },
                onMoveEntry = selectedEntry
                    ?.takeIf { canEditSelectedTopic && moveTargetTopics.isNotEmpty() }
                    ?.let {
                        {
                            moveTargetTopicId = moveTargetTopics.firstOrNull()?.id.orEmpty()
                            showMoveEntryDialog = true
                        }
                    },
                onDeleteEntry = selectedEntry
                    ?.takeIf { canManageSelectedTopic }
                    ?.let { { showDeleteEntryDialog = true } },
                onOpenCopilot = currentCopilotMode?.let { mode ->
                    {
                        val willShow = !showCopilotSidebar
                        if (willShow) {
                            // Reset state when opening for a new entry/topic
                            copilotInstruction = KNOWLEDGE_COPILOT_DEFAULT_PROMPT
                            copilotFeedback = ""
                            copilotReply = ""
                            copilotDraft = null
                            copilotConversationHistory = emptyList()
                            showDiffReview = false
                            diffChunks = emptyList()
                            acceptedChunkIndices = emptySet()
                            rejectedChunkIndices = emptySet()
                        }
                        showCopilotSidebar = willShow
                    }
                },
                onSave = {
                    scope.launch {
                        saveKnowledgeEntry(
                            entry = selectedEntry,
                            topic = selectedTopic,
                            editorTitle = editorTitle,
                            editorContent = editorContent,
                            userId = user.id,
                            onSavingChange = { isSaving = it },
                            onSaveMessageChange = { saveMessage = it },
                            onSelectedEntryChange = { selectedEntry = it },
                            onEditorTitleChange = { editorTitle = it },
                            onEntriesChange = { entries = it },
                        )
                    }
                },
                onStatusAction = selectedEntryStatusAction?.let { (_, targetStatus) ->
                    {
                        entryFilter = knowledgeFilterForStatus(targetStatus)
                        scope.launch {
                            updateKnowledgeEntryStatus(
                                entry = selectedEntry,
                                topic = selectedTopic,
                                userId = user.id,
                                status = targetStatus,
                                onSavingChange = { isSaving = it },
                                onSaveMessageChange = { saveMessage = it },
                                onSelectedEntryChange = { selectedEntry = it },
                                onEntriesChange = { entries = it },
                            )
                        }
                    }
                },
                statusActionLabel = selectedEntryStatusAction?.first,
                onMergeCandidate = selectedEntry
                    ?.takeIf { it.status == KBEntryStatus.CANDIDATE && canEditSelectedTopic }
                    ?.let {
                        {
                            showMergeCandidateDialog = true
                            scope.launch {
                                isMergeTargetsLoading = true
                                mergeTargetOptions = loadKnowledgeMergeTargetOptions(
                                    topics = topics,
                                    sourceTopic = selectedTopic,
                                    userId = user.id,
                                    excludedEntryIds = setOf(it.id),
                                )
                                mergeTargetEntryId = mergeTargetOptions.firstOrNull()?.entry?.id.orEmpty()
                                isMergeTargetsLoading = false
                            }
                        }
                    },
                onExport = {
                    scope.launch {
                        exportKnowledgeEntry(
                            entry = selectedEntry,
                            topic = selectedTopic,
                            userId = user.id,
                            onSaveMessageChange = { saveMessage = it },
                        )
                    }
                },
                onCopyReference = {
                    val entry = selectedEntry ?: return@KnowledgeEditorPane
                    copyTextToClipboard(buildKnowledgeBaseReference(entry))
                    saveMessage = "引用已复制"
                },
            )
            } // end of else block (showDiffReview = false -> show editor pane)
            // Render copilot sidebar at scene level for both entry and topic mode
            if (showCopilotSidebar && currentCopilotMode != null) {
                KnowledgeHorizontalResizeHandle(storageHint = "copilot-sidebar") { deltaPx ->
                    copilotSidebarWidth = clampKnowledgeCopilotSidebarWidth(copilotSidebarWidth - deltaPx)
                    persistKnowledgePaneNumber(KNOWLEDGE_COPILOT_SIDEBAR_WIDTH_KEY, copilotSidebarWidth)
                }
                KnowledgeCopilotSidebar(
                    entry = selectedEntry,
                    topic = selectedTopic,
                    instruction = copilotInstruction,
                    isRunning = isCopilotRunning,
                    feedbackMessage = copilotFeedback,
                    assistantReply = copilotReply,
                    draft = copilotDraft,
                    sidebarWidth = copilotSidebarWidth,
                    copilotMode = currentCopilotMode,
                    sidebarState = effectiveSidebarState,
                    diffChunks = diffChunks,
                    acceptedChunkIndices = acceptedChunkIndices,
                    rejectedChunkIndices = rejectedChunkIndices,
                    onInstructionChange = { copilotInstruction = it },
                    onRun = {
                        scope.launch {
                            runKnowledgeBaseCopilot(
                                entry = selectedEntry,
                                topic = selectedTopic,
                                userId = user.id,
                                instruction = copilotInstruction,
                                // Always use applyChanges=false: all drafts go through review
                                applyChanges = false,
                                conversationHistory = copilotConversationHistory,
                                onRunningChange = { isCopilotRunning = it },
                                onFeedbackChange = { copilotFeedback = it },
                                onReplyChange = { copilotReply = it },
                                onDraftChange = { copilotDraft = it },
                                onConversationHistoryChange = { copilotConversationHistory = it },
                                onInstructionChange = { copilotInstruction = it },
                                onSelectedEntryChange = { selectedEntry = it },
                                onEntriesChange = { entries = it },
                                onEditorTitleChange = { editorTitle = it },
                                onEditorContentChange = { editorContent = it },
                            )
                        }
                    },
                    onClose = { showCopilotSidebar = false },
                    onBackToInput = {
                        copilotDraft = null
                        copilotReply = ""
                        copilotFeedback = ""
                    },
                    onStartReview = {
                        copilotDraft?.let { draft ->
                            if (currentCopilotMode == CopilotMode.ENTRY && draft.diffChunks.isNotEmpty()) {
                                diffChunks = draft.diffChunks
                                diffOriginalContent = editorContent
                                diffTargetTitle = draft.title
                                acceptedChunkIndices = emptySet()
                                rejectedChunkIndices = emptySet()
                                showDiffReview = true
                            } else if (currentCopilotMode == CopilotMode.TOPIC) {
                                // Topic mode: create entry directly from draft
                                scope.launch {
                                    isSaving = true
                                    val newEntry = ApiClient.createKBEntry(
                                        topicId = draft.topicId,
                                        title = draft.title,
                                        content = draft.content,
                                        tags = draft.tags,
                                        userId = user.id,
                                    )
                                    if (newEntry != null) {
                                        saveMessage = "已创建条目《${newEntry.title}》"
                                        entries = ApiClient.getKBEntries(draft.topicId, user.id)
                                        selectedEntry = newEntry
                                        editorTitle = newEntry.title
                                        editorContent = newEntry.content
                                        showCopilotSidebar = false
                                    } else {
                                        saveMessage = "创建条目失败"
                                    }
                                    isSaving = false
                                }
                            } else {
                                // Fallback: fill draft into editor
                                editorTitle = draft.title
                                editorContent = draft.content
                                saveMessage = "已把 Copilot 草稿填入当前编辑器，确认后可继续保存"
                            }
                        }
                    },
                    onAcceptChunk = { index ->
                        acceptedChunkIndices = acceptedChunkIndices + index
                        rejectedChunkIndices = rejectedChunkIndices - index
                    },
                    onRejectChunk = { index ->
                        rejectedChunkIndices = rejectedChunkIndices + index
                        acceptedChunkIndices = acceptedChunkIndices - index
                    },
                    onAcceptAll = {
                        val allIndices = diffChunks.indices.toSet()
                        acceptedChunkIndices = allIndices
                        rejectedChunkIndices = emptySet()
                    },
                    onApplyChanges = {
                        val finalContent = buildFinalContentFromDiff(diffChunks, acceptedChunkIndices)
                        editorTitle = diffTargetTitle
                        editorContent = finalContent
                        saveMessage = "修改已应用"
                        showDiffReview = false
                        showCopilotSidebar = false
                        diffChunks = emptyList()
                        acceptedChunkIndices = emptySet()
                        rejectedChunkIndices = emptySet()
                        copilotDraft = null
                    },
                    onCancelReview = {
                        showDiffReview = false
                        diffChunks = emptyList()
                        acceptedChunkIndices = emptySet()
                        rejectedChunkIndices = emptySet()
                        saveMessage = "已取消修改"
                    },
                    onClearConversation = {
                        copilotConversationHistory = emptyList()
                        copilotInstruction = KNOWLEDGE_COPILOT_DEFAULT_PROMPT
                        copilotFeedback = ""
                        copilotReply = ""
                        copilotDraft = null
                    },
                )
            }
        }
    }

    if (showMemoryDialog) {
        val currentGroupId = selectedSpaceId.takeIf { it != PERSONAL_SPACE_ID }
        KnowledgeMemoryDialog(
            preferences = memoryPreferences,
            entries = memoryEntries,
            groupEntries = groupMemoryEntries,
            groupId = currentGroupId,
            activeTab = memoryActiveTab,
            isLoading = isMemoryLoading,
            isSaving = isMemorySaving,
            draftTitle = memoryDraftTitle,
            draftContent = memoryDraftContent,
            draftType = memoryDraftType,
            feedbackMessage = memoryFeedback,
            onDraftTitleChange = { memoryDraftTitle = it },
            onDraftContentChange = { memoryDraftContent = it },
            onDraftTypeChange = { memoryDraftType = it },
            onPreferencesChange = { memoryPreferences = it },
            onActiveTabChange = { memoryActiveTab = it },
            onDismiss = {
                if (!isMemorySaving) {
                    showMemoryDialog = false
                    memoryFeedback = ""
                }
            },
            onSavePreferences = {
                scope.launch {
                    isMemorySaving = true
                    val updated = ApiClient.updateKBContextPreferences(
                        userId = user.id,
                        excludedSpaceIds = memoryPreferences.excludedSpaceIds,
                        memoryEnabled = memoryPreferences.memoryEnabled,
                        autoCaptureEnabled = memoryPreferences.autoCaptureEnabled,
                        ephemeralSessionEnabled = memoryPreferences.ephemeralSessionEnabled,
                    )
                    memoryFeedback = if (updated != null) {
                        memoryPreferences = updated
                        "记忆设置已保存"
                    } else {
                        "记忆设置保存失败"
                    }
                    isMemorySaving = false
                }
            },
            onCreateMemory = {
                scope.launch {
                    val content = memoryDraftContent.trim()
                    if (content.isBlank()) {
                        memoryFeedback = "请先输入记忆内容"
                        return@launch
                    }
                    isMemorySaving = true
                    val isGroupMode = memoryActiveTab == "group" && currentGroupId != null
                    val created = ApiClient.createKBMemoryEntry(
                        userId = user.id,
                        content = content,
                        memoryType = memoryDraftType,
                        title = memoryDraftTitle.trim().takeIf { it.isNotBlank() },
                        groupId = if (isGroupMode) currentGroupId else null,
                    )
                    memoryFeedback = if (created != null) {
                        if (isGroupMode) {
                            groupMemoryEntries = sortKnowledgeMemoryEntries(listOf(created) + groupMemoryEntries.filterNot { it.id == created.id })
                        } else {
                            memoryEntries = sortKnowledgeMemoryEntries(listOf(created) + memoryEntries.filterNot { it.id == created.id })
                        }
                        memoryDraftTitle = ""
                        memoryDraftContent = ""
                        memoryDraftType = KBMemoryType.PREFERENCE
                        "记忆已写入"
                    } else {
                        "写入记忆失败"
                    }
                    isMemorySaving = false
                }
            },
            onDeleteMemory = { entryId ->
                scope.launch {
                    isMemorySaving = true
                    val isGroupMode = memoryActiveTab == "group" && currentGroupId != null
                    val deleted = ApiClient.deleteKBMemoryEntry(
                        entryId, user.id,
                        groupId = if (isGroupMode) currentGroupId else null,
                    )
                    memoryFeedback = if (deleted) {
                        if (isGroupMode) {
                            groupMemoryEntries = groupMemoryEntries.filterNot { it.id == entryId }
                        } else {
                            memoryEntries = memoryEntries.filterNot { it.id == entryId }
                        }
                        "记忆已删除"
                    } else {
                        "删除记忆失败"
                    }
                    isMemorySaving = false
                }
            },
        )
    }

    val activeCopilotEntry = selectedEntry
    if (showCopilotDialog && activeCopilotEntry != null) {
        // Use the new three-state flow: always applyChanges=false (all drafts go through review)
        KnowledgeCopilotDialog(
            entry = activeCopilotEntry,
            instruction = copilotInstruction,
            applyChanges = false,
            isRunning = isCopilotRunning,
            feedbackMessage = copilotFeedback,
            assistantReply = copilotReply,
            draft = copilotDraft,
            onInstructionChange = { copilotInstruction = it },
            onApplyChangesChange = {},
            onApplyDraftToEditor = {
                copilotDraft?.let { draft ->
                    if (draft.diffChunks.isNotEmpty()) {
                        diffChunks = draft.diffChunks
                        diffOriginalContent = editorContent
                        diffTargetTitle = draft.title
                        acceptedChunkIndices = emptySet()
                        rejectedChunkIndices = emptySet()
                        showDiffReview = true
                        showCopilotDialog = false
                    } else {
                        editorTitle = draft.title
                        editorContent = draft.content
                        saveMessage = "已把 Copilot 草稿填入当前编辑器，确认后可继续保存"
                    }
                }
            },
            onDismiss = {
                if (!isCopilotRunning) {
                    showCopilotDialog = false
                }
            },
            onRun = {
                scope.launch {
                    runKnowledgeBaseCopilot(
                        entry = activeCopilotEntry,
                        topic = selectedTopic,
                        userId = user.id,
                        instruction = copilotInstruction,
                        applyChanges = false,
                        conversationHistory = emptyList(),
                        onRunningChange = { isCopilotRunning = it },
                        onFeedbackChange = { copilotFeedback = it },
                        onReplyChange = { copilotReply = it },
                        onDraftChange = { copilotDraft = it },
                        onConversationHistoryChange = {},
                        onInstructionChange = { copilotInstruction = it },
                        onSelectedEntryChange = { selectedEntry = it },
                        onEntriesChange = { entries = it },
                        onEditorTitleChange = { editorTitle = it },
                        onEditorContentChange = { editorContent = it },
                    )
                }
            },
        )
    }

    if (showCreateTopicDialog) {
        CreateTopicDialog(
            topicName = newTopicName,
            topicProject = newTopicProject,
            spaceOptions = spaceOptions,
            selectedSpaceId = newTopicSpaceId,
            onTopicNameChange = { newTopicName = it },
            onTopicProjectChange = { newTopicProject = it },
            onSpaceChange = { newTopicSpaceId = it },
            onDismiss = {
                resetTopicDialog(
                    onVisibilityChange = { showCreateTopicDialog = it },
                    onNameChange = { newTopicName = it },
                    onProjectChange = { newTopicProject = it },
                )
            },
            onConfirm = {
                scope.launch {
                    createKnowledgeTopic(
                        topicName = newTopicName,
                        topicProject = newTopicProject,
                        userId = user.id,
                        spaceOptions = spaceOptions,
                        selectedSpaceId = newTopicSpaceId,
                        onTopicsChange = { topics = it },
                        onVisibilityChange = { showCreateTopicDialog = it },
                        onNameChange = { newTopicName = it },
                        onProjectChange = { newTopicProject = it },
                    )
                }
            },
        )
    }

    val accessDialogTopic = selectedTopic
    if (showTopicAccessDialog && accessDialogTopic != null) {
        TopicAccessDialog(
            topicName = editableTopicName,
            topicProject = editableTopicProject,
            isTeamTopic = accessDialogTopic.spaceType == KnowledgeSpaceType.TEAM,
            readUserIds = editableReadUserIds,
            writeUserIds = editableWriteUserIds,
            manageUserIds = editableManageUserIds,
            writeLocked = editableWriteLocked,
            teamMembersCanWrite = editableTeamMembersCanWrite,
            onTopicNameChange = { editableTopicName = it },
            onTopicProjectChange = { editableTopicProject = it },
            onReadUserIdsChange = { editableReadUserIds = it },
            onWriteUserIdsChange = { editableWriteUserIds = it },
            onManageUserIdsChange = { editableManageUserIds = it },
            onWriteLockedChange = { editableWriteLocked = it },
            onTeamMembersCanWriteChange = { editableTeamMembersCanWrite = it },
            onDismiss = {
                resetTopicAccessDialog(
                    onVisibilityChange = { showTopicAccessDialog = it },
                    onTopicNameChange = { editableTopicName = it },
                    onTopicProjectChange = { editableTopicProject = it },
                    onReadUserIdsChange = { editableReadUserIds = it },
                    onWriteUserIdsChange = { editableWriteUserIds = it },
                    onManageUserIdsChange = { editableManageUserIds = it },
                    onWriteLockedChange = { editableWriteLocked = it },
                    onTeamMembersCanWriteChange = { editableTeamMembersCanWrite = it },
                )
            },
            onConfirm = {
                val topic = accessDialogTopic
                scope.launch {
                    val updated = ApiClient.updateKBTopic(
                        topicId = topic.id,
                        userId = user.id,
                        name = editableTopicName.trim(),
                        project = editableTopicProject.trim(),
                        accessPolicy = KBAccessPolicy(
                            readUserIds = editableReadUserIds,
                            writeUserIds = editableWriteUserIds,
                            manageUserIds = editableManageUserIds,
                            writeLocked = editableWriteLocked,
                            teamMembersCanWrite = if (topic.spaceType == KnowledgeSpaceType.TEAM) {
                                editableTeamMembersCanWrite
                            } else {
                                false
                            },
                        ),
                    )
                    if (updated != null) {
                        topics = ApiClient.getKBTopics(user.id)
                        selectedTopic = updated
                        saveMessage = "主题权限已更新"
                    }
                    resetTopicAccessDialog(
                        onVisibilityChange = { showTopicAccessDialog = it },
                        onTopicNameChange = { editableTopicName = it },
                        onTopicProjectChange = { editableTopicProject = it },
                        onReadUserIdsChange = { editableReadUserIds = it },
                        onWriteUserIdsChange = { editableWriteUserIds = it },
                        onManageUserIdsChange = { editableManageUserIds = it },
                        onWriteLockedChange = { editableWriteLocked = it },
                        onTeamMembersCanWriteChange = { editableTeamMembersCanWrite = it },
                    )
                }
            },
        )
    }

    if (showCreateEntryDialog && selectedTopic != null) {
        CreateEntryDialog(
            entryTitle = newEntryTitle,
            onEntryTitleChange = { newEntryTitle = it },
            onDismiss = {
                resetEntryDialog(
                    onVisibilityChange = { showCreateEntryDialog = it },
                    onTitleChange = { newEntryTitle = it },
                )
            },
            onConfirm = {
                scope.launch {
                    createKnowledgeEntry(
                        topic = selectedTopic,
                        entryTitle = newEntryTitle,
                        userId = user.id,
                        onEntriesChange = { entries = it },
                        onSelectedEntryChange = { selectedEntry = it },
                        onEditorContentChange = { editorContent = it },
                        onVisibilityChange = { showCreateEntryDialog = it },
                        onTitleChange = { newEntryTitle = it },
                    )
                }
            },
        )
    }

    if (showMeetingCaptureDialog) {
        KnowledgeBaseMeetingCaptureDialog(
            spaceOptions = spaceOptions,
            topics = topics.filter { canWriteKnowledgeTopic(it, user.id, userGroups) },
            selectedSpaceId = meetingCaptureSpaceId,
            selectedTopicId = meetingCaptureTopicId,
            title = meetingCaptureTitle,
            content = meetingCaptureContent,
            tagsText = meetingCaptureTagsText,
            status = meetingCaptureStatus,
            confidenceText = meetingCaptureConfidenceText,
            isSaving = isMeetingCaptureSaving,
            resultMessage = meetingCaptureResultMessage,
            onSelectedSpaceIdChange = { meetingCaptureSpaceId = it },
            onSelectedTopicIdChange = { meetingCaptureTopicId = it },
            onTitleChange = { meetingCaptureTitle = it },
            onContentChange = { meetingCaptureContent = it },
            onTagsTextChange = { meetingCaptureTagsText = it },
            onStatusChange = { meetingCaptureStatus = it },
            onConfidenceTextChange = { meetingCaptureConfidenceText = it },
            onDismiss = {
                if (!isMeetingCaptureSaving) {
                    showMeetingCaptureDialog = false
                    meetingCaptureResultMessage = null
                }
            },
            onConfirm = {
                scope.launch {
                    submitMeetingKnowledgeCapture(
                        userId = user.id,
                        topics = topics.filter { canWriteKnowledgeTopic(it, user.id, userGroups) },
                        selectedSpaceId = meetingCaptureSpaceId,
                        selectedTopicId = meetingCaptureTopicId,
                        title = meetingCaptureTitle,
                        content = meetingCaptureContent,
                        tagsText = meetingCaptureTagsText,
                        status = meetingCaptureStatus,
                        confidenceText = meetingCaptureConfidenceText,
                        onSavingChange = { isMeetingCaptureSaving = it },
                        onResultMessageChange = { meetingCaptureResultMessage = it },
                        onSelectedSpaceIdChange = { selectedSpaceId = it },
                        onSelectedTopicChange = { selectedTopic = it },
                        onEntriesChange = { entries = it },
                        onSelectedEntryChange = { selectedEntry = it },
                        onEditorContentChange = { editorContent = it },
                        onEntryFilterChange = { entryFilter = it },
                        onSaveMessageChange = { saveMessage = it },
                        onVisibilityChange = { showMeetingCaptureDialog = it },
                    )
                }
            },
        )
    }

    val mergeCandidateEntry = selectedEntry?.takeIf { it.status == KBEntryStatus.CANDIDATE }
    if (showMergeCandidateDialog && mergeCandidateEntry != null) {
        MergeKnowledgeEntryDialog(
            title = "并入已有文档",
            description = "将候选“${mergeCandidateEntry.title}”并入同一 knowledge space 的目标文档后，原候选会自动归档。",
            targetEntries = mergeTargetOptions,
            selectedTargetEntryId = mergeTargetEntryId,
            isLoading = isMergeTargetsLoading,
            isSaving = isMergeCandidateSaving,
            onSelectedTargetEntryIdChange = { mergeTargetEntryId = it },
            onDismiss = {
                if (!isMergeCandidateSaving) {
                    showMergeCandidateDialog = false
                }
            },
            onConfirm = {
                scope.launch {
                    mergeCandidateIntoKnowledgeEntry(
                        candidateEntry = mergeCandidateEntry,
                        sourceTopic = selectedTopic,
                        targetOption = mergeTargetOptions.find { it.entry.id == mergeTargetEntryId },
                        userId = user.id,
                        onSavingChange = { isMergeCandidateSaving = it },
                        onSaveMessageChange = { saveMessage = it },
                        onSelectedTopicChange = { selectedTopic = it },
                        onSelectedEntryChange = { selectedEntry = it },
                        onEntriesChange = { entries = it },
                        onEditorContentChange = { editorContent = it },
                        onEntryFilterChange = { entryFilter = it },
                        onDialogVisibilityChange = { showMergeCandidateDialog = it },
                    )
                }
            },
        )
    }

    if (showBatchMergeCandidatesDialog && batchMergeCandidateEntries.isNotEmpty()) {
        MergeKnowledgeEntryDialog(
            title = "批量并入已有文档",
            description = "将选中的 ${batchMergeCandidateEntries.size} 条候选并入同一 knowledge space 的目标文档后，这些候选会自动归档。",
            targetEntries = mergeTargetOptions,
            selectedTargetEntryId = batchMergeTargetEntryId,
            isLoading = isMergeTargetsLoading,
            isSaving = isBatchMergeSaving,
            onSelectedTargetEntryIdChange = { batchMergeTargetEntryId = it },
            onDismiss = {
                if (!isBatchMergeSaving) {
                    showBatchMergeCandidatesDialog = false
                }
            },
            onConfirm = {
                scope.launch {
                    bulkMergeCandidatesIntoKnowledgeEntry(
                        selectedCandidateEntryIds = selectedCandidateEntryIds,
                        sourceTopic = selectedTopic,
                        candidateEntries = batchMergeCandidateEntries,
                        targetOption = mergeTargetOptions.find { it.entry.id == batchMergeTargetEntryId },
                        userId = user.id,
                        onSavingChange = { isBatchMergeSaving = it },
                        onSaveMessageChange = { saveMessage = it },
                        onSelectedTopicChange = { selectedTopic = it },
                        onSelectedEntryChange = { selectedEntry = it },
                        onEntriesChange = { entries = it },
                        onEditorContentChange = { editorContent = it },
                        onEntryFilterChange = { entryFilter = it },
                        onSelectedCandidateEntryIdsChange = { selectedCandidateEntryIds = it },
                        onDialogVisibilityChange = { showBatchMergeCandidatesDialog = it },
                    )
                }
            },
        )
    }

    val moveEntry = selectedEntry
    val moveSourceTopic = selectedTopic
    val canShowMoveEntryDialog =
        showMoveEntryDialog && moveEntry != null && moveSourceTopic != null && moveTargetTopics.isNotEmpty()
    if (canShowMoveEntryDialog) {
        MoveKnowledgeEntryDialog(
            entryTitle = moveEntry!!.title,
            targetTopics = moveTargetTopics,
            selectedTargetTopicId = moveTargetTopicId,
            isSaving = isMoveEntrySaving,
            onSelectedTargetTopicIdChange = { moveTargetTopicId = it },
            onDismiss = {
                if (!isMoveEntrySaving) {
                    showMoveEntryDialog = false
                }
            },
            onConfirm = {
                scope.launch {
                    moveKnowledgeEntryToTopic(
                        entry = moveEntry,
                        sourceTopic = moveSourceTopic!!,
                        targetTopicId = moveTargetTopicId,
                        topics = topics,
                        userId = user.id,
                        onSavingChange = { isMoveEntrySaving = it },
                        onSaveMessageChange = { saveMessage = it },
                        onTopicsChange = { topics = it },
                        onSelectedTopicChange = { selectedTopic = it },
                        onEntriesChange = { entries = it },
                        onSelectedEntryChange = { selectedEntry = it },
                        onEditorContentChange = { editorContent = it },
                        onSelectedSpaceIdChange = { selectedSpaceId = it },
                        onDialogVisibilityChange = { showMoveEntryDialog = it },
                    )
                }
            },
        )
    }

    val deleteEntry = selectedEntry
    val deleteEntryTopic = selectedTopic
    if (showDeleteEntryDialog && deleteEntry != null && deleteEntryTopic != null) {
        ConfirmKnowledgeDeleteDialog(
            title = "删除条目",
            description = "删除后不可恢复：${deleteEntry.title}",
            confirmLabel = "确认删除",
            isSaving = isDeleteEntrySaving,
            onDismiss = {
                if (!isDeleteEntrySaving) {
                    showDeleteEntryDialog = false
                }
            },
            onConfirm = {
                scope.launch {
                    deleteKnowledgeEntry(
                        entry = deleteEntry,
                        topic = deleteEntryTopic,
                        userId = user.id,
                        onSavingChange = { isDeleteEntrySaving = it },
                        onSaveMessageChange = { saveMessage = it },
                        onEntriesChange = { entries = it },
                        onSelectedEntryChange = { selectedEntry = it },
                        onEditorContentChange = { editorContent = it },
                        onDialogVisibilityChange = { showDeleteEntryDialog = it },
                    )
                }
            },
        )
    }

    val deleteTopic = selectedTopic
    if (showDeleteTopicDialog && deleteTopic != null) {
        ConfirmKnowledgeDeleteDialog(
            title = "删除主题",
            description = "删除主题会同时删除该主题下的全部条目：${deleteTopic.name}",
            confirmLabel = "确认删除",
            isSaving = isDeleteTopicSaving,
            onDismiss = {
                if (!isDeleteTopicSaving) {
                    showDeleteTopicDialog = false
                }
            },
            onConfirm = {
                scope.launch {
                    deleteKnowledgeTopic(
                        topic = deleteTopic,
                        userId = user.id,
                        onSavingChange = { isDeleteTopicSaving = it },
                        onSaveMessageChange = { saveMessage = it },
                        onTopicsChange = { topics = it },
                        onSelectedTopicChange = { selectedTopic = it },
                        onEntriesChange = { entries = it },
                        onSelectedEntryChange = { selectedEntry = it },
                        onEditorContentChange = { editorContent = it },
                        onSelectedSpaceIdChange = { selectedSpaceId = it },
                        onDialogVisibilityChange = { showDeleteTopicDialog = it },
                    )
                }
            },
        )
    }

    if (showCreateEntryFromFileDialog) {
        val file = createEntryFromFileFile
        if (file != null) {
            val writableTopics = filteredTopics.filter { canWriteKnowledgeTopic(it, user.id, userGroups) }
            CreateEntryFromFileDialog(
                file = file,
                topics = writableTopics,
                selectedTopicId = createEntryFromFileTopicId,
                isSaving = isSaving,
                onTopicSelectionChange = { createEntryFromFileTopicId = it },
                onDismiss = {
                    if (!isSaving) {
                        showCreateEntryFromFileDialog = false
                        createEntryFromFileFile = null
                    }
                },
                onConfirm = {
                    scope.launch {
                        val targetTopic = writableTopics.find { it.id == createEntryFromFileTopicId }
                        if (targetTopic == null) {
                            saveMessage = "请选择目标主题"
                            return@launch
                        }
                        isSaving = true
                        val title = file.fileName.substringBeforeLast(".")
                        val source = KBEntrySource(
                            sourceType = KBSourceType.FILE,
                            sourceGroupId = selectedSpaceId.takeIf { it != PERSONAL_SPACE_ID },
                            fileRef = KBFileRef(
                                fileName = file.fileName,
                                fileSize = file.fileSize,
                                mimeType = file.mimeType,
                                downloadUrl = file.downloadUrl,
                            ),
                        )
                        val newEntry = ApiClient.captureKBEntry(
                            topicId = targetTopic.id,
                            title = title,
                            content = "## $title\n\n> 从群文件导入\n\n",
                            tags = listOf("file-import"),
                            userId = user.id,
                            source = source,
                            status = KBEntryStatus.CANDIDATE,
                        )
                        isSaving = false
                        if (newEntry != null) {
                            showCreateEntryFromFileDialog = false
                            createEntryFromFileFile = null
                            saveMessage = "已创建候选条目: $title"
                            val currentTopic = selectedTopic
                            if (currentTopic != null) {
                                loadKnowledgeEntries(
                                    topic = currentTopic,
                                    userId = user.id,
                                    onSelectedTopicChange = { selectedTopic = it },
                                    onSelectedEntryChange = { selectedEntry = it },
                                    onEditorContentChange = { editorContent = it },
                                    onEntriesChange = { entries = it },
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    // ── File preview overlay ──
    val previewingFile = previewFile
    if (previewingFile != null) {
        KnowledgeFilePreviewOverlay(
            downloadUrl = previewingFile.downloadUrl,
            fileName = previewingFile.fileName,
            mimeType = previewingFile.mimeType,
            fileSize = previewingFile.fileSize,
            onClose = { previewFile = null },
        )
    }
}

// ---- Shared dialog helpers ----

@Composable
private fun KnowledgeMemoryDialog(
    preferences: KnowledgeBaseContextPreferences,
    entries: List<KBEntryItem>,
    groupEntries: List<KBEntryItem>,
    groupId: String?,
    activeTab: String,
    isLoading: Boolean,
    isSaving: Boolean,
    draftTitle: String,
    draftContent: String,
    draftType: KBMemoryType,
    feedbackMessage: String,
    onDraftTitleChange: (String) -> Unit,
    onDraftContentChange: (String) -> Unit,
    onDraftTypeChange: (KBMemoryType) -> Unit,
    onPreferencesChange: (KnowledgeBaseContextPreferences) -> Unit,
    onActiveTabChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSavePreferences: () -> Unit,
    onCreateMemory: () -> Unit,
    onDeleteMemory: (String) -> Unit,
) {
    Div({
        style {
            position(Position.Fixed)
            top(0.px); left(0.px); right(0.px); bottom(0.px)
            backgroundColor(Color("rgba(0,0,0,0.42)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
        }
        onClick { onDismiss() }
    }) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(16.px)
                padding(24.px)
                width(760.px)
                property("max-width", "calc(100vw - 32px)")
                property("max-height", "calc(100vh - 48px)")
                property("overflow-y", "auto")
                property("box-shadow", "0 16px 48px rgba(0,0,0,0.18)")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({ style { marginTop(0.px); marginBottom(8.px); color(Color(SilkColors.textPrimary)) } }) {
                Text("KB Memory")
            }
            Div({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    marginBottom(16.px)
                    property("line-height", "1.6")
                }
            }) {
                Text("管理长期记忆的注入开关、自动记忆策略，以及当前已保存的个人/群组 memory。")
            }
            if (feedbackMessage.isNotBlank()) {
                Div({
                    style {
                        marginBottom(12.px)
                        padding(10.px, 12.px)
                        borderRadius(10.px)
                        backgroundColor(Color("#F7F1E3"))
                        color(Color(SilkColors.textPrimary))
                        fontSize(13.px)
                    }
                }) { Text(feedbackMessage) }
            }
            // Tab switcher — group tab only when inside a team space
            if (groupId != null) {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("gap", "8px")
                        marginBottom(16.px)
                    }
                }) {
                    listOf("personal" to "个人记忆", "group" to "群组记忆").forEach { (tabId, tabLabel) ->
                        KnowledgeToggleButton(
                            label = tabLabel,
                            selected = activeTab == tabId,
                            onClick = { onActiveTabChange(tabId) },
                        )
                    }
                }
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "12px")
                }
            }) {
                if (activeTab == "personal") {
                    KnowledgeMemorySettingsSection(
                        preferences = preferences,
                        onPreferencesChange = onPreferencesChange,
                        onSavePreferences = onSavePreferences,
                        isSaving = isSaving,
                    )
                    KnowledgeMemoryComposerSection(
                        draftTitle = draftTitle,
                        draftContent = draftContent,
                        draftType = draftType,
                        isSaving = isSaving,
                        onDraftTitleChange = onDraftTitleChange,
                        onDraftContentChange = onDraftContentChange,
                        onDraftTypeChange = onDraftTypeChange,
                        onCreateMemory = onCreateMemory,
                    )
                    KnowledgeMemoryEntriesSection(
                        entries = entries,
                        isLoading = isLoading,
                        isSaving = isSaving,
                        onDeleteMemory = onDeleteMemory,
                    )
                } else {
                    // Group memory tab — composer + entries, no settings
                    KnowledgeMemoryComposerSection(
                        draftTitle = draftTitle,
                        draftContent = draftContent,
                        draftType = draftType,
                        isSaving = isSaving,
                        onDraftTitleChange = onDraftTitleChange,
                        onDraftContentChange = onDraftContentChange,
                        onDraftTypeChange = onDraftTypeChange,
                        onCreateMemory = onCreateMemory,
                    )
                    KnowledgeMemoryEntriesSection(
                        entries = groupEntries,
                        isLoading = isLoading,
                        isSaving = isSaving,
                        onDeleteMemory = onDeleteMemory,
                    )
                }
            }
            DialogActions(
                onCancel = onDismiss,
                onConfirm = onDismiss,
                confirmLabel = "关闭",
                confirmEnabled = !isSaving,
            )
        }
    }
}

@Composable
private fun KnowledgeMemorySettingsSection(
    preferences: KnowledgeBaseContextPreferences,
    onPreferencesChange: (KnowledgeBaseContextPreferences) -> Unit,
    onSavePreferences: () -> Unit,
    isSaving: Boolean,
) {
    KnowledgeMemorySection(title = "设置") {
        KnowledgeMemoryCheckboxRow(
            label = "启用长期记忆注入",
            description = "回复前检索相关 memory 并注入 prompt。",
            checked = preferences.memoryEnabled,
            onCheckedChange = {
                onPreferencesChange(preferences.copy(memoryEnabled = it))
            },
        )
        KnowledgeMemoryCheckboxRow(
            label = "允许自动记忆",
            description = "只针对低风险偏好做自动抽取；高风险内容仍不自动入库。",
            checked = preferences.autoCaptureEnabled,
            onCheckedChange = {
                onPreferencesChange(preferences.copy(autoCaptureEnabled = it))
            },
        )
        KnowledgeMemoryCheckboxRow(
            label = "启用会话级临时记忆",
            description = "为后续的短会话记忆保留开关，当前先只做显式控制。",
            checked = preferences.ephemeralSessionEnabled,
            onCheckedChange = {
                onPreferencesChange(preferences.copy(ephemeralSessionEnabled = it))
            },
        )
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.FlexEnd)
                marginTop(8.px)
            }
        }) {
            KnowledgeInlineActionButton(
                label = if (isSaving) "保存中..." else "保存设置",
                background = SilkColors.primaryDark,
                enabled = !isSaving,
                onClick = onSavePreferences,
            )
        }
    }
}

@Composable
private fun KnowledgeMemoryComposerSection(
    draftTitle: String,
    draftContent: String,
    draftType: KBMemoryType,
    isSaving: Boolean,
    onDraftTitleChange: (String) -> Unit,
    onDraftContentChange: (String) -> Unit,
    onDraftTypeChange: (KBMemoryType) -> Unit,
    onCreateMemory: () -> Unit,
) {
    KnowledgeMemorySection(title = "新增记忆") {
        LabeledInput(
            placeholder = "标题（可选，不填则由后端生成）",
            currentValue = draftTitle,
            onValueChange = onDraftTitleChange,
        )
        Div({
            style {
                marginBottom(12.px)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "8px")
            }
        }) {
            Span({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    fontWeight("600")
                }
            }) { Text("记忆类型") }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "6px")
                    property("flex-wrap", "wrap")
                }
            }) {
                KBMemoryType.entries.forEach { type ->
                    KnowledgeToggleButton(
                        label = knowledgeMemoryTypeLabel(type),
                        selected = draftType == type,
                        onClick = { onDraftTypeChange(type) },
                    )
                }
            }
        }
        TextArea(
            value = draftContent,
            attrs = {
                attr("placeholder", "例如：记住我默认用中文回答；记住这个项目优先 Kotlin/Ktor；记住我喜欢先给最小修复。")
                onInput { onDraftContentChange(it.value) }
                style {
                    width(100.percent)
                    height(120.px)
                    borderRadius(10.px)
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    padding(10.px, 12.px)
                    fontSize(14.px)
                    property("box-sizing", "border-box")
                    marginBottom(12.px)
                }
            },
        )
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.FlexEnd)
            }
        }) {
            KnowledgeInlineActionButton(
                label = if (isSaving) "写入中..." else "写入记忆",
                background = SilkColors.primary,
                enabled = !isSaving,
                onClick = onCreateMemory,
            )
        }
    }
}

@Composable
private fun KnowledgeMemoryEntriesSection(
    entries: List<KBEntryItem>,
    isLoading: Boolean,
    isSaving: Boolean,
    onDeleteMemory: (String) -> Unit,
) {
    KnowledgeMemorySection(title = "当前记忆") {
        when {
            isLoading -> KnowledgeCenteredMessage("正在加载 memory...", SilkColors.textSecondary, 16.px)
            entries.isEmpty() -> KnowledgeCenteredMessage("还没有保存任何长期记忆", SilkColors.textLight, 20.px)
            else -> {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "10px")
                    }
                }) {
                    entries.forEach { entry ->
                        KnowledgeMemoryEntryCard(
                            entry = entry,
                            isSaving = isSaving,
                            onDelete = { onDeleteMemory(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeMemoryEntryCard(
    entry: KBEntryItem,
    isSaving: Boolean,
    onDelete: () -> Unit,
) {
    Div({
        style {
            padding(12.px)
            borderRadius(12.px)
            backgroundColor(Color("#FFFCF6"))
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "8px")
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                property("gap", "12px")
            }
        }) {
            Div({
                style {
                    fontSize(14.px)
                    fontWeight("600")
                    color(Color(SilkColors.textPrimary))
                }
            }) {
                Text(entry.title.ifBlank { "未命名记忆" })
            }
            KnowledgeInlineActionButton(
                label = "删除",
                background = "#8F3D3A",
                enabled = !isSaving,
                onClick = onDelete,
            )
        }
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "6px")
                property("flex-wrap", "wrap")
            }
        }) {
            entry.memory?.type?.let { KnowledgeBadge(knowledgeMemoryTypeLabel(it), SilkColors.primaryDark) }
            entry.memory?.key?.takeIf { it.isNotBlank() }?.let { KnowledgeBadge("key:$it", SilkColors.info) }
            if (entry.memory?.explicit == true) {
                KnowledgeBadge("显式记忆", SilkColors.success)
            }
        }
        Div({
            style {
                fontSize(13.px)
                color(Color(SilkColors.textPrimary))
                property("line-height", "1.6")
                property("white-space", "pre-wrap")
            }
        }) {
            Text(entry.content.ifBlank { "无内容" })
        }
    }
}

@Composable
private fun KnowledgeMemorySection(title: String, content: @Composable () -> Unit) {
    Div({
        style {
            padding(16.px)
            borderRadius(14.px)
            backgroundColor(Color("#FFFDF9"))
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
        }
    }) {
        Div({
            style {
                fontSize(15.px)
                fontWeight("600")
                color(Color(SilkColors.textPrimary))
                marginBottom(12.px)
            }
        }) { Text(title) }
        content()
    }
}

@Composable
private fun KnowledgeMemoryCheckboxRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.FlexStart)
            property("gap", "12px")
            padding(10.px, 0.px)
        }
    }) {
        Div({
            style {
                property("flex", "1")
            }
        }) {
            Div({
                style {
                    fontSize(14.px)
                    fontWeight("600")
                    color(Color(SilkColors.textPrimary))
                    marginBottom(4.px)
                }
            }) { Text(label) }
            Div({
                style {
                    fontSize(12.px)
                    color(Color(SilkColors.textSecondary))
                    property("line-height", "1.5")
                }
            }) { Text(description) }
        }
        Input(InputType.Checkbox) {
            checked(checked)
            onInput { onCheckedChange(!checked) }
            style {
                marginTop(4.px)
                property("transform", "scale(1.2)")
            }
        }
    }
}

@Composable
fun ModalDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Div({
        style {
            position(Position.Fixed)
            top(0.px); left(0.px); right(0.px); bottom(0.px)
            backgroundColor(Color("rgba(0,0,0,0.4)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
        }
        onClick { onDismiss() }
    }) {
        Div({
            style {
                backgroundColor(Color.white)
                borderRadius(12.px)
                padding(24.px)
                width(380.px)
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.15)")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({ style { marginTop(0.px); marginBottom(16.px); color(Color(SilkColors.textPrimary)) } }) { Text(title) }
            content()
        }
    }
}

@Composable
fun LabeledInput(placeholder: String, currentValue: String, onValueChange: (String) -> Unit) {
    Input(InputType.Text) {
        value(currentValue)
        onInput { onValueChange(it.value) }
        attr("placeholder", placeholder)
        style {
            width(100.percent)
            height(40.px)
            borderRadius(6.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            padding(8.px)
            fontSize(14.px)
            marginBottom(12.px)
            property("box-sizing", "border-box")
        }
    }
}

@Composable
private fun MergeKnowledgeEntryDialog(
    title: String,
    description: String,
    targetEntries: List<KnowledgeMergeTargetOption>,
    selectedTargetEntryId: String,
    isLoading: Boolean,
    isSaving: Boolean,
    onSelectedTargetEntryIdChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalDialog(title = title, onDismiss = onDismiss) {
        Div({
            style {
                fontSize(13.px)
                color(Color(SilkColors.textSecondary))
                marginBottom(12.px)
            }
        }) {
            Text(description)
        }
        Div({
            style {
                marginBottom(12.px)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "8px")
            }
        }) {
            Span({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    fontWeight("600")
                }
            }) { Text("目标文档") }
            if (isLoading) {
                Div({
                    style {
                        padding(12.px)
                        borderRadius(8.px)
                        backgroundColor(Color("#FAF5EC"))
                        fontSize(13.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text("正在加载同一 knowledge space 的可合并文档...")
                }
            } else if (targetEntries.isEmpty()) {
                Div({
                    style {
                        padding(12.px)
                        borderRadius(8.px)
                        backgroundColor(Color("#FFF5F2"))
                        fontSize(13.px)
                        color(Color(SilkColors.warning))
                    }
                }) {
                    Text("当前没有可合并的目标文档")
                }
            } else {
                org.jetbrains.compose.web.dom.Select({
                    style {
                        width(100.percent)
                        height(40.px)
                        borderRadius(8.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(0.px, 10.px)
                        backgroundColor(Color.white)
                        color(Color(SilkColors.textPrimary))
                    }
                    attr("value", selectedTargetEntryId)
                    onChange { onSelectedTargetEntryIdChange(it.value ?: "") }
                }) {
                    targetEntries.forEach { option ->
                        org.jetbrains.compose.web.dom.Option(value = option.entry.id) {
                            Text("${option.topic.name} / ${option.entry.title} · ${option.entry.status.name.lowercase()}")
                        }
                    }
                }
            }
        }
        DialogActions(
            onCancel = onDismiss,
            onConfirm = onConfirm,
            confirmLabel = if (isSaving) "合并中..." else "确认合并",
            confirmEnabled = !isLoading && !isSaving && selectedTargetEntryId.isNotBlank(),
        )
    }
}

@Composable
fun DialogActions(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = "确定",
    confirmEnabled: Boolean = true,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.FlexEnd)
            property("gap", "8px")
            marginTop(8.px)
        }
    }) {
        Button({
            style {
                backgroundColor(Color(SilkColors.surface))
                color(Color(SilkColors.textSecondary))
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                borderRadius(6.px)
                padding(8.px, 16.px)
                property("cursor", "pointer")
            }
            onClick { onCancel() }
        }) { Text("取消") }
        Button({
            style {
                backgroundColor(Color(if (confirmEnabled) SilkColors.primary else SilkColors.border))
                color(Color.white)
                border(0.px)
                borderRadius(6.px)
                padding(8.px, 16.px)
                property("cursor", if (confirmEnabled) "pointer" else "not-allowed")
            }
            if (!confirmEnabled) {
                attr("disabled", "true")
            }
            onClick {
                if (confirmEnabled) {
                    onConfirm()
                }
            }
        }) { Text(confirmLabel) }
    }
}
