package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * Decision 3: recordings awaiting recovery, upload or a confirmed report must not be deleted just because time passed, and
 * the clean-up for everything else must behave exactly as before. These run the same decision code the app's clean-up uses.
 */
class UnresolvedRetentionTest {
    @get:Rule val folder = TemporaryFolder()
    private val DAY = 24L * 60 * 60 * 1000
    private val MEDIA = DAY; private val RECOVERY = 7 * DAY
    private val NOW = 1_800_000_000_000L

    private fun root() = folder.newFolder("recordings")

    /** What the app's clean-up would delete in [root] at time [now]. Same walk and same decision as LocalRetention. */
    private fun sweep(root: File, now: Long, prot: Set<String>, pend: Set<String>, retain: Boolean): Set<String> =
        root.walkTopDown().filter { it.isFile }.filter { RetentionRules.shouldDelete(it, root, now, MEDIA, RECOVERY, prot, pend, retain) }.map { it.relativeTo(root).path }.toSet()

    private fun age(file: File, ms: Long) { file.setLastModified(NOW - ms) }

    /** A sandbox recording that is finished but not yet reported: placeholder, note, encrypted pieces. */
    private fun sealedWaiting(root: File, id: String, ageMs: Long): File {
        val media = File(root, "recording-$id.m4a")
        SealedPlaceholder.write(media, "safe-recording-$id")
        val note = File(root, "recording-$id.m4a.json").apply { writeText("{}") }
        val dir = File(root, "safe-recording-$id").apply { mkdirs() }
        File(dir, "seg-00001.aac").writeBytes(ByteArray(500))
        SessionInfo(dir).write(mapOf("status" to "finished", "finalName" to media.name, "format" to "sealed-v1"))
        listOf(media, note, File(dir, "seg-00001.aac"), File(dir, "session.properties")).forEach { age(it, ageMs) }
        return media
    }

    // ---- nothing changes for everything the old clean-up handled ----

    /** The original clean-up decision, copied from the 1.8.5-test5 source, as the reference. */
    private fun original(file: File, root: File, now: Long, prot: Set<String>, pend: Set<String>): Boolean {
        val age = if (file.canonicalPath in pend || file.extension == "json" || File(file.path + ".json").exists()) RECOVERY else MEDIA
        return file.canonicalPath !in prot && file.canonicalPath.startsWith(root.path + File.separator) && now - file.lastModified() > age
    }

    @Test fun withoutTheNewRuleTheDecisionIsExactlyTheOriginalOne() {
        val random = Random(2026)
        val r = root(); File(r, "segments").mkdirs()
        val files = mutableListOf<File>()
        repeat(60) { i ->
            val kind = random.nextInt(4)
            val f = when (kind) {
                0 -> File(r, "recording-$i.m4a")
                1 -> File(r, "recording-$i.m4a.json")
                2 -> File(r, "segments/segment-$i.m4a")
                else -> File(r, "capture-$i.jpg")
            }
            f.writeBytes(ByteArray(10)); files += f
        }
        repeat(2000) {
            files.forEach { age(it, (random.nextDouble() * 20 * DAY).toLong()) }
            val prot = files.filter { random.nextInt(6) == 0 }.map { it.canonicalPath }.toSet()
            val pend = files.filter { random.nextInt(6) == 0 }.map { it.canonicalPath }.toSet()
            for (f in files) assertEquals(f.name, original(f, r, NOW, prot, pend), RetentionRules.shouldDelete(f, r, NOW, MEDIA, RECOVERY, prot, pend, retainUnresolved = false))
        }
    }

    // ---- the new rule ----

    @Test fun anUnresolvedRecordingIsNeverDeletedByAgeNoMatterHowOld() {
        val r = root(); sealedWaiting(r, "a", 0)
        for (days in listOf(1, 2, 8, 40, 400, 4000)) {
            val files = r.walkTopDown().filter { it.isFile }
            files.forEach { age(it, days * DAY) }
            assertEquals("after $days days", emptySet<String>(), sweep(r, NOW, emptySet(), emptySet(), retain = true))
        }
    }

