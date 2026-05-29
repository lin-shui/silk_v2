package com.silk.shared

import com.silk.shared.models.Message
import com.silk.shared.models.MessageCategory
import com.silk.shared.models.MessageType
import com.silk.shared.models.ContentBlock
import com.silk.shared.models.InteractiveOption
import com.silk.shared.models.isAgentUserId
import kotlinx.datetime.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 日志回调
typealias LogCallback = (String) -> Unit

// 平台特定的 WebSocket 实现
expect class PlatformWebSocket(
    serverUrl: String,
    onMessage: (String) -> Unit,
    onConnected: () -> Unit,
    onDisconnected: () -> Unit,
    onError: (String) -> Unit,
    onLog: LogCallback?
) {
    fun connect(userId: String, userName: String, groupId: String)
    fun send(message: String)
    fun disconnect()
    val isConnected: Boolean
}

class ChatClient(
    private val serverUrl: String,
    private val onLog: LogCallback? = null
) {
    private fun log(message: String) {
        println(message)
        onLog?.invoke(message)
    }
    
    init {
        log("✅ ChatClient 已创建")
        log("   服务器 URL: $serverUrl")
    }
    
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    
    // 单独的临时消息状态（只保留最新的一条）- 用于 AI 增量回复
    private val _transientMessage = MutableStateFlow<Message?>(null)
    val transientMessage: StateFlow<Message?> = _transientMessage.asStateFlow()
    
    // 系统状态消息列表（用于显示搜索、索引等状态）
    private val _statusMessages = MutableStateFlow<List<Message>>(emptyList())
    val statusMessages: StateFlow<List<Message>> = _statusMessages.asStateFlow()

    // Agent 提问等待回答状态（AskUserQuestion requestId）
    private val _pendingQuestionId = MutableStateFlow<String?>(null)
    val pendingQuestionId: StateFlow<String?> = _pendingQuestionId.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _transientContentBlocks = MutableStateFlow<List<ContentBlock>>(emptyList())
    val transientContentBlocks: StateFlow<List<ContentBlock>> = _transientContentBlocks.asStateFlow()

    /** cc-connect 交互式按钮选项（AskUserQuestion） */
    private val _interactiveOptions = MutableStateFlow<List<InteractiveOption>>(emptyList())
    val interactiveOptions: StateFlow<List<InteractiveOption>> = _interactiveOptions.asStateFlow()

    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()
    private val historyBuffer = mutableListOf<Message>()
    private var historyLoadStartMs: Long = 0

    private val _ccMetadataJson = MutableStateFlow<String?>(null)
    val ccMetadataJson: StateFlow<String?> = _ccMetadataJson.asStateFlow()
    
    private var suppressTransient: Boolean = false
    
    private var webSocket: PlatformWebSocket? = null
    private var connectionGen: Int = 0
    private var currentUserId: String = ""
    private var currentUserName: String = ""
    
    suspend fun connect(userId: String, userName: String, groupId: String = "default_room") {
        log("🚀 [ChatClient] connect() 开始执行")
        currentUserId = userId
        currentUserName = userName

        // 静默断开旧连接 —— 不触发 DISCONNECTED 状态，避免切群时红色"已断开"闪烁
        val oldWs = webSocket
        if (oldWs != null) {
            log("🔄 [ChatClient] 静默关闭旧连接")
            webSocket = null
            oldWs.disconnect()
        }
        
        historyBuffer.clear()
        historyLoadStartMs = Clock.System.now().toEpochMilliseconds()
        _isLoadingHistory.value = true
        
        _connectionState.value = ConnectionState.CONNECTING
        
        // 用自增 token 标识本次连接，回调中比对以判断是否仍为活跃实例
        val connectToken = ++connectionGen
        
        webSocket = PlatformWebSocket(
            serverUrl = serverUrl,
            onMessage = { text ->
                handleMessage(text)
            },
            onConnected = {
                log("✅ [ChatClient] WebSocket 连接成功")
                _connectionState.value = ConnectionState.CONNECTED
            },
            onDisconnected = {
                log("🔌 [ChatClient] WebSocket 已断开")
                if (connectionGen == connectToken) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            },
            onError = { error ->
                log("❌ [ChatClient] WebSocket 错误: $error")
                if (connectionGen == connectToken) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            },
            onLog = onLog
        )
        
        webSocket?.connect(userId, userName, groupId)
    }
    
    private fun flushHistoryBuffer() {
        if (historyBuffer.isNotEmpty()) {
            log("📜 [ChatClient] 一次性刷入 ${historyBuffer.size} 条历史消息")
            _messages.value = historyBuffer.toList()
            historyBuffer.clear()
        }
        _isLoadingHistory.value = false
    }

    private fun handleMessage(text: String) {
        // 批量历史帧：服务端将最多 50 条消息编码为 JSON 数组一次性发送
        // 不依赖 _isLoadingHistory 状态，防止超时后历史消息被丢弃
        if (text.startsWith("[")) {
            try {
                val batch = Json.decodeFromString<List<Message>>(text)
                if (_isLoadingHistory.value) {
                    historyBuffer.addAll(batch)
                    log("📜 [ChatClient] 收到批量历史: ${batch.size} 条")
                } else {
                    // 历史加载已完成但批量历史晚到：直接写入消息列表
                    log("📜 [ChatClient] 历史加载已完成，直接写入 ${batch.size} 条历史消息")
                    _messages.value = (_messages.value + batch).distinctBy { it.id }
                }
                return
            } catch (_: SerializationException) {
                log("⚠️ [ChatClient] 批量解析失败，回退单条解析")
            }
        }

        try {
            val message = Json.decodeFromString<Message>(text)

            // 历史加载完成标记
            if (message.isTransient && message.type == MessageType.SYSTEM && message.content == "__history_end__") {
                log("📜 [ChatClient] 收到 history_end 标记")
                flushHistoryBuffer()
                return
            }

            // cc-connect metadata 更新：不显示为聊天消息
            if (message.isTransient && message.type == MessageType.SYSTEM && message.content.startsWith("{\"type\":\"cc_metadata\"")) {
                log("🔧 [ChatClient] 收到 cc_metadata 更新")
                _ccMetadataJson.value = message.content
                return
            }

            // 安全超时：超过 30 秒仍在缓冲则强制刷入
            if (_isLoadingHistory.value) {
                val elapsed = Clock.System.now().toEpochMilliseconds() - historyLoadStartMs
                if (elapsed > 30000) {
                    log("⏰ [ChatClient] 历史加载超时 (30s)，强制刷入")
                    flushHistoryBuffer()
                }
            }

            // 历史加载期间：缓冲普通消息，不逐条更新 UI
            if (_isLoadingHistory.value && !message.isTransient && message.category != MessageCategory.AGENT_STATUS && message.type != MessageType.RECALL) {
                historyBuffer.add(message)
                return
            }
            
            val isSilkAi = isAgentUserId(message.userId)
            
            // 停止后抑制残余流式消息（允许 CLEAR_STATUS 通过）
            if (suppressTransient && isSilkAi && message.isTransient) {
                if (message.category == MessageCategory.AGENT_STATUS &&
                    message.content.startsWith("CLEAR_STATUS")) {
                    _statusMessages.value = emptyList()
                }
                return
            }
            
            when {
                // 撤回消息：从列表中移除指定消息
                message.type == MessageType.RECALL -> {
                    log("🗑️ [ChatClient] 收到撤回消息: ${message.content}")
                    val messageIdsToRemove = message.content.split(",").map { it.trim() }
                    _messages.value = _messages.value.filter { msg ->
                        msg.id !in messageIdsToRemove
                    }
                    log("🗑️ [ChatClient] 已移除 ${messageIdsToRemove.size} 条消息")
                }
                // 结构化 content blocks（流式替换完整 block 列表），同时可能携带交互式选项
                message.isTransient && message.contentBlocks != null -> {
                    _transientMessage.value = null
                    _transientContentBlocks.value = message.contentBlocks
                    if (message.interactiveOptions != null) {
                        log("🔘 [ChatClient] 设置 interactiveOptions: ${message.interactiveOptions.size} options: ${message.interactiveOptions.map { it.label }}")
                        _interactiveOptions.value = message.interactiveOptions
                    }
                    if (isSilkAi) _isGenerating.value = true
                }
                // 交互式按钮选项（cc-connect 提问，无 contentBlocks 的场景）
                message.interactiveOptions != null -> {
                    _interactiveOptions.value = message.interactiveOptions
                }
                // Agent 状态消息：添加到状态消息列表（灰色显示）
                message.category == MessageCategory.AGENT_STATUS -> {
                    if (message.content.startsWith("CLEAR_STATUS")) {
                        log("🧹 [ChatClient] 清除状态消息")
                        _statusMessages.value = emptyList()
                        _isGenerating.value = false
                        suppressTransient = false
                        _pendingQuestionId.value = null
                        _transientContentBlocks.value = emptyList()  // 清除内容块
                        log("🔘 [ChatClient] CLEAR_STATUS 清除 interactiveOptions")
                        _interactiveOptions.value = emptyList()  // 清除交互式按钮
                    } else {
                        log("🔄 [ChatClient] Agent 状态消息: ${message.content.take(40)}")
                        if (isSilkAi) _isGenerating.value = true
                        val existingIndex = _statusMessages.value.indexOfFirst { it.id == message.id }
                        val updated = if (existingIndex >= 0) {
                            _statusMessages.value.toMutableList().apply { set(existingIndex, message) }
                        } else {
                            (_statusMessages.value + message).takeLast(10)
                        }
                        _statusMessages.value = updated
                    }
                }
                // 增量临时消息：拼接到已有内容尾部
                message.isTransient && message.isIncremental -> {
                    if (isSilkAi) _isGenerating.value = true
                    val existing = _transientMessage.value
                    if (existing != null &&
                        existing.userId == message.userId &&
                        existing.type == message.type) {
                        val newContent = existing.content + message.content
                        _transientMessage.value = existing.copy(
                            content = newContent,
                            timestamp = message.timestamp,
                            currentStep = message.currentStep,
                            totalSteps = message.totalSteps
                        )
                        log("📝 [ChatClient] 增量拼接: +${message.content.length}字 -> 总${newContent.length}字")
                    } else {
                        _transientMessage.value = message
                        log("📝 [ChatClient] 增量首帧: ${message.content.length}字")
                    }
                }
                // 完整临时消息：直接替换
                message.isTransient -> {
                    log("📝 [ChatClient] 完整临时消息")
                    if (isSilkAi) _isGenerating.value = true
                    _transientMessage.value = message
                }
                // 普通消息：添加到消息列表（如果不存在）
                else -> {
                    val exists = _messages.value.any { it.id == message.id }
                    if (!exists) {
                        log("💬 [ChatClient] 普通消息，添加到列表")
                        _messages.value = _messages.value + message
                    } else {
                        log("⚠️ [ChatClient] 消息已存在，跳过: ${message.id}")
                    }
                    // Track pending question state
                    if (message.category == MessageCategory.AGENT_QUESTION) {
                        val reqId = message.id.removePrefix("agent_question_")
                        _pendingQuestionId.value = reqId
                        // Must clear isGenerating so the send button shows (not stop button)
                        _isGenerating.value = false
                        _transientMessage.value = null
                        _statusMessages.value = emptyList()
                        _transientContentBlocks.value = emptyList()
                        _interactiveOptions.value = emptyList()
                        suppressTransient = false
                    } else {
                        _transientMessage.value = null
                        _statusMessages.value = emptyList()
                        _isGenerating.value = false
                        _transientContentBlocks.value = emptyList()
                        // 不清除 _interactiveOptions：cc-connect 的交互按钮独立于普通消息生命周期，
                        // 由 CLEAR_STATUS 或 sendCcAnswer 负责清除。普通消息到达不应取消等待中的按钮。
                        suppressTransient = false
                        _pendingQuestionId.value = null
                    }
                }
            }
        } catch (e: SerializationException) {
            log("❌ [ChatClient] 解析消息失败: ${e.message}")
        }
    }
    
    fun stopGeneration(userId: String, userName: String) {
        if (webSocket == null || _connectionState.value != ConnectionState.CONNECTED) return
        log("🛑 [ChatClient] 停止 AI 生成")
        
        val transient = _transientMessage.value
        if (transient != null && transient.content.isNotEmpty()) {
            val partialMessage = transient.copy(
                isTransient = false,
                isIncremental = false,
                content = transient.content + "\n\n*(已停止生成)*"
            )
            _messages.value = _messages.value + partialMessage
        }
        
        val stopMessage = Message(
            id = generateId(),
            userId = userId,
            userName = userName,
            content = "",
            timestamp = Clock.System.now().toEpochMilliseconds(),
            type = MessageType.STOP_GENERATE
        )
        try {
            val jsonMessage = Json.encodeToString(stopMessage)
            webSocket?.send(jsonMessage)
        } catch (e: SerializationException) {
            log("❌ [ChatClient] 发送停止信号失败: ${e.message}")
        }
        
        suppressTransient = true
        _isGenerating.value = false
        _transientMessage.value = null
        _statusMessages.value = emptyList()
        _pendingQuestionId.value = null
        _transientContentBlocks.value = emptyList()
        _interactiveOptions.value = emptyList()
    }

    suspend fun sendMessage(userId: String, userName: String, content: String) {
        suppressTransient = false
        // 用户发送新文本消息时清除等待中的交互按钮（cc-connect 场景）
        if (_interactiveOptions.value.isNotEmpty()) {
            log("🔘 [ChatClient] sendMessage 清除 interactiveOptions")
            _interactiveOptions.value = emptyList()
        }

        val message = Message(
            id = generateId(),
            userId = userId,
            userName = userName,
            content = content,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            type = MessageType.TEXT
        )
        
        _messages.value = _messages.value + message
        log("📝 [ChatClient] 消息已添加到本地列表")
        
        try {
            val jsonMessage = Json.encodeToString(message)
            log("📤 [ChatClient] 发送消息: ${content.take(50)}...")
            webSocket?.send(jsonMessage)
            log("✅ [ChatClient] 消息已发送到服务器")
        } catch (e: SerializationException) {
            log("❌ [ChatClient] 发送消息失败: ${e.message}")
        }
    }

    fun sendCcCommand(userId: String, commandText: String) {
        val message = Message(
            id = generateId(),
            userId = userId,
            userName = "",
            content = commandText,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            type = MessageType.CC_COMMAND,
            isTransient = true,
        )
        try {
            val jsonMessage = Json.encodeToString(message)
            log("📤 [ChatClient] 发送 cc_command: $commandText")
            webSocket?.send(jsonMessage)
        } catch (e: SerializationException) {
            log("❌ [ChatClient] 发送 cc_command 失败: ${e.message}")
        }
    }

    /**
     * 发送交互式按钮的答案到 cc-connect 适配器。
     * 不添加到本地消息列表（引擎会通过 reply/reply_stream 继续输出）。
     * cmd: 前缀的按钮值是引擎命令（如 cmd:/mode），直接以 CC_COMMAND 类型发送，
     * 让后端正确路由给引擎处理，不会落入 AI agent 导致权限拦截。
     */
    fun sendCcAnswer(content: String) {
        log("🔘 [ChatClient] sendCcAnswer: content=${content.take(30)}, clearing interactiveOptions")
        // cmd: 前缀 → 作为命令发送（引擎按钮值）
        if (content.startsWith("cmd:")) {
            sendCcCommand(currentUserId, content.substring(4))
            _interactiveOptions.value = emptyList()
            return
        }
        val message = Message(
            id = generateId(),
            userId = currentUserId,
            userName = currentUserName,
            content = content,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            type = MessageType.TEXT,
        )
        try {
            val jsonMessage = Json.encodeToString(message)
            log("📤 [ChatClient] 发送 cc-connect 交互式回答: ${content.take(30)}")
            webSocket?.send(jsonMessage)
            // 清除交互式选项（按钮已点击）
            _interactiveOptions.value = emptyList()
        } catch (e: SerializationException) {
            log("❌ [ChatClient] 发送 cc-connect 交互式回答失败: ${e.message}")
        }
    }
    
    suspend fun disconnect() {
        webSocket?.disconnect()
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _isGenerating.value = false
        suppressTransient = false
        log("✅ [ChatClient] 已断开连接")
    }
    
    fun clearMessages() {
        log("🗑️ [ChatClient] 清空所有消息")
        _messages.value = emptyList()
        _transientMessage.value = null
        _isGenerating.value = false
        _isLoadingHistory.value = false
        historyBuffer.clear()
        suppressTransient = false
        _transientContentBlocks.value = emptyList()
        _interactiveOptions.value = emptyList()
    }

    fun clearTransientOnly() {
        log("🗑️ [ChatClient] 只清空临时消息")
        _transientMessage.value = null
        _transientContentBlocks.value = emptyList()
        _interactiveOptions.value = emptyList()
    }

    private fun generateId(): String {
        return "${Clock.System.now().toEpochMilliseconds()}_${(0..9999).random()}"
    }
}

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}
