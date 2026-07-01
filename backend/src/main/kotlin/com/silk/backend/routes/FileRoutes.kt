package com.silk.backend.routes

import com.silk.backend.ai.AIConfig
import com.silk.backend.broadcastExtractedContent
import com.silk.backend.broadcastSystemStatus
import com.silk.backend.getGroupChatServer
import com.silk.backend.buildFileDownloadUrl
import com.silk.backend.database.SimpleResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import java.io.FileInputStream
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.time.Instant

private val logger = LoggerFactory.getLogger("FileRoutes")

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
 * 通过文件魔数 (magic bytes) 检测图片真实 Content-Type，
 * 不依赖文件扩展名。解决 Chrome 粘贴 webp 时命名 image.png
 * 但实际内容为 webp 导致的 Content-Type 错配问题。
 */
private fun detectImageContentTypeFromMagicBytes(file: File): String? {
    return try {
        val magic: ByteArray = FileInputStream(file).use { `in` ->
            val bytes = ByteArray(12)
            val read = `in`.read(bytes)
            if (read < 2) return@use ByteArray(0)
            bytes
        } ?: return null
        detectImageContentType(magic)
    } catch (_: Exception) {
        null
    }
}

/** magic 从 offset 处的字节是否依次等于 expected（越界视为不匹配）。 */
private fun bytesMatchAt(magic: ByteArray, offset: Int, vararg expected: Int): Boolean {
    if (magic.size < offset + expected.size) return false
    return expected.withIndex().all { (i, b) -> magic[offset + i] == b.toByte() }
}

