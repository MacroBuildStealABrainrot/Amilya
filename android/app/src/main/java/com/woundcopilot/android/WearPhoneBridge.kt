package com.woundcopilot.android

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.PowerManager
import androidx.compose.runtime.snapshotFlow
import com.google.android.gms.wearable.*
import com.woundcopilot.wear.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/** Transport adapter around AppState/RecordingService. No microphone or clinical pipeline here. */
class WearPhoneBridge(private val s:AppState,private val context:Context,private val scope:CoroutineScope) {
 companion object { var active:WeakReference<WearPhoneBridge> = WeakReference(null) }
 var visible=false
 private val epoch=WearProtocol.id()
 private var sequence=0L
 private var token=WearProtocol.id()
 private var identity=""
 private var last:WearStatus?=null
 private val mutex=Mutex()
 private var observer:Job?=null
 private var knownNodes:Set<String> = emptySet()
 private val connectionListener=CapabilityClient.OnCapabilityChangedListener { cap ->
  val nodes=cap.nodes.map{it.id}.toSet()
  if(nodes!=knownNodes){
   val event=if(nodes.isEmpty())"watch_disconnected"else "watch_connected"
   knownNodes=nodes
   if(owner().isNotBlank()&&!s.demo)DeveloperDiagnostics.wear(s.vault,owner(),WearProtocol.id(),event,WearProtocol.id(),"NONE")
   publish(true)
  }
 }

