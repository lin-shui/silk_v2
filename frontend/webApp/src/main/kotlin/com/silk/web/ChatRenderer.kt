package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.silk.shared.models.MessageReference
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.fontFamily
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.margin
import org.jetbrains.compose.web.css.marginLeft
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.paddingLeft
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.width
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

@Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
private fun parseSegments(raw: String): List<Segment> {
    if (!raw.contains("<!--THINKING") && !raw.contains("<!--TOOL")) {
        return listOf(Segment.Text(raw.trim()))
    }

    val segs = mutableListOf<Segment>()
    var rest = raw

    // We'll process sequentially
    while (rest.isNotEmpty()) {
        val ti = rest.indexOf("<!--THINKING")
        val te = rest.indexOf("<!--TOOL")
        val et = rest.indexOf("<!--END_TOOL-->")
        val eth = rest.indexOf("<!--END_THINKING-->")

        val noMarkersRemain = ti < 0 && te < 0 && et < 0 && eth < 0
        if (noMarkersRemain) {
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
                    var summary = extractAttr(attrs, "summary")
                    val endTool = rest.indexOf("<!--END_TOOL-->", closeIdx)
                    if (endTool >= 0) {
                        var content = rest.substring(closeIdx + closeTag.length, endTool).trim()
                        // Check for TOOL_SUMMARY marker in content (from pty_chat.py)
                        if (summary.isEmpty()) {
                            val tsRegex = Regex("""<!--TOOL_SUMMARY\s*:\s*([^>]+)-->""")
                            val tsMatch = tsRegex.find(content)
                            if (tsMatch != null) {
                                summary = tsMatch.groupValues[1].trim()
                                content = content.replace(tsRegex, "").trim()
                            }
                        }
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
@Suppress("UnusedParameter")
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

/**
 * @param elapsedMs 后端记录的真实思考耗时（毫秒）。>0 时优先使用，避免组件重新挂载丢失计时。
 *                  0 表示后端未提供（旧消息兼容），回退到组件挂载时起的本地计时。
 */
@Composable
fun ThinkingBlock(content: String, isComplete: Boolean = false, elapsedMs: Long = 0L) {
    var expanded by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableStateOf(0L) }
    val startEpochMs = remember { kotlin.js.Date.now().toLong() }
    // 后端提供了真实耗时则直接用，避免本地 ticker 重新挂载丢失计时
    val serverSeconds = elapsedMs / 1000

    // 仅当后端未提供 elapsedMs 时，才启动本地 ticker（兼容旧消息或异常情况）
    LaunchedEffect(isComplete, elapsedMs) {
        if (elapsedMs > 0) {
            elapsedSeconds = serverSeconds
        } else if (isComplete) {
            elapsedSeconds = (kotlin.js.Date.now().toLong() - startEpochMs) / 1000
        } else {
            elapsedSeconds = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                elapsedSeconds = (kotlin.js.Date.now().toLong() - startEpochMs) / 1000
            }
        }
    }

    val label = if (isComplete) {
        val secs = elapsedSeconds
        if (secs < 1) "Thought for <1s" else "Thought for ${secs}s"
    } else {
        "Thinking ${elapsedSeconds}s..."
    }

    Div({
        style {
            margin(10.px, 0.px)
            property("border-left", "2px solid #E8E0D4")
            paddingLeft(14.px)
            marginLeft(7.px)
        }
    }) {
        // Clickable header
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "8px")
                padding(3.px, 0.px)
                property("cursor", "pointer")
                property("user-select", "none")
                fontSize(13.px)
                color(Color("#8A7B6A"))
            }
            attr("tabindex", "0"); attr("role", "button")
            onClick { expanded = !expanded }
        }) {
            Span({ style { property("flex-shrink", "0"); fontSize(14.px) } }) {
                Text("\uD83D\uDCAD")
            }
            Span({
                style {
                    property("flex", "1")
                    property("font-weight", if (!isComplete) "500" else "400")
                    color(Color(if (!isComplete) "#C9A86C" else "#8A7B6A"))
                }
            }) {
                Text(label)
            }
            // Collapse/expand indicator
            Span({ style {
                fontSize(10.px)
                color(Color("#C0B0A0"))
                property("transition", "transform 0.2s")
                if (expanded) { property("transform", "rotate(90deg)") }
            }}) { Text("\u25B6") }
        }

        // Thinking content (auto-expanded during streaming, collapsible when done)
        if (expanded || !isComplete) {
            Div({
                style {
                    fontSize(13.px)
                    property("line-height", "1.6")
                    color(Color("#7A6B5A"))
                    padding(6.px, 0.px, 2.px, 0.px)
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
    // In the marker-based approach, we can't distinguish running vs completed
    // tool calls because both arrive in the same format. For streaming, the
    // marker arrives when the tool is complete. We assume it's completed.
    val isRunning = false

    val displayLabel = buildLabel(name, summary.ifEmpty { content })

    val icon = getToolIcon(name)

    Div({
        style {
            margin(8.px, 0.px)
            property("border-left", "2px solid #E8E0D4")
            paddingLeft(14.px)
            marginLeft(7.px)
        }
    }) {
        // Clickable header row
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "8px")
                padding(3.px, 0.px)
                property("cursor", "pointer")
                property("user-select", "none")
                fontSize(13.px)
                property("line-height", "1.4")
            }
            attr("tabindex", "0"); attr("role", "button")
            onClick { expanded = !expanded }
        }) {
            // Icon
            Span({ style { property("flex-shrink", "0"); fontSize(14.px); width(18.px); property("text-align", "center") } }) {
                Text(icon)
            }
            // Tool label: "Read: src/index.ts"
            Span({
                style {
                    color(Color("#5A5048"))
                    property("font-weight", "500")
                    property("white-space", "nowrap")
                    property("overflow", "hidden")
                    property("text-overflow", "ellipsis")
                    property("flex", "1")
                    property("min-width", "0")
                    fontFamily("SF Mono, SFMono-Regular, Menlo, Consolas, monospace")
                    fontSize(12.5.px)
                }
            }) {
                Text(displayLabel)
            }
            // Status indicator: green dot (running) or check (done)
            Span({ style {
                width(7.px); height(7.px)
                if (isRunning) {
                    borderRadius(50.percent)
                    backgroundColor(Color("#7DAE6C"))
                    property("animation", "silk-pulse 1.5s ease-in-out infinite")
                } else {
                    fontSize(12.px)
                    color(Color("#7DAE6C"))
                }
                property("flex-shrink", "0")
            }}) {
                if (!isRunning) Text("\u2713")
            }
            // Expand arrow
            Span({ style {
                fontSize(10.px)
                color(Color("#C0B0A0"))
                property("transition", "transform 0.2s")
                if (expanded) { property("transform", "rotate(90deg)") }
                property("flex-shrink", "0")
            }}) { Text("\u25B6") }
        }

        // Expanded content
        if (expanded && content.isNotEmpty()) {
            Div({
                style {
                    padding(8.px, 0.px, 2.px, 0.px)
                    fontSize(12.5.px)
                    property("line-height", "1.5")
                    color(Color("#7A6B5A"))
                    property("word-break", "break-word")
                }
            }) {
                MarkdownContent(content = content, references = emptyList())
            }
        }
    }
}