/** 按魔数前缀判断图片 Content-Type（与原 when 链等价）。 */
private fun detectImageContentType(magic: ByteArray): String? = when {
    // PNG: 89 50 4E 47 0D 0A 1A 0A
    bytesMatchAt(magic, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
    // JPEG: FF D8 FF
    bytesMatchAt(magic, 0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
    // GIF: 47 49 46 38
    bytesMatchAt(magic, 0, 0x47, 0x49, 0x46, 0x38) -> "image/gif"
    // WebP: RIFF(4) + size(4) + WEBP(4)
    bytesMatchAt(magic, 0, 0x52, 0x49, 0x46, 0x46) && bytesMatchAt(magic, 8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"
    // BMP: 42 4D
    bytesMatchAt(magic, 0, 0x42, 0x4D) -> "image/bmp"
    // TIFF little-endian: 49 49 2A 00
    bytesMatchAt(magic, 0, 0x49, 0x49, 0x2A, 0x00) -> "image/tiff"
    // TIFF big-endian: 4D 4D 00 2A
    bytesMatchAt(magic, 0, 0x4D, 0x4D, 0x00, 0x2A) -> "image/tiff"
    else -> null
}

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
@Suppress("TooGenericExceptionCaught")
fun Route.fileRoutes() {

    route("/api/files") {

        /**
         * 上传文件到指定会话
         * POST /api/files/upload
         *
         * Form data:
         * - sessionId: 会话 ID
         * - userId: 用户 ID
         * - file: 文件
         */
        post("/upload") {
            handleFileUpload(call)
        }

        /**
         * 下载文件
         * GET /api/files/download/{sessionId}/{fileId}
         */
        get("/download/{sessionId}/{fileId}") {
            handleFileDownload(call)
        }

        /**
         * 获取最新 APK 版本信息
         * GET /api/files/app-version
         *
         * 版本号基于 APK 文件的修改时间自动生成：
         * - versionCode: (year-2020)*100000000 + month*1000000 + day*10000 + hour*100 + minute
         * - versionName: yyyy.MMdd.HHmm
         */
        get("/app-version") {
            // APK 文件路径 - 按优先级查找
            val possiblePaths = listOf(
                "static/silk.apk",                              // silk.sh 创建的符号链接（最新版本）
                "static/downloads/Silk-Android.apk",           // 已部署的稳定版本
                "../frontend/androidApp/build/outputs/apk/debug/androidApp-debug.apk",  // 构建目录
                "frontend/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
            )
            respondAppVersion(call, possiblePaths, "/api/files/download-apk", "APK")
        }

        /**
         * 下载最新 Android APK
         * GET /api/files/download-apk
         * 优先从 static/downloads 目录读取稳定版本
         */
        get("/download-apk") {
            // APK 文件路径 - 优先使用 silk-{version}.apk 或 silk.apk
            val possiblePaths = listOf(
                "static/silk.apk",                               // 符号链接指向最新版本（优先）
                "static/downloads/Silk-Android.apk",             // 已部署的稳定版本
                "static/silk-*.apk",                             // silk-{version}.apk 通配符
                "../frontend/androidApp/build/outputs/apk/debug/*.apk",  // 构建目录（备用）
                "frontend/androidApp/build/outputs/apk/debug/*.apk"
            )
            respondAppBinaryDownload(
                call, possiblePaths,
                attachmentName = "Silk-Android.apk",
                missingMessage = "APK 文件不存在，请先构建 Android 应用",
                label = "APK"
            )
        }

        /**
         * 获取最新 HAP 版本信息
         * GET /api/files/hap-version
         *
         * 版本号基于 HAP 文件的修改时间自动生成（与 APK 相同算法）：
         * - versionCode: (year-2020)*100000000 + month*1000000 + day*10000 + hour*100 + minute
         * - versionName: yyyy.MMdd.HHmm
         */
        get("/hap-version") {
            // HAP 文件路径 - 按优先级查找
            val possiblePaths = listOf(
                "static/silk.hap",                              // silk.sh 可能创建的符号链接
                "frontend/harmonyApp/entry/build/default/outputs/default/entry-default-signed.hap",  // 构建目录
                "../frontend/harmonyApp/entry/build/default/outputs/default/entry-default-signed.hap"
            )
            respondAppVersion(call, possiblePaths, "/api/files/download-hap", "HAP")
        }

        /**
         * 下载最新 Harmony HAP
         * GET /api/files/download-hap
         */
        get("/download-hap") {
            // HAP 文件路径 - 按优先级查找
            val possiblePaths = listOf(
                "static/silk.hap",                               // 符号链接（优先）
                "frontend/harmonyApp/entry/build/default/outputs/default/entry-default-signed.hap",  // 构建目录
                "../frontend/harmonyApp/entry/build/default/outputs/default/entry-default-signed.hap"
            )
            respondAppBinaryDownload(
                call, possiblePaths,
                attachmentName = "Silk-Harmony.hap",
                missingMessage = "HAP 文件不存在，请先构建 Harmony 应用",
                label = "HAP"
            )
        }

        /**
         * 获取会话文件列表
         * GET /api/files/list/{sessionId}
         */
        get("/list/{sessionId}") {
            handleFileList(call)
        }

        /**
         * 删除文件
         * DELETE /api/files/{sessionId}/{fileId}
         */
        delete("/{sessionId}/{fileId}") {
            handleFileDelete(call)
        }
    }
}

/** 处理文件下载 GET /api/files/download/{sessionId}/{fileId}（原内联处理体，与之等价）。 */
private suspend fun handleFileDownload(call: ApplicationCall) {
    val sessionId = call.parameters["sessionId"]
    val fileId = call.parameters["fileId"]

    if (sessionId.isNullOrBlank() || fileId.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing parameters"))
        return
    }

    // 处理 sessionId - 确保使用正确的目录格式
    val uploadsDir = uploadsDirForSession(sessionId)
    val file = File(uploadsDir, fileId)

    logger.info("📥 [下载] sessionId={} fileId={} fileAbs={} exists={} uploadsDir={} uploadsDirExists={}",
        sessionId, fileId, file.absolutePath, file.exists(),
        uploadsDir.absolutePath, uploadsDir.exists())

    if (!file.exists()) {
        // 列出上传目录中的文件帮助诊断
        val dirList = if (uploadsDir.exists()) {
            uploadsDir.list()?.joinToString(", ") ?: "(empty)"
        } else "(dir not found)"
        logger.warn("❌ [下载] 文件不存在: {} (目录内容: {})", file.absolutePath, dirList)
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
        return
    }

    // 确定 Content-Type：优先通过魔数检测真实类型（解决 Chrome 粘贴 webp 时
    // 命名 image.png 但内容实际为 webp 导致的 Content-Type 错配）
    val detectedContentType = detectImageContentTypeFromMagicBytes(file)
        ?: Files.probeContentType(file.toPath())
        ?: ContentType.Application.OctetStream.toString()

    // 图片用 Inline（浏览器展示），非图片用 Attachment（下载）
    val isImage = detectedContentType.startsWith("image/")
    // 对文件名进行百分号编码，防止 Netty 因中文等非 ASCII 字符拒绝 Content-Disposition 头
    val encodedFileId = java.net.URLEncoder.encode(fileId, Charsets.UTF_8.name())
        .replace("+", "%20")
    val dispositionType = if (isImage) ContentDisposition.Inline else ContentDisposition.Attachment
    val disposition = dispositionType.withParameter(ContentDisposition.Parameters.FileName, encodedFileId)
    call.response.header(HttpHeaders.ContentDisposition, disposition.toString())

    // 显式设置 Content-Type（基于 magic bytes 检测，不依赖文件扩展名），
    // 解决粘贴上传 webp 时 Chrome 命名 image.png 但实际内容为 webp 导致的显示空白问题
    call.respondBytes(
        bytes = file.readBytes(),
        contentType = ContentType.parse(detectedContentType)
    )
}

/**
 * 按修改时间生成版本号并返回 AppVersionResponse（与 app-version/hap-version 原逻辑等价）。
 */
private suspend fun respondAppVersion(
    call: ApplicationCall,
    possiblePaths: List<String>,
    downloadUrl: String,
    label: String,
) {
    val appFile = possiblePaths
        .map { File(it) }
        .firstOrNull { it.exists() && it.length() > 0 }

    val lastModified = if (appFile != null && appFile.exists()) appFile.lastModified() else System.currentTimeMillis()
    val fileSize = if (appFile != null && appFile.exists()) appFile.length() else 0L

    // 基于文件修改时间生成版本号
    val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
        timeInMillis = lastModified
    }

    val year = calendar.get(java.util.Calendar.YEAR) - 2020
    val month = calendar.get(java.util.Calendar.MONTH) + 1
    val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = calendar.get(java.util.Calendar.MINUTE)

    val versionCode = year * 100000000 + month * 1000000 + day * 10000 + hour * 100 + minute
    val versionName = String.format(java.util.Locale.ROOT, "%04d.%02d%02d.%02d%02d",
        calendar.get(java.util.Calendar.YEAR), month, day, hour, minute)

    logger.info("📱 $label 版本: $versionName (code=$versionCode) - 文件时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastModified))}")

    call.respond(AppVersionResponse(
        versionCode = versionCode,
        versionName = versionName,
        lastModified = lastModified,
        fileSize = fileSize,
        downloadUrl = downloadUrl
    ))
}

/**
 * 下载应用二进制（APK/HAP）。文件缺失/正在写入时返回相应错误（与原逻辑等价）。
 */
@Suppress("LongParameterList")
private suspend fun respondAppBinaryDownload(
    call: ApplicationCall,
    possiblePaths: List<String>,
    attachmentName: String,
    missingMessage: String,
    label: String,
) {
    val appFile = possiblePaths
        .map { File(it) }
        .firstOrNull { it.exists() && it.length() > 0 }  // 确保文件存在且不为空

    if (appFile == null || !appFile.exists()) {
        logger.warn("$label 文件不存在，已检查路径: $possiblePaths")
        call.respond(HttpStatusCode.NotFound, mapOf("error" to missingMessage))
        return
    }

    // 检查文件是否正在被写入（最后修改时间在 5 秒内）
    val lastModified = appFile.lastModified()
    val now = System.currentTimeMillis()
    if (now - lastModified < 5000) {
        logger.warn("⚠️ $label 文件可能正在更新中，请稍后再试")
        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "文件正在更新中，请稍后再试"))
        return
    }

    logger.info("📱 提供 $label 下载: ${appFile.name} (${appFile.length()} bytes)")

    call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(
            ContentDisposition.Parameters.FileName,
            attachmentName
        ).toString()
    )

    call.respondFile(appFile)
}

