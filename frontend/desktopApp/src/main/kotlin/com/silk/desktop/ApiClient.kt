package com.silk.desktop

import com.silk.shared.models.Language
import com.silk.shared.models.LeaveGroupResponse
import com.silk.shared.models.SimpleResponse
import com.silk.shared.models.UpdateUserSettingsRequest
import com.silk.shared.models.UserSettingsResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * API响应基类
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null
)

/**
 * 注册请求
 */
@Serializable
data class RegisterRequest(
    val loginName: String,
    val fullName: String,
    val phoneNumber: String,
    val password: String
)

/**
 * 登录请求
 */
@Serializable
data class LoginRequest(
    val loginName: String,
    val password: String
)

/**
 * 认证响应
 */
@Serializable
data class AuthResponse(
    val success: Boolean,
    val message: String,
    val user: User? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null
)

/**
 * 创建群组请求
 */
@Serializable
data class CreateGroupRequest(
    val userId: String,
    val groupName: String
)

/**
 * 加入群组请求
 */
@Serializable
data class JoinGroupRequest(
    val userId: String,
    val invitationCode: String
)

/**
 * 群组响应
 */
@Serializable
data class GroupResponse(
    val success: Boolean,
    val message: String,
    val group: Group? = null,
    val groups: List<Group>? = null
)

/**
 * API客户端
 */
@Suppress("TooGenericExceptionCaught")
object ApiClient {
    private val BASE_URL = BuildConfig.BACKEND_BASE_URL
    private val json = Json { ignoreUnknownKeys = true }

    private inline fun <T> runApiCall(
        errorPrefix: String,
        onFailure: (String) -> T,
        block: () -> T
    ): T {
        return try {
            block()
        } catch (e: IOException) {
            val message = e.message ?: "IO error"
            println("❌ $errorPrefix: $message")
            onFailure(message)
        } catch (e: SerializationException) {
            val message = e.message ?: "Serialization error"
            println("❌ $errorPrefix: $message")
            onFailure(message)
        }
    }
    
    /**
     * 用户注册
     */
    fun register(
        loginName: String,
        fullName: String,
        phoneNumber: String,
        password: String
    ): AuthResponse {
        return runApiCall(
            errorPrefix = "注册失败",
            onFailure = { message -> AuthResponse(false, "网络错误: $message") }
        ) {
            val request = RegisterRequest(loginName, fullName, phoneNumber, password)
            val response = post("/auth/register", json.encodeToString(RegisterRequest.serializer(), request))
            json.decodeFromString(AuthResponse.serializer(), response)
        }
    }
    
    /**
     * 用户登录
     */
    fun login(loginName: String, password: String): AuthResponse {
        return runApiCall(
            errorPrefix = "登录失败",
            onFailure = { message -> AuthResponse(false, "网络错误: $message") }
        ) {
            val request = LoginRequest(loginName, password)
            val response = post("/auth/login", json.encodeToString(LoginRequest.serializer(), request))
            json.decodeFromString(AuthResponse.serializer(), response)
        }
    }
    
    /**
     * 验证用户（重新认证）
     */
    fun validateUser(userId: String): AuthResponse {
        return runApiCall(
            errorPrefix = "验证用户失败",
            onFailure = { message -> AuthResponse(false, "验证失败: $message") }
        ) {
            val response = get("/auth/validate/$userId")
            json.decodeFromString(AuthResponse.serializer(), response)
        }
    }
    
    /**
     * 创建群组
     */
    fun createGroup(userId: String, groupName: String): GroupResponse {
        return runApiCall(
            errorPrefix = "创建群组失败",
            onFailure = { message -> GroupResponse(false, "网络错误: $message") }
        ) {
            val request = CreateGroupRequest(userId, groupName)
            val response = post("/groups/create", json.encodeToString(CreateGroupRequest.serializer(), request))
            json.decodeFromString(GroupResponse.serializer(), response)
        }
    }
    
