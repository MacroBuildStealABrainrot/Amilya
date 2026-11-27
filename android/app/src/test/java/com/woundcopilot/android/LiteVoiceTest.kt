package com.woundcopilot.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LiteVoiceTest {
    private fun piece(text: String, speaker: Int?) = JSONObject().put("text", text).also {
        if (speaker != null) it.put("speaker", speaker)
    }

    @Test fun groupsCompleteConsecutiveWordsBySpeaker() {
        val segments = JSONArray(listOf(piece("Hello.", 0), piece("Good morning.", 1), piece("How are you?", 1)))
        val plain = "Hello.\nGood morning. How are you?"
        assertEquals("Speaker 1: Hello.\n\nSpeaker 2: Good morning. How are you?", liteSpeakerText(segments, plain))
    }

    @Test fun preservesAllWordsEvenWhenTheReviewTextOrSpeakerSegmentsOmitSpeech() {
        val raw = "Hello. صباح الخير. Hello again."
        val evidence = obj("text" to "Hello.", "warnings" to JSONArray(listOf("Clinical review")),
            "metadata" to obj("original_transcript" to raw, "segments" to JSONArray(listOf(piece("Hello.", 0)))))
        assertEquals(raw, liteTranscript(evidence, labels = true))
        assertEquals(raw, liteTranscript(evidence, labels = false))
        assertEquals("صباح الخير.", liteTranscript(obj("text" to "", "metadata" to obj("original_transcript" to "صباح الخير.")), false))
    }

    @Test fun fallsBackToTheSpeechTextWithoutEditorialFields() {
        val evidence = obj("text" to "One. Two.", "metadata" to obj("segments" to JSONArray(listOf(piece("One.", null), piece("Two.", null)))))
        assertEquals("One. Two.", liteTranscript(evidence, true))
        assertEquals("", liteTranscript(obj("text" to "", "warnings" to JSONArray(listOf("Review this."))), true))
    }

    @Test fun unlabelledWordsStaySeparateFromAttributedSpeech() {
        val segments = JSONArray(listOf(piece("", 0), piece("One.", 0), piece("Two.", null), piece("Three.", 0)))
        assertEquals("Speaker 1: One.\n\nTwo.\n\nSpeaker 1: Three.", liteSpeakerText(segments, "One. Two. Three."))
    }

    @Test fun retriedRecordingKeepsEditedTextAndOtherVoicesOnce() {
        val legacy = LiteVoiceDraft.restore(null, "Old transcript.")
        val first = legacy.append("first.m4a", "Hello.").copy(text = "Edited old transcript.\n\nHello.")
        val restored = LiteVoiceDraft.restore(first.encode(), "stale legacy text")
        assertEquals(first, restored.append("first.m4a", "Hello."))
        assertEquals(first, restored.append("silent.m4a", "  "))
        assertEquals("Edited old transcript.\n\nHello.\n\nHi!", restored.append("second.m4a", "Hi!").text)
    }
}
