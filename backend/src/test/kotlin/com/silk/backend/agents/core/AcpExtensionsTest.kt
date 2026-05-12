package com.silk.backend.agents.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AcpExtensionsTest {

    @Test
    fun `formatLocalSessionsForDisplay renders one session per line`() {
        val result = Json.parseToJsonElement(
            """
            {
              "sessions": [
                {
                  "sessionId": "sess-1",
                  "title": "first line\nsecond line",
                  "lastActivity": "2026-05-08T10:00:00Z"
                },
                {
                  "sessionId": "sess-2",
                  "title": "second session",
                  "createdAt": "2026-05-07T09:00:00Z"
                }
              ]
            }
            """.trimIndent()
        )

        val formatted = AcpExtensions.formatLocalSessionsForDisplay(result)

        assertEquals(
            """
            本地会话 (2 条):
            1. sess-1 | 2026-05-08T10:00:00Z | first line second line
            2. sess-2 | 2026-05-07T09:00:00Z | second session
            """.trimIndent(),
            formatted
        )
    }

    @Test
    fun `formatLocalSessionsForDisplay handles empty list`() {
        val result = Json.parseToJsonElement("""{"sessions": []}""")
        assertEquals("当前目录下没有本地会话", AcpExtensions.formatLocalSessionsForDisplay(result))
    }
}
