package com.silk.web

import androidx.compose.runtime.Composable
import com.silk.shared.models.KnowledgeBaseContextSelection
import com.silk.shared.models.Message
import com.silk.shared.models.MessageCategory
import com.silk.shared.models.MessageType
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

internal fun isKnowledgeBaseContextStatusMessage(message: Message): Boolean {
    return message.category == MessageCategory.AGENT_STATUS &&
        message.references.any { it.kind == "available" && parseKnowledgeBaseDeepLink(it.path) != null }
}

internal fun hasKnowledgeBaseContextSelection(selection: KnowledgeBaseContextSelection): Boolean {
    return selection.pinnedEntryIds.isNotEmpty() ||
        selection.excludedEntryIds.isNotEmpty() ||
        selection.excludedSpaceIds.isNotEmpty() ||
        selection.downrankedSpaceIds.isNotEmpty()
}

internal fun togglePinnedKnowledgeBaseEntry(
    selection: KnowledgeBaseContextSelection,
    entryId: String,
): KnowledgeBaseContextSelection {
    val pinned = selection.pinnedEntryIds.toMutableList()
    val excluded = selection.excludedEntryIds.toMutableList()
    if (entryId in pinned) {
        pinned.removeAll { it == entryId }
    } else {
        pinned += entryId
        excluded.removeAll { it == entryId }
    }
    return KnowledgeBaseContextSelection(
        pinnedEntryIds = pinned.distinct(),
        excludedEntryIds = excluded.distinct(),
        excludedSpaceIds = selection.excludedSpaceIds.distinct(),
    )
}

internal fun toggleExcludedKnowledgeBaseEntry(
    selection: KnowledgeBaseContextSelection,
    entryId: String,
): KnowledgeBaseContextSelection {
    val pinned = selection.pinnedEntryIds.toMutableList()
    val excluded = selection.excludedEntryIds.toMutableList()
    if (entryId in excluded) {
        excluded.removeAll { it == entryId }
    } else {
        excluded += entryId
        pinned.removeAll { it == entryId }
    }
    return KnowledgeBaseContextSelection(
        pinnedEntryIds = pinned.distinct(),
        excludedEntryIds = excluded.distinct(),
        excludedSpaceIds = selection.excludedSpaceIds.distinct(),
    )
}

/**
 * 三态切换：normal → downranked → excluded → normal
 * - 若 space 不在任何列表中 → 进入 downranked
 * - 若 space 在 downranked 中 → 进入 excluded
 * - 若 space 在 excluded 中 → 恢复 normal（从两者中移除）
 */
internal fun cycleKnowledgeBaseSpaceControl(
    selection: KnowledgeBaseContextSelection,
    spaceId: String,
): KnowledgeBaseContextSelection {
    val downranked = selection.downrankedSpaceIds.toMutableList()
    val excluded = selection.excludedSpaceIds.toMutableList()
    val isDownranked = spaceId in downranked
    val isExcludedOnly = !isDownranked && spaceId in excluded
    when {
        // 不在任何列表中 → 降权
        !isDownranked && !isExcludedOnly -> {
            downranked += spaceId
        }
        // 在降权中 → 排除
        isDownranked -> {
            downranked.removeAll { it == spaceId }
            excluded += spaceId
        }
        // 在排除中 → 恢复
        isExcludedOnly -> {
            excluded.removeAll { it == spaceId }
        }
    }
    return KnowledgeBaseContextSelection(
        pinnedEntryIds = selection.pinnedEntryIds.distinct(),
        excludedEntryIds = selection.excludedEntryIds.distinct(),
        excludedSpaceIds = excluded.distinct(),
        downrankedSpaceIds = downranked.distinct(),
    )
}

/** 向后兼容：直接切换排除状态（降权 → 排除，排除 → 正常） */
internal fun toggleExcludedKnowledgeBaseSpace(
    selection: KnowledgeBaseContextSelection,
    spaceId: String,
): KnowledgeBaseContextSelection {
    val excludedSpaces = selection.excludedSpaceIds.toMutableList()
    val downrankedSpaces = selection.downrankedSpaceIds.toMutableList()
    if (spaceId in excludedSpaces) {
        excludedSpaces.removeAll { it == spaceId }
    } else {
        excludedSpaces += spaceId
        downrankedSpaces.removeAll { it == spaceId }
    }
    return KnowledgeBaseContextSelection(
        pinnedEntryIds = selection.pinnedEntryIds.distinct(),
        excludedEntryIds = selection.excludedEntryIds.distinct(),
        excludedSpaceIds = excludedSpaces.distinct(),
        downrankedSpaceIds = downrankedSpaces.distinct(),
    )
}

