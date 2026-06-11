package com.silk.backend.routes

import com.silk.backend.ai.AIConfig
import com.silk.backend.broadcastSystemStatus
import com.silk.backend.buildFileDownloadUrl
import com.silk.backend.database.SimpleResponse
import com.silk.backend.search.IndexDocument
import com.silk.backend.search.WeaviateClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Locale

private val logger = LoggerFactory.getLogger("FileRoutes")

// Weaviate 客户端（延迟初始化）
private val weaviateClient by lazy { 
    WeaviateClient(AIConfig.requireWeaviateUrl()) 
}

private val TEXT_FILE_EXTENSIONS = listOf(
    ".txt", ".md", ".markdown", ".json", ".xml", ".html", ".htm",
    ".css", ".js", ".kt", ".java", ".py", ".yaml", ".yml"
)

private const val DEFAULT_FILE_CHUNK_SIZE = 10000
private const val DEFAULT_FILE_CHUNK_OVERLAP = 500
private const val MAX_INDEX_KEYWORDS = 20

private fun chatHistoryRootDir(): File =
    System.getProperty("silk.chatHistoryDir")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(::File)
        ?: File("chat_history")

private fun normalizedSessionDir(sessionId: String): String =
    if (sessionId.startsWith("group_")) sessionId else "group_$sessionId"

private fun uploadsDirForSession(sessionId: String): File =
    File(File(chatHistoryRootDir(), normalizedSessionDir(sessionId)), "uploads")

/**
 * APK 版本信息响应
 */
@Serializable
data class AppVersionResponse(
    val versionCode: Int,
    val versionName: String,
    val lastModified: Long,
    val fileSize: Long,
    val downloadUrl: String
)

/**
 * 文件上传/下载路由
 */
fun Route.fileRoutes() {
    route("/api/files") {
        registerFileUploadRoute()
        registerFileDownloadRoute()
        registerAppVersionRoute()
        registerApkDownloadRoute()
        registerHapVersionRoute()
        registerHapDownloadRoute()
        registerFileListRoute()
        registerFileDeleteRoute()
    }
}

private fun Route.registerFileUploadRoute() {
    post("/upload") {
        val uploadError = runCatching {
            val upload = readUploadRequest(call.receiveMultipart())
            if (upload.sessionId.isNullOrBlank() || upload.userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId or userId"))
                return@post
            }

            if (upload.fileName.isNullOrBlank() || upload.fileBytes == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file provided"))
                return@post
            }

            val timestamp = System.currentTimeMillis()
            val sessionId = requireNotNull(upload.sessionId)
            val userId = requireNotNull(upload.userId)
            val originalFileName = requireNotNull(upload.fileName)
            val fileBytes = requireNotNull(upload.fileBytes)
            val targetFile = persistUploadedFile(sessionId, originalFileName, fileBytes)
            val safeFileName = targetFile.name

            logger.info("📁 文件已保存: ${targetFile.absolutePath}")

            val user = com.silk.backend.database.UserRepository.findUserById(userId)
            val userName = user?.fullName ?: "用户"

            call.respond(HttpStatusCode.OK, FileUploadResponse(
                success = true,
                fileId = safeFileName,
                fileName = originalFileName,
                filePath = targetFile.absolutePath,
                downloadUrl = buildFileDownloadUrl(sessionId, safeFileName),
                timestamp = timestamp,
                indexed = false,
                message = "文件已上传，正在索引..."
            ))

            launchFileIndexing(
                sessionId = sessionId,
                userId = userId,
                userName = userName,
                originalFileName = originalFileName,
                storedFileName = safeFileName,
                fileBytes = fileBytes,
                targetFile = targetFile,
                contentType = upload.contentType
            )
        }.exceptionOrNull()

        if (uploadError != null) {
            uploadError.rethrowIfCancellation()
            logger.error("❌ 文件上传失败: ${uploadError.message}", uploadError)
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "error" to "Upload failed: ${uploadError.message}"
            ))
        }
    }
}

