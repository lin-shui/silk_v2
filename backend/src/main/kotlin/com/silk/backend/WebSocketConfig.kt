package com.silk.backend

import io.ktor.server.application.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.time.Duration
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import com.silk.backend.database.UnreadRepository
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MemberRole
import com.silk.backend.todos.GroupTodoExtractionService
import com.silk.backend.ai.AIConfig
import com.silk.backend.ai.KnowledgeBaseWorkspaceEntry
import com.silk.backend.search.WeaviateClient
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.kb.KnowledgeBaseManager
import com.silk.backend.kb.KnowledgeBaseContextPreferenceStore
import com.silk.backend.kb.KnowledgeBasePromptContext
import com.silk.backend.kb.KnowledgeBaseAiExecutionRequest
import com.silk.backend.kb.KnowledgeBaseAiExecutionResult
import com.silk.backend.kb.buildKnowledgeBaseActionSummary
import com.silk.backend.kb.detectAutoMemoryCaptures
import com.silk.backend.kb.detectExplicitMemoryCapture
import com.silk.backend.kb.executeKnowledgeBaseAiActions
import com.silk.backend.kb.resolveKnowledgeBasePromptContext
import com.silk.backend.kb.buildMemoryKey
import com.silk.backend.models.KnowledgeBaseContextSelection
import org.slf4j.LoggerFactory

private val knowledgeBaseManager: KnowledgeBaseManager get() = KnowledgeBaseManager()
private val knowledgeBaseContextPreferenceStore: KnowledgeBaseContextPreferenceStore get() = KnowledgeBaseContextPreferenceStore()
private val workflowManagerForKnowledgeBase: com.silk.backend.workflow.WorkflowManager get() = com.silk.backend.workflow.WorkflowManager()

private fun buildPersistentKnowledgeBaseContextSelection(
    preferences: com.silk.backend.kb.KnowledgeBaseContextPreferences,
): KnowledgeBaseContextSelection {
    return KnowledgeBaseContextSelection(excludedSpaceIds = preferences.excludedSpaceIds)
}

private fun mergeKnowledgeBaseContextSelection(
    persistent: KnowledgeBaseContextSelection,
    requestSelection: KnowledgeBaseContextSelection,
): KnowledgeBaseContextSelection {
    return KnowledgeBaseContextSelection(
        pinnedEntryIds = requestSelection.pinnedEntryIds.distinct(),
        excludedEntryIds = requestSelection.excludedEntryIds.distinct(),
        excludedSpaceIds = (persistent.excludedSpaceIds + requestSelection.excludedSpaceIds).distinct(),
    )
}

private fun buildKnowledgeBaseContextStatus(kbContext: KnowledgeBasePromptContext): String {
    val total = kbContext.availableReferences.size
    val manual = kbContext.diagnostics.manualReferenceCount
    val pinned = kbContext.diagnostics.pinnedReferenceCount
    val auto = kbContext.diagnostics.autoCandidateCount
    val memory = kbContext.diagnostics.memoryReferenceCount
    val excluded = kbContext.diagnostics.excludedReferenceCount
    return buildString {
        append("📚 本轮知识库上下文已准备")
        append("（共 $total 条")
        val hasReferenceBreakdown = manual > 0 || pinned > 0 || auto > 0 || memory > 0
        if (hasReferenceBreakdown) {
            append("：手动 $manual")
            if (pinned > 0) append("，固定 $pinned")
            if (auto > 0) append("，自动 $auto")
            if (memory > 0) append("，记忆 $memory")
        }
        if (excluded > 0) append("；排除 $excluded")
        append("）")
    }
}

private fun knowledgeBaseWorkspaceSpaceLabel(topic: com.silk.backend.models.KBTopic): String {
    return when (topic.spaceType) {
        com.silk.backend.models.KnowledgeSpaceType.PERSONAL -> "个人"
        com.silk.backend.models.KnowledgeSpaceType.TEAM -> topic.groupId?.let { groupId ->
            GroupRepository.findGroupById(groupId)?.name?.let { "团队/$it" } ?: "团队/$groupId"
        } ?: "团队"
    }
}

private fun buildDirectModelSystemPrompt(rolePrompt: String?): String {
    val now = java.time.LocalDateTime.now()
    val chineseFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm")
    val isoFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    LoggerFactory.getLogger("DirectModelSystemPrompt").info(
        "🤖 [generateIntelligentResponse] 注入系统时间: {} (epochMs={})",
        now.format(isoFmt),
        java.time.Instant.now().toEpochMilli(),
    )
    val roleSection = if (rolePrompt != null) {
        "你的角色设定：$rolePrompt\n\n请以上述角色身份回答问题。"
    } else {
        "你是 Silk，一个智能助手。"
    }
    return buildString {
        appendLine("## 当前日期和时间（系统精确注入，以此为准）")
        appendLine("当前日期：${now.format(chineseFmt)}")
        appendLine("ISO 格式：${now.format(isoFmt)}")
        appendLine("⚠️ 你必须使用上述精确时间回答所有时间/日期相关问题，不得自行推理或猜测。")
        appendLine()
        appendLine(roleSection)
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
}

private data class AgentStreamState(
    var fullResponse: String = "",
    var agentReferences: List<com.silk.backend.models.MessageReference> = emptyList(),
    var lastBlocks: List<com.silk.backend.ai.ContentBlock> = emptyList(),
    val streamingAccumulated: StringBuilder = StringBuilder(),
    var hasStructuredBlocks: Boolean = false,
)

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
    val references: List<com.silk.backend.models.MessageReference> = emptyList(),
    val contentBlocks: List<com.silk.backend.ai.ContentBlock>? = null,  // 结构化 content block（流式 / 持久化回放）
    val interactiveOptions: List<InteractiveOption>? = null,  // 交互式按钮选项（用于 cc-connect 提问）
    val kbContextSelection: com.silk.backend.models.KnowledgeBaseContextSelection? = null,  // 本轮知识库上下文选择（手动/固定/排除）
    val action: String? = null,  // null = 新消息(默认), "edit" = 覆盖同ID消息（CARD 编辑）
)

@Serializable
data class InteractiveOption(
    val label: String = "",
    val value: String = "",
)

@Serializable
enum class MessageType {
    TEXT, JOIN, LEAVE, SYSTEM, FILE, RECALL, STOP_GENERATE, CC_COMMAND, CARD, CARD_REPLY
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

/** 待处理图片状态（用于图片+文字合并发送给 vision 模型） */
data class PendingImageState(
    val userId: String,
    val ocrText: String,
    val imageFileName: String,
    val imageFile: java.io.File,
    val downloadUrl: String,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatServer(
    private val sessionName: String = "default_room",
    private val isSilkChatWorkflow: Boolean = false
) {
    private val logger = LoggerFactory.getLogger(ChatServer::class.java)
    private val connections = ConcurrentHashMap<String, CopyOnWriteArrayList<WebSocketSession>>()
    private val messageHistory = Collections.synchronizedList(mutableListOf<Message>())
    private val historyManager = ChatHistoryManager()
    private val historyJson = Json { ignoreUnknownKeys = true }
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

    /**
     * 统一的异常捕获工具函数，自动重新抛出 CancellationException
     */
    internal inline fun <T> runChatCatching(block: () -> T): Result<T> =
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
            try {
                processedUrlsFile.readLines().forEach { line ->
                    if (line.isNotBlank()) {
                        processedUrls.add(line.trim().lowercase())
                    }
                }
                logger.debug("📋 已从缓存恢复 {} 个已处理的URL", processedUrls.size)
            } catch (e: java.io.IOException) {
                logger.warn("⚠️ 读取URL缓存失败: {}", e.message, e)
            }
        }

        // 从持久化存储加载历史消息到内存（用于消息撤回等功能）
        try {
            val chatHistory = historyManager.loadChatHistory(sessionName)
            if (chatHistory != null && chatHistory.messages.isNotEmpty()) {
                chatHistory.messages.forEach { entry ->
                    val msg = Message(
                        id = entry.messageId,
                        userId = entry.senderId,
                        userName = entry.senderName,
                        content = entry.content,
                        timestamp = entry.timestamp,
                        type = try {
                            MessageType.valueOf(entry.messageType)
                        } catch (e: IllegalArgumentException) {
                            logger.warn("⚠️ 无效的消息类型: {}, 使用默认值 TEXT", entry.messageType, e)
                            MessageType.TEXT
                        },
                        references = entry.references,
                        contentBlocks = entry.contentBlocksJson?.let {
                            try {
                                historyJson.decodeFromString<List<com.silk.backend.ai.ContentBlock>>(it)
                            } catch (e: kotlinx.serialization.SerializationException) {
                                logger.warn("⚠️ 反序列化 contentBlocks 失败: {}", e.message, e)
                                null
                            }
                        },
                        interactiveOptions = entry.interactiveOptionsJson?.let {
                            try {
                                historyJson.decodeFromString<List<InteractiveOption>>(it)
                            } catch (e: kotlinx.serialization.SerializationException) {
                                logger.warn("⚠️ 反序列化 interactiveOptions 失败: {}", e.message, e)
                                null
                            }
                        },
                    )
                    messageHistory.add(msg)
                }
                logger.debug("📜 已从持久化加载 {} 条历史消息到内存 (session: {})", messageHistory.size, sessionName)
            }
        } catch (e: java.io.IOException) {
            logger.warn("⚠️ 加载历史消息到内存失败(IO错误): {}", e.message, e)
        } catch (e: kotlinx.serialization.SerializationException) {
            logger.warn("⚠️ 加载历史消息到内存失败(格式错误): {}", e.message, e)
        }
    }

