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
        routeUseAgent(trimmed)?.let { return it }

        // 3. triggerCommand 匹配（支持 `/cc` 单独触发，也支持 `/cc <text>` 切换+发送）
        routeTriggerCommand(trimmed)?.let { return it }

        // 4. @xxx 一次性插队（未注册的 @xxx 放行 → null 继续）
        routeAtAgent(trimmed)?.let { return it }

        // 5. 当前无 agent 指针 → 放行
        if (currentAgentType == null) {
            return RouteResult.PassThrough
        }

        // 6. slash 命令分发
        routeSlashCommand(trimmed, userId, groupId)?.let { return it }

        // 7. 普通 prompt
        return RouteResult.Prompt(trimmed)
    }

    /** 解析 `/use <agent>` / `/use none`；非 /use 前缀返回 null。 */
    private fun routeUseAgent(trimmed: String): RouteResult? {
        if (!trimmed.lowercase().startsWith("/use ")) return null
        val name = trimmed.substring(5).trim().lowercase()
        if (name == "none") return RouteResult.UseAgent(null)
        val descriptor = AgentRegistry.get(name)
        return if (descriptor != null) {
            RouteResult.UseAgent(descriptor.agentType)
        } else {
            RouteResult.Command(SilkCommand.Unknown("未找到 agent: $name"))
        }
    }

    /** 解析 triggerCommand（`/cc` 或 `/cc <text>`）；无匹配返回 null。 */
    private fun routeTriggerCommand(trimmed: String): RouteResult? {
        val lower = trimmed.lowercase()
        AgentRegistry.list().forEach { d ->
            val trigger = d.triggerCommand.lowercase()
            if (lower == trigger) {
                return RouteResult.TriggerAgent(d.agentType)
            }
            if (lower.startsWith("$trigger ")) {
                val inline = trimmed.substring(d.triggerCommand.length).trim()
                return RouteResult.TriggerAgent(d.agentType, inlineText = inline.ifBlank { null })
            }
        }
        return null
    }

    /** 解析 `@xxx <text>`；非 @ 前缀或未注册 agent 返回 null（放行为普通提及）。 */
    private fun routeAtAgent(trimmed: String): RouteResult? {
        if (!trimmed.startsWith("@")) return null
        val parts = trimmed.substring(1).split(" ", limit = 2)
        val name = parts[0].lowercase()
        val remaining = if (parts.size > 1) parts[1] else ""
        val descriptor = AgentRegistry.get(name) ?: return null
        return RouteResult.AtAgent(descriptor.agentType, remaining)
    }

    /** 解析 slash 命令；无匹配返回 null。 */
    private fun routeSlashCommand(trimmed: String, userId: String, groupId: String): RouteResult? {
        val lower = trimmed.lowercase()
        return when {
            lower == "/exit" -> RouteResult.Command(SilkCommand.Exit(userId, groupId))
            lower == "/cancel" -> RouteResult.Command(SilkCommand.Cancel(userId, groupId))
            lower == "/new" -> RouteResult.Command(SilkCommand.New(userId, groupId))
            lower == "/status" -> RouteResult.Command(SilkCommand.Status)
            lower == "/queue" -> RouteResult.Command(SilkCommand.Queue(false))
            lower == "/queue clear" -> RouteResult.Command(SilkCommand.Queue(true))
            lower == "/help" -> RouteResult.Command(SilkCommand.Help)
            lower == "/compact" -> RouteResult.Command(SilkCommand.Compact(userId, groupId))
            lower == "/session" -> RouteResult.Command(SilkCommand.SessionList(userId, groupId))
            lower.startsWith("/session ") -> RouteResult.Command(SilkCommand.SessionLoad(trimmed.substring(9).trim()))
            lower.startsWith("/cd ") -> RouteResult.Command(SilkCommand.Cd(trimmed.substring(4).trim()))
            else -> null
        }
    }
}
