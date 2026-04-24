package com.silk.shared

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
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
    
    actual val isConnected: Boolean
        get() = session != null
    
    actual fun connect(userId: String, userName: String, groupId: String) {
        val connectToken = connectionGen.incrementAndGet()

        // 切群时先让旧连接失效，避免旧协程 finally 把新连接状态清空。
        val previousSession = session
        job?.cancel()
        session = null
        job = null

        previousSession?.let { oldSession ->
            scope.launch {
                try {
                    oldSession.close(CloseReason(CloseReason.Codes.NORMAL, "Switching group"))
                } catch (_: CancellationException) {
                    // Normal cancellation while replacing the socket.
                } catch (e: Exception) {
                    log("⚠️ [WebSocket] 旧连接关闭异常: ${e.message}")
                }
            }
        }

        val safeUserName = userName.replace(" ", "_").replace("&", "_").replace("=", "_")
        val safeGroupId = groupId.replace(" ", "_").replace("&", "_").replace("=", "_")
        val fullUrl = "$serverUrl/chat?userId=$userId&userName=$safeUserName&groupId=$safeGroupId"
        
        log("🔗 [WebSocket] 连接到: $fullUrl")
        
        job = scope.launch {
            try {
                client.webSocket(urlString = fullUrl) {
                    if (connectionGen.get() != connectToken) {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Stale connection"))
                        return@webSocket
                    }
                    session = this
                    log("✅ [WebSocket] 连接已打开")
                    onConnected()
                    
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    onMessage(text)
                                }
                                else -> {}
                            }
                        }
                    } catch (e: CancellationException) {
                        // Normal cancellation
                    } catch (e: Exception) {
                        if (connectionGen.get() == connectToken) {
                            log("❌ [WebSocket] 接收错误: ${e.message}")
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Normal cancellation while reconnecting or disconnecting.
            } catch (e: Exception) {
                if (connectionGen.get() == connectToken) {
                    log("❌ [WebSocket] 连接失败: ${e.message}")
                    onError(e.message ?: "Unknown error")
                }
            } finally {
                if (connectionGen.get() == connectToken) {
                    session = null
                    job = null
                    onDisconnected()
                }
            }
        }
    }
    
    actual fun send(message: String) {
        scope.launch {
            try {
                session?.send(Frame.Text(message))
            } catch (e: Exception) {
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
            } catch (_: CancellationException) {
                // Normal cancellation while disconnecting.
            } catch (e: Exception) {
                log("⚠️ [WebSocket] 关闭异常: ${e.message}")
            }
            currentJob?.cancel()
            if (connectionGen.get() == disconnectToken) {
                onDisconnected()
            }
        }
    }
}
