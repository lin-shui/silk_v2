package com.silk.shared.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DateTimeUtilsDesktopTest {
    @Test
    fun formattersUseShanghaiTimezone() {
        val timestamp = 0L

        assertEquals("08:00", formatTimeHM(timestamp))
        assertEquals("08:00:00", formatTimeHMS(timestamp))
        assertEquals("1970-01-01 08:00", formatDateTime(timestamp))
        assertEquals("01-01 08:00", formatDateShortTime(timestamp))
    }

    @Test
    fun currentTimeMillisReturnsNonNegativeValue() {
        assertTrue(currentTimeMillis() >= 0L)
    }
}
