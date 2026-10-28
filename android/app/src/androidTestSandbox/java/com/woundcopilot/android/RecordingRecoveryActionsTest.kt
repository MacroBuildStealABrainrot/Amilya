package com.woundcopilot.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingRecoveryActionsTest {
    @Test fun discardIsOwnerScopedAndDeletesOnlyTheLocalRecoveryAudio() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=RecordingService.recordingsDirectory(context)
        val file=File(directory,"recovery-test-${UUID.randomUUID()}.m4a")
        file.writeBytes(byteArrayOf(1,2,3))
        val recovery=RecordingRecovery(context)
        val job=recovery.remember(file,"synthetic-owner",JSONObject("""{"encounter":{"id":"synthetic-encounter"}}"""),"nursing")

        assertFalse(recovery.discard(job.str("id"),"different-owner"))
        assertTrue("Wrong owner must not delete local audio",file.isFile)
        assertTrue("Wrong owner must not remove recovery state",recovery.read(file)!=null)

        assertTrue(recovery.discard(job.str("id"),"synthetic-owner"))
        assertFalse("Confirmed owner discard removes local audio",file.exists())
        assertTrue("Confirmed owner discard removes recovery state",recovery.read(file)==null)
    }
}
