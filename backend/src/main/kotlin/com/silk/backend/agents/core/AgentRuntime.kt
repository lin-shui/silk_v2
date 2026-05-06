// backend/src/main/kotlin/com/silk/backend/agents/core/AgentRuntime.kt
package com.silk.backend.agents.core

import com.silk.backend.Message
import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.acp.ContentBlock
import com.silk.backend.agents.acp.PermissionResponse
import com.silk.backend.agents.acp.StopReason
import com.silk.backend.agents.adapters.claudecode.ClaudeCodeDescriptor
import com.silk.backend.agents.adapters.codex.CodexDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 框架对外门面。
 */
object AgentRuntime {

    private val logger = LoggerFactory.getLogger(AgentRuntime::class.java)
    private val contexts = ConcurrentHashMap<String, GroupAgentContext>() // "${userId}_${groupId}"

    init {
        AgentRegistry.register(ClaudeCodeDescriptor)
        AgentRegistry.register(CodexDescriptor)
    }

    // ========== Workflow 持久化（Plan E2） ==========

    /**
     * Workflow 持久化回调：让 AgentRuntime 把 workingDir 和 cc_session_id 写回 WorkflowManager。
     * 由 [Application]/[configureRouting] 在启动时通过 [setWorkflowPersistence] 注入。
     */
    interface WorkflowPersistence {
        /** workingDir 变化时持久化（cdSync 成功后异步触发）。 */
        fun persistWorkingDir(rawGroupId: String, workingDir: String): Boolean
        /** Claude CLI session id 变化时持久化（prompt 完成后从 meta 拿到）。旧 API，per-workflow 单值。 */
        fun persistCcSession(rawGroupId: String, ccSessionId: String, sessionStarted: Boolean): Boolean
        /**
         * M4 Task 3: per-agent 持久化。agentType 是 runtime dash form。
         * 默认实现回退到旧的单值版本以保持 backward-compat。
         */
        fun persistCcSession(rawGroupId: String, agentType: String, ccSessionId: String, sessionStarted: Boolean): Boolean =
            persistCcSession(rawGroupId, ccSessionId, sessionStarted)
        /** M4 Task 3: 持久化用户当前激活的 agent（dash form）。 */
        fun persistActiveAgent(rawGroupId: String, agentType: String): Boolean = false
        /** 启动 / 首次激活时根据 workflow record 提供 seed；返回 null 表示不是 workflow 或无值可 seed。 */
        fun loadSeed(rawGroupId: String): WorkflowSeed?
        /**
         * M4 Task 3: per-agent seed。返回该 agent 自己的 ccSessionId。
         * 默认实现回退到旧的单值版本（向后兼容老 impl）。
         */
        fun loadSeed(rawGroupId: String, agentType: String): WorkflowSeed? = loadSeed(rawGroupId)
    }

    data class WorkflowSeed(
        val workingDir: String,
        val ccSessionId: String?,
        val sessionStarted: Boolean,
    )

    @Volatile
    private var persistence: WorkflowPersistence? = null

    fun setWorkflowPersistence(p: WorkflowPersistence) {
        persistence = p
    }

    /** group_xxx → xxx；非 group_ 前缀原样返回 */
    private fun stripGroupPrefix(groupId: String): String =
        if (groupId.startsWith("group_")) groupId.removePrefix("group_") else groupId

    /**
     * 把 ACP `session/prompt` response 里的 meta（adapter 携带的 cost/duration/turns/cc_sid）
     * 格式化成会话末尾的 "⏱ 费用: $X | 耗时: Xs | 轮次: N | 会话: XXXXXXXX..." 提示行。
     * 字段缺失或为零值时跳过；都为空返回空串。
     */
    private fun formatPromptMeta(
        meta: kotlinx.serialization.json.JsonElement,
        ccSessionId: String?,
    ): String {
        val obj = runCatching { meta.jsonObject }.getOrNull() ?: return ""
        val parts = mutableListOf<String>()
        val cost = obj["costUsd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val duration = obj["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L
        val turns = obj["numTurns"]?.jsonPrimitive?.intOrNull ?: 0
        val sid = obj["ccSessionId"]?.jsonPrimitive?.contentOrNull ?: ccSessionId.orEmpty()
        if (cost > 0) parts.add("费用: ${"$"}%.4f".format(cost))
        if (duration > 0) parts.add("耗时: %.1fs".format(duration / 1000.0))
        if (turns > 0) parts.add("轮次: $turns")
        if (sid.isNotBlank()) parts.add("会话: ${sid.take(8)}...")
        val joined = parts.joinToString(" | ")
        return if (joined.isNotBlank()) "⏱ $joined" else ""
    }

    /** 异步持久化 workingDir（不阻塞调用方）。 */
    private fun persistWorkingDirAsync(groupId: String, workingDir: String) {
        val p = persistence ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try { p.persistWorkingDir(stripGroupPrefix(groupId), workingDir) }
            catch (e: Exception) { logger.warn("[AgentRuntime] 持久化 workingDir 失败: {}", e.message) }
        }
    }

