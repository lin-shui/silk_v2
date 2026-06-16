package com.silk.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.localStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class Scene {
    LOGIN,
    GROUP_LIST,
    CONTACTS,
    CHAT_ROOM,
    SETTINGS
}

enum class NavTab {
    SILK,
    WORKFLOW,
    KNOWLEDGE_BASE,
    AUDIO_DUPLEX
}

class WebAppState {
    var currentScene by mutableStateOf(Scene.LOGIN)
        private set
    
    var currentUser by mutableStateOf<User?>(null)
        private set
    
    var selectedGroup by mutableStateOf<Group?>(null)
        private set

    var currentTab by mutableStateOf(NavTab.SILK)

    var knowledgeBaseNavigationTarget by mutableStateOf<KnowledgeBaseNavigationTarget?>(null)
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
        recoverNonCancellation(
            block = {
            localStorage.removeItem("silk_selected_group")
            localStorage.removeItem("silk_scene")
            },
            recover = { error ->
                console.log("清理LocalStorage:", error.message)
            },
        )
        
        // 尝试从LocalStorage加载用户
        loadUserFromStorage()
        console.log("🔧 AppState 初始化完成")
        console.log("   场景:", currentScene.toString())
        console.log("   用户:", currentUser?.fullName ?: "未登录")
        console.log("   群组:", selectedGroup?.name ?: "未选择")
    }
    
    fun setUser(user: User) {
        currentUser = user
        saveUserToStorage(user)
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
    
    fun logout() {
        console.log("🚪 用户明确请求退出登录")
        explicitLogoutRequested = true
        currentUser = null
        selectedGroup = null
        knowledgeBaseNavigationTarget = null
        sceneHistory.clear()
        localStorage.removeItem("silk_user")
        currentScene = Scene.LOGIN
    }
    
    /**
     * 检查是否应该恢复到群组列表页面
     * 在 Login 页面调用，如果用户没有明确退出登录但意外到达了登录页，则自动恢复
     */
    fun checkAndRestoreSession(): Boolean {
        // 如果用户明确请求了退出登录，不恢复
        if (explicitLogoutRequested) {
            console.log("🔐 用户明确退出登录，保持在登录页")
            return false
        }
        
        // 检查是否有保存的用户数据
        return recoverNonCancellation(
            block = {
            val json = localStorage.getItem("silk_user")
            if (json != null) {
                val user = kotlinx.serialization.json.Json.decodeFromString<User>(json)
                console.log("🔄 检测到保存的用户数据，用户未明确退出登录，自动恢复到群组列表")
                currentUser = user
                currentScene = Scene.GROUP_LIST
                sceneHistory.clear()
                true
            } else {
                false
            }
        },
            recover = { error ->
                console.log("检查会话失败:", error.message)
                false
            },
        ).also { restored ->
            if (!restored) {
                console.log("🔐 没有保存的用户数据，保持在登录页")
            }
        }
    }
    
    private fun saveUserToStorage(user: User) {
        recoverNonCancellation(
            block = {
                val json = Json.encodeToString(user)
                localStorage.setItem("silk_user", json)
                console.log("用户信息已保存到LocalStorage")
            },
            recover = { error ->
                console.log("保存用户信息失败:", error)
            },
        )
    }
    
    private fun loadUserFromStorage() {
        recoverNonCancellation(
            block = {
                val json = localStorage.getItem("silk_user")
                if (json != null) {
                    val user = Json.decodeFromString<User>(json)
                    currentUser = user
                    currentScene = Scene.GROUP_LIST
                    console.log("自动登录:", user.fullName)
                }
            },
            recover = { error ->
                console.log("加载用户信息失败:", error)
            },
        )
    }
}
