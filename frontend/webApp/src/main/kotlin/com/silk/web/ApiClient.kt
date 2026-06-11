package com.silk.web

import com.silk.shared.models.CcSettingsResponse
import com.silk.shared.models.CcStateResponse
import com.silk.shared.models.DirListingResponse
import com.silk.shared.models.Language
import com.silk.shared.models.TrustedDirCheckResponse
import com.silk.shared.models.UpdateUserSettingsRequest
import com.silk.shared.models.UserSettingsResponse
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit
import kotlin.js.json

@Serializable
data class User(
    val id: String,
    val loginName: String,
    val fullName: String,
    val phoneNumber: String,
    val createdAt: String = ""
)

@Serializable
data class Group(
    val id: String,
    val name: String,
    val invitationCode: String,
    val hostId: String,
    val hostName: String = "",
    val createdAt: String = ""
)

@Serializable
data class UnreadCountResponse(
    val success: Boolean,
    val unreadCounts: Map<String, Int> = emptyMap()
)

@Serializable
data class AuthResponse(
    val success: Boolean,
    val message: String,
    val user: User? = null
)

@Serializable
data class GroupResponse(
    val success: Boolean,
    val message: String,
    val group: Group? = null,
    val groups: List<Group>? = null,
    val ccConnectToken: String? = null,
)

@Serializable
data class CcModeOption(val key: String, val name: String)

@Serializable
data class CcModelOption(val name: String, val desc: String = "")

@Serializable
data class CcMetadataEvent(
    val type: String = "cc_metadata",
    val mode: String? = null,
    val model: String? = null,
    @SerialName("available_modes") val availableModes: List<CcModeOption>? = null,
    @SerialName("available_models") val availableModels: List<CcModelOption>? = null,
)

@Serializable
data class CcConnectTokenInfo(
    val success: Boolean = false,
    val token: String? = null,
    val connected: Boolean = false,
    val agentType: String? = null,
    val project: String? = null,
    val cwd: String? = null,
    val mode: String? = null,
    val model: String? = null,
    val availableModes: List<CcModeOption>? = null,
    val availableModels: List<CcModelOption>? = null,
)

// ==================== 联系人相关数据模型 ====================

@Serializable
enum class ContactRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}

@Serializable
data class Contact(
    val userId: String,
    val contactId: String,
    val contactName: String,
    val contactPhone: String,
    val createdAt: String = ""
)

@Serializable
data class ContactRequest(
    val id: String,
    val fromUserId: String,
    val fromUserName: String,
    val fromUserPhone: String,
    val toUserId: String,
    val status: ContactRequestStatus,
    val createdAt: String = ""
)

@Serializable
data class ContactResponse(
    val success: Boolean,
    val message: String,
    val contact: Contact? = null,
    val contacts: List<Contact>? = null,
    val pendingRequests: List<ContactRequest>? = null
)

@Serializable
data class UserSearchResult(
    val found: Boolean,
    val user: User? = null,
    val message: String = ""
)

@Serializable
data class PrivateChatResponse(
    val success: Boolean,
    val message: String,
    val group: Group? = null,
    val isNew: Boolean = false
)

// ==================== 群组成员相关数据模型 ====================

@Serializable
data class GroupMember(
    val id: String,
    val fullName: String,
    val phone: String = "",
    val role: String = "GUEST",
)

@Serializable
data class GroupMembersResponse(
    val success: Boolean,
    val members: List<GroupMember>
)

@Serializable
data class AddMemberResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class LeaveGroupResponse(
    val success: Boolean,
    val message: String,
    val groupDeleted: Boolean = false
)

@Serializable
data class DeleteGroupResponse(
    val success: Boolean,
    val message: String
)

// ==================== 通用响应模型 ====================

@Serializable
data class SimpleResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class ExportMarkdownResponse(
    val success: Boolean,
    val message: String,
    val fileName: String = "",
    val markdown: String = ""
)

// ==================== Workflow models ====================