 private var cancelledJob=""
 private var cancelledOwner=""
 init {
  if(BuildConfig.WEAR_REMOTE_CONTROL){active=WeakReference(this)
   Wearable.getCapabilityClient(context).addListener(connectionListener,WearProtocol.WATCH).addOnFailureListener{}
   Wearable.getCapabilityClient(context).getCapability(WearProtocol.WATCH,CapabilityClient.FILTER_REACHABLE).addOnSuccessListener{connectionListener.onCapabilityChanged(it)}.addOnFailureListener{}

   observer=scope.launch {
    combine(RecordingService.state.map{it.copy(amplitude=0f,elapsedSeconds=0)}.distinctUntilChanged(),snapshotFlow{listOf(s.user?.str("id"),s.locked,s.legalRequired,s.encounterId,s.recordingJobs.toString(),s.loading,s.error,s.screen)}){_,_->Unit}.collect{publish()}
   }
  }
 }
 private fun owner()=s.user?.str("id").orEmpty()
 private fun currentJob():JSONObject? {
  val file=RecordingService.state.value.filePath?.let(::File)
  val direct=file?.let{s.recordingRecovery.read(it)}?.takeIf{it.str("owner")==owner()}
  return direct?:s.recordingJobs.firstOrNull{it.str("owner")==owner()}
 }
 fun status():WearStatus {
  val rec=RecordingService.state.value
  val job=currentJob()
  val jobId=if(cancelledOwner==owner()&&cancelledJob.isNotBlank()&&!rec.recording)cancelledJob else job?.str("id").orEmpty()
  val belongs=rec.filePath?.let{s.vault.read("recording-owner-${File(it).name}")?.substringBefore(':')==owner()}==true
  val raw=job?.str("phase").orEmpty()
  val activeRec=rec.recording&&belongs&&rec.filePath?.let{s.recordingRecovery.read(File(it))?.str("id")==jobId}==true
  val nextIdentity="${owner()}|${s.encounterId}|$jobId|${s.legalRequired}"
  if(nextIdentity!=identity){identity=nextIdentity;token=WearProtocol.id()}
  val saved=jobId!=cancelledJob && job?.str("file")?.let{File(it).isFile&&File(it).length()>128}==true && raw !in listOf("RECORDING","RECORDING_FINALIZATION_FAILED")
  val phase=when {
   owner().isBlank()||s.demo -> Phase.IDLE
   activeRec -> if(rec.paused)Phase.PAUSED else Phase.RECORDING
   jobId.isNotBlank()&&cancelledOwner==owner()&&jobId==cancelledJob -> Phase.CANCELLED
   raw=="REPORT_READY" -> Phase.REPORT_READY
   raw=="GENERATING_REPORT" -> Phase.GENERATING_REPORT
   raw=="TRANSCRIBED" -> Phase.TRANSCRIBING
   raw in listOf("UPLOADING","WAITING_FOR_UPLOAD") && s.loading -> Phase.UPLOADING
   raw=="FAILED_RETRYABLE" -> if(saved&&!online())Phase.QUEUED_OFFLINE else Phase.FAILED
   saved -> Phase.SAVED
   raw in listOf("RECORDING","RECORDING_FINALIZATION_FAILED") -> Phase.FAILED
   else -> Phase.IDLE
  }
  val error=when {owner().isBlank()||s.demo||s.legalRequired->WearError.SESSION_REQUIRED
   phase in setOf(Phase.IDLE,Phase.CANCELLED) && (s.locked||deviceLocked())->WearError.PHONE_LOCKED
   phase in setOf(Phase.IDLE,Phase.CANCELLED) && !visible->WearError.OPEN_PHONE_REQUIRED
   phase in setOf(Phase.IDLE,Phase.CANCELLED) && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED->WearError.MIC_PERMISSION_REQUIRED
   phase==Phase.FAILED->if(saved)WearError.PROCESSING_FAILED else WearError.AUDIO_SAVE_FAILED;else->WearError.NONE}
  return WearStatus(epoch,sequence,token,jobId,phase,if(belongs)rec.elapsedSeconds*1000L else 0,System.currentTimeMillis(),saved,error)
 }
 private fun online():Boolean {
  val cm=context.getSystemService(android.net.ConnectivityManager::class.java)
  return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
 }
 fun publish(force:Boolean=false):WearStatus {
  var now=status()
  if(!BuildConfig.WEAR_REMOTE_CONTROL)return now.copy(error=WearError.UNAVAILABLE)
  // Amplitude and timer ticks never cause radio traffic. Send a timing sample on real transitions only.
  val signature=now.copy(sequence=0,elapsedMillis=0,sampledAt=0)
  val previous=last?.copy(sequence=0,elapsedMillis=0,sampledAt=0)
  if(force||signature!=previous){now=now.copy(sequence=++sequence);last=now
   val request=PutDataRequest.create(WearProtocol.STATUS).setData(WearWire.status(now)).setUrgent()
   Wearable.getDataClient(context).putDataItem(request).addOnFailureListener { /* no clinical logs */ }
  }
  return now.copy(sequence=sequence)
 }
 fun foreground(value:Boolean){visible=value;if(!BuildConfig.WEAR_REMOTE_CONTROL)return;if(value)consumeOpen();publish()}
 fun close(){observer?.cancel();Wearable.getCapabilityClient(context).removeListener(connectionListener,WearProtocol.WATCH);if(active.get()===this)active=WeakReference(null)}
 private fun diagnose(c:WearCommand,event:String,error:WearError=WearError.NONE){
  if(owner().isBlank()||s.demo)return
  val job=currentJob()?.str("id")?.takeIf{WearProtocol.validId(it)&&it.isNotBlank()}?:c.id
  DeveloperDiagnostics.wear(s.vault,owner(),job,event,c.id,error.name)
 }
 suspend fun command(c:WearCommand):WearStatus=mutex.withLock {
  var now=publish()
  fun reply(error:WearError=WearError.NONE):WearStatus {if(error!=WearError.NONE)diagnose(c,"watch_command_failed",error);return publish(true).copy(replyTo=c.id,error=error)}
  if(!BuildConfig.WEAR_REMOTE_CONTROL)return@withLock reply(WearError.UNAVAILABLE)
  val ledgerKey="wear-command-ledger"
  val rows=s.vault.read(ledgerKey)?.let{runCatching{JSONArray(it).objects()}.getOrNull()}?:emptyList()
  if(rows.any{it.str("id")==c.id})return@withLock reply(WearError.STALE_COMMAND)
  val validation=CommandGate.check(c,now,System.currentTimeMillis())
  if(validation!=WearError.NONE){diagnose(c,"watch_command_failed",validation);return@withLock reply(validation)}
  if(c.type==CommandType.STATUS){diagnose(c,"watch_status_sync");return@withLock reply(now.error)}
  if(owner().isBlank()||s.demo||s.legalRequired)return@withLock reply(WearError.SESSION_REQUIRED)
  if(c.type!=CommandType.OPEN_PHONE && c.type!=CommandType.STOP && (s.locked||deviceLocked()))return@withLock reply(WearError.PHONE_LOCKED)
  // Persist reservation first: no replay after a crash or network reconnect can rerun an action.
  s.vault.write(ledgerKey,JSONArray((rows.filter{System.currentTimeMillis()-it.optLong("at")<WearProtocol.CACHE_AGE}+obj("id" to c.id,"at" to System.currentTimeMillis())).takeLast(128)).toString())
  token=WearProtocol.id()
  try {
   when(c.type){
    CommandType.START -> {
     diagnose(c,"watch_start_requested")
     if(!visible)return@withLock reply(WearError.OPEN_PHONE_REQUIRED)
     if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return@withLock reply(WearError.MIC_PERMISSION_REQUIRED)
     if(s.loading)return@withLock reply(WearError.BUSY)
     if(RecordingService.state.value.recording)return@withLock reply(WearError.RECORDING_ALREADY_ACTIVE)
     if(s.quickMode!="document" || s.encounterId.isBlank() || now.phase==Phase.REPORT_READY)s.startQuick("document")
     cancelledJob="";cancelledOwner=""
     if(c.consent)s.confirmRecordingConsent("watch_tap")else s.recordingConsent=false;s.autoStartRecording=false;s.screen="record"
     s.prepareRecordingContext("${owner()}:${s.encounterId}")
     RecordingService.start(context,"${owner()}:${s.encounterId}")
     val started=withTimeoutOrNull(8000){RecordingService.state.first{it.recording||it.error!=null}}
     if(started?.recording!=true){diagnose(c,"watch_command_failed",WearError.UNAVAILABLE);return@withLock reply(WearError.UNAVAILABLE)}
     diagnose(c,"watch_start_confirmed")
    }
    CommandType.STOP -> {
     diagnose(c,"watch_stop_requested")
     RecordingService.stop(context)
     val stopped=withTimeoutOrNull(8000){RecordingService.state.first{!it.recording}}
     if(stopped==null)return@withLock reply(WearError.BUSY)
     diagnose(c,"watch_stop_confirmed")
    }
    CommandType.CANCEL -> {
     diagnose(c,"watch_cancel_requested")
     if(s.loading)return@withLock reply(WearError.BUSY)
     val file=RecordingService.state.value.filePath?.let(::File)?:return@withLock reply(WearError.NO_ACTIVE_RECORDING)
     val job=currentJob()?:return@withLock reply(WearError.NO_ACTIVE_RECORDING)
     // Mark before stop, so the existing auto-processing collector cannot race cancellation.
     s.cancelledRecordingPath=file.path
     s.vault.write("wear-cancel-${file.name}","requested")
     RecordingService.stop(context)
     if(withTimeoutOrNull(8000){RecordingService.state.first{!it.recording}}==null)return@withLock reply(WearError.BUSY)
     RecordingService.clearPending(context,file.path,true)
     if(file.exists())return@withLock reply(WearError.UNAVAILABLE)
     s.vault.remove(s.recordingRecovery.key(file));s.vault.remove("recording-owner-${file.name}")
     val capture="capture-${owner()}-${s.encounterId}-audio";if(s.vault.read(capture)==file.path){s.vault.remove(capture);s.vault.remove("$capture-mime")}
     s.vault.remove("wear-cancel-${file.name}");s.refreshRecordingJobs();cancelledJob=job.str("id");cancelledOwner=owner()
    }
    CommandType.RETRY -> {
     if(s.loading)return@withLock reply(WearError.BUSY)
     val job=currentJob()?:return@withLock reply(WearError.NO_ACTIVE_RECORDING)
     s.resumeRecording(job)
    }
    CommandType.OPEN_PHONE -> {
     diagnose(c,"watch_open_phone")
     s.vault.write("wear-open-result",obj("owner" to owner(),"job" to c.job,"at" to System.currentTimeMillis()).toString())
     if(visible)consumeOpen()
    }
    else -> Unit
   }
   reply()
  }catch(e:Exception){if(e is CancellationException)throw e;diagnose(c,"watch_command_failed",WearError.UNAVAILABLE);reply(WearError.UNAVAILABLE)}
 }
 private fun deviceLocked()=context.getSystemService(KeyguardManager::class.java).isKeyguardLocked||!context.getSystemService(PowerManager::class.java).isInteractive
 fun consumeOpen(){
  if(s.locked||s.user==null||s.legalRequired||deviceLocked()||s.loading)return
  val target=s.vault.read("wear-open-result")?.let{runCatching{JSONObject(it)}.getOrNull()}?:return
  if(target.str("owner")!=owner()||System.currentTimeMillis()-target.optLong("at")>120000){s.vault.remove("wear-open-result");return}
  val job=s.recordingRecovery.list(owner()).firstOrNull{it.str("id")==target.str("job")}
  s.vault.remove("wear-open-result")
  if(job!=null && job.str("resume_from")=="REPORT_READY")s.resumeRecording(job)
  else if(RecordingService.state.value.recording)s.screen="record"
  else if(job!=null){s.notice="Your recording is saved. Resume it from Document.";s.screen="home"}
  else if(s.quickMode!="document" || s.encounterId.isBlank())s.startQuick("document")
  else s.screen="document_capture"
 }
}

