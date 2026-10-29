package com.woundcopilot.android

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import java.time.Instant


/** Replay responses must never roll a locally newer case or reviewed finding backwards. */
internal fun mergeEvidenceResponse(current:JSONObject,response:JSONObject):JSONObject {
    val merged=JSONObject(current.toString())
    response.optJSONObject("evidence")?.let { evidence ->
        merged.put("evidence",JSONArray(merged.items("evidence").filter{it.str("id")!=evidence.str("id")}+evidence))
    }
    val facts=linkedMapOf<String,JSONObject>()
    merged.items("facts").forEach{facts[it.str("id")]=it}
    response.items("facts").forEach{incoming->
        val old=facts[incoming.str("id")]
        if(old==null || incoming.optInt("revision")>old.optInt("revision"))facts[incoming.str("id")]=incoming
    }
    merged.put("facts",JSONArray(facts.values.toList()))
    val encounter=merged.getJSONObject("encounter")
    encounter.put("revision",maxOf(encounter.optInt("revision"),response.optInt("case_revision")))
    merged.optJSONObject("review")?.let{it.put("stale",reviewIsStale(it,encounter.optInt("revision")))}
    return merged
}
internal fun reviewIsStale(review:JSONObject,caseRevision:Int)=
    review.optBoolean("stale") || !review.has("case_revision") || review.optInt("case_revision")!=caseRevision
internal fun requireValidPhotoUpload(result:JSONObject){
    val evidence=result.optJSONObject("evidence")
    if(evidence==null || evidence.str("id").isBlank() || evidence.str("kind")!="photo" || !result.has("case_revision") || result.optInt("case_revision",-1)<0)
        throw ApiException(502,"The photo response could not be verified. Your photograph remains saved on this device.")
}
internal fun boundedConsultHistory(messages:List<JSONObject>):List<JSONObject>{
    var size=0
    return messages.takeLast(20).asReversed().takeWhile{turn->
        val length=turn.str("content").length
        (length in 1..10000 && size+length<=40000).also{if(it)size+=length}
    }.asReversed()
}

