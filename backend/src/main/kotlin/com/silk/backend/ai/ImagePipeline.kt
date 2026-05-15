package com.silk.backend.ai

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO

object ImagePipeline {

    private val logger = LoggerFactory.getLogger(ImagePipeline::class.java)

    private val httpClient by lazy {
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun process(
        file: File,
        originalFileName: String,
        uploadsDir: File,
        config: PreprocessConfig
    ): PreprocessResult {
        logger.info("图片管线开始处理: {}", originalFileName)

        val ext = originalFileName.substringAfterLast(".", "").lowercase()
        val sizeStr = FilePreprocessor.formatFileSize(file.length())
        val dimensions = getImageDimensions(file)

        var ocrText = ""
        var visionDescription = ""

        if (isOcrAvailable()) {
            ocrText = runOcr(file, config.ocrLanguages)
        }

        if (config.visionEnabled && AIConfig.ANTHROPIC_API_KEY.isNotBlank()) {
            visionDescription = callVisionApi(file, config.visionModel)
        }

        val extractedFile = File(uploadsDir, "$originalFileName.extracted.md")
        extractedFile.writeText(buildMarkdown(originalFileName, ext, sizeStr, dimensions, ocrText, visionDescription))

        val summary = buildSummary(ocrText, visionDescription)
        val method = buildMethodString(ocrText.isNotBlank(), visionDescription.isNotBlank())

        logger.info("图片处理完成: {} (OCR: {} 字符, Vision: {} 字符)", originalFileName, ocrText.length, visionDescription.length)

        return PreprocessResult(
            extractedTextFile = extractedFile,
            summary = summary,
            imageCount = 1,
            method = method
        )
    }

    private fun getImageDimensions(file: File): String {
        return try {
            val img = ImageIO.read(file) ?: return "未知"
            "${img.width}x${img.height}"
        } catch (e: Exception) {
            "未知"
        }
    }

    private fun isOcrAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("tesseract", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun runOcr(file: File, languages: String): String {
        return try {
            logger.debug("运行 Tesseract OCR: {}, 语言: {}", file.name, languages)
            val outputBase = File(file.parentFile, "${file.nameWithoutExtension}_ocr")
            val process = ProcessBuilder(
                "tesseract", file.absolutePath, outputBase.absolutePath,
                "-l", languages, "--psm", "3"
            )
                .redirectErrorStream(true)
                .start()

            val exitCode = process.waitFor()
            val outputFile = File("${outputBase.absolutePath}.txt")

            if (exitCode == 0 && outputFile.exists()) {
                val text = outputFile.readText().trim()
                outputFile.delete()
                text
            } else {
                val stderr = process.inputStream.bufferedReader().readText()
                logger.warn("Tesseract OCR 失败 (exit={}): {}", exitCode, stderr.take(200))
                ""
            }
        } catch (e: Exception) {
            logger.warn("OCR 执行异常: {}", e.message)
            ""
        }
    }

    private fun callVisionApi(file: File, model: String): String {
        return try {
            logger.debug("调用 Vision API: {}, 模型: {}", file.name, model)

            val imageBytes = file.readBytes()
            val base64Image = Base64.getEncoder().encodeToString(imageBytes)
            val mediaType = when (file.extension.lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }

            val requestBody = buildJsonObject {
                put("model", model)
                put("max_tokens", 2048)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", mediaType)
                                    put("data", base64Image)
                                }
                            }
                            addJsonObject {
                                put("type", "text")
                                put("text", "请用中文详细描述这张图片的内容，包括：文字内容、图表数据、布局结构、关键视觉元素等所有可见信息。如果图片中有文字，请完整转录。")
                            }
                        }
                    }
                }
            }

            val baseUrl = AIConfig.ANTHROPIC_API_BASE_URL.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", AIConfig.ANTHROPIC_API_KEY)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val body = json.parseToJsonElement(response.body()).jsonObject
                val content = body["content"]?.jsonArray?.firstOrNull()?.jsonObject
                content?.get("text")?.jsonPrimitive?.content ?: ""
            } else {
                logger.warn("Vision API 返回 {}: {}", response.statusCode(), response.body().take(200))
                ""
            }
        } catch (e: Exception) {
            logger.error("Vision API 调用失败: {}", e.message)
            ""
        }
    }

    private fun buildMarkdown(
        fileName: String,
        ext: String,
        sizeStr: String,
        dimensions: String,
        ocrText: String,
        visionDescription: String
    ): String = buildString {
        appendLine("# 图片: $fileName")
        appendLine("类型: ${ext.uppercase()} | 尺寸: $dimensions | 大小: $sizeStr")
        appendLine()

        if (ocrText.isNotBlank()) {
            appendLine("## OCR 提取的文字")
            appendLine()
            appendLine(ocrText)
            appendLine()
        }

        if (visionDescription.isNotBlank()) {
            appendLine("## 图片内容描述")
            appendLine()
            appendLine(visionDescription)
            appendLine()
        }

        if (ocrText.isBlank() && visionDescription.isBlank()) {
            appendLine("## 备注")
            appendLine()
            appendLine("未能提取图片内容（OCR 和 Vision API 均不可用或未返回结果）。")
        }
    }

    private fun buildSummary(ocrText: String, visionDescription: String): String {
        val source = visionDescription.ifBlank { ocrText }
        return if (source.isNotBlank()) {
            source.take(100).replace('\n', ' ').trim()
        } else {
            "图片内容未能解析"
        }
    }

    private fun buildMethodString(hasOcr: Boolean, hasVision: Boolean): String {
        val parts = mutableListOf<String>()
        if (hasOcr) parts.add("ocr")
        if (hasVision) parts.add("vision")
        return if (parts.isEmpty()) "metadata_only" else parts.joinToString("+")
    }
}
