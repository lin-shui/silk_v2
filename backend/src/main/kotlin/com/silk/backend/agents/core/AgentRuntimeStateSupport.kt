package com.silk.backend.agents.core

import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.acp.AcpRpcException
import com.silk.backend.agents.adapters.claudecode.ClaudeCodeDescriptor
import com.silk.backend.agents.adapters.codex.CodexDescriptor
import com.silk.backend.agents.adapters.cursor.CursorDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

internal val agentRuntimeLogger = LoggerFactory.getLogger(AgentRuntime::class.java)
internal val agentRuntimeContexts = ConcurrentHashMap<String, GroupAgentContext>()

@Volatile
internal var agentRuntimePersistence: AgentRuntime.WorkflowPersistence? = null

internal fun agentRuntimeInitialize() {
    AgentRegistry.register(ClaudeCodeDescriptor)
    AgentRegistry.register(CodexDescriptor)
    AgentRegistry.register(CursorDescriptor)
}

internal fun setAgentRuntimeWorkflowPersistence(persistence: AgentRuntime.WorkflowPersistence) {
    agentRuntimePersistence = persistence
}

internal inline fun <T> runAgentCatching(block: () -> T): Result<T> =
    runCatching(block).onFailure { e ->
        if (e is CancellationException) throw e
    }

internal fun stripGroupPrefix(groupId: String): String =
    if (groupId.startsWith("group_")) groupId.removePrefix("group_") else groupId

