package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.silk.shared.models.MessageReference
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * 结构化内容渲染器 — 参考 claudian (YishenTu/claudian) 的显示流程。
 *
 * 格式约定（与 pty_chat.py 对齐）：
 * - `<!--THINKING-->...content...<!--END_THINKING-->`
 * - `<!--TOOL name="n" summary="s"-->...content...<!--END_TOOL-->`
 *
 * 注意：streaming 时 thinking 文本可能出现在 <!--THINKING--> 之前（分批发出），
 * 所有 <！--END_THINKING--> 之前的内容都应归入 thinking 块。
 */

// ── Segment types ──

private sealed class Segment {
    data class Text(val content: String) : Segment()
    data class Thinking(val content: String, val isComplete: Boolean = false) : Segment()
    data class ToolCall(val name: String, val summary: String, val content: String) : Segment()
}

// ── Parser: robust against partial/fragmented markers ──

private fun parseSegments(raw: String): List<Segment> {
    if (!raw.contains("<!--THINKING") && !raw.contains("<!--TOOL")) {
        return listOf(Segment.Text(raw.trim()))
    }

    val segs = mutableListOf<Segment>()
    var rest = raw

    // Phase 1: extract <!--TOOL ...--> ... <!--END_TOOL--> pairs
    val toolResults = mutableListOf<Pair<Int, Int>>()  // (start, end) in original text
    val toolMeta = mutableListOf<Pair<String, String>>()

    // We'll process sequentially instead
    while (rest.isNotEmpty()) {
        val ti = rest.indexOf("<!--THINKING")
        val te = rest.indexOf("<!--TOOL")
        val et = rest.indexOf("<!--END_TOOL-->")
        val eth = rest.indexOf("<!--END_THINKING-->")

        if (ti < 0 && te < 0 && et < 0 && eth < 0) {
            segs.add(Segment.Text(rest.trim()))
            break
        }

        // Pick the earliest meaningful marker
        val candidates = mutableListOf<Pair<Int, String>>()
        if (ti >= 0) candidates.add(ti to "THINKING")
        if (te >= 0) candidates.add(te to "TOOL")
        if (et >= 0) candidates.add(et to "END_TOOL")
        if (eth >= 0) candidates.add(eth to "END_THINKING")
        candidates.sortBy { it.first }

        val (pos, marker) = candidates.first()

        // Text before this marker
        if (pos > 0) {
            val before = rest.substring(0, pos).trim()
            if (before.isNotEmpty()) segs.add(Segment.Text(before))
        }

        when (marker) {
            "THINKING" -> {
                // Find <!--END_THINKING--> after this
                val endPos = rest.indexOf("<!--END_THINKING-->", pos)
                if (endPos >= 0) {
                    val content = rest.substring(pos + "<!--THINKING-->".length, endPos).trim()
                    if (content.isNotEmpty()) segs.add(Segment.Thinking(content, isComplete = true))
                    rest = rest.substring(endPos + "<!--END_THINKING-->".length)
                } else {
                    // Unclosed marker -> treat rest as thinking
                    val content = rest.substring(pos + "<!--THINKING-->".length).trim()
                    if (content.isNotEmpty()) segs.add(Segment.Thinking(content))
                    rest = ""
                }
            }
            "END_THINKING" -> {
                // Everything up to here is thinking (no <!--THINKING--> marker emitted due to streaming batches)
                val content = rest.substring(0, pos).trim()
                if (content.isNotEmpty()) segs.add(Segment.Thinking(content, isComplete = true))
                rest = rest.substring(pos + "<!--END_THINKING-->".length)
            }
            "TOOL" -> {
                val closeTag = "-->"
                val closeIdx = rest.indexOf(closeTag, pos + "<!--TOOL".length)
                if (closeIdx >= 0) {
                    val attrs = rest.substring(pos + "<!--TOOL".length, closeIdx).trim()
                    val name = extractAttr(attrs, "name")
                    val summary = extractAttr(attrs, "summary")
                    val endTool = rest.indexOf("<!--END_TOOL-->", closeIdx)
                    if (endTool >= 0) {
                        val content = rest.substring(closeIdx + closeTag.length, endTool).trim()
                        segs.add(Segment.ToolCall(name, summary, content))
                        rest = rest.substring(endTool + "<!--END_TOOL-->".length)
                    } else {
                        rest = rest.substring(closeIdx + closeTag.length)
                    }
                } else {
                    rest = rest.substring(pos + "<!--TOOL".length)
                }
            }
            "END_TOOL" -> {
                // Stray end marker without open -> skip
                rest = rest.substring(pos + "<!--END_TOOL-->".length)
            }
        }
    }

    return segs.filter { it !is Segment.Text || it.content.isNotBlank() }
}

