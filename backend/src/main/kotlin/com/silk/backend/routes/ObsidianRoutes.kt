package com.silk.backend.routes

import com.silk.backend.ChatHistoryManager
import com.silk.backend.database.GroupRepository
import com.silk.backend.export.ChatObsidianExporter
import com.silk.backend.kb.KBObsidianExporter
import com.silk.backend.kb.KnowledgeBaseManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ObsidianRoutes")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Obsidian 一键同步 API。
 *
 * `GET /api/obsidian/sync?userId=xxx`
 *
 * 返回该用户所有群聊 + KB 条目的 Obsidian Markdown，
 * 供 Obsidian 插件一键拉取写入 vault。
 */
fun Route.obsidianRoutes() {
    get("/api/obsidian/sync") {
        val userId = call.request.queryParameters["userId"]?.trim().orEmpty()
        if (userId.isBlank()) {
            call.respondText(
                """{"success":false,"message":"Missing userId"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }

        // --- 1. 拉取用户所属群组 ---
        val groups = GroupRepository.getUserGroups(userId)
        val historyManager = ChatHistoryManager()
        val chatExports = mutableListOf<ObsidianChatExport>()

        for (group in groups) {
            try {
                val sessionName = "group_${group.id}"
                val chatHistory = historyManager.loadChatHistory(sessionName)
                if (chatHistory == null || chatHistory.messages.isEmpty()) continue

                val markdown = ChatObsidianExporter.toMarkdown(
                    groupId = group.id,
                    groupName = group.name,
                    sessionName = sessionName,
                    history = chatHistory,
                )
                // 保留 Unicode 字母数字（中文等正常显示），仅替换文件系统不允许的字符
                val safeGroupName = group.name
                    .replace(Regex("[^\\p{L}\\p{N}.\\-_]"), "_")
                    .trim('_')
                    .ifBlank { "group_${group.id}" }

                chatExports.add(
                    ObsidianChatExport(
                        groupId = group.id,
                        groupName = group.name,
                        updatedAt = chatHistory.messages.maxOf { it.timestamp },
                        messageCount = chatHistory.messages.size,
                        vaultPath = "Silk/Chats/$safeGroupName.md",
                        markdown = markdown,
                    )
                )
            } @Suppress("TooGenericExceptionCaught") catch (e: Exception) {
                logger.warn("Failed to export chat for group {}: {}", group.id, e.message)
            }
        }

        // --- 2. 拉取 KB 条目 ---
        val kbManager = KnowledgeBaseManager()
        val kbExports = mutableListOf<ObsidianKbExport>()

        try {
            val topics = kbManager.listTopics(userId)
            for (topic in topics) {
                val entries = kbManager.listEntries(topic.id, userId)
                for (entry in entries) {
                    try {
                        val markdown = KBObsidianExporter.toMarkdown(topic, entry)
                        val vaultPath = KBObsidianExporter.suggestVaultPath(topic, entry)
                        kbExports.add(
                            ObsidianKbExport(
                                entryId = entry.id,
                                title = entry.title,
                                topicName = topic.name,
                                project = topic.project,
                                updatedAt = entry.updatedAt,
                                vaultPath = vaultPath,
                                markdown = markdown,
                            )
                        )
                    } @Suppress("TooGenericExceptionCaught") catch (e: Exception) {
                        logger.warn("Failed to export KB entry {}: {}", entry.id, e.message)
                    }
                }
            }
        } @Suppress("TooGenericExceptionCaught") catch (e: Exception) {
            logger.warn("Failed to list KB topics for user {}: {}", userId, e.message)
        }

        // --- 3. 返回 ---
        val response = ObsidianSyncResponse(
            success = true,
            syncedAt = System.currentTimeMillis(),
            chats = chatExports,
            kbEntries = kbExports,
        )

        call.respondText(
            json.encodeToString(response),
            ContentType.Application.Json,
        )
    }
}

// ---- Response DTOs ----

@Serializable
data class ObsidianSyncResponse(
    val success: Boolean,
    val syncedAt: Long,
    val chats: List<ObsidianChatExport>,
    val kbEntries: List<ObsidianKbExport>,
)

@Serializable
data class ObsidianChatExport(
    val groupId: String,
    val groupName: String,
    val updatedAt: Long,
    val messageCount: Int,
    val vaultPath: String,
    val markdown: String,
)

@Serializable
data class ObsidianKbExport(
    val entryId: String,
    val title: String,
    val topicName: String,
    val project: String,
    val updatedAt: Long,
    val vaultPath: String,
    val markdown: String,
)