class AppState(application:Application):AndroidViewModel(application) {
    val vault=Vault(application)
    val api=Api(vault)
    val externalCapture=ExternalCapture(this,application,viewModelScope)
    private val screenLockReceiver=object:android.content.BroadcastReceiver(){override fun onReceive(context:android.content.Context,intent:android.content.Intent){if(intent.action==android.content.Intent.ACTION_SCREEN_OFF)externalCapture.deviceLocked()}}
    init{androidx.core.content.ContextCompat.registerReceiver(application,screenLockReceiver,android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)}
    val recordingRecovery=RecordingRecovery(application)
    var recordingJobs by mutableStateOf<List<JSONObject>>(emptyList()); private set
    private var processingRecording:File?=null
    private val documentStarts=mutableMapOf<String,Deferred<JSONObject>>()
    private fun warmDocument(local:String){
        val owner=user?.str("id")?:return
        if(documentStarts.containsKey(local))return
        documentStarts.keys.filter{it!=local}.toList().forEach{documentStarts.remove(it)?.cancel()}
        documentStarts[local]=viewModelScope.async{
            val cache="local-bind-$owner-$local"
            vault.read(cache)?.let{return@async JSONObject(it)}
            val request=local.removePrefix("local-")
            val response=api.call("/quick/start","POST",obj("mode" to "document","request_id" to request),request)as JSONObject
            if(user?.str("id")==owner)vault.write(cache,response.toString())
            response
        }
    }
    var autoStartRecording by mutableStateOf(false)
    /** Message from the recorder (microphone taken by a call, visit recorded in parts) to show again with the finished report. */
    private var recordingNotice:String?=null
    var recordingConsent by mutableStateOf(false)
    fun prepareRecordingContext(owner:String){
        bindDiagnostics()
        if(quickMode=="document" && !voiceCorrection && !voiceMessage)case?.let{vault.write("recording-context-$owner",obj("owner" to user?.str("id"),"case" to it,"template" to editorTemplate).toString())}
    }
    fun refreshRecordingJobs(){recordingJobs=recordingRecovery.list(user?.str("id")?:"sample")}
    var recoveryActionId by mutableStateOf<String?>(null); private set
    fun discardRecovery(job:JSONObject){
        val owner=user?.str("id")?:return
        val id=job.str("id")
        if(id.isBlank() || job.str("owner")!=owner || recoveryActionId!=null)return
        if(RecordingService.state.value.recording){notice="Stop the current recording before discarding unfinished work.";return}
        recoveryActionId=id
        viewModelScope.launch{
            val discarded=withContext(Dispatchers.IO){runCatching{recordingRecovery.discard(id,owner)}.getOrDefault(false)}
            if(user?.str("id")==owner){
                refreshRecordingJobs()
                notice=if(discarded)"Unfinished recording deleted from this device." else "This unfinished recording could not be deleted."
            }
            if(recoveryActionId==id)recoveryActionId=null
        }
    }
    private fun publishRecordingJob(job:JSONObject){
        if(job.str("owner")!=(user?.str("id")?:"sample"))return
        recordingJobs=(listOf(job)+recordingJobs.filter{it.str("id")!=job.str("id")}).sortedByDescending{it.optLong("updated_at")}
    }
    fun rememberRecording(file:File,mime:String="audio/mp4"){
        if(quickMode!="document" || voiceCorrection || voiceMessage)return
        publishRecordingJob(recordingRecovery.remember(file,user?.str("id")?:"sample",case?:return,editorTemplate,mime))
    }
    private fun activeRecording():File?=processingRecording?.takeIf{file->recordingRecovery.read(file)?.let{job->
        job.str("owner")== (user?.str("id")?:"sample") && job.optJSONObject("case")?.optJSONObject("encounter")?.str("id")==encounterId
    }==true}
    private fun recordingStage(phase:String){
        val started=Performance.now()
        activeRecording()?.let{file->recordingRecovery.stage(file,phase,case,if(phase=="REPORT_READY")editor else null)?.let(::publishRecordingJob)}
        Performance.record("recovery_checkpoint",started)
        if(phase=="FAILED_RETRYABLE")DeveloperDiagnostics.emit(if(activeRecording()?.isFile==true)"recording_retained"else "recording_unavailable",status="failed")
    }
    fun resumeRecording(job:JSONObject){
        if(loading || job.str("owner")!=(user?.str("id")?:"sample"))return
        if(RecordingService.state.value.recording){notice="Stop the current recording before resuming saved work.";screen="record";return}
        val file=File(job.str("file"));if(!file.isFile){error="This recording is no longer available on this device.";return}
        persistEditor();case=JSONObject(job.getJSONObject("case").toString());restoreDraft();chooseQuickMode("document");updateTemplate(job.str("template","nursing"));screen="document_capture"
        processingRecording=file
        bindDiagnostics();DeveloperDiagnostics.retry()
        if(job.str("resume_from") in listOf("RECORDING","RECORDING_FINALIZATION_FAILED")){
            vault.write("capture-${user?.str("id")}-${encounterId}-audio",file.path)
            notice="Recording was interrupted. Listen to the saved audio before creating a report.";screen="record";return
        }
        if(job.str("resume_from")=="REPORT_READY"){if(editor.isBlank())edit(job.str("report"));screen="editor";return}
        if(job.str("resume_from") in listOf("TRANSCRIBED","GENERATING_REPORT")){
            runTask("Creating report",purpose="report",retry={resumeRecording(recordingRecovery.read(file)?:job)}){if(!demo && !encounterId.startsWith("local-"))reloadCase();draftQuick(true)}
        }else upload(file,job.str("mime","audio/mp4"),"audio")
    }
    internal var cancelledRecordingPath:String?=null
    val wearBridge by lazy { WearPhoneBridge(this,getApplication(),viewModelScope) }
    val pinLock=PinLock(vault)
    var locked by mutableStateOf(vault.read("session")!=null)
    fun unlock(){locked=false;wearBridge.consumeOpen();externalCapture.resumeDeferredPhoto()}
    fun lock(){if(!demo && user!=null)locked=true}
    var config by mutableStateOf(JSONObject()); private set
    var user by mutableStateOf<JSONObject?>(null); private set
    var legalRequired by mutableStateOf(false)
    var demo by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var quickMode by mutableStateOf<String?>(null); private set
    var consultantMessages by mutableStateOf<List<JSONObject>>(emptyList()); private set
    /** Done on Ask Consultant asks whether to turn the conversation into a report instead of losing it. */
    var consultantReportPrompt by mutableStateOf(false)
    private var reportFromConsultation=false
    var consultantDraft by mutableStateOf(""); private set
    var consultantReset by mutableIntStateOf(0); private set
    var transfer by mutableStateOf<JSONObject?>(null)
    var voiceCommandsOpen by mutableStateOf(false)
    var voiceInstruction by mutableStateOf("")
    var voiceCorrection by mutableStateOf(false); private set
    var voiceMessage by mutableStateOf(false); private set
    var reportLength by mutableStateOf("concise")
    var taskLabel by mutableStateOf("Working"); private set
    var error by mutableStateOf<String?>(null)
    var retryAvailable by mutableStateOf(false); private set
    var retryAtMillis by mutableLongStateOf(0); private set
    private var failedAction:(()->Unit)?=null
    private var taskRecovery:(()->Unit)?=null
    private var taskPurpose:String?=null
    private var consecutiveFailures=0
    var notice by mutableStateOf<String?>(null)
    var screen by mutableStateOf("welcome")
    var dark by mutableStateOf(vault.read("theme") != "light")
    var patients by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var encounters by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var case by mutableStateOf<JSONObject?>(null); private set
    var billing by mutableStateOf(JSONObject()); private set
    var audit by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var documentAlerts by mutableStateOf<List<String>>(emptyList()); private set
    var assessmentReview by mutableStateOf<JSONObject?>(null)
    var assessmentRevision by mutableIntStateOf(0); private set
    var assessmentEncounter by mutableStateOf(""); private set
    var editor by mutableStateOf(""); private set
    private var templateValue by mutableStateOf("nursing")
    private var customNameValue by mutableStateOf("")
    private var customInstructionsValue by mutableStateOf("")
    var editorTemplate:String get()=templateValue; set(value){if(templateValue!=value){templateValue=value;metadataChanged()}}
    var customName:String get()=customNameValue; set(value){if(customNameValue!=value){customNameValue=value;metadataChanged()}}
    var customInstructions:String get()=customInstructionsValue; set(value){if(customInstructionsValue!=value){customInstructionsValue=value;metadataChanged()}}
    var savedId by mutableStateOf<String?>(null)
    private var amendIdValue by mutableStateOf<String?>(null)
    var amendId:String? get()=amendIdValue; set(value){if(amendIdValue!=value){amendIdValue=value;metadataChanged()}}
    private var amendReasonValue by mutableStateOf("")
    var amendReason:String get()=amendReasonValue; set(value){if(amendReasonValue!=value){amendReasonValue=value;metadataChanged()}}
    var answer by mutableStateOf<JSONObject?>(null); private set
    var localRevision by mutableIntStateOf(0)
    var editorCaseRevision by mutableIntStateOf(0)
    val encounterId get()=case?.optJSONObject("encounter")?.str("id") ?: ""
    val revision get()=case?.optJSONObject("encounter")?.optInt("revision") ?: 0
    private fun storeKey(kind:String)="$kind-${user?.str("id") ?: "sample"}-$encounterId"
    private var draftJob:Job?=null
    private var editorOwner:String?=null
    private var restoring=false
    @Volatile private var draftGeneration=0
    var hasRecoverableDraft by mutableStateOf(false); private set
    private val demoCases=linkedMapOf<String,JSONObject>()
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun now()=Instant.now().toString()
    private fun clearClinical(){
        processingRecording=null;recordingConsent=false;autoStartRecording=false
        documentAlerts=emptyList();assessmentReview=null;assessmentEncounter=""
        voiceMessage=false;dismissError()
        draftGeneration++;draftJob?.cancel();editorOwner=null;restoring=true
        quickMode=null;consultantMessages=emptyList();consultantDraft="";voiceCorrection=false;voiceInstruction="";transfer=null;case=null;patients=emptyList();encounters=emptyList();billing=JSONObject();audit=emptyList();answer=null
        editor="";editorTemplate="nursing";customName="";customInstructions="";savedId=null;amendId=null;amendReason=""
        editorCaseRevision=0;localRevision++;hasRecoverableDraft=false;notice=null;restoring=false
    }
    private fun metadataChanged(){if(!restoring){vault.write("preferred-format-${user?.str("id")?:"sample"}",editorTemplate);savedId=null;localRevision++;persistEditor()}}
    fun updateTemplate(value:String){editorTemplate=value}
    fun updateCustomTemplate(name:String,instructions:String){restoring=true;customName=name;customInstructions=instructions;restoring=false;metadataChanged()}
    fun updateAmendmentReason(value:String){amendReason=value}
    private fun publishDemo(){
        if(!demo)return
        case?.let{demoCases[it.getJSONObject("encounter").str("id")]=JSONObject(it.toString())}
        encounters=demoCases.values.map{JSONObject(it.getJSONObject("encounter").toString()).put("storage_state",if(it.items("documents").isNotEmpty() || it.getJSONObject("encounter").optJSONObject("draft_data")?.str("retention")=="saved")"saved" else "temporary")}.sortedByDescending{storedInstant(it.str("updated_at"))?:Instant.EPOCH}
        patients=demoCases.values.map{JSONObject(it.getJSONObject("patient").toString())}.distinctBy{it.str("id")}
        vault.write("sample-cases-v1",JSONArray(demoCases.values.toList()).toString())
    }
    fun demoWounds(patientId:String)=demoCases.values.mapNotNull{it.optJSONObject("wound")}.filter{it.str("patient_id")==patientId}.distinctBy{it.str("id")}
    private fun snapshotDraft(){persistEditor();if(editor.isNotBlank() && savedId==null){vault.write(storeKey("draft-recovery"),draftJson().toString());hasRecoverableDraft=true}}
    fun restorePreviousDraft(){
        val d=vault.read(storeKey("draft-recovery"))?.let{runCatching{JSONObject(it)}.getOrNull()} ?: return
        val old=draftJson();vault.write(storeKey("draft"),d.toString());restoreDraft();vault.write(storeKey("draft-recovery"),old.toString());hasRecoverableDraft=true;screen="editor"
    }
    private fun matchesEditor(d:JSONObject)=d.str("text")==editor && d.str("template")==editorTemplate && d.str("amends_id")==amendId.orEmpty() &&
        d.str("reason")==amendReason && (editorTemplate!="custom" || d.optJSONObject("custom_template")?.let{it.str("name")==customName.trim()&&it.str("instructions")==customInstructions.trim()}==true)
    private suspend fun refreshAcknowledged(){
        try{reloadCase()}catch(e:Exception){if(e is CancellationException)throw e;if(e is ApiException&&e.status in listOf(401,428))throw e;notice=listOfNotNull(notice,"The change was accepted. Refresh to load the latest encounter state.").joinToString("\n")}
    }
    fun dismissError(){error=null;failedAction=null;retryAvailable=false;retryAtMillis=0}
    fun retryFailed(){if(loading || System.currentTimeMillis()<retryAtMillis)return;val action=failedAction?:return;DeveloperDiagnostics.retry();dismissError();action()}
    private fun taskRecovery(purpose:String,retry:()->Unit){taskPurpose=purpose;taskRecovery=retry}
    fun runTask(message:String="Working",purpose:String?=null,retry:(()->Unit)?=null,block:suspend ()->Unit){
        if(loading)return
        bindDiagnostics()
        taskLabel=message;loading=true;dismissError();notice=null;taskPurpose=purpose;taskRecovery=retry
        val owner=user?.str("id");val encounter=encounterId
        val foreground=purpose in listOf("audio","report","photo") && !demo
        if(foreground)runCatching{ProcessingService.start(getApplication())}
        (if(foreground)ProcessingService.scope else viewModelScope).launch {
            try{block();consecutiveFailures=0}catch(e:Exception){
                if(e is CancellationException)throw e
                if(taskPurpose in listOf("audio","report"))runCatching{recordingStage("FAILED_RETRYABLE")}
                val hasRecording=activeRecording()?.let{it.isFile && it.length()>128}==true
                val readable=clinicianError(e,taskPurpose)+if(hasRecording && taskPurpose in listOf("audio","report"))"\nYour recording is saved on this device."else ""
                if(e is ApiException && e.status==401){persistEditor();api.clear();clearClinical();demo=false;user=null;legalRequired=false;screen="login"}
                if(e is ApiException && e.status==428)legalRequired=true
                consecutiveFailures++
                val reference=(e as? ApiException)?.requestId?.let{runCatching{ "WC-"+((it.replace("-", "").take(8).toLong(16))%1_000_000).toString().padStart(6,'0') }.getOrNull()}
                error=readable+if(consecutiveFailures>1 && reference!=null)"\nSupport reference: $reference"else ""
                if(canRetryRequest(e) && taskRecovery!=null && owner==user?.str("id") && (encounter==encounterId || encounter.startsWith("local-"))){
                    val action=taskRecovery!!
                    val retryEncounter=encounterId
                    failedAction={if(owner==user?.str("id") && retryEncounter==encounterId)action()}
                    retryAvailable=true
                    retryAtMillis=System.currentTimeMillis()+((e as? ApiException)?.retryAfterSeconds?.coerceAtLeast(0)?.toLong()?:0L)*1000L
                }
            }finally{if(foreground)ProcessingService.stop(getApplication());loading=false;taskRecovery=null;taskPurpose=null;if(demo)runCatching{publishDemo()}.onFailure{notice="Sample recovery could not be saved on this device."};externalCapture.resumeDeferredPhoto()}
        }
    }
    init {
        viewModelScope.launch{while(isActive){delay(15000);val owner=user?.str("id");if(owner!=null&&!demo)DeveloperDiagnostics.flush(api,vault,owner)}}
        // Processing follows capture state, including while the Activity is locked/backgrounded.
        // A fresh process never auto-submits an interrupted or previously completed recording.
        viewModelScope.launch {
            var observed:String?=null
            combine(RecordingService.state,snapshotFlow{loading}){capture,busy->capture to busy}.collect{(capture,busy)->
                if(capture.recording)observed=capture.filePath
                else if(capture.filePath!=null && capture.filePath==observed && !busy){
                    observed=null
                    val file=File(capture.filePath)
                    val owner=vault.read("recording-owner-${file.name}")
                    // Keep a microphone-interruption or long-visit message visible after the report appears.
                    recordingNotice=capture.notice;capture.notice?.let{notice=it}
                    if(file.path!=cancelledRecordingPath && vault.read("wear-cancel-${file.name}")==null && owner=="${user?.str("id")}:$encounterId" && quickMode=="document" && !voiceCorrection && !voiceMessage && recordingConsent && capture.error==null){
                        upload(file,"audio/mp4","audio")
                    }else refreshRecordingJobs()
                }
            }
        }
        val cached=if(vault.read("session")!=null)vault.read("cached-account")?.let{runCatching{JSONObject(it)}.getOrNull()}else null
        if(cached==null){if(vault.read("session")!=null)resumeStoredSession()else loadPublicConfig()}else{
            setSession(cached);screen="home";refreshRecordingJobs()
            // Offline capture is local and PIN-protected; server access still validates its session.
            val expectedOwner=user?.str("id")
            viewModelScope.launch{
                try{
                    val session=api.call("/auth/session")as JSONObject
                    if(user?.str("id")!=expectedOwner || demo)return@launch
                    setSession(session);refreshHomeInBackground();loadPublicConfig()
                }
                catch(e:Exception){
                    if(e is CancellationException)throw e
                    if(user?.str("id")!=expectedOwner || demo)return@launch
                    if(e is ApiException && e.status==401){vault.remove("cached-account");api.clear();clearClinical();user=null;screen="login"}
                    else notice="Recording is available on this device. Processing needs a connection."
                }
            }
        }
    }
    private fun bindDiagnostics(){
        val owner=user?.str("id")
        if(owner==null || demo || encounterId.isBlank()){api.diagnosticJob=null;DeveloperDiagnostics.clear();return}
        api.diagnosticJob=DeveloperDiagnostics.bind(vault,owner,encounterId,processingRecording ?: RecordingService.state.value.filePath?.let(::File))
        api.diagnosticGeneration=runCatching{vault.read("diagnostic-generation-$owner-$encounterId")}.getOrNull()
    }
    private fun loadPublicConfig(){
        viewModelScope.launch{
            try{config=api.call("/config")as JSONObject}
            catch(e:Exception){if(e is CancellationException)throw e}
        }
    }
    private fun refreshHomeInBackground(){
        val owner=user?.str("id")?:return
        viewModelScope.launch{
            try{if(!legalRequired)refreshHome()}
            catch(e:Exception){if(e is CancellationException)throw e;if(user?.str("id")==owner && !demo)notice="Saved work could not be refreshed. Recording is still available on this device."}
        }
    }
    fun resumeStoredSession():Unit=runTask("Connecting",retry={resumeStoredSession()}){
        if(vault.read("session")!=null){screen="reconnect";setSession(api.call("/auth/session")as JSONObject);screen="home";refreshHomeInBackground()}
        loadPublicConfig()
    }
    fun toggleTheme(){dark=!dark;vault.write("theme",if(dark)"dark" else "light")}
    private fun setSession(s:JSONObject){val incoming=s.optJSONObject("user") ?: throw ApiException(502,"The server did not return a valid account.");if(demo || user?.str("id")!=incoming.str("id")){persistEditor();clearClinical()};demo=false;user=incoming;if(vault.read("pin-owner")!=incoming.str("id")){pinLock.clear();vault.write("pin-owner",incoming.str("id"));locked=true};if(!pinLock.configured)locked=true;legalRequired=s.optBoolean("requires_legal_acceptance");vault.write("cached-account",obj("user" to incoming,"requires_legal_acceptance" to legalRequired).toString());refreshRecordingJobs()}
    suspend fun refreshSession(){setSession(api.call("/auth/session") as JSONObject)}
    fun login(email:String,password:String)=runTask {persistEditor();clearClinical();demo=false;user=null;api.clear();setSession(api.call("/auth/login","POST",obj("email" to email.trim(),"password" to password)) as JSONObject);screen="home";refreshHomeInBackground()}
    fun signup(email:String,password:String,name:String,workspace:String)=runTask {persistEditor();clearClinical();demo=false;user=null;api.clear();val version=config.str("legal_version");setSession(api.call("/auth/signup","POST",obj("email" to email.trim(),"password" to password,"display_name" to name,"workspace_name" to workspace,"accept_terms" to true,"terms_version" to version,"privacy_version" to version)) as JSONObject);refreshHome();screen="home"}
    fun acceptLegal()=runTask {api.call("/auth/accept-legal","POST",obj("accept_terms" to true,"terms_version" to config.str("legal_version"),"privacy_version" to config.str("legal_version")));legalRequired=false;refreshHome();screen="home"}
    suspend fun refreshHome(){
        if(demo){publishDemo();return}
        val owner=user?.str("id")?:return
        coroutineScope{
            val p=async{(api.call("/patients")as JSONArray).objects()}
            val e=async{(api.call("/work")as JSONArray).objects()}
            val b=async{api.call("/billing")as JSONObject}
            val people=p.await();val work=e.await();val credits=b.await()
            if(user?.str("id")==owner && !demo){patients=people;encounters=work;billing=credits}
        }
    }
    fun refresh()=runTask {if(user==null){config=api.call("/config") as JSONObject}else if(case!=null && screen !in listOf("home","saved","account","new")){reloadCase()}else refreshHome()}
    fun startDemo(){
        if(loading)return
        persistEditor();api.clear();clearClinical();demo=true;user=obj("id" to "sample","display_name" to "Sample clinician","email" to "Synthetic preview","workspace_id" to "sample")
        demoCases.clear()
        val stored=vault.read("sample-cases-v1")?.let{runCatching{JSONArray(it).objects()}.getOrNull()}
        (stored?.takeIf{it.isNotEmpty()}?:listOf(DemoData.caseData())).forEach{demoCases[it.getJSONObject("encounter").str("id")]=it}
        case=JSONObject(demoCases.values.first().toString());publishDemo();restoreDraft();billing=obj("is_unlimited" to true);screen="home";error=null;notice=null;legalRequired=false
    }
    fun openEncounter(id:String)=runTask {
        persistEditor();voiceCorrection=false;voiceMessage=false;voiceInstruction=""
        case=if(demo)JSONObject((demoCases[id]?:throw IllegalArgumentException("Sample encounter not found.")).toString()) else api.call("/encounters/$id") as JSONObject
        answer=null;audit=emptyList();restoreDraft();restoreQuick();screen=quickDestination();refreshRecordingJobs()
    }
    suspend fun reloadCase(){if(!demo){if(encounterId.startsWith("local-") && RecordingService.state.value.recording){notice="Recording continues on this device. Refresh after stopping.";return};ensureRemoteDocument();case=api.call("/encounters/$encounterId") as JSONObject;val e=case!!.getJSONObject("encounter");encounters=(listOf(e)+encounters.filter{it.str("id")!=e.str("id")}).sortedByDescending{it.str("updated_at")}}}
    fun createEncounter(patientId:String?,name:String,external:String,woundId:String?,woundLabel:String,title:String,onPatient:(String)->Unit,onWound:(String)->Unit)=runTask {
        persistEditor();quickMode=null;voiceCorrection=false;voiceMessage=false;voiceInstruction="";answer=null;audit=emptyList()
        if(demo){
            val p=patients.firstOrNull{it.str("id")==patientId} ?: obj("id" to ("demo-patient-"+UUID.randomUUID()),"display_name" to name.ifBlank{"Sample patient"},"external_ref" to "SAMPLE ONLY","created_at" to now())
            onPatient(p.str("id"))
            val w=if(woundId!=null)demoWounds(p.str("id")).firstOrNull{it.str("id")==woundId}else if(woundLabel.isNotBlank())obj("id" to ("demo-wound-"+UUID.randomUUID()),"patient_id" to p.str("id"),"label" to woundLabel,"location" to null,"laterality" to null,"wound_type" to null)else null
            w?.let{onWound(it.str("id"))}
            val e=obj("id" to ("demo-encounter-"+UUID.randomUUID()),"patient_id" to p.str("id"),"wound_id" to w?.str("id"),"title" to title.ifBlank{"Sample encounter"},"status" to "open","revision" to 1,"draft_data" to obj(),"created_at" to now(),"updated_at" to now())
            case=obj("encounter" to e,"patient" to p,"wound" to w,"evidence" to JSONArray(),"facts" to JSONArray(),"documents" to JSONArray(),"review" to null,"conflicts" to JSONArray())
            publishDemo();restoreDraft();screen="encounter";return@runTask
        }
        val creationKey="creation-"+user!!.str("id")
        val prior=vault.read(creationKey)?.let{runCatching{JSONObject(it)}.getOrNull()}
        val compatible=prior!=null && prior.str("name")==name && prior.str("external")==external && prior.str("label")==woundLabel && prior.str("title")==title &&
            (patientId==null || patientId==prior.str("patient_id")) && (woundId==null || woundId==prior.str("wound_id"))
        val journal=if(compatible)prior!! else obj("name" to name,"external" to external,"label" to woundLabel,"title" to title,"patient_id" to patientId,"wound_id" to woundId)
        vault.write(creationKey,journal.toString())
        val pid=journal.str("patient_id").ifBlank{patientId.orEmpty()}.ifBlank{
            (api.call("/patients","POST",obj("display_name" to name,"external_ref" to external.ifBlank{null})) as JSONObject).str("id")
                .also{journal.put("patient_id",it);vault.write(creationKey,journal.toString())}
        }
        journal.put("patient_id",pid);vault.write(creationKey,journal.toString());onPatient(pid)
        val wid=journal.str("wound_id").ifBlank{woundId.orEmpty()}.ifBlank{
            if(woundLabel.isBlank())"" else(api.call("/patients/$pid/wounds","POST",obj("label" to woundLabel)) as JSONObject).str("id")
                .also{journal.put("wound_id",it);vault.write(creationKey,journal.toString())}
        }.ifBlank{null}
        if(wid!=null){journal.put("wound_id",wid);vault.write(creationKey,journal.toString());onWound(wid)}
        val created=journal.optJSONObject("encounter") ?: (api.call("/encounters","POST",obj("patient_id" to pid,"wound_id" to wid,"title" to title.ifBlank{"Wound care encounter"})) as JSONObject)
            .also{journal.put("encounter",it);vault.write(creationKey,journal.toString())}
        encounters=listOf(created)+encounters.filter{it.str("id")!=created.str("id")}
        case=api.call("/encounters/"+created.str("id")) as JSONObject
        vault.remove(creationKey);restoreDraft();screen="encounter"
    }
    private fun warnings(result:JSONObject){val messages=result.optJSONArray("warnings");if(messages!=null&&messages.length()>0)notice=prettyValue(messages)}
    private fun canonical(value:Any?):String=when(value){
        is JSONObject->value.keys().asSequence().toList().sorted().joinToString(prefix="{",postfix="}"){JSONObject.quote(it)+":"+canonical(value.opt(it))}
        is JSONArray->(0 until value.length()).joinToString(prefix="[",postfix="]"){canonical(value.opt(it))}
        null,JSONObject.NULL->"null";is String->JSONObject.quote(value);else->value.toString()
    }
    private fun journalKey(intent:String,payload:JSONObject):String{
        val identity=JSONObject(payload.toString());if(intent=="save")identity.remove("expected_revision")
        return storeKey("pending-$intent-"+hash(canonical(identity)))
    }
    private suspend fun mutation(intent:String,path:String,payload:JSONObject):JSONObject{
        val storage=journalKey(intent,payload)
        val record=vault.read(storage)?.let{runCatching{JSONObject(it)}.getOrNull()} ?: obj("key" to UUID.randomUUID().toString(),"payload" to JSONObject(payload.toString()))
        vault.write(storage,record.toString())
        record.optJSONObject("response")?.let{return JSONObject(it.toString())}
        try{
            val response=api.call(path,"POST",record.getJSONObject("payload"),record.str("key")) as JSONObject
            record.put("response",response);vault.write(storage,record.toString());return response
        }catch(e:ApiException){if(!e.ambiguous && e.status in listOf(400,402,403,404,409,413,415,422,429,502,503))vault.remove(storage);throw e}
    }
    private fun acknowledge(intent:String,payload:JSONObject){vault.remove(journalKey(intent,payload))}
    private fun mergeSource(r:JSONObject){case=mergeEvidenceResponse(case ?: throw IllegalStateException("No open encounter"),r)}
    fun addEvidence(payload:JSONObject)=runTask {
        ensureRemoteDocument()
        if(demo){val copy=JSONObject(case.toString());val eid=UUID.randomUUID().toString();copy.getJSONArray("evidence").put(obj("id" to eid,"kind" to payload.str("kind"),"text" to payload.str("text"),"metadata" to JSONObject(),"warnings" to JSONArray()));payload.items("findings").forEach{copy.getJSONArray("facts").put(obj("id" to UUID.randomUUID().toString(),"revision" to 1,"name" to it.str("name"),"value" to it.optJSONObject("value"),"confirmation_state" to "confirmed","source_type" to "clinician_manual","source_ref" to eid))};copy.getJSONObject("encounter").put("revision",revision+1);case=copy;notice="Saved in the synthetic preview."}
        else{val r=mutation("evidence","/encounters/$encounterId/evidence",payload);mergeSource(r);warnings(r);acknowledge("evidence",payload);screen="encounter";refreshAcknowledged()};screen="encounter";afterQuickSource(payload.str("kind"))
    }
    private suspend fun uploadResponse(keyName:String,block:suspend ()->JSONObject):JSONObject{
        try{return block()}catch(e:ApiException){
            // Definitive rejection needs a fresh attempt. Keep ambiguous timeouts replayable.
            if(e.status in listOf(400,403,404,413,415,422))vault.remove(keyName)
            throw e
        }
    }
    private fun clearCaptureSelection(kind:String){
        val key="capture-${user?.str("id")}-${encounterId}-$kind"
        vault.remove(key);vault.remove("$key-mime");vault.remove("$key-review")
    }
    fun upload(file:File,mime:String,kind:String,photoReview:JSONObject?=null):Unit=runTask(when(kind){"audio"->"Transcribing recording";"photo"->"Assessing your photo";else->"Reading your document"},purpose=kind,retry={upload(file,mime,kind,photoReview)}) {uploadNow(file,mime,kind,photoReview)}
    private suspend fun uploadNow(file:File,mime:String,kind:String,photoReview:JSONObject?=null){
        if(kind=="audio" && quickMode=="document" && !voiceCorrection && !voiceMessage){rememberRecording(file,mime);processingRecording=file;recordingStage("WAITING_FOR_UPLOAD")}
        ensureRemoteDocument()
        if(kind=="audio" && quickMode=="document" && !voiceCorrection && !voiceMessage)recordingStage("UPLOADING")
        val uploadOwner=user?.str("id");val uploadEncounter=encounterId
        val photoStart=Performance.now()
        val photoJob=uploadOwner?.let{DeveloperDiagnostics.job(vault,it,uploadEncounter)}
        fun photoEvent(stage:String,status:String){if(kind=="photo" && uploadOwner!=null && photoJob!=null)runCatching{DeveloperDiagnostics.photo(vault,uploadOwner,photoJob,file,stage,photoStart,status)}}
        photoEvent("photo_source","success")
        val digest=withContext(Dispatchers.IO){val md=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)}};md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}}
        val captureKind=if(kind=="audio" && voiceMessage)"message"else if(kind=="audio" && voiceCorrection)"correction"else if(kind=="document")"file"else kind
        val identity=obj("file" to file.name,"hash" to digest,"mime" to mime,"kind" to kind,"photo_review" to photoReview)
        if(captureKind in listOf("message","correction"))identity.put("purpose",captureKind)
        val keyName=storeKey("upload-"+hash(canonical(identity)));val key=vault.read(keyName) ?: UUID.randomUUID().toString().also{vault.write(keyName,it)}
        if((voiceCorrection || voiceMessage) && kind=="audio"){
            val r=if(demo)obj("text" to if(voiceMessage)"What else would you want to know?"else "Make the report more concise.","warnings" to JSONArray().put("Sample only. No transcription was performed."))else uploadResponse(keyName){api.multipart("/encounters/$encounterId/transcribe-$captureKind",file,mime,mapOf("consent" to "true"),key)}
            require(demo || r.optInt("case_revision")==revision){"Your sources changed. Your recording is saved; review the current conversation before trying again."}
            if(voiceMessage){updateConsultantDraft(listOf(consultantDraft,r.str("text")).filter{it.isNotBlank()}.joinToString("\n\n"));consultantReset++}
            else voiceInstruction=r.str("text")
            warnings(r);vault.remove(keyName);clearCaptureSelection(captureKind)
            if(file.canonicalFile.parentFile==File(getApplication<Application>().filesDir,"recordings").canonicalFile && file.extension=="m4a")RecordingService.clearPending(getApplication(),file.absolutePath)
            screen=if(voiceMessage)"consultant"else "voice_correction";voiceMessage=false;return
        }
        if(demo){val copy=JSONObject(case.toString());val id=UUID.randomUUID().toString();copy.getJSONArray("evidence").put(obj("id" to id,"kind" to kind,"text" to "Synthetic preview: no AI request was sent.","created_at" to now(),"metadata" to obj("filename" to file.name,"mime" to mime,"media_hash" to digest,"raw_media_retained" to false,"photo_review" to photoReview)));copy.getJSONObject("encounter").put("revision",revision+1);case=copy;vault.write(storeKey("source-$id"),file.absolutePath);notice="Capture kept on this device. No provider request was sent."}
        else {
            // A visit longer than about an hour was recorded in parts. Transcribe EVERY part, in recording order, before the one report.
            val visitParts=(if(kind=="audio" && quickMode=="document" && !voiceCorrection && !voiceMessage)visitRecordings(file) else listOf(file))
                .takeIf{parts->parts.any{it.canonicalPath==file.canonicalPath}} ?: listOf(file)
            val position=visitParts.indexOfFirst{it.canonicalPath==file.canonicalPath}
            val visitId=hash("visit:"+visitParts.first().name).take(32)
            val visitPosition=if(visitParts.size>1)VisitPosition(position+1,visitParts.size,visitId) else null
            if(visitParts.size>1)visitParts.take(position).forEachIndexed{index,part->transcribeVisitPart(part,mime,VisitPosition(index+1,visitParts.size,visitId))}
            val r=try {
                uploadResponse(keyName){
                    if(kind=="audio" && file.extension.lowercase() in listOf("m4a","mp4"))
                        AudioChunks(api,vault).transcribe(encounterId,file,"${user?.str("id")}-${encounterId}-$key",visitPosition){taskLabel=if(visitPosition!=null)"Part ${visitPosition.part} of ${visitPosition.parts}: $it" else it}
                    else api.upload(encounterId,file,mime,kind,key,photoReview)
                }.also { if(kind=="photo")requireValidPhotoUpload(it) }
            }catch(e:Exception){
                photoEvent("photo_retained",if(file.isFile && file.length()>0)"success"else "failed")
                photoEvent("photo_request","failed")
                throw e
            }
            if(kind=="photo" && (uploadOwner!=user?.str("id") || uploadEncounter!=encounterId))
                throw ApiException(409,"The active visit changed. Reopen the original visit to see its photo assessment.")
            photoEvent("photo_request","success")
            r.optJSONObject("evidence")?.str("id")?.let{vault.write(storeKey("source-$it"),file.absolutePath)}
            mergeSource(r);warnings(r);screen="encounter"
            if(visitParts.size>1)visitParts.drop(position+1).forEachIndexed{index,part->transcribeVisitPart(part,mime,VisitPosition(position+index+2,visitParts.size,visitId))}
            // Photo response already includes the evidence, findings and revision.
            // Avoid an extra network round-trip before displaying that assessment.
            if(kind!="photo" && (kind!="audio" || quickMode!="document"))refreshAcknowledged()
        }
        if(kind=="audio" && quickMode=="document")recordingStage("TRANSCRIBED")
        if(kind=="audio" && quickMode!="document" && file.canonicalFile.parentFile==File(getApplication<Application>().filesDir,"recordings").canonicalFile && file.extension=="m4a")
            RecordingService.clearPending(getApplication(),file.absolutePath)
        if(kind!="audio" || quickMode!="document"){vault.remove(keyName);clearCaptureSelection(captureKind)};screen="encounter";afterQuickSource(kind);photoEvent("photo_state","success")
    }
    /**
     * All recordings of the visit [file] belongs to, in recording order (one file for an ordinary recording). If a part is not on
     * this phone, stop with a clear message rather than make a report that silently leaves part of the visit out. Nothing is deleted.
     */
    private fun visitRecordings(file:File):List<File>{
        val names=crashSafeBackend()?.visitParts(file)?:return listOf(file)
        if(names.size<=1)return listOf(file)
        val parts=names.map{File(file.parentFile,it)}
        val missing=parts.withIndex().filter{!it.value.isFile}.map{it.index+1}
        require(missing.isEmpty()){"This visit was recorded in ${parts.size} parts, but part ${missing.joinToString(" and ")} is not on this phone, so one complete report cannot be made. The other parts are kept; nothing was deleted."}
        return parts
    }
    /** Transcribes one part of a multi-part visit and adds it to the visit's sources. A part already done is not sent again. */
    private suspend fun transcribeVisitPart(part:File,mime:String,visit:VisitPosition){
        val number=visit.part;val count=visit.parts
        val digest=withContext(Dispatchers.IO){val md=MessageDigest.getInstance("SHA-256");part.inputStream().use{input->val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)}};md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}}
        val identity=obj("file" to part.name,"hash" to digest,"mime" to mime,"kind" to "audio","photo_review" to null)
        val keyName=storeKey("upload-"+hash(canonical(identity)));val key=vault.read(keyName) ?: UUID.randomUUID().toString().also{vault.write(keyName,it)}
        taskLabel="Transcribing part $number of $count"
        val r=uploadResponse(keyName){AudioChunks(api,vault).transcribe(encounterId,part,"${user?.str("id")}-${encounterId}-$key",visit){taskLabel="Part $number of $count: $it"}}
        r.optJSONObject("evidence")?.str("id")?.let{vault.write(storeKey("source-$it"),part.absolutePath)}
        mergeSource(r);warnings(r)
    }
    fun uploadPhotos(files:List<Pair<File,String>>):Unit=runTask("Adding photos",purpose="photo",retry={uploadPhotos(files)}){
        vault.write(storeKey("photo-batch-pending"),JSONArray(files.map{obj("path" to it.first.path,"mime" to it.second)}).toString())
        val acknowledged=vault.read(storeKey("photo-batch-done"))?.let{JSONArray(it)}?:JSONArray()
        val done=(0 until acknowledged.length()).map{acknowledged.getString(it)}.toMutableSet()
        for((file,mime) in files){
            if(file.absolutePath in done)continue
            uploadNow(file,mime,"photo")
            done+=file.absolutePath;vault.write(storeKey("photo-batch-done"),JSONArray(done.toList()).toString())
            vault.write(storeKey("photo-batch-pending"),JSONArray(files.filter{it.first.path !in done}.map{obj("path" to it.first.path,"mime" to it.second)}).toString())
        }
    }
    fun pendingPhotoFiles():List<Pair<File,String>> = vault.read(storeKey("photo-batch-pending"))?.let{raw->runCatching{JSONArray(raw).objects().map{File(it.str("path")) to it.str("mime")}.filter{it.first.isFile}}.getOrNull()}?:emptyList()
    private fun quickDestination()=when(quickMode){"document"->if(editor.isNotBlank())"editor" else "document_capture";"photo"->if(case?.items("evidence")?.any{it.str("kind")=="photo"}==true)"photo_assessment" else "photo";"consultant"->"consultant";else->"encounter"}
    private fun restoreQuick(){
        quickMode=vault.read(storeKey("quick-mode"))?.takeIf{it in listOf("document","photo","consultant")} ?: case?.optJSONObject("encounter")?.optJSONObject("draft_data")?.str("quick_mode")?.takeIf{it.isNotBlank()}
        consultantMessages=vault.read(storeKey("consult-chat"))?.let{runCatching{JSONArray(it).objects()}.getOrNull()}?:emptyList()
        consultantDraft=vault.read(storeKey("consult-draft"))?:"";consultantReset++
    }
    fun chooseQuickMode(mode:String){quickMode=mode;vault.write(storeKey("quick-mode"),mode)}
    fun startQuick(mode:String){
        if(loading)return
        reportFromConsultation=false;consultantReportPrompt=false
        processingRecording=null
        recordingConsent=false
        if(mode=="document" && !demo){
            Performance.start("document_open")
            persistEditor();voiceCorrection=false;voiceMessage=false;voiceInstruction="";dismissError();notice=null
            val id="local-${UUID.randomUUID()}"
            case=obj("patient" to obj("id" to id,"display_name" to "Untitled visit"),"encounter" to obj("id" to id,"patient_id" to id,"title" to "Documentation","revision" to 1,"status" to "open","updated_at" to now(),"draft_data" to obj("quick_mode" to "document","retention" to "temporary")),"evidence" to JSONArray(),"facts" to JSONArray(),"documents" to JSONArray(),"review" to null,"conflicts" to JSONArray())
            answer=null;audit=emptyList();restoreDraft();chooseQuickMode(mode);screen="document_capture";warmDocument(id);return
        }
        startRemoteQuick(mode)
    }
    private suspend fun ensureRemoteDocument(){
        if(demo || !encounterId.startsWith("local-"))return
        check(!RecordingService.state.value.recording){"Stop the recording before processing this visit."}
        val local=encounterId;val request=local.removePrefix("local-");val format=editorTemplate;val mode=quickMode?:"document";val owner=user?.str("id")?:return
        val cache="local-bind-$owner-$local"
        val previous=documentStarts.remove(local)
        val response=vault.read(cache)?.let{JSONObject(it)} ?: if(previous!=null && !previous.isCancelled)previous.await()
            else api.call("/quick/start","POST",obj("mode" to "document","request_id" to request),request)as JSONObject
        check(user?.str("id")==owner && encounterId==local){"The active visit changed. Reopen your saved recording to continue."}
        vault.write(cache,response.toString())
        DeveloperDiagnostics.link(vault,owner,local,response.getJSONObject("encounter").str("id"))
        case=response;editorOwner=storeKey("draft");chooseQuickMode(mode);updateTemplate(format)
        bindDiagnostics()
        persistEditor()
        processingRecording?.let{file->vault.write("recording-owner-${file.name}","$owner:$encounterId");recordingRecovery.remember(file,owner,response,format)}
    }
    private fun startRemoteQuick(mode:String)=runTask("Opening ${if(mode=="consultant")"Ask Consultant" else mode}") {
        require(mode in listOf("document","photo","consultant"))
        persistEditor();voiceCorrection=false;voiceMessage=false;voiceInstruction=""
        val key="quick-start-${user?.str("id")?:"sample"}-$mode"
        val requestId=vault.read(key)?:UUID.randomUUID().toString().also{vault.write(key,it)}
        case=if(demo){
            val id="quick-$requestId";val p=obj("id" to "anonymous-sample","display_name" to "Untitled visit")
            obj("patient" to p,"wound" to null,"encounter" to obj("id" to id,"patient_id" to p.str("id"),"title" to when(mode){"document"->"Documentation";"photo"->"Photo assessment";else->"Wound Consultant"},"revision" to 1,"status" to "open","updated_at" to now(),"draft_data" to obj("quick_mode" to mode)),"evidence" to JSONArray(),"facts" to JSONArray(),"documents" to JSONArray(),"review" to null,"conflicts" to JSONArray())
        }else api.call("/quick/start","POST",obj("mode" to mode,"request_id" to requestId),requestId) as JSONObject
        answer=null;audit=emptyList();restoreDraft();restoreQuick();chooseQuickMode(mode)
        val encounter=case!!.getJSONObject("encounter");val patient=case!!.getJSONObject("patient")
        encounters=listOf(encounter)+encounters.filter{it.str("id")!=encounter.str("id")}
        patients=listOf(patient)+patients.filter{it.str("id")!=patient.str("id")}
        vault.remove(key)
        if(editor.isBlank()){restoring=true;editorTemplate="nursing";restoring=false}
        screen=quickDestination()
    }
    var deleteWorkId by mutableStateOf<String?>(null)
    var closeWorkPrompt by mutableStateOf(false)
    fun isSavedWork(row:JSONObject)=row.str("storage_state")=="saved" || row.optJSONObject("draft_data")?.str("retention")=="saved"
    private fun eraseLocalWork(id:String){
        recordingRecovery.complete(id,user?.str("id")?:"sample");refreshRecordingJobs()
        val context=getApplication<Application>();val owner="${user?.str("id")}:$id"
        // Only remove copies inside this app's private directories, never gallery originals.
        vault.names().filter{it.startsWith("capture-owner-")||it.startsWith("recording-owner-")}.forEach{key->
            val actual=vault.read(key).orEmpty()
            if(actual==owner || actual.startsWith("$owner:")){
                val name=key.substringAfter("owner-")
                for(folder in listOf("captures","recordings")){
                    val root=File(context.filesDir,folder).canonicalFile;val f=File(root,name).canonicalFile
                    if(f.parentFile==root){f.delete();File(f.path+".json").delete()}
                };vault.remove(key)
            }
        }
        vault.names().filter{it.contains("-${user?.str("id")}") && it.contains(id)}.forEach{key->
            if(key.startsWith("audio-batch-"))runCatching{
                val batch=JSONObject(vault.read(key)?:"{}");val files=batch.optJSONArray("files")?:JSONArray()
                for(index in 0 until files.length()){
                    val file=File(files.getString(index)).canonicalFile
                    if(file.path.startsWith(context.filesDir.canonicalPath+File.separator) && file.parentFile?.name=="segments")file.delete()
                }
            }
            vault.remove(key)
        }
        // Export caches contain no identifiers tying them to a case: clear this
        // device's copies on erasure; external destinations remain outside app control.
        File(context.cacheDir,"exports").listFiles()?.filter{it.isFile}?.forEach{it.delete()}
    }
    fun deleteWork(id:String)=runTask("Deleting work"){
        val ids=if(id=="all-saved"){
            if(demo)encounters.filter(::isSavedWork).map{it.str("id")}else{
                val result=api.call("/work/delete-all-saved","POST",obj("confirm" to true))as JSONObject
                val values=result.getJSONArray("ids");(0 until values.length()).map{values.getString(it)}
            }
        }else{if(!demo)api.call("/work/$id","DELETE",obj("confirm" to true));listOf(id)}
        ids.forEach{eraseLocalWork(it);demoCases.remove(it)}
        if(encounterId in ids)clearClinical()
        encounters=encounters.filter{it.str("id") !in ids};deleteWorkId=null;screen="saved";refreshHome()
        notice="Deleted from active app storage. Copies already exported or retained in backups are outside this deletion."
    }
    fun saveWorkAndClose()=runTask("Saving work"){
        ensureRemoteDocument()
        if(editor.isNotBlank() && savedId==null) {
            // Keep the close dialog open when a photo has changed the source revision.
            // No automatic approval of clinical changes or loss of the edited draft.
            try { saveDocumentNow() }
            catch(e:Exception) {
                closeWorkPrompt=true
                persistEditor()
                throw e
            }
        }
        if(!demo)api.call("/work/$encounterId/save","POST",JSONObject())
        case?.getJSONObject("encounter")?.put("storage_state","saved")?.getJSONObject("draft_data")?.put("retention","saved")
        recordingRecovery.complete(encounterId,user?.str("id")?:"sample");refreshRecordingJobs()
        closeWorkPrompt=false;persistEditor();quickMode=null;screen="home";refreshHome()
    }
    fun closeTemporaryWork()=runTask("Closing work"){
        val id=encounterId
        val result=if(demo || id.startsWith("local-"))obj("status" to if(savedId==null)"deleted"else "saved")else api.call("/work/$id/close","POST",JSONObject())as JSONObject
        if(result.str("status")=="deleted"){eraseLocalWork(id);demoCases.remove(id);clearClinical()}
        closeWorkPrompt=false;quickMode=null;screen="home";refreshHome()
    }
    fun doneQuick(){
        if(loading)return
        persistEditor();voiceCorrection=false;voiceMessage=false
        if(editor.isNotBlank() && savedId==null){closeWorkPrompt=true;return}
        if(quickMode=="photo" && case?.items("evidence")?.isNotEmpty()==true){closeTemporaryWork();return}
        if(savedId!=null){recordingRecovery.complete(encounterId,user?.str("id")?:"sample");refreshRecordingJobs()}
        quickMode=null;screen="home";notice=null;dismissError();if(demo)publishDemo()
    }
    fun quickConsultant(){if(loading)return;chooseQuickMode("consultant");screen="consultant";notice=null;dismissError()}
    /** Done on Ask Consultant: offer a report first when there is a conversation to keep. */
    fun finishConsultant(){
        if(loading)return
        if(quickMode=="consultant" && consultantMessages.isNotEmpty() && editor.isBlank()){consultantReportPrompt=true;return}
        doneQuick()
    }
    fun closeConsultantWithoutReport(){consultantReportPrompt=false;doneQuick()}
    /** Writes the selected report type from the whole consultation: questions, answers and attached photos and files. */
    fun reportFromConsultant(format:String):Unit=runTask("Creating report",purpose="report",retry={reportFromConsultant(format)}){
        consultantReportPrompt=false;reportFromConsultation=true
        recordingNotice=unreadableAttachments()
        updateTemplate(format);chooseQuickMode("document")
        draftQuick(true,format)
    }
    /** Names every attached photo or file that could not be read, so it is never silently left out. */
    private fun unreadableAttachments():String?{
        val sources=case?.items("evidence").orEmpty().filter{it.str("kind") in listOf("photo","document","result")}
        val photos=sources.filter{it.str("kind")=="photo"};val files=sources.filter{it.str("kind")!="photo"}
        val missing=photos.mapIndexedNotNull{i,it->
            val retake=it.optJSONObject("metadata")?.optJSONObject("photo_quality")?.optBoolean("retake_required")==true
            if(retake||it.str("text").isBlank())"Photo ${i+1} (it could not be assessed)" else null
        }+files.mapIndexedNotNull{i,it->
            val name=it.optJSONObject("metadata")?.str("filename").orEmpty().ifBlank{"File ${i+1}"}
            if(it.str("text").isBlank())"$name (it could not be read)" else null
        }
        return if(missing.isEmpty())null else "Not included in the report: ${missing.joinToString("; ")}. These attachments are still kept with this work."
    }
    fun finishPhotoAssessment(){if(loading)return;notice=null;dismissError();when(quickMode){"consultant"->screen="consultant";"document"->screen=if(editor.isBlank())"document_capture"else "editor";else->doneQuick()}}
    fun photoLayout():List<JSONObject>{
        val photos=case?.items("evidence").orEmpty().filter{it.str("kind")=="photo"}
        val layout=case?.optJSONObject("encounter")?.optJSONObject("draft_data")?.items("photo_layout").orEmpty()
        return layout.filter{p->photos.any{it.str("id")==p.str("id")}} + photos.filter{p->layout.none{it.str("id")==p.str("id")}}.map{obj("id" to it.str("id"),"label" to "", "group" to "Unassigned ${it.str("id").take(8)}")}
    }
    fun arrangePhotos(layout:List<JSONObject>)=runTask("Updating photos"){
        if(!demo)api.call("/encounters/$encounterId/photo-layout","PUT",obj("expected_revision" to revision,"photos" to JSONArray(layout)))
        else case?.getJSONObject("encounter")?.getJSONObject("draft_data")?.put("photo_layout",JSONArray(layout))
        refreshAcknowledged()
    }
    fun removePhoto(id:String)=runTask("Removing photo"){
        if(!demo)api.call("/encounters/$encounterId/photos/$id","DELETE")
        localSource(id)?.let{file->
            val root=getApplication<Application>().filesDir.canonicalPath+File.separator
            if(file.canonicalPath.startsWith(root)){file.delete();vault.remove("capture-owner-${file.name}")}
        }
        vault.remove(storeKey("source-$id"))
        if(demo)case?.put("evidence",JSONArray(case?.items("evidence").orEmpty().filter{it.str("id")!=id}))
        else reloadCase()
        notice="Photo and its assessment removed. Review any report wording already generated from it."
    }
    fun retakePhoto(){if(loading)return;clearCaptureSelection("photo");notice=null;dismissError();screen="photo"}
    fun updateConsultantDraft(text:String){consultantDraft=text;vault.write(storeKey("consult-draft"),consultantDraft)}
    fun sendConsultant(message:String):Unit=runTask("Consultant is responding",purpose="consultant",retry={sendConsultant(message)}) {
        ensureRemoteDocument()
        val text=message.trim();require(text.isNotBlank()&&text.length<=2000){"Enter a question within 2,000 characters."}
        val pending=consultantMessages.lastOrNull()?.let{it.str("role")=="user"&&it.str("content")==text}==true
        val history=if(pending)consultantMessages.dropLast(1)else consultantMessages
        if(!pending){consultantMessages=consultantMessages+obj("id" to UUID.randomUUID().toString(),"role" to "user","content" to text);vault.write(storeKey("consult-chat"),JSONArray(consultantMessages).toString())}
        val payload=obj("message" to text,"history" to JSONArray(boundedConsultHistory(history).map{obj("role" to it.str("role"),"content" to it.str("content"))}))
        val sourceRevision=revision;val intent="consult-$sourceRevision"
        val reply=if(demo)obj("answer" to "This is the local sample. In a connected visit, Ask Consultant can start with your question and the information you choose to add. No model was called.","case_revision" to revision)else mutation(intent,"/encounters/$encounterId/consult",payload)
        require(reply.optInt("case_revision")==revision){"Your sources changed. Ask again using the current sources."}
        consultantMessages=consultantMessages+obj("id" to UUID.randomUUID().toString(),"role" to "assistant","content" to reply.str("answer"),"effort" to reply.str("effort_level"))
        vault.write(storeKey("consult-chat"),JSONArray(consultantMessages.takeLast(100)).toString());updateConsultantDraft("");consultantReset++
        if(!demo)acknowledge(intent,payload)
    }
    private suspend fun afterQuickSource(kind:String){
        if(quickMode==null)return
        if(kind=="photo"){
            notice=null
            val quality=case?.items("evidence")?.lastOrNull{it.str("kind")=="photo"}?.optJSONObject("metadata")?.optJSONObject("photo_quality")
            if(quality?.optBoolean("retake_required")==true){screen="photo_assessment";return}
        }
        when(quickMode){
            "document"->{screen="document_capture";if(kind in listOf("audio","typed","transcript") && editor.isBlank())draftQuick(true)}
            "photo"->screen="photo_assessment"
            "consultant"->screen="consultant"
        }
    }
    private suspend fun draftQuick(ai:Boolean,format:String=editorTemplate){
        ensureRemoteDocument()
        recordingStage("GENERATING_REPORT");Performance.start("report_request")
        // Once a source is accepted, retry only drafting, never its successful upload.
        taskRecovery("report"){reformatOrRetry(ai,format)}
        taskLabel=if(ai)"Creating report"else "Preparing your draft"
        val start=localRevision;val sourceRevision=revision
        val payload=obj("template" to format,"use_ai" to ai,"preferred_length" to reportLength)
        if(format=="custom")payload.put("custom_template",obj("name" to customName,"instructions" to customInstructions))
        val consultation=if(reportFromConsultation)consultantMessages.filter{it.str("content").isNotBlank()}.takeLast(40)
            .map{obj("role" to (if(it.str("role")=="user")"user" else "assistant"),"content" to it.str("content").take(10000))} else emptyList()
        if(consultation.isNotEmpty())payload.put("consultation",JSONArray(consultation))
        var intent="quick-report-$sourceRevision"+if(consultation.isEmpty())"" else "-consult-"+hash(canonical(JSONArray(consultation))).take(16)
        suspend fun request():JSONObject=try{mutation(intent,"/encounters/$encounterId/quick-report",payload)}catch(e:ApiException){
            if(consultation.isEmpty() || e.status!=422 || !payload.has("consultation"))throw e
            // An older server cannot read the conversation yet: still write the report from the attachments, and say so.
            recordingNotice=listOfNotNull(recordingNotice,"This server cannot include the conversation yet, so the report uses only your attached photos and files.").joinToString("\n")
            payload.remove("consultation");intent="quick-report-$sourceRevision"
            mutation(intent,"/encounters/$encounterId/quick-report",payload)
        }
        val response=if(demo)sampleDocument(case!!,format)
            else try{request()}catch(e:ApiException){
                if(!ai || e.status !in listOf(402,429,502,503,504))throw e
                // The failed AI draft is never promoted. Reuse acknowledged original sources.
                // A distinct stable identity prevents a failed AI replay from blocking local drafting.
                val recovered=mutation("$intent-source-only","/encounters/$encounterId/quick-report",JSONObject(payload.toString()).put("use_ai",false))
                recovered.put("ui_alerts",JSONArray().put(obj("field" to "review","message" to "AI generation or checking could not complete. This source-only draft has not passed an AI check. Review the original source before using it.")))
                recovered.put("warnings",JSONArray().put("A source-only draft was recovered. It may retain transcript noise; review speaker attribution, corrections and clinical wording."))
                recovered
            }
        require(start==localRevision && response.optInt("case_revision")==revision){"Your report or sources changed during generation. Your edits were preserved."}
        if(!acceptDocumentResult(response)){if(!demo)acknowledge(intent,payload);return}
        snapshotDraft();restoring=true;editorTemplate=format;restoring=false
        vault.write("preferred-format-${user?.str("id")?:"sample"}",format)
        Performance.end("report_request");val persistStarted=Performance.now()
        response.str("_diagnostic_request_id").takeIf{it.isNotBlank()}?.let{runCatching{vault.write("diagnostic-generation-${user?.str("id")}-$encounterId",it);api.diagnosticGeneration=it}}
        editor=response.str("text");savedId=null;editorCaseRevision=response.optInt("case_revision",revision);localRevision++;notice=null;warnings(response);recordingNotice?.let{extra->notice=listOfNotNull(notice,extra).joinToString("\n");recordingNotice=null};persistEditor();recordingStage("REPORT_READY");Performance.record("report_state",persistStarted);if(!demo)acknowledge(intent,payload);Performance.start("report_ui");screen="editor"
    }
    fun quickReport(ai:Boolean=true)=runTask(if(ai)"Creating report" else "Preparing a source-based draft"){draftQuick(ai)}
    private fun reformatOrRetry(ai:Boolean,format:String)=runTask("Preparing your report"){draftQuick(ai,format)}
    fun reformatQuick(format:String)=runTask("Changing report format"){draftQuick(true,format)}
    fun applyVoiceCorrection():Unit=runTask("Applying your correction",purpose="report",retry={applyVoiceCorrection()}){
        require(voiceInstruction.isNotBlank()){"Enter the correction you want to make."}
        val start=localRevision;val intent="refine-$revision";val payload=obj("text" to editor,"instruction" to voiceInstruction,"template" to editorTemplate)
        if(editorTemplate=="custom")payload.put("custom_template",obj("name" to customName,"instructions" to customInstructions))
        val response=if(demo)obj("text" to editor,"case_revision" to revision,"warnings" to JSONArray().put("Sample mode does not apply AI edits."))else mutation(intent,"/encounters/$encounterId/refine-report",payload)
        require(start==localRevision && response.optInt("case_revision")==revision){"Your report or sources changed while the correction was being prepared. Your edits were preserved."}
        if(!acceptDocumentResult(response)){if(!demo)acknowledge(intent,payload);return@runTask}
        snapshotDraft();editor=response.str("text");savedId=null;editorCaseRevision=response.optInt("case_revision",revision);localRevision++;persistEditor();if(!demo)acknowledge(intent,payload);voiceCorrection=false;voiceInstruction="";warnings(response);screen="editor"
    }
    private fun acceptDocumentResult(result:JSONObject):Boolean {
        documentAlerts=result.items("ui_alerts").map{it.str("message")}.filter{it.isNotBlank()}
        if(result.str("status","ready")!="ready" || result.str("text").isBlank()){
            notice=result.str("ui_message").ifBlank{"Not enough visit information to generate this document."}
            return false
        }
        return true
    }
    fun improveReport():Unit=runTask("Improving report wording",purpose="report",retry={improveReport()}) {
        require(editor.isNotBlank()){"Create or write a report first."}
        require(editorCaseRevision==revision){"Review the current sources before improving this report."}
        val start=localRevision;val payload=obj("text" to editor,"template" to editorTemplate,"expected_revision" to revision)
        if(editorTemplate=="custom")payload.put("custom_template",obj("name" to customName,"instructions" to customInstructions))
        val intent="improve-report-$revision"
        if(demo){notice="Sample mode does not call AI or change your wording.";return@runTask}
        val result=mutation(intent,"/encounters/$encounterId/improve-report",payload)
        require(start==localRevision && result.optInt("case_revision")==revision){"Your report changed. Current wording was preserved."}
        if(acceptDocumentResult(result)){
            result.str("_diagnostic_request_id").takeIf{it.isNotBlank()}?.let{runCatching{vault.write("diagnostic-generation-${user?.str("id")}-$encounterId",it);api.diagnosticGeneration=it}}
            snapshotDraft();editor=result.str("text");savedId=null;localRevision++;persistEditor()
            notice="Wording improved. Review the changes; previous wording is available in More options."
        }
        acknowledge(intent,payload)
    }
    fun improveAssessment():Unit=runTask("Reviewing assessment questions") {
        val visit=encounterId;val sourceRevision=revision
        val payload=obj("expected_revision" to sourceRevision);val intent="improve-assessment-$sourceRevision"
        val result=if(demo)obj("answer" to "Sample mode does not call AI. In a connected visit, relevant assessment questions appear here without changing your report.","case_revision" to revision)
            else mutation(intent,"/encounters/$encounterId/improve-assessment",payload)
        require(visit==encounterId && result.optInt("case_revision")==revision){"Visit information changed. Request updated assessment questions."}
        assessmentReview=result;assessmentRevision=sourceRevision;assessmentEncounter=visit
        if(!demo)acknowledge(intent,payload)
    }
    fun cancelVoiceCorrection(){if(RecordingService.state.value.recording)return;voiceCorrection=false;voiceInstruction="";screen="editor";dismissError()}
    fun fixByVoice(){if(loading)return;persistEditor();voiceCorrection=true;voiceMessage=false;dismissError();screen="record"}
    fun recordConsultantMessage(){if(loading)return;voiceCorrection=false;voiceMessage=true;notice=null;dismissError();screen="record"}
    fun cancelVoiceMessage(){if(RecordingService.state.value.recording)return;voiceMessage=false;notice=null;dismissError();screen="consultant"}
    /** Send > Computer: saves the current wording first if needed, then makes a one-time link and code. */
    fun sendToComputer()=runTask("Preparing secure link"){
        require(!demo){"Secure links are available after signing in. Use Another app or Copy for this sample."}
        val saved=case?.items("documents")?.firstOrNull{it.str("id")==savedId}
        if(saved==null || saved.str("text")!=editor || saved.str("template")!=editorTemplate)saveDocumentNow()
        val id=savedId;require(id!=null){"Save the report before sending it to a computer."}
        transfer=api.call("/transfers","POST",obj("document_ids" to JSONArray(listOf(id)))) as JSONObject
    }
    fun sendReports(all:Boolean=false)=runTask("Preparing secure transfer"){
        require(!demo){"Secure transfer is available after signing in. Sample reports can be shared as PDF or text."}
        val documents=if(all){
            val ids=encounters.map{it.str("id")}.distinct()
            require(ids.size<=50){"Send All currently supports up to 50 visits. Send selected reports individually."}
            ids.mapNotNull{id->(api.call("/encounters/$id") as JSONObject).items("documents").maxByOrNull{it.optInt("version")}}
        }else listOfNotNull(case?.items("documents")?.firstOrNull{it.str("id")==savedId})
        val ids=documents.map{it.str("id")}.distinct();require(ids.isNotEmpty()){"Save a report before sending it."};require(ids.size<=50){"Send up to 50 saved reports at a time."}
        transfer=api.call("/transfers","POST",obj("document_ids" to JSONArray(ids))) as JSONObject
    }
    fun localSource(id:String):File?=vault.read(storeKey("source-$id"))?.let{File(it).takeIf{f->f.exists()}}
    fun reviewFact(fact:JSONObject,action:String,value:JSONObject?=null)=runTask {
        if(demo){val copy=JSONObject(case.toString());copy.items("facts").first{it.str("id")==fact.str("id")}.apply {put("confirmation_state",if(action=="reject")"rejected" else "confirmed");put("revision",optInt("revision")+1);if(value!=null)put("value",value)};copy.getJSONObject("encounter").put("revision",revision+1);case=copy}
        else {api.call("/facts/${fact.str("id")}/review","POST",obj("expected_revision" to fact.optInt("revision"),"action" to action,"value" to value));reloadCase()}
    }
    fun reviewCase(ai:Boolean)=runTask("Reviewing encounter findings") {if(demo){val copy=JSONObject(case.toString());copy.put("review",obj("gaps" to JSONArray(),"warnings" to JSONArray().put("Synthetic preview. This is not a clinical assessment."),"conflicts" to JSONArray(),"considerations" to JSONArray(),"case_revision" to revision));case=copy}else{api.call("/encounters/$encounterId/review","POST",obj("mode" to "auto","use_ai" to ai));reloadCase()};screen="review"}
    fun ask(question:String)=runTask("Kosmo is reviewing your question") {answer=if(demo)obj("answer" to "This sample is local. In a connected encounter, Kosmo uses this case's evidence to answer your question. No model was called in this preview.","case_revision" to revision)else api.call("/encounters/$encounterId/assistant","POST",obj("question" to question)) as JSONObject}
    fun edit(text:String){editor=text;savedId=null;localRevision++;draftJob?.cancel();val key=editorOwner ?: return;val generation=++draftGeneration;val data=draftJson().toString();draftJob=viewModelScope.launch{delay(150);try{withContext(Dispatchers.IO){synchronized(vault){if(generation==draftGeneration)vault.write(key,data)}}}catch(e:Exception){if(e is CancellationException)throw e;notice="Local draft recovery could not be saved."}}}
    fun persistEditor(){val key=editorOwner ?: return;if(key!=storeKey("draft"))return;draftGeneration++;draftJob?.cancel();try{vault.write(key,draftJson().toString())}catch(_:Exception){notice="The local draft could not be saved on this device."}}
    private fun draftJson()=obj("text" to editor,"template" to editorTemplate,"case_revision" to editorCaseRevision,"saved_id" to savedId,"amends_id" to amendId,"reason" to amendReason,"custom_name" to customName,"custom_instructions" to customInstructions,"ui_alerts" to JSONArray(documentAlerts))
    private fun restoreDraft(){
        assessmentReview=null;documentAlerts=emptyList()
        draftGeneration++;draftJob?.cancel();restoring=true;editorOwner=storeKey("draft")
        val d=vault.read(editorOwner!!)?.let{runCatching{JSONObject(it)}.getOrNull()} ?: if(case?.optJSONObject("encounter")?.optJSONObject("draft_data")?.str("quick_mode").orEmpty().isNotBlank())case?.items("documents")?.maxByOrNull{it.optInt("version")}?.let{
            obj("text" to it.str("text"),"template" to it.str("template"),"saved_id" to it.str("id"),"case_revision" to it.optInt("case_revision"),"amends_id" to it.str("amends_id"),"reason" to it.str("reason"),"custom_name" to it.optJSONObject("custom_template")?.str("name"),"custom_instructions" to it.optJSONObject("custom_template")?.str("instructions"))
        }else null
        editor=d?.str("text") ?: "";editorTemplate=d?.str("template","nursing") ?: "nursing";editorCaseRevision=d?.optInt("case_revision",revision) ?: revision
        documentAlerts=d?.optJSONArray("ui_alerts")?.let{a->(0 until a.length()).map{a.optString(it)}}?:emptyList()
        savedId=d?.str("saved_id")?.ifBlank{null};amendId=d?.str("amends_id")?.ifBlank{null};amendReason=d?.str("reason") ?: "";customName=d?.str("custom_name")?:"";customInstructions=d?.str("custom_instructions")?:""
        if(savedId!=null && case?.items("documents")?.none{it.str("id")==savedId && matchesEditor(it)}!=false)savedId=null
        hasRecoverableDraft=vault.read(storeKey("draft-recovery"))!=null;restoring=false;localRevision++
    }
    fun newDocument(){if(loading)return;snapshotDraft();documentAlerts=emptyList();restoring=true;editorOwner=storeKey("draft");editor="";savedId=null;editorCaseRevision=revision;amendId=case?.items("documents")?.filter{it.str("status")=="signed"}?.maxByOrNull{it.optInt("version")}?.str("id");amendReason="";restoring=false;localRevision++;persistEditor();screen="editor"}
    fun loadDocument(d:JSONObject){if(loading)return;snapshotDraft();documentAlerts=emptyList();restoring=true;editorOwner=storeKey("draft");editor=d.str("text");editorTemplate=d.str("template","nursing");savedId=d.str("id");editorCaseRevision=d.optInt("case_revision");amendId=d.str("amends_id").ifBlank{null};amendReason=d.str("reason");customName=d.optJSONObject("custom_template")?.str("name")?:"";customInstructions=d.optJSONObject("custom_template")?.str("instructions")?:"";restoring=false;localRevision++;screen="editor";persistEditor()}
    fun startAmendment(d:JSONObject){if(loading)return;loadDocument(d);amendId=d.str("id");savedId=null;editorCaseRevision=revision;amendReason="";persistEditor()}

    fun generate(ai:Boolean)=runTask(if(ai)"Generating report and checking sources" else "Preparing source-based draft") {
        val startingEdit=localRevision;val payload=obj("template" to editorTemplate,"mode" to "auto","use_ai" to ai);if(editorTemplate=="custom")payload.put("custom_template",obj("name" to customName,"instructions" to customInstructions))
        val result=if(demo)sampleDocument(case!!,editorTemplate)else mutation("draft-$revision","/encounters/$encounterId/draft",payload)
        require(localRevision==startingEdit){"Your editor changed during drafting. Your edits were preserved."};if(!acceptDocumentResult(result)){if(!demo)acknowledge("draft-$revision",payload);return@runTask};snapshotDraft();editor=result.str("text");editorCaseRevision=result.optInt("case_revision",revision);savedId=null;localRevision++;warnings(result);persistEditor();if(!demo)acknowledge("draft-$revision",payload)
    }
    private fun mergeDocument(d:JSONObject){
        val c=JSONObject(case.toString());c.put("documents",JSONArray((c.items("documents").filter{it.str("id")!=d.str("id")}+d).sortedByDescending{it.optInt("version")}))
        c.getJSONObject("encounter").put("revision",maxOf(revision,d.optInt("case_revision"))).put("updated_at",now())
        if(d.str("status")=="signed")c.getJSONObject("encounter").put("status","signed")
        case=c
    }
    fun saveDocument()=runTask("Saving exact document") {saveDocumentNow()}
    private suspend fun saveDocumentNow() {
        ensureRemoteDocument()
        require(editor.isNotBlank()&&editor.length<=60000){"Enter document text within 60,000 characters."}
        require(amendId==null||amendReason.isNotBlank()){"An amendment requires a reason."}
        // Photo uploads advance encounter revision. Keep edited text intact.
        if(!demo) {
            // Refresh once before saving, including when an upload acknowledgement
            // was received but the local encounter revision has not caught up.
            persistEditor()
            reloadCase()
            if(editorCaseRevision != revision) {
                closeWorkPrompt=false
                notice="A photo or other source changed this visit. Your report is preserved. Review the updated sources and select the current revision before saving."
                screen="editor"
                throw IllegalStateException("Review the updated sources before saving your report.")
            }
        }
        val before=localRevision
        val p=obj("text" to editor,"template" to editorTemplate,"expected_revision" to editorCaseRevision,"amends_id" to amendId,"reason" to amendReason.ifBlank{null})
        if(editorTemplate=="custom"){require(customName.trim().length in 1..120 && customInstructions.trim().length in 1..2000){"Enter a custom template name and layout instructions."};p.put("custom_template",obj("name" to customName.trim(),"instructions" to customInstructions.trim()))}
        val d=if(demo){
            require(editorCaseRevision==revision){"The case changed. Review current findings before saving."}
            val signed=case?.items("documents")?.filter{it.str("status")=="signed"}?.maxByOrNull{it.optInt("version")}
            require(signed==null || signed.str("id")==amendId){"Amend the latest signed document with an explicit reason."}
            obj("id" to UUID.randomUUID().toString(),"text" to editor,"template" to editorTemplate,"custom_template" to p.opt("custom_template"),"status" to "draft",
                "version" to ((case?.items("documents")?.maxOfOrNull{it.optInt("version")}?:0)+1),"case_revision" to revision+1,"saved_case_revision" to revision+1,
                "content_hash" to hash(editor),"amends_id" to amendId,"reason" to amendReason.ifBlank{null},"signed_at" to null,"signed_by" to null,
                "created_at" to now(),"fact_snapshot" to JSONArray(case?.items("facts")?.filter{it.str("confirmation_state")=="confirmed"}?:emptyList<JSONObject>()))
        }else mutation("save","/encounters/$encounterId/documents",p)
        mergeDocument(d)
        if(before==localRevision&&matchesEditor(d)){savedId=d.str("id");editorCaseRevision=d.optInt("case_revision")}
        persistEditor();if(!demo)acknowledge("save",p)
        notice=if(demo)"Saved in the synthetic preview." else "Exact document saved to your workspace."
        refreshAcknowledged()
    }
    fun useCurrentRevision(){editorCaseRevision=revision;persistEditor();notice="Current case revision selected. Review your text against the updated findings before saving."}
    fun sign(d:JSONObject)=runTask {
        val saved=case?.items("documents")?.firstOrNull{it.str("id")==d.str("id")} ?: throw IllegalArgumentException("Open a saved document before signing.")
        require(savedId==saved.str("id")&&matchesEditor(saved)){"Save and review the exact document, template and amendment details before signing."}
        require(saved.str("content_hash")==hash(saved.str("text"))){"The saved content hash does not match this document."}
        if(saved.str("status")=="signed")return@runTask
        require(saved.optInt("case_revision")==revision){"The encounter changed after saving. Save and review a new version."}
        require((case?.optJSONArray("conflicts")?.length()?:0)==0){"Resolve conflicting confirmed findings before signing."}
        val result=if(demo)JSONObject(saved.toString()).put("status","signed").put("case_revision",revision+1).put("signed_case_revision",revision+1).put("signed_at",now()).put("signed_by",user?.str("id"))
        else api.call("/documents/"+saved.str("id")+"/sign","POST",obj("content_hash" to saved.str("content_hash"),"acknowledge_review" to true,"expected_case_revision" to revision)) as JSONObject
        mergeDocument(result);editorCaseRevision=result.optInt("case_revision");persistEditor();notice=if(demo)"Signed in synthetic preview only."else"Signed version preserved.";refreshAcknowledged()
    }
    fun history()=runTask {audit=if(demo)listOf(obj("action" to "sample.encounter_opened","created_at" to "Synthetic preview"))else(api.call("/encounters/$encounterId/audit")as JSONArray).objects();screen="history"}
    fun logout()=runTask {
        persistEditor();var revokeFailed=false
        if(!demo)try{api.call("/auth/logout","POST",JSONObject())}catch(e:Exception){if(e is CancellationException)throw e;revokeFailed=true}
        externalCapture.endSession();api.clear();vault.remove("cached-account");pinLock.clear();locked=false;clearClinical();demo=false;user=null;legalRequired=false;screen="welcome"
        api.diagnosticJob=null;DeveloperDiagnostics.clear()
        if(revokeFailed)notice="Signed out on this device. The server session could not be revoked while disconnected."
    }
    override fun onCleared(){wearBridge.close();getApplication<Application>().unregisterReceiver(screenLockReceiver);persistEditor();super.onCleared()}

}
