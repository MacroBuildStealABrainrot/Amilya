package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 1.8.5-test16: Nurse / NP / Doctor mode and the office computer inbox. */
class ModesAndInboxTest {
    @Test fun nursesKeepNursingNoteAndPrescribersStartWithSoap() {
        assertEquals("nursing", ClinicianModes.defaultFormat("nurse"))
        assertEquals("soap", ClinicianModes.defaultFormat("np"))
        assertEquals("soap", ClinicianModes.defaultFormat("doctor"))
        assertEquals("nursing", ClinicianModes.defaultFormat(""))
    }

    @Test fun olderServersStillGetTheRightWritingStyleThroughProfession() {
        ClinicianModes.all.forEach { mode -> assertEquals(mode, ClinicianModes.fromProfession(ClinicianModes.profession(mode))) }
    }

    @Test fun typedProfessionIsReadLikeTheServerReadsIt() {
        assertEquals("nurse", ClinicianModes.fromProfession("Registered nurse"))
        assertEquals("np", ClinicianModes.fromProfession("nurse practitioner"))
        assertEquals("doctor", ClinicianModes.fromProfession(" physician "))
        assertEquals("", ClinicianModes.fromProfession("Family doctor"))
        assertFalse(ClinicianModes.isValid("Doctor"))
    }

    @Test fun notesWaitUntilLocalMidnightTonight() {
        val toronto = ZoneId.of("America/Toronto")
        assertEquals("2026-10-31T00:00:00-04:00", OfficeInbox.endOfToday(ZonedDateTime.of(2026, 10, 30, 15, 42, 0, 0, toronto)))
        // Just before midnight still means tonight, not tomorrow night.
        assertEquals("2026-10-31T00:00:00-04:00", OfficeInbox.endOfToday(ZonedDateTime.of(2026, 10, 30, 23, 59, 0, 0, toronto)))
        // The day the clocks change is still one local day.
        assertEquals("2026-11-02T00:00:00-05:00", OfficeInbox.endOfToday(ZonedDateTime.of(2026, 11, 1, 9, 0, 0, 0, toronto)))
    }

    @Test fun inboxTitleIsShortAndPlain() {
        val title = OfficeInbox.title("soap", ZonedDateTime.of(2026, 10, 30, 14, 5, 0, 0, ZoneId.of("America/Toronto")))
        assertTrue(title.startsWith("SOAP Note · 2:05"))
        assertTrue(title.length <= 80)
    }

    @Test fun onlyRealVisitIdsGoToTheInbox() {
        assertTrue(OfficeInbox.acceptsVisit("3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90"))
        assertFalse(OfficeInbox.acceptsVisit("local-3f2a9c1e"))
        assertFalse(OfficeInbox.acceptsVisit(""))
        assertFalse(OfficeInbox.acceptsVisit("x".repeat(37)))
        assertFalse(OfficeInbox.acceptsVisit("bad id"))
    }
}
