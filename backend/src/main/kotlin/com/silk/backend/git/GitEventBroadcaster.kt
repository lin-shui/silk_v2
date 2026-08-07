package com.silk.backend.git

import com.silk.backend.Message
import com.silk.backend.MessageScope
import com.silk.backend.MessageType

object GitEventBroadcaster {
    fun message(event: GitEventRecord): Message = Message(
        id = "github_${event.deliveryId}",
        userId = "github_bot",
        userName = "GitHub",
        content = GitEventCardBuilder.build(event),
        timestamp = event.createdAt,
        type = MessageType.CARD,
        scope = MessageScope.TEAM,
    )

    fun summaryMessage(event: GitEventRecord, summary: String): Message = Message(
        id = "github_summary_${event.deliveryId}",
        userId = "github_bot",
        userName = "GitHub AI",
        content = GitEventCardBuilder.buildSummary(event, summary),
        timestamp = System.currentTimeMillis(),
        type = MessageType.CARD,
        scope = MessageScope.TEAM,
    )
}
