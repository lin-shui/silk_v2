// backend/src/main/kotlin/com/silk/backend/agents/acp/AcpTransport.kt
package com.silk.backend.agents.acp

import kotlinx.coroutines.flow.Flow

/**
 * ACP 消息传输抽象。
 *
 * - `send(line)` 发送一行 JSON 文本（line-delimited JSON-RPC）。
 * - `incoming` 是接收到的每一行文本的 Flow。**单收集器约定**：每个 transport 实例只允许一个 collector
 *   （通常是其关联的 [AcpClient.receiveLoop]）。再次 collect 行为未定义，可能丢消息或抛异常。
 * - `close()` 关闭传输；之后 `incoming` 应正常完成。
 *
 * 实现：生产用 [AcpWebSocketTransport]，测试用 InMemoryAcpTransport。
 */
interface AcpTransport {
    val incoming: Flow<String>
    suspend fun send(line: String)
    suspend fun close(reason: String = "normal")
    val isClosed: Boolean
}