/** 处理会话文件列表 GET /api/files/list/{sessionId}（原内联处理体，与之等价）。 */
private suspend fun handleFileList(call: ApplicationCall) {
    val sessionId = call.parameters["sessionId"]

    if (sessionId.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId"))
        return
    }

    // 处理 sessionId - 确保使用正确的目录格式
    val uploadsDir = uploadsDirForSession(sessionId)

    // 读取已处理的 URL 清单
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
        return
    }

    val files = uploadsDir.listFiles()
        ?.filter { it.name != "processed_urls.txt" }  // 排除 URL 清单文件
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

/** 处理文件删除 DELETE /api/files/{sessionId}/{fileId}（原内联处理体，与之等价）。 */
@Suppress("TooGenericExceptionCaught")
private suspend fun handleFileDelete(call: ApplicationCall) {
    val sessionId = call.parameters["sessionId"]
    val fileId = call.parameters["fileId"]

    if (sessionId.isNullOrBlank() || fileId.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing parameters"))
        return
    }

    val file = File(uploadsDirForSession(sessionId), fileId)

    if (!file.exists()) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
        return
    }

    if (!file.delete()) {
        call.respond(HttpStatusCode.InternalServerError, mapOf(
            "error" to "Failed to delete file"
        ))
        return
    }

    // 从搜索索引中删除
    try {
        // 从搜索索引中删除文档（weaviateClient.deleteDocument() 暂未集成）
    } catch (e: Exception) {
        logger.warn("从索引删除文件失败: ${e.message}")
    }

    call.respond(
        HttpStatusCode.OK,
        SimpleResponse(
            success = true,
            message = "File deleted"
        )
    )
}

