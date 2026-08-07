@file:Suppress("TooGenericExceptionCaught")

package com.silk.backend.git

import com.silk.backend.ai.AIConfig
import com.silk.backend.ai.AnthropicClient
import com.silk.backend.ai.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Optional event summarization. Raw GitHub cards remain authoritative if this fails. */
class GitEventSummaryService(
    private val clientFactory: () -> AnthropicClient = { AnthropicClient() },
    private val timeoutMs: Long = 20_000L,
) {
    suspend fun summarize(event: GitEventRecord): String? {
        if (!shouldSummarize(event) || AIConfig.ANTHROPIC_API_KEY.isBlank()) return null
        val prompt = buildPrompt(event)
        return withTimeoutOrNull(timeoutMs) {
            runCatching {
                withContext(Dispatchers.IO) {
                    clientFactory().streamCompletion(
                        systemPrompt = "你是 GitHub 事件摘要助手。只输出不超过 5 条中文要点，不要编造未提供的信息。",
                        messages = listOf(Message(role = "user", content = prompt)),
                        tools = null,
                    ) { _, _, _ -> }
                }.content.trim().take(MAX_SUMMARY_LENGTH).takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }

    internal fun shouldSummarize(event: GitEventRecord): Boolean =
        event.event == "pull_request" ||
            (event.event == "check_run" && FAILURE_CONCLUSIONS.any { event.summary.contains("conclusion=$it") })

    internal fun buildPrompt(event: GitEventRecord): String = buildString {
        appendLine("事件：${event.event}/${event.action}")
        appendLine("仓库：${event.repository.take(200)}")
        event.issueNumber?.let { appendLine("编号：$it") }
        appendLine("标题：${event.title.take(500)}")
        appendLine("原始摘要：${event.summary.take(500)}")
        if (event.htmlUrl.isNotBlank()) appendLine("链接：${event.htmlUrl.take(500)}")
        appendLine()
        appendLine("请说明这次变更或失败检查对开发者最重要的影响，并给出下一步建议。")
    }.take(MAX_PROMPT_LENGTH)

    private companion object {
        val FAILURE_CONCLUSIONS = setOf("failure", "timed_out", "cancelled")
        const val MAX_PROMPT_LENGTH = 2_000
        const val MAX_SUMMARY_LENGTH = 1_200
    }
}
