package com.silk.backend.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolPolicyManagerTest {

    @Test
    fun `validateFilePath rejects sandboxed path outside allowed directories`() {
        ToolPolicyManager.resetForTest()
        val policy = ToolPolicyManager.ToolPolicy(
            name = "read_file",
            permission = ToolPolicyManager.ToolPermission.SANDBOXED,
            allowedPaths = listOf("/workspace/uploads"),
            deniedPaths = listOf("/etc"),
            description = "test"
        )

        val result = ToolPolicyManager.validateFilePath("/tmp/other/file.txt", policy)

        assertFalse(result.first)
        assertTrue(result.second.contains("允许访问的目录"))
    }

    @Test
    fun `validateFilePath rejects denied path even when sandbox allows parent`() {
        ToolPolicyManager.resetForTest()
        val policy = ToolPolicyManager.ToolPolicy(
            name = "read_file",
            permission = ToolPolicyManager.ToolPermission.SANDBOXED,
            allowedPaths = listOf("/"),
            deniedPaths = listOf("/etc"),
            description = "test"
        )

        val result = ToolPolicyManager.validateFilePath("/etc/hosts", policy)

        assertFalse(result.first)
        assertTrue(result.second.contains("禁止访问"))
    }

    @Test
    fun `validateCommand enforces disabled and safe command rules`() {
        ToolPolicyManager.resetForTest()
        val disabledPolicy = ToolPolicyManager.ToolPolicy(
            name = "execute_command",
            permission = ToolPolicyManager.ToolPermission.DISABLED,
            safeCommands = listOf("pwd"),
            description = "test"
        )
        val sandboxedPolicy = disabledPolicy.copy(
            permission = ToolPolicyManager.ToolPermission.SANDBOXED
        )

        val disabledResult = ToolPolicyManager.validateCommand("pwd", disabledPolicy)
        val sandboxDenied = ToolPolicyManager.validateCommand("rm -rf /tmp/x", sandboxedPolicy)
        val sandboxAllowed = ToolPolicyManager.validateCommand("pwd", sandboxedPolicy)

        assertEquals(false, disabledResult.first)
        assertEquals(false, sandboxDenied.first)
        assertEquals(true, sandboxAllowed.first)
    }
}
