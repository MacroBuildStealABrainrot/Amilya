package com.woundcopilot.android

/*
 * 1.8.5-test10. Pure rules (no Android), unit-tested on a PC:
 *  - MicInterruption: Android 10+ can silently take the microphone from a recording (phone call, another app). The recorder keeps
 *    running but records silence and reports no error. The services ask Android once a second whether this recording is silenced
 *    and show these plain-words messages.
 *  - RecordingPermissions: which permissions to ask for before recording. Notifications (Android 13+) are asked once so the
 *    recording notification with its Stop button is visible; refusing them never blocks recording.
 */
internal class MicInterruption {
    private var silencedSinceMs: Long? = null
    private var firstAtMs: Long? = null
    private var totalMs = 0L
    var times = 0; private set

    /** Call about once a second with whether Android says this recording is silenced. Returns the message to show now, or null. */
    fun update(silenced: Boolean, elapsedMs: Long): String? {
        val since = silencedSinceMs
        if (silenced && since == null) {
            silencedSinceMs = elapsedMs; times++
            if (firstAtMs == null) firstAtMs = elapsedMs
        } else if (!silenced && since != null) {
            totalMs += (elapsedMs - since).coerceAtLeast(0L)
            silencedSinceMs = null
        }
        return when {
            silencedSinceMs != null -> "The microphone is being used by a phone call or another app (since ${clock(silencedSinceMs!!)}). " +
                "Nothing is being recorded until it is free; the recording continues by itself after that."
            times > 0 -> summary(elapsedMs)
            else -> null
        }
    }

    /** Message to keep with the saved recording, or null if the microphone was never taken away. */
    fun summary(elapsedMs: Long): String? {
        if (times == 0) return null
        val total = totalMs + (silencedSinceMs?.let { (elapsedMs - it).coerceAtLeast(0L) } ?: 0L)
        val count = if (times == 1) "once" else "$times times"
        return "The microphone was taken by a phone call or another app $count (first at ${clock(firstAtMs ?: 0L)}, about ${seconds(total)} in total). " +
            "Those parts of the recording are silent. Check the report for anything missing from those moments."
    }

    private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
    private fun seconds(ms: Long): String { val s = ((ms + 500) / 1000).coerceAtLeast(1); return if (s < 60) "$s s" else "%d min %02d s".format(s / 60, s % 60) }
}

internal object RecordingPermissions {
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val NOTIFICATIONS_ASKED_KEY = "notification-permission-asked"

    /** Permissions to request before recording, or an empty list to start recording straight away. */
    fun toRequest(sdk: Int, microphoneGranted: Boolean, notificationsGranted: Boolean, notificationsAskedBefore: Boolean): List<String> =
        listOfNotNull(
            if (!microphoneGranted) RECORD_AUDIO else null,
            if (sdk >= 33 && !notificationsGranted && !notificationsAskedBefore) POST_NOTIFICATIONS else null,
        )

    /** Recording may start only with the microphone; the notification answer never blocks it. */
    fun mayRecord(microphoneGrantedNow: Boolean) = microphoneGrantedNow

    /** Shown during recording when notifications are off on Android 13+, so the clinician knows where Stop is. */
    fun notificationHint(sdk: Int, notificationsGranted: Boolean): String? =
        if (sdk >= 33 && !notificationsGranted)
            "Notifications are off for this app, so the recording controls are not in the notification shade. Use Stop on this screen. " +
                "To turn them on: Settings > Apps > WoundCoPilot > Notifications."
        else null
}
