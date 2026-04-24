// backend/src/main/kotlin/com/silk/backend/claudecode/ClaudeCodeManager.kt
package com.silk.backend.claudecode

import com.silk.backend.Message
import com.silk.backend.MessageCategory
import com.silk.backend.MessageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Claude Code 模式管理器。
 * 管理 per-user-per-group 的 CC 状态，路由命令和普通消息。
 * 所有 CC 执行委托给远程 Bridge Agent。
 */
object ClaudeCodeManager {

    private val logger = LoggerFactory.getLogger(ClaudeCodeManager::class.java)

    const val CC_AGENT_ID = "silk_ai_agent"
    const val CC_AGENT_NAME = "🤖 Claude Code"
    private const val MAX_QUEUE_SIZE = 10

    private val states = ConcurrentHashMap<String, UserCCState>()

    /** requestId → 回调上下文，用于 bridge 响应路由 */
    data class RequestContext(
        val userId: String,
        val groupId: String,
        val broadcastFn: suspend (Message) -> Unit,
        val startTime: Long = System.currentTimeMillis(),
    )

    private val activeRequests = ConcurrentHashMap<String, RequestContext>()

    /**
     * 纯 RPC 风格的请求响应（不经过 broadcastFn，直接通过 Deferred 返回）。
     * 用于 list_dir 等"前端直接等响应"的查询。
     */
    private val pendingRpc = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    /**
     * 向 bridge 发送一条 RPC 风格请求并等待响应。
     * 如果 bridge 未连接返回 null；如果超时抛 TimeoutCancellationException。
     */
    private suspend fun rpcCall(
        userId: String,
        request: BridgeRequest,
        timeoutMs: Long = 5_000,
    ): JsonObject? {
        if (!BridgeRegistry.isConnected(userId)) return null
        val deferred = CompletableDeferred<JsonObject>()
        pendingRpc[request.requestId] = deferred
        val sent = BridgeRegistry.sendToBridge(userId, request)
        if (!sent) {
            pendingRpc.remove(request.requestId)
            return null
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingRpc.remove(request.requestId)
            throw e
        }
    }

    /**
     * 列出指定用户下 Bridge 所在机器上 [path] 的子目录（不包含文件）。
     * @return bridge 未连接/未响应时返回 null；否则返回 bridge 的原始 JSON 对象。
     */
    suspend fun listDirectory(userId: String, path: String?, showHidden: Boolean = false): JsonObject? {
        val requestId = UUID.randomUUID().toString()
        return try {
            rpcCall(
                userId = userId,
                request = BridgeRequest(
                    type = "list_dir",
                    requestId = requestId,
                    path = path ?: "",
                    showHidden = showHidden,
                ),
            )
        } catch (e: TimeoutCancellationException) {
            logger.warn("[CC] list_dir 超时: userId={}, path={}", userId, path)
            null
        }
    }

    /**
     * RPC 风格的 /cd：不经过聊天消息流，直接同步切换 Bridge 工作目录并更新对应 group 的 CC 状态。
     * 适用于"创建工作流时设置初始目录"、"前端面板按钮换目录"等场景，避免在聊天里出现 /cd 指令气泡。
     *
     * 副作用：成功后会重置 sessionId/sessionStarted/messageQueue（与 handleCd 行为一致）。
     */
    sealed class CdResult {
        data class Ok(val resolvedPath: String) : CdResult()
        data class Err(val reason: String) : CdResult()
    }

