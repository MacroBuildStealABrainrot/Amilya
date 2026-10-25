package com.woundcopilot.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import java.io.File
import android.graphics.Bitmap
import androidx.core.content.FileProvider

/** Isolated synthetic account; actual Android picker/camera, never a provider request. */
class PhotoLifecycleTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var s:AppState
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val files=mutableListOf<File>()
    @Before fun setup(){
        compose.runOnIdle{s=ViewModelProvider(compose.activity)[AppState::class.java]}
        compose.waitUntil(15000){!s.loading}
        check(s.user==null){"Use an isolated test installation"}
        compose.runOnIdle{
            AppState::class.java.getDeclaredMethod("setSession",JSONObject::class.java).apply{isAccessible=true}.invoke(s,obj("user" to obj("id" to "synthetic-photo-lifecycle","workspace_id" to "synthetic")))
            AppState::class.java.getDeclaredMethod("setCase",JSONObject::class.java).apply{isAccessible=true}.invoke(s,DemoData.caseData());s.screen="photo";s.unlock()
        }
    }
    @After fun cleanup(){
        if(::s.isInitialized){
            compose.runOnIdle{s.startDemo();s.logout()}
            compose.waitUntil(15000){!s.loading}
            s.vault.remove("cached-account");s.vault.remove("pin-owner")
        }
        files.forEach{it.delete()}
    }
    @Test fun actualPickerCancellation(){
        compose.onNodeWithText("Choose an image").performScrollTo().performClick()
        Thread.sleep(1800)
        inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        Thread.sleep(700)
        compose.waitForIdle()
        val legacy=InstrumentationRegistry.getArguments().getString("expectLegacyLock")=="true"
        compose.runOnIdle{assertEquals("Picker cancellation lock state",legacy,s.locked);assertEquals("photo",s.screen)}
    }
    @Test fun actualCameraCancellation(){
        compose.onNodeWithText("Take photograph").performScrollTo().performClick()
        Thread.sleep(1800)
        inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        Thread.sleep(700);compose.waitForIdle()
        compose.runOnIdle{assertFalse(s.locked);assertNull(s.externalCapture.kind)}
    }
    @Test fun actualPickerSurvivesActivityRecreation(){
        compose.onNodeWithText("Choose an image").performScrollTo().performClick();Thread.sleep(1200)
        compose.activity.runOnUiThread{compose.activity.recreate()};Thread.sleep(400)
        inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close();Thread.sleep(700)
        compose.waitForIdle()
        compose.runOnIdle{assertSame(s,ViewModelProvider(compose.activity)[AppState::class.java]);assertFalse(s.locked);assertNull(s.externalCapture.kind)}
    }
    @Test fun actualHomeStillLocks(){
        inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close();Thread.sleep(500)
        inst.uiAutomation.executeShellCommand("am start -n ${inst.targetContext.packageName}/com.woundcopilot.android.MainActivity").close();Thread.sleep(500)
        compose.runOnIdle{assertTrue(s.locked)}
    }
    @Test fun permissionReturnCannotBypassDeviceLock(){
        compose.runOnIdle{
            assertTrue(s.externalCapture.begin(ExternalKind.PERMISSION,"audio","audio"))
            s.externalCapture.background(false,false);assertFalse(s.locked)
            s.externalCapture.deviceLocked();assertTrue(s.locked)
            assertFalse(s.externalCapture.permissionReturned());s.externalCapture.foreground(false);assertTrue(s.locked)
        }
    }
    @Test fun sessionEndDoesNotRecycleAnOutstandingLaunch(){
        compose.runOnIdle{
            assertTrue(s.externalCapture.begin(ExternalKind.PHOTO_PICKER,"photo","photo"))
            s.externalCapture.endSession()
            assertFalse(s.externalCapture.begin(ExternalKind.PHOTO_PICKER,"photo","photo"))
            s.externalCapture.receive(ExternalKind.CAMERA,emptyList())
            assertEquals(ExternalKind.PHOTO_PICKER,s.externalCapture.kind)
            s.externalCapture.receive(ExternalKind.PHOTO_PICKER,emptyList())
            assertTrue(s.locked);assertNull(s.externalCapture.kind)
        }
    }
    @Test fun abandonedResultCannotConsumeNewOperation(){
        compose.runOnIdle{
            assertTrue(s.externalCapture.begin(ExternalKind.PHOTO_PICKER,"photo","photo"))
            val old=s.externalCapture.operationId!!;s.externalCapture.discard()
            assertTrue(s.externalCapture.begin(ExternalKind.PHOTO_PICKER,"photo","photo"))
            val current=s.externalCapture.operationId!!
            s.externalCapture.receive(ExternalKind.PHOTO_PICKER,emptyList(),operationId=old)
            assertEquals(current,s.externalCapture.operationId)
            s.externalCapture.receive(ExternalKind.PHOTO_PICKER,emptyList(),operationId=current)
            assertNull(s.externalCapture.kind)
        }
    }
    @Test fun malformedPhotoAcknowledgementIsNotSuccessfulUpload(){
        for(r in listOf(JSONObject(),obj("case_revision" to 1),obj("case_revision" to 1,"evidence" to obj("id" to "synthetic","kind" to "audio"))))
            assertTrue(runCatching{requireValidPhotoUpload(r)}.exceptionOrNull() is ApiException)
        requireValidPhotoUpload(obj("case_revision" to 1,"evidence" to obj("id" to "synthetic","kind" to "photo")))
    }
    @Test fun timeoutAndProcessRestorationNeverExemptAuthentication(){
        var now=10L
        compose.runOnIdle{
            val flow=ExternalCapture(s,compose.activity,compose.activity.lifecycleScope){now}
            assertTrue(flow.begin(ExternalKind.PHOTO_PICKER,"photo","photo"))
            flow.background(false,false);assertFalse(s.locked)
            now+=ExternalCapture.TRUST_MS+1;flow.receive(ExternalKind.PHOTO_PICKER,emptyList());flow.foreground(false);assertTrue(s.locked)
            flow.clear();s.unlock()
            assertTrue(flow.begin(ExternalKind.CAMERA,"photo","photo"))
            val reconstructed=ExternalCapture(s,compose.activity,compose.activity.lifecycleScope)
            reconstructed.background(false,false);assertTrue(s.locked)
            reconstructed.clear();flow.clear()
        }
    }
    @Test fun duplicateResultIsConsumedOnceAndRetainedWhenLocked(){
        val file=File(File(compose.activity.filesDir,"captures").apply{mkdirs()},"synthetic-${java.util.UUID.randomUUID()}.png");files+=file
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val uri=FileProvider.getUriForFile(compose.activity,BuildConfig.APPLICATION_ID+".files",file)
        val key="capture-${s.user!!.str("id")}-${s.encounterId}-photo"
        val before=File(compose.activity.filesDir,"captures").listFiles()!!.map{it.name}.toSet()
        compose.runOnIdle{
            assertTrue(s.externalCapture.begin(ExternalKind.PHOTO_PICKER,"photo","photo"))
            assertFalse(s.externalCapture.begin(ExternalKind.CAMERA,"photo","photo"))
            s.externalCapture.background(false,false);s.externalCapture.deviceLocked()
            s.externalCapture.receive(ExternalKind.PHOTO_PICKER,listOf(uri));s.externalCapture.receive(ExternalKind.PHOTO_PICKER,listOf(uri))
            s.externalCapture.foreground(false)
        }
        compose.waitUntil(10000){!s.externalCapture.busy}
        val imported=File(s.vault.read(key)!!);files+=imported
        assertTrue(imported.isFile);assertTrue(s.locked)
        assertEquals(1,File(compose.activity.filesDir,"captures").listFiles()!!.count{it.name !in before})
        assertNull(s.externalCapture.kind)
        s.vault.remove(key);s.vault.remove("$key-mime");s.vault.remove("$key-batch")
    }
    @Test fun returnedImageCannotAttachToAnotherEncounter(){
        val file=File(File(compose.activity.filesDir,"captures").apply{mkdirs()},"synthetic-${java.util.UUID.randomUUID()}.png");files+=file
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val uri=FileProvider.getUriForFile(compose.activity,BuildConfig.APPLICATION_ID+".files",file)
        val origin=s.encounterId;val key="capture-${s.user!!.str("id")}-$origin-photo"
        compose.runOnIdle{
            assertTrue(s.externalCapture.begin(ExternalKind.PHOTO_PICKER,"photo","photo"))
            val other=DemoData.caseData();other.getJSONObject("encounter").put("id","synthetic-other-encounter")
            AppState::class.java.getDeclaredMethod("setCase",JSONObject::class.java).apply{isAccessible=true}.invoke(s,other)
            s.externalCapture.receive(ExternalKind.PHOTO_PICKER,listOf(uri))
        }
        compose.waitUntil(10000){!s.externalCapture.busy}
        assertEquals("synthetic-other-encounter",s.encounterId);assertFalse(s.loading)
        val imported=File(s.vault.read(key)!!);files+=imported;assertTrue(imported.isFile)
        assertNull(s.vault.read("capture-${s.user!!.str("id")}-${s.encounterId}-photo"))
        s.vault.remove(key);s.vault.remove("$key-mime")
    }
    @Test fun rejectedCameraFileKeepsRecoveryReference(){
        val file=File(File(compose.activity.filesDir,"captures").apply{mkdirs()},"synthetic-${java.util.UUID.randomUUID()}.jpg");files+=file
        java.io.RandomAccessFile(file,"rw").use{it.setLength(13L*1024*1024)}
        val key="capture-${s.user!!.str("id")}-${s.encounterId}-photo"
        compose.runOnIdle{assertTrue(s.externalCapture.begin(ExternalKind.CAMERA,"photo","photo",file));s.externalCapture.receive(ExternalKind.CAMERA,emptyList(),true)}
        compose.waitUntil(10000){!s.externalCapture.busy}
        assertEquals(file.path,s.vault.read(key));assertTrue(file.isFile);assertNotNull(s.error);assertFalse(s.locked)
        s.vault.remove(key);s.vault.remove("$key-mime")
    }
    @Test fun actualDeviceLockDuringPickerRequiresPin(){
        compose.onNodeWithText("Choose an image").performScrollTo().performClick();Thread.sleep(1000)
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText()}
        shell("input keyevent KEYCODE_SLEEP");Thread.sleep(800)
        assertFalse("Device did not sleep",compose.activity.getSystemService(android.os.PowerManager::class.java).isInteractive)
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");Thread.sleep(500)
        shell("input keyevent KEYCODE_BACK");Thread.sleep(500)
        compose.runOnIdle{assertTrue(s.locked)}
    }
    @Test fun seededSystemPickerSelection(){
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("nativeSelection")=="true")
        val values=android.content.ContentValues().apply{put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"synthetic-picker-${java.util.UUID.randomUUID()}.png");put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")}
        val resolver=compose.activity.contentResolver;val uri=resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        try{
            val bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.CYAN)
            resolver.openOutputStream(uri)!!.use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            compose.runOnIdle{AppState::class.java.getDeclaredField("api").apply{isAccessible=true}.set(s,Api(s.vault,"http://127.0.0.1:18766/api/v1"))}
            val key="capture-${s.user!!.str("id")}-${s.encounterId}-photo"
            compose.onNodeWithText("Choose an image").performScrollTo().performClick()
            Thread.sleep(1500)
            clickSystem{it.contentDescription?.toString()?.startsWith("Photo taken on")==true}
            clickSystem{it.text?.toString()=="Done"}
            compose.waitUntil(15000){s.vault.read(key)?.let{File(it).isFile}==true && !s.externalCapture.busy}
            val imported=File(s.vault.read(key)!!);files+=imported
            compose.runOnIdle{assertFalse(s.locked)}
            compose.onNodeWithContentDescription("Captured wound photograph").assertExists()
            s.vault.remove(key);s.vault.remove("$key-mime")
        }finally{resolver.delete(uri,null,null);inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()}
    }
    @Test fun actualCameraCaptureAndAccept(){
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("nativeCamera")=="true")
        compose.runOnIdle{AppState::class.java.getDeclaredField("api").apply{isAccessible=true}.set(s,Api(s.vault,"http://127.0.0.1:18766/api/v1"))}
        val key="capture-${s.user!!.str("id")}-${s.encounterId}-photo"
        compose.onNodeWithText("Take photograph").performScrollTo().performClick();Thread.sleep(1500)
        clickSystem{it.viewIdResourceName?.endsWith("shutter_button")==true || it.contentDescription?.toString()=="Shutter"}
        Thread.sleep(700)
        clickSystem{it.contentDescription?.toString() in listOf("Done","Accept","OK") || it.viewIdResourceName?.endsWith("done_button")==true}
        compose.waitUntil(15000){s.vault.read(key)?.let{File(it).isFile}==true && !s.externalCapture.busy}
        val captured=File(s.vault.read(key)!!);files+=captured;assertTrue(captured.length()>128)
        compose.runOnIdle{assertFalse(s.locked)}
        compose.waitUntil(10000){compose.onAllNodesWithContentDescription("Captured wound photograph").fetchSemanticsNodes().isNotEmpty()}
        s.vault.remove(key);s.vault.remove("$key-mime")
    }
    private fun clickSystem(predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean){
        fun find(n:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo?{
            n?:return null;if(predicate(n))return n
            for(i in 0 until n.childCount)find(n.getChild(i))?.let{return it};return null
        }
        var node:android.view.accessibility.AccessibilityNodeInfo?=null
        repeat(150){if(node==null){node=find(inst.uiAutomation.rootInActiveWindow);if(node==null)Thread.sleep(100)}}
        fun describe(n:android.view.accessibility.AccessibilityNodeInfo?):String{n?:return "";return "${n.viewIdResourceName} ${n.text} ${n.contentDescription}\n"+(0 until n.childCount).joinToString(""){describe(n.getChild(it))}}
        var target=node?:throw AssertionError("System control unavailable: "+describe(inst.uiAutomation.rootInActiveWindow))
        while(!target.isClickable && target.parent!=null)target=target.parent
        assertTrue(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(300)
    }
}