private suspend fun readUploadRequest(multipart: io.ktor.http.content.MultiPartData): UploadRequest {
    var sessionId: String? = null
    var userId: String? = null
    var fileName: String? = null
    var fileBytes: ByteArray? = null
    var contentType: String? = null

    multipart.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> {
                when (part.name) {
                    "sessionId" -> sessionId = part.value
                    "userId" -> userId = part.value
                }
            }
            is PartData.FileItem -> {
                fileName = part.originalFileName
                contentType = part.contentType?.toString()
                fileBytes = part.streamProvider().readBytes()
            }
            else -> {}
        }
        part.dispose()
    }

    return UploadRequest(
        sessionId = sessionId,
        userId = userId,
        fileName = fileName,
        fileBytes = fileBytes,
        contentType = contentType
    )
}

private fun persistUploadedFile(sessionId: String, originalFileName: String, fileBytes: ByteArray): File {
    val sessionDir = uploadsDirForSession(sessionId)
    if (!sessionDir.exists()) {
        sessionDir.mkdirs()
    }

    val baseName = originalFileName.substringBeforeLast(".", originalFileName)
    val extension = originalFileName.substringAfterLast(".", "").let { if (it.isEmpty()) "" else ".$it" }

    var safeFileName = originalFileName
    var targetFile = File(sessionDir, safeFileName)
    var counter = 1
    while (targetFile.exists()) {
        safeFileName = "$baseName($counter)$extension"
        targetFile = File(sessionDir, safeFileName)
        counter++
    }
    targetFile.writeBytes(fileBytes)
    return targetFile
}

private fun launchFileIndexing(
    sessionId: String,
    userId: String,
    userName: String,
    originalFileName: String,
    storedFileName: String,
    fileBytes: ByteArray,
    targetFile: File,
    contentType: String?
) {
    CoroutineScope(Dispatchers.IO).launch {
        val indexingError = runCatching {
            com.silk.backend.broadcastFileMessage(
                groupId = sessionId,
                userId = userId,
                userName = userName,
                fileName = originalFileName,
                fileSize = fileBytes.size.toLong(),
                downloadUrl = buildFileDownloadUrl(sessionId, storedFileName)
            )

            broadcastSystemStatus(sessionId, "🔄 正在索引文件: $originalFileName ...")

            val indexed = indexFileToWeaviate(
                file = targetFile,
                sessionId = sessionId,
                userId = userId,
                originalFileName = originalFileName,
                contentType = contentType
            )

            if (indexed) {
                broadcastSystemStatus(sessionId, "✅ 文件索引完成: $originalFileName")
            } else {
                broadcastSystemStatus(sessionId, "⚠️ 文件索引失败: $originalFileName")
            }
        }.exceptionOrNull()

        if (indexingError != null) {
            indexingError.rethrowIfCancellation()
            logger.error("❌ 异步索引失败: ${indexingError.message}", indexingError)
            broadcastSystemStatus(sessionId, "❌ 文件索引异常: $originalFileName - ${indexingError.message}")
        }
    }
}

private data class UploadRequest(
    val sessionId: String?,
    val userId: String?,
    val fileName: String?,
    val fileBytes: ByteArray?,
    val contentType: String?
)

private fun Route.registerFileDownloadRoute() {
    get("/download/{sessionId}/{fileId}") {
        val sessionId = call.parameters["sessionId"]
        val fileId = call.parameters["fileId"]

        if (sessionId.isNullOrBlank() || fileId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing parameters"))
            return@get
        }

        val file = File(uploadsDirForSession(sessionId), fileId)
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
            return@get
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName,
                fileId
            ).toString()
        )

        call.respondFile(file)
    }
}

