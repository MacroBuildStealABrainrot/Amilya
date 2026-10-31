package com.woundcopilot.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Technical-only outbox, encrypted using the existing vault; never owns source media. */
object DeveloperDiagnostics {
    private data class Binding(val vault:Vault,val owner:String,val job:String,val recording:File?)
    @Volatile private var binding:Binding?=null
    private val writer=Executors.newSingleThreadExecutor()
    private val mutex=kotlinx.coroutines.sync.Mutex()
    private var lastRetry=0L
    private var lastRetryJob=""
    private val stages=mapOf("audio_finalization" to "local_save","audio_preparation" to "audio_preparation","request_upload" to "upload","transcription_request" to "transcription_request","transcript_assembly" to "transcript_assembly","report_network" to "generation_request","report_state" to "report_state","report_ui" to "report_display","stop_to_report" to "processing_total","report_save_network" to "report_save")
    fun job(vault:Vault,owner:String,encounter:String):String {
        val key="diagnostic-job-$owner-$encounter"
        return runCatching{vault.read(key)?:UUID.randomUUID().toString().also{vault.write(key,it)}}.getOrElse{UUID.randomUUID().toString()}
    }
    fun bind(vault:Vault,owner:String,encounter:String,file:File?=null):String {
        val id=job(vault,owner,encounter);binding=Binding(vault,owner,id,file);return id
    }
    fun clear(){binding=null}
    fun external(vault:Vault,owner:String,job:String,stage:String,flowType:String){runCatching{emitBinding(Binding(vault,owner,job,null),stage,Performance.now(),if(stage=="external_failed")"failed"else "success",flowType=flowType)}}
    fun photo(vault:Vault,owner:String,job:String,file:File,stage:String,start:Long,status:String){
        emitBinding(Binding(vault,owner,job,null),stage,start,status,photo=file)
    }
    fun wear(vault:Vault,owner:String,job:String,stage:String,commandId:String,error:String){
        emitBinding(Binding(vault,owner,job,null),stage,Performance.now(),if(error=="NONE")"success"else "failed",commandId,"WEAR_OS",error)
    }
    fun link(vault:Vault,owner:String,from:String,to:String){runCatching{vault.write("diagnostic-job-$owner-$to",job(vault,owner,from))}}
    fun record(stage:String,start:Long,status:String,job:String="session",requestId:String?=null){
        if(job!="session" && binding?.job!=job)return
        val name=stages[stage]?:return
        val b=binding?:return
        emitBinding(b,name,start,if(status=="ok")"success"else "failed",requestId)
    }
    @Synchronized fun retry(){val job=binding?.job?:return;val now=Performance.now();if(lastRetryJob==job && now-lastRetry<250_000_000)return;lastRetry=now;lastRetryJob=job;emit("retry")}
    fun emit(stage:String,start:Long=Performance.now(),status:String="success"){
        val b=binding?:return
        emitBinding(b,stage,start,status)
    }
    fun fileRemoved(vault:Vault,ownerKey:String?,file:File,deleted:Boolean){runCatching{
        val parts=ownerKey?.split(':',limit=2)?.takeIf{it.size==2}?:return
        val id=vault.read("diagnostic-job-${parts[0]}-${parts[1]}")?:return
        emitBinding(Binding(vault,parts[0],id,file),"cleanup",Performance.now(),if(deleted)"success"else "failed")
    }}
    private fun emitBinding(b:Binding,stage:String,start:Long,status:String,requestId:String?=null,flowType:String?=null,wearError:String?=null,photo:File?=null){
        val duration=((Performance.now()-start)/1_000_000.0).coerceAtLeast(0.0)
        val finish=Instant.now()
        val exists=b.recording?.let{it.isFile && it.length()>128}
        val row=obj("id" to UUID.randomUUID().toString(),"job_id" to b.job,"stage" to stage,"started_at" to finish.minusNanos((duration*1_000_000).toLong()).toString(),"finished_at" to finish.toString(),"duration_ms" to duration,"status" to status,"storage" to if(b.recording==null)"unknown"else "app_private")
        if(exists!=null)row.put("recording_exists",exists)
        if(photo!=null){row.put("photo_exists",photo.isFile && photo.length()>0);row.put("image_bytes",photo.length().coerceIn(0,25L*1024*1024));row.put("storage","app_private")}
        if(requestId!=null)row.put("client_request_id",requestId)
        if(flowType!=null)row.put("flow_type",flowType)
        if(wearError!=null)row.put("wear_error",wearError)
        writer.execute{runCatching{
            synchronized(this){
                val key="diagnostic-outbox-${b.owner}"
                val rows=b.vault.read(key)?.let{JSONArray(it).objects()}?:emptyList()
                b.vault.write(key,JSONArray((rows+row).filter{runCatching{Instant.parse(it.str("finished_at")).isAfter(Instant.now().minusSeconds(7*86400))}.getOrDefault(false)}.takeLast(1000)).toString())
            }
        }}
    }
    /** A server without the diagnostics address (HTTP 404/405) is not asked again until the app restarts. */
    @Volatile private var serverUnsupported=false
    suspend fun flush(api:Api,vault:Vault,owner:String){
        if(serverUnsupported || !mutex.tryLock())return
        try{
            val key="diagnostic-outbox-$owner"
            val rows=withContext(Dispatchers.IO){synchronized(this){vault.read(key)?.let{JSONArray(it).objects().filter{runCatching{Instant.parse(it.str("finished_at")).isAfter(Instant.now().minusSeconds(7*86400))}.getOrDefault(false)}.take(100)}?:emptyList()}}
            if(rows.isEmpty())return
            val accepted=api.call("/diagnostics/events","POST",obj("events" to JSONArray(rows)))as JSONObject
            check(accepted.optInt("accepted",-1)==rows.size){"Diagnostic acknowledgement missing"}
            withContext(Dispatchers.IO){synchronized(this){
                val ids=rows.map{it.str("id")}.toSet()
                val rest=vault.read(key)?.let{JSONArray(it).objects().filter{it.str("id") !in ids}}?:emptyList()
                if(rest.isEmpty())vault.remove(key)else vault.write(key,JSONArray(rest).toString())
            }}
        }catch(e:Exception){if(e is CancellationException)throw e;if(e is ApiException && e.status in listOf(404,405))serverUnsupported=true}finally{mutex.unlock()}
    }
}

