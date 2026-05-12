// backend/src/main/kotlin/com/silk/backend/agents/acp/AcpClient.kt
package com.silk.backend.agents.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ACP (Zed Agent Client Protocol) JSON-RPC 2.0 client。
 * 与具体 agent 类型无关，只负责消息收发与请求/响应配对。
 */
class AcpClient(
    private val transport: AcpTransport,
    scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(AcpClient::class.java)
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()

    private val sessionUpdateHandlers = ConcurrentHashMap<String, (SessionUpdateNotification) -> Unit>()
    private val permissionHandlers = ConcurrentHashMap<String, suspend (PermissionRequestParams) -> PermissionResponse>()

    /**
     * 为指定 ACP session 注册 `session/update` 通知处理器。
     *
     * 同一 sessionId 重复注册会覆盖旧 handler（同一 session 的 queue drain 循环换 accumulated 时正常）。
     * 不同 sessionId 的 handler 互不影响。
     *
     * **重要**：handler 在 receive-loop 协程中同步执行，会阻塞该循环对后续消息的处理。
     * 不要在 handler 内做耗时操作或挂起；如需执行重型工作，请在 handler 内 `launch` 到另一个 scope。
     */
    fun onSessionUpdate(sessionId: String, handler: (SessionUpdateNotification) -> Unit) {
        sessionUpdateHandlers[sessionId] = handler
    }

    /**
     * 为指定 ACP session 注册 `session/request_permission` 处理器。
     *
     * 同 [onSessionUpdate]：按 sessionId 隔离，handler 不应长时间阻塞 receive loop。
     * 未注册 handler 的 session 收到 permission request 会回以 JSON-RPC error -32601。
     */
    fun onPermissionRequest(sessionId: String, handler: suspend (PermissionRequestParams) -> PermissionResponse) {
        permissionHandlers[sessionId] = handler
    }

    /**
     * 注销指定 session 的所有 handler。
     * 在 prompt 结束或 session 销毁时调用，防止内存泄漏。
     */
    fun removeHandlers(sessionId: String) {
        sessionUpdateHandlers.remove(sessionId)
        permissionHandlers.remove(sessionId)
    }

    init {
        scope.launch { receiveLoop() }
    }

    suspend fun initialize(params: InitializeParams): InitializeResult {
        val resp = call("initialize", json.encodeToJsonElement(InitializeParams.serializer(), params))
        return decodeResultOrThrow(resp, InitializeResult.serializer())
    }

    suspend fun sessionNew(
        cwd: String,
        mcpServers: List<McpServer> = emptyList(),
        cliSessionId: String? = null,
    ): SessionNewResult {
        val params = json.encodeToJsonElement(
            SessionNewParams.serializer(),
            SessionNewParams(cwd = cwd, mcpServers = mcpServers, cliSessionId = cliSessionId),
        )
        val resp = call("session/new", params)
        return decodeResultOrThrow(resp, SessionNewResult.serializer())
    }

    suspend fun sessionLoad(sessionId: String, cwd: String): SessionLoadResult {
        val params = json.encodeToJsonElement(
            SessionLoadParams.serializer(),
            SessionLoadParams(sessionId = sessionId, cwd = cwd),
        )
        val resp = call("session/load", params)
        return decodeResultOrThrow(resp, SessionLoadResult.serializer())
    }

    suspend fun sessionPrompt(
        sessionId: String,
        prompt: List<ContentBlock>,
    ): SessionPromptResult {
        val params = json.encodeToJsonElement(
            SessionPromptParams.serializer(),
            SessionPromptParams(sessionId = sessionId, prompt = prompt),
        )
        val resp = call("session/prompt", params, timeoutMs = PROMPT_TIMEOUT_MS)
        return decodeResultOrThrow(resp, SessionPromptResult.serializer())
    }

    /** session/cancel 是 notification，不期望响应。 */
    suspend fun sessionCancel(sessionId: String) {
        val params = json.encodeToJsonElement(
            SessionCancelParams.serializer(),
            SessionCancelParams(sessionId = sessionId),
        )
        val notif = JsonRpcNotification(method = "session/cancel", params = params)
        transport.send(json.encodeToString(JsonRpcNotification.serializer(), notif))
    }

    /**
     * 调用 silk 私有扩展（method 形如 "_silk/compact"）。
     * 返回原始 JsonElement，由调用方按扩展自身 schema 反序列化。
     * 如果服务端返回 -32601 method not found，抛 [AcpRpcException]，调用方据此把对应 capability 标记为不支持。
     */
    suspend fun callExtension(method: String, params: kotlinx.serialization.json.JsonObject): JsonElement {
        require(method.startsWith("_silk/")) { "extension method must start with _silk/" }
        val resp = call(method, params)
        return decodeResultOrThrow(resp, JsonElement.serializer())
    }

    /** 关闭底层 transport。 */
    suspend fun close(reason: String = "closed") {
        transport.close(reason)
    }

    // ---- core ----

    private suspend fun call(
        method: String,
        params: JsonElement,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JsonRpcResponse {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
        try {
            val req = JsonRpcRequest(id = id, method = method, params = params)
            transport.send(json.encodeToString(JsonRpcRequest.serializer(), req))
        } catch (e: Exception) {
            pending.remove(id)
            throw e
        }
        return try {
            if (timeoutMs == Long.MAX_VALUE) {
                deferred.await()
            } else {
                withTimeout(timeoutMs) { deferred.await() }
            }
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        /** 控制面操作（initialize / sessionNew / sessionLoad / callExtension）的默认超时。 */
        const val DEFAULT_TIMEOUT_MS = 30_000L
        /** sessionPrompt 不设超时，生命周期由 session/cancel + promptJob.cancelAndJoin 管理。 */
        const val PROMPT_TIMEOUT_MS = Long.MAX_VALUE
    }

    private suspend fun receiveLoop() {
        try {
            transport.incoming.collect { line -> dispatch(line) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("[AcpClient] receive loop ended: {}", e.message)
        } finally {
            // receive loop 终止（transport 关闭 / scope 取消 / 异常）：失败所有 pending
            val cause = IllegalStateException("receive loop terminated")
            pending.values.forEach { it.completeExceptionally(cause) }
            pending.clear()
        }
    }

    private suspend fun dispatch(line: String) {
        val element = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            logger.warn("[AcpClient] malformed JSON: {}", e.message)
            return
        }
        val id = element["id"]?.jsonPrimitive?.longOrNull
        val method = element["method"]?.jsonPrimitive?.contentOrNull

        try {
            when {
                // 响应（有 id 但没有 method）
                id != null && method == null -> {
                    val resp = json.decodeFromJsonElement(JsonRpcResponse.serializer(), element)
                    pending.remove(id)?.complete(resp)
                }
                // 通知（有 method 无 id）
                id == null && method != null -> handleNotification(method, element["params"])
                // 服务端 → client 请求（有 id 有 method）
                id != null && method != null -> handleServerRequest(id, method, element["params"])
                else -> logger.warn("[AcpClient] unrecognized message: {}", line)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 单条消息处理失败不应杀死整个 receive loop（影响所有待响应的 RPC）
            logger.warn("[AcpClient] dispatch failed for method={}, id={}: {}", method, id, e.message)
        }
    }

    private fun handleNotification(method: String, params: JsonElement?) {
        when (method) {
            "session/update" -> {
                if (params == null) return
                val notif = json.decodeFromJsonElement(SessionUpdateNotification.serializer(), params)
                sessionUpdateHandlers[notif.sessionId]?.invoke(notif)
            }
            else -> logger.debug("[AcpClient] unhandled notification: {}", method)
        }
    }

    private suspend fun handleServerRequest(id: Long, method: String, params: JsonElement?) {
        when (method) {
            "session/request_permission" -> {
                if (params == null) {
                    sendError(id, JsonRpcError(JsonRpcError.INVALID_PARAMS, "no params"))
                    return
                }
                val req = try {
                    json.decodeFromJsonElement(PermissionRequestParams.serializer(), params)
                } catch (e: Exception) {
                    sendError(id, JsonRpcError(JsonRpcError.INVALID_PARAMS, "bad params: ${e.message}"))
                    return
                }
                val handler = permissionHandlers[req.sessionId]
                if (handler == null) {
                    sendError(id, JsonRpcError(JsonRpcError.METHOD_NOT_FOUND, "no permission handler for session ${req.sessionId}"))
                    return
                }
                try {
                    val resp = handler(req)
                    val result = json.encodeToJsonElement(PermissionResponse.serializer(), resp)
                    sendResponse(id, result)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sendError(id, JsonRpcError(JsonRpcError.INTERNAL_ERROR, e.message ?: "handler error"))
                }
            }
            else -> sendError(id, JsonRpcError(JsonRpcError.METHOD_NOT_FOUND, method))
        }
    }

    private suspend fun sendResponse(id: Long, result: JsonElement) {
        val resp = JsonRpcResponse(id = id, result = result)
        transport.send(json.encodeToString(JsonRpcResponse.serializer(), resp))
    }

    private suspend fun sendError(id: Long, error: JsonRpcError) {
        val resp = JsonRpcResponse(id = id, error = error)
        transport.send(json.encodeToString(JsonRpcResponse.serializer(), resp))
    }

    private fun <T> decodeResultOrThrow(
        resp: JsonRpcResponse,
        deser: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        if (resp.error != null) throw AcpRpcException(resp.error)
        val result = resp.result ?: throw AcpRpcException(
            JsonRpcError(JsonRpcError.INTERNAL_ERROR, "missing result")
        )
        return json.decodeFromJsonElement(deser, result)
    }
}

class AcpRpcException(val rpcError: JsonRpcError) :
    RuntimeException("ACP error ${rpcError.code}: ${rpcError.message}")
