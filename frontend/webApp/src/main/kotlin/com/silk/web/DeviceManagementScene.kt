package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.silk.shared.models.RoomKind
import com.silk.web.workspace.fetchWorkspaces
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flex
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontFamily
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.gap
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.maxWidth
import org.jetbrains.compose.web.css.minWidth
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
fun DeviceManagementScene(appState: WebAppState) {
    val user = appState.currentUser ?: return
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf<List<ManagedDeviceDto>>(emptyList()) }
    var agents by remember { mutableStateOf<List<ManagedAgentDto>>(emptyList()) }
    var bindings by remember { mutableStateOf<List<ManagedBindingDto>>(emptyList()) }
    var targets by remember { mutableStateOf<List<BindingTargetOption>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var showRevokedHistory by remember { mutableStateOf(false) }
    var detailsAgentId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ManagedResourceRenameTarget?>(null) }

    var pairingCode by remember(user.id) { mutableStateOf(appState.pendingPairingCode.orEmpty()) }
    var pairingPreview by remember { mutableStateOf<PairingPreviewDto?>(null) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var pairingBusy by remember { mutableStateOf(false) }

    var selectedAgentId by remember { mutableStateOf("") }
    var selectedTargetType by remember { mutableStateOf(BindingTargetType.ROOM) }
    var selectedTargetId by remember { mutableStateOf("") }
    var selectedTrigger by remember { mutableStateOf(BindingTriggerPolicy.MENTION) }
    var selectedMentionAlias by remember { mutableStateOf("") }
    var selectedAccessMode by remember { mutableStateOf(defaultBindingAccessMode(BindingTargetType.ROOM)) }
    var editingBindingId by remember { mutableStateOf<String?>(null) }
    var bindingSubmissionError by remember { mutableStateOf<String?>(null) }

    val activeDevices = devices.filter { it.status == DeviceEnrollmentStatus.ACTIVE }
    val revokedDevices = devices.filter { it.status == DeviceEnrollmentStatus.REVOKED }
    val activeAgents = agents.filter { it.status != ManagedAgentStatus.REVOKED }
    val revokedAgents = agents.filter { it.status == ManagedAgentStatus.REVOKED }
    val visibleBindings = bindings.filter { it.status != ManagedBindingStatus.REVOKED }
    val revokedBindings = bindings.filter { it.status == ManagedBindingStatus.REVOKED }
    val mentionConflictMessage = managedAgentMentionConflictMessage(
        bindings = bindings,
        targetType = selectedTargetType,
        targetId = selectedTargetId,
        mentionAlias = selectedMentionAlias,
        excludingBindingId = editingBindingId,
    )

    LaunchedEffect(user.id, refreshKey) {
        isLoading = true
        pageError = null
        try {
            devices = ApiClient.getManagedDevices()
            agents = ApiClient.getManagedAgents()
            bindings = ApiClient.getManagedBindings()

            val rooms = ApiClient.getVisibleRooms()
            val roomTargets = rooms
                .filter { it.roomKind != RoomKind.SILK_PRIVATE }
                .map {
                    BindingTargetOption(
                        BindingTargetType.ROOM,
                        it.roomId,
                        it.name,
                        canManage = canBindRoomRole(it.role),
                    )
                }
            val workspaceTargets = mutableListOf<BindingTargetOption>()
            val token = JwtManager.getAccessToken()
            if (token != null) {
                rooms.filter { it.roomKind == RoomKind.WORKFLOW }.forEach { room ->
                    runCatching { fetchWorkspaces(room.roomId, token) }
                        .getOrDefault(emptyList())
                        .filter { workspace -> workspace.lifecycleState == "ACTIVE" }
                        .forEach { workspace ->
                            workspaceTargets += BindingTargetOption(
                                BindingTargetType.WORKSPACE,
                                workspace.workspaceId,
                                "${room.name} / ${workspace.name}",
                                canManage = workspace.ownerId == user.id,
                            )
                        }
                }
            }
            targets = roomTargets + workspaceTargets

            if (selectedAgentId !in agents.filter { it.status == ManagedAgentStatus.ACTIVE }.map { it.agentInstanceId }) {
                selectedAgentId = agents.firstOrNull { it.status == ManagedAgentStatus.ACTIVE }?.agentInstanceId.orEmpty()
            }
            if (selectedMentionAlias.isBlank()) {
                selectedMentionAlias = agents.firstOrNull { it.agentInstanceId == selectedAgentId }
                    ?.let { defaultManagedAgentMentionAlias(it.agentType) }
                    .orEmpty()
            }
            val currentTargets = targets.filter { it.type == selectedTargetType }
            if (selectedTargetId !in currentTargets.map { it.id }) {
                selectedTargetId = currentTargets.firstOrNull()?.id.orEmpty()
            }
        } catch (error: Exception) {
            pageError = error.message ?: "加载设备数据失败"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(user.id, appState.pendingPairingCode) {
        val linkedCode = appState.pendingPairingCode ?: return@LaunchedEffect
        pairingCode = linkedCode
        pairingPreview = null
        pairingBusy = true
        pairingError = null
        try {
            pairingPreview = ApiClient.previewAgentPairing(normalizePairingCode(linkedCode))
        } catch (_: Exception) {
            pairingError = "当前登录账号 ${user.loginName} 无法处理此配对。请切换到配对指定账号，或重新生成配对链接。"
        } finally {
            pairingBusy = false
        }
    }

    Div({ style { devicePageStyle() } }) {
        DevicePageHeader(
            userName = user.fullName,
            loading = isLoading,
            onBack = appState::closeDeviceManagement,
            onRefresh = { refreshKey++ },
        )

        Div({ style { deviceContentStyle() } }) {
            feedback?.let { DeviceNotice(it, isError = false) }
            pageError?.let { DeviceNotice(it, isError = true) }

            PairingApprovalSection(
                loginName = user.loginName,
                pairingCode = pairingCode,
                preview = pairingPreview,
                linkedPairing = appState.pendingPairingCode != null,
                busy = pairingBusy,
                error = pairingError,
                onCodeChange = { value ->
                    pairingCode = formatPairingCode(value)
                    if (normalizePairingCode(pairingCode) != normalizePairingCode(appState.pendingPairingCode.orEmpty())) {
                        appState.clearPendingPairingCode()
                    }
                    pairingPreview = null
                    pairingError = null
                },
                onPreview = {
                    scope.launch {
                        pairingBusy = true
                        pairingError = null
                        try {
                            pairingPreview = ApiClient.previewAgentPairing(normalizePairingCode(pairingCode))
                        } catch (error: Exception) {
                            pairingError = error.message ?: "配对码无效或已过期"
                        } finally {
                            pairingBusy = false
                        }
                    }
                },
                onDecision = { approve ->
                    scope.launch {
                        pairingBusy = true
                        pairingError = null
                        try {
                            val requestKind = pairingPreview?.pairingKind ?: PairingKind.DEVICE_ENROLLMENT
                            val decision = ApiClient.decideAgentPairing(normalizePairingCode(pairingCode), approve)
                            appState.clearPendingPairingCode()
                            pairingPreview = null
                            pairingCode = ""
                            feedback = when {
                                !approve -> "已拒绝配对请求。"
                                requestKind == PairingKind.ADD_AGENT && decision.state == PairingState.CONSUMED ->
                                    "已批准新增 Agent。"
                                else -> "已批准新设备，正在等待设备完成密钥证明。"
                            }
                            refreshKey++
                        } catch (error: Exception) {
                            pairingError = error.message ?: "处理配对请求失败"
                        } finally {
                            pairingBusy = false
                        }
                    }
                },
                onSwitchAccount = appState::logout,
            )

            DeviceSectionTitle("已登记设备", activeDevices.size)
            if (isLoading) {
                DeviceEmptyState("加载中…")
            } else if (activeDevices.isEmpty()) {
                DeviceEmptyState("暂无设备")
            } else {
                Div({ style { deviceGridStyle() } }) {
                    activeDevices.forEach { device ->
                        DeviceCard(
                            device = device,
                            busy = busyAction == "device:${device.deviceId}",
                            onRename = {
                                renameTarget = ManagedResourceRenameTarget(
                                    ManagedResourceKind.DEVICE,
                                    device.deviceId,
                                    device.displayName,
                                )
                            },
                            onRevoke = {
                                if (window.confirm("撤销设备 ${device.displayName}？该设备上的 Agent 将同时失效。")) {
                                    scope.launch {
                                        busyAction = "device:${device.deviceId}"
                                        feedback = null
                                        pageError = null
                                        try {
                                            ApiClient.revokeManagedDevice(device.deviceId)
                                            feedback = "设备已撤销。"
                                            refreshKey++
                                        } catch (error: Exception) {
                                            pageError = error.message ?: "撤销设备失败"
                                        } finally {
                                            busyAction = null
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            DeviceSectionTitle("Agent", activeAgents.count { it.status == ManagedAgentStatus.ACTIVE })
            if (isLoading) {
                DeviceEmptyState("加载中…")
            } else if (activeAgents.isEmpty()) {
                DeviceEmptyState("暂无 Agent")
            } else {
                Div({ style { deviceGridStyle() } }) {
                    activeAgents.forEach { agent ->
                        AgentCard(
                            agent = agent,
                            deviceName = devices.deviceName(agent.deviceId),
                            busy = busyAction == "agent:${agent.agentInstanceId}",
                            onDetails = { detailsAgentId = agent.agentInstanceId },
                            onRename = {
                                renameTarget = ManagedResourceRenameTarget(
                                    ManagedResourceKind.AGENT,
                                    agent.agentInstanceId,
                                    agent.displayName,
                                )
                            },
                            onPermissionModeChange = { mode ->
                                scope.launch {
                                    busyAction = "agent:${agent.agentInstanceId}"
                                    pageError = null
                                    try {
                                        ApiClient.updateManagedAgentRuntimePermissionMode(agent.agentInstanceId, mode)
                                        agents = agents.map {
                                            if (it.agentInstanceId == agent.agentInstanceId) {
                                                it.copy(runtimePermissionMode = mode)
                                            } else it
                                        }
                                        feedback = "Agent 权限已更新，将在下一轮生效。"
                                    } catch (error: Exception) {
                                        pageError = error.message ?: "更新 Agent 权限失败"
                                    } finally {
                                        busyAction = null
                                    }
                                }
                            },
                            onRevoke = {
                                if (window.confirm("撤销 Agent ${agent.displayName}？相关 Binding 将同时失效。")) {
                                    scope.launch {
                                        busyAction = "agent:${agent.agentInstanceId}"
                                        feedback = null
                                        pageError = null
                                        try {
                                            ApiClient.revokeManagedAgent(agent.agentInstanceId)
                                            feedback = "Agent 已撤销。"
                                            refreshKey++
                                        } catch (error: Exception) {
                                            pageError = error.message ?: "撤销 Agent 失败"
                                        } finally {
                                            busyAction = null
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            DeviceSectionTitle("使用范围", visibleBindings.count { it.status == ManagedBindingStatus.ACTIVE })
            BindingCreator(
                agents = agents.filter { it.status == ManagedAgentStatus.ACTIVE },
                targets = targets,
                selectedAgentId = selectedAgentId,
                selectedTargetType = selectedTargetType,
                selectedTargetId = selectedTargetId,
                selectedTrigger = selectedTrigger,
                selectedMentionAlias = selectedMentionAlias,
                selectedAccessMode = selectedAccessMode,
                editing = editingBindingId != null,
                busy = busyAction == "binding:create",
                mentionConflictMessage = mentionConflictMessage,
                submissionError = bindingSubmissionError,
                deviceNames = devices.associate { it.deviceId to it.displayName },
                onAgentChange = { agentId ->
                    bindingSubmissionError = null
                    selectedAgentId = agentId
                    if (editingBindingId == null) {
                        selectedMentionAlias = agents.firstOrNull { it.agentInstanceId == agentId }
                            ?.let { defaultManagedAgentMentionAlias(it.agentType) }
                            .orEmpty()
                    }
                },
                onTargetTypeChange = { type ->
                    bindingSubmissionError = null
                    selectedTargetType = type
                    selectedTargetId = targets.firstOrNull { it.type == type }?.id.orEmpty()
                    selectedAccessMode = defaultBindingAccessMode(type)
                },
                onTargetChange = {
                    bindingSubmissionError = null
                    selectedTargetId = it
                },
                onTriggerChange = {
                    bindingSubmissionError = null
                    selectedTrigger = it
                },
                onMentionAliasChange = {
                    bindingSubmissionError = null
                    selectedMentionAlias = formatManagedAgentMentionAlias(it)
                },
                onAccessModeChange = { mode ->
                    bindingSubmissionError = null
                    selectedAccessMode = mode
                },
                onCancel = {
                    bindingSubmissionError = null
                    editingBindingId = null
                },
                onSubmit = submit@{
                    val localConflict = managedAgentMentionConflictMessage(
                        bindings = bindings,
                        targetType = selectedTargetType,
                        targetId = selectedTargetId,
                        mentionAlias = selectedMentionAlias,
                        excludingBindingId = editingBindingId,
                    )
                    if (localConflict != null) {
                        bindingSubmissionError = localConflict
                        return@submit
                    }
                    scope.launch {
                        busyAction = "binding:create"
                        bindingSubmissionError = null
                        feedback = null
                        pageError = null
                        try {
                            val request = CreateManagedBindingRequest(
                                agentInstanceId = selectedAgentId,
                                targetType = selectedTargetType,
                                targetId = selectedTargetId,
                                messageScope = compatibleMessageScope(selectedTargetType),
                                triggerPolicy = selectedTrigger,
                                mentionAlias = selectedMentionAlias,
                                accessMode = selectedAccessMode,
                            )
                            val bindingId = editingBindingId
                            val saved = if (bindingId == null) {
                                ApiClient.createManagedBinding(request)
                            } else {
                                ApiClient.updateManagedBinding(bindingId, request)
                            }
                            feedback = if (saved.status == ManagedBindingStatus.PENDING) {
                                bindingApprovalSummary(saved) ?: "使用范围已提交审批。"
                            } else if (bindingId == null) {
                                "使用范围已添加。"
                            } else {
                                "使用范围已更新。"
                            }
                            editingBindingId = null
                            refreshKey++
                        } catch (error: Exception) {
                            val message = error.message ?: "添加使用范围失败"
                            bindingSubmissionError = if (
                                message.contains("already used in this Room", ignoreCase = true)
                            ) {
                                "提及词 @$selectedMentionAlias 已被这个 Room 中的另一个 Agent 使用，请换一个唯一提及词后重试。"
                            } else {
                                message
                            }
                        } finally {
                            busyAction = null
                        }
                    }
                },
            )

            if (visibleBindings.isEmpty()) {
                DeviceEmptyState("暂无使用范围")
            } else {
                visibleBindings.forEach { binding ->
                    BindingRow(
                        binding = binding,
                        agentName = agents.firstOrNull { it.agentInstanceId == binding.agentInstanceId }?.let { agent ->
                            "${agent.displayName} · ${devices.deviceName(agent.deviceId)}"
                        } ?: listOf(binding.agentDisplayName, binding.agentDeviceDisplayName)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        targetName = targets.firstOrNull {
                            it.type == binding.targetType && it.id == binding.targetId
                        }?.label ?: binding.targetId,
                        busy = busyAction == "binding:${binding.bindingId}",
                        onApprove = {
                            scope.launch {
                                busyAction = "binding:${binding.bindingId}"
                                feedback = null
                                pageError = null
                                try {
                                    val decided = ApiClient.decideManagedBinding(binding.bindingId, approve = true)
                                    feedback = if (decided.status == ManagedBindingStatus.ACTIVE) {
                                        "使用范围已启用。"
                                    } else {
                                        bindingApprovalSummary(decided) ?: "审批已记录。"
                                    }
                                    refreshKey++
                                } catch (error: Exception) {
                                    pageError = error.message ?: "批准使用范围失败"
                                } finally {
                                    busyAction = null
                                }
                            }
                        },
                        onReject = {
                            if (window.confirm("拒绝此使用范围请求？")) {
                                scope.launch {
                                    busyAction = "binding:${binding.bindingId}"
                                    feedback = null
                                    pageError = null
                                    try {
                                        ApiClient.decideManagedBinding(binding.bindingId, approve = false)
                                        feedback = "使用范围请求已拒绝。"
                                        refreshKey++
                                    } catch (error: Exception) {
                                        pageError = error.message ?: "拒绝使用范围失败"
                                    } finally {
                                        busyAction = null
                                    }
                                }
                            }
                        },
                        onEdit = {
                            editingBindingId = binding.bindingId
                            selectedAgentId = binding.agentInstanceId
                            selectedTargetType = binding.targetType
                            selectedTargetId = binding.targetId
                            selectedTrigger = binding.triggerPolicy
                            selectedMentionAlias = binding.mentionAlias
                            selectedAccessMode = binding.accessMode
                            bindingSubmissionError = null
                        },
                    ) {
                        if (window.confirm("移除此使用范围？")) {
                            scope.launch {
                                busyAction = "binding:${binding.bindingId}"
                                feedback = null
                                pageError = null
                                try {
                                    ApiClient.revokeManagedBinding(binding.bindingId)
                                    feedback = "使用范围已移除。"
                                    refreshKey++
                                } catch (error: Exception) {
                                    pageError = error.message ?: "移除使用范围失败"
                                } finally {
                                    busyAction = null
                                }
                            }
                        }
                    }
                }
            }

            val revokedCount = revokedDevices.size + revokedAgents.size + revokedBindings.size
            if (revokedCount > 0) {
                RevokedHistorySection(
                    devices = revokedDevices,
                    agents = revokedAgents,
                    bindings = revokedBindings,
                    expanded = showRevokedHistory,
                    cleanupBusy = busyAction == "revoked-history:cleanup",
                    deviceNames = devices.associate { it.deviceId to it.displayName },
                    onToggle = { showRevokedHistory = !showRevokedHistory },
                    onAgentDetails = { detailsAgentId = it },
                    onCleanup = {
                        scope.launch {
                            busyAction = "revoked-history:cleanup"
                            feedback = null
                            pageError = null
                            try {
                                val result = ApiClient.cleanupRevokedAgentHistory()
                                feedback = "${result.message}：设备 ${result.deletedDevices} 个，Agent ${result.deletedAgents} 个，使用范围 ${result.deletedBindings} 个。"
                                refreshKey++
                            } catch (error: Exception) {
                                pageError = error.message ?: "清理撤销历史失败"
                            } finally {
                                busyAction = null
                            }
                        }
                    },
                )
            }
        }
    }

    agents.firstOrNull { it.agentInstanceId == detailsAgentId }?.let { agent ->
        AgentDetailsDialog(
            agent = agent,
            deviceName = devices.deviceName(agent.deviceId),
            bindings = bindings.filter { it.agentInstanceId == agent.agentInstanceId },
            targets = targets,
            onDismiss = { detailsAgentId = null },
        )
    }

    renameTarget?.let { target ->
        ManagedResourceRenameDialog(
            target = target,
            saving = busyAction == "rename:${target.id}",
            onDismiss = { renameTarget = null },
            onSave = { newName ->
                scope.launch {
                    busyAction = "rename:${target.id}"
                    feedback = null
                    pageError = null
                    try {
                        when (target.kind) {
                            ManagedResourceKind.DEVICE -> ApiClient.renameManagedDevice(target.id, newName)
                            ManagedResourceKind.AGENT -> ApiClient.renameManagedAgent(target.id, newName)
                        }
                        feedback = if (target.kind == ManagedResourceKind.DEVICE) "设备名称已更新。" else "Agent 名称已更新。"
                        renameTarget = null
                        refreshKey++
                    } catch (error: Exception) {
                        pageError = error.message ?: "名称更新失败"
                    } finally {
                        busyAction = null
                    }
                }
            },
        )
    }
}

@Composable
private fun DevicePageHeader(
    userName: String,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Div({ style { deviceHeaderStyle() } }) {
        Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); gap(12.px) } }) {
            DeviceIconButton("返回", "←", onBack)
            Div {
                H2({ style { fontSize(20.px); fontWeight("600"); color(Color.white) } }) { Text("设备与 Agent") }
                Div({ style { fontSize(12.px); color(Color("rgba(255,255,255,0.78)")); marginTop(2.px) } }) {
                    Text(userName)
                }
            }
        }
        DeviceIconButton("刷新", if (loading) "…" else "↻", onRefresh, disabled = loading)
    }
}

@Composable
private fun PairingApprovalSection(
    loginName: String,
    pairingCode: String,
    preview: PairingPreviewDto?,
    linkedPairing: Boolean,
    busy: Boolean,
    error: String?,
    onCodeChange: (String) -> Unit,
    onPreview: () -> Unit,
    onDecision: (Boolean) -> Unit,
    onSwitchAccount: () -> Unit,
) {
    Div({ style { pairingToolStyle() } }) {
        Div({ style { fontSize(15.px); fontWeight("600"); color(Color(SilkColors.textPrimary)); marginBottom(12.px) } }) {
            Text("批准设备或 Agent")
        }
        if (linkedPairing) {
            Div({ style { fontSize(13.px); color(Color(SilkColors.textSecondary)); marginBottom(12.px) } }) {
                Text("配对链接已自动填入，当前登录账号：$loginName")
            }
        }
        Div({ style { display(DisplayStyle.Flex); gap(8.px); property("flex-wrap", "wrap") } }) {
            Input(InputType.Text) {
                value(pairingCode)
                attr("placeholder", "配对码")
                attr("autocomplete", "one-time-code")
                attr("aria-label", "配对码")
                onInput { onCodeChange(it.value) }
                style { deviceInputStyle(); flex(1); minWidth(180.px); fontFamily("monospace") }
            }
            DevicePrimaryButton(
                label = if (busy && preview == null) "查询中…" else "查看请求",
                disabled = busy || normalizePairingCode(pairingCode).length != 8,
                onClick = onPreview,
            )
        }
        error?.let { DeviceInlineError(it) }
        if (linkedPairing && preview == null && !busy) {
            Div({ style { marginTop(10.px) } }) {
                DeviceSecondaryButton("切换账号", disabled = false, onClick = onSwitchAccount)
            }
        }

        preview?.let { request ->
            Div({ style { marginTop(18.px); property("border-top", "1px solid ${SilkColors.divider}"); padding(18.px, 0.px, 0.px, 0.px) } }) {
                Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.SpaceBetween); gap(12.px) } }) {
                    Div {
                        Div({ style { fontSize(16.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                            Text(request.deviceName)
                        }
                        Div({ style { fontSize(13.px); color(Color(SilkColors.textSecondary)); marginTop(4.px) } }) {
                            Text("${request.platform} · ${request.agentDisplayName} · ${request.transportAdapter.name}")
                        }
                    }
                    StatusBadge(request.state.name, request.state == PairingState.USER_PENDING)
                }
                DeviceMetadata("请求", pairingKindLabel(request.pairingKind))
                DeviceMetadata("公钥指纹", request.publicKeyFingerprint)
                DeviceMetadata("Connector", request.connectorVersion)
                DeviceMetadata("能力", request.capabilities.sortedBy { it.name }.joinToString(" · ") { it.name })
                DeviceMetadata("有效期", formatMessageTimestampForWeb(request.expiresAtEpochMs, includeSeconds = false))
                Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd); gap(8.px); marginTop(16.px) } }) {
                    DeviceSecondaryButton("拒绝", busy) { onDecision(false) }
                    DevicePrimaryButton(if (busy) "处理中…" else "批准连接", busy) { onDecision(true) }
                }
            }
        }
    }
}

@Composable
private fun RevokedHistorySection(
    devices: List<ManagedDeviceDto>,
    agents: List<ManagedAgentDto>,
    bindings: List<ManagedBindingDto>,
    expanded: Boolean,
    cleanupBusy: Boolean,
    deviceNames: Map<String, String>,
    onToggle: () -> Unit,
    onAgentDetails: (String) -> Unit,
    onCleanup: () -> Unit,
) {
    Div({ style { marginTop(28.px) } }) {
        Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); justifyContent(JustifyContent.SpaceBetween); gap(12.px); property("flex-wrap", "wrap") } }) {
            Div {
                Div({ style { fontSize(17.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                    Text("已撤销历史")
                }
                Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginTop(3.px) } }) {
                    Text("默认隐藏，保留 ${devices.size} 个设备、${agents.size} 个 Agent、${bindings.size} 个使用范围")
                }
            }
            Div({ style { display(DisplayStyle.Flex); gap(8.px); property("flex-wrap", "wrap") } }) {
                DeviceSecondaryButton(if (expanded) "收起历史" else "查看历史", false, onToggle)
                if (expanded) {
                    DeviceDangerButton(
                        if (cleanupBusy) "清理中…" else "清理到期记录",
                        cleanupBusy,
                        onCleanup,
                    )
                }
            }
        }
        if (expanded) {
            Div({ style { marginTop(12.px) } }) {
                devices.forEach { device ->
                    DeviceCard(device = device, busy = false, onRename = null, onRevoke = null)
                }
                agents.forEach { agent ->
                    AgentCard(
                        agent = agent,
                        deviceName = deviceNames[agent.deviceId] ?: "未知设备",
                        busy = false,
                        onDetails = { onAgentDetails(agent.agentInstanceId) },
                        onRename = null,
                        onPermissionModeChange = null,
                        onRevoke = null,
                    )
                }
                bindings.forEach { binding ->
                    RevokedBindingHistoryRow(binding)
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: ManagedDeviceDto,
    busy: Boolean,
    onRename: (() -> Unit)?,
    onRevoke: (() -> Unit)?,
) {
    Div({ style { deviceCardStyle() } }) {
        Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.SpaceBetween); gap(12.px) } }) {
            Div({ style { minWidth(0.px) } }) {
                Div({ style { fontSize(15.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                    Text(device.displayName)
                }
                Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginTop(3.px) } }) {
                    Text("${device.platform} · ${device.keyAlgorithm}")
                }
            }
            StatusBadge(
                when {
                    device.connected -> "已连接"
                    device.status == DeviceEnrollmentStatus.REVOKED -> "已撤销"
                    else -> device.status.name
                },
                device.connected,
            )
        }
        DeviceMetadata("指纹", fingerprintSuffix(device.publicKeyFingerprint))
        DeviceMetadata("最近连接", device.lastSeenAtEpochMs?.let {
            formatMessageTimestampForWeb(it, includeSeconds = false)
        } ?: "尚未连接")
        device.lastSeenIp?.let { DeviceMetadata("最近地址", it) }
        if (device.status == DeviceEnrollmentStatus.ACTIVE && onRevoke != null) {
            Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd); gap(8.px); marginTop(14.px) } }) {
                if (onRename != null) DeviceSecondaryButton("重命名", busy, onRename)
                DeviceDangerButton(if (busy) "撤销中…" else "撤销设备", busy, onRevoke)
            }
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun AgentCard(
    agent: ManagedAgentDto,
    deviceName: String,
    busy: Boolean,
    onDetails: () -> Unit,
    onRename: (() -> Unit)?,
    onPermissionModeChange: ((ManagedAgentRuntimePermissionMode) -> Unit)?,
    onRevoke: (() -> Unit)?,
) {
    Div({ style { deviceCardStyle() } }) {
        Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.SpaceBetween); gap(12.px) } }) {
            Div({ style { minWidth(0.px) } }) {
                Div({ style { fontSize(15.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                    Text(agent.displayName)
                }
                Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginTop(3.px) } }) {
                    Text("${agent.agentType} · ${agent.transportAdapter.name} · ${agent.connectorVersion}")
                }
            }
            StatusBadge(
                when {
                    agent.connected -> "已连接"
                    agent.status == ManagedAgentStatus.REVOKED -> "已撤销"
                    else -> agent.status.name
                },
                agent.connected,
            )
        }
        DeviceMetadata("最近连接", agent.lastSeenAtEpochMs?.let {
            formatMessageTimestampForWeb(it, includeSeconds = false)
        } ?: "尚未连接")
        DeviceMetadata("设备", deviceName)
        if (agent.status == ManagedAgentStatus.ACTIVE && onPermissionModeChange != null) {
            Div({ style { marginTop(12.px) } }) {
                DeviceSelectField(
                    label = "Agent 权限",
                    value = agent.runtimePermissionMode.name,
                    onChange = { value ->
                        runCatching { ManagedAgentRuntimePermissionMode.valueOf(value) }
                            .getOrNull()
                            ?.let(onPermissionModeChange)
                    },
                    disabled = busy,
                ) {
                    ManagedAgentRuntimePermissionMode.entries.forEach { mode ->
                        Option(mode.name, attrs = {
                            if (mode == agent.runtimePermissionMode) attr("selected", "")
                        }) {
                            Text(agentRuntimePermissionModeLabel(mode))
                        }
                    }
                }
            }
        }
        Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd); gap(8.px); marginTop(14.px) } }) {
            DeviceSecondaryButton("查看详情", busy, onDetails)
            if (agent.status == ManagedAgentStatus.ACTIVE && onRename != null) {
                DeviceSecondaryButton("重命名", busy, onRename)
            }
            if (agent.status == ManagedAgentStatus.ACTIVE && onRevoke != null) {
                DeviceDangerButton(if (busy) "撤销中…" else "撤销 Agent", busy, onRevoke)
            }
        }
    }
}

