package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

data class KnowledgeBaseNavigationTarget(
    val entryId: String,
    val topicId: String? = null,
    val requestId: Long,
)

data class KnowledgeBaseDeepLink(
    val entryId: String,
    val topicId: String? = null,
)

sealed interface KnowledgeBaseTextSegment {
    data class Plain(val text: String) : KnowledgeBaseTextSegment
    data class Reference(val entryId: String, val label: String) : KnowledgeBaseTextSegment
}

private val knowledgeBaseReferenceRegex = Regex("""\[\[kb:([A-Za-z0-9_\-]+)(?:\|([^\]]+))?\]\]""")

fun buildKnowledgeBaseReference(entry: KBEntryItem): String = "[[kb:${entry.id}|${entry.title}]]"

fun parseKnowledgeBaseTextSegments(content: String): List<KnowledgeBaseTextSegment> {
    if (content.isEmpty()) return emptyList()
    val segments = mutableListOf<KnowledgeBaseTextSegment>()
    var cursor = 0
    knowledgeBaseReferenceRegex.findAll(content).forEach { match ->
        if (match.range.first > cursor) {
            segments += KnowledgeBaseTextSegment.Plain(content.substring(cursor, match.range.first))
        }
        val entryId = match.groupValues[1]
        val label = match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() } ?: entryId
        segments += KnowledgeBaseTextSegment.Reference(entryId = entryId, label = label)
        cursor = match.range.last + 1
    }
    if (cursor < content.length) {
        segments += KnowledgeBaseTextSegment.Plain(content.substring(cursor))
    }
    return if (segments.isEmpty()) listOf(KnowledgeBaseTextSegment.Plain(content)) else segments
}

fun parseKnowledgeBaseDeepLink(path: String?): KnowledgeBaseDeepLink? {
    if (path.isNullOrBlank() || !path.startsWith("kb://")) return null
    val parts = path.removePrefix("kb://").split("/").filter { it.isNotBlank() }
    return when (parts.size) {
        1 -> KnowledgeBaseDeepLink(entryId = parts[0])
        2 -> KnowledgeBaseDeepLink(topicId = parts[0], entryId = parts[1])
        else -> null
    }
}

fun renderKnowledgeBaseMarkersInHtml(html: String): String {
    return knowledgeBaseReferenceRegex.replace(html) { match ->
        val entryId = match.groupValues[1]
        val label = (match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() } ?: entryId)
            .escapeHtml()
        """<a href="#" class="silk-kb-link" data-kb-entry-id="$entryId">[[$label]]</a>"""
    }
}

fun openKnowledgeBaseEntryLink(entryId: String, topicId: String? = null): Boolean {
    val opener = window.asDynamic().__silkOpenKnowledgeBaseEntry
    return try {
        if (opener != null) {
            opener(topicId, entryId)
            true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }
}

fun attachKnowledgeBaseLinkHandlers(root: HTMLElement) {
    val links = root.querySelectorAll(".silk-kb-link")
    var index = 0
    while (index < links.length) {
        bindKnowledgeBaseLink(links.item(index) as? HTMLElement)
        index += 1
    }
}

@Composable
fun InlineKnowledgeBaseText(content: String) {
    val segments = remember(content) { parseKnowledgeBaseTextSegments(content) }
    segments.forEach { segment ->
        when (segment) {
            is KnowledgeBaseTextSegment.Plain -> Text(segment.text)
            is KnowledgeBaseTextSegment.Reference -> {
                Span({
                    style {
                        color(Color(SilkColors.primaryDark))
                        property("cursor", "pointer")
                        fontWeight("500")
                        property("text-decoration", "underline")
                        property("text-decoration-style", "dotted")
                    }
                    attr("title", "打开知识库文档")
                    onClick { openKnowledgeBaseEntryLink(entryId = segment.entryId) }
                }) {
                    Text("[[${segment.label}]]")
                }
            }
        }
    }
}

private fun String.escapeHtml(): String {
    return this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

private fun bindKnowledgeBaseLink(link: HTMLElement?) {
    val element = link ?: return
    if (element.dataset.asDynamic().silkKbBound == "true") {
        return
    }

    element.dataset.asDynamic().silkKbBound = "true"
    element.addEventListener("click", { event ->
        event.preventDefault()
        val entryId = element.getAttribute("data-kb-entry-id") ?: return@addEventListener
        openKnowledgeBaseEntryLink(entryId = entryId)
    })
}
