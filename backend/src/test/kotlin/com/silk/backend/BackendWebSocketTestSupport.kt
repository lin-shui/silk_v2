package com.silk.backend

import com.silk.backend.database.GroupRepository
import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal val backendWebSocketJson = Json { ignoreUnknownKeys = true }

private val backendWebSocketFrameBuffers =
    ConcurrentHashMap<DefaultClientWebSocketSession, ArrayDeque<Message>>()

internal fun createGroupForBackendWebSocketTest(groupName: String) =
    assertNotNull(GroupRepository.createGroup(groupName, hostId = "host-user"))

internal suspend fun assertBackendWebSocketUploadsRemainEmpty(workspace: TestWorkspace, groupId: String) {
    val uploadsDir = File(workspace.chatHistoryDir, "group_$groupId/uploads")
    delay(300)
    assertFalse(File(uploadsDir, "processed_urls.txt").exists())
    val savedFiles = uploadsDir.listFiles()
        ?.filter { it.name != "processed_urls.txt" }
        .orEmpty()
    assertTrue(savedFiles.isEmpty())
}

internal fun seedBackendWebSocketHistory(groupId: String, entries: List<ChatHistoryEntry>) {
    ChatHistoryManager().saveChatHistory(
        sessionName = "group_$groupId",
        chatHistory = ChatHistory(
            sessionId = "session-$groupId",
            messages = entries.toMutableList(),
        ),
    )
}

internal fun backendWebSocketChatEntry(
    messageId: String,
    senderId: String,
    senderName: String,
    content: String,
    timestamp: Long,
) = ChatHistoryEntry(
    messageId = messageId,
    senderId = senderId,
    senderName = senderName,
    content = content,
    timestamp = timestamp,
    messageType = "TEXT",
)

internal fun backendWebSocketMessageMarker(message: Message): String =
    if (message.type == MessageType.FILE) "FILE" else message.content

internal fun parseBackendWebSocketFilePayload(message: Message): FileMessagePayload {
    assertEquals(MessageType.FILE, message.type)
    return backendWebSocketJson.decodeFromString(message.content)
}

internal suspend fun HttpClient.connectBackendChat(
    userId: String,
    userName: String,
    groupId: String,
): DefaultClientWebSocketSession = webSocketSession {
    url("/chat?userId=$userId&userName=$userName&groupId=$groupId")
}

internal suspend fun DefaultClientWebSocketSession.receiveBackendHistory(): List<Message> = buildList {
    withTimeout(5_000) {
        while (true) {
            val message = receiveBackendRawMessage(5_000) ?: error("Timed out waiting for history replay")
            if (message.isBackendHistoryEndMarker()) {
                break
            }
            add(message)
        }
    }
}

internal suspend fun DefaultClientWebSocketSession.receiveBackendMessage(): Message {
    while (true) {
        val message = receiveBackendRawMessage(5_000) ?: error("Timed out waiting for websocket message")
        if (!message.isBackendHistoryEndMarker()) {
            return message
        }
    }
}

internal suspend fun DefaultClientWebSocketSession.receiveBackendMessageOrNull(timeoutMillis: Long): Message? {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (true) {
        val remainingMillis = deadline - System.currentTimeMillis()
        if (remainingMillis <= 0) {
            return null
        }
        val message = receiveBackendRawMessage(remainingMillis) ?: return null
        if (!message.isBackendHistoryEndMarker()) {
            return message
        }
    }
}

internal suspend fun DefaultClientWebSocketSession.receiveBackendMessagesUntil(
    predicate: (Message) -> Boolean,
): List<Message> = buildList {
    withTimeout(120_000) {
        while (true) {
            val message = receiveBackendMessage()
            add(message)
            if (predicate(message)) {
                break
            }
        }
    }
}

private suspend fun DefaultClientWebSocketSession.receiveBackendRawMessage(timeoutMillis: Long): Message? {
    val frameBuffer = backendWebSocketFrameBuffers.getOrPut(this) { ArrayDeque() }
    if (frameBuffer.isNotEmpty()) {
        return frameBuffer.removeFirst()
    }

    val frame = withTimeoutOrNull(timeoutMillis) { incoming.receive() } ?: return null
    return when (frame) {
        is Frame.Text -> parseBackendWebSocketFrameText(this, frame.readText())
        else -> error("Expected text frame but received $frame")
    }
}

private fun parseBackendWebSocketFrameText(
    session: DefaultClientWebSocketSession,
    text: String,
): Message {
    if (!text.startsWith("[")) {
        return backendWebSocketJson.decodeFromString(text)
    }

    val batch: List<Message> = backendWebSocketJson.decodeFromString(text)
    check(batch.isNotEmpty()) { "Empty batch frame" }

    val frameBuffer = backendWebSocketFrameBuffers.getOrPut(session) { ArrayDeque() }
    batch.drop(1).forEach(frameBuffer::addLast)
    return batch.first()
}

private fun Message.isBackendHistoryEndMarker(): Boolean =
    isTransient && type == MessageType.SYSTEM && content == "__history_end__"

internal suspend fun waitForBackendWebSocketCondition(
    timeoutMillis: Long = 5_000,
    intervalMillis: Long = 50,
    condition: () -> Boolean,
) {
    withTimeout(timeoutMillis) {
        while (!condition()) {
            delay(intervalMillis)
        }
    }
}

internal fun assertBackendWebSocketMessagesContainInOrder(actual: List<String>, expected: List<String>) {
    var cursor = 0
    expected.forEach { target ->
        while (cursor < actual.size && actual[cursor] != target) {
            cursor++
        }
        assertTrue(cursor < actual.size, "Expected '$target' in order within $actual")
        cursor++
    }
}

internal suspend inline fun <reified T> HttpResponse.decodeBackendWebSocketBody(): T =
    backendWebSocketJson.decodeFromString(bodyAsText())
