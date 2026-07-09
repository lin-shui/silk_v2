package com.silk.backend.models

import kotlinx.serialization.Serializable

@Serializable
enum class KnowledgeSpaceType {
    PERSONAL,
    TEAM,
}

@Serializable
enum class KBTopicPurpose {
    GENERAL,
    MEMORY,
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
enum class KBMemoryType {
    PROFILE,
    PREFERENCE,
    EPISODIC,
    PROCEDURAL,
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
data class ArchivedMemoryVersion(
    val content: String,
    val title: String,
    val archivedAt: Long,
    val reason: String = "",
)

@Serializable
data class KBMemoryMetadata(
    val type: KBMemoryType = KBMemoryType.EPISODIC,
    val key: String? = null,
    val explicit: Boolean = true,
    val capturedAt: Long = 0L,
    val lastAccessedAt: Long = 0L,
    val accessedCount: Int = 0,
    val archivedVersions: List<ArchivedMemoryVersion> = emptyList(),
)

@Serializable
data class KBTopic(
    val id: String,
    val name: String,
    val project: String = "",
    val ownerId: String,
    val purpose: KBTopicPurpose = KBTopicPurpose.GENERAL,
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
    val memory: KBMemoryMetadata? = null,
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAt: Long,
    val updatedAt: Long
)
