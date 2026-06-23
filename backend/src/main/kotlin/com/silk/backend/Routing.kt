package com.silk.backend

import com.silk.backend.ai.AIConfig
import com.silk.backend.auth.AuthService
import com.silk.backend.auth.GroupService
import com.silk.backend.database.AddMemberRequest
import com.silk.backend.database.AuthResponse
import com.silk.backend.database.CcSettingsResponse
import com.silk.backend.database.ContactRepository
import com.silk.backend.database.ContactResponse
import com.silk.backend.database.CreateGroupRequest
import com.silk.backend.database.DeleteGroupRequest
import com.silk.backend.database.DeleteUserTodoRequest
import com.silk.backend.database.Group
import com.silk.backend.database.GroupMemberApi
import com.silk.backend.database.GroupMembers
import com.silk.backend.database.GroupMembersResponse
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.GroupResponse
import com.silk.backend.database.Groups
import com.silk.backend.database.HandleContactRequestData
import com.silk.backend.database.JoinGroupRequest
import com.silk.backend.database.LeaveGroupRequest
import com.silk.backend.database.LeaveGroupResponse
import com.silk.backend.database.LoginRequest
import com.silk.backend.database.MarkReadRequest
import com.silk.backend.database.MemberRole
import com.silk.backend.database.PrivateChatResponse
import com.silk.backend.database.RecallMessageRequest
import com.silk.backend.database.RefreshUserTodosRequest
import com.silk.backend.database.RegisterRequest
import com.silk.backend.database.SendContactRequestByIdData
import com.silk.backend.database.SendContactRequestData
import com.silk.backend.database.SendMessageRequest
import com.silk.backend.database.SimpleResponse
import com.silk.backend.database.StartPrivateChatRequest
import com.silk.backend.database.StartSilkPrivateChatRequest
import com.silk.backend.database.UnreadCountResponse
import com.silk.backend.database.UnreadRepository
import com.silk.backend.database.UpdateUserSettingsRequest
import com.silk.backend.database.UpdateUserTodoRequest
import com.silk.backend.database.UserRepository
import com.silk.backend.database.UserSearchResult
import com.silk.backend.database.UserSettingsRepository
import com.silk.backend.database.UserSettingsResponse
import com.silk.backend.database.UserTodoExtractionDiagnosticsResponse
import com.silk.backend.database.UserTodoRefreshStatusResponse
import com.silk.backend.database.UserTodosResponse
import com.silk.backend.export.ChatObsidianExporter
import com.silk.backend.kb.KBObsidianExporter
import com.silk.backend.kb.KnowledgeBaseManager
import com.silk.backend.models.KBAccessPolicy
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBEntrySource
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBSourceType
import com.silk.backend.models.KBTopic
import com.silk.backend.models.KnowledgeSpaceType
import com.silk.backend.models.Workflow
import com.silk.backend.workflow.WorkflowManager
import com.silk.backend.routes.asrRoutes
import com.silk.backend.routes.fileRoutes
import com.silk.backend.agents.acp.AcpRegistry
import com.silk.backend.agents.core.AgentRegistry
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.trust.TrustedDirManager
import com.silk.shared.models.AddTrustRequest
import com.silk.shared.models.CcStateResponse
import com.silk.shared.models.DirEntry
import com.silk.shared.models.DirListingResponse
import com.silk.shared.models.TrustedDirListResponse
import com.silk.shared.models.TrustedDirCheckResponse
import com.silk.shared.models.TrustedDirRecordDto
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

// 群组聊天服务器映射（每个群组一个ChatServer实例）
private val groupChatServers = ConcurrentHashMap<String, ChatServer>()
private val logger = LoggerFactory.getLogger("Routing")
private val workflowManager = WorkflowManager()
private val trustedDirManager = TrustedDirManager()
private val knowledgeBaseManager: KnowledgeBaseManager
    get() = KnowledgeBaseManager()
private val kbRouteJson = Json { ignoreUnknownKeys = true }

private fun sanitizeFileName(input: String): String =
    input.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]"), "_").take(100)

private fun <T> Result<T>.rethrowRoutingCancellation(): Result<T> =
    onFailure { error ->
        if (error is CancellationException) {
            throw error
        }
    }

private sealed interface CcSettingsUpdateResult {
    data class Ok(val agentSwitchMessage: String?) : CcSettingsUpdateResult
    data class BadRequest(val error: String) : CcSettingsUpdateResult
}

private data class WorkflowCreateInput(
    val userId: String,
    val name: String,
    val description: String,
    val agentType: String,
    val taskFocus: String,
    val permissionMode: String,
    val initialDir: String,
)

/**
 * 解析当前 user 对应的 bridgeId（用于 TrustedDirManager 的 scope key）。
 * 格式 "ip:<remoteIp>" 兼容已有 trust 记录。优先用 claude-code 的 IP（保留旧 trust 记录的兼容性），
 * 没有时退到任意一个已连接 agent 的 IP。无任何连接返回 null。
 */
private fun resolveBridgeId(userId: String): String? {
    val ip = getAnyBridgeIp(userId) ?: return null
    return "ip:$ip"
}

/**
 * 检测某 user 是否有任意一个 ACP 桥连接（claude-code / codex / 其他）。
 */
private fun isAnyBridgeConnected(userId: String): Boolean =
    AcpRegistry.listConnected(userId).isNotEmpty()

/**
 * 取该 user 任意一个已连接 agent 的远端 IP（用于状态展示与 trust scope）。
 * 优先 claude-code（兼容旧 TrustedDir 记录），否则回退到第一个已连接 agent。
 */
private fun getAnyBridgeIp(userId: String): String? {
    val connected = AcpRegistry.listConnected(userId)
    if (connected.isEmpty()) return null
    val preferred = if (connected.contains("claude-code")) "claude-code" else connected.first()
    return AcpRegistry.getRemoteIp(userId, preferred)
}

/**
 * 解析 cdSync / listDirectory 等"无显式 agentType"路径应该调用哪个 bridge。
 * 优先 claude-code（保持旧行为），否则用第一个已连接的 agent。无连接返回 null。
 */
private fun resolveActiveAgentType(userId: String): String? {
    val connected = AcpRegistry.listConnected(userId)
    if (connected.isEmpty()) return null
    return if (connected.contains("claude-code")) "claude-code" else connected.first()
}

