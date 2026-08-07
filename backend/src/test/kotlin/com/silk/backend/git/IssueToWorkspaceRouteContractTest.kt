package com.silk.backend

import com.silk.backend.TestWorkspace
import com.silk.backend.auth.JwtProvider
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MemberRole
import com.silk.backend.database.User
import com.silk.backend.database.UserRepository
import com.silk.backend.git.GitBindingStatus
import com.silk.backend.git.GitEncryption
import com.silk.backend.git.GitEventStore
import com.silk.backend.git.RoomGitBinding
import com.silk.backend.routes.IssueToWorkspaceRequest
import com.silk.shared.models.CreateRoomRequest
import com.silk.shared.models.CreateRoomResponse
import com.silk.shared.models.RoomKind
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for POST /api/rooms/{roomId}/git/issue-to-workspace.
 *
 * Verifies that every authorization and prerequisite gate is enforced before
 * any GitHub API call or workspace creation is attempted.  The happy path
 * (full workspace creation with a real Bridge and GitHub PAT) is an end-to-end
 * concern verified in manual acceptance; these tests cover the server-side
 * permission and prerequisite logic that must be correct regardless of
 * external connectivity.
 */
class IssueToWorkspaceRouteContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `issue-to-workspace gates enforce membership binding and bridge prerequisites`() {
        TestWorkspace().use { workspace ->
            val gitStore = GitEventStore(workspace.workflowDir.absolutePath)
            val testKey = ByteArray(32) { it.toByte() }

            testApplication {
                application { module() }

                val owner = makeUser("itw-owner", "13902000001")
                val member = makeUser("itw-member", "13902000002")
                val outsider = makeUser("itw-outsider", "13902000003")
                val ownerToken = JwtProvider.generateAccessToken(owner.id)
                val memberToken = JwtProvider.generateAccessToken(member.id)
                val outsiderToken = JwtProvider.generateAccessToken(outsider.id)

                val workflowResp = client.post("/api/rooms") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(CreateRoomRequest("Issue Room", RoomKind.WORKFLOW)))
                }.decode<CreateRoomResponse>()
                val roomId = workflowResp.room.roomId
                GroupRepository.addUserToGroup(roomId, member.id, MemberRole.GUEST)

                val baseRequest = IssueToWorkspaceRequest(
                    issueNumber = 42,
                    workingDir = "/home/user/project",
                )

                // ── Gate 1: membership ──────────────────────────────────────────
                // No token → 401
                assertEquals(HttpStatusCode.Unauthorized,
                    client.post("/api/rooms/$roomId/git/issue-to-workspace") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(baseRequest))
                    }.status)
                // Outsider (non-member) → 404
                assertEquals(HttpStatusCode.NotFound,
                    client.post("/api/rooms/$roomId/git/issue-to-workspace") {
                        header(HttpHeaders.Authorization, "Bearer $outsiderToken")
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(baseRequest))
                    }.status)

                // ── Gate 2: active binding required ────────────────────────────
                // Member, no binding → 409 GITHUB_BINDING_REQUIRED
                val noBinding = client.post("/api/rooms/$roomId/git/issue-to-workspace") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(baseRequest))
                }
                assertEquals(HttpStatusCode.Conflict, noBinding.status)
                assertErrorCode(noBinding.bodyAsText(), "GITHUB_BINDING_REQUIRED")

                // ── Gate 3: agent / bridge checks (reached only with active binding) ─
                // Pre-populate an active binding (hookId=null avoids any GitHub call path)
                gitStore.putBinding(
                    RoomGitBinding(
                        roomId = roomId,
                        owner = "octo",
                        repo = "demo",
                        hookId = null,
                        webhookUrl = "https://silk.test/api/git/webhook/$roomId",
                        tokenEncrypted = GitEncryption.encrypt("bound-pat", testKey),
                        webhookSecretEncrypted = GitEncryption.encrypt("bound-secret", testKey),
                        createdBy = owner.id,
                        createdAt = 1,
                        updatedAt = 1,
                        status = GitBindingStatus.ACTIVE,
                    )
                )

                // With active binding, the route proceeds to agent/bridge checks.
                // In a test environment no agents are registered and no bridges are
                // connected, so the call fails with either UNSUPPORTED_AGENT (400) or
                // BRIDGE_OFFLINE (409) — both are "before workspace creation" gates.
                val withBinding = client.post("/api/rooms/$roomId/git/issue-to-workspace") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(baseRequest))
                }
                assertTrue(
                    withBinding.status == HttpStatusCode.BadRequest ||
                        withBinding.status == HttpStatusCode.Conflict,
                    "Expected 400 (UNSUPPORTED_AGENT) or 409 (BRIDGE_OFFLINE), got ${withBinding.status}",
                )
                val errorCode = errorCode(withBinding.bodyAsText())
                assertTrue(
                    errorCode == "UNSUPPORTED_AGENT" || errorCode == "BRIDGE_OFFLINE",
                    "Expected UNSUPPORTED_AGENT or BRIDGE_OFFLINE, got $errorCode",
                )

                // ── Gate 4: server must reject invalid request fields ───────────
                // issueNumber <= 0 → 400 INVALID_REQUEST
                val badIssue = client.post("/api/rooms/$roomId/git/issue-to-workspace") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(
                        IssueToWorkspaceRequest(issueNumber = 0, workingDir = "/home/user/project")))
                }
                assertEquals(HttpStatusCode.BadRequest, badIssue.status)
                assertErrorCode(badIssue.bodyAsText(), "INVALID_REQUEST")

                // blank workingDir → 400 INVALID_REQUEST
                val blankDir = client.post("/api/rooms/$roomId/git/issue-to-workspace") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(
                        IssueToWorkspaceRequest(issueNumber = 1, workingDir = "   ")))
                }
                assertEquals(HttpStatusCode.BadRequest, blankDir.status)
                assertErrorCode(blankDir.bodyAsText(), "INVALID_REQUEST")
            }
        }
    }

    private fun assertErrorCode(responseBody: String, expected: String) {
        val actual = errorCode(responseBody)
        assertEquals(expected, actual, "Expected errorCode=$expected in: $responseBody")
    }

    private fun errorCode(responseBody: String): String =
        runCatching {
            json.parseToJsonElement(responseBody).jsonObject["errorCode"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")

    private fun makeUser(loginName: String, phone: String): User =
        UserRepository.createUser(
            loginName = loginName,
            fullName = loginName,
            phoneNumber = phone,
            passwordHash = BCrypt.hashpw("secret", BCrypt.gensalt()),
        ) ?: error("Failed to create user $loginName")

    private suspend inline fun <reified T> HttpResponse.decode(): T =
        json.decodeFromString(bodyAsText())
}
