package com.woundcopilot.android



import android.content.Context

import android.security.keystore.KeyGenParameterSpec

import android.security.keystore.KeyProperties

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext

import org.json.JSONArray

import org.json.JSONObject

import org.json.JSONTokener

import java.io.File

import java.net.HttpURLConnection

import java.net.URL

import java.security.KeyStore

import javax.crypto.Cipher

import javax.crypto.KeyGenerator

import javax.crypto.SecretKey

import javax.crypto.spec.GCMParameterSpec



fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { put(it.first, it.second ?: JSONObject.NULL) } }

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.str(key:String, fallback:String=""):String = if (isNull(key)) fallback else optString(key, fallback)

fun JSONObject.items(key:String): List<JSONObject> = optJSONArray(key)?.objects() ?: emptyList()

fun prettyValue(v:Any?):String = when(v) {

    null, JSONObject.NULL -> "Not recorded"

    is JSONObject -> if(v.has("length_cm")) listOf("length_cm","width_cm","depth_cm").mapNotNull { if(!v.isNull(it)) v.opt(it)?.toString() else null }.joinToString(" × ")+" cm" else {

        val text=v.str("text").trim()

        val details=v.keys().asSequence().filter{it!="text" && !v.isNull(it)}.map { key ->

            when(key){

                "present" -> if(v.optBoolean(key))"Present" else "Absent"

                "score_0_10" -> "Pain score: ${v.opt(key)}/10"

                else -> key.replace('_',' ')+": "+prettyValue(v.opt(key))

            }

        }.toList()

        (listOf(text).filter{it.isNotBlank()}+details).joinToString(" · ").ifBlank{"Not recorded"}

    }

    is JSONArray -> (0 until v.length()).joinToString("\n") { prettyValue(v.opt(it)) }

    else -> v.toString()

}



internal fun parseApiJson(text:String):Any {

    val parser=JSONTokener(text)

    val value=parser.nextValue()

    require(parser.nextClean()=='\u0000') { "Unexpected content after JSON response." }

    return value

}



/** Private durable storage. Session and drafts never go in plaintext preferences or backup. */

class Vault(context:Context) {

    private val root = File(context.noBackupFilesDir,"vault").apply { mkdirs() }

    companion object {
        // The key never leaves the Android Keystore; this only keeps its handle, so each read or write no longer
        // reloads the Keystore (slow on Samsung). One process-wide lock also stops two Vault objects from creating
        // two different keys at the same moment on first launch (the second would make the first one's data unreadable).
        @Volatile private var cachedKey:SecretKey?=null
        private val keyLock=Any()
    }

    private fun key():SecretKey = cachedKey ?: synchronized(keyLock) { cachedKey ?: loadOrCreateKey().also { cachedKey=it } }

    private fun loadOrCreateKey():SecretKey {

        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        return (store.getKey("wcp.local.v1",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {

            init(KeyGenParameterSpec.Builder("wcp.local.v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())

        }.generateKey()

    }

    @Synchronized fun write(name:String,value:String) {

        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }

        val data=cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8))

        val target=File(root,name.replace(Regex("[^a-zA-Z0-9_.-]"),"_"))

        android.util.AtomicFile(target).let { atomic -> val stream=atomic.startWrite(); try { stream.write(data);atomic.finishWrite(stream) } catch(e:Exception){atomic.failWrite(stream);throw e} }

    }

    @Synchronized fun read(name:String):String? = runCatching {

        val data=android.util.AtomicFile(File(root,name.replace(Regex("[^a-zA-Z0-9_.-]"),"_"))).openRead().use { it.readBytes() }

        require(data.size >= 28)

        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,data.copyOfRange(0,12))) }.doFinal(data.copyOfRange(12,data.size)).toString(Charsets.UTF_8)

    }.getOrNull()

    @Synchronized fun names():List<String> = root.listFiles()?.filter{it.isFile && !it.name.endsWith(".bak") && !it.name.endsWith(".new")}?.map{it.name}?:emptyList()

    @Synchronized fun expireRecovery(cutoff:Long,keep:(String)->Boolean={false}){
        val kinds=listOf("draft-","draft-recovery-","consult-chat-","consult-draft-","capture-","upload-","audio-batch-","source-","photo-batch-","recording-job-","recording-context-","local-bind-","diagnostic-","wear-")
        root.listFiles()?.filter{it.isFile && it.lastModified()<cutoff && kinds.any{prefix->it.name.startsWith(prefix)} && !keep(it.name)}?.forEach{android.util.AtomicFile(it).delete()}
    }

    @Synchronized fun modified(name:String):Long=File(root,name).lastModified()

    @Synchronized fun remove(name:String) { android.util.AtomicFile(File(root,name.replace(Regex("[^a-zA-Z0-9_.-]"),"_"))).delete() }

}



