package com.woundcopilot.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real HTTP against the existing backend in an isolated local database. Never production. */
@RunWith(AndroidJUnit4::class)
class BackendContractTest {
    @Test fun nativeSessionEvidenceReviewSaveSignAndAmend()=runBlocking {
        org.junit.Assume.assumeTrue("Local HTTP is debug-only", BuildConfig.DEBUG)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val api=Api(Vault(context),"http://127.0.0.1:18766/api/v1")
        api.clear()
        val config=api.call("/config") as JSONObject
        val session=api.call("/auth/signup","POST",obj("email" to "android-${UUID.randomUUID()}@example.invalid","password" to "Synthetic-test-only-9714!","display_name" to "Android test","workspace_name" to "Isolated instrumentation","accept_terms" to true,"terms_version" to config.str("legal_version"),"privacy_version" to config.str("legal_version"))) as JSONObject
        assertTrue(session.str("csrf_token").isNotBlank())
        assertEquals(session.getJSONObject("user").str("id"),(api.call("/auth/session") as JSONObject).getJSONObject("user").str("id"))
        val patient=api.call("/patients","POST",obj("display_name" to "Synthetic Android patient","external_ref" to "TEST ONLY")) as JSONObject
        val encounter=api.call("/encounters","POST",obj("patient_id" to patient.str("id"),"title" to "Native contract test")) as JSONObject
        val path="/encounters/${encounter.str("id")}"
        val evidence=obj("kind" to "measurement","findings" to JSONArray().put(obj("name" to "wound_measurement","value" to obj("length_cm" to 2.0,"width_cm" to 1.0))))
        val id=UUID.randomUUID().toString()
        val first=api.call("$path/evidence","POST",evidence,id) as JSONObject
        val replay=api.call("$path/evidence","POST",evidence,id) as JSONObject
        assertEquals(first.getJSONObject("evidence").str("id"),replay.getJSONObject("evidence").str("id"))
        assertEquals("confirmed",first.getJSONArray("facts").getJSONObject(0).str("confirmation_state"))
        api.call("$path/review","POST",obj("mode" to "auto","use_ai" to false))
        val generated=api.call("$path/draft","POST",obj("template" to "nursing","mode" to "auto","use_ai" to false)) as JSONObject
        assertTrue(generated.str("text").isNotBlank())
        val exact=generated.str("text")+"\n\nClinician correction — café, 2 × 1 cm.\n"
        var case=api.call(path) as JSONObject
        val save=obj("text" to exact,"template" to "nursing","expected_revision" to case.getJSONObject("encounter").optInt("revision"))
        val saveKey=UUID.randomUUID().toString()
        val doc=api.call("$path/documents","POST",save,saveKey) as JSONObject
        assertEquals(exact,doc.str("text"))
        assertEquals(doc.str("id"),(api.call("$path/documents","POST",save,saveKey) as JSONObject).str("id"))
        case=api.call(path) as JSONObject
        val signed=api.call("/documents/${doc.str("id")}/sign","POST",obj("content_hash" to doc.str("content_hash"),"acknowledge_review" to true,"expected_case_revision" to case.getJSONObject("encounter").optInt("revision"))) as JSONObject
        assertEquals("signed",signed.str("status"));assertEquals(exact,signed.str("text"))
        case=api.call(path) as JSONObject
        val amendment=api.call("$path/documents","POST",obj("text" to "$exact\nAmendment: synthetic follow-up.","template" to "nursing","expected_revision" to case.getJSONObject("encounter").optInt("revision"),"amends_id" to signed.str("id"),"reason" to "Synthetic correction"),UUID.randomUUID().toString()) as JSONObject
        assertEquals(signed.str("id"),amendment.str("amends_id"))
        assertEquals("Synthetic correction",amendment.str("reason"))
        val history=api.call("$path/audit") as JSONArray
        assertTrue(history.length()>3)
        api.call("/auth/logout","POST",JSONObject());api.clear()
    }
}
