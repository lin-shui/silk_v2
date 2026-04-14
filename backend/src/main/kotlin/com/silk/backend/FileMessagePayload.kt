package com.silk.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FileMessagePayload(
    val fileName: String,
    val fileSize: Long,
    val downloadUrl: String
)

fun buildFileMessageContent(
    fileName: String,
    fileSize: Long,
    downloadUrl: String
): String = Json.encodeToString(
    FileMessagePayload(
        fileName = fileName,
        fileSize = fileSize,
        downloadUrl = downloadUrl
    )
)
