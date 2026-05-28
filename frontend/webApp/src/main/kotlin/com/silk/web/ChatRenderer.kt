package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.silk.shared.models.MessageReference
import kotlinx.browser.document
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * 结构化内容渲染器 — 参考 claudian (YishenTu/claudian) 的显示流程。
 *
 * 解析消息中的标记并渲染为可折叠块：
 * - `<!--THINKING-->...<!--END_THINKING-->` → 可折叠 thinking 块
 * - `<!--TOOL name="..." status="..." summary="..."-->...<!--END_TOOL-->` → 可折叠 tool 块
 * - 其余内容 → 普通 Markdown 渲染
 */

private data class ContentSegment(
    val type: String,       // "text", "thinking", "tool"
    val content: String,
    val meta: Map<String, String> = emptyMap()
)

private fun parseStructuredContent(raw: String): List<ContentSegment> {
    val segments = mutableListOf<ContentSegment>()
    var remaining = raw

    while (remaining.isNotEmpty()) {
        val ti = remaining.indexOf("<!--THINKING-->")
        val te = remaining.indexOf("<!--TOOL")
        val first = when {
            ti >= 0 && (te < 0 || ti < te) -> "thinking" to ti
            te >= 0 -> "tool" to te
            else -> null to -1
        }

        if (first == null) {
            segments.add(ContentSegment("text", remaining.trim()))
            break
        }

        val (type, pos) = first

        // Text before the marker
        if (pos > 0) {
            val before = remaining.substring(0, pos).trim()
            if (before.isNotEmpty()) segments.add(ContentSegment("text", before))
        }

        if (type == "thinking") {
            val endTag = "<!--END_THINKING-->"
            val endIdx = remaining.indexOf(endTag, pos)
            if (endIdx > 0) {
                val content = remaining.substring(pos + "<!--THINKING-->".length, endIdx).trim()
                segments.add(ContentSegment("thinking", content))
                remaining = remaining.substring(endIdx + endTag.length)
            } else {
                remaining = remaining.substring(pos + "<!--THINKING-->".length)
            }
        } else {
            val closeTag = "-->"
            val closeIdx = remaining.indexOf(closeTag, pos + "<!--TOOL".length)
            if (closeIdx > 0) {
                val attrs = remaining.substring(pos + "<!--TOOL".length, closeIdx).trim()
                val meta = parseAttrs(attrs)
                val endTag = "<!--END_TOOL-->"
                val endIdx = remaining.indexOf(endTag, closeIdx)
                if (endIdx > 0) {
                    val content = remaining.substring(closeIdx + closeTag.length, endIdx).trim()
                    segments.add(ContentSegment("tool", content, meta))
                    remaining = remaining.substring(endIdx + endTag.length)
                } else {
                    remaining = remaining.substring(closeIdx + closeTag.length)
                }
            } else {
                remaining = remaining.substring(pos + "<!--TOOL".length)
            }
        }
    }

    return segments.filter { it.content.isNotEmpty() }
}

private fun parseAttrs(s: String): Map<String, String> {
    val m = mutableMapOf<String, String>()
    val r = Regex("""(\w+)\s*=\s*"([^"]*)"""")
    r.findAll(s).forEach { m[it.groupValues[1]] = it.groupValues[2] }
    return m
}

/** 将消息内容渲染为结构化块序列（thinking/tool/text） */
@Composable
fun StructuredContent(content: String, references: List<MessageReference>, msgId: String) {
    val hasAnyMarker = content.contains("<!--THINKING") || content.contains("<!--TOOL")
    if (!hasAnyMarker) {
        MarkdownContent(content = content, references = references)
        return
    }

    val segments = remember(content) { parseStructuredContent(content) }
    for ((i, seg) in segments.withIndex()) {
        when (seg.type) {
            "thinking" -> ThinkingBlock(content = seg.content)
            "tool" -> ToolCallBlock(
                toolName = seg.meta["name"] ?: "",
                status = seg.meta["status"] ?: "completed",
                summary = seg.meta["summary"] ?: "",
                content = seg.content
            )
            else -> MarkdownContent(content = seg.content, references = references)
        }
    }
}

/** 可折叠 Thinking 块 — "Thought for Xs" 标签 + 树状左边线 */
@Composable
fun ThinkingBlock(content: String) {
    var expanded by remember { mutableStateOf(false) }
    // Estimate duration from content or just show "Thought" for stored messages
    val label = "思考过程"

    Div({
        style {
            margin(8.px, 0.px)
            property("border-left", "2px solid #E8E0D4")
            paddingLeft(16.px)
            marginLeft(7.px)
        }
    }) {
        // Clickable header
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "8px")
                padding(4.px, 0.px)
                property("cursor", "pointer")
                property("user-select", "none")
                fontSize(13.px)
                fontWeight("500")
                color(Color("#C9A86C"))
            }
            attr("tabindex", "0")
            attr("role", "button")
            onClick { expanded = !expanded }
        }) {
            Span({ style { property("flex-shrink", "0"); fontSize(14.px) } }) { Text("💭") }
            Span({ style { property("flex", "1") } }) { Text(label) }
            Span({
                style {
                    fontSize(10.px)
                    color(Color("#B8A890"))
                    property("transition", "transform 0.2s")
                    if (expanded) { property("transform", "rotate(90deg)") }
                }
            }) { Text("▶") }
        }

        // Collapsible content
        if (expanded) {
            Div({
                style {
                    fontSize(13.px)
                    property("line-height", "1.5")
                    color(Color("#8A7B6A"))
                    padding(4.px, 0.px)
                }
            }) {
                MarkdownContent(content = content, references = emptyList())
            }
        }
    }
}

