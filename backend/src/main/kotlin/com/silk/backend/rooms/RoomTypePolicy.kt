package com.silk.backend.rooms

import com.silk.backend.database.GroupRepository
import com.silk.backend.workflow.WorkflowManager
import com.silk.backend.workspace.WorkspaceManager
import com.silk.shared.models.RoomKind
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("RoomTypePolicy")

/** Uses all legacy evidence during migration so a workflow room can never lose its stricter boundary. */
fun isWorkflowRoom(
    roomId: String,
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
): Boolean {
    val groupKind = GroupRepository.findGroupById(roomId)?.roomKind
    val hasWorkflow = workflowManager.getWorkflowByGroupId(roomId) != null
    val hasWorkspace = workspaceManager.listRoomWorkspaces(roomId).isNotEmpty()
    val workflow = groupKind == RoomKind.WORKFLOW || hasWorkflow || hasWorkspace
    if (workflow && groupKind != RoomKind.WORKFLOW) {
        logger.warn(
            "RoomKind mismatch protected as WORKFLOW: roomId={}, groupKind={}, workflowMetadata={}, workspaceMetadata={}",
            roomId,
            groupKind,
            hasWorkflow,
            hasWorkspace,
        )
    }
    return workflow
}

fun effectiveRoomKind(
    roomId: String,
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
): RoomKind {
    if (isWorkflowRoom(roomId, workflowManager, workspaceManager)) return RoomKind.WORKFLOW
    return GroupRepository.findGroupById(roomId)?.roomKind ?: RoomKind.CHAT
}
