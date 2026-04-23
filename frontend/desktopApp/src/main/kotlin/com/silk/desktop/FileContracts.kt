package com.silk.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URLDecoder

private val fileContractsJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class FileMessageApiPayload(
    val fileName: String = "未知文件",
    val fileSize: Long = 0,
    val downloadUrl: String = ""
)

data class DesktopFileMessageContent(
    val fileName: String,
    val fileSize: Long,
    val downloadUrl: String
)

data class DesktopPdfReportContent(
    val downloadUrl: String,
    val fileName: String
)

fun parseDesktopFileMessageContent(content: String): DesktopFileMessageContent {
    val trimmed = content.trim()
    if (trimmed.startsWith("{")) {
        return runCatching {
            val payload = fileContractsJson.decodeFromString<FileMessageApiPayload>(trimmed)
            DesktopFileMessageContent(
                fileName = payload.fileName,
                fileSize = payload.fileSize,
                downloadUrl = payload.downloadUrl
            )
        }.getOrElse {
            DesktopFileMessageContent(
                fileName = "解析失败",
                fileSize = 0L,
                downloadUrl = ""
            )
        }
    }

    val parts = content.split('|')
    return DesktopFileMessageContent(
        fileName = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "未知文件",
        fileSize = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
        downloadUrl = parts.getOrNull(2) ?: ""
    )
}

fun parseDesktopPdfReportContent(content: String): DesktopPdfReportContent? {
    val downloadUrl = Regex("/download/report/[^\\r\\n]+\\.pdf")
        .find(content)
        ?.value
        ?.trim()
        ?: return null

    return DesktopPdfReportContent(
        downloadUrl = downloadUrl,
        fileName = extractFileNameFromDownloadUrl(downloadUrl, "diagnosis_report.pdf")
    )
}

fun extractFileNameFromDownloadUrl(downloadUrl: String, fallbackName: String = "未知文件"): String {
    val encodedFileName = downloadUrl.substringAfterLast("/", "")
    if (encodedFileName.isBlank()) return fallbackName

    return runCatching {
        URLDecoder.decode(encodedFileName, "UTF-8")
            .replace(Regex("[\\r\\n\\t]"), "")
            .trim()
            .ifBlank { fallbackName }
    }.getOrElse {
        encodedFileName
            .replace(Regex("[\\r\\n\\t]"), "")
            .trim()
            .ifBlank { fallbackName }
    }
}

fun desktopFileIconForName(fileName: String): String {
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

fun formatDesktopFileSize(size: Long): String {
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

fun resolveDownloadTargetFileName(selectedFilePath: String, defaultFileName: String, requiredExtension: String? = null): String {
    val normalizedRequiredExtension = requiredExtension?.trimStart('.')?.takeIf { it.isNotBlank() }
        ?: defaultFileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
    val alreadyHasExtension = selectedFilePath.substringAfterLast('/', selectedFilePath).substringAfterLast('\\', selectedFilePath).contains('.')

    if (alreadyHasExtension || normalizedRequiredExtension.isNullOrBlank()) {
        return selectedFilePath
    }

    return "$selectedFilePath.$normalizedRequiredExtension"
}
