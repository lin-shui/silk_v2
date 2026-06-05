package com.silk.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

actual class PlatformWebSocket actual constructor(
    private val serverUrl: String,
    private val onMessage: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit,
    private val onLog: LogCallback?
) {
    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 15_000
        }
    }
    
    private var session: DefaultClientWebSocketSession? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectionGen = AtomicInteger(0)
    
    private fun log(message: String) {
        println(message)
        onLog?.invoke(message)
    }

    private fun rethrowCancellation(error: CancellationException): Nothing = throw error
    
    actual val isConnected: Boolean
        get() = session != null
    
    actual fun connect(userId: String, userName: String, groupId: String) {
        val connectToken = connectionGen.incrementAndGet()
        val previousSession = cleanupExistingConnection()
        closePreviousSession(previousSession)
        val fullUrl = buildChatUrl(userId, userName, groupId)
        log("🔗 [WebSocket] 连接到: $fullUrl")
        job = scope.launch {
            openSocketConnection(fullUrl, connectToken)
        }
    }

    private fun cleanupExistingConnection(): DefaultClientWebSocketSession? {
        val previousSession = session
        job?.cancel()
        job = null
        session = null
        return previousSession
    }

    private fun closePreviousSession(previousSession: DefaultClientWebSocketSession?) {
        previousSession?.let { oldSession ->
            scope.launch {
                try {
                    oldSession.close(CloseReason(CloseReason.Codes.NORMAL, "Switching group"))
                } catch (e: CancellationException) {
                    rethrowCancellation(e)
                } catch (e: IOException) {
                    log("⚠️ [WebSocket] 旧连接关闭异常: ${e.message}")
                } catch (e: IllegalStateException) {
                    log("⚠️ [WebSocket] 旧连接关闭异常: ${e.message}")
                }
            }
        }
    }

    private fun buildChatUrl(userId: String, userName: String, groupId: String): String {
        val safeUserName = sanitizeQueryValue(userName)
        val safeGroupId = sanitizeQueryValue(groupId)
        return "$serverUrl/chat?userId=$userId&userName=$safeUserName&groupId=$safeGroupId"
    }

    private fun sanitizeQueryValue(value: String): String {
        return value.replace(" ", "_").replace("&", "_").replace("=", "_")
    }

    private suspend fun openSocketConnection(fullUrl: String, connectToken: Int) {
        try {
            client.webSocket(urlString = fullUrl) {
                if (connectionGen.get() != connectToken) {
                    close(CloseReason(CloseReason.Codes.NORMAL, "Stale connection"))
                    return@webSocket
                }
                session = this
                log("✅ [WebSocket] 连接已打开")
                onConnected()
                consumeIncomingFrames(connectToken)
            }
        } catch (e: CancellationException) {
            rethrowCancellation(e)
        } catch (e: IOException) {
            handleConnectFailure(e, connectToken)
        } catch (e: IllegalStateException) {
            handleConnectFailure(e, connectToken)
        } finally {
            finalizeConnection(connectToken)
        }
    }

    private suspend fun DefaultClientWebSocketSession.consumeIncomingFrames(connectToken: Int) {
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    onMessage(frame.readText())
                }
            }
        } catch (e: CancellationException) {
            rethrowCancellation(e)
        } catch (e: IOException) {
            handleReceiveFailure(e, connectToken)
        } catch (e: IllegalStateException) {
            handleReceiveFailure(e, connectToken)
        }
    }

    private fun handleReceiveFailure(error: Exception, connectToken: Int) {
        if (connectionGen.get() == connectToken) {
            log("❌ [WebSocket] 接收错误: ${error.message}")
        }
    }

    private fun handleConnectFailure(error: Exception, connectToken: Int) {
        if (connectionGen.get() == connectToken) {
            log("❌ [WebSocket] 连接失败: ${error.message}")
            onError(error.message ?: "Unknown error")
        }
    }

    private fun finalizeConnection(connectToken: Int) {
        if (connectionGen.get() == connectToken) {
            session = null
            job = null
            onDisconnected()
        }
    }
    
    actual fun send(message: String) {
        scope.launch {
            try {
                session?.send(Frame.Text(message))
            } catch (e: CancellationException) {
                rethrowCancellation(e)
            } catch (e: IOException) {
                log("❌ [WebSocket] 发送失败: ${e.message}")
            } catch (e: IllegalStateException) {
                log("❌ [WebSocket] 发送失败: ${e.message}")
            }
        }
    }
    
    actual fun disconnect() {
        val disconnectToken = connectionGen.incrementAndGet()
        val currentSession = session
        val currentJob = job
        session = null
        job = null

        scope.launch {
            try {
                currentSession?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnecting"))
            } catch (e: CancellationException) {
                rethrowCancellation(e)
            } catch (e: IOException) {
                log("⚠️ [WebSocket] 关闭异常: ${e.message}")
            } catch (e: IllegalStateException) {
                log("⚠️ [WebSocket] 关闭异常: ${e.message}")
            } finally {
                currentJob?.cancel()
                if (connectionGen.get() == disconnectToken) {
                    onDisconnected()
                }
            }
        }
    }
}
