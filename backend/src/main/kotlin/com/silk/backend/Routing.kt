package com.silk.backend

import com.silk.backend.ai.AIConfig
import com.silk.backend.ai.ContentBlock
import com.silk.backend.ai.DirectModelAgent
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
import com.silk.backend.auth.HuaweiAuthService
import com.silk.backend.auth.JwtProvider
import com.silk.backend.auth.isPublicPath
import com.silk.backend.database.HuaweiWebLoginRequest
import com.silk.backend.database.HuaweiLoginRequest
import com.silk.backend.database.HuaweiBindRequest
import com.silk.backend.database.RefreshTokenRequest
import com.silk.backend.database.LogoutRequest
import com.silk.backend.database.HuaweiAuthResponse
import com.silk.backend.database.WechatLoginRequest
import com.silk.backend.database.WechatAuthResponse
import com.silk.backend.auth.WechatAuthService
import com.silk.backend.database.TokenRefreshResponse
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
import com.silk.backend.database.UserSearchByNameResponse
import com.silk.backend.database.UserSearchItem
import com.silk.backend.database.UserSearchResult
import com.silk.backend.database.UserSettingsRepository
import com.silk.backend.database.UserSettingsResponse
import com.silk.backend.database.UserTodoExtractionDiagnosticsResponse
import com.silk.backend.database.UserTodoRefreshStatusResponse
import com.silk.backend.database.UserTodosResponse
import com.silk.backend.export.ChatObsidianExporter
import com.silk.backend.kb.KBObsidianExporter
import com.silk.backend.kb.ConsolidationReport
import com.silk.backend.kb.GroupAssetsResponse
import com.silk.backend.kb.KnowledgeBaseCopilotRequest
import com.silk.backend.kb.KnowledgeBaseContextPreferenceStore
import com.silk.backend.kb.KnowledgeBaseManager
import com.silk.backend.kb.buildKnowledgeBaseWorkspaceEntries
import com.silk.backend.kb.executeKnowledgeBaseCopilot
import com.silk.backend.models.KBAccessPolicy
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBEntrySource
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBSourceType
import com.silk.backend.models.KBTopic
import com.silk.backend.models.KnowledgeSpaceType
import com.silk.backend.models.Workflow
import com.silk.backend.workflow.WorkflowManager
import com.silk.backend.routes.agentChangesRoutes
import com.silk.backend.routes.asrRoutes
import com.silk.backend.routes.fileRoutes
import com.silk.backend.routes.obsidianRoutes
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
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.request.get as httpGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.contentType
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
private val knowledgeBaseManager: KnowledgeBaseManager get() = KnowledgeBaseManager()

private fun sanitizeFileName(input: String): String =
    input.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]"), "_").take(100)

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

/**
 * 获取或创建指定群组的ChatServer
 */
internal fun getGroupChatServer(groupId: String): ChatServer {
    return groupChatServers.computeIfAbsent(groupId) {
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
 * 广播文件提取内容到指定群组 - 供 FileRoutes 使用
 * 预处理完成后，将 OCR/Vision 提取结果发送到聊天中
 */
suspend fun broadcastExtractedContent(groupId: String, content: String, label: String) {
    val chatServer = groupChatServers[groupId]
    if (chatServer != null) {
        chatServer.broadcastExtractedContent(content, label)
    } else {
        logger.warn("⚠️ [broadcastExtractedContent] 群组 {} 不存在", groupId)
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

@Suppress("TooGenericExceptionCaught", "SwallowedException")
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

        override fun resolveWorkflowId(rawGroupId: String): String? =
            workflowManager.getWorkflowByGroupId(rawGroupId)?.id
    })

    routing {
        coreRoutes()
        agentChangesRoutes()
        authRoutes()
        groupContactRoutes()
        unreadTodoMessageRoutes()
        agentBridgeRoute()
        ccConnectBridgeRoute()
        ccConnectApiRoutes()
        workflowKbRoutes()
        obsidianRoutes()
        chatWebSocketRoute()
        audioDuplexRoute()
    }
}

// 聚合一组独立的 Ktor 路由注册；圈复杂度来自注册的 handler 数量而非真实控制流，各 handler 自身已是独立闭包。
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
private fun Route.coreRoutes() {
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
        
        // 图片代理：通过后端转发 HTTP 图片，解决 Mixed Content 问题
        get("/api/image-proxy") {
            val url = call.request.queryParameters["url"]
            if (url.isNullOrBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                call.respond(HttpStatusCode.BadRequest, "Missing or invalid url parameter")
                return@get
            }
            try {
                val client = HttpClient { expectSuccess = false }
                val resp = client.httpGet(url)
                val bytes = resp.body<ByteArray>()
                val contentType = resp.contentType()?.toString() ?: "image/png"
                client.close()
                call.response.header(HttpHeaders.ContentType, contentType)
                call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
                call.respond(bytes)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, "Failed to fetch image")
            }
        }

        get("/health") {
            call.respondText(
                """{"status":"ok","service":"silk","timestamp":${System.currentTimeMillis()}}""",
                ContentType.Application.Json
            )
        }
        
        get("/users") {
            val users = chatServer.getOnlineUsers()
            call.respond(users)
        }
        
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
            
            try {
                val settings = UserSettingsRepository.getUserSettings(userId)
                // 确保 appAuthToken 存在（旧用户可能没有该字段）
                if (settings.appAuthToken == null) {
                    UserSettingsRepository.getOrCreateAppAuthToken(userId)
                }
                // 重新获取包含完整字段的设置
                val updatedSettings = UserSettingsRepository.getUserSettings(userId)
                call.respond(UserSettingsResponse(true, "获取设置成功", updatedSettings))
            } catch (e: Exception) {
                logger.error("❌ 获取用户设置失败: {}", e.message)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    UserSettingsResponse(false, "获取设置失败: ${e.message}")
                )
            }
        }
        
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
            
            try {
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
            } catch (e: Exception) {
                logger.error("❌ 更新用户设置失败: {}", e.message)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    UserSettingsResponse(false, "更新设置失败: ${e.message}")
                )
            }
        }

        // ==================== CC 设置 API ====================

        // 获取 CC 设置（token + bridge 状态）
        get("/users/{userId}/cc-settings") {
            val userId = call.parameters["userId"] ?: ""
            if (userId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, CcSettingsResponse(false, "用户ID不能为空"))
                return@get
            }
            try {
                val token = UserSettingsRepository.getBridgeToken(userId)
                val connected = isAnyBridgeConnected(userId)
                val bridgeIp = if (connected) getAnyBridgeIp(userId) else null
                call.respond(CcSettingsResponse(true, "ok", token, connected, bridgeIp))
            } catch (e: Exception) {
                logger.error("❌ 获取CC设置失败: {}", e.message)
                call.respond(HttpStatusCode.InternalServerError, CcSettingsResponse(false, "获取失败: ${e.message}"))
            }
        }

        // 生成/重新生成 Bridge Token
        post("/users/{userId}/cc-settings/generate-token") {
            val userId = call.parameters["userId"] ?: ""
            if (userId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, CcSettingsResponse(false, "用户ID不能为空"))
                return@post
            }
            try {
                val token = UserSettingsRepository.generateBridgeToken(userId)
                // 踢掉用旧 token 认证的 ACP 连接
                val acpClosed = AcpRegistry.disconnect(userId)
                if (acpClosed > 0) {
                    logger.info("🔌 Token 重生：关闭 {} 个 ACP 连接", acpClosed)
                }
                val connected = isAnyBridgeConnected(userId)
                val bridgeIp = if (connected) getAnyBridgeIp(userId) else null
                call.respond(CcSettingsResponse(true, "Token 已生成", token, connected, bridgeIp))
            } catch (e: Exception) {
                logger.error("❌ 生成Bridge Token失败: {}", e.message)
                call.respond(HttpStatusCode.InternalServerError, CcSettingsResponse(false, "生成失败: ${e.message}"))
            }
        }

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
            try {
                val resp = DirListingResponse(
                    success = raw["success"]?.jsonPrimitive?.booleanOrNull ?: false,
                    path = raw["path"]?.jsonPrimitive?.contentOrNull ?: "",
                    parent = raw["parent"]?.jsonPrimitive?.contentOrNull,
                    segments = raw["segments"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    separator = raw["separator"]?.jsonPrimitive?.contentOrNull ?: "/",
                    entries = raw["entries"]?.jsonArray?.mapNotNull { el ->
                        val obj = el.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        DirEntry(
                            name = name,
                            isDir = obj["isDir"]?.jsonPrimitive?.booleanOrNull ?: true,
                        )
                    } ?: emptyList(),
                    truncated = raw["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
                    error = raw["error"]?.jsonPrimitive?.contentOrNull,
                )
                call.respond(resp)
            } catch (e: Exception) {
                logger.error("❌ 解析 Bridge dir_listing 失败: {}", e.message)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    DirListingResponse(success = false, error = "解析响应失败: ${e.message}")
                )
            }
        }

        // 直接切换 user+group 的工作目录（不经过聊天消息流，避免 /cd 在聊天中显示气泡）。
        // 请求体（JSON）：{ "groupId": "...", "path": "..." }
        post("/users/{userId}/cc-fs/cd") {
            val userId = call.parameters["userId"] ?: ""
            val reqJson = try {
                Json.parseToJsonElement(call.receiveText()).jsonObject
            } catch (e: Exception) {
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
                    val snap = AgentRuntime.snapshotState(userId, ccGroupId)
                    val bridgeConnected = isAnyBridgeConnected(userId)
                    // 切目录会重置 sessionId（等价于 /new），在聊天里广播一条提示让用户感知
                    val rawGid = if (ccGroupId.startsWith("group_")) ccGroupId.removePrefix("group_") else ccGroupId
                    val descriptor = snap?.agentType?.let { com.silk.backend.agents.core.AgentRegistry.getByType(it) }
                    try {
                        getGroupChatServer(rawGid).broadcast(
                            com.silk.backend.agents.core.AgentMessages.system(
                                "工作目录已切换至：${result.resolvedPath} 会话已重置",
                                agentUserId = descriptor?.agentUserId ?: SilkAgent.AGENT_ID,
                                agentName = descriptor?.displayName ?: SilkAgent.AGENT_NAME,
                            )
                        )
                    } catch (e: Exception) {
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
            }
        }

        // API-driven 切换 agent / 权限模式（更改对话框用，不走聊天消息流）
        post("/users/{userId}/cc-settings/update") {
            val userId = call.parameters["userId"] ?: ""
            val reqJson = try {
                Json.parseToJsonElement(call.receiveText()).jsonObject
            } catch (e: Exception) {
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

            // 切换 agent
            var agentSwitchMsg: String? = null
            if (!newAgent.isNullOrBlank()) {
                // 前端传 underscore form（claude_code），runtime 用 dash form（claude-code）
                val dashType = newAgent.replace('_', '-')
                val descriptor = AgentRuntime.switchAgent(userId, ccGroupId, dashType)
                if (descriptor == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CcStateResponse(success = false, error = "未知 agent: $newAgent")
                    )
                    return@post
                }
                // 持久化到 workflow record（switchAgent 已持久化 runtime 侧）
                workflowManager.updateActiveAgent(groupId, dashType)
                agentSwitchMsg = "已切换到 ${descriptor.displayName}。"
            }

            // 切换权限模式
            if (!newPermMode.isNullOrBlank()) {
                val ok = AgentRuntime.setPermissionMode(userId, ccGroupId, newPermMode)
                if (!ok) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CcStateResponse(success = false, error = "无效权限模式: $newPermMode（INTERACTIVE / ACCEPT_EDITS / BYPASS）")
                    )
                    return@post
                }
                workflowManager.updatePermissionMode(groupId, newPermMode)
            }

            // 广播系统消息通知前端
            val rawGid = if (ccGroupId.startsWith("group_")) ccGroupId.removePrefix("group_") else ccGroupId
            if (agentSwitchMsg != null) {
                try {
                    val snap = AgentRuntime.snapshotState(userId, ccGroupId)
                    val desc = snap?.agentType?.let { com.silk.backend.agents.core.AgentRegistry.getByType(it) }
                    getGroupChatServer(rawGid).broadcast(
                        com.silk.backend.agents.core.AgentMessages.system(
                            agentSwitchMsg,
                            agentUserId = desc?.agentUserId ?: SilkAgent.AGENT_ID,
                            agentName = desc?.displayName ?: SilkAgent.AGENT_NAME,
                        )
                    )
                } catch (e: Exception) {
                    logger.warn("广播 agent 切换提示失败: {}", e.message)
                }
            }

            // 返回最新状态
            val snap = AgentRuntime.snapshotState(userId, ccGroupId)
            val bridgeConnected = isAnyBridgeConnected(userId)
            val descriptor = snap?.agentType?.let { com.silk.backend.agents.core.AgentRegistry.getByType(it) }
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

        post("/users/{userId}/trusted-dirs") {
            val userId = call.parameters["userId"] ?: ""
            val req = try {
                Json.decodeFromString<AddTrustRequest>(call.receiveText())
            } catch (e: Exception) {
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

        delete("/users/{userId}/trusted-dirs") {
            val userId = call.parameters["userId"] ?: ""
            val req = try {
                Json.decodeFromString<AddTrustRequest>(call.receiveText())
            } catch (e: Exception) {
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

        get("/users/{userId}/trusted-dirs") {
            val userId = call.parameters["userId"] ?: ""
            val bridgeId = call.request.queryParameters["bridgeId"]
            val entries = trustedDirManager.listTrusts(userId, bridgeId).map {
                TrustedDirRecordDto(it.bridgeId, it.path, it.trustedAt)
            }
            call.respond(TrustedDirListResponse(entries))
        }

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
                try {
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
                } catch (e: Exception) {
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
        
        // 用户认证API
}

// 聚合一组独立的 Ktor 路由注册；圈复杂度来自注册的 handler 数量而非真实控制流，各 handler 自身已是独立闭包。
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
private fun Route.authRoutes() {
        post("/auth/register") {
            try {
                val request = call.receive<RegisterRequest>()
                val response = AuthService.register(request)
                call.respond(response)
            } catch (e: Exception) {
                logger.error("❌ 注册失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "请求格式错误"))
            }
        }
        
        post("/auth/login") {
            try {
                val request = call.receive<LoginRequest>()
                val response = AuthService.login(request)
                call.respond(response)
            } catch (e: Exception) {
                logger.error("❌ 登录失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "请求格式错误"))
            }
        }
        
        // 验证用户（用于重新认证）
        get("/auth/validate/{userId}") {
            val userId = call.parameters["userId"] ?: ""
            val user = UserRepository.findUserById(userId)

            if (user != null) {
                val appAuthToken = UserSettingsRepository.getOrCreateAppAuthToken(user.id)
                call.respond(AuthResponse(true, "验证成功", user, appAuthToken = appAuthToken))
            } else {
                call.respond(AuthResponse(false, "用户不存在或已失效"))
            }
        }
        
        // ==================== 华为账号认证 API ====================
        
        /**
         * 华为 Web OAuth 登录
         * 前端跳转华为 OAuth 页面 -> 回调 -> 前端发 code -> 后端交换 token
         */
        post("/auth/huawei/web-login") {
            try {
                val request = call.receive<HuaweiWebLoginRequest>()
                val result = HuaweiAuthService.webLogin(request.code, request.redirectUri)
                if (result.success) {
                    call.respond(HuaweiAuthResponse(
                        success = true,
                        message = result.message,
                        user = result.user,
                        accessToken = result.accessToken,
                        refreshToken = result.refreshToken,
                        isNewUser = result.isNewUser
                    ))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, HuaweiAuthResponse(
                        success = false,
                        message = result.message
                    ))
                }
            } catch (e: Exception) {
                logger.error("❌ 华为 Web 登录失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, HuaweiAuthResponse(false, "请求格式错误"))
            }
        }
        
        /**
         * 华为 ID Token 登录（Harmony/Android 原生端）
         */
        post("/auth/huawei/login") {
            try {
                val request = call.receive<HuaweiLoginRequest>()
                val result = HuaweiAuthService.nativeLogin(request.idToken)
                if (result.success) {
                    call.respond(HuaweiAuthResponse(
                        success = true,
                        message = result.message,
                        user = result.user,
                        accessToken = result.accessToken,
                        refreshToken = result.refreshToken,
                        isNewUser = result.isNewUser
                    ))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, HuaweiAuthResponse(
                        success = false,
                        message = result.message
                    ))
                }
            } catch (e: Exception) {
                logger.error("❌ 华为原生登录失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, HuaweiAuthResponse(false, "请求格式错误"))
            }
        }

        /**
         * 华为 OAuth 账号绑定（老账号迁移，需 JWT 鉴权）
         * 老用户登录后，将华为账号绑定到当前 userId
         */
        post("/api/account/bind-huawei") {
            try {
                val request = call.receive<HuaweiBindRequest>()
                // 从 JWT 中获取当前用户 ID
                val authHeader = call.request.headers[HttpHeaders.Authorization]
                val token = authHeader?.removePrefix("Bearer ")?.trim()
                val userId = if (token != null) JwtProvider.verifyAccessToken(token) else null
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, HuaweiAuthResponse(false, "未登录"))
                    return@post
                }
                val result = HuaweiAuthService.bindToUser(request.code, request.redirectUri, userId)
                if (result.success) {
                    call.respond(HuaweiAuthResponse(success = true, message = result.message))
                } else {
                    call.respond(HttpStatusCode.BadRequest, HuaweiAuthResponse(false, result.message))
                }
            } catch (e: Exception) {
                logger.error("❌ 华为账号绑定失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, HuaweiAuthResponse(false, "请求格式错误"))
            }
        }

        // ==================== 微信账号认证 API ====================

        /**
         * 微信 OAuth 登录
         * Android 端微信 SDK 授权 -> 回调 -> 前端发 code -> 后端用 code 交换 token
         */
        post("/auth/wechat/login") {
            try {
                val request = call.receive<WechatLoginRequest>()
                val result = WechatAuthService.login(request.code)
                if (result.success) {
                    call.respond(WechatAuthResponse(
                        success = true,
                        message = result.message,
                        user = result.user,
                        accessToken = result.accessToken,
                        refreshToken = result.refreshToken,
                        isNewUser = result.isNewUser
                    ))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, WechatAuthResponse(
                        success = false,
                        message = result.message
                    ))
                }
            } catch (e: Exception) {
                logger.error("❌ 微信登录失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, WechatAuthResponse(false, "请求格式错误"))
            }
        }

        /**
         * 更新用户资料（昵称）
         */
        put("/users/{userId}/profile") {
            try {
                val userId = call.parameters["userId"] ?: ""
                if (userId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "缺少用户ID"))
                    return@put
                }

                val body = call.receive<Map<String, String>>()
                val newFullName = body["fullName"]?.trim() ?: ""

                if (newFullName.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "昵称不能为空"))
                    return@put
                }
                if (newFullName.length > 50) {
                    call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "昵称不能超过50个字符"))
                    return@put
                }

                val updated = UserRepository.updateFullName(userId, newFullName)
                if (updated != null) {
                    call.respond(SimpleResponse(true, "昵称更新成功"))
                } else {
                    call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "用户不存在"))
                }
            } catch (e: Exception) {
                logger.error("❌ 更新用户资料失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "请求格式错误"))
            }
        }

        /**
         * 注销账号（删除用户及其所有关联数据）
         */
        delete("/users/{userId}/account") {
            try {
                val userId = call.parameters["userId"] ?: ""
                if (userId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "缺少用户ID"))
                    return@delete
                }

                val user = UserRepository.findUserById(userId)
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "用户不存在"))
                    return@delete
                }

                val deleted = UserRepository.deleteUser(userId)
                if (deleted) {
                    call.respond(SimpleResponse(true, "账号已注销"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, SimpleResponse(false, "注销失败，请稍后重试"))
                }
            } catch (e: Exception) {
                logger.error("❌ 注销账号失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "请求格式错误"))
            }
        }

        /**
         * 刷新 Access Token
         */
        post("/auth/refresh") {
            try {
                val request = call.receive<RefreshTokenRequest>()
                val newAccessToken = JwtProvider.refreshAccessToken(request.refreshToken)
                if (newAccessToken != null) {
                    call.respond(TokenRefreshResponse(
                        success = true,
                        message = "Token 已刷新",
                        accessToken = newAccessToken
                    ))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, TokenRefreshResponse(
                        success = false,
                        message = "Refresh Token 无效或已过期"
                    ))
                }
            } catch (e: Exception) {
                logger.error("❌ Token 刷新失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, TokenRefreshResponse(false, "请求格式错误"))
            }
        }
        
        /**
         * 登出（撤销 Refresh Token）
         */
        post("/auth/logout") {
            try {
                val request = call.receive<LogoutRequest>()
                JwtProvider.revokeRefreshToken(request.refreshToken)
                call.respond(AuthResponse(true, "已登出"))
            } catch (e: Exception) {
                logger.error("❌ 登出失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "请求格式错误"))
            }
        }
        
        // 群组管理API
}