private fun Route.registerAppVersionRoute() {
    get("/app-version") {
        val possiblePaths = listOf(
            "static/silk.apk",
            "static/downloads/Silk-Android.apk",
            "../frontend/androidApp/build/outputs/apk/debug/androidApp-debug.apk",
            "frontend/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
        )

        val apkFile = possiblePaths
            .map { File(it) }
            .firstOrNull { it.exists() && it.length() > 0 }

        val lastModified = if (apkFile != null && apkFile.exists()) apkFile.lastModified() else System.currentTimeMillis()
        val fileSize = if (apkFile != null && apkFile.exists()) apkFile.length() else 0L

        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
            timeInMillis = lastModified
        }

        val year = calendar.get(java.util.Calendar.YEAR) - 2020
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)

        val versionCode = year * 100000000 + month * 1000000 + day * 10000 + hour * 100 + minute
        val versionName = String.format(
            Locale.ROOT,
            "%04d.%02d%02d.%02d%02d",
            calendar.get(java.util.Calendar.YEAR),
            month,
            day,
            hour,
            minute
        )

        logger.info("📱 APK 版本: $versionName (code=$versionCode) - 文件时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastModified))}")

        call.respond(AppVersionResponse(
            versionCode = versionCode,
            versionName = versionName,
            lastModified = lastModified,
            fileSize = fileSize,
            downloadUrl = "/api/files/download-apk"
        ))
    }
}

private fun Route.registerApkDownloadRoute() {
    get("/download-apk") {
        val possiblePaths = listOf(
            "static/silk.apk",
            "static/downloads/Silk-Android.apk",
            "static/silk-*.apk",
            "../frontend/androidApp/build/outputs/apk/debug/*.apk",
            "frontend/androidApp/build/outputs/apk/debug/*.apk"
        )

        val apkFile = possiblePaths
            .map { File(it) }
            .firstOrNull { it.exists() && it.length() > 0 }

        if (apkFile == null || !apkFile.exists()) {
            logger.warn("APK 文件不存在，已检查路径: $possiblePaths")
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "APK 文件不存在，请先构建 Android 应用"))
            return@get
        }

        val lastModified = apkFile.lastModified()
        val now = System.currentTimeMillis()
        if (now - lastModified < 5000) {
            logger.warn("⚠️ APK 文件可能正在更新中，请稍后再试")
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "文件正在更新中，请稍后再试"))
            return@get
        }

        logger.info("📱 提供 APK 下载: ${apkFile.name} (${apkFile.length()} bytes)")

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName,
                "Silk-Android.apk"
            ).toString()
        )

        call.respondFile(apkFile)
    }
}

private fun Route.registerHapVersionRoute() {
    get("/hap-version") {
        val possiblePaths = listOf(
            "static/silk.hap",
            "frontend/harmonyApp/entry/build/default/outputs/default/entry-default-signed.hap",
            "../frontend/harmonyApp/entry/build/default/outputs/default/entry-default-signed.hap"
        )

        val hapFile = possiblePaths
            .map { File(it) }
            .firstOrNull { it.exists() && it.length() > 0 }

        val lastModified = if (hapFile != null && hapFile.exists()) hapFile.lastModified() else System.currentTimeMillis()
        val fileSize = if (hapFile != null && hapFile.exists()) hapFile.length() else 0L

        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
            timeInMillis = lastModified
        }

        val year = calendar.get(java.util.Calendar.YEAR) - 2020
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)

        val versionCode = year * 100000000 + month * 1000000 + day * 10000 + hour * 100 + minute
        val versionName = String.format(
            Locale.ROOT,
            "%04d.%02d%02d.%02d%02d",
            calendar.get(java.util.Calendar.YEAR),
            month,
            day,
            hour,
            minute
        )

        logger.info("📱 HAP 版本: $versionName (code=$versionCode) - 文件时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastModified))}")

        call.respond(AppVersionResponse(
            versionCode = versionCode,
            versionName = versionName,
            lastModified = lastModified,
            fileSize = fileSize,
            downloadUrl = "/api/files/download-hap"
        ))
    }
}

private fun Route.registerHapDownloadRoute() {
    get("/download-hap") {
        val possiblePaths = listOf(
            "static/silk.hap",
            "frontend/harmonyApp/entry/build/default/outputs/default/entry-default-signed.hap",
            "../frontend/harmonyApp/entry/build/default/outputs/default/entry-default-signed.hap"
        )

        val hapFile = possiblePaths
            .map { File(it) }
            .firstOrNull { it.exists() && it.length() > 0 }

        if (hapFile == null || !hapFile.exists()) {
            logger.warn("HAP 文件不存在，已检查路径: $possiblePaths")
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "HAP 文件不存在，请先构建 Harmony 应用"))
            return@get
        }

        val lastModified = hapFile.lastModified()
        val now = System.currentTimeMillis()
        if (now - lastModified < 5000) {
            logger.warn("⚠️ HAP 文件可能正在更新中，请稍后再试")
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "文件正在更新中，请稍后再试"))
            return@get
        }

        logger.info("📱 提供 HAP 下载: ${hapFile.name} (${hapFile.length()} bytes)")

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName,
                "Silk-Harmony.hap"
            ).toString()
        )

        call.respondFile(hapFile)
    }
}

