// backend/src/test/kotlin/com/silk/backend/ai/dsh/DshSdkClientTest.kt
package com.silk.backend.ai.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DshSdkClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun data(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `assistant chunk text-delta maps to TextDelta`() {
        val ev = parseDshEvent(
            "assistant/chunk",
            data("""{"turn":1,"step":1,"chunk":{"type":"text-delta","index":0,"text":"hello"}}"""),
        )
        assertEquals(DshEvent.TextDelta("hello"), ev)
    }

    @Test
    fun `assistant chunk reasoning-delta maps to ThinkingDelta`() {
        val ev = parseDshEvent(
            "assistant/chunk",
            data("""{"turn":1,"step":1,"chunk":{"type":"reasoning-delta","index":0,"text":"let me think"}}"""),
        )
        assertEquals(DshEvent.ThinkingDelta("let me think"), ev)
    }

    @Test
    fun `assistant message joins text blocks`() {
        val ev = parseDshEvent(
            "assistant/message",
            data(
                """
                {"turn":1,"step":1,"message":{"role":"assistant","content":[
                  {"type":"text","text":"2。"}
                ],"usage":{"inputTokens":1060,"outputTokens":3}}}
                """.trimIndent(),
            ),
        )
        assertEquals(DshEvent.AssistantMessage("2。"), ev)
    }

    @Test
    fun `tool call carries name and input`() {
        val ev = parseDshEvent(
            "tool/call",
            data("""{"turn":1,"step":1,"tool":{"name":"web_search","input":{"query":"DeepSeek Harness"}}}"""),
        )
        assertIs<DshEvent.ToolCall>(ev)
        assertEquals("web_search", ev.name)
        assertEquals("""{"query":"DeepSeek Harness"}""", ev.input)
    }

    @Test
    fun `tool result carries content`() {
        val ev = parseDshEvent(
            "tool/result",
            data("""{"turn":1,"step":1,"content":"found 3 sources"}"""),
        )
        assertEquals(DshEvent.ToolResult("found 3 sources"), ev)
    }

    @Test
    fun `turn end carries reason kind`() {
        val ev = parseDshEvent(
            "turn/end",
            data("""{"turn":1,"reason":{"kind":"completed"}}"""),
        )
        assertEquals(DshEvent.TurnEnd("completed"), ev)
    }

    @Test
    fun `unknown event types are ignored`() {
        assertNull(parseDshEvent("session/title", data("""{"title":"x"}""")))
        assertNull(parseDshEvent("assistant/chunk", data("""{"turn":1,"step":1,"chunk":{"type":"block-start"}}""")))
    }

    @Test
    fun `sandbox launcher is prepended when configured`() {
        val wrapped = buildLaunchCommand(
            launchCommand = listOf("node", "--import", "tsx", "bin.ts", "agent.cordis.yml"),
            sandboxScript = "backend/scripts/dsh_sandbox.py",
            sessionCwd = "/ws/group_1",
            runtimeRoot = "/opt/dsh",
        )
        assertEquals(
            listOf(
                "python3",
                "backend/scripts/dsh_sandbox.py",
                "/ws/group_1",
                "/opt/dsh",
                "--",
                "node", "--import", "tsx", "bin.ts", "agent.cordis.yml",
            ),
            wrapped,
        )
    }

    @Test
    fun `command is unchanged without sandbox script`() {
        assertEquals(
            listOf("node", "bin.js"),
            buildLaunchCommand(listOf("node", "bin.js"), null, "/ws", "/opt/dsh"),
        )
        assertEquals(
            listOf("node", "bin.js"),
            buildLaunchCommand(listOf("node", "bin.js"), "", "/ws", "/opt/dsh"),
        )
    }
}