/** 可折叠 Tool 调用块 — "Read xxx" 标签 + 状态 + 树状左边线 */
@Composable
fun ToolCallBlock(toolName: String, status: String = "completed", summary: String = "", content: String = "") {
    var expanded by remember { mutableStateOf(false) }

    val icon = when {
        toolName.contains("bash", ignoreCase = true) -> "💻"
        toolName.contains("read", ignoreCase = true) -> "📖"
        toolName.contains("write", ignoreCase = true) || toolName.contains("edit", ignoreCase = true) -> "✏️"
        toolName.contains("grep", ignoreCase = true) || toolName.contains("glob", ignoreCase = true) || toolName.contains("search", ignoreCase = true) -> "🔍"
        toolName.contains("web", ignoreCase = true) || toolName.contains("fetch", ignoreCase = true) -> "🌐"
        toolName.contains("todo", ignoreCase = true) -> "✅"
        toolName.contains("think", ignoreCase = true) -> "💭"
        toolName.contains("ask", ignoreCase = true) -> "❓"
        toolName.contains("plan", ignoreCase = true) -> "📋"
        toolName.contains("apply", ignoreCase = true) || toolName.contains("patch", ignoreCase = true) -> "🔧"
        else -> "🔧"
    }

    val statusColor = when (status) {
        "completed" -> "#7DAE6C"
        "error" -> "#D97B7B"
        "running" -> "#C9A86C"
        else -> "#8A7B6A"
    }

    val displayName = if (summary.isNotEmpty()) "$toolName $summary" else toolName

    Div({
        style {
            margin(6.px, 0.px)
            property("border-left", "2px solid #E8E0D4")
            paddingLeft(16.px)
            marginLeft(7.px)
        }
    }) {
        // Clickable header
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "8px")
                padding(4.px, 0.px)
                property("cursor", "pointer")
                property("user-select", "none")
                property("font-family", "SF Mono, Fira Code, monospace")
                fontSize(13.px)
            }
            attr("tabindex", "0")
            attr("role", "button")
            onClick { expanded = !expanded }
        }) {
            Span({ style { property("flex-shrink", "0"); fontSize(14.px) } }) { Text(icon) }
            Span({
                style {
                    color(Color("#4A4038"))
                    property("white-space", "nowrap")
                    property("flex-shrink", "0")
                }
            }) { Text(toolName) }
            if (summary.isNotEmpty()) {
                Span({
                    style {
                        color(Color("#8A7B6A"))
                        fontSize(12.px)
                        property("overflow", "hidden")
                        property("text-overflow", "ellipsis")
                        property("white-space", "nowrap")
                        property("flex", "1")
                        property("min-width", "0")
                    }
                }) { Text(summary) }
            }
            // Status dot
            Span({
                style {
                    width(8.px)
                    height(8.px)
                    borderRadius(50.percent)
                    backgroundColor(Color(statusColor))
                    property("flex-shrink", "0")
                    if (status == "running") { property("animation", "silk-pulse 1.5s ease-in-out infinite") }
                }
            }) {}
            Span({
                style {
                    fontSize(10.px)
                    color(Color("#B8A890"))
                    property("transition", "transform 0.2s")
                    if (expanded) { property("transform", "rotate(90deg)") }
                }
            }) { Text("▶") }
        }

        // Collapsible content
        if (expanded && content.isNotEmpty()) {
            Div({
                style {
                    padding(4.px, 0.px)
                    property("font-family", "SF Mono, Fira Code, monospace")
                    fontSize(12.px)
                    property("line-height", "1.4")
                    color(Color("#8A7B6A"))
                    property("white-space", "pre-wrap")
                    property("word-break", "break-word")
                    maxHeight(300.px)
                    property("overflow-y", "auto")
                }
            }) {
                Text(content)
            }
        }
    }
}