internal fun filterNonKnowledgeBaseContextStatusMessages(messages: List<Message>): List<Message> {
    return messages.filterNot(::isKnowledgeBaseContextStatusMessage)
}

internal fun latestKnowledgeBaseContextSelection(
    messages: List<Message>,
    userId: String,
): KnowledgeBaseContextSelection? {
    return messages.asReversed().firstOrNull { message ->
        message.userId == userId &&
            message.type == MessageType.TEXT &&
            message.kbContextSelection != null
    }?.kbContextSelection
}

internal fun mergeKnowledgeBaseContextSelectionWithPersistentSpaces(
    restoredSelection: KnowledgeBaseContextSelection?,
    persistentExcludedSpaceIds: List<String>,
    persistentDownrankedSpaceIds: List<String> = emptyList(),
): KnowledgeBaseContextSelection {
    val base = restoredSelection ?: KnowledgeBaseContextSelection()
    return KnowledgeBaseContextSelection(
        pinnedEntryIds = base.pinnedEntryIds.distinct(),
        excludedEntryIds = base.excludedEntryIds.distinct(),
        excludedSpaceIds = (persistentExcludedSpaceIds + base.excludedSpaceIds).distinct(),
        downrankedSpaceIds = (persistentDownrankedSpaceIds + base.downrankedSpaceIds).distinct(),
    )
}

