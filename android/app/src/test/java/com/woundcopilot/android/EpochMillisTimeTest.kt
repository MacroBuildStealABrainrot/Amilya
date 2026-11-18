package com.woundcopilot.android

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpochMillisTimeTest {
    @Test fun millisecondsBecomeReadableLocalTime() {
        // The value that showed up as a raw number on the Samsung: 2026-11-02 16:53:02 UTC = 11:53 a.m. in Toronto.
        val stored = epochMillisAsStoredTime(1793638382146L)!!
        assertEquals("Nov 2, 2026 · 11:53 AM", formatLocalTime(stored, ZoneId.of("America/Toronto"), false, java.util.Locale.US))
    }

    @Test fun missingTimeShowsNothingInsteadOfANumber() {
        assertNull(epochMillisAsStoredTime(0L))
        assertNull(epochMillisAsStoredTime(-5L))
    }
}
