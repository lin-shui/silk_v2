// backend/src/main/kotlin/com/silk/backend/agents/core/AgentRuntime.kt
package com.silk.backend.agents.core

import com.silk.backend.Message
import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.acp.ContentBlock
import com.silk.backend.agents.acp.PermissionResponse
import com.silk.backend.agents.acp.StopReason
import com.silk.backend.agents.adapters.claudecode.ClaudeCodeDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 框架对外门面。签名兼容旧 ClaudeCodeManager 的 5 个公共方法。
 */
object AgentRuntime {

    private val logger = LoggerFactory.getLogger(AgentRuntime::class.java)
    private val contexts = ConcurrentHashMap<String, GroupAgentContext>() // "${userId}_${groupId}"

    init {
        AgentRegistry.register(ClaudeCodeDescriptor)
    }

    // ========== 公共 API（5 个方法） ==========

    /** 列出所有已注册的 agent descriptor。 */
    fun listRegisteredAgents(): List<AgentDescriptor> = AgentRegistry.list()

    /** 判断某条消息是否来自某个 agent（用于 WebSocketConfig 的 AGENT_ID 过滤）。 */
    fun isAgentMessage(msg: Message): Boolean {
        return AgentRegistry.list().any { it.agentUserId == msg.userId }
    }

    /**
     * 被 ChatServer.broadcast() 调用。
     * 返回 true 表示消息已被 agent 框架处理，ChatServer 不应再走 Silk AI 逻辑。
     */
    suspend fun handleIfActive(
        userId: String,
        groupId: String,
        text: String,
        userName: String,
        broadcastFn: suspend (Message) -> Unit,
    ): Boolean {
        val ctx = context(userId, groupId)
        val route = CommandRouter.route(text, userId, groupId, ctx.currentAgentType)

        return when (route) {
            is CommandRouter.RouteResult.ListAgents -> {
                handleListAgents(broadcastFn)
                true
            }
            is CommandRouter.RouteResult.UseAgent -> {
                handleUseAgent(ctx, route.agentType, broadcastFn)
                true
            }
            is CommandRouter.RouteResult.TriggerAgent -> {
                handleTriggerAgent(ctx, route.agentType, broadcastFn)
                true
            }
            is CommandRouter.RouteResult.AtAgent -> {
                handleAtAgent(ctx, route.agentType, route.remainingText, userId, userName, broadcastFn)
                true
            }
            is CommandRouter.RouteResult.Command -> {
                handleCommand(ctx, route.cmd, broadcastFn)
                true
            }
            is CommandRouter.RouteResult.Prompt -> {
                handlePrompt(ctx, route.text, userId, userName, broadcastFn)
                true
            }
            is CommandRouter.RouteResult.PassThrough -> {
                false
            }
        }
    }

    /**
     * 被 ChatServer.handleStopGeneration() 调用。
     * 只取消 currentAgentType 的 running 任务。
     */
    suspend fun cancelIfActive(
        userId: String,
        groupId: String,
        broadcastFn: suspend (Message) -> Unit,
    ): Boolean {
        val ctx = context(userId, groupId)
        val agentType = ctx.currentAgentType ?: return false
        val session = ctx.sessions[agentType] ?: return false
        if (!session.running) return false

        handleCancel(session, broadcastFn)
        return true
    }

    /**
     * 工作流自动激活 agent（静默）。
     * Plan B 无持久化，只设 currentAgentType。
     */
    fun autoActivateForWorkflow(userId: String, groupId: String, agentType: String) {
        val ctx = context(userId, groupId)
        ctx.currentAgentType = agentType
        ctx.getOrCreateSession(agentType)
        logger.info("[AgentRuntime] 工作流自动激活: userId={}, groupId={}, agentType={}", userId, groupId, agentType)
    }

    /** Bridge 断线时由 Routing.kt 调用 */
    fun handleAgentDisconnect(userId: String, agentType: String) {
        for ((_, ctx) in contexts) {
            if (ctx.userId != userId) continue
            val session = ctx.sessions[agentType] ?: continue

            if (session.running) {
                session.running = false
                session.cancelled = false
                logger.info(
                    "[AgentRuntime] Bridge 断线，agent 任务已终止: userId={}, groupId={}, agentType={}",
                    userId, ctx.groupId, agentType
                )
            }
            session.acpSessionId = null
        }
    }

    // ========== 内部方法 ==========

    private fun key(userId: String, groupId: String) = "${userId}_${groupId}"