internal fun formatPromptMeta(
    meta: kotlinx.serialization.json.JsonElement,
    cliSessionId: String?,
): String {
    val obj = runCatching { meta.jsonObject }.getOrNull() ?: return ""
    val parts = mutableListOf<String>()
    val cost = obj["costUsd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    val duration = obj["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L
    val turns = obj["numTurns"]?.jsonPrimitive?.intOrNull ?: 0
    val sid = obj["cliSessionId"]?.jsonPrimitive?.contentOrNull ?: cliSessionId.orEmpty()
    if (cost > 0) parts.add("费用: ${"$"}%.4f".format(cost))
    if (duration > 0) parts.add("耗时: %.1fs".format(duration / 1000.0))
    if (turns > 0) parts.add("轮次: $turns")
    if (sid.isNotBlank()) parts.add("会话: ${sid.take(16)}...")
    val joined = parts.joinToString(" | ")
    return if (joined.isNotBlank()) "⏱ $joined" else ""
}

internal fun persistWorkingDirAsync(groupId: String, workingDir: String) {
    val persistence = agentRuntimePersistence ?: return
    CoroutineScope(Dispatchers.IO).launch {
        runAgentCatching { persistence.persistWorkingDir(stripGroupPrefix(groupId), workingDir) }
            .onFailure { e -> agentRuntimeLogger.warn("[AgentRuntime] 持久化 workingDir 失败: {}", e.message) }
    }
}

internal fun persistCliSessionAsync(groupId: String, agentType: String, cliSessionId: String, started: Boolean) {
    val persistence = agentRuntimePersistence ?: return
    CoroutineScope(Dispatchers.IO).launch {
        runAgentCatching { persistence.persistCliSession(stripGroupPrefix(groupId), agentType, cliSessionId, started) }
            .onFailure { e -> agentRuntimeLogger.warn("[AgentRuntime] 持久化 cliSessionId 失败: {}", e.message) }
    }
}

internal fun persistPermissionModeAsync(groupId: String, permissionMode: PermissionMode) {
    val persistence = agentRuntimePersistence ?: return
    CoroutineScope(Dispatchers.IO).launch {
        runAgentCatching { persistence.persistPermissionMode(stripGroupPrefix(groupId), permissionMode.name) }
            .onFailure { e -> agentRuntimeLogger.warn("[AgentRuntime] 持久化 permissionMode 失败: {}", e.message) }
    }
}

internal fun persistActiveAgentAsync(groupId: String, agentType: String) {
    val persistence = agentRuntimePersistence ?: return
    CoroutineScope(Dispatchers.IO).launch {
        runAgentCatching { persistence.persistActiveAgent(stripGroupPrefix(groupId), agentType) }
            .onFailure { e -> agentRuntimeLogger.warn("[AgentRuntime] 持久化 activeAgent 失败: {}", e.message) }
    }
}

internal fun agentRuntimeKey(userId: String, groupId: String) = "${userId}_${groupId}"

internal fun agentRuntimeContext(userId: String, groupId: String): GroupAgentContext =
    agentRuntimeContexts.getOrPut(agentRuntimeKey(userId, groupId)) {
        GroupAgentContext(userId = userId, groupId = groupId)
    }

internal fun agentRuntimeGetAcpClient(agentType: String, userId: String): AcpClient? =
    AcpRegistry.get(userId, agentType)

internal fun agentRuntimeCleanupSessionHandlers(session: AgentSession) {
    val sid = session.acpSessionId ?: return
    agentRuntimeGetAcpClient(session.agentType, session.userId)?.removeHandlers(sid)
}

internal fun agentRuntimeClearPendingQuestion(session: AgentSession) {
    val pending = session.pendingQuestion ?: return
    com.silk.backend.card.CardReplyRouter.unregister("agent_question_${pending.requestId}")
    session.pendingQuestion = null
}

internal fun agentRuntimeAutoActivateForWorkflow(userId: String, groupId: String, agentType: String) {
    val ctx = agentRuntimeContext(userId, groupId)
    ctx.currentAgentType = agentType
    val session = ctx.getOrCreateSession(agentType)
    val seed = runAgentCatching {
        agentRuntimePersistence?.loadSeed(stripGroupPrefix(groupId), agentType)
    }.onFailure { e ->
        agentRuntimeLogger.warn("[AgentRuntime] loadSeed 失败: {}", e.message)
    }.getOrNull()
    if (seed != null) {
        if (seed.workingDir.isNotBlank()) ctx.workingDir = seed.workingDir
        if (!seed.cliSessionId.isNullOrBlank() && seed.sessionStarted) {
            session.cliSessionId = seed.cliSessionId
        }
        if (seed.permissionMode.isNotBlank()) {
            try {
                session.permissionMode = PermissionMode.valueOf(seed.permissionMode)
            } catch (_: IllegalArgumentException) {
                // ignore invalid value
            }
        }
    }
    agentRuntimeLogger.info(
        "[AgentRuntime] 工作流自动激活: userId={}, groupId={}, agentType={}, workingDir={}, cliSeed={}",
        userId,
        groupId,
        agentType,
        ctx.workingDir,
        seed?.cliSessionId?.take(8) ?: "-",
    )
}

internal fun agentRuntimeHandleAgentDisconnect(userId: String, agentType: String) {
    agentRuntimeContexts.values
        .filter { it.userId == userId }
        .forEach { ctx ->
            ctx.sessions[agentType]?.let { session ->
                agentRuntimeHandleDisconnectedSession(ctx, session, agentType)
            }
        }
}

internal fun agentRuntimeHandleDisconnectedSession(
    ctx: GroupAgentContext,
    session: AgentSession,
    agentType: String,
) {
    agentRuntimeCleanupSessionHandlers(session)
    agentRuntimeClearPendingQuestion(session)
    if (session.running) {
        session.promptJob?.cancel()
        session.promptJob = null
        session.running = false
        session.cancelled = false
        session.pendingQuestion = null
        agentRuntimeLogger.info(
            "[AgentRuntime] Bridge 断线，agent 任务已终止: userId={}, groupId={}, agentType={}",
            ctx.userId,
            ctx.groupId,
            agentType,
        )
    }
    session.acpSessionId = null
}

internal fun agentRuntimeSnapshotState(userId: String, groupId: String): AgentRuntime.AgentStateSnapshot? {
    val ctx = agentRuntimeContexts[agentRuntimeKey(userId, groupId)] ?: return null
    val agentType = ctx.currentAgentType
    val session = if (agentType != null) ctx.sessions[agentType] else null
    return AgentRuntime.AgentStateSnapshot(
        active = agentType != null,
        running = session?.running ?: false,
        workingDir = ctx.workingDir,
        agentType = agentType,
        permissionMode = session?.permissionMode?.name ?: "",
    )
}

internal fun agentRuntimeSwitchAgent(userId: String, groupId: String, agentType: String): AgentDescriptor? {
    val descriptor = AgentRegistry.getByType(agentType) ?: return null
    val ctx = agentRuntimeContext(userId, groupId)
    ctx.currentAgentType = agentType
    val session = ctx.getOrCreateSession(agentType)
    val seed = runAgentCatching {
        agentRuntimePersistence?.loadSeed(stripGroupPrefix(groupId), agentType)
    }.onFailure { e ->
        agentRuntimeLogger.warn("[AgentRuntime] switchAgent {} loadSeed failed: {}", agentType, e.message)
    }.getOrNull()
    if (seed != null && !seed.cliSessionId.isNullOrBlank() && seed.sessionStarted) {
        session.cliSessionId = seed.cliSessionId
    }
    persistActiveAgentAsync(groupId, agentType)
    return descriptor
}

internal fun agentRuntimeSetPermissionMode(userId: String, groupId: String, mode: String): Boolean {
    val permissionMode = try {
        PermissionMode.valueOf(mode)
    } catch (_: IllegalArgumentException) {
        return false
    }
    val ctx = agentRuntimeContext(userId, groupId)
    val agentType = ctx.currentAgentType ?: return false
    val session = ctx.getOrCreateSession(agentType)
    if (session.permissionMode == permissionMode) return true
    session.permissionMode = permissionMode
    persistPermissionModeAsync(groupId, permissionMode)
    return true
}

internal fun agentRuntimeSnapshotPendingQuestion(
    userId: String,
    groupId: String,
): AgentRuntime.PendingQuestionSnapshot? {
    val ctx = agentRuntimeContexts[agentRuntimeKey(userId, groupId)] ?: return null
    val agentType = ctx.currentAgentType ?: return null
    val session = ctx.sessions[agentType] ?: return null
    val pending = session.pendingQuestion ?: return null
    val descriptor = AgentRegistry.getByType(agentType) ?: return null
    return AgentRuntime.PendingQuestionSnapshot(
        requestId = pending.requestId,
        questions = pending.questions,
        agentUserId = descriptor.agentUserId,
        agentName = descriptor.displayName,
    )
}

internal suspend fun agentRuntimeCdSync(
    userId: String,
    groupId: String,
    path: String,
    agentType: String,
): AgentRuntime.CdResult {
    val ctx = agentRuntimeContext(userId, groupId)
    val existingSession = ctx.sessions[agentType]
    if (existingSession?.running == true) {
        return AgentRuntime.CdResult.Err("任务运行中，请先 /cancel 再 /cd")
    }
    val acp = AcpRegistry.get(userId, agentType) ?: return AgentRuntime.CdResult.Err("ACP Bridge 未连接")

    val session = ctx.getOrCreateSession(agentType)
    var acpSessionId = session.acpSessionId
    if (acpSessionId == null) {
        val newSession = runAgentCatching {
            acp.sessionNew(cwd = path)
        }.getOrElse { e ->
            agentRuntimeLogger.warn("[AgentRuntime] cdSync 创建 ACP session 失败: {}", e.message)
            return AgentRuntime.CdResult.Err("创建 ACP session 失败: ${e.message}")
        }
        acpSessionId = newSession.sessionId
        session.acpSessionId = acpSessionId
    }

    val currentAcpSessionId = checkNotNull(acpSessionId) { "ACP session id should be initialized" }
    val response = runAgentCatching {
        AcpExtensions.setCwd(acp, currentAcpSessionId, path)
    }.getOrElse { e ->
        return if (e is AcpRpcException) {
            AgentRuntime.CdResult.Err(e.rpcError.message)
        } else {
            agentRuntimeLogger.warn("[AgentRuntime] cdSync 异常: {}", e.message)
            AgentRuntime.CdResult.Err("set_cwd 异常: ${e.message}")
        }
    }
    val resolvedPath = response.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: path
    ctx.workingDir = resolvedPath
    agentRuntimeCleanupSessionHandlers(session)
    session.acpSessionId = null
    session.cliSessionId = null
    persistWorkingDirAsync(groupId, resolvedPath)
    persistCliSessionAsync(groupId, agentType, "", false)
    agentRuntimeLogger.info("[AgentRuntime] cdSync 成功: userId={}, groupId={}, path={}", userId, groupId, resolvedPath)
    return AgentRuntime.CdResult.Ok(resolvedPath)
}

internal suspend fun agentRuntimeListDirectory(
    userId: String,
    path: String?,
    showHidden: Boolean,
    agentType: String,
): JsonObject? {
    val acp = AcpRegistry.get(userId, agentType) ?: return null
    val response = runAgentCatching {
        AcpExtensions.listDir(acp, path ?: "", showHidden)
    }.getOrElse { e ->
        if (e is AcpRpcException) {
            agentRuntimeLogger.warn("[AgentRuntime] _silk/list_dir 失败: {}", e.rpcError.message)
        } else {
            agentRuntimeLogger.warn("[AgentRuntime] _silk/list_dir 异常: {}", e.message)
        }
        return null
    }
    return response.jsonObject
}

internal fun agentRuntimeCleanupState(userId: String, groupId: String) {
    val ctx = agentRuntimeContexts.remove(agentRuntimeKey(userId, groupId)) ?: return
    for ((_, session) in ctx.sessions) {
        agentRuntimeCleanupSessionHandlers(session)
        agentRuntimeClearPendingQuestion(session)
    }
    ctx.scope.cancel()
}

internal fun agentRuntimeClearForTest() {
    agentRuntimeContexts.values.forEach { ctx ->
        for ((_, session) in ctx.sessions) {
            agentRuntimeCleanupSessionHandlers(session)
            agentRuntimeClearPendingQuestion(session)
        }
        ctx.scope.cancel()
    }
    agentRuntimeContexts.clear()
}
