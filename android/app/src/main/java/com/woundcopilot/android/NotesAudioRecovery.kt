package com.woundcopilot.android

import org.json.JSONObject

/** Raw upload hashes and chunk job IDs identify completed sources. Partial audio rows never qualify. */
internal fun completedNotesAudio(
    evidence: List<JSONObject>, owner: String, encounter: String, fileHash: String, chunkJob: String?
): JSONObject? = evidence.firstOrNull { source ->
    val meta = source.optJSONObject("metadata")
    source.str("id").isNotBlank() && source.str("kind") == "audio" &&
        owner.isNotBlank() && encounter.isNotBlank() && source.str("created_by") == owner && source.str("encounter_id") == encounter &&
        meta != null && (fileHash.isNotBlank() && meta.str("media_hash") == fileHash ||
            !chunkJob.isNullOrBlank() && meta.str("job_id") == chunkJob && meta.optInt("segment_count") > 0)
}

internal fun notesRecordingComplete(source: JSONObject, evidence: List<JSONObject>, localParts: Int): Boolean {
    val meta = source.getJSONObject("metadata")
    val count = meta.optInt("visit_parts", 1)
    if (count == 1) return localParts == 1
    val visit = meta.str("visit_id")
    if (visit.isBlank() || count !in 2..8 || localParts > 1 && localParts != count) return false
    val seen = evidence.filter { row ->
        val part = row.optJSONObject("metadata")
        row.str("kind") == "audio" && row.str("created_by") == source.str("created_by") &&
            row.str("encounter_id") == source.str("encounter_id") && row.str("text").isNotBlank() &&
            part != null && part.str("visit_id") == visit && part.optInt("visit_parts") == count &&
            row.str("id").isNotBlank() && (part.str("media_hash").isNotBlank() ||
                part.str("job_id").isNotBlank() && part.optInt("segment_count") > 0)
    }.map { it.getJSONObject("metadata").optInt("visit_part") }.toSet()
    return seen == (1..count).toSet()
}

/** A recording retry must leave previously reviewed wording in the editor. */
internal fun notesShouldReopenRecordingDraft(phase: String, words: String): Boolean =
    words.isNotBlank() && phase in setOf("TRANSCRIBED", "GENERATING_REPORT", "REPORT_READY")

/** Older audio files can expire before the encrypted copy of the finished report. */
internal fun readyNotesRecordingVisit(job: JSONObject, owner: String): String? {
    if (owner.isBlank() || job.str("owner") != owner || job.str("id").isBlank() ||
        job.str("resume_from") != "REPORT_READY" || job.str("report").isBlank() ||
        job.str("phase") !in setOf("REPORT_READY", "FAILED_RETRYABLE") || job.str("template") != "nursing") return null
    val encounter = job.optJSONObject("case")?.optJSONObject("encounter") ?: return null
    return encounter.str("id").takeIf { it.isNotBlank() && job.str("file").isNotBlank() }
}

/** The vault's service-owned checkpoint and file ownership entry must agree before restoring a visit. */
internal fun activeNotesVisit(owner: String, path: String, fileOwner: String?, job: JSONObject?): JSONObject? {
    if (owner.isBlank() || path.isBlank() || job == null || job.str("owner") != owner ||
        job.str("file") != path || job.str("phase") != "RECORDING" || job.str("template") != "nursing") return null
    val snapshot = job.optJSONObject("case") ?: return null
    val encounter = snapshot.optJSONObject("encounter") ?: return null
    val visit = encounter.str("id")
    if (visit.isBlank() || fileOwner != "$owner:$visit" || encounter.str("status") != "open" ||
        snapshot.items("documents").any { it.str("status") == "signed" }) return null
    return snapshot
}
