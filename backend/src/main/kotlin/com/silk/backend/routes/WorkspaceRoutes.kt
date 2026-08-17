package com.silk.backend.routes

import com.silk.backend.ChatHistoryManager
import com.silk.backend.MessageScope
import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.auth.AgentAuthRepository
import com.silk.backend.agents.auth.AgentBindingMessageScope
import com.silk.backend.agents.auth.AgentBindingTargetType
import com.silk.backend.agents.auth.AgentInstanceStatus
import com.silk.backend.agents.auth.AgentPermission
import com.silk.backend.agents.auth.AgentTriggerPolicy
import com.silk.backend.agents.auth.CreateAgentBindingRequest
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.UserRepository
import com.silk.backend.resolveAuthenticatedUserId
import com.silk.backend.trust.TrustedDirManager
import com.silk.backend.workspace.PersonalWorkspace
import com.silk.backend.workspace.WorkspaceLifecycleState
import com.silk.backend.workspace.WorkspaceManager
import com.silk.backend.workspace.WorkspaceVisibility
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class CreateWorkspaceRequest(
    val name: String,
    val workingDir: String = "",
    val agentType: String = "claude-code",
    val agentInstanceId: String = "",
    val visibility: WorkspaceVisibility = WorkspaceVisibility.PRIVATE,
)

@Serializable
data class PatchWorkspaceRequest(
    val name: String? = null,
    val visibility: WorkspaceVisibility? = null,
    val copilots: List<String>? = null,
    val lifecycleState: WorkspaceLifecycleState? = null,
)

@Serializable
enum class WorkspaceActivityState { RUNNING, WAITING, IDLE, OFFLINE }

@Serializable
data class WorkspaceActivityDto(
    val state: WorkspaceActivityState,
    val updatedAt: Long,
)

@Serializable
data class WorkspaceDto(
    val workspaceId: String,
    val roomId: String,
    val ownerId: String,
    val ownerDisplayName: String,
    val name: String,
    val workingDir: String = "",
    val agentType: String = "claude-code",
    val activeAgentInstanceId: String = "",
    val visibility: WorkspaceVisibility,
    val copilots: List<String> = emptyList(),
    val role: String,
    val lifecycleState: WorkspaceLifecycleState,
    val activity: WorkspaceActivityDto,
    val createdAt: Long,
    val recentActivityAt: Long,
    val linkedGithubRef: String? = null,
    val historyOnly: Boolean = false,
)

@Serializable
data class WorkspaceErrorResponse(
    val errorCode: String,
    val message: String,
    val path: String? = null,
    val bridgeId: String? = null,
)

@Serializable
data class RecentWorkingDirResponse(
    val workingDir: String = "",
)

private fun PersonalWorkspace.activityDto(): WorkspaceActivityDto {
    val runtimeAgentType = activeAgent.ifBlank { agentType }
    val runtime = AgentRuntime.snapshotState(ownerId, workspaceId)
    val state = when {
        lifecycleState == WorkspaceLifecycleState.ARCHIVED -> WorkspaceActivityState.IDLE
        activeAgentInstanceId.isNotBlank() &&
            !AcpRegistry.isConnectedInstance(activeAgentInstanceId) -> WorkspaceActivityState.OFFLINE
        activeAgentInstanceId.isBlank() &&
            !AcpRegistry.isConnected(ownerId, runtimeAgentType) -> WorkspaceActivityState.OFFLINE
        AgentRuntime.snapshotPendingQuestion(ownerId, workspaceId) != null -> WorkspaceActivityState.WAITING
        runtime?.running == true -> WorkspaceActivityState.RUNNING
        else -> WorkspaceActivityState.IDLE
    }
    return WorkspaceActivityDto(state = state, updatedAt = updatedAt)
}

private fun PersonalWorkspace.ownerDisplayName(callerId: String): String =
    UserRepository.findUserById(ownerId)?.fullName?.takeIf { it.isNotBlank() }
        ?: if (ownerId == callerId) "You" else "Room member"

