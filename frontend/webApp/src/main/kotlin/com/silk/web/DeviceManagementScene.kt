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

    var pairingCode by remember(user.id) { mutableStateOf(appState.pendingPairingCode.orEmpty()) }
    var pairingPreview by remember { mutableStateOf<PairingPreviewDto?>(null) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var pairingBusy by remember { mutableStateOf(false) }

    var selectedAgentId by remember { mutableStateOf("") }
    var selectedTargetType by remember { mutableStateOf(BindingTargetType.ROOM) }
    var selectedTargetId by remember { mutableStateOf("") }
    var selectedTrigger by remember { mutableStateOf(BindingTriggerPolicy.MENTION) }
    var selectedPermissions by remember { mutableStateOf(defaultBindingPermissions()) }
    var editingBindingId by remember { mutableStateOf<String?>(null) }

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

            DeviceSectionTitle("已登记设备", devices.count { it.status == DeviceEnrollmentStatus.ACTIVE })
            if (isLoading) {
                DeviceEmptyState("加载中…")
            } else if (devices.isEmpty()) {
                DeviceEmptyState("暂无设备")
            } else {
                Div({ style { deviceGridStyle() } }) {
                    devices.forEach { device ->
                        DeviceCard(device, busyAction == "device:${device.deviceId}") {
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
                        }
                    }
                }
            }

            DeviceSectionTitle("Agent", agents.count { it.status == ManagedAgentStatus.ACTIVE })
            if (isLoading) {
                DeviceEmptyState("加载中…")
            } else if (agents.isEmpty()) {
                DeviceEmptyState("暂无 Agent")
            } else {
                Div({ style { deviceGridStyle() } }) {
                    agents.forEach { agent ->
                        AgentCard(agent, busyAction == "agent:${agent.agentInstanceId}") {
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
                        }
                    }
                }
            }

            DeviceSectionTitle("使用范围", bindings.count { it.status == ManagedBindingStatus.ACTIVE })
            BindingCreator(
                agents = agents.filter { it.status == ManagedAgentStatus.ACTIVE },
                targets = targets,
                selectedAgentId = selectedAgentId,
                selectedTargetType = selectedTargetType,
                selectedTargetId = selectedTargetId,
                selectedTrigger = selectedTrigger,
                selectedPermissions = selectedPermissions,
                editing = editingBindingId != null,
                busy = busyAction == "binding:create",
                onAgentChange = { selectedAgentId = it },
                onTargetTypeChange = { type ->
                    selectedTargetType = type
                    selectedTargetId = targets.firstOrNull { it.type == type }?.id.orEmpty()
                    selectedPermissions = defaultBindingPermissions()
                },
                onTargetChange = { selectedTargetId = it },
                onTriggerChange = { selectedTrigger = it },
                onPermissionChange = { permission, enabled ->
                    selectedPermissions = updateBindingPermissionSelection(
                        selectedPermissions,
                        permission,
                        enabled,
                    )
                },
                onCancel = { editingBindingId = null },
                onSubmit = {
                    scope.launch {
                        busyAction = "binding:create"
                        feedback = null
                        pageError = null
                        try {
                            val request = CreateManagedBindingRequest(
                                agentInstanceId = selectedAgentId,
                                targetType = selectedTargetType,
                                targetId = selectedTargetId,
                                messageScope = compatibleMessageScope(selectedTargetType),
                                triggerPolicy = selectedTrigger,
                                permissions = selectedPermissions,
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
                            pageError = error.message ?: "添加使用范围失败"
                        } finally {
                            busyAction = null
                        }
                    }
                },
            )

            if (bindings.isEmpty()) {
                DeviceEmptyState("暂无使用范围")
            } else {
                bindings.forEach { binding ->
                    BindingRow(
                        binding = binding,
                        agentName = agents.firstOrNull { it.agentInstanceId == binding.agentInstanceId }?.displayName
                            ?: binding.agentDisplayName,
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
                            selectedPermissions = binding.permissions
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
        }
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
private fun DeviceCard(device: ManagedDeviceDto, busy: Boolean, onRevoke: () -> Unit) {
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
            StatusBadge(if (device.connected) "已连接" else device.status.name, device.connected)
        }
        DeviceMetadata("指纹", fingerprintSuffix(device.publicKeyFingerprint))
        DeviceMetadata("最近连接", device.lastSeenAtEpochMs?.let {
            formatMessageTimestampForWeb(it, includeSeconds = false)
        } ?: "尚未连接")
        device.lastSeenIp?.let { DeviceMetadata("最近地址", it) }
        if (device.status == DeviceEnrollmentStatus.ACTIVE) {
            Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd); marginTop(14.px) } }) {
                DeviceDangerButton(if (busy) "撤销中…" else "撤销设备", busy, onRevoke)
            }
        }
    }
}

