package com.silk.backend.database

import com.silk.backend.ChatHistoryManager
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId

/** Backfills the dedicated room recency field from persisted message history. */
object RoomLastMessageMigration {
    private val logger = LoggerFactory.getLogger(RoomLastMessageMigration::class.java)

    fun run() {
        val roomIds = transaction {
            Groups.selectAll()
                .filter { it[Groups.lastMessageAt] == null }
                .map { it[Groups.id] }
        }
        if (roomIds.isEmpty()) return

        val historyManager = ChatHistoryManager()
        val backfilled = roomIds.associateWith { roomId ->
            historyManager.loadChatHistory("group_$roomId")
                ?.messages
                ?.maxOfOrNull { it.timestamp }
                ?.takeIf { it > 0L }
                ?: 0L
        }

        transaction {
            backfilled.forEach { (roomId, timestamp) ->
                Groups.update({ Groups.id eq roomId }) {
                    it[Groups.lastMessageAt] = Instant.ofEpochMilli(timestamp)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                }
            }
        }
        logger.info(
            "Room last-message migration complete: recovered={}, candidates={}",
            backfilled.values.count { it > 0L },
            roomIds.size,
        )
    }
}