private fun Route.registerFileListRoute() {
    get("/list/{sessionId}") {
        val sessionId = call.parameters["sessionId"]

        if (sessionId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId"))
            return@get
        }

        val uploadsDir = uploadsDirForSession(sessionId)
        val processedUrlsFile = File(uploadsDir, "processed_urls.txt")
        val processedUrls = if (processedUrlsFile.exists()) {
            processedUrlsFile.readLines()
                .filter { it.isNotBlank() }
                .map { it.trim() }
        } else {
            emptyList()
        }

        if (!uploadsDir.exists()) {
            call.respond(HttpStatusCode.OK, FileListResponse(
                sessionId = sessionId,
                files = emptyList(),
                totalCount = 0,
                processedUrls = processedUrls
            ))
            return@get
        }

        val files = uploadsDir.listFiles()
            ?.filter { it.name != "processed_urls.txt" }
            ?.map { file ->
                FileInfo(
                    fileId = file.name,
                    fileName = file.name,
                    size = file.length(),
                    contentType = Files.probeContentType(file.toPath()) ?: "application/octet-stream",
                    uploadTime = file.lastModified(),
                    downloadUrl = buildFileDownloadUrl(sessionId, file.name)
                )
            }?.sortedByDescending { it.uploadTime } ?: emptyList()

        call.respond(HttpStatusCode.OK, FileListResponse(
            sessionId = sessionId,
            files = files,
            totalCount = files.size,
            processedUrls = processedUrls
        ))
    }
}

private fun Route.registerFileDeleteRoute() {
    delete("/{sessionId}/{fileId}") {
        val sessionId = call.parameters["sessionId"]
        val fileId = call.parameters["fileId"]

        if (sessionId.isNullOrBlank() || fileId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing parameters"))
            return@delete
        }

        val file = File(uploadsDirForSession(sessionId), fileId)
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
            return@delete
        }

        val deleted = file.delete()
        if (deleted) {
            logger.debug("文件已删除，搜索索引删除仍未接线: {}", file.absolutePath)
            call.respond(
                HttpStatusCode.OK,
                SimpleResponse(
                    success = true,
                    message = "File deleted"
                )
            )
        } else {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "error" to "Failed to delete file"
            ))
        }
    }
}

/**
 * 索引文件到 Weaviate
 */
private suspend fun indexFileToWeaviate(
    file: File,
    sessionId: String,
    userId: String,
    originalFileName: String,
    contentType: String?
): Boolean = withContext(Dispatchers.IO) {
    val indexingResult = runCatching {
        if (!canIndexToWeaviate(originalFileName)) {
            return@runCatching false
        }

        logger.info("🔍 开始索引文件到 Weaviate: $originalFileName (type: $contentType)")

        if (!isWeaviateReady()) {
            return@runCatching false
        }

        val content = readIndexableFileContent(file, originalFileName, contentType)
        val chunks = prepareChunks(cleanContent(content))
        val successCount = indexChunks(
            file = file,
            sessionId = sessionId,
            userId = userId,
            originalFileName = originalFileName,
            chunks = chunks
        )
        if (successCount == chunks.size) {
            logger.info("✅ 文件已索引到 Weaviate: $originalFileName (${chunks.size} 个块)")
        } else {
            logger.warn("⚠️ 文件索引部分失败: $originalFileName (成功: $successCount/${chunks.size})")
        }
        
        successCount > 0
    }

    indexingResult.exceptionOrNull()?.let { error ->
        error.rethrowIfCancellation()
        logger.error("❌ 索引文件失败: ${error.message}", error)
    }
    indexingResult.getOrDefault(false)
}

