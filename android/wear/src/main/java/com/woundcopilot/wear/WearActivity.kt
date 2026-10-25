package com.woundcopilot.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.*
import com.woundcopilot.wear.contract.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode

class WearActivity:ComponentActivity(){
 private val model:WearViewModel by viewModels()
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme{WatchScreen(model)}}}
 override fun onResume(){super.onResume();model.resume()}
 override fun onPause(){model.pause();super.onPause()}
}
@Composable private fun WatchScreen(vm:WearViewModel){
 val s=vm.status
 val scroll=rememberScrollState()
 val rotaryFocus=remember{FocusRequester()}
 val scope=rememberCoroutineScope()
 var help by remember{mutableStateOf(false)}
 val needsPhone=vm.problem in setOf(WearError.SESSION_REQUIRED,WearError.PHONE_LOCKED,WearError.MIC_PERMISSION_REQUIRED,WearError.OPEN_PHONE_REQUIRED)
 LaunchedEffect(Unit){rotaryFocus.requestFocus()}
 var confirm by remember{mutableStateOf<CommandType?>(null)}
 var confirmationJob by remember{mutableStateOf<String?>(null)}
 LaunchedEffect(s?.job,s?.phase,vm.ready){if(confirm!=null&&!confirmationAllowed(confirm!!,confirmationJob,s,vm.ready))confirm=null}
 var elapsed by remember{mutableLongStateOf(0)}
 val haptic=LocalHapticFeedback.current
 var previous by remember{mutableStateOf<Phase?>(null)}
 LaunchedEffect(s?.phase){if(previous!=null && vm.ready && s?.phase in listOf(Phase.RECORDING,Phase.SAVED,Phase.REPORT_READY))haptic.performHapticFeedback(HapticFeedbackType.LongPress);previous=s?.phase}
 LaunchedEffect(s,vm.ready){elapsed=vm.elapsed();while(vm.ready && s?.phase==Phase.RECORDING){delay(1000);elapsed=vm.elapsed()}}
 Column(Modifier.fillMaxSize().background(Color(0xFF0B141A)).onRotaryScrollEvent{event->scope.launch{scroll.scrollBy(event.verticalScrollPixels)};true}.focusRequester(rotaryFocus).focusable().verticalScroll(scroll).padding(start=28.dp,end=28.dp,top=28.dp,bottom=56.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(9.dp)){
  Text("AMILYA",fontSize=15.sp,color=Color(0xFF9BE7CF))
  Text(if(vm.connected)if(vm.ready)"Phone connected"else "Syncing with phone"else "Phone disconnected",fontSize=12.sp,textAlign=TextAlign.Center)
  if(confirm!=null){
   Text(if(confirm==CommandType.START)"Consent confirmed to record and process this visit?"else "Cancel recording and discard this audio?",textAlign=TextAlign.Center)
   Button(enabled=confirm?.let{confirmationAllowed(it,confirmationJob,s,vm.ready)}==true&&!vm.busy,onClick={val c=confirm!!;confirm=null;vm.send(c,c==CommandType.START)},modifier=Modifier.fillMaxWidth()){Text(if(confirm==CommandType.START)"Record visit"else "Discard audio")}
   TextButton(onClick={confirm=null}){Text("Go back")}
  }else{
   if(s!=null && !vm.ready)Text("Last known status",fontSize=12.sp,color=Color(0xFFFFD18A))
   when(s?.phase?:Phase.IDLE){
    Phase.RECORDING,Phase.PAUSED->{
     Text("%02d:%02d".format(elapsed/60000,(elapsed/1000)%60),fontSize=36.sp)
     Text(if(s?.phase==Phase.PAUSED)"PAUSED"else "RECORDING",color=Color(0xFF9BE7CF))
     Button(onClick={vm.send(CommandType.STOP)},enabled=vm.ready&&!vm.busy,modifier=Modifier.fillMaxWidth()){Text("STOP")}
     TextButton(onClick={confirmationJob=s?.job;confirm=CommandType.CANCEL},enabled=vm.ready&&!vm.busy){Text("Cancel")}
    }
    Phase.REPORT_READY->{Text("✓",fontSize=36.sp,color=Color(0xFF9BE7CF));Text("REPORT READY",textAlign=TextAlign.Center);Button(onClick={vm.send(CommandType.OPEN_PHONE)},enabled=vm.ready&&!vm.busy,modifier=Modifier.fillMaxWidth()){Text("Open on phone")};TextButton(onClick={confirmationJob=s?.job;confirm=CommandType.START},enabled=vm.ready&&!vm.busy){Text("New visit")}}
    Phase.IDLE,Phase.CANCELLED->{
     if(vm.ready)Canvas(Modifier.size(40.dp).semantics{contentDescription="Microphone"}){val c=Color(0xFF9BE7CF);val w=size.width;drawRoundRect(c,Offset(w*.36f,w*.1f),Size(w*.28f,w*.5f),androidx.compose.ui.geometry.CornerRadius(w*.14f));drawArc(c,0f,180f,false,Offset(w*.21f,w*.27f),Size(w*.58f,w*.47f),style=Stroke(w*.055f));drawLine(c,Offset(w*.5f,w*.74f),Offset(w*.5f,w*.92f),w*.055f);drawLine(c,Offset(w*.34f,w*.92f),Offset(w*.66f,w*.92f),w*.055f)}
     if(vm.ready&&!needsPhone)Text("Record visit confirms consent to record and process this visit.",fontSize=12.sp,textAlign=TextAlign.Center)
     Button(onClick={when{needsPhone&&vm.connected->if(vm.ready)vm.send(CommandType.OPEN_PHONE)else vm.openPhone();vm.ready->vm.send(CommandType.START,true);else->vm.discover()}},enabled=!vm.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(when{needsPhone&&vm.connected->"Open phone";vm.ready->"Record visit";else->"Check connection"})}
    }
    else->{Text(phaseText(s!!.phase),textAlign=TextAlign.Center,fontSize=19.sp);if(s.saved)Text("Saved on phone",color=Color(0xFF9BE7CF),textAlign=TextAlign.Center);if(s.phase in listOf(Phase.FAILED,Phase.QUEUED_OFFLINE,Phase.SAVED))Button(onClick={vm.send(CommandType.RETRY)},enabled=vm.ready&&!vm.busy){Text("Retry")}}
   }
   if(vm.busy)Text("Waiting for phone…",fontSize=12.sp,textAlign=TextAlign.Center)
   if(vm.problem!=WearError.NONE && (vm.connected || vm.problem !in setOf(WearError.PHONE_UNAVAILABLE,WearError.CONNECTION_LOST)))Text(errorText(vm.problem),modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite},fontSize=12.sp,textAlign=TextAlign.Center,color=Color(0xFFFFD18A))
   if(!vm.connected){
    Text(if(vm.pairedPhoneAvailable==true)"Phone found. Install the matching Amilya phone app and open it."else "Pair this watch with your phone in the watch companion app. Amilya connects automatically.",fontSize=12.sp,textAlign=TextAlign.Center)
    TextButton(onClick={help=!help},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(help)"Hide setup"else "Setup help")}
    if(help)Text("1. Pair watch and phone.\n2. Install both matching Amilya apps.\n3. Sign in on the phone and allow its microphone.\nNo watch account or API key needed.",fontSize=12.sp,textAlign=TextAlign.Start)
   }
   if(vm.openNotice)Text("Continue on phone",fontSize=12.sp)
   if(!vm.ready && s?.phase !in listOf(null,Phase.IDLE,Phase.CANCELLED))Button(onClick={vm.discover()},enabled=!vm.busy){Text("Reconnect")}
   if(vm.connected && !needsPhone && s?.phase!=Phase.REPORT_READY)TextButton(onClick={if(vm.ready)vm.send(CommandType.OPEN_PHONE)else vm.openPhone()},enabled=vm.connected&&!vm.busy){Text("Open phone")}
  }
 }
}
private fun phaseText(p:Phase)=when(p){Phase.UPLOADING->"Uploading / transcribing…";Phase.TRANSCRIBING->"Transcription complete";Phase.GENERATING_REPORT->"Creating report…";Phase.QUEUED_OFFLINE->"Waiting for connection";Phase.SAVED->"Ready to process";Phase.FAILED->"Unable to complete";Phase.STOPPING->"Stopping…";Phase.SAVING->"Saving…";else->"Preparing…"}
private fun errorText(e:WearError)=when(e){WearError.PHONE_LOCKED->"Unlock Amilya on your phone.";WearError.SESSION_REQUIRED->"Sign in and complete setup on your phone.";WearError.MIC_PERMISSION_REQUIRED->"Allow microphone access on your phone.";WearError.CONSENT_REQUIRED->"Confirm consent before recording.";WearError.OPEN_PHONE_REQUIRED->"Open Amilya on your phone to continue.";WearError.PROTOCOL_INCOMPATIBLE->"Update both phone and watch apps.";WearError.MULTIPLE_PHONES->"More than one phone found. Connect one phone.";WearError.RECORDING_ALREADY_ACTIVE->"Recording already in progress.";WearError.BUSY->"Phone is completing the previous action.";WearError.AUDIO_SAVE_FAILED->"Check recording recovery on your phone.";WearError.PROCESSING_FAILED->"Processing failed. Retry the saved recording.";WearError.CONNECTION_LOST, WearError.PHONE_UNAVAILABLE->"Check your phone connection.";else->"Open the phone to check the current status."}
