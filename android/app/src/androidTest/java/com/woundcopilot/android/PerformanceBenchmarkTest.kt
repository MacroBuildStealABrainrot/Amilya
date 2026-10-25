package com.woundcopilot.android
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import java.io.File
import java.util.UUID

/** Opt-in live-provider benchmark through a loopback proxy and synthetic-only local backend. */
class PerformanceBenchmarkTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun measuredFinalizedAudioToReport(){
        val inst=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(BuildConfig.DEBUG && args.getString("liveBenchmark")=="true")
        val phase=args.getString("phase")?:"unknown"
        val controlled=args.getString("controlledBenchmark")=="true"
        val outputPhase=if(controlled)"controlled-$phase"else phase
        require(phase in listOf("before","after"))
        lateinit var s:AppState
        compose.runOnIdle{s=ViewModelProvider(compose.activity)[AppState::class.java]}
        compose.waitUntil(30_000){!s.loading}
        val api=Api(s.vault,"http://127.0.0.1:18766/api/v1")
        runBlocking{api.call("/test/config","POST",obj("reset" to true,"live" to !controlled,"failure" to "","delay" to .4,"open_delay" to .8))}
        compose.runOnIdle{
            check(s.user==null){"Use isolated installation"}
            AppState::class.java.getDeclaredField("api").apply{isAccessible=true}.set(s,api)
            AppState::class.java.getDeclaredMethod("setSession",JSONObject::class.java).apply{isAccessible=true}.invoke(s,obj("user" to obj("id" to "benchmark-synthetic","workspace_id" to "synthetic","display_name" to "Synthetic benchmark")))
            s.unlock();s.screen="home"
        }
        val results=JSONArray();val reports=JSONArray()
        val durations=if(args.getString("shortOnly")=="true")listOf(45)else listOf(45,300,300,300,1500)
        durations.forEachIndexed{trial,seconds->
            runBlocking{api.call("/evaluation/mark","POST",obj("phase" to phase,"trial" to trial+1,"event" to "begin"))}
            val opened=SystemClock.elapsedRealtimeNanos()
            compose.runOnIdle{s.startQuick("document")}
            compose.waitUntil(30_000){!s.loading && s.screen=="document_capture"}
            compose.waitForIdle()
            val openingMs=(SystemClock.elapsedRealtimeNanos()-opened)/1_000_000.0
            // Both versions receive a pre-finalized fixture; allow background setup to overlap capture.
            Thread.sleep(1200)
            val file=File(File(inst.targetContext.filesDir,"recordings").apply{mkdirs()},"recording-${System.currentTimeMillis()}-${UUID.randomUUID()}.m4a")
            inst.context.assets.open("speech-$seconds.m4a").use{input->file.outputStream().use{input.copyTo(it)}}
            s.vault.write("recording-owner-${file.name}","${s.user!!.str("id")}:${s.encounterId}")
            File(file.path+".json").writeText(obj("filename" to file.name,"status" to "ready","elapsedMillis" to seconds*1000L).toString())
            val started=SystemClock.elapsedRealtimeNanos()
            compose.runOnIdle{s.upload(file,"audio/mp4","audio")}
            compose.waitUntil(900_000){!s.loading}
            compose.waitForIdle()
            val elapsed=(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0
            val success=s.screen=="editor" && s.editor.isNotBlank() && s.error==null
            results.put(obj("trial" to trial+1,"audio_seconds" to seconds,"opening_ms" to openingMs,"finalized_audio_to_report_ms" to elapsed,"success" to success,"source_preserved" to file.isFile,"retry_available" to s.retryAvailable))
            reports.put(obj("trial" to trial+1,"synthetic_only" to true,"text" to s.editor,"transcript" to s.case?.items("evidence")?.firstOrNull{it.str("kind")=="audio"}?.str("text")))
            File(inst.targetContext.filesDir,"benchmark-$outputPhase.json").writeText(results.toString())
            File(inst.targetContext.filesDir,"benchmark-$outputPhase-synthetic-reports.json").writeText(reports.toString())
            runCatching{val type=Class.forName("com.woundcopilot.android.Performance");val metrics=type.getMethod("snapshot").invoke(type.getField("INSTANCE").get(null));File(inst.targetContext.filesDir,"benchmark-$outputPhase-metrics.json").writeText(metrics.toString())}
            runBlocking{api.call("/evaluation/mark","POST",obj("phase" to phase,"trial" to trial+1,"event" to "end"))}
            file.delete();File(file.path+".json").delete()
        }
        compose.runOnIdle{s.logout()};compose.waitUntil(30_000){!s.loading}
    }
}
