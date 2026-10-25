package com.woundcopilot.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID

/** MediaMuxer adds wall-clock creation/modification times. Normalize only those
 * generated container fields so a rebuilt segment has the same idempotency hash.
 * Encoded samples, sample timestamps, track durations and codec data are untouched. */
private fun stableSegmentMetadata(file:File){
    RandomAccessFile(file,"rw").use{data->
        fun boxes(start:Long,end:Long,depth:Int){
            require(depth<=6)
            var position=start
            while(position<end){
                require(end-position>=8);data.seek(position)
                var size=data.readInt().toLong() and 0xffffffffL
                val type=ByteArray(4).also{data.readFully(it)}.toString(Charsets.US_ASCII)
                var header=8L
                if(size==1L){require(end-position>=16);size=data.readLong();header=16}
                if(size==0L)size=end-position
                require(size>=header && size<=end-position)
                val payload=position+header;val limit=position+size
                if(type in listOf("moov","trak","mdia"))boxes(payload,limit,depth+1)
                else if(type in listOf("mvhd","tkhd","mdhd")){
                    require(limit-payload>=12);data.seek(payload)
                    val version=data.readUnsignedByte();require(version==0 || version==1)
                    val length=if(version==1)16 else 8
                    require(payload+4+length<=limit);data.seek(payload+4);data.write(ByteArray(length))
                }
                position=limit
            }
        }
        boxes(0,data.length(),0)
    }
}

