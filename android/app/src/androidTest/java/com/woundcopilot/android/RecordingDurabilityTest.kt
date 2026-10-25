package com.woundcopilot.android
import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** External driver deliberately force-stops capture; verification runs in a new process. */
class RecordingDurabilityTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun optIn(){Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("durability")=="true")}
    @Test fun captureUntilExternalInterruption(){
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        inst.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val owner="durability-synthetic:${UUID.randomUUID()}"
        Vault(context).write("recording-context-$owner",obj("owner" to "durability-synthetic","case" to obj("encounter" to obj("id" to owner.substringAfter(':'))),"template" to "nursing").toString())
        compose.runOnUiThread{RecordingService.start(context,owner)}
        compose.waitUntil(10_000){RecordingService.state.value.recording}
        val path=RecordingService.state.value.filePath!!
        File(context.filesDir,"durability-marker.json").writeText(obj("path" to path,"owner" to owner).toString())
        Thread.sleep((InstrumentationRegistry.getArguments().getString("captureSeconds")?.toLong()?:120)*1000)
        RecordingService.stop(context)
    }
    @Test fun verifyAfterExternalInterruption(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val marker=JSONObject(File(context.filesDir,"durability-marker.json").readText())
        val file=File(marker.str("path"));assertTrue(file.exists())
        val pending=RecordingService.pendingRecordings(context).firstOrNull{it.filePath?.let{path->File(path).canonicalFile==file.canonicalFile}==true}
        val result=obj("exists" to file.exists(),"bytes" to file.length(),"recoverable" to (pending!=null),"elapsed_seconds" to pending?.elapsedSeconds,"format" to file.extension)
        result.put("job_recovered",RecordingRecovery(context).read(file)!=null)
        File(context.filesDir,"durability-result.json").writeText(result.toString())
        if(InstrumentationRegistry.getArguments().getString("expectDurable")=="true")assertNotNull("Interrupted capture must remain playable",pending)
    }
}