internal val PHOTO_ERROR_CATEGORIES=setOf("PROVIDER_QUOTA","PROVIDER_AUTH","MODEL_UNAVAILABLE","REQUEST_INCOMPATIBLE","PROVIDER_TIMEOUT","OUTPUT_LIMIT","INVALID_RESPONSE","NETWORK_UNAVAILABLE","RATE_LIMITED","PROVIDER_UNAVAILABLE","RETRY_LIMIT","RESPONSE_PROCESSING_FAILED","AI_PAUSED","AI_NOT_CONFIGURED","PROVIDER_APPROVAL_REQUIRED","PRIVACY_CONFIGURATION_REQUIRED","SECURE_STORAGE_REQUIRED","MODEL_CONFIGURATION_INVALID")

internal fun apiErrorMessage(value:Any?):String {
    val detail=(value as? JSONObject)?.opt("detail")
    // Framework validation messages can echo submitted values; never display their raw entries.
    return if(detail is JSONArray)"The server rejected one or more submitted fields. Review your input. [REQUEST_VALIDATION_FAILED]" else (detail as? String).orEmpty()
}

/** The CSRF token lives only in memory. After an offline start it is empty, so fetch it once, on demand,
 *  before the first write. Never for sign-in routes, and never more often than every 5 seconds. */
internal fun needsCsrfBootstrap(csrf:String,method:String,cookie:String,path:String,sinceLastAttemptMs:Long):Boolean =
    csrf.isBlank() && method!="GET" && cookie.isNotBlank() && sinceLastAttemptMs>=5_000L && path !in AUTH_ENTRY_PATHS

/** Routes that create or renew the session; they never need (or can fetch) the security token first. */
internal val AUTH_ENTRY_PATHS=setOf("/auth/login","/auth/signup","/auth/refresh","/auth/session")

/** The server answers this before it does any work, so the same request can safely be sent again. */
internal fun isCsrfRejection(status:Int,message:String)=status==403 && message.contains("request verification",ignoreCase=true)

class ApiException(val status:Int,message:String,val requestId:String?=null,val retryAfterSeconds:Long?=null,val ambiguous:Boolean=false,val category:String?=null,val retryAllowed:Boolean?=null,val upstreamStatus:Int?=null):Exception(message)

internal const val PRODUCTION_API_ORIGIN="https://woundcopilot-1039151887397.us-central1.run.app/api/v1"
internal const val REPAIR_API_ORIGIN="https://repair---woundcopilot-rhd7rrcqdq-uc.a.run.app/api/v1"
internal const val LOCAL_TEST_API_ORIGIN="http://127.0.0.1:18766/api/v1"

/** Which server addresses a build may talk to. The sandbox build allows ONLY the fake test server on this PC. */
internal fun isAllowedApiOrigin(url:String,debug:Boolean,sandbox:Boolean,repair:Boolean=false):Boolean{
    val local=url==LOCAL_TEST_API_ORIGIN || url=="http://localhost:18766/api/v1"
    return if(sandbox) local else if(repair) url==REPAIR_API_ORIGIN else url==PRODUCTION_API_ORIGIN || (debug && local)
}

class Api(private val vault:Vault,baseUrl:String=BuildConfig.API_BASE_URL) {
    @Volatile var diagnosticJob:String?=null
    @Volatile var diagnosticGeneration:String?=null

    val base=baseUrl.also { require(isAllowedApiOrigin(it,BuildConfig.DEBUG,BuildConfig.SANDBOX,BuildConfig.REPAIR_SERVER)) { "Unsupported API origin." } }

    private val sessionKey=if(base.startsWith("https://woundcopilot-"))"session" else "session-local-integration"

    @Volatile private var cookie=vault.read(sessionKey) ?: ""

    @Volatile private var refreshToken=vault.read("$sessionKey-refresh") ?: ""
    private val renewalLock=Any()
    @Volatile var csrf=""
    @Volatile private var lastCsrfBootstrapMs=-1L

    fun clear(){cookie="";refreshToken="";csrf="";diagnosticJob=null;diagnosticGeneration=null;vault.remove(sessionKey);vault.remove("$sessionKey-refresh")}

    suspend fun call(path:String,method:String="GET",body:JSONObject?=null,key:String?=null):Any = withContext(Dispatchers.IO) {

        execute(path,method,body?.toString()?.toByteArray(),"application/json",key)

    }

    suspend fun upload(encounter:String,file:File,mime:String,kind:String,key:String,photoReview:JSONObject?=null):JSONObject {

        val fields=mutableMapOf("kind" to kind)

        if(kind=="audio")fields["consent"]="true"

        if(kind=="photo" && photoReview!=null)fields["photo_review"]=photoReview.toString()

        return multipart("/encounters/$encounter/upload",file,mime,fields,key)

    }



