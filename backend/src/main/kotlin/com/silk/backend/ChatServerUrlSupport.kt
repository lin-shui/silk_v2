package com.silk.backend

import com.silk.backend.models.ChatHistoryEntry
import com.silk.backend.utils.WebPageContent
import com.silk.backend.utils.WebPageDownloader

internal suspend fun ChatServer.processUrlsInMessageSupport(message: Message) {
    logger.debug("🔗 [URL检测] 开始检测消息: {}...", message.content.take(50))
    val urls = WebPageDownloader.extractUrls(message.content)
    logger.debug("🔗 [URL检测] 提取到 {} 个URL: {}", urls.size, urls)

    val newUrls = filterNewUrls(urls)
    if (newUrls.isEmpty()) {
        return
    }

    logger.debug("🔗 检测到 {} 个URL，其中 {} 个是新的: {}", urls.size, newUrls.size, newUrls)
    val uploadDir = historyManager.getUploadsDir(currentSessionName)
    newUrls.forEach { url ->
        processSingleUrl(message, url, uploadDir)
    }
    clearUrlStatusAfterDelay()
}

private fun ChatServer.filterNewUrls(urls: List<String>): List<String> {
    if (urls.isEmpty()) {
        logger.debug("🔗 [URL检测] 没有URL，跳过")
        return emptyList()
    }
    val newUrls = urls.filterNot(::hasProcessedUrl)
    if (newUrls.isEmpty()) {
        logger.debug("🔗 检测到 {} 个URL，但都已处理过，跳过", urls.size)
    }
    return newUrls
}

private fun ChatServer.hasProcessedUrl(url: String): Boolean =
    processedUrls.contains(normalizeProcessedUrl(url))

private fun normalizeProcessedUrl(url: String): String =
    url.lowercase().trimEnd('/')

private suspend fun ChatServer.processSingleUrl(
    message: Message,
    url: String,
    uploadDir: java.io.File,
) {
    runChatCatching {
        broadcastSystemStatus("🌐 正在下载: $url")
        val content = WebPageDownloader.downloadAndExtract(url)
        if (content == null) {
            broadcastSystemStatus("⚠️ 无法下载: $url")
            return
        }
        handleDownloadedUrlContent(message, url, uploadDir, content)
    }.onFailure { error ->
        logger.error("❌ 处理URL失败: {}", url, error)
        broadcastSystemStatus("❌ 处理链接失败: $url")
    }
}

private suspend fun ChatServer.handleDownloadedUrlContent(
    message: Message,
    url: String,
    uploadDir: java.io.File,
    content: WebPageContent,
) {
    val normalizedUrl = normalizeProcessedUrl(url)
    processedUrls.add(normalizedUrl)
    saveProcessedUrl(normalizedUrl)

    val savedFile = WebPageDownloader.saveToFile(content, uploadDir)
    val fileType = if (content.isPdf) "PDF" else "网页"
    val downloadSessionId = currentSessionName.removePrefix("group_")
    broadcastSystemStatus("📄 已下载$fileType: ${content.title}")
    broadcast(
        Message(
            id = generateId(),
            userId = message.userId,
            userName = message.userName,
            content = buildFileMessageContent(
                fileName = savedFile.name,
                fileSize = savedFile.length(),
                downloadUrl = buildFileDownloadUrl(downloadSessionId, savedFile.name),
            ),
            timestamp = System.currentTimeMillis(),
            type = MessageType.FILE,
        ),
    )
    indexDownloadedUrlContent(message, content, fileType)
}

private suspend fun ChatServer.indexDownloadedUrlContent(
    message: Message,
    content: WebPageContent,
    fileType: String,
) {
    val participants = getSessionParticipants(message.userId)
    val webPageEntry = ChatHistoryEntry(
        messageId = "webpage_${System.currentTimeMillis()}",
        senderId = message.userId,
        senderName = "[$fileType] ${content.title}",
        content = buildDownloadedContentIndexBody(content, fileType),
        timestamp = System.currentTimeMillis(),
        messageType = if (content.isPdf) "PDF" else "WEBPAGE",
    )

    val indexed = silkAgent.indexMessageToSearch(webPageEntry, participants)
    if (indexed) {
        logger.debug("🔍 内容已索引: {}", content.title)
        broadcastSystemStatus("✅ 已索引$fileType: ${content.title}")
    }
}

private fun buildDownloadedContentIndexBody(
    content: WebPageContent,
    fileType: String,
): String = """
    来源URL: ${content.url}
    标题: ${content.title}
    类型: $fileType

    ${content.textContent.take(10000)}
""".trimIndent()

private suspend fun ChatServer.clearUrlStatusAfterDelay() {
    kotlinx.coroutines.delay(3000)
    broadcastSystemStatus("CLEAR_STATUS")
}
