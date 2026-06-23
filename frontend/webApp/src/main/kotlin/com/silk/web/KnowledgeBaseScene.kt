package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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

private enum class KnowledgeEditorMode(val label: String) {
    EDIT("编辑"),
    PREVIEW("预览"),
    SPLIT("分栏"),
}

internal enum class KnowledgeEntryFilter(val label: String) {
    ALL("全部"),
    CANDIDATE("候选"),
    PUBLISHED("已发布"),
    ARCHIVED("已归档"),
}

internal const val PERSONAL_SPACE_ID = "__personal__"

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

internal fun filterKnowledgeEntries(entries: List<KBEntryItem>, filter: KnowledgeEntryFilter): List<KBEntryItem> {
    return when (filter) {
        KnowledgeEntryFilter.ALL -> entries
        KnowledgeEntryFilter.CANDIDATE -> entries.filter { it.status == KBEntryStatus.CANDIDATE }
        KnowledgeEntryFilter.PUBLISHED -> entries.filter { it.status == KBEntryStatus.PUBLISHED }
        KnowledgeEntryFilter.ARCHIVED -> entries.filter { it.status == KBEntryStatus.ARCHIVED }
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

@Composable
private fun TopicSidebar(
    spaceOptions: List<KnowledgeSpaceOption>,
    selectedSpaceId: String,
    topics: List<KBTopicItem>,
    isLoading: Boolean,
    selectedTopic: KBTopicItem?,
    userId: String,
    groups: List<Group>,
    onCreateTopic: () -> Unit,
    onSpaceSelect: (KnowledgeSpaceOption) -> Unit,
    onTopicSelect: (KBTopicItem) -> Unit,
) {
    Div({
        style {
            width(260.px)
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
            onAction = onCreateTopic,
        )
        KnowledgeSpaceTabs(
            options = spaceOptions,
            selectedSpaceId = selectedSpaceId,
            onSpaceSelect = onSpaceSelect,
        )
        TopicSidebarContent(
            topics = topics,
            isLoading = isLoading,
            selectedTopic = selectedTopic,
            userId = userId,
            groups = groups,
            onTopicSelect = onTopicSelect,
        )
    }
}

@Composable
private fun TopicSidebarContent(
    topics: List<KBTopicItem>,
    isLoading: Boolean,
    selectedTopic: KBTopicItem?,
    userId: String,
    groups: List<Group>,
    onTopicSelect: (KBTopicItem) -> Unit,
) {
    Div({ style { property("flex", "1"); property("overflow-y", "auto") } }) {
        if (isLoading) {
            KnowledgeCenteredMessage("加载中...", SilkColors.textSecondary, 16.px)
        } else if (topics.isEmpty()) {
            KnowledgeCenteredMessage("当前空间还没有主题", SilkColors.textLight, 20.px)
        } else {
            topics.forEach { topic ->
                TopicRow(
                    topic = topic,
                    isSelected = selectedTopic?.id == topic.id,
                    spaceLabel = topicSpaceLabel(topic, groups),
                    permissionLabel = topicPermissionLabel(topic, userId, groups),
                    onClick = { onTopicSelect(topic) },
                )
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
    onClick: () -> Unit,
) {
    Div({
        style {
            padding(10.px, 14.px)
            property("cursor", "pointer")
            if (isSelected) backgroundColor(Color("rgba(201,168,108,0.15)"))
            property("border-bottom", "1px solid ${SilkColors.border}")
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
    selectedTopic: KBTopicItem?,
    entries: List<KBEntryItem>,
    selectedEntry: KBEntryItem?,
    selectedFilter: KnowledgeEntryFilter,
    canCreateEntry: Boolean,
    onFilterChange: (KnowledgeEntryFilter) -> Unit,
    onCreateEntry: () -> Unit,
    onEntrySelect: (KBEntryItem) -> Unit,
) {
    Div({
        style {
            width(240.px)
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
            onAction = onCreateEntry,
        )
        EntryFilterTabs(
            selectedFilter = selectedFilter,
            onFilterChange = onFilterChange,
        )
        EntrySidebarContent(
            selectedTopic = selectedTopic,
            entries = entries,
            selectedEntry = selectedEntry,
            onEntrySelect = onEntrySelect,
        )
    }
}

@Composable
private fun EntryFilterTabs(
    selectedFilter: KnowledgeEntryFilter,
    onFilterChange: (KnowledgeEntryFilter) -> Unit,
) {
    Div({
        style {
            padding(10.px, 12.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
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
}

@Composable
private fun EntrySidebarContent(
    selectedTopic: KBTopicItem?,
    entries: List<KBEntryItem>,
    selectedEntry: KBEntryItem?,
    onEntrySelect: (KBEntryItem) -> Unit,
) {
    Div({ style { property("flex", "1"); property("overflow-y", "auto") } }) {
        when {
            selectedTopic == null -> KnowledgeListEmptyState("请先选择主题")
            entries.isEmpty() -> KnowledgeListEmptyState("暂无条目")
            else -> entries.forEach { entry ->
                EntryRow(
                    entry = entry,
                    isSelected = selectedEntry?.id == entry.id,
                    onClick = { onEntrySelect(entry) },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(entry: KBEntryItem, isSelected: Boolean, onClick: () -> Unit) {
    Div({
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
                fontSize(13.px)
                color(Color(SilkColors.textPrimary))
                fontWeight(if (isSelected) "600" else "400")
            }
        }) { Text(entry.title) }
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "6px")
                marginTop(6.px)
                property("flex-wrap", "wrap")
            }
        }) {
            KnowledgeBadge(entry.status.name.lowercase(), SilkColors.primaryDark)
            val sourceLabel = when (entry.source.sourceType) {
                KBSourceType.MANUAL -> "手动"
                KBSourceType.CHAT -> "聊天"
                KBSourceType.WORKFLOW -> "工作流"
                KBSourceType.MEETING -> "会议"
                KBSourceType.FILE -> "文件"
                KBSourceType.URL -> "URL"
            }
            KnowledgeBadge(sourceLabel, SilkColors.primary)
        }
    }
}

@Composable
private fun KnowledgeEditorPane(
    selectedTopic: KBTopicItem?,
    selectedEntry: KBEntryItem?,
    editorContent: String,
    isSaving: Boolean,
    saveMessage: String,
    editorMode: KnowledgeEditorMode,
    canEdit: Boolean,
    canManageTopic: Boolean,
    spaceLabel: String?,
    permissionLabel: String?,
    onContentChange: (String) -> Unit,
    onEditorModeChange: (KnowledgeEditorMode) -> Unit,
    onManageTopic: () -> Unit,
    onSave: () -> Unit,
    onStatusAction: (() -> Unit)?,
    statusActionLabel: String?,
    onExport: () -> Unit,
    onCopyReference: () -> Unit,
) {
    Div({
        style {
            property("flex", "1")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            minWidth(0.px)
        }
    }) {
        if (selectedEntry == null) {
            EmptyEditorState()
        } else {
            KnowledgeEditorToolbar(
                title = selectedEntry.title,
                isSaving = isSaving,
                saveMessage = saveMessage,
                editorMode = editorMode,
                canEdit = canEdit,
                canManageTopic = canManageTopic,
                onEditorModeChange = onEditorModeChange,
                onManageTopic = onManageTopic,
                onSave = onSave,
                onStatusAction = onStatusAction,
                statusActionLabel = statusActionLabel,
                onExport = onExport,
                onCopyReference = onCopyReference,
            )
            KnowledgeEntryMetaBar(
                topic = selectedTopic,
                entry = selectedEntry,
                spaceLabel = spaceLabel,
                permissionLabel = permissionLabel,
            )
            KnowledgeMarkdownWorkspace(
                content = editorContent,
                onContentChange = onContentChange,
                editorMode = editorMode,
                readOnly = !canEdit,
                onSave = onSave,
            )
        }
    }
}

@Composable
private fun KnowledgeMarkdownWorkspace(
    content: String,
    onContentChange: (String) -> Unit,
    editorMode: KnowledgeEditorMode,
    readOnly: Boolean,
    onSave: () -> Unit,
) {
    Div({
        style {
            property("flex", "1")
            display(DisplayStyle.Flex)
            flexDirection(if (editorMode == KnowledgeEditorMode.SPLIT) FlexDirection.Row else FlexDirection.Column)
            backgroundColor(Color(SilkColors.background))
            minWidth(0.px)
            property("min-height", "0")
        }
    }) {
        if (editorMode != KnowledgeEditorMode.PREVIEW) {
            MarkdownSourcePane(
                content = content,
                onContentChange = onContentChange,
                onSave = onSave,
                readOnly = readOnly,
                isSplit = editorMode == KnowledgeEditorMode.SPLIT,
            )
        }
        if (editorMode != KnowledgeEditorMode.EDIT) {
            MarkdownPreviewPane(
                content = content,
                isSplit = editorMode == KnowledgeEditorMode.SPLIT,
            )
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
    isSaving: Boolean,
    saveMessage: String,
    editorMode: KnowledgeEditorMode,
    canEdit: Boolean,
    canManageTopic: Boolean,
    onEditorModeChange: (KnowledgeEditorMode) -> Unit,
    onManageTopic: () -> Unit,
    onSave: () -> Unit,
    onStatusAction: (() -> Unit)?,
    statusActionLabel: String?,
    onExport: () -> Unit,
    onCopyReference: () -> Unit,
) {
    Div({
        style {
            padding(8.px, 16.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            backgroundColor(Color(SilkColors.surfaceElevated))
        }
    }) {
        Span({
            style { fontSize(16.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) }
        }) { Text(title) }
        Div({
            style { display(DisplayStyle.Flex); property("gap", "12px"); alignItems(AlignItems.Center) }
        }) {
            KnowledgeEditorModeSwitch(
                selectedMode = editorMode,
                onModeChange = onEditorModeChange,
            )
            if (saveMessage.isNotEmpty()) {
                Span({ style { fontSize(12.px); color(Color(SilkColors.success)) } }) { Text(saveMessage) }
            }
            if (canManageTopic) {
                KnowledgeToolbarButton(
                    label = "权限",
                    background = SilkColors.textSecondary,
                    onClick = onManageTopic,
                )
            }
            KnowledgeToolbarButton(
                label = "复制引用",
                background = SilkColors.primaryDark,
                onClick = onCopyReference,
            )
            KnowledgeToolbarButton(
                label = if (!canEdit) "只读" else if (isSaving) "保存中..." else "保存",
                background = SilkColors.primary,
                enabled = canEdit && !isSaving,
                onClick = onSave,
            )
            if (statusActionLabel != null && onStatusAction != null) {
                KnowledgeToolbarButton(
                    label = statusActionLabel,
                    background = SilkColors.success,
                    enabled = canEdit && !isSaving,
                    onClick = onStatusAction,
                )
            }
            KnowledgeToolbarButton(
                label = "导出 Obsidian",
                background = SilkColors.info,
                onClick = onExport,
            )
        }
    }
}

@Composable
private fun KnowledgeEntryMetaBar(
    topic: KBTopicItem?,
    entry: KBEntryItem,
    spaceLabel: String?,
    permissionLabel: String?,
) {
    Div({
        style {
            padding(10.px, 16.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            property("gap", "8px")
            property("flex-wrap", "wrap")
            backgroundColor(Color(SilkColors.surface))
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
        val sourceLabel = when (entry.source.sourceType) {
            KBSourceType.MANUAL -> "手动创建"
            KBSourceType.CHAT -> "聊天沉淀"
            KBSourceType.WORKFLOW -> "工作流沉淀"
            KBSourceType.MEETING -> "会议沉淀"
            KBSourceType.FILE -> "文件导入"
            KBSourceType.URL -> "URL 导入"
        }
        KnowledgeBadge(sourceLabel, SilkColors.primary)
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
    onModeChange: (KnowledgeEditorMode) -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("overflow", "hidden")
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            borderRadius(7.px)
            backgroundColor(Color(SilkColors.surface))
        }
    }) {
        KnowledgeEditorMode.entries.forEach { mode ->
            Button({
                style {
                    backgroundColor(Color(if (selectedMode == mode) SilkColors.primary else SilkColors.surface))
                    color(Color(if (selectedMode == mode) "#FFFFFF" else SilkColors.textSecondary))
                    border(0.px)
                    borderRadius(0.px)
                    padding(6.px, 12.px)
                    property("cursor", "pointer")
                    fontSize(12.px)
                    fontWeight(if (selectedMode == mode) "600" else "500")
                    property("min-width", "52px")
                }
                onClick { onModeChange(mode) }
            }) { Text(mode.label) }
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
private fun EmptyEditorState() {
    Div({
        style {
            property("flex", "1")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            flexDirection(FlexDirection.Column)
        }
    }) {
        Span({ style { fontSize(48.px); marginBottom(16.px) } }) { Text("\uD83D\uDCDA") }
        Span({
            style { fontSize(18.px); color(Color(SilkColors.textSecondary)) }
        }) { Text("选择或创建条目开始编辑") }
        Span({
            style { fontSize(14.px); color(Color(SilkColors.textLight)); marginTop(8.px) }
        }) { Text("内容将自动归类到 Obsidian 知识库") }
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

@Composable
private fun TopicAccessDialog(
    topicName: String,
    topicProject: String,
    isTeamTopic: Boolean,
    readUserIds: String,
    writeUserIds: String,
    manageUserIds: String,
    writeLocked: Boolean,
    teamMembersCanWrite: Boolean,
    onTopicNameChange: (String) -> Unit,
    onTopicProjectChange: (String) -> Unit,
    onReadUserIdsChange: (String) -> Unit,
    onWriteUserIdsChange: (String) -> Unit,
    onManageUserIdsChange: (String) -> Unit,
    onWriteLockedChange: (Boolean) -> Unit,
    onTeamMembersCanWriteChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalDialog(title = "主题权限", onDismiss = onDismiss) {
        LabeledInput("主题名称", topicName, onTopicNameChange)
        LabeledInput("所属项目（可选）", topicProject, onTopicProjectChange)
        LabeledInput("只读用户，逗号分隔", readUserIds, onReadUserIdsChange)
        LabeledInput("可写用户，逗号分隔", writeUserIds, onWriteUserIdsChange)
        LabeledInput("可管理用户，逗号分隔", manageUserIds, onManageUserIdsChange)
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
private fun KnowledgeColumnHeader(title: String, actionLabel: String?, actionEnabled: Boolean = true, onAction: () -> Unit) {
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
        if (actionLabel != null) {
            Button({
                style {
                    backgroundColor(Color(if (actionEnabled) SilkColors.primary else SilkColors.border))
                    color(Color.white)
                    border(0.px)
                    borderRadius(6.px)
                    padding(4.px, 10.px)
                    property("cursor", if (actionEnabled) "pointer" else "not-allowed")
                    fontSize(12.px)
                }
                if (!actionEnabled) {
                    attr("disabled", "true")
                }
                onClick {
                    if (actionEnabled) {
                        onAction()
                    }
                }
            }) { Text(actionLabel) }
        }
    }
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
    onReadUserIdsChange: (String) -> Unit,
    onWriteUserIdsChange: (String) -> Unit,
    onManageUserIdsChange: (String) -> Unit,
    onWriteLockedChange: (Boolean) -> Unit,
    onTeamMembersCanWriteChange: (Boolean) -> Unit,
) {
    onVisibilityChange(false)
    onTopicNameChange("")
    onTopicProjectChange("")
    onReadUserIdsChange("")
    onWriteUserIdsChange("")
    onManageUserIdsChange("")
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
    editorContent: String,
    userId: String,
    onSavingChange: (Boolean) -> Unit,
    onSaveMessageChange: (String) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
) {
    if (entry == null || topic == null) return
    onSavingChange(true)
    onSaveMessageChange("")
    val updated = ApiClient.updateKBEntry(entry.id, null, editorContent, null, userId)
    if (updated != null) {
        onSelectedEntryChange(updated)
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

@Composable
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

    var showCreateTopicDialog by remember { mutableStateOf(false) }
    var newTopicName by remember { mutableStateOf("") }
    var newTopicProject by remember { mutableStateOf("") }
    var newTopicSpaceId by remember { mutableStateOf(PERSONAL_SPACE_ID) }

    var showCreateEntryDialog by remember { mutableStateOf(false) }
    var newEntryTitle by remember { mutableStateOf("") }
    var showTopicAccessDialog by remember { mutableStateOf(false) }
    var editableTopicName by remember { mutableStateOf("") }
    var editableTopicProject by remember { mutableStateOf("") }
    var editableReadUserIds by remember { mutableStateOf("") }
    var editableWriteUserIds by remember { mutableStateOf("") }
    var editableManageUserIds by remember { mutableStateOf("") }
    var editableWriteLocked by remember { mutableStateOf(false) }
    var editableTeamMembersCanWrite by remember { mutableStateOf(true) }

    var editorContent by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("") }
    var editorMode by remember { mutableStateOf(KnowledgeEditorMode.SPLIT) }
    var entryFilter by remember { mutableStateOf(KnowledgeEntryFilter.ALL) }

    LaunchedEffect(user.id) {
        isLoading = true
        val groupsResponse = ApiClient.getUserGroups(user.id)
        userGroups = (groupsResponse.groups ?: emptyList()).filterNot { it.name.startsWith("wf_") }
        topics = ApiClient.getKBTopics(user.id)
        isLoading = false
    }

    val spaceOptions = remember(userGroups) { buildKnowledgeSpaceOptions(userGroups) }
    val filteredTopics = remember(topics, selectedSpaceId) { filterTopicsForSpace(topics, selectedSpaceId) }
    val filteredEntries = remember(entries, entryFilter) { filterKnowledgeEntries(entries, entryFilter) }
    val canEditSelectedTopic = selectedTopic?.let { canWriteKnowledgeTopic(it, user.id, userGroups) } ?: false
    val canManageSelectedTopic = selectedTopic?.let { canManageKnowledgeTopic(it, user.id, userGroups) } ?: false
    val selectedTopicSpaceLabel = selectedTopic?.let { topicSpaceLabel(it, userGroups) }
    val selectedTopicPermissionLabel = selectedTopic?.let { topicPermissionLabel(it, user.id, userGroups) }
    val selectedEntryStatusAction = selectedEntry?.let(::knowledgeStatusAction)

    LaunchedEffect(selectedSpaceId, topics) {
        if (selectedTopic != null && filteredTopics.none { it.id == selectedTopic?.id }) {
            selectedTopic = null
            selectedEntry = null
            entries = emptyList()
            editorContent = ""
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
            property("overflow", "hidden")
            property("background", SilkColors.backgroundGradient)
        }
    }) {
        TopicSidebar(
            spaceOptions = spaceOptions,
            selectedSpaceId = selectedSpaceId,
            topics = filteredTopics,
            isLoading = isLoading,
            selectedTopic = selectedTopic,
            onCreateTopic = {
                newTopicSpaceId = selectedSpaceId
                showCreateTopicDialog = true
            },
            onSpaceSelect = { selectedSpace ->
                selectedSpaceId = selectedSpace.id
            },
            userId = user.id,
            groups = userGroups,
            onTopicSelect = { topic ->
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
        )
        EntrySidebar(
            selectedTopic = selectedTopic,
            entries = filteredEntries,
            selectedEntry = selectedEntry,
            selectedFilter = entryFilter,
            canCreateEntry = canEditSelectedTopic,
            onFilterChange = { entryFilter = it },
            onCreateEntry = { showCreateEntryDialog = true },
            onEntrySelect = { entry ->
                loadKnowledgeEntry(
                    entry = entry,
                    onSelectedEntryChange = { selectedEntry = it },
                    onEditorContentChange = { editorContent = it },
                )
            },
        )
        KnowledgeEditorPane(
            selectedTopic = selectedTopic,
            selectedEntry = selectedEntry,
            editorContent = editorContent,
            isSaving = isSaving,
            saveMessage = saveMessage,
            editorMode = editorMode,
            canEdit = canEditSelectedTopic,
            canManageTopic = canManageSelectedTopic,
            spaceLabel = selectedTopicSpaceLabel,
            permissionLabel = selectedTopicPermissionLabel,
            onContentChange = {
                editorContent = it
                if (saveMessage.isNotEmpty()) {
                    saveMessage = ""
                }
            },
            onEditorModeChange = { editorMode = it },
            onManageTopic = {
                val topic = selectedTopic ?: return@KnowledgeEditorPane
                editableTopicName = topic.name
                editableTopicProject = topic.project
                editableReadUserIds = knowledgeUserIdsToCsv(topic.accessPolicy.readUserIds)
                editableWriteUserIds = knowledgeUserIdsToCsv(topic.accessPolicy.writeUserIds)
                editableManageUserIds = knowledgeUserIdsToCsv(topic.accessPolicy.manageUserIds)
                editableWriteLocked = topic.accessPolicy.writeLocked
                editableTeamMembersCanWrite = topic.accessPolicy.teamMembersCanWrite
                showTopicAccessDialog = true
            },
            onSave = {
                scope.launch {
                    saveKnowledgeEntry(
                        entry = selectedEntry,
                        topic = selectedTopic,
                        editorContent = editorContent,
                        userId = user.id,
                        onSavingChange = { isSaving = it },
                        onSaveMessageChange = { saveMessage = it },
                        onSelectedEntryChange = { selectedEntry = it },
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
                            readUserIds = csvToKnowledgeUserIds(editableReadUserIds),
                            writeUserIds = csvToKnowledgeUserIds(editableWriteUserIds),
                            manageUserIds = csvToKnowledgeUserIds(editableManageUserIds),
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
}

// ---- Shared dialog helpers ----

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
fun DialogActions(onCancel: () -> Unit, onConfirm: () -> Unit, confirmLabel: String = "确定") {
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
                backgroundColor(Color(SilkColors.primary))
                color(Color.white)
                border(0.px)
                borderRadius(6.px)
                padding(8.px, 16.px)
                property("cursor", "pointer")
            }
            onClick { onConfirm() }
        }) { Text(confirmLabel) }
    }
}
