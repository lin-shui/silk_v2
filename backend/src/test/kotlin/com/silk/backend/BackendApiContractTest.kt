package com.silk.backend

import com.silk.backend.database.AuthResponse
import com.silk.backend.database.ContactResponse
import com.silk.backend.database.CreateGroupRequest
import com.silk.backend.database.GroupMembersResponse
import com.silk.backend.database.GroupResponse
import com.silk.backend.database.HandleContactRequestData
import com.silk.backend.database.JoinGroupRequest
import com.silk.backend.database.LoginRequest
import com.silk.backend.database.MarkReadRequest
import com.silk.backend.database.PrivateChatResponse
import com.silk.backend.database.RecallMessageRequest
import com.silk.backend.database.RegisterRequest
import com.silk.backend.database.SendContactRequestByIdData
import com.silk.backend.database.SendMessageRequest
import com.silk.backend.database.SimpleResponse
import com.silk.backend.database.StartPrivateChatRequest
import com.silk.backend.database.UnreadCountResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackendApiContractTest : BackendContractTestBase() {
    @Test
    fun registerAndLoginSupportMultipleIdentifiers() = testApplication {
        application {
            module()
        }
        val client = jsonClient()

        val registered = registerUser(
            client = client,
            loginName = "alice_login",
            fullName = "Alice Zhang",
            phoneNumber = "13800000001"
        )
        val registeredUser = assertNotNull(registered.user)

        assertTrue(registered.success)

        val byLoginName = login(client, "alice_login")
        val byPhone = login(client, "13800000001")
        val byFullName = login(client, "Alice Zhang")

        assertTrue(byLoginName.success)
        assertTrue(byPhone.success)
        assertTrue(byFullName.success)
        assertEquals(registeredUser.id, byPhone.user?.id)
        assertEquals(registeredUser.id, byFullName.user?.id)
    }

    @Test
    fun registerRejectsDuplicateLoginName() = testApplication {
        application {
            module()
        }
        val client = jsonClient()

        val first = registerUser(
            client = client,
            loginName = "same_login",
            fullName = "First User",
            phoneNumber = "13800000002"
        )
        val second = registerUser(
            client = client,
            loginName = "same_login",
            fullName = "Second User",
            phoneNumber = "13800000003"
        )

        assertTrue(first.success)
        assertEquals(false, second.success)
        assertEquals("该登录名已被使用", second.message)
    }

    @Test
    fun createGroupAndJoinByInvitationCode() = testApplication {
        application {
            module()
        }
        val client = jsonClient()

        val host = registerUser(client, "host_login", "Host User", "13800000004").user!!
        val guest = registerUser(client, "guest_login", "Guest User", "13800000005").user!!

        val createdGroup = client.post("/groups/create") {
            contentType(ContentType.Application.Json)
            setBody(CreateGroupRequest(userId = host.id, groupName = "Contract Group"))
        }
        assertEquals(HttpStatusCode.OK, createdGroup.status)
        val groupResponse = createdGroup.body<GroupResponse>()
        assertTrue(groupResponse.success)
        val group = assertNotNull(groupResponse.group)

        val joined = client.post("/groups/join") {
            contentType(ContentType.Application.Json)
            setBody(JoinGroupRequest(userId = guest.id, invitationCode = group.invitationCode))
        }
        assertEquals(HttpStatusCode.OK, joined.status)
        val joinResponse = joined.body<GroupResponse>()
        assertTrue(joinResponse.success)
        assertEquals(group.id, joinResponse.group?.id)

        val membersResponse = client.get("/groups/${group.id}/members")
        val members = membersResponse.body<GroupMembersResponse>()
        assertEquals(true, members.success)
        assertEquals(2, members.members.size)
        assertTrue(members.members.any { it.id == host.id })
        assertTrue(members.members.any { it.id == guest.id })
    }

    @Test
    fun acceptingContactRequestAllowsPrivateChatCreation() = testApplication {
        application {
            module()
        }
        val client = jsonClient()

        val alice = registerUser(client, "alice_contact", "Alice Contact", "13800000006").user!!
        val bob = registerUser(client, "bob_contact", "Bob Contact", "13800000007").user!!

        val requestResponse = client.post("/contacts/request-by-id") {
            contentType(ContentType.Application.Json)
            setBody(SendContactRequestByIdData(fromUserId = alice.id, toUserId = bob.id))
        }
        val createdRequest = requestResponse.body<ContactResponse>()
        assertTrue(createdRequest.success)

        val pending = client.get("/contacts/${bob.id}").body<ContactResponse>()
        assertEquals(1, pending.pendingRequests?.size)
        val pendingRequest = assertNotNull(pending.pendingRequests?.singleOrNull())

        val handled = client.post("/contacts/handle-request") {
            contentType(ContentType.Application.Json)
            setBody(
                HandleContactRequestData(
                    requestId = pendingRequest.id,
                    userId = bob.id,
                    accept = true
                )
            )
        }.body<ContactResponse>()
        assertTrue(handled.success)

        val privateChat = client.post("/contacts/private-chat") {
            contentType(ContentType.Application.Json)
            setBody(StartPrivateChatRequest(userId = alice.id, contactId = bob.id))
        }.body<PrivateChatResponse>()

        assertTrue(privateChat.success)
        assertTrue(privateChat.isNew)
        assertNotNull(privateChat.group)
    }

    @Test
    fun sendMessagePersistsHistoryAndTracksUnread() = testApplication {
        application {
            module()
        }
        val client = jsonClient()

        val host = registerUser(client, "send_host", "Send Host", "13800000008").user!!
        val guest = registerUser(client, "send_guest", "Send Guest", "13800000009").user!!
        val group = createGroupAndJoinGuest(client, host.id, guest.id)

        val sendResponse = client.post("/api/messages/send") {
            contentType(ContentType.Application.Json)
            setBody(
                SendMessageRequest(
                    groupId = group.id,
                    userId = host.id,
                    userName = host.fullName,
                    content = "hello contract test"
                )
            )
        }.body<SimpleResponse>()

        assertTrue(sendResponse.success)

        val history = ChatHistoryManager().loadChatHistory(group.id)
        assertNotNull(history)
        assertEquals(1, history.messages.size)
        assertEquals("hello contract test", history.messages.single().content)

        val unreadBeforeMarkRead = client.get("/api/unread/${guest.id}").body<UnreadCountResponse>()
        assertEquals(1, unreadBeforeMarkRead.unreadCounts[group.id])

        val marked = client.post("/api/unread/mark-read") {
            contentType(ContentType.Application.Json)
            setBody(MarkReadRequest(userId = guest.id, groupId = group.id))
        }.body<SimpleResponse>()
        assertTrue(marked.success)

        val unreadAfterMarkRead = client.get("/api/unread/${guest.id}").body<UnreadCountResponse>()
        assertEquals(0, unreadAfterMarkRead.unreadCounts[group.id])
    }

    @Test
    fun recallMessageRemovesItFromPersistedHistory() = testApplication {
        application {
            module()
        }
        val client = jsonClient()

        val host = registerUser(client, "recall_host", "Recall Host", "13800000010").user!!
        val group = client.post("/groups/create") {
            contentType(ContentType.Application.Json)
            setBody(CreateGroupRequest(userId = host.id, groupName = "Recall Group"))
        }.body<GroupResponse>().group!!

        val sent = client.post("/api/messages/send") {
            contentType(ContentType.Application.Json)
            setBody(
                SendMessageRequest(
                    groupId = group.id,
                    userId = host.id,
                    userName = host.fullName,
                    content = "message to recall"
                )
            )
        }.body<SimpleResponse>()
        assertTrue(sent.success)

        val historyBeforeRecall = assertNotNull(ChatHistoryManager().loadChatHistory(group.id))
        val messageId = historyBeforeRecall.messages.single().messageId

        val recalled = client.post("/api/messages/recall") {
            contentType(ContentType.Application.Json)
            setBody(
                RecallMessageRequest(
                    groupId = group.id,
                    messageId = messageId,
                    userId = host.id
                )
            )
        }.body<SimpleResponse>()

        assertTrue(recalled.success)
        val historyAfterRecall = assertNotNull(ChatHistoryManager().loadChatHistory(group.id))
        assertTrue(historyAfterRecall.messages.isEmpty())
    }

    @Test
    fun nonMemberCannotSendMessage() = testApplication {
        application {
            module()
        }
        val client = jsonClient()

        val host = registerUser(client, "non_member_host", "Host", "13800000011").user!!
        val outsider = registerUser(client, "outsider", "Outsider", "13800000012").user!!
        val group = client.post("/groups/create") {
            contentType(ContentType.Application.Json)
            setBody(CreateGroupRequest(userId = host.id, groupName = "Membership Group"))
        }.body<GroupResponse>().group!!

        val response = client.post("/api/messages/send") {
            contentType(ContentType.Application.Json)
            setBody(
                SendMessageRequest(
                    groupId = group.id,
                    userId = outsider.id,
                    userName = outsider.fullName,
                    content = "should be rejected"
                )
            )
        }.body<SimpleResponse>()

        assertEquals(false, response.success)
        assertEquals("您不是该群组成员", response.message)
    }

    private suspend fun registerUser(
        client: HttpClient,
        loginName: String,
        fullName: String,
        phoneNumber: String,
        password: String = "secret123"
    ): AuthResponse {
        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    loginName = loginName,
                    fullName = fullName,
                    phoneNumber = phoneNumber,
                    password = password
                )
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun login(
        client: HttpClient,
        identifier: String,
        password: String = "secret123"
    ): AuthResponse {
        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(loginName = identifier, password = password))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun createGroupAndJoinGuest(
        client: HttpClient,
        hostId: String,
        guestId: String
    ): com.silk.backend.database.Group {
        val created = client.post("/groups/create") {
            contentType(ContentType.Application.Json)
            setBody(CreateGroupRequest(userId = hostId, groupName = "Messaging Group"))
        }.body<GroupResponse>()
        val group = assertNotNull(created.group)

        val joined = client.post("/groups/join") {
            contentType(ContentType.Application.Json)
            setBody(JoinGroupRequest(userId = guestId, invitationCode = group.invitationCode))
        }.body<GroupResponse>()
        assertTrue(joined.success)
        return group
    }
}