    /** 异步持久化 ccSessionId / sessionStarted（per-agent，M4 Task 3）。 */
    private fun persistCcSessionAsync(groupId: String, agentType: String, ccSessionId: String, started: Boolean) {
        val p = persistence ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try { p.persistCcSession(stripGroupPrefix(groupId), agentType, ccSessionId, started) }
            catch (e: Exception) { logger.warn("[AgentRuntime] 持久化 ccSessionId 失败: {}", e.message) }
        }
    }

    /** 异步持久化用户当前激活的 agent（M4 Task 3）。 */
    private fun persistActiveAgentAsync(groupId: String, agentType: String) {
        val p = persistence ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try { p.persistActiveAgent(stripGroupPrefix(groupId), agentType) }
            catch (e: Exception) { logger.warn("[AgentRuntime] 持久化 activeAgent 失败: {}", e.message) }
        }
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
                if (!route.inlineText.isNullOrBlank()) {
                    handlePrompt(ctx, route.inlineText, userId, userName, broadcastFn)
                }
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
        val session = ctx.getOrCreateSession(agentType)
        // 优先从 WorkflowPersistence 加载该 agent 的 per-agent seed
        val seed = try {
            persistence?.loadSeed(stripGroupPrefix(groupId), agentType)
        } catch (e: Exception) {
            logger.warn("[AgentRuntime] loadSeed 失败: {}", e.message)
            null
        }
        if (seed != null) {
            if (seed.workingDir.isNotBlank()) ctx.workingDir = seed.workingDir
            if (!seed.ccSessionId.isNullOrBlank() && seed.sessionStarted) {
                session.ccSessionId = seed.ccSessionId
            }
        }
        logger.info(
            "[AgentRuntime] 工作流自动激活: userId={}, groupId={}, agentType={}, workingDir={}, ccSeed={}",
            userId, groupId, agentType, ctx.workingDir, seed?.ccSessionId?.take(8) ?: "-"
        )
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
        // M4 Task 3: per-agent loadSeed —— 取该 agent 自己的 ccSessionId，
        // 不再受其他 agent 干扰。同时持久化 activeAgent 让重启后保持选择。
        val session = ctx.getOrCreateSession(agentType)
        val seed = try {
            persistence?.loadSeed(stripGroupPrefix(ctx.groupId), agentType)
        } catch (e: Exception) {
            logger.warn("[AgentRuntime] /use {} loadSeed 失败: {}", agentType, e.message)
            null
        }
        if (seed != null && !seed.ccSessionId.isNullOrBlank() && seed.sessionStarted) {
            session.ccSessionId = seed.ccSessionId
            logger.info(
                "[AgentRuntime] /use {} 加载 seed: ccSessionId={}",
                agentType, seed.ccSessionId.take(8),
            )
        }
        persistActiveAgentAsync(ctx.groupId, agentType)
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
        // 触发命令时重置 session（和旧 /cc 行为一致：开新会话）
        session.acpSessionId = null
        session.ccSessionId = null
        session.running = false
        session.cancelled = false
        session.messageQueue.clear()
        persistCcSessionAsync(ctx.groupId, agentType, "", false)
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
            is CommandRouter.RouteResult.Prompt -> handlePrompt(ctx, route.text, userId, userName, broadcastFn, overrideAgentType = agentType)
            else -> handlePrompt(ctx, remainingText, userId, userName, broadcastFn, overrideAgentType = agentType)
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
                session.ccSessionId = null
                session.running = false
                session.cancelled = false
                session.messageQueue.clear()
                // 清除已持久化的 ccSessionId，让重启后不会盲目 resume 一个废 session（与 cdSync 行为一致）
                persistCcSessionAsync(ctx.groupId, agentType, "", false)
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
                val acp = getAcpClient(agentType, session.userId)
                if (acp == null) {
                    broadcastFn(AgentMessages.system(
                        "${descriptor.displayName} 未连接，无法加载会话",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ))
                } else {
                    try {
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            acp.sessionLoad(cmd.sessionIdPrefix, ctx.workingDir)
                        }
                        // adapter 返回的 ACP UUID 是后端用来发 session/prompt 的句柄；
                        // 用户输入的本地 session id（codex thread_id / claude session_id）走 ccSessionId
                        // 槽位，下次 prompt 时 adapter 会用它真正 resume 历史会话。
                        session.acpSessionId = result.sessionId
                        session.ccSessionId = cmd.sessionIdPrefix
                        broadcastFn(AgentMessages.system(
                            "已加载会话: ${cmd.sessionIdPrefix.take(8)}...",
                            agentUserId = descriptor.agentUserId,
                            agentName = descriptor.displayName,
                        ))
                    } catch (e: com.silk.backend.agents.acp.AcpRpcException) {
                        broadcastFn(AgentMessages.system(
                            "加载会话失败: ${e.rpcError.message}",
                            agentUserId = descriptor.agentUserId,
                            agentName = descriptor.displayName,
                        ))
                    } catch (e: Exception) {
                        logger.warn("[AgentRuntime] sessionLoad 异常: {}", e.message)
                        broadcastFn(AgentMessages.system(
                            "加载会话异常: ${e.message}",
                            agentUserId = descriptor.agentUserId,
                            agentName = descriptor.displayName,
                        ))
                    }
                }
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
        overrideAgentType: String? = null,
    ) {
        val agentType = overrideAgentType ?: ctx.currentAgentType ?: return
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
        val accumulated = StringBuilder()
        setupAcpHandlers(acp, session, descriptor, broadcastFn, accumulated)

        // 首次 prompt 需要 sessionNew（带 ccSessionId seed 让 adapter 续旧 CC session）
        if (session.acpSessionId == null) {
            try {
                val result = acp.sessionNew(
                    cwd = ctx.workingDir,
                    ccSessionId = session.ccSessionId,
                )
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

            // 从 result.meta 拿 ccSessionId 持久化（adapter complete.meta.sessionId → response.meta.ccSessionId）
            val metaCcSid = result.meta?.let { meta ->
                runCatching {
                    meta.jsonObject["ccSessionId"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }
            if (!metaCcSid.isNullOrBlank()) {
                session.ccSessionId = metaCcSid
                persistCcSessionAsync(ctx.groupId, agentType, metaCcSid, true)
            }

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

            // 广播 meta 信息（费用/耗时/轮次/会话 id），与旧路径行为一致
            val metaStr = result.meta?.let { formatPromptMeta(it, session.ccSessionId) }
            if (!metaStr.isNullOrBlank()) {
                broadcastFn(AgentMessages.system(
                    metaStr,
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ))
            }

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
        accumulated: StringBuilder,
    ) {
        acp.onSessionUpdate { notif ->
            if (notif.sessionId != session.acpSessionId) return@onSessionUpdate
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
            // 默认自动 approve
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

    // ========== Plan D proxy API ==========

    data class AgentStateSnapshot(
        val active: Boolean,
        val running: Boolean,
        val workingDir: String,
        val agentType: String?,
    )

    fun snapshotState(userId: String, groupId: String): AgentStateSnapshot? {
        val ctx = contexts[key(userId, groupId)] ?: return null
        val agentType = ctx.currentAgentType
        val session = if (agentType != null) ctx.sessions[agentType] else null
        return AgentStateSnapshot(
            active = agentType != null,
            running = session?.running ?: false,
            workingDir = ctx.workingDir,
            agentType = agentType,
        )
    }

    // ========== Plan E2: filesystem ops via ACP ==========

    sealed class CdResult {
        data class Ok(val resolvedPath: String) : CdResult()
        data class Err(val reason: String) : CdResult()
    }

    /**
     * 走 ACP `_silk/set_cwd` 切换工作目录。
     * 行为约束：
     *  - 任务运行中 → 拒绝（让用户先 /cancel）
     *  - ACP bridge 未连接 → 返回 Err
     *  - 没有 ACP session 时先 sessionNew（adapter 用 sessionId 索引 cwd）
     *  - 成功后 ctx.workingDir 取 adapter 返回的 resolved path
     *  - 切目录会让 adapter 把 cc_session_id reset，本地 acpSessionId 也重置（下次 prompt 重建）
     */
    suspend fun cdSync(
        userId: String,
        groupId: String,
        path: String,
        agentType: String = "claude-code",
    ): CdResult {
        val ctx = context(userId, groupId)
        val existingSession = ctx.sessions[agentType]
        if (existingSession?.running == true) {
            return CdResult.Err("任务运行中，请先 /cancel 再 /cd")
        }
        val acp = AcpRegistry.get(userId, agentType)
            ?: return CdResult.Err("ACP Bridge 未连接")

        // adapter 端用 sessionId 索引 cwd；如果还没有 ACP session 就先建一个
        val session = ctx.getOrCreateSession(agentType)
        var acpSessionId = session.acpSessionId
        if (acpSessionId == null) {
            try {
                val newSession = acp.sessionNew(cwd = path)
                acpSessionId = newSession.sessionId
                session.acpSessionId = acpSessionId
            } catch (e: Exception) {
                logger.warn("[AgentRuntime] cdSync 创建 ACP session 失败: {}", e.message)
                return CdResult.Err("创建 ACP session 失败: ${e.message}")
            }
        }

        return try {
            val resp = AcpExtensions.setCwd(acp, acpSessionId!!, path)
            // adapter 返回 {ok: true, path: <resolved>}
            val resolvedPath = resp.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: path
            ctx.workingDir = resolvedPath
            // adapter 已经把它的 cc_session_id 设为 null；本地也重置 acpSessionId + ccSessionId
            // 让下次 prompt 走 sessionNew 重建（与旧 cdSync 重置 sessionId 行为对齐）
            session.acpSessionId = null
            session.ccSessionId = null
            persistWorkingDirAsync(groupId, resolvedPath)
            // 切目录等价于 /new：清空已持久化的 ccSessionId 让重启不会盲目 resume 一个废 session
            persistCcSessionAsync(groupId, agentType, "", false)
            logger.info("[AgentRuntime] cdSync 成功: userId={}, groupId={}, path={}", userId, groupId, resolvedPath)
            CdResult.Ok(resolvedPath)
        } catch (e: com.silk.backend.agents.acp.AcpRpcException) {
            CdResult.Err(e.rpcError.message)
        } catch (e: Exception) {
            logger.warn("[AgentRuntime] cdSync 异常: {}", e.message)
            CdResult.Err("set_cwd 异常: ${e.message}")
        }
    }

    /**
     * 走 ACP `_silk/list_dir` 列出指定路径下的子目录。
     * @return adapter 的 raw JSON（含 success/path/parent/segments/separator/entries/truncated/error）；
     *   ACP 不可用时返回 null（让 caller 决定是否回退到旧桥）。
     */
    suspend fun listDirectory(
        userId: String,
        path: String?,
        showHidden: Boolean,
        agentType: String = "claude-code",
    ): kotlinx.serialization.json.JsonObject? {
        val acp = AcpRegistry.get(userId, agentType) ?: return null
        return try {
            val resp = AcpExtensions.listDir(acp, path ?: "", showHidden)
            resp.jsonObject
        } catch (e: com.silk.backend.agents.acp.AcpRpcException) {
            logger.warn("[AgentRuntime] _silk/list_dir 失败: {}", e.rpcError.message)
            null
        } catch (e: Exception) {
            logger.warn("[AgentRuntime] _silk/list_dir 异常: {}", e.message)
            null
        }
    }

    fun cleanupState(userId: String, groupId: String) {
        contexts.remove(key(userId, groupId))
    }

    /** 仅供测试使用 */
    internal fun clearForTest() {
        contexts.clear()
    }
}
