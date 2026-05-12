package com.silk.backend.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DirectModelAgent 与 AnthropicClient 之间共享的消息/工具模型。
 * 提取为独立文件，避免交叉依赖。
 */

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