private fun canIndexToWeaviate(originalFileName: String): Boolean {
    if (AIConfig.WEAVIATE_URL.isBlank()) {
        logger.info("⏭️ 未配置 Weaviate，跳过文件索引: {}", originalFileName)
        return false
    }
    return true
}

private suspend fun isWeaviateReady(): Boolean {
    val isReady = weaviateClient.isReady()
    logger.info("🔍 Weaviate 状态: ${if (isReady) "✅ 可用" else "❌ 不可用"}")
    if (!isReady) {
        logger.warn("⚠️ Weaviate 不可用，跳过索引")
    }
    return isReady
}

private fun readIndexableFileContent(file: File, originalFileName: String, contentType: String?): String =
    when {
        isTextLikeFile(originalFileName, contentType) -> {
            logger.info("📝 读取文本文件: $originalFileName")
            file.readText()
        }

        isPdfFile(originalFileName, contentType) -> {
            logger.info("📄 提取 PDF 文本...")
            extractTextFromPdf(file) ?: "[PDF 文件: $originalFileName, 无法提取文本]"
        }

        else -> {
            logger.info("📦 二进制文件，仅索引元数据: $originalFileName (contentType: $contentType)")
            "[二进制文件: $originalFileName, 大小: ${file.length()} bytes]"
        }
    }

private fun isTextLikeFile(originalFileName: String, contentType: String?): Boolean =
    TEXT_FILE_EXTENSIONS.any { originalFileName.endsWith(it, ignoreCase = true) } ||
        contentType?.startsWith("text/") == true

private fun isPdfFile(originalFileName: String, contentType: String?): Boolean =
    contentType == "application/pdf" || originalFileName.endsWith(".pdf", ignoreCase = true)

private fun cleanContent(content: String): String {
    logger.info("📊 内容长度: ${content.length} 字符")
    val cleanedContent = content
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        .replace("\uFFFD", "")
        .replace("\uFEFF", "")
    logger.info("📊 清理后内容长度: ${cleanedContent.length} 字符")
    return cleanedContent
}

private fun prepareChunks(cleanedContent: String): List<ChunkInfo> {
    val chunks = if (cleanedContent.length > DEFAULT_FILE_CHUNK_SIZE) {
        logger.info("📦 内容较长 (${cleanedContent.length} 字符)，进行分块处理...")
        chunkText(cleanedContent, chunkSize = DEFAULT_FILE_CHUNK_SIZE, overlap = DEFAULT_FILE_CHUNK_OVERLAP)
    } else {
        listOf(ChunkInfo(cleanedContent, 0, 1))
    }
    logger.info("📦 共生成 ${chunks.size} 个块")
    return chunks
}

private suspend fun indexChunks(
    file: File,
    sessionId: String,
    userId: String,
    originalFileName: String,
    chunks: List<ChunkInfo>
): Int {
    val fileType = file.extension.uppercase()
    val participants = listOf(userId)
    val normalizedSessionId = normalizedSessionDir(sessionId)
    val filenameKeywords = extractKeywordsFromFilename(originalFileName)
    var successCount = 0

    for (chunk in chunks) {
        if (indexChunk(
                chunk = chunk,
                file = file,
                fileType = fileType,
                sessionId = normalizedSessionId,
                userId = userId,
                originalFileName = originalFileName,
                filenameKeywords = filenameKeywords,
                participants = participants
            )
        ) {
            successCount++
        }
    }

    return successCount
}

private suspend fun indexChunk(
    chunk: ChunkInfo,
    file: File,
    fileType: String,
    sessionId: String,
    userId: String,
    originalFileName: String,
    filenameKeywords: List<String>,
    participants: List<String>
): Boolean {
    val allKeywords = buildChunkKeywords(filenameKeywords, chunk)
    val summary = generateChunkSummary(
        chunk = chunk,
        fileName = originalFileName,
        fileType = fileType,
        keywords = allKeywords
    )

    return weaviateClient.indexDocument(
        document = IndexDocument(
            content = chunk.content,
            title = originalFileName,
            summary = summary,
            sourceType = "FILE",
            fileType = fileType,
            sessionId = sessionId,
            authorId = userId,
            filePath = file.absolutePath,
            timestamp = Instant.now().toString(),
            tags = allKeywords,
            chunkIndex = chunk.chunkIndex,
            totalChunks = chunk.totalChunks
        ),
        participants = participants
    )
}

