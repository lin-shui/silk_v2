package com.silk.backend.workspace

import com.silk.backend.models.AgentSessionState
import com.silk.backend.models.Workflow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Suppress("TooGenericExceptionCaught")
class WorkspaceManager(
    private val baseDir: String =
        System.getProperty("silk.workflowDir")?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv("SILK_WORKFLOW_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${System.getProperty("user.home")}/.silk-data/workflows"
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(WorkspaceManager::class.java)
    private val storeFile get() = File("$baseDir/workspace_store.json")
    private val legacyStoreFile get() = File("$baseDir/workflow_store.json")

    init { File(baseDir).mkdirs() }

    @Synchronized private fun load(): WorkspaceStore =
        if (storeFile.exists()) runCatching { json.decodeFromString<WorkspaceStore>(storeFile.readText()) }
            .getOrElse { logger.error("Failed to load workspace store: {}", it.message); WorkspaceStore() }
        else WorkspaceStore()

    @Synchronized private fun save(store: WorkspaceStore) {
        File(baseDir).mkdirs()
        val tmp = File("${storeFile.path}.tmp")
        tmp.writeText(json.encodeToString(store))
        Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized fun createWorkspace(
        roomId: String,
        ownerId: String,
        name: String,
        agentType: String = "claude-code",
        activeAgentInstanceId: String = "",
        workingDir: String = "",
        visibility: WorkspaceVisibility = WorkspaceVisibility.PRIVATE,
        linkedGithubRef: String? = null,
    ): PersonalWorkspace {
        val store = load()
        val ws = PersonalWorkspace(
            workspaceId = "ws_${System.currentTimeMillis()}_${(1000..9999).random()}",
            roomId = roomId,
            ownerId = ownerId,
            name = name,
            agentType = agentType,
            activeAgent = agentType,
            activeAgentInstanceId = activeAgentInstanceId,
            workingDir = workingDir,
            visibility = visibility,
            linkedGithubRef = linkedGithubRef,
            lastSharedName = name.takeIf { visibility == WorkspaceVisibility.SHARED },
        )
        store.workspaces.add(0, ws)
        save(store)
        logger.info("Created workspace: {} for user {} in room {}", ws.workspaceId, ownerId, roomId)
        return ws
    }

    fun getWorkspace(workspaceId: String): PersonalWorkspace? =
        load().workspaces.find { it.workspaceId == workspaceId }

    fun listWorkspaces(userId: String, roomId: String): List<PersonalWorkspace> =
        load().workspaces.filter { it.ownerId == userId && it.roomId == roomId }

    fun listRoomWorkspaces(roomId: String): List<PersonalWorkspace> =
        load().workspaces.filter { it.roomId == roomId }

    fun listVisibleWorkspaces(userId: String, roomId: String): List<PersonalWorkspace> =
        load().workspaces.filter { workspace ->
            workspace.roomId == roomId && (
                workspace.ownerId == userId ||
                    workspace.lifecycleState == WorkspaceLifecycleState.ACTIVE &&
                    workspace.visibility == WorkspaceVisibility.SHARED
                )
        }.sortedWith(
            compareBy<PersonalWorkspace> { it.lifecycleState != WorkspaceLifecycleState.ACTIVE }
                .thenBy { if (it.ownerId == userId) 0 else if (userId in it.copilots) 1 else 2 }
                .thenByDescending { it.updatedAt }
                .thenBy { it.name.lowercase() }
        )

    @Synchronized
    fun getOrCreateDefaultWorkspace(userId: String, roomId: String): PersonalWorkspace =
        load().workspaces.find { it.ownerId == userId && it.roomId == roomId }
            ?: createWorkspace(roomId = roomId, ownerId = userId, name = "default")

    @Synchronized fun updateWorkingDir(workspaceId: String, workingDir: String): Boolean {
        val store = load()
        val idx = store.workspaces.indexOfFirst { it.workspaceId == workspaceId }
        if (idx < 0) return false
        val current = store.workspaces[idx]
        val dirChanged = current.workingDir != workingDir
        if (dirChanged) {
            store.workspaces[idx] = current.copy(
                workingDir = workingDir,
                updatedAt = System.currentTimeMillis(),
            )
        }
        val recentChanged = rememberWorkingDir(store, current, workingDir)
        if (!dirChanged && !recentChanged) return false
        save(store)
        return true
    }

    /**
     * Returns the last successfully applied directory for one user's exact Agent instance in a Room.
     * Paths are deliberately not shared between Agent instances because they may run on different hosts.
     */
    @Synchronized fun recentWorkingDir(
        roomId: String,
        ownerId: String,
        agentInstanceId: String,
    ): String? {
        val normalizedInstanceId = agentInstanceId.trim()
        if (normalizedInstanceId.isBlank()) return null
        val store = load()
        val remembered = store.recentWorkingDirectories
            .asSequence()
            .filter {
                it.roomId == roomId &&
                    it.ownerId == ownerId &&
                    it.agentInstanceId == normalizedInstanceId &&
                    it.workingDir.isNotBlank()
            }
            .maxByOrNull { it.updatedAt }
            ?.workingDir
        if (!remembered.isNullOrBlank()) return remembered

        // Stores written before directory memory existed can still provide a useful first default.
        return store.workspaces
            .asSequence()
            .filter {
                it.roomId == roomId &&
                    it.ownerId == ownerId &&
                    it.activeAgentInstanceId == normalizedInstanceId &&
                    it.workingDir.isNotBlank()
            }
            .maxByOrNull { it.updatedAt }
            ?.workingDir
    }

    private fun rememberWorkingDir(
        store: WorkspaceStore,
        workspace: PersonalWorkspace,
        workingDir: String,
    ): Boolean {
        val normalizedPath = workingDir.trim()
        val agentInstanceId = workspace.activeAgentInstanceId.trim()
        if (normalizedPath.isBlank() || agentInstanceId.isBlank()) return false
        val index = store.recentWorkingDirectories.indexOfFirst {
            it.roomId == workspace.roomId &&
                it.ownerId == workspace.ownerId &&
                it.agentInstanceId == agentInstanceId
        }
        val current = store.recentWorkingDirectories.getOrNull(index)
        if (current?.workingDir == normalizedPath) return false
        val updated = RecentWorkingDirectory(
            roomId = workspace.roomId,
            ownerId = workspace.ownerId,
            agentInstanceId = agentInstanceId,
            workingDir = normalizedPath,
        )
        if (index >= 0) store.recentWorkingDirectories[index] = updated
        else store.recentWorkingDirectories.add(updated)
        return true
    }

    @Synchronized fun updateSessionState(
        workspaceId: String,
        agentType: String,
        sessionId: String,
        sessionStarted: Boolean,
        agentInstanceId: String? = null,
    ): Boolean {
        val store = load()
        val idx = store.workspaces.indexOfFirst { it.workspaceId == workspaceId }
        if (idx < 0) return false
        val old = store.workspaces[idx]
        val sessionKey = agentInstanceId?.takeIf(String::isNotBlank) ?: agentType
        val newSessions = old.agentSessions.toMutableMap().also {
            it[sessionKey] = AgentSessionState(sessionId, sessionStarted)
        }
        val activeType = old.activeAgent.ifBlank { old.agentType }
        val isActiveSession = agentType == activeType && (
            old.activeAgentInstanceId.isBlank() || old.activeAgentInstanceId == agentInstanceId
        )
        store.workspaces[idx] = old.copy(
            agentSessions = newSessions,
            cliSessionId = if (isActiveSession) sessionId else old.cliSessionId,
            sessionStarted = if (isActiveSession) sessionStarted else old.sessionStarted,
            updatedAt = System.currentTimeMillis()
        )
        save(store); return true
    }

    @Synchronized fun updateActiveAgent(
        workspaceId: String,
        activeAgent: String,
        activeAgentInstanceId: String? = null,
    ): Boolean {
        val store = load()
        val idx = store.workspaces.indexOfFirst { it.workspaceId == workspaceId }
        if (idx < 0) return false
        val normalizedInstanceId = activeAgentInstanceId?.takeIf(String::isNotBlank).orEmpty()
        if (store.workspaces[idx].activeAgent == activeAgent &&
            store.workspaces[idx].activeAgentInstanceId == normalizedInstanceId
        ) return false
        store.workspaces[idx] = store.workspaces[idx].copy(
            activeAgent = activeAgent,
            activeAgentInstanceId = normalizedInstanceId,
            updatedAt = System.currentTimeMillis(),
        )
        save(store); return true
    }

    @Synchronized fun updatePermissionMode(workspaceId: String, permissionMode: String): Boolean {
        val store = load()
        val idx = store.workspaces.indexOfFirst { it.workspaceId == workspaceId }
        if (idx < 0) return false
        if (store.workspaces[idx].permissionMode == permissionMode) return false
        store.workspaces[idx] = store.workspaces[idx].copy(
            permissionMode = permissionMode, updatedAt = System.currentTimeMillis())
        save(store); return true
    }

    /** Returns (workingDir, cliSessionId, sessionStarted); null if no useful seed. */
    fun loadSeed(
        workspaceId: String,
        agentType: String,
        agentInstanceId: String? = null,
    ): Triple<String, String?, Boolean>? {
        val ws = getWorkspace(workspaceId) ?: return null
        val activeType = ws.activeAgent.ifBlank { ws.agentType }
        val sessionKey = agentInstanceId?.takeIf(String::isNotBlank) ?: agentType
        val perAgent = ws.agentSessions[sessionKey]
        val cliSid = perAgent?.sessionId?.takeIf { it.isNotBlank() }
            ?: ws.cliSessionId?.takeIf {
                agentInstanceId.isNullOrBlank() && it.isNotBlank() && agentType == activeType
            }
        val started = perAgent?.sessionStarted
            ?: (agentInstanceId.isNullOrBlank() && ws.sessionStarted && agentType == activeType)
        if (ws.workingDir.isBlank() && cliSid.isNullOrBlank()) return null
        return Triple(ws.workingDir, cliSid, started)
    }

    // ---- Task-3 methods ----

    @Synchronized fun updateName(workspaceId: String, name: String): Boolean {
        val store = load()
        val idx = store.workspaces.indexOfFirst { it.workspaceId == workspaceId }
        if (idx < 0) return false
        if (store.workspaces[idx].name == name) return false
        val current = store.workspaces[idx]
        store.workspaces[idx] = current.copy(
            name = name,
            lastSharedName = name.takeIf { current.visibility == WorkspaceVisibility.SHARED }
                ?: current.lastSharedName,
            updatedAt = System.currentTimeMillis(),
        )
        save(store); return true
    }

    @Synchronized fun updateVisibility(workspaceId: String, visibility: WorkspaceVisibility): Boolean {
        val store = load()
        val idx = store.workspaces.indexOfFirst { it.workspaceId == workspaceId }
        if (idx < 0) return false
        if (store.workspaces[idx].visibility == visibility) return false
        val current = store.workspaces[idx]
        store.workspaces[idx] = current.copy(
            visibility = visibility,
            lastSharedName = if (
                current.visibility == WorkspaceVisibility.SHARED || visibility == WorkspaceVisibility.SHARED
            ) current.name else current.lastSharedName,
            copilots = current.copilots.takeIf {
                visibility == WorkspaceVisibility.SHARED
            }.orEmpty(),
            updatedAt = System.currentTimeMillis(),
        )
        save(store); return true
    }

    @Synchronized fun updateCopilots(workspaceId: String, copilots: List<String>): Boolean {
        val store = load()
        val idx = store.workspaces.indexOfFirst { it.workspaceId == workspaceId }
        if (idx < 0) return false
        val normalized = copilots.filter { it.isNotBlank() }.distinct()
        if (store.workspaces[idx].visibility == WorkspaceVisibility.PRIVATE && normalized.isNotEmpty()) return false
        if (store.workspaces[idx].copilots == normalized) return false
        store.workspaces[idx] = store.workspaces[idx].copy(
            copilots = normalized, updatedAt = System.currentTimeMillis())
        save(store); return true
    }

    @Synchronized fun backfillLastSharedName(workspaceId: String): PersonalWorkspace? {
        val store = load()
        val idx = store.workspaces.indexOfFirst { it.workspaceId == workspaceId }
        if (idx < 0) return null
        val current = store.workspaces[idx]
        if (!current.lastSharedName.isNullOrBlank()) return current
        val updated = current.copy(lastSharedName = current.name)
        store.workspaces[idx] = updated
        save(store)
        return updated
    }

    @Synchronized fun removeCopilotFromRoom(roomId: String, userId: String): Int {
        val store = load()
        var changed = 0
        store.workspaces.replaceAll { workspace ->
            if (workspace.roomId == roomId && userId in workspace.copilots) {
                changed++
                workspace.copy(
                    copilots = workspace.copilots - userId,
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                workspace
            }
        }
        if (changed > 0) save(store)
        return changed
    }

    @Synchronized fun privatizeWorkspacesOwnedBy(roomId: String, ownerId: String): Int {
        val store = load()
        var changed = 0
        store.workspaces.replaceAll { workspace ->
            val belongsToOwner = workspace.roomId == roomId && workspace.ownerId == ownerId
            val needsPrivacyReset = workspace.visibility != WorkspaceVisibility.PRIVATE || workspace.copilots.isNotEmpty()
            if (belongsToOwner && needsPrivacyReset) {
                changed++
                workspace.copy(
                    visibility = WorkspaceVisibility.PRIVATE,
                    lastSharedName = workspace.lastSharedName
                        ?: workspace.name.takeIf { workspace.visibility == WorkspaceVisibility.SHARED },
                    copilots = emptyList(),
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                workspace
            }
        }
        if (changed > 0) save(store)
        return changed
    }

    @Synchronized fun updateLifecycleState(
        workspaceId: String,
        lifecycleState: WorkspaceLifecycleState,
    ): Boolean {
        val store = load()
        val idx = store.workspaces.indexOfFirst { it.workspaceId == workspaceId }
        if (idx < 0) return false
        val current = store.workspaces[idx]
        if (current.lifecycleState == lifecycleState) return false
        val now = System.currentTimeMillis()
        store.workspaces[idx] = current.copy(
            lifecycleState = lifecycleState,
            archivedAt = now.takeIf { lifecycleState == WorkspaceLifecycleState.ARCHIVED },
            updatedAt = now,
        )
        save(store)
        return true
    }

    @Synchronized fun touchWorkspace(workspaceId: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        val store = load()
        val idx = store.workspaces.indexOfFirst { it.workspaceId == workspaceId }
        if (idx < 0) return false
        if (store.workspaces[idx].updatedAt >= timestamp) return false
        store.workspaces[idx] = store.workspaces[idx].copy(updatedAt = timestamp)
        save(store)
        return true
    }

    @Synchronized fun deleteWorkspace(workspaceId: String): Boolean {
        val store = load()
        val removed = store.workspaces.removeAll { it.workspaceId == workspaceId }
        if (removed) {
            save(store)
            logger.info("Deleted workspace: {}", workspaceId)
        }
        return removed
    }

    @Synchronized fun deleteWorkspacesForRoom(roomId: String): Int {
        val store = load()
        val before = store.workspaces.size
        val recentBefore = store.recentWorkingDirectories.size
        store.workspaces.removeAll { it.roomId == roomId }
        store.recentWorkingDirectories.removeAll { it.roomId == roomId }
        val removed = before - store.workspaces.size
        val recentRemoved = recentBefore - store.recentWorkingDirectories.size
        if (removed > 0 || recentRemoved > 0) {
            save(store)
            logger.info("Deleted {} workspace(s) for room {}", removed, roomId)
        }
        return removed
    }

    /** On startup: skip if workspace_store.json already exists; otherwise migrate from workflow_store.json. */
    @Synchronized fun runMigration() {
        if (storeFile.exists()) { logger.info("workspace_store exists, skipping migration"); return }
        if (!legacyStoreFile.exists()) { logger.info("No workflow_store.json, nothing to migrate"); return }
        @Serializable data class OldStore(val workflows: List<Workflow> = emptyList())
        val oldStore = runCatching { json.decodeFromString<OldStore>(legacyStoreFile.readText()) }
            .getOrElse { logger.error("Migration read error: {}", it.message); return }
        val store = WorkspaceStore()
        for (wf in oldStore.workflows) {
            val agent = when (wf.agentType) { "claude_code" -> "claude-code"; else -> wf.agentType }
            store.workspaces.add(PersonalWorkspace(
                workspaceId = "ws_${System.currentTimeMillis()}_${(1000..9999).random()}", roomId = wf.groupId, ownerId = wf.ownerId,
                name = wf.name.ifBlank { "default" }, workingDir = wf.workingDir,
                agentType = agent, cliSessionId = wf.sessionId.takeIf { it.isNotBlank() },
                sessionStarted = wf.sessionStarted, activeAgent = wf.activeAgent,
                agentSessions = wf.agentSessions, permissionMode = wf.permissionMode,
                createdAt = wf.createdAt, updatedAt = wf.updatedAt,
            ))
        }
        save(store)
        logger.info("Migrated {} workflows -> workspaces", store.workspaces.size)
    }
}
