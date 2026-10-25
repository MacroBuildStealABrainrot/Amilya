package com.woundcopilot.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Item 2, end to end. Real HTTP against the existing backend in an isolated local database, with its real CSRF
 * check. A second Api instance starts from the saved session cookie only, exactly like an app that opened offline.
 */
@RunWith(AndroidJUnit4::class)
class CsrfAfterOfflineStartTest {
    private val local = "http://127.0.0.1:18766/api/v1"

    @Test fun firstWriteAfterAColdStartFetchesTheTokenInsteadOfFailing() = runBlocking {
        Assume.assumeTrue("Local HTTP is debug-only", BuildConfig.DEBUG)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val signedIn = Api(Vault(context), local)
        signedIn.clear()
        val config = signedIn.call("/config") as JSONObject
        signedIn.call("/auth/signup", "POST", obj(
            "email" to "csrf-${UUID.randomUUID()}@example.invalid", "password" to "Synthetic-test-only-9714!",
            "display_name" to "CSRF cold start test", "workspace_name" to "Isolated integration",
            "accept_terms" to true, "terms_version" to config.str("legal_version"), "privacy_version" to config.str("legal_version")))
        val coldStart = Api(Vault(context), local)           // the token is memory-only, so it starts empty
        assertEquals("", coldStart.csrf)
        val patient = coldStart.call("/patients", "POST", obj("display_name" to "Synthetic cold start", "external_ref" to "TEST ONLY")) as JSONObject
        assertTrue(patient.str("id").isNotBlank())
        assertTrue("token was fetched on demand", coldStart.csrf.isNotBlank())
        coldStart.call("/auth/logout", "POST", JSONObject())  // sign-out also needs the token
        coldStart.clear()
    }

    @Test fun aStaleTokenIsRefreshedAndTheRequestIsResentExactlyOnce() = runBlocking {
        Assume.assumeTrue("Local HTTP is debug-only", BuildConfig.DEBUG)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = Api(Vault(context), local)
        first.clear()
        val config = first.call("/config") as JSONObject
        first.call("/auth/signup", "POST", obj(
            "email" to "csrf-stale-${UUID.randomUUID()}@example.invalid", "password" to "Synthetic-test-only-9714!",
            "display_name" to "CSRF stale token test", "workspace_name" to "Isolated integration",
            "accept_terms" to true, "terms_version" to config.str("legal_version"), "privacy_version" to config.str("legal_version")))
        val api = Api(Vault(context), local)
        api.csrf = "stale-token"                              // the server rejects this with 403 before doing any work
        // POST /patients is NOT idempotent (the server has no replay protection for it), so this test never sends it
        // twice on purpose. The only question: after the 403, did the app resend exactly once and did one record result?
        val marker = "TEST-ONLY-" + UUID.randomUUID()
        val patient = api.call("/patients", "POST", obj("display_name" to "Synthetic stale token", "external_ref" to marker)) as JSONObject
        assertTrue(patient.str("id").isNotBlank())
        assertTrue("a fresh token replaced the stale one", api.csrf.isNotBlank() && api.csrf != "stale-token")
        val listed = (api.call("/patients") as org.json.JSONArray).objects().filter { it.str("external_ref") == marker }
        assertEquals("the rejected request was resent once, producing exactly one record", 1, listed.size)
        assertEquals(patient.str("id"), listed.single().str("id"))
        api.call("/auth/logout", "POST", JSONObject())
        api.clear()
    }
}
