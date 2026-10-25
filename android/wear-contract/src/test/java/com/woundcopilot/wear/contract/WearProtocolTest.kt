package com.woundcopilot.wear.contract
import org.junit.Test
import org.junit.Assert.*
class WearProtocolTest {
 private val now=100000L
 private fun state(p:Phase=Phase.IDLE)=WearStatus(phase=p)
 private fun cmd(s:WearStatus,t:CommandType)=WearCommand(type=t,token=s.token,job=s.job,issuedAt=now,consent=true)
 @Test fun roundTripEveryCommand(){val s=state();CommandType.entries.forEach{val c=cmd(s,it);assertEquals(c,WearWire.command(WearWire.command(c)))}}
 @Test fun roundTripEveryState(){Phase.entries.forEach{val s=state(it);assertEquals(s,WearWire.status(WearWire.status(s)))}}
 @Test fun validStart(){val s=state();assertEquals(WearError.NONE,CommandGate.check(cmd(s,CommandType.START),s,now))}
 @Test fun noConsent(){val s=state();assertEquals(WearError.CONSENT_REQUIRED,CommandGate.check(cmd(s,CommandType.START).copy(consent=false),s,now))}
 @Test fun duplicateStartWhileRecording(){val s=state(Phase.RECORDING);assertEquals(WearError.RECORDING_ALREADY_ACTIVE,CommandGate.check(cmd(s,CommandType.START),s,now))}
 @Test fun stopSameRecording(){val s=state(Phase.RECORDING);assertEquals(WearError.NONE,CommandGate.check(cmd(s,CommandType.STOP),s,now))}
 @Test fun stopPaused(){val s=state(Phase.PAUSED);assertEquals(WearError.NONE,CommandGate.check(cmd(s,CommandType.STOP),s,now))}
 @Test fun duplicateStopAfterSave(){val s=state(Phase.SAVED);assertEquals(WearError.NO_ACTIVE_RECORDING,CommandGate.check(cmd(s,CommandType.STOP),s,now))}
 @Test fun cancelCannotDeleteCompletedReport(){val s=state(Phase.REPORT_READY);assertEquals(WearError.NO_ACTIVE_RECORDING,CommandGate.check(cmd(s,CommandType.CANCEL),s,now))}
 @Test fun staleTimestamp(){val s=state();assertEquals(WearError.STALE_COMMAND,CommandGate.check(cmd(s,CommandType.START).copy(issuedAt=now-30001),s,now))}
 @Test fun futureTimestamp(){val s=state();assertEquals(WearError.STALE_COMMAND,CommandGate.check(cmd(s,CommandType.START).copy(issuedAt=now+5001),s,now))}
 @Test fun previousSessionCannotControlNewSession(){val s=state();assertEquals(WearError.STALE_COMMAND,CommandGate.check(cmd(s,CommandType.START).copy(token=WearProtocol.id()),s,now))}
 @Test fun previousJobCannotStopNewRecording(){val s=state(Phase.RECORDING);assertEquals(WearError.STALE_COMMAND,CommandGate.check(cmd(s,CommandType.STOP).copy(job=WearProtocol.id()),s,now))}
 @Test fun incompatibleVersion(){val s=state();assertEquals(WearError.PROTOCOL_INCOMPATIBLE,CommandGate.check(cmd(s,CommandType.STATUS).copy(version=999),s,now))}
 @Test fun retryOnlyRecoverableState(){val s=state(Phase.GENERATING_REPORT);assertEquals(WearError.BUSY,CommandGate.check(cmd(s,CommandType.RETRY),s,now))}
 @Test fun canRetryQueued(){val s=state(Phase.QUEUED_OFFLINE);assertEquals(WearError.NONE,CommandGate.check(cmd(s,CommandType.RETRY),s,now))}
 @Test fun openReadyReport(){val s=state(Phase.REPORT_READY);assertEquals(WearError.NONE,CommandGate.check(cmd(s,CommandType.OPEN_PHONE),s,now))}
 @Test fun noOptimisticRecording(){val m=WatchMirror();assertFalse(m.accept(state(Phase.RECORDING)));assertNull(m.status)}
 @Test fun restoreDoesNotEnableControls(){val m=WatchMirror();m.restore(state(Phase.RECORDING).copy(sampledAt=now),now);assertEquals(Phase.RECORDING,m.status?.phase);assertFalse(m.synchronized)}
 @Test fun reconnectionRequiresFreshStatus(){val m=WatchMirror();val s=state();assertTrue(m.accept(s,true));m.disconnected();assertFalse(m.accept(s.copy(sequence=5)));assertTrue(m.accept(s.copy(sequence=6),true))}
 @Test fun staleStateCannotUndoReportReady(){val m=WatchMirror();val s=state(Phase.REPORT_READY).copy(sequence=20);m.accept(s,true);assertFalse(m.accept(s.copy(phase=Phase.RECORDING,sequence=19)));assertEquals(Phase.REPORT_READY,m.status?.phase)}
 @Test fun newPhoneProcessRequiresHandshake(){val m=WatchMirror();m.accept(state(),true);assertFalse(m.accept(state(Phase.RECORDING)));assertTrue(m.accept(state(Phase.RECORDING),true))}
 @Test fun expiredCacheIsNotShown(){val m=WatchMirror();m.restore(state().copy(sampledAt=1),WearProtocol.CACHE_AGE+2);assertNull(m.status)}
 @Test fun clinicalStringsCannotEnterWire(){val s=state().copy(job="Fictional Patient name");assertThrows(IllegalArgumentException::class.java){WearWire.status(WearWire.status(s))}}
 @Test fun oversizePacketRejected(){assertThrows(IllegalArgumentException::class.java){WearWire.command(ByteArray(2049))}}
 @Test fun trailingFieldsRejected(){val bytes=WearWire.command(cmd(state(),CommandType.START))+byteArrayOf(1);assertThrows(IllegalArgumentException::class.java){WearWire.command(bytes)}}
 @Test fun negativeTimerRejected(){assertThrows(IllegalArgumentException::class.java){WearWire.status(WearWire.status(state().copy(elapsedMillis=-1)))}}
 @Test fun readyEventBeforeHandshakeReplyIsNotLost(){val m=WatchMirror();val ready=state(Phase.REPORT_READY).copy(sequence=8);assertFalse(m.accept(ready));assertTrue(m.accept(ready.copy(sequence=7,phase=Phase.GENERATING_REPORT),true));assertEquals(Phase.REPORT_READY,m.status?.phase)}

 @Test fun extremePastTimestampCannotOverflowExpiry(){val s=state();assertEquals(WearError.STALE_COMMAND,CommandGate.check(cmd(s,CommandType.START).copy(issuedAt=Long.MIN_VALUE),s,now))}
 @Test fun duplicateDataCannotResetAnAlreadyAcceptedTimer(){val m=WatchMirror();val s=state(Phase.RECORDING).copy(sequence=10,elapsedMillis=4000);m.accept(s,true);assertFalse(m.accept(s));assertEquals(s,m.status)}
 @Test fun emptyEpochIsNotAuthoritativeStatus(){assertThrows(IllegalArgumentException::class.java){WearWire.status(WearWire.status(state().copy(epoch="")))}}
 @Test fun emptyNonceIsNotAuthoritativeStatus(){assertThrows(IllegalArgumentException::class.java){WearWire.status(WearWire.status(state().copy(token="")))}}
}
