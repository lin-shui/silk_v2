// backend/src/main/kotlin/com/silk/backend/agents/core/AgentDescriptor.kt
package com.silk.backend.agents.core

import com.silk.backend.agents.acp.AcpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Adapter 要写的唯一东西。 */
interface AgentDescriptor {
    val agentType: String          // "claude-code"
    val displayName: String        // "🤖 Claude Code"
    val agentUserId: String        // 消息 userId，如 "claudecode_ai_agent"
    val triggerCommand: String     // "/cc"
    val aliases: List<String>      // ["cc"]

    /**
     * Adapter 处理专属命令。通用命令（/exit /cancel /new /cd /session /status /queue /help）
     * 由框架层统一处理，不会传给此方法。
     */
    suspend fun handleSilkCommand(
        cmd: SilkCommand,
        session: AgentSession,
        acp: AcpClient,
    ): SilkCommandResult

    /** initialize 时额外发给 agent 的参数（如 _silk 扩展声明）。 */
    fun extraInitializeParams(): JsonObject = buildJsonObject {}
}

/** 框架层识别的命令类型。 */
sealed class SilkCommand {
    data class Exit(val userId: String, val groupId: String) : SilkCommand()
    data class Cancel(val userId: String, val groupId: String) : SilkCommand()
    data class New(val userId: String, val groupId: String) : SilkCommand()
    data class Cd(val path: String) : SilkCommand()
    data class SessionList(val userId: String, val groupId: String) : SilkCommand()
    data class SessionLoad(val sessionIdPrefix: String) : SilkCommand()
    object Status : SilkCommand()
    data class Queue(val clear: Boolean = false) : SilkCommand()
    object Help : SilkCommand()
    data class Compact(val userId: String, val groupId: String) : SilkCommand()
    data class Unknown(val raw: String) : SilkCommand()
    data class Prompt(val text: String) : SilkCommand()
}

/** 命令处理结果。 */
sealed class SilkCommandResult {
    /** Adapter 已处理，框架层无需再动。 */
    object Handled : SilkCommandResult()

    /** 出错了，框架层广播错误消息。 */
    data class Error(val message: String) : SilkCommandResult()

    /** Adapter 不处理此命令，框架层按通用逻辑 fallback。 */
    object Fallback : SilkCommandResult()
}
