package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewPointsTest {
    @Test fun bulletLinesBecomePointsWithoutLabels() {
        assertEquals(listOf("Possible local infection", "Osteomyelitis not excluded"),
            reviewPoints(listOf("- Possible local infection\n- Osteomyelitis not excluded")))
        assertEquals(listOf("Probe depth and measure", "Urgent review if fever"),
            reviewPoints(listOf("Check: - Probe depth and measure - Urgent review if fever")))
    }

    @Test fun anOlderEssayAnswerIsSplitIntoShortPoints() {
        assertEquals(listOf("Granulation with slough, consistent with a chronic leg wound", "Erythema alone does not confirm infection"),
            reviewPoints(listOf("For clinician review: Granulation with slough, consistent with a chronic leg wound. Erythema alone does not confirm infection.")))
    }

    @Test fun probeToBoneStaysOnePoint() {
        assertEquals(listOf("Check probe-to-bone"), reviewPoints(listOf("Check probe-to-bone")))
    }
}