@Serializable
data class WorkflowItem(
    val id: String,
    val name: String,
    val description: String = "",
    val ownerId: String = "",
    val groupId: String = "",
    val agentType: String = "claude_code",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class AgentInfo(
    val agentType: String,
    val displayName: String,
    val connected: Boolean,
)

// ==================== Knowledge Base models ====================

@Serializable
data class KBTopicItem(
    val id: String,
    val name: String,
    val project: String = "",
    val ownerId: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Serializable
data class KBEntryItem(
    val id: String,
    val topicId: String = "",
    val title: String,
    val content: String = "",
    val tags: List<String> = emptyList(),
    val ownerId: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Serializable
data class ExportKBResponse(
    val success: Boolean,
    val markdown: String = "",
    val vaultPath: String = "",
    val fileName: String = ""
)
/**
 * JWT 令牌管理
 */
object JwtManager {
    private const val STORAGE_KEY_JWT = "silk_jwt"
    private const val STORAGE_KEY_REFRESH = "silk_refresh_token"
    private const val STORAGE_KEY_USER = "silk_user"

    fun getAccessToken(): String? {
        return try {
            localStorage.getItem(STORAGE_KEY_JWT)
        } catch (e: Exception) { null }
    }

    fun setAccessToken(token: String) {
        try { localStorage.setItem(STORAGE_KEY_JWT, token) } catch (_: Exception) {}
    }

    fun getRefreshToken(): String? {
        return try {
            localStorage.getItem(STORAGE_KEY_REFRESH)
        } catch (e: Exception) { null }
    }

    fun setRefreshToken(token: String) {
        try { localStorage.setItem(STORAGE_KEY_REFRESH, token) } catch (_: Exception) {}
    }

    fun getStoredUser(): User? {
        return try {
            val json = localStorage.getItem(STORAGE_KEY_USER)
            if (json != null) Json.decodeFromString<User>(json) else null
        } catch (e: Exception) { null }
    }

    fun setStoredUser(user: User) {
        try { localStorage.setItem(STORAGE_KEY_USER, Json.encodeToString(user)) } catch (_: Exception) {}
    }

    fun clearAll() {
        try {
            localStorage.removeItem(STORAGE_KEY_JWT)
            localStorage.removeItem(STORAGE_KEY_REFRESH)
            localStorage.removeItem(STORAGE_KEY_USER)
        } catch (_: Exception) {}
    }
}

/**
 * 华为 OAuth 认证响应
 */
@Serializable
data class HuaweiAuthResponse(
    val success: Boolean,
    val message: String = "",
    val user: User? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null
)

/**
 * Token 刷新响应
 */
@Serializable
data class TokenRefreshResponse(
    val success: Boolean,
    val message: String = "",
    val accessToken: String? = null
)

object ApiClient {
    private val BASE_URL: String
        get() {
            // 优先走同源，兼容 nginx 代理；
            // 仅在本地前端 dev server 直连场景下切到后端端口，避免跨端口登录失效。
            val protocol = window.location.protocol
            val hostname = window.location.hostname
            val origin = window.location.origin.let { if (it.endsWith("/")) it.dropLast(1) else it }
            val currentPort = window.location.port

            return if (currentPort == BuildConfig.FRONTEND_PORT) {
                "$protocol//$hostname:${BuildConfig.BACKEND_HTTP_PORT}"
            } else {
                origin
            }
        }
    private val jsonParser = Json { ignoreUnknownKeys = true }

    /**
     * 华为 Web OAuth 登录：发送 code 到后端交换 token
     */
    suspend fun huaweiWebLogin(code: String, redirectUri: String): HuaweiAuthResponse {
        return try {
            val body = jsonParser.encodeToString(
                buildJsonObject {
                    put("code", code)
                    put("redirectUri", redirectUri)
                }
            )
            val response = post("/auth/huawei/web-login", body)
            jsonParser.decodeFromString<HuaweiAuthResponse>(response)
        } catch (e: Exception) {
            console.log("华为登录失败:", e)
            HuaweiAuthResponse(false, "登录失败: ${e.message}")
        }
    }

    /**
     * 刷新 Access Token
     */
    suspend fun refreshAccessToken(): HuaweiAuthResponse {
        val refreshToken = JwtManager.getRefreshToken() ?: return HuaweiAuthResponse(false, "无 Refresh Token")
        return try {
            val body = """{"refreshToken":"$refreshToken"}"""
            val response = post("/auth/refresh", body)
            jsonParser.decodeFromString<HuaweiAuthResponse>(response)
        } catch (e: Exception) {
            console.log("Token 刷新失败:", e)
            HuaweiAuthResponse(false, "Token 刷新失败: ${e.message}")
        }
    }

    /**
     * 登出
     */
    suspend fun logout(): Boolean {
        val refreshToken = JwtManager.getRefreshToken() ?: return true
        return try {
            val body = """{"refreshToken":"$refreshToken"}"""
            post("/auth/logout", body)
            true
        } catch (e: Exception) {
            console.log("登出请求失败:", e)
            true // 即使失败也清除本地状态
        }
    }
    
    suspend fun getUserGroups(userId: String): GroupResponse {
        return try {
            val response = get("/groups/user/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取群组失败:", e)
            GroupResponse(false, "网络错误")
        }
    }
    
    /**
     * 获取用户所有群组的未读消息数
     */
    suspend fun getUnreadCounts(userId: String): UnreadCountResponse {
        return try {
            val response = get("/api/unread/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取未读数失败:", e)
            UnreadCountResponse(false)
        }
    }
    
    /**
     * 标记群组消息为已读
     */
    suspend fun markGroupAsRead(userId: String, groupId: String): Boolean {
        return try {
            val body = """{"userId":"$userId","groupId":"$groupId"}"""
            val response = post("/api/unread/mark-read", body)
            response.contains("\"success\":true") || response.contains("\"success\": true")
        } catch (e: Exception) {
            console.log("标记已读失败:", e)
            false
        }
    }
    
    suspend fun createGroup(userId: String, groupName: String, type: String? = null): GroupResponse {
        return try {
            val body = if (type != null) {
                """{"userId":"$userId","groupName":"$groupName","type":"$type"}"""
            } else {
                """{"userId":"$userId","groupName":"$groupName"}"""
            }
            val response = post("/groups/create", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("创建群组失败:", e)
            GroupResponse(false, "网络错误")
        }
    }
    
    suspend fun joinGroup(userId: String, invitationCode: String): GroupResponse {
        return try {
            val body = """{"userId":"$userId","invitationCode":"$invitationCode"}"""
            val response = post("/groups/join", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("加入群组失败:", e)
            GroupResponse(false, "网络错误")
        }
    }

    suspend fun getCcConnectTokenInfo(groupId: String, userId: String): CcConnectTokenInfo? {
        return try {
            val response = get("/api/ccconnect/groups/$groupId/token-info?userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取cc-connect信息失败:", e)
            null
        }
    }

    suspend fun regenerateCcConnectToken(groupId: String, userId: String): String? {
        return try {
            val response = post("/api/ccconnect/groups/$groupId/regenerate-token", """{"userId":"$userId"}""")
            val parsed = jsonParser.decodeFromString<CcConnectTokenInfo>(response)
            parsed.token
        } catch (e: Exception) {
            console.log("重新生成token失败:", e)
            null
        }
    }

    suspend fun setCcConnectOperator(groupId: String, userId: String, targetUserId: String, grant: Boolean): Boolean {
        return try {
            val body = """{"userId":"$userId","targetUserId":"$targetUserId","grant":$grant}"""
            val response = post("/api/ccconnect/groups/$groupId/set-operator", body)
            val parsed = jsonParser.decodeFromString<SimpleResponse>(response)
            parsed.success
        } catch (e: Exception) {
            console.log("设置operator失败:", e)
            false
        }
    }
    
    // ==================== 联系人相关 API ====================
    
    /**
     * 获取联系人列表（包含待处理请求）
     */
    suspend fun getContacts(userId: String): ContactResponse {
        return try {
            val response = get("/contacts/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取联系人失败:", e)
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 通过电话号码搜索用户
     */
    suspend fun searchUserByPhone(phoneNumber: String): UserSearchResult {
        return try {
            val response = get("/users/search?phone=$phoneNumber")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("搜索用户失败:", e)
            UserSearchResult(false, message = "网络错误")
        }
    }
    
    /**
     * 发送联系人请求（通过电话号码）
     */
    suspend fun sendContactRequest(fromUserId: String, toPhoneNumber: String): ContactResponse {
        return try {
            val body = """{"fromUserId":"$fromUserId","toPhoneNumber":"$toPhoneNumber"}"""
            val response = post("/contacts/request", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("发送联系人请求失败:", e)
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 发送联系人请求（通过用户ID）
     */
    suspend fun sendContactRequestById(fromUserId: String, toUserId: String): ContactResponse {
        return try {
            val body = """{"fromUserId":"$fromUserId","toUserId":"$toUserId"}"""
            val response = post("/contacts/request-by-id", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("发送联系人请求失败:", e)
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 处理联系人请求（接受/拒绝）
     */
    suspend fun handleContactRequest(requestId: String, userId: String, accept: Boolean): ContactResponse {
        return try {
            val body = """{"requestId":"$requestId","userId":"$userId","accept":$accept}"""
            val response = post("/contacts/handle-request", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("处理联系人请求失败:", e)
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 开始/获取私聊会话
     */
    suspend fun startPrivateChat(userId: String, contactId: String): PrivateChatResponse {
        return try {
            val body = """{"userId":"$userId","contactId":"$contactId"}"""
            val response = post("/contacts/private-chat", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取私聊会话失败:", e)
            PrivateChatResponse(false, "网络错误")
        }
    }
    
    /**
     * 开始/获取与 Silk AI 的专属私聊会话
     */
    suspend fun startSilkPrivateChat(userId: String): PrivateChatResponse {
        return try {
            val body = """{"userId":"$userId"}"""
            val response = post("/api/silk-private-chat", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取Silk私聊会话失败:", e)
            PrivateChatResponse(false, "网络错误")
        }
    }
    
    /**
     * 获取群组成员列表
     */
    suspend fun getGroupMembers(groupId: String): GroupMembersResponse {
        return try {
            val response = get("/groups/$groupId/members")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取群组成员失败:", e)
            GroupMembersResponse(false, emptyList())
        }
    }
    
    /**
     * 添加成员到群组
     */
    suspend fun addMemberToGroup(groupId: String, userId: String): AddMemberResponse {
        return try {
            val body = """{"userId":"$userId"}"""
            val response = post("/groups/$groupId/add-member", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("添加成员失败:", e)
            AddMemberResponse(false, "网络错误")
        }
    }
    
    /**
     * 退出群组
     */
    suspend fun leaveGroup(groupId: String, userId: String): LeaveGroupResponse {
        return try {
            val body = """{"userId":"$userId"}"""
            val response = post("/groups/$groupId/leave", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("退出群组失败:", e)
            LeaveGroupResponse(false, "网络错误")
        }
    }
    

    /**
     * 删除群组（仅群主可操作）
     */
    suspend fun deleteGroup(groupId: String, userId: String): SimpleResponse {
        return try {
            val body = """{"userId":"$userId"}"""
            val response = delete("/groups/$groupId", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("删除群组失败:", e)
            SimpleResponse(false, "网络错误")
        }
    }
    // ==================== 用户设置相关 API ====================

    /**
     * 获取用户设置
     */
    suspend fun getUserSettings(userId: String): UserSettingsResponse {
        return try {
            val response = get("/users/$userId/settings")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取用户设置失败:", e)
            UserSettingsResponse(false, "网络错误")
        }
    }

    /**
     * 更新用户设置
     */
    suspend fun updateUserSettings(userId: String, language: Language, defaultAgentInstruction: String): UserSettingsResponse {
        return try {
            val request = UpdateUserSettingsRequest(userId, language, defaultAgentInstruction)
            val body = jsonParser.encodeToString(UpdateUserSettingsRequest.serializer(), request)
            val response = put("/users/$userId/settings", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("更新用户设置失败:", e)
            UserSettingsResponse(false, "网络错误")
        }
    }

    // ==================== Claude Code 设置相关 API ====================

    /**
     * 获取 CC 设置（token + bridge 状态）
     */
    suspend fun getCcSettings(userId: String): CcSettingsResponse {
        return try {
            val response = get("/users/$userId/cc-settings")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取CC设置失败:", e)
            CcSettingsResponse(false, "网络错误")
        }
    }

    /**
     * 生成/重新生成 Bridge Token
     */
    suspend fun generateBridgeToken(userId: String): CcSettingsResponse {
        return try {
            val response = post("/users/$userId/cc-settings/generate-token", "{}")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("生成Bridge Token失败:", e)
            CcSettingsResponse(false, "网络错误")
        }
    }

    /**
     * 查询 Bridge 在线状态
     */
    suspend fun getBridgeStatus(userId: String): CcSettingsResponse {
        return try {
            val response = get("/users/$userId/cc-settings/bridge-status")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("查询Bridge状态失败:", e)
            CcSettingsResponse(false, "网络错误")
        }
    }

    /**
     * 查询 user+group 在 CC 模式下的当前状态（含工作目录），用于工作流前端显示
     */
    suspend fun getCcState(userId: String, groupId: String): CcStateResponse {
        return try {
            val response = get("/users/$userId/cc-state/$groupId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("查询CC状态失败:", e)
            CcStateResponse(success = false)
        }
    }

    /**
     * 列出 Bridge 机器上指定路径下的子目录（用于 Folder Picker）
     */
    suspend fun listCcDir(userId: String, path: String? = null, showHidden: Boolean = false): DirListingResponse {
        return try {
            val query = buildString {
                append("?showHidden=$showHidden")
                if (!path.isNullOrBlank()) {
                    append("&path=")
                    append(encodeUri(path))
                }
            }
            val response = get("/users/$userId/cc-fs/list$query")
            jsonParser.decodeFromString(response)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消必须原样往上抛，否则上层 Job.cancel() 无法真正中断加载
            throw e
        } catch (e: Exception) {
            console.log("列目录失败:", e)
            DirListingResponse(success = false, error = e.message ?: "网络错误")
        }
    }

    /**
     * 直接为 user+group 切换 Bridge 工作目录（不发聊天消息，不出现 /cd 气泡）。
     * groupId 可传 raw（如 "abc"）或已带前缀（如 "group_abc"），后端会兼容处理。
     */
    suspend fun cdCcDir(userId: String, groupId: String, path: String): CcStateResponse {
        return try {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("groupId", kotlinx.serialization.json.JsonPrimitive(groupId))
                put("path", kotlinx.serialization.json.JsonPrimitive(path))
            }.toString()
            val response = post("/users/$userId/cc-fs/cd", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("切换目录失败:", e)
            CcStateResponse(success = false)
        }
    }
    
    // ==================== 消息撤回相关 API ====================
    
    /**
     * 撤回消息
     * @param groupId 群组ID
     * @param messageId 要撤回的消息ID
     * @param userId 当前用户ID
     */
    suspend fun sendMessageToGroup(
        groupId: String,
        userId: String,
        userName: String,
        content: String
    ): Boolean {
        return try {
            val escapedContent = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            val body = """{"groupId":"$groupId","userId":"$userId","userName":"$userName","content":"$escapedContent"}"""
            val response = post("/api/messages/send", body)
            response.contains("\"success\":true") || response.contains("\"success\": true")
        } catch (e: Exception) {
            console.log("❌ 发送消息失败:", e)
            false
        }
    }

    suspend fun recallMessage(groupId: String, messageId: String, userId: String): SimpleResponse {
        return try {
            val body = """{"groupId":"$groupId","messageId":"$messageId","userId":"$userId"}"""
            val response = post("/api/messages/recall", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("撤回消息失败:", e)
            SimpleResponse(false, "网络错误")
        }
    }
    
    suspend fun deleteMessage(groupId: String, messageId: String, userId: String): SimpleResponse {
        return try {
            val body = """{"groupId":"$groupId","messageId":"$messageId","userId":"$userId"}"""
            val response = post("/api/messages/delete", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("删除消息失败:", e)
            SimpleResponse(false, "网络错误")
        }
    }

    suspend fun exportGroupMarkdown(groupId: String, userId: String): ExportMarkdownResponse {
        return try {
            val response = get("/groups/$groupId?export=obsidian_markdown&userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("导出聊天记录失败:", e)
            ExportMarkdownResponse(false, "网络错误: ${e.message}")
        }
    }
    
    /**
     * 获取 Authorization header
     */
    private fun authHeaders(): org.w3c.fetch.Headers {
        val headers = org.w3c.fetch.Headers()
        headers.append("Content-Type", "application/json")
        val token = JwtManager.getAccessToken()
        if (token != null) {
            headers.append("Authorization", "Bearer $token")
        }
        return headers
    }

    /**
     * 尝试刷新 Token：如果 401 且有 Refresh Token，自动刷新后重试
     */
    private suspend fun fetchWithRetry(endpoint: String, init: RequestInit): org.w3c.fetch.Response {
        val url = "$BASE_URL$endpoint"
        var response = window.fetch(url, init).await()
        
        // 401 自动刷新 Token
        if (response.status.toInt() == 401) {
            val refreshResult = refreshAccessToken()
            if (refreshResult.success && refreshResult.accessToken != null) {
                JwtManager.setAccessToken(refreshResult.accessToken!!)
                // 用新 Token 重建 headers
                val newHeaders = org.w3c.fetch.Headers()
                newHeaders.append("Content-Type", "application/json")
                newHeaders.append("Authorization", "Bearer ${refreshResult.accessToken}")
                val retryInit = if (init.body != null) {
                    RequestInit(method = init.method, headers = newHeaders, body = init.body)
                } else {
                    RequestInit(method = init.method, headers = newHeaders)
                }
                response = window.fetch(url, retryInit).await()
            }
        }
        
        return response
    }
    
    private suspend fun post(endpoint: String, jsonBody: String): String {
        val init = RequestInit(
            method = "POST",
            headers = authHeaders(),
            body = jsonBody
        )
        
        val response = fetchWithRetry(endpoint, init)
        return response.text().await()
    }
    
    private suspend fun put(endpoint: String, jsonBody: String): String {
        val init = RequestInit(
            method = "PUT",
            headers = authHeaders(),
            body = jsonBody
        )
        
        val response = fetchWithRetry(endpoint, init)
        
        if (!response.ok) {
            throw Exception("HTTP ${response.status}: ${response.statusText}")
        }
        
        return response.text().await()
    }
    
    private suspend fun delete(endpoint: String, jsonBody: String): String {
        val init = RequestInit(
            method = "DELETE",
            headers = authHeaders(),
            body = jsonBody
        )
        
        val response = fetchWithRetry(endpoint, init)
        return response.text().await()
    }

    private suspend fun get(endpoint: String): String {
        val init = RequestInit(
            method = "GET",
            headers = authHeaders()
        )
        
        val response = fetchWithRetry(endpoint, init)
        return response.text().await()
    }

    /** URL-encode a string via JS's encodeURIComponent. */
    private fun encodeUri(s: String): String = js("encodeURIComponent")(s).unsafeCast<String>()

    // ==================== Workflow API ====================

    suspend fun getWorkflows(userId: String): List<WorkflowItem> {
        return try {
            val response = get("/api/workflows?userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取工作流失败:", e)
            emptyList()
        }
    }

    /** 列出可作为 workflow agent 的选项（含 bridge agent 与 silk_chat）。失败时返回空列表。 */
    suspend fun listAgents(userId: String): List<AgentInfo> {
        return try {
            val response = get("/api/agents?userId=${encodeUri(userId)}")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取 agent 列表失败:", e)
            emptyList()
        }
    }

    /** 创建工作流的结果。Ok 携带后端落库后的 workflow；Err 携带可展示给用户的错误消息。 */
    sealed class CreateWorkflowResult {
        data class Ok(val workflow: WorkflowItem) : CreateWorkflowResult()
        data class Err(val message: String) : CreateWorkflowResult()
    }

    suspend fun createWorkflow(
        name: String,
        description: String,
        userId: String,
        initialDir: String = "",
        agentType: String = "claude_code",
    ): CreateWorkflowResult {
        return try {
            // 构造 JSON，使用 JsonObject 安全编码避免手动转义
            val obj = kotlinx.serialization.json.buildJsonObject {
                put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("description", kotlinx.serialization.json.JsonPrimitive(description))
                if (initialDir.isNotBlank()) {
                    put("initialDir", kotlinx.serialization.json.JsonPrimitive(initialDir))
                }
                if (agentType.isNotBlank()) {
                    put("agentType", kotlinx.serialization.json.JsonPrimitive(agentType))
                }
            }
            val response = post("/api/workflows", obj.toString())
            // 响应有两种形状：
            //   成功：Workflow 对象（有 id/groupId 等字段，无 success 字段）
            //   失败：错误信封 {"success": false, "message": "..."}
            // 优先用 success 字段区分，避免"错误响应恰好含 id 字段"这种 ignoreUnknownKeys 陷阱
            val parsed = try {
                kotlinx.serialization.json.Json.parseToJsonElement(response).jsonObject
            } catch (_: Exception) { null }
            if (parsed == null) {
                CreateWorkflowResult.Err("服务器返回了无法识别的响应")
            } else {
                val success = parsed["success"]?.jsonPrimitive?.booleanOrNull
                // success 字段存在且为 false → 错误信封；未出现 success 字段 → 视为 Workflow 对象
                if (success == false) {
                    val msg = parsed["message"]?.jsonPrimitive?.contentOrNull ?: "创建失败"
                    CreateWorkflowResult.Err(msg)
                } else {
                    try {
                        CreateWorkflowResult.Ok(jsonParser.decodeFromString<WorkflowItem>(response))
                    } catch (_: Exception) {
                        CreateWorkflowResult.Err("解析创建结果失败")
                    }
                }
            }
        } catch (e: Exception) {
            console.log("创建工作流失败:", e)
            CreateWorkflowResult.Err(e.message ?: "网络错误")
        }
    }

    suspend fun renameWorkflow(workflowId: String, userId: String, newName: String): WorkflowItem? {
        return try {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                put("name", kotlinx.serialization.json.JsonPrimitive(newName))
            }.toString()
            val response = put("/api/workflows/$workflowId", body)
            jsonParser.decodeFromString<WorkflowItem>(response)
        } catch (e: Exception) {
            console.log("重命名工作流失败:", e)
            null
        }
    }

    suspend fun deleteWorkflow(workflowId: String, userId: String): Boolean {
        return try {
            val response = window.fetch(
                "$BASE_URL/api/workflows/$workflowId?userId=$userId",
                RequestInit(method = "DELETE")
            ).await()
            response.ok
        } catch (e: Exception) {
            console.log("删除工作流失败:", e)
            false
        }
    }

    // ==================== Trusted Directory API ====================

    sealed class TrustCheckResult {
        data class Trusted(val bridgeId: String?) : TrustCheckResult()
        data class NotTrusted(val bridgeId: String?) : TrustCheckResult()
        object BridgeDisconnected : TrustCheckResult()
        data class Error(val message: String) : TrustCheckResult()
    }

    suspend fun checkTrustedDir(userId: String, path: String): TrustCheckResult {
        return try {
            val query = "?path=${encodeUri(path)}"
            val response = get("/users/$userId/trusted-dirs/check$query")
            val parsed = jsonParser.decodeFromString<TrustedDirCheckResponse>(response)
            when {
                !parsed.bridgeConnected -> TrustCheckResult.BridgeDisconnected
                parsed.trusted -> TrustCheckResult.Trusted(parsed.bridgeId)
                else -> TrustCheckResult.NotTrusted(parsed.bridgeId)
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            console.log("解析信任目录检查响应失败:", e)
            TrustCheckResult.Error("服务器返回了无法识别的响应")
        } catch (e: Exception) {
            console.log("检查信任目录失败:", e)
            TrustCheckResult.Error("网络错误: ${e.message}")
        }
    }

    suspend fun addTrustedDir(userId: String, path: String): Boolean {
        return try {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive(path))
            }.toString()
            val response = post("/users/$userId/trusted-dirs", body)
            val json = jsonParser.parseToJsonElement(response).jsonObject
            json["success"]?.jsonPrimitive?.booleanOrNull ?: false
        } catch (e: Exception) {
            console.log("添加信任目录失败:", e)
            false
        }
    }

    // ==================== Knowledge Base API ====================

    suspend fun getKBTopics(userId: String): List<KBTopicItem> {
        return try {
            val response = get("/api/kb/topics?userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取知识库主题失败:", e)
            emptyList()
        }
    }

    suspend fun createKBTopic(name: String, project: String, userId: String): KBTopicItem? {
        return try {
            val body = """{"userId":"$userId","name":"$name","project":"$project"}"""
            val response = post("/api/kb/topics", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("创建知识库主题失败:", e)
            null
        }
    }

    suspend fun getKBEntries(topicId: String, userId: String): List<KBEntryItem> {
        return try {
            val response = get("/api/kb/entries?topicId=$topicId&userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取知识库条目失败:", e)
            emptyList()
        }
    }

    suspend fun createKBEntry(topicId: String, title: String, content: String, tags: List<String>, userId: String): KBEntryItem? {
        return try {
            val tagsJson = tags.joinToString(",") { "\"$it\"" }
            val body = """{"userId":"$userId","topicId":"$topicId","title":"$title","content":"$content","tags":[$tagsJson]}"""
            val response = post("/api/kb/entries", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("创建知识库条目失败:", e)
            null
        }
    }

    suspend fun updateKBEntry(entryId: String, title: String?, content: String?, tags: List<String>?, userId: String): KBEntryItem? {
        return try {
            val fields = mutableListOf("\"userId\":\"$userId\"")
            if (title != null) fields.add("\"title\":\"${title.replace("\"", "\\\"")}\"")
            if (content != null) {
                val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                fields.add("\"content\":\"$escaped\"")
            }
            if (tags != null) fields.add("\"tags\":[${tags.joinToString(",") { "\"$it\"" }}]")
            val body = "{${fields.joinToString(",")}}"
            val response = put("/api/kb/entries/$entryId", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("更新知识库条目失败:", e)
            null
        }
    }

    suspend fun exportKBEntry(entryId: String): ExportKBResponse? {
        return try {
            val response = get("/api/kb/entries/$entryId/export")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("导出知识库条目失败:", e)
            null
        }
    }

    // ==================== ASR 语音识别 API ====================

    suspend fun transcribeAudio(audioBase64: String, format: String = "webm"): AsrResult {
        return try {
            val body = """{"audio":"$audioBase64","format":"$format"}"""
            val responseText = post("/api/asr/transcribe", body)
            val json = jsonParser.parseToJsonElement(responseText).jsonObject
            val success = json["success"]?.jsonPrimitive?.booleanOrNull ?: false
            val text = json["text"]?.jsonPrimitive?.contentOrNull ?: ""
            val error = json["error"]?.jsonPrimitive?.contentOrNull
            AsrResult(success, text, error)
        } catch (e: Exception) {
            console.log("语音识别请求失败:", e)
            AsrResult(false, "", "语音识别失败: ${e.message}")
        }
    }

    suspend fun deleteFile(sessionId: String, fileId: String): SimpleResponse {
        val response = delete("/api/files/$sessionId/$fileId", "{}")
        return jsonParser.decodeFromString(response)
    }
}

data class AsrResult(val success: Boolean, val text: String, val error: String? = null)