private fun parseBridgeDirListing(raw: JsonObject): DirListingResponse =
    DirListingResponse(
        success = raw["success"]?.jsonPrimitive?.booleanOrNull ?: false,
        path = raw["path"]?.jsonPrimitive?.contentOrNull ?: "",
        parent = raw["parent"]?.jsonPrimitive?.contentOrNull,
        segments = raw["segments"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
        separator = raw["separator"]?.jsonPrimitive?.contentOrNull ?: "/",
        entries = raw["entries"]?.jsonArray?.mapNotNull(::parseBridgeDirEntry) ?: emptyList(),
        truncated = raw["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
        error = raw["error"]?.jsonPrimitive?.contentOrNull,
    )

private fun parseBridgeDirEntry(element: kotlinx.serialization.json.JsonElement): DirEntry? {
    val obj = element.jsonObject
    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
    return DirEntry(
        name = name,
        isDir = obj["isDir"]?.jsonPrimitive?.booleanOrNull ?: true,
    )
}

private suspend fun respondToSuccessfulCd(
    call: ApplicationCall,
    userId: String,
    ccGroupId: String,
    result: AgentRuntime.CdResult.Ok,
) {
    val snap = AgentRuntime.snapshotState(userId, ccGroupId)
    val bridgeConnected = isAnyBridgeConnected(userId)
    val rawGid = ccGroupId.removePrefix("group_")
    val descriptor = snap?.agentType?.let { AgentRegistry.getByType(it) }
    runCatching {
        getGroupChatServer(rawGid).broadcast(
            com.silk.backend.agents.core.AgentMessages.system(
                "工作目录已切换至：${result.resolvedPath} 会话已重置",
                agentUserId = descriptor?.agentUserId ?: SilkAgent.AGENT_ID,
                agentName = descriptor?.displayName ?: SilkAgent.AGENT_NAME,
            )
        )
    }.rethrowRoutingCancellation().onFailure { e ->
        logger.warn("广播切目录提示失败: {}", e.message)
    }
    call.respond(
        CcStateResponse(
            success = true,
            active = snap?.active ?: true,
            running = snap?.running ?: false,
            workingDir = result.resolvedPath,
            sessionId = "",  // ACP 路径不暴露内部 sessionId
            sessionStarted = snap?.active ?: false,
            bridgeConnected = bridgeConnected,
            agentType = snap?.agentType ?: "",
            agentDisplayName = descriptor?.displayName ?: "",
            permissionMode = snap?.permissionMode ?: "",
        )
    )
}

private fun applyCcSettingsUpdate(
    userId: String,
    groupId: String,
    ccGroupId: String,
    newAgent: String?,
    newPermMode: String?,
): CcSettingsUpdateResult {
    var agentSwitchMsg: String? = null
    if (!newAgent.isNullOrBlank()) {
        val dashType = newAgent.replace('_', '-')
        val descriptor = AgentRuntime.switchAgent(userId, ccGroupId, dashType)
            ?: return CcSettingsUpdateResult.BadRequest("未知 agent: $newAgent")
        workflowManager.updateActiveAgent(groupId, dashType)
        agentSwitchMsg = "已切换到 ${descriptor.displayName}。"
    }
    if (!newPermMode.isNullOrBlank()) {
        val ok = AgentRuntime.setPermissionMode(userId, ccGroupId, newPermMode)
        if (!ok) {
            return CcSettingsUpdateResult.BadRequest(
                "无效权限模式: $newPermMode（INTERACTIVE / ACCEPT_EDITS / BYPASS）"
            )
        }
        workflowManager.updatePermissionMode(groupId, newPermMode)
    }
    return CcSettingsUpdateResult.Ok(agentSwitchMsg)
}

private suspend fun broadcastAgentSwitchMessage(userId: String, ccGroupId: String, message: String?) {
    if (message == null) return
    val rawGid = ccGroupId.removePrefix("group_")
    runCatching {
        val snap = AgentRuntime.snapshotState(userId, ccGroupId)
        val desc = snap?.agentType?.let { AgentRegistry.getByType(it) }
        getGroupChatServer(rawGid).broadcast(
            com.silk.backend.agents.core.AgentMessages.system(
                message,
                agentUserId = desc?.agentUserId ?: SilkAgent.AGENT_ID,
                agentName = desc?.displayName ?: SilkAgent.AGENT_NAME,
            )
        )
    }.rethrowRoutingCancellation().onFailure { e ->
        logger.warn("广播 agent 切换提示失败: {}", e.message)
    }
}

private suspend fun respondCcSettingsState(call: ApplicationCall, userId: String, ccGroupId: String) {
    val snap = AgentRuntime.snapshotState(userId, ccGroupId)
    val bridgeConnected = isAnyBridgeConnected(userId)
    val descriptor = snap?.agentType?.let { AgentRegistry.getByType(it) }
    call.respond(
        CcStateResponse(
            success = true,
            active = snap?.active ?: false,
            running = snap?.running ?: false,
            workingDir = snap?.workingDir ?: "",
            bridgeConnected = bridgeConnected,
            agentType = snap?.agentType ?: "",
            agentDisplayName = descriptor?.displayName ?: "",
            permissionMode = snap?.permissionMode ?: "",
        )
    )
}

private fun parseWorkflowCreateInput(req: JsonObject): WorkflowCreateInput? {
    val userId = req["userId"]?.jsonPrimitive?.content
    val name = req["name"]?.jsonPrimitive?.content
    if (userId.isNullOrBlank() || name.isNullOrBlank()) return null
    return WorkflowCreateInput(
        userId = userId,
        name = name,
        description = req["description"]?.jsonPrimitive?.content ?: "",
        agentType = req["agentType"]?.jsonPrimitive?.contentOrNull ?: "claude_code",
        taskFocus = req["taskFocus"]?.jsonPrimitive?.contentOrNull ?: "",
        permissionMode = req["permissionMode"]?.jsonPrimitive?.contentOrNull ?: "",
        initialDir = req["initialDir"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
    )
}

private suspend fun respondWorkflowCreateError(call: ApplicationCall, status: HttpStatusCode, message: String) {
    val payload = buildJsonObject {
        put("success", JsonPrimitive(false))
        put("message", JsonPrimitive(message))
    }
    call.respondText(payload.toString(), ContentType.Application.Json, status)
}

private suspend fun activateWorkflowAgentIfNeeded(
    groupChatServer: ChatServer,
    userId: String,
    groupId: String,
) {
    val workflow = workflowManager.getWorkflowByGroupId(groupId)
    if (workflow == null || workflow.agentType == "silk_chat") return
    val resolvedAgent = workflow.activeAgent.takeIf { it.isNotBlank() }
        ?: when (workflow.agentType) {
            "claude_code" -> "claude-code"
            else -> workflow.agentType
        }
    AgentRuntime.autoActivateForWorkflow(userId, "group_$groupId", resolvedAgent)
    rebroadcastPendingQuestion(groupChatServer, userId, groupId)
}

private suspend fun rebroadcastPendingQuestion(groupChatServer: ChatServer, userId: String, groupId: String) {
    val pendingSnapshot = AgentRuntime.snapshotPendingQuestion(userId, "group_$groupId") ?: return
    val questionMsg = com.silk.backend.agents.core.AgentMessages.question(
        content = com.silk.backend.agents.core.AgentMessages.formatQuestionText(pendingSnapshot.questions),
        requestId = pendingSnapshot.requestId,
        agentUserId = pendingSnapshot.agentUserId,
        agentName = pendingSnapshot.agentName,
    )
    groupChatServer.broadcast(questionMsg)
}

private suspend fun consumeIncomingChatFrames(
    incoming: kotlinx.coroutines.channels.ReceiveChannel<Frame>,
    groupChatServer: ChatServer,
) {
    incoming.consumeEach { frame ->
        if (frame is Frame.Text) {
            handleIncomingChatText(frame.readText(), groupChatServer)
        }
    }
}

private suspend fun handleIncomingChatText(receivedText: String, groupChatServer: ChatServer) {
    try {
        val message = Json.decodeFromString<Message>(receivedText)
        groupChatServer.broadcast(message)
    } catch (e: SerializationException) {
        logger.warn("⚠️ 解析消息失败: payload 不是合法消息 JSON", e)
    } catch (e: IllegalArgumentException) {
        logger.warn("⚠️ 解析消息失败: payload 缺少必要字段", e)
    }
}

/**
 * 获取或创建指定群组的ChatServer
 */
private fun getGroupChatServer(groupId: String): ChatServer {
    return groupChatServers.getOrPut(groupId) {
        val sessionName = "group_$groupId"
        val wf = workflowManager.getWorkflowByGroupId(groupId)
        val isSilkChat = wf != null && wf.agentType == "silk_chat"
        ChatServer(sessionName, isSilkChat).also {
            logger.info("🆕 创建新的群组聊天服务器: {} (silkChat={})", sessionName, isSilkChat)
        }
    }
}

/**
 * 全局状态广播 - 供其他模块（如 FileRoutes）使用
 * 广播系统状态消息到指定群组
 */
suspend fun broadcastSystemStatus(groupId: String, status: String) {
    val chatServer = groupChatServers[groupId]
    if (chatServer != null) {
        chatServer.broadcastSystemStatus(status)
    } else {
        logger.warn("⚠️ [broadcastSystemStatus] 群组 {} 不存在", groupId)
    }
}

/**
 * 广播文件消息到指定群组 - 供 FileRoutes 使用
 * 当用户上传文件后，在聊天中显示文件消息
 */
suspend fun broadcastFileMessage(
    groupId: String,
    userId: String,
    userName: String,
    fileName: String,
    fileSize: Long,
    downloadUrl: String
) {
    val chatServer = groupChatServers[groupId]
    if (chatServer != null) {
        val message = Message(
            id = System.currentTimeMillis().toString() + (0..999).random(),
            userId = userId,
            userName = userName,
            content = buildFileMessageContent(
                fileName = fileName,
                fileSize = fileSize,
                downloadUrl = downloadUrl
            ),
            timestamp = System.currentTimeMillis(),
            type = MessageType.FILE
        )
        chatServer.broadcast(message)
        logger.debug("📎 [broadcastFileMessage] 文件消息已广播到群组 {}: {}", groupId, fileName)
    } else {
        logger.warn("⚠️ [broadcastFileMessage] 群组 {} 不存在", groupId)
    }
}

fun Application.configureRouting() {
    // AgentRuntime 持久化 wiring：cdSync 成功 / prompt 完成时把 workingDir + cliSessionId 写回
    // workflow_store.json，让重启后能 seed 恢复对话。复用 Workflow.sessionId 字段存 cliSessionId。
    AgentRuntime.setWorkflowPersistence(object : AgentRuntime.WorkflowPersistence {
        override fun persistWorkingDir(rawGroupId: String, workingDir: String): Boolean =
            workflowManager.updateWorkingDir(rawGroupId, workingDir)

        override fun persistCliSession(rawGroupId: String, cliSessionId: String, sessionStarted: Boolean): Boolean =
            workflowManager.updateSessionState(rawGroupId, cliSessionId, sessionStarted)

        override fun persistCliSession(rawGroupId: String, agentType: String, cliSessionId: String, sessionStarted: Boolean): Boolean =
            workflowManager.updateSessionState(rawGroupId, agentType, cliSessionId, sessionStarted)

        override fun persistActiveAgent(rawGroupId: String, agentType: String): Boolean =
            workflowManager.updateActiveAgent(rawGroupId, agentType)

        override fun persistPermissionMode(rawGroupId: String, permissionMode: String): Boolean =
            workflowManager.updatePermissionMode(rawGroupId, permissionMode)

        override fun loadSeed(rawGroupId: String): AgentRuntime.WorkflowSeed? {
            val wf = workflowManager.getWorkflowByGroupId(rawGroupId) ?: return null
            if (wf.workingDir.isBlank() && wf.sessionId.isBlank()) return null
            return AgentRuntime.WorkflowSeed(
                workingDir = wf.workingDir,
                cliSessionId = wf.sessionId.takeIf { it.isNotBlank() },
                sessionStarted = wf.sessionStarted,
                permissionMode = wf.permissionMode,
            )
        }

        override fun loadSeed(rawGroupId: String, agentType: String): AgentRuntime.WorkflowSeed? {
            val wf = workflowManager.getWorkflowByGroupId(rawGroupId) ?: return null
            // 优先取 per-agent state；缺失时仅当 agentType 等于 workflow 默认 agent 才回落到旧字段，
            // 避免别的 agent 拿到不属于它的 cliSessionId 触发 resume 失败。
            val perAgent = wf.agentSessions[agentType]
            val defaultDash = when (wf.agentType) {
                "claude_code" -> "claude-code"
                else -> wf.agentType
            }
            val cliSid = perAgent?.sessionId?.takeIf { it.isNotBlank() }
                ?: wf.sessionId.takeIf { it.isNotBlank() && agentType == defaultDash }
            val sessionStarted = perAgent?.sessionStarted
                ?: (wf.sessionStarted && agentType == defaultDash)
            if (wf.workingDir.isBlank() && cliSid.isNullOrBlank()) return null
            return AgentRuntime.WorkflowSeed(
                workingDir = wf.workingDir,
                cliSessionId = cliSid,
                sessionStarted = sessionStarted,
                permissionMode = wf.permissionMode,
            )
        }
    })

    routing {
        registerRootGetRoute()
        registerApiInfoGetRoute()
        registerHealthGetRoute()
        registerUsersGetRoute()
        registerUsersUserIdSettingsGetRoute()
        registerUsersUserIdSettingsPutRoute()
        registerUsersUserIdCcSettingsGetRoute()
        registerUsersUserIdCcSettingsGenerateTokenPostRoute()
        registerUsersUserIdCcSettingsBridgeStatusGetRoute()
        registerUsersUserIdCcStateGroupIdGetRoute()
        registerUsersUserIdCcFsListGetRoute()
        registerUsersUserIdCcFsCdPostRoute()
        registerUsersUserIdCcSettingsUpdatePostRoute()
        registerUsersUserIdTrustedDirsCheckGetRoute()
        registerUsersUserIdTrustedDirsPostRoute()
        registerUsersUserIdTrustedDirsDeleteRoute()
        registerUsersUserIdTrustedDirsGetRoute()
        registerDownloadReportSessionNameFileNameGetRoute()
        registerAuthRegisterPostRoute()
        registerAuthLoginPostRoute()
        registerAuthValidateUserIdGetRoute()
        registerGroupsCreatePostRoute()
        registerGroupsJoinPostRoute()
        registerGroupsUserUserIdGetRoute()
        registerGroupsGroupIdGetRoute()
        registerGroupsGroupIdMembersGetRoute()
        registerContactsUserIdGetRoute()
        registerUsersSearchGetRoute()
        registerContactsRequestPostRoute()
        registerContactsRequestByIdPostRoute()
        registerContactsHandleRequestPostRoute()
        registerContactsPrivateChatPostRoute()
        registerGroupsGroupIdAddMemberPostRoute()
        registerGroupsGroupIdLeavePostRoute()
        registerGroupsGroupIdDeleteRoute()
        registerApiGroupsGroupIdExportMarkdownGetRoute()
        registerGroupsGroupIdExportMarkdownGetRoute()
        registerDownloadObsidianGroupIdGetRoute()
        registerApiUnreadUserIdGetRoute()
        registerApiUnreadMarkReadPostRoute()
        registerApiCalendarWorkdayDateGetRoute()
        registerApiUserTodosUserIdGetRoute()
        registerApiUserTodosUserIdRefreshGetRoute()
        registerApiUserTodosUserIdRefreshAsyncStartPostRoute()
        registerApiUserTodosUserIdRefreshAsyncStatusGetRoute()
        registerApiUserTodosUserIdDiagnosticsGetRoute()
        registerApiUserTodosItemPutRoute()
        registerApiUserTodosItemDeleteRoute()
        registerApiUserTodosRefreshPostRoute()
        registerFileRoutes()
        registerAsrRoutes()
        registerApiSilkPrivateChatPostRoute()
        registerApiMessagesExportMarkdownGetRoute()
        registerApiMessagesSendPostRoute()
        registerApiMessagesRecallPostRoute()
        registerApiMessagesDeletePostRoute()
        registerAgentBridgeWebSocketRoute()
        registerApiAgentsGetRoute()
        registerApiWorkflowsGetRoute()
        registerApiWorkflowsPostRoute()
        registerApiWorkflowsWorkflowIdDeleteRoute()
        registerApiWorkflowsWorkflowIdPutRoute()
        registerApiWorkflowsByGroupGroupIdGetRoute()
        registerApiKbTopicsGetRoute()
        registerApiKbTopicsPostRoute()
        registerApiKbTopicsTopicIdPutRoute()
        registerApiKbTopicsTopicIdDeleteRoute()
        registerApiKbEntriesGetRoute()
        registerApiKbEntriesEntryIdGetRoute()
        registerApiKbEntriesPostRoute()
        registerApiKbCapturesPostRoute()
        registerApiKbEntriesEntryIdPutRoute()
        registerApiKbEntriesEntryIdDeleteRoute()
        registerApiKbEntriesEntryIdExportGetRoute()
        registerChatWebSocketRoute()
        registerWsAudioDuplexWebSocketRoute()
    }
}
private fun Route.registerRootGetRoute() {

    get("/") {
        val html = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Silk Chat Server</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        color: #333;
                        min-height: 100vh;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        padding: 20px;
                    }
                    .container {
                        background: white;
                        border-radius: 20px;
                        box-shadow: 0 20px 60px rgba(0,0,0,0.3);
                        padding: 40px;
                        max-width: 800px;
                        width: 100%;
                    }
                    h1 {
                        color: #667eea;
                        font-size: 2.5em;
                        margin-bottom: 10px;
                        display: flex;
                        align-items: center;
                        gap: 15px;
                    }
                    .status {
                        display: inline-block;
                        background: #10b981;
                        color: white;
                        padding: 5px 15px;
                        border-radius: 20px;
                        font-size: 0.4em;
                        font-weight: bold;
                    }
                    .subtitle {
                        color: #666;
                        font-size: 1.1em;
                        margin-bottom: 30px;
                    }
                    .endpoints {
                        background: #f7fafc;
                        border-radius: 10px;
                        padding: 20px;
                        margin: 20px 0;
                    }
                    .endpoints h2 {
                        color: #667eea;
                        font-size: 1.3em;
                        margin-bottom: 15px;
                    }
                    .endpoint {
                        display: flex;
                        align-items: flex-start;
                        margin-bottom: 15px;
                        padding: 10px;
                        background: white;
                        border-radius: 8px;
                        border-left: 4px solid #667eea;
                    }
                    .endpoint-method {
                        background: #667eea;
                        color: white;
                        padding: 3px 8px;
                        border-radius: 4px;
                        font-size: 0.8em;
                        font-weight: bold;
                        min-width: 60px;
                        text-align: center;
                        margin-right: 10px;
                    }
                    .endpoint-method.ws {
                        background: #f59e0b;
                    }
                    .endpoint-path {
                        font-family: 'Courier New', monospace;
                        color: #333;
                        flex: 1;
                    }
                    .endpoint-desc {
                        color: #666;
                        font-size: 0.9em;
                        margin-top: 5px;
                    }
                    .info-box {
                        background: #eff6ff;
                        border: 1px solid #3b82f6;
                        border-radius: 8px;
                        padding: 15px;
                        margin-top: 20px;
                    }
                    .info-box strong {
                        color: #1e40af;
                    }
                    a {
                        color: #667eea;
                        text-decoration: none;
                        font-weight: bold;
                    }
                    a:hover {
                        text-decoration: underline;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>
                        🤍 Silk Chat Server
                        <span class="status">● RUNNING</span>
                    </h1>
                    <p class="subtitle">智能医疗问诊聊天系统 - 后端 API 服务</p>

                    <div class="endpoints">
                        <h2>🔐 认证接口</h2>
                        <div class="endpoint">
                            <span class="endpoint-method">POST</span>
                            <div>
                                <div class="endpoint-path">/auth/register</div>
                                <div class="endpoint-desc">用户注册</div>
                            </div>
                        </div>
                        <div class="endpoint">
                            <span class="endpoint-method">POST</span>
                            <div>
                                <div class="endpoint-path">/auth/login</div>
                                <div class="endpoint-desc">用户登录</div>
                            </div>
                        </div>
                        <div class="endpoint">
                            <span class="endpoint-method">GET</span>
                            <div>
                                <div class="endpoint-path">/auth/validate/{userId}</div>
                                <div class="endpoint-desc">验证用户身份</div>
                            </div>
                        </div>
                    </div>

                    <div class="endpoints">
                        <h2>👥 群组管理</h2>
                        <div class="endpoint">
                            <span class="endpoint-method">POST</span>
                            <div>
                                <div class="endpoint-path">/groups/create</div>
                                <div class="endpoint-desc">创建新群组</div>
                            </div>
                        </div>
                        <div class="endpoint">
                            <span class="endpoint-method">POST</span>
                            <div>
                                <div class="endpoint-path">/groups/join</div>
                                <div class="endpoint-desc">加入群组（使用邀请码）</div>
                            </div>
                        </div>
                        <div class="endpoint">
                            <span class="endpoint-method">GET</span>
                            <div>
                                <div class="endpoint-path">/groups/user/{userId}</div>
                                <div class="endpoint-desc">获取用户的所有群组</div>
                            </div>
                        </div>
                        <div class="endpoint">
                            <span class="endpoint-method">GET</span>
                            <div>
                                <div class="endpoint-path">/groups/{groupId}</div>
                                <div class="endpoint-desc">获取群组详情</div>
                            </div>
                        </div>
                        <div class="endpoint">
                            <span class="endpoint-method">GET</span>
                            <div>
                                <div class="endpoint-path">/groups/{groupId}/members</div>
                                <div class="endpoint-desc">获取群组成员列表</div>
                            </div>
                        </div>
                    </div>

                    <div class="endpoints">
                        <h2>💬 聊天服务</h2>
                        <div class="endpoint">
                            <span class="endpoint-method ws">WS</span>
                            <div>
                                <div class="endpoint-path">/chat?userId={userId}&userName={userName}&groupId={groupId}</div>
                                <div class="endpoint-desc">WebSocket 实时聊天连接</div>
                            </div>
                        </div>
                        <div class="endpoint">
                            <span class="endpoint-method">GET</span>
                            <div>
                                <div class="endpoint-path">/users</div>
                                <div class="endpoint-desc">获取在线用户列表</div>
                            </div>
                        </div>
                    </div>

                    <div class="endpoints">
                        <h2>📥 文件下载</h2>
                        <div class="endpoint">
                            <span class="endpoint-method">GET</span>
                            <div>
                                <div class="endpoint-path">/download/report/{sessionName}/{fileName}</div>
                                <div class="endpoint-desc">下载 PDF 诊断报告</div>
                            </div>
                        </div>
                    </div>

                    <div class="endpoints">
                        <h2>🔧 系统监控</h2>
                        <div class="endpoint">
                            <span class="endpoint-method">GET</span>
                            <div>
                                <div class="endpoint-path">/health</div>
                                <div class="endpoint-desc">健康检查</div>
                            </div>
                        </div>
                        <div class="endpoint">
                            <span class="endpoint-method">GET</span>
                            <div>
                                <div class="endpoint-path">/api/info</div>
                                <div class="endpoint-desc">API 版本信息（JSON）</div>
                            </div>
                        </div>
                    </div>

                    <div class="info-box">
                        <strong>💡 提示：</strong>
                        这是 Silk 的后端 API 服务器，不提供用户界面。
                        请使用 <strong>Desktop UI</strong> 或 <strong>Web UI</strong> 客户端连接到此服务器。
                    </div>

                    <div class="info-box" style="background: #fef3c7; border-color: #f59e0b; margin-top: 15px;">
                        <strong>🌐 Web UI 部署：</strong>
                        如需在浏览器中使用，请部署 Web UI 前端应用并配置其连接到此后端地址。
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
        call.respondText(html, ContentType.Text.Html)
    }
}

private fun Route.registerApiInfoGetRoute() {

    // API 信息端点（JSON 格式，方便程序化访问）
    get("/api/info") {
        call.respondText("""
            {
                "service": "Silk Chat Server",
                "version": "1.0.0",
                "status": "running",
                "endpoints": {
                    "auth": [
                        {"method": "POST", "path": "/auth/register", "description": "用户注册"},
                        {"method": "POST", "path": "/auth/login", "description": "用户登录"},
                        {"method": "GET", "path": "/auth/validate/{userId}", "description": "验证用户"}
                    ],
                    "groups": [
                        {"method": "POST", "path": "/groups/create", "description": "创建群组"},
                        {"method": "POST", "path": "/groups/join", "description": "加入群组"},
                        {"method": "GET", "path": "/groups/user/{userId}", "description": "获取用户群组"},
                        {"method": "GET", "path": "/groups/{groupId}", "description": "获取群组详情"},
                        {"method": "GET", "path": "/groups/{groupId}/members", "description": "获取群组成员"}
                    ],
                    "chat": [
                        {"method": "WS", "path": "/chat", "description": "WebSocket 聊天"},
                        {"method": "GET", "path": "/users", "description": "在线用户"}
                    ],
                    "files": [
                        {"method": "GET", "path": "/download/report/{sessionName}/{fileName}", "description": "下载报告"}
                    ]
                },
                "cors": {
                    "enabled": true,
                    "allowCredentials": true,
                    "allowedOrigins": "all"
                },
                "websocket": {
                    "enabled": true,
                    "endpoint": "/chat",
                    "protocol": "ws/wss"
                }
            }
        """.trimIndent(), ContentType.Application.Json)
    }
}

private fun Route.registerHealthGetRoute() {

    get("/health") {
        call.respondText(
            """{"status":"ok","service":"silk","timestamp":${System.currentTimeMillis()}}""",
            ContentType.Application.Json
        )
    }
}

private fun Route.registerUsersGetRoute() {

    get("/users") {
        val users = chatServer.getOnlineUsers()
        call.respond(users)
    }
}

private fun Route.registerUsersUserIdSettingsGetRoute() {

    // ==================== 用户设置 API ====================

    // 获取用户设置
    get("/users/{userId}/settings") {
        val userId = call.parameters["userId"] ?: ""

        if (userId.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                UserSettingsResponse(false, "用户ID不能为空")
            )
            return@get
        }

        runCatching {
            val settings = UserSettingsRepository.getUserSettings(userId)
            call.respond(UserSettingsResponse(true, "获取设置成功", settings))
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 获取用户设置失败: {}", e.message)
            call.respond(
                HttpStatusCode.InternalServerError,
                UserSettingsResponse(false, "获取设置失败: ${e.message}")
            )
        }
    }
}

private fun Route.registerUsersUserIdSettingsPutRoute() {

    // 更新用户设置
    put("/users/{userId}/settings") {
        val userId = call.parameters["userId"] ?: ""

        if (userId.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                UserSettingsResponse(false, "用户ID不能为空")
            )
            return@put
        }

        runCatching {
            val request = call.receive<UpdateUserSettingsRequest>()

            // 验证userId匹配
            if (request.userId != userId) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    UserSettingsResponse(false, "用户ID不匹配")
                )
                return@put
            }

            val settings = UserSettingsRepository.updateUserSettings(
                userId = userId,
                language = request.language,
                defaultAgentInstruction = request.defaultAgentInstruction
            )

            call.respond(UserSettingsResponse(true, "设置更新成功", settings))
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 更新用户设置失败: {}", e.message)
            call.respond(
                HttpStatusCode.InternalServerError,
                UserSettingsResponse(false, "更新设置失败: ${e.message}")
            )
        }
    }
}

