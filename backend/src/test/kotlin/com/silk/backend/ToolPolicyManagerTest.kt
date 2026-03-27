package com.silk.backend

import com.silk.backend.ai.ToolPolicyManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolPolicyManagerTest {
    @Test
    fun readFilePolicyAllowsConfiguredChatHistoryPaths() {
        val policy = ToolPolicyManager.getPolicy("read_file")

        val (allowed, message) = ToolPolicyManager.validateFilePath(
            "chat_history/session-1/note.txt",
            policy
        )

        assertTrue(allowed, message)
    }

    @Test
    fun readFilePolicyRejectsSensitiveDotEnvPaths() {
        val policy = ToolPolicyManager.getPolicy("read_file")

        val (allowed, message) = ToolPolicyManager.validateFilePath(".env", policy)

        assertFalse(allowed)
        assertTrue(message.contains("禁止访问"))
    }

    @Test
    fun sandboxedCommandPolicyRejectsUnsafeCommands() {
        val policy = ToolPolicyManager.ToolPolicy(
            name = "sandboxed-command",
            permission = ToolPolicyManager.ToolPermission.SANDBOXED,
            safeCommands = listOf("ls", "pwd"),
            description = "Unit test policy"
        )

        assertTrue(ToolPolicyManager.validateCommand("ls -la", policy).first)

        val (allowed, message) = ToolPolicyManager.validateCommand("rm -rf /", policy)
        assertFalse(allowed)
        assertTrue(message.contains("沙箱模式"))
    }

    @Test
    fun unknownToolsDefaultToDisabled() {
        val policy = ToolPolicyManager.getPolicy("unknown-tool-for-test")

        assertEquals(ToolPolicyManager.ToolPermission.DISABLED, policy.permission)
        assertFalse(ToolPolicyManager.isAllowed("unknown-tool-for-test"))
    }

    @Test
    fun auditLogStoresLatestEntry() {
        val toolName = "unit-test-tool-${System.nanoTime()}"

        ToolPolicyManager.logAudit(
            toolName = toolName,
            permission = ToolPolicyManager.ToolPermission.SANDBOXED,
            result = "DENIED",
            details = "unit-test",
            sessionId = "session-test",
            userId = "user-test"
        )

        val latest = ToolPolicyManager.getAuditLog(1).single()
        assertEquals(toolName, latest.toolName)
        assertEquals("DENIED", latest.result)
        assertEquals("session-test", latest.sessionId)
        assertEquals("user-test", latest.userId)
    }
}
