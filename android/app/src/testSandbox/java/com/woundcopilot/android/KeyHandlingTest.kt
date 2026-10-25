package com.woundcopilot.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.Random

/** Where the recording key can end up: files, spare copies in memory, failures, and the source code (synthetic data only). */
class KeyHandlingTest {
    @get:Rule val folder = TemporaryFolder()
    private val inner = TestKeyWrapper()

    /** Keeps the arrays it was handed, so the test can look at them AFTER the code under test has finished. */
    private class Spy(private val inner: KeyWrapper, private val failWrap: Boolean = false) : KeyWrapper {
        val given = mutableListOf<ByteArray>(); val returned = mutableListOf<ByteArray>()
        override fun wrap(plain: ByteArray): ByteArray { given += plain; if (failWrap) throw IllegalStateException("keystore unavailable"); return inner.wrap(plain) }
        override fun unwrap(wrapped: ByteArray): ByteArray = inner.unwrap(wrapped).also { returned += it }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) { for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer; return true }
        return false
    }

    @Test fun theSpareCopyOfTheKeyMadeForLockingIsWipedAfterwards() {
        val spy = Spy(inner); val dir = folder.newFolder()
        val key = SealedSession.begin(dir, spy, mapOf("finalName" to "x.m4a"))
        assertEquals(1, spy.given.size); assertTrue("spare copy must be zeroed", spy.given[0].all { it == 0.toByte() })
        assertFalse("the working key itself must still be usable", key.key.all { it == 0.toByte() })
    }

    @Test fun ifTheKeyCannotBeLockedNothingIsRecordedAndNoKeyIsLeftInMemory() {
        val spy = Spy(inner, failWrap = true); val dir = folder.newFolder()
        try { SealedSession.begin(dir, spy, mapOf("finalName" to "x.m4a")); fail("must refuse to start") } catch (e: IllegalStateException) { assertEquals("keystore unavailable", e.message) }
        assertTrue(spy.given[0].all { it == 0.toByte() })
        assertNull("no settings file, so no recording could be mistaken for a protected one", SessionInfo(dir).read())
        assertTrue(SafeSessions.segments(dir).isEmpty())
    }

    @Test fun theUnlockedBytesFromRecoveryAreWipedAfterTheKeyIsBuilt() {
        val dir = folder.newFolder(); val key = SealedSession.begin(dir, inner, mapOf("finalName" to "x.m4a"))
        val spy = Spy(inner); val opened = SealedSession.open(SessionInfo(dir).read()!!, spy)
        assertNotNull(opened); assertArrayEquals(key.key, opened!!.key)
        assertEquals(1, spy.returned.size); assertTrue(spy.returned[0].all { it == 0.toByte() })
        opened.wipe(); assertTrue(opened.key.all { it == 0.toByte() } && opened.noncePrefix.all { it == 0.toByte() } && opened.sessionId.all { it == 0.toByte() })
    }

    @Test fun noFileOfTheRecordingHoldsTheKeyInTheClearOrAsText() {
        val dir = folder.newFolder(); val key = SealedSession.begin(dir, inner, mapOf("finalName" to "x.m4a"))
        val random = Random(1)
        SegmentWriter(dir, SealedCodec(key), 50).use { w -> repeat(300) { w.writeFrame(ByteArray(80 + random.nextInt(60)).also(random::nextBytes)) } }
        SealedPlaceholder.write(File(dir.parentFile, "recording-1.m4a"), SealedPlaceholder.sessionName("recording-1.m4a"))
        val hex = key.key.joinToString("") { "%02x".format(it) }
        val needles = listOf(key.key, key.serialize(), Base64.getEncoder().encode(key.key), Base64.getEncoder().encodeToString(key.serialize()).toByteArray(), hex.toByteArray(), hex.uppercase().toByteArray())
        val files = dir.listFiles()!!.toList() + File(dir.parentFile, "recording-1.m4a")
        assertTrue(files.size > 3)
        for (f in files) for ((n, needle) in needles.withIndex()) assertFalse("${f.name} contains key form #$n", contains(f.readBytes(), needle))
    }

    @Test fun theLockedKeyIsReallyLockedWithADifferentKey() {
        val dir = folder.newFolder(); val key = SealedSession.begin(dir, inner, mapOf("finalName" to "x.m4a"))
        val info = SessionInfo(dir).read()!!
        assertNull("another device/Keystore cannot open it", SealedSession.open(info, TestKeyWrapper(ByteArray(32) { 1 })))
        val damaged = info + ("keyBlob" to Base64.getEncoder().encodeToString(Base64.getDecoder().decode(info["keyBlob"]).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }))
        assertNull("a damaged blob is refused", SealedSession.open(damaged, inner))
        assertNotNull(SealedSession.open(info, inner)); key.wipe()
    }

    @Test fun noSandboxOrWatchLogLineNotificationOrMessageMentionsAKey() {
        val src = moduleFile("sandbox.src", "src/main/java/com/woundcopilot/android/SafeRecorderService.kt").parentFile
        val roots = listOfNotNull(src.takeIf { it.isDirectory }, File(src, "../../../main/java/com/woundcopilot/android").takeIf { it.isDirectory })
        assertTrue("source folders not found", roots.isNotEmpty())
        val risky = Regex("""(Log\.[a-z]\(|println\(|setContentText\(|setContentTitle\(|Toast\.makeText|\.message\s*=|setProgress\()""")
        val sensitive = Regex("""(?i)(keyblob|sessionkey|\bkek\b|\.key\b|noncePrefix|sessionId|serialize\(\))""")
        val offenders = roots.flatMap { r -> r.walkTopDown().filter { it.extension == "kt" }.toList() }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> risky.containsMatchIn(l) && sensitive.containsMatchIn(l) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }
        assertTrue("key material near a log/notification line: $offenders", offenders.isEmpty())
    }
}
