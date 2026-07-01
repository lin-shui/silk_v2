package com.silk.backend.card

import com.silk.backend.Message
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

interface CardReplyHandler {
    suspend fun onCardReply(
        reply: CardReplyPayload,
        sessionName: String,
        broadcastFn: suspend (Message) -> Unit,
    )
}

@Suppress("TooGenericExceptionCaught")
object CardReplyRouter {

    private val logger = LoggerFactory.getLogger(CardReplyRouter::class.java)
    private val handlers = ConcurrentHashMap<String, CardReplyHandler>()

    fun register(cardId: String, handler: CardReplyHandler) {
        handlers[cardId] = handler
        logger.info("[CardReplyRouter] Registered handler for card: {}", cardId)
    }

    fun unregister(cardId: String) {
        handlers.remove(cardId)
        logger.info("[CardReplyRouter] Unregistered handler for card: {}", cardId)
    }

    suspend fun route(
        sessionName: String,
        reply: CardReplyPayload,
        broadcastFn: suspend (Message) -> Unit,
    ): Boolean {
        val handler = handlers[reply.cardId]
        if (handler == null) {
            logger.warn("[CardReplyRouter] No handler for card: {} (expired?)", reply.cardId)
            return true
        }
        try {
            handler.onCardReply(reply, sessionName, broadcastFn)
        } catch (e: Exception) {
            logger.error("[CardReplyRouter] Handler failed for card: {}", reply.cardId, e)
        }
        return false
    }

    fun clear() {
        handlers.clear()
    }
}
