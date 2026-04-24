package com.silk.web

import com.silk.shared.models.*
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
    val groups: List<Group>? = null
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
    val phone: String = ""
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
    /** 仅 POST /api/workflows 创建响应可能带：初始目录切换失败的原因。其他场景始终为 null */
    val initialDirError: String? = null,
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

    
    suspend fun register(
        loginName: String,
        fullName: String,
        phoneNumber: String,
        password: String
    ): AuthResponse {
        return try {
            val body = """{"loginName":"$loginName","fullName":"$fullName","phoneNumber":"$phoneNumber","password":"$password"}"""
            val response = post("/auth/register", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("注册失败:", e)
            AuthResponse(false, "网络错误: ${e.message}")
        }
    }
    
    suspend fun login(loginName: String, password: String): AuthResponse {
        return try {
            val body = """{"loginName":"$loginName","password":"$password"}"""
            val response = post("/auth/login", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("登录失败:", e)
            AuthResponse(false, "网络错误")
        }
    }
    
    suspend fun validateUser(userId: String): AuthResponse {
        return try {
            val response = get("/auth/validate/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            AuthResponse(false, "验证失败")
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

    suspend fun exportGroupMarkdown(groupId: String, userId: String): ExportMarkdownResponse {
        return try {
            val response = get("/groups/$groupId?export=obsidian_markdown&userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("导出聊天记录失败:", e)
            ExportMarkdownResponse(false, "网络错误: ${e.message}")
        }
    }
    
    private suspend fun post(endpoint: String, jsonBody: String): String {
        val headers = org.w3c.fetch.Headers()
        headers.append("Content-Type", "application/json")
        
        val init = RequestInit(
            method = "POST",
            headers = headers,
            body = jsonBody
        )
        
        val response = window.fetch("$BASE_URL$endpoint", init).await()
        return response.text().await()
    }
    
    private suspend fun put(endpoint: String, jsonBody: String): String {
        val url = "$BASE_URL$endpoint"
        val response = window.fetch(url, RequestInit(
            method = "PUT",
            headers = json("Content-Type" to "application/json"),
            body = jsonBody
        )).await()
        
        if (!response.ok) {
            throw Exception("HTTP ${response.status}: ${response.statusText}")
        }
        
        return response.text().await()
    }
    
    private suspend fun delete(endpoint: String, jsonBody: String): String {
        val headers = org.w3c.fetch.Headers()
        headers.append("Content-Type", "application/json")
        
        val init = RequestInit(
            method = "DELETE",
            headers = headers,
            body = jsonBody
        )
        
        val response = window.fetch("$BASE_URL$endpoint", init).await()
        return response.text().await()
    }

    private suspend fun get(endpoint: String): String {
        val response = window.fetch("$BASE_URL$endpoint").await()
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

    suspend fun createWorkflow(
        name: String,
        description: String,
        userId: String,
        initialDir: String = "",
    ): WorkflowItem? {
        return try {
            // 构造 JSON，使用 JsonObject 安全编码避免手动转义
            val obj = kotlinx.serialization.json.buildJsonObject {
                put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("description", kotlinx.serialization.json.JsonPrimitive(description))
                if (initialDir.isNotBlank()) {
                    put("initialDir", kotlinx.serialization.json.JsonPrimitive(initialDir))
                }
            }
            val response = post("/api/workflows", obj.toString())
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("创建工作流失败:", e)
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
}

data class AsrResult(val success: Boolean, val text: String, val error: String? = null)
