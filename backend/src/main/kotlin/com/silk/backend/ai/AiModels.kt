package com.silk.backend.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DirectModelAgent 与 AnthropicClient 之间共享的消息/工具模型。
 * 提取为独立文件，避免交叉依赖。
 */

@Suppress("ConstructorParameterNaming")
@Serializable
data class Message(
    val role: String,
    val content: String? = null,
    val tool_calls: List<ToolCall>? = null,
    val tool_call_id: String? = null,
    val reasoning_content: String? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class Tool(
    val type: String = "function",
    val function: ToolDefinition
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

/**
 * 结构化 content block，用于流式传输 thinking/text/tool_use 内容。
 * 对应 Anthropic Messages API 的 content block 概念。
 */
@Serializable
data class ContentBlock(
    val index: Int,
    val type: String,          // "thinking", "text", "tool_use"
    val content: String = "",
    val isComplete: Boolean = false,
    val toolName: String = "",
    val toolId: String = "",
    /**
     * 仅对 thinking block 有意义：由后端记录的真实思考耗时（毫秒）。
     * 前端据此显示 "Thought for Xs"，避免因组件重新挂载导致计时丢失。
     * 0 表示未提供（兼容旧消息），前端回退到旧的本地计时逻辑。
     */
    val elapsedMs: Long = 0L
)
