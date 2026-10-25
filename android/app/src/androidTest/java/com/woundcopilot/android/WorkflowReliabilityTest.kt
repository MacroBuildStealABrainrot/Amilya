package com.woundcopilot.android
import android.media.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.time.ZoneId
import java.util.Locale
import java.io.File
import java.nio.ByteBuffer

class WorkflowReliabilityTest{
    @Test fun deviceTimeUsesInstantAndDst(){
        val toronto=ZoneId.of("America/Toronto")
        assertEquals("Jul 1, 2026 · 08:00",formatLocalTime("2026-07-01T12:00:00Z",toronto,true,Locale.US))
        assertEquals("Jan 1, 2026 · 7:00 AM",formatLocalTime("2026-01-01T12:00:00Z",toronto,false,Locale.US))
        assertEquals("Dec 31, 2025 · 19:30",formatLocalTime("2026-01-01T00:30:00Z",toronto,true,Locale.US))
        assertEquals("Mar 8, 2026 · 03:01",formatLocalTime("2026-03-08T07:01:00Z",toronto,true,Locale.US))
        assertEquals("Mar 8, 2026 · 01:59",formatLocalTime("2026-03-08T06:59:00Z",toronto,true,Locale.US))
        assertEquals("Jan 1, 2026 · 21:00",formatLocalTime("2026-01-01T12:00:00Z",ZoneId.of("Asia/Tokyo"),true,Locale.US))
        assertEquals(storedInstant("2026-01-01T12:00:00Z"),storedInstant("2026-01-01T12:00:00"))
    }
    @Test fun dictationCannotBecomeACommand(){
        assertNull(parseVoiceCommand("remove no fever"))
        assertNull(parseVoiceCommand("the patient says generate report"))
        assertEquals("generate",parseVoiceCommand("Hey WoundCoPilot generate report")?.action)
        assertEquals("generate",parseVoiceCommand("Generate report",activelyListening=true)?.action)
        assertNull(parseVoiceCommand("delete all saved work",activelyListening=true))
        assertNull(parseVoiceCommand("send this report to someone",activelyListening=true))
    }
    @Test fun consequentialEditsRemainExactProposals(){
        val before="Right heel. Dose 5 mg. No fever."
        assertEquals("Left heel. Dose 5 mg. No fever.",proposedVoiceEdit(before,VoiceCommand("replace","Right heel","Left heel")))
        assertEquals("Right heel. Dose 10 mg. No fever.",proposedVoiceEdit(before,VoiceCommand("replace","5 mg","10 mg")))
        assertEquals("Right heel. Dose 5 mg. Fever present.",proposedVoiceEdit(before,VoiceCommand("replace","No fever","Fever present")))
        assertNull(proposedVoiceEdit("Right heel and right heel",VoiceCommand("replace","right heel","left heel")))
        assertNull(proposedVoiceEdit(before,VoiceCommand("replace","left heel","right heel")))
        assertEquals("Right heel. Dose 5 mg. No fever.",before)
    }
    @Test fun longRecordingsSplitWithoutTranscodingAtAllRequestedDurations(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val testContext=InstrumentationRegistry.getInstrumentation().context
        val root=File(context.cacheDir,"audio-check-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        val source=File(root,"fixture.m4a")
        testContext.assets.open("synthetic-60-minute.m4a").use{input->source.outputStream().use{input.copyTo(it)}}
        try{
            for(minutes in listOf(5,15,25,30,60)){
                val selected=File(root,"selected-$minutes.m4a")
                val extractor=MediaExtractor();var muxer:MediaMuxer?=null
                try{
                    extractor.setDataSource(source.path);extractor.selectTrack(0)
                    muxer=MediaMuxer(selected.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    val track=muxer.addTrack(extractor.getTrackFormat(0));muxer.start();val data=ByteBuffer.allocate(1024*1024)
                    val origin=extractor.sampleTime
                    while(extractor.sampleTrackIndex>=0 && extractor.sampleTime-origin<minutes*60_000_000L){
                        data.clear();val size=extractor.readSampleData(data,0);if(size<0)break
                        muxer.writeSampleData(track,data,MediaCodec.BufferInfo().apply{set(0,size,extractor.sampleTime-origin,MediaCodec.BUFFER_FLAG_KEY_FRAME)});extractor.advance()
                    };muxer.stop()
                }finally{muxer?.release();extractor.release()}
                val pieces=splitAacRecording(selected,File(root,"parts-$minutes"))
                var total=0L
                for(piece in pieces){
                    val retriever=MediaMetadataRetriever()
                    try{retriever.setDataSource(piece.path);val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong();assertTrue(duration<=120100);assertTrue(duration>0);total+=duration}finally{retriever.release()}
                }
                assertTrue("$minutes minute coverage: $total",kotlin.math.abs(total-minutes*60_000L)<1000)
                assertTrue(pieces.size>=kotlin.math.ceil(minutes/2.0).toInt())
            }
        }finally{root.walkBottomUp().forEach{it.delete()}}
    }
}