    suspend fun cdSync(userId: String, groupId: String, path: String): CdResult {
        val state = getOrCreateState(userId, groupId)
        // 先做快速检查（允许不一致但快速失败）；真正修改放到 withLock 里二次校验
        if (state.running) return CdResult.Err("任务运行中，请先 /cancel 再 /cd")
        if (!BridgeRegistry.isConnected(userId)) return CdResult.Err("Bridge 未连接")

        val requestId = UUID.randomUUID().toString()
        val raw = try {
            rpcCall(
                userId = userId,
                request = BridgeRequest(type = "cd", requestId = requestId, path = path),
            )
        } catch (e: TimeoutCancellationException) {
            return CdResult.Err("Bridge 未响应或超时")
        } ?: return CdResult.Err("Bridge 未连接或发送失败")

        val success = raw["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!success) {
            val err = raw["error"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
            return CdResult.Err(err)
        }
        val resolved = raw["path"]?.jsonPrimitive?.contentOrNull ?: path
        // 复合状态变更在 mutex 保护下完成（和 chat 路径的 executePrompt/finishQueue 互斥）
        return state.withLock { h ->
            // 二次校验：在 rpcCall 过程中可能有其他操作把 running 置为 true
            if (h.running) {
                return@withLock CdResult.Err("任务运行中，请先 /cancel 再 /cd")
            }
            h.sessionId = UUID.randomUUID().toString()
            h.sessionStarted = false
            h.workingDir = resolved
            h.messageQueue.clear()
            h.active = true  // 确保 CC 已激活，便于后续聊天命令路由
            CdResult.Ok(resolved)
        }
    }

    /**
     * 用户 CC 状态。字段私有，外部只能通过 [withLock] 拿到 [Handle] 来修改；
     * 强制所有复合修改在 mutex 保护下串行化，避免 chat WebSocket 路径与 HTTP 路径
     * 并发改写同一 state 造成的 race（见 doc: step2 封装）。
     *
     * 只读快照仍可通过 [snapshot] 直接获取（各字段 @Volatile，单字段读取原子；
     * 快照内部字段间可能有短暂不一致，但对 UI 展示足够）。
     */
    class UserCCState {
        // 字段仍保留 @Volatile：写总在 mutex 内，读可无锁得到最新值
        @Volatile private var _active: Boolean = false
        @Volatile private var _sessionId: String = UUID.randomUUID().toString()
        @Volatile private var _sessionStarted: Boolean = false
        @Volatile private var _running: Boolean = false
        @Volatile private var _workingDir: String = System.getProperty("user.dir") ?: "/"
        @Volatile private var _cancelled: Boolean = false
        private val _messageQueue: java.util.concurrent.ConcurrentLinkedDeque<QueuedMessage> =
            java.util.concurrent.ConcurrentLinkedDeque()

        private val mutex = Mutex()

        /** 只读访问，非锁保护。适合展示/判断，要求复合一致性的逻辑必须用 [withLock]。 */
        val active: Boolean get() = _active
        val sessionId: String get() = _sessionId
        val sessionStarted: Boolean get() = _sessionStarted
        val running: Boolean get() = _running
        val workingDir: String get() = _workingDir
        val cancelled: Boolean get() = _cancelled
        /** 队列本身是线程安全的，单独 peek 没问题；批量检查+修改仍需走 withLock。 */
        val messageQueue: java.util.concurrent.ConcurrentLinkedDeque<QueuedMessage> get() = _messageQueue

        /**
         * 获取当前 state 的只读快照（各字段来自同一瞬间的 @Volatile 读）。
         */
        fun snapshot(): Snapshot = Snapshot(
            active = _active,
            sessionId = _sessionId,
            sessionStarted = _sessionStarted,
            running = _running,
            workingDir = _workingDir,
            cancelled = _cancelled,
            queueSize = _messageQueue.size,
        )

        /**
         * 在 mutex 保护下执行写/复合操作。所有会修改 state 的代码都应该走这里。
         * 被 block 接收到的 [Handle] 是修改入口，只在 block 内有效——离开 block 后
         * 对 handle 的调用行为未定义（字段更新仍会生效，但不再受 mutex 保护）。
         *
         * block 可以是 suspend 的（例如内部还要 sendToBridge）。但记住：长时间挂起会
         * 占着锁，影响其他人——尽量只在锁内做必要的检查与字段更新。
         */
        suspend fun <T> withLock(block: suspend (Handle) -> T): T = mutex.withLock {
            block(handle)
        }

        private val handle = object : Handle {
            override var active: Boolean
                get() = _active
                set(value) { _active = value }
            override var sessionId: String
                get() = _sessionId
                set(value) { _sessionId = value }
            override var sessionStarted: Boolean
                get() = _sessionStarted
                set(value) { _sessionStarted = value }
            override var running: Boolean
                get() = _running
                set(value) { _running = value }
            override var workingDir: String
                get() = _workingDir
                set(value) { _workingDir = value }
            override var cancelled: Boolean
                get() = _cancelled
                set(value) { _cancelled = value }
            override val messageQueue: java.util.concurrent.ConcurrentLinkedDeque<QueuedMessage>
                get() = _messageQueue
        }

        /** 在 [withLock] 块内暴露的可变接口，强制"先 lock 再改"。 */
        interface Handle {
            var active: Boolean
            var sessionId: String
            var sessionStarted: Boolean
            var running: Boolean
            var workingDir: String
            var cancelled: Boolean
            /** 队列本身并发安全；在 Handle 中使用可保证读-改-写的原子性。 */
            val messageQueue: java.util.concurrent.ConcurrentLinkedDeque<QueuedMessage>
        }

        data class Snapshot(
            val active: Boolean,
            val sessionId: String,
            val sessionStarted: Boolean,
            val running: Boolean,
            val workingDir: String,
            val cancelled: Boolean,
            val queueSize: Int,
        )
    }

    data class QueuedMessage(val text: String, val userId: String, val userName: String)

    private fun key(userId: String, groupId: String) = "${userId}_${groupId}"

    /**
     * 查询用户在某 group 下的 CC 状态（只读快照）。返回 null 表示从未激活。
     */
    fun snapshotState(userId: String, groupId: String): CCStateSnapshot? {
        val state = states[key(userId, groupId)] ?: return null
        val s = state.snapshot()
        return CCStateSnapshot(
            active = s.active,
            running = s.running,
            workingDir = s.workingDir,
            sessionId = s.sessionId,
            sessionStarted = s.sessionStarted,
        )
    }

    data class CCStateSnapshot(
        val active: Boolean,
        val running: Boolean,
        val workingDir: String,
        val sessionId: String,
        val sessionStarted: Boolean,
    )

    private fun getOrCreateState(userId: String, groupId: String): UserCCState {
        return states.getOrPut(key(userId, groupId)) { UserCCState() }
    }

    /**
     * 被 ChatServer.handleStopGeneration() 调用。
     * 如果该用户处于 CC 模式且有运行中任务，则执行取消并返回 true。
     */
    suspend fun cancelIfActive(userId: String, groupId: String, broadcastFn: suspend (Message) -> Unit): Boolean {
        val state = states[key(userId, groupId)] ?: return false
        if (!state.active) return false
        handleCancel(userId, state, broadcastFn)
        return true
    }

    /**
     * 被 ChatServer.broadcast() 调用的入口。
     * 返回 true 表示消息已被 CC 处理，ChatServer 不应再走 Silk AI 逻辑。
     */
    suspend fun handleIfActive(
        userId: String,
        groupId: String,
        text: String,
        userName: String,
        broadcastFn: suspend (Message) -> Unit,
    ): Boolean {
        val trimmed = text.trim()

        // "/cc" 任何时候都拦截
        if (trimmed.lowercase() == "/cc") {
            activate(userId, groupId, broadcastFn)
            return true
        }

        // 非 CC 模式 → 不拦截
        val state = states[key(userId, groupId)] ?: return false
        if (!state.active) return false

        // CC 模式下的消息路由
        routeMessage(userId, groupId, userName, trimmed, state, broadcastFn)
        return true
    }

    /**
     * 工作流自动激活 CC 模式（静默激活，不发送提示消息）。
     * 如果已经激活则不重复操作。
     */
    suspend fun autoActivateForWorkflow(userId: String, groupId: String) {
        val state = getOrCreateState(userId, groupId)
        state.withLock { h ->
            if (h.active) return@withLock
            h.active = true
            // 保留已有的 sessionId，不重置
            logger.info("[CC] 工作流自动激活: userId={}, groupId={}", userId, groupId)
        }
    }

    private suspend fun activate(userId: String, groupId: String, broadcastFn: suspend (Message) -> Unit) {
        val state = getOrCreateState(userId, groupId)
        val newSessionId = state.withLock { h ->
            h.active = true
            h.sessionId = UUID.randomUUID().toString()
            h.sessionStarted = false
            h.sessionId
        }
        logger.info("[CC] 用户激活 CC 模式: userId={}, groupId={}, sessionId={}", userId, groupId, newSessionId.take(8))
        broadcastFn(systemMessage(buildString {
            appendLine("🤖 Claude Code 已激活")
            appendLine("会话: ${newSessionId.take(8)}... | 目录: ${state.workingDir}")
            append("发送消息开始编程，/help 查看命令，/exit 退出")
        }))
    }

    private suspend fun routeMessage(
        userId: String, groupId: String, userName: String,
        text: String, state: UserCCState,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        when {
            text.lowercase() == "/exit" -> handleExit(userId, groupId, state, broadcastFn)
            text.lowercase() == "/new" -> handleNew(userId, groupId, state, broadcastFn)
            text.lowercase() == "/cancel" -> handleCancel(userId, state, broadcastFn)
            text.lowercase() == "/status" -> handleStatus(state, broadcastFn)
            text.lowercase() == "/help" -> handleHelp(broadcastFn)
            text.lowercase() == "/session" -> handleSessionList(userId, groupId, broadcastFn)
            text.lowercase().startsWith("/session ") -> handleSessionResume(userId, groupId, text.substring(9).trim(), state, broadcastFn)
            text.lowercase() == "/cd" || text.lowercase().startsWith("/cd ") -> {
                broadcastFn(systemMessage("ℹ️ /cd 命令已废弃。请使用工作流头部的「更改」按钮选择目录。"))
            }
            text.lowercase() == "/queue" -> handleQueueView(state, broadcastFn)
            text.lowercase() == "/queue clear" -> handleQueueClear(state, broadcastFn)
            text.lowercase() == "/compact" -> executePrompt(userId, groupId, userName, "/compact", state, broadcastFn)
            else -> {
                // 原子地决定走入队还是走执行；执行的实际状态变更在 executePrompt 内部仍走 withLock
                val shouldEnqueue = state.withLock { h -> h.running }
                if (shouldEnqueue) {
                    enqueue(state, text, userId, userName, broadcastFn)
                } else {
                    executePrompt(userId, groupId, userName, text, state, broadcastFn)
                }
            }
        }
    }

    // ========== 命令处理 ==========

    private suspend fun handleExit(userId: String, groupId: String, state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        state.withLock { h ->
            if (h.running) {
                val activeRequestId = activeRequests.entries.find { it.value.userId == userId }?.key
                if (activeRequestId != null) {
                    BridgeRegistry.sendToBridge(userId, BridgeRequest(type = "cancel", requestId = activeRequestId))
                }
            }
            h.active = false
            h.running = false
            h.messageQueue.clear()
        }
        logger.info("[CC] 用户退出 CC 模式: userId={}", userId)
        broadcastFn(systemMessage("已退出 Claude Code 模式"))
    }

    private suspend fun handleNew(userId: String, groupId: String, state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        if (state.running) {
            broadcastFn(systemMessage("任务运行中，请先 /cancel 再 /new"))
            return
        }
        if (!BridgeRegistry.isConnected(userId)) {
            broadcastFn(systemMessage("Bridge 未连接。"))
            return
        }
        val requestId = UUID.randomUUID().toString()
        activeRequests[requestId] = RequestContext(userId, groupId, broadcastFn)
        BridgeRegistry.sendToBridge(userId, BridgeRequest(type = "new_session", requestId = requestId))
    }

    private suspend fun handleCancel(userId: String, state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        val msg = state.withLock { h ->
            if (!h.running) {
                return@withLock null
            }
            val dropped = h.messageQueue.size
            h.messageQueue.clear()
            h.cancelled = true
            val activeRequestId = activeRequests.entries.find { it.value.userId == userId }?.key
            if (activeRequestId != null) {
                BridgeRegistry.sendToBridge(userId, BridgeRequest(type = "cancel", requestId = activeRequestId))
            }
            if (!h.sessionStarted) h.sessionStarted = true
            buildString {
                append("已发送取消请求")
                if (dropped > 0) append("（队列中 $dropped 条指令已清空）")
            }
        }
        if (msg == null) {
            broadcastFn(systemMessage("当前没有运行中的任务"))
        } else {
            broadcastFn(systemMessage(msg))
        }
    }

    private suspend fun handleStatus(state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        broadcastFn(systemMessage(buildString {
            appendLine("📊 Claude Code 状态")
            appendLine("会话: ${state.sessionId.take(8)}...")
            appendLine("目录: ${state.workingDir}")
            appendLine("状态: ${if (state.running) "运行中" else "空闲"}")
            if (state.messageQueue.isNotEmpty()) {
                append("队列: ${state.messageQueue.size} 条")
            }
        }))
    }

    private suspend fun handleHelp(broadcastFn: suspend (Message) -> Unit) {
        broadcastFn(systemMessage(buildString {
            appendLine("📖 Claude Code 命令")
            appendLine("/exit — 退出 CC 模式")
            appendLine("/new — 新建会话")
            appendLine("/cancel — 取消当前任务")
            appendLine("/session — 查看历史会话")
            appendLine("/session <id> — 恢复会话")
            appendLine("/status — 查看当前状态")
            appendLine("/queue — 查看排队消息")
            appendLine("/queue clear — 清空队列")
            appendLine("/compact — 压缩上下文")
            appendLine("（切换工作目录请使用界面上的「更改」按钮）")
            append("/help — 显示此帮助")
        }))
    }

    private suspend fun handleSessionList(userId: String, groupId: String, broadcastFn: suspend (Message) -> Unit) {
        if (!BridgeRegistry.isConnected(userId)) {
            broadcastFn(systemMessage("Bridge 未连接，无法查看历史会话。请先配置并启动 Bridge。"))
            return
        }
        val requestId = UUID.randomUUID().toString()
        activeRequests[requestId] = RequestContext(userId, groupId, broadcastFn)
        BridgeRegistry.sendToBridge(userId, BridgeRequest(type = "list_sessions", requestId = requestId))
    }

    private suspend fun handleSessionResume(
        userId: String, groupId: String, idPrefix: String,
        state: UserCCState, broadcastFn: suspend (Message) -> Unit,
    ) {
        if (state.running) {
            broadcastFn(systemMessage("任务运行中，无法切换会话"))
            return
        }
        if (!BridgeRegistry.isConnected(userId)) {
            broadcastFn(systemMessage("Bridge 未连接，无法恢复会话。"))
            return
        }
        val requestId = UUID.randomUUID().toString()
        activeRequests[requestId] = RequestContext(userId, groupId, broadcastFn)
        BridgeRegistry.sendToBridge(userId, BridgeRequest(type = "resume_session", requestId = requestId, sessionIdPrefix = idPrefix))
    }

    private suspend fun handleQueueView(state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        if (state.messageQueue.isEmpty()) {
            broadcastFn(systemMessage("队列为空"))
            return
        }
        val text = buildString {
            appendLine("排队中的指令（共 ${state.messageQueue.size} 条）")
            for ((i, item) in state.messageQueue.toList().withIndex()) {
                val preview = if (item.text.length > 30) item.text.take(30) + "…" else item.text
                appendLine("${i + 1}. $preview")
            }
            append("/queue clear 清空全部")
        }
        broadcastFn(systemMessage(text))
    }

    private suspend fun handleQueueClear(state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        val count = state.withLock { h ->
            val c = h.messageQueue.size
            h.messageQueue.clear()
            c
        }
        broadcastFn(systemMessage("已清空队列（$count 条）"))
    }

    // ========== 执行 ==========

    private suspend fun enqueue(
        state: UserCCState, text: String, userId: String, userName: String,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        // size check + add 必须原子：两条并发消息都看到 size=9 会各自插入，溢出上限
        val result = state.withLock { h ->
            if (h.messageQueue.size >= MAX_QUEUE_SIZE) {
                -1  // full
            } else {
                h.messageQueue.addLast(QueuedMessage(text, userId, userName))
                h.messageQueue.size
            }
        }
        if (result < 0) {
            broadcastFn(systemMessage("队列已满（最多 $MAX_QUEUE_SIZE 条），请等待或 /cancel"))
        } else {
            broadcastFn(statusMessage("指令已加入队列（第 $result 条）"))
        }
    }

    private suspend fun executePrompt(
        userId: String, groupId: String, userName: String,
        prompt: String, state: UserCCState,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        if (!BridgeRegistry.isConnected(userId)) {
            broadcastFn(systemMessage("Bridge 未连接，无法执行。请先在设置中生成 Token，然后启动 Bridge Agent。"))
            return
        }

        // 原子地获取执行上下文：设置 running=true，读取 sessionId/workingDir/sessionStarted 快照
        val execCtx = state.withLock { h ->
            h.running = true
            h.cancelled = false
            Triple(h.sessionId, h.workingDir, h.sessionStarted)
        }
        val (sessionId, workingDir, sessionStarted) = execCtx

        val requestId = UUID.randomUUID().toString()
        activeRequests[requestId] = RequestContext(userId, groupId, broadcastFn)

        val isCompact = prompt == "/compact"
        val request = BridgeRequest(
            type = if (isCompact) "compact" else "execute",
            requestId = requestId,
            prompt = prompt,
            sessionId = sessionId,
            workingDir = workingDir,
            resume = if (isCompact) true else sessionStarted,
        )

        logger.info("[CC] 发送到 Bridge: type={}, prompt={}, sessionId={}, resume={}", request.type, prompt.take(30), sessionId.take(8), sessionStarted)
        val sent = BridgeRegistry.sendToBridge(userId, request)
        if (!sent) {
            state.withLock { h -> h.running = false }
            activeRequests.remove(requestId)
            broadcastFn(systemMessage("发送命令到 Bridge 失败"))
        }
    }

    private suspend fun finishAndProcessQueue(
        userId: String, groupId: String,
        state: UserCCState,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        // 原子地取队首或清 running
        val next = state.withLock { h ->
            if (h.messageQueue.isNotEmpty() && !h.cancelled) {
                val n = h.messageQueue.removeFirst()
                h.cancelled = false
                n
            } else {
                h.running = false
                null
            }
        }
        if (next != null) {
            CoroutineScope(Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                logger.error("[CC] 队列自动执行异常: {}", throwable.message, throwable)
                // 异常恢复：running 回落
                CoroutineScope(Dispatchers.IO).launch {
                    state.withLock { h -> h.running = false }
                }
            }).launch {
                executePrompt(next.userId, groupId, next.userName, next.text, state, broadcastFn)
            }
        }
    }

