package com.silk.backend.workspace

import com.silk.backend.ChatHistoryManager
import com.silk.backend.MessageScope
import com.silk.backend.TestWorkspace
import com.silk.backend.auth.JwtProvider
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.User
import com.silk.backend.database.UserRepository
import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import com.silk.backend.module
import com.silk.backend.routes.WorkspaceDto
import com.silk.backend.workflow.WorkflowManager
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceHistoryMetadataContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `workspace discovery returns named sanitized metadata for observer visible history`() {
        TestWorkspace().use { testWorkspace ->
            val owner = createUser("history-owner", "History Owner", "13800000041")
            val observer = createUser("history-observer", "History Observer", "13800000042")
            val group = assertNotNull(GroupRepository.createGroup("Historical Workspace Room", owner.id))
            assertTrue(GroupRepository.addUserToGroup(group.id, observer.id))
            WorkflowManager(testWorkspace.workflowDir.absolutePath).createWorkflow(
                name = "Historical Workspace",
                description = "",
                userId = owner.id,
                groupId = group.id,
                agentType = "claude_code",
                taskFocus = "",
            )
            val manager = WorkspaceManager(testWorkspace.workflowDir.absolutePath)
            val historical = manager.createWorkspace(
                roomId = group.id,
                ownerId = owner.id,
                name = "Shared Project",
                workingDir = "/private/history-owner/project",
                visibility = WorkspaceVisibility.SHARED,
            )
            seedObserverVisibleHistory(group.id, owner, historical.workspaceId)
            assertTrue(manager.updateActiveAgent(historical.workspaceId, "claude-code", "owner-agent-instance"))
            assertTrue(manager.updateVisibility(historical.workspaceId, WorkspaceVisibility.PRIVATE))
            assertTrue(manager.updateName(historical.workspaceId, "Private Secret Project"))

            testApplication {
                application { module() }
                val ownerWorkspace = fetchWorkspaces(group.id, owner.id).single()
                assertEquals("Private Secret Project", ownerWorkspace.name)
                assertEquals("owner-agent-instance", ownerWorkspace.activeAgentInstanceId)
                assertFalse(ownerWorkspace.historyOnly)

                val observerHistory = fetchWorkspaces(group.id, observer.id).single()
                assertEquals("History Owner", observerHistory.ownerDisplayName)
                assertEquals("Shared Project", observerHistory.name)
                assertEquals("", observerHistory.workingDir)
                assertEquals("", observerHistory.agentType)
                assertEquals("", observerHistory.activeAgentInstanceId)
                assertEquals("OBSERVER", observerHistory.role)
                assertEquals(WorkspaceVisibility.PRIVATE, observerHistory.visibility)
                assertTrue(observerHistory.copilots.isEmpty())
                assertTrue(observerHistory.historyOnly)
                assertEquals(0L, observerHistory.activity.updatedAt)
                assertEquals(0L, observerHistory.createdAt)
                assertEquals(0L, observerHistory.recentActivityAt)
            }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.fetchWorkspaces(
        roomId: String,
        userId: String,
    ): List<WorkspaceDto> {
        val response = client.get("/api/rooms/$roomId/workspaces") {
            header(HttpHeaders.Authorization, "Bearer ${JwtProvider.generateAccessToken(userId)}")
        }
        return json.decodeFromString(response.bodyAsText())
    }

    private fun createUser(loginName: String, fullName: String, phoneNumber: String): User =
        assertNotNull(
            UserRepository.createUser(
                loginName = loginName,
                fullName = fullName,
                phoneNumber = phoneNumber,
                passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
            )
        )

    private fun seedObserverVisibleHistory(roomId: String, owner: User, workspaceId: String) {
        ChatHistoryManager().saveChatHistory(
            sessionName = "group_$roomId",
            chatHistory = ChatHistory(
                sessionId = "session-$roomId",
                messages = mutableListOf(
                    ChatHistoryEntry(
                        messageId = "historical-shared-message",
                        senderId = owner.id,
                        senderName = owner.fullName,
                        content = "shared at send time",
                        timestamp = 1_000L,
                        messageType = "TEXT",
                        scope = MessageScope.WORKSPACE,
                        workspaceId = workspaceId,
                        observerVisible = true,
                    )
                ),
            ),
        )
    }
}
