package com.woundcopilot.android
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.woundcopilot.wear.contract.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real microphone service + existing AppState, synthetic loopback provider only. */
class WearBridgeTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var s:AppState
 private val inst get()=InstrumentationRegistry.getInstrumentation()
 private val context get()=inst.targetContext
 private lateinit var api:Api
 private val files=mutableSetOf<String>()
 @Before fun setup(){
  check(BuildConfig.DEBUG)
  compose.runOnIdle{s=ViewModelProvider(compose.activity)[AppState::class.java]}
  compose.waitUntil(15000){!s.loading};check(s.user==null){"Isolated test installation required"}
  api=Api(s.vault,"http://127.0.0.1:18766/api/v1")
  runBlocking{api.call("/test/config","POST",obj("live" to false,"reset" to true,"failure" to "","delay" to .1,"open_delay" to .1))}
  compose.runOnIdle{
   AppState::class.java.getDeclaredField("api").apply{isAccessible=true}.set(s,api)
   AppState::class.java.getDeclaredMethod("setSession",JSONObject::class.java).apply{isAccessible=true}.invoke(s,obj("user" to obj("id" to "wear-synthetic","workspace_id" to "synthetic")))
   s.vault.remove("wear-command-ledger");s.unlock();s.screen="home";s.wearBridge.foreground(true)
  }
  inst.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
 }
 private fun status()=runBlocking{withContext(Dispatchers.Main){s.wearBridge.status()}}
 private fun make(type:CommandType,consent:Boolean=true):WearCommand{val state=status();return WearCommand(type=type,token=state.token,job=state.job,consent=consent)}
 private fun send(c:WearCommand)=runBlocking{withContext(Dispatchers.Main){s.wearBridge.command(c)}}
 private fun start():WearStatus{val result=send(make(CommandType.START));RecordingService.state.value.filePath?.let{files+=it};assertEquals(WearError.NONE,result.error);assertEquals(Phase.RECORDING,result.phase);return result}
 @After fun cleanup(){
  if(::s.isInitialized){
   compose.runOnIdle{s.cancelledRecordingPath=RecordingService.state.value.filePath;RecordingService.state.value.filePath?.let{files+=it};RecordingService.stop(context)}
   compose.waitUntil(15000){!RecordingService.state.value.recording&&!s.loading}
   compose.runOnIdle{s.startDemo();s.logout()};compose.waitUntil(15000){!s.loading}
   s.vault.remove("cached-account");s.vault.remove("pin-owner");s.vault.remove("wear-command-ledger");s.vault.remove("wear-open-result")
   files.forEach{path->runCatching{RecordingService.clearPending(context,path,true)};val f=File(path);s.vault.remove(s.recordingRecovery.key(f));s.vault.remove("recording-owner-${f.name}");s.vault.remove("wear-cancel-${f.name}")}
  }
 }
 @Test fun watchStartStopUsesExistingPipelineAndOpensCorrectReport(){
  start();Thread.sleep(2200)
  val stop=send(make(CommandType.STOP));assertEquals(WearError.NONE,stop.error)
  compose.waitUntil(20000){!s.loading&&s.editor.isNotBlank()}
  val ready=status();assertEquals(Phase.REPORT_READY,ready.phase);assertTrue(ready.saved)
  compose.runOnIdle{s.screen="home"}
  assertEquals(WearError.NONE,send(make(CommandType.OPEN_PHONE)).error)
  compose.waitUntil(10000){s.screen=="editor"};assertTrue(s.editor.isNotBlank())
 }
 @Test fun duplicateStartAndStopDoNotCreateSecondRecording(){
  val command=make(CommandType.START);val first=send(command);assertEquals(Phase.RECORDING,first.phase)
  val path=RecordingService.state.value.filePath!!;files+=path
  assertEquals(WearError.STALE_COMMAND,send(command).error);assertEquals(path,RecordingService.state.value.filePath)
  assertEquals(WearError.RECORDING_ALREADY_ACTIVE,send(make(CommandType.START)).error)
  Thread.sleep(1800);val stop=make(CommandType.STOP);send(stop);assertEquals(WearError.STALE_COMMAND,send(stop).error)
 }
 @Test fun lockedOrBackgroundCannotStart(){
  compose.runOnIdle{s.lock()};assertEquals(WearError.PHONE_LOCKED,send(make(CommandType.START)).error)
  compose.runOnIdle{s.unlock();s.wearBridge.foreground(false)};assertEquals(WearError.OPEN_PHONE_REQUIRED,send(make(CommandType.START)).error)
  assertFalse(RecordingService.state.value.recording)
 }
 @Test fun missingConsentDoesNotStartMicrophone(){assertEquals(WearError.CONSENT_REQUIRED,send(make(CommandType.START,false)).error);assertFalse(RecordingService.state.value.recording)}
 @Test fun phoneCanStopWatchRecording(){start();Thread.sleep(1800);compose.runOnIdle{RecordingService.stop(context)};compose.waitUntil(20000){!s.loading&&s.editor.isNotBlank()};assertEquals(Phase.REPORT_READY,status().phase)}
 @Test fun watchCanStopPhoneRecording(){
  compose.runOnIdle{s.startQuick("document");s.recordingConsent=true;s.prepareRecordingContext("wear-synthetic:${s.encounterId}");RecordingService.start(context,"wear-synthetic:${s.encounterId}")}
  compose.waitUntil(10000){RecordingService.state.value.recording};RecordingService.state.value.filePath?.let{files+=it};assertEquals(Phase.RECORDING,status().phase)
  Thread.sleep(1800);assertEquals(WearError.NONE,send(make(CommandType.STOP)).error)
  compose.waitUntil(20000){!s.loading&&s.editor.isNotBlank()}
 }
 @Test fun cancelUsesExistingDeletionAndDoesNotGenerate(){
  start();Thread.sleep(1800);val path=RecordingService.state.value.filePath!!
  assertEquals(WearError.NONE,send(make(CommandType.CANCEL)).error)
  Thread.sleep(600);assertFalse(File(path).exists());assertFalse(s.loading);assertTrue(s.editor.isBlank());assertEquals(Phase.CANCELLED,status().phase)
 }
 @Test fun failedAiReportAutomaticallyRecoversOriginalWithoutRepeatingTranscription(){
  runBlocking{api.call("/test/config","POST",obj("failure" to "report-ai"))}
  start();Thread.sleep(1800);val path=RecordingService.state.value.filePath!!;send(make(CommandType.STOP))
  compose.waitUntil(20000){!s.loading&&s.editor.isNotBlank()}
  assertNull(s.error);assertEquals("editor",s.screen);assertTrue(File(path).isFile)
  assertTrue(s.documentAlerts.any{it.contains("source-only")})
  assertEquals(Phase.REPORT_READY,status().phase)
  val stats=(runBlocking{api.call("/test/stats")} as JSONObject).getJSONObject("calls")
  assertEquals(2,stats.getInt("quick-report"));assertEquals(1,stats.getInt("reports_created"))
  assertEquals(1,stats.getInt("audio-complete"))
 }
 @Test fun reportFailureRetainsRecordingAndRetryReusesTranscript(){
  runBlocking{api.call("/test/config","POST",obj("failure" to "quick-report"))}
  start();Thread.sleep(1800);val path=RecordingService.state.value.filePath!!;send(make(CommandType.STOP))
  compose.waitUntil(20000){!s.loading&&s.error!=null};assertTrue(File(path).exists());assertTrue(status().saved)
  val before=runBlocking{api.call("/test/stats")}as JSONObject
  runBlocking{api.call("/test/config","POST",obj("failure" to ""))}
  assertEquals(WearError.NONE,send(make(CommandType.RETRY)).error)
  compose.waitUntil(20000){!s.loading&&s.editor.isNotBlank()}
  val after=runBlocking{api.call("/test/stats")}as JSONObject
  assertEquals(before.getJSONObject("calls").optInt("audio-parts"),after.getJSONObject("calls").optInt("audio-parts"))
  assertEquals(Phase.REPORT_READY,status().phase)
 }
 @Test fun oldCommandCannotStartAfterEncounterChanged(){val c=make(CommandType.START);compose.runOnIdle{s.startQuick("document")};assertEquals(WearError.STALE_COMMAND,send(c).error);assertFalse(RecordingService.state.value.recording)}

 @Test fun simultaneousStartsHaveOneRecording(){
  val a=make(CommandType.START);val b=make(CommandType.START)
  val results=runBlocking{listOf(async(Dispatchers.Main){s.wearBridge.command(a)},async(Dispatchers.Main){s.wearBridge.command(b)}).awaitAll()}
  RecordingService.state.value.filePath?.let{files+=it}
  assertEquals(1,results.count{it.error==WearError.NONE});assertTrue(RecordingService.state.value.recording)
 }
 @Test fun lockDoesNotPreventStoppingAlreadyAuthorizedRecording(){
  start();Thread.sleep(1800);val c=make(CommandType.STOP)
  compose.runOnIdle{s.lock();s.wearBridge.foreground(false)}
  assertEquals(WearError.NONE,send(c).error)
  assertTrue(s.locked);assertFalse(RecordingService.state.value.recording)
  compose.waitUntil(20000){!s.loading}
 }
 @Test fun coldReceiverOnlyRestoresMetadataAndOpensAfterUnlock(){
  start();Thread.sleep(1800);send(make(CommandType.STOP));compose.waitUntil(20000){!s.loading&&s.editor.isNotBlank()}
  s.vault.write("session","synthetic-cold-session")
  try {
   val cold=runBlocking{withContext(Dispatchers.Main){ColdWearState.reply(context,WearCommand(type=CommandType.STATUS))}}
   assertEquals(Phase.REPORT_READY,cold.phase);assertTrue(cold.saved)
   val start=WearCommand(type=CommandType.START,token=cold.token,job=cold.job,consent=true)
   assertEquals(WearError.OPEN_PHONE_REQUIRED,ColdWearState.reply(context,start).error)
   compose.runOnIdle{s.lock();s.screen="home"}
   val open=WearCommand(type=CommandType.OPEN_PHONE,token=cold.token,job=cold.job)
   assertEquals(WearError.NONE,ColdWearState.reply(context,open).error)
   compose.runOnIdle{s.wearBridge.consumeOpen()};assertEquals("home",s.screen);assertTrue(s.locked)
   compose.runOnIdle{s.unlock()};assertEquals("editor",s.screen)
  }finally{s.vault.remove("session")}
 }
 @Test fun statusExplainsLockedPhoneBeforeUserAttemptsRecording(){
  compose.runOnIdle{s.lock()}
  assertEquals(WearError.PHONE_LOCKED,status().error)
  assertFalse(RecordingService.state.value.recording)
 }
 @Test fun openPhoneForSetupGoesStraightToDocumentWithoutRecording(){
  assertEquals(WearError.NONE,send(make(CommandType.OPEN_PHONE)).error)
  compose.waitUntil(15000){!s.loading}
  assertEquals("document_capture",s.screen)
  assertFalse(RecordingService.state.value.recording)
 }
 @Test fun setupLinkWaitsForUnlockAndCannotStartMicrophone(){
  compose.runOnIdle{s.lock();s.screen="home"}
  send(make(CommandType.OPEN_PHONE))
  assertTrue(s.locked);assertEquals("home",s.screen)
  compose.runOnIdle{s.unlock()};compose.waitUntil(15000){!s.loading}
  assertEquals("document_capture",s.screen);assertFalse(RecordingService.state.value.recording)
 }

 @Test fun coldReceiverDoesNotExposeAnotherOwnersActiveRecording(){
  start();Thread.sleep(1500)
  val cached=s.vault.read("cached-account")!!
  s.vault.write("session","synthetic-cold-session")
  try{
   s.vault.write("cached-account",obj("user" to obj("id" to "other-synthetic-owner")).toString())
   val cold=ColdWearState.reply(context,WearCommand(type=CommandType.STATUS))
   assertEquals(Phase.IDLE,cold.phase);assertEquals(0L,cold.elapsedMillis);assertFalse(cold.saved)
   assertEquals(WearError.NO_ACTIVE_RECORDING,ColdWearState.reply(context,WearCommand(type=CommandType.STOP,token=cold.token,job=cold.job)).error)
   assertTrue(RecordingService.state.value.recording)
   s.vault.remove("session")
   val signedOut=ColdWearState.reply(context,WearCommand(type=CommandType.STATUS))
   assertEquals(Phase.IDLE,signedOut.phase);assertEquals(0L,signedOut.elapsedMillis);assertEquals(WearError.SESSION_REQUIRED,signedOut.error)
  }finally{s.vault.write("cached-account",cached);s.vault.remove("session")}
 }
 @Test fun coldReceiverCanStopOnlySameAuthorizedActiveRecording(){
  start();Thread.sleep(1500);val path=RecordingService.state.value.filePath!!;s.vault.write("session","synthetic-cold-session")
  try{
   val cold=ColdWearState.reply(context,WearCommand(type=CommandType.STATUS))
   val stop=WearCommand(type=CommandType.STOP,token=cold.token,job=cold.job)
   val reply=ColdWearState.reply(context,stop)
   assertEquals(WearError.NONE,reply.error)
   compose.waitUntil(15000){!RecordingService.state.value.recording}
   assertEquals(WearError.STALE_COMMAND,ColdWearState.reply(context,stop).error)
   assertEquals(WearError.STALE_COMMAND,send(stop).error)
   assertTrue(File(path).isFile);assertTrue(File(path).length()>128)
   compose.waitUntil(20000){!s.loading&&s.editor.isNotBlank()}
   assertEquals(Phase.REPORT_READY,status().phase)
  }finally{s.vault.remove("session")}
 }
}