    @Test fun theOldRuleWouldHaveDestroyedItAfterSevenDays() {
        // This documents the hazard: without the new rule, placeholder and note age out, and then so do the encrypted pieces.
        val r = root(); sealedWaiting(r, "b", 9 * DAY)
        val first = sweep(r, NOW, emptySet(), emptySet(), retain = false)
        assertTrue("the placeholder and its note are removed first", "recording-b.m4a" in first && "recording-b.m4a.json" in first)
        first.forEach { File(r, it).delete() }
        val second = sweep(r, NOW, emptySet(), emptySet(), retain = false)
        assertTrue("then the encrypted pieces become deletable too: the audio would be lost", "safe-recording-b/seg-00001.aac" in second)
    }

    @Test fun onceTheAppRemovesTheNoteTheRecordingFollowsTheNormalRules() {
        val r = root(); val media = sealedWaiting(r, "c", 400 * DAY)
        assertTrue(sweep(r, NOW, emptySet(), emptySet(), true).isEmpty())
        File(r, "recording-c.m4a.json").delete() // report saved, or the clinician confirmed a discard
        val gone = sweep(r, NOW, emptySet(), emptySet(), true)
        assertTrue("the placeholder now ages out", media.name in gone)
    }

    @Test fun aRecordingWithAJobButNoNoteIsAlsoKept() {
        val r = root(); val media = File(r, "recording-d.m4a").apply { writeBytes(ByteArray(1000)) }; age(media, 90 * DAY)
        assertEquals(emptySet<String>(), sweep(r, NOW, setOf(media.canonicalPath), emptySet(), true))
    }

    @Test fun temporaryUploadFilesOfAnUnfinishedUploadAreKept() {
        val r = root(); File(r, "segments").mkdirs()
        val part = File(r, "segments/segment-1.m4a").apply { writeBytes(ByteArray(100)) }; age(part, 90 * DAY)
        assertEquals(emptySet<String>(), sweep(r, NOW, emptySet(), setOf(part.canonicalPath), true))
        assertEquals(setOf("segments/segment-1.m4a"), sweep(r, NOW, emptySet(), setOf(part.canonicalPath), false))
    }

    @Test fun otherOldFilesStillAgeOutAsBefore() {
        val r = root()
        val photo = File(r, "capture-9.jpg").apply { writeBytes(ByteArray(10)) }; age(photo, 3 * DAY)
        assertEquals(setOf("capture-9.jpg"), sweep(r, NOW, emptySet(), emptySet(), true))
    }

    // ---- stored records ----

    private fun job(key: String, file: String, phase: String = "RECORDED_LOCALLY", owner: String = "u1", encounter: String = "e1") =
        UnresolvedRetention.JobRecord(key, file, phase, owner, encounter)

    @Test fun theStoredRecordsOfAnUnresolvedRecordingAreProtected() {
        val keep = UnresolvedRetention.protectedRecordNames(
            jobs = listOf(job("recording-job-recording-1.m4a", "/f/recording-1.m4a")),
            batches = listOf(UnresolvedRetention.BatchRecord("audio-batch-x", listOf("/f/recording-1.m4a")), UnresolvedRetention.BatchRecord("audio-batch-other", listOf("/f/other.m4a"))),
            captureValues = mapOf("capture-u1-e1-audio" to "/f/recording-1.m4a", "capture-u1-e2-audio" to "/f/else.m4a"),
            contextKeys = listOf("recording-context-u1_e1", "recording-context-u1_e2"),
            uploadKeys = listOf("upload-abc"),
            recordingExists = { true },
        )
        assertEquals(setOf("recording-job-recording-1.m4a", "audio-batch-x", "capture-u1-e1-audio", "capture-u1-e1-audio-mime", "capture-u1-e1-audio-review", "recording-context-u1_e1", "upload-abc"), keep)
    }

    @Test fun completedMissingOrBlankJobsProtectNothing() {
        fun keep(j: UnresolvedRetention.JobRecord, exists: Boolean) = UnresolvedRetention.protectedRecordNames(listOf(j), emptyList(), emptyMap(), emptyList(), listOf("upload-abc")) { exists }
        assertTrue(keep(job("recording-job-a", "/f/a.m4a", "COMPLETED"), true).isEmpty())
        assertTrue("its recording and note are gone, so nothing is waiting", keep(job("recording-job-a", "/f/a.m4a"), false).isEmpty())
        assertTrue(keep(job("recording-job-a", ""), true).isEmpty())
    }

