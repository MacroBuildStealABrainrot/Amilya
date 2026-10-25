package com.woundcopilot.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Temporary, encrypted processing checkpoint. Recording completion is not report completion. */
class RecordingRecovery(private val context:Context){
    companion object { internal val storageLock=Any() }
    private val vault=Vault(context)
    private val root=File(context.filesDir,"recordings").canonicalFile
    fun key(file:File)="recording-job-${file.name}"
    fun read(file:File):JSONObject?=synchronized(storageLock){vault.read(key(file))?.let{runCatching{JSONObject(it)}.getOrNull()}}
    fun remember(file:File,user:String,case:JSONObject,template:String,mime:String="audio/mp4"):JSONObject=synchronized(storageLock){
        require(file.canonicalPath.startsWith(root.parentFile!!.path+File.separator))
        val job=read(file)?:obj("id" to UUID.randomUUID().toString(),"owner" to user,"file" to file.canonicalPath,"mime" to mime,"phase" to "RECORDED_LOCALLY","resume_from" to "RECORDED_LOCALLY")
        check(job.str("owner")==user)
        job.put("case",JSONObject(case.toString()));job.put("template",template)
        write(file,job);job
    }
    fun stage(file:File,phase:String,case:JSONObject?=null,report:String?=null)=synchronized(storageLock){
        val job=read(file)?:return@synchronized null
        if(phase!="FAILED_RETRYABLE")job.put("resume_from",phase)
        job.put("phase",phase)
        case?.let{job.put("case",JSONObject(it.toString()))}
        report?.let{job.put("report",it)}
        write(file,job)
        JSONObject(job.toString())
    }
    private fun write(file:File,job:JSONObject){job.put("updated_at",System.currentTimeMillis());vault.write(key(file),job.toString());WearCheckpointPublisher.changed(context.applicationContext)}
    fun list(user:String)=synchronized(storageLock){vault.names().filter{it.startsWith("recording-job-")}.mapNotNull{key->
        vault.read(key)?.let{runCatching{JSONObject(it)}.getOrNull()}
    }.filter{it.str("owner")==user && it.str("phase")!="COMPLETED" && (BuildConfig.RETAIN_UNRESOLVED || System.currentTimeMillis()-it.optLong("updated_at")<LocalRetention.RECOVERY_AGE) && File(it.str("file")).isFile}.sortedByDescending{it.optLong("updated_at")}}
    fun complete(encounter:String,user:String)=synchronized(storageLock){list(user).filter{it.optJSONObject("case")?.optJSONObject("encounter")?.str("id")==encounter}.forEach{job->
        val file=File(job.str("file"));if(file.canonicalFile.parentFile==root)RecordingService.clearPending(context,file.path)
        vault.names().filter{it.startsWith("capture-") && vault.read(it)==file.path}.forEach{capture->
            vault.remove(capture);vault.remove("$capture-mime");vault.remove("$capture-review")
        }
        vault.remove(key(file))
        vault.remove("recording-context-$user:$encounter")
    }}
}
