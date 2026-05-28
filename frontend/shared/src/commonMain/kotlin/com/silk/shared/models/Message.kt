package com.silk.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val userId: String,
    val userName: String,
    val content: String,
    val timestamp: Long,
    val type: MessageType = MessageType.TEXT,
    val isTransient: Boolean = false,  // false = 普通消息，true = 临时消息
    val currentStep: Int? = null,      // 当前执行的步骤编号（1-11）
    val totalSteps: Int? = null,       // 总步骤数（11）
    val isIncremental: Boolean = false, // true = 增量消息（需拼接），false = 完整消息（直接替换）
    val category: MessageCategory = MessageCategory.NORMAL,  // ✅ 消息类别（用于UI显示亮度）
    val references: List<MessageReference> = emptyList(),
    val contentBlocks: List<ContentBlock>? = null  // 结构化 content block（流式 / 持久化回放）
)

@Serializable
enum class MessageType {
    TEXT, JOIN, LEAVE, SYSTEM, FILE, RECALL, STOP_GENERATE, CC_COMMAND
}

@Serializable
enum class MessageCategory {
    NORMAL,           // 普通聊天消息（正常亮度）
    TODO_LIST,        // 待办事项列表（低亮度）
    STEP_PROCESS,     // 步骤执行过程（低亮度，可转发）
    FINAL_REPORT,     // 最终诊断报告（高亮度）
    AGENT_STATUS,     // Agent 工作状态（灰色，低亮度）
    AGENT_QUESTION,   // Agent 向用户提问（需要用户回答）
}

@Serializable
data class MessageReference(
    val kind: String,       // "citation" = 网络搜索, "available" = 本地资源
    val index: Int,
    val title: String,
    val url: String? = null,
    val snippet: String? = null,
    val path: String? = null
)

/** 结构化 content block，对应 Anthropic Messages API 的 content block 概念。 */
@Serializable
data class ContentBlock(
    val index: Int,
    val type: String,          // "thinking", "text", "tool_use"
    val content: String = "",
    val isComplete: Boolean = false,
    val toolName: String = "",
    val toolId: String = ""
)

@Serializable
data class User(
    val id: String,
    val name: String
)

/** Silk 内建 AI 的 userId 常量，用于前端需要显式引用的场景（如 @ 提及列表）。 */
const val SILK_AGENT_USER_ID = "silk_ai_agent"
const val SILK_AGENT_DISPLAY_NAME = "Silk"

/** 判断某个 userId 是否属于 AI agent（所有 agent 的 userId 均以 _ai_agent 结尾）。 */
fun isAgentUserId(userId: String): Boolean = userId.endsWith("_ai_agent")