/** Google Play services authenticates Data Layer binder callers and matching app signatures. */
class WearCommandService:WearableListenerService(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 override fun onMessageReceived(event:MessageEvent){
  if(event.path!=WearProtocol.COMMAND||event.data.size>WearProtocol.MAX_BYTES)return
  val command=runCatching{WearWire.command(event.data)}.getOrNull()?:return
  val node=event.sourceNodeId
  Wearable.getCapabilityClient(this).getCapability(WearProtocol.WATCH,CapabilityClient.FILTER_REACHABLE).addOnSuccessListener{cap->
   if(cap.nodes.none{it.id==node})return@addOnSuccessListener
   scope.launch {
    val bridge=WearPhoneBridge.active.get()
    val reply=try {
     if(!BuildConfig.WEAR_REMOTE_CONTROL)WearStatus(error=WearError.UNAVAILABLE,replyTo=command.id)
     else if(bridge!=null)bridge.command(command)else ColdWearState.reply(this@WearCommandService,command)
    }catch(e:Exception){if(e is CancellationException)throw e;WearStatus(error=WearError.UNAVAILABLE,replyTo=command.id)}
    Wearable.getMessageClient(this@WearCommandService).sendMessage(node,WearProtocol.RESPONSE,WearWire.status(reply))
   }
  }
 }
 override fun onDestroy(){scope.cancel();super.onDestroy()}
}

