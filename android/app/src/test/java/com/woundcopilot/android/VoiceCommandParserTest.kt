package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandParserTest {
    private fun heard(text: String) = parseVoiceCommand(text, activelyListening = true)

    @Test fun everyDayCommandsAreUnderstood() {
        assertEquals("record", heard("Record")?.action)
        assertEquals("photo", heard("Take a photo.")?.action)
        assertEquals("consult", heard("ask consultant")?.action)
        assertEquals("send", heard("Send")?.action)
        assertEquals("done", heard("Done")?.action)
        assertEquals("home", heard("go home please")?.action)
        assertEquals("read", heard("read the report")?.action)
        assertEquals(VoiceCommand("format", "soap"), heard("SOAP note"))
        assertEquals(VoiceCommand("format", "nursing"), heard("switch to nursing note"))
        assertEquals(VoiceCommand("format", "handoff"), heard("make a handoff"))
    }

    @Test fun ordinarySpeechNeverTriggersACommand() {
        assertNull(heard("the patient says send it to his daughter"))
        assertNull(heard("send this report to someone"))
        assertNull(heard("delete all saved work"))
        assertNull(parseVoiceCommand("Send"))
    }
}

/** Consent to record is confirmed only by a tap. Voice can open recording but never confirms consent. */
class VoiceConsentTest {
    @Test fun recordCommandsAreStillRecognisedButOnlyOpenTheRecordingScreen() {
        org.junit.Assert.assertEquals("record_consent", parseVoiceCommand("record with consent", activelyListening = true)?.action)
        org.junit.Assert.assertEquals("record", parseVoiceCommand("record", activelyListening = true)?.action)
    }
    @Test fun onlyTapMethodsAreValidConsent() {
        org.junit.Assert.assertEquals(setOf("phone_tap", "watch_tap"), CONSENT_METHODS)
        for (spoken in listOf("voice", "voice_yes", "spoken", "", "yes")) {
            org.junit.Assert.assertTrue(spoken, consentFormFields(spoken, "2026-10-31T01:00:00Z", CONSENT_WORDING).isEmpty())
        }
        org.junit.Assert.assertTrue(consentFormFields(null, null, null).isEmpty())
    }
    @Test fun aTapIsSentWithItsTimeAndWording() {
        val fields = consentFormFields("phone_tap", "2026-10-31T01:02:03.456Z", CONSENT_WORDING)
        org.junit.Assert.assertEquals("phone_tap", fields["consent_method"])
        org.junit.Assert.assertEquals("2026-10-31T01:02:03.456Z", fields["consent_confirmed_at"])
        org.junit.Assert.assertEquals("tap-client-agreed-v1", fields["consent_wording"])
        org.junit.Assert.assertEquals("watch_tap", consentFormFields("watch_tap", null, null)["consent_method"])
    }
    @Test fun oddValuesAreDroppedNotSent() {
        val fields = consentFormFields("phone_tap", "yesterday\r\nX: y", "Wording With Spaces")
        org.junit.Assert.assertEquals(mapOf("consent_method" to "phone_tap"), fields)
    }
}
