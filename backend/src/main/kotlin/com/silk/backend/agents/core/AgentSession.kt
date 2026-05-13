// backend/src/main/kotlin/com/silk/backend/agents/core/AgentSession.kt
package com.silk.backend.agents.core

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * per-(userId, groupId, agentType) 状态机。
 * 不含 workingDir —— cwd 在 GroupAgentContext 共享。
 */
class AgentSession(
    val userId: String,
    val groupId: String,
    val agentType: String,
    @Volatile var acpSessionId: String? = null,
    /** CLI 真实 session id（Claude session UUID / Codex thread_id，从 adapter 拿到，用于持久化 + 重启 resume） */
    @Volatile var cliSessionId: String? = null,
    @Volatile var running: Boolean = false,
    @Volatile var cancelled: Boolean = false,
    @Volatile var currentRequestId: String? = null,
    val messageQueue: ConcurrentLinkedDeque<QueuedMessage> = ConcurrentLinkedDeque(),
    val startedAt: Long = System.currentTimeMillis(),
    @Volatile var pendingQuestion: PendingQuestion? = null,
    /** The background coroutine running the current prompt + queue drain. */
    @Volatile var promptJob: Job? = null,
)

data class QueuedMessage(val text: String, val userId: String, val userName: String)

/**
 * Claude Code AskUserQuestion 的单个选项。
 */
data class QuestionOption(
    val label: String,
    val description: String = "",
)

/**
 * Claude Code AskUserQuestion 的单个问题（结构化）。
 */
data class StructuredQuestion(
    val question: String,
    val header: String = "",
    val options: List<QuestionOption> = emptyList(),
)

data class PendingQuestion(
    val requestId: String,
    /** 结构化问题列表 */
    val questions: List<StructuredQuestion>,
    /** 已回答的 questionIndex → 用户回答文本 */
    val answers: MutableMap<Int, String> = mutableMapOf(),
    val receivedAt: Long = System.currentTimeMillis(),
)
