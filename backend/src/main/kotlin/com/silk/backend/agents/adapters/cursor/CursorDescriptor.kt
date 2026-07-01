// backend/src/main/kotlin/com/silk/backend/agents/adapters/cursor/CursorDescriptor.kt
package com.silk.backend.agents.adapters.cursor

import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.core.AgentDescriptor
import com.silk.backend.agents.core.AgentSession
import com.silk.backend.agents.core.SilkCommand
import com.silk.backend.agents.core.SilkCommandResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object CursorDescriptor : AgentDescriptor {
    override val agentType = "cursor"
    override val displayName = "\uD83D\uDDB1\uFE0F Cursor"
    override val agentUserId = "cursor_ai_agent"
    override val triggerCommand = "/cursor"
    override val aliases = listOf("cursor")

    override suspend fun handleSilkCommand(
        cmd: SilkCommand,
        session: AgentSession,
        acp: AcpClient,
    ): SilkCommandResult {
        return SilkCommandResult.Fallback
    }

    override fun extraInitializeParams(): JsonObject = buildJsonObject {
        put("_silk", buildJsonObject {
            put("compact", false)
            put("listLocalSessions", false)
            put("setCwd", true)
            put("listDir", true)
        })
    }
}
