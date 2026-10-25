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
class ReliabilityPassTest{
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
    private fun output(name:String,data:JSONObject){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir,"reliability-$name.json").writeText(data.toString())
        println("RELIABILITY $name $data")
    }
    @Test fun documentOpeningBaseline(){
        val times=mutableListOf<Long>()
        repeat(3){
            compose.runOnIdle{s.screen="home"}
            val start=SystemClock.elapsedRealtimeNanos()
            compose.onNodeWithText("Document",useUnmergedTree=true).performClick()
            compose.waitUntil(10_000){s.screen=="document_capture" && !s.loading}
            compose.onNodeWithText("Record").assertIsEnabled()
            times+=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000
        }
        output("opening",obj("milliseconds" to org.json.JSONArray(times),"median_ms" to times.sorted()[1],"condition" to "Loopback HTTP: quick/start delay 800ms; other requests 400ms; emulator UI"))
    }
    @Test fun reportFailureRecoveryBaseline(){
        compose.runOnIdle{s.startQuick("document")};awaitTask()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(File(context.filesDir,"recordings").apply{mkdirs()},"recording-${System.currentTimeMillis()}-${UUID.randomUUID()}.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("reliability-5-minute.m4a").use{input->file.outputStream().use{input.copyTo(it)}}
        val sidecar=File(file.path+".json");sidecar.writeText(obj("filename" to file.name,"status" to "ready","elapsedMillis" to 300000).toString())
        artifacts+=file;artifacts+=sidecar
        s.vault.write("recording-owner-${file.name}","${s.user!!.str("id")}:${s.encounterId}")
        s.vault.write("capture-${s.user!!.str("id")}-${s.encounterId}-audio",file.path)
        assertTrue(RecordingService.pendingRecordings(context).any{it.filePath==file.path})
        call("/test/config",obj("failure" to "quick-report"))
        val started=SystemClock.elapsedRealtimeNanos()
        compose.runOnIdle{s.upload(file,"audio/mp4","audio")};awaitTask()
        val recovered=RecordingService.pendingRecordings(context).any{it.filePath==file.path}
        val statsBefore=call("/test/stats").getJSONObject("calls")
        val result=obj("elapsed_ms" to (SystemClock.elapsedRealtimeNanos()-started)/1_000_000,"file_exists" to file.exists(),"pending_recovery" to recovered,"retry_available" to s.retryAvailable,"transcript_count" to s.case!!.items("evidence").count{it.str("kind")=="audio"},"calls_after_failure" to statsBefore)
        assertTrue(file.exists());assertNotNull(s.error);assertTrue(s.retryAvailable)
        call("/test/config",obj("failure" to ""))
        compose.runOnIdle{s.retryFailed()};awaitTask()
        assertTrue(s.editor.isNotBlank());assertEquals("editor",s.screen)
        val after=call("/test/stats").getJSONObject("calls")
        assertEquals(statsBefore.optInt("audio-parts"),after.optInt("audio-parts"))
        result.put("retry_reused_transcript",true);result.put("calls_after_retry",after)
        output("report-failure",result)
    }
    @Test fun recoveredJobSurvivesReconstructionAndDoesNotRepeatTranscription(){
        compose.runOnIdle{s.startQuick("document")};awaitTask()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(File(context.filesDir,"recordings").apply{mkdirs()},"recording-${System.currentTimeMillis()}-${UUID.randomUUID()}.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("reliability-5-minute.m4a").use{input->file.outputStream().use{input.copyTo(it)}}
        artifacts+=file
        call("/test/config",obj("failure" to "quick-report"))
        compose.runOnIdle{s.upload(file,"audio/mp4","audio")};awaitTask()
        val recovered=RecordingRecovery(context).list(s.user!!.str("id")).single{it.str("file")==file.canonicalPath}
        assertEquals("FAILED_RETRYABLE",recovered.str("phase"));assertEquals("GENERATING_REPORT",recovered.str("resume_from"))
        val before=call("/test/stats").getJSONObject("calls").optInt("audio-parts")
        call("/test/config",obj("failure" to ""))
        compose.runOnIdle{s.startQuick("document")};awaitTask()
        compose.runOnIdle{s.resumeRecording(recovered)};awaitTask()
        assertTrue(s.editor.isNotBlank());assertEquals(before,call("/test/stats").getJSONObject("calls").optInt("audio-parts"))
        compose.runOnIdle{s.edit("Clinician edited wording stays intact.");s.persistEditor();s.screen="home"}
        val ready=RecordingRecovery(context).list(s.user!!.str("id")).single{it.str("file")==file.canonicalPath}
        compose.runOnIdle{s.resumeRecording(ready)};awaitTask()
        assertEquals("Clinician edited wording stays intact.",s.editor)
        output("reopen",obj("durable_job_reconstructed" to true,"transcript_reused" to true,"clinician_edit_preserved" to true))
    }
    @Test fun transcriptionFailureKeepsOriginalAndRetryIsIdempotent(){
        compose.runOnIdle{s.startQuick("document")};awaitTask()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(File(context.filesDir,"recordings").apply{mkdirs()},"recording-${System.currentTimeMillis()}-${UUID.randomUUID()}.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("reliability-5-minute.m4a").use{input->file.outputStream().use{input.copyTo(it)}}
        artifacts+=file
        val original=file.readBytes()
        call("/test/config",obj("failure" to "audio-parts"))
        compose.runOnIdle{s.upload(file,"audio/mp4","audio")};awaitTask()
        assertTrue(s.retryAvailable);assertArrayEquals(original,file.readBytes())
        val recovered=RecordingRecovery(context).list(s.user!!.str("id")).single{it.str("file")==file.canonicalPath}
        call("/test/config",obj("failure" to ""))
        compose.runOnIdle{s.resumeRecording(recovered);s.resumeRecording(recovered)};awaitTask()
        assertTrue(s.editor.isNotBlank());assertEquals(1,s.case!!.items("evidence").count{it.str("kind")=="audio"})
        output("transcription-retry",obj("source_unchanged" to true,"double_tap_single_result" to true))
    }
    @Test fun interruptedUploadAndMissingSegmentsRecoverFromOriginal(){
        compose.runOnIdle{s.startQuick("document")};awaitTask()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(File(context.filesDir,"recordings").apply{mkdirs()},"recording-${UUID.randomUUID()}.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("reliability-5-minute.m4a").use{input->file.outputStream().use{input.copyTo(it)}}
        artifacts+=file
        call("/test/config",obj("drop_upload_once" to true))
        compose.runOnIdle{s.upload(file,"audio/mp4","audio")};awaitTask()
        assertTrue(file.isFile);assertNotNull(s.error);assertTrue(s.retryAvailable)
        // Remove only this synthetic job's segment files to reproduce expired cache recovery.
        s.vault.names().filter{it.startsWith("audio-batch-reliability-synthetic-${s.encounterId}")}.forEach{key->
            val batch=JSONObject(s.vault.read(key)!!);val files=batch.getJSONArray("files")
            for(i in 0 until files.length())File(files.getString(i)).delete()
        }
        compose.runOnIdle{s.retryFailed()};awaitTask()
        assertTrue(s.editor.isNotBlank());assertEquals(1,s.case!!.items("evidence").count{it.str("kind")=="audio"})
        output("upload-disconnect",obj("socket_interrupted" to true,"source_preserved" to file.isFile,"segments_rebuilt" to true,"retry_success" to true))
    }
    @Test fun lostReportResponseReplaysWithoutDuplicateSource()=reportResponseRecovery("drop_response_once")
    @Test fun malformedSuccessReplaysSameReport()=reportResponseRecovery("malformed_once")
    @Test fun inProgressResponseRetainsRequestIdentity()=reportResponseRecovery("pending_once")
    private fun reportResponseRecovery(failure:String){
        compose.runOnIdle{s.startQuick("document")};awaitTask()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(File(context.filesDir,"recordings").apply{mkdirs()},"recording-${UUID.randomUUID()}.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("reliability-5-minute.m4a").use{input->file.outputStream().use{input.copyTo(it)}}
        artifacts+=file
        call("/test/config",obj(failure to "quick-report"))
        compose.runOnIdle{s.upload(file,"audio/mp4","audio")};awaitTask()
        val before=call("/test/stats").getJSONObject("calls").optInt("audio-parts")
        assertTrue(file.isFile)
        if(s.error!=null){assertTrue(s.retryAvailable);Thread.sleep((s.retryAtMillis-System.currentTimeMillis()).coerceIn(0,3000)+50);compose.runOnIdle{s.retryFailed()};awaitTask()}
        assertTrue(s.editor.isNotBlank());assertEquals(before,call("/test/stats").getJSONObject("calls").optInt("audio-parts"))
        assertEquals(1,s.case!!.items("evidence").count{it.str("kind")=="audio"})
        assertEquals(1,call("/test/stats").getJSONObject("calls").optInt("reports_created"))
        output(failure,obj("transcription_not_repeated" to true,"single_visible_source" to true,"single_report_created" to true))
    }
    @Test fun otherEncounterCannotReassignPendingRecording(){
        compose.runOnIdle{s.startQuick("document")};awaitTask()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(File(context.filesDir,"recordings").apply{mkdirs()},"recording-${UUID.randomUUID()}.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("reliability-5-minute.m4a").use{input->file.outputStream().use{input.copyTo(it)}}
        artifacts+=file
        call("/test/config",obj("failure" to "quick-report"))
        compose.runOnIdle{s.upload(file,"audio/mp4","audio")};awaitTask()
        val original=s.encounterId
        val other=call("/quick/start",obj("mode" to "document","request_id" to UUID.randomUUID().toString())).getJSONObject("encounter").str("id")
        call("/test/config",obj("failure" to ""))
        compose.runOnIdle{s.openEncounter(other)};awaitTask()
        compose.runOnIdle{s.quickReport()};awaitTask()
        val job=RecordingRecovery(context).read(file)!!
        assertEquals(original,job.getJSONObject("case").getJSONObject("encounter").str("id"))
        assertEquals("FAILED_RETRYABLE",job.str("phase"))
        output("encounter-isolation",obj("original_owner_preserved" to true,"pending_phase_preserved" to true))
    }
}
