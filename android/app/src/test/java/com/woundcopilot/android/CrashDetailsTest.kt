package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashDetailsTest {
    private val frame = Regex("^[A-Za-z0-9_.$<>]{1,190}:[0-9]{1,6}$")

    @Test fun crashReportKeepsClassAndCodeLocationsButNeverTheMessage() {
        val error = RuntimeException("wrapper", IllegalStateException("Jane Doe, cellulitis, password hunter2"))
        val details = DeveloperDiagnostics.crashDetails(error)
        assertEquals("java.lang.IllegalStateException", details.type)
        assertTrue(details.frames.isNotEmpty())
        assertTrue(details.frames.all { it.matches(frame) })
        val everything = details.toString()
        listOf("Jane", "cellulitis", "password", "hunter2").forEach { assertFalse(everything.contains(it)) }
    }

    @Test fun framesWithUnsafeCharactersAreDroppedNotSent() {
        val error = IllegalArgumentException()
        error.stackTrace = arrayOf(StackTraceElement("com.x.Bad Class", "run", "F.kt", 3),
            StackTraceElement("com.woundcopilot.android.AppState", "quickReport", "AppState.kt", -2))
        val details = DeveloperDiagnostics.crashDetails(error)
        assertEquals(listOf("com.woundcopilot.android.AppState.quickReport:0"), details.frames)
    }
}
