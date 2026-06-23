// backend/src/main/kotlin/com/silk/backend/agents/acp/AcpWebSocketTransport.kt
package com.silk.backend.agents.acp

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Ktor WebSocketSession 包装。
 * 一行 JSON-RPC = 一个 WS Text frame（line-delimited 在 WS 上等价于 frame-delimited）。
 */
class AcpWebSocketTransport(
    private val session: WebSocketSession,
) : AcpTransport {

    @Volatile
    override var isClosed: Boolean = false
        private set

    override val incoming: Flow<String> = flow {
        try {
            session.incoming.consumeEach { frame ->
                if (frame is Frame.Text) emit(frame.readText())
            }
        } finally {
            isClosed = true
        }
    }

    override suspend fun send(line: String) {
        check(!isClosed) { "transport closed" }
        session.send(Frame.Text(line))
    }

    /**
     * 以 WS NORMAL (1000) 关闭会话，`reason` 字符串作为 close-reason payload。
     *
     * 当前实现固定使用 `CloseReason.Codes.NORMAL`；如果未来需要发送 POLICY_VIOLATION / GOING_AWAY
     * 等非正常关闭码，需要扩展 [AcpTransport.close] 接口加 code 参数（Plan B/C 决定）。
     */
    override suspend fun close(reason: String) {
        if (isClosed) return
        isClosed = true
        try {
            session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
        } catch (_: Exception) { /* already closing */ }
    }
}
