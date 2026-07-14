package com.silk.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.localStorage
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class Scene {
    LOGIN,
    NICKNAME_SETUP,
    GROUP_LIST,
    CONTACTS,
    CHAT_ROOM,
    SETTINGS
}

enum class NavTab {
    SILK,
    WORKFLOW,
    KNOWLEDGE_BASE,
    AUDIO_DUPLEX,
    GAP_LIGHT
}

data class ChatNavigationTarget(
    val groupId: String,
    val messageId: String? = null,
    val requestId: Long,
)

data class WorkflowNavigationTarget(
    val workflowId: String,
    val messageId: String? = null,
    val requestId: Long,
)

class WebAppState {
    var currentScene by mutableStateOf(Scene.LOGIN)
        private set
    
    var currentUser by mutableStateOf<User?>(null)
        private set
    
    /** OAuth 登录失败时的错误信息，由 handleOAuthCallback 设置，LoginScene 展示 */
    var loginError by mutableStateOf("")
        internal set
    
    /** 更新当前用户的昵称（不触发网络请求，仅更新本地状态） */
    fun updateCurrentUserNickname(newFullName: String) {
        currentUser = currentUser?.copy(fullName = newFullName)
    }
    
    var selectedGroup by mutableStateOf<Group?>(null)
        private set

    var currentTab by mutableStateOf(NavTab.SILK)

    // KB 内联引用：点击聊天里的 [[kb:...]] 链接 → 跳转到知识库对应条目
    var knowledgeBaseNavigationTarget by mutableStateOf<KnowledgeBaseNavigationTarget?>(null)
        private set
    var chatNavigationTarget by mutableStateOf<ChatNavigationTarget?>(null)
        private set
    var workflowNavigationTarget by mutableStateOf<WorkflowNavigationTarget?>(null)
        private set

    // 标记用户是否明确请求了退出登录
    private var explicitLogoutRequested = false
    
    private val sceneHistory = mutableListOf<Scene>()
    
    init {
        console.log("🔧 AppState 初始化...")
        
        // 清除任何可能的残留状态
        selectedGroup = null
        sceneHistory.clear()
        
        // 清除可能的旧版本LocalStorage数据
        @Suppress("TooGenericExceptionCaught")
        try {
            localStorage.removeItem("silk_selected_group")
            localStorage.removeItem("silk_scene")
        } catch (e: Exception) {
            console.log("清理LocalStorage:", e.message)
        }
        
        // 尝试从LocalStorage加载用户（JWT 会话恢复）
        tryRestoreSession()
        console.log("🔧 AppState 初始化完成")
        console.log("   场景:", currentScene.toString())
        console.log("   用户:", currentUser?.fullName ?: "未登录")
        console.log("   群组:", selectedGroup?.name ?: "未选择")
    }
    
    /**
     * 设置用户会话（华为 OAuth 登录成功后调用）
     * @param isNewUser 是否为新注册用户，新用户需要先设置昵称
     */
    fun setSession(user: User, accessToken: String, refreshToken: String, isNewUser: Boolean = false) {
        currentUser = user
        JwtManager.setAccessToken(accessToken)
        JwtManager.setRefreshToken(refreshToken)
        JwtManager.setStoredUser(user)
        explicitLogoutRequested = false
        if (isNewUser) {
            navigateTo(Scene.NICKNAME_SETUP)
        } else {
            navigateTo(Scene.GROUP_LIST)
        }
    }
    
    /**
     * 向后兼容：保留旧的 setUser 方法（用于旧版密码登录，现在仅做兜底）
     */
    fun setUser(user: User) {
        currentUser = user
        JwtManager.setStoredUser(user)
        navigateTo(Scene.GROUP_LIST)
    }
    
    fun selectGroup(group: Group) {
        console.log("📌 选择群组:", group.name)
        selectedGroup = group
        navigateTo(Scene.CHAT_ROOM)
    }
    
    fun navigateTo(scene: Scene) {
        if (currentScene != scene) {
            sceneHistory.add(currentScene)
        }
        currentScene = scene
    }

    /** Switch top-level tab; if the Settings overlay is open, dismiss it first. */
    fun selectTab(tab: NavTab) {
        if (currentScene == Scene.SETTINGS) navigateBack()
        currentTab = tab
    }

    fun openKnowledgeBaseEntry(entryId: String, topicId: String? = null) {
        knowledgeBaseNavigationTarget = KnowledgeBaseNavigationTarget(
            entryId = entryId,
            topicId = topicId,
            requestId = kotlin.js.Date.now().toLong(),
        )
        selectTab(NavTab.KNOWLEDGE_BASE)
    }

    fun consumeKnowledgeBaseNavigationTarget(requestId: Long) {
        val current = knowledgeBaseNavigationTarget ?: return
        if (current.requestId == requestId) {
            knowledgeBaseNavigationTarget = null
        }
    }

