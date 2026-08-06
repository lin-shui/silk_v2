package com.silk.backend.routes

import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.auth.GroupService
import com.silk.backend.ccconnect.CcConnectRegistry
import com.silk.backend.ccconnect.CcConnectTokenRepository
import com.silk.backend.database.Group
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MemberRole
import com.silk.backend.database.UnreadRepository
import com.silk.backend.resolveAuthenticatedUserId
import com.silk.backend.rooms.effectiveRoomKind
import com.silk.backend.workflow.WorkflowManager
import com.silk.backend.workspace.WorkspaceManager
import com.silk.shared.models.CreateRoomRequest
import com.silk.shared.models.CreateRoomResponse
import com.silk.shared.models.RenameRoomRequest
import com.silk.shared.models.RoomActionResponse
import com.silk.shared.models.RoomIntegrationSummaryDto
import com.silk.shared.models.RoomKind
import com.silk.shared.models.RoomSummaryDto
import com.silk.shared.models.sortedByLatestActivity
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.time.LocalDateTime
import java.time.ZoneId

private const val CC_CONNECT_INTEGRATION = "ccconnect"
private const val MAX_ROOM_NAME_LENGTH = 256

private data class RoomActionError(
    val status: HttpStatusCode,
    val message: String,
)

fun Route.roomRoutes(
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
    onMemberRevoked: suspend (roomId: String, userId: String) -> Unit,
    onRoomDeleted: suspend (roomId: String, memberIds: List<String>) -> Unit,
) {
    registerVisibleRoomsRoute(workflowManager, workspaceManager)
    registerCreateRoomRoute(workflowManager, workspaceManager)
    registerRenameRoomRoute(workflowManager, workspaceManager)
    registerLeaveRoomRoute(workflowManager, workspaceManager, onMemberRevoked)
    registerDeleteRoomRoute(workflowManager, workspaceManager, onRoomDeleted)
}

private fun Route.registerVisibleRoomsRoute(
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
) {
    get("/api/rooms/visible") {
        val callerId = call.resolveAuthenticatedUserId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val groups = GroupRepository.getUserGroups(callerId)
        val unread = UnreadRepository.getUnreadCounts(callerId, groups.map { it.id })
        val rooms = groups.map { group ->
            group.toRoomSummary(callerId, unread[group.id] ?: 0, workflowManager, workspaceManager)
        }.sortedByLatestActivity()
        call.respond(rooms)
    }
}

private fun Route.registerCreateRoomRoute(
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
) {
    post("/api/rooms") {
        val callerId = call.resolveAuthenticatedUserId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val request = call.receive<CreateRoomRequest>()
        if (request.roomKind == RoomKind.SILK_PRIVATE) {
            return@post call.respond(HttpStatusCode.Forbidden)
        }
        val integrationType = request.integrationType?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        if (integrationType != null &&
            (request.roomKind != RoomKind.CHAT || integrationType != CC_CONNECT_INTEGRATION)
        ) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }

        val createResult = GroupService.createRoom(callerId, request.name, request.roomKind)
        val group = createResult.group
            ?: return@post call.respond(HttpStatusCode.BadRequest, createResult)

        val workflow = if (request.roomKind == RoomKind.WORKFLOW) {
            runCatching {
                workflowManager.createWorkflow(
                    name = group.name,
                    description = request.description.trim(),
                    userId = callerId,
                    groupId = group.id,
                    agentType = "claude_code",
                )
            }.getOrElse {
                GroupRepository.deleteGroup(group.id)
                return@post call.respond(HttpStatusCode.InternalServerError)
            }
        } else {
            null
        }

        val ccConnectToken = if (integrationType == CC_CONNECT_INTEGRATION) {
            CcConnectTokenRepository.generateToken(group.id, group.name)
        } else {
            null
        }
        val room = group.toRoomSummary(callerId, 0, workflowManager, workspaceManager)
            .copy(workflowId = workflow?.id)
        call.respond(HttpStatusCode.Created, CreateRoomResponse(room, ccConnectToken))
    }
}

