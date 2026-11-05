package com.silk.web

import com.silk.shared.models.Language
import com.silk.shared.models.UpdateUserSettingsRequest
import com.silk.shared.models.UserSettingsResponse
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
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
    val user: User? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null
)

@Serializable
data class GroupResponse(
    val success: Boolean,
    val message: String,
    val group: Group? = null,
    val groups: List<Group>? = null,
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
data class UserSearchItem(
    val id: String,
    val fullName: String,
    val loginName: String = "",
)

@Serializable
data class UserSearchByNameResponse(
    val success: Boolean,
    val users: List<UserSearchItem> = emptyList(),
    val message: String = "",
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

// ==================== Knowledge Base models ====================

@Serializable
enum class KnowledgeSpaceType {
    PERSONAL,
    TEAM,
}

@Serializable
data class KBAccessPolicy(
    val readUserIds: List<String> = emptyList(),
    val writeUserIds: List<String> = emptyList(),
    val manageUserIds: List<String> = emptyList(),
    val writeLocked: Boolean = false,
    val teamMembersCanWrite: Boolean = true,
)

@Serializable
enum class KBEntryStatus {
    CANDIDATE,
    PUBLISHED,
    ARCHIVED,
    DELETED,
}

@Serializable
enum class KBSourceType {
    MANUAL,
    CHAT,
    AI_RESPONSE,
    WORKFLOW,
    MEETING,
    FILE,
    URL,
}

@Serializable
enum class KBMemoryType {
    PROFILE,
    PREFERENCE,
    EPISODIC,
    PROCEDURAL,
}

@Serializable
data class KBFileRef(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val downloadUrl: String,
    val sourceMessageId: String? = null,
)

@Serializable
data class KBEntrySource(
    val sourceType: KBSourceType = KBSourceType.MANUAL,
    val sourceGroupId: String? = null,
    val workflowId: String? = null,
    val messageIds: List<String> = emptyList(),
    val confidence: Double? = null,
    val fileRef: KBFileRef? = null,
)

@Serializable
data class ArchivedMemoryVersion(
    val content: String,
    val title: String,
    val archivedAt: Long,
    val reason: String = "",
)

@Serializable
data class KBMemoryMetadata(
    val type: KBMemoryType = KBMemoryType.EPISODIC,
    val key: String? = null,
    val explicit: Boolean = true,
    val capturedAt: Long = 0L,
    val lastAccessedAt: Long = 0L,
    val accessedCount: Int = 0,
    val archivedVersions: List<ArchivedMemoryVersion> = emptyList(),
)

@Serializable
data class KBTopicItem(
    val id: String,
    val name: String,
    val project: String = "",
    val ownerId: String = "",
    val spaceType: KnowledgeSpaceType = KnowledgeSpaceType.PERSONAL,
    val groupId: String? = null,
    val accessPolicy: KBAccessPolicy = KBAccessPolicy(),
    val createdBy: String = "",
    val updatedBy: String = "",
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
    val status: KBEntryStatus = KBEntryStatus.PUBLISHED,
    val source: KBEntrySource = KBEntrySource(),
    val memory: KBMemoryMetadata? = null,
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Serializable
data class KnowledgeBaseContextPreferences(
    val userId: String,
    val excludedSpaceIds: List<String> = emptyList(),
    val downrankedSpaceIds: List<String> = emptyList(),
    val memoryEnabled: Boolean = true,
    val autoCaptureEnabled: Boolean = false,
    val ephemeralSessionEnabled: Boolean = false,
    val updatedAt: Long = 0L,
)

@Serializable
data class ExportKBResponse(
    val success: Boolean,
    val markdown: String = "",
    val vaultPath: String = "",
    val fileName: String = ""
)

@Serializable
data class DiffChunk(
    val type: String, // "unchanged" | "deleted" | "inserted" | "modified"
    val originalText: String = "",
    val newText: String = "",
    val lineStart: Int = 0,
    val lineEnd: Int = 0,
)

@Serializable
data class KnowledgeBaseCopilotDraft(
    val entryId: String? = null,
    val topicId: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val diffChunks: List<DiffChunk> = emptyList(),
)

/**
 * KB 条目搜索结果（$ 快捷引用用）。
 */
@Serializable
data class KnowledgeBaseEntrySearchResult(
    val entryId: String,
    val title: String,
    val topicName: String,
    val spaceLabel: String,
)

@Serializable
data class KnowledgeBaseEntrySearchResponse(
    val entries: List<KnowledgeBaseEntrySearchResult>,
)

/**
 * A single turn in a multi-turn KB Copilot conversation.
 */
@Serializable
data class ConversationTurn(
    val role: String, // "user" or "assistant"
    val content: String,
)

@Serializable
data class KnowledgeBaseCopilotResponse(
    val success: Boolean,
    val assistantReply: String = "",
    val draft: KnowledgeBaseCopilotDraft? = null,
    val appliedEntry: KBEntryItem? = null,
    val message: String = "",
)
/**
 * JWT 令牌管理
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
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
    val refreshToken: String? = null,
    val isNewUser: Boolean = false
)

/**
 * 微信 OAuth 认证响应
 */
@Serializable
data class WechatAuthResponse(
    val success: Boolean,
    val message: String = "",
    val user: User? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val isNewUser: Boolean = false
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

@Suppress("LargeClass", "TooGenericExceptionCaught", "SwallowedException")
object ApiClient {
    private val BASE_URL: String
        get() {
            val hostname = window.location.hostname
            val origin = window.location.origin.let { if (it.endsWith("/")) it.dropLast(1) else it }

            // 生产环境（非 localhost）始终走同源，配合 nginx 代理后端 API，避免跨端口 CORS 问题
            // 本地开发环境（localhost/127.0.0.1）且端口匹配时切到后端端口
            val isLocalDev = hostname == "localhost" || hostname == "127.0.0.1"
            return if (isLocalDev) {
                val protocol = window.location.protocol
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
            val body = """{"code":"$code","redirectUri":"$redirectUri"}"""
            val response = post("/auth/huawei/web-login", body)
            jsonParser.decodeFromString<HuaweiAuthResponse>(response)
        } catch (e: Exception) {
            console.log("华为登录失败:", e)
            HuaweiAuthResponse(false, "登录失败: ${e.message}")
        }
    }

    /**
     * 华为 OAuth 账号绑定：将华为账号绑定到当前登录用户
     */
    suspend fun huaweiBind(code: String, redirectUri: String): AuthResponse {
        return try {
            val body = """{"code":"$code","redirectUri":"$redirectUri"}"""
            val response = post("/api/account/bind-huawei", body)
            jsonParser.decodeFromString<AuthResponse>(response)
        } catch (e: Exception) {
            console.log("华为绑定失败:", e)
            AuthResponse(false, "绑定失败: ${e.message}")
        }
    }

    /**
     * 微信 OAuth 登录：发送 code 到后端交换 token
     * 适用于 Web 端扫码登录和 Android 端 SDK 登录
     */
    suspend fun wechatLogin(code: String): WechatAuthResponse {
        return try {
            val body = """{"code":"$code"}"""
            val response = post("/auth/wechat/login", body)
            jsonParser.decodeFromString<WechatAuthResponse>(response)
        } catch (e: Exception) {
            console.log("微信登录失败:", e)
            WechatAuthResponse(false, "登录失败: ${e.message}")
        }
    }

    /**
     * 用户名/密码登录
     */
    suspend fun login(loginName: String, password: String): AuthResponse {
        return try {
            val body = """{"loginName":"$loginName","password":"$password"}"""
            val response = post("/auth/login", body)
            jsonParser.decodeFromString<AuthResponse>(response)
        } catch (e: Exception) {
            console.log("登录失败:", e)
            AuthResponse(false, "登录失败: ${e.message}")
        }
    }

    /**
     * 更新用户昵称
     */
    suspend fun updateUserProfile(userId: String, fullName: String): SimpleResponse {
        return try {
            val body = """{"fullName":"$fullName"}"""
            val response = put("/users/$userId/profile", body)
            jsonParser.decodeFromString<SimpleResponse>(response)
        } catch (e: Exception) {
            console.log("更新用户资料失败:", e)
            SimpleResponse(false, "网络错误")
        }
    }

    /**
     * 注销账号（删除用户及其所有数据）
     */
    suspend fun deleteAccount(userId: String): SimpleResponse {
        return try {
            val response = delete("/users/$userId/account", "")
            jsonParser.decodeFromString<SimpleResponse>(response)
        } catch (e: Exception) {
            console.log("注销账号失败:", e)
            SimpleResponse(false, "网络错误")
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
    
    suspend fun createGroup(userId: String, groupName: String): GroupResponse {
        return try {
            val body = """{"userId":"$userId","groupName":"$groupName"}"""
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
     * 按名称关键词搜索用户（用于知识库分享等场景）
     */
    suspend fun searchUsersByName(query: String): UserSearchByNameResponse {
        return try {
            val response = get("/api/users/search-by-name?q=${encodeUri(query)}")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("搜索用户失败:", e)
            UserSearchByNameResponse(success = false, message = "网络错误")
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
            @Suppress("TooGenericExceptionThrown")
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

    suspend fun createKBTopic(
        name: String,
        project: String,
        userId: String,
        spaceType: KnowledgeSpaceType = KnowledgeSpaceType.PERSONAL,
        groupId: String? = null,
        accessPolicy: KBAccessPolicy = KBAccessPolicy(),
    ): KBTopicItem? {
        return try {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("project", kotlinx.serialization.json.JsonPrimitive(project))
                put("spaceType", kotlinx.serialization.json.JsonPrimitive(spaceType.name))
                groupId?.takeIf { it.isNotBlank() }?.let {
                    put("groupId", kotlinx.serialization.json.JsonPrimitive(it))
                }
                put(
                    "accessPolicy",
                    jsonParser.encodeToJsonElement(KBAccessPolicy.serializer(), accessPolicy)
                )
            }.toString()
            val response = post("/api/kb/topics", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("创建知识库主题失败:", e)
            null
        }
    }

    suspend fun updateKBTopic(
        topicId: String,
        userId: String,
        name: String? = null,
        project: String? = null,
        accessPolicy: KBAccessPolicy? = null,
    ): KBTopicItem? {
        return try {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                name?.let { put("name", kotlinx.serialization.json.JsonPrimitive(it)) }
                project?.let { put("project", kotlinx.serialization.json.JsonPrimitive(it)) }
                accessPolicy?.let {
                    put(
                        "accessPolicy",
                        jsonParser.encodeToJsonElement(KBAccessPolicy.serializer(), it)
                    )
                }
            }.toString()
            val response = put("/api/kb/topics/$topicId", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("更新知识库主题失败:", e)
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

    // KB 内联引用：按 entryId 取单条 entry 详情（[[kb:entryId|标题]] 预览/导航用）
    suspend fun getKBEntry(entryId: String, userId: String): KBEntryItem? {
        return try {
            val response = get("/api/kb/entries/$entryId?userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取知识库条目详情失败:", e)
            null
        }
    }

    // $ 快捷引用搜索 KB 条目
    suspend fun searchKbEntries(userId: String, query: String): List<KnowledgeBaseEntrySearchResult> {
        return try {
            val encoded = js("encodeURIComponent(query)")
            val response = get("/api/kb/entries/search?userId=$userId&q=$encoded")
            val parsed = jsonParser.decodeFromString<KnowledgeBaseEntrySearchResponse>(response)
            parsed.entries
        } catch (e: Exception) {
            console.log("搜索 KB 条目失败:", e)
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

    suspend fun captureKBEntry(
        topicId: String,
        title: String,
        content: String,
        tags: List<String>,
        userId: String,
        source: KBEntrySource,
        status: KBEntryStatus? = null,
    ): KBEntryItem? {
        return try {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                put("topicId", kotlinx.serialization.json.JsonPrimitive(topicId))
                put("title", kotlinx.serialization.json.JsonPrimitive(title))
                put("content", kotlinx.serialization.json.JsonPrimitive(content))
                put(
                    "tags",
                    kotlinx.serialization.json.JsonArray(tags.map { kotlinx.serialization.json.JsonPrimitive(it) })
                )
                put(
                    "source",
                    jsonParser.encodeToJsonElement(KBEntrySource.serializer(), source)
                )
                status?.let {
                    put("status", kotlinx.serialization.json.JsonPrimitive(it.name))
                }
            }.toString()
            val response = post("/api/kb/captures", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("保存知识库候选失败:", e)
            null
        }
    }

    suspend fun updateKBEntry(
        entryId: String,
        topicId: String? = null,
        title: String?,
        content: String?,
        tags: List<String>?,
        userId: String,
        status: KBEntryStatus? = null,
    ): KBEntryItem? {
        return try {
            val fields = mutableListOf("\"userId\":\"$userId\"")
            if (topicId != null) fields.add("\"topicId\":\"${topicId.replace("\"", "\\\"")}\"")
            if (title != null) fields.add("\"title\":\"${title.replace("\"", "\\\"")}\"")
            if (content != null) {
                val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                fields.add("\"content\":\"$escaped\"")
            }
            if (tags != null) fields.add("\"tags\":[${tags.joinToString(",") { "\"$it\"" }}]")
            if (status != null) fields.add("\"status\":\"${status.name}\"")
            val body = "{${fields.joinToString(",")}}"
            val response = put("/api/kb/entries/$entryId", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("更新知识库条目失败:", e)
            null
        }
    }

    suspend fun deleteKBTopic(topicId: String, userId: String): SimpleResponse {
        return try {
            val body = """{"userId":"$userId"}"""
            val response = delete("/api/kb/topics/$topicId", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("删除知识库主题失败:", e)
            SimpleResponse(false, "网络错误")
        }
    }

    suspend fun deleteKBEntry(entryId: String, userId: String): SimpleResponse {
        return try {
            val body = """{"userId":"$userId"}"""
            val response = delete("/api/kb/entries/$entryId", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("删除知识库条目失败:", e)
            SimpleResponse(false, "网络错误")
        }
    }

    suspend fun exportKBEntry(entryId: String, userId: String): ExportKBResponse? {
        return try {
            val response = get("/api/kb/entries/$entryId/export?userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("导出知识库条目失败:", e)
            null
        }
    }

    suspend fun runKBCopilot(
        userId: String,
        entryId: String?,
        topicId: String? = null,
        instruction: String,
        applyChanges: Boolean,
        conversationHistory: List<ConversationTurn> = emptyList(),
    ): KnowledgeBaseCopilotResponse? {
        return try {
            val body = buildJsonObject {
                put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                if (!entryId.isNullOrBlank()) {
                    put("entryId", kotlinx.serialization.json.JsonPrimitive(entryId))
                }
                if (!topicId.isNullOrBlank()) {
                    put("topicId", kotlinx.serialization.json.JsonPrimitive(topicId))
                }
                put("instruction", kotlinx.serialization.json.JsonPrimitive(instruction))
                put("applyChanges", kotlinx.serialization.json.JsonPrimitive(applyChanges))
                if (conversationHistory.isNotEmpty()) {
                    put("conversationHistory", kotlinx.serialization.json.JsonArray(
                        conversationHistory.map { turn ->
                            kotlinx.serialization.json.JsonObject(
                                mapOf(
                                    "role" to kotlinx.serialization.json.JsonPrimitive(turn.role),
                                    "content" to kotlinx.serialization.json.JsonPrimitive(turn.content),
                                )
                            )
                        }
                    ))
                }
            }.toString()
            val response = post("/api/kb/copilot", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("运行 KB Copilot 失败:", e)
            null
        }
    }

    /**
     * Stream KB Copilot via SSE.
     * Uses fetch to POST to /api/kb/copilot/stream and parses SSE events incrementally.
     * @param onEvent called for each SSE event: (eventType, data)
     * @return the final KnowledgeBaseCopilotResponse built from streamed events
     */
    suspend fun streamKBCopilot(
        userId: String,
        entryId: String?,
        topicId: String?,
        instruction: String,
        applyChanges: Boolean,
        conversationHistory: List<ConversationTurn>,
        onEvent: (event: String, data: String) -> Unit,
    ): KnowledgeBaseCopilotResponse? {
        return try {
            val body = buildJsonObject {
                put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                if (!entryId.isNullOrBlank()) {
                    put("entryId", kotlinx.serialization.json.JsonPrimitive(entryId))
                }
                if (!topicId.isNullOrBlank()) {
                    put("topicId", kotlinx.serialization.json.JsonPrimitive(topicId))
                }
                put("instruction", kotlinx.serialization.json.JsonPrimitive(instruction))
                put("applyChanges", kotlinx.serialization.json.JsonPrimitive(applyChanges))
                if (conversationHistory.isNotEmpty()) {
                    put("conversationHistory", kotlinx.serialization.json.JsonArray(
                        conversationHistory.map { turn ->
                            kotlinx.serialization.json.JsonObject(
                                mapOf(
                                    "role" to kotlinx.serialization.json.JsonPrimitive(turn.role),
                                    "content" to kotlinx.serialization.json.JsonPrimitive(turn.content),
                                )
                            )
                        }
                    ))
                }
            }.toString()

            val headers = authHeaders()
            val init = RequestInit(
                method = "POST",
                headers = headers,
                body = body,
            )
            val url = "$BASE_URL/api/kb/copilot/stream"
            val response = window.fetch(url, init).await()

            if (!response.ok) {
                console.log("KBCopilot SSE 请求失败:", response.status)
                return null
            }

            // Consume SSE response (non-streaming fallback via response.text())
            // True ReadableStream streaming will be added in a follow-up iteration
            var draft: KnowledgeBaseCopilotDraft? = null
            var appliedEntry: KBEntryItem? = null
            var assistantReply = ""
            var success = false
            var message = ""

            // Parse SSE events from full response text
            val fullText = response.text().await()
            val rawEvents = fullText.split("\n\n")
            for (rawEvent in rawEvents) {
                val lines = rawEvent.split("\n")
                var eventType = ""
                var eventData = ""
                for (line in lines) {
                    if (line.startsWith("event: ")) eventType = line.removePrefix("event: ")
                    else if (line.startsWith("data: ")) {
                        eventData = line.removePrefix("data: ")
                            .replace("\\n", "\n")
                    }
                }
                if (eventType.isNotEmpty()) {
                    onEvent(eventType, eventData)
                    when (eventType) {
                        "text" -> assistantReply = eventData
                        "draft" -> {
                            try {
                                draft = jsonParser.decodeFromString<KnowledgeBaseCopilotDraft>(eventData)
                            } catch (e: Exception) {
                                console.log("解析 draft 失败:", e)
                            }
                        }
                        "applied" -> {
                            try {
                                appliedEntry = jsonParser.decodeFromString<KBEntryItem>(eventData)
                            } catch (e: Exception) {
                                console.log("解析 appliedEntry 失败:", e)
                            }
                        }
                        "error" -> message = eventData
                        "done" -> success = true
                    }
                }
            }

            KnowledgeBaseCopilotResponse(
                success = success,
                assistantReply = assistantReply,
                draft = draft,
                appliedEntry = appliedEntry,
                message = message,
            )
        } catch (e: Exception) {
            console.log("KBCopilot 流式请求失败:", e)
            null
        }
    }

    // ── Group Assets ──

    @Serializable
    data class GroupAssetFile(
        val fileId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val uploadTime: Long,
        val downloadUrl: String,
        val hasLinkedEntry: Boolean = false,
        val linkedEntryIds: List<String> = emptyList(),
        val sourceMessageId: String? = null,
        val sourceType: String = "upload",
    )

    @Serializable
    data class GroupAssetsResponse(
        val groupId: String,
        val files: List<GroupAssetFile> = emptyList(),
        val kbEntries: List<KBEntryItem> = emptyList(),
        val kbTopics: List<KBTopicItem> = emptyList(),
    )

    suspend fun getGroupAssets(groupId: String, userId: String): GroupAssetsResponse? {
        return try {
            val response = get("/api/kb/group-assets/$groupId?userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取群空间资产失败:", e)
            null
        }
    }

    suspend fun linkFileToKBEntry(
        entryId: String,
        userId: String,
        groupId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        sourceMessageId: String? = null,
    ): KBEntryItem? {
        return try {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                put("groupId", kotlinx.serialization.json.JsonPrimitive(groupId))
                put("fileName", kotlinx.serialization.json.JsonPrimitive(fileName))
                put("fileSize", kotlinx.serialization.json.JsonPrimitive(fileSize.toString()))
                put("mimeType", kotlinx.serialization.json.JsonPrimitive(mimeType))
                sourceMessageId?.let { put("sourceMessageId", kotlinx.serialization.json.JsonPrimitive(it)) }
            }.toString()
            val response = post("/api/kb/entries/$entryId/link-file", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("关联文件到 KB 条目失败:", e)
            null
        }
    }

    suspend fun getKBContextPreferences(userId: String): KnowledgeBaseContextPreferences {
        return try {
            val response = get("/api/kb/context-preferences?userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取知识库上下文偏好失败:", e)
            KnowledgeBaseContextPreferences(userId = userId)
        }
    }

    suspend fun updateKBContextPreferences(
        userId: String,
        excludedSpaceIds: List<String>,
        downrankedSpaceIds: List<String> = emptyList(),
        memoryEnabled: Boolean,
        autoCaptureEnabled: Boolean,
        ephemeralSessionEnabled: Boolean,
    ): KnowledgeBaseContextPreferences? {
        return try {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                put(
                    "excludedSpaceIds",
                    kotlinx.serialization.json.JsonArray(excludedSpaceIds.map { kotlinx.serialization.json.JsonPrimitive(it) })
                )
                put(
                    "downrankedSpaceIds",
                    kotlinx.serialization.json.JsonArray(downrankedSpaceIds.map { kotlinx.serialization.json.JsonPrimitive(it) })
                )
                put("memoryEnabled", kotlinx.serialization.json.JsonPrimitive(memoryEnabled))
                put("autoCaptureEnabled", kotlinx.serialization.json.JsonPrimitive(autoCaptureEnabled))
                put("ephemeralSessionEnabled", kotlinx.serialization.json.JsonPrimitive(ephemeralSessionEnabled))
            }.toString()
            val response = put("/api/kb/context-preferences", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("更新知识库上下文偏好失败:", e)
            null
        }
    }

    suspend fun listKBMemoryEntries(userId: String, groupId: String? = null): List<KBEntryItem> {
        return try {
            val url = buildString {
                append("/api/kb/memory?userId=$userId")
                if (groupId != null) append("&groupId=$groupId")
            }
            val response = get(url)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取 memory 列表失败:", e)
            emptyList()
        }
    }

    suspend fun createKBMemoryEntry(
        userId: String,
        content: String,
        memoryType: KBMemoryType? = null,
        title: String? = null,
        key: String? = null,
        groupId: String? = null,
    ): KBEntryItem? {
        return try {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                put("content", kotlinx.serialization.json.JsonPrimitive(content))
                memoryType?.let { put("memoryType", kotlinx.serialization.json.JsonPrimitive(it.name)) }
                title?.takeIf { it.isNotBlank() }?.let { put("title", kotlinx.serialization.json.JsonPrimitive(it)) }
                key?.takeIf { it.isNotBlank() }?.let { put("key", kotlinx.serialization.json.JsonPrimitive(it)) }
                groupId?.let { put("groupId", kotlinx.serialization.json.JsonPrimitive(it)) }
            }.toString()
            val response = post("/api/kb/memory", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("创建 memory 失败:", e)
            null
        }
    }

    suspend fun deleteKBMemoryEntry(entryId: String, userId: String, groupId: String? = null): Boolean {
        return try {
            val url = buildString {
                append("$BASE_URL/api/kb/memory/$entryId?userId=$userId")
                if (groupId != null) append("&groupId=$groupId")
            }
            val response = window.fetch(
                url,
                RequestInit(method = "DELETE", headers = authHeaders())
            ).await()
            response.ok
        } catch (e: Exception) {
            console.log("删除 memory 失败:", e)
            false
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
