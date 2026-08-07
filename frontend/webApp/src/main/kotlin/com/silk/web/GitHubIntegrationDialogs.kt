@file:Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")

package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.silk.web.workspace.IssueToWorkspaceResponse
import com.silk.web.workspace.WorkspaceApiException
import com.silk.web.workspace.WorkspaceDto
import com.silk.web.workspace.createWorkspaceFromGithubIssue
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
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
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
internal fun GitHubIntegrationDialog(
    roomId: String,
    canManage: Boolean,
    onDismiss: () -> Unit,
    onBindingChanged: (GitBindingSummary?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var binding by remember { mutableStateOf<GitBindingSummary?>(null) }
    var repositoryUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(roomId) {
        loading = true
        val loaded = ApiClient.getGitBinding(roomId).takeIf { it.enabled }
        binding = loaded
        repositoryUrl = loaded?.let { "https://github.com/${it.owner}/${it.repo}" }.orEmpty()
        loading = false
    }

    fun displayError(result: GitBindingOperationResult) {
        errorMessage = result.message.ifBlank { result.errorCode.ifBlank { "GitHub 操作失败" } }
    }

    ModalOverlay(onDismiss = { if (!saving) onDismiss() }, zIndex = 2200) {
        Div({
            style {
                width(480.px)
                property("max-width", "calc(100vw - 32px)")
                property("max-height", "calc(100vh - 32px)")
                property("overflow-y", "auto")
                padding(24.px)
                backgroundColor(Color.white)
                borderRadius(10.px)
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.16)")
            }
        }) {
            H3({ style { marginTop(0.px); marginBottom(8.px); color(Color(SilkColors.textPrimary)) } }) {
                Text("GitHub 集成")
            }
            Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginBottom(16.px) } }) {
                Text("将 GitHub Issue、Pull Request 和检查结果同步到当前 Team Channel。")
            }

            if (loading) {
                Div({ style { color(Color(SilkColors.textSecondary)); padding(12.px, 0.px) } }) {
                    Text("正在读取集成状态…")
                }
            } else {
                binding?.let { current ->
                    Div({
                        style {
                            padding(12.px)
                            marginBottom(14.px)
                            border(1.px, LineStyle.Solid, Color("#D7E8D7"))
                            borderRadius(6.px)
                            backgroundColor(Color("#F5FBF5"))
                        }
                    }) {
                        Div({ style { fontWeight("600"); color(Color("#2E7D32")); marginBottom(4.px) } }) {
                            Text("● 已连接")
                        }
                        Div({ style { fontSize(13.px); color(Color(SilkColors.textPrimary)) } }) {
                            Text("${current.owner}/${current.repo}")
                        }
                        Div({ style { fontSize(11.px); color(Color(SilkColors.textSecondary)); marginTop(4.px) } }) {
                            Text("同步方式：${transportModeLabel(current)}")
                        }
                        Div({ style { fontSize(11.px); color(Color(SilkColors.textSecondary)); marginTop(2.px) } }) {
                            Text("事件：${current.events.joinToString(", ").ifBlank { "issues" }}")
                        }
                        if (isPollingMode(current.mode)) {
                            Div({
                                style {
                                    fontSize(11.px); color(Color("#B26A00")); marginTop(4.px)
                                    property("background", "#FFF8E6")
                                    property("padding", "4px 6px")
                                    borderRadius(4.px)
                                }
                            }) {
                                Text("ℹ CI 检查事件不可用（无公网入口）。需要配置 GITHUB_WEBHOOK_BASE_URL 后重新绑定可启用。")
                            }
                        }
                        lastActivityAt(current)?.let { timestamp ->
                            Div({ style { fontSize(11.px); color(Color(SilkColors.textSecondary)); marginTop(2.px) } }) {
                                Text("最近同步：$timestamp")
                            }
                        }
                    }
                }

                if (canManage) {
                    WorkspaceFormLabel(if (binding == null) "仓库地址" else "更换仓库")
                    Input(InputType.Text) {
                        value(repositoryUrl)
                        onInput { repositoryUrl = it.value }
                        attr("placeholder", "https://github.com/owner/repository")
                        style { workspaceFormInputStyle() }
                    }
                    WorkspaceFormLabel(if (binding == null) "Personal Access Token" else "新 Token（更换时填写）")
                    Input(InputType.Password) {
                        value(token)
                        onInput { token = it.value }
                        attr("placeholder", if (binding == null) "仅用于配置，不会显示或回填" else "留空则保持当前凭据")
                        style { workspaceFormInputStyle() }
                    }
                    Div({ style { fontSize(11.px); color(Color(SilkColors.textSecondary)); marginTop(6.px) } }) {
                        Text("Token 只会在服务端加密保存；重新绑定时会替换 GitHub Webhook。")
                    }
                } else if (binding == null) {
                    Div({ style { color(Color(SilkColors.textSecondary)); padding(10.px, 0.px) } }) {
                        Text("只有 Room Owner 可以配置 GitHub 集成。")
                    }
                }
            }

            errorMessage?.let { message ->
                Div({ style { color(Color(SilkColors.error)); fontSize(12.px); marginTop(12.px) } }) { Text(message) }
            }
            feedback?.let { message ->
                Div({ style { color(Color("#2E7D32")); fontSize(12.px); marginTop(12.px) } }) { Text(message) }
            }

            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.FlexEnd)
                    property("gap", "8px")
                    marginTop(20.px)
                }
            }) {
                if (binding != null && canManage) {
                    WorkspaceDangerButton("解绑", disabled = saving) {
                        scope.launch {
                            saving = true
                            errorMessage = null
                            val result = ApiClient.unbindGitHub(roomId)
                            saving = false
                            if (result.success) {
                                binding = null
                                repositoryUrl = ""
                                token = ""
                                feedback = "GitHub 集成已解绑"
                                onBindingChanged(null)
                            } else {
                                displayError(result)
                            }
                        }
                    }
                }
                WorkspaceSecondaryButton("关闭", disabled = saving, onClick = onDismiss)
                if (canManage) {
                    WorkspacePrimaryButton(
                        label = if (saving) "保存中…" else "保存",
                        disabled = saving || repositoryUrl.isBlank() || (binding == null && token.isBlank()),
                    ) {
                        scope.launch {
                            saving = true
                            errorMessage = null
                            feedback = null
                            val result = ApiClient.bindGitHub(roomId, repositoryUrl.trim(), token)
                            saving = false
                            if (result.success && result.binding != null) {
                                binding = result.binding
                                token = ""
                                repositoryUrl = "https://github.com/${result.binding.owner}/${result.binding.repo}"
                                feedback = "GitHub 集成已更新"
                                onBindingChanged(result.binding)
                            } else {
                                displayError(result)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GithubIssueWorkspaceDialog(
    userId: String,
    roomId: String,
    issueNumber: Int,
    agents: List<AgentInfo>,
    initialWorkingDir: String,
    onDismiss: () -> Unit,
    onCreated: (IssueToWorkspaceResponse) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(issueNumber) { mutableStateOf("Issue #$issueNumber") }
    var workingDir by remember(issueNumber) { mutableStateOf(initialWorkingDir) }
    var agentType by remember(agents) { mutableStateOf(agents.firstOrNull { it.connected }?.agentType.orEmpty()) }
    var visibility by remember { mutableStateOf("PRIVATE") }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showTrustConfirm by remember { mutableStateOf(false) }
    var trustBridgeId by remember { mutableStateOf<String?>(null) }

    suspend fun create() {
        val token = JwtManager.getAccessToken()
        if (token == null) {
            errorMessage = "登录状态已失效"
            return
        }
        if (workingDir.isBlank() || agentType.isBlank()) {
            errorMessage = "请填写 Agent 和工作目录"
            return
        }
        saving = true
        errorMessage = null
        try {
            val response = createWorkspaceFromGithubIssue(
                roomId = roomId,
                authToken = token,
                issueNumber = issueNumber,
                workingDir = workingDir.trim(),
                agentType = agentType,
                visibility = visibility,
                name = name.trim().ifBlank { null },
            )
            onCreated(response)
        } catch (e: WorkspaceApiException) {
            if (e.errorCode == "DIRECTORY_NOT_TRUSTED") {
                trustBridgeId = e.bridgeId
                showTrustConfirm = true
            } else {
                errorMessage = e.message
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Issue 工作区创建失败"
        } finally {
            saving = false
        }
    }

    suspend fun validateAndCreate() {
        if (workingDir.isBlank()) {
            errorMessage = "请选择工作目录"
            return
        }
        when (val trust = ApiClient.checkTrustedDir(userId, workingDir.trim())) {
            is ApiClient.TrustCheckResult.Trusted -> create()
            is ApiClient.TrustCheckResult.NotTrusted -> {
                trustBridgeId = trust.bridgeId
                showTrustConfirm = true
            }
            is ApiClient.TrustCheckResult.BridgeDisconnected -> errorMessage = "Bridge 未连接"
            is ApiClient.TrustCheckResult.Error -> errorMessage = trust.message
        }
    }

    ModalOverlay(onDismiss = { if (!saving) onDismiss() }, zIndex = 2200) {
        Div({
            style {
                width(460.px)
                property("max-width", "calc(100vw - 32px)")
                padding(24.px)
                backgroundColor(Color.white)
                borderRadius(10.px)
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.16)")
            }
        }) {
            H3({ style { marginTop(0.px); marginBottom(8.px); color(Color(SilkColors.textPrimary)) } }) {
                Text("从 GitHub Issue 开始开发")
            }
            Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginBottom(14.px) } }) {
                Text("创建后会在新工作区顶部展示 Issue 摘要。")
            }
            WorkspaceFormLabel("工作区名称")
            Input(InputType.Text) {
                value(name)
                onInput { name = it.value }
                style { workspaceFormInputStyle() }
            }
            WorkspaceFormLabel("Agent")
            Select({
                style { workspaceFormInputStyle() }
                onChange { agentType = it.value ?: "" }
            }) {
                if (agents.isEmpty()) Option("") { Text("没有可用 Agent") }
                agents.forEach { agent ->
                    Option(agent.agentType, attrs = {
                        if (!agent.connected) attr("disabled", "")
                        if (agent.agentType == agentType) attr("selected", "")
                    }) { Text("${agent.displayName}${if (agent.connected) "" else "（离线）"}") }
                }
            }
            WorkspaceFormLabel("工作目录")
            Div({ style { display(DisplayStyle.Flex); property("gap", "8px") } }) {
                Input(InputType.Text) {
                    value(workingDir)
                    onInput { workingDir = it.value }
                    attr("placeholder", "工作目录路径")
                    style { workspaceFormInputStyle(flex = true) }
                }
                Button({
                    attr("title", "选择工作目录")
                    style {
                        width(40.px); height(40.px); padding(0.px)
                        borderRadius(6.px); border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        backgroundColor(Color.white); color(Color(SilkColors.primary)); property("cursor", "pointer")
                    }
                    onClick { showFolderPicker = true }
                }) { Text("📂") }
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.SpaceBetween)
                    marginTop(16.px)
                }
            }) {
                Div {
                    Div({ style { fontSize(13.px); fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) { Text("团队可见") }
                    Div({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)); marginTop(2.px) } }) {
                        Text(if (visibility == "SHARED") "SHARED" else "PRIVATE")
                    }
                }
                Input(InputType.Checkbox) {
                    checked(visibility == "SHARED")
                    onInput { visibility = if (visibility == "SHARED") "PRIVATE" else "SHARED" }
                    style { property("transform", "scale(1.2)") }
                }
            }
            errorMessage?.let { message ->
                Div({ style { color(Color(SilkColors.error)); fontSize(12.px); marginTop(12.px) } }) { Text(message) }
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.FlexEnd)
                    property("gap", "8px")
                    marginTop(20.px)
                }
            }) {
                WorkspaceSecondaryButton("取消", disabled = saving, onClick = onDismiss)
                WorkspacePrimaryButton(
                    label = if (saving) "创建中…" else "开始开发",
                    disabled = saving || workingDir.isBlank() || agentType.isBlank(),
                    onClick = { scope.launch { validateAndCreate() } },
                )
            }
        }
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            userId = userId,
            zIndex = 2400,
            initialPath = workingDir.ifBlank { null },
            onDismiss = { showFolderPicker = false },
            onConfirm = {
                workingDir = it
                showFolderPicker = false
            },
        )
    }
    if (showTrustConfirm) {
        TrustConfirmDialog(
            path = workingDir,
            bridgeId = trustBridgeId,
            onDismiss = { showTrustConfirm = false },
            onTrust = {
                scope.launch {
                    saving = true
                    val added = ApiClient.addTrustedDir(userId, workingDir.trim())
                    saving = false
                    if (!added) {
                        errorMessage = "添加信任记录失败"
                        showTrustConfirm = false
                    } else {
                        showTrustConfirm = false
                        create()
                    }
                }
            },
        )
    }
}