    fun openChatGroup(group: Group, messageId: String? = null) {
        chatNavigationTarget = ChatNavigationTarget(
            groupId = group.id,
            messageId = messageId,
            requestId = kotlin.js.Date.now().toLong(),
        )
        selectTab(NavTab.SILK)
        selectGroup(group)
    }

    fun openWorkflow(workflowId: String, messageId: String? = null) {
        workflowNavigationTarget = WorkflowNavigationTarget(
            workflowId = workflowId,
            messageId = messageId,
            requestId = kotlin.js.Date.now().toLong(),
        )
        selectTab(NavTab.WORKFLOW)
    }

    fun consumeChatNavigationTarget(requestId: Long) {
        val current = chatNavigationTarget ?: return
        if (current.requestId == requestId) {
            chatNavigationTarget = null
        }
    }

    fun consumeWorkflowNavigationTarget(requestId: Long) {
        val current = workflowNavigationTarget ?: return
        if (current.requestId == requestId) {
            workflowNavigationTarget = null
        }
    }
    
    fun navigateBack(): Boolean {
        return if (sceneHistory.isNotEmpty()) {
            val previousScene = sceneHistory.last()
            
            // 防止意外退出登录：从群组列表不能返回到登录页面
            // 用户必须通过点击登出按钮来明确退出登录
            if (previousScene == Scene.LOGIN && currentUser != null) {
                console.log("🚫 阻止返回到登录页面（需要点击登出按钮）")
                return false
            }
            
            sceneHistory.removeLast()
            currentScene = previousScene
            if (currentScene == Scene.GROUP_LIST) {
                selectedGroup = null
            }
            true
        } else {
            false
        }
    }
    
    /**
     * 检查是否可以通过返回手势/按钮退出当前页面
     * 在群组列表页面时，返回不应该退出到登录页
     */
    fun canNavigateBack(): Boolean {
        // 如果当前在群组列表且已登录，不允许返回（会回到登录页）
        if (currentScene == Scene.GROUP_LIST && currentUser != null) {
            return false
        }
        // 如果当前在登录页，不允许返回
        if (currentScene == Scene.LOGIN) {
            return false
        }
        return sceneHistory.isNotEmpty()
    }
    
    /**
     * 登出：清除本地状态
     */
    fun logout() {
        console.log("🚪 用户明确请求退出登录")
        GlobalScope.launch {
            ApiClient.logout()
        }
        JwtManager.clearAll()
        explicitLogoutRequested = true
        currentUser = null
        selectedGroup = null
        knowledgeBaseNavigationTarget = null
        chatNavigationTarget = null
        workflowNavigationTarget = null
        sceneHistory.clear()
        currentScene = Scene.LOGIN
    }

    /**
     * 注销账号：删除所有数据后跳转到登录页
     */
    fun deleteAccount(onResult: (Boolean, String) -> Unit) {
        val userId = currentUser?.id ?: run {
            onResult(false, "用户未登录")
            return
        }
        GlobalScope.launch {
            val result = ApiClient.deleteAccount(userId)
            if (result.success) {
                JwtManager.clearAll()
                explicitLogoutRequested = true
                currentUser = null
                selectedGroup = null
                sceneHistory.clear()
                currentScene = Scene.LOGIN
                onResult(true, "账号已注销")
            } else {
                onResult(false, result.message.ifBlank { "注销失败" })
            }
        }
    }
    
    /**
     * 检查并恢复 JWT 会话
     * 在 Login 页面调用，如果用户没有明确退出登录则尝试自动恢复
     */
    fun checkAndRestoreSession(): Boolean {
        if (explicitLogoutRequested) {
            console.log("🔐 用户明确退出登录，保持在登录页")
            return false
        }
        
        val user = JwtManager.getStoredUser()
        val token = JwtManager.getAccessToken()
        
        if (user != null && token != null) {
            console.log("🔄 检测到保存的 JWT 会话，自动恢复")
            currentUser = user
            currentScene = Scene.GROUP_LIST
            sceneHistory.clear()
            return true
        }
        
        console.log("🔐 无有效 JWT 会话，保持在登录页")
        return false
    }
    
    /**
     * 初始化时尝试恢复 JWT 会话
     */
    private fun tryRestoreSession() {
        val user = JwtManager.getStoredUser()
        val token = JwtManager.getAccessToken()
        
        if (user != null && token != null) {
            currentUser = user
            currentScene = Scene.GROUP_LIST
            console.log("🔄 JWT 自动登录:", user.fullName)
        } else {
            // fallback: 尝试旧版 localStorage 兼容
            try {
                val json = localStorage.getItem("silk_user")
                if (json != null) {
                    val oldUser = Json.decodeFromString<User>(json)
                    currentUser = oldUser
                    currentScene = Scene.GROUP_LIST
                    console.log("🔄 旧版 localStorage 登录:", oldUser.fullName)
                }
            } catch (_: Exception) {}
        }
    }
}