// 聚合一组独立的 Ktor 路由注册；圈复杂度来自注册的 handler 数量而非真实控制流，各 handler 自身已是独立闭包。
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
private fun Route.groupContactRoutes() {
        post("/groups/create") {
            try {
                val request = call.receive<CreateGroupRequest>()
                // 禁止创建以 [Silk] 开头的群组（系统保留）
                if (request.groupName.trimStart().startsWith("[Silk]")) {
                    call.respond(HttpStatusCode.BadRequest, GroupResponse(false, "群组名不能以 [Silk] 开头"))
                    return@post
                }
                val response = GroupService.createGroup(request)
                
                if (response.success && response.group != null && request.type == "ccconnect") {
                    val token = com.silk.backend.ccconnect.CcConnectTokenRepository.generateToken(
                        response.group.id, response.group.name
                    )
                    call.respond(response.copy(ccConnectToken = token))
                } else {
                    call.respond(response)
                }
            } catch (e: Exception) {
                logger.error("❌ 创建群组失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, GroupResponse(false, "请求格式错误"))
            }
        }
        
        post("/groups/join") {
            try {
                val request = call.receive<JoinGroupRequest>()
                // 禁止通过邀请码加入 [Silk] 专属对话
                val targetGroup = GroupRepository.findGroupByInvitationCode(request.invitationCode)
                if (targetGroup != null && targetGroup.name.startsWith("[Silk]")) {
                    call.respond(HttpStatusCode.Forbidden, GroupResponse(false, "该群组为专属对话，无法通过邀请码加入"))
                    return@post
                }
                val response = GroupService.joinGroup(request)
                call.respond(response)
            } catch (e: Exception) {
                logger.error("❌ 加入群组失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, GroupResponse(false, "请求格式错误"))
            }
        }
        
        get("/groups/user/{userId}") {
            val userId = call.parameters["userId"] ?: ""
            val response = GroupService.getUserGroups(userId)
            call.respond(response)
        }
        
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
        
        get("/groups/{groupId}/members") {
            val groupId = call.parameters["groupId"] ?: ""
            val members = GroupService.getGroupMembers(groupId)
            // 转换为前端期望的格式
            val apiMembers = members.map { member ->
                val user = UserRepository.findUserById(member.userId)
                GroupMemberApi(
                    id = member.userId,
                    fullName = user?.fullName ?: member.userName,
                    phone = user?.phoneNumber ?: "",
                    role = member.role.name,
                )
            }
            call.respond(GroupMembersResponse(success = true, members = apiMembers))
        }
        
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
        
        // 按名称关键词搜索用户（用于知识库分享等场景）
        get("/api/users/search-by-name") {
            val query = call.request.queryParameters["q"] ?: ""
            if (query.isBlank() || query.length < 2) {
                call.respond(UserSearchByNameResponse(success = false, message = "请输入至少2个字符"))
                return@get
            }
            
            val users = UserRepository.searchUsersByName(query)
            val items = users.map { user ->
                UserSearchItem(
                    id = user.id,
                    fullName = user.fullName,
                    loginName = user.loginName,
                )
            }
            call.respond(UserSearchByNameResponse(success = true, users = items))
        }
        
        // 发送联系人请求（通过电话号码）
        post("/contacts/request") {
            try {
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
            } catch (e: Exception) {
                logger.error("❌ 发送联系人请求失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, ContactResponse(false, "请求格式错误"))
            }
        }

        // 发送联系人请求（通过用户ID，用于从聊天中添加）
        post("/contacts/request-by-id") {
            try {
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
            } catch (e: Exception) {
                logger.error("❌ 发送联系人请求失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, ContactResponse(false, "请求格式错误"))
            }
        }

        // 处理联系人请求（接受/拒绝）
        post("/contacts/handle-request") {
            try {
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
            } catch (e: Exception) {
                logger.error("❌ 处理联系人请求失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, ContactResponse(false, "请求格式错误"))
            }
        }
        
        // 开始/获取私聊会话（与联系人的双人+Silk对话）
        post("/contacts/private-chat") {
            try {
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
            } catch (e: Exception) {
                logger.error("❌ 对话会话失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, PrivateChatResponse(false, "请求格式错误"))
            }
        }
        
        // 添加成员到群组（无需确认）
        post("/groups/{groupId}/add-member") {
            try {
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
                
                // 禁止向 Silk 专属对话添加成员
                if (group.name.startsWith("[Silk]")) {
                    call.respond(SimpleResponse(false, "专属对话无法添加成员"))
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
            } catch (e: Exception) {
                logger.error("❌ 添加成员失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "请求格式错误"))
            }
        }
        
        // 退出群组
        post("/groups/{groupId}/leave") {
            try {
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
            } catch (e: Exception) {
                logger.error("❌ 退出群组失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, LeaveGroupResponse(false, "请求格式错误"))
            }
        }
        
        // 删除群组（仅群主可操作）
        delete("/groups/{groupId}") {
            try {
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
                val (success, message, removedMembers) = GroupRepository.deleteGroupByHost(groupId, request.userId)
                
                if (success) {
                    // 清理群组的ChatServer实例
                    groupChatServers.remove(groupId)
                    logger.info("🗑️ 群组 {} 已被群主 {} 删除", groupId, request.userId)
                    call.respond(SimpleResponse(true, message))
                } else {
                    call.respond(SimpleResponse(false, message))
                }
            } catch (e: Exception) {
                logger.error("❌ 删除群组失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "请求格式错误"))
            }
        }

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
        
        // ==================== 未读消息 API ====================
        
        // 获取用户所有群组的未读消息数
}

// 聚合一组独立的 Ktor 路由注册；圈复杂度来自注册的 handler 数量而非真实控制流，各 handler 自身已是独立闭包。
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
private fun Route.unreadTodoMessageRoutes() {
        get("/api/unread/{userId}") {
            val userId = call.parameters["userId"] ?: ""
            
            // 获取用户的所有群组
            val groups = GroupRepository.getUserGroups(userId)
            val groupIds = groups.map { it.id }
            
            // 获取未读数
            val unreadCounts = UnreadRepository.getUnreadCounts(userId, groupIds)
            
            call.respond(UnreadCountResponse(true, unreadCounts))
        }
        
        // 标记群组已读
        post("/api/unread/mark-read") {
            try {
                val request = call.receive<MarkReadRequest>()
                UnreadRepository.markAsRead(request.userId, request.groupId)
                call.respond(SimpleResponse(true, "已标记为已读"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "请求格式错误"))
            }
        }

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
            try {
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
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "success" to false,
                        "message" to "date 格式错误，应为 yyyy-MM-dd"
                    )
                )
            }
        }

        // ==================== 跨群待办（[Silk] 专属对话侧边能力） ====================
        get("/api/user-todos/{userId}") {
            val userId = call.parameters["userId"] ?: ""
            val items = com.silk.backend.todos.UserTodoStore.load(userId)
                .sortedByDescending { it.updatedAt }
            call.respond(UserTodosResponse(true, "ok", items))
        }

        /**
         * 触发跨群待办同步（GET，无请求体，避免部分客户端 POST JSON 序列化不兼容）。
         */
        get("/api/user-todos/{userId}/refresh") {
            val userId = call.parameters["userId"] ?: ""
            var syncDetail = "ok"
            try {
                kotlinx.coroutines.runBlocking {
                    com.silk.backend.todos.GroupTodoExtractionService.refreshTodosForUser(userId)
                }
            } catch (e: Exception) {
                println("❌ 待办同步异常 userId=${userId.take(8)}…: ${e.message}")
                e.printStackTrace()
                syncDetail = "同步异常，已返回已有列表: ${e.message?.take(120)}"
            }
            val items = com.silk.backend.todos.UserTodoStore.load(userId)
                .sortedByDescending { it.updatedAt }
            call.respond(UserTodosResponse(true, syncDetail, items))
        }

        /**
         * 后台异步刷新：快速返回任务状态，避免客户端长时间阻塞。
         */
        post("/api/user-todos/{userId}/refresh-async/start") {
            val userId = call.parameters["userId"] ?: ""
            val status = com.silk.backend.todos.UserTodoRefreshAsyncManager.start(userId)
            call.respond(status)
        }

        /**
         * 查询后台异步刷新状态。
         */
        get("/api/user-todos/{userId}/refresh-async/status") {
            val userId = call.parameters["userId"] ?: ""
            val status = com.silk.backend.todos.UserTodoRefreshAsyncManager.status(userId)
            call.respond(status)
        }

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

        put("/api/user-todos/item") {
            try {
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
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, UserTodosResponse(false, "请求格式错误"))
            }
        }

        delete("/api/user-todos/item") {
            try {
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
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, UserTodosResponse(false, "请求格式错误"))
            }
        }

        post("/api/user-todos/refresh") {
            val userId = try {
                call.receive<RefreshUserTodosRequest>().userId
            } catch (e: Exception) {
                println("❌ 刷新待办请求体解析失败: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    UserTodosResponse(false, "请求格式错误: ${e.message}", emptyList())
                )
                return@post
            }
            var syncDetail = "已根据各群记录刷新待办"
            try {
                kotlinx.coroutines.runBlocking {
                    com.silk.backend.todos.GroupTodoExtractionService.refreshTodosForUser(userId)
                }
            } catch (e: Exception) {
                println("❌ 待办同步异常: ${e.message}")
                e.printStackTrace()
                syncDetail = "同步异常，已返回已有列表: ${e.message?.take(120)}"
            }
            val items = com.silk.backend.todos.UserTodoStore.load(userId)
                .sortedByDescending { it.updatedAt }
            call.respond(UserTodosResponse(true, syncDetail, items))
        }
        
        // 文件上传/下载 API
        fileRoutes()

        // 语音识别 (ASR) API
        asrRoutes()
        
        // ==================== 与 Silk 直接对话 API ====================
        post("/api/silk-private-chat") {
            try {
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
            } catch (e: Exception) {
                logger.error("❌ 创建 Silk 私聊失败: {}", e.message)
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, PrivateChatResponse(false, "请求格式错误"))
            }
        }
        
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

        post("/api/messages/send") {
            try {
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
            } catch (e: Exception) {
                logger.error("❌ 发送消息失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "发送失败: ${e.message}"))
            }
        }
        
        // ==================== 消息撤回 API ====================
        post("/api/messages/recall") {
            try {
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
            } catch (e: Exception) {
                logger.error("❌ 撤回消息失败: {}", e.message)
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "撤回失败: ${e.message}"))
            }
        }
        
        // ==================== 消息删除 API ====================
        post("/api/messages/delete") {
            try {
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
            } catch (e: Exception) {
                logger.error("❌ 删除消息失败: {}", e.message)
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "删除失败: ${e.message}"))
            }
        }
        
        // ==================== Agent Bridge WebSocket (ACP) ====================

}

// 聚合一组独立的 Ktor 路由注册；圈复杂度来自注册的 handler 数量而非真实控制流，各 handler 自身已是独立闭包。
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun Route.agentBridgeRoute() {
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
            val client = try {
                AcpRegistry.acceptConnection(
                    userId = userId,
                    agentType = agentType,
                    session = this,
                    remoteIp = remoteIp,
                    scope = scope,
                )
            } catch (e: Exception) {
                logger.error("❌ Agent Bridge acceptConnection 失败: {}", e.message)
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "accept failed"))
                return@webSocket
            }

            if (client == null) {
                logger.error("❌ Agent Bridge acceptConnection 返回 null")
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "accept failed"))
                return@webSocket
            }

            try {
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
            } catch (e: Exception) {
                logger.error("[Agent Bridge] initialize 失败: {}", e.message)
                AcpRegistry.unregister(userId, agentType)
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "initialize failed"))
                return@webSocket
            }

            try {
                // IMPORTANT: Do NOT consume `incoming` here.
                // AcpClient.receiveLoop() handles all incoming frames via AcpWebSocketTransport
                // which consumes from the same Ktor channel. Two consumers race and lose frames.
                // Just suspend until connection closes.
                closeReason.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal: session scope cancelled on connection close
            } catch (e: Exception) {
                logger.error("❌ Agent Bridge WebSocket 错误: userId={}, agentType={}, error={}", userId, agentType, e.message)
            } finally {
                logger.info("🔌 Agent Bridge 断开: userId={}, agentType={}", userId, agentType)
                scope.cancel()
                AcpRegistry.unregister(userId, agentType)
                AgentRuntime.handleAgentDisconnect(userId, agentType)
            }
        }

        // ==================== cc-connect Bridge WebSocket ====================

}