    // ========== Bridge 消息处理 ==========

    /**
     * 处理来自 bridge 的消息。由 Routing.kt 的 /cc-bridge 端点调用。
     */
    suspend fun handleBridgeMessage(userId: String, jsonStr: String) {
        val data = try {
            Json.parseToJsonElement(jsonStr).jsonObject
        } catch (e: Exception) {
            logger.warn("[CC] Bridge 消息解析失败: {}", e.message)
            return
        }

        val type = data["type"]?.jsonPrimitive?.contentOrNull ?: return
        val requestId = data["requestId"]?.jsonPrimitive?.contentOrNull

        // hello message
        if (type == "hello") {
            val dir = data["defaultDir"]?.jsonPrimitive?.contentOrNull ?: ""
            BridgeRegistry.updateDefaultDir(userId, dir)
            logger.info("[CC] Bridge hello: userId={}, defaultDir={}", userId, dir)
            return
        }

        if (type == "pong") return

        // RPC 风格响应：直接完成对应的 Deferred，不经过 activeRequests/broadcastFn
        if (requestId != null) {
            val rpc = pendingRpc.remove(requestId)
            if (rpc != null) {
                rpc.complete(data)
                return
            }
        }

        val ctx = if (requestId != null) activeRequests[requestId] else null
        if (ctx == null && requestId != null) {
            logger.debug("[CC] Bridge 消息的 requestId 无匹配上下文: {}", requestId)
            return
        }

        val broadcastFn = ctx?.broadcastFn ?: return
        val stateKey = "${ctx.userId}_${ctx.groupId}"
        val state = states[stateKey] ?: return

        when (type) {
            "stream_text" -> {
                val text = data["text"]?.jsonPrimitive?.contentOrNull ?: ""
                broadcastFn(streamingMessage(text))
            }
            "tool_log" -> {
                val log = data["log"]?.jsonPrimitive?.contentOrNull ?: ""
                val stableId = data["stableId"]?.jsonPrimitive?.contentOrNull
                broadcastFn(statusMessage(log, stableId))
            }
            "status_update" -> {
                val status = data["status"]?.jsonPrimitive?.contentOrNull ?: ""
                broadcastFn(statusMessage(status, "cc_running_status"))
            }
            "complete" -> {
                val text = data["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val metaObj = data["meta"]?.jsonObject
                val meta = if (metaObj != null) {
                    StreamParser.ResultMeta(
                        costUsd = metaObj["costUsd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        durationMs = metaObj["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                        numTurns = metaObj["numTurns"]?.jsonPrimitive?.intOrNull ?: 0,
                        sessionId = metaObj["sessionId"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                } else null

                val wallClockMs = System.currentTimeMillis() - ctx.startTime
                if (text.isNotBlank()) {
                    broadcastFn(finalMessage(text))
                }
                val effectiveMeta = (meta ?: StreamParser.ResultMeta()).copy(durationMs = wallClockMs)
                val metaStr = StreamParser.formatMeta(effectiveMeta)
                // 先清除状态消息（tool_log 等），重置前端 isGenerating
                broadcastFn(statusMessage("CLEAR_STATUS"))
                // 再发 meta 信息作为永久消息（不会触发 isGenerating）
                if (metaStr.isNotBlank()) {
                    broadcastFn(systemMessage(metaStr))
                }
                state.withLock { h ->
                    if (meta?.sessionId?.isNotBlank() == true) {
                        h.sessionId = meta.sessionId
                    }
                    h.sessionStarted = true
                }
                activeRequests.remove(requestId)
                finishAndProcessQueue(ctx.userId, ctx.groupId, state, broadcastFn)
            }
            "error" -> {
                val error = data["error"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
                val exitCode = data["exitCode"]?.jsonPrimitive?.intOrNull
                val wallClockMs = System.currentTimeMillis() - ctx.startTime
                val msg = buildString {
                    append("❌ $error")
                    if (exitCode != null) append(" (exit=$exitCode)")
                    append("（耗时: %.1fs）".format(wallClockMs / 1000.0))
                }
                broadcastFn(systemMessage(msg))
                state.withLock { h -> h.sessionStarted = true }
                activeRequests.remove(requestId)
                finishAndProcessQueue(ctx.userId, ctx.groupId, state, broadcastFn)
            }
            "cancelled" -> {
                activeRequests.remove(requestId)
                state.withLock { h -> h.cancelled = true }
                finishAndProcessQueue(ctx.userId, ctx.groupId, state, broadcastFn)
            }
            "session_list" -> {
                activeRequests.remove(requestId)
                val sessions = data["sessions"]?.jsonArray
                if (sessions == null || sessions.isEmpty()) {
                    broadcastFn(systemMessage("暂无历史会话记录。完成第一次任务后将自动记录。"))
                } else {
                    val text = buildString {
                        appendLine("📋 历史会话（共 ${sessions.size} 个）")
                        for ((i, s) in sessions.withIndex()) {
                            val obj = s.jsonObject
                            val sid = obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""
                            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                            val dir = obj["workingDir"]?.jsonPrimitive?.contentOrNull ?: ""
                            val lastAct = obj["lastActivity"]?.jsonPrimitive?.contentOrNull ?: ""
                            appendLine("${i + 1}. ${sid.take(8)}... | $title")
                            appendLine("   目录: $dir | 最近: $lastAct")
                        }
                        append("发送 `/session <id前缀>` 恢复会话")
                    }
                    broadcastFn(systemMessage(text))
                }
            }
            "session_resumed" -> {
                activeRequests.remove(requestId)
                val resumedId = data["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""
                val resumedDirFromBridge = data["workingDir"]?.jsonPrimitive?.contentOrNull
                val finalDir = state.withLock { h ->
                    val resumedDir = resumedDirFromBridge ?: h.workingDir
                    h.sessionId = resumedId
                    h.workingDir = resumedDir
                    h.sessionStarted = true
                    h.messageQueue.clear()
                    resumedDir
                }
                broadcastFn(systemMessage("已恢复会话 ${resumedId.take(8)}...\n目录: $finalDir\n发送消息继续"))
            }
            "new_session" -> {
                activeRequests.remove(requestId)
                val newSessionId = data["sessionId"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
                val dir = state.withLock { h ->
                    h.sessionId = newSessionId
                    h.sessionStarted = false
                    h.messageQueue.clear()
                    h.workingDir
                }
                broadcastFn(systemMessage("会话已重置\n新会话: ${newSessionId.take(8)}... | 目录: $dir"))
            }
        }
    }

    /**
     * Bridge 断线处理。由 BridgeRegistry.unregister 调用（非 suspend 入口）。
     * 这里的状态修改通过 launch 协程持锁完成——调用方不会 block。
     */
    fun handleBridgeDisconnect(userId: String) {
        for ((key, state) in states) {
            if (!key.startsWith("${userId}_")) continue
            if (!state.active) continue

            // 读-判-改不是原子的——用 launch 进锁里判断
            val snap = state.snapshot()
            if (snap.running) {
                logger.info("[CC] Bridge 断线，用户 {} 有运行中的任务", userId)
                CoroutineScope(Dispatchers.IO).launch {
                    state.withLock { h ->
                        h.running = false
                        h.cancelled = false
                    }
                }
                val toRemove = activeRequests.entries.filter { it.value.userId == userId }
                for (entry in toRemove) {
                    val ctx = entry.value
                    activeRequests.remove(entry.key)
                    CoroutineScope(Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                        logger.error("[CC] Bridge 断线通知发送失败: {}", throwable.message)
                    }).launch {
                        ctx.broadcastFn(systemMessage("⚠️ Bridge 已断开，正在执行的任务已丢失"))
                    }
                }
            }

            if (snap.queueSize > 0) {
                CoroutineScope(Dispatchers.IO).launch {
                    state.withLock { h -> h.messageQueue.clear() }
                }
                logger.info("[CC] Bridge 断线，清空用户 {} 的消息队列（{}条）", userId, snap.queueSize)
            }
        }

        // Bridge 断开时，pendingRpc 里该 user 的 in-flight RPC 会在对应的
        // withTimeout (默认 5s) 内自然超时清理，无需在此主动 cancel（目前 pendingRpc
        // 未按 userId 索引）。若将来 RPC 数量增大，可把 pendingRpc value 改成带 userId
        // 的结构以支持精确取消。
    }

    // ========== 消息构造 ==========

    private fun generateId(): String = UUID.randomUUID().toString()

    fun systemMessage(content: String) = Message(
        id = generateId(),
        userId = CC_AGENT_ID,
        userName = CC_AGENT_NAME,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = MessageType.SYSTEM,
        isTransient = false,
    )

    private fun statusMessage(content: String, stableId: String? = null) = Message(
        id = stableId ?: generateId(),
        userId = CC_AGENT_ID,
        userName = CC_AGENT_NAME,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = MessageType.SYSTEM,
        isTransient = true,
        category = MessageCategory.AGENT_STATUS,
    )

    private fun streamingMessage(accumulated: String) = Message(
        id = "cc_streaming",
        userId = CC_AGENT_ID,
        userName = CC_AGENT_NAME,
        content = accumulated,
        timestamp = System.currentTimeMillis(),
        type = MessageType.TEXT,
        isTransient = true,
        isIncremental = false,
    )

    private fun finalMessage(content: String) = Message(
        id = generateId(),
        userId = CC_AGENT_ID,
        userName = CC_AGENT_NAME,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = MessageType.TEXT,
        isTransient = false,
        isIncremental = false,
    )
}
