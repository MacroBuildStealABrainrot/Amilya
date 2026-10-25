package com.woundcopilot.android

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhoneSharingTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun releaseWindowAllowsSystemCapture(){
        compose.runOnIdle{assertEquals(0,compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)}
    }
    @Test fun screenCaptureCreatesReadablePng(){
        compose.waitForIdle()
        val uri=runBlocking{withContext(Dispatchers.Main){captureScreen(compose.activity)}}
        val resolver=compose.activity.contentResolver
        val bitmap=resolver.openInputStream(uri)!!.use{BitmapFactory.decodeStream(it)}
        assertNotNull(bitmap);assertTrue(bitmap.width>0);assertTrue(bitmap.height>0)
        bitmap.recycle()
    }
    @Test fun messagePreservesEntireReportAndUsesOneRecipient(){
        val report="Left heel. No fever. Morphine 2.5 mg was discussed, not administered.\n".repeat(200)
        val intent=reportMessageIntent("+1 (416) 555-0100",report)
        assertEquals(Intent.ACTION_SENDTO,intent.action)
        assertEquals("smsto",intent.data!!.scheme)
        assertEquals("+14165550100",intent.data!!.schemeSpecificPart)
        assertEquals(report,intent.getStringExtra("sms_body"))
        for(bad in listOf("", "123", "4165550100;911", "4165550100?body=x", "++14165550100", "someone@example.com"))assertNull(phoneRecipient(bad))
    }
    @Test fun selectedMediaSharesWithTemporaryReadGrant(){
        val uri=Uri.parse("content://synthetic.provider/capture/one")
        for(mime in listOf("image/png","video/mp4")){
            val intent=mediaShareIntent(uri,mime)
            assertEquals(Intent.ACTION_SEND,intent.action);assertEquals(mime,intent.type)
            assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            assertEquals(0,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}