/** 上传请求解析出的表单字段。 */
private class UploadParts {
    var sessionId: String? = null
    var userId: String? = null
    var fileName: String? = null
    var fileBytes: ByteArray? = null
    var contentType: String? = null
    var userTextInput: String? = null
}

/** 解析 multipart 上传请求的各字段。 */
private suspend fun parseUploadParts(call: ApplicationCall): UploadParts {
    val parts = UploadParts()
    val multipart = call.receiveMultipart()
    multipart.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> when (part.name) {
                "sessionId" -> parts.sessionId = part.value
                "userId" -> parts.userId = part.value
                "text" -> parts.userTextInput = part.value
            }
            is PartData.FileItem -> {
                parts.fileName = part.originalFileName
                parts.contentType = part.contentType?.toString()
                parts.fileBytes = part.streamProvider().readBytes()
            }
            else -> {}
        }
        part.dispose()
    }
    return parts
}

/** 在 sessionDir 内生成唯一文件名（重名追加 (n) 后缀），写入字节，返回 (安全名, 目标文件)。 */
private fun writeUniqueUploadFile(sessionDir: File, originalName: String, fileBytes: ByteArray): Pair<String, File> {
    val baseName = if (originalName.contains(".")) originalName.substringBeforeLast(".") else originalName
    val extension = if (originalName.contains(".")) "." + originalName.substringAfterLast(".") else ""

    var safeFileName = originalName
    var targetFile = File(sessionDir, safeFileName)
    var counter = 1
    while (targetFile.exists()) {
        safeFileName = "$baseName($counter)$extension"
        targetFile = File(sessionDir, safeFileName)
        counter++
    }
    targetFile.writeBytes(fileBytes)
    return safeFileName to targetFile
}

private fun isImageFileName(name: String): Boolean {
    val ext = name.substringAfterLast(".", "").lowercase()
    return setOf("jpg", "jpeg", "png", "gif", "webp", "bmp").contains(ext)
}

