package com.woundcopilot.android

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

enum class ExternalKind { PHOTO_PICKER, CAMERA, DOCUMENT_PICKER, AUDIO_PICKER, PERMISSION, BIOMETRIC }

/** One app-initiated operation, not an authenticated session. Restored operations never exempt locks. */
class ExternalCapture(private val s:AppState,private val context:Context,private val scope:CoroutineScope,private val clock:()->Long={SystemClock.elapsedRealtime()}) {
    companion object { const val TRUST_MS=LockPolicy.EXTERNAL_FLOW_MS; private const val KEY="capture-external-pending" }
    private var live=false
    private var started=0L
    private var returned=false
    private var awaitingForeground=false
    private val lifecycleJob=UUID.randomUUID().toString()
    private var pending by mutableStateOf<JSONObject?>(runCatching{s.vault.read(KEY)?.let(::JSONObject)?.takeIf{System.currentTimeMillis()-it.optLong("created") in 0..86_400_000}}.getOrNull())
    /** True while [pending] came from storage after Android closed the app, not from a launch in this process. */
    private var restored=pending!=null
    /** The operation the app already locked for, so it does not lock again on every resume (PIN loop). */
    private var lockedFor:String?=null
    /** A photo that arrived while the PIN screen was showing; it is sent once the clinician unlocks. */
    private var deferredPhoto:JSONObject?=null
    var busy by mutableStateOf(false);private set
    var revision by mutableIntStateOf(0);private set
    val kind get()=pending?.str("type")?.let{runCatching{ExternalKind.valueOf(it)}.getOrNull()}
    val operationId get()=pending?.str("id")
    val canDiscard get()=pending!=null&&!busy
    fun cameraFile():File?=pending?.str("camera")?.takeIf{it.isNotBlank()}?.let(::File)
    private fun event(name:String,p:JSONObject?=pending){
        val owner=p?.str("owner")?:s.user?.str("id")?:return
        DeveloperDiagnostics.external(s.vault,owner,p?.str("id")?:lifecycleJob,name,p?.str("type")?:"NONE")
    }
    fun begin(type:ExternalKind,media:String,purpose:String,camera:File?=null):Boolean {
        if(pending!=null || busy){s.notice="Finish or cancel the current system selection before starting another.";return false}
        if(s.locked || s.loading || s.user==null)return false
        val owner=s.user!!.str("id")
        val p=obj("id" to UUID.randomUUID().toString(),"owner" to owner,"encounter" to s.encounterId,"screen" to s.screen,"purpose" to purpose,"media" to media,"type" to type.name,"created" to System.currentTimeMillis(),"camera" to camera?.absolutePath)
        try{s.vault.write(KEY,p.toString())}catch(_:Exception){s.error="The pending capture could not be saved. Check device storage.";return false}
        pending=p;live=true;started=clock();returned=false;awaitingForeground=false;restored=false;lockedFor=null
        event("external_started");return true
    }
    fun background(changing:Boolean,deviceLocked:Boolean){
        if(changing)return
        event("app_background")
        if(!deviceLocked && trusted()){awaitingForeground=true;event("lock_suppressed_trusted_flow")}
        else {live=false;s.lock();event(if(deviceLocked)"lock_required_device"else"lock_required_background")}
    }
    private fun trusted()=pending!=null && live && !s.locked && s.user?.str("id")==pending?.str("owner") && clock()-started in 0..TRUST_MS
    fun foreground(deviceLocked:Boolean){
        event("app_foreground")
        val id=pending?.str("id")
        if(LockPolicy.lockForPendingOperation(deviceLocked,id,live,clock()-started,awaitingForeground,returned,lockedFor)){
            s.lock();live=false;if(id!=null)lockedFor=id;event(if(deviceLocked)"lock_required_device"else"lock_required_timeout")
        }
        awaitingForeground=false
        // After Android closed the app, a result can only arrive before this first resume. None came, so it never will.
        if(LockPolicy.forgetRestoredOperation(restored && pending!=null,returned,busy)){event("external_cancelled");clear()}
        // Results may be delivered before onResume. Keep their identity through that transition.
        if(returned && !busy)clear()
    }
    fun deviceLocked(){live=false;s.lock();event("lock_required_device")}
    // Do not reuse an outstanding launch for a new account/encounter after logout.
    // Its eventual result still belongs to the original operation and cannot upload.
    fun endSession(){live=false}
    fun cancel(){returned=true;event("external_cancelled");if(!awaitingForeground)clear()}
    fun discard(){if(!busy){event("external_cancelled");clear()}}
    fun clear(){pending=null;live=false;returned=false;awaitingForeground=false;restored=false;runCatching{s.vault.remove(KEY)}}
    /**
     * Sends a photo that arrived while the app was locked or busy. Called after the PIN/biometric unlock and again whenever
     * a task finishes, so it waits until the app is ready instead of being dropped. Same clinician and same visit only.
     */
    fun resumeDeferredPhoto(){
        val p=deferredPhoto?:return
        when(LockPolicy.deferredPhotoAction(s.user?.str("id"),p.str("owner"),s.encounterId,p.str("encounter"),s.locked,s.loading,busy)){
            LockPolicy.Deferred.DROP->deferredPhoto=null
            LockPolicy.Deferred.WAIT->return
            LockPolicy.Deferred.SEND->{
                deferredPhoto=null
                val files=s.pendingPhotoFiles()
                if(files.isNotEmpty()){s.screen=p.str("screen");s.uploadPhotos(files)}
            }
        }
    }
    fun permissionReturned():Boolean {
        val valid=trusted() && s.encounterId==pending?.str("encounter")
        returned=true;event("external_returned");if(!valid)s.lock()
        if(!awaitingForeground)clear()
        return valid
    }
    fun receive(type:ExternalKind,uris:List<Uri>,cameraAccepted:Boolean=false,operationId:String?=this.operationId){
        val p=pending?:return
        if(kind!=type || returned || operationId!=p.str("id"))return
        returned=true;event("external_returned")
        if(!trusted()){live=false;s.lock()}
        if(uris.isEmpty()&&!cameraAccepted){cancel();return}
        busy=true
        scope.launch {
            val imported=mutableListOf<Pair<File,String>>()
            try{
                require(uris.size<=20){"Choose up to 20 images."}
                withContext(Dispatchers.IO){
                    fun preserve(){
                        val key="capture-${p.str("owner")}-${p.str("encounter")}-${p.str("purpose")}"
                        val ownerKey="${p.str("owner")}:${p.str("encounter")}"+if(p.str("purpose") in listOf("message","correction"))":${p.str("purpose")}"else ""
                        val selected=imported.last()
                        s.vault.write("capture-owner-${selected.first.name}",ownerKey)
                        s.vault.write(key,selected.first.path);s.vault.write("$key-mime",selected.second);s.vault.remove("$key-review")
                        if(p.str("media")=="photo"){
                            val batchKey="photo-batch-pending-${p.str("owner")}-${p.str("encounter")}"
                            val existing=s.vault.read(batchKey)?.let{JSONArray(it).objects()}?:emptyList()
                            s.vault.write(batchKey,JSONArray((existing+obj("path" to selected.first.path,"mime" to selected.second)).distinctBy{it.str("path")}).toString())
                        }
                    }
                    if(cameraAccepted){
                        val file=cameraFile()?:error("Camera recovery unavailable")
                        require(file.canonicalFile.parentFile==File(context.filesDir,"captures").canonicalFile)
                        if(file.isFile && file.length()>0){
                            // Keep the original reachable even if validation rejects its size/encoding.
                            val key="capture-${p.str("owner")}-${p.str("encounter")}-${p.str("purpose")}"
                            s.vault.write(key,file.path);s.vault.write("$key-mime","image/jpeg")
                            s.vault.write("capture-owner-${file.name}","${p.str("owner")}:${p.str("encounter")}")
                        }
                        require(file.isFile && file.length() in 1..12L*1024*1024){"Photograph is empty or larger than 12 MB. The original remains on this device."}
                        imported+=file to identifyCapture(file,"photo","image/jpeg",file.name);preserve()
                    }else for(uri in uris){imported+=importCapture(context,uri,p.str("media"));preserve()}
                }
                revision++
                val matches=s.user?.str("id")==p.str("owner") && s.encounterId==p.str("encounter")
                if(matches && !s.locked){
                    s.screen=p.str("screen")
                    if(p.str("media")=="photo"&&!s.loading)s.uploadPhotos(s.pendingPhotoFiles())
                    else if(p.str("media")=="photo")deferredPhoto=p   // app busy: send when the current task ends
                }else if(matches && p.str("media")=="photo")deferredPhoto=p   // app locked: send after unlock
                event("external_saved",p)
            }catch(e:Exception){
                if(e is CancellationException)throw e
                if(s.user?.str("id")==p.str("owner"))s.error="The selected file could not be prepared. The original was not changed. Try selecting it again."
                event("external_failed",p)
            }finally{revision++;busy=false;if(!awaitingForeground)clear();resumeDeferredPhoto()}
        }
    }
}

/** Existing import validation, moved out of a disposable Compose callback. */
internal fun importCapture(context:Context,uri:Uri,kind:String):Pair<File,String> {
    val limit=when(kind){"photo"->12;"audio"->25;else->20}
    val resolver=context.contentResolver
    val mime=resolver.getType(uri)?.lowercase(java.util.Locale.ROOT)?:"application/octet-stream"
    val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)?:""else ""}.orEmpty()
    val directory=File(context.filesDir,"captures").apply{check(exists()||mkdirs())}
    val temporary=File(directory,"import-${UUID.randomUUID()}.partial")
    try{
        resolver.openInputStream(uri)?.use{input->temporary.outputStream().use{output->
            val buffer=ByteArray(32768);var total=0L
            while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit.toLong()*1024*1024);output.write(buffer,0,n)}
            require(total>0)
        }}?:error("File unavailable")
        val detected=identifyCapture(temporary,kind,mime,name)
        val file=File(directory,"$kind-${UUID.randomUUID()}.${captureExtension(detected)}")
        check(temporary.renameTo(file));return file to detected
    }finally{temporary.delete()}
}
