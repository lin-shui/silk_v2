package com.silk.backend.routes

import com.silk.backend.auth.JwtProvider
import com.silk.backend.workspace.WorkspaceManager
import com.silk.backend.workspace.WorkspaceVisibility
import io.ktor.http.HttpHeaders
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

@Serializable data class CreateWorkspaceRequest(val name: String)

@Serializable data class PatchWorkspaceRequest(
    val name: String? = null,
    val visibility: WorkspaceVisibility? = null,
)

fun Route.workspaceRoutes(workspaceManager: WorkspaceManager) {
    route("/api/rooms/{roomId}/workspaces") {
        post {
            val authHeader = call.request.headers[HttpHeaders.Authorization]
            val token = authHeader?.removePrefix("Bearer ")?.trim()
            val userId = if (token != null) JwtProvider.verifyAccessToken(token) else null
            if (userId == null) return@post call.respond(HttpStatusCode.Unauthorized)
            val roomId = call.parameters["roomId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<CreateWorkspaceRequest>()
            val ws = workspaceManager.createWorkspace(roomId = roomId, ownerId = userId, name = req.name)
            call.respond(HttpStatusCode.Created, ws)
        }

        get {
            val authHeader = call.request.headers[HttpHeaders.Authorization]
            val token = authHeader?.removePrefix("Bearer ")?.trim()
            val userId = if (token != null) JwtProvider.verifyAccessToken(token) else null
            if (userId == null) return@get call.respond(HttpStatusCode.Unauthorized)
            val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(workspaceManager.listWorkspaces(userId, roomId))
        }

        patch("{wsId}") {
            val authHeader = call.request.headers[HttpHeaders.Authorization]
            val token = authHeader?.removePrefix("Bearer ")?.trim()
            val userId = if (token != null) JwtProvider.verifyAccessToken(token) else null
            if (userId == null) return@patch call.respond(HttpStatusCode.Unauthorized)
            val wsId = call.parameters["wsId"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val ws = workspaceManager.getWorkspace(wsId)
            if (ws == null || ws.ownerId != userId) return@patch call.respond(HttpStatusCode.NotFound)
            val req = call.receive<PatchWorkspaceRequest>()
            req.visibility?.let { v -> workspaceManager.updateVisibility(wsId, v) }
            req.name?.let { n -> workspaceManager.updateName(wsId, n) }
            call.respond(workspaceManager.getWorkspace(wsId) ?: HttpStatusCode.NotFound)
        }

        delete("{wsId}") {
            val authHeader = call.request.headers[HttpHeaders.Authorization]
            val token = authHeader?.removePrefix("Bearer ")?.trim()
            val userId = if (token != null) JwtProvider.verifyAccessToken(token) else null
            if (userId == null) return@delete call.respond(HttpStatusCode.Unauthorized)
            val wsId = call.parameters["wsId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val ws = workspaceManager.getWorkspace(wsId)
            if (ws == null || ws.ownerId != userId) return@delete call.respond(HttpStatusCode.NotFound)
            workspaceManager.deleteWorkspace(wsId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
