package com.silk.backend.agents.core

import com.silk.backend.Message
import com.silk.backend.SilkAgent
import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.acp.AcpRpcException
import com.silk.backend.agents.acp.ContentBlock
import com.silk.backend.agents.acp.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

internal suspend fun agentRuntimeHandleIfActive(
    userId: String,
    groupId: String,
    text: String,
    userName: String,
    broadcastFn: suspend (Message) -> Unit,
): Boolean {
    val ctx = agentRuntimeContext(userId, groupId)
    val route = CommandRouter.route(text, userId, groupId, ctx.currentAgentType)
    return when (route) {
        is CommandRouter.RouteResult.ListAgents -> {
            agentRuntimeHandleListAgents(broadcastFn)
            true
        }
        is CommandRouter.RouteResult.UseAgent -> {
            agentRuntimeHandleUseAgent(ctx, route.agentType, broadcastFn)
            true
        }
        is CommandRouter.RouteResult.TriggerAgent -> {
            agentRuntimeHandleTriggerAgent(ctx, route.agentType, broadcastFn)
            if (!route.inlineText.isNullOrBlank()) {
                agentRuntimeHandlePrompt(ctx, route.inlineText, userId, userName, broadcastFn)
            }
            true
        }
        is CommandRouter.RouteResult.AtAgent -> {
            agentRuntimeHandleAtAgent(ctx, route.agentType, route.remainingText, userId, userName, broadcastFn)
            true
        }
        is CommandRouter.RouteResult.Command -> {
            agentRuntimeHandleCommand(ctx, route.cmd, broadcastFn)
            true
        }
        is CommandRouter.RouteResult.Prompt -> {
            agentRuntimeHandlePrompt(ctx, route.text, userId, userName, broadcastFn)
            true
        }
        is CommandRouter.RouteResult.PassThrough -> false
    }
}

internal suspend fun agentRuntimeCancelIfActive(
    userId: String,
    groupId: String,
    broadcastFn: suspend (Message) -> Unit,
): Boolean {
    val ctx = agentRuntimeContext(userId, groupId)
    val agentType = ctx.currentAgentType ?: return false
    val session = ctx.sessions[agentType] ?: return false
    if (!session.running) return false
    agentRuntimeHandleCancel(session, broadcastFn)
    return true
}

private suspend fun agentRuntimeHandleListAgents(broadcastFn: suspend (Message) -> Unit) {
    val agents = AgentRegistry.list().joinToString("\n") {
        "- ${it.displayName} (`${it.agentType}`) — 激活: `${it.triggerCommand}`"
    }
    broadcastFn(
        AgentMessages.system(
            "已注册的 Agent:\n$agents\n\n使用 `/use <agent>` 切换当前 agent，或使用 `@<agent>` 一次性插队。",
            agentUserId = SilkAgent.AGENT_ID,
            agentName = SilkAgent.AGENT_NAME,
        ),
    )
}

private suspend fun agentRuntimeHandleUseAgent(
    ctx: GroupAgentContext,
    agentType: String?,
    broadcastFn: suspend (Message) -> Unit,
) {
    if (agentType == null) {
        ctx.currentAgentType = null
        broadcastFn(
            AgentMessages.system(
                "已退出 agent 模式，回到普通 Silk AI。",
                agentUserId = SilkAgent.AGENT_ID,
                agentName = SilkAgent.AGENT_NAME,
            ),
        )
        return
    }
    val descriptor = AgentRegistry.getByType(agentType) ?: return
    ctx.currentAgentType = agentType
    val session = ctx.getOrCreateSession(agentType)
    val seed = runAgentCatching {
        agentRuntimePersistence?.loadSeed(stripGroupPrefix(ctx.groupId), agentType)
    }.onFailure { e ->
        agentRuntimeLogger.warn("[AgentRuntime] /use {} loadSeed 失败: {}", agentType, e.message)
    }.getOrNull()
    if (seed != null && !seed.cliSessionId.isNullOrBlank() && seed.sessionStarted) {
        session.cliSessionId = seed.cliSessionId
        agentRuntimeLogger.info("[AgentRuntime] /use {} 加载 seed: cliSessionId={}", agentType, seed.cliSessionId.take(8))
    }
    persistActiveAgentAsync(ctx.groupId, agentType)
    broadcastFn(agentRuntimeSystemMessage("已切换到 ${descriptor.displayName}。", descriptor))
}