    // ---- storage limits and reminders ----

    @Test fun aNewRecordingIsRefusedWhenThePhoneIsNearlyFull() {
        val v = UnresolvedRetention.beforeStarting(UnresolvedRetention.MIN_FREE_TO_START_BYTES - 1, 0, 0)
        assertEquals(UnresolvedRetention.Level.BLOCK, v.level); assertTrue(v.message!!.contains("Nothing in WoundCoPilot was deleted"))
        assertEquals(UnresolvedRetention.Level.OK, UnresolvedRetention.beforeStarting(UnresolvedRetention.MIN_FREE_TO_START_BYTES, 0, 0).level)
    }

    @Test fun aNewRecordingIsRefusedWhenTooManyUnfinishedOnesAreWaiting() {
        val gb = 1024L * 1024 * 1024
        assertEquals(UnresolvedRetention.Level.OK, UnresolvedRetention.beforeStarting(10 * gb, gb / 2, 3).level)
        assertEquals(UnresolvedRetention.Level.WARN, UnresolvedRetention.beforeStarting(10 * gb, gb, 5).level)
        val blocked = UnresolvedRetention.beforeStarting(10 * gb, 3 * gb, 9)
        assertEquals(UnresolvedRetention.Level.BLOCK, blocked.level)
        assertTrue(blocked.message!!.contains("Nothing is deleted automatically")); assertTrue(blocked.message!!.contains("9 recordings"))
    }

    @Test fun recordingStopsAndSavesWhenStorageRunsOut() {
        assertEquals(UnresolvedRetention.Level.OK, UnresolvedRetention.whileRecording(UnresolvedRetention.MIN_FREE_WHILE_RECORDING_BYTES).level)
        val v = UnresolvedRetention.whileRecording(UnresolvedRetention.MIN_FREE_WHILE_RECORDING_BYTES - 1)
        assertEquals(UnresolvedRetention.Level.BLOCK, v.level); assertTrue(v.message!!.contains("stopped and saved"))
    }

    @Test fun remindersWaitTwoDaysThenComeAtMostOncePerDay() {
        val t0 = NOW - 3 * DAY
        assertNull(UnresolvedRetention.reminder(NOW, null, 0, 0))
        assertNull("not yet two days", UnresolvedRetention.reminder(NOW, NOW - DAY, 1, 0))
        val first = UnresolvedRetention.reminder(NOW, t0, 2, 0)!!
        assertEquals("2 recordings are waiting", first.title)
        assertNull("already reminded today", UnresolvedRetention.reminder(NOW + DAY / 2, t0, 2, NOW))
        assertNotNull("a day later", UnresolvedRetention.reminder(NOW + DAY, t0, 2, NOW))
        assertEquals("A recording is waiting", UnresolvedRetention.reminder(NOW, t0, 1, 0)!!.title)
    }

    @Test fun reminderTextNeverContainsPatientDetailsOrFileNames() {
        val text = UnresolvedRetention.reminder(NOW, NOW - 5 * DAY, 3, 0)!!.let { it.title + " " + it.text }
        assertFalse(text.contains("recording-")); assertFalse(text.contains(".m4a"))
        assertTrue(text.contains("Nothing is deleted automatically"))
    }

    @Test fun waitingListCountsNotesAndTheSpaceTheyUse() {
        val r = root()
        File(r, "recording-1700000000000-aaa.m4a.json").writeText("{}")
        SealedPlaceholder.write(File(r, "recording-1700000000000-aaa.m4a"), "safe-recording-1700000000000-aaa")
        File(r, "safe-recording-1700000000000-aaa").mkdirs(); File(r, "safe-recording-1700000000000-aaa/seg-00001.aac").writeBytes(ByteArray(5000))
        File(r, "recording-1700000009999-bbb.m4a.json").writeText("{}")
        File(r, "recording-9.jpg").writeBytes(ByteArray(7))
        val list = UnresolvedRetention.waiting(r).sortedBy { it.sinceMillis }
        assertEquals(2, list.size)
        assertEquals(1700000000000L, list[0].sinceMillis)
        assertTrue(list[0].bytes >= 5000)
        assertEquals(0L, list[1].bytes)
    }