private fun Route.registerRenameRoomRoute(
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
) {
    put("/api/rooms/{roomId}") {
        val callerId = call.resolveAuthenticatedUserId()
            ?: return@put call.respond(HttpStatusCode.Unauthorized)
        val roomId = call.parameters["roomId"].orEmpty()
        val group = GroupRepository.findGroupById(roomId)
        if (group == null || !GroupRepository.isUserInGroup(roomId, callerId)) {
            return@put call.respond(
                HttpStatusCode.NotFound,
                RoomActionResponse(false, "群组不存在"),
            )
        }
        if (group.roomKind == RoomKind.SILK_PRIVATE) {
            return@put call.respond(
                HttpStatusCode.Forbidden,
                RoomActionResponse(false, "Silk 专属会话不能重命名"),
            )
        }
        if (group.hostId != callerId) {
            return@put call.respond(
                HttpStatusCode.Forbidden,
                RoomActionResponse(false, "只有群主才能重命名群组"),
            )
        }

        val newName = call.receive<RenameRoomRequest>().name.trim()
        roomNameValidationError(newName)?.let { error ->
            return@put call.respond(error.status, RoomActionResponse(false, error.message))
        }
        if (newName != group.name && GroupRepository.isGroupNameExists(newName)) {
            return@put call.respond(
                HttpStatusCode.Conflict,
                RoomActionResponse(false, "该群组名称已存在"),
            )
        }

        updateRoomNames(group, callerId, newName, workflowManager)?.let { error ->
            return@put call.respond(error.status, RoomActionResponse(false, error.message))
        }
        CcConnectTokenRepository.updateLabelForGroup(roomId, newName)
        val updated = GroupRepository.findGroupById(roomId)
            ?: return@put call.respond(HttpStatusCode.InternalServerError)
        val unreadCount = UnreadRepository.getUnreadCounts(callerId, listOf(roomId))[roomId] ?: 0
        call.respond(
            RoomActionResponse(
                success = true,
                message = "群组名称已更新",
                room = updated.toRoomSummary(callerId, unreadCount, workflowManager, workspaceManager),
            )
        )
    }
}

private fun roomNameValidationError(name: String): RoomActionError? = when {
    name.isBlank() -> RoomActionError(HttpStatusCode.BadRequest, "群组名称不能为空")
    name.length > MAX_ROOM_NAME_LENGTH -> RoomActionError(
        HttpStatusCode.BadRequest,
        "群组名称不能超过 $MAX_ROOM_NAME_LENGTH 个字符",
    )
    name.startsWith("[Silk]") -> RoomActionError(
        HttpStatusCode.BadRequest,
        "群组名称不能使用系统保留前缀 [Silk]",
    )
    else -> null
}

private fun updateRoomNames(
    group: Group,
    callerId: String,
    newName: String,
    workflowManager: WorkflowManager,
): RoomActionError? {
    val groupNameChanged = newName != group.name
    if (groupNameChanged && !GroupRepository.updateGroupName(group.id, newName)) {
        return RoomActionError(HttpStatusCode.InternalServerError, "群组重命名失败")
    }
    val workflow = workflowManager.getWorkflowByGroupId(group.id)
    if (workflow != null && newName != workflow.name) {
        val updatedWorkflow = workflowManager.renameWorkflow(workflow.id, callerId, newName)
        if (updatedWorkflow == null) {
            if (groupNameChanged) GroupRepository.updateGroupName(group.id, group.name)
            return RoomActionError(HttpStatusCode.InternalServerError, "工作群组重命名失败")
        }
    }
    return null
}

private fun Route.registerLeaveRoomRoute(
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
    onMemberRevoked: suspend (roomId: String, userId: String) -> Unit,
) {
    post("/api/rooms/{roomId}/leave") {
        val callerId = call.resolveAuthenticatedUserId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val roomId = call.parameters["roomId"].orEmpty()
        val group = GroupRepository.findGroupById(roomId)
        if (group == null || !GroupRepository.isUserInGroup(roomId, callerId)) {
            return@post call.respond(
                HttpStatusCode.NotFound,
                RoomActionResponse(false, "群组不存在"),
            )
        }
        if (group.roomKind == RoomKind.SILK_PRIVATE) {
            return@post call.respond(
                HttpStatusCode.Forbidden,
                RoomActionResponse(false, "不能退出 Silk 专属会话"),
            )
        }
        if (group.hostId == callerId) {
            return@post call.respond(
                HttpStatusCode.Conflict,
                RoomActionResponse(false, "群主不能退出群组，请使用删除群组"),
            )
        }

        val roomKind = effectiveRoomKind(roomId, workflowManager, workspaceManager)
        val ownedWorkspaces = if (roomKind == RoomKind.WORKFLOW) {
            workspaceManager.listWorkspaces(callerId, roomId)
        } else {
            emptyList()
        }
        val (success, _) = GroupRepository.leaveGroup(roomId, callerId)
        if (!success) {
            return@post call.respond(
                HttpStatusCode.InternalServerError,
                RoomActionResponse(false, "退出群组失败"),
            )
        }
        if (roomKind == RoomKind.WORKFLOW) {
            ownedWorkspaces.forEach { AgentRuntime.cleanupState(it.ownerId, it.workspaceId) }
            workspaceManager.privatizeWorkspacesOwnedBy(roomId, callerId)
            workspaceManager.removeCopilotFromRoom(roomId, callerId)
        }
        onMemberRevoked(roomId, callerId)
        call.respond(RoomActionResponse(true, "已退出群组"))
    }
}

