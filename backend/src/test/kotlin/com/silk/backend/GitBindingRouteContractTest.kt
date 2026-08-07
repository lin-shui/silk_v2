package com.silk.backend

import com.silk.backend.TestWorkspace
import com.silk.backend.auth.JwtProvider
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MemberRole
import com.silk.backend.database.User
import com.silk.backend.database.UserRepository
import com.silk.backend.git.GitBindingDto
import com.silk.backend.git.GitBindingRequest
import com.silk.backend.git.GitBindingStatus
import com.silk.backend.git.GitEncryption
import com.silk.backend.git.GitEventStore
import com.silk.backend.git.RoomGitBinding
import com.silk.shared.models.CreateRoomRequest
import com.silk.shared.models.CreateRoomResponse
import com.silk.shared.models.RoomKind
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GitBindingRouteContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `binding routes enforce workflow type membership and owner role`() {
        TestWorkspace().use { workspace ->
            val gitStore = GitEventStore(workspace.workflowDir.absolutePath)
            val testKey = ByteArray(32) { it.toByte() }

            testApplication {
                application { module() }

                val owner = makeUser("git-binding-owner", "13901000001")
                val member = makeUser("git-binding-member", "13901000002")
                val outsider = makeUser("git-binding-outsider", "13901000003")
                val ownerToken = JwtProvider.generateAccessToken(owner.id)
                val memberToken = JwtProvider.generateAccessToken(member.id)
                val outsiderToken = JwtProvider.generateAccessToken(outsider.id)

                // WORKFLOW room owned by owner; member joins directly
                val workflowResp = client.post("/api/rooms") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(CreateRoomRequest("GitHub Room", RoomKind.WORKFLOW)))
                }.decode<CreateRoomResponse>()
                val roomId = workflowResp.room.roomId
                GroupRepository.addUserToGroup(roomId, member.id, MemberRole.GUEST)

                // CHAT room — git binding routes must return 404 for any caller
                val chatResp = client.post("/api/rooms") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(CreateRoomRequest("Chat Room", RoomKind.CHAT)))
                }.decode<CreateRoomResponse>()
                val chatRoomId = chatResp.room.roomId

                // ─── GET checks ───────────────────────────────────────────────
                // No JWT → 401
                assertEquals(HttpStatusCode.Unauthorized,
                    client.get("/api/rooms/$roomId/git/binding").status)
                // Outsider (non-member) → 404
                assertEquals(HttpStatusCode.NotFound,
                    client.get("/api/rooms/$roomId/git/binding") {
                        header(HttpHeaders.Authorization, "Bearer $outsiderToken")
                    }.status)
                // CHAT room owner → 404 (not a workflow room)
                assertEquals(HttpStatusCode.NotFound,
                    client.get("/api/rooms/$chatRoomId/git/binding") {
                        header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    }.status)
                // Member on unbound workflow room → 200, enabled=false
                val getDisabled = client.get("/api/rooms/$roomId/git/binding") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                }
                assertEquals(HttpStatusCode.OK, getDisabled.status)
                assertFalse(json.decodeFromString<GitBindingDto>(getDisabled.bodyAsText()).enabled)

                // ─── POST permission checks (no GitHub call reached) ──────────
                // Non-member → 404
                assertEquals(HttpStatusCode.NotFound,
                    client.post("/api/rooms/$roomId/git/binding") {
                        header(HttpHeaders.Authorization, "Bearer $outsiderToken")
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(
                            GitBindingRequest(repositoryUrl = "https://github.com/octo/demo", token = "pat")))
                    }.status)
                // Member (non-owner) → 403
                assertEquals(HttpStatusCode.Forbidden,
                    client.post("/api/rooms/$roomId/git/binding") {
                        header(HttpHeaders.Authorization, "Bearer $memberToken")
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(
                            GitBindingRequest(repositoryUrl = "https://github.com/octo/demo", token = "pat")))
                    }.status)
                // CHAT room → 404
                assertEquals(HttpStatusCode.NotFound,
                    client.post("/api/rooms/$chatRoomId/git/binding") {
                        header(HttpHeaders.Authorization, "Bearer $ownerToken")
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(
                            GitBindingRequest(repositoryUrl = "https://github.com/octo/demo", token = "pat")))
                    }.status)
                // Invalid repository URL → 400
                assertEquals(HttpStatusCode.BadRequest,
                    client.post("/api/rooms/$roomId/git/binding") {
                        header(HttpHeaders.Authorization, "Bearer $ownerToken")
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(
                            GitBindingRequest(repositoryUrl = "not-a-github-url", token = "pat")))
                    }.status)

                // ─── Pre-populate a binding to test GET data exposure and DELETE ─
                // status=ERROR so decryptability check is skipped (no SILK_ENCRYPTION_KEY in test env)
                gitStore.putBinding(
                    RoomGitBinding(
                        roomId = roomId,
                        owner = "octo",
                        repo = "demo",
                        hookId = null,
                        webhookUrl = "https://silk.test/api/git/webhook/$roomId",
                        tokenEncrypted = GitEncryption.encrypt("super-secret-pat", testKey),
                        webhookSecretEncrypted = GitEncryption.encrypt("super-secret-webhook", testKey),
                        createdBy = owner.id,
                        createdAt = 1,
                        updatedAt = 1,
                        status = GitBindingStatus.ERROR,
                    )
                )

                // GET with binding present: credentials must not appear in the response body
                val getWithBinding = client.get("/api/rooms/$roomId/git/binding") {
                    header(HttpHeaders.Authorization, "Bearer $memberToken")
                }
                assertEquals(HttpStatusCode.OK, getWithBinding.status)
                val responseBody = getWithBinding.bodyAsText()
                assertFalse(responseBody.contains("super-secret-pat"),
                    "PAT must not be exposed in GET response")
                assertFalse(responseBody.contains("super-secret-webhook"),
                    "Webhook secret must not be exposed in GET response")
                val dto = json.decodeFromString<GitBindingDto>(responseBody)
                assertEquals("octo", dto.owner)
                assertEquals("demo", dto.repo)

                // ─── DELETE permission checks ─────────────────────────────────
                // Non-member → 404
                assertEquals(HttpStatusCode.NotFound,
                    client.delete("/api/rooms/$roomId/git/binding") {
                        header(HttpHeaders.Authorization, "Bearer $outsiderToken")
                    }.status)
                // Member (non-owner) → 403
                assertEquals(HttpStatusCode.Forbidden,
                    client.delete("/api/rooms/$roomId/git/binding") {
                        header(HttpHeaders.Authorization, "Bearer $memberToken")
                    }.status)
                // Binding still intact after rejected deletes
                assertNotNull(gitStore.getBinding(roomId))

                // Owner → 204; binding erased from local store (hookId=null so no GitHub call)
                assertEquals(HttpStatusCode.NoContent,
                    client.delete("/api/rooms/$roomId/git/binding") {
                        header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    }.status)
                assertNull(gitStore.getBinding(roomId), "Binding must be cleared from store after unbind")
            }
        }
    }

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
