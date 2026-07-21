package com.silk.shared

import org.w3c.dom.WebSocket as BrowserWebSocket
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import kotlinx.browser.window

actual class PlatformWebSocket actual constructor(
    private val serverUrl: String,
    private val onMessage: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit,
    private val onLog: LogCallback?
) {
    private var ws: BrowserWebSocket? = null
    private var isDisconnecting = false

    private fun log(message: String) { println(message); onLog?.invoke(message) }

    private fun errorMessage(error: dynamic): String =
        error?.message ?: error?.toString() ?: "Unknown error"

    actual val isConnected: Boolean
        get() = ws?.readyState == BrowserWebSocket.OPEN

    actual fun connect(token: String?, userId: String, userName: String, groupId: String) {
        isDisconnecting = false

        try {
            ws?.let { old ->
                old.onclose = null; old.onerror = null; old.onmessage = null; old.onopen = null
                try { old.close(1000, "Switching group") } catch (_: dynamic) { /* close failure is safe to ignore */ }
            }

            val safeName = userName.replace(" ", "_").replace("&", "_").replace("=", "_")
            val safeGroup = groupId.replace(" ", "_").replace("&", "_").replace("=", "_")
            val url = if (!token.isNullOrBlank()) {
                "$serverUrl/chat?token=$token&userName=$safeName&groupId=$safeGroup"
            } else {
                "$serverUrl/chat?userId=$userId&userName=$safeName&groupId=$safeGroup"
            }
            log("🔗 [WebSocket] $url")

            ws = BrowserWebSocket(url)

            ws?.onopen = { log("✅ WS open"); onConnected() }
            ws?.onclose = { if (!isDisconnecting) onDisconnected() }
            ws?.onerror = { log("❌ WS error"); onError("WebSocket error") }
            ws?.onmessage = { e -> val d = e.data; if (d is String) onMessage(d) }
        } catch (e: dynamic) { onError(errorMessage(e)) }
    }

    actual fun send(message: String) {
        if (ws?.readyState == BrowserWebSocket.OPEN) {
            try { ws?.send(message) } catch (e: dynamic) { log("❌ WS send: " + errorMessage(e)) }
        } else {
            log("⚠️ not connected")
        }
    }

    actual fun disconnect() {
        isDisconnecting = true
        try { ws?.close(1000, "Client disconnecting"); ws = null } catch (_: dynamic) { /* close failure is safe to ignore */ }
        onDisconnected()
    }
}
