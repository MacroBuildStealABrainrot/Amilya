package com.woundcopilot.android

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class CorePrioritiesTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun productionCapturePolicyProtectsClinicalAndUnknownScreens(){
        for(route in listOf("home","saved","editor","record","photo_assessment","consultant","unknown"))
            assertTrue(route,protectScreen(false,false,false,route,false))
        for(route in listOf("welcome","login","signup","reconnect","account")){
            assertFalse(route,protectScreen(false,false,false,route,false))
            assertTrue(protectScreen(false,false,false,route,true))
            assertTrue(protectScreen(false,false,true,route,false))
        }
        assertFalse(protectScreen(true,false,true,"editor",true))
        assertFalse(protectScreen(false,true,false,"editor",true))
    }
    @Test fun clinicalScreenBlocksCaptureThenPublicScreenRestoresIt(){
        assumeTrue(!BuildConfig.DEBUG && !BuildConfig.ALLOW_SCREEN_CAPTURE)
        lateinit var s:AppState
        compose.runOnIdle{s=ViewModelProvider(compose.activity)[AppState::class.java]}
        assumeTrue(s.user==null && !s.demo)
        val original=s.screen
        try{
            compose.runOnIdle{s.screen="home";s.notice="Synthetic clinical observation"}
            compose.waitForIdle()
            compose.runOnIdle{assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)}
            val failure=runCatching{runBlocking{withContext(Dispatchers.Main){captureScreen(compose.activity)}}}.exceptionOrNull()
            assertNotNull("Protected capture must be rejected",failure)
            compose.runOnIdle{s.notice=null;s.screen="welcome"}
            compose.waitForIdle()
            compose.runOnIdle{assertEquals(0,compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)}
        }finally{compose.runOnIdle{s.notice=null;s.screen=original}}
    }
    @Test fun fourthPinDigitUnlocksWithoutConfirmation(){
        lateinit var s:AppState
        compose.runOnIdle{s=ViewModelProvider(compose.activity)[AppState::class.java]}
        assumeTrue(s.user==null && !s.pinLock.configured)
        val preference=s.vault.read("biometric-auto")
        try{
            s.vault.write("biometric-auto","false")
            s.pinLock.set("5729")
            compose.runOnIdle{s.locked=true}
            compose.onNodeWithText("PIN").performTextInput("572")
            compose.runOnIdle{assertTrue(s.locked)}
            compose.onNodeWithText("PIN").performTextInput("9")
            compose.waitUntil(10_000){!s.locked}
        }finally{
            s.pinLock.clear()
            if(preference==null)s.vault.remove("biometric-auto")else s.vault.write("biometric-auto",preference)
            compose.runOnIdle{s.locked=false}
        }
    }
    @Test fun voiceExportRequiresAnExplicitCommand(){
        assertEquals("export",parseVoiceCommand("export report",activelyListening=true)?.action)
        assertNull(parseVoiceCommand("patient asked us to export report",activelyListening=true))
        assertNull(parseVoiceCommand("export report"))
    }
}
