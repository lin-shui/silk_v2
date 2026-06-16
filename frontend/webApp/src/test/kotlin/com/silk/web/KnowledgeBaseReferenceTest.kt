package com.silk.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class KnowledgeBaseReferenceTest {
    @Test
    fun inlineParserSplitsPlainTextAndReferences() {
        val segments = parseKnowledgeBaseTextSegments(
            "先看 [[kb:entry_1|架构说明]]，再看 [[kb:entry_2]]。"
        )

        assertEquals(5, segments.size)
        assertEquals("先看 ", assertIs<KnowledgeBaseTextSegment.Plain>(segments[0]).text)
        assertEquals("entry_1", assertIs<KnowledgeBaseTextSegment.Reference>(segments[1]).entryId)
        assertEquals("架构说明", assertIs<KnowledgeBaseTextSegment.Reference>(segments[1]).label)
        assertEquals("entry_2", assertIs<KnowledgeBaseTextSegment.Reference>(segments[3]).label)
    }

    @Test
    fun deepLinkParserSupportsTopicAndEntryPath() {
        val link = parseKnowledgeBaseDeepLink("kb://topic_1/entry_9")

        assertNotNull(link)
        assertEquals("topic_1", link.topicId)
        assertEquals("entry_9", link.entryId)
    }

    @Test
    fun htmlRendererTurnsKnowledgeBaseMarkersIntoAnchors() {
        val html = renderKnowledgeBaseMarkersInHtml("<p>[[kb:entry_1|协议]]</p>")

        assertEquals(
            "<p><a href=\"#\" class=\"silk-kb-link\" data-kb-entry-id=\"entry_1\">[[协议]]</a></p>",
            html,
        )
    }
}
