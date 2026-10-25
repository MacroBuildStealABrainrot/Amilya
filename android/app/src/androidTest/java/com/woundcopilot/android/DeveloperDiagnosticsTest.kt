package com.woundcopilot.android

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID

class DeveloperDiagnosticsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun signedTestBuildAllowsCaptureAcrossLockAndLifecycle(){
        assumeTrue(BuildConfig.ALLOW_SCREEN_CAPTURE)
        lateinit var s:AppState
        compose.runOnIdle{s=ViewModelProvider(compose.activity)[AppState::class.java]}
        assumeTrue(s.user==null && !s.demo)
        try{
            for(route in listOf("home","developer_diagnostics","login")){
                compose.runOnIdle{s.screen=route}
                compose.waitForIdle()
                compose.runOnIdle{assertEquals(0,compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)}
            }
            compose.runOnIdle{s.locked=true}
            compose.waitForIdle()
            compose.runOnIdle{assertEquals(0,compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)}
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle{assertEquals(0,compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)}
        }finally{compose.runOnIdle{s.locked=false;s.screen="welcome"}}
    }
    @Test fun normalAccountCannotOpenDeveloperScreen(){
        lateinit var s:AppState
        compose.runOnIdle{s=ViewModelProvider(compose.activity)[AppState::class.java]}
        assumeTrue(s.user==null)
        try{
            compose.runOnIdle{s.screen="developer_diagnostics"}
            compose.onNodeWithText("Administrator access required.").assertIsDisplayed()
            compose.onNodeWithText("Filter task, date or request ID").assertDoesNotExist()
        }finally{compose.runOnIdle{s.screen="welcome"}}
    }
    @Test fun technicalOutboxSurvivesVaultReconstructionWithoutSourceContent(){
        val context=compose.activity.applicationContext;val vault=Vault(context)
        val owner="diagnostic-test-${UUID.randomUUID()}";val encounter=UUID.randomUUID().toString()
        val file=File(context.cacheDir,"diagnostic-test-${UUID.randomUUID()}.m4a")
        val sentinel="SYNTHETIC_CLINICAL_SENTINEL"
        try{
            file.writeText(sentinel.repeat(20))
            DeveloperDiagnostics.bind(vault,owner,encounter,file)
            DeveloperDiagnostics.emit("local_save")
            compose.waitUntil(5000){vault.read("diagnostic-outbox-$owner")!=null}
            val restored=Vault(context).read("diagnostic-outbox-$owner")!!
            assertFalse(restored.contains(sentinel));assertFalse(restored.contains(file.path))
            val event=org.json.JSONArray(restored).getJSONObject(0)
            assertTrue(event.getBoolean("recording_exists"));assertEquals("app_private",event.getString("storage"))
            assertEquals("local_save",event.getString("stage"))
        }finally{DeveloperDiagnostics.clear();file.delete();vault.remove("diagnostic-outbox-$owner");vault.remove("diagnostic-job-$owner-$encounter")}
    }
    @Test fun holdSyntheticScreenForSystemRecording(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureEvidence")=="true")
        assumeTrue(BuildConfig.ALLOW_SCREEN_CAPTURE)
        lateinit var s:AppState
        compose.runOnIdle{s=ViewModelProvider(compose.activity)[AppState::class.java]}
        assumeTrue(s.user==null)
        try{
            compose.runOnIdle{s.screen="home";s.notice="SYNTHETIC SCREEN CAPTURE TEST — no patient data"}
            compose.waitForIdle()
            println("CAPTURE_READY")
            Thread.sleep(15000)
        }finally{compose.runOnIdle{s.notice=null;s.screen="welcome"}}
    }
}
