package com.woundcopilot.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RedTeamTest {
    @Test fun replayCannotDowngradeReviewedEvidenceOrCaseRevision(){
        val current=obj("encounter" to obj("revision" to 9),"facts" to org.json.JSONArray().put(obj("id" to "f","revision" to 3,"confirmation_state" to "confirmed")),"evidence" to org.json.JSONArray())
        val replay=obj("case_revision" to 2,"facts" to org.json.JSONArray().put(obj("id" to "f","revision" to 1,"confirmation_state" to "unconfirmed")))
        val merged=mergeEvidenceResponse(current,replay)
        assertEquals(9,merged.getJSONObject("encounter").getInt("revision"))
        assertEquals("confirmed",merged.items("facts").single().str("confirmation_state"))
        assertEquals(3,merged.items("facts").single().getInt("revision"))
    }
    @Test fun acceptedEvidenceInvalidatesReviewBeforeRefresh(){
        val current=obj("encounter" to obj("revision" to 1),"facts" to org.json.JSONArray(),"evidence" to org.json.JSONArray(),"review" to obj("case_revision" to 1,"stale" to false))
        val merged=mergeEvidenceResponse(current,obj("case_revision" to 2))
        assertTrue(merged.getJSONObject("review").getBoolean("stale"))
        assertFalse(current.getJSONObject("review").getBoolean("stale"))
    }
    @Test fun staleOrUnversionedReviewsCannotLookCurrent(){
        assertTrue(reviewIsStale(obj("case_revision" to 2,"stale" to false),3))
        assertTrue(reviewIsStale(obj(),3))
        assertTrue(reviewIsStale(obj("case_revision" to 3,"stale" to true),3))
        assertFalse(reviewIsStale(obj("case_revision" to 3),3))
    }
    @Test fun absentFindingWithNullTextRemainsVisible(){
        assertEquals("Absent",prettyValue(obj("text" to null,"present" to false)))
    }
    @Test fun zeroPainAndAdditionalScoreRemainVisible(){
        assertTrue(prettyValue(obj("text" to null,"score_0_10" to 0)).contains("0/10"))
        val value=prettyValue(obj("text" to "During dressing change","score_0_10" to 4))
        assertTrue(value.contains("During dressing change"));assertTrue(value.contains("4/10"))
    }
    @Test fun nullObservationIsNotBlank(){assertEquals("Not recorded",prettyValue(obj("text" to null)))}
    @Test fun noDepthIsInvented(){assertEquals("2 × 1 cm",prettyValue(obj("length_cm" to 2,"width_cm" to 1,"depth_cm" to null)))}
    @Test fun apiRejectsTrailingJunk(){
        for(text in listOf("{}garbage","{}{}","[]false")){
            try{parseApiJson(text);fail("Accepted trailing content")}catch(_:IllegalArgumentException){}
        }
        assertTrue(parseApiJson(" {} \n") is JSONObject)
    }
}
