package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDetailsTest {
    @Test fun nothingSelectedByDefaultAndNothingSent() {
        val empty = PhotoDetails()
        assertTrue(empty.isEmpty())
        assertEquals(0, empty.toJson().length())
        assertEquals(0, empty.selectedCount())
    }

    @Test fun blankMeasurementsStayUnknownNeverZero() {
        val details = PhotoDetails(lengthCm = "3.2", widthCm = "", depthCm = " ")
        val json = details.toJson()
        assertEquals(3.2, json.getDouble("length_cm"), 0.0)
        assertFalse(json.has("width_cm")); assertFalse(json.has("depth_cm"))
        assertFalse(details.hasInvalidMeasurement())
    }

    @Test fun zeroOrNonsenseMeasurementIsFlaggedAndNotSent() {
        listOf("0", "abc", "150", "-1").forEach {
            val details = PhotoDetails(depthCm = it)
            assertTrue(it, details.hasInvalidMeasurement())
            assertFalse(it, details.toJson().has("depth_cm"))
        }
        assertEquals(0.4, PhotoDetails.centimetres("0,4")!!, 0.0)
    }

    @Test fun multipleChoicesAndSingleDrainageAreKeptExactly() {
        val details = PhotoDetails(woundBed = setOf("slough", "granulation"), surroundingSkin = setOf("erythema"),
            drainageType = "serous", drainageAmount = "small")
        val json = details.toJson()
        assertEquals("granulation", json.getJSONArray("wound_bed").getString(0))
        assertEquals("slough", json.getJSONArray("wound_bed").getString(1))
        assertEquals("serous", json.getString("drainage_type")); assertEquals("small", json.getString("drainage_amount"))
        assertEquals(5, details.selectedCount())
    }

    @Test fun savedEntriesSurviveAFailedRequestUnchanged() {
        val details = PhotoDetails(woundBed = setOf("eschar"), drainageAmount = "none", lengthCm = "2", widthCm = "1.5", depthCm = "")
        assertEquals(details, PhotoDetails.fromStored(details.toStored()))
        assertEquals(PhotoDetails(), PhotoDetails.fromStored("not json"))
        assertEquals(PhotoDetails(), PhotoDetails.fromStored("""{"wound_bed":["osteomyelitis"]}"""))
    }

    @Test fun edgesInfectionSignsAndDeeperStructuresAreSentAndSaved() {
        val details = PhotoDetails(woundBed = setOf("bone", "tendon"), woundEdges = setOf("unattached", "undermining"), infectionSigns = setOf("warmth", "odour"))
        val json = details.toJson()
        assertEquals("unattached", json.getJSONArray("wound_edges").getString(0))
        assertEquals("warmth", json.getJSONArray("infection_signs").getString(0))
        assertEquals("bone", json.getJSONArray("wound_bed").getString(0))
        assertEquals(6, details.selectedCount())
        assertEquals(details, PhotoDetails.fromStored(details.toStored()))
    }

    @Test fun attachedAndUnattachedExcludeEachOther() {
        assertEquals(setOf("rolled", "unattached"), PhotoDetails.toggleEdge(setOf("rolled", "attached"), "unattached"))
        assertEquals(setOf("attached", "tunnelling"), PhotoDetails.toggleEdge(setOf("attached"), "tunnelling"))
    }

    @Test fun noInfectionSignsStandsAlone() {
        assertEquals(setOf("none"), PhotoDetails.toggleInfection(setOf("warmth", "odour"), "none"))
        assertEquals(setOf("warmth"), PhotoDetails.toggleInfection(setOf("none"), "warmth"))
        assertEquals(emptySet<String>(), PhotoDetails.toggleInfection(setOf("none"), "none"))
    }

    @Test fun healingPercentageIsOptionalAndCalculated() {
        val d = PhotoDetails(lengthCm = "0.5", widthCm = "0.5", previousLengthCm = "1", previousWidthCm = "1", previousWeeksAgo = "4")
        assertEquals(75, d.areaChangePercent())
        val json = d.toJson()
        assertEquals(1.0, json.getDouble("previous_length_cm"), 0.0); assertEquals(4, json.getInt("previous_weeks_ago"))
        assertEquals(d, PhotoDetails.fromStored(d.toStored()))
        assertEquals(-20, PhotoDetails(lengthCm = "3", widthCm = "2", previousLengthCm = "2", previousWidthCm = "2.5").areaChangePercent())
        val half = PhotoDetails(previousLengthCm = "2", previousWidthCm = "1")
        assertTrue(half.progressIncomplete()); assertTrue(half.hasInvalidMeasurement()); assertFalse(half.toJson().has("previous_length_cm"))
        assertFalse(PhotoDetails(lengthCm = "2", widthCm = "1").hasInvalidMeasurement())
    }

    @Test fun painZeroIsSentAndBlankIsNot() {
        assertEquals(0, PhotoDetails(painScore = 0).toJson().getInt("pain_score"))
        assertFalse(PhotoDetails(painScore = 0).isEmpty())
        assertFalse(PhotoDetails().toJson().has("pain_score"))
        val d = PhotoDetails(painScore = 6)
        assertEquals(d, PhotoDetails.fromStored(d.toStored()))
        assertEquals(PhotoDetails(), PhotoDetails.fromStored(PhotoDetails().toStored()))
    }
}
