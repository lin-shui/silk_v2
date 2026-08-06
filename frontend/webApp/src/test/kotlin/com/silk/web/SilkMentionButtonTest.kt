package com.silk.web

import kotlin.test.Test
import kotlin.test.assertEquals

class SilkMentionButtonTest {
    @Test
    fun insertsMentionAtEndWithLeadingSeparator() {
        assertEquals(
            "问题 @Silk " to 9,
            insertSilkMention("问题", 2),
        )
    }

    @Test
    fun insertsMentionAtCursorAndKeepsFollowingText() {
        assertEquals(
            "请 @Silk 继续说明" to 8,
            insertSilkMention("请继续说明", 1),
        )
    }

    @Test
    fun clampsCursorOutsideTextBounds() {
        assertEquals(
            "@Silk 原问题" to 6,
            insertSilkMention("原问题", -1),
        )
    }
}
