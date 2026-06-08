package com.silk.backend.trust

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.AccessDeniedException

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
        System.getProperty("silk.workflowDir")?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv("SILK_WORKFLOW_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${System.getProperty("user.home")}/.silk-data/workflows"
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
                json.decodeFromString<TrustedDirStore>(storeFile.readText())
            } catch (e: kotlinx.serialization.SerializationException) {
                logger.error("Failed to decode trusted dirs store: {}", e.message)
                TrustedDirStore()
            } catch (e: IOException) {
                logger.error("Failed to read trusted dirs store: {}", e.message)
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
        val normalizedPath = normalizePathForLookup(path)
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

    private fun normalizePathForLookup(path: String): String {
        return try {
            Path.of(path).toFile().canonicalPath
        } catch (e: InvalidPathException) {
            logger.warn("Invalid trust path '{}', falling back to raw path: {}", path, e.message)
            path.trimEnd('/')
        } catch (e: NoSuchFileException) {
            logger.warn("Path disappeared while checking trust '{}', falling back to raw path: {}", path, e.message)
            path.trimEnd('/')
        } catch (e: AccessDeniedException) {
            logger.warn("Access denied while checking trust '{}', falling back to raw path: {}", path, e.message)
            path.trimEnd('/')
        } catch (e: SecurityException) {
            logger.warn("Security manager rejected trust path '{}', falling back to raw path: {}", path, e.message)
            path.trimEnd('/')
        } catch (e: IOException) {
            logger.warn("canonicalPath failed for '{}', falling back to raw path: {}", path, e.message)
            path.trimEnd('/')
        }
    }
}
