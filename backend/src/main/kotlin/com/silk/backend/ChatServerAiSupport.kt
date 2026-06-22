package com.silk.backend

import com.silk.backend.ai.AIConfig
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.database.GroupRepository
import com.silk.backend.kb.resolveKnowledgeBasePromptContext
import com.silk.backend.models.MessageReference
import com.silk.backend.todos.GroupTodoExtractionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun ChatServer.isRolePromptMessageSupport(content: String): Boolean {
    val roleKeywords = listOf(
        "你是", "你现在是", "扮演", "假设你是", "作为", "角色是",
        "请以", "假装你是", "模拟", "充当", "担任",
        "you are", "act as", "pretend to be", "role:", "persona:",
    )
    val lowerContent = content.lowercase()
    return roleKeywords.any { keyword ->
        lowerContent.startsWith(keyword.lowercase()) ||
            lowerContent.contains("角色") ||
            lowerContent.contains("身份")
    }
}

internal suspend fun ChatServer.handleStopGenerationSupport(userId: String) {
    logger.info("🛑 收到停止生成请求 (userId={})", userId)

    val userSessions = connections[userId]
    if (!userSessions.isNullOrEmpty()) {
        val ccBroadcastFn: suspend (Message) -> Unit = { msg ->
            if (!msg.isTransient) {
                messageHistory.add(msg)
                historyManager.addMessage(currentSessionName, msg)
            }
            val msgJson = Json.encodeToString(msg)
            val currentSessions = connections[userId] ?: emptyList()
            currentSessions.forEach { session ->
                sendFrameSafely(session, msgJson) { error ->
                    logSessionSendFailure("stop-generate", error)
                }
            }
        }
        val ccCancelled = AgentRuntime.cancelIfActive(userId, currentSessionName, ccBroadcastFn)
        if (ccCancelled) {
            logger.info("🛑 已通过 AgentRuntime 取消 Agent 任务")
            broadcastSystemStatus("CLEAR_STATUS")
            return
        }
    }

    val job = activeAiJob
    if (job != null && job.isActive) {
        job.cancel()
        activeAiJob = null
        logger.info("🛑 已取消活跃的 AI 任务")
    }
    broadcastSystemStatus("CLEAR_STATUS")
}

