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

    /** /session（列表） */
    suspend fun listLocalSessions(acp: AcpClient): JsonElement {
        return acp.callExtension("_silk/list_local_sessions", buildJsonObject {})
    }

    /** /cd */
    suspend fun setCwd(acp: AcpClient, cwd: String): JsonElement {
        return acp.callExtension("_silk/set_cwd", buildJsonObject {
            put("cwd", cwd)
        })
    }
}