    private fun context(userId: String, groupId: String): GroupAgentContext {
        return contexts.getOrPut(key(userId, groupId)) {
            GroupAgentContext(userId = userId, groupId = groupId)
        }
    }

    private suspend fun handleListAgents(broadcastFn: suspend (Message) -> Unit) {
        val agents = AgentRegistry.list().joinToString("\n") {
            "- ${it.displayName} (`${it.agentType}`) — 激活: `${it.triggerCommand}`"
        }
        val msg = AgentMessages.system(
            "已注册的 Agent:\n$agents\n\n使用 `/use <agent>` 切换当前 agent，或使用 `@<agent>` 一次性插队。",
            agentUserId = "silk_system",
            agentName = "System",
        )
        broadcastFn(msg)
    }

    private suspend fun handleUseAgent(
        ctx: GroupAgentContext,
        agentType: String?,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        if (agentType == null) {
            ctx.currentAgentType = null
            broadcastFn(AgentMessages.system(
                "已退出 agent 模式，回到普通 Silk AI。",
                agentUserId = "silk_system",
                agentName = "System",
            ))
            return
        }
        val descriptor = AgentRegistry.getByType(agentType) ?: return
        ctx.currentAgentType = agentType
        broadcastFn(AgentMessages.system(
            "已切换到 ${descriptor.displayName}。",
            agentUserId = descriptor.agentUserId,
            agentName = descriptor.displayName,
        ))
    }

    private suspend fun handleTriggerAgent(
        ctx: GroupAgentContext,
        agentType: String,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        val descriptor = AgentRegistry.getByType(agentType) ?: return
        ctx.currentAgentType = agentType
        val session = ctx.getOrCreateSession(agentType)
        // 触发命令时重置 session（和旧 /cc 行为一致）
        session.acpSessionId = null
        session.running = false
        session.cancelled = false
        session.messageQueue.clear()
        broadcastFn(AgentMessages.system(
            "${descriptor.displayName} 已激活\n发送消息开始对话，/help 查看命令，/exit 退出",
            agentUserId = descriptor.agentUserId,
            agentName = descriptor.displayName,
        ))
    }

    private suspend fun handleAtAgent(
        ctx: GroupAgentContext,
        agentType: String,
        remainingText: String,
        userId: String,
        userName: String,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        val descriptor = AgentRegistry.getByType(agentType) ?: return

        if (remainingText.isBlank()) {
            // @agent 没内容，只显示状态
            broadcastFn(AgentMessages.status(
                "${descriptor.displayName} 就绪",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
            ))
            return
        }

        // 解析 remainingText 中的 slash 命令
        val route = CommandRouter.route(remainingText, userId, ctx.groupId, agentType)
        when (route) {
            is CommandRouter.RouteResult.Command -> handleCommand(ctx, route.cmd, broadcastFn)
            is CommandRouter.RouteResult.Prompt -> handlePrompt(ctx, route.text, userId, userName, broadcastFn)
            else -> handlePrompt(ctx, remainingText, userId, userName, broadcastFn)
        }
    }

