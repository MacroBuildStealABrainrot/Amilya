package com.woundcopilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sandbox (test-only) build must be unable to reach the live service, whatever else changes. */
class ApiOriginGuardTest {
    private val production = PRODUCTION_API_ORIGIN
    private val local = LOCAL_TEST_API_ORIGIN

    @Test fun sandboxRefusesTheLiveService() = assertFalse(isAllowedApiOrigin(production, debug = true, sandbox = true))
    @Test fun sandboxAllowsOnlyTheTestServerOnThisPc() {
        assertTrue(isAllowedApiOrigin(local, debug = true, sandbox = true))
        assertTrue(isAllowedApiOrigin("http://localhost:18766/api/v1", debug = true, sandbox = true))
    }
    @Test fun sandboxRefusesAnyOtherAddress() {
        for (url in listOf("https://example.com/api/v1", "http://192.168.1.5:18766/api/v1", "http://127.0.0.1:8766/api/v1",
            "http://127.0.0.1:18766/api/v1/", "https://woundcopilot-1039151887397.us-central1.run.app/api/v1/x", "")) {
            assertFalse(url, isAllowedApiOrigin(url, debug = true, sandbox = true))
        }
    }
    @Test fun releaseStillUsesTheLiveServiceAndNeverLocalHttp() {
        assertTrue(isAllowedApiOrigin(production, debug = false, sandbox = false))
        assertFalse(isAllowedApiOrigin(local, debug = false, sandbox = false))
    }
    @Test fun normalDebugBehavesAsBefore() {
        assertTrue(isAllowedApiOrigin(production, debug = true, sandbox = false))
        assertTrue(isAllowedApiOrigin(local, debug = true, sandbox = false))
    }
    @Test fun repairOnlyAllowsExactRepairEndpoint() {
        assertTrue(isAllowedApiOrigin(REPAIR_API_ORIGIN, true, false, true))
        for (url in listOf(PRODUCTION_API_ORIGIN, LOCAL_TEST_API_ORIGIN, "https://example.com/api/v1", REPAIR_API_ORIGIN + "/x")) {
            assertFalse(isAllowedApiOrigin(url, true, false, true))
        }
        assertFalse(isAllowedApiOrigin(REPAIR_API_ORIGIN, true, true, true))
        assertFalse(isAllowedApiOrigin(REPAIR_API_ORIGIN, true, false))
    }
    @Test fun renderTestAllowsOnlyItsExactHttpsEndpoint() {
        assertTrue(isAllowedApiOrigin(RENDER_TEST_API_ORIGIN, true, false, false, true))
        assertTrue(isAllowedApiOrigin(RENDER_TEST_API_ORIGIN, false, false, false, true))
        for (url in listOf(PRODUCTION_API_ORIGIN, REPAIR_API_ORIGIN, LOCAL_TEST_API_ORIGIN,
            "https://example.com/api/v1", RENDER_TEST_API_ORIGIN + "/", RENDER_TEST_API_ORIGIN + "/x",
            "http://amilya-repair-test.onrender.com/api/v1", "https://amilya-repair-test.onrender.com:443/api/v1",
            "https://amilya-repair-test.onrender.com.evil.example/api/v1")) {
            assertFalse(url, isAllowedApiOrigin(url, true, false, false, true))
        }
    }
    @Test fun otherBuildsCannotAccidentallyUseRender() {
        assertFalse(isAllowedApiOrigin(RENDER_TEST_API_ORIGIN, true, false))
        assertFalse(isAllowedApiOrigin(RENDER_TEST_API_ORIGIN, false, false))
        assertFalse(isAllowedApiOrigin(RENDER_TEST_API_ORIGIN, true, false, true))
        assertFalse(isAllowedApiOrigin(RENDER_TEST_API_ORIGIN, true, true))
    }
    @Test fun contradictoryRenderFlagsFailClosed() {
        for (url in listOf(RENDER_TEST_API_ORIGIN, LOCAL_TEST_API_ORIGIN, REPAIR_API_ORIGIN, PRODUCTION_API_ORIGIN)) {
            assertFalse(isAllowedApiOrigin(url, true, true, false, true))
            assertFalse(isAllowedApiOrigin(url, true, false, true, true))
        }
    }
}
