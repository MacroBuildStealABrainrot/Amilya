package com.woundcopilot.android

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Opt-in controlled-network tests against an isolated loopback synthetic server. */
class PhotoRecoveryTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var s:AppState
    private lateinit var api:Api
    private val artifacts=mutableListOf<File>()
    private fun awaitTask()=compose.waitUntil(30_000){!s.loading}
    private fun call(path:String,body:JSONObject?=null)=runBlocking{api.call(path,if(body==null)"GET" else "POST",body)} as JSONObject
    @Before fun setup(){
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("reliability")=="true" && BuildConfig.DEBUG)
        compose.runOnIdle{s=ViewModelProvider(compose.activity)[AppState::class.java]};awaitTask()
        api=Api(s.vault,"http://127.0.0.1:18766/api/v1")
        call("/test/config",obj("live" to false,"reset" to true,"failure" to "","delay" to .4,"open_delay" to .8))
        compose.runOnIdle{
            check(s.user==null){"Use isolated test installation"}
            AppState::class.java.getDeclaredField("api").apply{isAccessible=true}.set(s,api)
            AppState::class.java.getDeclaredMethod("setSession",JSONObject::class.java).apply{isAccessible=true}.invoke(s,obj("user" to obj("id" to "reliability-synthetic","workspace_id" to "synthetic","display_name" to "Synthetic test")))
            s.unlock();s.screen="home"
        }
    }
    @After fun cleanup(){
        if(::s.isInitialized){awaitTask();compose.runOnIdle{s.logout()};awaitTask()}
        artifacts.forEach{it.delete()}
    }
    private fun image():File {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(File(context.filesDir,"captures").apply{mkdirs()},"photo-${UUID.randomUUID()}.png")
        val bitmap=android.graphics.Bitmap.createBitmap(40,30,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        artifacts+=file;return file
    }
    private fun startPhoto(){compose.runOnIdle{s.startQuick("photo")};awaitTask()}
    @Test fun failedPhotoRetainedRetrySameBytesAndDuplicateTapsIgnored(){
        startPhoto();val file=image();val bytes=file.readBytes()
        call("/test/config",obj("failure" to "upload"))
        compose.runOnIdle{s.uploadPhotos(listOf(file to "image/png"))};awaitTask()
        assertTrue(s.retryAvailable);assertNotNull(s.error);assertArrayEquals(bytes,file.readBytes())
        assertEquals(file.path,s.pendingPhotoFiles().single().first.path)
        call("/test/config",obj("failure" to ""))
        compose.runOnIdle{s.retryFailed();s.retryFailed();s.retryFailed()};awaitTask()
        assertNull(s.error);assertEquals("photo_assessment",s.screen)
        assertEquals(1,s.case!!.items("evidence").count{it.str("kind")=="photo"})
        assertTrue(s.pendingPhotoFiles().isEmpty());assertArrayEquals(bytes,file.readBytes())
        val stats=call("/test/stats").getJSONObject("calls")
        assertEquals(2,stats.getInt("upload"));assertEquals(1,stats.getInt("photo_successes"))
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        assertEquals(hash,stats.str("last_photo_hash"))
    }
    @Test fun lostResponseRetryReturnsOneAssessment(){
        startPhoto();val file=image()
        call("/test/config",obj("drop_response_once" to "upload"))
        compose.runOnIdle{s.uploadPhotos(listOf(file to "image/png"))};awaitTask()
        if(s.retryAvailable){compose.runOnIdle{s.retryFailed()};awaitTask()}
        assertNull(s.error);assertEquals(1,s.case!!.items("evidence").size)
        assertEquals(1,call("/test/stats").getJSONObject("calls").getInt("photo_successes"))
        assertTrue(file.isFile)
    }
    @Test fun lateResponseCannotReplaceAnotherVisit(){
        startPhoto();val original=s.encounterId;val file=image()
        call("/test/config",obj("delay" to 1.5))
        compose.runOnIdle{s.uploadPhotos(listOf(file to "image/png"))}
        Thread.sleep(300)
        compose.runOnIdle{AppState::class.java.getDeclaredMethod("setCase",JSONObject::class.java).apply{isAccessible=true}.invoke(s,JSONObject(s.case.toString()).apply{getJSONObject("encounter").put("id",UUID.randomUUID().toString());put("evidence",org.json.JSONArray())})}
        awaitTask()
        assertNotEquals(original,s.encounterId);assertTrue(s.case!!.items("evidence").isEmpty());assertTrue(file.exists())
    }
    @Test fun pendingBatchGetsBoundedSevenDayRecovery(){
        startPhoto();val a=image();val b=image();val now=System.currentTimeMillis()
        a.setLastModified(now-2*24*3600000L);b.setLastModified(now-2*24*3600000L)
        val key="photo-batch-pending-${s.user!!.str("id")}-${s.encounterId}"
        s.vault.write(key,org.json.JSONArray(listOf(obj("path" to a.path,"mime" to "image/png"),obj("path" to b.path,"mime" to "image/png"))).toString())
        LocalRetention.clean(compose.activity,now)
        assertTrue(a.isFile);assertTrue(b.isFile)
        LocalRetention.clean(compose.activity,now+8*24*3600000L)
        assertFalse(a.isFile);assertFalse(b.isFile)
    }
    @Test fun permanentFailureIsNotOfferedAsTemporaryRetry(){
        assertFalse(canRetryRequest(ApiException(503,"safe",category="PROVIDER_QUOTA",retryAllowed=false)))
        assertTrue(canRetryRequest(ApiException(502,"safe",category="PROVIDER_UNAVAILABLE",retryAllowed=true)))
        assertTrue(clinicianError(ApiException(503,"safe",category="PROVIDER_QUOTA"),"photo").contains("administrator"))
    }
}
