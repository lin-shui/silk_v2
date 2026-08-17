package com.silk.backend.workspace

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceManagerTest {

    private lateinit var tempDir: File

    @BeforeTest fun setUp() {
        tempDir = Files.createTempDirectory("workspace_test").toFile()
    }

    @AfterTest fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test fun `create and retrieve workspace`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)
        val ws = mgr.createWorkspace("room1", "user1", "default")
        assertEquals("room1", ws.roomId)
        assertEquals("user1", ws.ownerId)
        assertTrue(ws.workspaceId.startsWith("ws_"))
        assertNotNull(mgr.getWorkspace(ws.workspaceId))
    }

    @Test fun `linked github reference persists and legacy workspaces default to null`() {
        val linked = "https://github.com/octo/demo/issues/42"
        val mgr = WorkspaceManager(tempDir.absolutePath)
        val ws = mgr.createWorkspace("room1", "user1", "issue", linkedGithubRef = linked)
        assertEquals(linked, mgr.getWorkspace(ws.workspaceId)!!.linkedGithubRef)

        File(tempDir, "workspace_store.json").writeText(
            """{"workspaces":[{"workspaceId":"legacy","roomId":"room1","ownerId":"user1","name":"old"}]}"""
        )
        assertNull(WorkspaceManager(tempDir.absolutePath).getWorkspace("legacy")!!.linkedGithubRef)
        assertEquals("", WorkspaceManager(tempDir.absolutePath).getWorkspace("legacy")!!.activeAgentInstanceId)
    }

    @Test fun `active agent selection persists the exact instance`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)
        val workspace = mgr.createWorkspace("room1", "user1", "exact-agent")

        assertTrue(mgr.updateActiveAgent(workspace.workspaceId, "claude-code", "agent-linux"))

        val updated = mgr.getWorkspace(workspace.workspaceId)!!
        assertEquals("claude-code", updated.activeAgent)
        assertEquals("agent-linux", updated.activeAgentInstanceId)
    }

    @Test fun `workspace creation persists its selected agent instance`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)

        val workspace = mgr.createWorkspace(
            roomId = "room1",
            ownerId = "user1",
            name = "windows-agent",
            agentType = "claude-code",
            activeAgentInstanceId = "agent-windows",
        )

        val persisted = mgr.getWorkspace(workspace.workspaceId)!!
        assertEquals("claude-code", persisted.activeAgent)
        assertEquals("agent-windows", persisted.activeAgentInstanceId)
    }

    @Test fun `same type agent sessions are isolated by instance`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)
        val workspace = mgr.createWorkspace("room1", "user1", "session-isolation")
        mgr.updateWorkingDir(workspace.workspaceId, "/project")
        mgr.updateActiveAgent(workspace.workspaceId, "claude-code", "agent-linux")
        mgr.updateSessionState(
            workspace.workspaceId,
            "claude-code",
            "session-linux",
            true,
            "agent-linux",
        )
        mgr.updateSessionState(
            workspace.workspaceId,
            "claude-code",
            "session-windows",
            true,
            "agent-windows",
        )

        assertEquals("session-linux", mgr.loadSeed(workspace.workspaceId, "claude-code", "agent-linux")!!.second)
        assertEquals("session-windows", mgr.loadSeed(workspace.workspaceId, "claude-code", "agent-windows")!!.second)
    }

    @Test fun `getOrCreateDefaultWorkspace is idempotent`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)
        val ws1 = mgr.getOrCreateDefaultWorkspace("user1", "room1")
        val ws2 = mgr.getOrCreateDefaultWorkspace("user1", "room1")
        assertEquals(ws1.workspaceId, ws2.workspaceId)
    }

    @Test fun `listWorkspaces filters by user and room`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)
        mgr.createWorkspace("room1", "user1", "ws-a")
        mgr.createWorkspace("room1", "user1", "ws-b")
        mgr.createWorkspace("room1", "user2", "ws-c")
        mgr.createWorkspace("room2", "user1", "ws-d")
        assertEquals(2, mgr.listWorkspaces("user1", "room1").size)
    }

    @Test fun `updateWorkingDir persists`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)
        val ws = mgr.createWorkspace("room1", "user1", "default")
        mgr.updateWorkingDir(ws.workspaceId, "/home/user/project")
        assertEquals("/home/user/project", mgr.getWorkspace(ws.workspaceId)!!.workingDir)
    }

    @Test fun `recent working directory is persisted per room user and agent instance`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)
        val linuxWorkspace = mgr.createWorkspace(
            "room1",
            "user1",
            "linux",
            activeAgentInstanceId = "agent-linux",
        )
        val windowsWorkspace = mgr.createWorkspace(
            "room1",
            "user1",
            "windows",
            activeAgentInstanceId = "agent-windows",
        )

        mgr.updateWorkingDir(linuxWorkspace.workspaceId, "/home/user/project-a")
        mgr.updateWorkingDir(windowsWorkspace.workspaceId, "C:\\Users\\user\\project-b")

        val reloaded = WorkspaceManager(tempDir.absolutePath)
        assertEquals("/home/user/project-a", reloaded.recentWorkingDir("room1", "user1", "agent-linux"))
        assertEquals("C:\\Users\\user\\project-b", reloaded.recentWorkingDir("room1", "user1", "agent-windows"))
        assertNull(reloaded.recentWorkingDir("room1", "user1", "other-agent"))
        assertNull(reloaded.recentWorkingDir("room2", "user1", "agent-linux"))

        reloaded.deleteWorkspacesForRoom("room1")
        assertNull(WorkspaceManager(tempDir.absolutePath).recentWorkingDir("room1", "user1", "agent-linux"))
    }

    @Test fun `recent working directory falls back to legacy workspace state`() {
        File(tempDir, "workspace_store.json").writeText(
            """{"workspaces":[{"workspaceId":"legacy","roomId":"room1","ownerId":"user1",
              "name":"legacy","workingDir":"/legacy/project","activeAgentInstanceId":"agent-linux",
              "updatedAt":1000}]}"""
        )

        assertEquals(
            "/legacy/project",
            WorkspaceManager(tempDir.absolutePath).recentWorkingDir("room1", "user1", "agent-linux"),
        )
    }

    @Test fun `archive and restore persist lifecycle timestamps`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)
        val workspace = mgr.createWorkspace("room1", "user1", "lifecycle")

        assertTrue(mgr.updateLifecycleState(workspace.workspaceId, WorkspaceLifecycleState.ARCHIVED))
        val archived = mgr.getWorkspace(workspace.workspaceId)!!
        assertEquals(WorkspaceLifecycleState.ARCHIVED, archived.lifecycleState)
        assertNotNull(archived.archivedAt)

        assertTrue(mgr.updateLifecycleState(workspace.workspaceId, WorkspaceLifecycleState.ACTIVE))
        val restored = mgr.getWorkspace(workspace.workspaceId)!!
        assertEquals(WorkspaceLifecycleState.ACTIVE, restored.lifecycleState)
        assertNull(restored.archivedAt)
    }

    @Test fun `private visibility clears and rejects copilots`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)
        val workspace = mgr.createWorkspace(
            roomId = "room1",
            ownerId = "owner",
            name = "shared",
            visibility = WorkspaceVisibility.SHARED,
        )

        assertTrue(mgr.updateCopilots(workspace.workspaceId, listOf("copilot")))
        assertTrue(mgr.updateVisibility(workspace.workspaceId, WorkspaceVisibility.PRIVATE))
        assertEquals(emptyList(), mgr.getWorkspace(workspace.workspaceId)!!.copilots)
        assertTrue(!mgr.updateCopilots(workspace.workspaceId, listOf("copilot")))
    }

    @Test fun `last shared name is preserved after workspace becomes private`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)
        val workspace = mgr.createWorkspace(
            roomId = "room1",
            ownerId = "owner",
            name = "shared-name",
            visibility = WorkspaceVisibility.SHARED,
        )

        assertTrue(mgr.updateName(workspace.workspaceId, "shared-renamed"))
        assertTrue(mgr.updateVisibility(workspace.workspaceId, WorkspaceVisibility.PRIVATE))
        assertTrue(mgr.updateName(workspace.workspaceId, "private-secret-name"))

        val updated = mgr.getWorkspace(workspace.workspaceId)!!
        assertEquals("private-secret-name", updated.name)
        assertEquals("shared-renamed", updated.lastSharedName)
    }

    @Test fun `legacy shared workspace captures its name when becoming private`() {
        File(tempDir, "workspace_store.json").writeText(
            """{"workspaces":[{"workspaceId":"legacy-shared","roomId":"room1","ownerId":"owner",
              "name":"legacy-shared-name","visibility":"SHARED"}]}"""
        )
        val mgr = WorkspaceManager(tempDir.absolutePath)

        assertTrue(mgr.updateVisibility("legacy-shared", WorkspaceVisibility.PRIVATE))
        assertTrue(mgr.updateName("legacy-shared", "private-secret-name"))

        val updated = mgr.getWorkspace("legacy-shared")!!
        assertEquals("private-secret-name", updated.name)
        assertEquals("legacy-shared-name", updated.lastSharedName)
    }

    @Test fun `legacy private history name is frozen when first backfilled`() {
        File(tempDir, "workspace_store.json").writeText(
            """{"workspaces":[{"workspaceId":"legacy-private","roomId":"room1","ownerId":"owner",
              "name":"best-known-shared-name","visibility":"PRIVATE"}]}"""
        )
        val mgr = WorkspaceManager(tempDir.absolutePath)

        val backfilled = mgr.backfillLastSharedName("legacy-private")!!
        assertEquals("best-known-shared-name", backfilled.lastSharedName)
        assertTrue(mgr.updateName("legacy-private", "private-secret-name"))

        val updated = mgr.getWorkspace("legacy-private")!!
        assertEquals("private-secret-name", updated.name)
        assertEquals("best-known-shared-name", updated.lastSharedName)
    }

    @Test fun `migrates from workflow_store json`() {
        File(tempDir, "workflow_store.json").writeText(
            """{"workflows":[{"id":"wf_001","name":"My WF","description":"","ownerId":"u1",
              "groupId":"grp1","agentType":"claude_code","taskFocus":"","createdAt":1000,
              "updatedAt":2000,"workingDir":"/proj","sessionId":"sess_abc",
              "sessionStarted":true,"activeAgent":"claude-code","agentSessions":{},
              "permissionMode":"INTERACTIVE"}]}"""
        )
        val mgr = WorkspaceManager(tempDir.absolutePath)
        mgr.runMigration()
        val list = mgr.listWorkspaces("u1", "grp1")
        assertEquals(1, list.size)
        assertEquals("claude-code", list[0].agentType)
        assertEquals("/proj", list[0].workingDir)
        assertEquals("sess_abc", list[0].cliSessionId)
        assertTrue(list[0].sessionStarted)
    }

    @Test fun `migration skips if workspace_store exists`() {
        val mgr = WorkspaceManager(tempDir.absolutePath)
        mgr.createWorkspace("room1", "user1", "existing")
        File(tempDir, "workflow_store.json").writeText("""""")
        mgr.runMigration()
        assertEquals(1, mgr.listWorkspaces("user1", "room1").size)
    }
}
