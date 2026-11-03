package com.silk.backend.routes

import com.silk.backend.Message
import com.silk.backend.MessageType
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.ChatHistoryManager
import com.silk.backend.dsh.DshBotIdentity
import com.silk.backend.dsh.DshBotTokenRepository
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MemberRole
import com.silk.backend.getGroupChatServer
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveOrNull
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * dsh-superagent 集成 bot 的 HTTP 面（silk 作为 dsh 的一个 Source）。
 *
 * 职责刻意收窄为 IM 原语：token 鉴权、按邀请码入群、按时间游标拉群消息、
 * 以 bot 身份幂等发消息。协作编排（授权、接活、执行）全部在 dsh 侧。
 *
 * - pull/send 均校验 bot 是群成员；bot 固定 GUEST 角色，silk 内部无触发权。
 * - send 的幂等键由 dsh Outbox 的 message.id 充当，messageId = "dsh_<key>"，
 *   与 ChatServer.broadcast 的去重同一数据源，重试不会重复落盘/广播。
 * - token 管理端点与 ccconnect token 端点保持同一暴露姿态（见 KNOWN_DRIFT/文档）。
 */

@Serializable
data class DshBotTokenRequest(val label: String = "")

@Serializable
data class DshBotTokenResponse(
    val success: Boolean,
    val message: String = "",
    val token: String? = null,
    val userId: String? = null,
    val label: String? = null,
)