/**
 * 处理文件上传 POST /api/files/upload（原内联 post 处理体，与之等价）。
 * 校验参数 → 保存唯一文件 → 广播合并消息并异步 vision → 返回成功 → 异步预处理。
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun handleFileUpload(call: ApplicationCall) {
    try {
        val parts = parseUploadParts(call)

        // 验证参数
        if (parts.sessionId.isNullOrBlank() || parts.userId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId or userId"))
            return
        }
        if (parts.fileName.isNullOrBlank() || parts.fileBytes == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file provided"))
            return
        }

        val sessionId = parts.sessionId!!
        val userId = parts.userId!!
        val fileName = parts.fileName!!
        val fileBytes = parts.fileBytes!!

        // 创建会话文件夹 - 确保使用 group_ 前缀保持一致
        val sessionDir = uploadsDirForSession(sessionId)
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }

        val timestamp = System.currentTimeMillis()
        val (safeFileName, targetFile) = writeUniqueUploadFile(sessionDir, fileName, fileBytes)
        logger.info("📁 文件已保存: ${targetFile.absolutePath}")

        // 获取用户名（用于显示在聊天消息中）
        val user = com.silk.backend.database.UserRepository.findUserById(userId)
        val userName = user?.fullName ?: "用户"

        val isImageFile = isImageFileName(fileName)

        // 广播用户合并消息（图片预览 + 文字，单条 SYSTEM 消息）
        val downloadUrl = buildFileDownloadUrl(sessionId, safeFileName)
        val fileSize = fileBytes.size.toLong()
        val userTextForMsg = parts.userTextInput?.trim() ?: ""

        broadcastUploadUserMessageAndVision(
            sessionId, userId, userName, fileName,
            targetFile, downloadUrl, fileSize, userTextForMsg, isImageFile
        )

        // 先返回上传成功响应（不阻塞用户）
        call.respond(HttpStatusCode.OK, FileUploadResponse(
            success = true,
            fileId = safeFileName,
            fileName = fileName,
            filePath = targetFile.absolutePath,
            downloadUrl = buildFileDownloadUrl(sessionId, safeFileName),
            timestamp = timestamp,
            indexed = false,
            message = "文件已上传，正在索引..."
        ))

        val hasUserText = parts.userTextInput?.trim().isNullOrBlank().not()
        CoroutineScope(Dispatchers.IO).launch {
            preprocessUploadedFile(
                finalSessionId = sessionId,
                finalUserId = userId,
                finalUserName = userName,
                finalFileName = fileName,
                finalSafeFileName = safeFileName,
                targetFile = targetFile,
                fileSize = fileSize,
                hasUserText = hasUserText,
                isImageFile = isImageFile,
            )
        }
    } catch (e: Exception) {
        logger.error("❌ 文件上传失败: ${e.message}", e)
        call.respond(HttpStatusCode.InternalServerError, mapOf(
            "error" to "Upload failed: ${e.message}"
        ))
    }
}

/**
 * 广播图片预览合并消息并启动异步 vision 处理；失败时兜底广播 FILE 消息。
 * 与原内联 try/catch 块等价。
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList")
private suspend fun broadcastUploadUserMessageAndVision(
    sessionId: String,
    userId: String,
    userName: String,
    fileName: String,
    targetFile: File,
    downloadUrl: String,
    fileSize: Long,
    userText: String,
    isImageFile: Boolean,
) {
    val previewContent = "##PREVIEW_IMAGE:${downloadUrl}##\n${userText}"
    try {
        val chatSvr = getGroupChatServer(sessionId)
        chatSvr.broadcastUserMessage(userId, userName, previewContent)
        logger.info("📸 已广播用户合并消息: {} + {}", fileName, userText.take(50))

        // 在后端协程中处理 vision（不走 WebSocket）
        CoroutineScope(Dispatchers.IO).launch {
            processUploadedImageVision(
                chatSvr = chatSvr,
                finalSessionId = sessionId,
                finalUserId = userId,
                finalUserName = userName,
                finalFileName = fileName,
                finalUserText = userText,
                targetFile = targetFile,
                downloadUrl = downloadUrl,
                isImageFile = isImageFile,
            )
        }
    } catch (e: Exception) {
        logger.error("❌ 广播用户消息失败: {}", e.message, e)
        // 兜底：尝试广播 FILE 消息
        try {
            com.silk.backend.broadcastFileMessage(
                groupId = sessionId, userId = userId, userName = userName,
                fileName = fileName, fileSize = fileSize,
                downloadUrl = downloadUrl
            )
        } catch (_: Exception) {}
    }
}

/**
 * 上传文件的异步预处理：必要时广播 FILE 消息，运行 FilePreprocessor 生成 .extracted.md，
 * 并按结果广播状态/OCR 提取信息。与原内联 launch 体等价。
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList")
private suspend fun preprocessUploadedFile(
    finalSessionId: String,
    finalUserId: String,
    finalUserName: String,
    finalFileName: String,
    finalSafeFileName: String,
    targetFile: File,
    fileSize: Long,
    hasUserText: Boolean,
    isImageFile: Boolean,
) {
    try {
        // 图片已通过 ##PREVIEW_IMAGE 显示，不再广播冗余 FILE 消息
        if (!hasUserText && !isImageFile) {
            com.silk.backend.broadcastFileMessage(
                groupId = finalSessionId,
                userId = finalUserId,
                userName = finalUserName,
                fileName = finalFileName,
                fileSize = fileSize,
                downloadUrl = buildFileDownloadUrl(finalSessionId, finalSafeFileName)
            )
        }

        // 文件预处理（生成 .extracted.md 供 Claude CLI 读取）
        val normalizedSession = if (finalSessionId.startsWith("group_")) finalSessionId else "group_$finalSessionId"
        val workspaceDir = "${com.silk.backend.ai.AIConfig.CLAUDE_CLI_WORKSPACE_ROOT}/$normalizedSession"
        java.io.File(workspaceDir).mkdirs()

        broadcastSystemStatus(finalSessionId, "🔄 正在解析文件: $finalFileName ...")

        val result = com.silk.backend.ai.FilePreprocessor.process(
            file = targetFile,
            originalFileName = finalFileName,
            sessionName = normalizedSession,
            workspaceDir = workspaceDir,
            userId = finalUserId,
            onVisionComplete = { updatedContent, _ ->
                // 检查待处理图片是否已被 text 消息消费（发给 vision 模型了）
                val chatSvr = getGroupChatServer(finalSessionId)
                if (chatSvr != null && chatSvr.hasPendingImage(finalUserId)) {
                    // 待处理图片还在，说明 text 还没到，vision 正常广播
                    val downloadUrl = buildFileDownloadUrl(finalSessionId, finalSafeFileName)
                    broadcastSystemStatus(finalSessionId, "✅ 图片解析完成: $finalFileName")
                    broadcastExtractedContent(finalSessionId, updatedContent, finalFileName, downloadUrl)
                } else {
                    // 待处理图片已被 text 消费，不再广播冗余的 vision 结果
                    logger.debug("📸 Vision 异步结果已由 text 合并处理，跳过广播: {}", finalFileName)
                }
            }
        )

        if (result.extractedTextFile != null) {
            reportOcrExtraction(result.extractedTextFile, finalSessionId, finalFileName)
        } else {
            broadcastSystemStatus(finalSessionId, "⚠️ 文件存储完成: $finalFileName (无法提取内容)")
        }
    } catch (e: Exception) {
        logger.error("❌ 文件预处理失败: ${e.message}", e)
        broadcastSystemStatus(finalSessionId, "❌ 文件处理异常: $finalFileName - ${e.message}")
    }
}

/** 读取 OCR 提取结果并广播解析完成状态（原内联 OCR 处理块，与之等价）。 */
@Suppress("TooGenericExceptionCaught")
private suspend fun reportOcrExtraction(extractedTextFile: File, finalSessionId: String, finalFileName: String) {
    try {
        val extractedContent = extractedTextFile.readText()

        // 从提取内容中提取 OCR 文本部分
        val ocrMatch = Regex("""## OCR 提取的文字\s+([\s\S]*?)(?=\n## |\Z)""").find(extractedContent)
        val ocrText = ocrMatch?.groupValues?.get(1)?.trim() ?: ""

        // OCR 结果不再单独广播（vision 回复会一并处理）
        broadcastSystemStatus(finalSessionId, "✅ 文件已解析: $finalFileName")

        // 更新待处理图片的 OCR 文字（供 ChatServer 使用）
        val chatSvr = getGroupChatServer(finalSessionId)
        if (chatSvr != null) {
            // 更新已有 pendingImage 的 OCR 文字（虽然当前已不再发给 vision 模型）
            logger.info("📸 OCR 文字已提取完成: {} 字符", ocrText.length)
        }
    } catch (e: Exception) {
        logger.warn("无法读取提取内容: ${e.message}")
    }
}

