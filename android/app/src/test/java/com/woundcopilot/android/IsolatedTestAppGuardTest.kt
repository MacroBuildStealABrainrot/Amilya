package com.woundcopilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The isolated test app must be unable to send anything to the live service, whatever else changes. */
class IsolatedTestAppGuardTest {
    private val test = "https://amilya-repair-test.onrender.com/api/v1"

    @Test fun onlyTheExactTestServerAddressIsAllowed() {
        assertTrue(isAllowedApiOrigin(test, debug = true, sandbox = false, repair = false, renderTest = true, isolated = true))
        for (url in listOf(PRODUCTION_API_ORIGIN, REPAIR_API_ORIGIN, LOCAL_TEST_API_ORIGIN, "https://example.com/api/v1",
            "http://amilya-repair-test.onrender.com/api/v1", "https://amilya-repair-test.onrender.com:8443/api/v1",
            "https://amilya-repair-test.onrender.com.evil.example/api/v1", "$test/x", "")) {
            assertFalse(url, isAllowedApiOrigin(url, debug = true, sandbox = false, repair = false, renderTest = true, isolated = true))
        }
    }
    @Test fun contradictoryFlagsFailClosed() {
        assertFalse(isAllowedApiOrigin(test, true, sandbox = true, repair = false, renderTest = true, isolated = true))
        assertFalse(isAllowedApiOrigin(test, true, sandbox = false, repair = true, renderTest = true, isolated = true))
    }
    @Test fun everyLiveServiceAddressIsRecognised() {
        for (url in listOf(PRODUCTION_API_ORIGIN, REPAIR_API_ORIGIN, PRODUCTION_API_ORIGIN + "/encounters/x/upload",
            "https://WOUNDCOPILOT-1039151887397.us-central1.run.app/api/v1", "https://any-other-service.a.run.app/api/v1")) {
            assertTrue(url, isLiveServiceUrl(url))
            assertFalse(url, isolatedRequestAllowed(url))
        }
        assertFalse(isLiveServiceUrl(test))
    }
    @Test fun everyRequestAddressIsCheckedNotOnlyTheStart() {
        assertTrue(isolatedRequestAllowed("$test/encounters/abc/upload"))
        assertTrue(isolatedRequestAllowed("$test/config"))
        assertFalse(isolatedRequestAllowed("https://amilya-repair-test.onrender.com/other"))
        assertFalse(isolatedRequestAllowed("https://user@amilya-repair-test.onrender.com/api/v1/config"))
    }
    @Test fun onlyReadingTheServerDescriptionIsAllowedBeforeTheServerProvesItself() {
        assertTrue(isIsolationProbe("GET", "/config"))
        assertTrue(isIsolationProbe("GET", "/health"))
        for ((m, p) in listOf("POST" to "/config", "GET" to "/auth/session", "POST" to "/auth/login", "POST" to "/encounters/x/upload", "GET" to "/encounters/x")) {
            assertFalse("$m $p", isIsolationProbe(m, p))
        }
    }
    @Test fun serverMustSayItIsTheSyntheticOnlyTestServer() {
        // Today's test server (synthetic marker, patient data not allowed, clinical data off):
        assertTrue(isolatedServerAnswer(true, false, false, null))
        // A newer test server that also declares itself:
        assertTrue(isolatedServerAnswer(true, false, false, true))
        // The live service, or any unclear answer:
        assertFalse(isolatedServerAnswer(false, null, false, null))   // no synthetic marker (live service)
        assertFalse(isolatedServerAnswer(true, true, false, null))    // patient data allowed
        assertFalse(isolatedServerAnswer(true, null, false, null))    // answer missing
        assertFalse(isolatedServerAnswer(true, false, true, null))    // clinical data on
        assertFalse(isolatedServerAnswer(true, false, null, null))    // clinical data answer missing
        assertFalse(isolatedServerAnswer(true, false, false, false))  // server says it is NOT the isolated test server
    }
}
