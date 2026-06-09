package com.silk.backend

import com.silk.backend.database.DatabaseFactory
import com.silk.backend.database.GroupRepository
import com.silk.backend.search.WeaviateClient
import com.silk.backend.utils.WebPageDownloader
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

fun main() {
    // 优先加载 .env（避免用 gradlew 直接启动时读不到配置）
    EnvLoader.load()

    // 在 logger 初始化之前设置日志级别系统属性
    val logLevel = System.getProperty("log.level")
        ?: EnvLoader.get("LOG_LEVEL")
        ?: "INFO"
    System.setProperty("log.level", logLevel)

    val logger = LoggerFactory.getLogger("Application")
    logger.info("日志级别: {}, 日志文件: logs/silk-backend.log", logLevel)

    // 初始化数据库
    DatabaseFactory.init()

    // 启动后同步所有群组到 Weaviate SilkSession
    runCatching {
        syncGroupsToWeaviate(logger)
    }.onFailure { error ->
        logger.warn("⚠️ 启动时同步群组到 Weaviate 失败: {}", error.message)
    }

    // 批量索引历史聊天记录到 Weaviate
    runCatching {
        bulkIndexChatHistoryToWeaviate(logger)
    }.onFailure { error ->
        logger.warn("⚠️ 批量索引历史聊天记录到 Weaviate 失败: {}", error.message)
    }

    // 添加关闭钩子，清理 Playwright 资源
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("🔄 正在关闭服务...")
        WebPageDownloader.shutdown()
        logger.info("✅ 服务已关闭")
    })
    val port = EnvLoader.get("BACKEND_INTERNAL_PORT")?.toIntOrNull()
        ?: EnvLoader.get("BACKEND_HTTP_PORT")?.toIntOrNull()
        ?: 8003
    logger.info("🚀 启动后端服务，端口: {}", port)
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/**
 * 批量索引历史聊天记录到 Weaviate
 * 读取 chat_history/ 目录下所有会话的 chat_history.json，
 * 将尚未索引的消息批量写入 Weaviate，使 @silk 在部署后仍能搜索到历史上下文。
 *
 * 使用游标文件（.weaviate_cursor）记录每个会话最后索引的消息 ID，
 * 避免重复索引已处理过的消息。
 */
private fun bulkIndexChatHistoryToWeaviate(logger: Logger) {
    val weaviate = WeaviateClient.getInstance()
    if (!runBlocking { weaviate.isReady() }) {
        logger.warn("⚠️ Weaviate 不可用，跳过历史聊天记录批量索引")
        return
    }

    val chatHistoryDir = System.getProperty("silk.chatHistoryDir")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "chat_history"
    val baseFolder = File(chatHistoryDir)
    if (!baseFolder.exists() || !baseFolder.isDirectory) {
        logger.info("ℹ️ 聊天历史目录不存在，跳过批量索引: {}", chatHistoryDir)
        return
    }

    val sessionDirs = baseFolder.listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("group_") }
        .orEmpty()
    if (sessionDirs.isEmpty()) {
        logger.info("ℹ️ 没有找到需要索引的会话目录")
        return
    }

    val json = Json { ignoreUnknownKeys = true }
    val totals = SessionIndexTotals()
    sessionDirs
        .mapNotNull { sessionDir ->
            val historyFile = File(sessionDir, CHAT_HISTORY_FILE_NAME)
            sessionDir.takeIf { historyFile.exists() }?.let { it to historyFile }
        }
        .forEach { (sessionDir, historyFile) ->
            indexSessionHistory(
                sessionDir = sessionDir,
                historyFile = historyFile,
                json = json,
                weaviate = weaviate,
                logger = logger
            ).onSuccess(totals::record)
                .onFailure { error ->
                    totals.failedSessions++
                    logger.warn("⚠️ 索引会话 {} 失败: {}", sessionDir.name, error.message)
                }
        }

    logger.info("✅ 历史聊天记录批量索引完成: 新索引 {} 条, 跳过 {} 条, 失败 {} 个会话",
        totals.indexedMessages, totals.skippedMessages, totals.failedSessions)
}

/**
 * 同步所有 SQL 群组到 Weaviate SilkSession（在 deploy 或重启后恢复上下文关联）
 */
private fun syncGroupsToWeaviate(logger: org.slf4j.Logger) {
    val weaviate = WeaviateClient.getInstance()
    if (!runBlocking { weaviate.isReady() }) {
        logger.warn("⚠️ Weaviate 不可用，跳过群组同步")
        return
    }

    val groups = GroupRepository.getAllGroups()
    if (groups.isEmpty()) {
        logger.info("ℹ️ 没有需要同步的群组")
        return
    }

    var synced = 0
    var failed = 0
    for (group in groups) {
        runCatching {
            val members = GroupRepository.getGroupMembers(group.id)
            val participantIds = members.map { it.userId }
            runBlocking {
                weaviate.upsertSession(
                    com.silk.backend.search.SessionInfo(
                        sessionId = group.id,
                        sessionName = group.name,
                        participants = participantIds.distinct(),
                        ownerId = group.hostId
                    )
                )
            }
            synced++
            logger.debug("✅ 已同步群组 {} ({}) - {} 名参与者", group.name, group.id, participantIds.size)
        }.onFailure { error ->
            failed++
            logger.warn("⚠️ 同步群组 {} 失败: {}", group.id, error.message)
        }
    }
    logger.info("✅ 启动时群组同步完成: 成功 {} / 失败 {}", synced, failed)
}