private enum class ManagedResourceKind { DEVICE, AGENT }

private data class ManagedResourceRenameTarget(
    val kind: ManagedResourceKind,
    val id: String,
    val currentName: String,
)

@Composable
private fun ManagedResourceRenameDialog(
    target: ManagedResourceRenameTarget,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var displayName by remember(target.kind, target.id) { mutableStateOf(target.currentName) }
    val resourceLabel = if (target.kind == ManagedResourceKind.DEVICE) "设备" else "Agent"
    ModalOverlay(onDismiss = { if (!saving) onDismiss() }, zIndex = 2300) {
        Div({ style { agentDetailsDialogStyle(); width(420.px) } }) {
            Div({ style { fontSize(18.px); fontWeight("600"); color(Color(SilkColors.textPrimary)); marginBottom(16.px) } }) {
                Text("重命名$resourceLabel")
            }
            Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginBottom(6.px) } }) {
                Text("显示名称")
            }
            Input(InputType.Text) {
                value(displayName)
                attr("maxlength", "256")
                attr("aria-label", "$resourceLabel 显示名称")
                onInput { displayName = it.value }
                style { deviceInputStyle() }
            }
            Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd); gap(8.px); marginTop(18.px) } }) {
                DeviceSecondaryButton("取消", saving, onDismiss)
                DevicePrimaryButton(
                    label = if (saving) "保存中…" else "保存",
                    disabled = saving || displayName.trim().isBlank() || displayName.trim() == target.currentName,
                    onClick = { onSave(displayName.trim()) },
                )
            }
        }
    }
}

