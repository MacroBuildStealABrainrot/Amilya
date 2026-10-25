package com.woundcopilot.android
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

class LocalSecurityTest{
    @Test fun encryptedPinSurvivesReconstructionAndThrottlesGuesses(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context)
        assumeTrue("Use an isolated installation without an app PIN",vault.read("app-pin")==null)
        val pin=PinLock(vault)
        try{
            pin.set("5729")
            assertTrue(PinLock(Vault(context)).verify("5729"))
            repeat(5){assertFalse(pin.verify("0000"))}
            assertTrue(PinLock(Vault(context)).remainingSeconds()>0)
            assertFalse(pin.verify("5729"))
            val stored=File(context.noBackupFilesDir,"vault/app-pin").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(stored.contains("5729"))
        }finally{pin.clear()}
    }
    @Test fun cleanupDeletesExpiredCopiesAndPreservesPendingRecoveryAndCredentials(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val vault=Vault(context)
        assumeTrue("Use an isolated installation without an account",vault.read("session")==null)
        val nonce=UUID.randomUUID().toString();val root=File(context.filesDir,"captures").apply{mkdirs()}
        val old=File(root,"test-old-$nonce.jpg").apply{writeText("synthetic");setLastModified(System.currentTimeMillis()-25*60*60*1000L)}
        val pending=File(root,"test-pending-$nonce.jpg").apply{writeText("synthetic");setLastModified(System.currentTimeMillis()-25*60*60*1000L)}
        val captureKey="capture-$nonce";val draft="draft-$nonce";val credential="session-retention-$nonce"
        try{
            vault.write(captureKey,pending.path);vault.write(draft,"Synthetic old recovery");vault.write(credential,"Synthetic credential marker")
            for(key in listOf(draft,credential))File(context.noBackupFilesDir,"vault/$key").setLastModified(System.currentTimeMillis()-8*24*60*60*1000L)
            LocalRetention.clean(context)
            assertFalse(old.exists());assertTrue(pending.exists());assertNull(vault.read(draft));assertNotNull(vault.read(credential))
        }finally{old.delete();pending.delete();listOf(captureKey,draft,credential).forEach(vault::remove)}
    }
}