private fun buildLabel(name: String, content: String): String {
    val shortName = when {
        name.equals("Read", true) || name.equals("Write", true) || name.equals("Edit", true) -> name
        name.equals("Bash", true) || name.equals("ExecuteCommand", true) -> name
        else -> name
    }
    if (content.isEmpty()) return name
    // Extract a concise summary from full tool content
    val summary = extractToolSummary(name, content)
    return if (summary.isNotEmpty()) "$shortName: $summary" else shortName
}

@Suppress("CyclomaticComplexMethod")
private fun extractToolSummary(name: String, content: String): String {
    // Normalize: collapse newlines/spaces in JSON for single-line regex matching
    val flat = content.replace("\n", " ").replace("\r", " ").replace(Regex("\\s+"), " ")

    // Try JSON field extraction (works even with multiline JSON)
    val trimmed = content.trimStart()
    // Try to extract file_path value
    val fileRe = Regex(""""file_path"\s*:\s*"([^"]+)"""")
    val fileMatch = fileRe.find(flat)
    if (fileMatch != null) {
        val path = fileMatch.groupValues[1]
        val idx = path.lastIndexOf("/")
        return if (idx >= 0) path.substring(idx + 1) else path
    }
    // Try to extract command value
    val cmdRe = Regex(""""command"\s*:\s*"([^"]+)"""")
    val cmdMatch = cmdRe.find(flat)
    if (cmdMatch != null) {
        val cmd = cmdMatch.groupValues[1]
        return if (cmd.length > 60) cmd.substring(0, 60) + "..." else cmd
    }
    // Try to extract pattern (for grep/glob)
    val patRe = Regex(""""pattern"\s*:\s*"([^"]+)"""")
    val patMatch = patRe.find(flat)
    if (patMatch != null) return patMatch.groupValues[1]

    // Helper: find first meaningful line, skipping markdown code block markers
    fun firstMeaningfulLine(text: String): String {
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("```")) return trimmed
        }
        return ""
    }

    // Fallback: if content looks like a direct shell command (Bash), show first line
    if (name.equals("Bash", true) || name.equals("ExecuteCommand", true)) {
        val cmd = firstMeaningfulLine(trimmed).take(60)
        if (cmd.isNotEmpty()) return cmd + if (cmd.length >= 60) "..." else ""
    }

    // Fallback: for Read/Write/Edit, extract last path component from any path-like string
    if (name.equals("Read", true) || name.equals("Write", true) || name.equals("Edit", true)) {
        val pathRe = Regex("""[\w./\\-]+\.\w{1,4}""")
        val pathMatch = pathRe.find(trimmed)
        if (pathMatch != null) {
            val path = pathMatch.value
            val idx = path.lastIndexOf("/")
            return if (idx >= 0) path.substring(idx + 1) else path
        }
    }

    // Generic fallback: first meaningful (non-codeblock) line, truncated
    val firstLine = firstMeaningfulLine(trimmed).take(70)
    if (firstLine.isNotEmpty()) return firstLine + if (firstLine.length >= 70) "..." else ""
    return ""
}

@Suppress("CyclomaticComplexMethod")
private fun getToolIcon(name: String): String {
    val n = name.lowercase()
    return when {
        n.contains("bash") || n.contains("command") || n.contains("shell") -> "\uD83D\uDCBB"
        n.contains("read") || n.contains("ls") || n.contains("glob") -> "\uD83D\uDCD6"
        n.contains("write") || n.contains("edit") || n.contains("apply") || n.contains("patch") -> "\u270F\uFE0F"
        n.contains("grep") || n.contains("search") || n.contains("find") -> "\uD83D\uDD0D"
        n.contains("web") || n.contains("fetch") || n.contains("url") -> "\uD83C\uDF10"
        n.contains("todo") || n.contains("task") -> "\u2705"
        n.contains("think") || n.contains("reason") -> "\uD83D\uDCAD"
        n.contains("ask") || n.contains("question") -> "\u2753"
        n.contains("plan") -> "\uD83D\uDCCB"
        n.contains("diff") -> "\uD83D\uDD27"
        n.contains("notebook") || n.contains("jupyter") -> "\uD83D\uDCD8"
        else -> "\uD83D\uDD27"
    }
}
