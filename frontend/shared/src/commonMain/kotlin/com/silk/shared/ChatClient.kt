package com.silk.shared

import com.silk.shared.models.Message
import com.silk.shared.models.MessageType
import com.silk.shared.models.MessageCategory
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.datetime.Clock

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
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    
    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()
    private val historyBuffer = mutableListOf<Message>()
    private var historyLoadStartMs: Long = 0
    
    private var suppressTransient: Boolean = false
    
    private var webSocket: PlatformWebSocket? = null
    private var currentUserId: String = ""
    private var currentUserName: String = ""
    
    suspend fun connect(userId: String, userName: String, groupId: String = "default_room") {
        log("🚀 [ChatClient] connect() 开始执行")
        currentUserId = userId
        currentUserName = userName
        
        historyBuffer.clear()
        historyLoadStartMs = Clock.System.now().toEpochMilliseconds()
        _isLoadingHistory.value = true
        
        _connectionState.value = ConnectionState.CONNECTING
        
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
                _connectionState.value = ConnectionState.DISCONNECTED
            },
            onError = { error ->
                log("❌ [ChatClient] WebSocket 错误: $error")
                _connectionState.value = ConnectionState.DISCONNECTED
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
        log("📨 [ChatClient] 收到消息: ${text.take(100)}...")
        try {
            val message = Json.decodeFromString<Message>(text)
            log("✅ [ChatClient] 解析成功: ${message.type}, 用户: ${message.userName}, category: ${message.category}")

            // 历史加载完成标记
            if (message.isTransient && message.type == MessageType.SYSTEM && message.content == "__history_end__") {
                log("📜 [ChatClient] 收到 history_end 标记")
                flushHistoryBuffer()
                return
            }

            // 安全超时：超过 3 秒仍在缓冲则强制刷入
            if (_isLoadingHistory.value) {
                val elapsed = Clock.System.now().toEpochMilliseconds() - historyLoadStartMs
                if (elapsed > 3000) {
                    log("⏰ [ChatClient] 历史加载超时，强制刷入")
                    flushHistoryBuffer()
                }
            }

            // 历史加载期间：缓冲普通消息，不逐条更新 UI
            if (_isLoadingHistory.value && !message.isTransient && message.category != MessageCategory.AGENT_STATUS && message.type != MessageType.RECALL) {
                historyBuffer.add(message)
                return
            }
            
            val isSilkAi = message.userId == "silk_ai_agent"
            
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
                // Agent 状态消息：添加到状态消息列表（灰色显示）
                message.category == MessageCategory.AGENT_STATUS -> {
                    if (message.content.startsWith("CLEAR_STATUS")) {
                        log("🧹 [ChatClient] 清除状态消息")
                        _statusMessages.value = emptyList()
                        _isGenerating.value = false
                        suppressTransient = false
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
                    log("📝 [ChatClient] 增量临时消息")
                    if (isSilkAi) _isGenerating.value = true
                    val existing = _transientMessage.value
                    if (existing != null &&
                        existing.userId == message.userId &&
                        existing.type == message.type) {
                        _transientMessage.value = existing.copy(
                            content = existing.content + message.content,
                            timestamp = message.timestamp,
                            currentStep = message.currentStep,
                            totalSteps = message.totalSteps
                        )
                    } else {
                        _transientMessage.value = message
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
                    _transientMessage.value = null
                    _statusMessages.value = emptyList()
                    _isGenerating.value = false
                    suppressTransient = false
                }
            }
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            log("❌ [ChatClient] 发送停止信号失败: ${e.message}")
        }
        
        suppressTransient = true
        _isGenerating.value = false
        _transientMessage.value = null
        _statusMessages.value = emptyList()
    }
    
    suspend fun sendMessage(userId: String, userName: String, content: String) {
        suppressTransient = false
        
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
        } catch (e: Exception) {
            log("❌ [ChatClient] 发送消息失败: ${e.message}")
        }
    }
    
    suspend fun disconnect() {
        try {
            webSocket?.disconnect()
            webSocket = null
            _connectionState.value = ConnectionState.DISCONNECTED
            _isGenerating.value = false
            suppressTransient = false
            log("✅ [ChatClient] 已断开连接")
        } catch (e: Exception) {
            log("⚠️ [ChatClient] 断开连接: ${e.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }
    
    fun clearMessages() {
        log("🗑️ [ChatClient] 清空所有消息")
        _messages.value = emptyList()
        _transientMessage.value = null
        _isGenerating.value = false
        _isLoadingHistory.value = false
        historyBuffer.clear()
        suppressTransient = false
    }
    
    fun clearTransientOnly() {
        log("🗑️ [ChatClient] 只清空临时消息")
        _transientMessage.value = null
    }
    
    private fun generateId(): String {
        return "${Clock.System.now().toEpochMilliseconds()}_${(0..9999).random()}"
    }
}

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}
