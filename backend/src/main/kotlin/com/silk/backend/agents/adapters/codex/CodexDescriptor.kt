package com.silk.backend.agents.adapters.codex

import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.core.AgentDescriptor
import com.silk.backend.agents.core.AgentSession
import com.silk.backend.agents.core.SilkCommand
import com.silk.backend.agents.core.SilkCommandResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object CodexDescriptor : AgentDescriptor {
    override val agentType = "codex"
    override val displayName = "🧠 Codex"
    override val agentUserId = "silk_ai_codex"
    override val triggerCommand = "/codex"
    override val aliases = listOf("codex")

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
            put("listLocalSessions", true)
            put("setCwd", true)
            put("listDir", true)
        })
    }
}
