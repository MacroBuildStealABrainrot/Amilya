package com.woundcopilot.android
import android.Manifest
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Same harness for baseline and updated app; no dependency on new production classes. */
class NativeStopBenchmarkTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun stopToUsableReport(){
        val inst=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("nativeTiming")=="true")
        val phase=args.getString("phase")!!;require(phase in listOf("before","after"))
        lateinit var s:AppState
        compose.runOnIdle{s=ViewModelProvider(compose.activity)[AppState::class.java]}
        compose.waitUntil(30_000){!s.loading}
        val api=Api(s.vault,"http://127.0.0.1:18766/api/v1")
        runBlocking{api.call("/test/config","POST",obj("live" to false,"reset" to true,"failure" to "","delay" to .4,"open_delay" to .8))}
        compose.runOnIdle{
            AppState::class.java.getDeclaredField("api").apply{isAccessible=true}.set(s,api)
            AppState::class.java.getDeclaredMethod("setSession",JSONObject::class.java).apply{isAccessible=true}.invoke(s,obj("user" to obj("id" to "native-timing-synthetic","workspace_id" to "synthetic","display_name" to "Synthetic timing")))
            s.unlock();s.screen="home";s.startQuick("document")
        }
        compose.waitUntil(30_000){!s.loading};Thread.sleep(1200)
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName,Manifest.permission.RECORD_AUDIO)
        compose.runOnUiThread{RecordingService.start(inst.targetContext,"${s.user!!.str("id")}:${s.encounterId}")}
        compose.waitUntil(15_000){RecordingService.state.value.recording}
        val path=RecordingService.state.value.filePath!!
        Thread.sleep(45_000)
        val start=SystemClock.elapsedRealtimeNanos()
        RecordingService.stop(inst.targetContext)
        compose.waitUntil(15_000){!RecordingService.state.value.recording}
        val finalized=SystemClock.elapsedRealtimeNanos()
        val file=File(path);assertTrue(file.isFile)
        // Submit immediately in both versions; exclude human delay from the old extra Generate tap.
        compose.runOnIdle{s.upload(file,"audio/mp4","audio")}
        compose.waitUntil(60_000){!s.loading};compose.waitForIdle()
        assertEquals("editor",s.screen);assertTrue(s.editor.isNotBlank());assertNull(s.error)
        val complete=SystemClock.elapsedRealtimeNanos()
        File(inst.targetContext.filesDir,"native-timing-$phase.json").writeText(obj("recording_seconds" to 45,"stop_to_finalized_ms" to (finalized-start)/1_000_000.0,"finalized_to_report_ms" to (complete-finalized)/1_000_000.0,"stop_to_usable_report_ms" to (complete-start)/1_000_000.0,"source_preserved" to file.isFile,"condition" to "Native emulator capture; fixed 400ms synthetic endpoint delay; immediate automated submission in both versions").toString())
        runCatching{val type=Class.forName("com.woundcopilot.android.Performance");val metrics=type.getMethod("snapshot").invoke(type.getField("INSTANCE").get(null));File(inst.targetContext.filesDir,"native-timing-$phase-metrics.json").writeText(metrics.toString())}
        compose.runOnIdle{s.logout()};compose.waitUntil(30_000){!s.loading}
    }
}
