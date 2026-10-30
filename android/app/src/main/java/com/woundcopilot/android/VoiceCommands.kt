package com.woundcopilot.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.*
import android.speech.tts.TextToSpeech
import android.media.ToneGenerator
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

data class VoiceCommand(val action:String,val value:String="",val replacement:String="")
internal fun parseVoiceCommand(input:String,wakePhrase:String="Hey WoundCoPilot",activelyListening:Boolean=false):VoiceCommand?{
    val trimmed=input.trim()
    val hasWake=trimmed.startsWith(wakePhrase,true)
    if(!hasWake&&!activelyListening)return null
    val original=if(hasWake)trimmed.drop(wakePhrase.length).trim(' ', ',')else trimmed
    // Whole-phrase matches only, so a sentence that merely contains a command word never triggers it.
    val text=original.lowercase(Locale.ROOT).trim().trimEnd('.','!','?').removePrefix("please ").removeSuffix(" please").trim()
    val commands=mapOf("start a new report" to "new","new report" to "new","start another report" to "new","new visit" to "new",
        "generate report" to "generate","prepare report" to "generate","create report" to "generate","write report" to "generate","write the report" to "generate",
        "try again" to "retry","try preparing the report again" to "retry",
        "improve report" to "improve_report","improve assessment" to "improve_assessment","copy report" to "copy","copy" to "copy",
        "save report" to "save","save" to "save","save the report" to "save","export report" to "export",
        "send" to "send","send report" to "send","send the report" to "send","share report" to "send",
        "finish report" to "finish","done" to "done","i'm done" to "done","finish" to "done",
        "read the report" to "read","read report" to "read","read it" to "read","repeat" to "read","stop reading" to "stop_reading","pause reading" to "stop_reading",
        "add another photo" to "photo","add photo" to "photo","take photo" to "photo","take a photo" to "photo","photo" to "photo",
        "start recording" to "record","record" to "record","record a visit" to "record","stop" to "stop_reading",
        "ask consultant" to "consult","ask the consultant" to "consult","consultant" to "consult",
        "go home" to "home","home" to "home",
        "tell me the recording duration" to "duration","undo" to "undo","redo" to "redo")
    commands[text]?.let{return VoiceCommand(it)}
    val formats=mapOf("nursing note" to "nursing","nursing" to "nursing","soap note" to "soap","soap" to "soap","sbar" to "sbar","physician update" to "physician_update","referral letter" to "referral_letter","handoff" to "handoff","hand off" to "handoff")
    formats[text.removePrefix("create ").removePrefix("make ").removePrefix("switch to ").removePrefix("change to ").removePrefix("a ").removePrefix("an ")]?.let{return VoiceCommand("format",it)}
    Regex("(?i)^replace (.+) with (.+)$").matchEntire(original)?.let{return VoiceCommand("replace",it.groupValues[1],it.groupValues[2])}
    Regex("(?i)^remove (.+)$").matchEntire(original)?.let{return VoiceCommand("replace",it.groupValues[1],"")}
    Regex("(?i)^add wording (.+)$").matchEntire(original)?.let{return VoiceCommand("append",it.groupValues[1])}
    return null
}

internal fun proposedVoiceEdit(text:String,command:VoiceCommand):String?{
    if(command.action=="append")return text.trimEnd()+"\n"+command.value
    if(command.action!="replace"||command.value.isBlank())return null
    val matches=Regex(Regex.escape(command.value),RegexOption.IGNORE_CASE).findAll(text).toList()
    if(matches.size!=1)return null
    return text.replaceRange(matches.single().range,command.replacement)
}