private fun extractAttr(s: String, key: String): String {
    val r = Regex("""${key}\s*=\s*"([^"]*)"""")
    return r.find(s)?.groupValues?.getOrElse(1) { "" } ?: ""
}

// ── Top-level composable ──

@Composable
fun StructuredContent(content: String, references: List<MessageReference>, msgId: String) {
    val segments = remember(content) { parseSegments(content) }

    // If only one text segment, use MarkdownContent directly (no marker overhead)
    if (segments.size == 1 && segments[0] is Segment.Text) {
        MarkdownContent(content = (segments[0] as Segment.Text).content, references = references)
        return
    }

    for (seg in segments) {
        when (seg) {
            is Segment.Text -> MarkdownContent(content = seg.content, references = references)
            is Segment.Thinking -> ThinkingBlock(content = seg.content, isComplete = seg.isComplete)
            is Segment.ToolCall -> ToolCallBlock(name = seg.name, summary = seg.summary, content = seg.content)
        }
    }
}

// ── Thinking Block ──

@Composable
fun ThinkingBlock(content: String, isComplete: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableStateOf(0L) }
    val startEpochMs = remember { kotlin.js.Date.now().toLong() }

    LaunchedEffect(isComplete) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            elapsedSeconds = (kotlin.js.Date.now().toLong() - startEpochMs) / 1000
            if (isComplete) break
        }
    }

    // Show "Thinking for Xs" during streaming, "Thought for Xs" when done (claudian-style)
    val label = if (isComplete) "Thought for ${elapsedSeconds}s"
                else "Thinking... (${elapsedSeconds}s)"

    Div({
        style {
            margin(8.px, 0.px)
            property("border-left", "2px solid #E8E0D4")
            paddingLeft(16.px)
            marginLeft(7.px)
        }
    }) {
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
                if (!isComplete) { property("animation", "silk-pulse 1.5s ease-in-out") }
            }
            attr("tabindex", "0"); attr("role", "button")
            onClick { expanded = !expanded }
        }) {
            Span({ style { property("flex-shrink", "0"); fontSize(14.px) } }) { Text("\uD83D\uDCAD") }
            Span({ style { property("flex", "1") } }) { Text(label) }
            Span({ style { fontSize(10.px); color(Color("#B8A890")); property("transition", "transform 0.2s")
                if (expanded) { property("transform", "rotate(90deg)") }
            } }) { Text("\u25B6") }
        }

        // Auto-expand during streaming, collapsible when done
        if (expanded || !isComplete) {
            Div({
                style {
                    fontSize(13.px); property("line-height", "1.5")
                    color(Color("#8A7B6A")); padding(4.px, 0.px)
                }
            }) {
                if (content.isNotEmpty()) {
                    MarkdownContent(content = content, references = emptyList())
                }
            }
        }
    }
}
@Composable
fun ToolCallBlock(name: String, summary: String = "", content: String = "") {
    var expanded by remember { mutableStateOf(false) }

    val icon = when {
        name.contains("bash", true) || name.contains("command", true) -> "\uD83D\uDCBB"
        name.contains("read", true) -> "\uD83D\uDCD6"
        name.contains("write", true) || name.contains("edit", true) -> "\u270F\uFE0F"
        name.contains("grep", true) || name.contains("search", true) || name.contains("glob", true) -> "\uD83D\uDD0D"
        name.contains("web", true) || name.contains("fetch", true) -> "\uD83C\uDF10"
        name.contains("todo", true) -> "\u2705"
        name.contains("think", true) -> "\uD83D\uDCAD"
        name.contains("ask", true) -> "\u2753"
        name.contains("plan", true) -> "\uD83D\uDCCB"
        name.contains("apply", true) || name.contains("patch", true) -> "\uD83D\uDD27"
        else -> "\uD83D\uDD27"
    }

    Div({
        style {
            margin(6.px, 0.px)
            property("border-left", "2px solid #E8E0D4")
            paddingLeft(16.px)
            marginLeft(7.px)
        }
    }) {
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
            Span({ style { color(Color("#4A4038")); property("flex-shrink", "0") } }) { Text(name) }
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
            Span({
                style {
                    width(8.px)
                    height(8.px)
                    borderRadius(50.percent)
                    backgroundColor(Color("#7DAE6C"))
                    property("flex-shrink", "0")
                }
            }) {}
            Span({
                style {
                    fontSize(10.px)
                    color(Color("#B8A890"))
                    property("transition", "transform 0.2s")
                    if (expanded) { property("transform", "rotate(90deg)") }
                }
            }) { Text("\u25B6") }
        }

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
