package com.silk.backend.kb

import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBTopic
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class KBStore(
    val topics: MutableList<KBTopic> = mutableListOf(),
    val entries: MutableList<KBEntry> = mutableListOf()
)

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
        return if (storeFile.exists()) {
            try {
                json.decodeFromString(storeFile.readText())
            } catch (e: Exception) {
                logger.error("Failed to load KB store: {}", e.message)
                KBStore()
            }
        } else KBStore()
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
        load().topics.filter { it.ownerId == userId }

    fun createTopic(name: String, project: String, userId: String): KBTopic {
        val store = load()
        val topic = KBTopic(
            id = "kb_topic_${System.currentTimeMillis()}_${(1000..9999).random()}",
            name = name,
            project = project,
            ownerId = userId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        store.topics.add(topic)
        save(store)
        logger.info("Created KB topic: {} for user {}", topic.id, userId)
        return topic
    }

    fun deleteTopic(topicId: String, userId: String): Boolean {
        val store = load()
        val removed = store.topics.removeAll { it.id == topicId && it.ownerId == userId }
        if (removed) {
            store.entries.removeAll { it.topicId == topicId && it.ownerId == userId }
            save(store)
            logger.info("Deleted KB topic: {}", topicId)
        }
        return removed
    }

    // ---- Entries ----

    fun listEntries(topicId: String, userId: String): List<KBEntry> =
        load().entries.filter { it.topicId == topicId && it.ownerId == userId }

    fun getEntry(entryId: String): KBEntry? =
        load().entries.find { it.id == entryId }

    fun createEntry(topicId: String, title: String, content: String, tags: List<String>, userId: String): KBEntry? {
        val store = load()
        if (store.topics.none { it.id == topicId && it.ownerId == userId }) return null
        val entry = KBEntry(
            id = "kb_entry_${System.currentTimeMillis()}_${(1000..9999).random()}",
            topicId = topicId,
            title = title,
            content = content,
            tags = tags,
            ownerId = userId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        store.entries.add(entry)
        save(store)
        logger.info("Created KB entry: {} in topic {}", entry.id, topicId)
        return entry
    }

    fun updateEntry(entryId: String, title: String?, content: String?, tags: List<String>?, userId: String): KBEntry? {
        val store = load()
        val idx = store.entries.indexOfFirst { it.id == entryId && it.ownerId == userId }
        if (idx == -1) return null
        val old = store.entries[idx]
        val updated = old.copy(
            title = title ?: old.title,
            content = content ?: old.content,
            tags = tags ?: old.tags,
            updatedAt = System.currentTimeMillis()
        )
        store.entries[idx] = updated
        save(store)
        return updated
    }

    fun deleteEntry(entryId: String, userId: String): Boolean {
        val store = load()
        val removed = store.entries.removeAll { it.id == entryId && it.ownerId == userId }
        if (removed) save(store)
        return removed
    }

    fun getTopic(topicId: String): KBTopic? = load().topics.find { it.id == topicId }
}
