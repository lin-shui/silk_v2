package com.silk.backend.routes

import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.UserRepository
import com.silk.backend.models.Workflow
import com.silk.backend.resolveAuthenticatedUserId
import com.silk.backend.workflow.WorkflowManager
import com.silk.backend.workspace.WorkspaceManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowSummaryDto(
    val id: String,
    val name: String,
    val description: String = "",
    val ownerId: String,
    val ownerDisplayName: String,
    val groupId: String,
    val agentType: String,
    val role: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class WorkflowRoomMemberDto(
    val id: String,
    val fullName: String,
    val role: String,
)

@Serializable
data class WorkflowMemberCandidateDto(
    val id: String,
    val loginName: String,
    val fullName: String,
    val phoneNumber: String,
)

@Serializable
data class WorkflowRoomMembersResponse(
    val success: Boolean,
    val members: List<WorkflowRoomMemberDto> = emptyList(),
    val message: String = "",
)

@Serializable
data class WorkflowMemberCandidatesResponse(
    val success: Boolean,
    val candidates: List<WorkflowMemberCandidateDto> = emptyList(),
    val message: String = "",
)

@Serializable
data class AddWorkflowRoomMemberRequest(val userId: String)

private fun Workflow.toSummary(callerId: String): WorkflowSummaryDto = WorkflowSummaryDto(
    id = id,
    name = name,
    description = description,
    ownerId = ownerId,
    ownerDisplayName = UserRepository.findUserById(ownerId)?.fullName.orEmpty().ifBlank { "Room owner" },
    groupId = groupId,
    agentType = agentType,
    role = if (ownerId == callerId) "OWNER" else "MEMBER",
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Workflow.membersResponse(): WorkflowRoomMembersResponse {
    val members = GroupRepository.getGroupMembers(groupId).map { member ->
        WorkflowRoomMemberDto(
            id = member.userId,
            fullName = UserRepository.findUserById(member.userId)?.fullName.orEmpty().ifBlank { member.userName },
            role = if (member.userId == ownerId) "OWNER" else "MEMBER",
        )
    }.sortedWith(compareBy<WorkflowRoomMemberDto> { it.role != "OWNER" }.thenBy { it.fullName })
    return WorkflowRoomMembersResponse(success = true, members = members)
}

private suspend fun ApplicationCall.resolveWorkflowRoom(
    workflowManager: WorkflowManager,
    ownerRequired: Boolean,
): Pair<Workflow, String>? {
    val callerId = resolveAuthenticatedUserId()
    if (callerId == null) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    val workflowId = parameters["workflowId"].orEmpty()
    val workflow = workflowManager.getWorkflowById(workflowId)
    if (workflow == null || !GroupRepository.isUserInGroup(workflow.groupId, callerId)) {
        respond(HttpStatusCode.NotFound)
        return null
    }
    if (ownerRequired && workflow.ownerId != callerId) {
        respond(HttpStatusCode.Forbidden)
        return null
    }
    return workflow to callerId
}

fun Route.workflowRoomRoutes(
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
    onMemberRevoked: suspend (roomId: String, userId: String) -> Unit,
) {
    registerVisibleWorkflowsRoute(workflowManager)
    registerWorkflowMembersRoute(workflowManager)
    registerWorkflowMemberCandidatesRoute(workflowManager)
    registerAddWorkflowMemberRoute(workflowManager)
    registerRemoveWorkflowMemberRoute(workflowManager, workspaceManager, onMemberRevoked)
}

private fun Route.registerVisibleWorkflowsRoute(workflowManager: WorkflowManager) {
    get("/api/workflows/visible") {
        val callerId = call.resolveAuthenticatedUserId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val visible = workflowManager.listAllWorkflows()
            .filter { GroupRepository.isUserInGroup(it.groupId, callerId) }
            .sortedByDescending { it.updatedAt }
            .map { it.toSummary(callerId) }
        call.respond(visible)
    }
}

private fun Route.registerWorkflowMembersRoute(workflowManager: WorkflowManager) {
    get("/api/workflows/{workflowId}/members") {
        val workflow = call.resolveWorkflowRoom(workflowManager, ownerRequired = false)?.first
            ?: return@get
        call.respond(workflow.membersResponse())
    }
}

private fun Route.registerWorkflowMemberCandidatesRoute(workflowManager: WorkflowManager) {
    get("/api/workflows/{workflowId}/members/candidates") {
        val workflow = call.resolveWorkflowRoom(workflowManager, ownerRequired = true)?.first
            ?: return@get
        val query = call.request.queryParameters["query"].orEmpty().trim()
        if (query.isBlank()) {
            return@get call.respond(WorkflowMemberCandidatesResponse(success = true))
        }
        val candidates = UserRepository.searchUsersByName(query)
            .filterNot { GroupRepository.isUserInGroup(workflow.groupId, it.id) }
            .map {
                WorkflowMemberCandidateDto(
                    id = it.id,
                    loginName = it.loginName,
                    fullName = it.fullName,
                    phoneNumber = it.phoneNumber,
                )
            }
        call.respond(WorkflowMemberCandidatesResponse(success = true, candidates = candidates))
    }
}

private fun Route.registerAddWorkflowMemberRoute(workflowManager: WorkflowManager) {
    post("/api/workflows/{workflowId}/members") {
        val workflow = call.resolveWorkflowRoom(workflowManager, ownerRequired = true)?.first
            ?: return@post
        val targetUserId = call.receive<AddWorkflowRoomMemberRequest>().userId.trim()
        if (targetUserId.isBlank() || UserRepository.findUserById(targetUserId) == null) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                WorkflowRoomMembersResponse(false, message = "用户不存在"),
            )
        }
        if (GroupRepository.isUserInGroup(workflow.groupId, targetUserId)) {
            return@post call.respond(
                HttpStatusCode.Conflict,
                WorkflowRoomMembersResponse(false, message = "用户已在 Room 中"),
            )
        }
        if (!GroupRepository.addUserToGroup(workflow.groupId, targetUserId)) {
            return@post call.respond(
                HttpStatusCode.InternalServerError,
                WorkflowRoomMembersResponse(false, message = "添加成员失败"),
            )
        }
        call.respond(HttpStatusCode.Created, workflow.membersResponse())
    }
}

