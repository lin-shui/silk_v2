package com.silk.backend.kb

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class KnowledgeBaseContextPreferences(
    val userId: String,
    val excludedSpaceIds: List<String> = emptyList(),
    /** 降权空间 ID 列表：这些空间的条目仍参与自动召回，但优先级降低。 */
    val downrankedSpaceIds: List<String> = emptyList(),
    val memoryEnabled: Boolean = true,
    val autoCaptureEnabled: Boolean = false,
    val ephemeralSessionEnabled: Boolean = false,
    val updatedAt: Long = 0L,
)

@Serializable
private data class KnowledgeBaseContextPreferenceStoreData(
    val users: MutableMap<String, KnowledgeBaseContextPreferences> = mutableMapOf(),
)

class KnowledgeBaseContextPreferenceStore(
    private val baseDir: String =
        System.getProperty("silk.kbDir")?.trim()?.takeIf { it.isNotEmpty() } ?: "knowledge_base",
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(KnowledgeBaseContextPreferenceStore::class.java)
    private val storeFile: File
        get() = File("$baseDir/context_preferences.json")

    init {
        File(baseDir).mkdirs()
    }

    @Synchronized
    fun get(userId: String): KnowledgeBaseContextPreferences {
        val normalizedUserId = userId.trim()
        if (normalizedUserId.isEmpty()) {
            return KnowledgeBaseContextPreferences(userId = "")
        }
        return load().users[normalizedUserId] ?: KnowledgeBaseContextPreferences(userId = normalizedUserId)
    }

    @Synchronized
    fun updateExcludedSpaces(userId: String, excludedSpaceIds: List<String>): KnowledgeBaseContextPreferences {
        return update(userId = userId, excludedSpaceIds = excludedSpaceIds)
    }

    @Synchronized
    fun updateDownrankedSpaces(userId: String, downrankedSpaceIds: List<String>): KnowledgeBaseContextPreferences {
        return update(userId = userId, downrankedSpaceIds = downrankedSpaceIds)
    }

    @Synchronized
    fun update(
        userId: String,
        excludedSpaceIds: List<String>? = null,
        downrankedSpaceIds: List<String>? = null,
        memoryEnabled: Boolean? = null,
        autoCaptureEnabled: Boolean? = null,
        ephemeralSessionEnabled: Boolean? = null,
    ): KnowledgeBaseContextPreferences {
        val normalizedUserId = userId.trim()
        require(normalizedUserId.isNotEmpty()) { "userId must not be blank" }
        val store = load()
        val existing = store.users[normalizedUserId] ?: KnowledgeBaseContextPreferences(userId = normalizedUserId)
        val normalizedExcludedSpaceIds = normalizeSpaceIds(excludedSpaceIds ?: existing.excludedSpaceIds)
        val normalizedDownrankedSpaceIds = normalizeSpaceIds(downrankedSpaceIds ?: existing.downrankedSpaceIds)
        val updated = KnowledgeBaseContextPreferences(
            userId = normalizedUserId,
            excludedSpaceIds = normalizedExcludedSpaceIds,
            downrankedSpaceIds = normalizedDownrankedSpaceIds,
            memoryEnabled = memoryEnabled ?: existing.memoryEnabled,
            autoCaptureEnabled = autoCaptureEnabled ?: existing.autoCaptureEnabled,
            ephemeralSessionEnabled = ephemeralSessionEnabled ?: existing.ephemeralSessionEnabled,
            updatedAt = System.currentTimeMillis(),
        )
        if (shouldRemove(updated)) {
            store.users.remove(normalizedUserId)
        } else {
            store.users[normalizedUserId] = updated
        }
        save(store)
        return if (shouldRemove(updated)) {
            KnowledgeBaseContextPreferences(userId = normalizedUserId)
        } else {
            updated
        }
    }

    private fun normalizeSpaceIds(spaceIds: List<String>): List<String> {
        return spaceIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
    }

    private fun shouldRemove(preferences: KnowledgeBaseContextPreferences): Boolean {
        return preferences.excludedSpaceIds.isEmpty() &&
            preferences.downrankedSpaceIds.isEmpty() &&
            preferences.memoryEnabled &&
            !preferences.autoCaptureEnabled &&
            !preferences.ephemeralSessionEnabled
    }

    @Synchronized
    private fun load(): KnowledgeBaseContextPreferenceStoreData {
        return if (storeFile.exists()) {
            try {
                json.decodeFromString<KnowledgeBaseContextPreferenceStoreData>(storeFile.readText())
            } catch (e: kotlinx.serialization.SerializationException) {
                logger.error("Failed to decode kb context preference store: {}", e.message)
                KnowledgeBaseContextPreferenceStoreData()
            } catch (e: IOException) {
                logger.error("Failed to read kb context preference store: {}", e.message)
                KnowledgeBaseContextPreferenceStoreData()
            }
        } else {
            KnowledgeBaseContextPreferenceStoreData()
        }
    }

    @Synchronized
    private fun save(store: KnowledgeBaseContextPreferenceStoreData) {
        File(baseDir).mkdirs()
        val tmp = File("${storeFile.path}.tmp")
        tmp.writeText(json.encodeToString(store))
        Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