private fun Route.registerUsersUserIdCcSettingsGetRoute() {

    // ==================== CC 设置 API ====================

    // 获取 CC 设置（token + bridge 状态）
    get("/users/{userId}/cc-settings") {
        val userId = call.parameters["userId"] ?: ""
        if (userId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, CcSettingsResponse(false, "用户ID不能为空"))
            return@get
        }
        runCatching {
            val token = UserSettingsRepository.getBridgeToken(userId)
            val connected = isAnyBridgeConnected(userId)
            val bridgeIp = if (connected) getAnyBridgeIp(userId) else null
            call.respond(CcSettingsResponse(true, "ok", token, connected, bridgeIp))
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 获取CC设置失败: {}", e.message)
            call.respond(HttpStatusCode.InternalServerError, CcSettingsResponse(false, "获取失败: ${e.message}"))
        }
    }
}

private fun Route.registerUsersUserIdCcSettingsGenerateTokenPostRoute() {

    // 生成/重新生成 Bridge Token
    post("/users/{userId}/cc-settings/generate-token") {
        val userId = call.parameters["userId"] ?: ""
        if (userId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, CcSettingsResponse(false, "用户ID不能为空"))
            return@post
        }
        runCatching {
            val token = UserSettingsRepository.generateBridgeToken(userId)
            // 踢掉用旧 token 认证的 ACP 连接
            val acpClosed = AcpRegistry.disconnect(userId)
            if (acpClosed > 0) {
                logger.info("🔌 Token 重生：关闭 {} 个 ACP 连接", acpClosed)
            }
            val connected = isAnyBridgeConnected(userId)
            val bridgeIp = if (connected) getAnyBridgeIp(userId) else null
            call.respond(CcSettingsResponse(true, "Token 已生成", token, connected, bridgeIp))
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 生成Bridge Token失败: {}", e.message)
            call.respond(HttpStatusCode.InternalServerError, CcSettingsResponse(false, "生成失败: ${e.message}"))
        }
    }
}

private fun Route.registerUsersUserIdCcSettingsBridgeStatusGetRoute() {

    // 查询 Bridge 在线状态
    get("/users/{userId}/cc-settings/bridge-status") {
        val userId = call.parameters["userId"] ?: ""
        if (userId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, CcSettingsResponse(false, "用户ID不能为空"))
            return@get
        }
        val connected = isAnyBridgeConnected(userId)
        val bridgeIp = if (connected) getAnyBridgeIp(userId) else null
        call.respond(CcSettingsResponse(true, "ok", bridgeConnected = connected, bridgeIp = bridgeIp))
    }
}

private fun Route.registerUsersUserIdCcStateGroupIdGetRoute() {

    // 查询 user+group 的 CC 当前状态（含工作目录），供工作流前端显示
    get("/users/{userId}/cc-state/{groupId}") {
        val userId = call.parameters["userId"] ?: ""
        val rawGroupId = call.parameters["groupId"] ?: ""
        if (userId.isBlank() || rawGroupId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, CcStateResponse(success = false))
            return@get
        }
        // CC 状态 key 使用 sessionName 格式 "group_{groupId}"（见 autoActivateForWorkflow 调用点）
        // 兼容：前端可能传 raw 或已带前缀
        val candidateIds = listOf(
            if (rawGroupId.startsWith("group_")) rawGroupId else "group_$rawGroupId",
            rawGroupId,
        ).distinct()
        val agentSnap = candidateIds.firstNotNullOfOrNull { gid ->
            AgentRuntime.snapshotState(userId, gid)
        }
        val bridgeConnected = isAnyBridgeConnected(userId)
        if (agentSnap != null) {
            val descriptor = agentSnap.agentType?.let { com.silk.backend.agents.core.AgentRegistry.getByType(it) }
            call.respond(
                CcStateResponse(
                    success = true,
                    active = agentSnap.active,
                    running = agentSnap.running,
                    workingDir = agentSnap.workingDir,
                    sessionId = "",
                    sessionStarted = agentSnap.active,
                    bridgeConnected = bridgeConnected,
                    agentType = agentSnap.agentType ?: "",
                    agentDisplayName = descriptor?.displayName ?: "",
                    permissionMode = agentSnap.permissionMode,
                )
            )
        } else {
            call.respond(CcStateResponse(success = true, bridgeConnected = bridgeConnected))
        }
    }
}

private fun Route.registerUsersUserIdCcFsListGetRoute() {

    // 列出 Bridge 所在机器上某路径下的子目录（用于工作流 Folder Picker）
    // path 为空表示使用 bridge 当前 workingDir 起点
    get("/users/{userId}/cc-fs/list") {
        val userId = call.parameters["userId"] ?: ""
        val path = call.request.queryParameters["path"]
        val showHidden = call.request.queryParameters["showHidden"]?.toBoolean() ?: false
        if (userId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, DirListingResponse(success = false, error = "userId 为空"))
            return@get
        }
        if (!isAnyBridgeConnected(userId)) {
            call.respond(HttpStatusCode.Conflict, DirListingResponse(success = false, error = "Bridge 未连接"))
            return@get
        }
        val raw = AgentRuntime.listDirectory(userId, path, showHidden, agentType = resolveActiveAgentType(userId) ?: "claude-code")
        if (raw == null) {
            call.respond(HttpStatusCode.GatewayTimeout, DirListingResponse(success = false, error = "Bridge 未响应或超时"))
            return@get
        }
        runCatching {
            call.respond(parseBridgeDirListing(raw))
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 解析 Bridge dir_listing 失败: {}", e.message)
            call.respond(
                HttpStatusCode.InternalServerError,
                DirListingResponse(success = false, error = "解析响应失败: ${e.message}")
            )
        }
    }
}

private fun Route.registerUsersUserIdCcFsCdPostRoute() {

    // 直接切换 user+group 的工作目录（不经过聊天消息流，避免 /cd 在聊天中显示气泡）。
    // 请求体（JSON）：{ "groupId": "...", "path": "..." }
    post("/users/{userId}/cc-fs/cd") {
        val userId = call.parameters["userId"] ?: ""
        val reqJson = runCatching {
            Json.parseToJsonElement(call.receiveText()).jsonObject
        }.rethrowRoutingCancellation().getOrElse { e ->
            call.respond(
                HttpStatusCode.BadRequest,
                CcStateResponse(success = false, error = "请求体非法 JSON: ${e.message}")
            )
            return@post
        }
        val groupId = reqJson["groupId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rawPath = reqJson["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (userId.isBlank() || groupId.isBlank() || rawPath.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                CcStateResponse(success = false, error = "userId / groupId / path 不能为空")
            )
            return@post
        }
        // 信任目录检查（bridgeId 格式 "ip:<ip>" 兼容已有 trust 记录）
        val bridgeId = resolveBridgeId(userId) ?: "unknown"
        if (!trustedDirManager.isTrusted(userId, bridgeId, rawPath)) {
            call.respond(
                HttpStatusCode.BadRequest,
                CcStateResponse(
                    success = false,
                    error = "目录 $rawPath 未被信任。请先确认信任该目录。",
                )
            )
            return@post
        }
        // 与 autoActivateForWorkflow 一致使用 "group_<id>" 形式作为 CC state key
        val ccGroupId = if (groupId.startsWith("group_")) groupId else "group_$groupId"
        when (val result = AgentRuntime.cdSync(userId, ccGroupId, rawPath, agentType = resolveActiveAgentType(userId) ?: "claude-code")) {
            is AgentRuntime.CdResult.Err -> {
                call.respond(
                    HttpStatusCode.Conflict,
                    CcStateResponse(success = false, error = result.reason)
                )
            }
            is AgentRuntime.CdResult.Ok -> {
                respondToSuccessfulCd(call, userId, ccGroupId, result)
            }
        }
    }
}