internal suspend fun ChatServer.generateIntelligentResponseSupport(
    userMessage: String,
    userId: String = "",
) {
    val callId = System.currentTimeMillis()
    logger.info("🤖 [Agent-{}] 开始直接调用模型 (userId={})", callId, userId)
    broadcastSystemStatus("🤖 正在处理您的问题...")
    val systemPrompt = buildDirectModelSystemPrompt(historyManager.getRolePrompt(currentSessionName))
    prepareDirectModelAgentContext()
    initializeDirectModelWorkspace()
    val kbContext = resolveKnowledgeBasePromptContext(
        rawInput = userMessage,
        userId = userId,
        knowledgeBaseManager = knowledgeBaseManager,
        preferredGroupId = currentSessionName.removePrefix("group_").takeIf { currentSessionName.startsWith("group_") },
    )
    if (kbContext.availableReferences.isNotEmpty()) {
        logger.info(
            "📚 [Agent-{}] 注入 {} 条知识库上下文（手动={}, 自动={}）",
            callId,
            kbContext.availableReferences.size,
            kbContext.diagnostics.manualReferenceCount,
            kbContext.diagnostics.autoCandidateCount,
        )
        broadcastSystemStatus(
            status = buildKnowledgeBaseContextStatus(kbContext),
            references = kbContext.availableReferences.mapIndexed { index, reference ->
                MessageReference(
                    kind = "available",
                    index = index + 1,
                    title = reference.title,
                    snippet = reference.snippet,
                    path = reference.path,
                    origin = reference.origin,
                    reason = reference.reason,
                )
            },
        )
    }

    val state = IntelligentResponseState()
    val responseResult = runCatching {
        directModelAgent.processInput(
            userInput = kbContext.resolvedUserInput,
            systemPrompt = systemPrompt,
            additionalContext = kbContext.promptBlock,
            availableReferences = kbContext.availableReferences,
        ) { stepType, content, isComplete ->
            handleDirectModelStep(callId, stepType, content, state)
            maybeSendFinalAgentMessage(callId, stepType, isComplete, state)
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
        state.agentReferences.size,
    )
    refreshSilkPrivateChatTodosIfNeeded(userId)
}

internal suspend fun ChatServer.generateHistoryRecallResponseSupport(query: String, userId: String) {
    val callId = System.currentTimeMillis()
    logger.info("[/recall-{}] userId={}, query={}...", callId, userId, query.take(50))

    val recallResult = runCatching {
        userHistoryAgent.queryWithHistory(userId = userId, userMessage = query) { _, content, isComplete ->
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

private fun ChatServer.buildDirectModelSystemPrompt(rolePrompt: String?): String = buildString {
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

private fun ChatServer.prepareDirectModelAgentContext() {
    val historyMessages = historyManager.loadChatHistory(currentSessionName)?.messages ?: emptyList()
    directModelAgent.setGroupChatHistory(historyMessages)
    directModelAgent.loadRecentHistory(historyMessages, SilkAgent.AGENT_ID)

    if (!currentSessionName.startsWith("group_")) {
        return
    }

    val groupId = currentSessionName.removePrefix("group_")
    val memberList = GroupRepository.getGroupMembers(groupId).map { member ->
        member.userId to member.userName
    }
    directModelAgent.setGroupMembersList(memberList)
}

private fun ChatServer.initializeDirectModelWorkspace() {
    val workspaceDir = "${AIConfig.CLAUDE_CLI_WORKSPACE_ROOT}/$currentSessionName"
    java.io.File(workspaceDir).mkdirs()
    directModelAgent.initClaudeClient(workspaceDir)
}

private suspend fun ChatServer.handleDirectModelStep(
    callId: Long,
    stepType: String,
    content: String,
    state: IntelligentResponseState,
) {
    when (stepType) {
        "thinking", "tool" -> broadcastSystemStatus(content)
        "streaming_incremental" -> sendStreamingIncrementalMessage(callId, content)
        "complete" -> {
            state.fullResponse = ensureSilkReplyVisible(content)
            state.agentReferences = directModelAgent.lastAgentResponse?.references ?: emptyList()
        }
        "error" -> broadcastSystemStatus("❌ $content")
    }
}

private suspend fun ChatServer.sendStreamingIncrementalMessage(callId: Long, content: String) {
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

private suspend fun ChatServer.maybeSendFinalAgentMessage(
    callId: Long,
    stepType: String,
    isComplete: Boolean,
    state: IntelligentResponseState,
) {
    if (stepType == "complete" && isComplete) {
        sendFinalAgentMessage(callId, state)
    }
}

private suspend fun ChatServer.sendFinalAgentMessage(callId: Long, state: IntelligentResponseState) {
    logger.debug("📤 [智能回答-{}] 准备发送最终消息，内容长度: {}", callId, state.fullResponse.length)

    val messageId = generateId()
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
        references = state.agentReferences,
    )

    messageHistory.add(finalMessage)
    historyManager.addMessage(currentSessionName, finalMessage)

    val messageJson = Json.encodeToString(finalMessage)
    allSessions().forEach { session ->
        sendFrameSafely(session, messageJson) { error ->
            logger.warn("📤 [智能回答-{}] 最终消息发送失败: {}", callId, error.message)
        }
    }
}

private fun ChatServer.syncAgentResponseState(state: IntelligentResponseState, fallbackResponse: String) {
    val agentResponse = directModelAgent.lastAgentResponse
    state.fullResponse = ensureSilkReplyVisible(agentResponse?.content ?: fallbackResponse)
    state.agentReferences = agentResponse?.references ?: emptyList()
}

private fun ChatServer.refreshSilkPrivateChatTodosIfNeeded(userId: String) {
    if (userId.isBlank() || getGroupDisplayName(currentSessionName)?.startsWith("[Silk]") != true) {
        return
    }

    CoroutineScope(Dispatchers.IO).launch {
        runChatCatching { GroupTodoExtractionService.refreshTodosForUser(userId) }
            .onFailure { error -> logger.warn("⚠️ 专属对话待办提取失败", error) }
    }
}

private fun buildKnowledgeBaseContextStatus(kbContext: com.silk.backend.kb.KnowledgeBasePromptContext): String {
    val total = kbContext.availableReferences.size
    val manual = kbContext.diagnostics.manualReferenceCount
    val auto = kbContext.diagnostics.autoCandidateCount
    return buildString {
        append("📚 本轮知识库上下文已准备")
        append("（共 $total 条")
        if (manual > 0 || auto > 0) {
            append("：手动 $manual")
            if (auto > 0) append("，自动 $auto")
        }
        append("）")
    }
}

private suspend fun ChatServer.sendHistoryRecallDelta(content: String, isComplete: Boolean) {
    val message = buildSilkTextMessage(
        content = content,
        isTransient = !isComplete,
        isIncremental = true,
    )
    sendMessageToAllSessions(message)
}

private suspend fun ChatServer.sendFinalHistoryRecallResponse(fullResponse: String) {
    if (fullResponse.isBlank()) return
    val finalMsg = buildSilkTextMessage(fullResponse)
    messageHistory.add(finalMsg)
    sendMessageToAllSessions(finalMsg)
    historyManager.addMessage(currentSessionName, finalMsg)
}
