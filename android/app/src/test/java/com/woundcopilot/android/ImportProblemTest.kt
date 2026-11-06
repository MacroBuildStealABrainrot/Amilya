package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportProblemTest {
    @Test fun theRealReasonIsShown() {
        assertEquals("Choose a JPEG, PNG or WebP image. Other formats are not supported.",
            importProblem(IllegalStateException("Choose a JPEG, PNG or WebP image. Other formats are not supported.")))
    }

    @Test fun vagueInternalErrorsBecomeAHelpfulHint() {
        listOf(IllegalArgumentException("Failed requirement."), IllegalStateException("File unavailable"), java.io.IOException("EOF"))
            .forEach { assertTrue(importProblem(it).contains("open it in Gallery first")) }
    }
}