private fun Route.registerUsersUserIdCcSettingsUpdatePostRoute() {

    // API-driven 切换 agent / 权限模式（更改对话框用，不走聊天消息流）
    post("/users/{userId}/cc-settings/update") {
        val userId = call.parameters["userId"] ?: ""
        val reqJson = runCatching {
            Json.parseToJsonElement(call.receiveText()).jsonObject
        }.rethrowRoutingCancellation().getOrElse { e ->
            call.respond(
                HttpStatusCode.BadRequest,
                CcStateResponse(success = false, error = "请求体非法 JSON: ${e.message}")
            )
            return@post
        }
        val groupId = reqJson["groupId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (userId.isBlank() || groupId.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                CcStateResponse(success = false, error = "userId / groupId 不能为空")
            )
            return@post
        }
        val ccGroupId = if (groupId.startsWith("group_")) groupId else "group_$groupId"
        val newAgent = reqJson["activeAgent"]?.jsonPrimitive?.contentOrNull
        val newPermMode = reqJson["permissionMode"]?.jsonPrimitive?.contentOrNull

        when (val update = applyCcSettingsUpdate(userId, groupId, ccGroupId, newAgent, newPermMode)) {
            is CcSettingsUpdateResult.BadRequest -> {
                call.respond(
                    HttpStatusCode.BadRequest,
                    CcStateResponse(success = false, error = update.error)
                )
                return@post
            }
            is CcSettingsUpdateResult.Ok -> {
                broadcastAgentSwitchMessage(userId, ccGroupId, update.agentSwitchMessage)
            }
        }

        respondCcSettingsState(call, userId, ccGroupId)
    }
}

private fun Route.registerUsersUserIdTrustedDirsCheckGetRoute() {

    // ==================== Trusted Directory API ====================

    get("/users/{userId}/trusted-dirs/check") {
        val userId = call.parameters["userId"] ?: ""
        val path = call.request.queryParameters["path"] ?: ""
        if (userId.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                TrustedDirCheckResponse(trusted = false, bridgeConnected = false)
            )
            return@get
        }
        val bridgeConnected = isAnyBridgeConnected(userId)
        val bridgeId = if (bridgeConnected) resolveBridgeId(userId) else null
        val trusted = if (bridgeConnected && path.isNotBlank() && bridgeId != null) {
            trustedDirManager.isTrusted(userId, bridgeId, path)
        } else false
        call.respond(
            TrustedDirCheckResponse(
                trusted = trusted,
                bridgeConnected = bridgeConnected,
                bridgeId = bridgeId,
            )
        )
    }
}

