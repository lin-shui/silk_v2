// backend/src/main/kotlin/com/silk/backend/agents/acp/AcpCapabilities.kt
package com.silk.backend.agents.acp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * ACP capability negotiation 数据类。
 * 协议参考: https://agentclientprotocol.com/protocol/initialization
 */

@Serializable
data class ClientCapabilities(
    val fs: FsCapability = FsCapability(),
    val terminal: Boolean = false,
)

@Serializable
data class FsCapability(
    val readTextFile: Boolean = false,
    val writeTextFile: Boolean = false,
)

@Suppress("ConstructorParameterNaming")
@Serializable
data class AgentCapabilities(
    val loadSession: Boolean = false,
    val promptCapabilities: PromptCapabilities = PromptCapabilities(),
    /** silk 私有扩展能力声明，未声明的扩展按"不支持"处理。命名空间 `_silk`。 */
    val _silk: SilkExtensions = SilkExtensions(),
)

@Serializable
data class PromptCapabilities(
    val image: Boolean = false,
    val audio: Boolean = false,
    val embeddedContext: Boolean = false,
)

@Serializable
data class SilkExtensions(
    val compact: Boolean = false,
    val listLocalSessions: Boolean = false,
    val setCwd: Boolean = false,
)

@Serializable
data class McpServer(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: JsonObject = buildJsonObject {},
)
