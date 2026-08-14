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
    fun workspaceBindingsExposeGranularPermissionsWhileRoomsStayMessageScoped() {
        assertEquals(
            setOf(BindingPermission.READ_MESSAGE, BindingPermission.SEND_MESSAGE),
            defaultBindingPermissions(),
        )
        assertEquals(
            listOf(BindingPermission.READ_MESSAGE, BindingPermission.SEND_MESSAGE),
            availableBindingPermissions(BindingTargetType.ROOM),
        )
        assertEquals(
            listOf(
                BindingPermission.READ_MESSAGE,
                BindingPermission.SEND_MESSAGE,
                BindingPermission.READ_FILE,
                BindingPermission.WRITE_FILE,
                BindingPermission.RUN_COMMAND,
                BindingPermission.READ_WORKSPACE,
                BindingPermission.WRITE_WORKSPACE,
            ),
            availableBindingPermissions(BindingTargetType.WORKSPACE),
        )
        assertEquals("读取文件", bindingPermissionLabel(BindingPermission.READ_FILE))
    }

    @Test
    fun fileMutationAndCommandPermissionsKeepReadPrerequisite() {
        val writeEnabled = updateBindingPermissionSelection(
            defaultBindingPermissions(),
            BindingPermission.WRITE_FILE,
            enabled = true,
        )
        assertTrue(BindingPermission.READ_FILE in writeEnabled)
        assertTrue(BindingPermission.WRITE_FILE in writeEnabled)

        val commandEnabled = updateBindingPermissionSelection(
            writeEnabled,
            BindingPermission.RUN_COMMAND,
            enabled = true,
        )
        val readDisabled = updateBindingPermissionSelection(
            commandEnabled,
            BindingPermission.READ_FILE,
            enabled = false,
        )
        assertFalse(BindingPermission.READ_FILE in readDisabled)
        assertFalse(BindingPermission.WRITE_FILE in readDisabled)
        assertFalse(BindingPermission.RUN_COMMAND in readDisabled)
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
}
