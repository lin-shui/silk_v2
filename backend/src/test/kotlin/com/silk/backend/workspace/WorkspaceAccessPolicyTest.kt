package com.silk.backend.workspace

import com.silk.backend.ChatHistoryManager
import com.silk.backend.ChatServer
import com.silk.backend.Message
import com.silk.backend.MessageScope
import com.silk.backend.filterChatHistoryForUser
import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceAccessPolicyTest {
    @Test
    fun `owner and copilot can control while observer only reads shared snapshots`() {
        withWorkspaceManager { manager ->
            val workspace = manager.createWorkspace("room-1", "owner", "default")
            manager.updateVisibility(workspace.workspaceId, WorkspaceVisibility.SHARED)
            manager.updateCopilots(workspace.workspaceId, listOf("copilot"))
            val updated = assertNotNull(manager.getWorkspace(workspace.workspaceId))

            assertTrue(WorkspaceAccessPolicy.canControl(updated, "owner"))
            assertTrue(WorkspaceAccessPolicy.canControl(updated, "copilot"))
            assertFalse(WorkspaceAccessPolicy.canControl(updated, "observer"))
            assertFalse(WorkspaceAccessPolicy.canRead(updated, "observer", observerVisible = false))
            assertTrue(WorkspaceAccessPolicy.canRead(updated, "observer", observerVisible = true))
        }
    }

    @Test
    fun `archived workspace keeps history readable but rejects new control`() {
        withWorkspaceManager { manager ->
            val workspace = manager.createWorkspace("room-1", "owner", "archive-me")
            manager.updateVisibility(workspace.workspaceId, WorkspaceVisibility.SHARED)
            manager.updateCopilots(workspace.workspaceId, listOf("copilot"))
            manager.updateLifecycleState(workspace.workspaceId, WorkspaceLifecycleState.ARCHIVED)
            val archived = assertNotNull(manager.getWorkspace(workspace.workspaceId))

            assertFalse(WorkspaceAccessPolicy.canControl(archived, "owner"))
            assertFalse(WorkspaceAccessPolicy.canControl(archived, "copilot"))
            assertTrue(WorkspaceAccessPolicy.canRead(archived, "owner", observerVisible = false))
            assertTrue(WorkspaceAccessPolicy.canRead(archived, "copilot", observerVisible = false))
            assertTrue(WorkspaceAccessPolicy.canRead(archived, "observer", observerVisible = true))
            assertFalse(WorkspaceAccessPolicy.canRead(archived, "observer", observerVisible = false))
        }
    }

    @Test
    fun `history filtering keeps the send-time observer visibility snapshot`() {
        withWorkspaceManager { manager ->
            val workspace = manager.createWorkspace("room-1", "owner", "default")
            manager.updateVisibility(workspace.workspaceId, WorkspaceVisibility.SHARED)
            manager.updateCopilots(workspace.workspaceId, listOf("copilot"))
            val history = ChatHistory(
                sessionId = "room-1",
                messages = mutableListOf(
                    entry("team", MessageScope.TEAM),
                    entry("private", MessageScope.WORKSPACE, workspace.workspaceId, observerVisible = false),
                    entry("shared", MessageScope.WORKSPACE, workspace.workspaceId, observerVisible = true),
                    entry("unknown", MessageScope.WORKSPACE, "missing", observerVisible = true),
                ),
            )

            assertEquals(
                listOf("team", "shared"),
                filterChatHistoryForUser(history, "observer", "room-1", manager).messages.map { it.messageId },
            )
            assertEquals(
                listOf("team", "private", "shared"),
                filterChatHistoryForUser(history, "copilot", "room-1", manager).messages.map { it.messageId },
            )
            assertEquals(
                listOf("team", "private", "shared"),
                filterChatHistoryForUser(history, "owner", "room-1", manager).messages.map { it.messageId },
            )
        }
    }

    @Test
    fun `message canonicalization fails closed and snapshots visibility`() {
        val historyRoot = Files.createTempDirectory("chat-server-policy-test").toFile()
        val previousHistoryDir = System.getProperty("silk.chatHistoryDir")
        try {
            System.setProperty("silk.chatHistoryDir", historyRoot.absolutePath)
            withWorkspaceManager { manager ->
                val workspace = manager.createWorkspace("room-1", "owner", "default")
                val server = ChatServer("group_room-1", workspaceManager = manager)

                assertNull(server.canonicalizeMessage(workspaceMessage("missing", "owner")))
                assertNull(server.canonicalizeMessage(workspaceMessage(workspace.workspaceId, "observer")))

                val privateMessage = assertNotNull(
                    server.canonicalizeMessage(workspaceMessage(workspace.workspaceId, "owner"))
                )
                assertFalse(privateMessage.observerVisible)

                manager.updateVisibility(workspace.workspaceId, WorkspaceVisibility.SHARED)
                val sharedMessage = assertNotNull(
                    server.canonicalizeMessage(workspaceMessage(workspace.workspaceId, "owner"))
                )
                assertTrue(sharedMessage.observerVisible)

                val teamMessage = assertNotNull(
                    server.canonicalizeMessage(
                        Message(
                            id = "team-message",
                            userId = "owner",
                            userName = "Owner",
                            content = "team",
                            timestamp = 1L,
                            workspaceId = workspace.workspaceId,
                        )
                    )
                )
                assertEquals(MessageScope.TEAM, teamMessage.scope)
                assertNull(teamMessage.workspaceId)
            }
        } finally {
            if (previousHistoryDir == null) System.clearProperty("silk.chatHistoryDir")
            else System.setProperty("silk.chatHistoryDir", previousHistoryDir)
            historyRoot.deleteRecursively()
        }
    }

    @Test
    fun `chat history persists workspace audience fields across reload`() {
        val root = Files.createTempDirectory("workspace-history-test").toFile()
        try {
            val manager = ChatHistoryManager(root.absolutePath)
            val sessionName = "group_room-1"
            manager.addMessage(
                sessionName,
                Message(
                    id = "workspace-message",
                    userId = "owner",
                    userName = "Owner",
                    content = "private",
                    timestamp = 1L,
                    scope = MessageScope.WORKSPACE,
                    workspaceId = "ws-1",
                    observerVisible = true,
                ),
            )

            val entry = assertNotNull(ChatHistoryManager(root.absolutePath).loadChatHistory(sessionName))
                .messages.single()
            assertEquals(MessageScope.WORKSPACE, entry.scope)
            assertEquals("ws-1", entry.workspaceId)
            assertTrue(entry.observerVisible)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `visible workspace list excludes another owners private workspace`() {
        withWorkspaceManager { manager ->
            val privateWorkspace = manager.createWorkspace("room-1", "owner-a", "private")
            val sharedWorkspace = manager.createWorkspace("room-1", "owner-b", "shared")
            manager.updateVisibility(sharedWorkspace.workspaceId, WorkspaceVisibility.SHARED)
            assertFalse(manager.updateCopilots(privateWorkspace.workspaceId, listOf("copilot")))

            assertEquals(
                listOf(sharedWorkspace.workspaceId),
                manager.listVisibleWorkspaces("observer", "room-1").map { it.workspaceId },
            )
            assertEquals(
                setOf(sharedWorkspace.workspaceId),
                manager.listVisibleWorkspaces("copilot", "room-1").map { it.workspaceId }.toSet(),
            )
        }
    }

    @Test
    fun `archived workspace is only discoverable by its owner`() {
        withWorkspaceManager { manager ->
            val workspace = manager.createWorkspace("room-1", "owner", "archived")
            manager.updateVisibility(workspace.workspaceId, WorkspaceVisibility.SHARED)
            manager.updateLifecycleState(workspace.workspaceId, WorkspaceLifecycleState.ARCHIVED)

            assertEquals(
                listOf(workspace.workspaceId),
                manager.listVisibleWorkspaces("owner", "room-1").map { it.workspaceId },
            )
            assertTrue(manager.listVisibleWorkspaces("observer", "room-1").isEmpty())
        }
    }

    private fun workspaceMessage(workspaceId: String, userId: String) = Message(
        id = "message-$workspaceId-$userId",
        userId = userId,
        userName = userId,
        content = "workspace",
        timestamp = 1L,
        scope = MessageScope.WORKSPACE,
        workspaceId = workspaceId,
    )

    private fun entry(
        id: String,
        scope: MessageScope,
        workspaceId: String? = null,
        observerVisible: Boolean = false,
    ) = ChatHistoryEntry(
        messageId = id,
        senderId = "owner",
        senderName = "Owner",
        content = id,
        timestamp = 1L,
        messageType = "TEXT",
        scope = scope,
        workspaceId = workspaceId,
        observerVisible = observerVisible,
    )

    private fun withWorkspaceManager(block: (WorkspaceManager) -> Unit) {
        val root = Files.createTempDirectory("workspace-policy-test").toFile()
        try {
            block(WorkspaceManager(root.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }
}
