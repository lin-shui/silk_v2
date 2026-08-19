package com.silk.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceManagementModelsTest {
    @Test
    fun pairingCodeInputIsNormalizedAndFormattedWithoutPunctuation() {
        assertEquals("ABCD5678", normalizePairingCode(" abcd-5678 "))
        assertEquals("ABCD5678", normalizePairingCode("设备 abcd-5678"))
        assertEquals("ABCD-5678", formatPairingCode("ab cd_5678"))
        assertEquals("ABCD-5678", formatPairingCode("abcd5678ignored"))
    }

    @Test
    fun pairingLinkReadsOnlyACompleteCodeFromTheFragment() {
        assertEquals("ABCD-5678", pairingCodeFromFragment("#code=abcd-5678"))
        assertEquals("ABCD-5678", pairingCodeFromFragment("#source=agent&code=ABCD5678"))
        assertEquals(null, pairingCodeFromFragment("#code=short"))
        assertEquals(null, pairingCodeFromFragment("#code=ABCD-5678-extra"))
        assertEquals(null, pairingCodeFromFragment("#code=ABCD%2D5678"))
        assertEquals(null, pairingCodeFromFragment("#account=owner"))
    }

    @Test
    fun bindingTargetSelectsTheProtocolCompatibleMessageScope() {
        assertEquals(BindingMessageScope.TEAM, compatibleMessageScope(BindingTargetType.ROOM))
        assertEquals(BindingMessageScope.WORKSPACE, compatibleMessageScope(BindingTargetType.WORKSPACE))
    }

    @Test
    fun roomBindingRolesMatchVisibleRoomContract() {
        assertTrue(canBindRoomRole("OWNER"))
        assertTrue(canBindRoomRole("operator"))
        assertFalse(canBindRoomRole("MEMBER"))
    }

    @Test
    fun bindingsExposeOnlyRoomChatOrWorkspaceAccessModes() {
        assertEquals(
            BindingAccessMode.CHAT_ONLY,
            defaultBindingAccessMode(BindingTargetType.ROOM),
        )
        assertEquals(
            BindingAccessMode.APPROVAL_REQUIRED,
            defaultBindingAccessMode(BindingTargetType.WORKSPACE),
        )
        assertEquals(
            listOf(
                BindingAccessMode.READ_ONLY,
                BindingAccessMode.APPROVAL_REQUIRED,
                BindingAccessMode.AUTONOMOUS,
            ),
            availableBindingAccessModes(BindingTargetType.WORKSPACE),
        )
        assertEquals("需审批", bindingAccessModeLabel(BindingAccessMode.APPROVAL_REQUIRED))
    }

    @Test
    fun agentCapabilitiesHaveReadableDetailLabels() {
        assertEquals("停止生成", agentCapabilityLabel(ManagedAgentCapability.CANCEL))
        assertEquals("执行策略 V1", agentCapabilityLabel(ManagedAgentCapability.EXECUTION_POLICY_V1))
        assertEquals("执行策略 V2", agentCapabilityLabel(ManagedAgentCapability.EXECUTION_POLICY_V2))
        assertEquals("图片输出", agentCapabilityLabel(ManagedAgentCapability.IMAGE_OUTPUT))
    }

    @Test
    fun agentRuntimePermissionModesHaveStableLabels() {
        assertEquals("原生默认", agentRuntimePermissionModeLabel(ManagedAgentRuntimePermissionMode.NATIVE_DEFAULT))
        assertEquals("需要审批", agentRuntimePermissionModeLabel(ManagedAgentRuntimePermissionMode.APPROVAL_REQUIRED))
        assertEquals("只读", agentRuntimePermissionModeLabel(ManagedAgentRuntimePermissionMode.READ_ONLY))
        assertEquals("自动执行", agentRuntimePermissionModeLabel(ManagedAgentRuntimePermissionMode.AUTOMATIC))
    }

    @Test
    fun roomAgentMentionsAreNormalizedAndReservedNamesAreRejected() {
        assertEquals("cc", defaultManagedAgentMentionAlias("claude-code"))
        assertEquals("agent1", defaultManagedAgentMentionAlias("agent"))
        assertEquals(32, defaultManagedAgentMentionAlias("future-agent-type-with-a-very-long-name").length)
        assertEquals("cc-linux_2", formatManagedAgentMentionAlias("@CC-Linux_2!"))
        assertTrue(isManagedAgentMentionAliasValid("cc-linux_2"))
        assertFalse(isManagedAgentMentionAliasValid("silk"))
        assertFalse(isManagedAgentMentionAliasValid("-cc"))
    }

    @Test
    fun roomMentionConflictsAreExplainedAndSuggestTheNextAvailableAlias() {
        val existing = managedBinding(
            bindingId = "binding-cc",
            mentionAlias = "cc",
            status = ManagedBindingStatus.ACTIVE,
        )
        val second = managedBinding(
            bindingId = "binding-cc1",
            mentionAlias = "cc1",
            status = ManagedBindingStatus.PENDING,
        )

        val conflict = managedAgentMentionConflictMessage(
            bindings = listOf(existing, second),
            targetType = BindingTargetType.ROOM,
            targetId = "room-1",
            mentionAlias = "cc",
        )

        assertTrue(conflict?.contains("@cc 已被") == true)
        assertTrue(conflict?.contains("@cc2") == true)
        assertEquals(
            null,
            managedAgentMentionConflictMessage(
                bindings = listOf(existing),
                targetType = BindingTargetType.ROOM,
                targetId = "room-1",
                mentionAlias = "cc",
                excludingBindingId = existing.bindingId,
            ),
        )
        assertEquals(
            null,
            managedAgentMentionConflictMessage(
                bindings = listOf(existing.copy(status = ManagedBindingStatus.REVOKED)),
                targetType = BindingTargetType.ROOM,
                targetId = "room-1",
                mentionAlias = "cc",
            ),
        )
    }

    @Test
    fun pairingKindsArePresentedAsSeparateApprovalScopes() {
        assertEquals("新设备", pairingKindLabel(PairingKind.DEVICE_ENROLLMENT))
        assertEquals("新增 Agent", pairingKindLabel(PairingKind.ADD_AGENT))
    }

    @Test
    fun pendingBindingNamesTheApprovalSideThatIsStillMissing() {
        val binding = ManagedBindingDto(
            bindingId = "binding-1",
            agentInstanceId = "agent-1",
            agentType = "codex",
            agentDisplayName = "Codex",
            targetType = BindingTargetType.ROOM,
            targetId = "room-1",
            messageScope = BindingMessageScope.TEAM,
            triggerPolicy = BindingTriggerPolicy.MENTION,
            status = ManagedBindingStatus.PENDING,
            createdBy = "agent-owner",
            ownerId = "agent-owner",
            agentOwnerApprovedBy = "agent-owner",
            agentOwnerApprovedAtEpochMs = 1L,
            createdAtEpochMs = 1L,
        )

        assertEquals("等待目标管理员批准", bindingApprovalSummary(binding))
        assertEquals("待审批", bindingStatusLabel(binding.status))
        assertEquals(null, bindingApprovalSummary(binding.copy(status = ManagedBindingStatus.ACTIVE)))
    }

    private fun managedBinding(
        bindingId: String,
        mentionAlias: String,
        status: ManagedBindingStatus,
    ) = ManagedBindingDto(
        bindingId = bindingId,
        agentInstanceId = "agent-$bindingId",
        agentType = "claude-code",
        agentDisplayName = "Claude Code",
        targetType = BindingTargetType.ROOM,
        targetId = "room-1",
        messageScope = BindingMessageScope.TEAM,
        triggerPolicy = BindingTriggerPolicy.MENTION,
        mentionAlias = mentionAlias,
        status = status,
        createdBy = "owner-1",
        ownerId = "owner-1",
        createdAtEpochMs = 1L,
    )
}
