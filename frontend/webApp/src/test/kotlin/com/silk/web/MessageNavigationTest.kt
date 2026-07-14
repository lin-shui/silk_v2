package com.silk.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageNavigationTest {
    @Test
    fun messageDomIdKeepsSourceMessageIdVisible() {
        assertEquals("silk-msg-msg-42", messageDomId("msg-42"))
    }

    @Test
    fun computeMessageNavigationScrollTopCentersTargetInsideContainer() {
        assertEquals(
            250.0,
            computeMessageNavigationScrollTop(
                messageOffsetTop = 400.0,
                messageHeight = 100.0,
                containerHeight = 400.0,
                scrollHeight = 1200.0,
            ),
        )
    }

    @Test
    fun computeMessageNavigationScrollTopClampsToScrollableRange() {
        assertEquals(
            0.0,
            computeMessageNavigationScrollTop(
                messageOffsetTop = 40.0,
                messageHeight = 80.0,
                containerHeight = 400.0,
                scrollHeight = 1200.0,
            ),
        )
        assertEquals(
            800.0,
            computeMessageNavigationScrollTop(
                messageOffsetTop = 1180.0,
                messageHeight = 80.0,
                containerHeight = 400.0,
                scrollHeight = 1200.0,
            ),
        )
    }

    @Test
    fun messageSelectorTargetsContainerScopedDataAttribute() {
        assertTrue("[data-message-id=\"msg-42\"]".contains("msg-42"))
    }
}
