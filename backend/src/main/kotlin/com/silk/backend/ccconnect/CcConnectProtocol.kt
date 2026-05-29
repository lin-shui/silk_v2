package com.silk.backend.ccconnect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire protocol between silk backend and cc-connect's silk platform plugin.
 * All messages are JSON text frames with a "type" discriminator.
 */

val protocolJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// ── cc-connect → silk ──

@Serializable
data class HelloMessage(
    val type: String = "hello",
    val platform: String = "silk",
    val version: Int = 1,
    val project: String = "",
    @SerialName("agent_type") val agentType: String = "",
    val cwd: String = "",
)

@Serializable
data class ReplyMessage(
    val type: String = "reply",
    val content: String,
    val format: String = "markdown",
)

@Serializable
data class ReplyStreamMessage(
    val type: String = "reply_stream",
    val content: String,
    val done: Boolean = false,
    val incremental: Boolean? = null,
)

@Serializable
data class StatusMessage(
    val type: String = "status",
    val state: String,
    val tool: String? = null,
    val detail: String? = null,
)

// ── silk → cc-connect ──

@Serializable
data class HelloAckMessage(
    val type: String = "hello_ack",
    val ok: Boolean,
    @SerialName("group_id") val groupId: String = "",
    @SerialName("group_name") val groupName: String = "",
    val error: String? = null,
)

@Serializable
data class UserMessage(
    val type: String = "message",
    val content: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String = "",
    @SerialName("msg_id") val msgId: String = "",
)

@Serializable
data class PingMessage(val type: String = "ping")

@Serializable
data class PongMessage(val type: String = "pong")

// ── cc-connect → silk (metadata) ──

@Serializable
data class CcModeOption(val key: String, val name: String)

@Serializable
data class CcModelOption(val name: String, val desc: String = "")

@Serializable
data class MetadataMessage(
    val type: String = "metadata",
    val mode: String? = null,
    val model: String? = null,
    @SerialName("available_modes") val availableModes: List<CcModeOption>? = null,
    @SerialName("available_models") val availableModels: List<CcModelOption>? = null,
)

// ── cc-connect → silk (interactive question with buttons) ──

@Serializable
data class QuestionButton(
    val label: String = "",
    val value: String = "",
)

@Serializable
data class QuestionButtonRow(
    val row: List<QuestionButton> = emptyList(),
)

@Serializable
data class QuestionMessage(
    val type: String = "question",
    val content: String = "",
    val options: List<QuestionButtonRow> = emptyList(),
)

// ── silk → cc-connect (command) ──

@Serializable
data class CommandMessage(
    val type: String = "command",
    val text: String,
)

fun parseMessageType(raw: String): String? {
    return try {
        val obj = protocolJson.decodeFromString<JsonObject>(raw)
        obj["type"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}
