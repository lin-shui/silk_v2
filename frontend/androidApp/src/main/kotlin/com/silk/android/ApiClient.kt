package com.silk.android

import com.silk.shared.models.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.HttpURLConnection
import java.net.URL

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

// ==================== cc-connect 数据模型 ====================

@Serializable
data class CcModeOption(val key: String, val name: String)

@Serializable
data class CcModelOption(val name: String, val desc: String = "")

@Serializable
data class CcMetadataEvent(
    val type: String = "cc_metadata",
    val mode: String? = null,
    val model: String? = null,
    val availableModes: List<CcModeOption>? = null,
    val availableModels: List<CcModelOption>? = null,
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
    val role: String = "GUEST"
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

// ==================== 版本检查相关数据模型 ====================

@Serializable
data class AppVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val lastModified: Long = 0,
    val fileSize: Long = 0,
    val downloadUrl: String = ""
)

// ==================== Workflow API ====================

@Serializable
data class WorkflowItem(
    val id: String,
    val name: String,
    val description: String = "",
    val ownerId: String = "",
    val groupId: String = "",
    val agentType: String = "claude_code",
    val workingDir: String = "",
    val sessionId: String = "",
    val sessionStarted: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

sealed class CreateWorkflowResult {
    data class Ok(val workflow: WorkflowItem) : CreateWorkflowResult()
    data class Err(val message: String) : CreateWorkflowResult()
}

sealed class TrustCheckResult {
    data class Trusted(val bridgeId: String?) : TrustCheckResult()
    data class NotTrusted(val bridgeId: String?) : TrustCheckResult()
    object BridgeDisconnected : TrustCheckResult()
    data class Error(val message: String) : TrustCheckResult()
}

object ApiClient {
    private val baseUrl: String get() = BackendUrlHolder.getBaseUrl()
    private val jsonParser = Json { ignoreUnknownKeys = true }
    
    suspend fun register(
        loginName: String,
        fullName: String,
        phoneNumber: String,
        password: String
    ): AuthResponse = withContext(Dispatchers.IO) {
        try {
            val body = """{"loginName":"$loginName","fullName":"$fullName","phoneNumber":"$phoneNumber","password":"$password"}"""
            val response = post("/auth/register", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("注册失败: $e")
            AuthResponse(false, "网络错误: ${e.message}")
        }
    }
    
    suspend fun login(loginName: String, password: String): AuthResponse = withContext(Dispatchers.IO) {
        try {
            val body = """{"loginName":"$loginName","password":"$password"}"""
            val response = post("/auth/login", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("登录失败: $e")
            AuthResponse(false, "网络错误")
        }
    }
    
    suspend fun validateUser(userId: String): AuthResponse = withContext(Dispatchers.IO) {
        try {
            val response = get("/auth/validate/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            AuthResponse(false, "验证失败")
        }
    }
    
    suspend fun getUserGroups(userId: String): GroupResponse = withContext(Dispatchers.IO) {
        try {
            val response = get("/groups/user/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取群组失败: $e")
            GroupResponse(false, "网络错误")
        }
    }
    
    /**
     * 获取用户所有群组的未读消息数
     */
    suspend fun getUnreadCounts(userId: String): UnreadCountResponse = withContext(Dispatchers.IO) {
        try {
            val response = get("/api/unread/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取未读数失败: $e")
            UnreadCountResponse(false)
        }
    }
    
    /**
     * 标记群组消息为已读
     */
    suspend fun markGroupAsRead(userId: String, groupId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = """{"userId":"$userId","groupId":"$groupId"}"""
            val response = post("/api/unread/mark-read", body)
            response.contains("\"success\":true") || response.contains("\"success\": true")
        } catch (e: Exception) {
            println("标记已读失败: $e")
            false
        }
    }
    
    suspend fun createGroup(userId: String, groupName: String, type: String? = null): GroupResponse = withContext(Dispatchers.IO) {
        try {
            val body = if (type != null) {
                """{"userId":"$userId","groupName":"$groupName","type":"$type"}"""
            } else {
                """{"userId":"$userId","groupName":"$groupName"}"""
            }
            val response = post("/groups/create", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("创建群组失败: $e")
            GroupResponse(false, "网络错误")
        }
    }
    
    suspend fun joinGroup(userId: String, invitationCode: String): GroupResponse = withContext(Dispatchers.IO) {
        try {
            val body = """{"userId":"$userId","invitationCode":"$invitationCode"}"""
            val response = post("/groups/join", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("加入群组失败: $e")
            GroupResponse(false, "网络错误")
        }
    }
    
    // ==================== 联系人相关 API ====================
    
    /**
     * 获取联系人列表（包含待处理请求）
     */
    suspend fun getContacts(userId: String): ContactResponse = withContext(Dispatchers.IO) {
        try {
            val response = get("/contacts/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取联系人失败: $e")
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 通过电话号码搜索用户
     */
    suspend fun searchUserByPhone(phoneNumber: String): UserSearchResult = withContext(Dispatchers.IO) {
        try {
            val response = get("/users/search?phone=$phoneNumber")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("搜索用户失败: $e")
            UserSearchResult(false, message = "网络错误")
        }
    }
    
    /**
     * 发送联系人请求（通过电话号码）
     */
    suspend fun sendContactRequest(fromUserId: String, toPhoneNumber: String): ContactResponse = withContext(Dispatchers.IO) {
        try {
            val body = """{"fromUserId":"$fromUserId","toPhoneNumber":"$toPhoneNumber"}"""
            val response = post("/contacts/request", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("发送联系人请求失败: $e")
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 发送联系人请求（通过用户ID）
     */
    suspend fun sendContactRequestById(fromUserId: String, toUserId: String): ContactResponse = withContext(Dispatchers.IO) {
        try {
            val body = """{"fromUserId":"$fromUserId","toUserId":"$toUserId"}"""
            val response = post("/contacts/request-by-id", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("发送联系人请求失败: $e")
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 处理联系人请求（接受/拒绝）
     */
    suspend fun handleContactRequest(requestId: String, userId: String, accept: Boolean): ContactResponse = withContext(Dispatchers.IO) {
        try {
            val body = """{"requestId":"$requestId","userId":"$userId","accept":$accept}"""
            val response = post("/contacts/handle-request", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("处理联系人请求失败: $e")
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 开始/获取私聊会话
     */
    suspend fun startPrivateChat(userId: String, contactId: String): PrivateChatResponse = withContext(Dispatchers.IO) {
        try {
            val body = """{"userId":"$userId","contactId":"$contactId"}"""
            val response = post("/contacts/private-chat", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取私聊会话失败: $e")
            PrivateChatResponse(false, "网络错误")
        }
    }
    
    /**
     * 开始/获取与 Silk AI 的专属私聊会话
     */
    suspend fun startSilkPrivateChat(userId: String): PrivateChatResponse = withContext(Dispatchers.IO) {
        try {
            val body = """{"userId":"$userId"}"""
            val response = post("/api/silk-private-chat", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取Silk私聊会话失败: $e")
            PrivateChatResponse(false, "网络错误")
        }
    }
    
    /**
     * 获取群组成员列表
     */
    suspend fun getGroupMembers(groupId: String): GroupMembersResponse = withContext(Dispatchers.IO) {
        try {
            val response = get("/groups/$groupId/members")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取群组成员失败: $e")
            GroupMembersResponse(false, emptyList())
        }
    }
    
    /**
     * 添加成员到群组
     */
    suspend fun addMemberToGroup(groupId: String, userId: String): AddMemberResponse = withContext(Dispatchers.IO) {
        try {
            val body = """{"userId":"$userId"}"""
            val response = post("/groups/$groupId/add-member", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("添加成员失败: $e")
            AddMemberResponse(false, "网络错误")
        }
    }
    
    /**
     * 退出群组
     */
    suspend fun leaveGroup(groupId: String, userId: String): LeaveGroupResponse = withContext(Dispatchers.IO) {
        try {
            val body = """{"userId":"$userId"}"""
            val response = post("/groups/$groupId/leave", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("退出群组失败: $e")
            LeaveGroupResponse(false, "网络错误")
        }
    }
    
    // ==================== 用户设置相关 API ====================
    
    /**
     * 获取用户设置
     */
    suspend fun getUserSettings(userId: String): UserSettingsResponse = withContext(Dispatchers.IO) {
        try {
            val response = get("/users/$userId/settings")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取用户设置失败: $e")
            UserSettingsResponse(false, "网络错误")
        }
    }
    
    /**
     * 更新用户设置
     */
    suspend fun updateUserSettings(userId: String, language: Language, defaultAgentInstruction: String): UserSettingsResponse = withContext(Dispatchers.IO) {
        try {
            val request = UpdateUserSettingsRequest(userId, language, defaultAgentInstruction)
            val body = jsonParser.encodeToString(UpdateUserSettingsRequest.serializer(), request)
            val response = put("/users/$userId/settings", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("更新用户设置失败: $e")
            UserSettingsResponse(false, "网络错误")
        }
    }
    
    // ==================== CC Bridge 相关 API ====================

    suspend fun getCcSettings(userId: String): CcSettingsResponse = withContext(Dispatchers.IO) {
        try {
            val response = get("/users/$userId/cc-settings")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取CC设置失败: $e")
            CcSettingsResponse(false, "网络错误")
        }
    }

    suspend fun generateBridgeToken(userId: String): CcSettingsResponse = withContext(Dispatchers.IO) {
        try {
            val response = post("/users/$userId/cc-settings/generate-token", "{}")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("生成Bridge Token失败: $e")
            CcSettingsResponse(false, "网络错误")
        }
    }

    suspend fun getBridgeStatus(userId: String): CcSettingsResponse = withContext(Dispatchers.IO) {
        try {
            val response = get("/users/$userId/cc-settings/bridge-status")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取Bridge状态失败: $e")
            CcSettingsResponse(false, "网络错误")
        }
    }

    // ==================== cc-connect Token / Status API ====================

    suspend fun getCcConnectTokenInfo(groupId: String, userId: String): CcConnectTokenInfo? = withContext(Dispatchers.IO) {
        try {
            val response = get("/api/ccconnect/groups/$groupId/token-info?userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取 cc-connect token 信息失败: $e")
            null
        }
    }

    suspend fun regenerateCcConnectToken(groupId: String, userId: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = """{"groupId":"$groupId","userId":"$userId"}"""
            val response = post("/api/ccconnect/regenerate-token", body)
            val info = jsonParser.decodeFromString<CcConnectTokenInfo>(response)
            info.token
        } catch (e: Exception) {
            println("重新生成 cc-connect token 失败: $e")
            null
        }
    }

    // ==================== 消息发送 API ====================
    
    /**
     * 发送消息到群组（用于转发功能）
     */
    suspend fun sendMessageToGroup(
        groupId: String,
        userId: String,
        userName: String,
        content: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 对 content 进行 JSON 转义
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
            println("❌ 发送消息失败: ${e.message}")
            false
        }
    }
    
    // ==================== 消息撤回相关 API ====================
    
    /**
     * 撤回消息
     * @param groupId 群组ID
     * @param messageId 要撤回的消息ID
     * @param userId 当前用户ID
     */
    suspend fun recallMessage(groupId: String, messageId: String, userId: String): SimpleResponse = withContext(Dispatchers.IO) {
        try {
            val body = """{"groupId":"$groupId","messageId":"$messageId","userId":"$userId"}"""
            val response = post("/api/messages/recall", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("❌ 撤回消息失败: ${e.message}")
            SimpleResponse(false, "网络错误")
        }
    }
    
    // ==================== 版本检查相关 API ====================
    
    /**
     * 获取云端最新版本信息
     */
    suspend fun getAppVersion(): AppVersionInfo? = withContext(Dispatchers.IO) {
        println("📡 [ApiClient] 正在请求版本信息: $baseUrl/api/files/app-version")
        try {
            val response = get("/api/files/app-version")
            println("📡 [ApiClient] 版本信息响应: $response")
            val versionInfo = jsonParser.decodeFromString<AppVersionInfo>(response)
            println("📡 [ApiClient] 解析成功: versionCode=${versionInfo.versionCode}, versionName=${versionInfo.versionName}")
            versionInfo
        } catch (e: Exception) {
            println("❌ [ApiClient] 获取版本信息失败: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 获取 APK 下载 URL
     */
    fun getApkDownloadUrl(): String = "$baseUrl/api/files/download-apk"
    
    private fun post(endpoint: String, jsonBody: String): String {
        val url = URL("$baseUrl$endpoint")
        val connection = AndroidHttpCompat.openConnection(url)
        
        return try {
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10000
                readTimeout = 10000
            }
            
            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray())
            }
            
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
    
    private fun put(endpoint: String, jsonBody: String): String {
        val url = URL("$baseUrl$endpoint")
        val connection = AndroidHttpCompat.openConnection(url)
        
        return try {
            connection.apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10000
                readTimeout = 10000
            }
            
            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray())
            }
            
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
    
    private fun get(endpoint: String): String {
        val url = URL("$baseUrl$endpoint")
        val connection = AndroidHttpCompat.openConnection(url)
        
        return try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }
            
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun delete(endpoint: String): String {
        val url = URL("$baseUrl$endpoint")
        val connection = AndroidHttpCompat.openConnection(url)
        return try {
            connection.apply {
                requestMethod = "DELETE"
                connectTimeout = 10000
                readTimeout = 10000
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun getWorkflows(userId: String): List<WorkflowItem> = withContext(Dispatchers.IO) {
        try {
            val response = get("/api/workflows?userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取工作流失败: $e")
            emptyList()
        }
    }

    suspend fun createWorkflow(
        name: String,
        description: String,
        userId: String,
        initialDir: String,
    ): CreateWorkflowResult = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("userId", JsonPrimitive(userId))
                put("name", JsonPrimitive(name))
                put("description", JsonPrimitive(description))
                put("initialDir", JsonPrimitive(initialDir))
            }.toString()
            val response = post("/api/workflows", body)
            val obj = jsonParser.parseToJsonElement(response).jsonObject
            if (obj["success"]?.jsonPrimitive?.booleanOrNull == false) {
                val msg = obj["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
                CreateWorkflowResult.Err(msg)
            } else {
                CreateWorkflowResult.Ok(jsonParser.decodeFromString(response))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            CreateWorkflowResult.Err(e.message ?: "网络错误")
        }
    }

    suspend fun deleteWorkflow(workflowId: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            delete("/api/workflows/$workflowId?userId=$userId")
            true
        } catch (e: Exception) {
            println("删除工作流失败: $e")
            false
        }
    }

    suspend fun renameWorkflow(workflowId: String, userId: String, newName: String): WorkflowItem? = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("userId", JsonPrimitive(userId))
                put("name", JsonPrimitive(newName))
            }.toString()
            val response = put("/api/workflows/$workflowId", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("重命名工作流失败: $e")
            null
        }
    }

    suspend fun getCcState(userId: String, groupId: String): CcStateResponse = withContext(Dispatchers.IO) {
        try {
            val response = get("/users/$userId/cc-state/$groupId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("获取 CC 状态失败: $e")
            CcStateResponse(success = false, error = e.message)
        }
    }

    suspend fun listCcDir(
        userId: String,
        path: String? = null,
        showHidden: Boolean = false,
    ): DirListingResponse = withContext(Dispatchers.IO) {
        try {
            val params = buildString {
                append("?showHidden=$showHidden")
                if (!path.isNullOrBlank()) {
                    append("&path=").append(java.net.URLEncoder.encode(path, "UTF-8"))
                }
            }
            val response = get("/users/$userId/cc-fs/list$params")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("列目录失败: $e")
            DirListingResponse(success = false, error = e.message)
        }
    }

    suspend fun cdCcDir(userId: String, groupId: String, path: String): CcStateResponse = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("groupId", JsonPrimitive(groupId))
                put("path", JsonPrimitive(path))
            }.toString()
            val response = post("/users/$userId/cc-fs/cd", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("切换目录失败: $e")
            CcStateResponse(success = false, error = e.message)
        }
    }

    suspend fun checkTrustedDir(userId: String, path: String): TrustCheckResult = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(path, "UTF-8")
            val response = get("/users/$userId/trusted-dirs/check?path=$encoded")
            val obj = jsonParser.parseToJsonElement(response).jsonObject
            val bridgeConnected = obj["bridgeConnected"]?.jsonPrimitive?.booleanOrNull ?: false
            val trusted = obj["trusted"]?.jsonPrimitive?.booleanOrNull ?: false
            val bridgeId = obj["bridgeId"]?.jsonPrimitive?.contentOrNull
            when {
                !bridgeConnected -> TrustCheckResult.BridgeDisconnected
                trusted -> TrustCheckResult.Trusted(bridgeId)
                else -> TrustCheckResult.NotTrusted(bridgeId)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            TrustCheckResult.Error(e.message ?: "网络错误")
        }
    }

    suspend fun addTrustedDir(userId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("path", JsonPrimitive(path))
            }.toString()
            val response = post("/users/$userId/trusted-dirs", body)
            val obj = jsonParser.parseToJsonElement(response).jsonObject
            obj["success"]?.jsonPrimitive?.booleanOrNull ?: false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("添加信任目录失败: $e")
            false
        }
    }

    // ==================== Knowledge Base API ====================

    suspend fun getKBTopics(userId: String): List<KBTopicItem> = withContext(Dispatchers.IO) {
        try {
            val response = get("/api/kb/topics?userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取知识库主题失败: $e")
            emptyList()
        }
    }

    suspend fun createKBTopic(name: String, project: String, userId: String): KBTopicItem? = withContext(Dispatchers.IO) {
        try {
            val body = """{"userId":"$userId","name":"$name","project":"$project"}"""
            val response = post("/api/kb/topics", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("创建知识库主题失败: $e")
            null
        }
    }

    suspend fun getKBEntries(topicId: String, userId: String): List<KBEntryItem> = withContext(Dispatchers.IO) {
        try {
            val response = get("/api/kb/entries?topicId=$topicId&userId=$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("获取知识库条目失败: $e")
            emptyList()
        }
    }

    suspend fun createKBEntry(topicId: String, title: String, content: String, tags: List<String>, userId: String): KBEntryItem? = withContext(Dispatchers.IO) {
        try {
            val tagsJson = tags.joinToString(",") { "\"$it\"" }
            val body = """{"userId":"$userId","topicId":"$topicId","title":"$title","content":"$content","tags":[$tagsJson]}"""
            val response = post("/api/kb/entries", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            println("创建知识库条目失败: $e")
            null
        }
    }

    suspend fun updateKBEntry(entryId: String, title: String?, content: String?, tags: List<String>?, userId: String): KBEntryItem? = withContext(Dispatchers.IO) {
        try {
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
            println("更新知识库条目失败: $e")
            null
        }
    }

    // ==================== ASR 语音识别 API ====================

    suspend fun transcribeAudio(audioBase64: String, format: String = "m4a"): AsrResult = withContext(Dispatchers.IO) {
        try {
            val body = """{"audio":"$audioBase64","format":"$format"}"""
            val url = URL("$baseUrl/api/asr/transcribe")
            val connection = AndroidHttpCompat.openConnection(url)
            val responseText = try {
                connection.apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 10000
                    readTimeout = 120000
                }
                connection.outputStream.use { os -> os.write(body.toByteArray()) }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val json = jsonParser.parseToJsonElement(responseText).jsonObject
            val success = json["success"]?.jsonPrimitive?.booleanOrNull ?: false
            val text = json["text"]?.jsonPrimitive?.contentOrNull ?: ""
            val error = json["error"]?.jsonPrimitive?.contentOrNull
            AsrResult(success, text, error)
        } catch (e: java.net.ConnectException) {
            AsrResult(false, "", "语音识别服务不可用，请确认 vLLM ASR 已启动")
        } catch (e: Exception) {
            println("语音识别请求失败: $e")
            AsrResult(false, "", "语音识别失败: ${e.message}")
        }
    }
}

data class AsrResult(val success: Boolean, val text: String, val error: String? = null)