/**
 * 上传图片的 vision 异步处理：cc-connect 群组按前缀/单人模式转发给外部 agent，
 * 否则走 Silk 自带 vision；异常时广播错误结果。与原内联 launch 体等价。
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList")
private suspend fun processUploadedImageVision(
    chatSvr: com.silk.backend.ChatServer,
    finalSessionId: String,
    finalUserId: String,
    finalUserName: String,
    finalFileName: String,
    finalUserText: String,
    targetFile: File,
    downloadUrl: String,
    isImageFile: Boolean,
) {
    try {
        // === cc-connect image routing ===
        // If this is a cc-connect group and the caption starts with @cc / @claude prefix,
        // forward the image to cc-connect's agent (Claude) for vision processing
        // instead of Silk's built-in vision pipeline.
        // Solo mode (single member): no @-prefix needed, same as TEXT routing.
        val ccGroupId = finalSessionId.removePrefix("group_")
        val isCcConnected = com.silk.backend.ccconnect.CcConnectRegistry.isConnected(ccGroupId)

        val matchedPrefix = matchedCcConnectPrefix(ccGroupId, isCcConnected, finalUserText)
        val memberCount = com.silk.backend.database.GroupRepository.getGroupMemberCount(ccGroupId)
        val isSoloMode = memberCount <= 1L

        val forwardToCcConnect = isCcConnected && isImageFile && (isSoloMode || matchedPrefix != null)
        if (forwardToCcConnect) {
            val strippedText = stripCcConnectPrefix(finalUserText, matchedPrefix)
            chatSvr.forwardImageToCcConnect(
                imageFile = targetFile,
                userText = strippedText.trim(),
                downloadUrl = downloadUrl,
                userId = finalUserId,
                userName = finalUserName,
                ccGroupId = ccGroupId,
            )
        } else {
            chatSvr.handleVisionImageAndText(
                targetFile, "", finalUserText, downloadUrl, finalUserId
            )
        }
    } catch (e: Exception) {
        logger.error("❌ Vision 异步处理失败: {}", e.message, e)
        chatSvr.broadcastCombinedVisionResult(
            com.silk.backend.PendingImageState(finalUserId, "", finalFileName, targetFile, downloadUrl),
            "⚠️ Vision 分析出错: ${e.message}",
            System.currentTimeMillis()
        )
    }
}

/**
 * 返回标题命中的 cc-connect 触发前缀（@cc 或 @<trigger>），未连接或无命中返回 null。
 * 与原内联前缀匹配逻辑等价。
 */
private fun matchedCcConnectPrefix(ccGroupId: String, isCcConnected: Boolean, userText: String): String? {
    val connMeta = if (isCcConnected) {
        com.silk.backend.ccconnect.CcConnectRegistry.getConnectionInfo(ccGroupId)
    } else null
    val triggerName = com.silk.backend.ccconnect.agentTriggerName(connMeta?.agentType ?: "")
    val prefixes = buildSet {
        add("@cc")
        if (triggerName != "cc") add("@$triggerName")
    }
    return prefixes.firstOrNull { p ->
        userText.startsWith("$p ", ignoreCase = true) || userText.equals(p, ignoreCase = true)
    }
}

/** 去除命中的 cc-connect 前缀（带空格则多去一个空格），无前缀（单人模式）原样返回。 */
private fun stripCcConnectPrefix(userText: String, matchedPrefix: String?): String {
    if (matchedPrefix == null) return userText // solo mode, keep caption as-is
    return if (userText.startsWith("$matchedPrefix ", ignoreCase = true)) {
        userText.substring(matchedPrefix.length + 1)
    } else {
        userText.substring(matchedPrefix.length)
    }
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
