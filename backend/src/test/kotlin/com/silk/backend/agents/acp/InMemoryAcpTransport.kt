// backend/src/test/kotlin/com/silk/backend/agents/acp/InMemoryAcpTransport.kt
package com.silk.backend.agents.acp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * 测试用内存 transport。
 * - clientOutbound: client 写出去的消息 → 测试侧读
 * - serverOutbound: 测试侧塞进来 → client 读到
 */
class InMemoryAcpTransport : AcpTransport {
    private val serverOutbound = Channel<String>(capacity = Channel.UNLIMITED)
    private val clientOutbound = Channel<String>(capacity = Channel.UNLIMITED)

    @Volatile
    override var isClosed: Boolean = false
        private set

    override val incoming: Flow<String> = serverOutbound.consumeAsFlow()

    override suspend fun send(line: String) {
        check(!isClosed) { "transport closed" }
        clientOutbound.send(line)
    }

    override suspend fun close(reason: String) {
        if (isClosed) return
        isClosed = true
        serverOutbound.close()
        clientOutbound.close()
    }

    /** 测试用：模拟服务端推一条消息给 client */
    suspend fun pushFromServer(line: String) {
        serverOutbound.send(line)
    }

    /** 测试用：读一条 client 写出的消息 */
    suspend fun readClientSent(): String = clientOutbound.receive()
}
