package com.silk.backend.routes

import com.silk.backend.Message
import com.silk.backend.MessageType
import com.silk.backend.TestWorkspace
import com.silk.backend.ChatHistoryManager
import com.silk.backend.database.Group
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.User
import com.silk.backend.database.UserRepository
import com.silk.backend.module
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * dsh bot HTTP 面合同测试：token 生命周期、邀请码入群、时间游标拉取、幂等发送。
 */
class DshBotRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())

    private fun createTestUser(name: String, phone: String): User {
        return UserRepository.createUser(
            loginName = name,
            fullName = name.replaceFirstChar { it.uppercaseChar() },
            phoneNumber = phone,
            passwordHash = "not-a-login-hash"
        )!!
    }

    private fun createGroup(host: User, groupName: String): Group {
        return GroupRepository.createGroup(groupName, host.id)!!
    }

    @Test
    fun `token lifecycle and bearer auth are enforced`() {
        TestWorkspace().use {
            testApplication {
                application { module() }

                // 无 token / 错 token 一律 401
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/dsh-bot/me").status)
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.get("/api/dsh-bot/me") { header(HttpHeaders.Authorization, "Bearer deadbeef") }.status
                )

                // 生成 token：幂等创建 bot 用户并返回明文 token
                val tokenResponse = client.post("/api/dsh-bot/token") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(DshBotTokenRequest(label = "lab-dsh")))
                }
                assertEquals(HttpStatusCode.OK, tokenResponse.status)
                val tokenBody = tokenResponse.decode<DshBotTokenResponse>()
                assertTrue(tokenBody.success)
                assertNotNull(tokenBody.token)
                assertEquals("dsh_ai_agent", tokenBody.userId)

                // token-info 不回显明文
                val info = client.get("/api/dsh-bot/token-info").decode<DshBotTokenInfoResponse>()
                assertTrue(info.success)
                assertEquals("lab-dsh", info.label)

                // 正确 token 可访问 me，尚无群
                val me = client.get("/api/dsh-bot/me") {
                    header(HttpHeaders.Authorization, "Bearer ${tokenBody.token}")
                }.decode<DshBotMeResponse>()
                assertEquals("dsh_ai_agent", me.userId)
                assertTrue(me.groups.isEmpty())

                // regenerate 后旧 token 失效
                client.post("/api/dsh-bot/token") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(DshBotTokenRequest(label = "lab-dsh-2")))
                }
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.get("/api/dsh-bot/me") {
                        header(HttpHeaders.Authorization, "Bearer ${tokenBody.token}")
                    }.status
                )
            }
        }
    }

    @Test
    fun `bot joins a group by invitation code as guest and shows up in me`() {
        TestWorkspace().use {
            testApplication {
                application { module() }

                val host = createTestUser("host1", "13800000101")
                val group = createGroup(host, "dsh-lab")

                val token = client.post("/api/dsh-bot/token") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(DshBotTokenRequest()))
                }.decode<DshBotTokenResponse>().token!!

                // 邀请码无效 → 404
                val badJoin = client.post("/api/dsh-bot/join") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(DshBotJoinRequest(invitationCode = "XXXXXX")))
                }
                assertEquals(HttpStatusCode.NotFound, badJoin.status)

                // 正确邀请码 → GUEST 入群，重复 join 幂等
                repeat(2) {
                    val join = client.post("/api/dsh-bot/join") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(DshBotJoinRequest(invitationCode = group.invitationCode)))
                    }.decode<DshBotJoinResponse>()
                    assertTrue(join.success)
                    assertEquals(group.id, join.group?.id)
                    assertEquals("GUEST", join.group?.role)
                }
                assertEquals(2L, GroupRepository.getGroupMemberCount(group.id))

                val me = client.get("/api/dsh-bot/me") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }.decode<DshBotMeResponse>()
                assertEquals(1, me.groups.size)
                assertEquals(group.id, me.groups.first().id)
                assertEquals("GUEST", me.groups.first().role)
            }
        }
    }

    @Test
    fun `pull returns member messages after cursor and rejects non-members`() {
        TestWorkspace().use {
            testApplication {
                application { module() }

                val host = createTestUser("host2", "13800000102")
                val group = createGroup(host, "dsh-pull")
                val other = createGroup(host, "dsh-other")

                val token = client.post("/api/dsh-bot/token") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(DshBotTokenRequest()))
                }.decode<DshBotTokenResponse>().token!!
                client.post("/api/dsh-bot/join") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(DshBotJoinRequest(invitationCode = group.invitationCode)))
                }

                // 直接落两条历史：人类消息 + 其他 agent 消息
                val history = ChatHistoryManager()
                history.addMessage(
                    "group_${group.id}",
                    Message(id = "m1", userId = host.id, userName = host.fullName, content = "@DSH 帮我修个 bug", timestamp = 1_000L, type = MessageType.TEXT)
                )
                history.addMessage(
                    "group_${group.id}",
                    Message(id = "m2", userId = "silk_ai_agent", userName = "Silk", content = "已在处理", timestamp = 2_000L, type = MessageType.TEXT)
                )

                suspend fun pull(groupId: String, since: Long, bearer: String): HttpResponse = client.get("/api/dsh-bot/messages?groupId=$groupId&since=$since") {
                    header(HttpHeaders.Authorization, "Bearer $bearer")
                }

                val all = pull(group.id, 0L, token).decode<DshBotPullResponse>()
                assertEquals(2, all.messages.size)
                assertEquals("m1", all.messages[0].id)
                assertEquals(false, all.messages[0].bot)
                assertEquals(true, all.messages[1].bot)
                assertTrue(all.serverTime > 0)

                val afterFirst = pull(group.id, 1_000L, token).decode<DshBotPullResponse>()
                assertEquals(listOf("m2"), afterFirst.messages.map { it.id })

                // 未加入的群 → 403；不存在的群 → 404
                assertEquals(HttpStatusCode.Forbidden, pull(other.id, 0L, token).status)
                assertEquals(HttpStatusCode.NotFound, pull("no-such-group", 0L, token).status)
            }
        }
    }

    @Test
    fun `send is idempotent by key and persists once as the bot user`() {
        TestWorkspace().use {
            testApplication {
                application { module() }

                val host = createTestUser("host3", "13800000103")
                val group = createGroup(host, "dsh-send")
                val stranger = createGroup(host, "dsh-send-other")

                val token = client.post("/api/dsh-bot/token") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(DshBotTokenRequest()))
                }.decode<DshBotTokenResponse>().token!!
                client.post("/api/dsh-bot/join") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(DshBotJoinRequest(invitationCode = group.invitationCode)))
                }

                suspend fun send(groupId: String, key: String, text: String): HttpResponse = client.post("/api/dsh-bot/messages") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(DshBotSendRequest(groupId = groupId, idempotencyKey = key, text = text)))
                }

                // 空字段 → 400；未加入的群 → 403
                assertEquals(HttpStatusCode.BadRequest, send(group.id, "", "hi").status)
                assertEquals(HttpStatusCode.Forbidden, send(stranger.id, "k0", "hi").status)

                // 首次 sent，重试同 key → duplicate
                val first = send(group.id, "reply-1", "PR 已提交：#12").decode<DshBotSendResponse>()
                assertTrue(first.success)
                assertEquals("sent", first.status)
                assertEquals("dsh_reply-1", first.messageId)
                val retry = send(group.id, "reply-1", "PR 已提交：#12").decode<DshBotSendResponse>()
                assertEquals("duplicate", retry.status)

                // 历史里只有一条，发送者是 bot
                val messages = ChatHistoryManager()
                    .loadChatHistory("group_${group.id}")!!.messages
                    .filter { it.messageId == "dsh_reply-1" }
                assertEquals(1, messages.size)
                assertEquals("dsh_ai_agent", messages.first().senderId)

                // pull 能看到，且 bot=true
                val pulled = client.get("/api/dsh-bot/messages?groupId=${group.id}&since=0") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }.decode<DshBotPullResponse>()
                val botMessage = pulled.messages.firstOrNull { it.id == "dsh_reply-1" }
                assertNotNull(botMessage)
                assertEquals(true, botMessage.bot)
            }
        }
    }
}