private fun Route.registerRemoveWorkflowMemberRoute(
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
    onMemberRevoked: suspend (roomId: String, userId: String) -> Unit,
) {
    delete("/api/workflows/{workflowId}/members/{userId}") {
        val workflow = call.resolveWorkflowRoom(workflowManager, ownerRequired = true)?.first
            ?: return@delete
        val targetUserId = call.parameters["userId"].orEmpty()
        if (targetUserId.isBlank() || targetUserId == workflow.ownerId) {
            return@delete call.respond(HttpStatusCode.BadRequest)
        }
        if (!GroupRepository.isUserInGroup(workflow.groupId, targetUserId)) {
            return@delete call.respond(HttpStatusCode.NotFound)
        }
        val ownedWorkspaces = workspaceManager.listWorkspaces(targetUserId, workflow.groupId)
        val (removed, _) = GroupRepository.leaveGroup(workflow.groupId, targetUserId)
        if (!removed) {
            return@delete call.respond(HttpStatusCode.InternalServerError)
        }
        ownedWorkspaces.forEach { AgentRuntime.cleanupState(it.ownerId, it.workspaceId) }
        workspaceManager.privatizeWorkspacesOwnedBy(workflow.groupId, targetUserId)
        workspaceManager.removeCopilotFromRoom(workflow.groupId, targetUserId)
        onMemberRevoked(workflow.groupId, targetUserId)
        call.respond(workflow.membersResponse())
    }
}