internal fun PersonalWorkspace.toDto(callerId: String): WorkspaceDto {
    val canInspectRuntime = ownerId == callerId || callerId in copilots
    return WorkspaceDto(
        workspaceId = workspaceId,
        roomId = roomId,
        ownerId = ownerId,
        ownerDisplayName = ownerDisplayName(callerId),
        name = name,
        workingDir = workingDir.takeIf { canInspectRuntime }.orEmpty(),
        agentType = activeAgent.ifBlank { agentType },
        activeAgentInstanceId = activeAgentInstanceId.takeIf { canInspectRuntime }.orEmpty(),
        visibility = visibility,
        copilots = copilots.takeIf { ownerId == callerId }.orEmpty(),
        role = when {
            ownerId == callerId -> "OWNER"
            callerId in copilots -> "COPILOT"
            else -> "OBSERVER"
        },
        lifecycleState = lifecycleState,
        activity = activityDto(),
        createdAt = createdAt,
        recentActivityAt = updatedAt,
        linkedGithubRef = linkedGithubRef,
    )
}

private fun PersonalWorkspace.toHistoricalDto(callerId: String): WorkspaceDto =
    WorkspaceDto(
        workspaceId = workspaceId,
        roomId = roomId,
        ownerId = ownerId,
        ownerDisplayName = ownerDisplayName(callerId),
        name = lastSharedName?.takeIf { it.isNotBlank() } ?: name,
        workingDir = "",
        agentType = "",
        activeAgentInstanceId = "",
        visibility = WorkspaceVisibility.PRIVATE,
        copilots = emptyList(),
        role = "OBSERVER",
        lifecycleState = lifecycleState,
        activity = WorkspaceActivityDto(WorkspaceActivityState.OFFLINE, 0L),
        createdAt = 0L,
        recentActivityAt = 0L,
        linkedGithubRef = null,
        historyOnly = true,
    )

private fun workspaceHasHistory(roomId: String, workspaceId: String): Boolean =
    ChatHistoryManager().loadChatHistory("group_$roomId")?.messages?.any {
        it.scope == MessageScope.WORKSPACE && it.workspaceId == workspaceId
    } == true

private fun observerVisibleWorkspaceIds(roomId: String): Set<String> =
    ChatHistoryManager().loadChatHistory("group_$roomId")?.messages.orEmpty()
        .asSequence()
        .filter { it.scope == MessageScope.WORKSPACE && it.observerVisible }
        .mapNotNull { it.workspaceId?.takeIf(String::isNotBlank) }
        .toSet()

