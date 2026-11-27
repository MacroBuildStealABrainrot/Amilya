package com.woundcopilot.android

internal const val RECORDING_LIMIT_MILLIS = 3_600_000

/** A CPU-only hold for one active recording span. A paused visit holds no wake lock. */
internal class NotesRecordingPower(private val acquire: (Long) -> (() -> Unit)) {
    private var releaseLock: (() -> Unit)? = null

    fun hold(completedMillis: Long): Boolean {
        if (releaseLock != null) return true
        val remaining = RECORDING_LIMIT_MILLIS - completedMillis.coerceIn(0L, RECORDING_LIMIT_MILLIS.toLong())
        // Allow the recorder to close the file after Android signals its duration limit.
        return try {
            releaseLock = acquire(remaining + 30_000L)
            true
        } catch (_: RuntimeException) {
            // The microphone can still work. The service tells the user to keep the screen on.
            false
        }
    }

    fun release() {
        val release = releaseLock ?: return
        releaseLock = null
        try {
            release()
        } catch (_: RuntimeException) {
            // The timed hold still expires if the OS rejects release. Allow recording teardown to finish.
        }
    }
}
