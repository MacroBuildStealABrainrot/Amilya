package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An AI report retry keeps the SAME request code, so a report the server already finished is never paid for twice. */
class ReportRetryCodeTest {
    @Test fun aLostOrUnclearReplyKeepsTheCode() {
        assertEquals(0, reportCodeAfterError(502, ambiguous = true, serverErrorsSoFar = 0))
        assertEquals(2, reportCodeAfterError(409, ambiguous = true, serverErrorsSoFar = 2))
    }
    @Test fun gatewayOrServerErrorsKeepTheCode() {
        for (status in listOf(429, 500, 502, 503, 504)) assertEquals("$status", 1, reportCodeAfterError(status, false, 0))
        assertEquals(3, reportCodeAfterError(502, false, 2))
    }
    @Test fun theCodeIsOnlyGivenUpAfterFourServerErrorsInARow() {
        var errors = 0
        repeat(REPORT_CODE_MAX_SERVER_ERRORS - 1) { errors = reportCodeAfterError(503, false, errors)!! }
        assertEquals(3, errors)
        assertNull(reportCodeAfterError(503, false, errors))
    }
    @Test fun aRequestRefusedBeforeAnyWorkDropsTheCode() {
        for (status in listOf(400, 402, 403, 404, 409, 413, 415, 422)) assertNull("$status", reportCodeAfterError(status, false, 0))
    }
}
