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
}
