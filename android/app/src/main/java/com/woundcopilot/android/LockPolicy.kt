package com.woundcopilot.android

/**
 * When an app-started camera, photo picker, file picker or permission screen needs the PIN again. Pure rules, unit-tested.
 *
 * Unchanged on purpose: going to the home screen or another app still locks at once, and a locked phone always locks the app.
 */
internal object LockPolicy {
    /** How long an app-started camera or picker may stay open before the PIN is needed again (was 60 seconds). */
    const val EXTERNAL_FLOW_MS = 180_000L

    /**
     * True when returning to the app must show the PIN screen because of a pending camera/picker operation.
     * [alreadyLockedFor] is the operation the app already locked for: once the clinician has unlocked, the same old operation
     * must not lock the app again on every resume (a biometric prompt pauses and resumes the app, which caused a PIN loop).
     */
    fun lockForPendingOperation(
        deviceLocked: Boolean,
        operationId: String?,
        live: Boolean,
        elapsedMs: Long,
        awaitingResult: Boolean,
        resultReturned: Boolean,
        alreadyLockedFor: String?,
    ): Boolean {
        if (deviceLocked) return true
        if (operationId == null) return false
        val stale = !live || elapsedMs !in 0..EXTERNAL_FLOW_MS || (awaitingResult && !resultReturned)
        return stale && alreadyLockedFor != operationId
    }

    /**
     * An operation restored after Android closed the app can only receive its result before the first resume. If none arrived,
     * it never will: forget it so it cannot block the next camera or picker launch for up to 24 hours.
     */
    fun forgetRestoredOperation(restoredFromStorage: Boolean, resultReturned: Boolean, importing: Boolean): Boolean =
        restoredFromStorage && !resultReturned && !importing

    enum class Deferred { SEND, WAIT, DROP }

    /**
     * What to do with a photo that came back from the camera/picker while the app was locked or busy.
     * Another clinician signed in: drop (never send their photo). Locked, busy, or a different visit open: wait.
     */
    fun deferredPhotoAction(currentUser: String?, photoOwner: String, openVisit: String?, photoVisit: String,
                            locked: Boolean, loading: Boolean, importing: Boolean): Deferred = when {
        currentUser == null || currentUser != photoOwner -> Deferred.DROP
        locked || loading || importing || openVisit != photoVisit -> Deferred.WAIT
        else -> Deferred.SEND
    }
}
