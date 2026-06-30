// backend/src/main/kotlin/com/silk/backend/agents/acp/AcpMessages.kt
package com.silk.backend.agents.acp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * ACP (Zed Agent Client Protocol) JSON-RPC 2.0 消息定义。
 * 协议参考: https://agentclientprotocol.com/protocol/overview
 *
 * 本文件只定义"线上消息形态"，不含任何业务逻辑。
 */

/** JSON-RPC 2.0 请求（有 id，期望响应） */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
)

/** JSON-RPC 2.0 响应（id 与请求匹配） */
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

/** JSON-RPC 2.0 通知（无 id，不期望响应） */
@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

// ============== initialize ==============

@Serializable
data class InitializeParams(
    val protocolVersion: String,
    val clientCapabilities: ClientCapabilities,
)

@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val agentCapabilities: AgentCapabilities,
)

// ============== session/new ==============

@Serializable
data class SessionNewParams(
    val cwd: String,
    val mcpServers: List<McpServer> = emptyList(),
    /** Silk 扩展：续旧 CLI session（重启后从 WorkflowPersistence seed 拿到 cli_session_id） */
    val cliSessionId: String? = null,
)

@Serializable
data class SessionNewResult(
    val sessionId: String,
)

// ============== session/load ==============

@Serializable
data class SessionLoadParams(
    val sessionId: String,
    val cwd: String,
)

@Serializable
data class SessionLoadResult(
    val sessionId: String,
)

// ============== session/prompt ==============

@Serializable
data class SessionPromptParams(
    val sessionId: String,
    val prompt: List<ContentBlock>,
)

@Serializable
data class SessionPromptResult(
    val stopReason: StopReason,
    /** Silk 扩展：adapter 通过 meta.cliSessionId 把 CLI 真实 session id 报回来用于持久化 */
    val meta: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
enum class StopReason {
    @SerialName("end_turn") END_TURN,
    @SerialName("max_tokens") MAX_TOKENS,
    @SerialName("refusal") REFUSAL,
    @SerialName("cancelled") CANCELLED,
}

// ============== session/cancel (notification) ==============

@Serializable
data class SessionCancelParams(
    val sessionId: String,
)

// ============== session/update (notification, agent → client) ==============

@Serializable
data class SessionUpdateNotification(
    val sessionId: String,
    val update: JsonObject,   // 由 Plan B 解析具体 sessionUpdate kind
)

// ============== session/request_permission (request, agent → client) ==============

@Serializable
data class PermissionRequestParams(
    val sessionId: String,
    val toolCall: JsonObject,
    val options: List<JsonObject> = emptyList(),
)

@Serializable
data class PermissionResponse(
    val outcome: JsonObject,   // {kind: "selected", optionId: "..."} 或 {kind: "cancelled"}
)

// ============== ContentBlock ==============

@Serializable
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock()

    @Serializable
    @SerialName("image")
    data class Image(val mimeType: String, val data: String) : ContentBlock()
}
