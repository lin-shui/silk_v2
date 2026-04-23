package com.silk.backend

import com.silk.backend.database.DatabaseFactory
import com.silk.backend.database.GroupRepository
import com.silk.backend.search.WeaviateClient
import com.silk.backend.utils.WebPageDownloader
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
    try {
        syncGroupsToWeaviate(logger)
    } catch (e: Exception) {
        logger.warn("⚠️ 启动时同步群组到 Weaviate 失败: {}", e.message)
    }

    // 批量索引历史聊天记录到 Weaviate
    try {
        bulkIndexChatHistoryToWeaviate(logger)
    } catch (e: Exception) {
        logger.warn("⚠️ 批量索引历史聊天记录到 Weaviate 失败: {}", e.message)
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
private fun bulkIndexChatHistoryToWeaviate(logger: org.slf4j.Logger) {
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
        ?: emptyList()

    if (sessionDirs.isEmpty()) {
        logger.info("ℹ️ 没有找到需要索引的会话目录")
        return
    }

    val json = Json { ignoreUnknownKeys = true }
    var totalIndexed = 0
    var totalSkipped = 0
    var totalFailed = 0

    for (sessionDir in sessionDirs) {
        val sessionName = sessionDir.name
        val historyFile = File(sessionDir, "chat_history.json")
        if (!historyFile.exists()) continue

        try {
            val content = historyFile.readText()
            if (content.isBlank()) continue

            val chatHistory = json.decodeFromString<com.silk.backend.models.ChatHistory>(content)
            val messages = chatHistory.messages
            if (messages.isEmpty()) continue

            // 读取游标文件（记录最后索引的消息ID）
            val cursorFile = File(sessionDir, ".weaviate_cursor")
            val lastIndexedId = if (cursorFile.exists()) cursorFile.readText().trim() else ""

            // 找出尚未索引的消息
            val toIndex = if (lastIndexedId.isEmpty()) {
                messages
            } else {
                val lastIndex = messages.indexOfLast { it.messageId == lastIndexedId }
                if (lastIndex >= 0 && lastIndex < messages.size - 1) {
                    messages.subList(lastIndex + 1, messages.size)
                } else {
                    emptyList()
                }
            }

            if (toIndex.isEmpty()) {
                totalSkipped += messages.size
                logger.debug("⏭️ 会话 {} 已全部索引 ({} 条)", sessionName, messages.size)
                continue
            }

            // 获取参与者列表
            val groupId = sessionName.removePrefix("group_")
            val members = try {
                GroupRepository.getGroupMembers(groupId)
            } catch (_: Exception) {
                emptyList()
            }
            val participantIds = members.map { it.userId }.distinct()

            // 转换为 Weaviate.ChatMessage 格式
            val chatMessages = toIndex.map { msg ->
                com.silk.backend.search.ChatMessage(
                    userId = msg.senderId,
                    userName = msg.senderName ?: "未知用户",
                    content = msg.content,
                    timestamp = Instant.ofEpochMilli(msg.timestamp).toString(),
                    isImportant = false
                )
            }

            // 分批索引（每批 50 条，避免单次请求过大）
            val batchSize = 50
            var batchIndexed = 0
            for (i in chatMessages.indices step batchSize) {
                val batch = chatMessages.subList(i, minOf(i + batchSize, chatMessages.size))
                val count = runBlocking {
                    weaviate.indexChatMessages(
                        sessionId = "group_$groupId",
                        participants = participantIds,
                        messages = batch
                    )
                }
                batchIndexed += count
            }

            // 更新游标文件
            val lastMsg = toIndex.last()
            cursorFile.writeText(lastMsg.messageId)

            totalIndexed += batchIndexed
            logger.info("✅ 已索引会话 {}: {} 条新消息 (共 {} 条)", sessionName, batchIndexed, messages.size)
        } catch (e: Exception) {
            totalFailed++
            logger.warn("⚠️ 索引会话 {} 失败: {}", sessionName, e.message)
        }
    }

    logger.info("✅ 历史聊天记录批量索引完成: 新索引 {} 条, 跳过 {} 条, 失败 {} 个会话",
        totalIndexed, totalSkipped, totalFailed)
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
        try {
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
        } catch (e: Exception) {
            failed++
            logger.warn("⚠️ 同步群组 {} 失败: {}", group.id, e.message)
        }
    }
    logger.info("✅ 启动时群组同步完成: 成功 {} / 失败 {}", synced, failed)
}

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
    configureRouting()
}