    // ---- unfinished reports and drafts (test8) ----
    private val allKinds = listOf("draft-u1-e1", "draft-recovery-u1-e1", "consult-draft-u1-e1", "consult-chat-u1-e1", "photo-batch-pending-u1-e1",
        "capture-u1-e1-audio", "capture-u1-e1-audio-mime", "upload-abc", "audio-batch-xyz", "recording-job-recording-1.m4a", "recording-context-u1_e1",
        "source-u1-ev1", "local-bind-1", "diagnostic-1", "wear-cancel-x.m4a", "cached-account", "pin-failures")

    @Test fun unfinishedReportsAndDraftsAreKeptWhateverTheirAge() {
        val expiring = allKinds.filterNot { UnresolvedRetention.keepRecord(it, true, emptySet()) }.toSet()
        // only these still expire by age in the sandbox build: local copies of saved photos, watch notes, logs, bindings
        assertEquals(setOf("source-u1-ev1", "local-bind-1", "diagnostic-1", "wear-cancel-x.m4a", "cached-account", "pin-failures"), expiring)
        for (kind in listOf("draft-u1-e1", "draft-recovery-u1-e1", "consult-draft-u1-e1", "consult-chat-u1-e1", "photo-batch-pending-u1-e1"))
            assertTrue("$kind must be kept", UnresolvedRetention.keepRecord(kind, true, emptySet()))
    }

    @Test fun withTheFlagOffNothingIsKeptSoTheOriginalRulesApplyUnchanged() {
        for (kind in allKinds) assertFalse(kind, UnresolvedRetention.keepRecord(kind, false, setOf(kind)))
    }

    @Test fun anExplicitlyProtectedRecordIsKeptEvenIfItsNameIsNotAWorkKind() {
        assertTrue(UnresolvedRetention.keepRecord("something-else", true, setOf("something-else")))
        assertFalse(UnresolvedRetention.keepRecord("something-else", true, emptySet()))
    }

    @Test fun theVaultCleanupThatLocalRetentionRunsKeepsDraftsOfAnyAgeAndStillClearsTheRest() {
        // same predicate shape as Vault.expireRecovery: delete when older than the cutoff and not kept
        val ages = listOf(8L, 30L, 365L, 400L * 365)
        for (days in ages) for (kind in allKinds) {
            val deleted = days * DAY > RECOVERY && !UnresolvedRetention.keepRecord(kind, true, emptySet())
            if (UnresolvedRetention.isUnfinishedWork(kind)) assertFalse("$kind at $days days", deleted)
        }
    }

    @Test fun theReminderCountsUnfinishedReportsAndStaysQuietBeforeTwoDays() {
        val start = NOW
        assertNull(UnresolvedRetention.reminderForWork(start + DAY, start, 0, 2, 0))
        val r = UnresolvedRetention.reminderForWork(start + 3 * DAY, start, 0, 2, 0)!!
        assertEquals("2 unfinished reports are waiting", r.title)
        assertEquals("An unfinished report is waiting", UnresolvedRetention.reminderForWork(start + 3 * DAY, start, 0, 1, 0)!!.title)
        assertEquals("3 unfinished items are waiting", UnresolvedRetention.reminderForWork(start + 3 * DAY, start, 1, 2, 0)!!.title)
        assertNull("at most once a day", UnresolvedRetention.reminderForWork(start + 3 * DAY, start, 0, 2, start + 3 * DAY - DAY / 2))
        assertNotNull(UnresolvedRetention.reminderForWork(start + 4 * DAY + 1, start, 0, 2, start + 3 * DAY))
        for (text in listOf(r.title, r.text)) assertFalse("no patient details", text.contains("patient", true) || text.contains("name", true))
    }

    @Test fun withNoDraftsTheNewReminderIsExactlyTheOldOne() {
        for (rec in 0..3) for (days in listOf(0L, 1L, 2L, 3L, 10L)) for (last in listOf(0L, NOW + days * DAY - DAY / 2, NOW + days * DAY - 2 * DAY)) {
            val a = UnresolvedRetention.reminder(NOW + days * DAY, NOW, rec, last); val b = UnresolvedRetention.reminderForWork(NOW + days * DAY, NOW, rec, 0, last)
            assertEquals(a?.title, b?.title); assertEquals(a?.text, b?.text)
        }
    }
}