// cc-connect 适配器 WebSocket：单 handler 内驱动完整流式协议（hello/reply/stream/status/done），
// 控制流刚合并并经人工验证，强行拆分有破坏转发行为的实际风险，故抑制 CyclomaticComplexMethod。
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
private fun Route.ccConnectBridgeRoute() {
        webSocket("/ccconnect-bridge") {
            val token = call.request.queryParameters["token"]
            if (token.isNullOrBlank()) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing token"))
                return@webSocket
            }
            val groupId = com.silk.backend.ccconnect.CcConnectTokenRepository.findGroupIdByToken(token)
            if (groupId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid token"))
                return@webSocket
            }

            val groupName = GroupRepository.findGroupById(groupId)?.name ?: groupId
            logger.info("[CcConnect] WS connected: groupId={}", groupId)

            // Wait for hello handshake
            val helloFrame = incoming.receive()
            val helloRaw = (helloFrame as? Frame.Text)?.readText() ?: run {
                close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "expected text frame"))
                return@webSocket
            }

            val hello = try {
                com.silk.backend.ccconnect.protocolJson.decodeFromString(
                    com.silk.backend.ccconnect.HelloMessage.serializer(), helloRaw
                )
            } catch (_: Exception) {
                val ack = com.silk.backend.ccconnect.HelloAckMessage(ok = false, error = "invalid hello")
                send(Frame.Text(com.silk.backend.ccconnect.protocolJson.encodeToString(
                    com.silk.backend.ccconnect.HelloAckMessage.serializer(), ack
                )))
                close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "invalid hello"))
                return@webSocket
            }

            val meta = com.silk.backend.ccconnect.CcConnectConnectionMeta(
                groupId = groupId,
                project = hello.project,
                agentType = hello.agentType,
                cwd = hello.cwd,
            )
            com.silk.backend.ccconnect.CcConnectRegistry.register(groupId, this, meta)

            val ack = com.silk.backend.ccconnect.HelloAckMessage(
                ok = true, groupId = groupId, groupName = groupName
            )
            send(Frame.Text(com.silk.backend.ccconnect.protocolJson.encodeToString(
                com.silk.backend.ccconnect.HelloAckMessage.serializer(), ack
            )))

            val chatServer = getGroupChatServer(groupId)

            val statusMsg = Message(
                id = java.util.UUID.randomUUID().toString(),
                userId = "system",
                userName = "cc-connect",
                content = "cc-connect (${hello.agentType}) connected — project: ${hello.project}",
                timestamp = System.currentTimeMillis(),
                type = MessageType.SYSTEM,
                isTransient = true,
            )
            chatServer.broadcast(statusMsg)

            // Phased turn aggregation: classify reply messages into
            // thinking / tool / answer sections and build structured
            // markdown so the frontend renders a Cursor-like display.
            var turnActive = false
            val thinkingParts = mutableListOf<String>()
            val toolParts = mutableListOf<String>()
            var answerText = ""
            var gotReplyAfterStream = false
            // Streaming block state: ordered list of content blocks as they arrive in real time.
            // Each reply_stream fragment is scanned for 💭/🔧 emoji markers that delimit
            // thinking/tool_use blocks, emitting updated contentBlocks with every fragment.
            val streamBlockTypes = mutableListOf<String>()        // "thinking", "tool_use", "text"
            val streamBlockContent = mutableListOf<StringBuilder>()
            var finalBlocksSent = false
            var pendingFinalBlocks = emptyList<com.silk.backend.ai.ContentBlock>()  // contentBlocks from done:true, for status:idle broadcast
            var lastStreamBlocks = emptyList<com.silk.backend.ai.ContentBlock>()    // last streaming blocks, for question context
            var preQuestionBlocks = emptyList<com.silk.backend.ai.ContentBlock>()   // blocks saved before question, merged into final
            var expectFinalizeReply = false   // done=true sent → Finalize fallback reply incoming, skip it

            // convertImageUrls detects plain HTTP(S) image URLs in text and wraps them
            // in markdown image syntax so the frontend renders them inline.
            // HTTP images are proxied through /api/image-proxy by the frontend.
            fun convertImageUrls(text: String): String {
                val imageUrlRegex = Regex(
                    """https?://[^\s"'<>)]++\.(?:png|jpg|jpeg|gif|webp)(?:\?[^\s"'<>)]*+)?(?:&[^\s"'<>)]*+)*+(?!\))""",
                    RegexOption.IGNORE_CASE
                )
                return imageUrlRegex.replace(text) { match ->
                    val url = match.value
                    "![]($url)"
                }
            }

            /** 检测 cc-connect 回复是否为权限请求提示，若是则返回交互式按钮选项。 */
            fun detectPermissionRequest(text: String): List<InteractiveOption>? {
                val trimmed = text.trim()
                // 中文权限提示
                if (trimmed.contains("允许") && trimmed.contains("拒绝") && trimmed.contains("允许所有")) {
                    return listOf(
                        InteractiveOption(label = "✅ 允许", value = "允许"),
                        InteractiveOption(label = "❌ 拒绝", value = "拒绝"),
                        InteractiveOption(label = "🔄 允许所有", value = "允许所有"),
                    )
                }
                // 英文权限提示
                if (trimmed.contains("allow", ignoreCase = true) &&
                    trimmed.contains("deny", ignoreCase = true) &&
                    trimmed.contains("allow all", ignoreCase = true)
                ) {
                    return listOf(
                        InteractiveOption(label = "✅ Allow", value = "allow"),
                        InteractiveOption(label = "❌ Deny", value = "deny"),
                        InteractiveOption(label = "🔄 Allow All", value = "allow all"),
                    )
                }
                return null
            }

            fun buildStructuredContent(collapseTools: Boolean = false): String {
                val sb = StringBuilder()
                sb.append("<!--CC_TURN-->\n")

                val hasPostThinkingContent = toolParts.isNotEmpty() || answerText.isNotEmpty()

                if (thinkingParts.isNotEmpty()) {
                    val joined = thinkingParts.joinToString("\n\n")
                    if (hasPostThinkingContent) {
                        sb.append(joined)
                        sb.append("\n<!--THINKING_END-->\n\n")
                    } else {
                        sb.append(joined)
                    }
                }

                if (toolParts.isNotEmpty()) {
                    sb.append(toolParts.joinToString("\n\n"))
                    if (collapseTools) {
                        sb.append("\n<!--TOOLS_END-->\n\n")
                    } else if (answerText.isNotEmpty()) {
                        sb.append("\n\n---\n\n")
                    }
                }

                if (answerText.isNotEmpty()) {
                    sb.append(convertImageUrls(answerText))
                }

                return sb.toString()
            }

            fun buildContentBlockList(preBlocks: List<ContentBlock> = emptyList()): List<ContentBlock> {
                val blocks = mutableListOf<ContentBlock>()
                var index = 0

                // Prepend non-text pre-blocks (thinking/tool_use from pre-question context,
                // preserved across permission resolution / question-answer boundaries)
                for (b in preBlocks) {
                    if (b.type != "text") {
                        blocks.add(b.copy(index = index++))
                    }
                }

                val hasPostThinkingContent = toolParts.isNotEmpty() || answerText.isNotEmpty()
                if (thinkingParts.isNotEmpty()) {
                    blocks.add(ContentBlock(
                        index = index++,
                        type = "thinking",
                        content = thinkingParts.joinToString("\n\n"),
                        isComplete = hasPostThinkingContent
                    ))
                }
                for (toolPart in toolParts) {
                    val toolName = toolPart.lines().firstOrNull()
                        ?.removePrefix("🔧")
                        ?.trim()
                        ?.replace("**", "")
                        ?.trim()
                        ?.take(40) ?: ""
                    blocks.add(ContentBlock(
                        index = index++,
                        type = "tool_use",
                        content = toolPart,
                        isComplete = true,
                        toolName = toolName
                    ))
                }
                if (answerText.isNotEmpty()) {
                    blocks.add(ContentBlock(
                        index = index++,
                        type = "text",
                        content = answerText,
                        isComplete = true
                    ))
                }
                return blocks
            }

            fun isLikelyQuestion(text: String): Boolean {
                if (text.isBlank()) return false
                if (text.contains("?") || text.contains("？")) return true
                if (text.contains(Regex("""[（(]\d+[)）]"""))) return true
                if (text.contains("选项") || text.contains("choose", true) || text.contains("select", true)) return true
                if (text.contains(Regex("""\d+\.""")) && text.length < 500) return true
                // Chinese permission/confirmation patterns
                if (text.contains("请回复") || text.contains("请选择")) return true
                if (text.contains("允许") || text.contains("拒绝")) return true
                if (text.contains("是否") || text.contains("确认")) return true
                if (text.contains("权限") || text.contains("等待")) return true
                if (text.contains("继续执行")) return true
                return false
            }


            val ccUserName = com.silk.backend.ccconnect.agentTriggerName(hello.agentType).replaceFirstChar { it.uppercaseChar() }

            try {
                @Suppress("LoopWithTooManyJumpStatements")
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    val msgType = com.silk.backend.ccconnect.parseMessageType(text) ?: continue

                    when (msgType) {
                        "reply" -> {
                            val reply = com.silk.backend.ccconnect.protocolJson.decodeFromString(
                                com.silk.backend.ccconnect.ReplyMessage.serializer(), text
                            )
                            // ── 从等待回答恢复：清累积器，继续新段落 ──
                            if (com.silk.backend.ccconnect.CcConnectRegistry.isWaitingForInput(groupId)) {
                                thinkingParts.clear()
                                toolParts.clear()
                                answerText = ""
                                gotReplyAfterStream = false
                                streamBlockTypes.clear()
                                streamBlockContent.clear()
                                com.silk.backend.ccconnect.CcConnectRegistry.clearWaitingForInput(groupId)
                                turnActive = true
                                logger.info("[CcConnect][{}] answer received → continuation (reply)", groupId)
                            }
                            if (turnActive) {
                                // ── 结构化最终块已发送：引擎 Finalize 回退的 reply ──
                                // expectFinalizeReply 在 done=true 时设置，独立于 finalBlocksSent。
                                // 避免 status=idle 清除 finalBlocksSent 后 reply 被当作普通消息广播。
                                if (expectFinalizeReply) {
                                    expectFinalizeReply = false
                                    val rawContent = reply.content.trimStart()
                                    if (rawContent.startsWith("💭") || rawContent.startsWith("🔧")) {
                                        // 工具/思考块 → 正常处理（continue 后落到下方 when）
                                        finalBlocksSent = false
                                    } else {
                                        finalBlocksSent = false
                                        answerText = convertImageUrls(reply.content)
                                        gotReplyAfterStream = true
                                        logger.info("[CcConnect][{}] expectFinalizeReply → skip reply (Finalize fallback)", groupId)
                                        continue
                                    }
                                }
                                val content = reply.content.trimStart()
                                when {
                                    content.startsWith("\uD83D\uDCAD") -> thinkingParts.add(reply.content)
                                    content.startsWith("\uD83D\uDD27") -> toolParts.add(reply.content)
                                    else -> {
                                        answerText = reply.content
                                        gotReplyAfterStream = true
                                    }
                                }
                                val msg = Message(
                                    id = java.util.UUID.randomUUID().toString(),
                                    userId = "cc-connect",
                                    userName = ccUserName,
                                    content = buildStructuredContent(),
                                    timestamp = System.currentTimeMillis(),
                                    type = MessageType.TEXT,
                                    isTransient = true,
                                    isIncremental = false,
                                    contentBlocks = buildContentBlockList(preBlocks = preQuestionBlocks),
                                )
                                chatServer.broadcast(msg)
                            } else {
                                val replyText = convertImageUrls(reply.content)
                                // 检测是否为权限请求提示，自动生成交互式按钮
                                val permOptions = detectPermissionRequest(replyText)
                                val msg = Message(
                                    id = java.util.UUID.randomUUID().toString(),
                                    userId = "cc-connect",
                                    userName = ccUserName,
                                    content = replyText,
                                    timestamp = System.currentTimeMillis(),
                                    type = MessageType.TEXT,
                                    interactiveOptions = permOptions,
                                )
                                chatServer.broadcast(msg)
                                // 非 turn 中收到的 reply 通常是错误/日志或已完成回复，标记空闲让排队消息继续
                                logger.info("[CcConnect][{}] non-turn reply → markIdle", groupId)
                                com.silk.backend.ccconnect.CcConnectRegistry.markIdle(groupId)
                            }
                        }
                        "reply_stream" -> {
                            val stream = com.silk.backend.ccconnect.protocolJson.decodeFromString(
                                com.silk.backend.ccconnect.ReplyStreamMessage.serializer(), text
                            )
                            // ── 结构化事件通道：优先使用引擎直接发来的 contentBlocks ──
                            val rawJson = com.silk.backend.ccconnect.protocolJson.parseToJsonElement(text).jsonObject
                            val engineContentBlocks = rawJson["contentBlocks"]?.jsonArray
                            if (engineContentBlocks != null) {
                                val blocks = engineContentBlocks.map { elem ->
                                    val obj = elem.jsonObject
                                    com.silk.backend.ai.ContentBlock(
                                        index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                        type = obj["type"]?.jsonPrimitive?.content ?: "",
                                        content = obj["content"]?.jsonPrimitive?.content ?: "",
                                        isComplete = obj["isComplete"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                                        toolName = obj["toolName"]?.jsonPrimitive?.content ?: "",
                                    )
                                }
                                // ── 在 question 之后，引擎的 UpdateStructured 可能只发 answer text ──
                                // （thinking/tools 为空，且旧 thinking 未进入 completedThinking）。
                                // 此时合并 preQuestionBlocks 中非 text 块，确保前序内容不消失。
                                val displayBlocks: List<com.silk.backend.ai.ContentBlock>
                                if (preQuestionBlocks.isNotEmpty()) {
                                    val hasNonText = blocks.any { it.type == "thinking" || it.type == "tool_use" }
                                    if (hasNonText) {
                                        // 引擎已赶上，直接使用其 blocks，后续不再需要 preQuestionBlocks
                                        preQuestionBlocks = emptyList()
                                        displayBlocks = blocks
                                    } else {
                                        // 引擎只发了 text → 补充前序 thinking/tool 块
                                        val preNonText = preQuestionBlocks.filter { it.type != "text" }
                                        if (preNonText.isNotEmpty()) {
                                            displayBlocks = (preNonText + blocks).mapIndexed { i, b -> b.copy(index = i) }
                                        } else {
                                            displayBlocks = blocks
                                        }
                                    }
                                } else {
                                    displayBlocks = blocks
                                }
                                // Preserve streaming blocks for question context
                                if (!stream.done) lastStreamBlocks = displayBlocks
                                // Extract answer text for content field and question detection
                                var contentField = stream.content
                                if (stream.done) {
                                    for (block in blocks) {
                                        if (block.type == "text" && block.content.isNotBlank()) {
                                            contentField = convertImageUrls(block.content)
                                            answerText = convertImageUrls(block.content)
                                        }
                                    }
                                }
                                val msg = Message(
                                    id = java.util.UUID.randomUUID().toString(),
                                    userId = "cc-connect",
                                    userName = ccUserName,
                                    content = contentField,
                                    timestamp = System.currentTimeMillis(),
                                    type = MessageType.TEXT,
                                    isTransient = true,
                                    isIncremental = false,
                                    contentBlocks = displayBlocks,
                                )
                                chatServer.broadcast(msg)
                                if (stream.done) {
                                    streamBlockTypes.clear()
                                    streamBlockContent.clear()
                                    pendingFinalBlocks = displayBlocks
                                    finalBlocksSent = true
                                    expectFinalizeReply = true
                                }
                                continue
                            }
                            // ── 从等待回答恢复：清累积器，继续新段落 ──
                            if (com.silk.backend.ccconnect.CcConnectRegistry.isWaitingForInput(groupId)) {
                                thinkingParts.clear()
                                toolParts.clear()
                                answerText = ""
                                gotReplyAfterStream = false
                                streamBlockTypes.clear()
                                streamBlockContent.clear()
                                com.silk.backend.ccconnect.CcConnectRegistry.clearWaitingForInput(groupId)
                                turnActive = true
                                logger.info("[CcConnect][{}] answer received → continuation (stream)", groupId)
                            }
                            if (turnActive) {
                                // silk.go now sends structured contentBlocks directly via the
                                // engineContentBlocks check above. This is a safety fallback:
                                // just send raw content as plain text. Include preQuestionBlocks
                                // to prevent frontend from clearing transient display during
                                // question-answer-handoff.
                                val fallbackBlocks = if (preQuestionBlocks.isNotEmpty()) {
                                    preQuestionBlocks
                                } else {
                                    emptyList()
                                }
                                chatServer.broadcast(Message(
                                    id = java.util.UUID.randomUUID().toString(),
                                    userId = "cc-connect",
                                    userName = ccUserName,
                                    content = stream.content,
                                    timestamp = System.currentTimeMillis(),
                                    type = MessageType.TEXT,
                                    isTransient = true,
                                    isIncremental = false,
                                    contentBlocks = fallbackBlocks.ifEmpty { null },
                                ))
                            } else {
                                val isIncremental = stream.incremental ?: !stream.done
                                val msg = Message(
                                    id = java.util.UUID.randomUUID().toString(),
                                    userId = "cc-connect",
                                    userName = ccUserName,
                                    content = stream.content,
                                    timestamp = System.currentTimeMillis(),
                                    type = MessageType.TEXT,
                                    isTransient = !stream.done,
                                    isIncremental = isIncremental,
                                )
                                chatServer.broadcast(msg)
                            }
                        }
                        "reply_images" -> {
                            // cc-connect agent generated images during this turn (e.g. PNG/SVG from tools).
                            // Render as Markdown data URI images — the frontend markdown renderer
                            // handles ![](data:image/png;base64,...) natively.
                            try {
                                val replyImages = com.silk.backend.ccconnect.protocolJson.decodeFromString(
                                    com.silk.backend.ccconnect.ReplyImagesMessage.serializer(), text
                                )
                                logger.info("[CcConnect][{}] reply_images: received {} image(s)", groupId, replyImages.images.size)

                                for (img in replyImages.images) {
                                    try {
                                        val content = "![](data:${img.mimeType};base64,${img.data})"
                                        val imgMsg = Message(
                                            id = java.util.UUID.randomUUID().toString(),
                                            userId = "cc-connect",
                                            userName = ccUserName,
                                            content = content,
                                            timestamp = System.currentTimeMillis(),
                                            type = MessageType.TEXT,
                                        )
                                        chatServer.broadcast(imgMsg)
                                        logger.info("[CcConnect][{}] reply_images: broadcast {} (mime={}, base64_len={})",
                                            groupId, img.fileName, img.mimeType, img.data.length)
                                    } catch (e: Exception) {
                                        logger.warn("[CcConnect][{}] failed to broadcast reply image {}: {}",
                                            groupId, img.fileName, e.message)
                                    }
                                }
                            } catch (e: Exception) {
                                logger.warn("[CcConnect][{}] failed to parse reply_images: {}", groupId, e.message)
                            }
                        }
                        "question" -> {
                            val question = com.silk.backend.ccconnect.protocolJson.decodeFromString(
                                com.silk.backend.ccconnect.QuestionMessage.serializer(), text
                            )
                            logger.info("[CcConnect][{}] question received: options={}", groupId, question.options.size)

                            // 将 button rows 展平为 InteractiveOption 列表（所有按钮平铺）
                            val interactiveOptions = question.options.flatMap { row ->
                                row.row.map { btn ->
                                    InteractiveOption(label = btn.label, value = btn.value)
                                }
                            }

                            // Convert streaming blocks to thinkingParts/toolParts for the question context
                            // Strategy: prefer the engine's structured contentBlocks (lastStreamBlocks),
                            // fall back to emoji-based streamBlockTypes/streamBlockContent for non-structured path.
                            val blocks: MutableList<ContentBlock>
                            if (lastStreamBlocks.isNotEmpty()) {
                                // Structured path: preserve what was streaming before question
                                // Save pre-question blocks separately so they survive the answer→continuation boundary
                                preQuestionBlocks = lastStreamBlocks.toList()
                                blocks = lastStreamBlocks.toMutableList()
                                lastStreamBlocks = emptyList()
                            } else if (pendingFinalBlocks.isNotEmpty()) {
                                // Stream already done=true before question: use pendingFinalBlocks
                                preQuestionBlocks = pendingFinalBlocks.toList()
                                blocks = pendingFinalBlocks.toMutableList()
                            } else {
                                // Non-structured path: rebuild from emoji markers
                                if (streamBlockTypes.isNotEmpty()) {
                                    for (i in streamBlockTypes.indices) {
                                        when (streamBlockTypes[i]) {
                                            "thinking" -> thinkingParts.add(streamBlockContent[i].toString())
                                            "tool_use" -> toolParts.add(streamBlockContent[i].toString())
                                            "text" -> {} // preceding text is superseded by question
                                        }
                                    }
                                    streamBlockTypes.clear()
                                    streamBlockContent.clear()
                                }
                                blocks = buildContentBlockList(preBlocks = preQuestionBlocks).toMutableList()
                            }
                            // Always include the question text as a text block (frontend renders
                            // contentBlocks + interactiveOptions, but NOT message.content)
                            blocks.add(ContentBlock(
                                index = blocks.size + 1,
                                type = "text",
                                content = question.content,
                                isComplete = true
                            ))
                            val msg = Message(
                                id = java.util.UUID.randomUUID().toString(),
                                userId = "cc-connect",
                                userName = ccUserName,
                                content = question.content,
                                timestamp = System.currentTimeMillis(),
                                type = MessageType.TEXT,
                                isTransient = true,
                                contentBlocks = blocks,
                                interactiveOptions = interactiveOptions.ifEmpty { null },
                            )
                            chatServer.broadcast(msg)
                            // 引擎已阻塞等待回答，设置 waitingForInput
                            com.silk.backend.ccconnect.CcConnectRegistry.setWaitingForInput(groupId)
                            expectFinalizeReply = false
                            logger.info("[CcConnect][{}] question broadcast with {} options: {}, waitingForInput set", groupId, interactiveOptions.size, interactiveOptions.map { it.label })
                        }
                        "status" -> {
                            val status = com.silk.backend.ccconnect.protocolJson.decodeFromString(
                                com.silk.backend.ccconnect.StatusMessage.serializer(), text
                            )
                            when (status.state) {
                                "thinking" -> {
                                    // ── 如果 waitingForInput, 清除（新段落开始） ──
                                    if (com.silk.backend.ccconnect.CcConnectRegistry.isWaitingForInput(groupId)) {
                                        com.silk.backend.ccconnect.CcConnectRegistry.clearWaitingForInput(groupId)
                                        logger.info("[CcConnect][{}] clearWaitingForInput (status=thinking)", groupId)
                                    }
                                    logger.info("[CcConnect][{}] status=thinking, turnActive={}→true", groupId, turnActive)
                                    turnActive = true
                                    thinkingParts.clear()
                                    toolParts.clear()
                                    answerText = ""
                                    gotReplyAfterStream = false
                                    streamBlockTypes.clear()
                                    streamBlockContent.clear()
                                    finalBlocksSent = false
                                    expectFinalizeReply = false
                                    pendingFinalBlocks = emptyList()
                                    lastStreamBlocks = emptyList()
                                    // 保留 preQuestionBlocks：权限回复后 engine 发 status=thinking 开启新段落，
                                    // 但 pre-question blocks 仍需在后续 reply_stream 中合并显示，直到 engine 自己产生新块。
                                    chatServer.broadcastSystemStatus("Thinking...")
                                }
                                "tool_use" -> {
                                    chatServer.broadcastSystemStatus(
                                        "Using tool: ${status.tool ?: ""}${if (status.detail != null) " — ${status.detail}" else ""}"
                                    )
                                }
                                "idle" -> {
                                    logger.info("[CcConnect][{}] status=idle, turnActive={}", groupId, turnActive)
                                    if (turnActive) {
                                        if (finalBlocksSent || pendingFinalBlocks.isNotEmpty()) {
                                            // 结构化流路径：广播最终消息（含 thinking / tool / text 完整 blocks）
                                            var blocks = pendingFinalBlocks.also { pendingFinalBlocks = emptyList() }
                                            // 如果 post-answer 没有 done=true blocks（引擎回答后没有继续输出），
                                            // 使用 pre-question blocks 作为最终消息，确保提问前的内容不丢失。
                                            if (blocks.isEmpty() && preQuestionBlocks.isNotEmpty()) {
                                                blocks = preQuestionBlocks
                                                preQuestionBlocks = emptyList()
                                            }
                                            val finalContent = convertImageUrls(answerText).ifEmpty {
                                                blocks.firstOrNull { it.type == "text" }?.content ?: ""
                                            }
                                            if (blocks.isNotEmpty() || finalContent.isNotBlank()) {
                                                val msg = Message(
                                                    id = java.util.UUID.randomUUID().toString(),
                                                    userId = "cc-connect",
                                                    userName = ccUserName,
                                                    content = finalContent,
                                                    timestamp = System.currentTimeMillis(),
                                                    type = MessageType.TEXT,
                                                    contentBlocks = blocks,
                                                )
                                                chatServer.broadcast(msg)
                                            }
                                            finalBlocksSent = false
                                        } else {
                                            // 旧路径：直接使用已累积的 thinkingParts/toolParts/answerText
                                            // （reply/reply_stream 处理器已通过 emoji 标记解析填充它们）
                                            val finalContent = buildStructuredContent(collapseTools = true)
                                            if (finalContent.isNotBlank() &&
                                                finalContent != "<!--CC_TURN-->\n") {
                                                val msg = Message(
                                                    id = java.util.UUID.randomUUID().toString(),
                                                    userId = "cc-connect",
                                                    userName = ccUserName,
                                                    content = finalContent,
                                                    timestamp = System.currentTimeMillis(),
                                                    type = MessageType.TEXT,
                                                    contentBlocks = buildContentBlockList(preBlocks = preQuestionBlocks),
                                                )
                                                chatServer.broadcast(msg)
                                            }
                                        }
                                        // ── 检测 AI 是否在提问 → 保持会话存活 ──
                                        val isQuestion = isLikelyQuestion(answerText) || expectFinalizeReply ||
                                            com.silk.backend.ccconnect.CcConnectRegistry.isWaitingForInput(groupId)
                                        if (isQuestion) {
                                            logger.info("[CcConnect][{}] question detected → waitingForAnswer, answers={}, tools={}",
                                                groupId, answerText.length, toolParts.size)
                                            com.silk.backend.ccconnect.CcConnectRegistry.setWaitingForInput(groupId)
                                            // 保持 turnActive=true, 不清除累积器
                                        } else {
                                            turnActive = false
                                            thinkingParts.clear()
                                            toolParts.clear()
                                            answerText = ""
                                            pendingFinalBlocks = emptyList()
                                            lastStreamBlocks = emptyList()
                                            preQuestionBlocks = emptyList()
                                            expectFinalizeReply = false
                                        }
                                    }
                                    // ── 提问场景：不清除状态，不标记空闲 ──
                                    if (!com.silk.backend.ccconnect.CcConnectRegistry.isWaitingForInput(groupId)) {
                                        chatServer.broadcastSystemStatus("CLEAR_STATUS")
                                        com.silk.backend.ccconnect.CcConnectRegistry.markIdle(groupId)
                                    }
                                }
                                else -> chatServer.broadcastSystemStatus(status.state)
                            }
                        }
                        "pong" -> { /* keepalive response */ }
                        "metadata" -> {
                            val metadata = com.silk.backend.ccconnect.protocolJson.decodeFromString(
                                com.silk.backend.ccconnect.MetadataMessage.serializer(), text
                            )
                            com.silk.backend.ccconnect.CcConnectRegistry.updateMetadata(groupId, metadata)
                            val metaJson = kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("cc_metadata"))
                                put("mode", kotlinx.serialization.json.JsonPrimitive(metadata.mode))
                                put("model", kotlinx.serialization.json.JsonPrimitive(metadata.model))
                                put("available_modes", kotlinx.serialization.json.Json.encodeToJsonElement(
                                    kotlinx.serialization.builtins.ListSerializer(com.silk.backend.ccconnect.CcModeOption.serializer()),
                                    metadata.availableModes ?: emptyList()
                                ))
                                put("available_models", kotlinx.serialization.json.Json.encodeToJsonElement(
                                    kotlinx.serialization.builtins.ListSerializer(com.silk.backend.ccconnect.CcModelOption.serializer()),
                                    metadata.availableModels ?: emptyList()
                                ))
                            }
                            val metaBroadcast = Message(
                                id = java.util.UUID.randomUUID().toString(),
                                userId = "system",
                                userName = "cc-connect",
                                content = metaJson.toString(),
                                timestamp = System.currentTimeMillis(),
                                type = MessageType.SYSTEM,
                                isTransient = true,
                            )
                            chatServer.broadcast(metaBroadcast)
                        }
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                logger.error("[CcConnect] WS error: groupId={}, err={}", groupId, e.message)
            } finally {
                logger.info("[CcConnect] WS disconnected: groupId={}", groupId)

                // ── 持久化未完成轮次的回复内容 ──
                // 当引擎重启/崩溃时，status:idle 不会到达，流式回复仅以 transient
                // 方式广播过。此处将已累积内容作为最终消息持久化，确保刷新后不丢失。
                if (turnActive) {
                    val blocks = pendingFinalBlocks.ifEmpty {
                        // 无结构化 blocks → 从 legacy thinkingParts/toolParts/answerText 构建
                        buildContentBlockList(preBlocks = preQuestionBlocks)
                    }
                    val finalContent = answerText.ifEmpty {
                        blocks.firstOrNull { it.type == "text" }?.content ?: ""
                    }
                    if (blocks.isNotEmpty() || finalContent.isNotBlank()) {
                        val msg = Message(
                            id = java.util.UUID.randomUUID().toString(),
                            userId = "cc-connect",
                            userName = ccUserName,
                            content = finalContent,
                            timestamp = System.currentTimeMillis(),
                            type = MessageType.TEXT,
                            contentBlocks = blocks,
                        )
                        chatServer.broadcast(msg)
                        logger.info("[CcConnect][{}] persisted in-flight turn on disconnect: blocks={}, contentLen={}",
                            groupId, blocks.size, finalContent.length)
                    }
                    turnActive = false
                }
                com.silk.backend.ccconnect.CcConnectRegistry.unregister(groupId, this)

                val offlineMsg = Message(
                    id = java.util.UUID.randomUUID().toString(),
                    userId = "system",
                    userName = "cc-connect",
                    content = "cc-connect disconnected",
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.SYSTEM,
                    isTransient = true,
                )
                chatServer.broadcast(offlineMsg)
            }
        }

        // ==================== cc-connect Token Management ====================

}

