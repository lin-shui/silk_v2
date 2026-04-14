package com.silk.backend.testsupport

import com.silk.backend.utils.WebPageDownloader
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

internal class LocalWebContentServer(
    html: String,
    pdfBytes: ByteArray,
    private val htmlPath: String = "/docs/ci-smoke",
    private val pdfPath: String = "/docs/ci-smoke.pdf",
    private val unsupportedPath: String = "/docs/unsupported-content",
    private val corruptPdfPath: String = "/docs/corrupt.pdf",
    private val slowHtmlPath: String = "/docs/slow-content"
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    init {
        server.createContext(htmlPath) { exchange ->
            respond(
                exchange = exchange,
                contentType = "text/html; charset=UTF-8",
                body = html.toByteArray(Charsets.UTF_8)
            )
        }
        server.createContext(pdfPath) { exchange ->
            respond(
                exchange = exchange,
                contentType = "application/pdf",
                body = pdfBytes
            )
        }
        server.createContext(unsupportedPath) { exchange ->
            respond(
                exchange = exchange,
                contentType = "application/octet-stream",
                body = byteArrayOf(0x13, 0x37)
            )
        }
        server.createContext(corruptPdfPath) { exchange ->
            respond(
                exchange = exchange,
                contentType = "application/pdf",
                body = "not-a-real-pdf".toByteArray(Charsets.UTF_8)
            )
        }
        server.createContext(slowHtmlPath) { exchange ->
            Thread.sleep(500)
            respond(
                exchange = exchange,
                contentType = "text/html; charset=UTF-8",
                body = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Slow Content</title></head>
                    <body><p>This endpoint intentionally responds too slowly for the smoke timeout override.</p></body>
                    </html>
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
        }
        server.start()
    }

    val baseUrl: String = "http://${server.address.hostString}:${server.address.port}"
    val htmlUrl: String = "$baseUrl$htmlPath"
    val pdfUrl: String = "$baseUrl$pdfPath"
    val unsupportedUrl: String = "$baseUrl$unsupportedPath"
    val corruptPdfUrl: String = "$baseUrl$corruptPdfPath"
    val slowHtmlUrl: String = "$baseUrl$slowHtmlPath"
    val missingUrl: String = "$baseUrl/docs/missing-content"
    val refusedUrl: String = unusedLoopbackHttpUrl("/docs/refused-content")

    override fun close() {
        server.stop(0)
        WebPageDownloader.shutdown()
    }

    private fun respond(exchange: HttpExchange, contentType: String, body: ByteArray) {
        runCatching {
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    companion object {
        fun createPdfBytes(
            title: String = "CI PDF Smoke",
            contentLine: String = "CI PDF smoke content for local fast validation."
        ): ByteArray {
            val output = ByteArrayOutputStream()
            PDDocument().use { document ->
                document.documentInformation = PDDocumentInformation().apply {
                    this.title = title
                }
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(72f, 720f)
                    stream.showText(contentLine)
                    stream.endText()
                }
                document.save(output)
            }
            return output.toByteArray()
        }
    }
}

internal class HttpOnlyWebPageDownloaderOverride(
    connectTimeoutMillis: Int? = null,
    readTimeoutMillis: Int? = null
) : AutoCloseable {
    private val properties = linkedMapOf(
        "silk.webPageDownloader.disablePlaywright" to "true"
    ).apply {
        connectTimeoutMillis?.let { put("silk.webPageDownloader.connectTimeoutMillis", it.toString()) }
        readTimeoutMillis?.let { put("silk.webPageDownloader.readTimeoutMillis", it.toString()) }
    }
    private val previous = properties.mapValues { (key, _) -> System.getProperty(key) }

    init {
        properties.forEach { (key, value) ->
            System.setProperty(key, value)
        }
    }

    override fun close() {
        previous.forEach { (key, value) ->
            if (value == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, value)
            }
        }
        WebPageDownloader.shutdown()
    }
}

private fun unusedLoopbackHttpUrl(path: String): String {
    val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { socket ->
        socket.localPort
    }
    return "http://127.0.0.1:$port$path"
}
