package com.silk.backend.agents.core

import com.silk.backend.agents.auth.AgentBindingDto
import com.silk.backend.agents.auth.AgentBindingMessageScope
import com.silk.backend.agents.auth.AgentBindingStatus
import com.silk.backend.agents.auth.AgentBindingTargetType
import com.silk.backend.agents.auth.AgentPermission
import com.silk.backend.agents.auth.AgentTriggerPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentBindingTriggerMatcherTest {
    @Test
    fun `mention policy only accepts the bound agent mention`() {
        AgentRuntime.listRegisteredAgents()
        val binding = binding(AgentTriggerPolicy.MENTION)
        assertEquals("hello", AgentBindingTriggerMatcher.promptFor(binding, "@codex hello"))
        assertEquals("/new", AgentBindingTriggerMatcher.promptFor(binding, "@codex /new"))
        assertNull(AgentBindingTriggerMatcher.promptFor(binding, "@cc hello"))
    }

    @Test
    fun `all policy ignores Silk and other explicit agent mentions`() {
        AgentRuntime.listRegisteredAgents()
        val binding = binding(AgentTriggerPolicy.ALL)
        assertEquals("hello", AgentBindingTriggerMatcher.promptFor(binding, "hello"))
        assertNull(AgentBindingTriggerMatcher.promptFor(binding, "@Silk hello"))
        assertNull(AgentBindingTriggerMatcher.promptFor(binding, "@cc hello"))
    }

    private fun binding(policy: AgentTriggerPolicy) = AgentBindingDto(
        bindingId = "binding",
        agentInstanceId = "agent",
        agentType = "codex",
        agentDisplayName = "Codex",
        targetType = AgentBindingTargetType.ROOM,
        targetId = "room",
        messageScope = AgentBindingMessageScope.TEAM,
        triggerPolicy = policy,
        permissions = setOf(AgentPermission.READ_MESSAGE, AgentPermission.SEND_MESSAGE),
        status = AgentBindingStatus.ACTIVE,
        createdBy = "owner",
        ownerId = "owner",
        createdAtEpochMs = 1L,
    )
}
