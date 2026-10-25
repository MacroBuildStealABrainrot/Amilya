package com.woundcopilot.android

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class RecordingCheckpointTest {
    @Test fun pendingSourceSurvivesCleanupButExpiresAfterRecoveryWindow(){
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"checkpoint-test-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object:ContextWrapper(base){
            override fun getFilesDir()=File(root,"files").apply{mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply{mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
        }
        try{
            val file=File(File(context.filesDir,"recordings").apply{mkdirs()},"synthetic.m4a").apply{writeBytes(ByteArray(256))}
            val now=System.currentTimeMillis()
            assertTrue(file.setLastModified(now-8*24*60*60*1000L))
            val first=RecordingRecovery(context)
            first.remember(file,"synthetic-owner",obj("encounter" to obj("id" to "synthetic-case")),"nursing")
            first.stage(file,"TRANSCRIBED");RecordingRecovery(context).stage(file,"FAILED_RETRYABLE")
            assertEquals("TRANSCRIBED",first.read(file)!!.str("resume_from"))
            LocalRetention.clean(context,now)
            assertTrue(file.isFile)
            assertEquals(1,RecordingRecovery(context).list("synthetic-owner").size)
            assertTrue(RecordingRecovery(context).list("other-owner").isEmpty())
            LocalRetention.clean(context,now+LocalRetention.RECOVERY_AGE+60_000)
            assertFalse(file.exists());assertNull(first.read(file))
        }finally{root.walkBottomUp().forEach{it.delete()}}
    }
    @Test fun metricsNeverExposeNonOpaqueIdentifiers(){
        val sentinel="PRIVATE_SYNTHETIC_PATIENT_AND_KEY"
        Performance.record("audio_preparation",Performance.now(),job=sentinel)
        assertFalse(Performance.snapshot().toString().contains(sentinel))
    }
    @Test fun rebuiltSegmentsKeepIdenticalRequestBytes(){
        val inst=InstrumentationRegistry.getInstrumentation()
        val root=File(inst.targetContext.cacheDir,"segment-replay-test-${UUID.randomUUID()}").apply{mkdirs()}
        try{
            val source=File(root,"source.m4a")
            inst.context.assets.open("reliability-5-minute.m4a").use{input->source.outputStream().use{input.copyTo(it)}}
            val before=splitAacRecording(source,File(root,"before"))
            Thread.sleep(1200)
            val after=splitAacRecording(source,File(root,"after"))
            assertEquals(before.size,after.size)
            before.zip(after).forEach{(first,second)->assertArrayEquals("Rebuilt segments must replay the same request hash",first.readBytes(),second.readBytes())}
            fun encodedHash(files:List<File>):ByteArray{
                val hash=java.security.MessageDigest.getInstance("SHA-256")
                files.forEach{file->
                    val extractor=android.media.MediaExtractor()
                    try{
                        extractor.setDataSource(file.path);extractor.selectTrack(0)
                        val buffer=java.nio.ByteBuffer.allocate(1024*1024)
                        while(extractor.sampleTrackIndex>=0){buffer.clear();val size=extractor.readSampleData(buffer,0);if(size<0)break;hash.update(buffer.array(),0,size);extractor.advance()}
                    }finally{extractor.release()}
                }
                return hash.digest()
            }
            assertArrayEquals("Container normalization must not change encoded audio",encodedHash(listOf(source)),encodedHash(after))
        }finally{root.walkBottomUp().forEach{it.delete()}}
    }
}
