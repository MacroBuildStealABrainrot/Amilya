package com.woundcopilot.wear

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.*
import com.woundcopilot.wear.contract.*
import kotlinx.coroutines.*
import java.util.concurrent.Executor

class WearViewModel(app:Application):AndroidViewModel(app),MessageClient.OnMessageReceivedListener,DataClient.OnDataChangedListener,CapabilityClient.OnCapabilityChangedListener {
 private val mirror=WatchMirror()
 private val prefs=app.getSharedPreferences("wear-operational-status",0)
 var status by mutableStateOf<WearStatus?>(null);private set
 var connected by mutableStateOf(false);private set
 var ready by mutableStateOf(false);private set
 var busy by mutableStateOf(false);private set
 var problem by mutableStateOf(WearError.NONE);private set
 var openNotice by mutableStateOf(false);private set
 private var node:String?=null
 private var pending:WearCommand?=null
 private var timeout:Job?=null
 private var samplingMono=SystemClock.elapsedRealtime()
 private var active=false
 private val discovery=ConnectionEpoch()
 private var reconnect:Job?=null
 var pairedPhoneAvailable by mutableStateOf<Boolean?>(null);private set
 init {
  runCatching{prefs.getString("state",null)?.let{WearWire.status(android.util.Base64.decode(it,0))}}.getOrNull()?.let{mirror.restore(it,System.currentTimeMillis());status=mirror.status;if(status==null)prefs.edit().remove("state").apply()}
 }
 fun resume(){
  if(active)return;active=true;discovery.resume()
  val app=getApplication<Application>()
  Wearable.getMessageClient(app).addListener(this).addOnFailureListener{problem=WearError.UNAVAILABLE}
  Wearable.getDataClient(app).addListener(this).addOnFailureListener{problem=WearError.UNAVAILABLE}
  Wearable.getCapabilityClient(app).addListener(this,WearProtocol.PHONE).addOnFailureListener{problem=WearError.UNAVAILABLE}
  discover()
  reconnect=viewModelScope.launch{while(isActive){delay(15000);if(active&&!ready&&!busy&&problem!=WearError.PROTOCOL_INCOMPATIBLE)discover()}}
 }
 fun pause(){
  if(!active)return;active=false;discovery.pause();reconnect?.cancel();connected=false
  val app=getApplication<Application>();Wearable.getMessageClient(app).removeListener(this);Wearable.getDataClient(app).removeListener(this);Wearable.getCapabilityClient(app).removeListener(this,WearProtocol.PHONE)
  timeout?.cancel();pending=null;busy=false;ready=false;mirror.disconnected()
 }
 fun discover(){
  val ticket=discovery.request()?:return
  Wearable.getCapabilityClient(getApplication<Application>()).getCapability(WearProtocol.PHONE,CapabilityClient.FILTER_REACHABLE)
   .addOnSuccessListener{if(discovery.accepts(ticket)){
    select(it)
    if(it.nodes.isEmpty())Wearable.getNodeClient(getApplication<Application>()).connectedNodes.addOnSuccessListener{nodes->if(discovery.accepts(ticket))pairedPhoneAvailable=nodes.isNotEmpty()}
    else pairedPhoneAvailable=true
   }}.addOnFailureListener{if(discovery.accepts(ticket))disconnect(WearError.PHONE_UNAVAILABLE)}
 }
 override fun onCapabilityChanged(cap:CapabilityInfo){if(active)discover()}
 private fun select(cap:CapabilityInfo){
  if(!active)return
  if(cap.nodes.size!=1){disconnect(if(cap.nodes.size>1)WearError.MULTIPLE_PHONES else WearError.PHONE_UNAVAILABLE);return}
  val selected=cap.nodes.single().id
  if(node!=selected||!ready){if(node!=selected){timeout?.cancel();pending=null;busy=false};node=selected;connected=true;mirror.disconnected();ready=false;send(CommandType.STATUS)}
 }
 private fun disconnect(error:WearError){pairedPhoneAvailable=null;node=null;connected=false;ready=false;mirror.disconnected();pending=null;timeout?.cancel();busy=false;problem=error}
 fun send(type:CommandType,consent:Boolean=false){
  if(!active||busy)return
  val target=node?:return discover()
  if(type!=CommandType.STATUS && !ready){send(CommandType.STATUS);return}
  val current=status
  val command=WearCommand(type=type,token=current?.token.orEmpty(),job=current?.job.orEmpty(),consent=consent)
  pending=command;busy=true;problem=WearError.NONE;openNotice=false
  Wearable.getMessageClient(getApplication<Application>()).sendMessage(target,WearProtocol.COMMAND,WearWire.command(command)).addOnFailureListener{if(pending?.id==command.id)disconnect(WearError.CONNECTION_LOST)}
  timeout?.cancel();timeout=viewModelScope.launch{delay(12000);if(pending?.id==command.id){pending=null;busy=false;ready=false;mirror.disconnected();problem=WearError.CONNECTION_LOST}}
 }
 override fun onMessageReceived(event:MessageEvent){
  if(!active||event.sourceNodeId!=node||event.path!=WearProtocol.RESPONSE)return
  val value=runCatching{WearWire.status(event.data)}.getOrNull()?:return
  val waiting=pending
  if(waiting==null||value.replyTo!=waiting.id)return
  timeout?.cancel();busy=false;pending=null
  if(value.version!=WearProtocol.VERSION){problem=WearError.PROTOCOL_INCOMPATIBLE;ready=false;return}
  val fresh=waiting.type==CommandType.STATUS
  if(mirror.accept(value,fresh)){apply(mirror.status!!);ready=mirror.synchronized}
  if(ready)problem=status?.error?:value.error
  else problem=value.error
  if(waiting.type==CommandType.OPEN_PHONE && value.error in listOf(WearError.NONE,WearError.OPEN_PHONE_REQUIRED,WearError.SESSION_REQUIRED))openPhone()
  if(waiting.type==CommandType.START && value.error in setOf(WearError.PHONE_LOCKED,WearError.SESSION_REQUIRED,WearError.MIC_PERMISSION_REQUIRED,WearError.OPEN_PHONE_REQUIRED))send(CommandType.OPEN_PHONE)
  if(value.error==WearError.STALE_COMMAND){ready=false;mirror.disconnected();send(CommandType.STATUS)}
 }
 override fun onDataChanged(events:DataEventBuffer){
  if(!active)return
  for(e in events){if(e.type!=DataEvent.TYPE_CHANGED||e.dataItem.uri.host!=node||e.dataItem.uri.path!=WearProtocol.STATUS)continue
   val v=runCatching{WearWire.status(e.dataItem.data?:continue)}.getOrNull()?:continue
   if(mirror.accept(v))apply(v)else if(!busy && (!ready || status?.epoch!=v.epoch)){ready=false;mirror.disconnected();send(CommandType.STATUS)}
  }
 }
 private fun apply(value:WearStatus){
  status=value;problem=value.error;samplingMono=SystemClock.elapsedRealtime()
  prefs.edit().putString("state",android.util.Base64.encodeToString(WearWire.status(value),android.util.Base64.NO_WRAP)).apply()
 }
 fun elapsed():Long {
  val s=status?:return 0
  // Local monotonic display only. Disconnect freezes the last confirmed sample, labelled stale.
  return s.elapsedMillis+if(ready&&connected&&s.phase==Phase.RECORDING)(SystemClock.elapsedRealtime()-samplingMono)else 0
 }
 fun openPhone(){
  if(!active)return
  val target=node?:return
  val intent=Intent(Intent.ACTION_VIEW,Uri.parse("woundcopilot://wear/open")).addCategory(Intent.CATEGORY_BROWSABLE)
  val future=try{RemoteActivityHelper(getApplication(),Executor{it.run()}).startRemoteActivity(intent,target)}catch(_:Exception){problem=WearError.OPEN_PHONE_REQUIRED;return}
  future.addListener({if(active&&node==target)runCatching{future.get()}.onSuccess{openNotice=true}.onFailure{problem=WearError.OPEN_PHONE_REQUIRED}},Executor{it.run()})
 }
 override fun onCleared(){pause();super.onCleared()}
}
