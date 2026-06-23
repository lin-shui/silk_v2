package com.silk.backend.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.time.Instant

private val logger = LoggerFactory.getLogger("FileRegistry")
private val registryJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

@Serializable
data class FileRegistryData(
    val version: Int = 1,
    val files: MutableMap<String, FileEntry> = mutableMapOf()
)

@Serializable
data class FileEntry(
    val originalName: String,
    val fileSize: Long = 0,
    val contentHash: String = "",
    val uploadedAt: String = "",
    val uploadedBy: String = "",
    val preprocessing: PreprocessingState = PreprocessingState()
)

@Serializable
data class PreprocessingState(
    val status: String = "pending",
    val processedAt: String = "",
    val extractedFile: String = "",
    val method: String = "",
    val visionEnabled: Boolean = false,
    val ocrLanguages: String = "",
    val summary: String = "",
    val pageCount: Int = 0,
    val imageCount: Int = 0,
    val error: String? = null
)

@Suppress("TooGenericExceptionCaught")
object FileRegistry {

    private const val REGISTRY_FILE = "file_registry.json"

    fun load(uploadsDir: File): FileRegistryData {
        val file = File(uploadsDir, REGISTRY_FILE)
        if (!file.exists()) return FileRegistryData()
        return try {
            registryJson.decodeFromString<FileRegistryData>(file.readText())
        } catch (e: Exception) {
            logger.warn("读取 file_registry.json 失败，重新创建: {}", e.message)
            FileRegistryData()
        }
    }

    fun save(uploadsDir: File, data: FileRegistryData) {
        uploadsDir.mkdirs()
        val file = File(uploadsDir, REGISTRY_FILE)
        file.writeText(registryJson.encodeToString(data))
    }

    fun computeHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun needsProcessing(entry: FileEntry, currentHash: String, config: PreprocessConfig): Boolean {
        val pre = entry.preprocessing

        if (pre.status == "pending" || pre.status == "processing") return true
        if (pre.status == "failed") return true

        if (pre.status == "skipped") return false

        if (entry.contentHash != currentHash) return true

        if (config.visionEnabled && !pre.visionEnabled) return true

        return false
    }

    fun markProcessing(uploadsDir: File, fileName: String, contentHash: String, fileSize: Long, userId: String) {
        val data = load(uploadsDir)
        val existing = data.files[fileName]
        data.files[fileName] = FileEntry(
            originalName = fileName,
            fileSize = fileSize,
            contentHash = contentHash,
            uploadedAt = existing?.uploadedAt ?: Instant.now().toString(),
            uploadedBy = userId.ifBlank { existing?.uploadedBy ?: "" },
            preprocessing = PreprocessingState(status = "processing")
        )
        save(uploadsDir, data)
    }

    fun markDone(uploadsDir: File, fileName: String, result: PreprocessResult, config: PreprocessConfig) {
        val data = load(uploadsDir)
        val existing = data.files[fileName] ?: return
        data.files[fileName] = existing.copy(
            preprocessing = PreprocessingState(
                status = "done",
                processedAt = Instant.now().toString(),
                extractedFile = "$fileName.extracted.md",
                method = result.method,
                visionEnabled = config.visionEnabled,
                ocrLanguages = config.ocrLanguages,
                summary = result.summary.take(200),
                pageCount = result.pageCount,
                imageCount = result.imageCount,
                error = null
            )
        )
        save(uploadsDir, data)
    }

    fun markFailed(uploadsDir: File, fileName: String, error: String) {
        val data = load(uploadsDir)
        val existing = data.files[fileName] ?: return
        data.files[fileName] = existing.copy(
            preprocessing = existing.preprocessing.copy(
                status = "failed",
                processedAt = Instant.now().toString(),
                error = error.take(500)
            )
        )
        save(uploadsDir, data)
    }

    fun markSkipped(uploadsDir: File, fileName: String) {
        val data = load(uploadsDir)
        val existing = data.files[fileName] ?: return
        data.files[fileName] = existing.copy(
            preprocessing = existing.preprocessing.copy(
                status = "skipped",
                processedAt = Instant.now().toString()
            )
        )
        save(uploadsDir, data)
    }
}
