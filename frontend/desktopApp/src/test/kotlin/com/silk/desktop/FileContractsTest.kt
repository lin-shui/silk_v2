package com.silk.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileContractsTest {
    @Test
    fun parseDesktopFileMessageContentPreservesJsonPayload() {
        val parsed = parseDesktopFileMessageContent(
            """
                {
                  "fileName": "release notes #1?.md",
                  "fileSize": 1536,
                  "downloadUrl": "/api/files/download/group-1/release%20notes%20%231%3F.md"
                }
            """.trimIndent()
        )

        assertEquals("release notes #1?.md", parsed.fileName)
        assertEquals(1536L, parsed.fileSize)
        assertEquals("/api/files/download/group-1/release%20notes%20%231%3F.md", parsed.downloadUrl)
        assertEquals("📃", desktopFileIconForName(parsed.fileName))
        assertEquals("1.5 KB", formatDesktopFileSize(parsed.fileSize))
    }

    @Test
    fun parseDesktopFileMessageContentSupportsLegacyPayload() {
        val parsed = parseDesktopFileMessageContent(
            "legacy export.json|512|/api/files/download/group-1/legacy%20export.json"
        )

        assertEquals("legacy export.json", parsed.fileName)
        assertEquals(512L, parsed.fileSize)
        assertEquals("/api/files/download/group-1/legacy%20export.json", parsed.downloadUrl)
    }

    @Test
    fun parseDesktopPdfReportContentExtractsDecodedFileName() {
        val parsed = parseDesktopPdfReportContent(
            """
                诊断报告已生成：
                /download/report/demo-session/Quarterly%20Report%20%232.pdf
            """.trimIndent()
        )

        requireNotNull(parsed)
        assertEquals("/download/report/demo-session/Quarterly%20Report%20%232.pdf", parsed.downloadUrl)
        assertEquals("Quarterly Report #2.pdf", parsed.fileName)
    }

    @Test
    fun parseDesktopPdfReportContentReturnsNullWhenNoReportLinkExists() {
        assertNull(parseDesktopPdfReportContent("普通消息，没有下载链接"))
    }

    @Test
    fun resolveDownloadTargetFileNameAppendsMissingExtension() {
        assertEquals(
            "/tmp/Quarterly Report #2.pdf",
            resolveDownloadTargetFileName("/tmp/Quarterly Report #2", "Quarterly Report #2.pdf")
        )
        assertEquals(
            "/tmp/report.final.md",
            resolveDownloadTargetFileName("/tmp/report.final.md", "Quarterly Report #2.pdf")
        )
    }
}