@Suppress("CyclomaticComplexMethod", "ComplexCondition", "LongMethod")
fun Route.workspaceRoutes(
    workspaceManager: WorkspaceManager,
    trustedDirManager: TrustedDirManager,
    isWorkflowRoom: (String) -> Boolean,
) {
    route("/api/rooms/{roomId}/workspaces") {
        get("recent-working-dir") {
            val userId = call.resolveAuthenticatedUserId()
            if (userId == null) return@get call.respond(HttpStatusCode.Unauthorized)
            val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            if (!isWorkflowRoom(roomId) || !GroupRepository.isUserInGroup(roomId, userId)) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
            val agentInstanceId = call.request.queryParameters["agentInstanceId"].orEmpty().trim()
            if (agentInstanceId.isBlank()) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    WorkspaceErrorResponse("AGENT_INSTANCE_REQUIRED", "Agent 实例必填"),
                )
            }
            val agentExists = AgentAuthRepository.listAgents(userId).any {
                it.agentInstanceId == agentInstanceId && it.status == AgentInstanceStatus.ACTIVE
            }
            if (!agentExists) {
                return@get call.respond(
                    HttpStatusCode.NotFound,
                    WorkspaceErrorResponse("AGENT_NOT_FOUND", "所选 Agent 不存在或已失效"),
                )
            }
            call.respond(
                RecentWorkingDirResponse(
                    workspaceManager.recentWorkingDir(roomId, userId, agentInstanceId).orEmpty(),
                ),
            )
        }

        post {
            val userId = call.resolveAuthenticatedUserId()
            if (userId == null) return@post call.respond(HttpStatusCode.Unauthorized)
            val roomId = call.parameters["roomId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (!isWorkflowRoom(roomId) || !GroupRepository.isUserInGroup(roomId, userId)) {
                return@post call.respond(HttpStatusCode.NotFound)
            }
            val req = call.receive<CreateWorkspaceRequest>()
            val name = req.name.trim()
            val workingDir = req.workingDir.trim()
            val requestedAgentInstanceId = req.agentInstanceId.trim()
            val selectedAgent = requestedAgentInstanceId.takeIf(String::isNotBlank)?.let { instanceId ->
                AgentAuthRepository.listAgents(userId).firstOrNull {
                    it.agentInstanceId == instanceId && it.status == AgentInstanceStatus.ACTIVE
                }
            }
            if (requestedAgentInstanceId.isNotBlank() && selectedAgent == null) {
                return@post call.respond(
                    HttpStatusCode.NotFound,
                    WorkspaceErrorResponse("AGENT_NOT_FOUND", "所选 Agent 不存在或已失效"),
                )
            }
            val agentType = selectedAgent?.agentType
                ?: req.agentType.trim().replace('_', '-').ifBlank { "claude-code" }
            if (name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
            if (workingDir.isBlank()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    WorkspaceErrorResponse("WORKING_DIR_REQUIRED", "工作目录不能为空"),
                )
            }
            if (AgentRuntime.listRegisteredAgents().none { it.agentType == agentType }) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    WorkspaceErrorResponse("UNSUPPORTED_AGENT", "不支持的 Agent: $agentType"),
                )
            }
            val agentConnected = if (selectedAgent != null) {
                AcpRegistry.isConnectedInstance(selectedAgent.agentInstanceId)
            } else {
                AcpRegistry.isConnected(userId, agentType)
            }
            if (!agentConnected) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    WorkspaceErrorResponse("BRIDGE_OFFLINE", "所选 Agent Bridge 未连接"),
                )
            }
            val bridgeId = if (selectedAgent != null) {
                AcpRegistry.getRemoteIpByInstance(selectedAgent.agentInstanceId)?.let { "ip:$it" }
            } else {
                AcpRegistry.getRemoteIp(userId, agentType)?.let { "ip:$it" }
            }
            if (bridgeId == null || !trustedDirManager.isTrusted(userId, bridgeId, workingDir)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    WorkspaceErrorResponse(
                        errorCode = "DIRECTORY_NOT_TRUSTED",
                        message = "目录 $workingDir 未被信任",
                        path = workingDir,
                        bridgeId = bridgeId,
                    ),
                )
            }

            val workspace = workspaceManager.createWorkspace(
                roomId = roomId,
                ownerId = userId,
                name = name,
                agentType = agentType,
                activeAgentInstanceId = requestedAgentInstanceId,
                visibility = req.visibility,
            )
            AgentRuntime.autoActivateForWorkspace(
                userId,
                workspace.workspaceId,
                agentType,
                requestedAgentInstanceId,
            )
            when (val result = AgentRuntime.cdSync(
                userId,
                workspace.workspaceId,
                workingDir,
                agentType,
                requestedAgentInstanceId,
            )) {
                is AgentRuntime.CdResult.Err -> {
                    AgentRuntime.cleanupState(userId, workspace.workspaceId)
                    workspaceManager.deleteWorkspace(workspace.workspaceId)
                    call.respond(
                        HttpStatusCode.Conflict,
                        WorkspaceErrorResponse("WORKING_DIR_REJECTED", result.reason),
                    )
                }
                is AgentRuntime.CdResult.Ok -> {
                    workspaceManager.updateWorkingDir(workspace.workspaceId, result.resolvedPath)
                    if (selectedAgent != null) {
                        val bindingCreated = runCatching {
                            AgentAuthRepository.createBinding(
                                userId = userId,
                                request = CreateAgentBindingRequest(
                                    agentInstanceId = selectedAgent.agentInstanceId,
                                    targetType = AgentBindingTargetType.WORKSPACE,
                                    targetId = workspace.workspaceId,
                                    messageScope = AgentBindingMessageScope.WORKSPACE,
                                    triggerPolicy = AgentTriggerPolicy.ALL,
                                    permissions = setOf(
                                        AgentPermission.READ_MESSAGE,
                                        AgentPermission.SEND_MESSAGE,
                                    ),
                                ),
                                approveAsAgentOwner = true,
                                approveAsTargetManager = true,
                            )
                        }.isSuccess
                        if (!bindingCreated) {
                            AgentRuntime.cleanupState(userId, workspace.workspaceId)
                            workspaceManager.deleteWorkspace(workspace.workspaceId)
                            return@post call.respond(
                                HttpStatusCode.Conflict,
                                WorkspaceErrorResponse("AGENT_BINDING_FAILED", "无法为工作区添加所选 Agent"),
                            )
                        }
                    }
                    val created = workspaceManager.getWorkspace(workspace.workspaceId)
                        ?: return@post call.respond(HttpStatusCode.InternalServerError)
                    call.respond(HttpStatusCode.Created, created.toDto(userId))
                }
            }
        }

        get {
            val userId = call.resolveAuthenticatedUserId()
            if (userId == null) return@get call.respond(HttpStatusCode.Unauthorized)
            val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            if (!isWorkflowRoom(roomId) || !GroupRepository.isUserInGroup(roomId, userId)) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
            val visible = workspaceManager.listVisibleWorkspaces(userId, roomId)
            val visibleIds = visible.mapTo(mutableSetOf()) { it.workspaceId }
            val historicalIds = observerVisibleWorkspaceIds(roomId) - visibleIds
            val historical = workspaceManager.listRoomWorkspaces(roomId)
                .filter { it.workspaceId in historicalIds }
                .map { workspace ->
                    workspaceManager.backfillLastSharedName(workspace.workspaceId) ?: workspace
                }
            call.respond(
                visible.map { it.toDto(userId) } +
                    historical.map { it.toHistoricalDto(userId) }
            )
        }

        patch("{wsId}") {
            val userId = call.resolveAuthenticatedUserId()
            if (userId == null) return@patch call.respond(HttpStatusCode.Unauthorized)
            val roomId = call.parameters["roomId"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val wsId = call.parameters["wsId"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val workspace = workspaceManager.getWorkspace(wsId)
            if (!isWorkflowRoom(roomId) || !GroupRepository.isUserInGroup(roomId, userId) ||
                workspace == null || workspace.ownerId != userId || workspace.roomId != roomId
            ) return@patch call.respond(HttpStatusCode.NotFound)

            val req = call.receive<PatchWorkspaceRequest>()
            val name = req.name?.trim()
            if (name != null && name.isBlank()) return@patch call.respond(HttpStatusCode.BadRequest)
            val copilots = req.copilots?.filter { it.isNotBlank() }?.distinct()
            if (copilots != null &&
                (workspace.ownerId in copilots || copilots.any { !GroupRepository.isUserInGroup(roomId, it) })
            ) return@patch call.respond(HttpStatusCode.BadRequest)
            val effectiveVisibility = req.visibility ?: workspace.visibility
            if (effectiveVisibility == WorkspaceVisibility.PRIVATE && !copilots.isNullOrEmpty()) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    WorkspaceErrorResponse(
                        "PRIVATE_WORKSPACE_CANNOT_HAVE_COPILOTS",
                        "私密工作区不能授权 Co-pilot",
                    ),
                )
            }

            req.visibility?.let { workspaceManager.updateVisibility(wsId, it) }
            name?.let { workspaceManager.updateName(wsId, it) }
            copilots?.let { workspaceManager.updateCopilots(wsId, it) }
            req.lifecycleState?.let { lifecycleState ->
                workspaceManager.updateLifecycleState(wsId, lifecycleState)
                if (lifecycleState == WorkspaceLifecycleState.ARCHIVED) {
                    AgentRuntime.cleanupState(workspace.ownerId, workspace.workspaceId)
                }
            }
            val updated = workspaceManager.getWorkspace(wsId)
                ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(updated.toDto(userId))
        }

        delete("{wsId}") {
            val userId = call.resolveAuthenticatedUserId()
            if (userId == null) return@delete call.respond(HttpStatusCode.Unauthorized)
            val roomId = call.parameters["roomId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val wsId = call.parameters["wsId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val workspace = workspaceManager.getWorkspace(wsId)
            if (!isWorkflowRoom(roomId) || !GroupRepository.isUserInGroup(roomId, userId) ||
                workspace == null || workspace.ownerId != userId || workspace.roomId != roomId
            ) return@delete call.respond(HttpStatusCode.NotFound)
            if (workspace.lifecycleState != WorkspaceLifecycleState.ARCHIVED) {
                return@delete call.respond(
                    HttpStatusCode.Conflict,
                    WorkspaceErrorResponse("ARCHIVE_REQUIRED", "请先归档工作区再删除"),
                )
            }
            if (workspaceHasHistory(roomId, wsId)) {
                return@delete call.respond(
                    HttpStatusCode.Conflict,
                    WorkspaceErrorResponse("WORKSPACE_HAS_HISTORY", "包含消息历史的工作区只能归档，不能硬删除"),
                )
            }
            AgentRuntime.cleanupState(workspace.ownerId, workspace.workspaceId)
            workspaceManager.deleteWorkspace(wsId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
