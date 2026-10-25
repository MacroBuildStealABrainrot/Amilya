package com.woundcopilot.android

import java.io.File

/**
 * Sandbox rules for recordings that are not finished with yet ("unresolved"): recovery pending, upload pending, or report not
 * yet confirmed. Plain logic that is tested on a PC. Nothing here deletes anything.
 *
 *  - A recording stays unresolved until the app itself removes its recovery note: after the report is saved, or after the
 *    clinician explicitly chooses "Discard this recording" and confirms.
 *  - Age, low storage, a full recovery list or an expired job record NEVER remove an unresolved recording.
 *  - Instead the clinician is told (a reminder notification, plain words, no patient details), and when the phone is nearly
 *    full or too many recordings are waiting, a NEW recording is refused with a clear message.
 */
internal object UnresolvedRetention {
    const val FIRST_REMINDER_AFTER_MILLIS = 2L * 24 * 60 * 60 * 1000
    const val REMINDER_EVERY_MILLIS = 24L * 60 * 60 * 1000
    /** Less than this free: do not start a recording (a one-hour visit needs about 17 MB; this leaves room for the rest of the phone). */
    const val MIN_FREE_TO_START_BYTES = 200L * 1024 * 1024
    /** Less than this free while recording: stop and save, so the last pieces are not lost to a full disk. */
    const val MIN_FREE_WHILE_RECORDING_BYTES = 40L * 1024 * 1024
    /** Waiting recordings use more than this: show a warning. About 60 hours of audio. */
    const val WARN_UNRESOLVED_BYTES = 1024L * 1024 * 1024
    /** Waiting recordings use more than this: refuse new recordings until some are finished or discarded. */
    const val BLOCK_UNRESOLVED_BYTES = 3L * 1024 * 1024 * 1024

    enum class Level { OK, WARN, BLOCK }
    class Verdict(val level: Level, val message: String?)

    fun megabytes(bytes: Long) = (bytes + 512 * 1024) / (1024 * 1024)

    fun beforeStarting(freeBytes: Long, unresolvedBytes: Long, unresolvedCount: Int): Verdict = when {
        freeBytes < MIN_FREE_TO_START_BYTES -> Verdict(Level.BLOCK,
            "There is not enough free space on this phone to record safely (about ${megabytes(freeBytes)} MB free). Free some space and try again. " +
                "Nothing in WoundCoPilot was deleted.")
        unresolvedBytes >= BLOCK_UNRESOLVED_BYTES -> Verdict(Level.BLOCK,
            "WoundCoPilot is holding $unresolvedCount recordings (about ${megabytes(unresolvedBytes)} MB) that are not finished yet. " +
                "Finish or discard some of them before starting a new recording. Nothing is deleted automatically.")
        unresolvedBytes >= WARN_UNRESOLVED_BYTES -> Verdict(Level.WARN,
            "$unresolvedCount unfinished recordings are using about ${megabytes(unresolvedBytes)} MB. Please finish or discard some soon.")
        else -> Verdict(Level.OK, null)
    }

    fun whileRecording(freeBytes: Long): Verdict =
        if (freeBytes < MIN_FREE_WHILE_RECORDING_BYTES) Verdict(Level.BLOCK,
            "The phone is almost out of storage, so the recording was stopped and saved. Free some space and start another recording to continue.")
        else Verdict(Level.OK, null)

    class Reminder(val title: String, val text: String)

    /** A reminder is due when something has waited for two days, and at most once a day after that. Never contains patient details. */
    fun reminder(now: Long, oldestWaitingSince: Long?, count: Int, lastReminderAt: Long): Reminder? {
        if (count <= 0 || oldestWaitingSince == null) return null
        if (now - oldestWaitingSince < FIRST_REMINDER_AFTER_MILLIS) return null
        if (lastReminderAt > 0 && now - lastReminderAt < REMINDER_EVERY_MILLIS) return null
        return Reminder(
            if (count == 1) "A recording is waiting" else "$count recordings are waiting",
            "Open WoundCoPilot to upload, finish or discard ${if (count == 1) "it" else "them"}. Nothing is deleted automatically.",
        )
    }

    // ---- unfinished reports and drafts (decision 1 of test8): same promise as for recordings ----

    /**
     * Stored records that are unfinished clinical work. In the sandbox build they never expire because of age: the app removes them
     * itself when the report is saved or the clinician discards the work. Everything else the 7-day clean-up handles
     * (local copies of already-saved photos "source-", watch hand-over notes "wear-", "diagnostic-" logs, "local-bind-") still expires.
     */
    val UNFINISHED_WORK_PREFIXES = listOf(
        "draft-", "draft-recovery-", "consult-draft-", "consult-chat-", "photo-batch-",
        "capture-", "upload-", "audio-batch-", "recording-job-", "recording-context-",
    )
    /** Drafts and reports being written (the ones a clinician would call "my unfinished report"). */
    val DRAFT_PREFIXES = listOf("draft-", "draft-recovery-", "consult-draft-")

