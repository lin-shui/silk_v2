package com.silk.backend

import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.database.GroupRepository
import com.silk.backend.search.WeaviateClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun ChatServer.getGroupHostIdSupport(sessionName: String): String? {
    if (!sessionName.startsWith("group_")) {
        return null
    }
    val groupId = sessionName.removePrefix("group_")
    return runChatCatching {
        GroupRepository.findGroupById(groupId)?.hostId
    }.onFailure { error ->
        logger.warn("⚠️ 获取Host ID失败: {}", error.message)
    }.getOrNull()
}

internal fun ChatServer.getGroupDisplayNameSupport(sessionName: String): String? {
    if (!sessionName.startsWith("group_")) {
        logger.debug("📋 非群组session，使用sessionName: {}", sessionName)
        return null
    }

    val groupId = sessionName.removePrefix("group_")
    logger.debug("📋 正在查询群组名称，groupId: {}", groupId)
    return runChatCatching {
        GroupRepository.findGroupById(groupId)
    }.fold(
        onSuccess = { group ->
            when {
                group != null -> {
                    logger.debug("📋 找到群组名称: {}", group.name)
                    group.name
                }
                else -> {
                    logger.warn("⚠️ 未找到群组：{}", groupId)
                    null
                }
            }
        },
        onFailure = { error ->
            logger.warn("⚠️ 查询群组名称失败: {}", error.message)
            logger.debug("⚠️ 查询群组名称异常详情", error)
            null
        },
    )
}

internal suspend fun ChatServer.recallMessageSupport(messageId: String, userId: String): RecallResult {
    logger.debug("🔄 [recallMessage] 开始撤回消息: {} by user {}", messageId, userId)
    logger.debug("🔄 [recallMessage] sessionName: {}", currentSessionName)

    val chatHistory = historyManager.loadChatHistory(currentSessionName)
    logger.debug("🔄 [recallMessage] chatHistory: {}, messages count: {}", chatHistory != null, chatHistory?.messages?.size)
    if (chatHistory != null) {
        logger.debug("🔄 [recallMessage] message IDs in history: {}", chatHistory.messages.map { it.messageId })
    }
    val messageEntry = chatHistory?.messages?.find { it.messageId == messageId }
        ?: return RecallResult(false, "消息不存在", emptyList()).also {
            logger.error("❌ [recallMessage] 消息不存在: {}", messageId)
        }

    if (messageEntry.senderId != userId) {
        logger.error("❌ [recallMessage] 无权撤回此消息: sender={}, requester={}", messageEntry.senderId, userId)
        return RecallResult(false, "只能撤回自己发送的消息", emptyList())
    }

    val deletedMessageIds = mutableListOf<String>()
    val messageIndex = chatHistory.messages.indexOf(messageEntry)
    val silkReply = chatHistory.messages.drop(messageIndex + 1).firstOrNull { AgentRuntime.isAgentUserId(it.senderId) }

    if (silkReply != null) {
        historyManager.deleteMessages(currentSessionName, listOf(messageId, silkReply.messageId))
        deletedMessageIds += listOf(messageId, silkReply.messageId)
        messageHistory.removeIf { it.id == messageId || it.id == silkReply.messageId }
        broadcastRecallNotification(listOf(messageId, silkReply.messageId))
        launchWeaviateDeletion(listOf(messageId, silkReply.messageId), "recallMessage")
        logger.info("✅ [recallMessage] 已撤回用户消息和 Silk 回复")
    } else {
        logger.warn("⚠️ [recallMessage] 未找到 Silk 回复，只撤回用户消息")
        historyManager.deleteMessages(currentSessionName, listOf(messageId))
        deletedMessageIds += messageId
        messageHistory.removeIf { it.id == messageId }
        broadcastRecallNotification(listOf(messageId))
        launchWeaviateDeletion(listOf(messageId), "recallMessage")
    }

    return RecallResult(true, "撤回成功", deletedMessageIds)
}

internal suspend fun ChatServer.deleteMessageSupport(messageId: String, userId: String): RecallResult {
    logger.debug("🗑️ [deleteMessage] 删除消息: {} by user {}", messageId, userId)

    val chatHistory = historyManager.loadChatHistory(currentSessionName)
    val messageEntry = chatHistory?.messages?.find { it.messageId == messageId }
        ?: return RecallResult(false, "消息不存在", emptyList())

    val isOwnMessage = messageEntry.senderId == userId
    val isGroupHost = getGroupHostId(currentSessionName) == userId
    val isSilkReplyToMe = isSilkReplyToUser(chatHistory.messages, messageEntry, userId)

    if (!isOwnMessage && !isGroupHost && !isSilkReplyToMe) {
        return RecallResult(false, "无权删除此消息", emptyList())
    }

    historyManager.deleteMessages(currentSessionName, listOf(messageId))
    messageHistory.removeIf { it.id == messageId }
    broadcastRecallNotification(listOf(messageId))
    launchWeaviateDeletion(listOf(messageId), "deleteMessage")

    logger.info(
        "🗑️ [deleteMessage] 消息已删除: {} by {} (own={}, host={}, silkReply={})",
        messageId,
        userId,
        isOwnMessage,
        isGroupHost,
        isSilkReplyToMe,
    )
    return RecallResult(true, "删除成功", listOf(messageId))
}

internal suspend fun ChatServer.broadcastRecallNotificationSupport(messageIds: List<String>) {
    val recallMessage = Message(
        id = generateId(),
        userId = "system",
        userName = "系统",
        content = messageIds.joinToString(","),
        timestamp = System.currentTimeMillis(),
        type = MessageType.RECALL,
        isTransient = true,
    )
    val notificationJson = Json.encodeToString(recallMessage)

    allSessions().forEach { session ->
        sendFrameSafely(session, notificationJson) { error ->
            logger.error("❌ [broadcastRecallNotification] 发送失败", error)
        }
    }
    logger.debug("📢 [broadcastRecallNotification] 已广播撤回通知: {}", messageIds)
}

private fun ChatServer.isSilkReplyToUser(
    historyEntries: List<com.silk.backend.models.ChatHistoryEntry>,
    messageEntry: com.silk.backend.models.ChatHistoryEntry,
    userId: String,
): Boolean {
    if (!AgentRuntime.isAgentUserId(messageEntry.senderId)) {
        return false
    }

    val msgIndex = historyEntries.indexOf(messageEntry)
    val precedingMsg = historyEntries.take(msgIndex).lastOrNull { !AgentRuntime.isAgentUserId(it.senderId) }
    return precedingMsg?.senderId == userId &&
        (precedingMsg.content.startsWith("@Silk") || precedingMsg.content.startsWith("@silk"))
}

private fun ChatServer.launchWeaviateDeletion(messageIds: List<String>, scope: String) {
    CoroutineScope(Dispatchers.IO).launch {
        runChatCatching {
            WeaviateClient.getInstance().deleteChatMessages(currentSessionName, messageIds)
        }.onFailure { error ->
            logger.warn("⚠️ [{}] Weaviate 删除向量失败", scope, error)
        }
    }
}
