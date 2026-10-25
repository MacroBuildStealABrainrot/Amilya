package com.woundcopilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RetentionRulesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun root() = folder.newFolder("recordings")
    private fun session(root: File, name: String, status: String?, finalName: String = "recording-1.m4a"): File {
        val dir = File(root, "safe-$name").apply { mkdirs() }
        File(dir, "seg-00001.aac").writeBytes(ByteArray(100))
        if (status != null) SessionInfo(dir).write(mapOf("status" to status, "finalName" to finalName))
        return dir
    }
    private fun keep(file: File, root: File, prot: Set<String> = emptySet(), pend: Set<String> = emptySet()) = RetentionRules.mustKeep(file, root, prot, pend)

    @Test fun unfinishedSessionPiecesAreNeverDeleted() {
        val r = root()
        for (status in listOf("recording", "paused", "stopped", "empty")) {
            val d = session(r, status, status)
            assertTrue(status, keep(File(d, "seg-00001.aac"), r)); assertTrue(keep(File(d, "session.properties"), r))
        }
    }

    @Test fun aSessionWithMissingOrBrokenSettingsIsKept() {
        val r = root()
        val none = session(r, "none", null); assertTrue(keep(File(none, "seg-00001.aac"), r))
        val broken = session(r, "broken", null); File(broken, "session.properties").writeBytes(byteArrayOf(0x5c, 0x75, 0x00, 0x7a))
        assertTrue(keep(File(broken, "seg-00001.aac"), r))
    }

    @Test fun aFinishedSessionIsKeptWhileItsRecordingOrRecoveryNoteExists() {
        val r = root()
        val d = session(r, "done", "finished")
        File(r, "recording-1.m4a").writeBytes(ByteArray(10))
        assertTrue(keep(File(d, "seg-00001.aac"), r))
        File(r, "recording-1.m4a").delete(); File(r, "recording-1.m4a.json").writeText("{}")
        assertTrue(keep(File(d, "seg-00001.aac"), r))
        File(r, "recording-1.m4a.json").delete()
        assertFalse("only a finished session whose recording and note are both gone may age out", keep(File(d, "seg-00001.aac"), r))
    }

    @Test fun aSettingsNameThatTriesToEscapeIsTreatedAsStillNeeded() {
        val r = root(); val d = session(r, "evil", "finished", finalName = "../x.m4a")
        assertTrue(keep(File(d, "seg-00001.aac"), r))
    }

    @Test fun theRecoveryNoteOfAWaitingRecordingIsKept() {
        val r = root()
        val media = File(r, "recording-2.m4a").apply { writeBytes(ByteArray(10)) }; val note = File(r, "recording-2.m4a.json").apply { writeText("{}") }
        assertTrue(keep(note, r, pend = setOf(media.canonicalPath)))
        assertTrue(keep(note, r, prot = setOf(media.canonicalPath)))
        assertFalse("a note for a recording nobody is waiting for follows the normal age rule", keep(note, r))
    }

    @Test fun ordinaryRecordingsAndOtherFoldersAreLeftToTheExistingAgeRules() {
        val r = root()
        assertFalse(keep(File(r, "recording-3.m4a").apply { writeBytes(ByteArray(1)) }, r))
        val other = File(r, "notes").apply { mkdirs() }
        assertFalse(keep(File(other, "a.txt").apply { writeText("x") }, r))
        // a lookalike folder outside the recordings folder is not ours
        val outside = File(folder.newFolder("elsewhere"), "safe-x").apply { mkdirs() }
        assertFalse(keep(File(outside, "seg-00001.aac").apply { writeBytes(ByteArray(1)) }, r))
    }

    @Test fun nestedFilesInsideASessionAreAlsoKept() {
        val r = root(); val d = session(r, "nested", "recording")
        val deep = File(d, "extra/more").apply { mkdirs() }
        assertTrue(keep(File(deep, "x.bin").apply { writeBytes(ByteArray(1)) }, r))
    }
}