private data class SessionIndexResult(
    val indexedCount: Int = 0,
    val skippedCount: Int = 0,
)

private data class SessionIndexTotals(
    var indexedMessages: Int = 0,
    var skippedMessages: Int = 0,
    var failedSessions: Int = 0,
) {
    fun record(result: SessionIndexResult) {
        indexedMessages += result.indexedCount
        skippedMessages += result.skippedCount
    }
}

private fun indexSessionHistory(
    sessionDir: File,
    historyFile: File,
    json: Json,
    weaviate: WeaviateClient,
    logger: Logger,
): Result<SessionIndexResult> = runCatching {
    val sessionName = sessionDir.name
    val messages = readHistoryMessages(historyFile, json)
    if (messages.isEmpty()) {
        return@runCatching SessionIndexResult()
    }

    val cursorFile = File(sessionDir, CURSOR_FILE_NAME)
    val pendingMessages = pendingMessages(messages, cursorFile)
    if (pendingMessages.isEmpty()) {
        logger.debug("⏭️ 会话 {} 已全部索引 ({} 条)", sessionName, messages.size)
        return@runCatching SessionIndexResult(skippedCount = messages.size)
    }

    val groupId = sessionName.removePrefix(GROUP_SESSION_PREFIX)
    val participantIds = loadParticipantIds(groupId)
    val indexedCount = indexMessagesInBatches(
        weaviate = weaviate,
        sessionId = "$GROUP_SESSION_PREFIX$groupId",
        participantIds = participantIds,
        messages = pendingMessages.map(::toWeaviateChatMessage)
    )

    cursorFile.writeText(pendingMessages.last().messageId)
    logger.info("✅ 已索引会话 {}: {} 条新消息 (共 {} 条)", sessionName, indexedCount, messages.size)
    SessionIndexResult(indexedCount = indexedCount)
}

private fun readHistoryMessages(
    historyFile: File,
    json: Json,
): List<com.silk.backend.models.ChatHistoryEntry> {
    val content = historyFile.readText()
    if (content.isBlank()) {
        return emptyList()
    }
    return json.decodeFromString<com.silk.backend.models.ChatHistory>(content).messages
}

private fun pendingMessages(
    messages: List<com.silk.backend.models.ChatHistoryEntry>,
    cursorFile: File,
): List<com.silk.backend.models.ChatHistoryEntry> {
    val lastIndexedId = cursorFile.takeIf(File::exists)?.readText()?.trim().orEmpty()
    if (lastIndexedId.isEmpty()) {
        return messages
    }
    val lastIndex = messages.indexOfLast { it.messageId == lastIndexedId }
    return if (lastIndex in 0 until messages.lastIndex) {
        messages.subList(lastIndex + 1, messages.size)
    } else {
        emptyList()
    }
}

private fun loadParticipantIds(groupId: String): List<String> = runCatching {
    GroupRepository.getGroupMembers(groupId)
}.getOrElse {
    emptyList()
}.map { it.userId }
    .distinct()

private fun toWeaviateChatMessage(
    message: com.silk.backend.models.ChatHistoryEntry,
): com.silk.backend.search.ChatMessage = com.silk.backend.search.ChatMessage(
    userId = message.senderId,
    userName = message.senderName,
    content = message.content,
    timestamp = Instant.ofEpochMilli(message.timestamp).toString(),
    isImportant = false,
    messageId = message.messageId
)

private fun indexMessagesInBatches(
    weaviate: WeaviateClient,
    sessionId: String,
    participantIds: List<String>,
    messages: List<com.silk.backend.search.ChatMessage>,
): Int = messages.chunked(WEAVIATE_BATCH_SIZE).sumOf { batch ->
    runBlocking {
        weaviate.indexChatMessages(
            sessionId = sessionId,
            participants = participantIds,
            messages = batch
        )
    }
}

private const val CHAT_HISTORY_FILE_NAME = "chat_history.json"
private const val CURSOR_FILE_NAME = ".weaviate_cursor"
private const val GROUP_SESSION_PREFIX = "group_"
private const val WEAVIATE_BATCH_SIZE = 50

fun Application.module() {
    install(CallLogging)
    install(Compression)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(CORS) {
        // 允许所有 HTTP 方法
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)

        // 允许标准头部
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        allowHeader(HttpHeaders.AccessControlAllowHeaders)

        // WebSocket 专用头部支持
        allowHeader(HttpHeaders.Upgrade)
        allowHeader(HttpHeaders.Connection)
        allowHeader("Sec-WebSocket-Key")
        allowHeader("Sec-WebSocket-Version")
        allowHeader("Sec-WebSocket-Extensions")
        allowHeader("Sec-WebSocket-Protocol")

        // 暴露自定义响应头
        exposeHeader(HttpHeaders.ContentDisposition)

        // 允许凭据（cookies）
        allowCredentials = true

        // 允许所有来源（生产环境建议限制为特定域名）
        anyHost()

        // 设置预检请求缓存时间（24小时）
        maxAgeInSeconds = 86400
    }

    configureWebSockets()

    // 触发 AgentRuntime 单例初始化（注册 ClaudeCodeDescriptor 等）
    com.silk.backend.agents.core.AgentRuntime.listRegisteredAgents()

    configureRouting()
}
