package com.silk.backend.kb

import com.silk.backend.database.GroupRepository
import com.silk.backend.models.KBAccessPolicy
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBMemoryMetadata
import com.silk.backend.models.KBMemoryType
import com.silk.backend.models.KBEntrySource
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBTopic
import com.silk.backend.models.KBTopicPurpose
import com.silk.backend.models.KBFileRef
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

/**
 * 群空间资产中的文件条目（KB 场景中展示用）。
 */
@Serializable
data class GroupAssetFile(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val uploadTime: Long,
    val downloadUrl: String,
    /** 该文件是否已被 KB 条目引用（通过 fileRef 匹配 downloadUrl）。 */
    val hasLinkedEntry: Boolean = false,
    /** 引用该文件的 KB 条目 ID 列表。 */
    val linkedEntryIds: List<String> = emptyList(),
    /** 来源消息 ID（若有）。 */
    val sourceMessageId: String? = null,
)

/**
 * 群空间资产统一响应。
 */
@Serializable
data class GroupAssetsResponse(
    val groupId: String,
    /** 该群上传目录中的文件。 */
    val files: List<GroupAssetFile> = emptyList(),
    /** 该群团队空间中的 KB 条目（PUBLISHED，不含 memory）。 */
    val kbEntries: List<KBEntry> = emptyList(),
    /** 该群团队空间中的 KB 主题。 */
    val kbTopics: List<KBTopic> = emptyList(),
)

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
        load().topics.filter { it.purpose == KBTopicPurpose.GENERAL && canReadTopic(it, userId) }

    fun createTopic(
        name: String,
        project: String,
        userId: String,
        purpose: KBTopicPurpose = KBTopicPurpose.GENERAL,
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
            purpose = purpose,
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
        memory: KBMemoryMetadata? = null,
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
            memory = memory,
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
        topicId: String? = null,
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
                if (topic.purpose == KBTopicPurpose.MEMORY) return@mapNotNull null
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

    fun listMemoryEntries(userId: String): List<KBEntry> {
        val store = load()
        val topicIds = store.topics.asSequence()
            .filter { it.purpose == KBTopicPurpose.MEMORY && it.ownerId == userId }
            .map { it.id }
            .toSet()
        if (topicIds.isEmpty()) return emptyList()
        return store.entries.asSequence()
            .filter { it.topicId in topicIds }
            .filter { it.status != KBEntryStatus.DELETED }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    fun captureExplicitMemory(
        userId: String,
        content: String,
        title: String,
        type: KBMemoryType,
        key: String? = null,
    ): KBEntry {
        return captureMemory(
            userId = userId,
            content = content,
            title = title,
            type = type,
            key = key,
            explicit = true,
            sourceType = com.silk.backend.models.KBSourceType.CHAT,
        )
    }

    fun captureAutoMemory(
        userId: String,
        content: String,
        title: String,
        type: KBMemoryType,
        key: String,
    ): KBEntry? {
        return captureMemory(
            userId = userId,
            content = content,
            title = title,
            type = type,
            key = key,
            explicit = false,
            sourceType = com.silk.backend.models.KBSourceType.AI_RESPONSE,
        )
    }

    private fun captureMemory(
        userId: String,
        content: String,
        title: String,
        type: KBMemoryType,
        key: String? = null,
        explicit: Boolean,
        sourceType: com.silk.backend.models.KBSourceType,
    ): KBEntry {
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "memory content must not be blank" }
        val normalizedTitle = title.trim().ifEmpty { buildMemoryTitle(type, normalizedContent) }
        val normalizedKey = key?.trim()?.takeIf { it.isNotEmpty() } ?: buildMemoryKey(type, normalizedContent)
        val store = load()
        val topic = findOrCreateMemoryTopic(store, userId)
        val now = System.currentTimeMillis()
        val existingIndex = store.entries.indexOfFirst { entry ->
            entry.topicId == topic.id &&
                entry.memory?.key == normalizedKey
        }
        if (!explicit && existingIndex >= 0 && store.entries[existingIndex].memory?.explicit == true) {
            return store.entries[existingIndex]
        }
        val saved = if (existingIndex >= 0) {
            val existing = store.entries[existingIndex]
            // Phase 3: 覆盖前归档旧值
            val archivedMeta = archiveOldVersion(
                existingEntry = existing,
                newContent = normalizedContent,
                reason = if (explicit) "用户显式更新" else "自动记忆覆盖",
            )
            existing.copy(
                title = normalizedTitle,
                content = normalizedContent,
                tags = buildMemoryTags(type),
                status = KBEntryStatus.PUBLISHED,
                source = KBEntrySource(sourceType = sourceType),
                memory = (archivedMeta ?: defaultMemoryMetadata(type, normalizedKey, explicit = explicit)).copy(
                    explicit = explicit,
                ),
                updatedBy = userId,
                updatedAt = now,
            )
        } else {
            KBEntry(
                id = "kb_entry_${System.currentTimeMillis()}_${(1000..9999).random()}",
                topicId = topic.id,
                title = normalizedTitle,
                content = normalizedContent,
                tags = buildMemoryTags(type),
                ownerId = userId,
                status = KBEntryStatus.PUBLISHED,
                source = KBEntrySource(sourceType = sourceType),
                memory = defaultMemoryMetadata(type, normalizedKey, explicit = explicit),
                createdBy = userId,
                updatedBy = userId,
                createdAt = now,
                updatedAt = now,
            )
        }
        if (existingIndex >= 0) {
            store.entries[existingIndex] = saved
        } else {
            store.entries.add(saved)
        }
        save(store)
        return saved
    }

    fun searchMemoryEntriesForContext(
        userId: String,
        query: String,
        limit: Int = 5,
    ): List<KBContextSearchHit> {
        val terms = extractSearchTerms(query)
        if (terms.isEmpty() || limit <= 0) return emptyList()

        val store = load()
        val topicById = store.topics.associateBy { it.id }
        val now = System.currentTimeMillis()
        return store.entries.asSequence()
            .filter { it.ownerId == userId }
            .filter { it.status == KBEntryStatus.PUBLISHED }
            .filter { it.memory != null }
            .mapNotNull { entry ->
                val topic = topicById[entry.topicId] ?: return@mapNotNull null
                if (topic.purpose != KBTopicPurpose.MEMORY) return@mapNotNull null
                scoreContextCandidate(topic, entry, terms, preferredGroupId = null, memoryBoost = 10)
            }
            .map { hit ->
                // Phase 3: 叠加 recency 加权分
                val meta = hit.entry.memory ?: return@map hit
                val recency = recencyScore(meta, now)
                hit.copy(score = hit.score + (recency * 2).toInt())
            }
            .sortedWith(
                compareByDescending<KBContextSearchHit> { it.score }
                    .thenByDescending { it.entry.updatedAt }
            )
            .take(limit)
            .toList()
    }

    /**
     * 获取记忆条目并在访问时更新 lastAccessedAt / accessedCount。
     */
    fun getMemoryEntryWithAccess(entryId: String, userId: String): KBEntry? {
        val store = load()
        val idx = store.entries.indexOfFirst { it.id == entryId }
        if (idx < 0) return null
        val entry = store.entries[idx]
        val meta = entry.memory ?: return entry
        val topic = store.topics.find { it.id == entry.topicId }
        if (topic?.purpose != KBTopicPurpose.MEMORY) return entry
        if (entry.ownerId != userId) return entry
        val updatedMeta = markMemoryAccessed(meta)
        store.entries[idx] = entry.copy(memory = updatedMeta)
        save(store)
        return store.entries[idx]
    }

    /**
     * 对指定用户的记忆 store 执行 consolidation（去重合并 + TTL 衰减）。
     * @return ConsolidationReport 报告本次操作统计
     */
    fun consolidateMemoryStore(userId: String): ConsolidationReport {
        val store = load()
        val topic = store.topics.firstOrNull { it.ownerId == userId && it.purpose == KBTopicPurpose.MEMORY }
            ?: return ConsolidationReport()
        val memoryEntries = store.entries.filter { it.topicId == topic.id }.toMutableList()
        val report = consolidateMemories(memoryEntries)
        // 将 consolidation 后的条目写回 store
        val nonMemoryInTopic = store.entries.filter { it.topicId != topic.id }
        store.entries.clear()
        store.entries.addAll(nonMemoryInTopic)
        store.entries.addAll(memoryEntries)
        save(store)
        return report
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
            purpose = topic.purpose,
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
            memory = entry.memory,
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

    private fun normalizePolicy(accessPolicy: KBAccessPolicy, spaceType: KnowledgeSpaceType): KBAccessPolicy {
        return if (spaceType == KnowledgeSpaceType.PERSONAL) {
            accessPolicy.copy(teamMembersCanWrite = false)
        } else {
            accessPolicy
        }
    }

    private fun canMoveEntryBetweenTopics(sourceTopic: KBTopic, targetTopic: KBTopic): Boolean {
        if (sourceTopic.id == targetTopic.id) return true
        if (sourceTopic.spaceType != targetTopic.spaceType) return false
        return when (sourceTopic.spaceType) {
            KnowledgeSpaceType.PERSONAL -> true
            KnowledgeSpaceType.TEAM -> sourceTopic.groupId == targetTopic.groupId
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
        memoryBoost: Int = 0,
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
        score += memoryBoost
        if (!preferredGroupId.isNullOrBlank() && topic.groupId == preferredGroupId) {
            score += 5
            reasons += "当前团队空间"
        }
        if (topic.purpose == KBTopicPurpose.MEMORY) {
            reasons += "长期记忆"
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

    private fun findOrCreateMemoryTopic(store: KBStore, userId: String): KBTopic {
        val existing = store.topics.firstOrNull { it.ownerId == userId && it.purpose == KBTopicPurpose.MEMORY }
        if (existing != null) return existing

        val now = System.currentTimeMillis()
        val created = KBTopic(
            id = "kb_topic_${System.currentTimeMillis()}_${(1000..9999).random()}",
            name = "Memory",
            project = "memory",
            ownerId = userId,
            purpose = KBTopicPurpose.MEMORY,
            createdBy = userId,
            updatedBy = userId,
            createdAt = now,
            updatedAt = now,
        )
        store.topics.add(created)
        return created
    }

    // ── Phase 4: Scoped Project (Group) Memory ──

    /**
     * 查找或创建群组级别的记忆 topic（team-scoped, purpose=MEMORY）。
     * Group memory topic 通过 groupId + purpose=MEMORY 唯一识别。
     * 只允许群组成员创建/访问。
     */
    internal fun findOrCreateGroupMemoryTopic(store: KBStore, groupId: String, userId: String): KBTopic {
        val existing = store.topics.firstOrNull {
            it.purpose == KBTopicPurpose.MEMORY &&
                it.spaceType == KnowledgeSpaceType.TEAM &&
                it.groupId == groupId
        }
        if (existing != null) return existing

        require(GroupRepository.isUserInGroup(groupId, userId)) {
            "User $userId is not a member of group $groupId"
        }
        val now = System.currentTimeMillis()
        val created = KBTopic(
            id = "kb_topic_${System.currentTimeMillis()}_${(1000..9999).random()}",
            name = "Group Memory",
            project = "memory",
            ownerId = userId,
            purpose = KBTopicPurpose.MEMORY,
            spaceType = KnowledgeSpaceType.TEAM,
            groupId = groupId,
            accessPolicy = KBAccessPolicy(teamMembersCanWrite = true),
            createdBy = userId,
            updatedBy = userId,
            createdAt = now,
            updatedAt = now,
        )
        store.topics.add(created)
        logger.info("Created group memory topic for group {} by user {}", groupId, userId)
        return created
    }

    /**
     * 获取群组记忆 topic，若不存在则返回 null。
     */
    fun getGroupMemoryTopic(groupId: String): KBTopic? {
        return load().topics.firstOrNull {
            it.purpose == KBTopicPurpose.MEMORY &&
                it.spaceType == KnowledgeSpaceType.TEAM &&
                it.groupId == groupId
        }
    }

    /**
     * 以群组身份捕获显式记忆。记忆归属于群组，所有群组成员可读。
     * 使用 group-scoped memory topic，每个 groupId 一个记忆空间。
     */
    fun captureExplicitGroupMemory(
        userId: String,
        groupId: String,
        content: String,
        title: String,
        type: KBMemoryType,
        key: String? = null,
    ): KBEntry {
        return captureGroupMemory(
            userId = userId,
            groupId = groupId,
            content = content,
            title = title,
            type = type,
            key = key,
            explicit = true,
            sourceType = com.silk.backend.models.KBSourceType.CHAT,
        )
    }

    /**
     * 以群组身份捕获自动记忆。不会覆盖同 key 的显式群组记忆。
     */
    fun captureAutoGroupMemory(
        userId: String,
        groupId: String,
        content: String,
        title: String,
        type: KBMemoryType,
        key: String,
    ): KBEntry? {
        return captureGroupMemory(
            userId = userId,
            groupId = groupId,
            content = content,
            title = title,
            type = type,
            key = key,
            explicit = false,
            sourceType = com.silk.backend.models.KBSourceType.AI_RESPONSE,
        )
    }

    private fun captureGroupMemory(
        userId: String,
        groupId: String,
        content: String,
        title: String,
        type: KBMemoryType,
        key: String? = null,
        explicit: Boolean,
        sourceType: com.silk.backend.models.KBSourceType,
    ): KBEntry {
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "memory content must not be blank" }
        val normalizedTitle = title.trim().ifEmpty { buildMemoryTitle(type, normalizedContent) }
        val normalizedKey = key?.trim()?.takeIf { it.isNotEmpty() } ?: buildMemoryKey(type, normalizedContent)
        val store = load()

        require(GroupRepository.isUserInGroup(groupId, userId)) {
            "User $userId is not a member of group $groupId"
        }
        val topic = findOrCreateGroupMemoryTopic(store, groupId, userId)
        val now = System.currentTimeMillis()
        val existingIndex = store.entries.indexOfFirst { entry ->
            entry.topicId == topic.id &&
                entry.memory?.key == normalizedKey
        }

        // 自动记忆不覆盖同 key 的显式记忆
        if (!explicit && existingIndex >= 0 && store.entries[existingIndex].memory?.explicit == true) {
            return store.entries[existingIndex]
        }

        val saved = if (existingIndex >= 0) {
            val existing = store.entries[existingIndex]
            val archivedMeta = archiveOldVersion(
                existingEntry = existing,
                newContent = normalizedContent,
                reason = if (explicit) "用户显式更新群组记忆" else "自动记忆覆盖群组记忆",
            )
            existing.copy(
                title = normalizedTitle,
                content = normalizedContent,
                tags = buildMemoryTags(type),
                status = KBEntryStatus.PUBLISHED,
                source = KBEntrySource(sourceType = sourceType),
                memory = (archivedMeta ?: defaultMemoryMetadata(type, normalizedKey, explicit = explicit)).copy(
                    explicit = explicit,
                ),
                updatedBy = userId,
                updatedAt = now,
            )
        } else {
            KBEntry(
                id = "kb_entry_${System.currentTimeMillis()}_${(1000..9999).random()}",
                topicId = topic.id,
                title = normalizedTitle,
                content = normalizedContent,
                tags = buildMemoryTags(type),
                ownerId = userId,
                status = KBEntryStatus.PUBLISHED,
                source = KBEntrySource(sourceType = sourceType),
                memory = defaultMemoryMetadata(type, normalizedKey, explicit = explicit),
                createdBy = userId,
                updatedBy = userId,
                createdAt = now,
                updatedAt = now,
            )
        }
        if (existingIndex >= 0) {
            store.entries[existingIndex] = saved
        } else {
            store.entries.add(saved)
        }
        save(store)
        return saved
    }

    /**
     * 搜索给定群组的记忆条目（含群组记忆）。仅返回当前用户可读的记忆。
     */
    fun searchGroupMemoryEntriesForContext(
        userId: String,
        groupId: String,
        query: String,
        limit: Int = 5,
    ): List<KBContextSearchHit> {
        val terms = extractSearchTerms(query)
        if (terms.isEmpty() || limit <= 0) return emptyList()

        val store = load()
        val topicById = store.topics.associateBy { it.id }
        val now = System.currentTimeMillis()
        return store.entries.asSequence()
            .filter { it.status == KBEntryStatus.PUBLISHED }
            .filter { it.memory != null }
            .mapNotNull { entry ->
                val topic = topicById[entry.topicId] ?: return@mapNotNull null
                if (topic.purpose != KBTopicPurpose.MEMORY) return@mapNotNull null
                if (topic.spaceType != KnowledgeSpaceType.TEAM) return@mapNotNull null
                if (topic.groupId != groupId) return@mapNotNull null
                if (!canReadTopic(topic, userId)) return@mapNotNull null
                scoreContextCandidate(topic, entry, terms, preferredGroupId = groupId, memoryBoost = 10)
            }
            .map { hit ->
                val meta = hit.entry.memory ?: return@map hit
                val recency = recencyScore(meta, now)
                hit.copy(score = hit.score + (recency * 2).toInt())
            }
            .sortedWith(
                compareByDescending<KBContextSearchHit> { it.score }
                    .thenByDescending { it.entry.updatedAt }
            )
            .take(limit)
            .toList()
    }

    /**
     * 列出群组记忆条目。
     * @param groupId 目标群组 ID
     * @param userId 调用者 ID（用于权限校验）
     */
    fun listGroupMemoryEntries(groupId: String, userId: String): List<KBEntry> {
        val store = load()
        val topic = store.topics.firstOrNull {
            it.purpose == KBTopicPurpose.MEMORY &&
                it.spaceType == KnowledgeSpaceType.TEAM &&
                it.groupId == groupId
        } ?: return emptyList()

        if (!canReadTopic(topic, userId)) return emptyList()
        return store.entries.asSequence()
            .filter { it.topicId == topic.id }
            .filter { it.status != KBEntryStatus.DELETED }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    /**
     * 对指定群组的记忆 store 执行 consolidation（去重合并 + TTL 衰减）。
     */
    fun consolidateGroupMemoryStore(groupId: String, userId: String): ConsolidationReport {
        val store = load()
        val topic = store.topics.firstOrNull {
            it.purpose == KBTopicPurpose.MEMORY &&
                it.spaceType == KnowledgeSpaceType.TEAM &&
                it.groupId == groupId
        } ?: return ConsolidationReport()

        if (!canManageTopic(topic, userId)) return ConsolidationReport()
        val memoryEntries = store.entries.filter { it.topicId == topic.id }.toMutableList()
        val report = consolidateMemories(memoryEntries)
        val nonMemoryInTopic = store.entries.filter { it.topicId != topic.id }
        store.entries.clear()
        store.entries.addAll(nonMemoryInTopic)
        store.entries.addAll(memoryEntries)
        save(store)
        return report
    }

    // ── Group Assets (unified file + KB browsing) ──

    /**
     * 获取群空间统一资产列表（上传文件 + KB 条目）。
     * 只返回当前用户可读的内容。
     */
    fun getGroupAssets(groupId: String, userId: String): GroupAssetsResponse {
        require(GroupRepository.isUserInGroup(groupId, userId)) {
            "User $userId is not a member of group $groupId"
        }

        val store = load()
        val teamTopics = collectGroupTopics(store, groupId, userId)
        val teamTopicIds = teamTopics.map { it.id }.toSet()
        val kbEntries = store.entries.filter {
            it.topicId in teamTopicIds &&
                it.status == KBEntryStatus.PUBLISHED
        }

        val files = collectGroupUploadFiles(groupId, kbEntries)

        return GroupAssetsResponse(
            groupId = groupId,
            files = files,
            kbEntries = kbEntries,
            kbTopics = teamTopics,
        )
    }

    private fun collectGroupTopics(store: KBStore, groupId: String, userId: String): List<KBTopic> =
        store.topics.filter {
            it.spaceType == KnowledgeSpaceType.TEAM &&
                it.groupId == groupId &&
                it.purpose == KBTopicPurpose.GENERAL &&
                canReadTopic(it, userId)
        }

    private fun collectGroupUploadFiles(groupId: String, kbEntries: List<KBEntry>): List<GroupAssetFile> {
        val chatHistoryDir = System.getProperty("silk.chatHistoryDir")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)
            ?: File("chat_history")
        val sessionDirName = if (groupId.startsWith("group_")) groupId else "group_$groupId"
        val uploadsDir = File(File(chatHistoryDir, sessionDirName), "uploads")

        val fileRefMap = buildFileRefMap(kbEntries)

        return if (uploadsDir.exists()) {
            collectFilesFromUploadDir(uploadsDir, groupId, fileRefMap)
        } else {
            emptyList()
        }
    }

    private fun buildFileRefMap(kbEntries: List<KBEntry>): Map<String, List<String>> {
        val map = mutableMapOf<String, MutableList<String>>()
        kbEntries.forEach { entry ->
            entry.source.fileRef?.let { ref ->
                map.getOrPut(ref.downloadUrl) { mutableListOf() }.add(entry.id)
            }
        }
        return map
    }

    private fun collectFilesFromUploadDir(uploadsDir: File, groupId: String, fileRefMap: Map<String, List<String>>): List<GroupAssetFile> =
        uploadsDir.listFiles()
            ?.filter { file ->
                file.name != "processed_urls.txt" &&
                    file.name != "file_registry.json" &&
                    !file.name.endsWith(".extracted.md")
            }
            ?.map { file ->
                val downloadUrl = "/api/files/download/$groupId/${file.name}"
                val linkedEntryIds = fileRefMap[downloadUrl] ?: emptyList()
                GroupAssetFile(
                    fileId = file.name,
                    fileName = file.name,
                    fileSize = file.length(),
                    mimeType = Files.probeContentType(file.toPath()) ?: "application/octet-stream",
                    uploadTime = file.lastModified(),
                    downloadUrl = downloadUrl,
                    hasLinkedEntry = linkedEntryIds.isNotEmpty(),
                    linkedEntryIds = linkedEntryIds,
                )
            }
            ?.sortedByDescending { it.uploadTime }
            ?: emptyList()

    /**
     * 为 KB 条目关联一个群文件引用。
     * 用于在 KB 编辑器中从文件创建条目时，直接将文件引用存入 source.fileRef。
     */
    fun linkFileToEntry(
        entryId: String,
        groupId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        userId: String,
        sourceMessageId: String? = null,
    ): KBEntry? {
        val store = load()
        val idx = store.entries.indexOfFirst { it.id == entryId }
        if (idx < 0) return null
        val entry = store.entries[idx]
        val topic = store.topics.find { it.id == entry.topicId } ?: return null
        if (!canWriteTopic(topic, userId)) return null

        val downloadUrl = "/api/files/download/$groupId/$fileName"
        val fileRef = KBFileRef(
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            downloadUrl = downloadUrl,
            sourceMessageId = sourceMessageId,
        )
        val updatedSource = entry.source.copy(fileRef = fileRef)
        val updated = entry.copy(
            source = updatedSource,
            updatedBy = userId,
            updatedAt = System.currentTimeMillis(),
        )
        store.entries[idx] = updated
        save(store)
        return updated
    }
}
