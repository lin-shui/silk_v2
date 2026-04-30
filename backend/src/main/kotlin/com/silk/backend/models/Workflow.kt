package com.silk.backend.models

import kotlinx.serialization.Serializable

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
    /** 持久化的工作目录，用户在创建/更改目录时落库；后端重启后用它 seed CC state 的 workingDir，避免回退到 backend 进程 cwd。 */
    val workingDir: String = "",
    /** Bridge 上一次返回的 sessionId（complete/session_resumed/new_session 都会更新）。重启后据此发起 resume，使会话历史可续。 */
    val sessionId: String = "",
    /** 上一次的 sessionStarted 标记。true → 下次 executePrompt 会带 resume=true 让 bridge 续会话。 */
    val sessionStarted: Boolean = false,
)