    /**
     * 加入群组
     */
    fun joinGroup(userId: String, invitationCode: String): GroupResponse {
        return runApiCall(
            errorPrefix = "加入群组失败",
            onFailure = { message -> GroupResponse(false, "网络错误: $message") }
        ) {
            val request = JoinGroupRequest(userId, invitationCode)
            val response = post("/groups/join", json.encodeToString(JoinGroupRequest.serializer(), request))
            json.decodeFromString(GroupResponse.serializer(), response)
        }
    }
    
    /**
     * 获取用户的所有群组
     */
    fun getUserGroups(userId: String): GroupResponse {
        return runApiCall(
            errorPrefix = "获取群组列表失败",
            onFailure = { message -> GroupResponse(false, "网络错误: $message") }
        ) {
            val response = get("/groups/user/$userId")
            json.decodeFromString(GroupResponse.serializer(), response)
        }
    }
    
    /**
     * 退出群组
     */
    fun leaveGroup(groupId: String, userId: String): LeaveGroupResponse {
        return runApiCall(
            errorPrefix = "退出群组失败",
            onFailure = { message -> LeaveGroupResponse(false, "网络错误: $message") }
        ) {
            val body = """{"userId":"$userId"}"""
            val response = post("/groups/$groupId/leave", body)
            json.decodeFromString(LeaveGroupResponse.serializer(), response)
        }
    }
    
    /**
     * 删除群组（群主）
     */
    fun deleteGroup(groupId: String, userId: String): SimpleResponse {
        return runApiCall(
            errorPrefix = "删除群组失败",
            onFailure = { message -> SimpleResponse(false, "网络错误: $message") }
        ) {
            val body = """{"userId":"$userId"}"""
            val response = delete("/groups/$groupId", body)
            json.decodeFromString(SimpleResponse.serializer(), response)
        }
    }
    
    // ==================== 用户设置相关 API ====================
    
    /**
     * 获取用户设置
     */
    fun getUserSettings(userId: String): UserSettingsResponse {
        return runApiCall(
            errorPrefix = "获取用户设置失败",
            onFailure = { message -> UserSettingsResponse(false, "网络错误: $message") }
        ) {
            val response = get("/users/$userId/settings")
            json.decodeFromString(UserSettingsResponse.serializer(), response)
        }
    }
    
    /**
     * 更新用户设置
     */
    fun updateUserSettings(userId: String, language: Language, defaultAgentInstruction: String): UserSettingsResponse {
        return runApiCall(
            errorPrefix = "更新用户设置失败",
            onFailure = { message -> UserSettingsResponse(false, "网络错误: $message") }
        ) {
            val request = UpdateUserSettingsRequest(userId, language, defaultAgentInstruction)
            val body = json.encodeToString(UpdateUserSettingsRequest.serializer(), request)
            val response = put("/users/$userId/settings", body)
            json.decodeFromString(UserSettingsResponse.serializer(), response)
        }
    }
    
    /**
     * HTTP POST请求
     */
    private fun post(endpoint: String, jsonBody: String): String {
        val url = URL("$BASE_URL$endpoint")
        val connection = url.openConnection() as HttpURLConnection
        
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } 
                    ?: """{"success":false,"message":"HTTP Error: $responseCode"}"""
            }
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * HTTP PUT请求
     */
    private fun put(endpoint: String, jsonBody: String): String {
        val url = URL("$BASE_URL$endpoint")
        val connection = url.openConnection() as HttpURLConnection
        
        return try {
            connection.requestMethod = "PUT"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } 
                    ?: """{"success":false,"message":"HTTP Error: $responseCode"}"""
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * HTTP DELETE请求
     */
    private fun delete(endpoint: String, jsonBody: String): String {
        val url = URL("$BASE_URL$endpoint")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: """{"success":false,"message":"HTTP Error: $responseCode"}"""
            }
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * HTTP GET请求
     */
    private fun get(endpoint: String): String {
        val url = URL("$BASE_URL$endpoint")
        val connection = url.openConnection() as HttpURLConnection
        
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } 
                    ?: """{"success":false,"message":"HTTP Error: $responseCode"}"""
            }
        } finally {
            connection.disconnect()
        }
    }
}