@Composable
private fun AgentDetailsDialog(
    agent: ManagedAgentDto,
    deviceName: String,
    bindings: List<ManagedBindingDto>,
    targets: List<BindingTargetOption>,
    onDismiss: () -> Unit,
) {
    ModalOverlay(onDismiss = onDismiss, zIndex = 2200) {
        Div({ style { agentDetailsDialogStyle() } }) {
            Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.SpaceBetween); gap(16.px) } }) {
                Div({ style { minWidth(0.px) } }) {
                    Div({ style { fontSize(19.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                        Text(agent.displayName)
                    }
                    Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginTop(4.px) } }) {
                        Text("Agent 详情")
                    }
                }
                Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); gap(8.px) } }) {
                    StatusBadge(
                        when {
                            agent.connected -> "已连接"
                            agent.status == ManagedAgentStatus.REVOKED -> "已撤销"
                            else -> agent.status.name
                        },
                        agent.connected,
                    )
                    DeviceIconCloseButton(onDismiss)
                }
            }

            Div({ style { agentDetailsSectionStyle() } }) {
                AgentDetailsSectionTitle("基本信息")
                DeviceMetadata("设备", deviceName)
                DeviceMetadata("类型", agent.agentType)
                DeviceMetadata("传输适配器", agent.transportAdapter.name)
                DeviceMetadata("Connector", agent.connectorVersion)
                DeviceMetadata("Agent ID", agent.agentInstanceId)
                DeviceMetadata(
                    "最近连接",
                    agent.lastSeenAtEpochMs?.let {
                        formatMessageTimestampForWeb(it, includeSeconds = false)
                    } ?: "尚未连接",
                )
            }

            Div({ style { agentDetailsSectionStyle() } }) {
                AgentDetailsSectionTitle("Agent 声明能力")
                Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginTop(6.px) } }) {
                    Text("声明能力表示 Agent 支持的操作；实际可用范围仍受 Binding 权限限制。")
                }
                if (agent.capabilities.isEmpty()) {
                    DeviceMetadata("能力", "未声明")
                } else {
                    Div({ style { display(DisplayStyle.Flex); gap(7.px); property("flex-wrap", "wrap"); marginTop(12.px) } }) {
                        agent.capabilities.sortedBy(::agentCapabilityLabel).forEach { capability ->
                            AgentCapabilityChip(agentCapabilityLabel(capability), capability.name)
                        }
                    }
                }
            }

            Div({ style { agentDetailsSectionStyle() } }) {
                AgentDetailsSectionTitle("使用范围")
                if (bindings.isEmpty()) {
                    DeviceMetadata("Binding", "尚未绑定 Room 或 Workspace")
                } else {
                    bindings.sortedByDescending { it.createdAtEpochMs }.forEach { binding ->
                        val targetName = targets.firstOrNull {
                            it.type == binding.targetType && it.id == binding.targetId
                        }?.label ?: binding.targetId
                        AgentDetailsBindingRow(binding, targetName)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentDetailsSectionTitle(title: String) {
    Div({ style { fontSize(14.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
        Text(title)
    }
}

@Composable
private fun AgentCapabilityChip(label: String, rawName: String) {
    Span({
        attr("title", rawName)
        style {
            padding(5.px, 8.px)
            borderRadius(5.px)
            backgroundColor(Color("#F3EFE8"))
            color(Color(SilkColors.textPrimary))
            fontSize(12.px)
        }
    }) { Text(label) }
}

@Composable
private fun AgentDetailsBindingRow(binding: ManagedBindingDto, targetName: String) {
    Div({
        style {
            marginTop(10.px)
            padding(10.px, 12.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            borderRadius(6.px)
        }
    }) {
        Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); gap(8.px); property("flex-wrap", "wrap") } }) {
            Span({ style { fontSize(13.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                Text(targetName)
            }
            StatusBadge(bindingStatusLabel(binding.status), binding.status == ManagedBindingStatus.ACTIVE)
        }
        Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginTop(5.px) } }) {
            val mention = binding.mentionAlias.takeIf {
                binding.targetType == BindingTargetType.ROOM && !it.startsWith("__")
            }?.let { " · @$it" }.orEmpty()
            Text("${binding.targetType.name} · ${triggerPolicyLabel(binding.triggerPolicy)}$mention")
        }
        DeviceMetadata(
            "权限",
            bindingAccessModeLabel(binding.accessMode),
        )
    }
}

@Composable
private fun DeviceIconCloseButton(onClick: () -> Unit) {
    Button({
        attr("type", "button")
        attr("title", "关闭")
        attr("aria-label", "关闭")
        onClick { onClick() }
        style {
            width(32.px)
            height(32.px)
            padding(0.px)
            border(0.px)
            backgroundColor(Color("transparent"))
            color(Color(SilkColors.textSecondary))
            fontSize(20.px)
            property("cursor", "pointer")
        }
    }) { Text("×") }
}

@Composable
private fun RevokedBindingHistoryRow(binding: ManagedBindingDto) {
    Div({ style { bindingRowStyle() } }) {
        Div({ style { minWidth(0.px); flex(1) } }) {
            Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); gap(8.px); property("flex-wrap", "wrap") } }) {
                Span({ style { fontSize(14.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                    Text(
                        listOf(binding.agentDisplayName, binding.agentDeviceDisplayName)
                            .filter(String::isNotBlank)
                            .joinToString(" · ")
                    )
                }
                Span({ style { color(Color(SilkColors.textLight)) } }) { Text("→") }
                Span({ style { fontSize(14.px); color(Color(SilkColors.textPrimary)) } }) {
                    Text(binding.targetId)
                }
                StatusBadge("已撤销", false)
            }
            DeviceMetadata(
                "撤销时间",
                binding.revokedAtEpochMs?.let { formatMessageTimestampForWeb(it, includeSeconds = false) } ?: "未知",
            )
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun BindingCreator(
    agents: List<ManagedAgentDto>,
    targets: List<BindingTargetOption>,
    selectedAgentId: String,
    selectedTargetType: BindingTargetType,
    selectedTargetId: String,
    selectedTrigger: BindingTriggerPolicy,
    selectedMentionAlias: String,
    selectedAccessMode: BindingAccessMode,
    editing: Boolean,
    busy: Boolean,
    mentionConflictMessage: String?,
    submissionError: String?,
    deviceNames: Map<String, String>,
    onAgentChange: (String) -> Unit,
    onTargetTypeChange: (BindingTargetType) -> Unit,
    onTargetChange: (String) -> Unit,
    onTriggerChange: (BindingTriggerPolicy) -> Unit,
    onMentionAliasChange: (String) -> Unit,
    onAccessModeChange: (BindingAccessMode) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    val availableTargets = targets.filter { it.type == selectedTargetType }
    Div({ style { bindingFormStyle() } }) {
        submissionError?.let { DeviceNotice(it, isError = true) }
        Div({ style { property("display", "grid"); property("grid-template-columns", "repeat(auto-fit, minmax(180px, 1fr))"); gap(12.px) } }) {
            DeviceSelectField("Agent", selectedAgentId, onAgentChange) {
                if (agents.isEmpty()) Option("") { Text("没有可用 Agent") }
                agents.forEach { agent ->
                    Option(agent.agentInstanceId, attrs = { if (agent.agentInstanceId == selectedAgentId) attr("selected", "") }) {
                        Text("${agent.displayName} · ${deviceNames[agent.deviceId] ?: "未知设备"}")
                    }
                }
            }
            DeviceSelectField("目标类型", selectedTargetType.name, { value ->
                onTargetTypeChange(BindingTargetType.valueOf(value))
            }) {
                BindingTargetType.entries.forEach { type ->
                    Option(type.name, attrs = { if (type == selectedTargetType) attr("selected", "") }) {
                        Text(if (type == BindingTargetType.ROOM) "Room" else "Workspace")
                    }
                }
            }
            DeviceSelectField("目标", selectedTargetId, onTargetChange) {
                if (availableTargets.isEmpty()) Option("") { Text("没有可管理目标") }
                availableTargets.forEach { target ->
                    Option(target.id, attrs = { if (target.id == selectedTargetId) attr("selected", "") }) {
                        Text(if (target.canManage) target.label else "${target.label} · 需审批")
                    }
                }
            }
            DeviceSelectField("触发方式", selectedTrigger.name, { value ->
                onTriggerChange(BindingTriggerPolicy.valueOf(value))
            }) {
                BindingTriggerPolicy.entries.forEach { policy ->
                    Option(policy.name, attrs = { if (policy == selectedTrigger) attr("selected", "") }) {
                        Text(triggerPolicyLabel(policy))
                    }
                }
            }
        }
        if (selectedTargetType == BindingTargetType.ROOM) {
            Div({ style { marginTop(14.px); maxWidth(360.px) } }) {
                Div({ style { fontSize(12.px); fontWeight("600"); color(Color(SilkColors.textSecondary)); marginBottom(6.px) } }) {
                    Text("Room 提及词")
                }
                Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); gap(6.px) } }) {
                    Span({ style { color(Color(SilkColors.textSecondary)); fontSize(14.px) } }) { Text("@") }
                    Input(InputType.Text) {
                        value(selectedMentionAlias)
                        attr("placeholder", "例如 cc-linux")
                        attr("aria-label", "Room 提及词")
                        if (mentionConflictMessage != null) attr("aria-invalid", "true")
                        onInput { onMentionAliasChange(it.value) }
                        style { deviceInputStyle() }
                    }
                }
                Div({ style { fontSize(11.px); color(Color(SilkColors.textSecondary)); marginTop(5.px) } }) {
                    Text("同一 Room 内必须唯一，可使用小写字母、数字、- 和 _。")
                }
                mentionConflictMessage?.let { DeviceInlineError(it) }
            }
        }
        if (selectedTargetType == BindingTargetType.WORKSPACE) {
            Div({ style { marginTop(14.px); maxWidth(360.px) } }) {
                DeviceSelectField("Workspace 访问", selectedAccessMode.name, { value ->
                    onAccessModeChange(BindingAccessMode.valueOf(value))
                }) {
                    availableBindingAccessModes(selectedTargetType).forEach { mode ->
                        Option(mode.name, attrs = { if (mode == selectedAccessMode) attr("selected", "") }) {
                            Text(bindingAccessModeLabel(mode))
                        }
                    }
                }
            }
        }
        Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.SpaceBetween); alignItems(AlignItems.Center); gap(12.px); marginTop(14.px); property("flex-wrap", "wrap") } }) {
            Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)) } }) {
                Text(bindingAccessModeLabel(selectedAccessMode))
            }
            Div({ style { display(DisplayStyle.Flex); gap(8.px) } }) {
                if (editing) {
                    DeviceSecondaryButton("取消", busy, onCancel)
                }
                DevicePrimaryButton(
                    label = if (busy) {
                        if (editing) "保存中…" else "添加中…"
                    } else if (editing) {
                        "保存使用范围"
                    } else {
                        "添加使用范围"
                    },
                    disabled = busy || selectedAgentId.isBlank() || selectedTargetId.isBlank() ||
                        selectedTargetType == BindingTargetType.ROOM &&
                        (!isManagedAgentMentionAliasValid(selectedMentionAlias) || mentionConflictMessage != null),
                    onClick = onSubmit,
                )
            }
        }
    }
}