    // ── 待处理图片状态（图片+文字合并发给 vision 模型） ──
    private val pendingImages = ConcurrentHashMap<String, PendingImageState>()

    fun setPendingImage(userId: String, ocrText: String, fileName: String, file: java.io.File, downloadUrl: String) {
        val key = "$sessionName:$userId"
        pendingImages[key] = PendingImageState(userId, ocrText, fileName, file, downloadUrl)
        logger.info("📸 设置待处理图片: session={}, user={}, file={}", sessionName, userId, fileName)
    }

    fun getAndRemovePendingImage(userId: String): PendingImageState? {
        val key = "$sessionName:$userId"
        val state = pendingImages[key]
        if (state != null) {
            pendingImages.remove(key)
            logger.info("📸 取出待处理图片: session={}, user={}", sessionName, userId)
        }
        return state
    }

    fun hasPendingImage(userId: String): Boolean {
        return pendingImages.containsKey("$sessionName:$userId")
    }

    // 保存已处理的URL到文件
    private fun saveProcessedUrl(url: String) {
        try {
            processedUrlsFile.appendText("$url\n")
        } catch (e: java.io.IOException) {
            logger.warn("⚠️ 保存URL缓存失败: {}", e.message, e)
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
            try {
                val members = com.silk.backend.database.GroupRepository.getGroupMembers(groupId)
                if (members.isNotEmpty()) {
                    return members.map { it.userId }
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.warn("⚠️ 获取群组成员失败，回退到连接列表: {}", e.message)
            }
        }
        // 非群组或获取失败时，从 WebSocket 连接获取
        return connections.keys.toList().ifEmpty { listOf(userId) }
    }

    // ==================== broadcast 辅助函数 ====================

    /**
     * 检查消息是否应该跳过（重复消息检测）
     */
    private fun shouldSkipDuplicateMessage(message: Message): Boolean {
        // 临时消息或编辑消息不检查重复
        if (message.isTransient || message.action == "edit") {
            return false
        }
        return messageHistory.any { it.id == message.id }
    }

    /**
     * 检查消息是否需要持久化（非临时消息）
     */
    private fun isPersistableNewMessage(message: Message): Boolean {
        return !message.isTransient
    }

    /**
     * 持久化消息到内存历史、文件系统，并记录未读状态、启动 Weaviate 索引
     */
    private fun persistBroadcastMessageIfNeeded(message: Message) {
        if (!message.isTransient) {
            // 添加到内存历史
            messageHistory.add(message)

            // 持久化到文件系统
            historyManager.addMessage(sessionName, message)
            logger.debug("💾 [broadcast] 消息已保存: {}", message.id)

            // 记录新消息用于未读追踪
            val groupId = sessionName.removePrefix("group_")
            UnreadRepository.recordNewMessage(groupId, System.currentTimeMillis(), message.userId)

            // 启动 Weaviate 索引（异步）
            launchWeaviateMessageIndex(message)
        }
    }

    /**
     * 异步索引消息到 Weaviate 搜索系统
     */
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

    /**
     * 安全地发送帧到 WebSocket 会话，捕获异常并调用失败回调
     */
    private suspend fun sendFrameSafely(
        session: WebSocketSession,
        messageJson: String,
        onFailure: (Throwable) -> Unit
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

    /**
     * 将消息广播到所有 WebSocket 连接
     */
    private suspend fun broadcastMessageToAllSessions(message: Message) {
        val messageJson = Json.encodeToString(message)
        if (message.interactiveOptions != null) {
            logger.info("📨 [broadcast] 广播带 interactiveOptions 的消息: options={}, json(前300字符)={}",
                message.interactiveOptions.size, messageJson.take(300))
        }
        val sessions = allSessions()
        sessions.forEach { session ->
            sendFrameSafely(session, messageJson) { error ->
                logger.warn("📤 [broadcast] 消息发送失败: {}", error.message)
            }
        }
        logger.debug("📤 [broadcast] 消息已广播到 {} 个连接", sessions.size)
    }

    /**
     * 如果消息是非 Agent 的 TEXT 消息，启动 URL 处理（异步）
     */
    private fun maybeLaunchUrlProcessing(message: Message) {
        if (message.type == MessageType.TEXT && !message.isTransient && !AgentRuntime.isAgentMessage(message)) {
            CoroutineScope(Dispatchers.IO).launch {
                runChatCatching {
                    processUrlsInMessage(message)
                }.onFailure { e ->
                    logger.warn("⚠️ URL处理失败: {}", e.message)
                }
            }
        }
    }

    /**
     * 处理 CC_COMMAND 类型消息：转发命令给 cc-connect agent
     * @return true 表示已处理（应 return），false 表示未处理（继续后续流程）
     */
    private suspend fun handleCcCommandMessage(message: Message): Boolean {
        val ccCmdGroupId = sessionName.removePrefix("group_")
        if (message.type == MessageType.CC_COMMAND
            && com.silk.backend.ccconnect.CcConnectRegistry.isConnected(ccCmdGroupId)
        ) {
            val memberRole = com.silk.backend.database.GroupRepository.getMemberRole(ccCmdGroupId, message.userId)
            if (memberRole == MemberRole.HOST || memberRole == MemberRole.OPERATOR) {
                com.silk.backend.ccconnect.CcConnectRegistry.sendCommand(ccCmdGroupId, message.content)
            }
            return true
        }
        return false
    }

    /**
     * 处理 TEXT 中的 cmd: 前缀：转发命令给 cc-connect agent
     * @return true 表示已处理（应 return），false 表示未处理（继续后续流程）
     */
    private suspend fun handleCcTextPrefixCommand(message: Message): Boolean {
        val ccCmdGroupId = sessionName.removePrefix("group_")
        if (message.type == MessageType.TEXT
            && message.content.startsWith("cmd:")
            && com.silk.backend.ccconnect.CcConnectRegistry.isConnected(ccCmdGroupId)
        ) {
            val memberRole = com.silk.backend.database.GroupRepository.getMemberRole(ccCmdGroupId, message.userId)
            if (memberRole == MemberRole.HOST || memberRole == MemberRole.OPERATOR) {
                com.silk.backend.ccconnect.CcConnectRegistry.sendCommand(ccCmdGroupId, message.content.removePrefix("cmd:"))
            }
            return true
        }
        return false
    }

    /**
     * 处理 cc-connect 路由：根据多人/单人模式和 @-prefix 转发消息给适配器
     * @return true 表示已处理（应 return），false 表示未处理（继续后续流程）
     */
    private suspend fun handleCcConnectRouting(message: Message): Boolean {
        val ccGroupId = sessionName.removePrefix("group_")
        if (!isCcConnectRoutingApplicable(ccGroupId, message)) {
            return false
        }

        val memberRole = com.silk.backend.database.GroupRepository.getMemberRole(ccGroupId, message.userId)
        if (memberRole != MemberRole.HOST && memberRole != MemberRole.OPERATOR) {
            return true
        }

        val memberCount = com.silk.backend.database.GroupRepository.getGroupMemberCount(ccGroupId)
        val isSoloMode = memberCount <= 1L
        val matchedPrefix = findMatchingCcPrefix(ccGroupId, message.content)

        if (isSoloMode || matchedPrefix != null) {
            val forwardContent = extractForwardContent(message.content, matchedPrefix)
            if (forwardContent.isNotBlank()) {
                forwardToCcConnect(ccGroupId, message, forwardContent)
            }
        }
        return true
    }

    /**
     * 检查是否适用 cc-connect 路由
     */
    private fun isCcConnectRoutingApplicable(ccGroupId: String, message: Message): Boolean {
        return com.silk.backend.ccconnect.CcConnectRegistry.isConnected(ccGroupId)
            && message.type == MessageType.TEXT && !message.isTransient
            && message.userId != "cc-connect" && message.userId != "system"
    }

    /**
     * 查找匹配的 cc-connect 前缀
     */
    private fun findMatchingCcPrefix(ccGroupId: String, content: String): String? {
        val connMeta = com.silk.backend.ccconnect.CcConnectRegistry.getConnectionInfo(ccGroupId)
        val triggerName = com.silk.backend.ccconnect.agentTriggerName(connMeta?.agentType ?: "")
        val prefixes = buildSet {
            add("@cc")
            if (triggerName != "cc") add("@$triggerName")
        }
        return prefixes.firstOrNull { p ->
            content.startsWith("$p ", ignoreCase = true) || content.equals(p, ignoreCase = true)
        }
    }

    /**
     * 提取转发内容（移除前缀）
     */
    private fun extractForwardContent(content: String, matchedPrefix: String?): String {
        if (matchedPrefix == null) {
            return content
        }
        val stripped = if (content.startsWith("$matchedPrefix ", ignoreCase = true)) {
            content.substring(matchedPrefix.length + 1)
        } else {
            content.substring(matchedPrefix.length)
        }
        return stripped.trim()
    }

    /**
     * 转发消息到 cc-connect
     */
    private suspend fun forwardToCcConnect(ccGroupId: String, message: Message, forwardContent: String) {
        val engineContent = transformCcPermissionValues(forwardContent)
        val historyEntries = loadChatHistoryForCc()
        val userMsg = com.silk.backend.ccconnect.UserMessage(
            content = engineContent,
            userId = message.userId,
            userName = message.userName,
            msgId = message.id,
            history = historyEntries,
        )
        if (com.silk.backend.ccconnect.CcConnectRegistry.isWaitingForInput(ccGroupId)) {
            com.silk.backend.ccconnect.CcConnectRegistry.forwardAnswer(ccGroupId, userMsg)
        } else {
            com.silk.backend.ccconnect.CcConnectRegistry.forwardToAdapter(ccGroupId, userMsg)
        }
    }

    /**
     * 转换 cc-connect 权限按钮值
     */
    private fun transformCcPermissionValues(content: String): String {
        return when (content) {
            "perm:allow" -> "allow"
            "perm:deny" -> "deny"
            "perm:allow_all" -> "allow all"
            else -> content
        }
    }

    /**
     * 加载聊天历史用于 cc-connect
     */
    private fun loadChatHistoryForCc(): List<com.silk.backend.ccconnect.HistoryEntry>? {
        val chatHistory = historyManager.loadChatHistory(sessionName)
        return chatHistory?.messages
            ?.filter { it.messageType == "TEXT" }
            ?.takeLast(50)
            ?.map { entry ->
                com.silk.backend.ccconnect.HistoryEntry(
                    senderId = entry.senderId,
                    senderName = entry.senderName,
                    content = entry.content,
                    messageType = entry.messageType,
                    timestamp = entry.timestamp,
                )
            }
    }

    /**
     * 处理 Claude Code 模式拦截：如果 AgentRuntime 处于活跃状态，拦截消息
     * @return true 表示已处理（应 return），false 表示未处理（继续后续流程）
     */
    private suspend fun handleClaudeCodeBroadcastInterception(message: Message, isSilkPrivateChat: Boolean): Boolean {
        if (!shouldInterceptForClaudeCode(message, isSilkPrivateChat)) {
            return false
        }

        val groupId = sessionName
        val ccUserId = message.userId
        val userSessions = connections[ccUserId]
        if (userSessions != null && userSessions.isNotEmpty()) {
            val ccBroadcastFn: suspend (Message) -> Unit = { msg ->
                runChatCatching {
                    if (!msg.isTransient) {
                        messageHistory.add(msg)
                        historyManager.addMessage(sessionName, msg)
                    }
                    val msgJson = Json.encodeToString(msg)
                    val currentSessions = connections[ccUserId] ?: emptyList()
                    currentSessions.forEach { session ->
                        sendFrameSafely(session, msgJson) { error ->
                            logger.warn("[CC] 发送消息到某连接失败: {}", error.message)
                        }
                    }
                }.onFailure { e ->
                    logger.warn("[CC] 发送消息失败: {}", e.message)
                }
            }
            val ccText = message.content
                .removePrefix("@Silk").removePrefix("@silk")
                .trim()
            val ccHandled = AgentRuntime.handleIfActive(
                userId = message.userId,
                groupId = groupId,
                text = ccText,
                userName = message.userName,
                broadcastFn = ccBroadcastFn,
            )
            if (ccHandled) return true
        }
        return false
    }

    /**
     * 处理 Vision 图片+文字合并：检测 ##VISION_IMG: 标记并处理
     * @return true 表示已处理（应 return），false 表示未处理（继续后续流程）
     */
    private suspend fun handleVisionMessage(message: Message): Boolean {
        val visionImgMarker = "##VISION_IMG:"

        // Early return: 不是 Vision 消息
        if (message.type != MessageType.TEXT || message.isTransient || !message.content.startsWith(visionImgMarker)) {
            return false
        }

        val markerEnd = message.content.indexOf("##", visionImgMarker.length)
        if (markerEnd == -1) {
            return false
        }

        val base64Data = message.content.substring(visionImgMarker.length, markerEnd)
        val userText = message.content.substring(markerEnd + 2)
        logger.info("📸 收到 WebSocket 内嵌图片: {} base64 字符 + {} 文字字符", base64Data.length, userText.length)

        val uploadDir = historyManager.getUploadsDir(sessionName)
        uploadDir.mkdirs()

        val imageBytes = decodeBase64Image(base64Data) ?: return false

        val savedFile = saveVisionImage(uploadDir, base64Data, imageBytes)
        val downloadUrl = "/api/files/download/${sessionName.removePrefix("group_")}/${savedFile.name}"
        logger.info("📸 已保存 vision 图片: {} -> {}", savedFile.absolutePath, downloadUrl)

        launchVisionProcessing(savedFile, userText.trim())
        return true
    }

    /**
     * 解码 base64 图片数据
     */
    private fun decodeBase64Image(base64Data: String): ByteArray? {
        return runChatCatching {
            val dataPart = base64Data.substringAfter(",")
            java.util.Base64.getDecoder().decode(dataPart)
        }.onFailure { e ->
            logger.error("❌ base64 解码失败: {}", e.message)
        }.getOrNull()
    }

    /**
     * 保存 Vision 图片到文件
     */
    private fun saveVisionImage(uploadDir: java.io.File, base64Data: String, imageBytes: ByteArray): java.io.File {
        val ext = when {
            base64Data.contains("image/png") -> "png"
            base64Data.contains("image/gif") -> "gif"
            base64Data.contains("image/webp") -> "webp"
            else -> "jpg"
        }
        val timestamp = System.currentTimeMillis()
        val fileName = "vision_${timestamp}.${ext}"
        val savedFile = java.io.File(uploadDir, fileName)
        savedFile.writeBytes(imageBytes)
        return savedFile
    }

    /**
     * 启动 Vision 处理
     */
    private fun launchVisionProcessing(
        savedFile: java.io.File,
        userText: String
    ) {
        activeAiJob?.cancel()
        activeAiJob = CoroutineScope(Dispatchers.IO).launch {
            runChatCatching {
                handleVisionImageAndText(savedFile, userText)
            }.onFailure { e ->
                when (e) {
                    is CancellationException -> logger.info("🛑 Vision 生成已被取消")
                    else -> logger.error("❌ Vision 处理异常: {}", e.message)
                }
            }.also {
                activeAiJob = null
            }
        }
    }

    /**
     * 处理 Silk AI 回复逻辑：角色设置、/recall 命令、普通问题
     */
    private suspend fun handleSilkAiBroadcast(message: Message, isSilkPrivateChat: Boolean) {
        if (!AgentRuntime.isAgentMessage(message) && message.type == MessageType.TEXT && !message.isTransient) {
            messagesSinceAgentResponse++

            val shouldTriggerAI = isSilkPrivateChat || isSilkChatWorkflow ||
                                  message.content.startsWith("@Silk") ||
                                  message.content.startsWith("@silk")

            if (shouldTriggerAI) {
                val silkContent = extractSilkContent(message.content, isSilkPrivateChat)
                processSilkAiRequest(message, silkContent, isSilkPrivateChat)
            } else {
                logger.debug("📝 [broadcast] 普通消息已索引，不触发 AI 回复: {}...", message.content.take(30))
            }
        }
    }

    /**
     * 提取 Silk 内容（移除 @silk/@Silk 前缀）
     */
    private fun extractSilkContent(content: String, isSilkPrivateChat: Boolean): String {
        return if (isSilkPrivateChat || isSilkChatWorkflow) {
            content
        } else {
            content.removePrefix("@Silk").removePrefix("@silk").trim()
        }
    }

    /**
     * 处理 Silk AI 请求：根据内容类型分发到不同的处理函数
     */
    private suspend fun processSilkAiRequest(message: Message, silkContent: String, isSilkPrivateChat: Boolean) {
        when {
            !isSilkPrivateChat && silkContent.isBlank() -> showSilkHelp()
            silkContent == "重置角色" || silkContent.lowercase() == "reset" -> resetSilkRole()
            isRolePromptMessage(silkContent) -> setSilkRole(silkContent, message.userId)
            silkContent.startsWith("/recall ") || silkContent.startsWith("/recall\n") ->
                handleRecallCommand(message, silkContent, isSilkPrivateChat)
            else -> handleSilkQuestion(message, silkContent, isSilkPrivateChat)
        }
    }

    /**
     * 显示 Silk 帮助信息
     */
    private fun showSilkHelp() {
        logger.debug("📖 [broadcast] @silk 帮助提示")
        CoroutineScope(Dispatchers.IO).launch {
            sendAgentStatus("""
                🎯 Silk 使用帮助：
                • @silk [问题] - 向 Silk 提问
                • @silk 你是... - 设置 Silk 角色
                • @silk 重置角色 - 恢复默认角色
            """.trimIndent())
        }
    }

    /**
     * 重置 Silk 角色
     */
    private fun resetSilkRole() {
        historyManager.updateRolePrompt(sessionName, null)
        logger.debug("🎭 [broadcast] 角色已重置")
        CoroutineScope(Dispatchers.IO).launch {
            sendAgentStatus("🎭 角色已重置为默认")
        }
    }

    /**
     * 设置 Silk 角色
     */
    private fun setSilkRole(silkContent: String, userId: String) {
        historyManager.updateRolePrompt(sessionName, silkContent)
        logger.debug("🎭 [broadcast] 角色已设置: {}", silkContent)
        activeAiJob?.cancel()
        activeAiJob = CoroutineScope(Dispatchers.IO).launch {
            runChatCatching {
                sendAgentStatus("🎭 角色已设置")
                generateIntelligentResponse("请简短地自我介绍（1-2句话）", userId)
            }.onFailure { e ->
                if (e is CancellationException) {
                    logger.info("🛑 角色确认生成已被取消")
                    throw e
                }
            }.also {
                activeAiJob = null
            }
        }
    }

    /**
     * 处理 Silk 普通问题（可能包含待处理图片）
     */
    private suspend fun handleSilkQuestion(message: Message, silkContent: String, isSilkPrivateChat: Boolean) {
        val logPrefix = if (isSilkPrivateChat) "[Silk私聊]" else "[@silk]"
        logger.debug("💬 [broadcast] {} 问题: {}...", logPrefix, silkContent.take(50))

        val pendingImg = checkPendingImage(message.userId)
        if (pendingImg != null) {
            launchVisionWithText(pendingImg, silkContent, message.userId)
        } else {
            launchIntelligentResponse(silkContent, message.userId, message.kbContextSelection)
        }
    }

    /**
     * 检查是否有待处理的图片（等待 500ms）
     */
    private suspend fun checkPendingImage(userId: String): PendingImageState? {
        var pendingImg = getAndRemovePendingImage(userId)
        if (pendingImg == null) {
            kotlinx.coroutines.delay(500)
            pendingImg = getAndRemovePendingImage(userId)
            if (pendingImg != null) {
                logger.info("📸 等待后取到待处理图片: user={}", userId)
            }
        }
        return pendingImg
    }

    /**
     * 启动 Vision + 文字分析
     */
    private fun launchVisionWithText(pendingImg: PendingImageState, silkContent: String, userId: String) {
        logger.info("📸 检测到待处理图片，将图片+文字合并发送给 vision 模型: user={}", userId)
        activeAiJob?.cancel()
        activeAiJob = CoroutineScope(Dispatchers.IO).launch {
            runChatCatching {
                sendAgentStatus("🤖 正在结合图片分析您的问题...")
                handleCombinedVisionAndText(pendingImg, silkContent, userId)
            }.onFailure { e ->
                when (e) {
                    is CancellationException -> logger.info("🛑 Vision+文字生成已被用户取消")
                    else -> {
                        logger.error("❌ Vision+文字生成异常: {}", e.message)
                        e.printStackTrace()
                    }
                }
            }.also {
                activeAiJob = null
            }
        }
    }

    /**
     * 启动智能回复生成
     */
    private fun launchIntelligentResponse(
        silkContent: String,
        userId: String,
        kbContextSelection: com.silk.backend.models.KnowledgeBaseContextSelection?
    ) {
        activeAiJob?.cancel()
        activeAiJob = CoroutineScope(Dispatchers.IO).launch {
            runChatCatching {
                generateIntelligentResponse(silkContent, userId, kbContextSelection)
            }.onFailure { e ->
                when (e) {
                    is CancellationException -> logger.info("🛑 AI 生成已被用户取消")
                    else -> {
                        logger.error("❌ 生成AI回答异常: {}", e.message)
                        e.printStackTrace()
                    }
                }
            }.also {
                activeAiJob = null
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    /**
     * 检查是否应该跳过广播（cc-connect 等待输入时）
     */
    private fun shouldSkipBroadcastForCcWaiting(msg: Message): Boolean {
        return msg.type == MessageType.TEXT
            && msg.userId != "cc-connect" && msg.userId != "system"
            && com.silk.backend.ccconnect.CcConnectRegistry.isConnected(sessionName.removePrefix("group_"))
            && com.silk.backend.ccconnect.CcConnectRegistry.isWaitingForInput(sessionName.removePrefix("group_"))
    }

    /**
     * 检查是否应该为 Claude Code 拦截消息
     */
    private fun shouldInterceptForClaudeCode(message: Message, isSilkPrivateChat: Boolean): Boolean {
        return !isSilkPrivateChat
            && message.type == MessageType.TEXT
            && !message.isTransient
            && !AgentRuntime.isAgentMessage(message)
    }

    suspend fun broadcast(message: Message) {
        // 🛑 停止生成：立即取消活跃的 AI 任务并通知客户端
        if (message.type == MessageType.STOP_GENERATE) {
            handleStopGeneration(message.userId)
            return
        }

        // 🃏 卡片回复：路由到 CardReplyRouter，不触发 AI/Agent 流程
        if (message.type == MessageType.CARD_REPLY) {
            handleCardReply(message)
            return
        }

        // ✅ 添加调试日志
        logger.debug("📨 [broadcast] 收到消息: ID={}, User={}, IsTransient={}, Content={}...", message.id, message.userName, message.isTransient, message.content.take(30))

        // ✅ 防止重复处理：检查消息是否已经在历史中
        if (shouldSkipDuplicateMessage(message)) {
            logger.warn("⚠️ [broadcast] 忽略重复消息: {} from {}", message.id, message.userName)
            return
        }

        // 归一化时间戳：用服务端时间替换客户端/浏览器时间，确保所有消息使用同源时钟
        val msg = if (isPersistableNewMessage(message)) {
            message.copy(timestamp = System.currentTimeMillis())
        } else {
            message
        }

        // 持久化消息（内存历史、文件系统、未读追踪、Weaviate 索引）
        persistBroadcastMessageIfNeeded(msg)

        // ⛔ cc-connect 等待回答时，用户 TEXT 消息由下方 cc-connect 路由直接转发给引擎，
        // 不必在此广播——否则按钮值（如 "perm:allow"）会作为用户消息展示给所有人。
        if (shouldSkipBroadcastForCcWaiting(msg)) {
            logger.debug("⏭️ [broadcast] 跳过广播: cc-connect waitingForInput (msg={})", msg.content.take(20))
        } else {
            broadcastMessageToAllSessions(msg)
        }

        val isSilkPrivateChat = getGroupDisplayName(sessionName)?.startsWith("[Silk]") == true
        val isSingleUserGroup = !isSilkPrivateChat && isSingleHumanGroup()
        // 单人群组相当于私聊 Silk：自动触发 AI，无需 @silk
        val effectiveSilkPrivate = isSilkPrivateChat || isSingleUserGroup

        // ==================== cc-connect 命令转发 ====================
        if (handleCcCommandMessage(message)) return
        if (handleCcTextPrefixCommand(message)) return
        if (handleCcConnectRouting(message)) return

        // ==================== Claude Code 模式拦截 ====================
        if (handleClaudeCodeBroadcastInterception(message, isSilkPrivateChat)) return

        // ==================== Vision 图片+文字合并处理 ====================
        if (handleVisionMessage(message)) return

        // ==================== Silk AI 回复逻辑 ====================
        handleSilkAiBroadcast(message, effectiveSilkPrivate)
    }

    /**
     * 处理消息中的URL - 下载网页并索引
     */
    @Suppress("NestedBlockDepth")
    private suspend fun processUrlsInMessage(message: Message) {
        logger.debug("🔗 [URL检测] 开始检测消息: {}...", message.content.take(50))
        val urls = com.silk.backend.utils.WebPageDownloader.extractUrls(message.content)
        logger.debug("🔗 [URL检测] 提取到 {} 个URL: {}", urls.size, urls)

        if (urls.isEmpty()) {
            logger.debug("🔗 [URL检测] 没有URL，跳过")
            return
        }

        // 过滤掉已经处理过的URL
        val newUrls = urls.filter { url ->
            val normalized = url.lowercase().trimEnd('/')
            !processedUrls.contains(normalized)
        }

        if (newUrls.isEmpty()) {
            logger.debug("🔗 检测到 {} 个URL，但都已处理过，跳过", urls.size)
            return
        }

        logger.debug("🔗 检测到 {} 个URL，其中 {} 个是新的: {}", urls.size, newUrls.size, newUrls)

        // 创建上传目录（使用统一的方法获取目录路径）
        val uploadDir = historyManager.getUploadsDir(sessionName)

        for (url in newUrls) {
            val normalizedUrl = url.lowercase().trimEnd('/')

            try {
                // 发送状态消息
                broadcastSystemStatus("🌐 正在下载: $url")

                // 下载内容（支持网页和PDF）
                val content = com.silk.backend.utils.WebPageDownloader.downloadAndExtract(url)

                if (content != null) {
                    // ✅ 只有成功下载后才标记为已处理
                    processedUrls.add(normalizedUrl)
                    saveProcessedUrl(normalizedUrl)

                    // 保存到文件
                    val savedFile = com.silk.backend.utils.WebPageDownloader.saveToFile(content, uploadDir)
                    val downloadSessionId = sessionName.removePrefix("group_")

                    // 发送状态消息
                    val fileType = if (content.isPdf) "PDF" else "网页"
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

                    // 索引到 Weaviate
                    val participants = getSessionParticipants(message.userId)

                    // 创建一个代表内容的历史条目
                    val webPageEntry = com.silk.backend.models.ChatHistoryEntry(
                        messageId = "webpage_${System.currentTimeMillis()}",
                        senderId = message.userId,
                        senderName = "[$fileType] ${content.title}",
                        content = """
                            来源URL: ${content.url}
                            标题: ${content.title}
                            类型: $fileType

                            ${content.textContent.take(10000)}
                        """.trimIndent(),
                        timestamp = System.currentTimeMillis(),
                        messageType = if (content.isPdf) "PDF" else "WEBPAGE"
                    )

                    val indexed = silkAgent.indexMessageToSearch(webPageEntry, participants)
                    if (indexed) {
                        logger.debug("🔍 内容已索引: {}", content.title)
                        broadcastSystemStatus("✅ 已索引$fileType: ${content.title}")
                    }
                } else {
                    broadcastSystemStatus("⚠️ 无法下载: $url")
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("❌ 处理URL失败: {} - {}", url, e.message)
                broadcastSystemStatus("❌ 处理链接失败: $url")
            }
        }

        // ✅ 处理完成后，延迟3秒清除状态消息（让用户能看到结果）
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
    suspend fun broadcastExtractedContent(content: String, label: String) {
        logger.debug("📄 [提取内容广播] {} ({} 字符)", label, content.length)
        // 文件消息已有图片预览，提取内容不再重复加图片
        val combinedContent = content
        val msg = Message(
            id = generateId(),
            userId = SilkAgent.AGENT_ID,
            userName = "📄 文件解析",
            content = combinedContent,
            timestamp = System.currentTimeMillis(),
            type = MessageType.SYSTEM,
            isTransient = false,
            category = MessageCategory.NORMAL
        )
        broadcast(msg)
    }

    suspend fun broadcastSystemStatus(
        status: String,
        references: List<com.silk.backend.models.MessageReference> = emptyList(),
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
            try {
                session.send(Frame.Text(messageJson))
                logger.info("   ✅ 状态已发送到一个连接")
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("   ❌ 状态发送失败: {}", e.message)
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
                    try { session.send(Frame.Text(msgJson)) } catch (_: Exception) {}
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

    /**
     * 生成智能回答 - 简化流程
     *
     * 直接调用模型，让模型使用其内置的 tool 能力（搜索文件、浏览器等）
     * 不再执行复杂的三层搜索流程
     */

    /**
     * 处理图片+文字合并的 vision 请求
     * 当用户上传图片并附带文字时，将图片（OCR 文字）+ 用户文字一起发送给 vision 模型
     */
    /**
     * 广播合并的 Vision 回复（图片预览 + 文字，单条 SYSTEM 消息）
     */
    /**
     * 广播用户消息（图片预览 + 文字，单条 SYSTEM 消息）
     */
    suspend fun broadcastUserMessage(userId: String, userName: String, previewContent: String) {
        val msg = Message(
            id = generateId(),
            userId = userId,
            userName = userName,
            content = previewContent,
            timestamp = System.currentTimeMillis(),
            type = MessageType.SYSTEM,
            isTransient = false
        )
        messageHistory.add(msg)
        historyManager.addMessage(sessionName, msg)
        val msgJson = kotlinx.serialization.json.Json.encodeToString(msg)
        allSessions().forEach { session ->
            try { session.send(io.ktor.websocket.Frame.Text(msgJson)) } catch (_: Exception) {}
        }
        logger.info("📸 [broadcastUserMessage] 已广播用户消息: {} 字符", previewContent.length)
    }

    suspend fun broadcastCombinedVisionResult(
        text: String,
        callId: Long
    ) {
        // 只回复文字，不重复加图片（用户的图片已在上方消息中显示）
        val finalMessage = Message(
            id = generateId(),
            userId = SilkAgent.AGENT_ID,
            userName = "${SilkAgent.AGENT_NAME} (图片分析)",
            content = text,
            timestamp = System.currentTimeMillis(),
            type = MessageType.TEXT,
            isTransient = false
        )
        messageHistory.add(finalMessage)
        historyManager.addMessage(sessionName, finalMessage)

        val messageJson = kotlinx.serialization.json.Json.encodeToString(finalMessage)
        allSessions().forEach { session ->
            try { session.send(io.ktor.websocket.Frame.Text(messageJson)) } catch (_: Exception) {}
        }
        logger.info("✅ [CombinedVision-{}] 广播完成: {} 字符", callId, text.length)
    }

    /**
     * 处理 WebSocket 内嵌的 Vision 图片+文字
     * 前端将图片 base64 嵌入消息（##VISION_IMG:data:...##用户文字），后端在此统一处理
     */
    /**
     * Forward an image to cc-connect's Claude agent for vision processing.
     * Called from FileRoutes.kt when a user uploads an image with @claude/@cc prefix
     * in a cc-connect group. Falls back to Silk's built-in vision if cc-connect is not connected.
     */
    suspend fun forwardImageToCcConnect(
        imageFile: java.io.File,
        userText: String,
        userId: String,
        userName: String,
        ccGroupId: String
    ) {
        logger.info("📸 [CcImage] forwarding image to cc-connect: {}", imageFile.name)
        if (!imageFile.exists()) {
            logger.warn("📸 [CcImage] image file not found: {}", imageFile.absolutePath)
            return
        }
        if (!com.silk.backend.ccconnect.CcConnectRegistry.isConnected(ccGroupId)) {
            logger.warn("📸 [CcImage] cc-connect not connected, falling back to Silk vision")
            handleVisionImageAndText(imageFile, userText)
            return
        }

        try {
            val imageBytes = imageFile.readBytes()
            val base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes)
            val ext = imageFile.extension.lowercase()
            val mimeType = when (ext) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }

            // Load recent chat history (same pattern as TEXT routing)
            val chatHistory = historyManager.loadChatHistory(sessionName)
            val historyEntries = chatHistory?.messages
                ?.filter { it.messageType == "TEXT" }
                ?.takeLast(50)
                ?.map { entry ->
                    com.silk.backend.ccconnect.HistoryEntry(
                        senderId = entry.senderId,
                        senderName = entry.senderName,
                        content = entry.content,
                        messageType = entry.messageType,
                        timestamp = entry.timestamp,
                    )
                }

            val userMsg = com.silk.backend.ccconnect.UserMessage(
                content = userText.ifBlank { "请分析这张图片" },
                userId = userId,
                userName = userName,
                msgId = "img_${System.currentTimeMillis()}",
                history = historyEntries,
                images = listOf(
                    com.silk.backend.ccconnect.ImageAttachment(
                        mimeType = mimeType,
                        data = base64Image,
                        fileName = imageFile.name,
                    )
                ),
            )

            if (com.silk.backend.ccconnect.CcConnectRegistry.isWaitingForInput(ccGroupId)) {
                com.silk.backend.ccconnect.CcConnectRegistry.forwardAnswer(ccGroupId, userMsg)
            } else {
                com.silk.backend.ccconnect.CcConnectRegistry.forwardToAdapter(ccGroupId, userMsg)
            }
            logger.info("📸 [CcImage] image forwarded to cc-connect: {}", imageFile.name)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("📸 [CcImage] failed to forward image to cc-connect: {}", e.message, e)
            // Fallback to Silk vision on error
            handleVisionImageAndText(imageFile, userText)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    suspend fun handleVisionImageAndText(
        imageFile: java.io.File,
        userText: String
    ) {
        val callId = System.currentTimeMillis()
        logger.info("🤖 [VisionWS-{}] 开始处理 WebSocket 内嵌图片", callId)

        try {
            if (!imageFile.exists()) {
                sendAgentStatus("❌ 图片文件不存在")
                return
            }

            val imageBytes = imageFile.readBytes()
            val base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes)
            val ext = imageFile.extension.lowercase()
            val mediaType = when (ext) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }

            val combinedPrompt = if (userText.isNotBlank()) userText else "请用中文详细描述这张图片的内容"

            // 优先 Anthropic API，其次 OpenAI 兼容 API
            val visionText = if (com.silk.backend.ai.AIConfig.ANTHROPIC_API_KEY.isNotBlank()) {
                // Anthropic API
                sendAgentStatus("🤖 正在分析图片...")

                val requestBody = kotlinx.serialization.json.buildJsonObject {
                    put("model", com.silk.backend.ai.AIConfig.VISION_MODEL)
                    put("max_tokens", 4096)
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
                                    put("text", combinedPrompt)
                                }
                            }
                        }
                    }
                }

                val baseUrl = com.silk.backend.ai.AIConfig.ANTHROPIC_API_BASE_URL.trimEnd('/')
                val httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("$baseUrl/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", com.silk.backend.ai.AIConfig.ANTHROPIC_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .timeout(java.time.Duration.ofSeconds(300))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build()

                val httpClient = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build()

                val response = httpClient.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() == 200) {
                    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    val body = json.parseToJsonElement(response.body()).jsonObject
                    body["content"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("text")?.jsonPrimitive?.content ?: ""
                } else {
                    val errMsg = response.body().take(200)
                    logger.warn("⚠️ [VisionWS-{}] Anthropic API 返回 {}: {}", callId, response.statusCode(), errMsg)
                    "⚠️ Vision API 返回 ${response.statusCode()}"
                }
            } else if (com.silk.backend.ai.AIConfig.VISION_BASE_URL.isNotBlank()) {
                // OpenAI 兼容 API
                sendAgentStatus("🤖 正在分析图片...")

                val requestBody = kotlinx.serialization.json.buildJsonObject {
                    put("model", com.silk.backend.ai.AIConfig.VISION_MODEL)
                    put("max_tokens", 4096)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:$mediaType;base64,$base64Image")
                                    }
                                }
                                addJsonObject {
                                    put("type", "text")
                                    put("text", combinedPrompt)
                                }
                            }
                        }
                    }
                }

                val baseUrl = com.silk.backend.ai.AIConfig.VISION_BASE_URL.trimEnd('/')
                val url = "$baseUrl/chat/completions"
                val apiKey = com.silk.backend.ai.AIConfig.VISION_API_KEY.ifBlank { "sk-no-key" }

                val httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                    .timeout(java.time.Duration.ofSeconds(300))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build()

                val httpClient = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build()

                val response = httpClient.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() == 200) {
                    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    val body = json.parseToJsonElement(response.body()).jsonObject
                    body["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("message")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.content ?: ""
                } else {
                    val errMsg = response.body().take(200)
                    logger.warn("⚠️ [VisionWS-{}] OpenAI API 返回 {}: {}", callId, response.statusCode(), errMsg)
                    "⚠️ Vision API 返回 ${response.statusCode()}"
                }
            } else {
                "⚠️ 未配置 Vision 模型"
            }

            sendAgentStatus("CLEAR_STATUS")

            broadcastCombinedVisionResult(visionText, callId)
            logger.info("✅ [VisionWS-{}] Vision 处理完成", callId)

        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("❌ [VisionWS-{}] 处理失败: {}", callId, e.message, e)
            sendAgentStatus("❌ 图片分析失败: ${e.message}")
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun handleCombinedVisionAndText(
        pendingImg: PendingImageState,
        userText: String,
        userId: String
    ) {
        val callId = System.currentTimeMillis()
        logger.info("🤖 [CombinedVision-{}] 开始合并处理图片+文字", callId)

        try {
            val imageFile = pendingImg.imageFile
            if (!imageFile.exists()) {
                sendAgentStatus("❌ 图片文件已不存在")
                return
            }

            // 读取图片
            val imageBytes = imageFile.readBytes()
            val base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes)
            val ext = imageFile.extension.lowercase()
            val mediaType = when (ext) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }

            // 构建 prompt：只使用用户问题，图片由模型自行理解
            val combinedPrompt = if (userText.isNotBlank()) {
                userText
            } else {
                "请用中文详细描述这张图片的内容"
            }

            if (com.silk.backend.ai.AIConfig.ANTHROPIC_API_KEY.isNotBlank()) {
                // Anthropic API
                sendAgentStatus("🤖 正在分析图片...")

                val requestBody = kotlinx.serialization.json.buildJsonObject {
                    put("model", com.silk.backend.ai.AIConfig.VISION_MODEL)
                    put("max_tokens", 4096)
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
                                    put("text", combinedPrompt)
                                }
                            }
                        }
                    }
                }

                val baseUrl = com.silk.backend.ai.AIConfig.ANTHROPIC_API_BASE_URL.trimEnd('/')
                val httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("$baseUrl/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", com.silk.backend.ai.AIConfig.ANTHROPIC_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .timeout(java.time.Duration.ofSeconds(300))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build()

                val httpClient = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build()

                val response = httpClient.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() == 200) {
                    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    val body = json.parseToJsonElement(response.body()).jsonObject
                    val text = body["content"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("text")?.jsonPrimitive?.content ?: ""

                    sendAgentStatus("CLEAR_STATUS")
                    broadcastCombinedVisionResult(text.ifEmpty { "抱歉，未能分析图片内容" }, callId)
                } else {
                    val errBody = response.body().take(200)
                    logger.warn("⚠️ [CombinedVision-{}] Vision API 返回 {}: {}", callId, response.statusCode(), errBody)
                    val errText = "⚠️ Vision API 返回 ${response.statusCode()}，请检查模型配置。\n\n**错误信息**: $errBody"
                    // 仍然广播带图片预览的 SYSTEM 消息，即使 API 失败
                    broadcastCombinedVisionResult(errText, callId)
                }
            } else if (com.silk.backend.ai.AIConfig.VISION_BASE_URL.isNotBlank()) {
                // OpenAI 兼容 API
                sendAgentStatus("🤖 正在分析图片...")

                val requestBody = kotlinx.serialization.json.buildJsonObject {
                    put("model", com.silk.backend.ai.AIConfig.VISION_MODEL)
                    put("max_tokens", 4096)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:$mediaType;base64,$base64Image")
                                    }
                                }
                                addJsonObject {
                                    put("type", "text")
                                    put("text", combinedPrompt)
                                }
                            }
                        }
                    }
                }

                val baseUrl = com.silk.backend.ai.AIConfig.VISION_BASE_URL.trimEnd('/')
                val url = "$baseUrl/chat/completions"
                val apiKey = com.silk.backend.ai.AIConfig.VISION_API_KEY.ifBlank { "sk-no-key" }

                val httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                    .timeout(java.time.Duration.ofSeconds(300))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build()

                val httpClient = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build()

                val response = httpClient.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() == 200) {
                    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    val body = json.parseToJsonElement(response.body()).jsonObject
                    val text = body["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("message")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.content ?: ""

                    sendAgentStatus("CLEAR_STATUS")
                    broadcastCombinedVisionResult(text.ifEmpty { "抱歉，未能分析图片内容" }, callId)
                } else {
                    val errBody = response.body().take(200)
                    logger.warn("⚠️ [CombinedVision-{}] Vision API 返回 {}: {}", callId, response.statusCode(), errBody)
                    val errText = "⚠️ Vision API 返回 ${response.statusCode()}，请检查模型配置。\n\n**错误信息**: $errBody"
                    broadcastCombinedVisionResult(errText, callId)
                }
            } else {
                // 没有 vision 模型配置，fallback 到文字模型
                logger.warn("⚠️ [CombinedVision-{}] 未配置 vision 模型，回退到文字模型", callId)
                sendAgentStatus("⚠️ 未配置 Vision 模型，使用文字模型回答")
                broadcastCombinedVisionResult("⚠️ 未配置 Vision 模型。\n\n" + userText.ifBlank { "请用中文详细描述这张图片的内容" }, callId)
            }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("❌ [CombinedVision-{}] 处理失败: {}", callId, e.message, e)
            val errText = "❌ 图片分析失败: ${e.message}\n\n已使用文字模式回答，请稍后重试。"
            broadcastCombinedVisionResult(errText, callId)
            // 额外用文字模型再试一次
            try { generateIntelligentResponse(userText, userId) } catch (_: Exception) {}
        }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private suspend fun generateIntelligentResponse(
        userMessage: String,
        userId: String = "",
        kbContextSelection: KnowledgeBaseContextSelection? = null,
    ) {
        val callId = System.currentTimeMillis()
        logger.info("🤖 [Agent-{}] 开始直接调用模型 (userId={})", callId, userId)

        // 发送开始状态
        sendAgentStatus("🤖 正在处理您的问题...")

        // 获取 session 的角色提示（通过 @Silk 设置）
        val rolePrompt = historyManager.getRolePrompt(sessionName)

        // 构建系统提示
        val systemPrompt = buildDirectModelSystemPrompt(rolePrompt)

        // 加载聊天历史并设置到 Agent（用于群组统计等功能 + 近期上下文）
        val chatHistory = historyManager.loadChatHistory(sessionName)
        val historyMessages = chatHistory?.messages ?: emptyList()
        directModelAgent.setGroupChatHistory(historyMessages)
        directModelAgent.loadRecentHistory(historyMessages, SilkAgent.AGENT_ID)

        // 获取群组成员列表并设置到 Agent（用于统计所有成员）
        if (sessionName.startsWith("group_")) {
            val groupId = sessionName.removePrefix("group_")
            val members = GroupRepository.getGroupMembers(groupId)
            val memberList = members.map { it.userId to it.userName }
            directModelAgent.setGroupMembersList(memberList)
        }

        // 根据“群聊作用域 / 私聊跨群作用域”计算用户可访问的 sessionId 列表
        val accessibleSessionIds = resolveAccessibleSessionIds(userId)

        // 使用 DirectModelAgent 直接调用模型
        // 初始化 claude CLI 进程客户端（设置群组隔离的工作目录）
        val workspaceDir = "${AIConfig.CLAUDE_CLI_WORKSPACE_ROOT}/$sessionName"
        java.io.File(workspaceDir).mkdirs()
        directModelAgent.initClaudeClient(workspaceDir)
        syncKnowledgeBaseWorkspaceForUser(userId)

        val kbPreferences = if (userId.isBlank()) {
            com.silk.backend.kb.KnowledgeBaseContextPreferences(userId = "")
        } else {
            knowledgeBaseContextPreferenceStore.get(userId)
        }
        val preferredGroupId = sessionName.removePrefix("group_").takeIf { sessionName.startsWith("group_") }
        val explicitMemoryCapture = detectExplicitMemoryCapture(userMessage)
        if (kbPreferences.memoryEnabled) {
            explicitMemoryCapture?.let { capture ->
                val saved = knowledgeBaseManager.captureExplicitMemory(
                    userId = userId,
                    content = capture.content,
                    title = capture.title,
                    type = capture.type,
                    key = capture.key ?: buildMemoryKey(capture.type, capture.content),
                )
                broadcastSystemStatus(status = "🧠 已保存长期记忆：${saved.title}")
                // Phase 4: 群组会话中也保存一份到群组记忆
                if (preferredGroupId != null) {
                    try {
                        knowledgeBaseManager.captureExplicitGroupMemory(
                            userId = userId,
                            groupId = preferredGroupId,
                            content = capture.content,
                            title = capture.title,
                            type = capture.type,
                            key = capture.key ?: buildMemoryKey(capture.type, capture.content),
                        )
                    } catch (_: Exception) { /* 非群组成员时静默忽略 */ }
                }
            }
            if (explicitMemoryCapture == null && kbPreferences.autoCaptureEnabled) {
                val autoSaved = detectAutoMemoryCaptures(userMessage)
                    .mapNotNull { capture ->
                        knowledgeBaseManager.captureAutoMemory(
                            userId = userId,
                            content = capture.content,
                            title = capture.title,
                            type = capture.type,
                            key = capture.key,
                        )
                    }
                    .distinctBy { it.memory?.key ?: it.id }
                if (autoSaved.isNotEmpty()) {
                    val summary = autoSaved.joinToString("、") {
                        it.title.removePrefix("Preference: ").removePrefix("Procedure: ")
                    }
                    broadcastSystemStatus(status = "🧠 已更新自动记忆：$summary")
                    // Phase 4: 群组会话中也保存自动记忆到群组
                    if (preferredGroupId != null) {
                        detectAutoMemoryCaptures(userMessage).forEach { capture ->
                            try {
                                knowledgeBaseManager.captureAutoGroupMemory(
                                    userId = userId,
                                    groupId = preferredGroupId,
                                    content = capture.content,
                                    title = capture.title,
                                    type = capture.type,
                                    key = capture.key,
                                )
                            } catch (_: Exception) { /* 非群组成员时静默忽略 */ }
                        }
                    }
                }
            }
        } else if (explicitMemoryCapture != null) {
            broadcastSystemStatus(status = "🧠 记忆功能已关闭，本轮不会保存长期记忆")
        }

        // 解析知识库上下文（手动引用 [[kb:...]] + pinned/excluded + 自动检索 + space 级排除）
        val persistentSelection = buildPersistentKnowledgeBaseContextSelection(kbPreferences)
        val kbContext = resolveKnowledgeBasePromptContext(
            rawInput = userMessage,
            userId = userId,
            knowledgeBaseManager = knowledgeBaseManager,
            preferredGroupId = preferredGroupId,
            memoryEnabled = kbPreferences.memoryEnabled,
            selection = mergeKnowledgeBaseContextSelection(
                persistentSelection,
                kbContextSelection ?: KnowledgeBaseContextSelection(),
            ),
        )
        if (kbContext.availableReferences.isNotEmpty()) {
            logger.info(
                "📚 [Agent-{}] 注入 {} 条知识库上下文（手动={}, 自动={}, 记忆={}）",
                callId,
                kbContext.availableReferences.size,
                kbContext.diagnostics.manualReferenceCount,
                kbContext.diagnostics.autoCandidateCount,
                kbContext.diagnostics.memoryReferenceCount,
            )
            broadcastSystemStatus(
                status = buildKnowledgeBaseContextStatus(kbContext),
                references = kbContext.availableReferences.mapIndexed { index, reference ->
                    com.silk.backend.models.MessageReference(
                        kind = "available",
                        index = index + 1,
                        title = reference.title,
                        snippet = reference.snippet,
                        path = reference.path,
                        origin = reference.origin,
                        reason = reference.reason,
                        spaceId = reference.spaceId,
                        spaceLabel = reference.spaceLabel,
                    )
                },
            )
        }

        val streamState = AgentStreamState()
        try {
            val activeWorkflowId = sessionName.removePrefix("group_")
                .takeIf { sessionName.startsWith("group_") }
                ?.let { workflowManagerForKnowledgeBase.getWorkflowByGroupId(it)?.id }
            val response = directModelAgent.processInput(
                userInput = kbContext.resolvedUserInput,
                systemPrompt = systemPrompt,
                accessibleSessionIds = accessibleSessionIds,
                availableReferences = kbContext.availableReferences,
                additionalContext = kbContext.promptBlock,
            ) { stepType, content, isComplete ->
                handleAgentStepUpdate(callId, stepType, content, streamState)
            }

            val agentResponse = directModelAgent.lastAgentResponse
            streamState.fullResponse = ensureSilkReplyVisible(agentResponse?.content ?: response)
            streamState.agentReferences = agentResponse?.references ?: emptyList()
            val kbActionResults = executeKnowledgeBaseAiActions(
                manager = knowledgeBaseManager,
                request = KnowledgeBaseAiExecutionRequest(
                    userId = userId,
                    preferredGroupId = preferredGroupId,
                    sourceGroupId = preferredGroupId,
                    workflowId = activeWorkflowId,
                    recentMessageIds = historyMessages.takeLast(8).map { it.messageId },
                ),
                actions = directModelAgent.lastKnowledgeBaseActions,
            )
            if (kbActionResults.isNotEmpty()) {
                streamState.fullResponse = (streamState.fullResponse.trimEnd() + buildKnowledgeBaseActionSummary(kbActionResults)).trimEnd()
            }
            logger.debug("🏁 [generateIntelligentResponse-{}] 函数执行完成，响应长度: {}, 引用数: {}", callId, streamState.fullResponse.length, streamState.agentReferences.size)

            val messageId = generateId()
            val finalMessage = Message(
                id = messageId,
                userId = SilkAgent.AGENT_ID,
                userName = SilkAgent.AGENT_NAME,
                content = streamState.fullResponse,
                timestamp = System.currentTimeMillis(),
                type = MessageType.TEXT,
                isTransient = false,
                isIncremental = false,
                references = streamState.agentReferences,
                contentBlocks = streamState.lastBlocks.ifEmpty { null },
            )
            if (!messageHistory.any { it.id == messageId }) {
                messageHistory.add(finalMessage)
                historyManager.addMessage(sessionName, finalMessage)
                val messageJson = Json.encodeToString(finalMessage)
                allSessions().forEach { session ->
                    try {
                        session.send(Frame.Text(messageJson))
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        logger.error("📤 [智能回答-{}] 发送最终消息失败: {}", callId, e.message)
                    }
                }
            }

            if (userId.isNotBlank() && getGroupDisplayName(sessionName)?.startsWith("[Silk]") == true) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        GroupTodoExtractionService.refreshTodosForUser(userId)
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        logger.warn("⚠️ 专属对话待办提取失败: {}", e.message)
                    }
                }
            }

        } catch (e: CancellationException) {
            logger.info("🛑 [generateIntelligentResponse-{}] 生成被取消", callId)
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("❌ [generateIntelligentResponse-{}] 生成AI回答失败: {}", callId, e.message, e)

            // 发送错误消息
            val errorMessage = Message(
                id = generateId(),
                userId = SilkAgent.AGENT_ID,
                userName = SilkAgent.AGENT_NAME,
                content = "抱歉，处理您的问题时发生了错误: ${e.message}",
                timestamp = System.currentTimeMillis(),
                type = MessageType.TEXT,
                isTransient = false,
                isIncremental = false
            )

            val messageJson = Json.encodeToString(errorMessage)
            allSessions().forEach { session ->
                try {
                    session.send(Frame.Text(messageJson))
                } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
    }

    private fun resolveAccessibleSessionIds(userId: String): List<String> {
        if (!sessionName.startsWith("group_") || userId.isBlank()) {
            return listOf(sessionName)
        }
        val groupId = sessionName.removePrefix("group_")
        val isSilkPrivateChat = getGroupDisplayName(sessionName)?.startsWith("[Silk]") == true
        if (isSilkPrivateChat) {
            return GroupRepository.getUserGroups(userId).map { "group_${it.id}" }.distinct()
        }
        return if (GroupRepository.isUserInGroup(groupId, userId)) listOf(sessionName) else emptyList()
    }

    private fun syncKnowledgeBaseWorkspaceForUser(userId: String) {
        if (userId.isBlank()) return
        val kbWorkspaceEntries = knowledgeBaseManager.listTopics(userId)
            .asSequence()
            .flatMap { topic ->
                knowledgeBaseManager.listEntries(topic.id, userId)
                    .asSequence()
                    .filter { it.status != com.silk.backend.models.KBEntryStatus.DELETED }
                    .map { entry ->
                        KnowledgeBaseWorkspaceEntry(
                            topicId = topic.id,
                            topicName = topic.name,
                            topicProject = topic.project,
                            entryId = entry.id,
                            entryTitle = entry.title,
                            content = entry.content,
                            status = entry.status.name,
                            spaceLabel = knowledgeBaseWorkspaceSpaceLabel(topic),
                        )
                    }
            }
            .toList()
        directModelAgent.syncKnowledgeBaseWorkspace(kbWorkspaceEntries)
    }

    private suspend fun handleAgentStepUpdate(
        callId: Long,
        stepType: String,
        content: String,
        streamState: AgentStreamState,
    ) {
        when (stepType) {
            "thinking", "tool" -> {
                if (!streamState.hasStructuredBlocks) {
                    sendAgentStatus(content)
                }
            }
            "streaming_incremental" -> handleStreamingIncremental(callId, content, streamState)
            "blocks_state" -> handleStructuredBlocksState(callId, content, streamState)
            "complete" -> {
                streamState.fullResponse = ensureSilkReplyVisible(content)
                streamState.agentReferences = directModelAgent.lastAgentResponse?.references ?: emptyList()
            }
            "error" -> sendAgentStatus("❌ $content")
        }
    }

    private suspend fun handleStreamingIncremental(
        callId: Long,
        content: String,
        streamState: AgentStreamState,
    ) {
        streamState.streamingAccumulated.append(content)
        if (streamState.hasStructuredBlocks) return

        val accumulated = streamState.streamingAccumulated.toString()
        val blockMessage = Message(
            id = "streaming_${System.currentTimeMillis()}",
            userId = SilkAgent.AGENT_ID,
            userName = SilkAgent.AGENT_NAME,
            content = accumulated,
            timestamp = System.currentTimeMillis(),
            type = MessageType.TEXT,
            isTransient = true,
            isIncremental = false,
        )
        logger.info("📤 [流式-{}] 增量 {}字符 -> {}个连接", callId, accumulated.length, allSessions().size)
        broadcastAgentTransientMessage("流式", callId, blockMessage)
    }

    private suspend fun handleStructuredBlocksState(
        callId: Long,
        content: String,
        streamState: AgentStreamState,
    ) {
        streamState.hasStructuredBlocks = true
        val blocks = Json.decodeFromString<List<com.silk.backend.ai.ContentBlock>>(content)
        streamState.lastBlocks = blocks
        val textContent = blocks.firstOrNull { it.type == "text" }?.content ?: ""
        val blockMessage = Message(
            id = "streaming_${System.currentTimeMillis()}",
            userId = SilkAgent.AGENT_ID,
            userName = SilkAgent.AGENT_NAME,
            content = textContent,
            timestamp = System.currentTimeMillis(),
            type = MessageType.TEXT,
            isTransient = true,
            isIncremental = false,
            contentBlocks = blocks,
        )
        broadcastAgentTransientMessage("blocks_state", callId, blockMessage)
    }

    private suspend fun broadcastAgentTransientMessage(
        channel: String,
        callId: Long,
        message: Message,
    ) {
        val messageJson = Json.encodeToString(message)
        allSessions().forEach { session ->
            try {
                session.send(Frame.Text(messageJson))
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("📤 [{}-{}] 发送失败: {}", channel, callId, e.message)
            }
        }
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
                try {
                    session.send(Frame.Text(messageJson))
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    e.printStackTrace()
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
        try {
            silkAgent.executeDoctorDiagnosisUpdate(
                chatHistory = historyEntries,
                doctorMessage = doctorMessage,
                callback = callback,
                userName = userName,
                groupDisplayName = groupDisplayName
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("❌ 医生诊断更新失败: {}", e.message, e)
        }
    }

    /**
     * 获取群组的Host用户ID
     */
    private fun getGroupHostId(sessionName: String): String? {
        return if (sessionName.startsWith("group_")) {
            val groupId = sessionName.removePrefix("group_")
            try {
                val group = com.silk.backend.database.GroupRepository.findGroupById(groupId)
                group?.hostId
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.warn("⚠️ 获取Host ID失败: {}", e.message)
                null
            }
        } else {
            null
        }
    }

    /**
     * 获取群组的显示名称
     * 从sessionName（格式：group_<uuid>）获取实际的群组名称
     */
    /**
     * 判断当前群组是否只有 1 个真人成员（排除 AI Agent）。
     * 单人群中所有消息自动触发 AI 回复，无需 @silk。
     */
    private fun isSingleHumanGroup(): Boolean {
        if (!sessionName.startsWith("group_")) return false
        val groupId = sessionName.removePrefix("group_")
        return try {
            val members = GroupRepository.getGroupMembers(groupId)
            val humanCount = members.count { !AgentRuntime.isAgentUserId(it.userId) }
            humanCount == 1
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.warn("⚠️ 检查单人群失败: {}", e.message)
            false
        }
    }

    private fun getGroupDisplayName(sessionName: String): String? {
        return if (sessionName.startsWith("group_")) {
            // 提取群组ID
            val groupId = sessionName.removePrefix("group_")
            logger.debug("📋 正在查询群组名称，groupId: {}", groupId)

            // 从数据库查询群组名称
            try {
                val group = com.silk.backend.database.GroupRepository.findGroupById(groupId)
                if (group != null) {
                    logger.debug("📋 找到群组名称: {}", group.name)
                    group.name  // 返回群组的实际名称，例如："liaoheng's Sophie Ankle"
                } else {
                    logger.warn("⚠️ 未找到群组：{}", groupId)
                    null
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.warn("⚠️ 查询群组名称失败: {}", e.message, e)
                null
            }
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

        // 3. 查找用户消息之后的所有连续 Agent 回复（Silk / cc-connect 等），级联删除
        //    包括 thinking、tool_use、answer 等多个分段消息
        logger.debug("🔄 [recallMessage] 查找 Agent 的回复")
        val messageIndex = chatHistory.messages.indexOf(messageEntry)
        val agentReplies = chatHistory.messages
            .drop(messageIndex + 1)
            .takeWhile { AgentRuntime.isAgentUserId(it.senderId) }

        if (agentReplies.isNotEmpty()) {
            val agentIds = agentReplies.map { it.messageId }
            val allIds = listOf(messageId) + agentIds
            logger.debug("🔄 [recallMessage] 找到 {} 条 Agent 回复: {}", agentReplies.size, agentIds)
            historyManager.deleteMessages(sessionName, allIds)
            deletedMessageIds.addAll(allIds)
            messageHistory.removeIf { it.id in allIds }
            broadcastRecallNotification(allIds)
            // 同步删除 Weaviate 向量索引
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    WeaviateClient.getInstance().deleteChatMessages(sessionName, allIds)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    logger.warn("⚠️ [recallMessage] Weaviate 删除向量失败: {}", e.message)
                }
            }
            logger.info("✅ [recallMessage] 已撤回用户消息和 {} 条 Agent 回复", agentReplies.size)
        } else {
            logger.warn("⚠️ [recallMessage] 未找到 Agent 回复，只撤回用户消息")
            historyManager.deleteMessages(sessionName, listOf(messageId))
            deletedMessageIds.add(messageId)
            messageHistory.removeIf { it.id == messageId }
            broadcastRecallNotification(listOf(messageId))
            // 同步删除 Weaviate 向量索引
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    WeaviateClient.getInstance().deleteChatMessages(sessionName, listOf(messageId))
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    logger.warn("⚠️ [recallMessage] Weaviate 删除向量失败: {}", e.message)
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
            try {
                WeaviateClient.getInstance().deleteChatMessages(sessionName, listOf(messageId))
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.warn("⚠️ [deleteMessage] Weaviate 删除向量失败: {}", e.message)
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
            try {
                session.send(Frame.Text(notificationJson))
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("❌ [broadcastRecallNotification] 发送失败: {}", e.message)
            }
        }
        logger.debug("📢 [broadcastRecallNotification] 已广播撤回通知: {}", messageIds)
    }

    private fun generateId(): String {
        return System.currentTimeMillis().toString() + (0..999).random()
    }

    /**
     * 发送消息到所有会话
     */
    private suspend fun sendMessageToAllSessions(message: Message) {
        val messageJson = Json.encodeToString(message)
        allSessions().forEach { session ->
            try { session.send(Frame.Text(messageJson)) } catch (_: Exception) {}
        }
    }

    /**
     * 构建 Silk Agent 文本消息
     */
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

    /**
     * 🃏 卡片回复：持久化、广播并路由到 CardReplyRouter，不触发 AI/Agent 流程。
     */
    private suspend fun handleCardReply(message: Message) {
        logger.info("🃏 [broadcast] 收到卡片回复: {} from {}", message.content.take(80), message.userName)
        // 持久化卡片回复到历史
        if (!message.isTransient) {
            messageHistory.add(message)
            historyManager.addMessage(sessionName, message)
        }
        // 广播给所有客户端
        val messageJson = Json.encodeToString(message)
        allSessions().forEach { session ->
            try { session.send(Frame.Text(messageJson)) } catch (_: Exception) {}
        }
        // 路由到注册的 handler
        try {
            val reply = Json.decodeFromString<com.silk.backend.card.CardReplyPayload>(message.content)
            val broadcastRef: suspend (Message) -> Unit = { msg -> broadcast(msg) }
            val expired = com.silk.backend.card.CardReplyRouter.route(sessionName, reply, broadcastRef)
            if (expired) {
                // 兜底：卡片已过期，发系统提示并 disable 卡片
                broadcast(Message(
                    id = java.util.UUID.randomUUID().toString(),
                    userId = "system",
                    userName = "系统",
                    content = "该卡片已过期，无法处理此操作。",
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.SYSTEM,
                    isTransient = false,
                ))
                broadcast(Message(
                    id = reply.cardId,
                    userId = "system",
                    userName = "系统",
                    content = com.silk.backend.card.CardBuilder("已过期", template = "red")
                        .addText("该卡片已过期。")
                        .buildDisabled(),
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.CARD,
                    action = "edit",
                ))
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("🃏 [broadcast] 卡片回复解析失败: {}", e.message)
        }
    }

    /**
     * /recall 命令：仅限 Silk 专属对话使用，触发历史会话搜索回复。
     */
    private suspend fun handleRecallCommand(message: Message, silkContent: String, isSilkPrivateChat: Boolean) {
        // /recall 命令 - 仅限 Silk 专属对话使用（防止在群聊中泄露用户隐私）
        if (!isSilkPrivateChat) {
            sendMessageToAllSessions(buildSilkTextMessage("/recall 仅可在 Silk 专属对话中使用，请切换到专属对话后再试"))
            return
        }
        val recallQuery = silkContent.removePrefix("/recall").trim()
        logger.info("[/recall] userId={}, query={}...", message.userId, recallQuery.take(50))

        activeAiJob?.cancel()
        activeAiJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                sendMessageToAllSessions(buildSilkTextMessage("正在搜索历史会话...", isTransient = true))
                generateHistoryRecallResponse(recallQuery, message.userId)
            } catch (e: CancellationException) {
                logger.info("[/recall] 已被用户取消: {}", e.message)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("[/recall] 异常: {}", e.message, e)
                sendAgentStatus("历史查询失败: ${e.message}")
            } finally {
                activeAiJob = null
            }
        }
    }

    /**
     * 生成历史回忆响应（使用 UserHistoryAgent）
     */
    private suspend fun generateHistoryRecallResponse(query: String, userId: String) {
        val callId = System.currentTimeMillis()
        logger.info("[/recall-{}] userId={}, query={}...", callId, userId, query.take(50))

        try {
            val fullResponse = userHistoryAgent.queryWithHistory(
                userId = userId,
                userMessage = query,
            ) { _, content, isComplete ->
                sendHistoryRecallDelta(content, isComplete)
            }

            sendFinalHistoryRecallResponse(fullResponse)
            logger.info("[/recall-{}] 完成, responseLen={}", callId, fullResponse.length)
        } catch (e: CancellationException) {
            logger.info("[/recall-{}] 已取消", callId)
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("[/recall-{}] 失败: {}", callId, e.message)
            sendMessageToAllSessions(buildSilkTextMessage("历史查询失败: ${e.message}"))
        }
    }

    /**
     * 发送历史回忆增量内容（流式）
     */
    private suspend fun sendHistoryRecallDelta(content: String, isComplete: Boolean) {
        val message = buildSilkTextMessage(
            content = content,
            isTransient = !isComplete,
            isIncremental = true,
        )
        sendMessageToAllSessions(message)
    }

    /**
     * 发送历史回忆最终响应（持久化）
     */
    private suspend fun sendFinalHistoryRecallResponse(fullResponse: String) {
        if (fullResponse.isBlank()) return
        val finalMsg = buildSilkTextMessage(fullResponse)
        messageHistory.add(finalMsg)
        sendMessageToAllSessions(finalMsg)
        historyManager.addMessage(sessionName, finalMsg)
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