@Serializable
data class DshBotTokenInfoResponse(
    val success: Boolean,
    val userId: String? = null,
    val label: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class DshBotGroupSummary(
    val id: String,
    val name: String,
    val role: String,
    val memberCount: Long,
)

@Serializable
data class DshBotMeResponse(
    val userId: String,
    val userName: String,
    val groups: List<DshBotGroupSummary>,
)

@Serializable
data class DshBotJoinRequest(val invitationCode: String = "")

@Serializable
data class DshBotJoinResponse(
    val success: Boolean,
    val message: String = "",
    val group: DshBotGroupSummary? = null,
)

@Serializable
data class DshBotMessageDto(
    val id: String,
    val senderId: String,
    val senderName: String,
    val bot: Boolean,
    val content: String,
    val timestamp: Long,
    val type: String,
)

@Serializable
data class DshBotPullResponse(
    val groupId: String,
    val serverTime: Long,
    val messages: List<DshBotMessageDto>,
)

@Serializable
data class DshBotSendRequest(
    val groupId: String = "",
    val idempotencyKey: String = "",
    val text: String = "",
)

@Serializable
data class DshBotSendResponse(
    val success: Boolean,
    val message: String = "",
    val status: String? = null,
    val messageId: String? = null,
)

private const val DEFAULT_PULL_LIMIT = 100
private const val MAX_PULL_LIMIT = 500
private const val MESSAGE_ID_PREFIX = "dsh_"

private val dshBotLogger = LoggerFactory.getLogger("DshBotRoutes")

/** 从 Authorization: Bearer 解析并校验 dsh bot token，返回 bot userId；失败返回 null。 */
private fun ApplicationCall.dshBotUserIdOrNull(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return DshBotTokenRepository.findUserIdByToken(header.substring(7).trim())
}

private fun groupSummary(groupId: String, botUserId: String): DshBotGroupSummary? {
    val group = GroupRepository.findGroupById(groupId) ?: return null
    val role = GroupRepository.getMemberRole(groupId, botUserId)?.name ?: return null
    return DshBotGroupSummary(
        id = group.id,
        name = group.name,
        role = role,
        memberCount = GroupRepository.getGroupMemberCount(groupId),
    )
}

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException", "LongMethod")
fun Route.dshBotRoutes() {

    // ==================== token 管理 ====================

    post("/api/dsh-bot/token") {
        try {
            val request = call.receiveOrNull<DshBotTokenRequest>() ?: DshBotTokenRequest()
            val bot = DshBotIdentity.ensureBotUser()
            if (bot == null) {
                call.respond(HttpStatusCode.InternalServerError, DshBotTokenResponse(false, "bot 用户创建失败"))
                return@post
            }
            val label = request.label.ifBlank { "dsh-superagent" }
            val token = DshBotTokenRepository.regenerate(bot.id, label)
            dshBotLogger.info("🤖 dsh bot token 已生成 (label={})", label)
            call.respond(DshBotTokenResponse(true, token = token, userId = bot.id, label = label))
        } catch (e: Exception) {
            dshBotLogger.error("❌ 生成 dsh bot token 失败: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, DshBotTokenResponse(false, "请求格式错误"))
        }
    }

    get("/api/dsh-bot/token-info") {
        val info = DshBotTokenRepository.getTokenInfo()
        if (info == null) {
            call.respond(DshBotTokenInfoResponse(false))
        } else {
            call.respond(
                DshBotTokenInfoResponse(
                    success = true,
                    userId = info.userId,
                    label = info.label,
                    createdAt = info.createdAt,
                )
            )
        }
    }

    // ==================== bot 数据面（Bearer token） ====================

    get("/api/dsh-bot/me") {
        val botUserId = call.dshBotUserIdOrNull()
        if (botUserId == null) {
            call.respond(HttpStatusCode.Unauthorized, DshBotMeResponse("", "", emptyList()))
            return@get
        }
        val groups = GroupRepository.getUserGroups(botUserId).mapNotNull { group ->
            groupSummary(group.id, botUserId)
        }
        call.respond(DshBotMeResponse(botUserId, DshBotIdentity.BOT_DISPLAY_NAME, groups))
    }

    post("/api/dsh-bot/join") {
        val botUserId = call.dshBotUserIdOrNull()
        if (botUserId == null) {
            call.respond(HttpStatusCode.Unauthorized, DshBotJoinResponse(false, "missing or invalid token"))
            return@post
        }
        val request = call.receiveOrNull<DshBotJoinRequest>() ?: DshBotJoinRequest()
        val group = GroupRepository.findGroupByInvitationCode(request.invitationCode.trim())
        if (group == null) {
            call.respond(HttpStatusCode.NotFound, DshBotJoinResponse(false, "邀请码无效"))
            return@post
        }
        // 与 /groups/join 相同的系统保留规则
        if (group.name.trimStart().startsWith("[Silk]")) {
            call.respond(HttpStatusCode.Forbidden, DshBotJoinResponse(false, "不能加入 [Silk] 专属对话"))
            return@post
        }
        if (!GroupRepository.isUserInGroup(group.id, botUserId)) {
            GroupRepository.addUserToGroup(group.id, botUserId, MemberRole.GUEST)
            dshBotLogger.info("🤖 dsh bot 已加入群组 {} ({})", group.name, group.id)
        }
        val summary = groupSummary(group.id, botUserId)
        call.respond(DshBotJoinResponse(true, group = summary))
    }

    get("/api/dsh-bot/messages") {
        val botUserId = call.dshBotUserIdOrNull()
        if (botUserId == null) {
            call.respond(HttpStatusCode.Unauthorized, DshBotPullResponse("", 0L, emptyList()))
            return@get
        }
        val groupId = call.request.queryParameters["groupId"]?.trim().orEmpty()
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PULL_LIMIT)
            .coerceIn(1, MAX_PULL_LIMIT)
        if (groupId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, DshBotPullResponse("", 0L, emptyList()))
            return@get
        }
        if (GroupRepository.findGroupById(groupId) == null) {
            call.respond(HttpStatusCode.NotFound, DshBotPullResponse(groupId, 0L, emptyList()))
            return@get
        }
        if (GroupRepository.getMemberRole(groupId, botUserId) == null) {
            call.respond(HttpStatusCode.Forbidden, DshBotPullResponse(groupId, 0L, emptyList()))
            return@get
        }
        val sessionName = "group_$groupId"
        val messages = ChatHistoryManager().loadChatHistory(sessionName)?.messages
            ?.filter { it.timestamp > since }
            ?.sortedBy { it.timestamp }
            ?.take(limit)
            ?.map { entry ->
                DshBotMessageDto(
                    id = entry.messageId,
                    senderId = entry.senderId,
                    senderName = entry.senderName,
                    bot = AgentRuntime.isAgentUserId(entry.senderId),
                    content = entry.content,
                    timestamp = entry.timestamp,
                    type = entry.messageType,
                )
            }
            ?: emptyList()
        call.respond(DshBotPullResponse(groupId, System.currentTimeMillis(), messages))
    }

    post("/api/dsh-bot/messages") {
        val botUserId = call.dshBotUserIdOrNull()
        if (botUserId == null) {
            call.respond(HttpStatusCode.Unauthorized, DshBotSendResponse(false, "missing or invalid token"))
            return@post
        }
        val request = call.receiveOrNull<DshBotSendRequest>() ?: DshBotSendRequest()
        if (request.groupId.isBlank() || request.idempotencyKey.isBlank() || request.text.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, DshBotSendResponse(false, "groupId/idempotencyKey/text 不能为空"))
            return@post
        }
        if (GroupRepository.findGroupById(request.groupId) == null) {
            call.respond(HttpStatusCode.NotFound, DshBotSendResponse(false, "群组不存在"))
            return@post
        }
        if (GroupRepository.getMemberRole(request.groupId, botUserId) == null) {
            call.respond(HttpStatusCode.Forbidden, DshBotSendResponse(false, "bot 不是该群组成员"))
            return@post
        }

        val messageId = MESSAGE_ID_PREFIX + request.idempotencyKey
        val chatServer = getGroupChatServer(request.groupId)
        val status = if (chatServer.hasPersistedMessage(messageId)) {
            "duplicate"
        } else {
            chatServer.broadcast(
                Message(
                    id = messageId,
                    userId = botUserId,
                    userName = DshBotIdentity.BOT_DISPLAY_NAME,
                    content = request.text,
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.TEXT,
                )
            )
            "sent"
        }
        call.respond(DshBotSendResponse(true, status = status, messageId = messageId))
    }
}
