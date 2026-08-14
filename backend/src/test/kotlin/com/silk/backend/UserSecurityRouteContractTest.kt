package com.silk.backend

import com.silk.backend.database.AuthResponse
import com.silk.backend.database.Language
import com.silk.backend.database.LoginRequest
import com.silk.backend.database.RegisterRequest
import com.silk.backend.database.UpdateUserSettingsRequest
import com.silk.backend.database.UserRepository
import com.silk.backend.database.UserSettingsResponse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlin.test.assertTrue

class UserSecurityRouteContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `auth and user routes require the current user bearer`() {
        TestWorkspace().use {
            val user = UserRepository.createUser(
                loginName = "alice",
                fullName = "Alice Chen",
                phoneNumber = "13800000001",
                passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
            ) ?: error("Failed to create test user")

            testApplication {
                application { module() }

                val registerResponse = client.post("/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            RegisterRequest(
                                loginName = "other",
                                fullName = "Other User",
                                phoneNumber = "13800000002",
                                password = "secret123",
                            ),
                        ),
                    )
                }
                assertEquals(HttpStatusCode.OK, registerResponse.status)
                val registerBody = registerResponse.decode<AuthResponse>()
                assertFalse(registerBody.success)
                assertEquals("注册已关闭，请使用华为帐号登录", registerBody.message)
                assertNull(registerBody.user)

                val loginResponse = client.post("/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(LoginRequest("13800000001", "secret123")))
                }.decode<AuthResponse>()
                assertTrue(loginResponse.success)
                assertEquals(user.id, loginResponse.user?.id)
                val accessToken = assertNotNull(loginResponse.accessToken)

                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.get("/auth/validate/${user.id}").status,
                )
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.get("/users/${user.id}/settings").status,
                )
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.put("/users/${user.id}/profile") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"fullName":"Unauthorized"}""")
                    }.status,
                )
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.delete("/users/${user.id}/account").status,
                )

                val validateBody = client.get("/auth/validate/${user.id}") {
                    bearer(accessToken)
                }.decode<AuthResponse>()
                assertTrue(validateBody.success)
                assertEquals("Alice Chen", validateBody.user?.fullName)

                val defaultSettings = client.get("/users/${user.id}/settings") {
                    bearer(accessToken)
                }.decode<UserSettingsResponse>()
                assertTrue(defaultSettings.success)
                assertEquals(Language.CHINESE, defaultSettings.settings?.language)

                val updateSettingsResponse = client.put("/users/${user.id}/settings") {
                    contentType(ContentType.Application.Json)
                    bearer(accessToken)
                    setBody(
                        json.encodeToString(
                            UpdateUserSettingsRequest(
                                userId = user.id,
                                language = Language.ENGLISH,
                                defaultAgentInstruction = "Answer briefly.",
                            ),
                        ),
                    )
                }
                assertEquals(HttpStatusCode.OK, updateSettingsResponse.status)
                val updatedSettings = updateSettingsResponse.decode<UserSettingsResponse>()
                assertTrue(updatedSettings.success)
                assertEquals(Language.ENGLISH, updatedSettings.settings?.language)
                assertEquals("Answer briefly.", updatedSettings.settings?.defaultAgentInstruction)

                val profileResponse = client.put("/users/${user.id}/profile") {
                    contentType(ContentType.Application.Json)
                    bearer(accessToken)
                    setBody("""{"fullName":"Alice Secured"}""")
                }
                assertEquals(HttpStatusCode.OK, profileResponse.status)
                assertEquals("Alice Secured", UserRepository.findUserById(user.id)?.fullName)
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())
}
