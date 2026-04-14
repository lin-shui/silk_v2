package com.silk.backend.utils

import com.silk.backend.testsupport.HttpOnlyWebPageDownloaderOverride
import com.silk.backend.testsupport.LocalWebContentServer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebPageDownloaderSmokeTest {
    @Test
    fun `extractUrls trims punctuation deduplicates and skips unsupported assets`() {
        val urls = WebPageDownloader.extractUrls(
            """
            请看这里：https://example.com/guide?chapter=ci，
            重复一次 https://example.com/guide?chapter=ci!
            这张图忽略 https://example.com/banner.png
            这个 PDF 要保留 https://example.com/files/ci-smoke.pdf)
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://example.com/guide?chapter=ci",
                "https://example.com/files/ci-smoke.pdf"
            ),
            urls
        )
    }

    @Test
    fun `downloadAndExtract downloads html from local server and saveToFile persists html snapshot`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>CI HTML Smoke</title>
                <script>console.log("ignore");</script>
            </head>
            <body>
                <header>header should be removed</header>
                <main>
                    <p>This local page proves the fast validation smoke covers HTML extraction without any internet dependency.</p>
                    <p>It also contains enough content to keep the extracted text above the fallback threshold in environments where Playwright is available.</p>
                </main>
                <footer>footer should be removed</footer>
            </body>
            </html>
        """.trimIndent()

        LocalWebContentServer(
            html = html,
            pdfBytes = LocalWebContentServer.createPdfBytes()
        ).use { localWeb ->
            HttpOnlyWebPageDownloaderOverride().use {
                val content = assertNotNull(WebPageDownloader.downloadAndExtract(localWeb.htmlUrl))
                assertFalse(content.isPdf)
                assertEquals("CI HTML Smoke", content.title)
                assertTrue(content.textContent.contains("fast validation smoke covers HTML extraction"))
                assertFalse(content.textContent.contains("header should be removed"))
                assertTrue(content.fileName.startsWith("webpage_CI_HTML_Smoke_"))
                assertTrue(content.fileName.endsWith(".html"))

                val uploadDir = Files.createTempDirectory("silk-html-smoke").toFile()
                try {
                    val savedFile = WebPageDownloader.saveToFile(content, uploadDir)
                    assertTrue(savedFile.isFile)
                    assertTrue(savedFile.readText().contains("""<meta name="source-url" content="${localWeb.htmlUrl}">"""))
                    assertTrue(savedFile.readText().contains("fast validation smoke covers HTML extraction"))
                } finally {
                    uploadDir.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun `downloadAndExtract downloads pdf from local server and saveToFile persists pdf with extracted text`() {
        val pdfBytes = LocalWebContentServer.createPdfBytes()

        LocalWebContentServer(
            html = "<html><head><title>unused</title></head><body>unused</body></html>",
            pdfBytes = pdfBytes
        ).use { localWeb ->
            HttpOnlyWebPageDownloaderOverride().use {
                val content = assertNotNull(WebPageDownloader.downloadAndExtract(localWeb.pdfUrl))
                assertTrue(content.isPdf)
                assertEquals("CI PDF Smoke", content.title)
                assertTrue(content.textContent.contains("CI PDF smoke content"))
                assertTrue(content.fileName.startsWith("webpage_CI_PDF_Smoke_"))
                assertTrue(content.fileName.endsWith(".pdf"))
                assertContentEquals(pdfBytes, content.pdfBytes)

                val uploadDir = Files.createTempDirectory("silk-pdf-smoke").toFile()
                try {
                    val savedFile = WebPageDownloader.saveToFile(content, uploadDir)
                    assertTrue(savedFile.isFile)
                    assertContentEquals(pdfBytes, savedFile.readBytes())

                    val extractedTextFile = uploadDir.resolve(savedFile.name.replace(".pdf", "_text.txt"))
                    assertTrue(extractedTextFile.isFile)
                    assertTrue(extractedTextFile.readText().contains("来源: ${localWeb.pdfUrl}"))
                    assertTrue(extractedTextFile.readText().contains("CI PDF smoke content"))
                } finally {
                    uploadDir.deleteRecursively()
                }
            }
        }
    }
}
