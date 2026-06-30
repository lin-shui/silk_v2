package com.silk.backend

import com.silk.backend.database.Group
import com.silk.backend.database.GroupRepository
import com.silk.backend.models.ChatHistory
import com.silk.backend.models.SessionData
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * 用户历史会话 workspace 管理器。
 *
 * 为每个用户在 [workspaceBaseDir] 下维护一个目录，
 * 通过硬链接（hardlink）将该用户所属群组的 chat_history.json / session.json
 * 映射到 workspace 内，供只读 Claude agent（dontAsk 模式）探索。
 *
 * 硬链接特性：
 * - 零额外磁盘开销（共享 inode）
 * - 对 dontAsk 模式透明（看起来就是普通文件）
 * - 后端原子写入（temp -> rename）会创建新 inode 导致断链，
 *   每次 [ensureWorkspace] 会检测并重建。
 */
class UserWorkspaceManager(
    private val chatHistoryDir: String =
        System.getProperty("silk.chatHistoryDir")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "chat_history",
    private val workspaceBaseDir: String = "user_workspace_views"
) {
    private val logger = LoggerFactory.getLogger(UserWorkspaceManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        Path.of(workspaceBaseDir).toFile().mkdirs()
    }

    /**
     * 确保用户 workspace 存在且硬链接指向最新 inode。
     * 每次历史查询前调用。
     *
     * @return workspace 目录的绝对路径（作为 agent CWD）
     */
    fun ensureWorkspace(userId: String): Path {
        val wsDir = Path.of(workspaceBaseDir, "user_$userId")
        wsDir.toFile().mkdirs()

        val userGroups = GroupRepository.getUserGroups(userId)
        syncHardlinks(wsDir, userGroups)
        writeIndex(wsDir, userGroups)

        logger.info("UserWorkspace ready: userId={}, groups={}, path={}",
            userId, userGroups.size, wsDir.toAbsolutePath())
        return wsDir.toAbsolutePath()
    }

    /**
     * 同步硬链接：创建缺失的、刷新断链的、清理已退出群组的。
     */
    private fun syncHardlinks(wsDir: Path, groups: List<Group>) {
        cleanupStaleGroupDirs(wsDir, groups.map { "group_${it.id}" }.toSet())
        groups.forEach { syncGroupHardlinks(wsDir, it) }
    }

    private fun cleanupStaleGroupDirs(wsDir: Path, expectedDirs: Set<String>) {
        wsDir.toFile().listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("group_") }
            ?.filter { it.name !in expectedDirs }
            ?.forEach {
                it.deleteRecursively()
                logger.debug("Cleaned stale workspace dir: {}", it.name)
            }
    }

    private fun syncGroupHardlinks(wsDir: Path, group: Group) {
        val originalDir = Path.of(chatHistoryDir, "group_${group.id}")
        if (!Files.exists(originalDir)) return

        val groupDir = wsDir.resolve("group_${group.id}")
        groupDir.toFile().mkdirs()

        historyFileNames.forEach { syncHistoryHardlink(originalDir, groupDir, it) }
    }

    private fun syncHistoryHardlink(originalDir: Path, groupDir: Path, fileName: String) {
        val originalFile = originalDir.resolve(fileName)
        if (!Files.exists(originalFile)) return

        val linkFile = groupDir.resolve(fileName)
        if (!shouldRefreshHardlink(originalFile, linkFile)) return

        Files.deleteIfExists(linkFile)
        Files.createLink(linkFile, originalFile)
    }

    private fun shouldRefreshHardlink(originalFile: Path, linkFile: Path): Boolean {
        if (!Files.exists(linkFile)) return true
        val origAttr = Files.readAttributes(originalFile, BasicFileAttributes::class.java)
        val linkAttr = Files.readAttributes(linkFile, BasicFileAttributes::class.java)
        return origAttr.fileKey() != linkAttr.fileKey()
    }

    /**
     * 生成 index.md，包含各群组的摘要信息，供 agent 快速定位。
     */
    private fun writeIndex(wsDir: Path, groups: List<Group>) {
        val index = buildString {
            appendLine("# 历史会话索引")
            appendLine()
            appendLine("每个 group_xxx/ 目录包含一个群组的完整聊天记录。")
            appendLine("- chat_history.json: 聊天数据文件（JSON 格式）")
            appendLine("- session.json: 会话元信息（成员、创建时间等）")
            appendLine()

            for (group in groups) {
                val originalDir = Path.of(chatHistoryDir, "group_${group.id}")
                val sessionInfo = loadSessionData(originalDir)
                val chatInfo = loadChatHistory(originalDir)

                appendLine("## ${cleanGroupName(group.name)}")
                appendLine("- 目录: group_${group.id}/")
                appendLine("- 群组类型: ${inferGroupType(group.name)}")
                appendLine("- 创建时间: ${formatCreatedAt(group.createdAt)}")
                if (sessionInfo != null) {
                    val memberNames = sessionInfo.members
                        .filter { it.leftAt == null }
                        .joinToString(", ") { it.userName }
                    if (memberNames.isNotBlank()) {
                        appendLine("- 当前成员: $memberNames")
                    }
                }
                if (chatInfo != null) {
                    appendLine("- 消息数: ${chatInfo.messages.size}")
                    if (chatInfo.messages.isNotEmpty()) {
                        val lastTs = chatInfo.messages.last().timestamp
                        appendLine("- 最后对话时间: ${formatTimestamp(lastTs)}")
                    }
                    if (!chatInfo.rolePrompt.isNullOrBlank()) {
                        appendLine("- AI 角色: ${chatInfo.rolePrompt}")
                    }
                }
                appendLine()
            }
        }
        wsDir.resolve("index.md").toFile().writeText(index)
    }

    private fun loadSessionData(groupDir: Path): SessionData? {
        return loadJsonFile(groupDir, "session.json")
    }

    private fun loadChatHistory(groupDir: Path): ChatHistory? {
        return loadJsonFile(groupDir, "chat_history.json")
    }

    private inline fun <reified T> loadJsonFile(groupDir: Path, fileName: String): T? {
        val file = groupDir.resolve(fileName).toFile()
        if (!file.exists()) return null
        return try {
            json.decodeFromString<T>(file.readText())
        } catch (e: SerializationException) {
            logger.warn("Failed to parse {} in {}: {}", fileName, groupDir, e.message)
            null
        } catch (e: IOException) {
            logger.warn("Failed to read {} in {}: {}", fileName, groupDir, e.message)
            null
        }
    }

    /** 清理群组显示名：wf_ 开头的去掉尾部数字ID后缀 */
    private fun cleanGroupName(name: String): String {
        if (!name.startsWith("wf_")) return name
        // wf_https_1777345689635 -> wf_https
        return name.replace(Regex("_\\d{10,}$"), "")
    }

    private fun formatTimestamp(ts: Long): String {
        val instant = java.time.Instant.ofEpochMilli(ts)
        val zoned = instant.atZone(java.time.ZoneId.of("Asia/Shanghai"))
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(zoned) + " UTC+8"
    }

    /** 将 LocalDateTime.toString() 格式的创建时间统一为 "yyyy-MM-dd HH:mm:ss UTC+8" */
    private fun formatCreatedAt(createdAt: String): String {
        return try {
            val ldt = java.time.LocalDateTime.parse(createdAt.trim())
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(ldt) + " UTC+8"
        } catch (_: Exception) {
            createdAt
        }
    }

    private fun inferGroupType(name: String): String = when {
        name.startsWith("wf_") -> "工作流"
        name.startsWith("silk_") -> "Silk 私聊"
        else -> "群聊"
    }

    private companion object {
        val historyFileNames = listOf("chat_history.json", "session.json")
    }
}
