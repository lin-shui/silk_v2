package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.Position
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flex
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.gap
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.maxWidth
import org.jetbrains.compose.web.css.minWidth
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.position
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.vh
import org.jetbrains.compose.web.css.vw
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
internal fun AgentBindingManagementDialog(
    targetType: BindingTargetType,
    targetId: String,
    targetName: String,
    workspaceOwnerId: String? = null,
    activeAgentInstanceId: String = "",
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var devices by remember(targetType, targetId) { mutableStateOf<List<ManagedDeviceDto>>(emptyList()) }
    var agents by remember(targetType, targetId) { mutableStateOf<List<ManagedAgentDto>>(emptyList()) }
    var bindings by remember(targetType, targetId) { mutableStateOf<List<ManagedBindingDto>>(emptyList()) }
    var loading by remember(targetType, targetId) { mutableStateOf(true) }
    var refreshKey by remember(targetType, targetId) { mutableStateOf(0) }
    var errorMessage by remember(targetType, targetId) { mutableStateOf<String?>(null) }
    var feedback by remember(targetType, targetId) { mutableStateOf<String?>(null) }
    var busyAction by remember(targetType, targetId) { mutableStateOf<String?>(null) }
    var editingBindingId by remember(targetType, targetId) { mutableStateOf<String?>(null) }
    var selectedAgentId by remember(targetType, targetId) { mutableStateOf("") }
    var selectedTrigger by remember(targetType, targetId) {
        mutableStateOf(if (targetType == BindingTargetType.ROOM) BindingTriggerPolicy.MENTION else BindingTriggerPolicy.ALL)
    }
    var selectedMentionAlias by remember(targetType, targetId) { mutableStateOf("") }
    var selectedPermissions by remember(targetType, targetId) { mutableStateOf(defaultBindingPermissions()) }
    var currentAgentInstanceId by remember(targetType, targetId) { mutableStateOf(activeAgentInstanceId) }

    val activeAgents = agents.filter { it.status == ManagedAgentStatus.ACTIVE }
    val targetBindings = bindings.filter {
        it.targetType == targetType && it.targetId == targetId && it.status != ManagedBindingStatus.REVOKED
    }
    val deviceNames = devices.associate { it.deviceId to it.displayName }
    val mentionConflict = managedAgentMentionConflictMessage(
        bindings = bindings,
        targetType = targetType,
        targetId = targetId,
        mentionAlias = selectedMentionAlias,
        excludingBindingId = editingBindingId,
    )

    fun resetForm(preferredAgentId: String? = null) {
        val availableAgents = agents.filter { it.status == ManagedAgentStatus.ACTIVE }
        editingBindingId = null
        selectedAgentId = preferredAgentId
            ?.takeIf { id -> availableAgents.any { it.agentInstanceId == id } }
            ?: availableAgents.firstOrNull()?.agentInstanceId.orEmpty()
        selectedMentionAlias = availableAgents.firstOrNull { it.agentInstanceId == selectedAgentId }
            ?.let { defaultManagedAgentMentionAlias(it.agentType) }
            .orEmpty()
        selectedTrigger = if (targetType == BindingTargetType.ROOM) {
            BindingTriggerPolicy.MENTION
        } else {
            BindingTriggerPolicy.ALL
        }
        selectedPermissions = defaultBindingPermissions()
        errorMessage = null
    }

    suspend fun selectWorkspaceAgent(binding: ManagedBindingDto): Boolean {
        val ownerId = workspaceOwnerId ?: return true
        val response = ApiClient.updateCcSettings(
            userId = ownerId,
            workspaceId = targetId,
            activeAgent = binding.agentType,
            activeAgentInstanceId = binding.agentInstanceId,
        )
        if (!response.success) {
            errorMessage = response.error ?: "Agent 已添加，但设为当前 Agent 失败"
            return false
        }
        currentAgentInstanceId = binding.agentInstanceId
        return true
    }

    LaunchedEffect(activeAgentInstanceId) {
        currentAgentInstanceId = activeAgentInstanceId
    }

    LaunchedEffect(targetType, targetId, refreshKey) {
        loading = true
        errorMessage = null
        try {
            devices = ApiClient.getManagedDevices()
            agents = ApiClient.getManagedAgents()
            bindings = ApiClient.getManagedBindings()
            if (selectedAgentId !in agents.filter { it.status == ManagedAgentStatus.ACTIVE }
                    .map { it.agentInstanceId }
            ) {
                resetForm(currentAgentInstanceId)
            }
        } catch (error: Exception) {
            errorMessage = error.message ?: "加载 Agent 失败"
        } finally {
            loading = false
        }
    }

    Div({
        style {
            position(Position.Fixed)
            property("inset", "0")
            backgroundColor(Color("rgba(40, 36, 32, 0.48)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            padding(16.px)
            property("z-index", "1300")
            property("backdrop-filter", "blur(2px)")
        }
        onClick { onDismiss() }
    }) {
        Div({
            style {
                width(760.px)
                maxWidth(94.vw)
                property("max-height", "88vh")
                property("overflow-y", "auto")
                backgroundColor(Color(SilkColors.surfaceElevated))
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                borderRadius(8.px)
                padding(22.px)
                property("box-sizing", "border-box")
                property("box-shadow", "0 16px 44px rgba(0,0,0,0.22)")
            }
            onClick { it.stopPropagation() }
        }) {
            Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.SpaceBetween); gap(16.px) } }) {
                Div({ style { minWidth(0.px) } }) {
                    H3({ style { marginTop(0.px); marginBottom(5.px); fontSize(18.px); color(Color(SilkColors.textPrimary)) } }) {
                        Text("Agent")
                    }
                    Div({ style { fontSize(13.px); color(Color(SilkColors.textSecondary)) } }) {
                        Text(if (targetType == BindingTargetType.ROOM) "Room · $targetName" else "Workspace · $targetName")
                    }
                }
                AgentDialogButton("关闭", secondary = true, onClick = onDismiss)
            }

            feedback?.let { AgentDialogNotice(it, false) }
            errorMessage?.let { AgentDialogNotice(it, true) }

            Div({ style { marginTop(20.px); marginBottom(10.px); fontSize(14.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                Text("已添加 ${targetBindings.size}")
            }
            when {
                loading -> AgentDialogEmpty("加载中…")
                targetBindings.isEmpty() -> AgentDialogEmpty("此处还没有 Agent")
                else -> targetBindings.forEach { binding ->
                    val isCurrent = targetType == BindingTargetType.WORKSPACE &&
                        binding.agentInstanceId == currentAgentInstanceId
                    val selectableWorkspaceBinding = targetType == BindingTargetType.WORKSPACE &&
                        binding.status == ManagedBindingStatus.ACTIVE
                    val canSelect = selectableWorkspaceBinding &&
                        binding.ownerId == workspaceOwnerId && !isCurrent
                    AgentTargetBindingRow(
                        binding = binding,
                        current = isCurrent,
                        busy = busyAction == binding.bindingId,
                        onSelect = if (canSelect) {
                            {
                                scope.launch {
                                    busyAction = binding.bindingId
                                    errorMessage = null
                                    if (selectWorkspaceAgent(binding)) {
                                        feedback = "已切换到 ${binding.agentDisplayName} · ${binding.agentDeviceDisplayName}"
                                        onChanged()
                                    }
                                    busyAction = null
                                }
                            }
                        } else null,
                        onApprove = if (binding.canApproveAsAgentOwner || binding.canApproveAsTargetManager) {
                            {
                                scope.launch {
                                    busyAction = binding.bindingId
                                    errorMessage = null
                                    try {
                                        val decided = ApiClient.decideManagedBinding(binding.bindingId, approve = true)
                                        feedback = bindingApprovalSummary(decided) ?: "Agent 已批准"
                                        if (targetType == BindingTargetType.WORKSPACE &&
                                            decided.status == ManagedBindingStatus.ACTIVE &&
                                            decided.ownerId == workspaceOwnerId
                                        ) {
                                            selectWorkspaceAgent(decided)
                                        }
                                        refreshKey++
                                        onChanged()
                                    } catch (error: Exception) {
                                        errorMessage = error.message ?: "批准 Agent 失败"
                                    } finally {
                                        busyAction = null
                                    }
                                }
                            }
                        } else null,
                        onEdit = if (binding.canEdit) {
                            {
                                editingBindingId = binding.bindingId
                                selectedAgentId = binding.agentInstanceId
                                selectedTrigger = binding.triggerPolicy
                                selectedMentionAlias = binding.mentionAlias
                                selectedPermissions = binding.permissions
                                errorMessage = null
                            }
                        } else null,
                        onRemove = if (binding.canRevoke) {
                            {
                                if (window.confirm("从这里移除 ${binding.agentDisplayName}？")) {
                                    scope.launch {
                                        busyAction = binding.bindingId
                                        errorMessage = null
                                        try {
                                            ApiClient.revokeManagedBinding(binding.bindingId)
                                            if (binding.agentInstanceId == currentAgentInstanceId && workspaceOwnerId != null) {
                                                ApiClient.updateCcSettings(
                                                    userId = workspaceOwnerId,
                                                    workspaceId = targetId,
                                                    activeAgent = binding.agentType,
                                                )
                                                currentAgentInstanceId = ""
                                            }
                                            feedback = "Agent 已移除"
                                            refreshKey++
                                            onChanged()
                                        } catch (error: Exception) {
                                            errorMessage = error.message ?: "移除 Agent 失败"
                                        } finally {
                                            busyAction = null
                                        }
                                    }
                                }
                            }
                        } else null,
                    )
                }
            }

            Div({ style { marginTop(22.px); marginBottom(10.px); fontSize(14.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                Text(if (editingBindingId == null) "添加 Agent" else "修改 Agent")
            }
            if (!loading && activeAgents.isEmpty()) {
                AgentDialogEmpty("没有可用 Agent，请先在设备页面完成设备与 Agent 配对。")
            } else {
                Div({ style { agentDialogFormStyle() } }) {
                    AgentDialogSelect("Agent", selectedAgentId, { value ->
                        selectedAgentId = value
                        if (editingBindingId == null) {
                            selectedMentionAlias = activeAgents.firstOrNull { it.agentInstanceId == value }
                                ?.let { defaultManagedAgentMentionAlias(it.agentType) }
                                .orEmpty()
                        }
                        errorMessage = null
                    }) {
                        if (activeAgents.isEmpty()) Option("") { Text("没有可用 Agent") }
                        activeAgents.forEach { agent ->
                            Option(agent.agentInstanceId, attrs = {
                                if (agent.agentInstanceId == selectedAgentId) attr("selected", "")
                            }) {
                                val state = if (agent.connected) "在线" else "离线"
                                Text("${agent.displayName} · ${deviceNames[agent.deviceId] ?: "未知设备"} · $state")
                            }
                        }
                    }

                    if (targetType == BindingTargetType.ROOM) {
                        Div({ style { marginTop(14.px) } }) {
                            AgentDialogLabel("提及词")
                            Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); gap(6.px) } }) {
                                Span({ style { color(Color(SilkColors.textSecondary)) } }) { Text("@") }
                                Input(InputType.Text) {
                                    value(selectedMentionAlias)
                                    attr("placeholder", "例如 cc-linux")
                                    attr("aria-label", "Agent 提及词")
                                    onInput {
                                        selectedMentionAlias = formatManagedAgentMentionAlias(it.value)
                                        errorMessage = null
                                    }
                                    style { agentDialogInputStyle() }
                                }
                            }
                            Div({ style { marginTop(5.px); fontSize(11.px); color(Color(SilkColors.textSecondary)) } }) {
                                Text("同一 Room 内必须唯一，用于 @ 提及这个 Agent。")
                            }
                            mentionConflict?.let { AgentDialogInlineError(it) }
                        }
                        Div({ style { marginTop(14.px) } }) {
                            AgentDialogSelect("触发方式", selectedTrigger.name, {
                                selectedTrigger = BindingTriggerPolicy.valueOf(it)
                            }) {
                                BindingTriggerPolicy.entries.forEach { policy ->
                                    Option(policy.name, attrs = { if (policy == selectedTrigger) attr("selected", "") }) {
                                        Text(triggerPolicyLabel(policy))
                                    }
                                }
                            }
                        }
                    }

                    Div({ style { marginTop(14.px) } }) {
                        AgentDialogLabel("权限")
                        Div({ style { display(DisplayStyle.Flex); gap(12.px); property("flex-wrap", "wrap") } }) {
                            availableBindingPermissions(targetType).forEach { permission ->
                                Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); gap(6.px); fontSize(12.px) } }) {
                                    Input(InputType.Checkbox) {
                                        checked(permission in selectedPermissions)
                                        onInput {
                                            selectedPermissions = updateBindingPermissionSelection(
                                                selectedPermissions,
                                                permission,
                                                permission !in selectedPermissions,
                                            )
                                        }
                                    }
                                    Text(bindingPermissionLabel(permission))
                                }
                            }
                        }
                    }

                    Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.FlexEnd); gap(8.px); marginTop(18.px) } }) {
                        if (editingBindingId != null) {
                            AgentDialogButton("取消", secondary = true) { resetForm() }
                        }
                        AgentDialogButton(
                            label = if (busyAction == "save") "保存中…" else if (editingBindingId == null) "添加" else "保存",
                            disabled = busyAction != null || selectedAgentId.isBlank() ||
                                targetType == BindingTargetType.ROOM &&
                                (!isManagedAgentMentionAliasValid(selectedMentionAlias) || mentionConflict != null),
                        ) {
                            scope.launch {
                                busyAction = "save"
                                errorMessage = null
                                try {
                                    val request = CreateManagedBindingRequest(
                                        agentInstanceId = selectedAgentId,
                                        targetType = targetType,
                                        targetId = targetId,
                                        messageScope = compatibleMessageScope(targetType),
                                        triggerPolicy = selectedTrigger,
                                        mentionAlias = selectedMentionAlias,
                                        permissions = selectedPermissions,
                                    )
                                    val editingId = editingBindingId
                                    val saved = if (editingId == null) {
                                        ApiClient.createManagedBinding(request)
                                    } else {
                                        ApiClient.updateManagedBinding(editingId, request)
                                    }
                                    if (targetType == BindingTargetType.WORKSPACE && saved.status == ManagedBindingStatus.ACTIVE) {
                                        selectWorkspaceAgent(saved)
                                    }
                                    feedback = bindingApprovalSummary(saved)
                                        ?: if (editingId == null) "Agent 已添加" else "Agent 设置已更新"
                                    resetForm(saved.agentInstanceId)
                                    refreshKey++
                                    onChanged()
                                } catch (error: Exception) {
                                    errorMessage = error.message ?: "保存 Agent 失败"
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
private fun AgentTargetBindingRow(
    binding: ManagedBindingDto,
    current: Boolean,
    busy: Boolean,
    onSelect: (() -> Unit)?,
    onApprove: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            gap(12.px)
            padding(12.px, 0.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
        }
    }) {
        Div({ style { minWidth(0.px); flex(1) } }) {
            Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); gap(7.px); property("flex-wrap", "wrap") } }) {
                Span({ style { fontSize(14.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                    Text(listOf(binding.agentDisplayName, binding.agentDeviceDisplayName).filter(String::isNotBlank).joinToString(" · "))
                }
                AgentDialogBadge(bindingStatusLabel(binding.status), binding.status == ManagedBindingStatus.ACTIVE)
                if (current) AgentDialogBadge("当前", true)
            }
            Div({ style { marginTop(5.px); fontSize(12.px); color(Color(SilkColors.textSecondary)) } }) {
                val mention = binding.mentionAlias.takeIf {
                    binding.targetType == BindingTargetType.ROOM && !it.startsWith("__")
                }?.let { "@$it · " }.orEmpty()
                Text("$mention${triggerPolicyLabel(binding.triggerPolicy)} · ${binding.permissions.joinToString(" · ") { bindingPermissionLabel(it) }}")
            }
            bindingApprovalSummary(binding)?.let { summary ->
                Div({ style { marginTop(4.px); fontSize(12.px); color(Color(SilkColors.textSecondary)) } }) { Text(summary) }
            }
        }
        Div({ style { display(DisplayStyle.Flex); gap(6.px); property("flex-wrap", "wrap") } }) {
            if (onSelect != null) AgentDialogButton(if (busy) "切换中…" else "设为当前", disabled = busy, onClick = onSelect)
            if (onApprove != null) AgentDialogButton(if (busy) "处理中…" else "批准", disabled = busy, onClick = onApprove)
            if (onEdit != null) AgentDialogButton("编辑", secondary = true, disabled = busy, onClick = onEdit)
            if (onRemove != null) AgentDialogButton("移除", danger = true, disabled = busy, onClick = onRemove)
        }
    }
}

@Composable
private fun AgentDialogSelect(label: String, value: String, onChange: (String) -> Unit, options: @Composable () -> Unit) {
    Div {
        AgentDialogLabel(label)
        Select({
            attr("aria-label", label)
            attr("value", value)
            onChange { onChange(it.value ?: "") }
            style { agentDialogInputStyle() }
        }) { options() }
    }
}

@Composable
private fun AgentDialogLabel(label: String) {
    Div({ style { marginBottom(6.px); fontSize(12.px); fontWeight("600"); color(Color(SilkColors.textSecondary)) } }) {
        Text(label)
    }
}

@Composable
private fun AgentDialogButton(
    label: String,
    secondary: Boolean = false,
    danger: Boolean = false,
    disabled: Boolean = false,
    onClick: () -> Unit,
) {
    Button({
        if (disabled) attr("disabled", "")
        onClick { onClick() }
        style {
            borderRadius(5.px)
            padding(7.px, 11.px)
            fontSize(12.px)
            property("cursor", if (disabled) "default" else "pointer")
            property("white-space", "nowrap")
            when {
                danger -> {
                    border(1.px, LineStyle.Solid, Color("#D9A3A3"))
                    backgroundColor(Color("#FFF4F4"))
                    color(Color(SilkColors.error))
                }
                secondary -> {
                    border(1.px, LineStyle.Solid, Color(SilkColors.border))
                    backgroundColor(Color(SilkColors.surfaceElevated))
                    color(Color(SilkColors.textPrimary))
                }
                else -> {
                    border(1.px, LineStyle.Solid, Color(SilkColors.primary))
                    backgroundColor(Color(SilkColors.primary))
                    color(Color.white)
                }
            }
            if (disabled) property("opacity", "0.55")
        }
    }) { Text(label) }
}

@Composable
private fun AgentDialogBadge(label: String, positive: Boolean) {
    Span({
        style {
            borderRadius(4.px)
            padding(2.px, 6.px)
            fontSize(11.px)
            backgroundColor(Color(if (positive) "#E8F4E5" else "#F3EFE8"))
            color(Color(if (positive) "#477A3B" else SilkColors.textSecondary))
        }
    }) { Text(label) }
}

@Composable
private fun AgentDialogNotice(message: String, error: Boolean) {
    Div({
        style {
            marginTop(14.px)
            padding(9.px, 11.px)
            borderRadius(5.px)
            backgroundColor(Color(if (error) "#FFF0F0" else "#EEF7EB"))
            color(Color(if (error) SilkColors.error else "#477A3B"))
            fontSize(12.px)
        }
    }) { Text(message) }
}

@Composable
private fun AgentDialogInlineError(message: String) {
    Div({ style { marginTop(6.px); color(Color(SilkColors.error)); fontSize(12.px) } }) { Text(message) }
}

@Composable
private fun AgentDialogEmpty(message: String) {
    Div({
        style {
            width(100.percent)
            padding(18.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            borderRadius(6.px)
            color(Color(SilkColors.textSecondary))
            fontSize(13.px)
            property("box-sizing", "border-box")
        }
    }) { Text(message) }
}

private fun org.jetbrains.compose.web.css.StyleScope.agentDialogFormStyle() {
    border(1.px, LineStyle.Solid, Color(SilkColors.border))
    borderRadius(6.px)
    padding(16.px)
    backgroundColor(Color("rgba(255,255,255,0.55)"))
}

private fun org.jetbrains.compose.web.css.StyleScope.agentDialogInputStyle() {
    width(100.percent)
    minWidth(0.px)
    padding(8.px, 10.px)
    border(1.px, LineStyle.Solid, Color(SilkColors.border))
    borderRadius(5.px)
    backgroundColor(Color.white)
    color(Color(SilkColors.textPrimary))
    fontSize(13.px)
    property("box-sizing", "border-box")
}
