// backend/src/main/kotlin/com/silk/backend/agents/core/AgentSession.kt
package com.silk.backend.agents.core

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 工具权限模式。
 * - INTERACTIVE: 只自动放行读操作（Read/Glob/Grep 等），写和执行需用户确认
 * - ACCEPT_EDITS: 允许工作目录内的写操作（Write/Edit/NotebookEdit），执行仍需确认
 * - BYPASS: 全部放行
 */
enum class PermissionMode {
    INTERACTIVE,
    ACCEPT_EDITS,
    BYPASS,
}

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
    /** 工具权限模式，默认 INTERACTIVE（读操作放行，写/执行需确认） */
    @Volatile var permissionMode: PermissionMode = PermissionMode.INTERACTIVE,
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
