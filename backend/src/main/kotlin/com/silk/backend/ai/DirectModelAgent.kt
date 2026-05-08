package com.silk.backend.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * 直接调用 Claude 的 Agent。
 * 通过 [ClaudeProcessClient] 启动 claude CLI 子进程。
 *
 * 工作流程：
 * 1. 接收用户输入
 * 2. 将完整对话历史 + system prompt 构造成一个 prompt
 * 3. 调用 claude -p（流式）
 * 4. 流式返回回复
 */
class DirectModelAgent(
    private val sessionId: String = "default"
) {
    private val logger = LoggerFactory.getLogger(DirectModelAgent::class.java)

    /** Claude CLI 进程客户端 */
    private lateinit var claudeProcessClient: ClaudeProcessClient

    /** 工作目录（群组隔离），由 initClaudeClient 设置 */
    private var workspaceDir: String = ""

    // 对话历史（保持上下文）
    private val conversationHistory = mutableListOf<Message>()

    // 群聊历史记录（兼容旧接口，不再使用）
    private var groupChatHistory: List<com.silk.backend.models.ChatHistoryEntry> = emptyList()
    // 群组成员列表（兼容旧接口，不再使用）
    private var groupMembersList: List<Pair<String, String>> = emptyList()

    private val currentResponseReferences = mutableListOf<com.silk.backend.models.MessageReference>()

    var lastAgentResponse: AgentResponse? = null
        private set

    /**
     * 在工作目录就绪后初始化 ClaudeProcessClient。
     * 与构造分离，因为 workspaceDir 在构造时尚不可知。
     */
    fun initClaudeClient(workspaceDir: String) {
        this.workspaceDir = workspaceDir
        if (!::claudeProcessClient.isInitialized) {
            claudeProcessClient = ClaudeProcessClient(
                groupId = sessionId,
                workspaceDir = workspaceDir,
            )
        }
    }

    /**
     * 设置群聊历史记录（兼容旧接口，不再使用）
     */
    fun setGroupChatHistory(history: List<com.silk.backend.models.ChatHistoryEntry>) {
        groupChatHistory = history
    }

    /**
     * 设置群组成员列表（兼容旧接口，不再使用）
     */
    fun setGroupMembersList(members: List<Pair<String, String>>) {
        groupMembersList = members
    }

    /**
     * 将持久化的近期聊天历史注入 conversationHistory，
     * 仅在 conversationHistory 为空时执行（deploy/重启后首次调用）。
     */
    fun loadRecentHistory(entries: List<com.silk.backend.models.ChatHistoryEntry>, agentId: String) {
        if (conversationHistory.isNotEmpty()) return
        val recent = entries.filter { it.messageType == "TEXT" }.takeLast(20)
        if (recent.isEmpty()) return

        for (entry in recent) {
            val role = if (entry.senderId == agentId) "assistant" else "user"
            val content = if (role == "user") {
                "[${entry.senderName}] ${entry.content}"
            } else {
                entry.content
            }
            conversationHistory.add(Message(role = role, content = content))
        }
        logger.info("📜 已注入 {} 条近期历史到 conversationHistory (session: {})", recent.size, sessionId)
    }

    data class AgentResponse(
        val content: String,
        val references: List<com.silk.backend.models.MessageReference> = emptyList()
    )

    private data class FinalCitationResult(
        val content: String,
        val references: List<com.silk.backend.models.MessageReference>
    )

    /**
     * 处理用户输入
     * @param userInput 用户输入
     * @param systemPrompt 系统提示词（可选）
     * @param callback 流式输出回调
     * @return 最终回复
     */
    suspend fun processInput(
        userInput: String,
        systemPrompt: String? = null,
        requestUserId: String = "",
        accessibleSessionIds: List<String> = listOf(sessionId),
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit
    ): String {
        currentResponseReferences.clear()
        lastAgentResponse = null

        val effectiveSystemPrompt = withCitationGuidelines(systemPrompt ?: "你是 Silk，一个智能助手。")

        // 1. 添加用户消息到历史
        conversationHistory.add(Message(role = "user", content = userInput))

        // 2. 保存 system prompt
        val existingSystemIndex = conversationHistory.indexOfFirst { it.role == "system" }
        if (existingSystemIndex >= 0) {
            conversationHistory[existingSystemIndex] = Message(role = "system", content = effectiveSystemPrompt)
        } else {
            conversationHistory.add(0, Message(role = "system", content = effectiveSystemPrompt))
        }

        // 3. 调用 Claude CLI（纯聊天）
        val wrappedCallback: suspend (String, String, Boolean) -> Unit = { stepType, content, isComplete ->
            if (stepType == "complete" && isComplete) {
                val finalized = finalizeAgentResponse(content)
                lastAgentResponse = AgentResponse(content = finalized.content, references = finalized.references)
                callback(stepType, finalized.content, isComplete)
            } else {
                callback(stepType, content, isComplete)
            }
        }
        val rawResponse = chat(wrappedCallback)
        if (lastAgentResponse == null) {
            val finalized = finalizeAgentResponse(rawResponse)
            lastAgentResponse = AgentResponse(content = finalized.content, references = finalized.references)
        }
        return lastAgentResponse!!.content
    }

    private fun withCitationGuidelines(systemPrompt: String?): String {
        val base = systemPrompt ?: "你是 Silk，一个智能助手。"
        return buildString {
            appendLine(base)
            appendLine()
            appendLine("## 引用规则")
            appendLine("请使用以下引用格式：")
            appendLine("- 引用网络搜索结果时使用 [citation:数字]")
            appendLine("- 引用标记必须放在相关内容的句末或段末")
            appendLine("- 每个观点通常只需引用2-3个最相关的来源")
            appendLine("- 禁止堆砌大量引用标记")
            appendLine("- 只能基于证据回答，不要凭空捏造来源编号")
        }
    }

    private fun registerReference(
        kind: String,
        title: String,
        url: String? = null,
        snippet: String? = null,
        path: String? = null
    ): Int {
        val index = currentResponseReferences.size + 1
        currentResponseReferences.add(
            com.silk.backend.models.MessageReference(
                kind = kind,
                index = index,
                title = title,
                url = url,
                snippet = snippet,
                path = path
            )
        )
        return index
    }

    /**
     * 构建 prompt 并调用 claude CLI。
     * 将 conversationHistory 格式化为文本 prompt，包含 system + 对话轮次 + 当前消息。
     */
    private suspend fun chat(
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit
    ): String {
        if (!::claudeProcessClient.isInitialized) {
            val fallback = "backend/chat_workspaces/$sessionId"
            claudeProcessClient = ClaudeProcessClient(
                groupId = sessionId,
                workspaceDir = fallback,
            )
            java.io.File(fallback).mkdirs()
        }

        callback("thinking", "🤔 思考中...", false)

        // 将对话历史写入 chat_history.md，供 Claude 的 Grep/Read 工具搜索
        val historyFile = java.io.File(workspaceDir, "chat_history.md")
        historyFile.writeText(buildString {
            appendLine("# 群聊历史记录")
            appendLine()
            for (msg in conversationHistory) {
                if (msg.role == "system") continue
                when (msg.role) {
                    "user" -> appendLine("**User**: ${msg.content}")
                    "assistant" -> {
                        appendLine("**Assistant**: ${msg.content}")
                        appendLine()
                    }
                }
            }
        })

        // 构建工具/工作区上下文（两种路径共用）
        val toolContext = buildString {
            appendLine("## 可用工具")
            appendLine("你有以下工具可用：")
            appendLine("- **web_search**: 搜索互联网获取实时信息")
            appendLine("- **Grep**: 搜索工作区文件内容（包括 chat_history.md 中的历史消息）")
            appendLine("- **Read**: 读取工作区文件")
            appendLine("- **glob**: 查找工作区文件")
            appendLine()
            appendLine("群聊历史已保存到 `chat_history.md`，你可以用 Grep 搜索历史消息。")
        }

        val response = try {
            val apiKey = AIConfig.ANTHROPIC_API_KEY
            if (apiKey.isNotBlank()) {
                chatViaAnthropicApi(apiKey, toolContext, callback)
            } else {
                chatViaClaudeProcess(toolContext, callback)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("❌ [DirectModelAgent] AI 调用失败: ${e.message}")
            callback("error", "❌ AI 调用失败: ${e.message}", true)
            "抱歉，处理您的问题时发生了错误。"
        }

        // 保存回复到对话历史
        conversationHistory.add(Message(role = "assistant", content = response))
        callback("complete", response, true)
        return response
    }

    private suspend fun chatViaClaudeProcess(
        toolContext: String,
        callback: suspend (String, String, Boolean) -> Unit
    ): String {
        val fullPrompt = buildString {
            for (msg in conversationHistory) {
                when (msg.role) {
                    "system" -> {
                        appendLine(msg.content)
                        appendLine()
                    }
                    "user" -> {
                        appendLine("User: ${msg.content}")
                    }
                    "assistant" -> {
                        appendLine("Assistant: ${msg.content}")
                        appendLine()
                    }
                }
            }
            appendLine()
            appendLine(toolContext)
            appendLine()
            appendLine("Assistant:")
        }

        return claudeProcessClient.streamCompletion(
            fullPrompt = fullPrompt,
            callback = callback,
        )
    }

    private suspend fun chatViaAnthropicApi(
        apiKey: String,
        toolContext: String,
        callback: suspend (String, String, Boolean) -> Unit
    ): String {
        // 提取 system prompt 并与工具上下文合并
        val systemMessages = conversationHistory.filter { it.role == "system" }.mapNotNull { it.content }
        val mergedSystem = buildString {
            if (systemMessages.isNotEmpty()) {
                systemMessages.forEach { appendLine(it) }
                appendLine()
            }
            appendLine(toolContext)
        }
        val nonSystemMessages = conversationHistory.filter { it.role != "system" }

        val client = AnthropicClient(apiKey = apiKey)
        val result = client.streamCompletion(
            systemPrompt = mergedSystem.trim(),
            messages = nonSystemMessages,
            tools = null,
            callback = callback,
        )
        return result.content
    }

    // ── 引用处理 ──────────────────────────────────────────────────────

    private fun removeInvalidCitationMarkers(content: String): String {
        return content.replace(Regex("\\[(citation|available):(\\d+)\\]")) { match ->
            val kind = match.groupValues[1]
            val idx = match.groupValues[2].toIntOrNull() ?: 0
            val exists = currentResponseReferences.any { it.kind == kind && it.index == idx }
            if (exists) match.value else ""
        }
    }

    private fun ensureCitationMarkers(content: String): String {
        val cleaned = removeInvalidCitationMarkers(content)
        if (currentResponseReferences.isEmpty()) return cleaned
        if (Regex("\\[(citation|available):\\d+\\]").containsMatchIn(cleaned)) return cleaned

        val topRefs = currentResponseReferences.take(3)
        val markers = topRefs.joinToString(" ") { ref ->
            "[${ref.kind}:${ref.index}]"
        }
        return "$cleaned $markers"
    }

    private fun finalizeAgentResponse(content: String): FinalCitationResult {
        val withMarkers = ensureCitationMarkers(content)
        return normalizeCitedReferences(withMarkers)
    }

    private fun normalizeCitedReferences(content: String): FinalCitationResult {
        val citedPattern = Regex("\\[(citation|available):(\\d+)\\]")
        val citedKeys = citedPattern.findAll(content)
            .mapNotNull { match ->
                val kind = match.groupValues[1]
                val idx = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                "$kind:$idx"
            }
            .distinct()
            .toList()

        if (citedKeys.isEmpty()) {
            return FinalCitationResult(content, emptyList())
        }

        val citedRefs = citedKeys.mapNotNull { key ->
            val kind = key.substringBefore(":")
            val idx = key.substringAfter(":").toIntOrNull() ?: return@mapNotNull null
            currentResponseReferences.find { it.kind == kind && it.index == idx }
        }

        val reindexMap = mutableMapOf<String, Int>()
        val newRefs = mutableListOf<com.silk.backend.models.MessageReference>()
        var citationCounter = 0
        var availableCounter = 0

        for (ref in citedRefs) {
            val newIndex = if (ref.kind == "citation") {
                ++citationCounter
            } else {
                ++availableCounter
            }
            reindexMap["${ref.kind}:${ref.index}"] = newIndex
            newRefs.add(ref.copy(index = newIndex))
        }

        val newContent = citedPattern.replace(content) { match ->
            val kind = match.groupValues[1]
            val oldIdx = match.groupValues[2].toInt()
            val newIdx = reindexMap["$kind:$oldIdx"] ?: oldIdx
            "[$kind:$newIdx]"
        }

        return FinalCitationResult(newContent, newRefs)
    }

    // ── 测试辅助 ──────────────────────────────────────────────────────

    internal fun citationGuidelinesForTest(prompt: String): String = withCitationGuidelines(prompt)

    internal fun referencesForTest(): List<com.silk.backend.models.MessageReference> =
        currentResponseReferences.toList()

    internal fun resetReferencesForTest() {
        currentResponseReferences.clear()
    }

    internal fun ensureCitationMarkersForTest(content: String): String =
        ensureCitationMarkers(content)

    internal fun registerCitationForTest(title: String, url: String): Int =
        registerReference(kind = "citation", title = title, url = url)

    internal fun citedReferencesForTest(content: String): List<com.silk.backend.models.MessageReference> {
        val result = normalizeCitedReferences(content)
        return result.references
    }

    internal fun finalizeCitationsForTest(content: String): AgentResponse {
        val result = finalizeAgentResponse(content)
        return AgentResponse(content = result.content, references = result.references)
    }

    /**
     * 清空对话历史
     */
    fun clearHistory() {
        conversationHistory.clear()
    }

    /**
     * 获取对话历史
     */
    fun getHistory(): List<Message> {
        return conversationHistory.toList()
    }
}
