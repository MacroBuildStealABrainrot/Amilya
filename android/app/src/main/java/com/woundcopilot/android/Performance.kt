package com.woundcopilot.android
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID

/** Bounded, content-free diagnostics. No URLs, account IDs, filenames or exception messages. */
object Performance{
    private val rows=ArrayDeque<JSONObject>()
    private val starts=mutableMapOf<String,Long>()
    fun now()=SystemClock.elapsedRealtimeNanos()
    @Synchronized fun start(stage:String,job:String="session"){starts["$stage:$job"]=now()}
    @Synchronized fun end(stage:String,job:String="session",status:String="ok"){
        starts.remove("$stage:$job")?.let{record(stage,it,status,job)}
    }
    @Synchronized fun record(stage:String,start:Long,status:String="ok",job:String="session",requestId:String?=null){
        runCatching{DeveloperDiagnostics.record(stage,start,status,job,requestId)}
        require(stage.matches(Regex("[a-z_]+")) && status.matches(Regex("[A-Za-z_]+")))
        val opaque=if(job=="session" || runCatching{UUID.fromString(job)}.isSuccess)job else "session"
        rows.addLast(obj("stage" to stage,"duration_ms" to (now()-start)/1_000_000.0,"status" to status,"job" to opaque))
        while(rows.size>1000)rows.removeFirst()
    }
    @Synchronized fun snapshot()=org.json.JSONArray(rows.map{JSONObject(it.toString())})
}
