package com.silk.web

internal data class MathDelimiter(
    val open: String,
    val close: String
)

private val markdownMathDelimiters = listOf(
    MathDelimiter("$$", "$$"),
    MathDelimiter("\\[", "\\]"),
    MathDelimiter("\\(", "\\)")
)

fun escapeMarkdownHtml(raw: String): String {
    return raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

fun normalizeMathMarkdownBlocks(markdown: String): String {
    val output = StringBuilder()
    var cursor = 0

    while (cursor < markdown.length) {
        var matched = false

        for (delimiter in markdownMathDelimiters) {
            if (!markdown.startsWith(delimiter.open, cursor)) continue

            val contentStart = cursor + delimiter.open.length
            val closingIndex = markdown.indexOf(delimiter.close, contentStart)
            if (closingIndex == -1) continue

            val innerContent = markdown.substring(contentStart, closingIndex)
                // markdown-it 会把数学环境中的 `\\` 吃成 `\`，这里先补一层转义。
                .replace("\\\\", "\\\\\\\\")
            output.append(delimiter.open)
            output.append(innerContent)
            output.append(delimiter.close)
            cursor = closingIndex + delimiter.close.length
            matched = true
            break
        }

        if (!matched) {
            output.append(markdown[cursor])
            cursor += 1
        }
    }

    return output.toString()
}

fun isLikelyAgentStatusMessage(content: String): Boolean {
    val text = content.trim()
    if (text.isBlank()) return false

    val statusHints = listOf(
        "正在处理",
        "思考中",
        "使用工具",
        "执行:",
        "处理中",
        "检索",
        "搜索",
        "🤔",
        "🔧",
        "⏳"
    )
    return statusHints.any { hint -> text.contains(hint) }
}
