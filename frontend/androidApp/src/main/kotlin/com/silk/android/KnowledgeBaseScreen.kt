package com.silk.android

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
enum class KnowledgeSpaceType {
    PERSONAL,
    TEAM,
}

@Serializable
data class KBAccessPolicy(
    val readUserIds: List<String> = emptyList(),
    val writeUserIds: List<String> = emptyList(),
    val manageUserIds: List<String> = emptyList(),
    val writeLocked: Boolean = false,
    val teamMembersCanWrite: Boolean = true,
)

@Serializable
enum class KBEntryStatus {
    CANDIDATE,
    PUBLISHED,
    ARCHIVED,
    DELETED,
}

@Serializable
enum class KBSourceType {
    MANUAL,
    CHAT,
    AI_RESPONSE,
    WORKFLOW,
    MEETING,
    FILE,
    URL,
}

@Serializable
data class KBEntrySource(
    val sourceType: KBSourceType = KBSourceType.MANUAL,
    val sourceGroupId: String? = null,
    val workflowId: String? = null,
    val messageIds: List<String> = emptyList(),
    val confidence: Double? = null,
)

@Serializable
data class KBTopicItem(
    val id: String,
    val name: String,
    val project: String = "",
    val ownerId: String = "",
    val spaceType: KnowledgeSpaceType = KnowledgeSpaceType.PERSONAL,
    val groupId: String? = null,
    val accessPolicy: KBAccessPolicy = KBAccessPolicy(),
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class KBEntryItem(
    val id: String,
    val topicId: String = "",
    val title: String,
    val content: String = "",
    val tags: List<String> = emptyList(),
    val ownerId: String = "",
    val status: KBEntryStatus = KBEntryStatus.PUBLISHED,
    val source: KBEntrySource = KBEntrySource(),
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

private enum class KBSubPage { TOPICS, ENTRIES, EDITOR }

private enum class KnowledgeEntryFilter(val label: String) {
    ALL("全部"),
    CANDIDATE("候选"),
    PUBLISHED("已发布"),
    ARCHIVED("已归档"),
}

private const val PERSONAL_SPACE_ID = "__personal__"

private data class KnowledgeSpaceOption(
    val id: String,
    val label: String,
    val type: KnowledgeSpaceType,
    val groupId: String? = null,
)

private fun buildKnowledgeSpaceOptions(groups: List<Group>): List<KnowledgeSpaceOption> {
    val teamSpaces = groups
        .filterNot { it.name.startsWith("wf_") }
        .sortedBy { it.name.lowercase() }
        .map { group ->
            KnowledgeSpaceOption(
                id = group.id,
                label = group.name,
                type = KnowledgeSpaceType.TEAM,
                groupId = group.id,
            )
        }
    return listOf(KnowledgeSpaceOption(PERSONAL_SPACE_ID, "个人", KnowledgeSpaceType.PERSONAL)) + teamSpaces
}

private fun filterTopicsForSpace(topics: List<KBTopicItem>, selectedSpaceId: String): List<KBTopicItem> =
    topics.filter { topic ->
        when (selectedSpaceId) {
            PERSONAL_SPACE_ID -> topic.spaceType == KnowledgeSpaceType.PERSONAL
            else -> topic.spaceType == KnowledgeSpaceType.TEAM && topic.groupId == selectedSpaceId
        }
    }

private fun canWriteKnowledgeTopic(topic: KBTopicItem?, userId: String): Boolean {
    if (topic == null) return false
    if (topic.ownerId == userId) return true
    if (userId in topic.accessPolicy.manageUserIds) return true
    if (topic.accessPolicy.writeLocked) return false
    if (userId in topic.accessPolicy.writeUserIds) return true
    return topic.spaceType == KnowledgeSpaceType.TEAM && topic.accessPolicy.teamMembersCanWrite
}

private fun topicSpaceLabel(topic: KBTopicItem, groups: List<Group>): String =
    when (topic.spaceType) {
        KnowledgeSpaceType.PERSONAL -> "个人"
        KnowledgeSpaceType.TEAM -> groups.find { it.id == topic.groupId }?.name ?: "团队"
    }

private fun topicPermissionLabel(topic: KBTopicItem, userId: String): String =
    if (canWriteKnowledgeTopic(topic, userId)) "可编辑" else "只读"

private fun entryStatusLabel(status: KBEntryStatus): String =
    when (status) {
        KBEntryStatus.CANDIDATE -> "候选"
        KBEntryStatus.PUBLISHED -> "已发布"
        KBEntryStatus.ARCHIVED -> "已归档"
        KBEntryStatus.DELETED -> "已删除"
    }

private fun entrySourceLabel(sourceType: KBSourceType): String =
    when (sourceType) {
        KBSourceType.MANUAL -> "手动"
        KBSourceType.CHAT -> "聊天"
        KBSourceType.AI_RESPONSE -> "AI"
        KBSourceType.WORKFLOW -> "工作流"
        KBSourceType.MEETING -> "会议"
        KBSourceType.FILE -> "文件"
        KBSourceType.URL -> "URL"
    }

private fun entrySourceDetails(entry: KBEntryItem, groups: List<Group>): List<Pair<String, String>> {
    val details = mutableListOf<Pair<String, String>>()
    entry.source.sourceGroupId?.takeIf { it.isNotBlank() }?.let { groupId ->
        val groupName = groups.find { it.id == groupId }?.name
        details += "来源群组" to if (groupName.isNullOrBlank()) groupId else "$groupName ($groupId)"
    }
    entry.source.workflowId?.takeIf { it.isNotBlank() }?.let { workflowId ->
        details += "工作流" to workflowId
    }
    if (entry.source.messageIds.isNotEmpty()) {
        val preview = entry.source.messageIds.take(3).joinToString(", ")
        val messageLabel = if (entry.source.messageIds.size > 3) "$preview 等 ${entry.source.messageIds.size} 条" else preview
        details += "消息" to messageLabel
    }
    entry.source.confidence?.let { confidence ->
        details += "置信度" to "${(confidence * 100).toInt()}%"
    }
    entry.createdBy.takeIf { it.isNotBlank() }?.let { createdBy ->
        details += "创建人" to createdBy
    }
    if (entry.updatedBy.isNotBlank() && entry.updatedBy != entry.createdBy) {
        details += "更新人" to entry.updatedBy
    }
    return details
}

private fun filterKnowledgeEntries(entries: List<KBEntryItem>, filter: KnowledgeEntryFilter): List<KBEntryItem> =
    when (filter) {
        KnowledgeEntryFilter.ALL -> entries
        KnowledgeEntryFilter.CANDIDATE -> entries.filter { it.status == KBEntryStatus.CANDIDATE }
        KnowledgeEntryFilter.PUBLISHED -> entries.filter { it.status == KBEntryStatus.PUBLISHED }
        KnowledgeEntryFilter.ARCHIVED -> entries.filter { it.status == KBEntryStatus.ARCHIVED }
    }

private fun knowledgeStatusAction(entry: KBEntryItem): Pair<String, KBEntryStatus>? =
    when (entry.status) {
        KBEntryStatus.CANDIDATE -> "发布" to KBEntryStatus.PUBLISHED
        KBEntryStatus.PUBLISHED -> "归档" to KBEntryStatus.ARCHIVED
        KBEntryStatus.ARCHIVED -> "重新发布" to KBEntryStatus.PUBLISHED
        KBEntryStatus.DELETED -> null
    }

private fun filterForStatus(status: KBEntryStatus): KnowledgeEntryFilter =
    when (status) {
        KBEntryStatus.CANDIDATE -> KnowledgeEntryFilter.CANDIDATE
        KBEntryStatus.PUBLISHED -> KnowledgeEntryFilter.PUBLISHED
        KBEntryStatus.ARCHIVED -> KnowledgeEntryFilter.ARCHIVED
        KBEntryStatus.DELETED -> KnowledgeEntryFilter.ALL
    }

private fun buildDefaultMeetingCaptureTitle(topic: KBTopicItem?): String =
    topic?.name?.takeIf { it.isNotBlank() }?.let { "$it 会议纪要" } ?: run {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        "会议纪要 $date"
    }

private fun parseKnowledgeCaptureTags(raw: String): List<String> =
    raw.split(',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

private fun parseKnowledgeCaptureConfidence(raw: String): Double? =
    raw.trim()
        .takeIf { it.isNotEmpty() }
        ?.toDoubleOrNull()
        ?.coerceIn(0.0, 1.0)

private fun buildMeetingCaptureSource(topic: KBTopicItem?, confidenceText: String): KBEntrySource =
    KBEntrySource(
        sourceType = KBSourceType.MEETING,
        sourceGroupId = topic?.takeIf { it.spaceType == KnowledgeSpaceType.TEAM }?.groupId,
        confidence = parseKnowledgeCaptureConfidence(confidenceText),
    )

@Composable
private fun HandleKnowledgeBaseBackNavigation(
    subPage: KBSubPage,
    onBackToTopics: () -> Unit,
    onBackToEntries: () -> Unit,
) {
    BackHandler(enabled = subPage != KBSubPage.TOPICS) {
        when (subPage) {
            KBSubPage.EDITOR -> onBackToEntries()
            KBSubPage.ENTRIES -> onBackToTopics()
            KBSubPage.TOPICS -> Unit
        }
    }
}

private suspend fun saveKnowledgeEntry(
    entry: KBEntryItem,
    topic: KBTopicItem,
    editorContent: String,
    userId: String,
    onSavingChange: (Boolean) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onSaved: () -> Unit,
) {
    onSavingChange(true)
    try {
        ApiClient.updateKBEntry(entry.id, null, editorContent, null, userId)
        onEntriesChange(ApiClient.getKBEntries(topic.id, userId))
        onSaved()
    } finally {
        onSavingChange(false)
    }
}

private suspend fun updateKnowledgeEntryStatus(
    entry: KBEntryItem,
    topic: KBTopicItem,
    editorContent: String,
    userId: String,
    status: KBEntryStatus,
    onEntryUpdated: (KBEntryItem) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onFilterChange: (KnowledgeEntryFilter) -> Unit,
    onResult: (Boolean) -> Unit,
) {
    val updated = ApiClient.updateKBEntry(
        entryId = entry.id,
        title = entry.title,
        content = editorContent,
        tags = entry.tags,
        userId = userId,
        status = status,
    )
    if (updated != null) {
        onEntryUpdated(updated)
        onEntriesChange(ApiClient.getKBEntries(topic.id, userId))
        onFilterChange(filterForStatus(status))
        onResult(true)
    } else {
        onResult(false)
    }
}

private suspend fun createKnowledgeTopic(
    name: String,
    project: String,
    userId: String,
    selectedSpace: KnowledgeSpaceOption,
    onTopicsChange: (List<KBTopicItem>) -> Unit,
    onFinished: () -> Unit,
) {
    ApiClient.createKBTopic(
        name = name.trim(),
        project = project.trim(),
        userId = userId,
        spaceType = selectedSpace.type,
        groupId = selectedSpace.groupId,
    )
    onTopicsChange(ApiClient.getKBTopics(userId))
    onFinished()
}

private suspend fun createKnowledgeEntry(
    topic: KBTopicItem,
    title: String,
    userId: String,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onFilterChange: (KnowledgeEntryFilter) -> Unit,
    onEntryCreated: (KBEntryItem) -> Unit,
    onFinished: () -> Unit,
) {
    val entry = ApiClient.createKBEntry(
        topic.id,
        title.trim(),
        "",
        emptyList(),
        userId,
    )
    if (entry != null) {
        onEntriesChange(ApiClient.getKBEntries(topic.id, userId))
        onFilterChange(KnowledgeEntryFilter.ALL)
        onEntryCreated(entry)
    }
    onFinished()
}

private suspend fun submitMeetingKnowledgeCapture(
    userId: String,
    topics: List<KBTopicItem>,
    selectedTopicId: String,
    title: String,
    content: String,
    tagsText: String,
    status: KBEntryStatus,
    confidenceText: String,
    onSavingChange: (Boolean) -> Unit,
    onResultMessageChange: (String?) -> Unit,
    onSelectedTopicChange: (KBTopicItem?) -> Unit,
    onEntriesChange: (List<KBEntryItem>) -> Unit,
    onSelectedEntryChange: (KBEntryItem?) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onFilterChange: (KnowledgeEntryFilter) -> Unit,
    onSubPageChange: (KBSubPage) -> Unit,
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
    onSelectedTopicChange(topic)
    onEntriesChange(refreshedEntries)
    onSelectedEntryChange(selectedEntry)
    onEditorContentChange(selectedEntry.content)
    onFilterChange(filterForStatus(selectedEntry.status))
    onSubPageChange(KBSubPage.EDITOR)
    onSavingChange(false)
    onVisibilityChange(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseScreen(appState: AppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var subPage by remember { mutableStateOf(KBSubPage.TOPICS) }
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var selectedSpaceId by remember { mutableStateOf(PERSONAL_SPACE_ID) }
    var topics by remember { mutableStateOf<List<KBTopicItem>>(emptyList()) }
    var selectedTopic by remember { mutableStateOf<KBTopicItem?>(null) }
    var entries by remember { mutableStateOf<List<KBEntryItem>>(emptyList()) }
    var selectedEntry by remember { mutableStateOf<KBEntryItem?>(null) }
    var entryFilter by remember { mutableStateOf(KnowledgeEntryFilter.ALL) }
    var isLoading by remember { mutableStateOf(true) }

    var showCreateTopicDialog by remember { mutableStateOf(false) }
    var newTopicName by remember { mutableStateOf("") }
    var newTopicProject by remember { mutableStateOf("") }
    var showCreateEntryDialog by remember { mutableStateOf(false) }
    var newEntryTitle by remember { mutableStateOf("") }
    var showMeetingCaptureDialog by remember { mutableStateOf(false) }
    var meetingCaptureTopicId by remember { mutableStateOf("") }
    var meetingCaptureTitle by remember { mutableStateOf("") }
    var meetingCaptureContent by remember { mutableStateOf("") }
    var meetingCaptureTagsText by remember { mutableStateOf("meeting, minutes") }
    var meetingCaptureStatus by remember { mutableStateOf(KBEntryStatus.CANDIDATE) }
    var meetingCaptureConfidenceText by remember { mutableStateOf("0.90") }
    var meetingCaptureResultMessage by remember { mutableStateOf<String?>(null) }
    var editorContent by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(user.id) {
        isLoading = true
        groups = ApiClient.getUserGroups(user.id).groups.orEmpty().filterNot { it.name.startsWith("wf_") }
        topics = ApiClient.getKBTopics(user.id)
        isLoading = false
    }

    val spaceOptions = remember(groups) { buildKnowledgeSpaceOptions(groups) }
    val selectedSpace = remember(spaceOptions, selectedSpaceId) {
        spaceOptions.find { it.id == selectedSpaceId } ?: spaceOptions.first()
    }
    val filteredTopics = remember(topics, selectedSpaceId) {
        filterTopicsForSpace(topics, selectedSpaceId)
    }
    val canWriteSelectedTopic = remember(selectedTopic, user.id) {
        canWriteKnowledgeTopic(selectedTopic, user.id)
    }
    val filteredEntries = remember(entries, entryFilter) {
        filterKnowledgeEntries(entries, entryFilter)
    }

    val backToTopics = {
        subPage = KBSubPage.TOPICS
        selectedTopic = null
        entries = emptyList()
    }
    val backToEntries = {
        subPage = KBSubPage.ENTRIES
        selectedEntry = null
        editorContent = ""
    }

    HandleKnowledgeBaseBackNavigation(
        subPage = subPage,
        onBackToTopics = backToTopics,
        onBackToEntries = backToEntries,
    )

    KnowledgeBasePageHost(
        subPage = subPage,
        userId = user.id,
        groups = groups,
        selectedSpace = selectedSpace,
        spaceOptions = spaceOptions,
        filteredTopics = filteredTopics,
        selectedTopic = selectedTopic,
        entries = filteredEntries,
        selectedEntry = selectedEntry,
        isLoading = isLoading,
        editorContent = editorContent,
        isSaving = isSaving,
        selectedEntryFilter = entryFilter,
        canWriteSelectedTopic = canWriteSelectedTopic,
        onSpaceSelected = { selectedSpaceId = it },
        onEntryFilterChange = { entryFilter = it },
        onShowCreateTopic = { showCreateTopicDialog = true },
        onTopicSelected = { topic ->
            selectedTopic = topic
            entryFilter = KnowledgeEntryFilter.ALL
            scope.launch { entries = ApiClient.getKBEntries(topic.id, user.id) }
            subPage = KBSubPage.ENTRIES
        },
        onBackToTopics = backToTopics,
        onShowCreateEntry = { showCreateEntryDialog = true },
        onShowMeetingCapture = {
            val topic = selectedTopic ?: return@KnowledgeBasePageHost
            meetingCaptureTopicId = topic.id
            meetingCaptureTitle = buildDefaultMeetingCaptureTitle(topic)
            meetingCaptureContent = ""
            meetingCaptureTagsText = "meeting, minutes"
            meetingCaptureStatus = KBEntryStatus.CANDIDATE
            meetingCaptureConfidenceText = "0.90"
            meetingCaptureResultMessage = null
            showMeetingCaptureDialog = true
        },
        onEntrySelected = { entry ->
            selectedEntry = entry
            editorContent = entry.content
            subPage = KBSubPage.EDITOR
        },
        onBackToEntries = backToEntries,
        onEditorContentChange = { editorContent = it },
        onSaveEntry = {
            val currentEntry = selectedEntry ?: return@KnowledgeBasePageHost
            val currentTopic = selectedTopic ?: return@KnowledgeBasePageHost
            scope.launch {
                saveKnowledgeEntry(
                    entry = currentEntry,
                    topic = currentTopic,
                    editorContent = editorContent,
                    userId = user.id,
                    onSavingChange = { isSaving = it },
                    onEntriesChange = { entries = it },
                    onSaved = { Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show() },
                )
            }
        },
        onStatusAction = { status ->
            val currentEntry = selectedEntry ?: return@KnowledgeBasePageHost
            val currentTopic = selectedTopic ?: return@KnowledgeBasePageHost
            scope.launch {
                updateKnowledgeEntryStatus(
                    entry = currentEntry,
                    topic = currentTopic,
                    editorContent = editorContent,
                    userId = user.id,
                    status = status,
                    onEntryUpdated = { selectedEntry = it },
                    onEntriesChange = { entries = it },
                    onFilterChange = { entryFilter = it },
                    onResult = { success ->
                        val message = if (success) "条目状态已更新" else "状态更新失败"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        },
    )

    KnowledgeBaseDialogs(
        isSaving = isSaving,
        showCreateTopicDialog = showCreateTopicDialog,
        newTopicName = newTopicName,
        newTopicProject = newTopicProject,
        selectedSpaceLabel = selectedSpace.label,
        onTopicNameChange = { newTopicName = it },
        onTopicProjectChange = { newTopicProject = it },
        onDismissCreateTopic = {
            showCreateTopicDialog = false
            newTopicName = ""
            newTopicProject = ""
        },
        onConfirmCreateTopic = {
            if (newTopicName.isNotBlank()) {
                scope.launch {
                    createKnowledgeTopic(
                        name = newTopicName,
                        project = newTopicProject,
                        userId = user.id,
                        selectedSpace = selectedSpace,
                        onTopicsChange = { topics = it },
                        onFinished = {
                            showCreateTopicDialog = false
                            newTopicName = ""
                            newTopicProject = ""
                        },
                    )
                }
            }
        },
        showCreateEntryDialog = showCreateEntryDialog,
        selectedTopic = selectedTopic,
        newEntryTitle = newEntryTitle,
        onEntryTitleChange = { newEntryTitle = it },
        onDismissCreateEntry = {
            showCreateEntryDialog = false
            newEntryTitle = ""
        },
        onConfirmCreateEntry = {
            val currentTopic = selectedTopic ?: return@KnowledgeBaseDialogs
            if (newEntryTitle.isNotBlank()) {
                scope.launch {
                    createKnowledgeEntry(
                        topic = currentTopic,
                        title = newEntryTitle,
                        user.id,
                        onEntriesChange = { entries = it },
                        onFilterChange = { entryFilter = it },
                        onEntryCreated = { entry ->
                            selectedEntry = entry
                            editorContent = entry.content
                            subPage = KBSubPage.EDITOR
                        },
                        onFinished = {
                            showCreateEntryDialog = false
                            newEntryTitle = ""
                        },
                    )
                }
            }
        },
        showMeetingCaptureDialog = showMeetingCaptureDialog,
        meetingCaptureTopics = filteredTopics.filter { canWriteKnowledgeTopic(it, user.id) },
        meetingCaptureTopicId = meetingCaptureTopicId,
        meetingCaptureTitle = meetingCaptureTitle,
        meetingCaptureContent = meetingCaptureContent,
        meetingCaptureTagsText = meetingCaptureTagsText,
        meetingCaptureStatus = meetingCaptureStatus,
        meetingCaptureConfidenceText = meetingCaptureConfidenceText,
        meetingCaptureResultMessage = meetingCaptureResultMessage,
        onMeetingCaptureTopicChange = { meetingCaptureTopicId = it },
        onMeetingCaptureTitleChange = { meetingCaptureTitle = it },
        onMeetingCaptureContentChange = { meetingCaptureContent = it },
        onMeetingCaptureTagsTextChange = { meetingCaptureTagsText = it },
        onMeetingCaptureStatusChange = { meetingCaptureStatus = it },
        onMeetingCaptureConfidenceTextChange = { meetingCaptureConfidenceText = it },
        onDismissMeetingCapture = {
            showMeetingCaptureDialog = false
            meetingCaptureResultMessage = null
        },
        onConfirmMeetingCapture = {
            scope.launch {
                val captureStatus = meetingCaptureStatus
                submitMeetingKnowledgeCapture(
                    userId = user.id,
                    topics = topics,
                    selectedTopicId = meetingCaptureTopicId,
                    title = meetingCaptureTitle,
                    content = meetingCaptureContent,
                    tagsText = meetingCaptureTagsText,
                    status = captureStatus,
                    confidenceText = meetingCaptureConfidenceText,
                    onSavingChange = { isSaving = it },
                    onResultMessageChange = { meetingCaptureResultMessage = it },
                    onSelectedTopicChange = { selectedTopic = it },
                    onEntriesChange = { entries = it },
                    onSelectedEntryChange = { selectedEntry = it },
                    onEditorContentChange = { editorContent = it },
                    onFilterChange = { entryFilter = it },
                    onSubPageChange = { subPage = it },
                    onVisibilityChange = { showMeetingCaptureDialog = it },
                )
                if (!showMeetingCaptureDialog) {
                    Toast.makeText(
                        context,
                        if (captureStatus == KBEntryStatus.PUBLISHED) "会议纪要已发布到知识库" else "会议纪要已作为候选入库",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        },
    )
}

@Composable
private fun KnowledgeBasePageHost(
    subPage: KBSubPage,
    userId: String,
    groups: List<Group>,
    selectedSpace: KnowledgeSpaceOption,
    spaceOptions: List<KnowledgeSpaceOption>,
    filteredTopics: List<KBTopicItem>,
    selectedTopic: KBTopicItem?,
    entries: List<KBEntryItem>,
    selectedEntry: KBEntryItem?,
    isLoading: Boolean,
    editorContent: String,
    isSaving: Boolean,
    selectedEntryFilter: KnowledgeEntryFilter,
    canWriteSelectedTopic: Boolean,
    onSpaceSelected: (String) -> Unit,
    onEntryFilterChange: (KnowledgeEntryFilter) -> Unit,
    onShowCreateTopic: () -> Unit,
    onTopicSelected: (KBTopicItem) -> Unit,
    onBackToTopics: () -> Unit,
    onShowCreateEntry: () -> Unit,
    onShowMeetingCapture: () -> Unit,
    onEntrySelected: (KBEntryItem) -> Unit,
    onBackToEntries: () -> Unit,
    onEditorContentChange: (String) -> Unit,
    onSaveEntry: () -> Unit,
    onStatusAction: (KBEntryStatus) -> Unit,
) {
    when (subPage) {
        KBSubPage.TOPICS -> KnowledgeBaseTopicsPage(
            topics = filteredTopics,
            groups = groups,
            userId = userId,
            isLoading = isLoading,
            selectedSpace = selectedSpace,
            spaceOptions = spaceOptions,
            onSpaceSelected = onSpaceSelected,
            onShowCreateTopic = onShowCreateTopic,
            onTopicSelected = onTopicSelected,
        )
        KBSubPage.ENTRIES -> KnowledgeBaseEntriesPage(
            userId = userId,
            groups = groups,
            selectedTopic = selectedTopic,
            entries = entries,
            selectedFilter = selectedEntryFilter,
            canWriteSelectedTopic = canWriteSelectedTopic,
            onFilterChange = onEntryFilterChange,
            onBack = onBackToTopics,
            onShowCreateEntry = onShowCreateEntry,
            onShowMeetingCapture = onShowMeetingCapture,
            onEntrySelected = onEntrySelected,
        )
        KBSubPage.EDITOR -> KnowledgeBaseEditorPage(
            userId = userId,
            groups = groups,
            selectedTopic = selectedTopic,
            selectedEntry = selectedEntry,
            editorContent = editorContent,
            isSaving = isSaving,
            canWriteSelectedTopic = canWriteSelectedTopic,
            onBack = onBackToEntries,
            onEditorContentChange = onEditorContentChange,
            onSave = onSaveEntry,
            onStatusAction = onStatusAction,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeBaseTopicsPage(
    topics: List<KBTopicItem>,
    groups: List<Group>,
    userId: String,
    isLoading: Boolean,
    selectedSpace: KnowledgeSpaceOption,
    spaceOptions: List<KnowledgeSpaceOption>,
    onSpaceSelected: (String) -> Unit,
    onShowCreateTopic: () -> Unit,
    onTopicSelected: (KBTopicItem) -> Unit,
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onShowCreateTopic,
                containerColor = SilkColors.primary,
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建主题")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("知识库", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(spaceOptions) { space ->
                    FilterChip(
                        selected = selectedSpace.id == space.id,
                        onClick = { onSpaceSelected(space.id) },
                        label = { Text(space.label) },
                    )
                }
            }
            Divider(color = SilkColors.divider)
            when {
                isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                topics.isEmpty() -> KnowledgeBaseEmptyState(
                    icon = "📚",
                    title = "当前空间暂无主题",
                    subtitle = "点击右下角 + 在${selectedSpace.label}空间创建主题",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(topics) { topic ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onTopicSelected(topic) },
                            colors = CardDefaults.cardColors(containerColor = SilkColors.cardBackground),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        topic.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    KnowledgeBadge(
                                        text = topicPermissionLabel(topic, userId),
                                        backgroundColor = SilkColors.surface,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    KnowledgeBadge(
                                        text = topicSpaceLabel(topic, groups),
                                        backgroundColor = SilkColors.background,
                                    )
                                }
                                if (topic.project.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        topic.project,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SilkColors.textLight,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeBaseEntriesPage(
    userId: String,
    groups: List<Group>,
    selectedTopic: KBTopicItem?,
    entries: List<KBEntryItem>,
    selectedFilter: KnowledgeEntryFilter,
    canWriteSelectedTopic: Boolean,
    onFilterChange: (KnowledgeEntryFilter) -> Unit,
    onBack: () -> Unit,
    onShowCreateEntry: () -> Unit,
    onShowMeetingCapture: () -> Unit,
    onEntrySelected: (KBEntryItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedTopic?.name ?: "条目") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = onShowMeetingCapture,
                        enabled = canWriteSelectedTopic,
                    ) {
                        Text("会议入库")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SilkColors.surface,
                    titleContentColor = SilkColors.textPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (canWriteSelectedTopic) {
                        onShowCreateEntry()
                    }
                },
                containerColor = if (canWriteSelectedTopic) SilkColors.primary else SilkColors.surface,
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建条目")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            selectedTopic?.let { topic ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KnowledgeBadge(
                        text = topicSpaceLabel(topic, groups),
                        backgroundColor = SilkColors.background,
                    )
                    KnowledgeBadge(
                        text = topicPermissionLabel(topic, userId),
                        backgroundColor = SilkColors.surface,
                    )
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(KnowledgeEntryFilter.entries.toList()) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { Text(filter.label) },
                    )
                }
            }
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    KnowledgeBaseEmptyState(
                        icon = "📝",
                        title = if (selectedFilter == KnowledgeEntryFilter.ALL) "暂无条目" else "当前筛选下暂无条目",
                        subtitle = when {
                            selectedFilter != KnowledgeEntryFilter.ALL -> "切换筛选查看其它状态的条目"
                            canWriteSelectedTopic -> "点击右下角 + 创建条目"
                            else -> "当前主题为只读，仅支持浏览已有条目"
                        },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onEntrySelected(entry) },
                            colors = CardDefaults.cardColors(containerColor = SilkColors.cardBackground),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    entry.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    KnowledgeBadge(
                                        text = entryStatusLabel(entry.status),
                                        backgroundColor = SilkColors.surface,
                                    )
                                    KnowledgeBadge(
                                        text = entrySourceLabel(entry.source.sourceType),
                                        backgroundColor = SilkColors.background,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeBaseEditorPage(
    userId: String,
    groups: List<Group>,
    selectedTopic: KBTopicItem?,
    selectedEntry: KBEntryItem?,
    editorContent: String,
    isSaving: Boolean,
    canWriteSelectedTopic: Boolean,
    onBack: () -> Unit,
    onEditorContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onStatusAction: (KBEntryStatus) -> Unit,
) {
    val sourceDetails = remember(selectedEntry, groups) {
        selectedEntry?.let { entrySourceDetails(it, groups) }.orEmpty()
    }
    val statusAction = remember(selectedEntry) {
        selectedEntry?.let(::knowledgeStatusAction)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedEntry?.title ?: "编辑") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onSave, enabled = canWriteSelectedTopic && !isSaving) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "保存",
                            tint = if (canWriteSelectedTopic && !isSaving) SilkColors.primary else SilkColors.textLight,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SilkColors.surface,
                    titleContentColor = SilkColors.textPrimary,
                ),
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            selectedTopic?.let { topic ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KnowledgeBadge(
                            text = topicSpaceLabel(topic, groups),
                            backgroundColor = SilkColors.background,
                        )
                        KnowledgeBadge(
                            text = topicPermissionLabel(topic, userId),
                            backgroundColor = SilkColors.surface,
                        )
                        selectedEntry?.let { entry ->
                            KnowledgeBadge(
                                text = entryStatusLabel(entry.status),
                                backgroundColor = SilkColors.surface,
                            )
                            KnowledgeBadge(
                                text = entrySourceLabel(entry.source.sourceType),
                                backgroundColor = SilkColors.background,
                            )
                        }
                    }
                    if (topic.project.isNotBlank()) {
                        Text(
                            "项目：${topic.project}",
                            color = SilkColors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    sourceDetails.forEach { (label, value) ->
                        KnowledgeDetailRow(label = label, value = value)
                    }
                    if (canWriteSelectedTopic && statusAction != null) {
                        Button(
                            onClick = { onStatusAction(statusAction.second) },
                            colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
                        ) {
                            Text(statusAction.first)
                        }
                    }
                }
            }
            if (!canWriteSelectedTopic) {
                Text(
                    "当前主题为只读，你可以浏览内容，但不能保存修改。",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    color = SilkColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = editorContent,
                onValueChange = onEditorContentChange,
                enabled = canWriteSelectedTopic,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                placeholder = { Text("在这里输入 Markdown 内容...") },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun KnowledgeBaseEmptyState(
    icon: String,
    title: String,
    subtitle: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(title, color = SilkColors.textSecondary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = SilkColors.textLight, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun KnowledgeBadge(
    text: String,
    backgroundColor: Color,
    contentColor: Color = SilkColors.textPrimary,
) {
    Surface(color = backgroundColor, contentColor = contentColor) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun KnowledgeDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$label：",
            color = SilkColors.textLight,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = value,
            color = SilkColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun KnowledgeBaseDialogs(
    isSaving: Boolean,
    showCreateTopicDialog: Boolean,
    newTopicName: String,
    newTopicProject: String,
    selectedSpaceLabel: String,
    onTopicNameChange: (String) -> Unit,
    onTopicProjectChange: (String) -> Unit,
    onDismissCreateTopic: () -> Unit,
    onConfirmCreateTopic: () -> Unit,
    showCreateEntryDialog: Boolean,
    selectedTopic: KBTopicItem?,
    newEntryTitle: String,
    onEntryTitleChange: (String) -> Unit,
    onDismissCreateEntry: () -> Unit,
    onConfirmCreateEntry: () -> Unit,
    showMeetingCaptureDialog: Boolean,
    meetingCaptureTopics: List<KBTopicItem>,
    meetingCaptureTopicId: String,
    meetingCaptureTitle: String,
    meetingCaptureContent: String,
    meetingCaptureTagsText: String,
    meetingCaptureStatus: KBEntryStatus,
    meetingCaptureConfidenceText: String,
    meetingCaptureResultMessage: String?,
    onMeetingCaptureTopicChange: (String) -> Unit,
    onMeetingCaptureTitleChange: (String) -> Unit,
    onMeetingCaptureContentChange: (String) -> Unit,
    onMeetingCaptureTagsTextChange: (String) -> Unit,
    onMeetingCaptureStatusChange: (KBEntryStatus) -> Unit,
    onMeetingCaptureConfidenceTextChange: (String) -> Unit,
    onDismissMeetingCapture: () -> Unit,
    onConfirmMeetingCapture: () -> Unit,
) {
    if (showCreateTopicDialog) {
        AlertDialog(
            onDismissRequest = onDismissCreateTopic,
            title = { Text("创建主题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "当前空间：$selectedSpaceLabel",
                        color = SilkColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = newTopicName,
                        onValueChange = onTopicNameChange,
                        label = { Text("主题名称") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newTopicProject,
                        onValueChange = onTopicProjectChange,
                        label = { Text("所属项目（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmCreateTopic,
                    colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCreateTopic) {
                    Text("取消")
                }
            },
        )
    }

    if (showCreateEntryDialog && selectedTopic != null) {
        AlertDialog(
            onDismissRequest = onDismissCreateEntry,
            title = { Text("创建条目") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "条目会创建在「${selectedTopic.name}」主题下",
                        color = SilkColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = newEntryTitle,
                        onValueChange = onEntryTitleChange,
                        label = { Text("条目标题") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmCreateEntry,
                    colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCreateEntry) {
                    Text("取消")
                }
            },
        )
    }

    if (showMeetingCaptureDialog) {
        MeetingCaptureDialog(
            isSaving = isSaving,
            meetingCaptureTopics = meetingCaptureTopics,
            meetingCaptureTopicId = meetingCaptureTopicId,
            meetingCaptureTitle = meetingCaptureTitle,
            meetingCaptureContent = meetingCaptureContent,
            meetingCaptureTagsText = meetingCaptureTagsText,
            meetingCaptureStatus = meetingCaptureStatus,
            meetingCaptureConfidenceText = meetingCaptureConfidenceText,
            meetingCaptureResultMessage = meetingCaptureResultMessage,
            onMeetingCaptureTopicChange = onMeetingCaptureTopicChange,
            onMeetingCaptureTitleChange = onMeetingCaptureTitleChange,
            onMeetingCaptureContentChange = onMeetingCaptureContentChange,
            onMeetingCaptureTagsTextChange = onMeetingCaptureTagsTextChange,
            onMeetingCaptureStatusChange = onMeetingCaptureStatusChange,
            onMeetingCaptureConfidenceTextChange = onMeetingCaptureConfidenceTextChange,
            onDismissMeetingCapture = onDismissMeetingCapture,
            onConfirmMeetingCapture = onConfirmMeetingCapture,
        )
    }
}

@Composable
private fun MeetingCaptureDialog(
    isSaving: Boolean,
    meetingCaptureTopics: List<KBTopicItem>,
    meetingCaptureTopicId: String,
    meetingCaptureTitle: String,
    meetingCaptureContent: String,
    meetingCaptureTagsText: String,
    meetingCaptureStatus: KBEntryStatus,
    meetingCaptureConfidenceText: String,
    meetingCaptureResultMessage: String?,
    onMeetingCaptureTopicChange: (String) -> Unit,
    onMeetingCaptureTitleChange: (String) -> Unit,
    onMeetingCaptureContentChange: (String) -> Unit,
    onMeetingCaptureTagsTextChange: (String) -> Unit,
    onMeetingCaptureStatusChange: (KBEntryStatus) -> Unit,
    onMeetingCaptureConfidenceTextChange: (String) -> Unit,
    onDismissMeetingCapture: () -> Unit,
    onConfirmMeetingCapture: () -> Unit,
) {
    val canSubmit = !isSaving &&
        meetingCaptureTopicId.isNotBlank() &&
        meetingCaptureTitle.isNotBlank() &&
        meetingCaptureContent.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismissMeetingCapture,
        title = { Text("会议纪要入库") },
        text = {
            MeetingCaptureDialogContent(
                meetingCaptureTopics = meetingCaptureTopics,
                meetingCaptureTopicId = meetingCaptureTopicId,
                meetingCaptureTitle = meetingCaptureTitle,
                meetingCaptureContent = meetingCaptureContent,
                meetingCaptureTagsText = meetingCaptureTagsText,
                meetingCaptureStatus = meetingCaptureStatus,
                meetingCaptureConfidenceText = meetingCaptureConfidenceText,
                meetingCaptureResultMessage = meetingCaptureResultMessage,
                onMeetingCaptureTopicChange = onMeetingCaptureTopicChange,
                onMeetingCaptureTitleChange = onMeetingCaptureTitleChange,
                onMeetingCaptureContentChange = onMeetingCaptureContentChange,
                onMeetingCaptureTagsTextChange = onMeetingCaptureTagsTextChange,
                onMeetingCaptureStatusChange = onMeetingCaptureStatusChange,
                onMeetingCaptureConfidenceTextChange = onMeetingCaptureConfidenceTextChange,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmMeetingCapture,
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = SilkColors.primary),
            ) {
                Text(if (isSaving) "保存中..." else if (meetingCaptureStatus == KBEntryStatus.PUBLISHED) "发布纪要" else "存入候选")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissMeetingCapture) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun MeetingCaptureDialogContent(
    meetingCaptureTopics: List<KBTopicItem>,
    meetingCaptureTopicId: String,
    meetingCaptureTitle: String,
    meetingCaptureContent: String,
    meetingCaptureTagsText: String,
    meetingCaptureStatus: KBEntryStatus,
    meetingCaptureConfidenceText: String,
    meetingCaptureResultMessage: String?,
    onMeetingCaptureTopicChange: (String) -> Unit,
    onMeetingCaptureTitleChange: (String) -> Unit,
    onMeetingCaptureContentChange: (String) -> Unit,
    onMeetingCaptureTagsTextChange: (String) -> Unit,
    onMeetingCaptureStatusChange: (KBEntryStatus) -> Unit,
    onMeetingCaptureConfidenceTextChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "通过统一 capture API 保存会议纪要，可直接存为候选或发布。",
            color = SilkColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = meetingCaptureTitle,
            onValueChange = onMeetingCaptureTitleChange,
            label = { Text("标题") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = meetingCaptureContent,
            onValueChange = onMeetingCaptureContentChange,
            label = { Text("内容") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6,
        )
        OutlinedTextField(
            value = meetingCaptureTagsText,
            onValueChange = onMeetingCaptureTagsTextChange,
            label = { Text("标签") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = meetingCaptureConfidenceText,
            onValueChange = onMeetingCaptureConfidenceTextChange,
            label = { Text("置信度") },
            modifier = Modifier.fillMaxWidth(),
        )
        MeetingCaptureStatusSelector(
            selectedStatus = meetingCaptureStatus,
            onStatusChange = onMeetingCaptureStatusChange,
        )
        Text(
            "目标主题",
            color = SilkColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        MeetingCaptureTopicPicker(
            topics = meetingCaptureTopics,
            selectedTopicId = meetingCaptureTopicId,
            onTopicChange = onMeetingCaptureTopicChange,
        )
        meetingCaptureResultMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                message,
                color = SilkColors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MeetingCaptureStatusSelector(
    selectedStatus: KBEntryStatus,
    onStatusChange: (KBEntryStatus) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(KBEntryStatus.CANDIDATE to "候选", KBEntryStatus.PUBLISHED to "发布").forEach { (status, label) ->
            Button(
                onClick = { onStatusChange(status) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedStatus == status) SilkColors.primary else SilkColors.surface,
                    contentColor = if (selectedStatus == status) Color.White else SilkColors.textPrimary,
                ),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun MeetingCaptureTopicPicker(
    topics: List<KBTopicItem>,
    selectedTopicId: String,
    onTopicChange: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(topics) { topic ->
            val isSelected = selectedTopicId == topic.id
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onTopicChange(topic.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) SilkColors.background else SilkColors.cardBackground,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(topic.name, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KnowledgeBadge(
                            text = if (isSelected) "目标主题" else "可写主题",
                            backgroundColor = if (isSelected) SilkColors.primary else SilkColors.surface,
                            contentColor = if (isSelected) Color.White else SilkColors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}
