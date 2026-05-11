// backend/src/main/kotlin/com/silk/backend/agents/core/CommandRouter.kt
package com.silk.backend.agents.core

object CommandRouter {

    sealed class RouteResult {
        /** /agents — 列出已注册 agent */
        object ListAgents : RouteResult()

        /** /use <agent> 或 /use none */
        data class UseAgent(val agentType: String?) : RouteResult()

        /** triggerCommand 匹配，如 /cc；inlineText 不为 null 时表示一次性切换+发送 */
        data class TriggerAgent(val agentType: String, val inlineText: String? = null) : RouteResult()

        /** @xxx <text> */
        data class AtAgent(val agentType: String, val remainingText: String) : RouteResult()

        /** slash 命令 */
        data class Command(val cmd: SilkCommand) : RouteResult()

        /** 普通 prompt，当前 agent 接收 */
        data class Prompt(val text: String) : RouteResult()

        /** 无当前 agent，放行给 ChatServer */
        object PassThrough : RouteResult()
    }

    fun route(
        text: String,
        userId: String,
        groupId: String,
        currentAgentType: String?,
    ): RouteResult {
        val trimmed = text.trim()

        // 1. /agents
        if (trimmed.lowercase() == "/agents") {
            return RouteResult.ListAgents
        }

        // 2. /use <agent> / /use none
        if (trimmed.lowercase().startsWith("/use ")) {
            val name = trimmed.substring(5).trim().lowercase()
            if (name == "none") return RouteResult.UseAgent(null)
            val descriptor = AgentRegistry.get(name)
            return if (descriptor != null) {
                RouteResult.UseAgent(descriptor.agentType)
            } else {
                RouteResult.Command(SilkCommand.Unknown("未找到 agent: $name"))
            }
        }

        // 3. triggerCommand 匹配（支持 `/cc` 单独触发，也支持 `/cc <text>` 切换+发送）
        AgentRegistry.list().forEach { d ->
            val trigger = d.triggerCommand.lowercase()
            val lower = trimmed.lowercase()
            if (lower == trigger) {
                return RouteResult.TriggerAgent(d.agentType)
            }
            if (lower.startsWith("$trigger ")) {
                val inline = trimmed.substring(d.triggerCommand.length).trim()
                return RouteResult.TriggerAgent(d.agentType, inlineText = inline.ifBlank { null })
            }
        }

        // 4. @xxx 一次性插队
        if (trimmed.startsWith("@")) {
            val parts = trimmed.substring(1).split(" ", limit = 2)
            val name = parts[0].lowercase()
            val remaining = if (parts.size > 1) parts[1] else ""
            val descriptor = AgentRegistry.get(name)
            if (descriptor != null) {
                return RouteResult.AtAgent(descriptor.agentType, remaining)
            }
            // 未注册的 @xxx 放行（普通提及）
        }

        // 5. 当前无 agent 指针 → 放行
        if (currentAgentType == null) {
            return RouteResult.PassThrough
        }

        // 6. slash 命令分发
        val lower = trimmed.lowercase()
        when {
            lower == "/exit" -> return RouteResult.Command(SilkCommand.Exit(userId, groupId))
            lower == "/cancel" -> return RouteResult.Command(SilkCommand.Cancel(userId, groupId))
            lower == "/new" -> return RouteResult.Command(SilkCommand.New(userId, groupId))
            lower == "/status" -> return RouteResult.Command(SilkCommand.Status)
            lower == "/queue" -> return RouteResult.Command(SilkCommand.Queue(false))
            lower == "/queue clear" -> return RouteResult.Command(SilkCommand.Queue(true))
            lower == "/help" -> return RouteResult.Command(SilkCommand.Help)
            lower == "/compact" -> return RouteResult.Command(SilkCommand.Compact(userId, groupId))
            lower == "/session" -> return RouteResult.Command(SilkCommand.SessionList(userId, groupId))
            lower.startsWith("/session ") -> {
                val prefix = trimmed.substring(9).trim()
                return RouteResult.Command(SilkCommand.SessionLoad(prefix))
            }
            lower.startsWith("/cd ") -> {
                val path = trimmed.substring(4).trim()
                return RouteResult.Command(SilkCommand.Cd(path))
            }
        }

        // 7. 普通 prompt
        return RouteResult.Prompt(trimmed)
    }
}
