package com.silk.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val fileContractsJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class FileListApiResponse(
    val files: List<FileListApiItem> = emptyList(),
    val processedUrls: List<String> = emptyList()
)

@Serializable
private data class FileListApiItem(
    val fileName: String = "",
    val size: Long = 0,
    val uploadTime: Long = 0,
    val downloadUrl: String = ""
)

@Serializable
private data class FileMessageApiPayload(
    val fileName: String = "未知文件",
    val fileSize: Long = 0,
    val downloadUrl: String = ""
)

data class AndroidFileMessageContent(
    val fileName: String,
    val fileSize: Long,
    val downloadUrl: String
)

fun parseFileListAndUrls(json: String): FilesAndUrls {
    return runCatching {
        val response = fileContractsJson.decodeFromString<FileListApiResponse>(json)
        FilesAndUrls(
            files = response.files.map { file ->
                FileItem(
                    name = file.fileName,
                    size = file.size,
                    uploadTime = file.uploadTime,
                    uploadedBy = "",
                    downloadUrl = file.downloadUrl
                )
            },
            processedUrls = response.processedUrls
        )
    }.getOrElse {
        FilesAndUrls(emptyList(), emptyList())
    }
}

fun parseAndroidFileMessageContent(content: String): AndroidFileMessageContent {
    val trimmed = content.trim()
    if (trimmed.startsWith("{")) {
        return runCatching {
            val payload = fileContractsJson.decodeFromString<FileMessageApiPayload>(trimmed)
            AndroidFileMessageContent(
                fileName = payload.fileName,
                fileSize = payload.fileSize,
                downloadUrl = payload.downloadUrl
            )
        }.getOrElse {
            AndroidFileMessageContent(
                fileName = "解析失败",
                fileSize = 0L,
                downloadUrl = ""
            )
        }
    }

    val parts = content.split('|')
    return AndroidFileMessageContent(
        fileName = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "未知文件",
        fileSize = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
        downloadUrl = parts.getOrNull(2) ?: ""
    )
}

fun androidFileIconForName(fileName: String): String {
    return when (fileName.substringAfterLast(".", "").lowercase()) {
        "pdf" -> "📄"
        "doc", "docx" -> "📝"
        "xls", "xlsx" -> "📊"
        "ppt", "pptx" -> "📽"
        "jpg", "jpeg", "png", "gif", "webp" -> "🖼️"
        "mp4", "avi", "mov", "mkv" -> "🎬"
        "mp3", "wav", "ogg" -> "🎵"
        "zip", "rar", "7z", "tar", "gz" -> "📦"
        "txt", "md", "log" -> "📃"
        "json", "xml", "yaml", "yml" -> "⚙️"
        else -> "📎"
    }
}
