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
import com.silk.backend.kb.KnowledgeBaseManager
import com.silk.backend.kb.resolveKnowledgeBasePromptContext
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

internal data class IntelligentResponseState(
    var fullResponse: String = "",
    var agentReferences: List<MessageReference> = emptyList(),
)

class ChatServer(
    private val sessionName: String = "default_room",
    private val isSilkChatWorkflow: Boolean = false
) {
    internal val currentSessionName: String
        get() = sessionName
    internal val silkChatWorkflowEnabled: Boolean
        get() = isSilkChatWorkflow
    internal val logger = LoggerFactory.getLogger(ChatServer::class.java)
    internal val connections = ConcurrentHashMap<String, CopyOnWriteArrayList<WebSocketSession>>()
    internal val messageHistory = Collections.synchronizedList(mutableListOf<Message>())
    internal val historyManager = ChatHistoryManager()
    internal val silkAgent = SilkAgent().apply {
        initializeAgent(sessionName)  // 初始化 Agent 并传递 session name
    }
    // 直接调用模型的 Agent（简化流程：让模型自动使用 tool 能力）
    internal val directModelAgent = com.silk.backend.ai.DirectModelAgent(sessionId = sessionName)
    internal val knowledgeBaseManager = KnowledgeBaseManager()
    // 用户历史回忆 Agent（/recall 命令使用）
    internal val userHistoryAgent = com.silk.backend.ai.UserHistoryAgent()
    private var messagesSinceAgentResponse = 0
    private var isAgentJoined = false

    @Volatile
    internal var activeAiJob: Job? = null

    internal inline fun <T> runChatCatching(block: () -> T): Result<T> =
        runCatching(block).onFailure { e ->
            if (e is CancellationException) throw e
        }

    // 已处理的URL缓存，避免重复下载（从持久化文件恢复）
    internal val processedUrls = Collections.synchronizedSet(mutableSetOf<String>())
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
    internal fun saveProcessedUrl(url: String) {
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
    internal fun getSessionParticipants(userId: String): List<String> {
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

    internal suspend fun sendFrameSafely(
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

    internal fun logSessionSendFailure(scope: String, error: Throwable) {
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
    private suspend fun processUrlsInMessage(message: Message) =
        processUrlsInMessageSupport(message)

    /**
     * 判断是否是角色设置消息
     * 角色设置关键词：你是、扮演、假设你是、作为、角色是、请以...身份
     */
    private fun isRolePromptMessage(content: String): Boolean =
        isRolePromptMessageSupport(content)

    /**
     * 发送 Agent 状态消息（灰色显示）- 内部使用
     */
    private suspend fun sendAgentStatus(status: String) {
        broadcastSystemStatus(status)
    }

    /**
     * 广播系统状态消息（灰色显示）- 公开方法，供其他模块调用
     */
    internal suspend fun broadcastSystemStatus(
        status: String,
        references: List<MessageReference> = emptyList(),
    ) {
        logger.debug("📢 [状态广播] {} (连接数: {})", status, allSessions().size)

        val statusMessage = Message(
            id = generateId(),
            userId = SilkAgent.AGENT_ID,
            userName = "🔄 系统",
            content = status,
            timestamp = System.currentTimeMillis(),
            type = MessageType.SYSTEM,
            isTransient = true,
            category = MessageCategory.AGENT_STATUS,
            references = references,
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
    private suspend fun handleStopGeneration(userId: String) =
        handleStopGenerationSupport(userId)

    /**
     * Claude PTY（pty_chat）在 thinking 结束时会插入 `<!--THINKING_END-->`；若模型未产出任何正文 text_delta，
     * 标记之后为空串，Web/Harmony 的 Markdown 可见区域会变成空白。此处补齐占位说明。
     */
    internal fun ensureSilkReplyVisible(content: String): String {
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

    internal suspend fun sendMessageToAllSessions(message: Message) {
        val messageJson = Json.encodeToString(message)
        allSessions().forEach { session ->
            sendFrameSafely(session, messageJson) { error ->
                logSessionSendFailure("broadcast-all", error)
            }
        }
    }

    internal fun buildSilkTextMessage(
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

    private suspend fun generateIntelligentResponse(userMessage: String, userId: String = "") =
        generateIntelligentResponseSupport(userMessage, userId)

    private suspend fun generateHistoryRecallResponse(query: String, userId: String) =
        generateHistoryRecallResponseSupport(query, userId)

    fun getOnlineUsers(): List<User> {
        return connections.keys.map { userId ->
            User(id = userId, name = "User_$userId")
        }
    }

    /** 获取所有活跃的 WebSocket 会话（展平多连接） */
    internal fun allSessions(): List<WebSocketSession> =
        connections.values.flatMap { it }

    /**
     * 获取群组的Host用户ID
     */
    internal fun getGroupHostId(sessionName: String): String? =
        getGroupHostIdSupport(sessionName)

    /**
     * 获取群组的显示名称
     * 从sessionName（格式：group_<uuid>）获取实际的群组名称
     */
    internal fun getGroupDisplayName(sessionName: String): String? =
        getGroupDisplayNameSupport(sessionName)

    /**
     * 撤回消息
     * @param messageId 要撤回的消息ID
     * @param userId 发起撤回的用户ID
     * @return 撤回结果：成功/失败，以及被删除的消息ID列表
     */
    suspend fun recallMessage(messageId: String, userId: String): RecallResult =
        recallMessageSupport(messageId, userId)

    /**
     * 删除消息
     * 权限：1) 自己的消息可删  2) Silk回复自己@silk触发的消息可删  3) 群主可删任意消息
     */
    suspend fun deleteMessage(messageId: String, userId: String): RecallResult =
        deleteMessageSupport(messageId, userId)

    /**
     * 广播撤回通知给所有连接的客户端
     */
    internal suspend fun broadcastRecallNotification(messageIds: List<String>) =
        broadcastRecallNotificationSupport(messageIds)

    internal fun generateId(): String {
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
