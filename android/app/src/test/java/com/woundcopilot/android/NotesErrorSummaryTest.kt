package com.woundcopilot.android

import java.net.UnknownHostException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesErrorSummaryTest {
    private val time = Instant.parse("2026-11-01T14:00:00Z")

    @Test fun onlyKnownTechnicalDetailsAreCopiedForServerErrors() {
        val requestId = "12345678-1234-4234-8123-0123456789ab"
        val error = ApiException(502, "private fake note", requestId = requestId, category = "PROVIDER_UNAVAILABLE")
        assertEquals(
            "Amilya Notes 1.9.3-notes (build 85)\n$time\nWriting report\nPROVIDER_UNAVAILABLE\nHTTP 502\nRequest $requestId",
            notesErrorSummary(error, "report", "unused", "unused", "1.9.3-notes", 85, time),
        )
    }

    @Test fun untrustedFieldsCannotEnterTheClipboard() {
        val untrusted = "private fake note\nhttps://example.invalid/file"
        val error = ApiException(700, untrusted, requestId = "12345678-1234-4234-8123-0123456789ab\n$untrusted", category = untrusted)
        assertEquals(
            "Amilya Notes (build 85)\n$time\nApp request\nSERVER_RESPONSE",
            notesErrorSummary(error, untrusted, untrusted, untrusted, untrusted, 85, time),
        )
    }

    @Test fun offlineErrorCopiesTheStepAndCauseWithoutTheHost() {
        assertEquals(
            "Amilya Notes 1.9.3-notes (build 85)\n$time\nSaving report\nNETWORK_UNAVAILABLE",
            notesErrorSummary(UnknownHostException("private-host.invalid"), null, "Saving report…", "editor", "1.9.3-notes", 85, time),
        )
    }
}
