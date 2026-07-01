package com.silk.backend.ai

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import java.io.File
import javax.imageio.ImageIO

@Suppress("TooGenericExceptionCaught", "SwallowedException")
object PdfPipeline {

    private val logger = LoggerFactory.getLogger(PdfPipeline::class.java)

    fun process(
        file: File,
        originalFileName: String,
        uploadsDir: File,
        config: PreprocessConfig
    ): PreprocessResult {
        logger.info("PDF 管线开始处理: {}", originalFileName)

        val document = PDDocument.load(file)
        val pageCount = document.numberOfPages
        val sizeStr = FilePreprocessor.formatFileSize(file.length())

        val extractedImages = mutableListOf<ExtractedImage>()
        val pageTexts = mutableListOf<PageContent>()

        try {
            val stripper = PDFTextStripper()

            for (pageIndex in 0 until pageCount) {
                val pageNum = pageIndex + 1
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                val text = stripper.getText(document).trim()
                val images = extractImagesFromPage(document, pageIndex, originalFileName, uploadsDir)
                extractedImages.addAll(images)
                pageTexts.add(PageContent(pageNum, text, images))
            }
        } finally {
            document.close()
        }

        val imageDescriptions = mutableListOf<String>()
        if (extractedImages.isNotEmpty() && (config.visionEnabled || isOcrAvailable())) {
            for (img in extractedImages) {
                val desc = ImagePipeline.process(img.file, img.file.name, uploadsDir, config)
                imageDescriptions.add(desc.summary)
            }
        }

        val extractedFile = File(uploadsDir, "$originalFileName.extracted.md")
        extractedFile.writeText(buildMarkdown(originalFileName, sizeStr, pageCount, pageTexts, config))

        val totalText = pageTexts.sumOf { it.text.length }
        val summaryPreview = pageTexts.firstOrNull()?.text?.take(80)?.replace('\n', ' ')?.trim() ?: ""
        val summary = "${pageCount}页PDF, ${extractedImages.size}张嵌入图片. $summaryPreview..."

        logger.info("PDF 处理完成: {} ({} 页, {} 张图片, {} 字符)", originalFileName, pageCount, extractedImages.size, totalText)

        return PreprocessResult(
            extractedTextFile = extractedFile,
            summary = summary,
            pageCount = pageCount,
            imageCount = extractedImages.size,
            method = buildMethodString(extractedImages.isNotEmpty(), config)
        )
    }

    private fun extractImagesFromPage(
        document: PDDocument,
        pageIndex: Int,
        pdfFileName: String,
        uploadsDir: File
    ): List<ExtractedImage> {
        val images = mutableListOf<ExtractedImage>()
        try {
            val page = document.getPage(pageIndex)
            val resources = page.resources ?: return images
            val xObjectNames = resources.xObjectNames ?: return images

            var imgIndex = 0
            for (name in xObjectNames) {
                val xObject = resources.getXObject(name)
                if (xObject !is PDImageXObject) continue
                imgIndex++
                val extracted = writeImageIfLargeEnough(xObject, pageIndex, imgIndex, pdfFileName, uploadsDir)
                if (extracted != null) {
                    images.add(extracted)
                }
            }
        } catch (e: Exception) {
            logger.warn("提取第 {} 页图片失败: {}", pageIndex + 1, e.message)
        }
        return images
    }

    /**
     * 将单个嵌入图片写出为 PNG。图片过小（任一边 < 50px）时跳过并返回 null。
     */
    private fun writeImageIfLargeEnough(
        xObject: PDImageXObject,
        pageIndex: Int,
        imgIndex: Int,
        pdfFileName: String,
        uploadsDir: File
    ): ExtractedImage? {
        val bufferedImage = xObject.image
        if (bufferedImage.width < 50 || bufferedImage.height < 50) return null

        val imgFile = File(uploadsDir, "${pdfFileName}_page${pageIndex + 1}_img${imgIndex}.png")
        ImageIO.write(bufferedImage, "png", imgFile)
        return ExtractedImage(imgFile, pageIndex + 1, imgIndex)
    }

    @Suppress("UnusedParameter")
    private fun buildMarkdown(
        fileName: String,
        sizeStr: String,
        pageCount: Int,
        pages: List<PageContent>,
        config: PreprocessConfig
    ): String = buildString {
        appendLine("# 文件: $fileName")
        appendLine("类型: PDF | 页数: $pageCount | 大小: $sizeStr")
        appendLine()

        for (page in pages) {
            appendLine("## 第 ${page.pageNum} 页")
            appendLine()
            if (page.text.isNotBlank()) {
                appendLine(page.text)
            } else {
                appendLine("（此页无可提取文本）")
            }
            appendLine()

            for (img in page.images) {
                appendLine("### [嵌入图片 ${img.index}] (${img.file.name})")
                val imgExtracted = File(img.file.parentFile, "${img.file.name}.extracted.md")
                if (imgExtracted.exists()) {
                    val imgContent = imgExtracted.readText()
                    val ocrSection = extractSection(imgContent, "## OCR 提取的文字")
                    val descSection = extractSection(imgContent, "## 图片内容描述")
                    if (ocrSection.isNotBlank()) {
                        appendLine("- OCR 文字: $ocrSection")
                    }
                    if (descSection.isNotBlank()) {
                        appendLine("- 图片描述: $descSection")
                    }
                } else {
                    appendLine("- （图片描述未生成）")
                }
                appendLine()
            }
        }
    }

    private fun extractSection(content: String, header: String): String {
        val idx = content.indexOf(header)
        if (idx < 0) return ""
        val afterHeader = content.substring(idx + header.length).trimStart('\n', '\r')
        val nextHeader = afterHeader.indexOf("\n## ")
        val section = if (nextHeader >= 0) afterHeader.substring(0, nextHeader) else afterHeader
        return section.trim().take(500)
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

    private fun buildMethodString(hasImages: Boolean, config: PreprocessConfig): String {
        val parts = mutableListOf("pdfbox")
        if (hasImages) {
            if (isOcrAvailable()) parts.add("ocr")
            if (config.visionEnabled) parts.add("vision")
        }
        return parts.joinToString("+")
    }

    private data class PageContent(
        val pageNum: Int,
        val text: String,
        val images: List<ExtractedImage>
    )

    data class ExtractedImage(
        val file: File,
        val page: Int,
        val index: Int
    )
}