    fun isUnfinishedWork(recordName: String) = UNFINISHED_WORK_PREFIXES.any { recordName.startsWith(it) }
    fun isDraft(recordName: String) = DRAFT_PREFIXES.any { recordName.startsWith(it) }

    /** True when the 7-day clean-up must leave this stored record alone. When [retain] is off this is always false (original behaviour). */
    fun keepRecord(recordName: String, retain: Boolean, protectedNames: Set<String>): Boolean =
        retain && (recordName in protectedNames || isUnfinishedWork(recordName))

    /** Like [reminder] but also counts unfinished drafts. With no drafts it gives exactly what [reminder] gives. */
    fun reminderForWork(now: Long, oldestWaitingSince: Long?, recordings: Int, drafts: Int, lastReminderAt: Long): Reminder? {
        val total = recordings + drafts
        if (total <= 0 || oldestWaitingSince == null) return null
        if (now - oldestWaitingSince < FIRST_REMINDER_AFTER_MILLIS) return null
        if (lastReminderAt > 0 && now - lastReminderAt < REMINDER_EVERY_MILLIS) return null
        if (drafts == 0) return reminder(now, oldestWaitingSince, recordings, lastReminderAt)
        val what = when {
            recordings == 0 -> if (drafts == 1) "An unfinished report is waiting" else "$drafts unfinished reports are waiting"
            else -> "$total unfinished items are waiting"
        }
        return Reminder(what, "Open WoundCoPilot to finish, save or discard ${if (total == 1) "it" else "them"}. Nothing is deleted automatically.")
    }

    class Waiting(val media: File, val sinceMillis: Long, val bytes: Long)

    /** Recordings that still have a recovery note next to them, with the space they use (including encrypted pieces). */
    fun waiting(recordingsRoot: File): List<Waiting> =
        recordingsRoot.listFiles { f -> f.isFile && f.name.endsWith(".m4a.json") }.orEmpty().map { note ->
            val media = File(note.path.removeSuffix(".json"))
            val session = File(recordingsRoot, RetentionRules.SESSION_PREFIX + media.name.removeSuffix(".m4a"))
            val bytes = (if (media.isFile) media.length() else 0L) + folderBytes(session)
            // Recordings are named recording-<time in ms>-<id>.m4a; fall back to the note's own time.
            val made = media.name.substringAfter("recording-").substringBefore('-').toLongOrNull() ?: note.lastModified()
            Waiting(media, made, bytes)
        }

    private fun folderBytes(folder: File): Long = folder.listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L }

    // ---- which stored app records must not expire while a recording is unresolved ----

    /** One stored recovery job (already read from the app's private store). */
    class JobRecord(val key: String, val file: String, val phase: String, val owner: String, val encounterId: String)
    /** One stored upload checkpoint and the files it covers. */
    class BatchRecord(val key: String, val files: List<String>)

    /**
     * Names of stored app records that must NOT be removed by the 7-day expiry because they belong to a recording that is still
     * unresolved (not completed, and its recording or recovery note still exists). Records of finished recordings are not protected.
     * [captureValues]: stored "capture-…" name to the file path it points to. [contextKeys]: stored "recording-context-…" names.
     * [uploadKeys]: stored "upload-…" names (the small records that make a retried upload recognisable to the server; losing them
     * could cause a second paid transcription, so they are kept while anything is unresolved).
     */
    fun protectedRecordNames(
        jobs: List<JobRecord>, batches: List<BatchRecord>, captureValues: Map<String, String>,
        contextKeys: Collection<String>, uploadKeys: Collection<String>, recordingExists: (path: String) -> Boolean,
    ): Set<String> {
        val open = jobs.filter { it.phase != "COMPLETED" && it.file.isNotBlank() && recordingExists(it.file) }
        if (open.isEmpty()) return emptySet()
        val keep = mutableSetOf<String>()
        val files = open.map { it.file }.toSet()
        open.forEach { keep += it.key }
        // The app's store turns every character other than letters, digits, "_", "." and "-" into "_" in a record's name.
        val contexts = open.map { "recording-context-${it.owner}:${it.encounterId}".replace(Regex("[^a-zA-Z0-9_.-]"), "_") }.toSet()
        contextKeys.filter { it in contexts }.forEach { keep += it }
        for ((name, path) in captureValues) if (path in files) { keep += name; keep += "$name-mime"; keep += "$name-review" }
        batches.filter { b -> b.files.any { it in files } }.forEach { keep += it.key }
        keep += uploadKeys
        return keep
    }
}