private suspend fun agentRuntimeHandleTriggerAgent(
    ctx: GroupAgentContext,
    agentType: String,
    broadcastFn: suspend (Message) -> Unit,
) {
    val descriptor = AgentRegistry.getByType(agentType) ?: return
    ctx.currentAgentType = agentType
    val session = ctx.getOrCreateSession(agentType)
    agentRuntimeCleanupSessionHandlers(session)
    session.acpSessionId = null
    session.cliSessionId = null
    session.running = false
    session.cancelled = false
    session.messageQueue.clear()
    persistCliSessionAsync(ctx.groupId, agentType, "", false)
    broadcastFn(
        AgentMessages.system(
            "${descriptor.displayName} 已激活\n发送消息开始对话，/help 查看命令，/exit 退出",
            agentUserId = descriptor.agentUserId,
            agentName = descriptor.displayName,
        ),
    )
}

private suspend fun agentRuntimeHandleAtAgent(
    ctx: GroupAgentContext,
    agentType: String,
    remainingText: String,
    userId: String,
    userName: String,
    broadcastFn: suspend (Message) -> Unit,
) {
    val descriptor = AgentRegistry.getByType(agentType) ?: return
    if (remainingText.isBlank()) {
        broadcastFn(
            AgentMessages.status(
                "${descriptor.displayName} 就绪",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
            ),
        )
        return
    }
    when (val route = CommandRouter.route(remainingText, userId, ctx.groupId, agentType)) {
        is CommandRouter.RouteResult.Command -> agentRuntimeHandleCommand(ctx, route.cmd, broadcastFn)
        is CommandRouter.RouteResult.Prompt -> {
            agentRuntimeHandlePrompt(ctx, route.text, userId, userName, broadcastFn, overrideAgentType = agentType)
        }
        else -> agentRuntimeHandlePrompt(ctx, remainingText, userId, userName, broadcastFn, overrideAgentType = agentType)
    }
}

private suspend fun agentRuntimeHandleCommand(
    ctx: GroupAgentContext,
    cmd: SilkCommand,
    broadcastFn: suspend (Message) -> Unit,
) {
    val agentType = ctx.currentAgentType ?: return
    val descriptor = AgentRegistry.getByType(agentType) ?: return
    val session = ctx.getOrCreateSession(agentType)
    if (agentRuntimeHandleLifecycleCommand(ctx, cmd, agentType, session, descriptor, broadcastFn)) return
    if (agentRuntimeHandleUtilityCommand(ctx, cmd, agentType, session, descriptor, broadcastFn)) return
    if (cmd is SilkCommand.Unknown) {
        broadcastFn(agentRuntimeSystemMessage("未知命令: ${cmd.raw}", descriptor))
        return
    }
    agentRuntimeHandleDescriptorCommand(cmd, agentType, session, descriptor, broadcastFn)
}

private suspend fun agentRuntimeHandleLifecycleCommand(
    ctx: GroupAgentContext,
    cmd: SilkCommand,
    agentType: String,
    session: AgentSession,
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
): Boolean = when (cmd) {
    is SilkCommand.Exit -> {
        agentRuntimeHandleExitCommand(ctx, session, agentType, descriptor, broadcastFn)
        true
    }
    is SilkCommand.Cancel -> {
        agentRuntimeHandleCancel(session, broadcastFn)
        true
    }
    is SilkCommand.New -> {
        agentRuntimeHandleNewCommand(ctx, session, agentType, descriptor, broadcastFn)
        true
    }
    is SilkCommand.Cd -> {
        ctx.workingDir = cmd.path
        broadcastFn(agentRuntimeSystemMessage("工作目录已切换: ${cmd.path}", descriptor))
        true
    }
    else -> false
}

