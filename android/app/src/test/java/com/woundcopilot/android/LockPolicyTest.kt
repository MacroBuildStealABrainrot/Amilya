package com.woundcopilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.5-test9: PIN loop after the camera or photo picker. */
class LockPolicyTest {
    private fun lock(device: Boolean = false, id: String? = "op", live: Boolean = true, elapsed: Long = 1_000L,
                     awaiting: Boolean = false, returned: Boolean = true, lockedFor: String? = null) =
        LockPolicy.lockForPendingOperation(device, id, live, elapsed, awaiting, returned, lockedFor)

    @Test fun aLockedPhoneAlwaysLocksTheApp() {
        assertTrue(lock(device = true))
        assertTrue(lock(device = true, id = null))
        assertTrue(lock(device = true, lockedFor = "op"))
    }

    @Test fun noPendingOperationNeverLocksOnResume() = assertFalse(lock(id = null, live = false, elapsed = 10_000_000L))

    @Test fun aNormalCameraReturnInsideTheWindowDoesNotAskForThePin() {
        assertFalse(lock(elapsed = 59_000L))
        assertFalse(lock(elapsed = 170_000L)) // 60 s was too short for a careful wound photo
    }

    @Test fun aTooLongOrUntrustedOperationLocksOnce() {
        assertTrue(lock(elapsed = LockPolicy.EXTERNAL_FLOW_MS + 1))
        assertTrue(lock(live = false))
        assertTrue(lock(awaiting = true, returned = false))
        assertTrue(lock(elapsed = -5L))
    }

    @Test fun afterUnlockTheSameOldOperationDoesNotLockAgain() {
        // A biometric prompt pauses and resumes the app. Before the fix this re-locked every time: an endless PIN loop.
        assertFalse(lock(live = false, lockedFor = "op"))
        assertFalse(lock(elapsed = LockPolicy.EXTERNAL_FLOW_MS + 1, lockedFor = "op"))
        // A different, newer operation is judged on its own.
        assertTrue(lock(id = "newer", live = false, lockedFor = "op"))
    }

    @Test fun onlyARestoredOperationWithNoResultIsForgotten() {
        assertTrue(LockPolicy.forgetRestoredOperation(restoredFromStorage = true, resultReturned = false, importing = false))
        assertFalse(LockPolicy.forgetRestoredOperation(restoredFromStorage = true, resultReturned = true, importing = false))
        assertFalse(LockPolicy.forgetRestoredOperation(restoredFromStorage = true, resultReturned = false, importing = true))
        assertFalse(LockPolicy.forgetRestoredOperation(restoredFromStorage = false, resultReturned = false, importing = false))
    }

    private fun deferred(user: String? = "u1", visit: String? = "v1", locked: Boolean = false, loading: Boolean = false, importing: Boolean = false) =
        LockPolicy.deferredPhotoAction(user, "u1", visit, "v1", locked, loading, importing)

    @Test fun aDeferredPhotoWaitsWhileTheAppIsBusyOrLockedInsteadOfBeingDropped() {
        // 1.8.5-test9 bug: the reference was cleared before the "app is loading" check, so the photo was never sent.
        assertTrue(deferred(loading = true) == LockPolicy.Deferred.WAIT)
        assertTrue(deferred(locked = true) == LockPolicy.Deferred.WAIT)
        assertTrue(deferred(importing = true) == LockPolicy.Deferred.WAIT)
        assertTrue(deferred(visit = "other-visit") == LockPolicy.Deferred.WAIT)
        assertTrue(deferred() == LockPolicy.Deferred.SEND)
    }

    @Test fun aDeferredPhotoIsNeverSentForAnotherClinician() {
        assertTrue(deferred(user = "u2") == LockPolicy.Deferred.DROP)
        assertTrue(deferred(user = null) == LockPolicy.Deferred.DROP)
    }
}
