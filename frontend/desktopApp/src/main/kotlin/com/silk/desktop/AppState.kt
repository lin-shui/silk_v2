package com.silk.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.silk.shared.models.RoomKind
import java.io.File

/**
 * 场景枚举
 */
enum class Scene {
    LOGIN,        // 登录/注册界面
    GROUP_LIST,   // 群组列表界面
    CHAT_ROOM,    // 聊天室界面
    SETTINGS      // 设置界面
}

/**
 * 用户信息
 */
@Serializable
data class User(
    val id: String,
    val loginName: String,
    val fullName: String,
    val phoneNumber: String,
    val createdAt: String = ""
)

@Serializable
private data class DesktopSession(
    val user: User,
    val accessToken: String,
)

/**
 * 群组信息
 */
@Serializable
data class Group(
    val id: String,
    val name: String,
    val invitationCode: String,
    val hostId: String,
    val hostName: String = "",
    val createdAt: String = "",
    val roomKind: RoomKind = RoomKind.CHAT,
)

/**
 * 应用状态管理
 */
@Suppress("TooGenericExceptionCaught")
class AppState {
    // 当前场景
    var currentScene by mutableStateOf(Scene.LOGIN)
        private set
    
    // 场景历史（用于返回）
    private val sceneHistory = mutableStateListOf<Scene>()
    
    // 当前登录用户
    var currentUser by mutableStateOf<User?>(null)
        private set
    
    // 当前选中的群组
    var selectedGroup by mutableStateOf<Group?>(null)
        private set
    
    // 标记是否正在验证用户
    var isValidating by mutableStateOf(false)
        private set
    
    init {
        // 尝试加载已保存的用户信息
        loadUserFromDisk()
    }
    
    /**
     * 启动时重新验证用户
     * 应该在UI初始化后调用（需要在协程中）
     */
    suspend fun revalidateUser(): Boolean {
        val savedUser = currentUser ?: return false
        
        isValidating = true
        return try {
            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ApiClient.validateUser(savedUser.id)
            }
            
            if (response.success && response.user != null) {
                // 更新用户信息（可能后端数据有更新）
                currentUser = response.user
                println("✅ 用户验证成功: ${response.user.fullName}")
                true
            } else {
                // 验证失败，清除本地用户信息
                println("❌ 用户验证失败: ${response.message}")
                logout()
                false
            }
        } catch (e: Exception) {
            println("❌ 验证过程异常: ${e.message}")
            // 网络错误，暂时保留用户信息，但返回登录界面
            currentUser = null
            currentScene = Scene.LOGIN
            false
        } finally {
            isValidating = false
        }
    }
    
    /**
     * 导航到指定场景
     */
    fun navigateTo(scene: Scene) {
        // 保存当前场景到历史
        if (currentScene != scene) {
            sceneHistory.add(currentScene)
        }
        currentScene = scene
    }
    
    /**
     * 返回上一个场景
     */
    fun navigateBack(): Boolean {
        return if (sceneHistory.isNotEmpty()) {
            currentScene = sceneHistory.removeLast()
            // 如果返回到群组列表，清除选中的群组
            if (currentScene == Scene.GROUP_LIST) {
                selectedGroup = null
            }
            true
        } else {
            false
        }
    }
    
    /**
     * 登录成功后设置用户信息
     */
    fun setUser(user: User, accessToken: String) {
        require(accessToken.isNotBlank()) { "登录响应缺少 access token" }
        currentUser = user
        ApiClient.accessToken = accessToken
        saveUserToDisk(user, accessToken)
        navigateTo(Scene.GROUP_LIST)
    }
    
    /**
     * 选择群组进入聊天室
     */
    fun selectGroup(group: Group) {
        selectedGroup = group
        navigateTo(Scene.CHAT_ROOM)
    }
    
    /**
     * 登出
     */
    fun logout() {
        ApiClient.accessToken = null
        currentUser = null
        selectedGroup = null
        sceneHistory.clear()
        deleteUserFromDisk()
        currentScene = Scene.LOGIN
    }
    
    /**
     * 保存用户信息到磁盘（用于自动登录）
     */
    private fun saveUserToDisk(user: User, accessToken: String) {
        try {
            val userFile = File(System.getProperty("user.home"), ".silk_user.json")
            val json = Json.encodeToString(DesktopSession(user, accessToken))
            if (!userFile.exists() && !userFile.createNewFile()) {
                error("无法创建本地会话文件")
            }
            protectSessionFile(userFile)
            userFile.writeText(json)
            println("✅ 用户信息已保存")
        } catch (e: Exception) {
            println("❌ 保存用户信息失败: ${e.message}")
        }
    }
    
    /**
     * 从磁盘加载用户信息
     */
    private fun loadUserFromDisk() {
        try {
            val userFile = File(System.getProperty("user.home"), ".silk_user.json")
            if (userFile.exists()) {
                protectSessionFile(userFile)
                val json = userFile.readText()
                val session = runCatching { Json.decodeFromString<DesktopSession>(json) }.getOrNull()
                if (session != null && session.accessToken.isNotBlank()) {
                    ApiClient.accessToken = session.accessToken
                    currentUser = session.user
                    currentScene = Scene.GROUP_LIST
                    println("✅ 自动登录: ${session.user.fullName}")
                } else {
                    // Legacy files contained only User and cannot authenticate after the route hardening.
                    userFile.delete()
                }
            }
        } catch (e: Exception) {
            println("❌ 加载用户信息失败: ${e.message}")
        }
    }
    
    /**
     * 删除保存的用户信息
     */
    private fun deleteUserFromDisk() {
        try {
            val userFile = File(System.getProperty("user.home"), ".silk_user.json")
            if (userFile.exists()) {
                userFile.delete()
                println("✅ 用户信息已删除")
            }
        } catch (e: Exception) {
            println("❌ 删除用户信息失败: ${e.message}")
        }
    }

    private fun protectSessionFile(file: File) {
        val readProtected = file.setReadable(false, false) && file.setReadable(true, true)
        val writeProtected = file.setWritable(false, false) && file.setWritable(true, true)
        if (!readProtected || !writeProtected) {
            error("无法将本地会话文件限制为当前用户访问")
        }
    }
}