    private suspend fun handleCommand(
        ctx: GroupAgentContext,
        cmd: SilkCommand,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        val agentType = ctx.currentAgentType ?: return
        val descriptor = AgentRegistry.getByType(agentType) ?: return
        val session = ctx.getOrCreateSession(agentType)

        when (cmd) {
            is SilkCommand.Exit -> {
                ctx.removeSession(agentType)
                if (ctx.sessions.isEmpty()) {
                    ctx.currentAgentType = null
                }
                broadcastFn(AgentMessages.system(
                    "已退出 ${descriptor.displayName} 模式",
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ))
            }
            is SilkCommand.Cancel -> handleCancel(session, broadcastFn)
            is SilkCommand.New -> {
                session.acpSessionId = null
                session.running = false
                session.cancelled = false
                session.messageQueue.clear()
                broadcastFn(AgentMessages.system(
                    "已开启新会话",
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ))
            }
            is SilkCommand.Cd -> {
                ctx.workingDir = cmd.path
                broadcastFn(AgentMessages.system(
                    "工作目录已切换: ${cmd.path}",
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ))
            }
            is SilkCommand.Status -> {
                val status = buildString {
                    appendLine("Agent: ${descriptor.displayName}")
                    appendLine("运行中: ${session.running}")
                    appendLine("队列: ${session.messageQueue.size} 条")
                    appendLine("工作目录: ${ctx.workingDir}")
                    append("ACP Session: ${session.acpSessionId ?: "未创建"}")
                }
                broadcastFn(AgentMessages.status(
                    status,
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ))
            }
            is SilkCommand.Queue -> {
                if (cmd.clear) {
                    session.messageQueue.clear()
                    broadcastFn(AgentMessages.system(
                        "队列已清空",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ))
                } else {
                    val items = session.messageQueue.mapIndexed { i, q ->
                        "${i + 1}. ${q.text.take(40)}${if (q.text.length > 40) "..." else ""}"
                    }.joinToString("\n")
                    val msg = if (items.isEmpty()) "队列为空" else "队列 (${session.messageQueue.size} 条):\n$items"
                    broadcastFn(AgentMessages.status(
                        msg,
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ))
                }
            }
            is SilkCommand.Help -> {
                val help = buildString {
                    appendLine("${descriptor.displayName} 命令:")
                    appendLine("/exit — 退出当前 agent")
                    appendLine("/cancel — 取消当前任务")
                    appendLine("/new — 开启新会话")
                    appendLine("/cd <path> — 切换工作目录")
                    appendLine("/session — 列出本地会话")
                    appendLine("/session <id> — 恢复会话")
                    appendLine("/status — 查看状态")
                    appendLine("/queue — 查看队列")
                    appendLine("/queue clear — 清空队列")
                    appendLine("/compact — 压缩当前会话")
                    appendLine("/help — 显示此帮助")
                    appendLine("@<agent> <text> — 向指定 agent 发送消息（不改当前 agent）")
                }
                broadcastFn(AgentMessages.system(
                    help,
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ))
            }
            is SilkCommand.Compact -> {
                val acp = getAcpClient(agentType, session.userId)
                if (acp != null && session.acpSessionId != null) {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.silk.backend.agents.core.AcpExtensions.compact(acp, session.acpSessionId!!)
                        }
                        broadcastFn(AgentMessages.system(
                            "会话已压缩",
                            agentUserId = descriptor.agentUserId,
                            agentName = descriptor.displayName,
                        ))
                    } catch (e: Exception) {
                        broadcastFn(AgentMessages.system(
                            "压缩失败: ${e.message}",
                            agentUserId = descriptor.agentUserId,
                            agentName = descriptor.displayName,
                        ))
                    }
                } else {
                    broadcastFn(AgentMessages.system(
                        "compact 需要 bridge 连接",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ))
                }
            }
            is SilkCommand.SessionList -> {
                val acp = getAcpClient(agentType, session.userId)
                if (acp != null) {
                    try {
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.silk.backend.agents.core.AcpExtensions.listLocalSessions(acp)
                        }
                        broadcastFn(AgentMessages.status(
                            result.toString(),
                            agentUserId = descriptor.agentUserId,
                            agentName = descriptor.displayName,
                        ))
                    } catch (e: Exception) {
                        broadcastFn(AgentMessages.status(
                            "获取会话列表失败: ${e.message}",
                            agentUserId = descriptor.agentUserId,
                            agentName = descriptor.displayName,
                        ))
                    }
                } else {
                    broadcastFn(AgentMessages.status(
                        "本地会话列表（待 bridge 连接后拉取）",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ))
                }
            }
            is SilkCommand.SessionLoad -> {
                session.acpSessionId = cmd.sessionIdPrefix
                broadcastFn(AgentMessages.system(
                    "已设置会话 ID: ${cmd.sessionIdPrefix}",
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ))
            }
            is SilkCommand.Unknown -> {
                broadcastFn(AgentMessages.system(
                    "未知命令: ${cmd.raw}",
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ))
            }
            else -> {
                // 尝试让 adapter 处理
                val acp = getAcpClient(agentType, userId = session.userId) ?: return
                val result = descriptor.handleSilkCommand(cmd, session, acp)
                when (result) {
                    is SilkCommandResult.Error -> broadcastFn(AgentMessages.system(
                        result.message,
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ))
                    else -> { /* Handled or Fallback, nothing to do */ }
                }
            }
        }
    }

    private suspend fun handlePrompt(
        ctx: GroupAgentContext,
        text: String,
        userId: String,
        userName: String,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        val agentType = ctx.currentAgentType ?: return
        val descriptor = AgentRegistry.getByType(agentType) ?: return
        val session = ctx.getOrCreateSession(agentType)
        val acp = getAcpClient(agentType, userId)

        if (acp == null) {
            broadcastFn(AgentMessages.system(
                "${descriptor.displayName} 未连接。请启动 Bridge Agent。",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
            ))
            return
        }

        if (session.running) {
            session.messageQueue.add(QueuedMessage(text, userId, userName))
            broadcastFn(AgentMessages.status(
                "任务运行中，消息已加入队列 (${session.messageQueue.size} 条)",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
                stableId = "agent_queue_${agentType}",
            ))
            return
        }

        // 设置 running 并注册 ACP 处理器
        session.running = true
        session.cancelled = false
        setupAcpHandlers(acp, session, descriptor, broadcastFn)

        val accumulated = StringBuilder()

        // 首次 prompt 需要 sessionNew
        if (session.acpSessionId == null) {
            try {
                val result = acp.sessionNew(cwd = ctx.workingDir)
                session.acpSessionId = result.sessionId
            } catch (e: Exception) {
                logger.error("[AgentRuntime] sessionNew 失败: userId={}, agentType={}", userId, agentType, e)
                session.running = false
                broadcastFn(AgentMessages.system(
                    "创建会话失败: ${e.message}",
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ))
                return
            }
        }

        // 发送 prompt
        val requestId = UUID.randomUUID().toString()
        session.currentRequestId = requestId

        try {
            val result = acp.sessionPrompt(
                sessionId = session.acpSessionId!!,
                prompt = listOf(ContentBlock.Text(text)),
            )

            // prompt 完成处理
            when (result.stopReason) {
                StopReason.END_TURN -> {
                    if (accumulated.isNotEmpty()) {
                        broadcastFn(AgentMessages.final(
                            accumulated.toString(),
                            agentUserId = descriptor.agentUserId,
                            agentName = descriptor.displayName,
                        ))
                    }
                }
                StopReason.MAX_TOKENS -> {
                    broadcastFn(AgentMessages.system(
                        "⚠️ 达到最大 token 限制",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ))
                }
                StopReason.REFUSAL -> {
                    broadcastFn(AgentMessages.system(
                        "请求被拒绝",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ))
                }
                StopReason.CANCELLED -> {
                    // cancel 已在 handleCancel 中处理
                }
            }

            // 清除状态消息 + 处理队列
            broadcastFn(AgentMessages.status(
                "CLEAR_STATUS",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
            ))

        } catch (e: Exception) {
            logger.error("[AgentRuntime] sessionPrompt 失败: userId={}, agentType={}", userId, agentType, e)
            broadcastFn(AgentMessages.system(
                "执行失败: ${e.message}",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
            ))
        } finally {
            session.running = false
            session.currentRequestId = null
            finishQueue(ctx, session, broadcastFn)
        }
    }

    private suspend fun handleCancel(
        session: AgentSession,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        val descriptor = AgentRegistry.getByType(session.agentType) ?: return
        if (!session.running) {
            broadcastFn(AgentMessages.status(
                "没有运行中的任务",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
            ))
            return
        }

        val acp = getAcpClient(session.agentType, session.userId)
        if (acp != null && session.acpSessionId != null) {
            try {
                acp.sessionCancel(session.acpSessionId!!)
            } catch (e: Exception) {
                logger.warn("[AgentRuntime] sessionCancel 失败: {}", e.message)
            }
        }

        session.running = false
        session.cancelled = true
        broadcastFn(AgentMessages.status(
            "任务已取消",
            agentUserId = descriptor.agentUserId,
            agentName = descriptor.displayName,
        ))
    }

    private suspend fun finishQueue(
        ctx: GroupAgentContext,
        session: AgentSession,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        val next = session.messageQueue.pollFirst() ?: return
        handlePrompt(ctx, next.text, next.userId, next.userName, broadcastFn)
    }

    private fun setupAcpHandlers(
        acp: AcpClient,
        session: AgentSession,
        descriptor: AgentDescriptor,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        acp.onSessionUpdate { notif ->
            if (notif.sessionId != session.acpSessionId) return@onSessionUpdate
            val accumulated = StringBuilder()
            val msg = AcpUpdateMapper.map(
                update = notif.update,
                descriptor = descriptor,
                agentType = session.agentType,
                accumulated = accumulated,
            )
            if (msg != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    broadcastFn(msg)
                }
            }
        }

        acp.onPermissionRequest { _ ->
            // 默认自动 approve（和旧 ClaudeCodeManager 行为一致）
            PermissionResponse(
                outcome = buildJsonObject {
                    put("kind", "selected")
                    put("optionId", "approve")
                }
            )
        }
    }

    private fun getAcpClient(agentType: String, userId: String): AcpClient? {
        return AcpRegistry.get(userId, agentType)
    }

    /** 仅供测试使用 */
    internal fun clearForTest() {
        contexts.clear()
    }
}
