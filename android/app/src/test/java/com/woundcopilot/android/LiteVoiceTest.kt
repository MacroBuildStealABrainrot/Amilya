package com.woundcopilot.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Amilya Voice speaker labels. Fake sentences only. */
class LiteVoiceTest {
    private fun piece(text: String, speaker: Int?) = JSONObject().put("text", text).also { if (speaker != null) it.put("speaker", speaker) }

    @Test fun groupsConsecutiveWordsBySpeaker() {
        val segments = JSONArray(listOf(piece("Hello, how are you today?", 0), piece("I am fine.", 1), piece("Any pain?", 0), piece("A little.", 1), piece("In the leg.", 1)))
        assertEquals("Speaker 1: Hello, how are you today?\n\nSpeaker 2: I am fine.\n\nSpeaker 1: Any pain?\n\nSpeaker 2: A little. In the leg.", liteSpeakerText(segments, "plain"))
    }

    @Test fun returnsThePlainTextWhenThereAreNoSpeakers() {
        assertEquals("plain", liteSpeakerText(JSONArray(listOf(piece("a", null), piece("b", null))), "plain"))
        assertEquals("plain", liteSpeakerText(null, "plain"))
        assertEquals("plain", liteSpeakerText(JSONArray(), "plain"))
    }

    @Test fun skipsEmptyPiecesAndKeepsUnlabelledWords() {
        val segments = JSONArray(listOf(piece("  ", 0), piece("One.", 0), piece("Two.", null)))
        assertEquals("Speaker 1: One. Two.", liteSpeakerText(segments, "plain"))
    }
}
