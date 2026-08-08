@file:Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")

package com.silk.backend.routes

import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MemberRole
import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.git.GitBindingDto
import com.silk.backend.git.GitBindingRequest
import com.silk.backend.git.GitBindingStatus
import com.silk.backend.git.GitEncryption
import com.silk.backend.git.GitErrorResponse
import com.silk.backend.git.GitEventParser
import com.silk.backend.git.GitEventRecord
import com.silk.backend.git.GitEventStore
import com.silk.backend.git.GitHubApiException
import com.silk.backend.git.GitHubClient
import com.silk.backend.git.GitHubRepositoryRef
import com.silk.backend.git.GitIngestionMode
import com.silk.backend.git.GitIngestionModeResolver
import com.silk.backend.git.GitIngestionSelection
import com.silk.backend.git.GitPollingService
import com.silk.backend.git.RoomGitBinding
import com.silk.backend.git.toDto
import com.silk.backend.resolveAuthenticatedUserId
import com.silk.backend.rooms.isWorkflowRoom
import com.silk.backend.trust.TrustedDirManager
import com.silk.backend.workflow.WorkflowManager
import com.silk.backend.workspace.WorkspaceManager
import com.silk.backend.workspace.WorkspaceVisibility
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

private val gitRouteLogger = LoggerFactory.getLogger("GitRoutes")
private const val DEFAULT_CALLBACK_PATH = "/api/git/webhook/"

@Serializable
data class IssueToWorkspaceRequest(
    val issueNumber: Int,
    val name: String? = null,
    val workingDir: String,
    val agentType: String = "claude-code",
    val visibility: WorkspaceVisibility = WorkspaceVisibility.PRIVATE,
)

@Serializable
data class IssueToWorkspaceResponse(
    val workspace: WorkspaceDto,
    val issueSummary: String,
)

/** Binding management and the unauthenticated, HMAC-protected GitHub webhook endpoint. */
fun Route.gitRoutes(
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
    store: GitEventStore = GitEventStore(),
    githubClient: GitHubClient = GitHubClient(),
    onEvent: suspend (GitEventRecord) -> Unit = {},
    encryptionKeyProvider: () -> ByteArray = { GitEncryption.configuredKey() },
    trustedDirManager: TrustedDirManager = TrustedDirManager(),
    pollingService: GitPollingService? = null,
    ingestionSelectionProvider: () -> GitIngestionSelection = { GitIngestionModeResolver.resolve() },
) {
    val effectivePollingService = pollingService ?: GitPollingService(store, githubClient, onEvent, encryptionKeyProvider)
    routeBindingRoutes(
        workflowManager,
        workspaceManager,
        trustedDirManager,
        store,
        githubClient,
        encryptionKeyProvider,
        effectivePollingService,
        ingestionSelectionProvider,
    )
    post("/api/git/webhook/{roomId}") {
        val roomId = call.parameters["roomId"].orEmpty()
        val contentLength = call.request.headers["Content-Length"]?.toLongOrNull()
        if (contentLength != null && contentLength > GitEventParser.MAX_BODY_BYTES) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge)
        }
        val body = runCatching { call.receive<ByteArray>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        if (body.size > GitEventParser.MAX_BODY_BYTES) return@post call.respond(HttpStatusCode.PayloadTooLarge)
        val binding = store.getBinding(roomId)
            ?.takeIf { it.status == GitBindingStatus.ACTIVE && it.ingestionMode == GitIngestionMode.WEBHOOK }
            ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val key = runCatching { encryptionKeyProvider() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val secretEncrypted = binding.webhookSecretEncrypted
            ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val secret = runCatching { GitEncryption.decrypt(secretEncrypted, key) }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized)
        if (!com.silk.backend.git.GitHubWebhookVerifier.verify(body, call.request.headers["X-Hub-Signature-256"], secret)) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }
        val hookHeader = call.request.headers["X-GitHub-Hook-ID"]?.trim()?.takeIf { it.isNotBlank() }
        if (hookHeader != null && hookHeader != binding.hookId?.toString()) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }
        val deliveryId = call.request.headers["X-GitHub-Delivery"]?.trim().orEmpty()
        val eventName = call.request.headers["X-GitHub-Event"]?.trim().orEmpty()
        if (deliveryId.isBlank() || eventName.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val event = GitEventParser.parse(eventName, null, body, roomId, deliveryId)
        if (!store.recordDelivery(roomId, deliveryId, event)) {
            return@post call.respond(HttpStatusCode.Accepted)
        }
        store.updateLastDelivery(roomId, System.currentTimeMillis())
        if (event != null) {
            CoroutineScope(Dispatchers.Default).launch {
                runCatching { onEvent(event) }
                    .onFailure { gitRouteLogger.warn("GitHub event processing failed: {}", it.message) }
            }
        }
        call.respond(HttpStatusCode.Accepted)
    }
}

