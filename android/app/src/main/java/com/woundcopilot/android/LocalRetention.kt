package com.woundcopilot.android
import android.app.job.*
import android.content.*
import java.io.File
import kotlinx.coroutines.*

object LocalRetention{
    const val MEDIA_AGE=24*60*60*1000L
    const val RECOVERY_AGE=7*24*60*60*1000L
    fun clean(context:Context,now:Long=System.currentTimeMillis())=synchronized(RecordingRecovery.storageLock){
        val vault=Vault(context)
        // Sandbox only: recordings that are not finished with are never removed because of age (see UnresolvedRetention).
        val retain=BuildConfig.RETAIN_UNRESOLVED
        val pending=mutableSetOf<String>()
        val protected=vault.names().filter{it.startsWith("recording-job-")}.mapNotNull{key->runCatching{
            val job=org.json.JSONObject(vault.read(key)?:return@runCatching null)
            if((retain || now-job.optLong("updated_at")<RECOVERY_AGE) && job.str("phase")!="COMPLETED")File(job.str("file")).canonicalPath else null
        }.getOrNull()}.toSet()
        vault.names().filter{it.startsWith("capture-") || it.startsWith("audio-batch-")}.forEach{key->
            val value=vault.read(key).orEmpty()
            if(value.startsWith(context.filesDir.path+File.separator))pending+=File(value).canonicalPath
            if(key.startsWith("audio-batch-"))runCatching{
                val batch=org.json.JSONObject(value)
                if(!batch.has("result")){val files=batch.getJSONArray("files");for(index in 0 until files.length())pending+=File(files.getString(index)).canonicalPath}
            }
        }
        vault.names().filter{it.startsWith("photo-batch-pending-")}.forEach{key->runCatching{
            org.json.JSONArray(vault.read(key)?:"[]").objects().forEach{item->
                val file=File(item.str("path")).canonicalFile
                if(file.parentFile==File(context.filesDir,"captures").canonicalFile)pending+=file.path
            }
        }}
        for(name in listOf("captures","recordings")){
            val root=File(context.filesDir,name).canonicalFile
            if(root.exists())root.walkTopDown().filter{it.isFile}.forEach{file->
                // Never age out unfinished crash-resistant segments, or the recovery note of a recording still waiting to be sent.
                // (RetentionRules.shouldDelete gives the original age rules unchanged, plus those protections.)
                if(RetentionRules.shouldDelete(file,root,now,MEDIA_AGE,RECOVERY_AGE,protected,pending,retain)){
                    val deleted=file.delete()
                    if(file.extension=="m4a")runCatching{DeveloperDiagnostics.fileRemoved(vault,vault.read("recording-owner-${file.name}"),file,deleted)}
                }
            }
        }
        File(context.cacheDir,"exports").listFiles()?.filter{it.isFile && now-it.lastModified()>MEDIA_AGE}?.forEach{it.delete()}
        val keepNames: Set<String> = if(retain)unresolvedRecordNames(vault) else emptySet()
        vault.expireRecovery(now-RECOVERY_AGE){name->UnresolvedRetention.keepRecord(name,retain,keepNames)}
        if(retain)runCatching{UnresolvedReminder.check(context,now)}
    }
    /** Names of stored records that belong to recordings still waiting to be recovered, uploaded or reported (sandbox only). */
    private fun unresolvedRecordNames(vault:Vault):Set<String>{
        val names=vault.names()
        val jobs=names.filter{it.startsWith("recording-job-")}.mapNotNull{key->runCatching{
            val job=org.json.JSONObject(vault.read(key)?:return@runCatching null)
            UnresolvedRetention.JobRecord(key,job.str("file"),job.str("phase"),job.str("owner"),job.optJSONObject("case")?.optJSONObject("encounter")?.str("id").orEmpty())
        }.getOrNull()}
        val batches=names.filter{it.startsWith("audio-batch-")}.mapNotNull{key->runCatching{
            val files=org.json.JSONObject(vault.read(key)?:return@runCatching null).optJSONArray("files")?:return@runCatching null
            UnresolvedRetention.BatchRecord(key,(0 until files.length()).map{files.getString(it)})
        }.getOrNull()}
        val captures=names.filter{it.startsWith("capture-") && !it.startsWith("capture-owner-") && !it.endsWith("-mime") && !it.endsWith("-review")}.associateWith{vault.read(it).orEmpty()}
        return UnresolvedRetention.protectedRecordNames(jobs,batches,captures,names.filter{it.startsWith("recording-context-")},names.filter{it.startsWith("upload-")}){path->File(path).exists() || File("$path.json").exists()}
    }
    fun schedule(context:Context){
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(4203,ComponentName(context,RetentionJob::class.java))
            .setPeriodic(60*60*1000L).setPersisted(true).build())
    }
}
class RetentionJob:JobService(){
    private var job:Job?=null
    override fun onStartJob(params:JobParameters):Boolean{
        job=CoroutineScope(Dispatchers.IO).launch{try{LocalRetention.clean(applicationContext)}finally{jobFinished(params,false)}}
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{job?.cancel();return true}
}