@Composable fun DeveloperDiagnosticsScreen(s:AppState){
    if(s.demo || s.user?.optBoolean("is_platform_admin")!=true){Page{Text("Administrator access required.")};return}
    var rows by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var detail by remember{mutableStateOf<JSONObject?>(null)}
    var filter by remember{mutableStateOf("")}
    var failures by remember{mutableStateOf(false)}
    var model by remember{mutableStateOf("")}
    var provider by remember{mutableStateOf("")}
    var task by remember{mutableStateOf("")}
    var since by remember{mutableStateOf("")}
    var slow by remember{mutableStateOf(false)}
    var fallback by remember{mutableStateOf(false)}
    var offset by remember{mutableIntStateOf(0)}
    var more by remember{mutableStateOf(false)}
    var problem by remember{mutableStateOf("")}
    var loading by remember{mutableStateOf(false)}
    var expanded by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    fun refresh(){scope.launch{loading=true;try{
        val query=mutableMapOf("offset" to offset.toString())
        for((key,value) in listOf("model" to model,"provider" to provider,"task" to task,"since" to since))if(value.isNotBlank())query[key]=value.trim()
        if(failures)query["status"]="failed"
        if(fallback)query["fallback"]="true"
        if(slow){query["slow_ms"]="10000";query["sort"]="duration"}
        val url="/admin/diagnostics?"+query.entries.joinToString("&"){it.key+"="+java.net.URLEncoder.encode(it.value,"UTF-8")}
        val data=s.api.call(url)as JSONObject;rows=data.items("items");more=data.optBoolean("has_more");problem=if(data.optInt("write_failures_since_process_start")>0)"Some diagnostic writes failed; this history may be incomplete."else ""
    }catch(_:Exception){problem="Diagnostics unavailable. Check filters, backend diagnostics version and administrator access."}finally{loading=false}}}
    LaunchedEffect(Unit){refresh()}
    Page{
        Text("Developer Diagnostics",style=MaterialTheme.typography.headlineSmall)
        Text("Technical metadata only · device observations are not server verification.",style=MaterialTheme.typography.bodySmall)
        if(BuildConfig.ALLOW_SCREEN_CAPTURE)Text("Test build: screenshots and screen recording enabled.")
        if(problem.isNotBlank())Text(problem,color=MaterialTheme.colorScheme.error)
        Button({refresh()},enabled=!loading){Text(if(loading)"Loading…"else "Refresh")}
        if(detail!=null){
            TextButton({detail=null}){Text("Back to requests")}
            val d=detail!!
            Text("${d.str("task")} · ${d.str("status")}")
            Text("Job ${d.str("job_id")}\nRequest ${d.str("request_id")}",style=MaterialTheme.typography.bodySmall)
            Text("Last generation model: ${d.str("last_generation_model").ifBlank{"Unknown / no generation in this request"}}")
            if(d.has("document_id"))Text("Saved document: ${d.str("document_id")}",style=MaterialTheme.typography.bodySmall)
            Text(if(d.has("duration_ms"))"Total server request: ${d.optDouble("duration_ms").toInt()} ms"else "Device observation; no server duration")
            Text("Client retries: ${d.optInt("client_retry_count")} · Provider retries: ${d.optInt("provider_retry_count")}")
            d.items("events").forEach{e->
                HorizontalDivider()
                TextButton({expanded=if(expanded==e.str("event_id"))null else e.str("event_id")}){Text("${e.str("stage")} · ${e.str("status")} · ${e.str("selected_model")}",style=MaterialTheme.typography.titleSmall)}
                if(expanded==e.str("event_id"))for(key in listOf("source","flow_type","wear_error","client_request_id","selected_model","reported_model","provider","effort_level","prompt_layers_version","clinician_role","report_format","original_model","original_provider","fallback_used","retry_count","started_at","finished_at","duration_ms","input_tokens","output_tokens","cost_usd","cost_basis","normalized_cost_usd","error_code","error_message","defect_count","recording_exists","photo_exists","image_bytes","input_bytes","prepared_bytes","width","height","mime","storage")){
                    if(e.has(key))Text("${key.replace('_',' ')}: ${if(e.isNull(key))"unknown"else e.opt(key)}",style=MaterialTheme.typography.bodySmall)
                }
            }
        }else{
            OutlinedTextField(filter,{filter=it},label={Text("Filter task, date or request ID")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(task,{task=it;offset=0},label={Text("Task ID (optional)")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(model,{model=it;offset=0},label={Text("Exact model ID (optional)")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(provider,{provider=it;offset=0},label={Text("Provider (optional)")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(since,{since=it;offset=0},label={Text("Since, ISO date/time (optional)")},modifier=Modifier.fillMaxWidth())
            Row{Checkbox(failures,{failures=it});Text("Failures only",Modifier.padding(top=12.dp))}
            Row{Checkbox(fallback,{fallback=it});Text("Fallback only",Modifier.padding(top=12.dp))}
            Row{Checkbox(slow,{slow=it});Text("Over 10 seconds · slowest first",Modifier.padding(top=12.dp))}
            Button({offset=0;refresh()},enabled=!loading){Text("Apply filters")}
            Row{
                TextButton({offset=(offset-100).coerceAtLeast(0);refresh()},enabled=offset>0&&!loading){Text("Previous")}
                TextButton({offset+=100;refresh()},enabled=more&&!loading){Text("Next")}
            }
            if(rows.isEmpty()&&!loading)Text("No diagnostic requests yet.")
            rows.filter{(!failures || it.str("status")=="failed")&&(filter.isBlank()||listOf("task","started_at","request_id","job_id").any{k->it.str(k).contains(filter,true)})}.forEach{r->
                OutlinedButton({scope.launch{try{detail=s.api.call("/admin/diagnostics/${r.str("request_id")}")as JSONObject}catch(_:Exception){problem="Request details could not be loaded."}}},Modifier.fillMaxWidth()){
                    Column{Text("${r.str("task")} · ${r.str("status")}");Text("${r.str("started_at")} · ${if(r.has("duration_ms"))r.optDouble("duration_ms").toInt().toString()+" ms"else "device observation"}",style=MaterialTheme.typography.bodySmall);Text(r.optJSONArray("models")?.toString()?:"",style=MaterialTheme.typography.bodySmall)}
                }
            }
        }
    }
}
