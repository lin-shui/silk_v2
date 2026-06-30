package com.silk.backend.kb

import com.silk.backend.database.GroupRepository
import com.silk.backend.models.KBAccessPolicy
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBEntrySource
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBTopic
import com.silk.backend.models.KnowledgeSpaceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class KBStore(
    val topics: MutableList<KBTopic> = mutableListOf(),
    val entries: MutableList<KBEntry> = mutableListOf()
)

data class KBContextSearchHit(
    val topic: KBTopic,
    val entry: KBEntry,
    val score: Int,
    val reasons: List<String>,
)

internal const val PERSONAL_KB_SPACE_ID = "__personal__"

class KnowledgeBaseManager(
    private val baseDir: String =
        System.getProperty("silk.kbDir")?.trim()?.takeIf { it.isNotEmpty() } ?: "knowledge_base"
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(KnowledgeBaseManager::class.java)
    private val storeFile get() = File("$baseDir/kb_store.json")

    init {
        File(baseDir).mkdirs()
    }

    @Synchronized
    private fun load(): KBStore {
        val store = if (storeFile.exists()) {
            try {
                json.decodeFromString(storeFile.readText())
            } catch (e: SerializationException) {
                logger.error("Failed to decode KB store: {}", e.message)
                KBStore()
            } catch (e: IllegalArgumentException) {
                logger.error("Invalid KB store content: {}", e.message)
                KBStore()
            } catch (e: IOException) {
                logger.error("Failed to load KB store: {}", e.message)
                KBStore()
            }
        } else {
            KBStore()
        }
        return normalizeStore(store)
    }

    @Synchronized
    private fun save(store: KBStore) {
        File(baseDir).mkdirs()
        val tmp = File("${storeFile.path}.tmp")
        tmp.writeText(json.encodeToString(store))
        Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    // ---- Topics ----

    fun listTopics(userId: String): List<KBTopic> =
        load().topics.filter { canReadTopic(it, userId) }

    fun createTopic(
        name: String,
        project: String,
        userId: String,
        spaceType: KnowledgeSpaceType = KnowledgeSpaceType.PERSONAL,
        groupId: String? = null,
        accessPolicy: KBAccessPolicy = KBAccessPolicy(),
    ): KBTopic {
        val store = load()
        val normalizedSpaceType = normalizeSpaceType(spaceType, groupId)
        val normalizedGroupId = normalizeGroupId(normalizedSpaceType, groupId)
        require(!(normalizedSpaceType == KnowledgeSpaceType.TEAM && normalizedGroupId == null)) {
            "Team knowledge base topics require a groupId"
        }
        require(normalizedGroupId == null || GroupRepository.isUserInGroup(normalizedGroupId, userId)) {
            "User $userId is not a member of group $normalizedGroupId"
        }
        val now = System.currentTimeMillis()
        val topic = KBTopic(
            id = "kb_topic_${System.currentTimeMillis()}_${(1000..9999).random()}",
            name = name,
            project = project,
            ownerId = userId,
            spaceType = normalizedSpaceType,
            groupId = normalizedGroupId,
            accessPolicy = normalizePolicy(accessPolicy, normalizedSpaceType),
            createdBy = userId,
            updatedBy = userId,
            createdAt = now,
            updatedAt = now,
        )
        store.topics.add(topic)
        save(store)
        logger.info("Created KB topic: {} for user {}", topic.id, userId)
        return topic
    }

    fun deleteTopic(topicId: String, userId: String): Boolean {
        val store = load()
        val topic = store.topics.find { it.id == topicId } ?: return false
        if (!canManageTopic(topic, userId)) return false
        val removed = store.topics.removeAll { it.id == topicId }
        if (removed) {
            store.entries.removeAll { it.topicId == topicId }
            save(store)
            logger.info("Deleted KB topic: {}", topicId)
        }
        return removed
    }

    fun updateTopic(
        topicId: String,
        userId: String,
        name: String?,
        project: String?,
        accessPolicy: KBAccessPolicy?,
    ): KBTopic? {
        val store = load()
        val idx = store.topics.indexOfFirst { it.id == topicId }
        if (idx == -1) return null
        val old = store.topics[idx]
        if (!canManageTopic(old, userId)) return null
        val updated = old.copy(
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: old.name,
            project = project?.trim() ?: old.project,
            accessPolicy = accessPolicy?.let { normalizePolicy(it, old.spaceType) } ?: old.accessPolicy,
            updatedBy = userId,
            updatedAt = System.currentTimeMillis(),
        )
        store.topics[idx] = updated
        save(store)
        return updated
    }

    // ---- Entries ----

    fun listEntries(topicId: String, userId: String): List<KBEntry> =
        load().let { store ->
            val topic = store.topics.find { it.id == topicId } ?: return emptyList()
            if (!canReadTopic(topic, userId)) return emptyList()
            store.entries.filter { it.topicId == topicId }
        }

    fun getEntry(entryId: String): KBEntry? =
        load().entries.find { it.id == entryId }

    fun getEntry(entryId: String, userId: String): KBEntry? {
        val store = load()
        val entry = store.entries.find { it.id == entryId } ?: return null
        val topic = store.topics.find { it.id == entry.topicId } ?: return null
        return entry.takeIf { canReadTopic(topic, userId) }
    }

    fun createEntry(
        topicId: String,
        title: String,
        content: String,
        tags: List<String>,
        userId: String,
        status: KBEntryStatus = KBEntryStatus.PUBLISHED,
        source: KBEntrySource = KBEntrySource(),
    ): KBEntry? {
        val store = load()
        val topic = store.topics.find { it.id == topicId } ?: return null
        if (!canWriteTopic(topic, userId)) return null
        val now = System.currentTimeMillis()
        val entry = KBEntry(
            id = "kb_entry_${System.currentTimeMillis()}_${(1000..9999).random()}",
            topicId = topicId,
            title = title,
            content = content,
            tags = tags,
            ownerId = userId,
            status = status,
            source = source,
            createdBy = userId,
            updatedBy = userId,
            createdAt = now,
            updatedAt = now,
        )
        store.entries.add(entry)
        save(store)
        logger.info("Created KB entry: {} in topic {}", entry.id, topicId)
        return entry
    }

    fun updateEntry(
        entryId: String,
        topicId: String?,
        title: String?,
        content: String?,
        tags: List<String>?,
        status: KBEntryStatus?,
        userId: String,
    ): KBEntry? {
        val store = load()
        val idx = store.entries.indexOfFirst { it.id == entryId }
        if (idx == -1) return null
        val old = store.entries[idx]
        val sourceTopic = store.topics.find { it.id == old.topicId } ?: return null
        val targetTopic = topicId
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != old.topicId }
            ?.let { requestedTopicId -> store.topics.find { it.id == requestedTopicId } }
            ?: sourceTopic
        if (!canWriteTopic(sourceTopic, userId) || !canWriteTopic(targetTopic, userId)) return null
        require(canMoveEntryBetweenTopics(sourceTopic, targetTopic)) {
            "Entries can only move within the same knowledge space"
        }
        val updated = old.copy(
            topicId = targetTopic.id,
            title = title ?: old.title,
            content = content ?: old.content,
            tags = tags ?: old.tags,
            status = status ?: old.status,
            updatedBy = userId,
            updatedAt = System.currentTimeMillis(),
        )
        store.entries[idx] = updated
        save(store)
        return updated
    }

    fun deleteEntry(entryId: String, userId: String): Boolean {
        val store = load()
        val entry = store.entries.find { it.id == entryId } ?: return false
        val topic = store.topics.find { it.id == entry.topicId } ?: return false
        if (!canManageTopic(topic, userId)) return false
        val removed = store.entries.removeAll { it.id == entryId }
        if (removed) save(store)
        return removed
    }

    fun getTopic(topicId: String): KBTopic? = load().topics.find { it.id == topicId }

    fun getTopic(topicId: String, userId: String): KBTopic? =
        load().topics.find { it.id == topicId }?.takeIf { canReadTopic(it, userId) }

    fun searchEntriesForContext(
        userId: String,
        query: String,
        preferredGroupId: String? = null,
        limit: Int = 3,
        excludedEntryIds: Set<String> = emptySet(),
        excludedSpaceIds: Set<String> = emptySet(),
    ): List<KBContextSearchHit> {
        val terms = extractSearchTerms(query)
        if (terms.isEmpty() || limit <= 0) return emptyList()

        val store = load()
        val topicById = store.topics.associateBy { it.id }
        return store.entries.asSequence()
            .filter { it.id !in excludedEntryIds }
            .filter { it.status == KBEntryStatus.PUBLISHED }
            .mapNotNull { entry ->
                val topic = topicById[entry.topicId] ?: return@mapNotNull null
                if (!canReadTopic(topic, userId)) return@mapNotNull null
                if (topic.spacePreferenceId() in excludedSpaceIds) return@mapNotNull null
                scoreContextCandidate(
                    topic = topic,
                    entry = entry,
                    terms = terms,
                    preferredGroupId = preferredGroupId,
                )
            }
            .sortedWith(
                compareByDescending<KBContextSearchHit> { it.score }
                    .thenByDescending { it.entry.updatedAt }
            )
            .take(limit)
            .toList()
    }

    fun canReadTopic(topic: KBTopic, userId: String): Boolean {
        if (topic.ownerId == userId) return true
        if (userId in topic.accessPolicy.manageUserIds) return true
        if (userId in topic.accessPolicy.writeUserIds) return true
        if (userId in topic.accessPolicy.readUserIds) return true
        return when (topic.spaceType) {
            KnowledgeSpaceType.PERSONAL -> false
            KnowledgeSpaceType.TEAM -> topic.groupId?.let { GroupRepository.isUserInGroup(it, userId) } ?: false
        }
    }

    fun canWriteTopic(topic: KBTopic, userId: String): Boolean {
        if (topic.ownerId == userId) return true
        if (canManageTopic(topic, userId)) return true
        if (topic.accessPolicy.writeLocked) return false
        if (userId in topic.accessPolicy.writeUserIds) return true
        return topic.spaceType == KnowledgeSpaceType.TEAM &&
            topic.accessPolicy.teamMembersCanWrite &&
            (topic.groupId?.let { GroupRepository.isUserInGroup(it, userId) } ?: false)
    }

    fun canManageTopic(topic: KBTopic, userId: String): Boolean {
        if (topic.ownerId == userId) return true
        if (userId in topic.accessPolicy.manageUserIds) return true
        return topic.spaceType == KnowledgeSpaceType.TEAM &&
            topic.groupId?.let { groupId ->
                GroupRepository.findGroupById(groupId)?.hostId == userId
            } == true
    }

    private fun normalizeStore(store: KBStore): KBStore {
        val topics = store.topics.map(::normalizeTopic).toMutableList()
        val topicById = topics.associateBy { it.id }
        val entries = store.entries.map { normalizeEntry(it, topicById[it.topicId]) }.toMutableList()
        return KBStore(topics = topics, entries = entries)
    }

    private fun normalizeTopic(topic: KBTopic): KBTopic {
        val normalizedSpaceType = normalizeSpaceType(topic.spaceType, topic.groupId)
        return topic.copy(
            spaceType = normalizedSpaceType,
            groupId = normalizeGroupId(normalizedSpaceType, topic.groupId),
            accessPolicy = normalizePolicy(topic.accessPolicy, normalizedSpaceType),
            createdBy = topic.createdBy.ifBlank { topic.ownerId },
            updatedBy = topic.updatedBy.ifBlank { topic.ownerId },
        )
    }

    private fun normalizeEntry(entry: KBEntry, topic: KBTopic?): KBEntry {
        val fallbackOwner = topic?.ownerId ?: entry.ownerId
        return entry.copy(
            status = entry.status,
            source = entry.source,
            createdBy = entry.createdBy.ifBlank { fallbackOwner },
            updatedBy = entry.updatedBy.ifBlank { fallbackOwner },
        )
    }

    private fun normalizeSpaceType(spaceType: KnowledgeSpaceType, groupId: String?): KnowledgeSpaceType {
        return if (!groupId.isNullOrBlank()) {
            KnowledgeSpaceType.TEAM
        } else {
            spaceType
        }
    }

    private fun normalizeGroupId(spaceType: KnowledgeSpaceType, groupId: String?): String? {
        val normalized = groupId?.trim()?.takeIf { it.isNotEmpty() }
        return if (spaceType == KnowledgeSpaceType.TEAM) normalized else null
    }

    private fun canMoveEntryBetweenTopics(sourceTopic: KBTopic, targetTopic: KBTopic): Boolean {
        if (sourceTopic.id == targetTopic.id) return true
        if (sourceTopic.spaceType != targetTopic.spaceType) return false
        return when (sourceTopic.spaceType) {
            KnowledgeSpaceType.PERSONAL -> true
            KnowledgeSpaceType.TEAM -> sourceTopic.groupId == targetTopic.groupId
        }
    }

    private fun normalizePolicy(accessPolicy: KBAccessPolicy, spaceType: KnowledgeSpaceType): KBAccessPolicy {
        return if (spaceType == KnowledgeSpaceType.PERSONAL) {
            accessPolicy.copy(teamMembersCanWrite = false)
        } else {
            accessPolicy
        }
    }

    internal fun KBTopic.spacePreferenceId(): String {
        return when (spaceType) {
            KnowledgeSpaceType.TEAM -> groupId?.takeIf { it.isNotBlank() } ?: PERSONAL_KB_SPACE_ID
            KnowledgeSpaceType.PERSONAL -> PERSONAL_KB_SPACE_ID
        }
    }

    private fun scoreContextCandidate(
        topic: KBTopic,
        entry: KBEntry,
        terms: List<String>,
        preferredGroupId: String?,
    ): KBContextSearchHit? {
        val title = normalizeSearchText(entry.title)
        val project = normalizeSearchText(topic.project)
        val tags = normalizeSearchText(entry.tags.joinToString(" "))
        val content = normalizeSearchText(entry.content.take(4_000))
        var score = 0
        val reasons = linkedSetOf<String>()

        terms.forEach { term ->
            when {
                title.contains(term) -> {
                    score += 12
                    reasons += "标题匹配"
                }
                tags.contains(term) -> {
                    score += 8
                    reasons += "标签匹配"
                }
                project.contains(term) -> {
                    score += 6
                    reasons += "项目匹配"
                }
                content.contains(term) -> {
                    score += 4
                    reasons += "内容匹配"
                }
            }
        }

        if (score == 0) return null
        if (!preferredGroupId.isNullOrBlank() && topic.groupId == preferredGroupId) {
            score += 5
            reasons += "当前团队空间"
        }

        return KBContextSearchHit(
            topic = topic,
            entry = entry,
            score = score,
            reasons = reasons.toList(),
        )
    }

    private fun extractSearchTerms(query: String): List<String> {
        val normalized = normalizeSearchText(query)
        val phrase = normalized.trim().takeIf { it.length >= 2 }
        val tokens = Regex("""[\p{IsHan}]{2,}|[a-z0-9_-]{2,}""")
            .findAll(normalized)
            .map { it.value }
            .toList()
        return buildList {
            if (phrase != null) add(phrase)
            addAll(tokens)
        }.distinct()
    }

    private fun normalizeSearchText(raw: String): String {
        return raw.lowercase()
            .replace(Regex("""[^\p{IsHan}a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
