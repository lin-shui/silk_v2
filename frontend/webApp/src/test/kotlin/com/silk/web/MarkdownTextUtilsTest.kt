package com.silk.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownTextUtilsTest {
    @Test
    fun normalizeMathBlocksOnlyEscapesInsideMathDelimiters() {
        val markdown = "outside \\\\ " + "$$" + "a \\\\ b" + "$$" + " after \\\\"

        val normalized = normalizeMathMarkdownBlocks(markdown)

        assertEquals("outside \\\\ " + "$$" + "a \\\\\\\\ b" + "$$" + " after \\\\", normalized)
    }

    @Test
    fun normalizeMathBlocksSupportsBracketMathSyntax() {
        val markdown = """\[\begin{matrix}x \\ y\end{matrix}\]"""

        val normalized = normalizeMathMarkdownBlocks(markdown)

        assertEquals("""\[\begin{matrix}x \\\\ y\end{matrix}\]""", normalized)
    }

    @Test
    fun escapeMarkdownHtmlEscapesHtmlSensitiveCharacters() {
        val escaped = escapeMarkdownHtml("""<tag attr="1">&'""")

        assertEquals("&lt;tag attr=&quot;1&quot;&gt;&amp;&#39;", escaped)
    }

    @Test
    fun detectAgentStatusMessagesFromCommonHints() {
        assertTrue(isLikelyAgentStatusMessage("🤔 正在处理中，请稍候"))
        assertTrue(isLikelyAgentStatusMessage("执行: search_web"))
        assertFalse(isLikelyAgentStatusMessage("这是普通聊天消息"))
        assertFalse(isLikelyAgentStatusMessage("   "))
    }
}
