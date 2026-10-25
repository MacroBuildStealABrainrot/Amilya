package com.woundcopilot.wear.contract
import org.junit.Test
import org.junit.Assert.*
class ConnectionEpochTest {
 @Test fun cannotDiscoverWhilePaused(){assertNull(ConnectionEpoch().request())}
 @Test fun oldQueryCannotDisconnectNewPeer(){val e=ConnectionEpoch();e.resume();val a=e.request()!!;val b=e.request()!!;assertFalse(e.accepts(a));assertTrue(e.accepts(b))}
 @Test fun lateSuccessCannotRevivePausedScreen(){val e=ConnectionEpoch();e.resume();val a=e.request()!!;e.pause();assertFalse(e.accepts(a));assertNull(e.request())}
 @Test fun priorSessionCallbackCannotReplaceReconnection(){val e=ConnectionEpoch();e.resume();val old=e.request()!!;e.pause();e.resume();val current=e.request()!!;assertFalse(e.accepts(old));assertTrue(e.accepts(current))}

 @Test fun discardConfirmationCannotFollowNewRecording(){val s=WearStatus(job=WearProtocol.id(),phase=Phase.RECORDING);assertFalse(confirmationAllowed(CommandType.CANCEL,WearProtocol.id(),s,true));assertTrue(confirmationAllowed(CommandType.CANCEL,s.job,s,true))}
 @Test fun disconnectedConfirmationCannotExecute(){val s=WearStatus(phase=Phase.RECORDING);assertFalse(confirmationAllowed(CommandType.CANCEL,s.job,s,false))}
 @Test fun completedRecordingCannotBeDiscardedByOldDialog(){val s=WearStatus(phase=Phase.REPORT_READY);assertFalse(confirmationAllowed(CommandType.CANCEL,s.job,s,true));assertTrue(confirmationAllowed(CommandType.START,s.job,s,true))}
}
