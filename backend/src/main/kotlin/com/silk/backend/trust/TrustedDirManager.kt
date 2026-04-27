package com.silk.backend.trust

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class TrustedDirStore(
    val entries: MutableMap<String, MutableList<TrustedDirRecord>> = mutableMapOf()
)

@Serializable
data class TrustedDirRecord(
    val bridgeId: String,
    val path: String,
    val trustedAt: Long,
)

class TrustedDirManager(
    private val baseDir: String =
        System.getProperty("silk.workflowDir")?.trim()?.takeIf { it.isNotEmpty() } ?: "workflows"
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(TrustedDirManager::class.java)
    private val storeFile get() = File("$baseDir/trusted_dirs.json")

    init {
        File(baseDir).mkdirs()
    }

    @Synchronized
    private fun load(): TrustedDirStore {
        return if (storeFile.exists()) {
            try {
                json.decodeFromString(storeFile.readText())
            } catch (e: Exception) {
                logger.error("Failed to load trusted dirs store: {}", e.message)
                TrustedDirStore()
            }
        } else TrustedDirStore()
    }

    @Synchronized
    private fun save(store: TrustedDirStore) {
        File(baseDir).mkdirs()
        val tmp = File("${storeFile.path}.tmp")
        tmp.writeText(json.encodeToString(store))
        Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun isTrusted(userId: String, bridgeId: String, path: String): Boolean {
        val store = load()
        val userEntries = store.entries[userId] ?: return false
        val normalizedPath = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            logger.warn("canonicalPath failed for '{}', falling back to raw path: {}", path, e.message)
            path.trimEnd('/')
        }
        return userEntries.any { record ->
            record.bridgeId == bridgeId && (
                normalizedPath == record.path || normalizedPath.startsWith("${record.path}/")
            )
        }
    }

    @Synchronized
    fun addTrust(userId: String, bridgeId: String, path: String): Boolean {
        val normalizedPath = File(path).canonicalPath
        val store = load()
        val userEntries = store.entries.getOrPut(userId) { mutableListOf() }
        val exists = userEntries.any { it.bridgeId == bridgeId && it.path == normalizedPath }
        if (exists) return false
        userEntries.add(
            TrustedDirRecord(
                bridgeId = bridgeId,
                path = normalizedPath,
                trustedAt = System.currentTimeMillis()
            )
        )
        save(store)
        logger.info("Added trust for user {} bridge {} path {}", userId, bridgeId, normalizedPath)
        return true
    }

    @Synchronized
    fun removeTrust(userId: String, bridgeId: String, path: String): Boolean {
        val normalizedPath = File(path).canonicalPath
        val store = load()
        val userEntries = store.entries[userId] ?: return false
        val removed = userEntries.removeAll { it.bridgeId == bridgeId && it.path == normalizedPath }
        if (removed) {
            save(store)
            logger.info("Removed trust for user {} bridge {} path {}", userId, bridgeId, normalizedPath)
        }
        return removed
    }

    fun listTrusts(userId: String, bridgeId: String? = null): List<TrustedDirRecord> {
        val store = load()
        val userEntries = store.entries[userId] ?: return emptyList()
        return if (bridgeId != null) {
            userEntries.filter { it.bridgeId == bridgeId }
        } else {
            userEntries.toList()
        }
    }
}