private fun buildChunkKeywords(filenameKeywords: List<String>, chunk: ChunkInfo): List<String> =
    (filenameKeywords + extractKeywordsFromContent(chunk.content)).distinct().take(MAX_INDEX_KEYWORDS)

/**
 * 从 PDF 提取文本（使用 Apache PDFBox）
 * 确保提取所有页面
 */
private fun extractTextFromPdf(file: File): String? {
    val extractionResult = runCatching {
        logger.info("📄 正在提取 PDF 文本: ${file.name}")
        org.apache.pdfbox.pdmodel.PDDocument.load(file).use { document ->
            val stripper = org.apache.pdfbox.text.PDFTextStripper()

            // 明确设置提取所有页面（从第1页到最后1页）
            stripper.startPage = 1
            stripper.endPage = document.numberOfPages

            val text = stripper.getText(document)
            val pageCount = document.numberOfPages
            logger.info("✅ PDF 文本提取成功: ${text.length} 字符, 共 $pageCount 页")
            text // 不再限制长度，由分块处理
        }
    }
    extractionResult.exceptionOrNull()?.let { error ->
        logger.error("❌ PDF 文本提取失败: ${error.message}", error)
    }
    return extractionResult.getOrNull()
}

private fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) {
        throw this
    }
}

/**
 * 将长文本分块（用于大文档索引）
 * @param text 要分块的文本
 * @param chunkSize 每块的最大字符数
 * @param overlap 块之间的重叠字符数
 * @return 分块列表，每个块包含 content, chunkIndex, totalChunks
 */
private fun chunkText(text: String, chunkSize: Int = 10000, overlap: Int = 500): List<ChunkInfo> {
    if (text.length <= chunkSize) {
        return listOf(ChunkInfo(text, 0, 1))
    }

    val chunks = mutableListOf<ChunkInfo>()
    var start = 0
    var chunkIndex = 0

    while (start < text.length) {
        val end = resolveChunkEnd(text, start, chunkSize)
        val chunkText = text.substring(start, end).trim()
        if (chunkText.isNotEmpty()) {
            chunks.add(ChunkInfo(chunkText, chunkIndex, -1)) // totalChunks 稍后更新
            chunkIndex++
        }

        start = (end - overlap).coerceAtLeast(start + 1) // 确保前进
    }

    // 更新所有块的 totalChunks
    val totalChunks = chunks.size
    return chunks.map { it.copy(totalChunks = totalChunks) }
}

private fun resolveChunkEnd(text: String, start: Int, chunkSize: Int): Int {
    val proposedEnd = (start + chunkSize).coerceAtMost(text.length)
    if (proposedEnd >= text.length) return proposedEnd

    val searchStart = (proposedEnd - chunkSize / 2).coerceAtLeast(start)
    return findChunkBoundary(text, searchStart, proposedEnd) ?: proposedEnd
}

private fun findChunkBoundary(text: String, searchStart: Int, end: Int): Int? {
    val searchWindow = text.substring(searchStart, end)
    val separators = listOf("\n\n", "\n", "。", ". ", "! ", "? ")
    for (separator in separators) {
        val separatorPosition = searchWindow.lastIndexOf(separator)
        if (separatorPosition >= 0) {
            return searchStart + separatorPosition + separator.length
        }
    }
    return null
}

/**
 * 文本块信息
 */
private data class ChunkInfo(
    val content: String,
    val chunkIndex: Int,
    val totalChunks: Int
)

/**
 * 从文件名提取关键词
 * 例如: "User_Manual_2024.pdf" -> ["User", "Manual", "2024"]
 */
