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

class VoiceConsentTest {
    @Test fun spokenConsentIsUnderstood() {
        org.junit.Assert.assertEquals(true, consentAnswer("Yes."))
        org.junit.Assert.assertEquals(true, consentAnswer("consent given"))
        org.junit.Assert.assertEquals(false, consentAnswer("no"))
        org.junit.Assert.assertNull(consentAnswer("yes but wait for the daughter"))
        org.junit.Assert.assertEquals("record_consent", parseVoiceCommand("record with consent", activelyListening = true)?.action)
    }
}
