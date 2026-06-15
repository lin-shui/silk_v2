package com.silk.backend

import com.silk.backend.models.MessageReference
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import java.time.Duration
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import com.silk.backend.database.UnreadRepository
import com.silk.backend.database.GroupRepository
import com.silk.backend.todos.GroupTodoExtractionService
import com.silk.backend.ai.AIConfig
import com.silk.backend.search.WeaviateClient
import com.silk.backend.agents.core.AgentRuntime
import org.slf4j.LoggerFactory

@Serializable
data class Message(
    val id: String,
    val userId: String,
    val userName: String,
    val content: String,
    val timestamp: Long,
    val type: MessageType = MessageType.TEXT,
    val isTransient: Boolean = false,  // false = 普通消息（持久化），true = 临时消息（不持久化）
    val currentStep: Int? = null,      // 当前执行的步骤编号（用于进度条）
    val totalSteps: Int? = null,       // 总步骤数（用于进度条）
    val isIncremental: Boolean = false, // true = 增量消息（前端需拼接），false = 完整消息（前端直接替换）
    val category: MessageCategory = MessageCategory.NORMAL,  // ✅ 消息类别（用于UI显示亮度区分）
    val references: List<MessageReference> = emptyList(),
    val action: String? = null  // null = 新消息(默认), "edit" = 覆盖同ID消息
)

@Serializable
enum class MessageType {
    TEXT, JOIN, LEAVE, SYSTEM, FILE, RECALL, STOP_GENERATE, CARD, CARD_REPLY
}

@Serializable
enum class MessageCategory {
    NORMAL,           // 普通聊天消息（正常亮度）
    TODO_LIST,        // 待办事项列表（低亮度）
    STEP_PROCESS,     // 步骤执行过程（低亮度）
    FINAL_REPORT,     // 最终诊断报告（高亮度）
    AGENT_STATUS,     // Agent 工作状态（灰色，低亮度）
    AGENT_QUESTION,   // Agent 向用户提问（需要用户回答）
    AGENT_PERMISSION, // Agent 工具权限确认（需要用户允许/拒绝）
}

@Serializable
data class User(
    val id: String,
    val name: String
)

