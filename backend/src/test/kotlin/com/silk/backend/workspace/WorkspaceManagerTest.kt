package com.silk.backend.workspace

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
