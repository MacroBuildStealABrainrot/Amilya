package com.woundcopilot.wear.contract

import java.io.*
import java.util.UUID

/** Closed, bounded operational contract. No clinical strings or authentication credentials. */
object WearProtocol {
 const val VERSION=1
 const val COMMAND="/amilya/v1/command"
 const val RESPONSE="/amilya/v1/response"
 const val STATUS="/amilya/v1/status"
 const val PHONE="amilya_phone_remote"
 const val WATCH="amilya_watch_remote"
 const val MAX_BYTES=2048
 const val MAX_AGE=30_000L
 const val CACHE_AGE=24*60*60*1000L
 fun id()=UUID.randomUUID().toString()
 fun validId(s:String)=s.isEmpty() || runCatching{UUID.fromString(s).toString()==s}.getOrDefault(false)
}
enum class CommandType { STATUS,START,STOP,CANCEL,RETRY,OPEN_PHONE }
enum class Phase { IDLE,STARTING,RECORDING,PAUSED,STOPPING,SAVING,SAVED,QUEUED_OFFLINE,UPLOADING,TRANSCRIBING,GENERATING_REPORT,REPORT_READY,FAILED,CANCELLED }
enum class WearError { NONE,PHONE_UNAVAILABLE,PHONE_LOCKED,SESSION_REQUIRED,MIC_PERMISSION_REQUIRED,CONSENT_REQUIRED,OPEN_PHONE_REQUIRED,RECORDING_ALREADY_ACTIVE,NO_ACTIVE_RECORDING,BUSY,STALE_COMMAND,PROCESSING_FAILED,AUDIO_SAVE_FAILED,PROTOCOL_INCOMPATIBLE,CONNECTION_LOST,MULTIPLE_PHONES,UNAVAILABLE }
data class WearCommand(val id:String=WearProtocol.id(),val type:CommandType,val token:String="",val job:String="",val issuedAt:Long=System.currentTimeMillis(),val consent:Boolean=false,val version:Int=WearProtocol.VERSION)
data class WearStatus(val epoch:String=WearProtocol.id(),val sequence:Long=0,val token:String=WearProtocol.id(),val job:String="",val phase:Phase=Phase.IDLE,val elapsedMillis:Long=0,val sampledAt:Long=System.currentTimeMillis(),val saved:Boolean=false,val error:WearError=WearError.NONE,val replyTo:String="",val version:Int=WearProtocol.VERSION)
object WearWire {
 private fun pack(f:DataOutputStream.()->Unit)=ByteArrayOutputStream().apply{DataOutputStream(this).use{it.f()}}.toByteArray().also{require(it.size<=WearProtocol.MAX_BYTES)}
 private fun <T> unpack(bytes:ByteArray,f:DataInputStream.()->T):T {require(bytes.size in 1..WearProtocol.MAX_BYTES);return DataInputStream(ByteArrayInputStream(bytes)).use{val result=it.f();require(it.available()==0);result}}
 private fun DataInputStream.id()=readUTF().also{require(WearProtocol.validId(it))}
 fun command(c:WearCommand)=pack {writeInt(c.version);writeUTF(c.id);writeUTF(c.type.name);writeUTF(c.token);writeUTF(c.job);writeLong(c.issuedAt);writeBoolean(c.consent)}
 fun command(b:ByteArray)=unpack(b){WearCommand(version=readInt(),id=id().also{require(it.isNotEmpty())},type=CommandType.valueOf(readUTF()),token=id(),job=id(),issuedAt=readLong(),consent=readBoolean())}
 fun status(s:WearStatus)=pack{writeInt(s.version);writeUTF(s.epoch);writeLong(s.sequence);writeUTF(s.token);writeUTF(s.job);writeUTF(s.phase.name);writeLong(s.elapsedMillis);writeLong(s.sampledAt);writeBoolean(s.saved);writeUTF(s.error.name);writeUTF(s.replyTo)}
 fun status(b:ByteArray)=unpack(b){WearStatus(version=readInt(),epoch=id().also{require(it.isNotEmpty())},sequence=readLong().also{require(it>=0)},token=id().also{require(it.isNotEmpty())},job=id(),phase=Phase.valueOf(readUTF()),elapsedMillis=readLong().also{require(it in 0..86_400_000)},sampledAt=readLong(),saved=readBoolean(),error=WearError.valueOf(readUTF()),replyTo=id())}
}
/** Validate before reserving a command ID. Caller persists reservation before executing effects. */
object CommandGate {
 fun check(c:WearCommand,s:WearStatus,now:Long):WearError = when {
  c.version!=WearProtocol.VERSION -> WearError.PROTOCOL_INCOMPATIBLE
  now<0 || c.issuedAt<0 || (c.issuedAt>now && c.issuedAt-now>5000) || (c.issuedAt<=now && now-c.issuedAt>WearProtocol.MAX_AGE) -> WearError.STALE_COMMAND
  c.type==CommandType.STATUS -> WearError.NONE
  c.token!=s.token || c.job!=s.job -> WearError.STALE_COMMAND
  c.type==CommandType.START && s.phase in setOf(Phase.RECORDING,Phase.PAUSED,Phase.STARTING) -> WearError.RECORDING_ALREADY_ACTIVE
  c.type==CommandType.START && s.phase !in setOf(Phase.IDLE,Phase.CANCELLED,Phase.REPORT_READY) -> WearError.BUSY
  c.type in setOf(CommandType.STOP,CommandType.CANCEL) && s.phase !in setOf(Phase.RECORDING,Phase.PAUSED) -> WearError.NO_ACTIVE_RECORDING
  c.type==CommandType.RETRY && s.phase !in setOf(Phase.FAILED,Phase.SAVED,Phase.QUEUED_OFFLINE) -> WearError.BUSY
  c.type==CommandType.START && !c.consent -> WearError.CONSENT_REQUIRED
  else -> WearError.NONE
 }
}
/** A reconnect requires a fresh STATUS reply; cached data never enables commands. */
class WatchMirror {
 private var buffered:WearStatus?=null
 var status:WearStatus?=null;private set
 var synchronized=false;private set
 fun disconnected(){synchronized=false;buffered=null}
 fun accept(s:WearStatus,freshReply:Boolean=false):Boolean {
  val old=status
  if(s.version!=WearProtocol.VERSION){synchronized=false;return false}
  if(!freshReply && !synchronized){if(buffered?.epoch!=s.epoch || s.sequence>=(buffered?.sequence?:0))buffered=s;return false}
  if(!freshReply && (old?.epoch!=s.epoch || s.sequence<=(old?.sequence?:0)))return false
  if(freshReply && old?.epoch==s.epoch && s.sequence<old.sequence)return false
  status=s
  if(freshReply){synchronized=true;buffered?.takeIf{it.epoch==s.epoch && it.sequence>s.sequence}?.let{status=it};buffered=null}
  return true
 }
 fun restore(s:WearStatus,now:Long){if(now-s.sampledAt in 0..WearProtocol.CACHE_AGE)status=s;synchronized=false}
}
