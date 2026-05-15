package com.silk.backend.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

data class PreprocessResult(
    val extractedTextFile: File?,
    val summary: String,
    val pageCount: Int = 0,
    val imageCount: Int = 0,
    val method: String = ""
)

data class PreprocessConfig(
    val visionEnabled: Boolean = AIConfig.VISION_ENABLED,
    val visionModel: String = AIConfig.VISION_MODEL,
    val ocrLanguages: String = AIConfig.OCR_LANGUAGES,
    val enabled: Boolean = AIConfig.FILE_PREPROCESS_ENABLED
)

object FilePreprocessor {

    private val logger = LoggerFactory.getLogger(FilePreprocessor::class.java)

    private val PDF_EXTENSIONS = setOf("pdf")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "tif")

    @Suppress("UNUSED_PARAMETER")
    suspend fun process(
        file: File,
        originalFileName: String,
        sessionName: String,
        workspaceDir: String,
        userId: String = "",
        config: PreprocessConfig = PreprocessConfig()
    ): PreprocessResult = withContext(Dispatchers.IO) {
        if (!config.enabled) {
            logger.info("文件预处理已禁用，跳过: {}", originalFileName)
            return@withContext PreprocessResult(null, "预处理已禁用")
        }

        val ext = originalFileName.substringAfterLast(".", "").lowercase()
        val uploadsDir = file.parentFile

        val registry = FileRegistry.load(uploadsDir)
        val existingEntry = registry.files[originalFileName]

        val contentHash = FileRegistry.computeHash(file)

        if (existingEntry != null && !FileRegistry.needsProcessing(existingEntry, contentHash, config)) {
            logger.info("文件已预处理且无变化，跳过: {}", originalFileName)
            syncToWorkspace(uploadsDir, workspaceDir, originalFileName, existingEntry)
            return@withContext PreprocessResult(
                extractedTextFile = File(uploadsDir, existingEntry.preprocessing.extractedFile),
                summary = existingEntry.preprocessing.summary,
                pageCount = existingEntry.preprocessing.pageCount,
                imageCount = existingEntry.preprocessing.imageCount,
                method = existingEntry.preprocessing.method
            )
        }

        FileRegistry.markProcessing(uploadsDir, originalFileName, contentHash, file.length(), userId)

        try {
            val result = when {
                ext in PDF_EXTENSIONS -> PdfPipeline.process(file, originalFileName, uploadsDir, config)
                ext in IMAGE_EXTENSIONS -> ImagePipeline.process(file, originalFileName, uploadsDir, config)
                else -> processTextOrSkip(file, originalFileName, uploadsDir)
            }

            if (result.extractedTextFile != null) {
                FileRegistry.markDone(uploadsDir, originalFileName, result, config)
                syncToWorkspace(uploadsDir, workspaceDir, originalFileName, null)
                updateManifest(uploadsDir, workspaceDir)
            } else {
                FileRegistry.markSkipped(uploadsDir, originalFileName)
            }

            result
        } catch (e: Exception) {
            logger.error("文件预处理失败: {} - {}", originalFileName, e.message, e)
            FileRegistry.markFailed(uploadsDir, originalFileName, e.message ?: "unknown error")
            PreprocessResult(null, "预处理失败: ${e.message}")
        }
    }

    private fun processTextOrSkip(file: File, originalFileName: String, uploadsDir: File): PreprocessResult {
        val textExtensions = setOf(
            "txt", "md", "markdown", "json", "xml", "html", "htm",
            "css", "js", "kt", "java", "py", "yaml", "yml", "csv", "log"
        )
        val ext = originalFileName.substringAfterLast(".", "").lowercase()
        if (ext !in textExtensions) {
            return PreprocessResult(null, "不支持的文件类型: $ext")
        }

        val content = file.readText()
        val extractedFile = File(uploadsDir, "$originalFileName.extracted.md")
        val sizeStr = formatFileSize(file.length())

        extractedFile.writeText(buildString {
            appendLine("# 文件: $originalFileName")
            appendLine("类型: ${ext.uppercase()} | 大小: $sizeStr")
            appendLine()
            appendLine("## 文件内容")
            appendLine()
            appendLine(content)
        })

        val summary = content.take(80).replace('\n', ' ').trim()
        return PreprocessResult(
            extractedTextFile = extractedFile,
            summary = "$ext 文本文件: $summary...",
            method = "text_read"
        )
    }

    fun syncToWorkspace(uploadsDir: File, workspaceDir: String, fileName: String, entry: FileEntry?) {
        val wsDir = File(workspaceDir)
        wsDir.mkdirs()

        val extractedFileName = entry?.preprocessing?.extractedFile ?: "$fileName.extracted.md"
        val source = File(uploadsDir, extractedFileName)
        val target = File(wsDir, extractedFileName)

        if (source.exists() && (!target.exists() || source.lastModified() > target.lastModified())) {
            source.copyTo(target, overwrite = true)
        }
    }

    fun syncAllToWorkspace(uploadsDir: File, workspaceDir: String) {
        val registry = FileRegistry.load(uploadsDir)
        val wsDir = File(workspaceDir)
        wsDir.mkdirs()

        for ((fileName, entry) in registry.files) {
            if (entry.preprocessing.status == "done") {
                syncToWorkspace(uploadsDir, workspaceDir, fileName, entry)
            }
        }
        updateManifest(uploadsDir, workspaceDir)
    }

    fun updateManifest(uploadsDir: File, workspaceDir: String) {
        val registry = FileRegistry.load(uploadsDir)
        val wsDir = File(workspaceDir)
        wsDir.mkdirs()

        val doneFiles = registry.files.filter { it.value.preprocessing.status == "done" }
        if (doneFiles.isEmpty()) return

        val manifest = File(wsDir, "files_manifest.md")
        manifest.writeText(buildString {
            appendLine("# 本群已上传的文件")
            appendLine()
            appendLine("共 ${doneFiles.size} 个文件已解析，可用 Read 工具查看详情。")
            appendLine()
            for ((name, entry) in doneFiles) {
                val pre = entry.preprocessing
                val sizeStr = formatFileSize(entry.fileSize)
                val typeInfo = when {
                    pre.pageCount > 0 -> "PDF, ${pre.pageCount}页, $sizeStr"
                    pre.imageCount > 0 -> "图片, $sizeStr"
                    else -> "${name.substringAfterLast('.').uppercase()}, $sizeStr"
                }
                appendLine("- **$name** ($typeInfo): ${pre.summary} -> `${pre.extractedFile}`")
            }
        })
    }

    fun formatFileSize(size: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            size < 1024 -> "$size B"
            size < mb -> "${String.format("%.1f", size / kb)} KB"
            size < gb -> "${String.format("%.1f", size / mb)} MB"
            else -> "${String.format("%.1f", size / gb)} GB"
        }
    }
}
