package com.silk.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileContractsTest {
    @Test
    fun `file list parser preserves encoded download urls and processed urls`() {
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

        val parsed = parseFileListAndUrls(json)

        assertEquals(1, parsed.files.size)
        assertEquals("release notes #1?.md", parsed.files.single().name)
        assertEquals("/api/files/download/group-1/release%20notes%20%231%3F.md", parsed.files.single().downloadUrl)
        assertEquals(listOf("http://127.0.0.1:9000/docs?id=1"), parsed.processedUrls)
    }

    @Test
    fun `file message parser preserves json payload`() {
        val parsed = parseAndroidFileMessageContent(
            """
                {
                  "fileName": "quarterly \"report\".pdf",
                  "fileSize": 2048,
                  "downloadUrl": "/api/files/download/group-1/quarterly%20%22report%22.pdf"
                }
            """.trimIndent()
        )

        assertEquals("quarterly \"report\".pdf", parsed.fileName)
        assertEquals(2048L, parsed.fileSize)
        assertEquals("/api/files/download/group-1/quarterly%20%22report%22.pdf", parsed.downloadUrl)
        assertEquals("📄", androidFileIconForName(parsed.fileName))
    }

    @Test
    fun `file message parser supports legacy payload`() {
        val parsed = parseAndroidFileMessageContent(
            "legacy export.json|512|/api/files/download/group-1/legacy%20export.json"
        )

        assertEquals("legacy export.json", parsed.fileName)
        assertEquals(512L, parsed.fileSize)
        assertEquals("/api/files/download/group-1/legacy%20export.json", parsed.downloadUrl)
        assertEquals("⚙️", androidFileIconForName(parsed.fileName))
    }

    @Test
    fun `file message parser returns fallback on malformed json`() {
        val parsed = parseAndroidFileMessageContent("{invalid")

        assertEquals("解析失败", parsed.fileName)
        assertEquals(0L, parsed.fileSize)
        assertTrue(parsed.downloadUrl.isEmpty())
    }
}