private fun Route.routeBindingRoutes(
    workflowManager: WorkflowManager,
    workspaceManager: WorkspaceManager,
    trustedDirManager: TrustedDirManager,
    store: GitEventStore,
    githubClient: GitHubClient,
    encryptionKeyProvider: () -> ByteArray,
    pollingService: GitPollingService,
    ingestionSelectionProvider: () -> GitIngestionSelection,
) {
    get("/api/rooms/{roomId}/git/binding") {
        val caller = call.resolveAuthenticatedUserId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val roomId = call.parameters["roomId"].orEmpty()
        if (!isWorkflowMember(roomId, caller, workflowManager, workspaceManager)) {
            return@get call.respond(HttpStatusCode.NotFound)
        }
        val binding = store.getBinding(roomId)
        if (binding == null) return@get call.respond(GitBindingDto(enabled = false))
        if (binding.status == GitBindingStatus.ACTIVE && !bindingCredentialsDecryptable(binding, encryptionKeyProvider)) {
            val errored = store.updateBindingStatus(roomId, GitBindingStatus.ERROR) ?: binding.copy(status = GitBindingStatus.ERROR)
            return@get call.respond(errored.toDto(store.getPollingState(roomId)))
        }
        call.respond(binding.toDto(store.getPollingState(roomId)))
    }

    post("/api/rooms/{roomId}/git/binding") {
        val caller = call.resolveAuthenticatedUserId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val roomId = call.parameters["roomId"].orEmpty()
        if (!isWorkflowMember(roomId, caller, workflowManager, workspaceManager)) {
            return@post call.respond(HttpStatusCode.NotFound)
        }
        val group = GroupRepository.findGroupById(roomId)
            ?: return@post call.respond(HttpStatusCode.NotFound)
        if (group.hostId != caller && GroupRepository.getMemberRole(roomId, caller) != MemberRole.HOST) {
            return@post call.respond(HttpStatusCode.Forbidden, GitErrorResponse("NOT_ROOM_OWNER", "只有 Room Owner 可以管理 GitHub 集成"))
        }
        val request = runCatching { call.receive<GitBindingRequest>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, GitErrorResponse("INVALID_REQUEST", "请求格式错误"))
        }
        if (request.provider.name != "GITHUB") {
            return@post call.respond(HttpStatusCode.BadRequest, GitErrorResponse("INVALID_REQUEST", "provider 无效"))
        }
        val ref = GitHubRepositoryRef.parse(request.repositoryUrl)
            ?: return@post call.respond(HttpStatusCode.BadRequest, GitErrorResponse("INVALID_REPOSITORY_URL", "只支持 github.com/{owner}/{repo}"))
        val encryptionKey = runCatching { encryptionKeyProvider() }.getOrElse {
            return@post call.respond(HttpStatusCode.PreconditionFailed, GitErrorResponse("ENCRYPTION_NOT_CONFIGURED", it.message.orEmpty()))
        }
        val oldBinding = store.getBinding(roomId)
        val githubToken = request.token.trim().takeIf { it.isNotEmpty() }
            ?: oldBinding?.let { runCatching { GitEncryption.decrypt(it.tokenEncrypted, encryptionKey) }.getOrNull() }
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                GitErrorResponse("TOKEN_REQUIRED", "首次绑定必须提供 GitHub PAT"),
            )
        val selection = ingestionSelectionProvider()
        if (selection.mode == GitIngestionMode.WEBHOOK && selection.callbackBase == null) {
            return@post call.respond(
                HttpStatusCode.PreconditionFailed,
                GitErrorResponse("WEBHOOK_URL_REQUIRED", selection.warning ?: "未配置公开 HTTPS Webhook 地址"),
            )
        }
        selection.warning?.let { gitRouteLogger.warn("GitHub binding mode fallback for room {}: {}", roomId, it) }
        val oldRef = oldBinding?.let { GitHubRepositoryRef(it.owner, it.repo) }
        var createdHookId: Long? = null
        try {
            githubClient.getRepository(ref, githubToken)
            val now = System.currentTimeMillis()
            val encryptedToken = GitEncryption.encrypt(githubToken, encryptionKey)
            val binding = if (selection.mode == GitIngestionMode.WEBHOOK) {
                val callbackUrl = selection.callbackBase!!.trimEnd('/') + DEFAULT_CALLBACK_PATH + roomId
                val secret = ByteArray(com.silk.backend.git.GitConfig.webhookSecretBytes).also(SecureRandom()::nextBytes)
                val secretText = Base64.getEncoder().encodeToString(secret)
                val hook = githubClient.reconcileHook(ref, githubToken, callbackUrl, secretText)
                if (hook.created) createdHookId = hook.hook.id
                RoomGitBinding(
                    roomId = roomId,
                    owner = ref.owner,
                    repo = ref.repo,
                    hookId = hook.hook.id,
                    webhookUrl = callbackUrl,
                    tokenEncrypted = encryptedToken,
                    webhookSecretEncrypted = GitEncryption.encrypt(secretText, encryptionKey),
                    createdBy = oldBinding?.createdBy ?: caller,
                    createdAt = oldBinding?.createdAt ?: now,
                    updatedAt = now,
                    ingestionMode = GitIngestionMode.WEBHOOK,
                ).also {
                    store.putWebhookBinding(it)
                }
            } else {
                val baseline = pollingService.prepareBaseline(ref, githubToken)
                RoomGitBinding(
                    roomId = roomId,
                    owner = ref.owner,
                    repo = ref.repo,
                    tokenEncrypted = encryptedToken,
                    createdBy = oldBinding?.createdBy ?: caller,
                    createdAt = oldBinding?.createdAt ?: now,
                    updatedAt = now,
                    ingestionMode = GitIngestionMode.POLLING,
                ).also { store.putPollingBinding(it, baseline.state, baseline.snapshots) }
            }
            val shouldDeleteOldHook = oldBinding?.hookId != null &&
                (binding.ingestionMode != GitIngestionMode.WEBHOOK ||
                    oldBinding.hookId != binding.hookId || oldRef?.fullName != ref.fullName)
            if (shouldDeleteOldHook) {
                oldBinding?.let { previous ->
                    runCatching {
                        val oldToken = GitEncryption.decrypt(previous.tokenEncrypted, encryptionKey)
                        oldRef?.let { previous.hookId?.let { id -> githubClient.deleteHook(it, id, oldToken) } }
                    }.onFailure { gitRouteLogger.warn("Unable to remove old GitHub webhook for room {}", roomId) }
                }
            }
            call.respond(binding.toDto(store.getPollingState(roomId)))
        } catch (e: GitHubApiException) {
            createdHookId?.let { hookId -> runCatching { githubClient.deleteHook(ref, hookId, githubToken) } }
            call.respond(e.status.toClientStatus(), GitErrorResponse(e.status.toErrorCode(), "GitHub 请求失败"))
        } catch (e: GitEncryption.ConfigurationException) {
            createdHookId?.let { hookId -> runCatching { githubClient.deleteHook(ref, hookId, githubToken) } }
            call.respond(HttpStatusCode.PreconditionFailed, GitErrorResponse("ENCRYPTION_NOT_CONFIGURED", e.message.orEmpty()))
        } catch (e: Exception) {
            createdHookId?.let { hookId -> runCatching { githubClient.deleteHook(ref, hookId, githubToken) } }
            gitRouteLogger.warn("GitHub binding failed for room {}: {}", roomId, e.message)
            val polling = selection.mode == GitIngestionMode.POLLING
            call.respond(
                HttpStatusCode.BadGateway,
                GitErrorResponse(
                    if (polling) "POLLING_INITIALIZATION_FAILED" else "WEBHOOK_REGISTRATION_FAILED",
                    if (polling) "GitHub 轮询初始化失败" else "GitHub Webhook 注册失败",
                ),
            )
        }
    }

    delete("/api/rooms/{roomId}/git/binding") {
        val caller = call.resolveAuthenticatedUserId()
            ?: return@delete call.respond(HttpStatusCode.Unauthorized)
        val roomId = call.parameters["roomId"].orEmpty()
        if (!isWorkflowMember(roomId, caller, workflowManager, workspaceManager)) {
            return@delete call.respond(HttpStatusCode.NotFound)
        }
        val group = GroupRepository.findGroupById(roomId)
            ?: return@delete call.respond(HttpStatusCode.NotFound)
        if (group.hostId != caller && GroupRepository.getMemberRole(roomId, caller) != MemberRole.HOST) {
            return@delete call.respond(HttpStatusCode.Forbidden, GitErrorResponse("NOT_ROOM_OWNER", "只有 Room Owner 可以解绑 GitHub"))
        }
        val binding = store.getBinding(roomId)
            ?: return@delete call.respond(HttpStatusCode.NotFound, GitErrorResponse("BINDING_NOT_FOUND", "GitHub 集成未开启"))
        val key = runCatching { encryptionKeyProvider() }.getOrNull()
        val remoteError = runCatching {
            if (key != null && binding.hookId != null) {
                val token = GitEncryption.decrypt(binding.tokenEncrypted, key)
                githubClient.deleteHook(GitHubRepositoryRef(binding.owner, binding.repo), binding.hookId, token)
            }
        }.exceptionOrNull()
        store.removeBinding(roomId)
        if (remoteError != null) gitRouteLogger.warn("GitHub webhook cleanup failed for room {}: {}", roomId, remoteError.message)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/api/rooms/{roomId}/git/issue-to-workspace") {
        val caller = call.resolveAuthenticatedUserId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val roomId = call.parameters["roomId"].orEmpty()
        if (!isWorkflowMember(roomId, caller, workflowManager, workspaceManager)) {
            return@post call.respond(HttpStatusCode.NotFound)
        }
        val binding = store.getBinding(roomId)
            ?.takeIf { it.status == GitBindingStatus.ACTIVE }
            ?: return@post call.respond(HttpStatusCode.Conflict, GitErrorResponse("GITHUB_BINDING_REQUIRED", "请先开启 GitHub 集成"))
        val request = runCatching { call.receive<IssueToWorkspaceRequest>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, GitErrorResponse("INVALID_REQUEST", "请求格式错误"))
        }
        if (request.issueNumber <= 0 || request.workingDir.trim().isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, GitErrorResponse("INVALID_REQUEST", "Issue 编号和工作目录必填"))
        }
        val agentType = request.agentType.trim().replace('_', '-').ifBlank { "claude-code" }
        if (AgentRuntime.listRegisteredAgents().none { it.agentType == agentType }) {
            return@post call.respond(HttpStatusCode.BadRequest, GitErrorResponse("UNSUPPORTED_AGENT", "不支持的 Agent: $agentType"))
        }
        if (!AcpRegistry.isConnected(caller, agentType)) {
            return@post call.respond(HttpStatusCode.Conflict, GitErrorResponse("BRIDGE_OFFLINE", "所选 Agent Bridge 未连接"))
        }
        val bridgeId = AcpRegistry.getRemoteIp(caller, agentType)?.let { "ip:$it" }
        val workingDir = request.workingDir.trim()
        if (bridgeId == null || !trustedDirManager.isTrusted(caller, bridgeId, workingDir)) {
            return@post call.respond(HttpStatusCode.BadRequest, GitErrorResponse("DIRECTORY_NOT_TRUSTED", "目录未被信任"))
        }
        val key = runCatching { encryptionKeyProvider() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.PreconditionFailed, GitErrorResponse("ENCRYPTION_NOT_CONFIGURED", "GitHub 集成密钥不可用"))
        val token = runCatching { GitEncryption.decrypt(binding.tokenEncrypted, key) }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.PreconditionFailed, GitErrorResponse("BINDING_UNAVAILABLE", "GitHub 集成凭据不可用"))
        val ref = GitHubRepositoryRef(binding.owner, binding.repo)
        val issue = try {
            githubClient.getIssue(ref, request.issueNumber, token)
        } catch (e: GitHubApiException) {
            return@post call.respond(e.status.toClientStatus(), GitErrorResponse(e.status.toErrorCode(), "GitHub Issue 获取失败"))
        } catch (e: Exception) {
            gitRouteLogger.warn("GitHub issue fetch failed for room {}: {}", roomId, e.message)
            return@post call.respond(HttpStatusCode.BadGateway, GitErrorResponse("GITHUB_REQUEST_FAILED", "GitHub Issue 获取失败"))
        }
        if (issue.number != request.issueNumber) {
            return@post call.respond(HttpStatusCode.BadGateway, GitErrorResponse("GITHUB_REQUEST_FAILED", "GitHub 返回的 Issue 不匹配"))
        }
        val issueUrl = "https://github.com/${binding.owner}/${binding.repo}/issues/${issue.number}"
        val linked = issueUrl
        val workspaceName = request.name?.trim()?.take(120)?.takeIf { it.isNotBlank() }
            ?: "#${issue.number}: ${issue.title.trim()}".take(120)
        val workspace = workspaceManager.createWorkspace(
            roomId = roomId,
            ownerId = caller,
            name = workspaceName,
            agentType = agentType,
            visibility = request.visibility,
            linkedGithubRef = linked,
        )
        AgentRuntime.autoActivateForWorkspace(caller, workspace.workspaceId, agentType)
        when (val result = AgentRuntime.cdSync(caller, workspace.workspaceId, workingDir, agentType)) {
            is AgentRuntime.CdResult.Err -> {
                AgentRuntime.cleanupState(caller, workspace.workspaceId)
                workspaceManager.deleteWorkspace(workspace.workspaceId)
                return@post call.respond(HttpStatusCode.Conflict, GitErrorResponse("WORKING_DIR_REJECTED", result.reason))
            }
            is AgentRuntime.CdResult.Ok -> workspaceManager.updateWorkingDir(workspace.workspaceId, result.resolvedPath)
        }
        val created = workspaceManager.getWorkspace(workspace.workspaceId)
            ?: return@post call.respond(HttpStatusCode.InternalServerError, GitErrorResponse("WORKSPACE_CREATE_FAILED", "工作区创建失败"))
        call.respond(
            HttpStatusCode.Created,
            IssueToWorkspaceResponse(
                workspace = created.toDto(caller),
                issueSummary = buildIssueSummary(issue, binding, issueUrl),
            ),
        )
    }
}