// 聚合一组独立的 Ktor 路由注册；圈复杂度来自注册的 handler 数量而非真实控制流，各 handler 自身已是独立闭包。
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
private fun Route.ccConnectApiRoutes() {
        get("/api/ccconnect/groups/{groupId}/token-info") {
            val groupId = call.parameters["groupId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "missing groupId"))
                return@get
            }
            val userId = call.request.queryParameters["userId"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "missing userId"))
                return@get
            }
            val group = GroupRepository.findGroupById(groupId)
            if (group == null) {
                call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "group not found"))
                return@get
            }
            if (group.hostId != userId) {
                if (!GroupRepository.isUserInGroup(groupId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, SimpleResponse(false, "非群组成员"))
                    return@get
                }
                val connected = com.silk.backend.ccconnect.CcConnectRegistry.isConnected(groupId)
                val meta = com.silk.backend.ccconnect.CcConnectRegistry.getConnectionInfo(groupId)
                val tokenExists = com.silk.backend.ccconnect.CcConnectTokenRepository.getTokenForGroup(groupId) != null
                if (!tokenExists) {
                    call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "not a cc-connect group"))
                    return@get
                }
                val json = kotlinx.serialization.json.buildJsonObject {
                    put("success", kotlinx.serialization.json.JsonPrimitive(true))
                    put("token", kotlinx.serialization.json.JsonPrimitive(null as String?))
                    put("connected", kotlinx.serialization.json.JsonPrimitive(connected))
                    put("agentType", kotlinx.serialization.json.JsonPrimitive(meta?.agentType))
                    put("project", kotlinx.serialization.json.JsonPrimitive(meta?.project))
                    put("cwd", kotlinx.serialization.json.JsonPrimitive(meta?.cwd))
                    put("mode", kotlinx.serialization.json.JsonPrimitive(meta?.mode))
                    put("model", kotlinx.serialization.json.JsonPrimitive(meta?.model))
                    if (meta?.availableModes != null) {
                        put("availableModes", kotlinx.serialization.json.Json.encodeToJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(com.silk.backend.ccconnect.CcModeOption.serializer()), meta.availableModes))
                    }
                    if (meta?.availableModels != null) {
                        put("availableModels", kotlinx.serialization.json.Json.encodeToJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(com.silk.backend.ccconnect.CcModelOption.serializer()), meta.availableModels))
                    }
                }
                call.respondText(json.toString(), ContentType.Application.Json)
                return@get
            }
            val tokenInfo = com.silk.backend.ccconnect.CcConnectTokenRepository.getTokenForGroup(groupId)
            if (tokenInfo == null) {
                call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "not a cc-connect group"))
                return@get
            }
            val connected = com.silk.backend.ccconnect.CcConnectRegistry.isConnected(groupId)
            val meta = com.silk.backend.ccconnect.CcConnectRegistry.getConnectionInfo(groupId)
            val json = kotlinx.serialization.json.buildJsonObject {
                put("success", kotlinx.serialization.json.JsonPrimitive(true))
                put("token", kotlinx.serialization.json.JsonPrimitive(tokenInfo.token))
                put("connected", kotlinx.serialization.json.JsonPrimitive(connected))
                put("agentType", kotlinx.serialization.json.JsonPrimitive(meta?.agentType))
                put("project", kotlinx.serialization.json.JsonPrimitive(meta?.project))
                put("cwd", kotlinx.serialization.json.JsonPrimitive(meta?.cwd))
                put("mode", kotlinx.serialization.json.JsonPrimitive(meta?.mode))
                put("model", kotlinx.serialization.json.JsonPrimitive(meta?.model))
                if (meta?.availableModes != null) {
                    put("availableModes", kotlinx.serialization.json.Json.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(com.silk.backend.ccconnect.CcModeOption.serializer()), meta.availableModes))
                }
                if (meta?.availableModels != null) {
                    put("availableModels", kotlinx.serialization.json.Json.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(com.silk.backend.ccconnect.CcModelOption.serializer()), meta.availableModels))
                }
            }
            call.respondText(json.toString(), ContentType.Application.Json)
        }

        post("/api/ccconnect/groups/{groupId}/regenerate-token") {
            val groupId = call.parameters["groupId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "missing groupId"))
                return@post
            }
            val body = call.receiveText()
            val bodyJson = try {
                kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
            } catch (_: Exception) { null }
            val userId = bodyJson?.get("userId")?.jsonPrimitive?.contentOrNull
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "missing userId"))
                return@post
            }
            val group = GroupRepository.findGroupById(groupId)
            if (group == null) {
                call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "group not found"))
                return@post
            }
            if (group.hostId != userId) {
                call.respond(HttpStatusCode.Forbidden, SimpleResponse(false, "仅群主可重新生成 token"))
                return@post
            }
            val newToken = com.silk.backend.ccconnect.CcConnectTokenRepository.regenerateToken(groupId, group.name)
            val json = kotlinx.serialization.json.buildJsonObject {
                put("success", kotlinx.serialization.json.JsonPrimitive(true))
                put("token", kotlinx.serialization.json.JsonPrimitive(newToken))
            }
            call.respondText(json.toString(), ContentType.Application.Json)
        }

        post("/api/ccconnect/groups/{groupId}/set-operator") {
            val groupId = call.parameters["groupId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "missing groupId"))
                return@post
            }
            val body = call.receiveText()
            val bodyJson = try {
                kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
            } catch (_: Exception) { null }
            val userId = bodyJson?.get("userId")?.jsonPrimitive?.contentOrNull
            val targetUserId = bodyJson?.get("targetUserId")?.jsonPrimitive?.contentOrNull
            val grant = bodyJson?.get("grant")?.jsonPrimitive?.booleanOrNull
            if (userId.isNullOrBlank() || targetUserId.isNullOrBlank() || grant == null) {
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "missing userId, targetUserId, or grant"))
                return@post
            }
            val group = GroupRepository.findGroupById(groupId)
            if (group == null) {
                call.respond(HttpStatusCode.NotFound, SimpleResponse(false, "group not found"))
                return@post
            }
            if (group.hostId != userId) {
                call.respond(HttpStatusCode.Forbidden, SimpleResponse(false, "仅群主可设置 operator"))
                return@post
            }
            if (targetUserId == group.hostId) {
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "不能修改群主角色"))
                return@post
            }
            if (!GroupRepository.isUserInGroup(groupId, targetUserId)) {
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "目标用户不在群组中"))
                return@post
            }
            val newRole = if (grant) MemberRole.OPERATOR else MemberRole.GUEST
            val ok = GroupRepository.updateMemberRole(groupId, targetUserId, newRole)
            call.respond(SimpleResponse(ok, if (ok) "角色已更新" else "更新失败"))
        }

        // ==================== Agents API ====================

        // GET /api/agents?userId=<id>
        // 列出可作为 workflow agent 的选项：仅包含已注册的 bridge agent（claude_code / codex 等）。
        // silk_chat 走普通会话路径，不在工作流 agent 选择范围内。
        // agentType 字段使用 workflow 存储一致的 underscore 形式。
}

