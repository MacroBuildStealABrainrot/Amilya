package com.woundcopilot.android

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real microphone capture, automatic Stop processing, and cross-process recovery. Opt-in only. */
class RecordingWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var s:AppState
    private lateinit var api:Api
    private var initialUser=""
    private var initialScreen=""
    private var initialLocked=false
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val args get()=InstrumentationRegistry.getArguments()
    private fun call(path:String,body:JSONObject?=null)=runBlocking{api.call(path,if(body==null)"GET"else"POST",body)} as JSONObject
    private fun idle()=compose.waitUntil(60_000){!s.loading}
    @Before fun setup(){
        Assume.assumeTrue(args.getString("workflowReliability")=="true")
        compose.runOnIdle{s=ViewModelProvider(compose.activity)[AppState::class.java];initialUser=s.user?.str("id").orEmpty();initialScreen=s.screen;initialLocked=s.locked};idle()
        api=Api(s.vault,"http://127.0.0.1:18766/api/v1")
        compose.runOnIdle{
            AppState::class.java.getDeclaredField("api").apply{isAccessible=true}.set(s,api)
            AppState::class.java.getDeclaredMethod("setSession",JSONObject::class.java).apply{isAccessible=true}.invoke(s,obj("user" to obj("id" to "reliability-synthetic","workspace_id" to "synthetic","display_name" to "Synthetic test")))
            s.unlock();s.screen="home"
        }
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName,Manifest.permission.RECORD_AUDIO)
    }
    private fun result(name:String,data:JSONObject){File(inst.targetContext.filesDir,"workflow-$name.json").writeText(data.toString())}
    @Test fun recordStopAndPreserveFailedReport(){
        val seconds=args.getString("captureSeconds")?.toInt()?:45
        call("/test/config",obj("live" to false,"reset" to true,"failure" to "quick-report","delay" to .2,"open_delay" to .5))
        compose.runOnIdle{s.startQuick("document")};idle()
        compose.onNodeWithText("Record").performClick()
        compose.onNodeWithText("Yes, record").performClick()
        compose.waitUntil(15_000){RecordingService.state.value.recording}
        val path=RecordingService.state.value.filePath!!
        result("capture-marker",obj("path" to path,"seconds" to seconds))
        // External driver can interrupt network, background the app, or lock the screen here.
        repeat(seconds){Thread.sleep(1000);assertTrue("Capture stopped early",RecordingService.state.value.recording)}
        // Bring capture UI back before Stop so its normal automatic processing path runs.
        inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        inst.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        inst.uiAutomation.executeShellCommand("am start -n ${inst.targetContext.packageName}/.MainActivity").close()
        Thread.sleep(700)
        if(args.getString("busyStop")=="true"){
            compose.runOnIdle{s.runTask("Synthetic concurrent request"){kotlinx.coroutines.delay(2000)}}
            compose.waitUntil(5000){s.loading}
        }
        val started=Performance.now()
        RecordingService.stop(inst.targetContext)
        compose.waitUntil(20_000){!RecordingService.state.value.recording}
        compose.waitUntil(60_000){s.error!=null && !s.loading}
        val file=File(path)
        assertTrue(file.isFile);assertTrue(file.length()>128)
        assertTrue(RecordingService.pendingRecordings(inst.targetContext).any{it.filePath==path})
        val job=RecordingRecovery(inst.targetContext).list("reliability-synthetic").single{it.str("file")==file.canonicalPath}
        assertEquals("FAILED_RETRYABLE",job.str("phase"));assertTrue(s.retryAvailable)
        result("pending-marker",obj("path" to path,"parts_before" to call("/test/stats").getJSONObject("calls").optInt("audio-parts"),"resume_from" to job.str("resume_from")))
        result("capture-$seconds",obj("capture_seconds" to seconds,"bytes" to file.length(),"stop_to_failure_ms" to (Performance.now()-started)/1_000_000,"automatic_processing" to true,"pending_recovery" to true,"metrics" to Performance.snapshot()))
    }
    @Test fun resumeAfterProcessRestart(){
        val marker=JSONObject(File(inst.targetContext.filesDir,"workflow-pending-marker.json").readText())
        val file=File(marker.str("path"));assertTrue(file.isFile)
        val job=RecordingRecovery(inst.targetContext).list("reliability-synthetic").single{it.str("file")==file.canonicalPath}
        call("/test/config",obj("live" to false,"failure" to ""))
        compose.runOnIdle{s.resumeRecording(job);s.resumeRecording(job)};idle()
        assertEquals("editor",s.screen);assertTrue(s.editor.isNotBlank())
        if(marker.str("resume_from")=="GENERATING_REPORT")assertEquals(marker.optInt("parts_before"),call("/test/stats").getJSONObject("calls").optInt("audio-parts"))
        assertEquals(1,s.case!!.items("evidence").count{it.str("kind")=="audio"})
        result("process-reopen",obj("source_preserved" to true,"retry_success" to true,"transcript_reused" to (marker.str("resume_from")=="GENERATING_REPORT"),"single_visible_source" to true))
        compose.runOnIdle{s.logout()};idle()
    }
    @Test fun prepareOfflineReopen(){
        // A synthetic expired token exercises local startup, never a production credential.
        s.vault.write("session","wcp_session=synthetic-offline-evaluation")
        result("offline-prepared",obj("prepared" to true))
    }
    @Test fun coldOfflineDocumentAndCapture(){
        assertEquals("reliability-synthetic",initialUser);assertEquals("home",initialScreen);assertTrue(initialLocked)
        call("/test/config",obj("live" to false,"reset" to true,"failure" to "start","delay" to .2,"open_delay" to .5))
        val started=Performance.now()
        compose.runOnIdle{s.startQuick("document")}
        compose.onNodeWithText("Record").assertIsEnabled().performClick()
        compose.onNodeWithText("Yes, record").performClick()
        compose.waitUntil(15_000){RecordingService.state.value.recording}
        val path=RecordingService.state.value.filePath!!
        Thread.sleep(45_000);RecordingService.stop(inst.targetContext)
        compose.waitUntil(30_000){s.error!=null && !s.loading}
        assertTrue(File(path).isFile)
        val job=RecordingRecovery(inst.targetContext).list("reliability-synthetic").single{it.str("file")==File(path).canonicalPath}
        call("/test/config",obj("failure" to ""))
        compose.runOnIdle{s.resumeRecording(job)};idle();assertTrue(s.editor.isNotBlank())
        result("cold-offline",obj("cached_account_loaded" to true,"pin_lock_preserved" to true,"recorded_seconds" to 45,"source_preserved" to true,"retry_success" to true,"elapsed_ms" to (Performance.now()-started)/1_000_000))
        s.vault.remove("session")
        compose.runOnIdle{s.logout()};idle()
    }
}