    suspend fun multipart(path:String,file:File,mime:String,fields:Map<String,String>,key:String):JSONObject = withContext(Dispatchers.IO) {

        require(file.length() in 1..(25L*1024*1024)) { "The file must be nonempty and no larger than 25 MB." }

        require(!mime.contains('\r') && !mime.contains('\n')) { "Invalid file content type." }

        val boundary="Wcp"+java.util.UUID.randomUUID().toString()

        val name=file.name.replace(Regex("[^a-zA-Z0-9._-]"),"_")

        val form=fields.entries.joinToString(""){(field,value)->

            require(Regex("[a-zA-Z0-9_]+").matches(field)) { "Invalid upload field." }

            "--$boundary\r\nContent-Disposition: form-data; name=\"$field\"\r\n\r\n$value\r\n"

        }

        val head=(form+"--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8)

        execute(path,"POST",head+file.readBytes()+"\r\n--$boundary--\r\n".toByteArray(),"multipart/form-data; boundary=$boundary",key) as JSONObject

    }

    /** Sandbox encrypted recordings: uploads bytes that exist only in memory (no file). Same checks and request as [multipart]. */
    suspend fun multipartBytes(path:String,name:String,data:ByteArray,mime:String,fields:Map<String,String>,key:String):JSONObject = withContext(Dispatchers.IO) {
        require(data.size in 1..(25*1024*1024)) { "The file must be nonempty and no larger than 25 MB." }
        require(!mime.contains('\r') && !mime.contains('\n')) { "Invalid file content type." }
        val boundary="Wcp"+java.util.UUID.randomUUID().toString()
        val safeName=name.replace(Regex("[^a-zA-Z0-9._-]"),"_")
        val form=fields.entries.joinToString(""){(field,value)->
            require(Regex("[a-zA-Z0-9_]+").matches(field)) { "Invalid upload field." }
            "--$boundary\r\nContent-Disposition: form-data; name=\"$field\"\r\n\r\n$value\r\n"
        }
        val head=(form+"--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\nContent-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8)
        execute(path,"POST",head+data+"\r\n--$boundary--\r\n".toByteArray(),"multipart/form-data; boundary=$boundary",key) as JSONObject
    }

    private fun execute(path:String,method:String,data:ByteArray?,type:String,key:String?, renewed:Boolean=false):Any {

        require(path.startsWith('/') && !path.startsWith("//")) { "Use a relative API path." }

        if(!renewed){
            val nowMs=System.nanoTime()/1_000_000
            val sinceMs=if(lastCsrfBootstrapMs<0)Long.MAX_VALUE else nowMs-lastCsrfBootstrapMs
            if(needsCsrfBootstrap(csrf,method,cookie,path,sinceMs)){
                lastCsrfBootstrapMs=nowMs
                // Best effort: if this fails (still offline) the real request below reports its own error.
                runCatching{execute("/auth/session","GET",null,"application/json",null,true)}
            }
        }

        val c=URL(base+path).openConnection() as HttpURLConnection
        val requestCookie=cookie
        val diagnosticRequestJob=if(path.startsWith("/encounters/"))diagnosticJob else null
        val diagnosticClientRequest=if(diagnosticRequestJob!=null)java.util.UUID.randomUUID().toString()else null
        val perfStarted=Performance.now()
        val perfStage=when{path.endsWith("/audio-parts")->"transcription_request";path.endsWith("/audio-complete")->"transcript_assembly";path.endsWith("/quick-report")->"report_network";path.endsWith("/documents")&&method=="POST"->"report_save_network";path.endsWith("/quick/start")->"encounter_creation";path.startsWith("/auth/")->"session_request";else->"api_request"}
        var perfStatus="failed"

        try {

            c.requestMethod=method;c.connectTimeout=20000;c.readTimeout=280000;c.instanceFollowRedirects=false

            c.setRequestProperty("Accept","application/json")
            c.setRequestProperty("X-WCP-Client","android")

            if(requestCookie.isNotBlank())c.setRequestProperty("Cookie",requestCookie+if(path.startsWith("/auth/") && refreshToken.isNotBlank())"; $refreshToken" else "")
            if(diagnosticRequestJob!=null && !path.startsWith("/diagnostics"))c.setRequestProperty("X-Diagnostic-Job",diagnosticRequestJob)
            if(diagnosticClientRequest!=null)c.setRequestProperty("X-Diagnostic-Client-Request",diagnosticClientRequest)
            if(path.endsWith("/documents")&&method=="POST")diagnosticGeneration?.let{c.setRequestProperty("X-Report-Generation",it)}

            if(csrf.isNotBlank() && method!="GET")c.setRequestProperty("X-CSRF-Token",csrf)

            if(key!=null)c.setRequestProperty("Idempotency-Key",key)

            if(data!=null){val sent=Performance.now();c.doOutput=true;c.setRequestProperty("Content-Type",type);c.setFixedLengthStreamingMode(data.size);c.outputStream.use{it.write(data)};if(!path.startsWith("/diagnostics"))Performance.record("request_upload",sent,job=if(type.startsWith("multipart/"))diagnosticRequestJob?:"unbound"else "unbound",requestId=diagnosticClientRequest)}

            val status=c.responseCode

            if(status in 200..299)c.headerFields.entries.filter { it.key?.equals("Set-Cookie",true)==true }
                .flatMap{it.value}.forEach { header ->
                    val issued=header.trimStart().substringBefore(';')
                    when(issued.substringBefore('=')) {
                        "wcp_session" -> if(issued.substringAfter('=').isBlank())clear() else {cookie=issued;vault.write(sessionKey,cookie)}
                        "wcp_refresh" -> if(issued.substringAfter('=').isBlank()){refreshToken="";vault.remove("$sessionKey-refresh")}else{refreshToken=issued;vault.write("$sessionKey-refresh",issued)}
                    }
                }
            if(status==401 && !renewed && path!="/auth/refresh" && path!="/auth/login" && refreshToken.isNotBlank()) {
                c.disconnect()
                synchronized(renewalLock){if(cookie==requestCookie)execute("/auth/refresh","POST","{}".toByteArray(),"application/json",null,true)}
                return execute(path,method,data,type,key,true)
            }

            val text=(if(status in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""

            val value=runCatching { parseApiJson(text) }.getOrNull()
            if(value is JSONObject && status in 200..299 && (path.endsWith("/quick-report")||path.endsWith("/improve-report")||path.endsWith("/quick-refine")))c.getHeaderField("X-Request-ID")?.let{id->if(runCatching{java.util.UUID.fromString(id)}.isSuccess)value.put("_diagnostic_request_id",id)}

            if(status !in 200..299){

                val message=apiErrorMessage(value)

                // A stale or missing security token is rejected before any work happens, so fetch a fresh one and
                // resend the identical request once. The Idempotency-Key is unchanged, so nothing can run twice.
                if(isCsrfRejection(status,message) && !renewed && method!="GET" && cookie.isNotBlank() && path !in AUTH_ENTRY_PATHS){
                    c.disconnect()
                    csrf=""
                    runCatching{execute("/auth/session","GET",null,"application/json",null,false)}
                    if(csrf.isNotBlank()){perfStatus="CSRF_REFRESHED";return execute(path,method,data,type,key,true)}
                }

                val pending=status==409 && message.contains("already processing or awaiting reconciliation")
                throw ApiException(status,message,c.getHeaderField("X-Request-ID"),c.getHeaderField("Retry-After")?.toLongOrNull()?:if(pending)2L else null,ambiguous=pending,category=c.getHeaderField("X-WCP-Error")?.takeIf{it in PHOTO_ERROR_CATEGORIES},retryAllowed=c.getHeaderField("X-WCP-Retryable")?.let{when(it){"false"->false;"true"->true;else->null}},upstreamStatus=c.getHeaderField("X-WCP-Upstream-Status")?.toIntOrNull()?.takeIf{it in 100..599})

            }

            if(status!=204 && value !is JSONObject && value !is JSONArray)
                {perfStatus="RESPONSE_PARSE_FAILED";throw ApiException(502,"The server response was unreadable. Refresh to check whether the operation completed.",c.getHeaderField("X-Request-ID"),ambiguous=true)}

            if(value is JSONObject && value.has("csrf_token"))csrf=value.str("csrf_token")

            perfStatus="ok";return value ?: JSONObject()

        } catch(e:Exception){
            if(perfStatus!="RESPONSE_PARSE_FAILED")perfStatus=when{
                e is ApiException && e.status==409 && e.ambiguous -> "PROCESSING_IN_PROGRESS"
                e is java.net.SocketTimeoutException || e is ApiException && e.status==504 -> when(perfStage){"transcription_request"->"TRANSCRIPTION_TIMEOUT";"report_network"->"REPORT_GENERATION_TIMEOUT";else->"NETWORK_TIMEOUT"}
                e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketException -> "NETWORK_UNAVAILABLE"
                perfStage=="transcription_request" -> "TRANSCRIPTION_FAILED"
                perfStage=="report_network" -> "REPORT_GENERATION_FAILED"
                e is ApiException && e.status>=500 -> "BACKEND_FAILED"
                else -> "REQUEST_FAILED"
            }
            throw e
        } finally {Performance.record(perfStage,perfStarted,perfStatus,diagnosticRequestJob?:"unbound",diagnosticClientRequest);c.disconnect()}

    }

}

