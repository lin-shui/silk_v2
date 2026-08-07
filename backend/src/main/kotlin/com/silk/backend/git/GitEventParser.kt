@file:Suppress("CyclomaticComplexMethod")

package com.silk.backend.git

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object GitEventParser {
    const val MAX_BODY_BYTES = 1_048_576
    const val MAX_FIELD_LENGTH = 500
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private val supportedActions = mapOf(
        "issues" to setOf("opened", "closed", "reopened"),
        "pull_request" to setOf("opened", "closed", "reopened", "ready_for_review"),
        "check_run" to setOf("completed"),
    )

    fun parse(
        event: String,
        actionHeader: String?,
        body: ByteArray,
        roomId: String = "",
        deliveryId: String = "",
        createdAt: Long = System.currentTimeMillis(),
    ): GitEventRecord? {
        if (body.size > MAX_BODY_BYTES) return null
        val root = runCatching { json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: return null
        val action = actionHeader?.trim()?.takeIf { it.isNotBlank() }
            ?: root.string("action")
            ?: return null
        if (supportedActions[event]?.contains(action) != true) return null
        val subject = when (event) {
            "issues" -> root.obj("issue")
            "pull_request" -> root.obj("pull_request")
            "check_run" -> root.obj("check_run")
            else -> null
        } ?: return null
        val repository = root.obj("repository")?.string("full_name")?.trim().orEmpty()
        if (repository.isBlank()) return null
        val issueNumber = root.int("number") ?: subject.int("number")
        val title = subject.string("title").bounded()
        val url = (subject.string("html_url") ?: root.string("html_url")).bounded()
        val actor = root.obj("sender")?.string("login").bounded()
        val state = subject.string("state").bounded()
        val labels = subject.arrayStrings("labels").joinToString(", ")
        val branch = if (event == "pull_request") {
            val head = subject.obj("head")?.string("ref").bounded()
            val base = subject.obj("base")?.string("ref").bounded()
            "${head.takeIf { it.isNotBlank() } ?: "?"} -> ${base.takeIf { it.isNotBlank() } ?: "?"}"
        } else ""
        val checkName = if (event == "check_run") subject.string("name").bounded() else ""
        val conclusion = if (event == "check_run") subject.string("conclusion").bounded() else ""
        val summary = buildString {
            append(event).append('/').append(action)
            if (title.isNotBlank()) append(" | ").append(title)
            if (actor.isNotBlank()) append(" | by ").append(actor)
            if (state.isNotBlank()) append(" | state=").append(state)
            if (labels.isNotBlank()) append(" | labels=").append(labels.bounded())
            if (branch.isNotBlank()) append(" | ").append(branch)
            if (checkName.isNotBlank()) append(" | check=").append(checkName)
            if (conclusion.isNotBlank()) append(" | conclusion=").append(conclusion)
        }.bounded(MAX_FIELD_LENGTH)
        return GitEventRecord(
            deliveryId = deliveryId.bounded(200),
            roomId = roomId.bounded(200),
            event = event,
            action = action,
            repository = repository.bounded(200),
            issueNumber = issueNumber,
            title = title,
            htmlUrl = url,
            summary = summary,
            createdAt = createdAt,
        )
    }

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.jsonObject
    private fun JsonObject.arrayStrings(key: String): List<String> = get(key)?.jsonArray.orEmpty().mapNotNull {
        runCatching { it.jsonObject.string("name") }.getOrNull() ?: it.jsonPrimitive.contentOrNull
    }.map { it.bounded(80) }
    private fun String?.bounded(limit: Int = MAX_FIELD_LENGTH): String = this.orEmpty().trim().take(limit)

    // ── Polling-mode API list parsing ──────────────────────────────────────────

    /**
     * Parse GitHub API list items (from [GitHubClient.listIssuesAndPulls]) into
     * [GitEventRecord]s.  The shape differs from webhook payloads so this is a
     * separate path; the [parse] method is not involved.
     */
    fun parseIssueList(
        items: List<GitHubIssueListItem>,
        roomId: String,
        pollCursorMs: Long,
        createdAt: Long = System.currentTimeMillis(),
    ): List<GitEventRecord> = items.mapNotNull { item ->
        runCatching { parseIssueListItem(item, roomId, pollCursorMs, createdAt) }.getOrNull()
    }

    /**
     * Whether this action warrants a Team Channel CARD broadcast.
     * `updated` events are recorded for cursor/dedup but not broadcast — title or
     * label changes would flood the channel.
     */
    fun shouldBroadcast(action: String): Boolean = action != "updated"

    /**
     * Stable synthetic delivery ID for polling dedup.
     * Same resource + same updated_at → same key → [GitEventStore.recordDelivery] deduplicates.
     */
    fun pollDeliveryId(event: String, number: Int, updatedAt: String): String =
        "poll:$event:$number:${updatedAt.take(50)}".take(200)

    private fun parseIssueListItem(
        item: GitHubIssueListItem,
        roomId: String,
        pollCursorMs: Long,
        createdAt: Long,
    ): GitEventRecord? {
        if (item.number <= 0) return null
        val event = if (item.pull_request != null) "pull_request" else "issues"
        val action = inferAction(item, pollCursorMs)
        val actor = item.user.login.take(MAX_FIELD_LENGTH)
        val labels = item.labels.joinToString(", ") { it.name.take(80) }
        val summary = buildString {
            append(event).append('/').append(action)
            if (item.title.isNotBlank()) append(" | ").append(item.title.take(MAX_FIELD_LENGTH))
            if (actor.isNotBlank()) append(" | by ").append(actor)
            if (item.state.isNotBlank()) append(" | state=").append(item.state)
            if (labels.isNotBlank()) append(" | labels=").append(labels.take(MAX_FIELD_LENGTH))
        }.take(MAX_FIELD_LENGTH)
        return GitEventRecord(
            deliveryId = pollDeliveryId(event, item.number, item.updated_at),
            roomId = roomId.take(200),
            event = event,
            action = action,
            repository = "",
            issueNumber = item.number,
            title = item.title.take(MAX_FIELD_LENGTH),
            htmlUrl = item.html_url.take(MAX_FIELD_LENGTH),
            summary = summary,
            createdAt = createdAt,
        )
    }

    internal fun inferAction(item: GitHubIssueListItem, pollCursorMs: Long): String {
        fun isoToMs(s: String?): Long? = s?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        }
        val createdMs = isoToMs(item.created_at)
        val closedMs = isoToMs(item.closed_at)
        // New: created within this poll window and currently open
        if (createdMs != null && createdMs > pollCursorMs && item.state == "open") return "opened"
        // Reopened: only detectable on issues via state_reason; PRs lack this field
        if (item.pull_request == null && item.state_reason == "reopened") return "reopened"
        // Merged PR: merged_at is visible even through the /issues endpoint
        if (item.pull_request?.merged_at != null) return "merged"
        // Closed within poll window
        if (item.state == "closed" && closedMs != null && closedMs > pollCursorMs) return "closed"
        // Default: title/label/body/comment change — not worth a card
        return "updated"
    }
}