private fun buildIssueSummary(
    issue: com.silk.backend.git.GitHubIssueResponse,
    binding: RoomGitBinding,
    issueUrl: String,
): String {
    return buildString {
        appendLine("GitHub Issue")
        appendLine("仓库：${binding.owner}/${binding.repo}")
        appendLine("Issue #${issue.number}：${issue.title.trim()}")
        appendLine("链接：$issueUrl")
    }.trim()
}

private fun isWorkflowMember(roomId: String, caller: String, workflowManager: WorkflowManager, workspaceManager: WorkspaceManager): Boolean =
    roomId.isNotBlank() && isWorkflowRoom(roomId, workflowManager, workspaceManager) && GroupRepository.isUserInGroup(roomId, caller)

private fun bindingCredentialsDecryptable(binding: RoomGitBinding, keyProvider: () -> ByteArray): Boolean = runCatching {
    val key = keyProvider()
    GitEncryption.decrypt(binding.tokenEncrypted, key)
    if (binding.ingestionMode == GitIngestionMode.WEBHOOK) {
        GitEncryption.decrypt(binding.webhookSecretEncrypted ?: error("Missing webhook secret"), key)
    }
}.isSuccess

private fun HttpStatusCode.toClientStatus(): HttpStatusCode = when (value) {
    401 -> HttpStatusCode.Unauthorized
    403 -> HttpStatusCode.Forbidden
    404 -> HttpStatusCode.NotFound
    408, 429 -> HttpStatusCode.GatewayTimeout
    else -> HttpStatusCode.BadGateway
}

private fun HttpStatusCode.toErrorCode(): String = when (value) {
    401 -> "GITHUB_UNAUTHORIZED"
    403 -> "GITHUB_FORBIDDEN"
    404 -> "GITHUB_NOT_FOUND"
    429 -> "GITHUB_RATE_LIMITED"
    else -> "GITHUB_REQUEST_FAILED"
}
