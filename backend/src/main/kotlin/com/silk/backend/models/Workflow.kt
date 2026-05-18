package com.silk.backend.models

import kotlinx.serialization.Serializable

/**
 * 单个 agent 的 per-workflow 会话状态（M4 Plan Task 3）。
 * - sessionId: 该 agent 上一次返回的 cliSessionId（让重启后的 prompt 能 resume 该 agent 的旧线程）
 * - sessionStarted: true → 下次 prompt 带 resume=true
 *
 * 与 Workflow.sessionId / sessionStarted 的关系：旧字段为 backward-compat 保留；
 * agentSessions 是 per-agent 多线程持久化，避免在多 agent 间切换时互相覆盖。
 */
@Serializable
data class AgentSessionState(
    val sessionId: String = "",
    val sessionStarted: Boolean = false,
)

@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val description: String = "",
    val ownerId: String,
    val groupId: String = "",
    val agentType: String = "claude_code",
    /** Silk Chat 工作流的任务焦点：shopping / ride / general（仅 agentType=silk_chat 时有效） */
    val taskFocus: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    /** 持久化的工作目录，用户在创建/更改目录时落库；后端重启后用它 seed agent state 的 workingDir，避免回退到 backend 进程 cwd。 */
    val workingDir: String = "",
    /** Bridge 上一次返回的 sessionId（complete/session_resumed/new_session 都会更新）。重启后据此发起 resume，使会话历史可续。 */
    val sessionId: String = "",
    /** 上一次的 sessionStarted 标记。true → 下次 executePrompt 会带 resume=true 让 bridge 续会话。 */
    val sessionStarted: Boolean = false,
    /**
     * M4 Task 3: 用户当前激活的 agent（runtime 的 dash form，例如 "claude-code" / "codex"）。
     * 空串 → 回落到 agentType（underscore form 需要 normalize）。
     * /use 切换时由 AgentRuntime.handleUseAgent 持久化。
     */
    val activeAgent: String = "",
    /**
     * M4 Task 3: per-agent 会话状态。Key 是 runtime 的 agentType（dash form）。
     * 让多 agent 互相切换时彼此的 resume 状态独立保留。
     */
    val agentSessions: Map<String, AgentSessionState> = emptyMap(),
    /** 工具权限模式：INTERACTIVE / ACCEPT_EDITS / BYPASS。空串 → 默认 INTERACTIVE。 */
    val permissionMode: String = "",
)
