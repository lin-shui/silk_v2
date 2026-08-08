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
        val resourceUpdatedAt = subject.string("updated_at").bounded(100)
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
            source = GitEventSource.WEBHOOK,
            dedupeKey = if (issueNumber != null && resourceUpdatedAt.isNotBlank()) {
                "$repository:$event:$issueNumber:$action:$resourceUpdatedAt"
            } else {
                ""
            },
        )
    }

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.jsonObject
    private fun JsonObject.arrayStrings(key: String): List<String> = get(key)?.jsonArray.orEmpty().mapNotNull {
        runCatching { it.jsonObject.string("name") }.getOrNull() ?: it.jsonPrimitive.contentOrNull
    }.map { it.bounded(80) }
    private fun String?.bounded(limit: Int = MAX_FIELD_LENGTH): String = this.orEmpty().trim().take(limit)
}
