package com.silk.backend.kb

import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBTopic
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object KBObsidianExporter {
    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun toMarkdown(topic: KBTopic, entry: KBEntry): String {
        val createdAt = formatIso(entry.createdAt)
        val updatedAt = formatIso(entry.updatedAt)

        val builder = StringBuilder()
        builder.appendLine("---")
        builder.appendLine("silk_kb: true")
        builder.appendLine("topic: \"${escapeYaml(topic.name)}\"")
        if (topic.project.isNotBlank()) {
            builder.appendLine("project: \"${escapeYaml(topic.project)}\"")
        }
        builder.appendLine("entry_id: \"${entry.id}\"")
        builder.appendLine("topic_id: \"${topic.id}\"")
        builder.appendLine("created_at: \"$createdAt\"")
        builder.appendLine("updated_at: \"$updatedAt\"")
        if (entry.tags.isNotEmpty()) {
            builder.appendLine("tags: [${entry.tags.joinToString(", ") { escapeYaml(it) }}]")
        }
        builder.appendLine("---")
        builder.appendLine()
        builder.appendLine("# ${entry.title}")
        builder.appendLine()
        builder.appendLine(entry.content)

        return builder.toString()
    }

    fun suggestVaultPath(topic: KBTopic, entry: KBEntry): String {
        val safeProject = sanitize(topic.project.ifBlank { "General" })
        val safeTopic = sanitize(topic.name)
        val safeTitle = sanitize(entry.title.ifBlank { "untitled" })
        return "Silk/Knowledge/$safeProject/$safeTopic/$safeTitle.md"
    }

    private fun sanitize(input: String): String =
        input.replace(Regex("[^\\w\\s\\-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "unknown" }

    private fun formatIso(timestampMs: Long): String =
        isoFormatter.format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))

    private fun escapeYaml(input: String): String =
        input.replace("\\", "\\\\").replace("\"", "\\\"")
}