// 聚合一组独立的 Ktor 路由注册；圈复杂度来自注册的 handler 数量而非真实控制流，各 handler 自身已是独立闭包。
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
private fun Route.workflowKbRoutes() {
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

        post("/api/workflows") {
            // 错误响应统一用 JsonObject 安全编码，避免外部字符串里的引号/反斜杠破坏 JSON 结构
            suspend fun respondError(status: HttpStatusCode, message: String) {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("success", kotlinx.serialization.json.JsonPrimitive(false))
                    put("message", kotlinx.serialization.json.JsonPrimitive(message))
                }
                call.respondText(payload.toString(), ContentType.Application.Json, status)
            }

            val body = call.receiveText()
            val json = Json { ignoreUnknownKeys = true }
            val req = json.decodeFromString<kotlinx.serialization.json.JsonObject>(body)
            val userId = req["userId"]?.jsonPrimitive?.content
            val name = req["name"]?.jsonPrimitive?.content
            if (userId.isNullOrBlank() || name.isNullOrBlank()) {
                respondError(HttpStatusCode.BadRequest, "Missing userId or name")
                return@post
            }
            val desc = req["description"]?.jsonPrimitive?.content ?: ""
            val agentType = req["agentType"]?.jsonPrimitive?.contentOrNull ?: "claude_code"
            val taskFocus = req["taskFocus"]?.jsonPrimitive?.contentOrNull ?: ""
            val permissionMode = req["permissionMode"]?.jsonPrimitive?.contentOrNull ?: ""

            // 自动创建关联群组（工作流私聊）
            val groupName = "wf_${sanitizeFileName(name)}_${System.currentTimeMillis()}"
            val group = com.silk.backend.database.GroupRepository.createGroup(groupName, userId)
            if (group == null) {
                respondError(HttpStatusCode.InternalServerError, "Failed to create workflow group")
                return@post
            }

            if (agentType == "silk_chat") {
                // Silk Chat 类型：跳过 bridge/目录校验，无 cdSync，直接返回
                val wf = workflowManager.createWorkflow(name, desc, userId, group.id, agentType, taskFocus)
                call.respondText(
                    Json.encodeToString(Workflow.serializer(), wf),
                    ContentType.Application.Json,
                    HttpStatusCode.Created,
                )
                return@post
            }

            // Claude Code 类型（默认）：需要 bridge 连接和有效工作目录
            val initialDir = req["initialDir"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            // 工作目录是工作流的硬约束：必须由 bridge 验证过的合法路径才能创建。
            // 这样可避免出现"workflow 创建成功但工作目录是 backend 进程的 cwd"这种半生不熟的状态。
            if (initialDir.isEmpty()) {
                com.silk.backend.database.GroupRepository.deleteGroup(group.id)
                respondError(HttpStatusCode.BadRequest, "工作目录不能为空")
                return@post
            }
            if (!isAnyBridgeConnected(userId)) {
                com.silk.backend.database.GroupRepository.deleteGroup(group.id)
                respondError(HttpStatusCode.Conflict, "Bridge 未连接，无法创建工作流。请先启动 Bridge Agent。")
                return@post
            }

            // 信任目录检查
            val bridgeId = resolveBridgeId(userId) ?: "unknown"
            if (!trustedDirManager.isTrusted(userId, bridgeId, initialDir)) {
                com.silk.backend.database.GroupRepository.deleteGroup(group.id)
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("success", kotlinx.serialization.json.JsonPrimitive(false))
                    put("errorCode", kotlinx.serialization.json.JsonPrimitive("DIRECTORY_NOT_TRUSTED"))
                    put("message", kotlinx.serialization.json.JsonPrimitive("目录 $initialDir 未被信任。请先确认信任该目录。"))
                    put("path", kotlinx.serialization.json.JsonPrimitive(initialDir))
                    put("bridgeId", kotlinx.serialization.json.JsonPrimitive(bridgeId))
                }
                call.respondText(payload.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val wf = workflowManager.createWorkflow(name, desc, userId, group.id, agentType, "")

            // cdSync 必须成功才能算创建完成；失败时回滚 group + workflow + CC state，避免遗留无效记录
            val cdResult: AgentRuntime.CdResult = try {
                AgentRuntime.cdSync(userId, "group_${group.id}", initialDir, agentType = resolveActiveAgentType(userId) ?: "claude-code")
            } catch (e: Exception) {
                AgentRuntime.CdResult.Err(e.message ?: "初始目录切换异常")
            }
            if (cdResult is AgentRuntime.CdResult.Err) {
                logger.warn("⚠️ 工作流 {} 初始 /cd 失败，回滚 group + workflow: {}", wf.id, cdResult.reason)
                workflowManager.deleteWorkflow(wf.id, userId)
                com.silk.backend.database.GroupRepository.deleteGroup(group.id)
                AgentRuntime.cleanupState(userId, "group_${group.id}")
                respondError(HttpStatusCode.Conflict, "工作目录设置失败：${cdResult.reason}")
                return@post
            }
            // 同步把 workingDir 写到 workflow record，确保返回给前端的 wf 对象包含真实路径
            val resolvedPath = (cdResult as AgentRuntime.CdResult.Ok).resolvedPath
            workflowManager.updateWorkingDir(group.id, resolvedPath)
            // 创建时指定的 permissionMode 持久化到 workflow record（seed 加载时生效）
            if (permissionMode.isNotBlank()) {
                workflowManager.updatePermissionMode(group.id, permissionMode)
            }
            val wfWithDir = wf.copy(workingDir = resolvedPath, permissionMode = permissionMode, updatedAt = System.currentTimeMillis())

            call.respondText(
                Json.encodeToString(Workflow.serializer(), wfWithDir),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        }

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

        // ==================== Knowledge Base API ====================

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
        registerApiKbCopilotPostRoute()
        registerApiKbMemoryGetRoute()
        registerApiKbMemoryEntryIdGetRoute()
        registerApiKbMemoryPostRoute()
        registerApiKbMemoryEntryIdDeleteRoute()
        registerApiKbMemoryConsolidatePostRoute()
        registerApiKbContextPreferencesGetRoute()
        registerApiKbContextPreferencesPutRoute()
        registerApiKbGroupAssetsGetRoute()
        registerApiKbEntriesEntryIdLinkFilePostRoute()

}

// /chat 聊天 WebSocket：单 handler 内处理连接/历史回放/消息分发，控制流敏感且耦合，拆分风险高于收益。
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
private fun Route.chatWebSocketRoute() {
        webSocket("/chat") {
            // 支持 JWT 认证：优先从 token 参数解析 userId，fallback 到旧版 userId 参数
            val token = call.parameters["token"]
            val resolvedUserId = if (!token.isNullOrBlank()) {
                JwtProvider.verifyAccessToken(token) ?: call.parameters["userId"]
            } else {
                call.parameters["userId"]
            }
            val userId = resolvedUserId ?: UUID.randomUUID().toString()
            val userName = if (!token.isNullOrBlank() && JwtProvider.verifyAccessToken(token) != null) {
                // 从 JWT 登录的用户名（从数据库查找）
                UserRepository.findUserById(userId)?.fullName
                    ?: call.parameters["userName"]
                    ?: "User_${userId.take(6)}"
            } else {
                call.parameters["userName"] ?: "User_${userId.take(6)}"
            }
            val groupId = call.parameters["groupId"] ?: "default_room"

            logger.info("👤 用户连接: {} ({}) -> 群组: {}", userName, userId, groupId)

            // 为每个群组获取或创建独立的ChatServer
            val groupChatServer = getGroupChatServer(groupId)

            // 工作流群组自动激活 agent 模式（仅非 silk_chat 类型）。
            // M4 Task 3: 优先取持久化的 activeAgent；缺失则回落到 workflow.agentType（underscore→dash）。
            val workflow = workflowManager.getWorkflowByGroupId(groupId)
            if (workflow != null && workflow.agentType != "silk_chat") {
                val resolvedAgent = workflow.activeAgent.takeIf { it.isNotBlank() }
                    ?: when (workflow.agentType) {
                        "claude_code" -> "claude-code"
                        else -> workflow.agentType
                    }
                AgentRuntime.autoActivateForWorkflow(userId, "group_$groupId", resolvedAgent)

                // Re-broadcast pending question if agent is waiting for user answer
                val pendingSnapshot = AgentRuntime.snapshotPendingQuestion(userId, "group_$groupId")
                if (pendingSnapshot != null) {
                    val questionMsg = com.silk.backend.agents.core.AgentMessages.question(
                        content = com.silk.backend.agents.core.AgentMessages.formatQuestionText(pendingSnapshot.questions),
                        requestId = pendingSnapshot.requestId,
                        agentUserId = pendingSnapshot.agentUserId,
                        agentName = pendingSnapshot.agentName,
                    )
                    groupChatServer.broadcast(questionMsg)
                }
            }

            try {
                groupChatServer.join(userId, userName, this)
                
                incoming.consumeEach { frame ->
                    when (frame) {
                        is Frame.Text -> {
                            val receivedText = frame.readText()
                            try {
                                val message = Json.decodeFromString<Message>(receivedText)

                                // ⛔ cc-connect 按钮答案拦截：按钮值（如 perm:allow）是引擎产生的
                                // 机器 token，不应作为聊天消息显示。拦截在 broadcast() 之前，
                                // 完全不存历史、不广播给客户端。
                                // 仅拦截已知的按钮 token 格式（自然语言回复仍正常显示）。
                                val isCcButtonAnswerCandidate = message.type == MessageType.TEXT &&
                                    !message.isTransient &&
                                    message.userId != "cc-connect" && message.userId != "system"
                                if (isCcButtonAnswerCandidate) {
                                    val ccGid = groupId
                                    val ccReg = com.silk.backend.ccconnect.CcConnectRegistry
                                    if (ccReg.isConnected(ccGid) && ccReg.isWaitingForInput(ccGid)) {
                                        // 映射按钮值 → 引擎期望的权限回复文本
                                        val engineContent = when (message.content) {
                                            "perm:allow", "perm:allow_all" -> "allow"
                                            "perm:deny" -> "deny"
                                            else -> null  // 不是已知按钮值 → 走正常 broadcast 流程
                                        }
                                        if (engineContent != null) {
                                            val userMsg = com.silk.backend.ccconnect.UserMessage(
                                                content = engineContent,
                                                userId = message.userId,
                                                userName = message.userName,
                                                msgId = message.id,
                                            )
                                            ccReg.forwardAnswer(ccGid, userMsg)
                                            logger.info("[CcAnswer] 按钮答案已转发: groupId={}, content={}→{}", ccGid, message.content, engineContent)
                                            return@consumeEach
                                        }
                                    }
                                }

                                groupChatServer.broadcast(message)
                            } catch (e: Exception) {
                                logger.warn("⚠️ 解析消息失败: {}", e.message)
                            }
                        }
                        else -> {}
                    }
                }
            } catch (_: CancellationException) {
                logger.debug("WebSocket 会话正常取消: {} ({})", userName, userId)
            } catch (e: Exception) {
                logger.error("❌ WebSocket 错误: {}", e.localizedMessage)
            } finally {
                logger.info("👤 用户断开: {} ({})", userName, userId)
                groupChatServer.leave(userId, userName, this)
            }
        }

}

// 聚合一组独立的 Ktor 路由注册；圈复杂度来自注册的 handler 数量而非真实控制流，各 handler 自身已是独立闭包。
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun Route.audioDuplexRoute() {
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

            try {
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
                            } catch (_: CancellationException) {}
                        }

                        val sendToClient = launch {
                            try {
                                incoming.consumeEach { frame ->
                                    if (frame is Frame.Text) {
                                        serverSession.send(Frame.Text(frame.readText()))
                                    }
                                }
                            } catch (_: CancellationException) {}
                        }

                        sendToUpstream.join()
                        sendToClient.cancelAndJoin()
                    }
                }
            } catch (e: Exception) {
                logger.error("❌ AudioDuplex proxy error: {}", e.message)
                try { close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, e.message ?: "proxy error")) } catch (_: Exception) {}
            } finally {
                httpClient.close()
            }
        }
}

