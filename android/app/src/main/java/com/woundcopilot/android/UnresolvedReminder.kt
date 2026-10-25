package com.woundcopilot.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import java.io.File

/** Sandbox only. Tells the clinician, once a day at most, that recordings are still waiting. No patient details are shown. */
internal object UnresolvedReminder {
    private const val CHANNEL = "woundcopilot_waiting"
    private const val NOTIFICATION_ID = 4042

    fun check(context: Context, now: Long) {
        val waiting = UnresolvedRetention.waiting(RecordingService.recordingsDirectory(context))
        val stamp = File(context.noBackupFilesDir, "waiting-reminder.txt")
        val last = runCatching { stamp.readText().trim().toLong() }.getOrDefault(0L)
        val vault = Vault(context)
        val drafts = vault.names().filter { UnresolvedRetention.isDraft(it) }
        val oldest = listOfNotNull(waiting.minOfOrNull { it.sinceMillis }, drafts.minOfOrNull { vault.modified(it) }).minOrNull()
        val reminder = UnresolvedRetention.reminderForWork(now, oldest, waiting.size, drafts.size, last) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Waiting recordings", NotificationManager.IMPORTANCE_DEFAULT))
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(context, 31, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        manager.notify(NOTIFICATION_ID, builder.build())
        runCatching { stamp.writeText(now.toString()) }
    }
}
