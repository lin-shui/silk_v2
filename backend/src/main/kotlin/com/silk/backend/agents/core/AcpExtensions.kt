// backend/src/main/kotlin/com/silk/backend/agents/core/AcpExtensions.kt
package com.silk.backend.agents.core

import com.silk.backend.agents.acp.AcpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object AcpExtensions {

    private val whitespaceRegex = Regex("\\s+")

    /** /compact */
    suspend fun compact(acp: AcpClient, sessionId: String): JsonElement {
        return acp.callExtension("_silk/compact", buildJsonObject {
            put("sessionId", sessionId)
        })
    }

    /** /session（列表），传 cwd 让 adapter 只返回该目录下的会话 */
    suspend fun listLocalSessions(acp: AcpClient, cwd: String): JsonElement {
        return acp.callExtension("_silk/list_local_sessions", buildJsonObject {
            put("cwd", cwd)
        })
    }

    /** /cd — 需要 sessionId 让 adapter 定位到正确的 AcpSession */
    suspend fun setCwd(acp: AcpClient, sessionId: String, cwd: String): JsonElement {
        return acp.callExtension("_silk/set_cwd", buildJsonObject {
            put("sessionId", sessionId)
            put("cwd", cwd)
        })
    }

    /** Folder Picker / 目录浏览。返回原始 JsonElement，调用方按需解析。 */
    suspend fun listDir(acp: AcpClient, path: String, showHidden: Boolean): JsonElement {
        return acp.callExtension("_silk/list_dir", buildJsonObject {
            put("path", path)
            put("showHidden", showHidden)
        })
    }

    /** AskUserQuestion 回答传回 bridge */
    suspend fun resolveQuestion(acp: AcpClient, requestId: String, answer: String): JsonElement {
        return acp.callExtension("_silk/resolve_question", buildJsonObject {
            put("requestId", requestId)
            put("answer", answer)
        })
    }

    /** 把 `_silk/list_local_sessions` 的 JSON 结果转成适合 Agent 状态框展示的多行文本。 */
    fun formatLocalSessionsForDisplay(result: JsonElement): String {
        val sessions = runCatching {
            result.jsonObject["sessions"]?.jsonArray
        }.getOrNull() ?: return result.toString()

        if (sessions.isEmpty()) return "当前目录下没有本地会话"

        return buildString {
            appendLine("本地会话 (${sessions.size} 条):")
            sessions.forEachIndexed { index, item ->
                val obj = runCatching { item.jsonObject }.getOrNull()
                if (obj == null) {
                    appendLine("${index + 1}. ${item.toString()}")
                    return@forEachIndexed
                }

                val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.ifBlank { null }
                    ?: "unknown-session"
                val title = obj["title"]?.jsonPrimitive?.contentOrNull
                    ?.replace(whitespaceRegex, " ")
                    ?.trim()
                    ?.ifBlank { null }
                    ?: "无标题"
                val lastActivity = obj["lastActivity"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.ifBlank { null }
                val createdAt = obj["createdAt"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.ifBlank { null }
                val whenText = lastActivity ?: createdAt

                append("${index + 1}. $sessionId")
                if (whenText != null) append(" | $whenText")
                append(" | $title")
                if (index < sessions.lastIndex) appendLine()
            }
        }
    }
}