private fun Route.registerDeleteRoomRoute(
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
    onRoomDeleted: suspend (roomId: String, memberIds: List<String>) -> Unit,
) {
    delete("/api/rooms/{roomId}") {
        val callerId = call.resolveAuthenticatedUserId()
            ?: return@delete call.respond(HttpStatusCode.Unauthorized)
        val roomId = call.parameters["roomId"].orEmpty()
        val group = GroupRepository.findGroupById(roomId)
        if (group == null || !GroupRepository.isUserInGroup(roomId, callerId)) {
            return@delete call.respond(
                HttpStatusCode.NotFound,
                RoomActionResponse(false, "群组不存在"),
            )
        }
        if (group.roomKind == RoomKind.SILK_PRIVATE) {
            return@delete call.respond(
                HttpStatusCode.Forbidden,
                RoomActionResponse(false, "Silk 专属会话不能删除"),
            )
        }
        if (group.hostId != callerId) {
            return@delete call.respond(
                HttpStatusCode.Forbidden,
                RoomActionResponse(false, "只有群主才能删除群组"),
            )
        }

        val roomKind = effectiveRoomKind(roomId, workflowManager, workspaceManager)
        val workflow = workflowManager.getWorkflowByGroupId(roomId)
        val workspaces = if (roomKind == RoomKind.WORKFLOW) {
            workspaceManager.listRoomWorkspaces(roomId)
        } else {
            emptyList()
        }
        if (workflow != null && !workflowManager.deleteWorkflow(workflow.id, callerId)) {
            return@delete call.respond(
                HttpStatusCode.InternalServerError,
                RoomActionResponse(false, "工作群组元数据删除失败"),
            )
        }

        val (success, message, memberIds) = GroupRepository.deleteGroupByHost(roomId, callerId)
        if (!success) {
            return@delete call.respond(
                HttpStatusCode.InternalServerError,
                RoomActionResponse(false, message),
            )
        }
        workspaces.forEach { AgentRuntime.cleanupState(it.ownerId, it.workspaceId) }
        if (roomKind == RoomKind.WORKFLOW) workspaceManager.deleteWorkspacesForRoom(roomId)
        CcConnectTokenRepository.revokeAllForGroup(roomId)
        CcConnectRegistry.disconnect(roomId, "Room deleted")
        onRoomDeleted(roomId, memberIds)
        call.respond(RoomActionResponse(true, message))
    }
}

private fun Group.toRoomSummary(
    callerId: String,
    unreadCount: Int,
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
): RoomSummaryDto {
    val effectiveKind = effectiveRoomKind(id, workflowManager, workspaceManager)
    val workflow = workflowManager.getWorkflowByGroupId(id)
    val ccToken = if (effectiveKind == RoomKind.CHAT) CcConnectTokenRepository.getTokenForGroup(id) else null
    val ccConnection = if (effectiveKind == RoomKind.CHAT) CcConnectRegistry.getConnectionInfo(id) else null
    val integration = if (ccToken != null || ccConnection != null) {
        RoomIntegrationSummaryDto(
            type = "CC_CONNECT",
            label = ccToken?.label.orEmpty(),
            agentType = ccConnection?.agentType.orEmpty(),
            connected = ccConnection != null,
        )
    } else {
        null
    }
    return RoomSummaryDto(
        roomId = id,
        roomKind = effectiveKind,
        name = name.ifBlank { workflow?.name.orEmpty() },
        invitationCode = invitationCode,
        ownerId = hostId,
        ownerDisplayName = hostName.ifBlank { "Room owner" },
        role = when (GroupRepository.getMemberRole(id, callerId)) {
            MemberRole.HOST -> "OWNER"
            MemberRole.OPERATOR -> "OPERATOR"
            MemberRole.GUEST, null -> "MEMBER"
        },
        workflowId = workflow?.id,
        unreadCount = unreadCount,
        createdAt = createdAt,
        createdAtEpochMs = createdAtEpochMs(),
        updatedAt = maxOf(updatedAt, workflow?.updatedAt ?: 0L),
        lastMessageAt = lastMessageAt,
        integration = integration,
    )
}

private fun Group.createdAtEpochMs(): Long = runCatching {
    LocalDateTime.parse(createdAt)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}.getOrDefault(updatedAt)