private suspend fun agentRuntimeHandleUtilityCommand(
    ctx: GroupAgentContext,
    cmd: SilkCommand,
    agentType: String,
    session: AgentSession,
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
): Boolean = when (cmd) {
    is SilkCommand.Status -> {
        agentRuntimeHandleStatusCommand(ctx, session, descriptor, broadcastFn)
        true
    }
    is SilkCommand.Queue -> {
        agentRuntimeHandleQueueCommand(cmd, session, descriptor, broadcastFn)
        true
    }
    is SilkCommand.Help -> {
        agentRuntimeHandleHelpCommand(descriptor, broadcastFn)
        true
    }
    is SilkCommand.Compact -> {
        agentRuntimeHandleCompactCommand(agentType, session, descriptor, broadcastFn)
        true
    }
    is SilkCommand.SessionList -> {
        agentRuntimeHandleSessionListCommand(ctx, agentType, session, descriptor, broadcastFn)
        true
    }
    is SilkCommand.SessionLoad -> {
        agentRuntimeHandleSessionLoadCommand(ctx, cmd, agentType, session, descriptor, broadcastFn)
        true
    }
    else -> false
}

private suspend fun agentRuntimeHandleExitCommand(
    ctx: GroupAgentContext,
    session: AgentSession,
    agentType: String,
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
) {
    agentRuntimeCleanupSessionHandlers(session)
    ctx.removeSession(agentType)
    if (ctx.sessions.isEmpty()) {
        ctx.currentAgentType = null
    }
    broadcastFn(agentRuntimeSystemMessage("已退出 ${descriptor.displayName} 模式", descriptor))
}

private suspend fun agentRuntimeHandleNewCommand(
    ctx: GroupAgentContext,
    session: AgentSession,
    agentType: String,
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
) {
    agentRuntimeCleanupSessionHandlers(session)
    session.acpSessionId = null
    session.cliSessionId = null
    session.running = false
    session.cancelled = false
    session.messageQueue.clear()
    persistCliSessionAsync(ctx.groupId, agentType, "", false)
    broadcastFn(agentRuntimeSystemMessage("已开启新会话", descriptor))
}

private suspend fun agentRuntimeHandleStatusCommand(
    ctx: GroupAgentContext,
    session: AgentSession,
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
) {
    val status = buildString {
        appendLine("Agent: ${descriptor.displayName}")
        appendLine("运行中: ${session.running}")
        appendLine("队列: ${session.messageQueue.size} 条")
        appendLine("工作目录: ${ctx.workingDir}")
        appendLine("权限模式: ${session.permissionMode}")
        append("ACP Session: ${session.acpSessionId ?: "未创建"}")
    }
    broadcastFn(agentRuntimeStatusMessage(status, descriptor))
}

private suspend fun agentRuntimeHandleQueueCommand(
    cmd: SilkCommand.Queue,
    session: AgentSession,
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
) {
    if (cmd.clear) {
        session.messageQueue.clear()
        broadcastFn(agentRuntimeSystemMessage("队列已清空", descriptor))
        return
    }
    val items = session.messageQueue.mapIndexed { index, queued ->
        "${index + 1}. ${queued.text.take(40)}${if (queued.text.length > 40) "..." else ""}"
    }.joinToString("\n")
    val message = if (items.isEmpty()) "队列为空" else "队列 (${session.messageQueue.size} 条):\n$items"
    broadcastFn(agentRuntimeStatusMessage(message, descriptor))
}

private suspend fun agentRuntimeHandleHelpCommand(
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
) {
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
    broadcastFn(agentRuntimeSystemMessage(help, descriptor))
}

private suspend fun agentRuntimeHandleCompactCommand(
    agentType: String,
    session: AgentSession,
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
) {
    val acp = agentRuntimeGetAcpClient(agentType, session.userId)
    val acpSessionId = session.acpSessionId
    if (acp == null || acpSessionId == null) {
        broadcastFn(agentRuntimeSystemMessage("compact 需要 bridge 连接", descriptor))
        return
    }
    runAgentCatching {
        withContext(Dispatchers.IO) {
            AcpExtensions.compact(acp, acpSessionId)
        }
    }.onSuccess {
        broadcastFn(agentRuntimeSystemMessage("会话已压缩", descriptor))
    }.onFailure { e ->
        broadcastFn(agentRuntimeSystemMessage("压缩失败: ${e.message}", descriptor))
    }
}