@Composable
@Suppress("CyclomaticComplexMethod")
internal fun KnowledgeBaseContextTray(
    statusMessages: List<Message>,
    selection: KnowledgeBaseContextSelection,
    onSelectionChange: (KnowledgeBaseContextSelection) -> Unit,
) {
    val status = statusMessages.lastOrNull(::isKnowledgeBaseContextStatusMessage) ?: return
    val references = status.references.filter { it.kind == "available" && parseKnowledgeBaseDeepLink(it.path) != null }
    if (references.isEmpty()) return
    val pinnedCount = selection.pinnedEntryIds.size
    val excludedCount = selection.excludedEntryIds.size
    val excludedSpaceCount = selection.excludedSpaceIds.size
    val downrankedSpaceCount = selection.downrankedSpaceIds.size

    Div({
        style {
            property("border", "1px solid #E8E0D4")
            borderRadius(12.px)
            padding(12.px)
            property("background", "linear-gradient(135deg, #FFFBF2 0%, #FFF7E5 100%)")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "10px")
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                property("justify-content", "space-between")
                alignItems(AlignItems.Center)
                property("gap", "12px")
            }
        }) {
            Div {
                Div({
                    style {
                        fontSize(13.px)
                        color(Color("#8B7355"))
                        fontWeight("600")
                    }
                }) { Text("本轮 Context Tray") }
                Div({
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        marginTop(4.px)
                    }
                }) { Text(status.content) }
                val hasActivePreferences = pinnedCount > 0 || excludedCount > 0 || excludedSpaceCount > 0 || downrankedSpaceCount > 0
                if (hasActivePreferences) {
                    Div({
                        style {
                            fontSize(12.px)
                            color(Color(SilkColors.textSecondary))
                            marginTop(4.px)
                        }
                    }) {
                        Text("下轮偏好：固定 $pinnedCount，排除条目 $excludedCount，关闭空间 $excludedSpaceCount，降权空间 $downrankedSpaceCount")
                    }
                }
            }
            ContextTrayBadge("KB ${references.size}", SilkColors.primary)
        }

        Div({
            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "8px")
            }
        }) {
            references.forEach { ref ->
                val kbLink = parseKnowledgeBaseDeepLink(ref.path)
                val entryId = kbLink?.entryId
                val isPinned = entryId != null && entryId in selection.pinnedEntryIds
                val isExcluded = entryId != null && entryId in selection.excludedEntryIds
                val spaceId = ref.spaceId
                val isSpaceExcluded = spaceId != null && spaceId in selection.excludedSpaceIds
                val isSpaceDownranked = spaceId != null && !isSpaceExcluded && spaceId in selection.downrankedSpaceIds
                Button({
                    style {
                        backgroundColor(Color("#FFFFFF"))
                        border(0.px)
                        borderRadius(10.px)
                        padding(10.px, 12.px)
                        property("cursor", "pointer")
                        property("text-align", "left")
                        property("box-shadow", "0 1px 0 rgba(201, 168, 108, 0.14)")
                    }
                    onClick {
                        kbLink?.let { openKnowledgeBaseEntryLink(entryId = it.entryId, topicId = it.topicId) }
                    }
                }) {
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            property("justify-content", "space-between")
                            alignItems(AlignItems.Center)
                            property("gap", "10px")
                        }
                    }) {
                        Div({
                            style {
                                fontSize(13.px)
                                color(Color(SilkColors.textPrimary))
                                fontWeight("600")
                                property("flex", "1")
                                property("min-width", "0")
                                property("word-break", "break-word")
                            }
                        }) { Text("[available:${ref.index}] ${ref.title}") }
                        ContextTrayBadge(
                            label = when (ref.origin) {
                                "manual" -> "手动"
                                "pin" -> "固定"
                                else -> "自动"
                            },
                            accent = when (ref.origin) {
                                "manual" -> SilkColors.success
                                "pin" -> SilkColors.primaryDark
                                else -> SilkColors.info
                            },
                        )
                    }
                    ref.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                        Div({
                            style {
                                fontSize(12.px)
                                color(Color(SilkColors.textSecondary))
                                marginTop(6.px)
                            }
                        }) { Text("加入原因：$reason") }
                    }
                    ref.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
                        Div({
                            style {
                                fontSize(12.px)
                                color(Color(SilkColors.textLight))
                                marginTop(6.px)
                                property("display", "-webkit-box")
                                property("-webkit-line-clamp", "2")
                                property("-webkit-box-orient", "vertical")
                                property("overflow", "hidden")
                                property("word-break", "break-word")
                            }
                        }) { Text(snippet) }
                    }
                    entryId?.let { resolvedEntryId ->
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                property("flex-wrap", "wrap")
                                property("gap", "8px")
                                marginTop(8.px)
                            }
                        }) {
                            ContextTrayActionButton(
                                label = if (isPinned) "取消固定" else "固定下轮",
                                accent = SilkColors.primaryDark,
                            ) {
                                onSelectionChange(togglePinnedKnowledgeBaseEntry(selection, resolvedEntryId))
                            }
                            if (ref.origin != "manual") {
                                ContextTrayActionButton(
                                    label = if (isExcluded) "恢复自动" else "排除下轮",
                                    accent = if (isExcluded) SilkColors.info else SilkColors.textSecondary,
                                ) {
                                    onSelectionChange(toggleExcludedKnowledgeBaseEntry(selection, resolvedEntryId))
                                }
                            }
                            if (spaceId != null) {
                                // 三态空间控制：normal → 🔽 downranked → ❌ excluded → normal
                                val spaceLabel = ref.spaceLabel ?: "此空间"
                                val stateLabel: String
                                val stateAccent: String
                                val stateIcon: String
                                when {
                                    isSpaceExcluded -> {
                                        stateLabel = "恢复${spaceLabel}推荐"
                                        stateAccent = SilkColors.info
                                        stateIcon = "✅ "
                                    }
                                    isSpaceDownranked -> {
                                        stateLabel = "降权${spaceLabel}"
                                        stateAccent = SilkColors.warning
                                        stateIcon = "🔽 "
                                    }
                                    else -> {
                                        stateLabel = "降低${spaceLabel}优先级"
                                        stateAccent = SilkColors.textSecondary
                                        stateIcon = ""
                                    }
                                }
                                ContextTrayActionButton(
                                    label = stateIcon + stateLabel,
                                    accent = stateAccent,
                                ) {
                                    onSelectionChange(cycleKnowledgeBaseSpaceControl(selection, spaceId))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextTrayBadge(label: String, accent: String) {
    Span({
        style {
            backgroundColor(Color("rgba(201, 168, 108, 0.12)"))
            color(Color(accent))
            borderRadius(999.px)
            padding(3.px, 8.px)
            fontSize(11.px)
            fontWeight("600")
            property("white-space", "nowrap")
        }
    }) { Text(label) }
}

@Composable
private fun ContextTrayActionButton(
    label: String,
    accent: String,
    onClick: () -> Unit,
) {
    Button({
        style {
            backgroundColor(Color("#FFFDF8"))
            color(Color(accent))
            property("border", "1px solid rgba(201, 168, 108, 0.28)")
            borderRadius(999.px)
            padding(4.px, 10.px)
            fontSize(11.px)
            fontWeight("600")
            property("cursor", "pointer")
        }
        onClick {
            it.stopPropagation()
            onClick()
        }
    }) { Text(label) }
}
