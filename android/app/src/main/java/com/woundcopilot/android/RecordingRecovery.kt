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
    fun discard(jobId:String,user:String):Boolean=synchronized(storageLock){
        val key=vault.names().firstOrNull{candidate->
            if(!candidate.startsWith("recording-job-"))false
            else vault.read(candidate)?.let{raw->
                runCatching{JSONObject(raw)}.getOrNull()?.let{it.str("id")==jobId && it.str("owner")==user}==true
            }?:false
        }?:return@synchronized false
        val raw=vault.read(key)?:return@synchronized false
        val job=runCatching{JSONObject(raw)}.getOrNull()?:return@synchronized false
        val file=runCatching{File(job.str("file")).canonicalFile}.getOrNull()?:return@synchronized false
        if(file.parentFile!=root || file.extension.lowercase()!="m4a" || !file.isFile)return@synchronized false
        if(job.str("owner")!=user || job.str("id")!=jobId)return@synchronized false
        RecordingService.clearPending(context,file.path,deleteFile=true)
        vault.names().filter{it.startsWith("capture-") && vault.read(it)==file.path}.forEach{capture->
            vault.remove(capture);vault.remove("$capture-mime");vault.remove("$capture-review")
        }
        val encounter=job.optJSONObject("case")?.optJSONObject("encounter")?.str("id").orEmpty()
        val hasSibling=encounter.isNotBlank() && vault.names().filter{it.startsWith("recording-job-") && it!=key}.any{other->
            vault.read(other)?.let{sibRaw->runCatching{JSONObject(sibRaw)}.getOrNull()}?.let{
                it.str("owner")==user && it.optJSONObject("case")?.optJSONObject("encounter")?.str("id")==encounter
            }==true
        }
        if(encounter.isNotBlank() && !hasSibling)vault.remove("recording-context-$user:$encounter")
        vault.remove(key)
        true
    }
    fun complete(encounter:String,user:String)=synchronized(storageLock){list(user).filter{it.optJSONObject("case")?.optJSONObject("encounter")?.str("id")==encounter}.forEach{job->
        val file=File(job.str("file"));if(file.canonicalFile.parentFile==root)RecordingService.clearPending(context,file.path)
        vault.names().filter{it.startsWith("capture-") && vault.read(it)==file.path}.forEach{capture->
            vault.remove(capture);vault.remove("$capture-mime");vault.remove("$capture-review")
        }
        vault.remove(key(file))
        vault.remove("recording-context-$user:$encounter")
    }}
}