private fun extractKeywordsFromFilename(fileName: String): List<String> {
    val nameWithoutExt = fileName.substringBeforeLast(".")
    
    // 分割方式：下划线、连字符、空格、驼峰命名
    val keywords = mutableListOf<String>()
    
    // 按常见分隔符分割
    val parts = nameWithoutExt.split(Regex("[_\\-\\s.]+"))
    keywords.addAll(parts.filter { it.length > 1 && it.isNotBlank() })
    
    // 处理驼峰命名 (例如: "UserManual" -> ["User", "Manual"])
    val camelCaseParts = nameWithoutExt.split(Regex("(?<!^)(?=[A-Z])"))
    keywords.addAll(camelCaseParts.filter { it.length > 2 && !keywords.any { existing -> existing.equals(it, ignoreCase = true) } })
    
    // 过滤掉太短或纯数字的单词（除非是年份）
    return keywords
        .map { it.trim() }
        .filter { 
            it.length >= 2 && (
                it.any { char -> char.isLetter() } || // 包含字母
                (it.length == 4 && it.all { char -> char.isDigit() }) // 或4位数字（可能是年份）
            )
        }
        .distinctBy { it.lowercase() }
        .take(10) // 限制数量
}

/**
 * 从内容中提取关键词
 * 提取：首句、重复出现的词、重要术语
 */
private fun extractKeywordsFromContent(content: String): List<String> {
    val keywords = mutableListOf<String>()
    
    // 提取第一句（通常包含重要信息）
    val firstSentence = content.split(Regex("[.!?。！？\n]")).firstOrNull()?.trim()
    if (firstSentence != null && firstSentence.length > 10 && firstSentence.length < 200) {
        // 从首句中提取重要单词（长度>=3的单词）
        val words = firstSentence.split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.length >= 3 && it.any { char -> char.isLetter() } }
            .map { it.lowercase() }
            .take(5)
        keywords.addAll(words)
    }
    
    // 提取重复出现的词（可能是重要术语）
    val words = content.split(Regex("[\\s\\p{Punct}]+"))
        .filter { it.length >= 4 && it.any { char -> char.isLetter() } }
        .map { it.lowercase() }
        .filter { !it.matches(Regex("^(the|and|for|are|but|not|you|all|can|her|was|one|our|out|day|get|has|him|his|how|man|new|now|old|see|two|way|who|boy|did|its|let|put|say|she|too|use)$")) } // 过滤常见停用词
    
    val wordFreq = words.groupingBy { it }.eachCount()
    val frequentWords = wordFreq.filter { it.value >= 2 } // 出现至少2次
        .entries
        .sortedByDescending { it.value }
        .take(5)
        .map { it.key }
    
    keywords.addAll(frequentWords)
    
    return keywords.distinct().take(10)
}

/**
 * 为块生成摘要（包含关键词，用于搜索）
 */
private fun generateChunkSummary(
    chunk: ChunkInfo,
    fileName: String,
    fileType: String,
    keywords: List<String>
): String {
    val summary = StringBuilder()
    
    // 添加文件信息
    summary.append("文件: $fileName")
    if (fileType.isNotEmpty()) {
        summary.append(" (类型: $fileType)")
    }
    
    // 如果是多块文档，添加块信息
    if (chunk.totalChunks > 1) {
        summary.append(" [块 ${chunk.chunkIndex + 1}/${chunk.totalChunks}]")
    }
    
    // 添加关键词
    if (keywords.isNotEmpty()) {
        summary.append(" | 关键词: ${keywords.joinToString(", ")}")
    }
    
    // 添加内容摘要（前100个字符）
    val contentPreview = chunk.content
        .replace(Regex("\\s+"), " ") // 规范化空白
        .take(100)
        .trim()
    
    if (contentPreview.isNotEmpty()) {
        summary.append(" | $contentPreview")
        if (chunk.content.length > 100) {
            summary.append("...")
        }
    }
    
    return summary.toString()
}

// ===== 数据类 =====

@Serializable
data class FileUploadResponse(
    val success: Boolean,
    val fileId: String,
    val fileName: String,
    val filePath: String,
    val downloadUrl: String,
    val timestamp: Long,
    val indexed: Boolean,
    val message: String
)

@Serializable
data class FileListResponse(
    val sessionId: String,
    val files: List<FileInfo>,
    val totalCount: Int,
    val processedUrls: List<String> = emptyList()  // 已下载的 URL 清单
)

@Serializable
data class FileInfo(
    val fileId: String,
    val fileName: String,
    val size: Long,
    val contentType: String,
    val uploadTime: Long,
    val downloadUrl: String
)
