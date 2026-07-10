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
    private var pollingMode = false
    private var currentGroupId = ""
    private var lastTimestamp: Long = 0
    private var historyEndSent = false
    private var isDisconnecting = false

    private val httpBaseUrl: String
        get() = window.location.origin

    private fun log(message: String) { println(message); onLog?.invoke(message) }

    private fun errorMessage(error: dynamic): String =
        error?.message ?: error?.toString() ?: "Unknown error"

    actual val isConnected: Boolean
        get() = if (pollingMode) true else (ws?.readyState == BrowserWebSocket.OPEN)

    actual fun connect(token: String?, userId: String, userName: String, groupId: String) {
        pollingMode = false; isDisconnecting = false; stopPolling()
        currentGroupId = groupId

        try {
            ws?.let { old ->
                old.onclose = null; old.onerror = null; old.onmessage = null; old.onopen = null
                try { old.close(1000, "Switching group") } catch (e: dynamic) {}
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
            val timer = window.setTimeout({ log("⏱️ WS timeout -> polling"); startPolling() }, 5000)

            ws?.onopen = { window.clearTimeout(timer); log("✅ WS open"); pollingMode = false; onConnected() }
            ws?.onclose = { window.clearTimeout(timer); if (!pollingMode && !isDisconnecting) { startPolling() } else if (!isDisconnecting && !pollingMode) { onDisconnected() } }
            ws?.onerror = { window.clearTimeout(timer); startPolling() }
            ws?.onmessage = { e -> val d = e.data; if (d is String) onMessage(d) }
        } catch (e: dynamic) { startPolling() }
    }

    private fun startPolling() {
        if (pollingMode || isDisconnecting) return
        pollingMode = true; historyEndSent = false; onConnected(); log("🔄 [HTTP Polling] start")
        doPoll()
    }

    private fun doPoll() {
        if (!pollingMode || isDisconnecting) return
        try {
            window.fetch("$httpBaseUrl/api/messages/poll/$currentGroupId?since=$lastTimestamp").then({ r ->
                r.text().then({ text ->
                    try {
                        val json = js("JSON")
                        val arr = json.parse(text) as Array<dynamic>
                        for (msg in arr) {
                            val ts = (msg.timestamp as? Double)?.toLong() ?: 0L
                            if (ts > lastTimestamp) lastTimestamp = ts
                            onMessage(json.stringify(msg) as String)
                        }
                        if (arr.isNotEmpty() && !historyEndSent) {
                            historyEndSent = true
                            onMessage("{\"id\":\"history_end\",\"userId\":\"\",\"userName\":\"\",\"content\":\"__history_end__\",\"timestamp\":0,\"type\":\"SYSTEM\",\"isTransient\":true}")
                        }
                    } catch (e: dynamic) { log("⚠️ Poll parse: $text") }
                    if (!isDisconnecting) window.setTimeout({ doPoll() }, 2000)
                })
            }).catch({ if (!isDisconnecting) window.setTimeout({ doPoll() }, 5000) })
        } catch (e: dynamic) { if (!isDisconnecting) window.setTimeout({ doPoll() }, 5000) }
    }

    private fun stopPolling() { pollingMode = false }

    actual fun send(message: String) {
        if (pollingMode) {
            try {
                val json = js("JSON")
                val msgObj = json.parse(message)
                msgObj.groupId = currentGroupId
                val newBody = json.stringify(msgObj)
                val sendUrl = httpBaseUrl + "/api/messages/send"
                js("""
                    var xhr = new XMLHttpRequest();
                    xhr.open("POST", sendUrl, false);
                    xhr.setRequestHeader("Content-Type", "application/json");
                    xhr.send(newBody);
                    if (xhr.status !== 200) throw new Error("HTTP " + xhr.status + ": " + xhr.responseText);
                """)
                log("✅ [HTTP Polling] sent")
            } catch (e: dynamic) { log("❌ [HTTP Polling] send: " + errorMessage(e)) }
        } else if (ws?.readyState == BrowserWebSocket.OPEN) {
            try { ws?.send(message) } catch (e: dynamic) { log("❌ WS send: " + errorMessage(e)) }
        } else { log("⚠️ not connected") }
    }

    actual fun disconnect() {
        isDisconnecting = true; stopPolling()
        try { ws?.close(1000, "Client disconnecting"); ws = null } catch (e: dynamic) {}
        onDisconnected()
    }
}