class ChatServer(
    private val sessionName: String = "default_room",
    private val isSilkChatWorkflow: Boolean = false
) {
    private val logger = LoggerFactory.getLogger(ChatServer::class.java)
    private val connections = ConcurrentHashMap<String, CopyOnWriteArrayList<WebSocketSession>>()
    private val messageHistory = Collections.synchronizedList(mutableListOf<Message>())
    private val historyManager = ChatHistoryManager()
    private val silkAgent = SilkAgent().apply {
        initializeAgent(sessionName)  // 初始化 Agent 并传递 session name
    }
    // 直接调用模型的 Agent（简化流程：让模型自动使用 tool 能力）
    private val directModelAgent = com.silk.backend.ai.DirectModelAgent(sessionId = sessionName)
    // 用户历史回忆 Agent（/recall 命令使用）
    private val userHistoryAgent = com.silk.backend.ai.UserHistoryAgent()
    private var messagesSinceAgentResponse = 0
    private var isAgentJoined = false

    @Volatile
    private var activeAiJob: Job? = null

    private inline fun <T> runChatCatching(block: () -> T): Result<T> =
        runCatching(block).onFailure { e ->
            if (e is CancellationException) throw e
        }

    // 已处理的URL缓存，避免重复下载（从持久化文件恢复）
    private val processedUrls = Collections.synchronizedSet(mutableSetOf<String>())
    private val processedUrlsFile: java.io.File

    init {
        // 初始化并从文件恢复已处理的URL列表（使用统一的目录命名逻辑）
        val uploadDir = historyManager.getUploadsDir(sessionName)
        uploadDir.mkdirs()
        processedUrlsFile = java.io.File(uploadDir, "processed_urls.txt")

        if (processedUrlsFile.exists()) {
            runChatCatching {
                processedUrlsFile.readLines().forEach { line ->
                    if (line.isNotBlank()) {
                        processedUrls.add(line.trim().lowercase())
                    }
                }
                logger.debug("📋 已从缓存恢复 {} 个已处理的URL", processedUrls.size)
            }.onFailure { e ->
                logger.warn("⚠️ 读取URL缓存失败: {}", e.message)
            }
        }

        // 从持久化存储加载历史消息到内存（用于消息撤回等功能）
        runChatCatching {
            val chatHistory = historyManager.loadChatHistory(sessionName)
            if (chatHistory != null && chatHistory.messages.isNotEmpty()) {
                chatHistory.messages.forEach { entry ->
                    val msg = Message(
                        id = entry.messageId,
                        userId = entry.senderId,
                        userName = entry.senderName,
                        content = entry.content,
                        timestamp = entry.timestamp,
                        type = parseStoredMessageType(entry.messageType),
                        references = entry.references
                    )
                    messageHistory.add(msg)
                }
                logger.debug("📜 已从持久化加载 {} 条历史消息到内存 (session: {})", messageHistory.size, sessionName)
            }
        }.onFailure { e ->
            logger.warn("⚠️ 加载历史消息到内存失败: {}", e.message)
        }
    }

    // 保存已处理的URL到文件
    private fun saveProcessedUrl(url: String) {
        runChatCatching {
            processedUrlsFile.appendText("$url\n")
        }.onFailure { e ->
            logger.warn("⚠️ 保存URL缓存失败: {}", e.message)
        }
    }

    suspend fun join(userId: String, userName: String, session: WebSocketSession) {
        // 权限校验：群聊仅允许群成员加入（否则会导致历史/工具上下文越权）
        if (sessionName.startsWith("group_") && !AgentRuntime.isAgentUserId(userId)) {
            val groupId = sessionName.removePrefix("group_")
            if (!GroupRepository.isUserInGroup(groupId, userId)) {
                session.close(
                    CloseReason(
                        CloseReason.Codes.VIOLATED_POLICY,
                        "Not authorized for this group"
                    )
                )
                return
            }
        }

        connections.getOrPut(userId) { CopyOnWriteArrayList() }.add(session)

        // 如果是第一个真实用户加入，让 Silk AI 也加入（静默模式）
        if (!isAgentJoined && !AgentRuntime.isAgentUserId(userId)) {
            joinSilkAgentSilently()
        }

        // 添加成员到会话记录
        historyManager.addMember(sessionName, userId, userName)

        // 从内存发送历史消息（init 已从磁盘加载，无需重复 I/O）
        val recentMessages = messageHistory.takeLast(50)
        if (recentMessages.isNotEmpty()) {
            val batch = Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(Message.serializer()),
                recentMessages
            )
            session.send(Frame.Text(batch))
            logger.debug("📜 批量发送 {} 条历史消息给 {}", recentMessages.size, userName)
        }

        // 短暂延迟确保批量历史帧先于 history_end 标记到达客户端
        // 避免客户端因帧乱序而错过历史消息
        kotlinx.coroutines.delay(100)

        // 历史加载完成标记，客户端据此一次性渲染消息列表
        session.send(Frame.Text(Json.encodeToString(
            Message(
                id = "history_end",
                userId = "system",
                userName = "system",
                content = "__history_end__",
                timestamp = System.currentTimeMillis(),
                type = MessageType.SYSTEM,
                isTransient = true
            )
        )))

        // 不发送加入消息到聊天室（避免产生无意义的历史记录）
        // 用户加入已经通过会话管理记录
        logger.debug("👤 用户已加入聊天室: {} ({})", userName, userId)
    }

    /**
     * 让 Silk AI Agent 静默加入会话（不发送欢迎消息）
     */
    private suspend fun joinSilkAgentSilently() {
        if (isAgentJoined) return

        isAgentJoined = true
        historyManager.addMember(sessionName, SilkAgent.AGENT_ID, SilkAgent.AGENT_NAME)

        // 不发送加入消息和欢迎消息（避免无意义的 chat 消息）
        // Silk 只在用户发送消息时才响应

        logger.info("🤖 Silk AI Agent 已静默加入会话")
    }

    suspend fun leave(userId: String, userName: String, session: WebSocketSession) {
        val sessions = connections[userId]
        if (sessions != null) {
            sessions.remove(session)
            if (sessions.isEmpty()) {
                connections.remove(userId)
            }
        }

        // 只有当该用户所有连接都断开后，才标记为离线
        if (connections[userId] == null) {
            historyManager.removeMember(sessionName, userId)
        }

        // 不发送离开消息到聊天室（避免产生无意义的历史记录）
        // 用户离开已经通过会话管理记录
        logger.debug("👋 用户已离开聊天室: {} ({})", userName, userId)
    }

    /**
     * 获取会话的参与者列表（优先从 SQL 群组成员获取，fallback 到 WebSocket 连接）
     */
    private fun getSessionParticipants(userId: String): List<String> {
        if (sessionName.startsWith("group_")) {
            val groupId = sessionName.removePrefix("group_")
            val members = runChatCatching {
                com.silk.backend.database.GroupRepository.getGroupMembers(groupId)
            }.onFailure { e ->
                logger.warn("⚠️ 获取群组成员失败，回退到连接列表: {}", e.message)
            }.getOrNull()
            if (!members.isNullOrEmpty()) {
                return members.map { it.userId }
            }
        }
        // 非群组或获取失败时，从 WebSocket 连接获取
        return connections.keys.toList().ifEmpty { listOf(userId) }
    }

    private fun isNonAgentTextMessage(message: Message): Boolean =
        message.type == MessageType.TEXT &&
            !message.isTransient &&
            !AgentRuntime.isAgentMessage(message)

    private fun shouldInterceptClaudeCodeBroadcast(
        message: Message,
        isSilkPrivateChat: Boolean,
    ): Boolean = !isSilkPrivateChat && isNonAgentTextMessage(message)

    private fun isSilkPrivateChatSession(): Boolean =
        getGroupDisplayName(sessionName)?.startsWith("[Silk]") == true

    private fun extractMentionFreeContent(content: String): String =
        content.removePrefix("@Silk").removePrefix("@silk").trim()

    private fun parseStoredMessageType(rawType: String): MessageType =
        MessageType.entries.firstOrNull { it.name == rawType } ?: MessageType.TEXT

    private fun shouldSkipDuplicateMessage(message: Message): Boolean {
        if (message.isTransient || message.action == "edit") {
            return false
        }
        return messageHistory.any { it.id == message.id }
    }

    private suspend fun handleCardReplyBroadcast(message: Message): Boolean {
        if (message.type != MessageType.CARD_REPLY) {
            return false
        }

        logger.info("🃏 [broadcast] 收到卡片回复: {} from {}", message.content.take(80), message.userName)
        if (!message.isTransient) {
            messageHistory.add(message)
            historyManager.addMessage(sessionName, message)
        }
        sendMessageToAllSessions(message)
        routeCardReplyMessage(message)
        return true
    }

    private suspend fun routeCardReplyMessage(message: Message) {
        runChatCatching {
            val reply = Json.decodeFromString<com.silk.backend.card.CardReplyPayload>(message.content)
            val broadcastRef: suspend (Message) -> Unit = { msg -> broadcast(msg) }
            val expired = com.silk.backend.card.CardReplyRouter.route(sessionName, reply, broadcastRef)
            if (expired) {
                broadcastExpiredCardReplyFeedback(reply)
            }
        }.onFailure { e ->
            logger.error("🃏 [broadcast] 卡片回复解析失败: {}", e.message)
        }
    }

    private suspend fun broadcastExpiredCardReplyFeedback(reply: com.silk.backend.card.CardReplyPayload) {
        broadcast(
            Message(
                id = java.util.UUID.randomUUID().toString(),
                userId = "system",
                userName = "系统",
                content = "该卡片已过期，无法处理此操作。",
                timestamp = System.currentTimeMillis(),
                type = MessageType.SYSTEM,
                isTransient = false,
            )
        )
        broadcast(
            Message(
                id = reply.cardId,
                userId = "system",
                userName = "系统",
                content = com.silk.backend.card.CardBuilder("已过期", template = "red")
                    .addText("该卡片已过期。")
                    .buildDisabled(),
                timestamp = System.currentTimeMillis(),
                type = MessageType.CARD,
                action = "edit",
            )
        )
    }

    private fun persistBroadcastMessageIfNeeded(message: Message) {
        if (message.isTransient) {
            return
        }

        if (message.action == "edit") {
            val idx = messageHistory.indexOfFirst { it.id == message.id }
            if (idx >= 0) {
                messageHistory[idx] = message
            }
            historyManager.editMessage(sessionName, message)
        } else {
            messageHistory.add(message)
            historyManager.addMessage(sessionName, message)
        }
        logger.debug("💾 [broadcast] 消息已保存: {}", message.id)

        val groupId = sessionName.removePrefix("group_")
        UnreadRepository.recordNewMessage(groupId, System.currentTimeMillis(), message.userId)
        launchWeaviateMessageIndex(message)
    }

    private fun launchWeaviateMessageIndex(message: Message) {
        CoroutineScope(Dispatchers.IO).launch {
            runChatCatching {
                val historyEntry = com.silk.backend.models.ChatHistoryEntry(
                    messageId = message.id,
                    senderId = message.userId,
                    senderName = message.userName,
                    content = message.content,
                    timestamp = message.timestamp,
                    messageType = message.type.name
                )
                val participants = getSessionParticipants(message.userId)
                val indexed = silkAgent.indexMessageToSearch(historyEntry, participants)
                if (indexed) {
                    logger.debug("🔍 [broadcast] 消息已索引到 Weaviate: {}", message.id)
                }
            }.onFailure { e ->
                logger.warn("⚠️ [broadcast] Weaviate 索引失败: {}", e.message)
            }
        }
    }

    private suspend fun sendFrameSafely(
        session: WebSocketSession,
        messageJson: String,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        val failure = runChatCatching {
            session.send(Frame.Text(messageJson))
        }.exceptionOrNull()
        if (failure != null) {
            onFailure(failure)
            return false
        }
        return true
    }

    private suspend fun broadcastMessageToAllSessions(message: Message) {
        val messageJson = Json.encodeToString(message)
        val sessions = allSessions()
        sessions.forEach { session ->
            sendFrameSafely(session, messageJson) { error ->
                logger.warn("📤 [broadcast] 消息发送失败: {}", error.message)
            }
        }
        logger.debug("📤 [broadcast] 消息已广播到 {} 个连接", sessions.size)
    }

    private fun logSessionSendFailure(scope: String, error: Throwable) {
        logger.warn("⚠️ [{}] 向会话发送消息失败", scope, error)
    }

    private fun maybeLaunchUrlProcessing(message: Message) {
        if (!isNonAgentTextMessage(message)) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            runChatCatching { processUrlsInMessage(message) }
                .onFailure { e -> logger.warn("⚠️ URL处理失败: {}", e.message) }
        }
    }

    private suspend fun handleClaudeCodeBroadcastInterception(
        message: Message,
        isSilkPrivateChat: Boolean,
    ): Boolean {
        if (!shouldInterceptClaudeCodeBroadcast(message, isSilkPrivateChat)) {
            return false
        }

        val ccUserId = message.userId
        val userSessions = connections[ccUserId]
        if (userSessions.isNullOrEmpty()) {
            return false
        }

        return AgentRuntime.handleIfActive(
            userId = message.userId,
            groupId = sessionName,
            text = extractMentionFreeContent(message.content),
            userName = message.userName,
            broadcastFn = createCcBroadcastFn(ccUserId),
        )
    }

    private fun createCcBroadcastFn(ccUserId: String): suspend (Message) -> Unit = { msg ->
        runChatCatching {
            if (!msg.isTransient) {
                messageHistory.add(msg)
                historyManager.addMessage(sessionName, msg)
            }
            val msgJson = Json.encodeToString(msg)
            val currentSessions = connections[ccUserId] ?: emptyList()
            currentSessions.forEach { session ->
                sendFrameSafely(session, msgJson) { error ->
                    logSessionSendFailure("CC", error)
                }
            }
        }.onFailure { e ->
            logger.warn("[CC] 发送消息失败", e)
        }
    }

    private suspend fun handleSilkAiBroadcast(message: Message, isSilkPrivateChat: Boolean) {
        if (!isNonAgentTextMessage(message)) {
            return
        }

        messagesSinceAgentResponse++
        if (!shouldTriggerSilkAi(message.content, isSilkPrivateChat)) {
            logger.debug("📝 [broadcast] 普通消息已索引，不触发 AI 回复: {}...", message.content.take(30))
            return
        }

        val silkContent = extractSilkContent(message.content, isSilkPrivateChat)
        when {
            !isSilkPrivateChat && silkContent.isBlank() -> launchAgentStatusHelp()
            silkContent == "重置角色" || silkContent.lowercase() == "reset" -> resetSilkRolePrompt()
            isRolePromptMessage(silkContent) -> handleSilkRolePrompt(message.userId, silkContent)
            silkContent.startsWith("/recall ") || silkContent.startsWith("/recall\n") ->
                handleRecallCommand(message.userId, silkContent, isSilkPrivateChat)
            else -> handleSilkQuestion(message.userId, silkContent, isSilkPrivateChat)
        }
    }

    private fun shouldTriggerSilkAi(content: String, isSilkPrivateChat: Boolean): Boolean =
        isSilkPrivateChat ||
            isSilkChatWorkflow ||
            content.startsWith("@Silk") ||
            content.startsWith("@silk")

    private fun extractSilkContent(content: String, isSilkPrivateChat: Boolean): String {
        if (isSilkPrivateChat || isSilkChatWorkflow) {
            return content
        }
        return extractMentionFreeContent(content)
    }

    private fun launchAgentStatusHelp() {
        logger.debug("📖 [broadcast] @silk 帮助提示")
        CoroutineScope(Dispatchers.IO).launch {
            sendAgentStatus(
                """
                🎯 Silk 使用帮助：
                • @silk [问题] - 向 Silk 提问
                • @silk 你是... - 设置 Silk 角色
                • @silk 重置角色 - 恢复默认角色
                """.trimIndent()
            )
        }
    }

    private fun resetSilkRolePrompt() {
        historyManager.updateRolePrompt(sessionName, null)
        logger.debug("🎭 [broadcast] 角色已重置")
        CoroutineScope(Dispatchers.IO).launch {
            sendAgentStatus("🎭 角色已重置为默认")
        }
    }

    private fun handleSilkRolePrompt(userId: String, silkContent: String) {
        historyManager.updateRolePrompt(sessionName, silkContent)
        logger.debug("🎭 [broadcast] 角色已设置: {}", silkContent)
        launchActiveAiJob(cancelLog = "🛑 角色确认生成已被取消") {
            sendAgentStatus("🎭 角色已设置")
            generateIntelligentResponse("请简短地自我介绍（1-2句话）", userId)
        }
    }

    private suspend fun handleRecallCommand(
        userId: String,
        silkContent: String,
        isSilkPrivateChat: Boolean,
    ) {
        if (!isSilkPrivateChat) {
            sendMessageToAllSessions(buildSilkTextMessage("/recall 仅可在 Silk 专属对话中使用，请切换到专属对话后再试"))
            return
        }

        val recallQuery = silkContent.removePrefix("/recall").trim()
        logger.info("[/recall] userId={}, query={}...", userId, recallQuery.take(50))
        launchActiveAiJob(
            cancelLog = "[/recall] 已被用户取消",
            onError = { e ->
                logger.error("[/recall] 异常", e)
                sendAgentStatus("历史查询失败: ${e.message}")
            }
        ) {
            sendMessageToAllSessions(buildSilkTextMessage("正在搜索历史会话...", isTransient = true))
            generateHistoryRecallResponse(recallQuery, userId)
        }
    }

    private fun handleSilkQuestion(userId: String, silkContent: String, isSilkPrivateChat: Boolean) {
        val logPrefix = if (isSilkPrivateChat) "[Silk私聊]" else "[@silk]"
        logger.debug("💬 [broadcast] {} 问题: {}...", logPrefix, silkContent.take(50))
        launchActiveAiJob(
            cancelLog = "🛑 AI 生成已被用户取消",
            onError = { e ->
                logger.error("❌ 生成AI回答异常", e)
            }
        ) {
            generateIntelligentResponse(silkContent, userId)
        }
    }

    private fun launchActiveAiJob(
        cancelLog: String,
        onError: suspend (Throwable) -> Unit = {},
        block: suspend () -> Unit,
    ) {
        activeAiJob?.cancel()
        activeAiJob = CoroutineScope(Dispatchers.IO).launch {
            val failure = runCatching {
                block()
            }.exceptionOrNull()
            try {
                if (failure is CancellationException) {
                    logger.info(cancelLog)
                    throw failure
                }
                if (failure != null) {
                    onError(failure)
                }
            } finally {
                activeAiJob = null
            }
        }
    }

    suspend fun broadcast(message: Message) {
        if (message.type == MessageType.STOP_GENERATE) {
            handleStopGeneration(message.userId)
            return
        }

        if (handleCardReplyBroadcast(message)) {
            return
        }

        logger.debug("📨 [broadcast] 收到消息: ID={}, User={}, IsTransient={}, Content={}...", message.id, message.userName, message.isTransient, message.content.take(30))
        if (shouldSkipDuplicateMessage(message)) {
            logger.warn("⚠️ [broadcast] 忽略重复消息: {} from {}", message.id, message.userName)
            return
        }

        persistBroadcastMessageIfNeeded(message)
        broadcastMessageToAllSessions(message)

        val isSilkPrivateChat = isSilkPrivateChatSession()
        maybeLaunchUrlProcessing(message)
        if (handleClaudeCodeBroadcastInterception(message, isSilkPrivateChat)) {
            return
        }
        handleSilkAiBroadcast(message, isSilkPrivateChat)
    }

    /**
     * 处理消息中的URL - 下载网页并索引
     */
    private suspend fun processUrlsInMessage(message: Message) {
        logger.debug("🔗 [URL检测] 开始检测消息: {}...", message.content.take(50))
        val urls = com.silk.backend.utils.WebPageDownloader.extractUrls(message.content)
        logger.debug("🔗 [URL检测] 提取到 {} 个URL: {}", urls.size, urls)

        val newUrls = filterNewUrls(urls)
        if (newUrls.isEmpty()) {
            return
        }

        logger.debug("🔗 检测到 {} 个URL，其中 {} 个是新的: {}", urls.size, newUrls.size, newUrls)
        val uploadDir = historyManager.getUploadsDir(sessionName)
        newUrls.forEach { url ->
            processSingleUrl(message, url, uploadDir)
        }
        clearUrlStatusAfterDelay()
    }

    private fun filterNewUrls(urls: List<String>): List<String> {
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

    private fun hasProcessedUrl(url: String): Boolean =
        processedUrls.contains(normalizeProcessedUrl(url))

    private fun normalizeProcessedUrl(url: String): String =
        url.lowercase().trimEnd('/')

    private suspend fun processSingleUrl(
        message: Message,
        url: String,
        uploadDir: java.io.File,
    ) {
        runChatCatching {
            broadcastSystemStatus("🌐 正在下载: $url")
            val content = com.silk.backend.utils.WebPageDownloader.downloadAndExtract(url)
            if (content == null) {
                broadcastSystemStatus("⚠️ 无法下载: $url")
                return
            }
            handleDownloadedUrlContent(message, url, uploadDir, content)
        }.onFailure { e ->
            logger.error("❌ 处理URL失败: {}", url, e)
            broadcastSystemStatus("❌ 处理链接失败: $url")
        }
    }

    private suspend fun handleDownloadedUrlContent(
        message: Message,
        url: String,
        uploadDir: java.io.File,
        content: com.silk.backend.utils.WebPageContent,
    ) {
        val normalizedUrl = normalizeProcessedUrl(url)
        processedUrls.add(normalizedUrl)
        saveProcessedUrl(normalizedUrl)

        val savedFile = com.silk.backend.utils.WebPageDownloader.saveToFile(content, uploadDir)
        val fileType = if (content.isPdf) "PDF" else "网页"
        val downloadSessionId = sessionName.removePrefix("group_")
        broadcastSystemStatus("📄 已下载$fileType: ${content.title}")
        broadcast(
            Message(
                id = generateId(),
                userId = message.userId,
                userName = message.userName,
                content = buildFileMessageContent(
                    fileName = savedFile.name,
                    fileSize = savedFile.length(),
                    downloadUrl = buildFileDownloadUrl(downloadSessionId, savedFile.name)
                ),
                timestamp = System.currentTimeMillis(),
                type = MessageType.FILE
            )
        )
        indexDownloadedUrlContent(message, content, fileType)
    }

    private suspend fun indexDownloadedUrlContent(
        message: Message,
        content: com.silk.backend.utils.WebPageContent,
        fileType: String,
    ) {
        val participants = getSessionParticipants(message.userId)
        val webPageEntry = com.silk.backend.models.ChatHistoryEntry(
            messageId = "webpage_${System.currentTimeMillis()}",
            senderId = message.userId,
            senderName = "[$fileType] ${content.title}",
            content = buildDownloadedContentIndexBody(content, fileType),
            timestamp = System.currentTimeMillis(),
            messageType = if (content.isPdf) "PDF" else "WEBPAGE"
        )

        val indexed = silkAgent.indexMessageToSearch(webPageEntry, participants)
        if (indexed) {
            logger.debug("🔍 内容已索引: {}", content.title)
            broadcastSystemStatus("✅ 已索引$fileType: ${content.title}")
        }
    }

    private fun buildDownloadedContentIndexBody(
        content: com.silk.backend.utils.WebPageContent,
        fileType: String,
    ): String = """
        来源URL: ${content.url}
        标题: ${content.title}
        类型: $fileType

        ${content.textContent.take(10000)}
    """.trimIndent()

    private suspend fun clearUrlStatusAfterDelay() {
        kotlinx.coroutines.delay(3000)
        broadcastSystemStatus("CLEAR_STATUS")
    }

    /**
     * 判断是否是角色设置消息
     * 角色设置关键词：你是、扮演、假设你是、作为、角色是、请以...身份
     */
    private fun isRolePromptMessage(content: String): Boolean {
        val roleKeywords = listOf(
            "你是", "你现在是", "扮演", "假设你是", "作为", "角色是",
            "请以", "假装你是", "模拟", "充当", "担任",
            "you are", "act as", "pretend to be", "role:", "persona:"
        )
        val lowerContent = content.lowercase()
        return roleKeywords.any { keyword ->
            lowerContent.startsWith(keyword.lowercase()) ||
            lowerContent.contains("角色") ||
            lowerContent.contains("身份")
        }
    }

    /**
     * 发送 Agent 状态消息（灰色显示）- 内部使用
     */
    private suspend fun sendAgentStatus(status: String) {
        broadcastSystemStatus(status)
    }

    /**
     * 广播系统状态消息（灰色显示）- 公开方法，供其他模块调用
     */
    suspend fun broadcastSystemStatus(status: String) {
        logger.debug("📢 [状态广播] {} (连接数: {})", status, allSessions().size)

        val statusMessage = Message(
            id = generateId(),
            userId = SilkAgent.AGENT_ID,
            userName = "🔄 系统",
            content = status,
            timestamp = System.currentTimeMillis(),
            type = MessageType.SYSTEM,
            isTransient = true,
            category = MessageCategory.AGENT_STATUS
        )

        val messageJson = Json.encodeToString(statusMessage)
        allSessions().forEach { session ->
            val sent = sendFrameSafely(session, messageJson) { error ->
                logSessionSendFailure("status", error)
            }
            if (sent) {
                logger.info("   ✅ 状态已发送到一个连接")
            }
        }
    }

    /**
     * 处理停止生成请求：取消活跃 AI 任务并清理客户端状态。
     * 同时支持 Silk 普通会话（取消协程）和 Claude Code 模式（委托 Bridge 取消）。
     */
    private suspend fun handleStopGeneration(userId: String) {
        logger.info("🛑 收到停止生成请求 (userId={})", userId)

        // 1. Agent 模式：委托 AgentRuntime 取消
        val groupId = sessionName
        val userSessions = connections[userId]
        if (userSessions != null && userSessions.isNotEmpty()) {
            val ccBroadcastFn: suspend (Message) -> Unit = { msg ->
                if (!msg.isTransient) {
                    messageHistory.add(msg)
                    historyManager.addMessage(sessionName, msg)
                }
                val msgJson = Json.encodeToString(msg)
                val currentSessions = connections[userId] ?: emptyList()
                currentSessions.forEach { session ->
                    sendFrameSafely(session, msgJson) { error ->
                        logSessionSendFailure("stop-generate", error)
                    }
                }
            }
            val ccCancelled = AgentRuntime.cancelIfActive(userId, groupId, ccBroadcastFn)
            if (ccCancelled) {
                logger.info("🛑 已通过 AgentRuntime 取消 Agent 任务")
                broadcastSystemStatus("CLEAR_STATUS")
                return
            }
        }

        // 2. Silk 普通会话：取消协程
        val job = activeAiJob
        if (job != null && job.isActive) {
            job.cancel()
            activeAiJob = null
            logger.info("🛑 已取消活跃的 AI 任务")
        }
        broadcastSystemStatus("CLEAR_STATUS")
    }

    /**
     * Claude PTY（pty_chat）在 thinking 结束时会插入 `<!--THINKING_END-->`；若模型未产出任何正文 text_delta，
     * 标记之后为空串，Web/Harmony 的 Markdown 可见区域会变成空白。此处补齐占位说明。
     */
    private fun ensureSilkReplyVisible(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return "抱歉，本次未生成有效回复，请稍后再试。"
        }
        val marker = "<!--THINKING_END-->"
        val idx = trimmed.indexOf(marker)
        if (idx < 0) return trimmed
        val after = trimmed.substring(idx + marker.length).trim()
        if (after.isNotEmpty()) return trimmed
        return trimmed + "\n\n*（模型未输出正文，请重试。）*"
    }

    private suspend fun sendMessageToAllSessions(message: Message) {
        val messageJson = Json.encodeToString(message)
        allSessions().forEach { session ->
            sendFrameSafely(session, messageJson) { error ->
                logSessionSendFailure("broadcast-all", error)
            }
        }
    }

    private fun buildSilkTextMessage(
        content: String,
        isTransient: Boolean = false,
        isIncremental: Boolean = false,
    ): Message {
        return Message(
            id = generateId(),
            userId = SilkAgent.AGENT_ID,
            userName = SilkAgent.AGENT_NAME,
            content = content,
            timestamp = System.currentTimeMillis(),
            type = MessageType.TEXT,
            isTransient = isTransient,
            isIncremental = isIncremental
        )
    }

    private data class IntelligentResponseState(
        var fullResponse: String = "",
        var agentReferences: List<MessageReference> = emptyList(),
    )

    private fun buildDirectModelSystemPrompt(rolePrompt: String?): String = buildString {
        if (rolePrompt != null) {
            appendLine("你的角色设定：$rolePrompt")
            appendLine()
            appendLine("请以上述角色身份回答问题。")
        } else {
            appendLine("你是 Silk，一个智能助手。")
        }
        appendLine()
        appendLine("你可以使用互联网搜索工具来查找最新信息。")
        appendLine("对于天气、新闻、实时数据等时效性信息，你必须使用互联网搜索获取最新结果，不能仅凭训练数据回答。")
        appendLine()
        appendLine("【HarmonyOS 元服务能力】")
        appendLine("你在 HarmonyOS 系统上运行，支持调用系统元服务（免安装应用）：")
        appendLine("- **出行/打车类请求**（如\"打车\"、\"叫车\"、\"去机场\"）→ 系统会自动在回复顶部显示 T3出行 快捷按钮和输入框，用户填写出发地/目的地后可直接跳转。")
        appendLine("- **购物类请求**（如\"买手机\"、\"京东购物\"）→ 系统会自动在回复顶部显示对应的购物应用快捷按钮。")
        appendLine("你无需在回复中模拟打开应用，只需正常回答用户问题。如果用户询问能否打车/买东西，确认可以并引导用户使用上方提供的按钮。")
    }

    private fun prepareDirectModelAgentContext() {
        val chatHistory = historyManager.loadChatHistory(sessionName)
        val historyMessages = chatHistory?.messages ?: emptyList()
        directModelAgent.setGroupChatHistory(historyMessages)
        directModelAgent.loadRecentHistory(historyMessages, SilkAgent.AGENT_ID)

        if (!sessionName.startsWith("group_")) {
            return
        }

        val groupId = sessionName.removePrefix("group_")
        val memberList = GroupRepository.getGroupMembers(groupId).map { member ->
            member.userId to member.userName
        }
        directModelAgent.setGroupMembersList(memberList)
    }

    private fun initializeDirectModelWorkspace() {
        val workspaceDir = "${AIConfig.CLAUDE_CLI_WORKSPACE_ROOT}/$sessionName"
        java.io.File(workspaceDir).mkdirs()
        directModelAgent.initClaudeClient(workspaceDir)
    }

    private suspend fun handleDirectModelStep(
        callId: Long,
        stepType: String,
        content: String,
        state: IntelligentResponseState,
    ) {
        when (stepType) {
            "thinking", "tool" -> sendAgentStatus(content)
            "streaming_incremental" -> sendStreamingIncrementalMessage(callId, content)
            "complete" -> {
                state.fullResponse = ensureSilkReplyVisible(content)
                state.agentReferences = directModelAgent.lastAgentResponse?.references ?: emptyList()
            }
            "error" -> sendAgentStatus("❌ $content")
        }
    }

    private suspend fun sendStreamingIncrementalMessage(callId: Long, content: String) {
        val incrementalMessage = buildSilkTextMessage(
            content = content,
            isTransient = true,
            isIncremental = true,
        ).copy(id = "streaming_${System.currentTimeMillis()}")
        val messageJson = Json.encodeToString(incrementalMessage)
        val sessions = allSessions()
        logger.info("📤 [流式-{}] 增量 {}字符 -> {}个连接", callId, content.length, sessions.size)
        sessions.forEach { session ->
            sendFrameSafely(session, messageJson) { error ->
                logger.error("📤 [流式-{}] 发送失败", callId, error)
            }
        }
    }

    private suspend fun maybeSendFinalAgentMessage(
        callId: Long,
        userId: String,
        stepType: String,
        isComplete: Boolean,
        state: IntelligentResponseState,
    ) {
        if (stepType != "complete" || !isComplete) {
            return
        }

        sendFinalAgentMessage(callId, userId, state)
    }

    private suspend fun sendFinalAgentMessage(
        callId: Long,
        userId: String,
        state: IntelligentResponseState,
    ) {
        logger.debug("📤 [智能回答-{}] 准备发送最终消息，内容长度: {}", callId, state.fullResponse.length)

        val messageId = generateId()
        logger.debug("📤 [智能回答-{}] 生成消息ID: {} (响应userId={})", callId, messageId, userId)

        if (messageHistory.any { it.id == messageId }) {
            logger.warn("⚠️ [智能回答-{}] 消息ID已存在，跳过发送: {}", callId, messageId)
            return
        }

        val finalMessage = Message(
            id = messageId,
            userId = SilkAgent.AGENT_ID,
            userName = SilkAgent.AGENT_NAME,
            content = state.fullResponse,
            timestamp = System.currentTimeMillis(),
            type = MessageType.TEXT,
            isTransient = false,
            isIncremental = false,
            references = state.agentReferences,
        )

        messageHistory.add(finalMessage)
        historyManager.addMessage(sessionName, finalMessage)
        logger.debug("📤 [智能回答-{}] 已保存到历史，当前历史大小: {}", callId, messageHistory.size)

        val messageJson = Json.encodeToString(finalMessage)
        logger.debug("📤 [智能回答-{}] 发送最终消息到 {} 个连接", callId, allSessions().size)
        allSessions().forEach { session ->
            val sent = sendFrameSafely(session, messageJson) { error ->
                logger.warn("📤 [智能回答-{}] 最终消息发送失败: {}", callId, error.message)
            }
            if (sent) {
                logger.info("   ✅ [智能回答-{}] 已发送到一个连接", callId)
            }
        }
        logger.debug("📤 [智能回答-{}] 最终消息发送完成 (messageId={})", callId, messageId)
    }

    private fun syncAgentResponseState(state: IntelligentResponseState, fallbackResponse: String) {
        val agentResponse = directModelAgent.lastAgentResponse
        state.fullResponse = ensureSilkReplyVisible(agentResponse?.content ?: fallbackResponse)
        state.agentReferences = agentResponse?.references ?: emptyList()
    }

    private fun refreshSilkPrivateChatTodosIfNeeded(userId: String) {
        if (userId.isBlank() || getGroupDisplayName(sessionName)?.startsWith("[Silk]") != true) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            runChatCatching { GroupTodoExtractionService.refreshTodosForUser(userId) }
                .onFailure { e -> logger.warn("⚠️ 专属对话待办提取失败", e) }
        }
    }

    /**
     * 生成智能回答 - 简化流程
     *
     * 直接调用模型，让模型使用其内置的 tool 能力（搜索文件、浏览器等）
     * 不再执行复杂的三层搜索流程
     */
    private suspend fun generateIntelligentResponse(userMessage: String, userId: String = "") {
        val callId = System.currentTimeMillis()
        logger.info("🤖 [Agent-{}] 开始直接调用模型 (userId={})", callId, userId)
        sendAgentStatus("🤖 正在处理您的问题...")
        val systemPrompt = buildDirectModelSystemPrompt(historyManager.getRolePrompt(sessionName))
        prepareDirectModelAgentContext()
        initializeDirectModelWorkspace()

        val state = IntelligentResponseState()
        val responseResult = runCatching {
            directModelAgent.processInput(
                userInput = userMessage,
                systemPrompt = systemPrompt
            ) { stepType, content, isComplete ->
                handleDirectModelStep(callId, stepType, content, state)
                maybeSendFinalAgentMessage(callId, userId, stepType, isComplete, state)
            }
        }
        val responseFailure = responseResult.exceptionOrNull()
        if (responseFailure != null) {
            if (responseFailure is CancellationException) {
                logger.info("🛑 [generateIntelligentResponse-{}] 生成被取消", callId)
                throw responseFailure
            }
            logger.error("❌ [generateIntelligentResponse-{}] 生成AI回答失败", callId, responseFailure)
            sendMessageToAllSessions(buildSilkTextMessage("抱歉，处理您的问题时发生了错误: ${responseFailure.message}"))
            return
        }

        syncAgentResponseState(state, responseResult.getOrThrow())
        logger.debug(
            "🏁 [generateIntelligentResponse-{}] 函数执行完成，响应长度: {}, 引用数: {}",
            callId,
            state.fullResponse.length,
            state.agentReferences.size
        )
        refreshSilkPrivateChatTodosIfNeeded(userId)
    }

    /**
     * 生成历史回忆回复 - 通过 UserHistoryAgent 在用户 workspace 中搜索历史会话
     */
    private suspend fun generateHistoryRecallResponse(query: String, userId: String) {
        val callId = System.currentTimeMillis()
        logger.info("[/recall-{}] userId={}, query={}...", callId, userId, query.take(50))

        val recallResult = runCatching {
            userHistoryAgent.queryWithHistory(
                userId = userId,
                userMessage = query,
            ) { _, content, isComplete ->
                sendHistoryRecallDelta(content, isComplete)
            }
        }
        val recallFailure = recallResult.exceptionOrNull()
        if (recallFailure != null) {
            if (recallFailure is CancellationException) {
                logger.info("[/recall-{}] 已取消", callId)
                throw recallFailure
            }
            logger.error("[/recall-{}] 失败", callId, recallFailure)
            sendMessageToAllSessions(buildSilkTextMessage("历史查询失败: ${recallFailure.message}"))
            return
        }
        val fullResponse = recallResult.getOrThrow()

        sendFinalHistoryRecallResponse(fullResponse)
        logger.info("[/recall-{}] 完成, responseLen={}", callId, fullResponse.length)
    }

    private suspend fun sendHistoryRecallDelta(content: String, isComplete: Boolean) {
        val message = buildSilkTextMessage(
            content = content,
            isTransient = !isComplete,
            isIncremental = true,
        )
        sendMessageToAllSessions(message)
    }

    private suspend fun sendFinalHistoryRecallResponse(fullResponse: String) {
        if (fullResponse.isBlank()) return
        val finalMsg = buildSilkTextMessage(fullResponse)
        messageHistory.add(finalMsg)
        sendMessageToAllSessions(finalMsg)
        historyManager.addMessage(sessionName, finalMsg)
    }

    fun getOnlineUsers(): List<User> {
        return connections.keys.map { userId ->
            User(id = userId, name = "User_$userId")
        }
    }

    /** 获取所有活跃的 WebSocket 会话（展平多连接） */
    private fun allSessions(): List<WebSocketSession> =
        connections.values.flatMap { it }

    /**
     * 执行医生诊断更新（Host的消息）
     */
    private suspend fun executeDoctorDiagnosisUpdate(doctorMessage: String) {
        // 定义回调函数，将AI的响应发送到聊天室
        val callback: suspend (String, String, Int?, Int?) -> Unit = { stepType, content, currentStep, totalSteps ->
            val agentMessage = Message(
                id = generateId(),
                userId = SilkAgent.AGENT_ID,
                userName = SilkAgent.AGENT_NAME,
                content = content,
                timestamp = System.currentTimeMillis(),
                type = MessageType.TEXT,
                isTransient = stepType == "processing",  // 处理中的消息是临时的
                currentStep = currentStep,
                totalSteps = totalSteps
            )

            // 非临时消息保存到历史
            if (!agentMessage.isTransient) {
                messageHistory.add(agentMessage)
                historyManager.addMessage(sessionName, agentMessage)
            }

            // 发送给所有客户端
            val messageJson = Json.encodeToString(agentMessage)
            allSessions().forEach { session ->
                sendFrameSafely(session, messageJson) { error ->
                    logger.warn("⚠️ [doctor-diagnosis] 发送更新失败", error)
                }
            }

            kotlinx.coroutines.delay(300)
        }

        // 获取群组显示名称
        val groupDisplayName = getGroupDisplayName(sessionName)

        // 加载聊天历史
        val chatHistory = historyManager.loadChatHistory(sessionName)
        val historyEntries = chatHistory?.messages ?: emptyList()

        // 提取用户名
        val userName = historyEntries
            .filter { !AgentRuntime.isAgentUserId(it.senderId) }
            .lastOrNull()?.senderName ?: "用户"

        // 执行医生诊断更新
        runChatCatching {
            silkAgent.executeDoctorDiagnosisUpdate(
                chatHistory = historyEntries,
                doctorMessage = doctorMessage,
                callback = callback,
                userName = userName,
                groupDisplayName = groupDisplayName
            )
        }.onFailure { e ->
            logger.error("❌ 医生诊断更新失败", e)
        }
    }

    /**
     * 获取群组的Host用户ID
     */
    private fun getGroupHostId(sessionName: String): String? {
        return if (sessionName.startsWith("group_")) {
            val groupId = sessionName.removePrefix("group_")
            runChatCatching {
                com.silk.backend.database.GroupRepository.findGroupById(groupId)?.hostId
            }.onFailure { e ->
                logger.warn("⚠️ 获取Host ID失败: {}", e.message)
            }.getOrNull()
        } else {
            null
        }
    }

    /**
     * 获取群组的显示名称
     * 从sessionName（格式：group_<uuid>）获取实际的群组名称
     */
    private fun getGroupDisplayName(sessionName: String): String? {
        return if (sessionName.startsWith("group_")) {
            // 提取群组ID
            val groupId = sessionName.removePrefix("group_")
            logger.debug("📋 正在查询群组名称，groupId: {}", groupId)

            // 从数据库查询群组名称
            runChatCatching {
                com.silk.backend.database.GroupRepository.findGroupById(groupId)
            }.fold(
                onSuccess = { group ->
                    if (group != null) {
                        logger.debug("📋 找到群组名称: {}", group.name)
                        group.name  // 返回群组的实际名称，例如："liaoheng's Sophie Ankle"
                    } else {
                        logger.warn("⚠️ 未找到群组：{}", groupId)
                        null
                    }
                },
                onFailure = { e ->
                logger.warn("⚠️ 查询群组名称失败: {}", e.message)
                logger.debug("⚠️ 查询群组名称异常详情", e)
                null
                },
            )
        } else {
            // 不是群组session，返回null（使用sessionName作为标题）
            logger.debug("📋 非群组session，使用sessionName: {}", sessionName)
            null
        }
    }

    /**
     * 撤回消息
     * @param messageId 要撤回的消息ID
     * @param userId 发起撤回的用户ID
     * @return 撤回结果：成功/失败，以及被删除的消息ID列表
     */
    suspend fun recallMessage(messageId: String, userId: String): RecallResult {
        logger.debug("🔄 [recallMessage] 开始撤回消息: {} by user {}", messageId, userId)
        logger.debug("🔄 [recallMessage] sessionName: {}", sessionName)

        // 1. 从历史记录中查找消息
        val chatHistory = historyManager.loadChatHistory(sessionName)
        logger.debug("🔄 [recallMessage] chatHistory: {}, messages count: {}", chatHistory != null, chatHistory?.messages?.size)
        if (chatHistory != null) {
            logger.debug("🔄 [recallMessage] message IDs in history: {}", chatHistory.messages.map { it.messageId })
        }
        val messageEntry = chatHistory?.messages?.find { it.messageId == messageId }

        if (messageEntry == null) {
            logger.error("❌ [recallMessage] 消息不存在: {}", messageId)
            return RecallResult(false, "消息不存在", emptyList())
        }

        // 2. 验证权限：只有消息发送者才能撤回
        if (messageEntry.senderId != userId) {
            logger.error("❌ [recallMessage] 无权撤回此消息: sender={}, requester={}", messageEntry.senderId, userId)
            return RecallResult(false, "只能撤回自己发送的消息", emptyList())
        }

        val deletedMessageIds = mutableListOf<String>()

        // 3. 查找用户消息之后最近的 Silk 回复，级联删除
        logger.debug("🔄 [recallMessage] 查找 Silk 的回复")
        val messageIndex = chatHistory.messages.indexOf(messageEntry)
        val silkReply = chatHistory.messages
            .drop(messageIndex + 1)
            .firstOrNull { AgentRuntime.isAgentUserId(it.senderId) }

        if (silkReply != null) {
            logger.debug("🔄 [recallMessage] 找到 Silk 回复: {}", silkReply.messageId)
            historyManager.deleteMessages(sessionName, listOf(messageId, silkReply.messageId))
            deletedMessageIds.add(messageId)
            deletedMessageIds.add(silkReply.messageId)
            messageHistory.removeIf { it.id == messageId || it.id == silkReply.messageId }
            broadcastRecallNotification(listOf(messageId, silkReply.messageId))
            // 同步删除 Weaviate 向量索引
            CoroutineScope(Dispatchers.IO).launch {
                runChatCatching {
                    WeaviateClient.getInstance().deleteChatMessages(sessionName, listOf(messageId, silkReply.messageId))
                }.onFailure { e ->
                    logger.warn("⚠️ [recallMessage] Weaviate 删除向量失败", e)
                }
            }
            logger.info("✅ [recallMessage] 已撤回用户消息和 Silk 回复")
        } else {
            logger.warn("⚠️ [recallMessage] 未找到 Silk 回复，只撤回用户消息")
            historyManager.deleteMessages(sessionName, listOf(messageId))
            deletedMessageIds.add(messageId)
            messageHistory.removeIf { it.id == messageId }
            broadcastRecallNotification(listOf(messageId))
            // 同步删除 Weaviate 向量索引
            CoroutineScope(Dispatchers.IO).launch {
                runChatCatching {
                    WeaviateClient.getInstance().deleteChatMessages(sessionName, listOf(messageId))
                }.onFailure { e ->
                    logger.warn("⚠️ [recallMessage] Weaviate 删除向量失败", e)
                }
            }
        }

        return RecallResult(true, "撤回成功", deletedMessageIds)
    }

    /**
     * 删除消息
     * 权限：1) 自己的消息可删  2) Silk回复自己@silk触发的消息可删  3) 群主可删任意消息
     */
    suspend fun deleteMessage(messageId: String, userId: String): RecallResult {
        logger.debug("🗑️ [deleteMessage] 删除消息: {} by user {}", messageId, userId)

        val chatHistory = historyManager.loadChatHistory(sessionName)
        val messageEntry = chatHistory?.messages?.find { it.messageId == messageId }

        if (messageEntry == null) {
            return RecallResult(false, "消息不存在", emptyList())
        }

        val isOwnMessage = messageEntry.senderId == userId
        val hostId = getGroupHostId(sessionName)
        val isGroupHost = hostId == userId

        val isSilkReplyToMe = if (AgentRuntime.isAgentUserId(messageEntry.senderId)) {
            val msgIndex = chatHistory.messages.indexOf(messageEntry)
            val precedingMsg = chatHistory.messages
                .take(msgIndex)
                .lastOrNull { !AgentRuntime.isAgentUserId(it.senderId) }
            precedingMsg?.senderId == userId &&
                (precedingMsg.content.startsWith("@Silk") || precedingMsg.content.startsWith("@silk"))
        } else false

        if (!isOwnMessage && !isGroupHost && !isSilkReplyToMe) {
            return RecallResult(false, "无权删除此消息", emptyList())
        }

        historyManager.deleteMessages(sessionName, listOf(messageId))
        messageHistory.removeIf { it.id == messageId }
        broadcastRecallNotification(listOf(messageId))
        // 同步删除 Weaviate 向量索引
        CoroutineScope(Dispatchers.IO).launch {
            runChatCatching {
                WeaviateClient.getInstance().deleteChatMessages(sessionName, listOf(messageId))
            }.onFailure { e ->
                logger.warn("⚠️ [deleteMessage] Weaviate 删除向量失败", e)
            }
        }

        logger.info("🗑️ [deleteMessage] 消息已删除: {} by {} (own={}, host={}, silkReply={})",
            messageId, userId, isOwnMessage, isGroupHost, isSilkReplyToMe)
        return RecallResult(true, "删除成功", listOf(messageId))
    }

    /**
     * 广播撤回通知给所有连接的客户端
     */
    private suspend fun broadcastRecallNotification(messageIds: List<String>) {
        val recallMessage = Message(
            id = generateId(),
            userId = "system",
            userName = "系统",
            content = messageIds.joinToString(","),
            timestamp = System.currentTimeMillis(),
            type = MessageType.RECALL,
            isTransient = true
        )
        val notificationJson = Json.encodeToString(recallMessage)

        allSessions().forEach { session ->
            sendFrameSafely(session, notificationJson) { error ->
                logger.error("❌ [broadcastRecallNotification] 发送失败", error)
            }
        }
        logger.debug("📢 [broadcastRecallNotification] 已广播撤回通知: {}", messageIds)
    }

    private fun generateId(): String {
        return System.currentTimeMillis().toString() + (0..999).random()
    }
}

/**
 * 消息撤回结果
 */
@Serializable
data class RecallResult(
    val success: Boolean,
    val message: String,
    val deletedMessageIds: List<String>
)

val chatServer = ChatServer()

fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(30)   // 每30秒发送一次ping
        timeout = Duration.ofSeconds(120)     // 2分钟超时，确保AI有足够时间响应
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
}