/** Split at encoded sample boundaries. No decoding/re-encoding or word rewriting. */
internal fun splitAacRecording(source:File,directory:File):List<File>{
    val extractor=MediaExtractor()
    val files=mutableListOf<File>()
    var muxer:MediaMuxer?=null
    try{
        extractor.setDataSource(source.absolutePath)
        val track=(0 until extractor.trackCount).firstOrNull{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm"}
            ?: error("Long recordings must be AAC/M4A. Your original is preserved.")
        val format=extractor.getTrackFormat(track);extractor.selectTrack(track)
        require(directory.exists()||directory.mkdirs())
        val buffer=ByteBuffer.allocate(1024*1024)
        var start=0L;var target=-1;var first=true
        while(extractor.sampleTrackIndex>=0){
            val time=extractor.sampleTime
            if(first||time-start>=120_000_000){
                muxer?.stop();muxer?.release();muxer=null
                val output=File(directory,"segment-${UUID.randomUUID()}.m4a")
                files+=output
                muxer=MediaMuxer(output.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                target=muxer.addTrack(format);muxer.start();start=time;first=false
            }
            buffer.clear();val size=extractor.readSampleData(buffer,0)
            require(size in 1..buffer.capacity()){ "Audio sample is unsupported. The original recording is preserved." }
            val info=MediaCodec.BufferInfo().apply{set(0,size,time-start,if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)}
            muxer!!.writeSampleData(target,buffer,info);extractor.advance()
        }
        muxer?.stop();muxer?.release();muxer=null
        require(files.isNotEmpty()&&files.size<=32)
        files.forEach(::stableSegmentMetadata)
        return files
    }catch(e:Exception){files.forEach{it.delete()};throw e}
    finally{runCatching{muxer?.release()};extractor.release()}
}

/** Where one recording sits in a visit recorded in several parts. [id] links the parts; it holds no patient details. */
data class VisitPosition(val part:Int,val parts:Int,val id:String)

class AudioChunks(private val api:Api,private val vault:Vault){
    /** Sandbox encrypted recordings: the parts are built in memory from the encrypted pieces and sent straight from memory. */
    /** Fields sent when the recording is one part of a longer visit, so the server labels the transcript "part 1 of 2" and keeps the parts together. */
    private fun completion(state:JSONObject,count:Int,visit:VisitPosition?)=obj("job_id" to state.str("job_id"),"count" to count).also{body->
        visit?.takeIf{it.parts>1}?.let{body.put("visit_part",it.part);body.put("visit_parts",it.parts);body.put("visit_id",it.id)}
    }

    private suspend fun transcribeSealed(encounter:String,source:File,sealed:SealedRecordingHandle,key:String,visit:VisitPosition?,stage:(String)->Unit):JSONObject{
        val checkpoint="audio-batch-$key"
        var state=vault.read(checkpoint)?.let{JSONObject(it)}
        val count=withContext(Dispatchers.IO){sealed.partCount}
        require(count in 1..32){"The recording cannot be sent in 32 parts or fewer. It is kept on this device."}
        if(state==null){
            stage("Preparing recording")
            // "files" names the recording itself (there are no temporary copies), so clean-up and recovery rules keep it until the report is done.
            state=obj("job_id" to UUID.randomUUID().toString(),"files" to JSONArray(listOf(source.absolutePath)),"parts" to count,"completed" to 0,"sealed" to true)
            vault.write(checkpoint,state.toString())
        }
        state.optJSONObject("result")?.let{return it}
        check(state.optInt("parts")==count){"The saved recording changed. Review the original before retrying."}
        for(index in state.optInt("completed") until count){
            stage("Transcribing")
            val bytes=withContext(Dispatchers.IO){sealed.part(index)}
            try{
                val overlap=withContext(Dispatchers.IO){sealed.overlapMillis(index)}
                api.multipartBytes("/encounters/$encounter/audio-parts","segment-$index.m4a",bytes,"audio/mp4",
                    mapOf("job_id" to state.str("job_id"),"index" to index.toString(),"count" to count.toString(),"consent" to "true")+
                        (if(overlap>0)mapOf("overlap_ms" to overlap.toString()) else emptyMap()),"${state.str("job_id")}-$index")
            }finally{bytes.fill(0)}
            state.put("completed",index+1);vault.write(checkpoint,state.toString())
        }
        stage("Preparing transcript")
        val result=api.call("/encounters/$encounter/audio-complete","POST",completion(state,count,visit),"${state.str("job_id")}-complete")as JSONObject
        state.put("result",result);vault.write(checkpoint,state.toString())
        return result
    }

    suspend fun transcribe(encounter:String,source:File,key:String,visit:VisitPosition?=null,stage:(String)->Unit):JSONObject{
        crashSafeBackend()?.sealedRecording(source)?.let{sealed->return sealed.use{transcribeSealed(encounter,source,it,key,visit,stage)}}
        val checkpoint="audio-batch-$key"
        var state=vault.read(checkpoint)?.let{JSONObject(it)}
        if(state==null){
            stage("Preparing recording")
            val prepared=Performance.now()
            val files=withContext(Dispatchers.IO){splitAacRecording(source,File(source.parentFile,"segments"))}
            Performance.record("audio_preparation",prepared)
            state=obj("job_id" to UUID.randomUUID().toString(),"files" to JSONArray(files.map{it.absolutePath}),"completed" to 0,"stable_segments" to true)
            vault.write(checkpoint,state.toString())
        }
        state.optJSONObject("result")?.let{return it}
        var files=state.getJSONArray("files");val count=files.length()
        if((state.optInt("completed") until count).any{!File(files.getString(it)).isFile}){
            check(state.optBoolean("stable_segments")){"An older recording's temporary segments are unavailable. The original audio remains on this device; open it from Sources before starting a new transcription."}
            stage("Preparing recording")
            val rebuilt=withContext(Dispatchers.IO){splitAacRecording(source,File(source.parentFile,"segments"))}
            if(rebuilt.size!=count){rebuilt.forEach{it.delete()};error("The saved recording changed. Review the original before retrying.")}
            val old=files
            files=JSONArray(rebuilt.map{it.absolutePath});state.put("files",files);vault.write(checkpoint,state.toString())
            for(index in 0 until old.length())File(old.getString(index)).delete()
        }
        for(index in state.optInt("completed") until count){
            stage("Transcribing")
            api.multipart("/encounters/$encounter/audio-parts",File(files.getString(index)),"audio/mp4",
                mapOf("job_id" to state.str("job_id"),"index" to index.toString(),"count" to count.toString(),"consent" to "true"),"${state.str("job_id")}-$index")
            state.put("completed",index+1);vault.write(checkpoint,state.toString())
        }
        stage("Preparing transcript")
        val result=api.call("/encounters/$encounter/audio-complete","POST",completion(state,count,visit),"${state.str("job_id")}-complete")as JSONObject
        state.put("result",result);vault.write(checkpoint,state.toString())
        for(index in 0 until count)File(files.getString(index)).delete()
        return result
    }
}
