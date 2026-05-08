// backend/src/main/kotlin/com/silk/backend/agents/core/AcpExtensions.kt
package com.silk.backend.agents.core

import com.silk.backend.agents.acp.AcpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AcpExtensions {

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
}