/** Explicit, on-device command capture. This is not advertised as a continuous wake-word engine. */
@Composable fun VoiceCommandsDialog(s:AppState){
    if(!s.voiceCommandsOpen)return
    val context=LocalContext.current
    val recordingState by RecordingService.state.collectAsState()
    var status by remember{mutableStateOf("")}
    var listening by remember{mutableStateOf(false)}
    var candidate by remember{mutableStateOf<Pair<String,String>?>(null)}
    var previous by remember(s.encounterId){mutableStateOf<String?>(null)}
    var redo by remember(s.encounterId){mutableStateOf<String?>(null)}
    val wake=remember{s.vault.read("command-wake-phrase")?:"Hey WoundCoPilot"}
    val tones=remember{ToneGenerator(AudioManager.STREAM_NOTIFICATION,35)}
    var ttsReady by remember{mutableStateOf(false)}
    var speech by remember{mutableStateOf<TextToSpeech?>(null)}
    val supported=Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val recognizer=remember{if(supported)SpeechRecognizer.createOnDeviceSpeechRecognizer(context)else null}
    fun read(text:String){
        recognizer?.cancel();listening=false
        if(ttsReady)text.chunked(TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3500)).forEachIndexed{index,part->speech?.speak(part,if(index==0)TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,null,"report-$index")}
    }
    fun handle(raw:String){
        status="Heard: $raw";tones.startTone(ToneGenerator.TONE_PROP_ACK,70)
        val command=parseVoiceCommand(raw,wake,activelyListening=true)
        if(command==null){status="Command unclear. Please repeat it.";tones.startTone(ToneGenerator.TONE_PROP_NACK,90);return}
        if(s.loading||s.locked)return
        when(command.action){
            "new"->{s.voiceCommandsOpen=false;s.startQuick("document")}
            "generate"->s.quickReport()
            "retry"->s.retryFailed()
            "improve_report"->s.improveReport()
            "improve_assessment"->s.improveAssessment()
            "save"->s.saveDocument()
            "finish"->{s.persistEditor();status="Ready for your review. Nothing was sent or signed."}
            "photo"->{s.voiceCommandsOpen=false
                // In an open visit the camera opens straight away; from Home a photo assessment starts first.
                if(s.case!=null && s.quickMode!=null){s.screen="photo";(context as? MainActivity)?.launchCapture(ExternalKind.CAMERA,"photo","photo")}else s.startQuick("photo")}
            "consult"->{s.voiceCommandsOpen=false;if(s.case!=null && s.quickMode!=null)s.quickConsultant()else s.startQuick("consultant")}
            "send"->{if(s.editor.isBlank())status="Open a report first." else {s.voiceCommandsOpen=false;s.screen="editor";s.sendRequested=true}}
            "done"->{s.voiceCommandsOpen=false;if(s.screen=="consultant")s.finishConsultant()else s.doneQuick()}
            "home"->{s.voiceCommandsOpen=false;if(s.quickMode!=null)s.doneQuick()else s.screen="home"}
            "record"->{s.voiceCommandsOpen=false;if(s.case==null)s.startQuick("document")else s.chooseQuickMode("document");s.autoStartRecording=true;s.screen="record"}
            "export"->{s.voiceCommandsOpen=false;s.runTask("Preparing report export"){
                require(s.editor.isNotBlank()){"Create or open a report first."}
                val uri=ExportTools.createPdf(context,s.editor,documentTypeTitle(s.editorTemplate))
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);clipData=android.content.ClipData.newRawUri("Report",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share report"))
            }}
            "format"->if(s.editor.isBlank())s.updateTemplate(command.value)else s.reformatQuick(command.value)
            "copy"->{val clip=context.getSystemService(android.content.ClipboardManager::class.java);clip.setPrimaryClip(android.content.ClipData.newPlainText("WoundCoPilot report",s.editor));status="Report copied."}
            "read"->read(s.editor)
            "stop_reading"->{speech?.stop();status="Reading stopped."}
            "duration"->{val sec=RecordingService.state.value.elapsedSeconds;status="${sec/60} minutes ${sec%60} seconds";read(status)}
            "replace","append"->{val changed=proposedVoiceEdit(s.editor,command);if(changed==null)status="That wording is missing or appears more than once. Please be more specific."else{candidate=s.editor to changed;read(if(command.action=="replace")"Change ${command.value} to ${command.replacement.ifBlank{"nothing"}}. Review and confirm this change."else "Add ${command.value}. Review and confirm this change.")}}
            "undo"->previous?.let{redo=s.editor;s.edit(it);previous=null}
            "redo"->redo?.let{previous=s.editor;s.edit(it);redo=null}
        }
    }
    DisposableEffect(recognizer){
        speech=TextToSpeech(context){ttsReady=it==TextToSpeech.SUCCESS}
        recognizer?.setRecognitionListener(object:RecognitionListener{
            override fun onReadyForSpeech(params:Bundle?){listening=true;tones.startTone(ToneGenerator.TONE_PROP_BEEP,60)}
            override fun onBeginningOfSpeech(){}
            override fun onRmsChanged(value:Float){}
            override fun onBufferReceived(buffer:ByteArray?){}
            override fun onEndOfSpeech(){listening=false}
            override fun onError(error:Int){listening=false;status="Speech was not understood. Tap Listen and try again.";tones.startTone(ToneGenerator.TONE_PROP_NACK,90)}
            override fun onResults(results:Bundle?){listening=false;results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::handle)}
            override fun onPartialResults(results:Bundle?){}
            override fun onEvent(eventType:Int,params:Bundle?){}
        })
        onDispose{recognizer?.destroy();speech?.stop();speech?.shutdown();tones.release()}
    }
    fun listen(){
        if(RecordingService.state.value.recording){status="Stop dictation before using voice commands.";return}
        speech?.stop()
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1))
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->val valid=s.externalCapture.permissionReturned();if(granted&&valid)listen()}
    fun listenWithPermission(){
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)listen()
        else if(s.externalCapture.begin(ExternalKind.PERMISSION,"audio","command"))try{permission.launch(Manifest.permission.RECORD_AUDIO)}catch(_:Exception){s.externalCapture.clear()}
    }
    // One tap: the voice button opens this and listening starts straight away.
    LaunchedEffect(Unit){if(supported)listenWithPermission()}
    AlertDialog(onDismissRequest={recognizer?.cancel();s.voiceCommandsOpen=false},title={Text(if(listening)"Listening…" else "Voice")},text={Column(verticalArrangement=androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)){
        if(!supported)Text("Voice commands aren't available on this phone. You can still use the buttons.")
        if(status.isNotBlank())Text(status,style=MaterialTheme.typography.titleSmall)
        Text("Say one of these:",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Record · Take photo · Ask consultant\nSOAP note · Nursing note · SBAR · Handoff\nRead the report · Save · Send · Done · Go home\nReplace … with … · Undo",style=MaterialTheme.typography.bodyMedium)
        TextButton({try{context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))}catch(_:Exception){status="Open Accessibility in Android Settings to turn on Voice Access."}}){Text("No hands at all? Set up Voice Access")}
    }},confirmButton={TextButton({listenWithPermission()},enabled=supported&&!listening&&!s.loading&&!recordingState.recording){Text(if(listening)"Listening…"else "Listen again")}},dismissButton={TextButton({recognizer?.cancel();s.voiceCommandsOpen=false}){Text("Close")}})
    candidate?.let{(before,after)->AlertDialog(onDismissRequest={candidate=null},title={Text("Confirm wording change")},text={Column{Text("Before\n$before");Text("After\n$after")}},confirmButton={TextButton({if(s.editor==before){previous=before;redo=null;s.edit(after)}else status="Report changed. Please repeat the edit.";candidate=null}){Text("Apply change")}},dismissButton={TextButton({candidate=null}){Text("Cancel")}})}
}
