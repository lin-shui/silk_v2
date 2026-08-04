package com.silk.backend.routes

import com.silk.backend.agents.acp.AcpRpcException
import com.silk.backend.agents.core.AcpExtensions
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.agents.core.GitChangesAssembler
import com.silk.backend.ccconnect.CcConnectRegistry
import com.silk.backend.database.GroupRepository
import com.silk.backend.resolveAuthenticatedUserId
import com.silk.backend.workspace.PersonalWorkspace
import com.silk.backend.workspace.WorkspaceAccessPolicy
import com.silk.backend.workspace.WorkspaceManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * 只读代码审查（Source Control）路由：工作树 vs HEAD。
 *
 * diff 在 workspace owner 的 bridge 上计算。caller 必须是工作区 Owner 或 Co-pilot。
 */
fun Route.agentChangesRoutes(workspaceManager: WorkspaceManager) {
    route("/api/agent/changes") {
        get { call.respondGitChanges(workspaceManager) }
        get("/file") { call.respondGitFileDiff(workspaceManager) }
    }
}

/** 拒绝绝对路径与越界（../） */
private fun isUnsafePath(path: String): Boolean = path.startsWith("/") || path.contains("..")

/** diff 请求参数校验：缺 workspace/path 或 path 不安全 */
private fun isInvalidDiffRequest(workspaceId: String, path: String): Boolean {
    if (workspaceId.isBlank() || path.isBlank()) return true
    return isUnsafePath(path)
}

private suspend fun ApplicationCall.resolveControllableWorkspace(
    workspaceManager: WorkspaceManager,
): PersonalWorkspace? {
    val callerId = resolveAuthenticatedUserId()
    if (callerId == null) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    val workspaceId = request.queryParameters["workspaceId"].orEmpty()
    val workspace = workspaceManager.getWorkspace(workspaceId)
    if (workspace == null || !GroupRepository.isUserInGroup(workspace.roomId, callerId) ||
        !WorkspaceAccessPolicy.canControl(workspace, callerId)
    ) {
        respond(HttpStatusCode.NotFound)
        return null
    }
    return workspace
}

/** 文件列表 + ±计数 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private suspend fun ApplicationCall.respondGitChanges(workspaceManager: WorkspaceManager) {
    val workspace = resolveControllableWorkspace(workspaceManager) ?: return
    val active = AgentRuntime.ensureActiveAcpSession(workspace.ownerId, workspace.workspaceId)
    if (active == null) {
        // cc-connect 群组无 ACP session：给专属空态而非误导的"未连接"
        val reason = if (CcConnectRegistry.isConnected(workspace.roomId)) "ccconnect" else null
        respond(GitChangesAssembler.assembleChanges(connected = false, supported = true, raw = null).copy(reason = reason))
        return
    }
    val resp = try {
        val raw = AcpExtensions.gitStatus(active.client, active.sessionId)
        GitChangesAssembler.assembleChanges(connected = true, supported = true, raw = raw)
    } catch (e: AcpRpcException) {
        // bridge 已连，但旧版本未广播 git 扩展
        GitChangesAssembler.assembleChanges(connected = true, supported = false, raw = null)
    } catch (e: Exception) {
        GitChangesAssembler.assembleChanges(true, true, null).copy(message = "git error: ${e.message}")
    }
    respond(resp)
}

/** 单文件 unified diff */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private suspend fun ApplicationCall.respondGitFileDiff(workspaceManager: WorkspaceManager) {
    val workspaceId = request.queryParameters["workspaceId"].orEmpty()
    val path = request.queryParameters["path"].orEmpty()
    if (isInvalidDiffRequest(workspaceId, path)) {
        respond(HttpStatusCode.BadRequest, GitChangesAssembler.assembleDiff(true, true, null))
        return
    }
    val workspace = resolveControllableWorkspace(workspaceManager) ?: return
    val active = AgentRuntime.ensureActiveAcpSession(workspace.ownerId, workspace.workspaceId)
    if (active == null) {
        respond(GitChangesAssembler.assembleDiff(connected = false, supported = true, raw = null))
        return
    }
    val resp = try {
        val raw = AcpExtensions.gitDiff(active.client, active.sessionId, path)
        GitChangesAssembler.assembleDiff(connected = true, supported = true, raw = raw)
    } catch (e: AcpRpcException) {
        GitChangesAssembler.assembleDiff(connected = true, supported = false, raw = null)
    } catch (e: Exception) {
        GitChangesAssembler.assembleDiff(true, true, null).copy(message = "git error: ${e.message}")
    }
    respond(resp)
}