@Composable
private fun DeviceSelectField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    disabled: Boolean = false,
    options: @Composable () -> Unit,
) {
    Div {
        Div({ style { fontSize(12.px); fontWeight("600"); color(Color(SilkColors.textSecondary)); marginBottom(6.px) } }) {
            Text(label)
        }
        Select({
            attr("aria-label", label)
            attr("value", value)
            if (disabled) attr("disabled", "")
            onChange { event -> onChange(event.value ?: "") }
            style { deviceInputStyle() }
        }) { options() }
    }
}

@Composable
private fun BindingRow(
    binding: ManagedBindingDto,
    agentName: String,
    targetName: String,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit,
    onRevoke: () -> Unit,
) {
    Div({ style { bindingRowStyle() } }) {
        Div({ style { minWidth(0.px); flex(1) } }) {
            Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); gap(8.px); property("flex-wrap", "wrap") } }) {
                Span({ style { fontSize(14.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) { Text(agentName) }
                Span({ style { color(Color(SilkColors.textLight)) } }) { Text("→") }
                Span({ style { fontSize(14.px); color(Color(SilkColors.textPrimary)) } }) { Text(targetName) }
                StatusBadge(bindingStatusLabel(binding.status), binding.status == ManagedBindingStatus.ACTIVE)
            }
            Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginTop(5.px) } }) {
                val mention = binding.mentionAlias.takeIf {
                    binding.targetType == BindingTargetType.ROOM && !it.startsWith("__")
                }?.let { "@$it · " }.orEmpty()
                Text("${binding.targetType.name} · $mention${triggerPolicyLabel(binding.triggerPolicy)} · ${bindingAccessModeLabel(binding.accessMode)}")
            }
            bindingApprovalSummary(binding)?.let { summary ->
                Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginTop(5.px) } }) {
                    Text(summary)
                }
            }
        }
        if (binding.canApproveAsAgentOwner || binding.canApproveAsTargetManager) {
            Div({ style { display(DisplayStyle.Flex); gap(8.px) } }) {
                DevicePrimaryButton(if (busy) "处理中…" else "批准", busy, onApprove)
                DeviceDangerButton("拒绝", busy, onReject)
            }
        } else if (binding.canEdit || binding.canRevoke) {
            Div({ style { display(DisplayStyle.Flex); gap(8.px) } }) {
                if (binding.canEdit) DeviceSecondaryButton("编辑", busy, onEdit)
                if (binding.canRevoke) {
                    DeviceDangerButton(if (busy) "移除中…" else "移除", busy, onRevoke)
                }
            }
        }
    }
}

