package com.silk.backend.ai

import com.silk.backend.UserWorkspaceManager
import org.slf4j.LoggerFactory

/**
 * 用户历史回忆 Agent。
 *
 * 在用户的 workspace 目录下启动 dontAsk 模式的 Claude CLI，
 * agent 可自主使用 Read/Grep/Glob 探索历史会话文件，
 * 基于历史信息回答用户问题。
 */
class UserHistoryAgent(
    private val workspaceManager: UserWorkspaceManager = UserWorkspaceManager()
) {
    private val logger = LoggerFactory.getLogger(UserHistoryAgent::class.java)

    /**
     * 查询用户历史会话。
     *
     * @param userId 用户 ID
     * @param userMessage 用户的问题
     * @param callback 流式回调 (stepType, content, isComplete)
     * @return 完整回复文本
     */
    suspend fun queryWithHistory(
        userId: String,
        userMessage: String,
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit,
    ): String {
        val workspace = workspaceManager.ensureWorkspace(userId)

        val historyClient = ClaudeProcessClient(
            groupId = "history_$userId",
            workspaceDir = workspace.toString(),
            permissionMode = "dontAsk",
        )

        val prompt = buildHistoryPrompt(userMessage)

        logger.info("UserHistoryAgent: userId={}, workspace={}, questionLen={}",
            userId, workspace, userMessage.length)

        val startMs = System.currentTimeMillis()
        val result = historyClient.streamCompletion(prompt, callback)
        val durationMs = System.currentTimeMillis() - startMs

        logger.debug("[IO] userId={}  duration={}ms\n>>> PROMPT ({} chars) >>>\n{}\n>>> RESPONSE ({} chars) >>>\n{}\n<<< END <<<",
            userId, durationMs, prompt.length, prompt, result.length, result)

        return result
    }

    private fun buildHistoryPrompt(userMessage: String): String = buildString {
        appendLine("你是 Silk 助手。你的工作目录下包含该用户所有历史会话记录。")
        appendLine()
        appendLine("## 工作目录结构")
        appendLine("- index.md: 群组列表索引（包含群名、类型、成员、消息数等摘要）")
        appendLine("- group_xxx/chat_history.json: 各群组的完整聊天记录（JSON 格式）")
        appendLine("- group_xxx/session.json: 会话元信息（成员、创建时间等）")
        appendLine()
        appendLine("## 操作指引")
        appendLine("1. 先读 index.md 了解有哪些群组及其摘要")
        appendLine("2. 根据摘要信息和用户问题，用 Grep 搜索相关关键词缩小范围")
        appendLine("3. 用 Read 读取具体内容（chat_history.json 可能很大，优先用 Grep 筛选）")
        appendLine("4. 基于找到的历史信息回答用户问题")
        appendLine("5. 回答时引用来源群组名称，方便用户定位")
        appendLine()
        appendLine("## 用户问题")
        appendLine(userMessage)
    }
}
