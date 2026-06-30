package com.silk.backend.agents.core

import com.silk.backend.Message

/**
 * Agent 框架对外门面。
 */
object AgentRuntime {

    init {
        agentRuntimeInitialize()
    }

    /**
     * Workflow 持久化回调：让 AgentRuntime 把 workingDir 和 cli_session_id 写回 WorkflowManager。
     * 由 [Application]/[configureRouting] 在启动时通过 [setWorkflowPersistence] 注入。
     */
    interface WorkflowPersistence {
        fun persistWorkingDir(rawGroupId: String, workingDir: String): Boolean
        fun persistCliSession(rawGroupId: String, cliSessionId: String, sessionStarted: Boolean): Boolean
        fun persistCliSession(rawGroupId: String, agentType: String, cliSessionId: String, sessionStarted: Boolean): Boolean =
            persistCliSession(rawGroupId, cliSessionId, sessionStarted)
        fun persistActiveAgent(rawGroupId: String, agentType: String): Boolean = false
        fun persistPermissionMode(rawGroupId: String, permissionMode: String): Boolean = false
        fun loadSeed(rawGroupId: String): WorkflowSeed?
        fun loadSeed(rawGroupId: String, agentType: String): WorkflowSeed? = loadSeed(rawGroupId)
    }

    data class WorkflowSeed(
        val workingDir: String,
        val cliSessionId: String?,
        val sessionStarted: Boolean,
        val permissionMode: String = "",
    )

    data class AgentStateSnapshot(
        val active: Boolean,
        val running: Boolean,
        val workingDir: String,
        val agentType: String?,
        val permissionMode: String = "",
    )

    data class PendingQuestionSnapshot(
        val requestId: String,
        val questions: List<StructuredQuestion>,
        val agentUserId: String,
        val agentName: String,
    )

    sealed class CdResult {
        data class Ok(val resolvedPath: String) : CdResult()
        data class Err(val reason: String) : CdResult()
    }

    fun setWorkflowPersistence(p: WorkflowPersistence) {
        setAgentRuntimeWorkflowPersistence(p)
    }

    fun listRegisteredAgents(): List<AgentDescriptor> = AgentRegistry.list()

    fun isAgentMessage(msg: Message): Boolean =
        msg.userId == com.silk.backend.SilkAgent.AGENT_ID || AgentRegistry.list().any { it.agentUserId == msg.userId }

    fun isAgentUserId(userId: String): Boolean =
        userId == com.silk.backend.SilkAgent.AGENT_ID || AgentRegistry.list().any { it.agentUserId == userId }

    suspend fun handleIfActive(
        userId: String,
        groupId: String,
        text: String,
        userName: String,
        broadcastFn: suspend (Message) -> Unit,
    ): Boolean = agentRuntimeHandleIfActive(userId, groupId, text, userName, broadcastFn)

    suspend fun cancelIfActive(
        userId: String,
        groupId: String,
        broadcastFn: suspend (Message) -> Unit,
    ): Boolean = agentRuntimeCancelIfActive(userId, groupId, broadcastFn)

    fun autoActivateForWorkflow(userId: String, groupId: String, agentType: String) {
        agentRuntimeAutoActivateForWorkflow(userId, groupId, agentType)
    }

    fun handleAgentDisconnect(userId: String, agentType: String) {
        agentRuntimeHandleAgentDisconnect(userId, agentType)
    }

    fun snapshotState(userId: String, groupId: String): AgentStateSnapshot? =
        agentRuntimeSnapshotState(userId, groupId)

    fun switchAgent(userId: String, groupId: String, agentType: String): AgentDescriptor? =
        agentRuntimeSwitchAgent(userId, groupId, agentType)

    fun setPermissionMode(userId: String, groupId: String, mode: String): Boolean =
        agentRuntimeSetPermissionMode(userId, groupId, mode)

    fun snapshotPendingQuestion(userId: String, groupId: String): PendingQuestionSnapshot? =
        agentRuntimeSnapshotPendingQuestion(userId, groupId)

    suspend fun cdSync(
        userId: String,
        groupId: String,
        path: String,
        agentType: String = "claude-code",
    ): CdResult = agentRuntimeCdSync(userId, groupId, path, agentType)

    suspend fun listDirectory(
        userId: String,
        path: String?,
        showHidden: Boolean,
        agentType: String = "claude-code",
    ): kotlinx.serialization.json.JsonObject? = agentRuntimeListDirectory(userId, path, showHidden, agentType)

    fun cleanupState(userId: String, groupId: String) {
        agentRuntimeCleanupState(userId, groupId)
    }

    internal fun clearForTest() {
        agentRuntimeClearForTest()
    }
}
