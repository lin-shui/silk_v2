package com.silk.backend.routes

import com.silk.backend.agents.acp.AcpRpcException
import com.silk.backend.agents.core.AcpExtensions
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.agents.core.GitChangesAssembler
import com.silk.backend.ccconnect.CcConnectRegistry
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
 * 鉴权姿态对齐现有 /users/{userId}/cc-state/{groupId}：从请求读 id、无显式 authenticate{}、
 * 依赖应用级鉴权。diff 在 bridge 机器上算（文件实际所在地），后端只透传与降级。
 */
fun Route.agentChangesRoutes() {
    route("/api/agent/changes") {
        get { call.respondGitChanges() }
        get("/file") { call.respondGitFileDiff() }
    }
}

/** 拒绝绝对路径与越界（../） */
private fun isUnsafePath(path: String): Boolean = path.startsWith("/") || path.contains("..")

/** diff 请求参数校验：缺 id/path 或 path 不安全 */
private fun isInvalidDiffRequest(userId: String, groupId: String, path: String): Boolean {
    if (userId.isBlank() || groupId.isBlank() || path.isBlank()) return true
    return isUnsafePath(path)
}

/** 文件列表 + ±计数 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private suspend fun ApplicationCall.respondGitChanges() {
    val userId = request.queryParameters["userId"].orEmpty()
    val groupId = request.queryParameters["groupId"].orEmpty()
    if (userId.isBlank() || groupId.isBlank()) {
        respond(HttpStatusCode.BadRequest, GitChangesAssembler.assembleChanges(true, true, null))
        return
    }
    val active = AgentRuntime.ensureActiveAcpSession(userId, groupId)
    if (active == null) {
        // cc-connect 群组无 ACP session：给专属空态而非误导的"未连接"
        val reason = if (CcConnectRegistry.isConnected(groupId)) "ccconnect" else null
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
private suspend fun ApplicationCall.respondGitFileDiff() {
    val userId = request.queryParameters["userId"].orEmpty()
    val groupId = request.queryParameters["groupId"].orEmpty()
    val path = request.queryParameters["path"].orEmpty()
    if (isInvalidDiffRequest(userId, groupId, path)) {
        respond(HttpStatusCode.BadRequest, GitChangesAssembler.assembleDiff(true, true, null))
        return
    }
    val active = AgentRuntime.ensureActiveAcpSession(userId, groupId)
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