private suspend fun agentRuntimeHandleSessionListCommand(
    ctx: GroupAgentContext,
    agentType: String,
    session: AgentSession,
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
) {
    val acp = agentRuntimeGetAcpClient(agentType, session.userId)
    if (acp == null) {
        broadcastFn(agentRuntimeStatusMessage("本地会话列表（待 bridge 连接后拉取）", descriptor))
        return
    }
    val result = runAgentCatching {
        withContext(Dispatchers.IO) {
            AcpExtensions.listLocalSessions(acp, ctx.workingDir)
        }
    }.getOrElse { e ->
        broadcastFn(agentRuntimeStatusMessage("获取会话列表失败: ${e.message}", descriptor))
        return
    }
    broadcastFn(agentRuntimeStatusMessage(AcpExtensions.formatLocalSessionsForDisplay(result), descriptor))
}

private suspend fun agentRuntimeHandleSessionLoadCommand(
    ctx: GroupAgentContext,
    cmd: SilkCommand.SessionLoad,
    agentType: String,
    session: AgentSession,
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
) {
    val acp = agentRuntimeGetAcpClient(agentType, session.userId)
    if (acp == null) {
        broadcastFn(agentRuntimeSystemMessage("${descriptor.displayName} 未连接，无法加载会话", descriptor))
        return
    }
    agentRuntimeCleanupSessionHandlers(session)
    val result = runAgentCatching {
        withContext(Dispatchers.IO) {
            acp.sessionLoad(cmd.sessionIdPrefix, ctx.workingDir)
        }
    }.getOrElse { e ->
        if (e is AcpRpcException) {
            broadcastFn(agentRuntimeSystemMessage("加载会话失败: ${e.rpcError.message}", descriptor))
        } else {
            agentRuntimeLogger.warn("[AgentRuntime] sessionLoad 异常: {}", e.message)
            broadcastFn(agentRuntimeSystemMessage("加载会话异常: ${e.message}", descriptor))
        }
        return
    }
    session.acpSessionId = result.sessionId
    session.cliSessionId = cmd.sessionIdPrefix
    broadcastFn(agentRuntimeSystemMessage("已加载会话: ${cmd.sessionIdPrefix.take(8)}...", descriptor))
}

private suspend fun agentRuntimeHandleDescriptorCommand(
    cmd: SilkCommand,
    agentType: String,
    session: AgentSession,
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
) {
    val acp = agentRuntimeGetAcpClient(agentType, session.userId) ?: return
    val result = descriptor.handleSilkCommand(cmd, session, acp)
    if (result is SilkCommandResult.Error) {
        broadcastFn(agentRuntimeSystemMessage(result.message, descriptor))
    }
}

private fun agentRuntimeSystemMessage(text: String, descriptor: AgentDescriptor): Message =
    AgentMessages.system(text, agentUserId = descriptor.agentUserId, agentName = descriptor.displayName)

private fun agentRuntimeStatusMessage(text: String, descriptor: AgentDescriptor): Message =
    AgentMessages.status(text, agentUserId = descriptor.agentUserId, agentName = descriptor.displayName)

