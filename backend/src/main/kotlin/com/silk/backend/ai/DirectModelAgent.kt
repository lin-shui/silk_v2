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

    data class AvailableReferenceSeed(
        val title: String,
        val snippet: String? = null,
        val path: String? = null,
        val origin: String? = null,
        val reason: String? = null,
        val spaceId: String? = null,
        val spaceLabel: String? = null,
    )

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
        availableReferences: List<AvailableReferenceSeed> = emptyList(),
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit
    ): String {
        currentResponseReferences.clear()
        lastAgentResponse = null

        // 注册用户提供的本地知识库引用（用于 [available:N] 引用解析）
        availableReferences.forEach { ref ->
            registerReference(kind = "available", title = ref.title, snippet = ref.snippet, path = ref.path, origin = ref.origin, reason = ref.reason)
        }

        val now = java.time.LocalDateTime.now()
        val chineseFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm")
        val isoFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        
        // 调试日志：每次调用均记录注入的时间，排查"时间停留"问题
        logger.info("[processInput] 注入系统时间: {} (epochMs={})",
            now.format(isoFormatter), java.time.Instant.now().toEpochMilli())

        val effectiveSystemPrompt = buildString {
            appendLine("## 当前日期和时间（系统精确注入）")
            appendLine("当前日期：${now.format(chineseFormatter)}")
            appendLine("ISO 格式：${now.format(isoFormatter)}")
            appendLine("⚠️ 你必须使用上述精确时间回答所有时间/日期相关问题，不得自行推理、猜测或根据训练数据推断。如果用户问\"今天\"、\"现在\"、\"星期几\"等，必须以上述注入的时间为准。")
            appendLine("⚠️ 注意：对话历史中你之前的回答可能包含旧的过期时间，那些时间已失效。每次都要以本系统提示开头的注入时间为准，不要引用自己之前回答中的时间。")
            appendLine()
            appendLine(withCitationGuidelines(systemPrompt ?: "你是 Silk，一个智能助手。"))
        }

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
        val rawResponse = chat(wrappedCallback, accessibleSessionIds)
        if (lastAgentResponse == null) {
            val finalized = finalizeAgentResponse(rawResponse)
            lastAgentResponse = AgentResponse(content = finalized.content, references = finalized.references)
        }
        logger.info("[processInput] refs={}, contentHasCitation={}, contentLen={}",
            lastAgentResponse!!.references.size,
            lastAgentResponse!!.content.contains("[citation:"),
            lastAgentResponse!!.content.length)
        logger.info("[processInput] content_preview: {}",
            lastAgentResponse!!.content.take(300).replace('\n', ' '))
        logger.info("[processInput] content_tail: {}",
            lastAgentResponse!!.content.takeLast(400).replace('\n', ' '))
        return lastAgentResponse!!.content
    }

    private fun withCitationGuidelines(systemPrompt: String?): String {
        val base = systemPrompt ?: "你是 Silk，一个智能助手。"
        return buildString {
            appendLine(base)
            appendLine()
            appendLine("## 网络搜索规则（必须遵守）")
            appendLine("对于天气、新闻、实时数据、股价、赛事等时效性信息，你必须使用 web_search 工具获取最新结果，不能仅凭训练数据回答。")
            appendLine()
            appendLine("## 引用规则（必须遵守）")
            appendLine("当你使用网络搜索获取信息后，必须在回答中标注信息来源：")
            appendLine("- 引用网络搜索结果时，在相关内容末尾添加 [citation:数字]")
            appendLine("- 第一个搜索结果的引用编号为 [citation:1]，第二个为 [citation:2]，以此类推")
            appendLine("- 每个重要观点都必须标注来源引用，不能遗漏")
            appendLine("- 如果你没有使用网络搜索，则不需要添加引用标记")
            appendLine()
            appendLine("## 参考来源列表（必须遵守）")
            appendLine("当你使用了网络搜索，必须在回答末尾附上完整的参考来源列表，格式如下：")
            appendLine("参考来源:")
            appendLine("1. [来源标题](完整URL)")
            appendLine("2. [来源标题](完整URL)")
            appendLine("- 必须列出所有引用来源，编号与正文中的 [citation:数字] 一一对应")
            appendLine("- URL 必须是完整的 https:// 链接，不能省略或截断")
            appendLine("- 如果没有使用网络搜索，则不需要添加参考来源列表")
        }
    }

    private fun registerReference(
        kind: String,
        title: String,
        url: String? = null,
        snippet: String? = null,
        path: String? = null,
        origin: String? = null,
        reason: String? = null,
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
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit,
        accessibleSessionIds: List<String> = listOf(sessionId)
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

        // 如果是 Silk 专属对话（有多个 accessibleSession），写入其他群的历史供 AI 参考
        writeOtherGroupsHistories(accessibleSessionIds)

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

        // 工作区文件同步：确保已解析的文件在工作区可用
        syncExtractedFilesToWorkspace()

        // 构建工具/工作区上下文（两种路径共用）
        val otherGroupsDir = java.io.File(workspaceDir, "other_groups")
        val hasOtherGroups = otherGroupsDir.exists() && otherGroupsDir.listFiles()?.isNotEmpty() == true

        val toolContext = buildString {
            appendLine("## 可用工具")
            appendLine("你有以下工具可用：")
            appendLine("- **web_search**: 搜索互联网获取实时信息")
            appendLine("- **Grep**: 搜索工作区文件内容（包括 chat_history.md 中的历史消息）")
            appendLine("- **Read**: 读取工作区文件")
            appendLine("- **glob**: 查找工作区文件")
            appendLine()
            appendLine("群聊历史已保存到 `chat_history.md`，你可以用 Grep 搜索历史消息。")

            if (hasOtherGroups) {
                appendLine()
                appendLine("## 跨群聊访问权限")
                appendLine("当前是 **Silk 专属对话**，你有权访问该用户**所有群聊**的历史记录。")
                appendLine("其他群聊的聊天记录已分别保存到 `other_groups/` 目录下，文件名格式为 `chat_history_<群名>.md`。")
                appendLine("你可以使用 `Grep` 或 `Read` 工具搜索或读取这些文件来获取其他群聊中的信息。")
            }

            val manifestFile = java.io.File(workspaceDir, "files_manifest.md")
            if (manifestFile.exists()) {
                appendLine()
                appendLine("## 已上传的文件")
                appendLine("工作区中有用户上传的文件，已自动提取为文本：")
                appendLine("- 使用 `Read` 工具读取 `files_manifest.md` 查看文件清单")
                appendLine("- 使用 `Read` 工具读取 `<文件名>.extracted.md` 查看具体文件内容")
                appendLine("- 用户提到文件相关问题时，主动读取对应的 .extracted.md 文件")
            }
            // 追加日期提醒（扁平文本路径下冗余提醒，提高遵从率）
            appendLine()
            appendLine("⚠️ 再次提醒：当前真实日期和时间已在系统指令开头给出，回答时间/日期相关问题时必须使用该信息，不得自行猜测或推算。")
        }

        val response = try {
            // 优先使用 Claude CLI（内置 web_search、Grep、Read、glob 工具，原生支持 [citation:N] 引用）
            try {
                chatViaClaudeProcess(toolContext, callback)
            } catch (e: Exception) {
                logger.warn("⚠️ [DirectModelAgent] Claude CLI 调用失败，回退到 API 路径: ${e.message}")
                val apiKey = AIConfig.ANTHROPIC_API_KEY
                if (apiKey.isNotBlank()) {
                    chatViaAnthropicApi(apiKey, toolContext, callback)
                } else {
                    throw e
                }
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

    /**
     * 将用户其他群聊的聊天历史写入工作区，供 AI 搜索参考。
     * 每次调用都会重新写入，确保包含最新的消息。
     */
    private fun writeOtherGroupsHistories(accessibleSessionIds: List<String>) {
        if (accessibleSessionIds.size <= 1) return // 仅当前会话，无需额外写入
        if (workspaceDir.isBlank()) return

        logger.info("📋 写入其他群聊历史: sessionId={}, accessibleCount={}, ids={}",
            sessionId, accessibleSessionIds.size, accessibleSessionIds)

        val otherGroupsDir = java.io.File(workspaceDir, "other_groups")
        val chatHistoryBaseDir = System.getProperty("silk.chatHistoryDir")?.trim()?.takeIf { it.isNotEmpty() } ?: "chat_history"
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        for (sessionName in accessibleSessionIds) {
            if (sessionName == sessionId) continue // 跳过当前群

            val groupId = sessionName.removePrefix("group_")
            val group = com.silk.backend.database.GroupRepository.findGroupById(groupId)
            val groupDisplayName = group?.name ?: groupId
            // 中文等 Unicode 字符直接保留在文件名中，仅过滤 Windows 不安全字符
            val safeFileName = groupDisplayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
            val targetFile = java.io.File(otherGroupsDir, "chat_history_${safeFileName}.md")

            // 读取 chat_history.json
            val historyFile = java.io.File(java.io.File(chatHistoryBaseDir, sessionName), "chat_history.json")
            if (!historyFile.exists()) continue

            try {
                val content = historyFile.readText()
                if (content.isBlank()) continue

                val chatHistory = json.decodeFromString<com.silk.backend.models.ChatHistory>(content)
                if (chatHistory.messages.isEmpty()) continue

                // 每次都重新写入，确保包含最新消息
                targetFile.parentFile.mkdirs()
                targetFile.writeText(buildString {
                    appendLine("# 群聊记录：$groupDisplayName")
                    appendLine()
                    for (msg in chatHistory.messages.takeLast(50)) {
                        if (msg.messageType != "TEXT") continue
                        val ts = java.time.Instant.ofEpochMilli(msg.timestamp)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDateTime()
                            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                        appendLine("- **${msg.senderName}** ($ts): ${msg.content.replace('\n', ' ').take(500)}")
                    }
                })
                logger.debug("已刷新其他群聊历史: {} ({} 条消息)", groupDisplayName, chatHistory.messages.size)
            } catch (e: Exception) {
                logger.warn("⚠️ 读取群聊历史失败 [{}]: {}", sessionName, e.message)
            }
        }
    }

    private fun syncExtractedFilesToWorkspace() {
        if (workspaceDir.isBlank()) return
        try {
            val sessionName = if (sessionId.startsWith("group_")) sessionId else "group_$sessionId"
            val chatHistoryDir = System.getProperty("silk.chatHistoryDir")?.trim()?.takeIf { it.isNotEmpty() } ?: "chat_history"
            val uploadsDir = java.io.File(java.io.File(chatHistoryDir, sessionName), "uploads")
            if (uploadsDir.exists()) {
                FilePreprocessor.syncAllToWorkspace(uploadsDir, workspaceDir)
            }
        } catch (e: Exception) {
            logger.warn("工作区文件同步失败: {}", e.message)
        }
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
            // 钉入当前时间：放在对话尾部 Assistant 前，确保 Claude 无论如何都看到
            val tsNow = java.time.LocalDateTime.now()
            val tsFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            appendLine("## 当前时间（尾部再次注入，必须以此为准）")
            appendLine("现在时刻：${tsNow.format(tsFmt)}")
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

        // web_search 是 Anthropic 服务端工具，Claude 自行调用并处理搜索结果
        val tools = listOf(
            Tool(
                type = "function",
                function = ToolDefinition(
                    name = "web_search",
                    description = "搜索互联网获取实时信息。当用户询问实时信息、最新新闻、你不知道的内容，或者需要外部数据来回答问题时使用。",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "搜索关键词")
                            })
                        })
                        put("required", buildJsonArray { add("query") })
                    }
                )
            )
        )

        val result = client.streamCompletion(
            systemPrompt = mergedSystem.trim(),
            messages = nonSystemMessages,
            tools = tools,
            callback = callback,
        )

        // 从 Anthropic 响应中提取服务端 web_search 返回的引用元数据
        val citationCount = result.citations?.size ?: 0
        result.citations?.forEach { cit ->
            registerReference("citation", cit.title, cit.url, cit.text)
        }

        logger.info("[chatViaAnthropicApi] result: content_len={}, citations={}, toolCalls={}",
            result.content.length, citationCount, result.toolCalls?.size ?: 0)
        // 记录回复的前 300 字，便于调试引用内容
        if (result.content.isNotEmpty()) {
            logger.info("[chatViaAnthropicApi] content_preview: {}",
                result.content.take(300).replace('\n', ' '))
        }

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
        if (currentResponseReferences.isEmpty()) {
            // 没有注册引用时：citation 标记来自 Anthropic 服务端 web_search，始终保留
            // 只清理 available 标记（旧代码注入的本地文件引用，无引用时无效）
            return content.replace(Regex("\\[available:\\d+\\]"), "")
        }
        val cleaned = removeInvalidCitationMarkers(content)
        if (Regex("\\[(citation|available):\\d+\\]").containsMatchIn(cleaned)) return cleaned

        val topRefs = currentResponseReferences.take(3)
        val markers = topRefs.joinToString(" ") { ref ->
            "[${ref.kind}:${ref.index}]"
        }
        return "$cleaned $markers"
    }

    private fun extractSourcesSection(content: String): Pair<String, List<com.silk.backend.models.MessageReference>> {
        val sectionRegex = Regex("""\n*(Sources|参考来源):\s*\n[\s\S]*$""")
        val match = sectionRegex.find(content) ?: return content to emptyList()

        val sectionText = match.value
        val cleanContent = content.substring(0, match.range.first).trimEnd()

        val linkRegex = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
        val refs = linkRegex.findAll(sectionText).mapIndexed { index, linkMatch ->
            com.silk.backend.models.MessageReference(
                kind = "citation",
                index = index + 1,
                title = linkMatch.groupValues[1],
                url = linkMatch.groupValues[2],
                snippet = null,
                path = null
            )
        }.toList()

        return cleanContent to refs
    }

    private fun finalizeAgentResponse(content: String): FinalCitationResult {
        // 从正文末尾提取 "Sources:" / "参考来源:" 节，避免前端重复渲染
        val (cleanedContent, sourcesRefs) = extractSourcesSection(content)
        // 注册来源节中的 URL 引用（去重）
        for (ref in sourcesRefs) {
            if (currentResponseReferences.none { it.url == ref.url && it.title == ref.title }) {
                currentResponseReferences.add(ref)
            }
        }

        // 回退：从全文提取 Markdown 链接作为备用 URL 来源（适用于模型未输出独立来源节的情况）
        if (currentResponseReferences.none { it.url != null || it.path != null }) {
            val bodyLinkRegex = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
            for (linkMatch in bodyLinkRegex.findAll(cleanedContent)) {
                val title = linkMatch.groupValues[1]
                val url = linkMatch.groupValues[2]
                if (url.isNotBlank() && currentResponseReferences.none { it.url == url }) {
                    currentResponseReferences.add(
                        com.silk.backend.models.MessageReference(
                            kind = "citation",
                            index = currentResponseReferences.size + 1,
                            title = title,
                            url = url,
                            snippet = null,
                            path = null
                        )
                    )
                }
            }
        }

        val hasRefs = currentResponseReferences.isNotEmpty() && currentResponseReferences.any { it.url != null || it.path != null }
        if (!hasRefs) {
            val hasCitationMarkers = Regex("\\[citation:\\d+\\]").containsMatchIn(cleanedContent)
            if (hasCitationMarkers) {
                val result = normalizeCitedReferences(cleanedContent)
                // 无 URL → 保留标记在正文中（前端渲染为可读的引用编号），引用列表不丢
                //（stripCitationMarkers 仅在 references 有 URL 时才剥离，保证点击链接着陆页干净）
                if (result.references.any { it.url != null || it.path != null }) {
                    return FinalCitationResult(
                        stripCitationMarkers(result.content),
                        result.references
                    )
                }
                return FinalCitationResult(result.content, result.references)
            }
            // 无任何引用标记 → 清理 available 标记后返回
            val stripped = cleanedContent.replace(Regex("\\[(citation|available):\\d+\\]"), "")
            return FinalCitationResult(stripped, currentResponseReferences.toList())
        }
        val withMarkers = ensureCitationMarkers(cleanedContent)
        val result = normalizeCitedReferences(withMarkers)
        // references 有 URL → 剥离标记，前端用引用列表渲染可点击链接
        return FinalCitationResult(
            stripCitationMarkers(result.content),
            result.references
        )
    }

    private fun stripCitationMarkers(content: String): String {
        return content.replace(Regex("\\[(citation|available):\\d+\\]"), "")
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
            // 文本中没有引用标记但有搜索结果的，仍然返回 references 供前端展示来源列表
            if (currentResponseReferences.isNotEmpty()) {
                return FinalCitationResult(content, currentResponseReferences.toList())
            }
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

        // 先处理有元数据的引用
        for (ref in citedRefs) {
            val newIndex = if (ref.kind == "citation") {
                ++citationCounter
            } else {
                ++availableCounter
            }
            reindexMap["${ref.kind}:${ref.index}"] = newIndex
            newRefs.add(ref.copy(index = newIndex))
        }

        // 对文本中有标记但无对应元数据的（如 Claude CLI 输出的 [citation:N]），创建占位引用
        for (key in citedKeys) {
            if (reindexMap.containsKey(key)) continue
            val kind = key.substringBefore(":")
            val idx = key.substringAfter(":").toIntOrNull() ?: continue
            val newIndex = if (kind == "citation" || kind == "available") {
                if (kind == "citation") ++citationCounter else ++availableCounter
            } else continue
            reindexMap[key] = newIndex
            newRefs.add(
                com.silk.backend.models.MessageReference(
                    kind = kind,
                    index = newIndex,
                    title = "${if (kind == "citation") "来源" else "资料"} $idx",
                    snippet = null,
                    url = null,
                    path = null
                )
            )
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