@Composable
private fun DeviceSectionTitle(title: String, count: Int) {
    Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); gap(8.px); marginTop(28.px); marginBottom(12.px) } }) {
        Span({ style { fontSize(17.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) { Text(title) }
        Span({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)) } }) { Text(count.toString()) }
    }
}

@Composable
private fun DeviceMetadata(label: String, value: String) {
    if (value.isBlank()) return
    Div({ style { display(DisplayStyle.Flex); gap(8.px); marginTop(9.px); fontSize(12.px); property("line-height", "1.5") } }) {
        Span({ style { color(Color(SilkColors.textSecondary)); property("flex-shrink", "0") } }) { Text(label) }
        Span({ style { color(Color(SilkColors.textPrimary)); property("overflow-wrap", "anywhere") } }) { Text(value) }
    }
}

@Composable
private fun StatusBadge(label: String, positive: Boolean) {
    Span({
        style {
            property("flex-shrink", "0")
            padding(3.px, 7.px)
            borderRadius(5.px)
            backgroundColor(Color(if (positive) "#E8F4E5" else "#F3EFE8"))
            color(Color(if (positive) "#477A3B" else SilkColors.textSecondary))
            fontSize(11.px)
        }
    }) { Text(label) }
}

@Composable
private fun DeviceNotice(message: String, isError: Boolean) {
    Div({
        style {
            padding(10.px, 12.px)
            marginBottom(12.px)
            borderRadius(6.px)
            backgroundColor(Color(if (isError) "#FFF0F0" else "#EEF7EB"))
            color(Color(if (isError) SilkColors.error else "#477A3B"))
            fontSize(13.px)
        }
    }) { Text(message) }
}

