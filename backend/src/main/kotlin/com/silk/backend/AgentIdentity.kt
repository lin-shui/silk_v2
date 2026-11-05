package com.silk.backend

/**
 * AI 消息发送者识别：内建 Silk、dsh bot，以及历史 ACP agent（*_ai_agent）。
 */
object AgentIdentity {
    const val DSH_BOT_USER_ID = "dsh_ai_agent"

    fun isAgentUserId(userId: String): Boolean =
        userId.endsWith("_ai_agent") || userId == DSH_BOT_USER_ID || userId == SilkAgent.AGENT_ID

    fun isAgentMessage(userId: String): Boolean = isAgentUserId(userId)
}
