package com.silk.shared.models

import kotlinx.serialization.Serializable

/**
 * 语言枚举
 */
@Serializable
enum class Language {
    ENGLISH,
    CHINESE
}

/**
 * 用户设置数据模型
 */
@Serializable
data class UserSettings(
    val language: Language = Language.CHINESE,
    val defaultAgentInstruction: String = "You are a helpful technical research assistant. ",
    val ccBridgeToken: String? = null,
)

/**
 * 更新用户设置请求
 */
@Serializable
data class UpdateUserSettingsRequest(
    val userId: String,
    val language: Language,
    val defaultAgentInstruction: String
)

/**
 * 用户设置响应
 */
@Serializable
data class UserSettingsResponse(
    val success: Boolean,
    val message: String,
    val settings: UserSettings? = null
)

/**
 * CC 设置响应
 */
@Serializable
data class CcSettingsResponse(
    val success: Boolean,
    val message: String,
    val ccBridgeToken: String? = null,
    val bridgeConnected: Boolean = false,
    val bridgeIp: String? = null,
)

/**
 * CC 当前 user+group 状态（含工作目录），用于工作流前端展示
 */
@Serializable
data class CcStateResponse(
    val success: Boolean,
    val active: Boolean = false,
    val running: Boolean = false,
    val workingDir: String = "",
    val sessionId: String = "",
    val sessionStarted: Boolean = false,
    val bridgeConnected: Boolean = false,
    val error: String? = null,
)

/**
 * 目录浏览条目（只包含目录）
 */
@Serializable
data class DirEntry(
    val name: String,
    val isDir: Boolean = true,
)

/**
 * 目录浏览响应（由 Bridge 提供磁盘列表）
 */
@Serializable
data class DirListingResponse(
    val success: Boolean,
    val path: String = "",
    val parent: String? = null,
    val segments: List<String> = emptyList(),
    /** 该平台的路径分隔符，Unix 为 "/"，Windows 为 "\\"。兼容老响应时默认 "/"。 */
    val separator: String = "/",
    val entries: List<DirEntry> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
)
