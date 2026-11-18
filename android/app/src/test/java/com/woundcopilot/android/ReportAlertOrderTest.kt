package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every report warning is shown; medication, dose, side and measurement warnings come first and are never hidden. */
class ReportAlertOrderTest {
    @Test fun aDoseWarningIsNotHiddenBehindAnotherWarning() {
        val (all, safety) = orderedReportAlerts(listOf(
            "review" to "Speaker attribution for one sentence is unclear.",
            "wound" to "Wound side is unclear: the recording says both left and right heel.",
            "medication" to "Metformin dose unclear: 500 or 850 mg."))
        assertEquals(3, all.size)
        assertEquals(2, safety)
        assertEquals("Wound side is unclear: the recording says both left and right heel.", all[0])
        assertEquals("Metformin dose unclear: 500 or 850 mg.", all[1])
        assertEquals("Speaker attribution for one sentence is unclear.", all[2])
    }
    @Test fun allFourSafetyWarningsAndTheGeneralNotesAreAllShownSafetyFirst() {
        val input = listOf(
            "review" to "Speaker attribution for one sentence is unclear.",
            "medication" to "Medication name may be misheard: Keflex or Cipro.",
            "review" to "Caregiver remark could belong to the client.",
            "dose" to "Dose unclear: 500 mg or 50 mg.",
            "wound" to "Side unclear: left or right heel.",
            "measurement" to "Length heard as 3.5 cm; could be 5.3 cm.")
        val (all, safety) = orderedReportAlerts(input)
        assertEquals("every warning is kept", 6, all.size)
        assertEquals("four safety warnings", 4, safety)
        assertEquals(listOf("Medication name may be misheard: Keflex or Cipro.", "Dose unclear: 500 mg or 50 mg.",
            "Side unclear: left or right heel.", "Length heard as 3.5 cm; could be 5.3 cm."), all.take(4))
        assertEquals(listOf("Speaker attribution for one sentence is unclear.", "Caregiver remark could belong to the client."), all.drop(4))
        assertTrue(all.containsAll(input.map { it.second }))
    }
    @Test fun eachSafetyKindIsRecognised() {
        assertTrue(SafetyAlert.MEDICATION in safetyKinds("", "Medication name may be misheard."))
        assertTrue(SafetyAlert.DOSE in safetyKinds("", "Insulin 10 units or 15 units?"))
        assertTrue(SafetyAlert.SIDE in safetyKinds("", "Left or right foot was not clear."))
        assertTrue(SafetyAlert.MEASUREMENT in safetyKinds("", "Wound length 3.5 cm may be 5.3 cm."))
        assertTrue(SafetyAlert.MEASUREMENT in safetyKinds("", "Blood pressure reading unclear."))
        assertTrue(safetyKinds("laterality", "Please confirm.").isNotEmpty())
        assertTrue(safetyKinds("review", "Speaker attribution is unclear.").isEmpty())
    }
    @Test fun nothingIsDroppedExceptBlanksAndExactRepeats() {
        // Any number counts as a possible measurement on purpose (a misheard number is a safety issue), so plain notes use letters.
        val input = ("ABCDEFGHIJKL").map { "general" to "General note $it" } + listOf("x" to "  ", "dose" to "Dose unclear.", "dose" to "Dose unclear.")
        val (all, safety) = orderedReportAlerts(input)
        assertEquals(1, safety)
        assertEquals("Dose unclear.", all[0])
        assertEquals(13, all.size)
    }
    @Test fun aWarningWithAnyNumberIsTreatedAsAPossibleMeasurement() {
        assertTrue(safetyKinds("general", "Heard 15 or 50.").isNotEmpty())
    }
    @Test fun noWarningsMeansNothingShown() {
        assertEquals(emptyList<String>() to 0, orderedReportAlerts(emptyList()))
    }
}
