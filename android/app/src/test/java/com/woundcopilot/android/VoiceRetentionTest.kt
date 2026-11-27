package com.woundcopilot.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceRetentionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun legacyCheckpointAndExactAudioSurviveExpiryUntilResultOrDiscard() {
        val root = folder.newFolder("recordings")
        val source = File(root, "recording-123-source.m4a").apply { writeText("sound") }
        val note = File(root, "${source.name}.json").apply { writeText("{}") }
        val other = File(root, "recording-123-other.m4a").apply { writeText("other") }
        val segments = File(root, "segments").apply { mkdir() }
        val part = File(segments, "segment-abc.m4a").apply { writeText("part") }
        val unrelated = File(segments, "segment-unused.m4a").apply { writeText("unused") }
        val key = "audio-batch-lite-user-id-${source.name}"
        val otherKey = "audio-batch-lite-other-${source.name}"
        val batch = JSONObject().put("job_id", "original-request")
            .put("files", JSONArray(listOf(part.path, other.path, part.path)))
        val values = mutableMapOf("recording-owner-${source.name}" to "user-id:lite",
            "recording-owner-${other.name}" to "other:lite", key to batch.toString(), otherKey to batch.toString())
        val now = System.currentTimeMillis() + LocalRetention.RECOVERY_AGE * 2
        fun expired(file: File, kept: VoiceRetention) =
            RetentionRules.shouldDelete(file, root, now, LocalRetention.MEDIA_AGE, LocalRetention.RECOVERY_AGE, kept.paths, emptySet())

        val legacy = pendingVoiceRetention(root, values::get)
        assertEquals(setOf(key), legacy.requests)
        assertTrue(legacy.keepsRequest("$key.bak"))
        assertFalse(legacy.keepsRequest(otherKey))
        for (file in listOf(source, note, part)) assertFalse(file.name, expired(file, legacy))
        for (file in listOf(other, unrelated)) assertTrue(file.name, expired(file, legacy))

        values[key] = batch.put("result", JSONObject()).toString()
        assertEquals(VoiceRetention(), pendingVoiceRetention(root, values::get))
        // A recorded server receipt remains unresolved until the app consumes the checkpoint.
        val receipt = "lite-pending-user-id-${source.name}"
        values[receipt] = "waiting"
        assertTrue(pendingVoiceRetention(root, values::get).keepsRequest(key))
        source.delete()
        values.remove(key)
        values.remove(receipt)
        assertEquals(VoiceRetention(), pendingVoiceRetention(root, values::get))
    }
}
