package com.silk.backend.workspace

object WorkspaceAccessPolicy {
    fun resolveInRoom(
        workspaceManager: WorkspaceManager,
        roomId: String,
        workspaceId: String?,
    ): PersonalWorkspace? {
        val id = workspaceId?.takeIf { it.isNotBlank() } ?: return null
        return workspaceManager.getWorkspace(id)?.takeIf { it.roomId == roomId }
    }

    fun canControl(workspace: PersonalWorkspace, userId: String): Boolean =
        workspace.lifecycleState == WorkspaceLifecycleState.ACTIVE &&
            (userId == workspace.ownerId ||
                workspace.visibility == WorkspaceVisibility.SHARED && userId in workspace.copilots)

    fun canRead(
        workspace: PersonalWorkspace,
        userId: String,
        observerVisible: Boolean,
    ): Boolean = userId == workspace.ownerId ||
        workspace.visibility == WorkspaceVisibility.SHARED && userId in workspace.copilots ||
        observerVisible

    fun eligibleUsers(
        workspace: PersonalWorkspace,
        observerVisible: Boolean,
        roomUserIds: Set<String>,
    ): Set<String> = buildSet {
        add(workspace.ownerId)
        if (workspace.visibility == WorkspaceVisibility.SHARED) {
            addAll(workspace.copilots.filter { it in roomUserIds })
        }
        if (observerVisible) addAll(roomUserIds)
    }
}