// ==================== KB access-control routes (KB layer 4, grafted from chore 245c71c/44e5c0e/b3fe1b0/8fe4e75) ====================
private val kbRouteJson = Json { ignoreUnknownKeys = true }

private const val KB_AUTHENTICATED_USER_ID_HEADER = "X-Silk-Authenticated-User-Id"
private const val AUTHORIZATION_HEADER = "Authorization"
private const val BEARER_PREFIX = "Bearer "

private val knowledgeBaseContextPreferenceStore: KnowledgeBaseContextPreferenceStore
    get() = KnowledgeBaseContextPreferenceStore()

private data class KbCallerResolution(
    val userId: String?,
    val mismatch: Boolean = false,
    val invalidToken: Boolean = false,
)

private fun ApplicationCall.resolveAuthenticatedUserId(): String? {
    val authorization = request.headers[AUTHORIZATION_HEADER]?.trim().orEmpty()
    if (!authorization.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
    val token = authorization.substring(BEARER_PREFIX.length).trim()
    if (token.isEmpty()) return null
    return JwtProvider.verifyAccessToken(token)
        ?: UserSettingsRepository.findUserIdByAppAuthToken(token)
}

private fun resolveKbCallerUserId(call: ApplicationCall, fallbackUserId: String?): KbCallerResolution {
    val bearerUserId = call.resolveAuthenticatedUserId()
    val legacyAuthenticatedUserId = call.request.headers[KB_AUTHENTICATED_USER_ID_HEADER]?.trim()?.takeIf { it.isNotEmpty() }
    val authenticatedUserId = bearerUserId ?: legacyAuthenticatedUserId
    val requestUserId = fallbackUserId?.trim()?.takeIf { it.isNotEmpty() }
    val bearerHeaderPresent = call.request.headers[AUTHORIZATION_HEADER]?.trim()?.startsWith(BEARER_PREFIX, ignoreCase = true) == true
    return when {
        bearerHeaderPresent && bearerUserId == null -> KbCallerResolution(userId = null, invalidToken = true)
        authenticatedUserId != null && requestUserId != null && authenticatedUserId != requestUserId ->
            KbCallerResolution(userId = authenticatedUserId, mismatch = true)
        authenticatedUserId != null -> KbCallerResolution(userId = authenticatedUserId)
        else -> KbCallerResolution(userId = requestUserId)
    }
}

private suspend fun resolveKbCallerUserIdOrRespond(
    call: ApplicationCall,
    fallbackUserId: String?,
): String? {
    val resolution = resolveKbCallerUserId(call, fallbackUserId)
    if (resolution.invalidToken) {
        call.respondText(
            """{"success":false,"message":"Invalid auth token"}""",
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized,
        )
        return null
    }
    if (resolution.mismatch) {
        call.respondText(
            """{"success":false,"message":"Authenticated user mismatch"}""",
            ContentType.Application.Json,
            HttpStatusCode.Forbidden,
        )
        return null
    }
    return resolution.userId
}

private fun Route.registerApiKbTopicsGetRoute() {

    // ==================== Knowledge Base API ====================

    get("/api/kb/topics") {
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
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
        val userId = resolveKbCallerUserIdOrRespond(call, req["userId"]?.jsonPrimitive?.content)
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
        val userId = resolveKbCallerUserIdOrRespond(call, req["userId"]?.jsonPrimitive?.content)
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
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
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
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
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
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
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
        val userId = resolveKbCallerUserIdOrRespond(call, req["userId"]?.jsonPrimitive?.content)
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
        val userId = resolveKbCallerUserIdOrRespond(call, req["userId"]?.jsonPrimitive?.contentOrNull)
        val topicId = req["topicId"]?.jsonPrimitive?.contentOrNull
        val title = req["title"]?.jsonPrimitive?.contentOrNull?.trim()
        val content = req["content"]?.jsonPrimitive?.contentOrNull
        val tags = req["tags"]?.let { tagsEl ->
            runCatching { tagsEl.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
        } ?: emptyList()
        val source = parseKbEntrySource(req)
        val requestedStatus = parseKbEntryStatus(req)

        if (isMissingKbCaptureFields(userId, topicId, title, content)) {
            call.respondText("""{"success":false,"message":"Missing required capture fields"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        if (source == null || source.sourceType == KBSourceType.MANUAL) {
            call.respondText("""{"success":false,"message":"Invalid capture source"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        if (req["status"] != null && requestedStatus == null) {
            call.respondText("""{"success":false,"message":"Invalid capture status"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val validUserId = userId!!
        val validTopicId = topicId!!
        val validTitle = title!!
        val validContent = content!!
        val status = when (source.sourceType) {
            KBSourceType.CHAT, KBSourceType.AI_RESPONSE, KBSourceType.WORKFLOW -> KBEntryStatus.CANDIDATE
            else -> requestedStatus ?: KBEntryStatus.CANDIDATE
        }

        val entry = knowledgeBaseManager.createEntry(
            topicId = validTopicId,
            title = validTitle,
            content = validContent,
            tags = tags,
            userId = validUserId,
            status = status,
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

private fun isMissingKbCaptureFields(
    userId: String?,
    topicId: String?,
    title: String?,
    content: String?,
): Boolean {
    return userId.isNullOrBlank() ||
        topicId.isNullOrBlank() ||
        title.isNullOrBlank() ||
        content.isNullOrBlank()
}

private fun Route.registerApiKbEntriesEntryIdPutRoute() {

    put("/api/kb/entries/{entryId}") {
        val entryId = call.parameters["entryId"] ?: ""
        val body = call.receiveText()
        val req = kbRouteJson.decodeFromString<JsonObject>(body)
        val userId = resolveKbCallerUserIdOrRespond(call, req["userId"]?.jsonPrimitive?.content)
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
        val topicId = req["topicId"]?.jsonPrimitive?.contentOrNull
        val updated = knowledgeBaseManager.updateEntry(entryId, topicId, title, content, tags, status, userId)
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
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
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
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
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

private fun Route.registerApiKbCopilotPostRoute() {

    post("/api/kb/copilot") {
        val req = runCatching {
            kbRouteJson.decodeFromString(KnowledgeBaseCopilotRequest.serializer(), call.receiveText())
        }.getOrNull()
        if (req == null) {
            call.respondText(
                """{"success":false,"message":"Invalid copilot request"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        val userId = resolveKbCallerUserIdOrRespond(call, req.userId)
        if (userId.isNullOrBlank()) {
            call.respondText(
                """{"success":false,"message":"Missing userId"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        val response = executeKnowledgeBaseCopilot(
            manager = knowledgeBaseManager,
            request = req.copy(userId = userId),
            runAgent = { input ->
                val agent = DirectModelAgent(sessionId = "kb_copilot_${sanitizeFileName(req.entryId)}")
                val workspaceDir = "${AIConfig.CLAUDE_CLI_WORKSPACE_ROOT}/kb_copilot_${sanitizeFileName(userId)}_${sanitizeFileName(req.entryId)}"
                java.io.File(workspaceDir).mkdirs()
                agent.initClaudeClient(workspaceDir)
                agent.syncKnowledgeBaseWorkspace(input.workspaceEntries.ifEmpty {
                    buildKnowledgeBaseWorkspaceEntries(knowledgeBaseManager, userId)
                })
                agent.processInput(
                    userInput = input.userPrompt,
                    systemPrompt = input.systemPrompt,
                    callback = { _, _, _ -> },
                )
            },
        )
        call.respondText(
            kbRouteJson.encodeToString(com.silk.backend.kb.KnowledgeBaseCopilotResponse.serializer(), response),
            ContentType.Application.Json,
            if (response.success || response.draft != null) HttpStatusCode.OK else HttpStatusCode.BadGateway,
        )
    }
}

private fun Route.registerApiKbContextPreferencesGetRoute() {

    get("/api/kb/context-preferences") {
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val preferences = knowledgeBaseContextPreferenceStore.get(userId)
        call.respondText(
            Json.encodeToString(com.silk.backend.kb.KnowledgeBaseContextPreferences.serializer(), preferences),
            ContentType.Application.Json,
        )
    }
}

private fun Route.registerApiKbContextPreferencesPutRoute() {

    put("/api/kb/context-preferences") {
        val body = call.receiveText()
        val req = kbRouteJson.decodeFromString<JsonObject>(body)
        val userId = resolveKbCallerUserIdOrRespond(call, req["userId"]?.jsonPrimitive?.contentOrNull)
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@put
        }
        val excludedSpaceIds = req["excludedSpaceIds"]?.let { element ->
            runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
        } ?: emptyList()
        val updated = knowledgeBaseContextPreferenceStore.update(
            userId = userId,
            excludedSpaceIds = excludedSpaceIds,
            memoryEnabled = req["memoryEnabled"]?.jsonPrimitive?.booleanOrNull,
            autoCaptureEnabled = req["autoCaptureEnabled"]?.jsonPrimitive?.booleanOrNull,
            ephemeralSessionEnabled = req["ephemeralSessionEnabled"]?.jsonPrimitive?.booleanOrNull,
        )
        call.respondText(
            Json.encodeToString(com.silk.backend.kb.KnowledgeBaseContextPreferences.serializer(), updated),
            ContentType.Application.Json,
        )
    }
}

private fun Route.registerApiKbMemoryGetRoute() {

    get("/api/kb/memory") {
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val groupId = call.request.queryParameters["groupId"]?.trim()?.takeIf { it.isNotEmpty() }
        val list = if (groupId != null) {
            knowledgeBaseManager.listGroupMemoryEntries(groupId, userId)
        } else {
            knowledgeBaseManager.listMemoryEntries(userId)
        }
        call.respondText(
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(KBEntry.serializer()), list),
            ContentType.Application.Json,
        )
    }
}

private fun Route.registerApiKbMemoryPostRoute() {

    post("/api/kb/memory") {
        val body = call.receiveText()
        val req = kbRouteJson.decodeFromString<JsonObject>(body)
        val userId = resolveKbCallerUserIdOrRespond(call, req["userId"]?.jsonPrimitive?.contentOrNull)
        val content = req["content"]?.jsonPrimitive?.contentOrNull?.trim()
        if (userId.isNullOrBlank() || content.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing required fields"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val memoryType = req["memoryType"]?.jsonPrimitive?.contentOrNull?.let {
            runCatching { com.silk.backend.models.KBMemoryType.valueOf(it.trim().uppercase()) }.getOrNull()
        }
        if (req["memoryType"] != null && memoryType == null) {
            call.respondText("""{"success":false,"message":"Invalid memoryType"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val detected = com.silk.backend.kb.detectExplicitMemoryCapture(content)
        val memoryContent = detected?.content ?: content
        val type = memoryType ?: detected?.type ?: com.silk.backend.models.KBMemoryType.EPISODIC
        val groupId = req["groupId"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val entry = if (groupId != null) {
            knowledgeBaseManager.captureExplicitGroupMemory(
                userId = userId,
                groupId = groupId,
                content = memoryContent,
                title = req["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty {
                    detected?.title ?: com.silk.backend.kb.buildMemoryTitle(type, memoryContent)
                },
                type = type,
                key = req["key"]?.jsonPrimitive?.contentOrNull,
            )
        } else {
            knowledgeBaseManager.captureExplicitMemory(
                userId = userId,
                content = memoryContent,
                title = req["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty {
                    detected?.title ?: com.silk.backend.kb.buildMemoryTitle(type, memoryContent)
                },
                type = type,
                key = req["key"]?.jsonPrimitive?.contentOrNull,
            )
        }
        call.respondText(
            Json.encodeToString(KBEntry.serializer(), entry),
            ContentType.Application.Json,
            HttpStatusCode.Created,
        )
    }
}

private fun Route.registerApiKbMemoryEntryIdDeleteRoute() {

    delete("/api/kb/memory/{entryId}") {
        val entryId = call.parameters["entryId"] ?: ""
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@delete
        }
        val entry = knowledgeBaseManager.getEntry(entryId, userId)
        if (entry?.memory == null) {
            call.respondText("""{"success":false,"message":"Memory entry not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return@delete
        }
        // 群组记忆：allow 群组中可管理的用户删除；个人记忆：仅 owner 可删
        val topic = knowledgeBaseManager.getTopic(entry.topicId, userId)
        val isGroupMemory = topic?.spaceType == KnowledgeSpaceType.TEAM
        val canDelete = if (isGroupMemory) {
            topic != null && knowledgeBaseManager.canManageTopic(topic, userId)
        } else {
            entry.ownerId == userId
        }
        if (!canDelete) {
            call.respondText("""{"success":false,"message":"Memory entry not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return@delete
        }
        val ok = knowledgeBaseManager.deleteEntry(entryId, userId)
        call.respondText(
            """{"success":$ok}""",
            ContentType.Application.Json,
            if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound,
        )
    }
}

private fun Route.registerApiKbMemoryEntryIdGetRoute() {

    get("/api/kb/memory/{entryId}") {
        val entryId = call.parameters["entryId"] ?: ""
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        // 使用 getMemoryEntryWithAccess 以追踪访问时间
        val entry = knowledgeBaseManager.getMemoryEntryWithAccess(entryId, userId)
        if (entry == null || entry.memory == null) {
            call.respondText("""{"success":false,"message":"Memory entry not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(
            Json.encodeToString(KBEntry.serializer(), entry),
            ContentType.Application.Json,
        )
    }
}

private fun Route.registerApiKbMemoryConsolidatePostRoute() {

    post("/api/kb/memory/consolidate") {
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
        if (userId.isNullOrBlank()) {
            call.respondText("""{"success":false,"message":"Missing userId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val groupId = call.request.queryParameters["groupId"]?.trim()?.takeIf { it.isNotEmpty() }
        val report = if (groupId != null) {
            knowledgeBaseManager.consolidateGroupMemoryStore(groupId, userId)
        } else {
            knowledgeBaseManager.consolidateMemoryStore(userId)
        }
        call.respondText(
            kbRouteJson.encodeToString(ConsolidationReport.serializer(), report),
            ContentType.Application.Json,
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

private fun parseKbEntryStatus(req: JsonObject): KBEntryStatus? {
    val raw = req["status"]?.jsonPrimitive?.contentOrNull ?: return null
    return runCatching { KBEntryStatus.valueOf(raw.trim().uppercase()) }.getOrNull()
}

/**
 * GET /api/kb/group-assets/{groupId}
 *
 * 获取群空间统一资产列表：
 * - 群上传目录中的文件
 * - 群团队空间中的 KB 条目（PUBLISHED）
 * - 群团队空间中的 KB 主题
 *
 * 调用者必须为群组成员。
 */
private fun Route.registerApiKbGroupAssetsGetRoute() {

    get("/api/kb/group-assets/{groupId}") {
        val groupId = call.parameters["groupId"] ?: ""
        if (groupId.isBlank()) {
            call.respondText(
                """{"success":false,"message":"Missing groupId"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }
        val userId = resolveKbCallerUserIdOrRespond(call, call.request.queryParameters["userId"])
        if (userId.isNullOrBlank()) {
            call.respondText(
                """{"success":false,"message":"Missing userId"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }
        try {
            val response = knowledgeBaseManager.getGroupAssets(groupId, userId)
            call.respondText(
                Json.encodeToString(GroupAssetsResponse.serializer(), response),
                ContentType.Application.Json,
            )
        } catch (e: IllegalArgumentException) {
            call.respondText(
                """{"success":false,"message":"${e.message ?: "Access denied"}"}""",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden,
            )
        }
    }
}

/**
 * POST /api/kb/entries/{entryId}/link-file
 *
 * 为 KB 条目关联一个群文件引用。
 * Request body:
 * {
 *   "userId": "...",
 *   "groupId": "...",
 *   "fileName": "...",
 *   "fileSize": 12345,
 *   "mimeType": "image/png",
 *   "sourceMessageId": "..." (optional)
 * }
 */
private fun Route.registerApiKbEntriesEntryIdLinkFilePostRoute() {

    post("/api/kb/entries/{entryId}/link-file") {
        val entryId = call.parameters["entryId"] ?: ""
        if (entryId.isBlank()) {
            call.respondText(
                """{"success":false,"message":"Missing entryId"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@post
        }

        val body = call.receiveText()
        val req = kbRouteJson.decodeFromString<JsonObject>(body)
        val userId = resolveKbCallerUserIdOrRespond(call, req["userId"]?.jsonPrimitive?.content)
        val groupId = req["groupId"]?.jsonPrimitive?.contentOrNull
        val fileName = req["fileName"]?.jsonPrimitive?.contentOrNull
        val fileSize = req["fileSize"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val mimeType = req["mimeType"]?.jsonPrimitive?.contentOrNull
        val sourceMessageId = req["sourceMessageId"]?.jsonPrimitive?.contentOrNull

        val hasMissingFields = userId.isNullOrBlank() || groupId.isNullOrBlank() ||
            fileName.isNullOrBlank() || fileSize == null || fileSize <= 0L || mimeType.isNullOrBlank()
        if (hasMissingFields) {
            call.respondText(
                """{"success":false,"message":"Missing required fields"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@post
        }

        val entry = knowledgeBaseManager.linkFileToEntry(
            entryId = entryId,
            groupId = groupId!!,
            fileName = fileName!!,
            fileSize = fileSize!!,
            mimeType = mimeType!!,
            userId = userId!!,
            sourceMessageId = sourceMessageId?.takeIf { it.isNotEmpty() },
        )
        if (entry == null) {
            call.respondText(
                """{"success":false,"message":"Entry not found or write denied"}""",
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
            return@post
        }

        call.respondText(
            Json.encodeToString(KBEntry.serializer(), entry),
            ContentType.Application.Json,
        )
    }
}
