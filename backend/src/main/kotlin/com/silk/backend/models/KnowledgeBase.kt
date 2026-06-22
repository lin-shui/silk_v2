package com.silk.backend.models

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
data class KBTopic(
    val id: String,
    val name: String,
    val project: String = "",
    val ownerId: String,
    val spaceType: KnowledgeSpaceType = KnowledgeSpaceType.PERSONAL,
    val groupId: String? = null,
    val accessPolicy: KBAccessPolicy = KBAccessPolicy(),
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class KBEntry(
    val id: String,
    val topicId: String,
    val title: String,
    val content: String = "",
    val tags: List<String> = emptyList(),
    val ownerId: String,
    val status: KBEntryStatus = KBEntryStatus.PUBLISHED,
    val source: KBEntrySource = KBEntrySource(),
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAt: Long,
    val updatedAt: Long
)