@Composable
private fun AgentCard(agent: ManagedAgentDto, busy: Boolean, onRevoke: () -> Unit) {
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
            StatusBadge(if (agent.connected) "已连接" else agent.status.name, agent.connected)
        }
        DeviceMetadata("最近连接", agent.lastSeenAtEpochMs?.let {
            formatMessageTimestampForWeb(it, includeSeconds = false)
        } ?: "尚未连接")
        DeviceMetadata("能力", agent.capabilities.sortedBy { it.name }.joinToString(" · ") { it.name })
        if (agent.status == ManagedAgentStatus.ACTIVE) {
            Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd); marginTop(14.px) } }) {
                DeviceDangerButton(if (busy) "撤销中…" else "撤销 Agent", busy, onRevoke)
            }
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
    selectedPermissions: Set<BindingPermission>,
    editing: Boolean,
    busy: Boolean,
    onAgentChange: (String) -> Unit,
    onTargetTypeChange: (BindingTargetType) -> Unit,
    onTargetChange: (String) -> Unit,
    onTriggerChange: (BindingTriggerPolicy) -> Unit,
    onPermissionChange: (BindingPermission, Boolean) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    val availableTargets = targets.filter { it.type == selectedTargetType }
    Div({ style { bindingFormStyle() } }) {
        Div({ style { property("display", "grid"); property("grid-template-columns", "repeat(auto-fit, minmax(180px, 1fr))"); gap(12.px) } }) {
            DeviceSelectField("Agent", selectedAgentId, onAgentChange) {
                if (agents.isEmpty()) Option("") { Text("没有可用 Agent") }
                agents.forEach { agent ->
                    Option(agent.agentInstanceId, attrs = { if (agent.agentInstanceId == selectedAgentId) attr("selected", "") }) {
                        Text(agent.displayName)
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
        Div({ style { marginTop(14.px) } }) {
            Div({ style { fontSize(12.px); fontWeight("600"); color(Color(SilkColors.textSecondary)); marginBottom(7.px) } }) {
                Text("权限")
            }
            Div({ style { display(DisplayStyle.Flex); gap(12.px); property("flex-wrap", "wrap") } }) {
                availableBindingPermissions(selectedTargetType).forEach { permission ->
                    BindingPermissionToggle(
                        permission = permission,
                        checked = permission in selectedPermissions,
                        onChange = { enabled -> onPermissionChange(permission, enabled) },
                    )
                }
            }
        }
        Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.SpaceBetween); alignItems(AlignItems.Center); gap(12.px); marginTop(14.px); property("flex-wrap", "wrap") } }) {
            Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)) } }) {
                Text("已选择 ${selectedPermissions.size} 项")
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
                    disabled = busy || selectedAgentId.isBlank() || selectedTargetId.isBlank(),
                    onClick = onSubmit,
                )
            }
        }
    }
}

@Composable
private fun BindingPermissionToggle(
    permission: BindingPermission,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            gap(6.px)
            fontSize(12.px)
            color(Color(SilkColors.textPrimary))
        }
    }) {
        Input(InputType.Checkbox) {
            checked(checked)
            onInput { onChange(!checked) }
        }
        Text(bindingPermissionLabel(permission))
    }
}

@Composable
private fun DeviceSelectField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    options: @Composable () -> Unit,
) {
    Div {
        Div({ style { fontSize(12.px); fontWeight("600"); color(Color(SilkColors.textSecondary)); marginBottom(6.px) } }) {
            Text(label)
        }
        Select({
            attr("aria-label", label)
            attr("value", value)
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
                Text("${binding.targetType.name} · ${triggerPolicyLabel(binding.triggerPolicy)} · ${binding.permissions.joinToString(" · ") { it.name }}")
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

private fun triggerPolicyLabel(policy: BindingTriggerPolicy): String = when (policy) {
    BindingTriggerPolicy.ALL -> "所有消息"
    BindingTriggerPolicy.MENTION -> "提及时"
    BindingTriggerPolicy.EVENT -> "事件"
}

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