private fun Route.registerUsersUserIdTrustedDirsPostRoute() {

    post("/users/{userId}/trusted-dirs") {
        val userId = call.parameters["userId"] ?: ""
        val req = runCatching {
            Json.decodeFromString<AddTrustRequest>(call.receiveText())
        }.rethrowRoutingCancellation().getOrElse { e ->
            logger.warn("Trusted dir add request JSON parse failed: {}", e.message, e)
            call.respondText(
                """{"success":false,"message":"请求体非法 JSON"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return@post
        }
        if (userId.isBlank() || req.path.isBlank()) {
            call.respondText(
                """{"success":false,"message":"userId 和 path 不能为空"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return@post
        }
        val bridgeId = resolveBridgeId(userId) ?: "unknown"
        val added = trustedDirManager.addTrust(userId, bridgeId, req.path)
        call.respondText(
            """{"success":true,"added":$added}""",
            ContentType.Application.Json,
            if (added) HttpStatusCode.Created else HttpStatusCode.OK
        )
    }
}

private fun Route.registerUsersUserIdTrustedDirsDeleteRoute() {

    delete("/users/{userId}/trusted-dirs") {
        val userId = call.parameters["userId"] ?: ""
        val req = runCatching {
            Json.decodeFromString<AddTrustRequest>(call.receiveText())
        }.rethrowRoutingCancellation().getOrElse { e ->
            logger.warn("Trusted dir delete request JSON parse failed: {}", e.message, e)
            call.respondText(
                """{"success":false,"message":"请求体非法 JSON"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return@delete
        }
        if (userId.isBlank() || req.path.isBlank()) {
            call.respondText(
                """{"success":false,"message":"userId 和 path 不能为空"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return@delete
        }
        val bridgeId = resolveBridgeId(userId) ?: "unknown"
        val removed = trustedDirManager.removeTrust(userId, bridgeId, req.path)
        call.respondText(
            """{"success":$removed}""",
            ContentType.Application.Json,
            if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound
        )
    }
}

private fun Route.registerUsersUserIdTrustedDirsGetRoute() {

    get("/users/{userId}/trusted-dirs") {
        val userId = call.parameters["userId"] ?: ""
        val bridgeId = call.request.queryParameters["bridgeId"]
        val entries = trustedDirManager.listTrusts(userId, bridgeId).map {
            TrustedDirRecordDto(it.bridgeId, it.path, it.trustedAt)
        }
        call.respond(TrustedDirListResponse(entries))
    }
}

private fun Route.registerDownloadReportSessionNameFileNameGetRoute() {

    // PDF 报告下载端点
    get("/download/report/{sessionName}/{fileName...}") {
        val sessionName = call.parameters["sessionName"] ?: "default_room"
        val fileName = call.parameters.getAll("fileName")?.joinToString("/") ?: ""

        logger.debug("📥 PDF下载请求:")
        logger.debug("   sessionName: {}", sessionName)
        logger.debug("   fileName: {}", fileName)

        // 获取当前工作目录
        val workingDir = System.getProperty("user.dir")
        logger.debug("   当前工作目录: {}", workingDir)

        // 尝试多个可能的路径（适配服务器和本地）
        val possiblePaths = listOf(
            // 服务器路径（~/Silk 目录）
            "${System.getProperty("user.home")}/Silk/chat_history/$sessionName/reports/$fileName",
            // 相对路径（从JAR运行位置）
            "chat_history/$sessionName/reports/$fileName",
            // 当前目录下的backend子目录
            "backend/chat_history/$sessionName/reports/$fileName",
            // 上级目录
            "../chat_history/$sessionName/reports/$fileName",
            // Mac本地开发路径
            "/Users/mac/Documents/Silk/backend/chat_history/$sessionName/reports/$fileName"
        )

        logger.debug("   尝试的路径:")
        possiblePaths.forEachIndexed { index, path ->
            val file = File(path)
            logger.debug("     {}. {} (exists={}, isFile={})", index + 1, path, file.exists(), file.isFile)
        }

        val pdfFile = possiblePaths
            .map { File(it) }
            .firstOrNull { it.exists() && it.isFile }

        if (pdfFile != null) {
            logger.info("✅ 找到PDF文件: {}", pdfFile.absolutePath)
            logger.debug("   文件大小: {} bytes", pdfFile.length())

            // ✅ 验证文件是否为有效的PDF（检查文件头）
            runCatching {
                pdfFile.inputStream().use { input ->
                    val header = ByteArray(5)
                    val bytesRead = input.read(header)
                    val isPdf = bytesRead == 5 &&
                               header[0] == 0x25.toByte() &&  // %
                               header[1] == 0x50.toByte() &&  // P
                               header[2] == 0x44.toByte() &&  // D
                               header[3] == 0x46.toByte()     // F

                    if (!isPdf) {
                        logger.warn("⚠️ 警告：文件不是有效的PDF格式")
                    }
                }
            }.rethrowRoutingCancellation().onFailure { e ->
                logger.warn("⚠️ 无法验证PDF格式: {}", e.message)
            }

            // ✅ 设置正确的 Content-Type
            call.response.header(HttpHeaders.ContentType, "application/pdf")

            // ✅ 设置 Content-Disposition，使用RFC 2231标准编码中文文件名
            // 只使用 filename* 参数（RFC 2231），避免在 filename 中使用非ASCII字符
            val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8")
                .replace("+", "%20")  // 空格使用 %20
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename*=UTF-8''$encodedFileName"  // ✅ 只使用 filename*，不使用 filename
            )

            // ✅ 设置 Content-Length 以便浏览器显示下载进度
            call.response.header(HttpHeaders.ContentLength, pdfFile.length().toString())

            // ✅ 禁用缓存，确保每次都下载最新文件
            call.response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
            call.response.header(HttpHeaders.Pragma, "no-cache")
            call.response.header(HttpHeaders.Expires, "0")

            call.respondFile(pdfFile)
        } else {
            val errorMsg = "PDF 文件未找到: $fileName\n\n尝试的路径:\n${possiblePaths.mapIndexed { i, p -> "${i+1}. $p" }.joinToString("\n")}"
            logger.error("❌ {}", errorMsg)
            call.respondText(errorMsg, status = HttpStatusCode.NotFound)
        }
    }
}

private fun Route.registerAuthRegisterPostRoute() {

    // 用户认证API
    post("/auth/register") {
        runCatching {
            val request = call.receive<RegisterRequest>()
            val response = AuthService.register(request)
            call.respond(response)
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 注册失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerAuthLoginPostRoute() {

    post("/auth/login") {
        runCatching {
            val request = call.receive<LoginRequest>()
            val response = AuthService.login(request)
            call.respond(response)
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 登录失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerAuthValidateUserIdGetRoute() {

    // 验证用户（用于重新认证）
    get("/auth/validate/{userId}") {
        val userId = call.parameters["userId"] ?: ""
        val user = UserRepository.findUserById(userId)

        if (user != null) {
            call.respond(AuthResponse(true, "验证成功", user))
        } else {
            call.respond(AuthResponse(false, "用户不存在或已失效"))
        }
    }
}

private fun Route.registerGroupsCreatePostRoute() {

    // 群组管理API
    post("/groups/create") {
        runCatching {
            val request = call.receive<CreateGroupRequest>()
            val response = GroupService.createGroup(request)

            call.respond(response)
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 创建群组失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, GroupResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerGroupsJoinPostRoute() {

    post("/groups/join") {
        runCatching {
            val request = call.receive<JoinGroupRequest>()
            val response = GroupService.joinGroup(request)
            call.respond(response)
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 加入群组失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, GroupResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerGroupsUserUserIdGetRoute() {

    get("/groups/user/{userId}") {
        val userId = call.parameters["userId"] ?: ""
        val response = GroupService.getUserGroups(userId)
        call.respond(response)
    }
}

private fun Route.registerGroupsGroupIdGetRoute() {

    get("/groups/{groupId}") {
        val groupId = call.parameters["groupId"] ?: ""
        val exportMode = call.request.queryParameters["export"]?.trim().orEmpty()
        if (exportMode == "obsidian_markdown") {
            val userId = call.request.queryParameters["userId"]?.trim().orEmpty()
            fun respondExportJson(success: Boolean, message: String, fileName: String = "", markdown: String = ""): String {
                return buildJsonObject {
                    put("success", success)
                    put("message", message)
                    put("fileName", fileName)
                    put("markdown", markdown)
                }.toString()
            }
            if (groupId.isBlank()) {
                call.respondText(respondExportJson(false, "缺少 groupId"), ContentType.Application.Json)
                return@get
            }
            if (userId.isBlank()) {
                call.respondText(respondExportJson(false, "缺少 userId"), ContentType.Application.Json)
                return@get
            }

            val group = GroupRepository.findGroupById(groupId)
            if (group == null) {
                call.respondText(respondExportJson(false, "群组不存在"), ContentType.Application.Json)
                return@get
            }
            if (!GroupRepository.isUserInGroup(groupId, userId)) {
                call.respondText(respondExportJson(false, "您不是该群组成员"), ContentType.Application.Json)
                return@get
            }

            val sessionName = "group_$groupId"
            val historyManager = ChatHistoryManager()
            val chatHistory = historyManager.loadChatHistory(sessionName)
            if (chatHistory == null) {
                call.respondText(respondExportJson(false, "聊天记录不存在"), ContentType.Application.Json)
                return@get
            }

            val markdown = ChatObsidianExporter.toMarkdown(
                groupId = groupId,
                groupName = group.name,
                sessionName = sessionName,
                history = chatHistory
            )
            val safeGroupName = group.name
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .trim('_')
                .ifBlank { "group_$groupId" }
            val fileName = "${safeGroupName}_${System.currentTimeMillis()}.md"

            call.respondText(respondExportJson(true, "ok", fileName, markdown), ContentType.Application.Json)
            return@get
        }

        val response = GroupService.getGroupDetails(groupId)
        call.respond(response)
    }
}

private fun Route.registerGroupsGroupIdMembersGetRoute() {

    get("/groups/{groupId}/members") {
        val groupId = call.parameters["groupId"] ?: ""
        val members = GroupService.getGroupMembers(groupId)
        // 转换为前端期望的格式
        val apiMembers = members.map { member ->
            val user = UserRepository.findUserById(member.userId)
            GroupMemberApi(
                id = member.userId,
                fullName = user?.fullName ?: member.userName,
                phone = user?.phoneNumber ?: ""
            )
        }
        call.respond(GroupMembersResponse(success = true, members = apiMembers))
    }
}

private fun Route.registerContactsUserIdGetRoute() {

    // ==================== 联系人管理 API ====================

    // 获取联系人列表（包含待处理请求）
    get("/contacts/{userId}") {
        val userId = call.parameters["userId"] ?: ""
        val contacts = ContactRepository.getContacts(userId)
        val pendingRequests = ContactRepository.getPendingRequests(userId)
        call.respond(ContactResponse(
            success = true,
            message = "获取联系人列表成功",
            contacts = contacts,
            pendingRequests = pendingRequests
        ))
    }
}

private fun Route.registerUsersSearchGetRoute() {

    // 通过电话号码搜索用户
    get("/users/search") {
        val phoneNumber = call.request.queryParameters["phone"] ?: ""
        if (phoneNumber.isBlank()) {
            call.respond(UserSearchResult(false, message = "请输入电话号码"))
            return@get
        }

        val user = UserRepository.findUserByPhoneNumber(phoneNumber)
        if (user != null) {
            call.respond(UserSearchResult(true, user, "找到用户"))
        } else {
            call.respond(UserSearchResult(false, message = "未找到该电话号码对应的用户"))
        }
    }
}

private fun Route.registerContactsRequestPostRoute() {

    // 发送联系人请求（通过电话号码）
    post("/contacts/request") {
        runCatching {
            val request = call.receive<SendContactRequestData>()

            // 查找目标用户
            val targetUser = UserRepository.findUserByPhoneNumber(request.toPhoneNumber)
            if (targetUser == null) {
                call.respond(ContactResponse(false, "未找到该电话号码对应的用户"))
                return@post
            }

            // 检查是否已经是联系人
            if (ContactRepository.areContacts(request.fromUserId, targetUser.id)) {
                call.respond(ContactResponse(false, "该用户已经是您的联系人"))
                return@post
            }

            // 检查是否是自己
            if (request.fromUserId == targetUser.id) {
                call.respond(ContactResponse(false, "不能添加自己为联系人"))
                return@post
            }

            // 创建联系人请求
            val contactRequest = ContactRepository.createContactRequest(request.fromUserId, targetUser.id)
            if (contactRequest != null) {
                call.respond(ContactResponse(true, "联系人请求已发送"))
            } else {
                call.respond(ContactResponse(false, "已有待处理的请求"))
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 发送联系人请求失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, ContactResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerContactsRequestByIdPostRoute() {

    // 发送联系人请求（通过用户ID，用于从聊天中添加）
    post("/contacts/request-by-id") {
        runCatching {
            val request = call.receive<SendContactRequestByIdData>()

            // 检查目标用户是否存在
            val targetUser = UserRepository.findUserById(request.toUserId)
            if (targetUser == null) {
                call.respond(ContactResponse(false, "用户不存在"))
                return@post
            }

            // 检查是否已经是联系人
            if (ContactRepository.areContacts(request.fromUserId, request.toUserId)) {
                call.respond(ContactResponse(false, "该用户已经是您的联系人"))
                return@post
            }

            // 检查是否是自己
            if (request.fromUserId == request.toUserId) {
                call.respond(ContactResponse(false, "不能添加自己为联系人"))
                return@post
            }

            // 创建联系人请求
            val contactRequest = ContactRepository.createContactRequest(request.fromUserId, request.toUserId)
            if (contactRequest != null) {
                call.respond(ContactResponse(true, "联系人请求已发送"))
            } else {
                call.respond(ContactResponse(false, "已有待处理的请求"))
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 发送联系人请求失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, ContactResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerContactsHandleRequestPostRoute() {

    // 处理联系人请求（接受/拒绝）
    post("/contacts/handle-request") {
        runCatching {
            val request = call.receive<HandleContactRequestData>()

            // 验证请求是否属于该用户
            val contactRequest = ContactRepository.getRequestById(request.requestId)
            if (contactRequest == null) {
                call.respond(ContactResponse(false, "请求不存在"))
                return@post
            }

            if (contactRequest.toUserId != request.userId) {
                call.respond(ContactResponse(false, "无权处理此请求"))
                return@post
            }

            val success = ContactRepository.handleContactRequest(request.requestId, request.accept)
            if (success) {
                val message = if (request.accept) "已添加联系人" else "已拒绝请求"
                call.respond(ContactResponse(true, message))
            } else {
                call.respond(ContactResponse(false, "处理请求失败"))
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 处理联系人请求失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, ContactResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerContactsPrivateChatPostRoute() {

    // 开始/获取私聊会话（与联系人的双人+Silk对话）
    post("/contacts/private-chat") {
        runCatching {
            val request = call.receive<StartPrivateChatRequest>()

            // 检查是否是联系人
            if (!ContactRepository.areContacts(request.userId, request.contactId)) {
                call.respond(PrivateChatResponse(false, "该用户不是您的联系人"))
                return@post
            }

            // 获取两个用户的信息
            val user = UserRepository.findUserById(request.userId)
            val contact = UserRepository.findUserById(request.contactId)

            if (user == null || contact == null) {
                call.respond(PrivateChatResponse(false, "用户信息不完整"))
                return@post
            }

            // 查找两个用户共同的任意群组（不限成员数，群组可扩展）
            val existingGroup = GroupRepository.findCommonGroup(request.userId, request.contactId)

            if (existingGroup != null) {
                call.respond(PrivateChatResponse(true, "打开对话", existingGroup, isNew = false))
            } else {
                // 创建新群组（用联系人名字命名，后续可扩展添加更多成员）
                val groupName = "${contact.fullName} 的对话"
                val newGroup = GroupRepository.createContactGroup(
                    user1Id = request.userId,
                    user2Id = request.contactId,
                    groupName = groupName
                )

                if (newGroup != null) {
                    call.respond(PrivateChatResponse(true, "创建对话", newGroup, isNew = true))
                } else {
                    call.respond(PrivateChatResponse(false, "创建会话失败"))
                }
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 对话会话失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, PrivateChatResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerGroupsGroupIdAddMemberPostRoute() {

    // 添加成员到群组（无需确认）
    post("/groups/{groupId}/add-member") {
        runCatching {
            val groupId = call.parameters["groupId"] ?: return@post call.respond(
                HttpStatusCode.BadRequest, SimpleResponse(false, "缺少群组ID")
            )
            val request = call.receive<AddMemberRequest>()

            // 检查群组是否存在
            val group = GroupRepository.findGroupById(groupId)
            if (group == null) {
                call.respond(SimpleResponse(false, "群组不存在"))
                return@post
            }

            // 检查用户是否已在群组中
            if (GroupRepository.isUserInGroup(groupId, request.userId)) {
                call.respond(SimpleResponse(false, "用户已在群组中"))
                return@post
            }

            // 添加用户到群组
            val success = GroupRepository.addUserToGroup(groupId, request.userId)
            if (success) {
                call.respond(SimpleResponse(true, "成功添加成员"))
            } else {
                call.respond(SimpleResponse(false, "添加成员失败"))
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 添加成员失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerGroupsGroupIdLeavePostRoute() {

    // 退出群组
    post("/groups/{groupId}/leave") {
        runCatching {
            val groupId = call.parameters["groupId"] ?: return@post call.respond(
                HttpStatusCode.BadRequest, LeaveGroupResponse(false, "缺少群组ID")
            )
            val request = call.receive<LeaveGroupRequest>()

            // 检查群组是否存在
            val group = GroupRepository.findGroupById(groupId)
            if (group == null) {
                call.respond(LeaveGroupResponse(false, "群组不存在"))
                return@post
            }

            // 用户退出群组
            val (success, groupDeleted) = GroupRepository.leaveGroup(groupId, request.userId)

            if (success) {
                val message = if (groupDeleted) {
                    "已退出群组，群组已被删除（无剩余成员）"
                } else {
                    "已退出群组"
                }
                call.respond(LeaveGroupResponse(true, message, groupDeleted))
            } else {
                call.respond(LeaveGroupResponse(false, "退出群组失败"))
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 退出群组失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, LeaveGroupResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerGroupsGroupIdDeleteRoute() {

    // 删除群组（仅群主可操作）
    delete("/groups/{groupId}") {
        runCatching {
            val groupId = call.parameters["groupId"] ?: return@delete call.respond(
                HttpStatusCode.BadRequest, SimpleResponse(false, "缺少群组ID")
            )
            val request = call.receive<DeleteGroupRequest>()

            // 检查群组是否存在
            val group = GroupRepository.findGroupById(groupId)
            if (group == null) {
                call.respond(SimpleResponse(false, "群组不存在"))
                return@delete
            }

            // 验证是否为群主
            if (group.hostId != request.userId) {
                call.respond(SimpleResponse(false, "只有群主才能删除群组"))
                return@delete
            }

            // 删除群组
            val (success, message, _) = GroupRepository.deleteGroupByHost(groupId, request.userId)

            if (success) {
                // 清理群组的ChatServer实例
                groupChatServers.remove(groupId)
                logger.info("🗑️ 群组 {} 已被群主 {} 删除", groupId, request.userId)
                call.respond(SimpleResponse(true, message))
            } else {
                call.respond(SimpleResponse(false, message))
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 删除群组失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerApiGroupsGroupIdExportMarkdownGetRoute() {

    // 导出群组聊天记录为 Obsidian Markdown（兼容 /api 与非 /api 前缀）
    get("/api/groups/{groupId}/export/markdown") {
        val groupId = call.parameters["groupId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            SimpleResponse(false, "缺少群组ID")
        )
        val userId = call.request.queryParameters["userId"]?.trim().orEmpty()
        if (userId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "缺少 userId"))
            return@get
        }

        val group = GroupRepository.findGroupById(groupId)
        if (group == null) {
            call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "群组不存在"))
            return@get
        }
        if (!GroupRepository.isUserInGroup(groupId, userId)) {
            call.respond(HttpStatusCode.Forbidden, SimpleResponse(false, "您不是该群组成员"))
            return@get
        }

        val sessionName = "group_$groupId"
        val historyManager = ChatHistoryManager()
        val chatHistory = historyManager.loadChatHistory(sessionName)
        if (chatHistory == null) {
            call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "聊天记录不存在"))
            return@get
        }

        val markdown = ChatObsidianExporter.toMarkdown(
            groupId = groupId,
            groupName = group.name,
            sessionName = sessionName,
            history = chatHistory
        )
        val safeGroupName = group.name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "group_$groupId" }
        val fileName = "${safeGroupName}_${System.currentTimeMillis()}.md"

        call.response.headers.append(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
        )
        call.response.headers.append("X-Silk-Group-Id", groupId)
        call.response.headers.append("X-Silk-Session-Id", chatHistory.sessionId)
        call.response.headers.append("X-Silk-Updated-At", (chatHistory.messages.maxOfOrNull { it.timestamp } ?: 0L).toString())
        call.respondText(markdown, ContentType.parse("text/markdown; charset=utf-8"))
    }
}

private fun Route.registerGroupsGroupIdExportMarkdownGetRoute() {
    get("/groups/{groupId}/export/markdown") {
        val groupId = call.parameters["groupId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            SimpleResponse(false, "缺少群组ID")
        )
        val userId = call.request.queryParameters["userId"]?.trim().orEmpty()
        if (userId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "缺少 userId"))
            return@get
        }

        val group = GroupRepository.findGroupById(groupId)
        if (group == null) {
            call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "群组不存在"))
            return@get
        }
        if (!GroupRepository.isUserInGroup(groupId, userId)) {
            call.respond(HttpStatusCode.Forbidden, SimpleResponse(false, "您不是该群组成员"))
            return@get
        }

        val sessionName = "group_$groupId"
        val historyManager = ChatHistoryManager()
        val chatHistory = historyManager.loadChatHistory(sessionName)
        if (chatHistory == null) {
            call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "聊天记录不存在"))
            return@get
        }

        val markdown = ChatObsidianExporter.toMarkdown(
            groupId = groupId,
            groupName = group.name,
            sessionName = sessionName,
            history = chatHistory
        )
        val safeGroupName = group.name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "group_$groupId" }
        val fileName = "${safeGroupName}_${System.currentTimeMillis()}.md"

        call.response.headers.append(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
        )
        call.response.headers.append("X-Silk-Group-Id", groupId)
        call.response.headers.append("X-Silk-Session-Id", chatHistory.sessionId)
        call.response.headers.append("X-Silk-Updated-At", (chatHistory.messages.maxOfOrNull { it.timestamp } ?: 0L).toString())
        call.respondText(markdown, ContentType.parse("text/markdown; charset=utf-8"))
    }
}

private fun Route.registerDownloadObsidianGroupIdGetRoute() {
    // 兼容受限网关：走 /download 前缀导出 Obsidian Markdown
    get("/download/obsidian/{groupId}") {
        val groupId = call.parameters["groupId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            SimpleResponse(false, "缺少群组ID")
        )
        val userId = call.request.queryParameters["userId"]?.trim().orEmpty()
        if (userId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "缺少 userId"))
            return@get
        }

        val group = GroupRepository.findGroupById(groupId)
        if (group == null) {
            call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "群组不存在"))
            return@get
        }
        if (!GroupRepository.isUserInGroup(groupId, userId)) {
            call.respond(HttpStatusCode.Forbidden, SimpleResponse(false, "您不是该群组成员"))
            return@get
        }

        val sessionName = "group_$groupId"
        val historyManager = ChatHistoryManager()
        val chatHistory = historyManager.loadChatHistory(sessionName)
        if (chatHistory == null) {
            call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "聊天记录不存在"))
            return@get
        }

        val markdown = ChatObsidianExporter.toMarkdown(
            groupId = groupId,
            groupName = group.name,
            sessionName = sessionName,
            history = chatHistory
        )
        val safeGroupName = group.name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "group_$groupId" }
        val fileName = "${safeGroupName}_${System.currentTimeMillis()}.md"

        call.response.headers.append(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
        )
        call.response.headers.append("X-Silk-Group-Id", groupId)
        call.response.headers.append("X-Silk-Session-Id", chatHistory.sessionId)
        call.response.headers.append("X-Silk-Updated-At", (chatHistory.messages.maxOfOrNull { it.timestamp } ?: 0L).toString())
        call.respondText(markdown, ContentType.parse("text/markdown; charset=utf-8"))
    }
}

private fun Route.registerApiUnreadUserIdGetRoute() {

    // ==================== 未读消息 API ====================

    // 获取用户所有群组的未读消息数
    get("/api/unread/{userId}") {
        val userId = call.parameters["userId"] ?: ""

        // 获取用户的所有群组
        val groups = GroupRepository.getUserGroups(userId)
        val groupIds = groups.map { it.id }

        // 获取未读数
        val unreadCounts = UnreadRepository.getUnreadCounts(userId, groupIds)

        call.respond(UnreadCountResponse(true, unreadCounts))
    }
}

private fun Route.registerApiUnreadMarkReadPostRoute() {

    // 标记群组已读
    post("/api/unread/mark-read") {
        runCatching {
            val request = call.receive<MarkReadRequest>()
            UnreadRepository.markAsRead(request.userId, request.groupId)
            call.respond(SimpleResponse(true, "已标记为已读"))
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.warn("Mark-read request failed: {}", e.message, e)
            call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerApiCalendarWorkdayDateGetRoute() {

    // ==================== 日历/工作日 API ====================
    get("/api/calendar/workday/{date}") {
        val dateRaw = call.parameters["date"] ?: ""
        if (dateRaw.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "success" to false,
                    "message" to "date 不能为空，格式应为 yyyy-MM-dd"
                )
            )
            return@get
        }
        runCatching {
            val date = LocalDate.parse(dateRaw)
            val isWorkday = com.silk.backend.todos.HolidayCalendarCn.isWorkday(date)
            call.respond(
                mapOf(
                    "success" to true,
                    "message" to "ok",
                    "date" to dateRaw,
                    "isWorkday" to isWorkday
                )
            )
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.warn("Workday request failed for date {}: {}", dateRaw, e.message, e)
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "success" to false,
                    "message" to "date 格式错误，应为 yyyy-MM-dd"
                )
            )
        }
    }
}

private fun Route.registerApiUserTodosUserIdGetRoute() {

    // ==================== 跨群待办（[Silk] 专属对话侧边能力） ====================
    get("/api/user-todos/{userId}") {
        val userId = call.parameters["userId"] ?: ""
        val items = com.silk.backend.todos.UserTodoStore.load(userId)
            .sortedByDescending { it.updatedAt }
        call.respond(UserTodosResponse(true, "ok", items))
    }
}

private fun Route.registerApiUserTodosUserIdRefreshGetRoute() {

    /**
     * 触发跨群待办同步（GET，无请求体，避免部分客户端 POST JSON 序列化不兼容）。
     */
    get("/api/user-todos/{userId}/refresh") {
        val userId = call.parameters["userId"] ?: ""
        var syncDetail = "ok"
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.silk.backend.todos.GroupTodoExtractionService.refreshTodosForUser(userId)
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.warn("待办同步异常 userId={}: {}", userId.take(8), e.message, e)
            syncDetail = "同步异常，已返回已有列表: ${e.message?.take(120)}"
        }
        val items = com.silk.backend.todos.UserTodoStore.load(userId)
            .sortedByDescending { it.updatedAt }
        call.respond(UserTodosResponse(true, syncDetail, items))
    }
}

private fun Route.registerApiUserTodosUserIdRefreshAsyncStartPostRoute() {

    /**
     * 后台异步刷新：快速返回任务状态，避免客户端长时间阻塞。
     */
    post("/api/user-todos/{userId}/refresh-async/start") {
        val userId = call.parameters["userId"] ?: ""
        val status = com.silk.backend.todos.UserTodoRefreshAsyncManager.start(userId)
        call.respond(status)
    }
}

private fun Route.registerApiUserTodosUserIdRefreshAsyncStatusGetRoute() {

    /**
     * 查询后台异步刷新状态。
     */
    get("/api/user-todos/{userId}/refresh-async/status") {
        val userId = call.parameters["userId"] ?: ""
        val status = com.silk.backend.todos.UserTodoRefreshAsyncManager.status(userId)
        call.respond(status)
    }
}

private fun Route.registerApiUserTodosUserIdDiagnosticsGetRoute() {

    /**
     * 查询最近一次待办抽取诊断信息（用于定位漏抽）。
     */
    get("/api/user-todos/{userId}/diagnostics") {
        val userId = call.parameters["userId"] ?: ""
        val d = com.silk.backend.todos.GroupTodoExtractionService.getDiagnostics(userId)
        call.respond(
            UserTodoExtractionDiagnosticsResponse(
                success = true,
                message = "ok",
                userId = d.userId,
                updatedAt = d.updatedAt,
                source = d.source,
                totalGroups = d.totalGroups,
                transcriptChars = d.transcriptChars,
                llmDraftCount = d.llmDraftCount,
                heuristicDraftCount = d.heuristicDraftCount,
                forcedRecurringCount = d.forcedRecurringCount,
                finalDraftCount = d.finalDraftCount,
                matchedRecurringLines = d.matchedRecurringLines,
                note = d.note
            )
        )
    }
}

private fun Route.registerApiUserTodosItemPutRoute() {

    put("/api/user-todos/item") {
        runCatching {
            val request = call.receive<UpdateUserTodoRequest>()
            val ok = com.silk.backend.todos.UserTodoStore.updateItem(
                userId = request.userId,
                itemId = request.itemId,
                done = request.done,
                title = request.title,
                actionType = request.actionType,
                actionDetail = request.actionDetail,
                executedAt = request.executedAt,
                reminderId = request.reminderId,
                clearReminderId = request.clearReminderId,
                taskKind = request.taskKind,
                repeatRule = request.repeatRule,
                repeatAnchor = request.repeatAnchor,
                activeFrom = request.activeFrom,
                activeTo = request.activeTo,
                templateId = request.templateId,
                lifecycleState = request.lifecycleState,
                closedAt = request.closedAt,
                lastEvidenceAt = request.lastEvidenceAt,
                explicitIntent = request.explicitIntent,
                dateBucket = request.dateBucket,
                reopenCount = request.reopenCount
            )
            val items = com.silk.backend.todos.UserTodoStore.load(request.userId)
                .sortedByDescending { it.updatedAt }
            call.respond(
                if (ok) UserTodosResponse(true, "已更新", items)
                else UserTodosResponse(false, "待办不存在", items)
            )
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.warn("User todo update request failed: {}", e.message, e)
            call.respond(HttpStatusCode.BadRequest, UserTodosResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerApiUserTodosItemDeleteRoute() {

    delete("/api/user-todos/item") {
        runCatching {
            val request = call.receive<DeleteUserTodoRequest>()
            val ok = com.silk.backend.todos.UserTodoStore.deleteItem(
                request.userId, request.itemId
            )
            val items = com.silk.backend.todos.UserTodoStore.load(request.userId)
                .sortedByDescending { it.updatedAt }
            call.respond(
                if (ok) UserTodosResponse(true, "已删除", items)
                else UserTodosResponse(false, "待办不存在", items)
            )
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.warn("User todo delete request failed: {}", e.message, e)
            call.respond(HttpStatusCode.BadRequest, UserTodosResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerApiUserTodosRefreshPostRoute() {

    post("/api/user-todos/refresh") {
        val userId = runCatching {
            call.receive<RefreshUserTodosRequest>().userId
        }.rethrowRoutingCancellation().getOrElse { e ->
            logger.warn("刷新待办请求体解析失败: {}", e.message, e)
            call.respond(
                HttpStatusCode.BadRequest,
                UserTodosResponse(false, "请求格式错误: ${e.message}", emptyList())
            )
            return@post
        }
        var syncDetail = "已根据各群记录刷新待办"
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.silk.backend.todos.GroupTodoExtractionService.refreshTodosForUser(userId)
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.warn("待办同步异常: {}", e.message, e)
            syncDetail = "同步异常，已返回已有列表: ${e.message?.take(120)}"
        }
        val items = com.silk.backend.todos.UserTodoStore.load(userId)
            .sortedByDescending { it.updatedAt }
        call.respond(UserTodosResponse(true, syncDetail, items))
    }
}

private fun Route.registerFileRoutes() {

    // 文件上传/下载 API
    fileRoutes()
}

private fun Route.registerAsrRoutes() {

    // 语音识别 (ASR) API
    asrRoutes()
}

private fun Route.registerApiSilkPrivateChatPostRoute() {

    // ==================== 与 Silk 直接对话 API ====================
    post("/api/silk-private-chat") {
        runCatching {
            val request = call.receive<StartSilkPrivateChatRequest>()

            // 获取用户信息
            val user = UserRepository.findUserById(request.userId)
            if (user == null) {
                call.respond(PrivateChatResponse(false, "用户不存在"))
                return@post
            }

            // Silk AI Agent ID
            val silkAgentId = SilkAgent.AGENT_ID

            // 查找用户与 Silk 的专属私聊群组
            // 使用特殊命名规则来区分 Silk 私聊：以 "[Silk] " 开头
            val existingGroup = transaction {
                val userGroups = GroupMembers
                    .select { GroupMembers.userId eq request.userId }
                    .map { it[GroupMembers.groupId] }

                for (groupId in userGroups) {
                    val group = Groups.select { Groups.id eq groupId }.singleOrNull()
                    if (group != null && group[Groups.name].startsWith("[Silk] ")) {
                        // 检查 Silk AI 是否也在这个群组中
                        val silkInGroup = GroupMembers.select {
                            (GroupMembers.groupId eq groupId) and (GroupMembers.userId eq silkAgentId)
                        }.count() > 0

                        if (silkInGroup) {
                            // 检查群组是否只有2个成员（用户 + Silk）
                            val memberCount = GroupMembers.select { GroupMembers.groupId eq groupId }.count()
                            if (memberCount == 2L) {
                                val hostUser = UserRepository.findUserById(group[Groups.hostId])
                                return@transaction com.silk.backend.database.Group(
                                    id = group[Groups.id],
                                    name = group[Groups.name],
                                    invitationCode = group[Groups.invitationCode],
                                    hostId = group[Groups.hostId],
                                    hostName = hostUser?.fullName ?: "",
                                    createdAt = group[Groups.createdAt].toString()
                                )
                            }
                        }
                    }
                }
                null
            }

            if (existingGroup != null) {
                logger.info("✅ 找到用户 {} 与 Silk 的私聊: {}", user.fullName, existingGroup.name)
                call.respond(PrivateChatResponse(true, "打开 Silk 对话", existingGroup, isNew = false))
            } else {
                // 创建新的 Silk 私聊群组
                val groupName = "[Silk] ${user.fullName} 的专属对话"
                val groupId = java.util.UUID.randomUUID().toString()
                val invitationCode = java.util.UUID.randomUUID().toString().substring(0, 6).uppercase()

                val newGroup = transaction {
                    // 创建群组
                    Groups.insert {
                        it[id] = groupId
                        it[name] = groupName
                        it[Groups.invitationCode] = invitationCode
                        it[hostId] = request.userId // 用户作为群主
                    }

                    // 添加用户作为成员
                    GroupMembers.insert {
                        it[GroupMembers.groupId] = groupId
                        it[GroupMembers.userId] = request.userId
                        it[GroupMembers.role] = MemberRole.HOST.name
                    }

                    // 添加 Silk AI 作为成员
                    GroupMembers.insert {
                        it[GroupMembers.groupId] = groupId
                        it[GroupMembers.userId] = silkAgentId
                        it[GroupMembers.role] = MemberRole.GUEST.name
                    }

                    // 创建聊天历史文件夹
                    val sessionDir = java.io.File("chat_history/group_$groupId")
                    sessionDir.mkdirs()
                    logger.debug("📁 Silk 私聊历史文件夹已创建: {}", sessionDir.path)

                    com.silk.backend.database.Group(
                        id = groupId,
                        name = groupName,
                        invitationCode = invitationCode,
                        hostId = request.userId,
                        hostName = user.fullName,
                        createdAt = System.currentTimeMillis().toString()
                    )
                }

                logger.info("🆕 创建用户 {} 与 Silk 的私聊: {}", user.fullName, groupName)
                call.respond(PrivateChatResponse(true, "创建 Silk 对话", newGroup, isNew = true))
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 创建 Silk 私聊失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, PrivateChatResponse(false, "请求格式错误"))
        }
    }
}

private fun Route.registerApiMessagesExportMarkdownGetRoute() {

    // ==================== 消息发送 API（用于转发等功能） ====================
    get("/api/messages/export/markdown") {
        val groupId = call.request.queryParameters["groupId"]?.trim().orEmpty()
        val userId = call.request.queryParameters["userId"]?.trim().orEmpty()
        if (groupId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "缺少 groupId"))
            return@get
        }
        if (userId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "缺少 userId"))
            return@get
        }

        val group = GroupRepository.findGroupById(groupId)
        if (group == null) {
            call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "群组不存在"))
            return@get
        }
        if (!GroupRepository.isUserInGroup(groupId, userId)) {
            call.respond(HttpStatusCode.Forbidden, SimpleResponse(false, "您不是该群组成员"))
            return@get
        }

        val sessionName = "group_$groupId"
        val historyManager = ChatHistoryManager()
        val chatHistory = historyManager.loadChatHistory(sessionName)
        if (chatHistory == null) {
            call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "聊天记录不存在"))
            return@get
        }

        val markdown = ChatObsidianExporter.toMarkdown(
            groupId = groupId,
            groupName = group.name,
            sessionName = sessionName,
            history = chatHistory
        )
        val safeGroupName = group.name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "group_$groupId" }
        val fileName = "${safeGroupName}_${System.currentTimeMillis()}.md"

        call.response.headers.append(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
        )
        call.response.headers.append("X-Silk-Group-Id", groupId)
        call.response.headers.append("X-Silk-Session-Id", chatHistory.sessionId)
        call.response.headers.append("X-Silk-Updated-At", (chatHistory.messages.maxOfOrNull { it.timestamp } ?: 0L).toString())
        call.respondText(markdown, ContentType.parse("text/markdown; charset=utf-8"))
    }
}

private fun Route.registerApiMessagesSendPostRoute() {

    post("/api/messages/send") {
        runCatching {
            val request = call.receive<SendMessageRequest>()

            // 检查群组是否存在
            val group = GroupRepository.findGroupById(request.groupId)
            if (group == null) {
                call.respond(SimpleResponse(false, "群组不存在"))
                return@post
            }

            // 检查用户是否在群组中
            if (!GroupRepository.isUserInGroup(request.groupId, request.userId)) {
                call.respond(SimpleResponse(false, "您不是该群组成员"))
                return@post
            }

            // 获取群组的 ChatServer 并发送消息
            val groupChatServer = getGroupChatServer(request.groupId)

            // 创建消息
            val message = Message(
                id = UUID.randomUUID().toString(),
                content = request.content,
                userId = request.userId,
                userName = request.userName,
                timestamp = System.currentTimeMillis(),
                type = MessageType.TEXT
            )

            // 广播消息到群组
            groupChatServer.broadcast(message)

            logger.debug("📨 转发消息到群组 {}: {}...", request.groupId, request.content.take(50))

            call.respond(SimpleResponse(true, "消息发送成功"))
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 发送消息失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "发送失败: ${e.message}"))
        }
    }
}

private fun Route.registerApiMessagesRecallPostRoute() {

    // ==================== 消息撤回 API ====================
    post("/api/messages/recall") {
        runCatching {
            val request = call.receive<RecallMessageRequest>()

            // 检查群组是否存在
            val group = GroupRepository.findGroupById(request.groupId)
            if (group == null) {
                call.respond(SimpleResponse(false, "群组不存在"))
                return@post
            }

            // 获取群组的 ChatServer 并撤回消息
            val groupChatServer = getGroupChatServer(request.groupId)

            // 撤回消息
            val result = groupChatServer.recallMessage(
                messageId = request.messageId,
                userId = request.userId
            )

            if (result.success) {
                logger.info("🗑️ 消息已撤回: {} by {}", request.messageId, request.userId)
                call.respond(SimpleResponse(true, result.message))
            } else {
                call.respond(SimpleResponse(false, result.message))
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 撤回消息失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "撤回失败: ${e.message}"))
        }
    }
}

private fun Route.registerApiMessagesDeletePostRoute() {

    // ==================== 消息删除 API ====================
    post("/api/messages/delete") {
        runCatching {
            val request = call.receive<RecallMessageRequest>()

            val group = GroupRepository.findGroupById(request.groupId)
            if (group == null) {
                call.respond(SimpleResponse(false, "群组不存在"))
                return@post
            }

            val groupChatServer = getGroupChatServer(request.groupId)
            val result = groupChatServer.deleteMessage(
                messageId = request.messageId,
                userId = request.userId
            )

            if (result.success) {
                logger.info("🗑️ 消息已删除: {} by {}", request.messageId, request.userId)
                call.respond(SimpleResponse(true, result.message))
            } else {
                call.respond(SimpleResponse(false, result.message))
            }
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("❌ 删除消息失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "删除失败: ${e.message}"))
        }
    }
}

private fun Route.registerAgentBridgeWebSocketRoute() {

    // ==================== Agent Bridge WebSocket (ACP) ====================

    webSocket("/agent-bridge") {
        val token = call.request.queryParameters["token"]
        if (token.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing token"))
            return@webSocket
        }
        val userId = UserSettingsRepository.findUserIdByBridgeToken(token)
        if (userId == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid token"))
            return@webSocket
        }

        val agentType = call.request.queryParameters["agentType"]
        if (agentType.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing agentType"))
            return@webSocket
        }
        if (!AgentRegistry.isRegistered(agentType)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unknown agentType: $agentType"))
            return@webSocket
        }

        logger.info("🔌 Agent Bridge 连接: userId={}, agentType={}", userId, agentType)
        val remoteIp = call.request.local.remoteAddress

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val client = runCatching {
            AcpRegistry.acceptConnection(
                userId = userId,
                agentType = agentType,
                session = this,
                remoteIp = remoteIp,
                scope = scope,
            )
        }.rethrowRoutingCancellation().getOrElse { e ->
            logger.error("❌ Agent Bridge acceptConnection 失败", e)
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "accept failed"))
            return@webSocket
        }

        if (client == null) {
            logger.error("❌ Agent Bridge acceptConnection 返回 null")
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "accept failed"))
            return@webSocket
        }

        runCatching {
            val result = client.initialize(
                com.silk.backend.agents.acp.InitializeParams(
                    protocolVersion = "0.2",
                    clientCapabilities = com.silk.backend.agents.acp.ClientCapabilities(
                        fs = com.silk.backend.agents.acp.FsCapability(readTextFile = true, writeTextFile = true),
                        terminal = false,
                    ),
                )
            )
            logger.info("[Agent Bridge] initialize 成功: agentCapabilities={}", result.agentCapabilities)
        }.rethrowRoutingCancellation().onFailure { e ->
            logger.error("[Agent Bridge] initialize 失败", e)
            AcpRegistry.unregister(userId, agentType)
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "initialize failed"))
            return@webSocket
        }

        runCatching {
            // IMPORTANT: Do NOT consume `incoming` here.
            // AcpClient.receiveLoop() handles all incoming frames via AcpWebSocketTransport
            // which consumes from the same Ktor channel. Two consumers race and lose frames.
            // Just suspend until connection closes.
            closeReason.await()
        }.onFailure { e ->
            if (e is CancellationException) {
                logger.debug(
                    "Agent Bridge 会话正常取消: userId={}, agentType={}, reason={}",
                    userId,
                    agentType,
                    e.message,
                )
            } else {
                logger.error("❌ Agent Bridge WebSocket 错误: userId={}, agentType={}", userId, agentType, e)
            }
        }
        run {
            logger.info("🔌 Agent Bridge 断开: userId={}, agentType={}", userId, agentType)
            scope.cancel()
            AcpRegistry.unregister(userId, agentType)
            AgentRuntime.handleAgentDisconnect(userId, agentType)
        }
    }
}

private fun Route.registerApiAgentsGetRoute() {

    // ==================== Agents API ====================

    // GET /api/agents?userId=<id>
    // 列出可作为 workflow agent 的选项：仅包含已注册的 bridge agent（claude_code / codex 等）。
    // silk_chat 走普通会话路径，不在工作流 agent 选择范围内。
    // agentType 字段使用 workflow 存储一致的 underscore 形式。
    get("/api/agents") {
        val userId = call.request.queryParameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respondText(
                """{"success":false,"message":"Missing userId"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }
        val arr = kotlinx.serialization.json.buildJsonArray {
            AgentRegistry.list().forEach { desc ->
                val dashType = desc.agentType
                val underscoreType = dashType.replace('-', '_')
                add(kotlinx.serialization.json.buildJsonObject {
                    put("agentType", kotlinx.serialization.json.JsonPrimitive(underscoreType))
                    put("displayName", kotlinx.serialization.json.JsonPrimitive(desc.displayName))
                    put("connected", kotlinx.serialization.json.JsonPrimitive(AcpRegistry.isConnected(userId, dashType)))
                })
            }
        }
        call.respondText(arr.toString(), ContentType.Application.Json)
    }
}

private fun Route.registerApiWorkflowsGetRoute() {

    // ==================== Workflow API ====================

    get("/api/workflows") {
        val userId = call.request.queryParameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val list = workflowManager.listWorkflows(userId)
        call.respondText(
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Workflow.serializer()), list),
            ContentType.Application.Json
        )
    }
}

private fun Route.registerApiWorkflowsPostRoute() {

    post("/api/workflows") {
        val body = call.receiveText()
        val json = Json { ignoreUnknownKeys = true }
        val req = json.decodeFromString<kotlinx.serialization.json.JsonObject>(body)
        val input = parseWorkflowCreateInput(req)
        if (input == null) {
            respondWorkflowCreateError(call, HttpStatusCode.BadRequest, "Missing userId or name")
            return@post
        }

        // 自动创建关联群组（工作流私聊）
        val groupName = "wf_${sanitizeFileName(input.name)}_${System.currentTimeMillis()}"
        val group = com.silk.backend.database.GroupRepository.createGroup(groupName, input.userId)
        if (group == null) {
            respondWorkflowCreateError(call, HttpStatusCode.InternalServerError, "Failed to create workflow group")
            return@post
        }

        if (input.agentType == "silk_chat") {
            // Silk Chat 类型：跳过 bridge/目录校验，无 cdSync，直接返回
            val wf = workflowManager.createWorkflow(
                input.name,
                input.description,
                input.userId,
                group.id,
                input.agentType,
                input.taskFocus
            )
            call.respondText(
                Json.encodeToString(Workflow.serializer(), wf),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
            return@post
        }

        // Claude Code 类型（默认）：需要 bridge 连接和有效工作目录
        // 工作目录是工作流的硬约束：必须由 bridge 验证过的合法路径才能创建。
        // 这样可避免出现"workflow 创建成功但工作目录是 backend 进程的 cwd"这种半生不熟的状态。
        if (input.initialDir.isEmpty()) {
            com.silk.backend.database.GroupRepository.deleteGroup(group.id)
            respondWorkflowCreateError(call, HttpStatusCode.BadRequest, "工作目录不能为空")
            return@post
        }
        if (!isAnyBridgeConnected(input.userId)) {
            com.silk.backend.database.GroupRepository.deleteGroup(group.id)
            respondWorkflowCreateError(call, HttpStatusCode.Conflict, "Bridge 未连接，无法创建工作流。请先启动 Bridge Agent。")
            return@post
        }

        // 信任目录检查
        val bridgeId = resolveBridgeId(input.userId) ?: "unknown"
        if (!trustedDirManager.isTrusted(input.userId, bridgeId, input.initialDir)) {
            com.silk.backend.database.GroupRepository.deleteGroup(group.id)
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("success", kotlinx.serialization.json.JsonPrimitive(false))
                put("errorCode", kotlinx.serialization.json.JsonPrimitive("DIRECTORY_NOT_TRUSTED"))
                put("message", kotlinx.serialization.json.JsonPrimitive("目录 ${input.initialDir} 未被信任。请先确认信任该目录。"))
                put("path", kotlinx.serialization.json.JsonPrimitive(input.initialDir))
                put("bridgeId", kotlinx.serialization.json.JsonPrimitive(bridgeId))
            }
            call.respondText(payload.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }

        val wf = workflowManager.createWorkflow(input.name, input.description, input.userId, group.id, input.agentType, "")

        // cdSync 必须成功才能算创建完成；失败时回滚 group + workflow + CC state，避免遗留无效记录
        val cdResult: AgentRuntime.CdResult = runCatching {
            AgentRuntime.cdSync(
                input.userId,
                "group_${group.id}",
                input.initialDir,
                agentType = resolveActiveAgentType(input.userId) ?: "claude-code"
            )
        }.rethrowRoutingCancellation().getOrElse { e ->
            AgentRuntime.CdResult.Err(e.message ?: "初始目录切换异常")
        }
        if (cdResult is AgentRuntime.CdResult.Err) {
            logger.warn("⚠️ 工作流 {} 初始 /cd 失败，回滚 group + workflow: {}", wf.id, cdResult.reason)
            workflowManager.deleteWorkflow(wf.id, input.userId)
            com.silk.backend.database.GroupRepository.deleteGroup(group.id)
            AgentRuntime.cleanupState(input.userId, "group_${group.id}")
            respondWorkflowCreateError(call, HttpStatusCode.Conflict, "工作目录设置失败：${cdResult.reason}")
            return@post
        }
        // 同步把 workingDir 写到 workflow record，确保返回给前端的 wf 对象包含真实路径
        val resolvedPath = (cdResult as AgentRuntime.CdResult.Ok).resolvedPath
        workflowManager.updateWorkingDir(group.id, resolvedPath)
        // 创建时指定的 permissionMode 持久化到 workflow record（seed 加载时生效）
        if (input.permissionMode.isNotBlank()) {
            workflowManager.updatePermissionMode(group.id, input.permissionMode)
        }
        val wfWithDir = wf.copy(
            workingDir = resolvedPath,
            permissionMode = input.permissionMode,
            updatedAt = System.currentTimeMillis()
        )

        call.respondText(
            Json.encodeToString(Workflow.serializer(), wfWithDir),
            ContentType.Application.Json,
            HttpStatusCode.Created,
        )
    }
}

private fun Route.registerApiWorkflowsWorkflowIdDeleteRoute() {

    delete("/api/workflows/{workflowId}") {
        val workflowId = call.parameters["workflowId"] ?: ""
        val userId = call.request.queryParameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@delete
        }
        // 删除前查出关联的 groupId，用于清理群组和 ChatServer
        val workflow = workflowManager.getWorkflow(workflowId, userId)
        val ok = workflowManager.deleteWorkflow(workflowId, userId)
        if (ok && workflow != null && workflow.groupId.isNotBlank()) {
            groupChatServers.remove(workflow.groupId)
            com.silk.backend.database.GroupRepository.deleteGroup(workflow.groupId)
        }
        call.respondText(
            """{"success":$ok}""",
            ContentType.Application.Json,
            if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound
        )
    }
}

private fun Route.registerApiWorkflowsWorkflowIdPutRoute() {

    put("/api/workflows/{workflowId}") {
        val workflowId = call.parameters["workflowId"] ?: ""
        val body = call.receiveText()
        val json = Json { ignoreUnknownKeys = true }
        val req = json.decodeFromString<kotlinx.serialization.json.JsonObject>(body)
        val userId = req["userId"]?.jsonPrimitive?.content ?: ""
        val newName = req["name"]?.jsonPrimitive?.content?.trim() ?: ""
        if (workflowId.isBlank() || userId.isBlank() || newName.isBlank()) {
            call.respondText(
                """{"success":false,"message":"Missing workflowId, userId, or name"}""",
                ContentType.Application.Json, HttpStatusCode.BadRequest
            )
            return@put
        }
        val updated = workflowManager.renameWorkflow(workflowId, userId, newName)
        if (updated == null) {
            call.respondText(
                """{"success":false,"message":"Workflow not found"}""",
                ContentType.Application.Json, HttpStatusCode.NotFound
            )
            return@put
        }
        call.respondText(
            Json.encodeToString(Workflow.serializer(), updated),
            ContentType.Application.Json
        )
    }
}

private fun Route.registerApiWorkflowsByGroupGroupIdGetRoute() {

    get("/api/workflows/by-group/{groupId}") {
        val groupId = call.parameters["groupId"] ?: ""
        if (groupId.isBlank()) {
            call.respondText("""{"success":false,"message":"Missing groupId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val wf = workflowManager.getWorkflowByGroupId(groupId)
        if (wf == null) {
            call.respondText("""{"success":false,"message":"Workflow not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(
            Json.encodeToString(Workflow.serializer(), wf),
            ContentType.Application.Json
        )
    }
}

private fun Route.registerApiKbTopicsGetRoute() {

    // ==================== Knowledge Base API ====================

    get("/api/kb/topics") {
        val userId = call.request.queryParameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val list = knowledgeBaseManager.listTopics(userId)
        call.respondText(
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(KBTopic.serializer()), list),
            ContentType.Application.Json
        )
    }
}

private fun Route.registerApiKbTopicsPostRoute() {

    post("/api/kb/topics") {
        val body = call.receiveText()
        val req = kbRouteJson.decodeFromString<JsonObject>(body)
        val userId = req["userId"]?.jsonPrimitive?.content
        val name = req["name"]?.jsonPrimitive?.content
        if (userId.isNullOrBlank() || name.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId or name"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val project = req["project"]?.jsonPrimitive?.content ?: ""
        val spaceType = parseKnowledgeSpaceType(req["spaceType"]?.jsonPrimitive?.contentOrNull)
        if (spaceType == null && req["spaceType"] != null) {
            call.respondText("""{"success":false,"message":"Invalid spaceType"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val accessPolicy = parseKbAccessPolicy(req)
        if (accessPolicy == null && req["accessPolicy"] != null) {
            call.respondText("""{"success":false,"message":"Invalid accessPolicy"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val topic = try {
            knowledgeBaseManager.createTopic(
                name = name,
                project = project,
                userId = userId,
                spaceType = spaceType ?: KnowledgeSpaceType.PERSONAL,
                groupId = req["groupId"]?.jsonPrimitive?.contentOrNull,
                accessPolicy = accessPolicy ?: KBAccessPolicy(),
            )
        } catch (e: IllegalArgumentException) {
            call.respondText(
                """{"success":false,"message":"${e.message ?: "Invalid topic request"}"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return@post
        }
        call.respondText(
            Json.encodeToString(KBTopic.serializer(), topic),
            ContentType.Application.Json,
            HttpStatusCode.Created
        )
    }
}

private fun Route.registerApiKbTopicsTopicIdPutRoute() {

    put("/api/kb/topics/{topicId}") {
        val topicId = call.parameters["topicId"] ?: ""
        val body = call.receiveText()
        val req = kbRouteJson.decodeFromString<JsonObject>(body)
        val userId = req["userId"]?.jsonPrimitive?.content
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@put
        }
        val accessPolicy = parseKbAccessPolicy(req)
        if (accessPolicy == null && req["accessPolicy"] != null) {
            call.respondText("""{"success":false,"message":"Invalid accessPolicy"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@put
        }
        val updated = knowledgeBaseManager.updateTopic(
            topicId = topicId,
            userId = userId,
            name = req["name"]?.jsonPrimitive?.contentOrNull,
            project = req["project"]?.jsonPrimitive?.contentOrNull,
            accessPolicy = accessPolicy,
        )
        if (updated == null) {
            call.respondText("""{"success":false,"message":"Topic not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        } else {
            call.respondText(
                Json.encodeToString(KBTopic.serializer(), updated),
                ContentType.Application.Json
            )
        }
    }
}

private fun Route.registerApiKbTopicsTopicIdDeleteRoute() {

    delete("/api/kb/topics/{topicId}") {
        val topicId = call.parameters["topicId"] ?: ""
        val userId = call.request.queryParameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@delete
        }
        val ok = knowledgeBaseManager.deleteTopic(topicId, userId)
        call.respondText(
            """{"success":$ok}""",
            ContentType.Application.Json,
            if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound
        )
    }
}

private fun Route.registerApiKbEntriesGetRoute() {

    get("/api/kb/entries") {
        val topicId = call.request.queryParameters["topicId"]
        val userId = call.request.queryParameters["userId"]
        if (topicId.isNullOrBlank() || userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing topicId or userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val list = knowledgeBaseManager.listEntries(topicId, userId)
        call.respondText(
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(KBEntry.serializer()), list),
            ContentType.Application.Json
        )
    }
}

private fun Route.registerApiKbEntriesEntryIdGetRoute() {

    get("/api/kb/entries/{entryId}") {
        val entryId = call.parameters["entryId"] ?: ""
        val userId = call.request.queryParameters["userId"]
        if (entryId.isBlank() || userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing entryId or userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val entry = knowledgeBaseManager.getEntry(entryId, userId)
        if (entry == null) {
            call.respondText("""{"success":false,"message":"Entry not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(
            Json.encodeToString(KBEntry.serializer(), entry),
            ContentType.Application.Json
        )
    }
}

private fun Route.registerApiKbEntriesPostRoute() {

    post("/api/kb/entries") {
        val body = call.receiveText()
        val req = kbRouteJson.decodeFromString<JsonObject>(body)
        val userId = req["userId"]?.jsonPrimitive?.content
        val topicId = req["topicId"]?.jsonPrimitive?.content
        val title = req["title"]?.jsonPrimitive?.content
        if (userId.isNullOrBlank() || topicId.isNullOrBlank() || title.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing required fields"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val content = req["content"]?.jsonPrimitive?.content ?: ""
        val tags = req["tags"]?.let { tagsEl ->
            runCatching {
                tagsEl.jsonArray.map { it.jsonPrimitive.content }
            }.getOrNull()
        } ?: emptyList()
        val entry = knowledgeBaseManager.createEntry(topicId, title, content, tags, userId)
        if (entry == null) {
            call.respondText("""{"success":false,"message":"Topic not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        } else {
            call.respondText(
                Json.encodeToString(KBEntry.serializer(), entry),
                ContentType.Application.Json,
                HttpStatusCode.Created
            )
        }
    }
}

private fun Route.registerApiKbCapturesPostRoute() {

    post("/api/kb/captures") {
        val body = call.receiveText()
        val req = kbRouteJson.decodeFromString<JsonObject>(body)
        val userId = req["userId"]?.jsonPrimitive?.contentOrNull
        val topicId = req["topicId"]?.jsonPrimitive?.contentOrNull
        val title = req["title"]?.jsonPrimitive?.contentOrNull?.trim()
        val content = req["content"]?.jsonPrimitive?.contentOrNull
        val tags = req["tags"]?.let { tagsEl ->
            runCatching { tagsEl.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
        } ?: emptyList()
        val source = parseKbEntrySource(req)

        if (userId.isNullOrBlank() || topicId.isNullOrBlank() || title.isNullOrBlank() || content.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing required capture fields"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        if (source == null || source.sourceType == KBSourceType.MANUAL) {
            call.respondText("""{"success":false,"message":"Invalid capture source"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }

        val entry = knowledgeBaseManager.createEntry(
            topicId = topicId,
            title = title,
            content = content,
            tags = tags,
            userId = userId,
            status = KBEntryStatus.CANDIDATE,
            source = source,
        )
        if (entry == null) {
            call.respondText("""{"success":false,"message":"Topic not found or write denied"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return@post
        }

        call.respondText(
            Json.encodeToString(KBEntry.serializer(), entry),
            ContentType.Application.Json,
            HttpStatusCode.Created,
        )
    }
}

private fun Route.registerApiKbEntriesEntryIdPutRoute() {

    put("/api/kb/entries/{entryId}") {
        val entryId = call.parameters["entryId"] ?: ""
        val body = call.receiveText()
        val req = kbRouteJson.decodeFromString<JsonObject>(body)
        val userId = req["userId"]?.jsonPrimitive?.content
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@put
        }
        val title = req["title"]?.jsonPrimitive?.content
        val content = req["content"]?.jsonPrimitive?.content
        val tags = req["tags"]?.let { tagsEl ->
            runCatching {
                tagsEl.jsonArray.map { it.jsonPrimitive.content }
            }.getOrNull()
        }
        val status = req["status"]?.jsonPrimitive?.contentOrNull?.let {
            runCatching { KBEntryStatus.valueOf(it) }.getOrNull()
        }
        if (req["status"] != null && status == null) {
            call.respondText("""{"success":false,"message":"Invalid status"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@put
        }
        val updated = knowledgeBaseManager.updateEntry(entryId, title, content, tags, status, userId)
        if (updated == null) {
            call.respondText("""{"success":false,"message":"Entry not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        } else {
            call.respondText(
                Json.encodeToString(KBEntry.serializer(), updated),
                ContentType.Application.Json
            )
        }
    }
}

private fun Route.registerApiKbEntriesEntryIdDeleteRoute() {

    delete("/api/kb/entries/{entryId}") {
        val entryId = call.parameters["entryId"] ?: ""
        val userId = call.request.queryParameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@delete
        }
        val ok = knowledgeBaseManager.deleteEntry(entryId, userId)
        call.respondText(
            """{"success":$ok}""",
            ContentType.Application.Json,
            if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound
        )
    }
}

private fun Route.registerApiKbEntriesEntryIdExportGetRoute() {

    get("/api/kb/entries/{entryId}/export") {
        val entryId = call.parameters["entryId"] ?: ""
        val userId = call.request.queryParameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val entry = knowledgeBaseManager.getEntry(entryId, userId)
        if (entry == null) {
            call.respondText("""{"success":false,"message":"Entry not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return@get
        }
        val topic = knowledgeBaseManager.getTopic(entry.topicId, userId)
        if (topic == null) {
            call.respondText("""{"success":false,"message":"Topic not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return@get
        }
        val markdown = KBObsidianExporter.toMarkdown(topic, entry)
        val vaultPath = KBObsidianExporter.suggestVaultPath(topic, entry)
        call.respondText(
            buildJsonObject {
                put("success", true)
                put("markdown", markdown)
                put("vaultPath", vaultPath)
                put("fileName", "${sanitizeFileName(entry.title)}.md")
            }.toString(),
            ContentType.Application.Json
        )
    }
}

private fun parseKnowledgeSpaceType(raw: String?): KnowledgeSpaceType? {
    if (raw.isNullOrBlank()) return null
    return runCatching { KnowledgeSpaceType.valueOf(raw.trim().uppercase()) }.getOrNull()
}

private fun parseKbAccessPolicy(req: JsonObject): KBAccessPolicy? {
    val accessPolicy = req["accessPolicy"] ?: return null
    return runCatching { kbRouteJson.decodeFromJsonElement(KBAccessPolicy.serializer(), accessPolicy) }.getOrNull()
}

private fun parseKbEntrySource(req: JsonObject): KBEntrySource? {
    val source = req["source"] ?: return null
    return runCatching { kbRouteJson.decodeFromJsonElement(KBEntrySource.serializer(), source) }.getOrNull()
}

private fun Route.registerChatWebSocketRoute() {

    webSocket("/chat") {
        val userId = call.parameters["userId"] ?: UUID.randomUUID().toString()
        val userName = call.parameters["userName"] ?: "User_${userId.take(6)}"
        val groupId = call.parameters["groupId"] ?: "default_room"

        logger.info("👤 用户连接: {} ({}) -> 群组: {}", userName, userId, groupId)

        // 为每个群组获取或创建独立的ChatServer
        val groupChatServer = getGroupChatServer(groupId)

        // 工作流群组自动激活 agent 模式（仅非 silk_chat 类型）。
        // M4 Task 3: 优先取持久化的 activeAgent；缺失则回落到 workflow.agentType（underscore→dash）。
        activateWorkflowAgentIfNeeded(groupChatServer, userId, groupId)

        runCatching {
            groupChatServer.join(userId, userName, this)
            consumeIncomingChatFrames(incoming, groupChatServer)
        }.onFailure { e ->
            if (e is CancellationException) {
                logger.debug("WebSocket 会话正常取消: {} ({})", userName, userId)
            } else {
                logger.error("❌ WebSocket 错误: {}", e.localizedMessage)
            }
        }
        run {
            logger.info("👤 用户断开: {} ({})", userName, userId)
            groupChatServer.leave(userId, userName, this)
        }
    }
}

private fun Route.registerWsAudioDuplexWebSocketRoute() {

    webSocket("/ws/audio-duplex") {
        val sessionId = call.request.queryParameters["sessionId"]
        if (sessionId.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing sessionId"))
            return@webSocket
        }

        val upstreamUrl = AIConfig.AUDIO_DUPLEX_URL.trimEnd('/')
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "/ws/duplex?session_id=$sessionId"

        logger.info("🔊 AudioDuplex proxy: {} -> {}", sessionId, upstreamUrl)

        val httpClient = HttpClient(CIO) {
            install(ClientWebSockets)
        }

        runCatching {
            val serverSession = this

            httpClient.webSocket(upstreamUrl) {
                coroutineScope {
                    val sendToUpstream = launch {
                        try {
                            serverSession.incoming.consumeEach { frame ->
                                if (frame is Frame.Text) {
                                    send(Frame.Text(frame.readText()))
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            logger.debug("AudioDuplex 上游发送协程正常取消: {}", cancelled.message)
                        }
                    }

                    val sendToClient = launch {
                        try {
                            incoming.consumeEach { frame ->
                                if (frame is Frame.Text) {
                                    serverSession.send(Frame.Text(frame.readText()))
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            logger.debug("AudioDuplex 下游发送协程正常取消: {}", cancelled.message)
                        }
                    }

                    sendToUpstream.join()
                    sendToClient.cancelAndJoin()
                }
            }
        }.onFailure { e ->
            if (e is CancellationException) {
                logger.debug("AudioDuplex proxy 正常取消: {}", e.message)
            } else {
                logger.error("❌ AudioDuplex proxy error", e)
                runCatching {
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, e.message ?: "proxy error"))
                }.onFailure { closeError ->
                    logger.warn("AudioDuplex proxy close 失败", closeError)
                }
            }
        }
        httpClient.close()
    }
}