/** Read existing checkpoints after process death; never instantiate a second AppState or recorder. */
internal object ColdWearState {
 private val epoch=WearProtocol.id()
 private var token=WearProtocol.id()
 private var identity=""
 private var sequence=0L
 private var stoppingPath:String?=null
 @Synchronized fun reply(context:Context,c:WearCommand):WearStatus {
  val vault=Vault(context)
  val owner=if(vault.read("session")!=null)vault.read("cached-account")?.let{runCatching{JSONObject(it).getJSONObject("user").str("id")}.getOrNull()}?.takeIf{it.isNotBlank()} else null
  val rec=RecordingService.state.value
  val recovery=RecordingRecovery(context)
  val direct=rec.filePath?.let{path->recovery.read(File(path))?.takeIf{
   owner!=null && it.str("owner")==owner && vault.read("recording-owner-${File(path).name}")?.substringBefore(':')==owner
  }}
  val activeRec=rec.recording&&direct!=null
  if(!rec.recording)stoppingPath=null
  val job=direct?:owner?.let{recovery.list(it).firstOrNull()}
  val nextIdentity="$owner|${job?.str("id").orEmpty()}"
  if(identity!=nextIdentity){identity=nextIdentity;token=WearProtocol.id()}
  val phase=job?.str("phase")
  val saved=!activeRec && job?.str("file")?.let{File(it).isFile&&File(it).length()>128}==true && phase !in listOf("RECORDING","RECORDING_FINALIZATION_FAILED")
  val state=WearStatus(epoch=epoch,token=token,sequence=++sequence,job=job?.str("id").orEmpty(),phase=when{
   activeRec&&stoppingPath==rec.filePath->Phase.STOPPING
   activeRec->if(rec.paused)Phase.PAUSED else Phase.RECORDING
   phase=="REPORT_READY"->Phase.REPORT_READY;saved->Phase.SAVED;job!=null->Phase.FAILED;else->Phase.IDLE
  },elapsedMillis=if(direct!=null)rec.elapsedSeconds*1000L else 0L,saved=saved,replyTo=c.id)
  val rows=vault.read("wear-command-ledger")?.let{runCatching{JSONArray(it).objects()}.getOrNull()}?:emptyList()
  if(rows.any{it.str("id")==c.id})return state.copy(error=WearError.STALE_COMMAND)
  val now=System.currentTimeMillis()
  val error=CommandGate.check(c,state,now)
  if(error!=WearError.NONE)return state.copy(error=error)
  if(owner==null)return state.copy(error=WearError.SESSION_REQUIRED)
  if(c.type==CommandType.STATUS)return state
  if(c.type!=CommandType.OPEN_PHONE && c.type!=CommandType.STOP)return state.copy(error=WearError.OPEN_PHONE_REQUIRED)
  // Same encrypted replay ledger as the live bridge. Reserve before sending a service command.
  vault.write("wear-command-ledger",JSONArray((rows.filter{now-it.optLong("at")<WearProtocol.CACHE_AGE}+obj("id" to c.id,"at" to now)).takeLast(128)).toString())
  token=WearProtocol.id()
  if(c.type==CommandType.OPEN_PHONE){
   vault.write("wear-open-result",obj("owner" to owner,"job" to state.job,"at" to now).toString())
   return state.copy(token=token)
  }
  return try {
   stoppingPath=rec.filePath
   RecordingService.stop(context)
   state.copy(token=token,phase=Phase.STOPPING,saved=false)
  }catch(e:Exception){stoppingPath=null;state.copy(token=token,error=WearError.UNAVAILABLE)}
 }
}

/** Event-driven publication also survives Activity disposal while existing processing finishes. */
internal object WearCheckpointPublisher {
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
 fun changed(context:Context){
  if(!BuildConfig.WEAR_REMOTE_CONTROL)return
  scope.launch {runCatching {
   val bridge=WearPhoneBridge.active.get()
   if(bridge!=null)bridge.publish()else {
    val status=ColdWearState.reply(context,WearCommand(type=CommandType.STATUS))
    Wearable.getDataClient(context).putDataItem(PutDataRequest.create(WearProtocol.STATUS).setData(WearWire.status(status.copy(replyTo=""))).setUrgent()).addOnFailureListener{}
   }
  }}
 }
}
