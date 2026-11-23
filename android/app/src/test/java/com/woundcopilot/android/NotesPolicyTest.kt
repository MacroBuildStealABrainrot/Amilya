package com.woundcopilot.android

import org.junit.Assert.*
import org.junit.Test

class NotesPolicyTest {
    @Test fun reportingAndRecoveryRemainReachable() {
        listOf("home", "saved", "record", "evidence:typed", "editor", "sources", "history", "voice_correction", "shift_summary").forEach {
            assertTrue(it, notesRouteAllowed(it))
        }
    }
    @Test fun legacyAndUnexpectedRoutesCannotExposeOtherWorkflows() {
        listOf("consultant", "photo", "photo_assessment", "upload", "findings", "review", "encounter", "evidence:manual", "evidence:result", "new", "unknown").forEach {
            assertFalse(it, notesRouteAllowed(it))
        }
    }
    @Test fun olderSpecialistReportsCannotBeRegeneratedAsNotes() {
        assertTrue(notesCanEditTemplate("nursing"))
        listOf("diagnostics", "medical_order", "soap", "physician_update", "custom", "").forEach {
            assertFalse(it, notesCanEditTemplate(it))
        }
    }
    @Test fun savedListOnlyIncludesDocumentationVisits() {
        listOf("document", "report", "nursing", "DOCUMENT").forEach { assertTrue(notesVisitAllowed(it)) }
        listOf("photo", "consultant", "", "unknown").forEach { assertFalse(notesVisitAllowed(it)) }
    }
}
