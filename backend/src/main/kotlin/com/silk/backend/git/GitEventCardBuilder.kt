@file:Suppress("CyclomaticComplexMethod")

package com.silk.backend.git

import com.silk.backend.card.CardBuilder
import com.silk.backend.card.ButtonType

object GitEventCardBuilder {
    fun build(event: GitEventRecord): String {
        val template = when {
            event.event == "check_run" && event.summary.contains("conclusion=failure") -> "red"
            event.event == "check_run" -> "green"
            event.event == "pull_request" && event.action == "merged" -> "green"
            event.event == "pull_request" -> "purple"
            else -> "blue"
        }
        val title = when (event.event) {
            "issues" -> when (event.action) {
                "opened" -> "GitHub Issue 已创建"
                "closed" -> "GitHub Issue 已关闭"
                "reopened" -> "GitHub Issue 已重新打开"
                else -> "GitHub Issue ${event.action}"
            }
            "pull_request" -> when (event.action) {
                "opened" -> "GitHub Pull Request 已创建"
                "merged" -> "GitHub Pull Request 已合并"
                "closed" -> "GitHub Pull Request 已关闭"
                "reopened" -> "GitHub Pull Request 已重新打开"
                "ready_for_review" -> "GitHub Pull Request 可供审查"
                else -> "GitHub Pull Request ${event.action}"
            }
            "check_run" -> "GitHub Check Run ${event.action}"
            else -> "GitHub event"
        }
        val card = CardBuilder(title, template = template)
        if (event.title.isNotBlank()) card.addText(event.title)
        card.addText(buildString {
            append(event.repository)
            event.issueNumber?.let { append(" #").append(it) }
            if (event.summary.isNotBlank()) append("\n").append(event.summary)
        })
        if (event.htmlUrl.isNotBlank()) card.addButton("打开 GitHub", "github:url:${event.htmlUrl}")
        if (event.event == "issues" && event.issueNumber != null) {
            card.addButton(
                "开始开发",
                "github:issue-to-workspace:${event.roomId}:${event.issueNumber}",
                ButtonType.PRIMARY,
            )
        }
        return card.build()
    }

    fun buildSummary(event: GitEventRecord, summary: String): String {
        val template = when {
            event.event == "check_run" && event.summary.contains("conclusion=failure") -> "red"
            event.event == "pull_request" -> "purple"
            else -> "blue"
        }
        val card = CardBuilder("GitHub AI 摘要", template = template)
        card.addText(buildString {
            append(event.repository)
            event.issueNumber?.let { append(" #").append(it) }
            if (event.title.isNotBlank()) append("\n").append(event.title)
        })
        card.addDivider()
        card.addText(summary.take(MAX_SUMMARY_LENGTH))
        if (event.htmlUrl.isNotBlank()) card.addButton("打开 GitHub", "github:url:${event.htmlUrl}")
        return card.build()
    }

    private const val MAX_SUMMARY_LENGTH = 1_200
}
