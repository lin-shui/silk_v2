// backend/src/main/kotlin/com/silk/backend/agents/adapters/claudecode/ClaudeCodeDescriptor.kt
package com.silk.backend.agents.adapters.claudecode

import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.core.AgentDescriptor
import com.silk.backend.agents.core.AgentSession
import com.silk.backend.agents.core.SilkCommand
import com.silk.backend.agents.core.SilkCommandResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ClaudeCodeDescriptor : AgentDescriptor {
    override val agentType = "claude-code"
    override val displayName = "🤖 Claude Code"
    override val agentUserId = "claudecode_ai_agent"
    override val triggerCommand = "/cc"
    override val aliases = listOf("cc")

    override suspend fun handleSilkCommand(
        cmd: SilkCommand,
        session: AgentSession,
        acp: AcpClient,
    ): SilkCommandResult {
        return when (cmd) {
            is SilkCommand.Compact -> {
                // Framework 会处理 compact；这里只是声明支持
                SilkCommandResult.Fallback
            }
            else -> SilkCommandResult.Fallback
        }
    }

    override fun extraInitializeParams(): JsonObject = buildJsonObject {
        put("_silk", buildJsonObject {
            put("compact", true)
            put("listLocalSessions", true)
            put("setCwd", true)
        })
    }
}