@Composable
private fun DeviceInlineError(message: String) {
    Div({ style { marginTop(10.px); color(Color(SilkColors.error)); fontSize(12.px) } }) { Text(message) }
}

@Composable
private fun DeviceEmptyState(message: String) {
    Div({
        style {
            padding(24.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            borderRadius(6.px)
            color(Color(SilkColors.textSecondary))
            fontSize(13.px)
            backgroundColor(Color("rgba(255,255,255,0.55)"))
        }
    }) { Text(message) }
}

@Composable
private fun DeviceIconButton(
    title: String,
    icon: String,
    onClick: () -> Unit,
    disabled: Boolean = false,
) {
    Button({
        attr("title", title)
        attr("aria-label", title)
        if (disabled) attr("disabled", "")
        onClick { onClick() }
        style {
            width(38.px); height(38.px); padding(0.px)
            borderRadius(6.px); border(1.px, LineStyle.Solid, Color("rgba(255,255,255,0.32)"))
            backgroundColor(Color("rgba(255,255,255,0.12)")); color(Color.white)
            fontSize(19.px); property("cursor", if (disabled) "default" else "pointer")
        }
    }) { Text(icon) }
}

@Composable
private fun DevicePrimaryButton(label: String, disabled: Boolean, onClick: () -> Unit) {
    Button({
        if (disabled) attr("disabled", "")
        onClick { onClick() }
        style { deviceButtonStyle(primary = true, disabled = disabled) }
    }) { Text(label) }
}

@Composable
private fun DeviceSecondaryButton(label: String, disabled: Boolean, onClick: () -> Unit) {
    Button({
        if (disabled) attr("disabled", "")
        onClick { onClick() }
        style { deviceButtonStyle(primary = false, disabled = disabled) }
    }) { Text(label) }
}

@Composable
private fun DeviceDangerButton(label: String, disabled: Boolean, onClick: () -> Unit) {
    Button({
        if (disabled) attr("disabled", "")
        onClick { onClick() }
        style {
            padding(7.px, 11.px); borderRadius(6.px)
            border(1.px, LineStyle.Solid, Color("#E8CACA")); backgroundColor(Color.white)
            color(Color(SilkColors.error)); fontSize(12.px); property("cursor", if (disabled) "default" else "pointer")
            property("opacity", if (disabled) "0.6" else "1")
        }
    }) { Text(label) }
}

internal fun triggerPolicyLabel(policy: BindingTriggerPolicy): String = when (policy) {
    BindingTriggerPolicy.ALL -> "所有消息"
    BindingTriggerPolicy.MENTION -> "提及时"
    BindingTriggerPolicy.EVENT -> "事件"
}

private fun List<ManagedDeviceDto>.deviceName(deviceId: String): String =
    firstOrNull { it.deviceId == deviceId }?.displayName ?: "未知设备"

private fun org.jetbrains.compose.web.css.StyleScope.devicePageStyle() {
    minWidth(0.px); width(100.percent); property("min-height", "100%")
    backgroundColor(Color(SilkColors.background)); color(Color(SilkColors.textPrimary))
}

private fun org.jetbrains.compose.web.css.StyleScope.deviceHeaderStyle() {
    display(DisplayStyle.Flex); alignItems(AlignItems.Center); justifyContent(JustifyContent.SpaceBetween)
    padding(14.px, 22.px); property("background", "linear-gradient(135deg, ${SilkColors.primaryDark}, ${SilkColors.primary})")
    property("position", "sticky"); property("top", "0"); property("z-index", "2")
}

private fun org.jetbrains.compose.web.css.StyleScope.deviceContentStyle() {
    maxWidth(1120.px); width(100.percent); padding(26.px); property("margin", "0 auto")
}

private fun org.jetbrains.compose.web.css.StyleScope.pairingToolStyle() {
    padding(20.px); border(1.px, LineStyle.Solid, Color(SilkColors.border)); borderRadius(8.px)
    backgroundColor(Color(SilkColors.surfaceElevated)); property("box-shadow", "0 2px 10px rgba(74,64,56,0.05)")
}

private fun org.jetbrains.compose.web.css.StyleScope.deviceGridStyle() {
    property("display", "grid"); property("grid-template-columns", "repeat(auto-fit, minmax(280px, 1fr))"); gap(12.px)
}

private fun org.jetbrains.compose.web.css.StyleScope.deviceCardStyle() {
    padding(16.px); border(1.px, LineStyle.Solid, Color(SilkColors.border)); borderRadius(7.px)
    backgroundColor(Color(SilkColors.surfaceElevated)); minWidth(0.px)
}

private fun org.jetbrains.compose.web.css.StyleScope.agentDetailsDialogStyle() {
    width(620.px)
    property("max-width", "calc(100vw - 32px)")
    property("max-height", "calc(100vh - 48px)")
    property("overflow-y", "auto")
    property("box-sizing", "border-box")
    padding(20.px)
    borderRadius(8.px)
    backgroundColor(Color(SilkColors.surfaceElevated))
    property("box-shadow", "0 12px 36px rgba(0,0,0,0.18)")
}

private fun org.jetbrains.compose.web.css.StyleScope.agentDetailsSectionStyle() {
    marginTop(18.px)
    padding(14.px, 0.px, 0.px, 0.px)
    property("border-top", "1px solid ${SilkColors.divider}")
}

private fun org.jetbrains.compose.web.css.StyleScope.bindingFormStyle() {
    padding(16.px); marginBottom(12.px); border(1.px, LineStyle.Solid, Color(SilkColors.border)); borderRadius(7.px)
    backgroundColor(Color(SilkColors.surfaceElevated))
}

private fun org.jetbrains.compose.web.css.StyleScope.bindingRowStyle() {
    display(DisplayStyle.Flex); alignItems(AlignItems.Center); justifyContent(JustifyContent.SpaceBetween)
    gap(16.px); padding(14.px, 16.px); marginBottom(8.px)
    border(1.px, LineStyle.Solid, Color(SilkColors.border)); borderRadius(7.px)
    backgroundColor(Color(SilkColors.surfaceElevated))
}

private fun org.jetbrains.compose.web.css.StyleScope.deviceInputStyle() {
    width(100.percent); height(40.px); padding(9.px, 11.px); borderRadius(6.px)
    border(1.px, LineStyle.Solid, Color(SilkColors.border)); backgroundColor(Color.white)
    color(Color(SilkColors.textPrimary)); fontSize(13.px); property("box-sizing", "border-box")
}

private fun org.jetbrains.compose.web.css.StyleScope.deviceButtonStyle(primary: Boolean, disabled: Boolean) {
    padding(9.px, 15.px); borderRadius(6.px); fontSize(13.px); fontWeight("600")
    border(1.px, LineStyle.Solid, Color(if (primary) SilkColors.primaryDark else SilkColors.border))
    backgroundColor(Color(if (primary) SilkColors.primaryDark else SilkColors.surfaceElevated))
    color(Color(if (primary) "#FFFFFF" else SilkColors.textPrimary))
    property("cursor", if (disabled) "default" else "pointer"); property("opacity", if (disabled) "0.6" else "1")
}
