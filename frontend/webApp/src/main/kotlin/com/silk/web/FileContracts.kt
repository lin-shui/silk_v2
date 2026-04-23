package com.silk.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val fileContractsJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class FolderFileListApiResponse(
    val files: List<FolderFileApiItem> = emptyList(),
    val processedUrls: List<String> = emptyList()
)

@Serializable
private data class FolderFileApiItem(
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

data class WebFolderContents(
    val files: List<FileInfo>,
    val processedUrls: List<String>
)

data class WebFileMessageContent(
    val fileName: String,
    val fileSize: Long,
    val downloadUrl: String
)

fun parseWebFolderContents(json: String): WebFolderContents {
    return runCatching {
        val response = fileContractsJson.decodeFromString<FolderFileListApiResponse>(json)
        WebFolderContents(
            files = response.files.map { file ->
                FileInfo(
                    name = file.fileName,
                    size = file.size,
                    uploadTime = file.uploadTime,
                    downloadUrl = file.downloadUrl
                )
            },
            processedUrls = response.processedUrls
        )
    }.getOrElse {
        WebFolderContents(emptyList(), emptyList())
    }
}

fun parseWebFileMessageContent(content: String): WebFileMessageContent {
    val trimmed = content.trim()
    if (trimmed.startsWith("{")) {
        return runCatching {
            val payload = fileContractsJson.decodeFromString<FileMessageApiPayload>(trimmed)
            WebFileMessageContent(
                fileName = payload.fileName,
                fileSize = payload.fileSize,
                downloadUrl = payload.downloadUrl
            )
        }.getOrElse {
            WebFileMessageContent(
                fileName = "文件",
                fileSize = 0L,
                downloadUrl = ""
            )
        }
    }

    val parts = content.split('|')
    return WebFileMessageContent(
        fileName = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "未知文件",
        fileSize = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
        downloadUrl = parts.getOrNull(2) ?: ""
    )
}

fun webFileIconForName(fileName: String): String {
    return when (fileName.substringAfterLast(".", "").lowercase()) {
        "pdf" -> "📄"
        "doc", "docx" -> "📝"
        "xls", "xlsx" -> "📊"
        "ppt", "pptx" -> "📽"
        "jpg", "jpeg", "png", "gif", "webp" -> "🖼"
        "mp3", "wav", "ogg" -> "🎵"
        "mp4", "avi", "mov", "mkv" -> "🎬"
        "zip", "rar", "7z", "tar", "gz" -> "📦"
        "txt", "md", "log" -> "📃"
        "json", "xml", "yaml", "yml" -> "⚙"
        else -> "📎"
    }
}

fun formatWebFileSize(size: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        size < 1024 -> "$size B"
        size < mb -> "${(size / kb * 10).toInt() / 10.0} KB"
        size < gb -> "${(size / mb * 10).toInt() / 10.0} MB"
        else -> "${(size / gb * 10).toInt() / 10.0} GB"
    }
}