private suspend fun agentRuntimeHandlePrompt(
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
    val acp = agentRuntimeGetAcpClient(agentType, userId)

    val pending = session.pendingQuestion
    agentRuntimeLogger.info(
        "[AgentRuntime] handlePrompt: agentType={}, running={}, pendingQuestion={}, textLen={}",
        agentType,
        session.running,
        pending?.requestId?.take(8),
        text.length,
    )
    if (pending != null) {
        agentRuntimeLogger.info(
            "[AgentRuntime] User reply to question: requestId={}, answerLen={}",
            pending.requestId.take(8),
            text.length,
        )
        val currentIdx = (0 until pending.questions.size).firstOrNull { it !in pending.answers } ?: 0
        agentRuntimeHandleQuestionReply(session, pending, currentIdx, text, broadcastFn)
        return
    }

    if (acp == null) {
        broadcastFn(
            AgentMessages.system(
                "${descriptor.displayName} 未连接。请启动 Bridge Agent。",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
            ),
        )
        return
    }

    if (session.running) {
        agentRuntimeLogger.info("[AgentRuntime] Message queued: running=true, pendingQuestion=null, textLen={}", text.length)
        session.messageQueue.add(QueuedMessage(text, userId, userName))
        broadcastFn(
            AgentMessages.status(
                "任务运行中，消息已加入队列 (${session.messageQueue.size} 条)",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
                stableId = "agent_queue_${agentType}",
            ),
        )
        return
    }

    session.running = true
    session.cancelled = false
    session.promptJob = ctx.scope.launch {
        try {
            if (session.acpSessionId == null) {
                val result = runAgentCatching {
                    acp.sessionNew(cwd = ctx.workingDir, cliSessionId = session.cliSessionId)
                }.getOrElse { e ->
                    agentRuntimeLogger.error("[AgentRuntime] sessionNew 失败: userId={}, agentType={}", userId, agentType, e)
                    broadcastFn(
                        AgentMessages.system(
                            "创建会话失败: ${e.message}",
                            agentUserId = descriptor.agentUserId,
                            agentName = descriptor.displayName,
                        ),
                    )
                    return@launch
                }
                session.acpSessionId = result.sessionId
            }

            val acpSessionId = session.acpSessionId
            if (acpSessionId == null) {
                broadcastFn(
                    AgentMessages.system(
                        "会话已断开，请重试",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ),
                )
                return@launch
            }
            val accumulated = StringBuilder()
            agentRuntimeSetupAcpHandlers(acp, acpSessionId, session, descriptor, broadcastFn, accumulated, ctx.scope, ctx)
            agentRuntimeExecuteSinglePrompt(ctx, acp, session, descriptor, text, broadcastFn, accumulated)

            var next = session.messageQueue.pollFirst()
            while (next != null) {
                agentRuntimeLogger.info(
                    "[AgentRuntime] drainQueue: processing next queued message, remaining={}",
                    session.messageQueue.size,
                )
                val drainSessionId = session.acpSessionId ?: break
                val nextAccumulated = StringBuilder()
                agentRuntimeSetupAcpHandlers(
                    acp,
                    drainSessionId,
                    session,
                    descriptor,
                    broadcastFn,
                    nextAccumulated,
                    ctx.scope,
                    ctx,
                )
                agentRuntimeExecuteSinglePrompt(ctx, acp, session, descriptor, next.text, broadcastFn, nextAccumulated)
                next = session.messageQueue.pollFirst()
            }
        } finally {
            session.running = false
            session.promptJob = null
            agentRuntimeCleanupSessionHandlers(session)
        }
    }
}

