package com.silk.web.workspace

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceDtoDecodeTest {
    @Test
    fun decodesNullAccessModeAsNull() {
        // Backend WorkspaceDto.accessMode is nullable and emitted as explicit null
        // when no ACTIVE binding contributes an access mode.
        val payload = """
            [{
              "workspaceId": "ws_1",
              "roomId": "room_1",
              "ownerId": "user_1",
              "ownerDisplayName": "jhshen",
              "name": "silk_v2",
              "workingDir": "/home/ubuntu/work/silk_v2",
              "agentType": "codex",
              "activeAgentInstanceId": "inst_1",
              "accessMode": null,
              "visibility": "PRIVATE",
              "copilots": [],
              "role": "OWNER",
              "lifecycleState": "ACTIVE",
              "activity": {"state": "OFFLINE", "updatedAt": 1787294748722},
              "createdAt": 1787193236731,
              "recentActivityAt": 1787294748722,
              "linkedGithubRef": null,
              "historyOnly": false
            }]
        """.trimIndent()
        val workspaces = workspaceJson.decodeFromString<List<WorkspaceDto>>(payload)
        val workspace = workspaces.single()
        assertEquals("ws_1", workspace.workspaceId)
        assertNull(workspace.accessMode)
    }
}
