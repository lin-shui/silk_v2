package com.silk.backend

import java.net.URLEncoder

private fun encodeDownloadPathSegment(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")

fun buildFileDownloadUrl(sessionId: String, fileId: String): String =
    "/api/files/download/${encodeDownloadPathSegment(sessionId)}/${encodeDownloadPathSegment(fileId)}"
