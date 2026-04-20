package com.silk.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileContractsTest {
    @Test
    fun folderResponseParserPreservesEncodedDownloadUrls() {
        val json = """
            {
              "sessionId": "group-1",
              "files": [
                {
                  "fileName": "release notes #1?.md",
                  "size": 1536,
                  "uploadTime": 1710000000000,
                  "downloadUrl": "/api/files/download/group-1/release%20notes%20%231%3F.md",
                  "ignored": "value"
                }
              ],
              "processedUrls": ["http://127.0.0.1:9000/docs?id=1"],
              "totalCount": 1
            }
        """.trimIndent()

        val parsed = parseWebFolderContents(json)

        assertEquals(1, parsed.files.size)
        assertEquals("release notes #1?.md", parsed.files.single().name)
        assertEquals("/api/files/download/group-1/release%20notes%20%231%3F.md", parsed.files.single().downloadUrl)
        assertEquals(listOf("http://127.0.0.1:9000/docs?id=1"), parsed.processedUrls)
    }

    @Test
    fun fileMessageParserPreservesJsonPayload() {
        val content = """
            {
              "fileName": "quarterly \"report\".pdf",
              "fileSize": 2048,
              "downloadUrl": "/api/files/download/group-1/quarterly%20%22report%22.pdf"
            }
        """.trimIndent()

        val parsed = parseWebFileMessageContent(content)

        assertEquals("quarterly \"report\".pdf", parsed.fileName)
        assertEquals(2048L, parsed.fileSize)
        assertEquals("/api/files/download/group-1/quarterly%20%22report%22.pdf", parsed.downloadUrl)
        assertEquals("📄", webFileIconForName(parsed.fileName))
        assertEquals("2 KB", formatWebFileSize(parsed.fileSize))
    }

    @Test
    fun fileMessageParserSupportsLegacyPayload() {
        val parsed = parseWebFileMessageContent(
            "legacy export.json|512|/api/files/download/group-1/legacy%20export.json"
        )

        assertEquals("legacy export.json", parsed.fileName)
        assertEquals(512L, parsed.fileSize)
        assertEquals("/api/files/download/group-1/legacy%20export.json", parsed.downloadUrl)
        assertEquals("⚙", webFileIconForName(parsed.fileName))
    }

    @Test
    fun folderResponseParserFallsBackToEmptyResultOnMalformedJson() {
        val parsed = parseWebFolderContents("{invalid")

        assertTrue(parsed.files.isEmpty())
        assertTrue(parsed.processedUrls.isEmpty())
    }
}