private suspend fun agentRuntimeExecuteSinglePrompt(
    ctx: GroupAgentContext,
    acp: AcpClient,
    session: AgentSession,
    descriptor: AgentDescriptor,
    text: String,
    broadcastFn: suspend (Message) -> Unit,
    accumulated: StringBuilder,
) {
    val requestId = UUID.randomUUID().toString()
    session.currentRequestId = requestId
    try {
        val result = runAgentCatching {
            acp.sessionPrompt(
                sessionId = session.acpSessionId!!,
                prompt = listOf(ContentBlock.Text(text)),
            )
        }.getOrElse { e ->
            agentRuntimeLogger.error(
                "[AgentRuntime] sessionPrompt 失败: userId={}, agentType={}",
                session.userId,
                session.agentType,
                e,
            )
            broadcastFn(
                AgentMessages.system(
                    "执行失败: ${e.message}",
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ),
            )
            return
        }

        val metaCliSid = result.meta?.let { meta ->
            runCatching { meta.jsonObject["cliSessionId"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
        if (!metaCliSid.isNullOrBlank()) {
            session.cliSessionId = metaCliSid
            persistCliSessionAsync(ctx.groupId, session.agentType, metaCliSid, true)
        }

        when (result.stopReason) {
            StopReason.END_TURN -> {
                if (accumulated.isNotEmpty()) {
                    broadcastFn(
                        AgentMessages.final(
                            accumulated.toString(),
                            agentUserId = descriptor.agentUserId,
                            agentName = descriptor.displayName,
                        ),
                    )
                }
            }
            StopReason.MAX_TOKENS -> {
                broadcastFn(
                    AgentMessages.system(
                        "⚠️ 达到最大 token 限制",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ),
                )
            }
            StopReason.REFUSAL -> {
                broadcastFn(
                    AgentMessages.system(
                        "请求被拒绝",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ),
                )
            }
            StopReason.CANCELLED -> Unit
        }

        broadcastFn(
            AgentMessages.status(
                "CLEAR_STATUS",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
            ),
        )
        val metaStr = result.meta?.let { formatPromptMeta(it, session.cliSessionId) }
        if (!metaStr.isNullOrBlank()) {
            broadcastFn(
                AgentMessages.system(
                    metaStr,
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ),
            )
        }
    } finally {
        session.currentRequestId = null
        session.pendingQuestion = null
    }
}

private suspend fun agentRuntimeHandleCancel(
    session: AgentSession,
    broadcastFn: suspend (Message) -> Unit,
) {
    val descriptor = AgentRegistry.getByType(session.agentType) ?: return
    if (!session.running) {
        broadcastFn(
            AgentMessages.status(
                "没有运行中的任务",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
            ),
        )
        return
    }

    val acp = agentRuntimeGetAcpClient(session.agentType, session.userId)
    if (acp != null && session.acpSessionId != null) {
        runAgentCatching { acp.sessionCancel(session.acpSessionId!!) }
            .onFailure { e -> agentRuntimeLogger.warn("[AgentRuntime] sessionCancel 失败: {}", e.message) }
    }

    session.promptJob?.cancelAndJoin()
    session.cancelled = true
    session.pendingQuestion = null
    broadcastFn(
        AgentMessages.status(
            "任务已取消",
            agentUserId = descriptor.agentUserId,
            agentName = descriptor.displayName,
        ),
    )
}

internal suspend fun agentRuntimeHandleQuestionReply(
    session: AgentSession,
    pending: PendingQuestion,
    questionIndex: Int,
    answerText: String,
    broadcastFn: suspend (Message) -> Unit,
) {
    val descriptor = AgentRegistry.getByType(session.agentType) ?: return
    pending.answers[questionIndex] = AgentMessages.cleanAnswerText(answerText)
    val total = pending.questions.size

    if (pending.answers.size < total) {
        val nextIndex = (0 until total).first { it !in pending.answers }
        agentRuntimeLogger.info(
            "[AgentRuntime] Question {}/{} answered, advancing to {}. requestId={}",
            pending.answers.size,
            total,
            nextIndex + 1,
            pending.requestId.take(8),
        )
        broadcastFn(
            AgentMessages.questionCard(
                questions = pending.questions,
                requestId = pending.requestId,
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
                currentIndex = nextIndex,
                answers = pending.answers.toMap(),
            ).copy(action = "edit"),
        )
        return
    }

    val cardId = "agent_question_${pending.requestId}"
    com.silk.backend.card.CardReplyRouter.unregister(cardId)
    session.pendingQuestion = null
    val cleanAnswer = { raw: String -> AgentMessages.cleanAnswerText(raw) }
    val resolveText = if (total == 1) {
        "用户选择了「${cleanAnswer(pending.answers[0] ?: "")}」。" +
            "请按照用户的选择继续执行，不要再次调用 AskUserQuestion 询问同一个问题。"
    } else {
        val lines = pending.questions.mapIndexed { index, question ->
            val answer = cleanAnswer(pending.answers[index] ?: "")
            "${index + 1}. ${question.question}\n回答: $answer"
        }
        "用户已完成多题回答：\n${lines.joinToString("\n\n")}\n\n请根据这些答案继续执行，不要再次调用 AskUserQuestion 询问相同问题。"
    }

    val acp = agentRuntimeGetAcpClient(session.agentType, session.userId)
    val acpSessionId = session.acpSessionId
    if (acp == null || acpSessionId == null) {
        broadcastFn(agentRuntimeSystemMessage("Bridge 未连接，无法提交回答", descriptor))
        return
    }
    val response = runAgentCatching {
        acp.sessionPrompt(
            sessionId = acpSessionId,
            prompt = listOf(ContentBlock.Text(resolveText)),
        )
    }.getOrElse { e ->
        broadcastFn(agentRuntimeSystemMessage("提交回答失败: ${e.message}", descriptor))
        return
    }
    if (response.stopReason == StopReason.END_TURN) {
        broadcastFn(
            AgentMessages.status(
                "已提交回答，继续执行中",
                agentUserId = descriptor.agentUserId,
                agentName = descriptor.displayName,
            ),
        )
    }
}
