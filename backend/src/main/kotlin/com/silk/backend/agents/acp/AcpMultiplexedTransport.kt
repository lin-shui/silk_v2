package com.silk.backend.agents.acp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded ACP logical stream carried inside a device-level Host WebSocket. */
class AcpMultiplexedTransport(
    private val sendToHost: suspend (String) -> Unit,
    private val onClose: suspend (String) -> Unit = {},
    capacity: Int = DEFAULT_CAPACITY,
) : AcpTransport {
    private val messages = Channel<String>(capacity)
    private val closed = AtomicBoolean(false)

    override val incoming: Flow<String> = messages.receiveAsFlow()

    override val isClosed: Boolean
        get() = closed.get()

    override suspend fun send(line: String) {
        check(!isClosed) { "transport closed" }
        require(line.length <= MAX_MESSAGE_CHARS) { "ACP message exceeds multiplexed transport limit" }
        sendToHost(line)
    }

    suspend fun acceptFromHost(line: String) {
        check(!isClosed) { "transport closed" }
        require(line.length <= MAX_MESSAGE_CHARS) { "ACP message exceeds multiplexed transport limit" }
        messages.send(line)
    }

    fun tryAcceptFromHost(line: String): Boolean {
        if (isClosed || line.length > MAX_MESSAGE_CHARS) return false
        return messages.trySend(line).isSuccess
    }

    override suspend fun close(reason: String) {
        close(reason, notifyHost = true)
    }

    suspend fun closeFromHost(reason: String) {
        close(reason, notifyHost = false)
    }

    private suspend fun close(reason: String, notifyHost: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        messages.close()
        if (notifyHost) onClose(reason)
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
        const val MAX_MESSAGE_CHARS = 10 * 1024 * 1024
    }
}
