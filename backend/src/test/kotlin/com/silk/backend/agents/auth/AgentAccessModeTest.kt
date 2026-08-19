package com.silk.backend.agents.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentAccessModeTest {
    @Test
    fun `workspace modes derive the internal permission ceiling`() {
        val readOnly = AgentAccessMode.READ_ONLY.derivedPermissions()
        assertTrue(AgentPermission.READ_FILE in readOnly)
        assertFalse(AgentPermission.WRITE_FILE in readOnly)
        assertFalse(AgentPermission.RUN_COMMAND in readOnly)

        val approval = AgentAccessMode.APPROVAL_REQUIRED.derivedPermissions()
        val autonomous = AgentAccessMode.AUTONOMOUS.derivedPermissions()
        assertEquals(AgentPermission.entries.toSet(), approval)
        assertEquals(approval, autonomous)
    }

    @Test
    fun `legacy granular workspace input migrates conservatively`() {
        assertEquals(
            AgentAccessMode.READ_ONLY,
            resolveAgentAccessMode(
                AgentBindingTargetType.WORKSPACE,
                requestedMode = null,
                legacyPermissions = setOf(AgentPermission.READ_FILE),
            ),
        )
        assertEquals(
            AgentAccessMode.APPROVAL_REQUIRED,
            resolveAgentAccessMode(
                AgentBindingTargetType.WORKSPACE,
                requestedMode = null,
                legacyPermissions = setOf(AgentPermission.WRITE_FILE),
            ),
        )
        assertFailsWith<AgentAuthException> {
            resolveAgentAccessMode(
                AgentBindingTargetType.ROOM,
                requestedMode = AgentAccessMode.AUTONOMOUS,
                legacyPermissions = AgentPermission.entries.toSet(),
            )
        }
    }
}
