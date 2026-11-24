package com.woundcopilot.android

import org.json.JSONObject
import java.security.MessageDigest

internal enum class NotesDraftKind { TYPED, SOURCE, REPORT }

internal data class NotesDeviceDraft(
    val owner: String,
    val visit: String,
    val key: String,
    val kind: NotesDraftKind,
    val modified: Long,
    val revision: Int,
)

internal object NotesDraftIndex {
    private val visitId = Regex("(?:local-)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Local request IDs which were already bound to remote visits for this account. */
    fun bindings(owner: String, names: List<String>, read: (String) -> String?): Map<String, String> {
        val prefix = "local-bind-$owner-"
        return names.asSequence().filter { it.startsWith(prefix) }.mapNotNull { key ->
            val local = key.removePrefix(prefix)
            if (!local.startsWith("local-") || !visitId.matches(local)) return@mapNotNull null
            val remote = read(key)?.let {
                runCatching { JSONObject(it).getJSONObject("encounter").getString("id") }.getOrNull()
            }
            if (remote == null || remote.startsWith("local-") || !visitId.matches(remote)) null else local to remote
        }.toMap()
    }

    fun list(owner: String, names: List<String>, read: (String) -> String?,
             modified: (String) -> Long, cutoff: Long): List<NotesDeviceDraft> {
        if (owner.isBlank()) return emptyList()
        val reportPrefix = "draft-$owner-"
        val typedPrefix = "evidence-form-$owner-"
        val sourcePrefix = "source-notes-$owner-"
        val aliases = bindings(owner, names, read)
        val byText = mutableMapOf<Pair<String, String>, NotesDeviceDraft>()
        val alreadySaved = mutableSetOf<Pair<String, String>>()
        val reportVisits = mutableSetOf<String>()
        val savedRevisions = mutableMapOf<String, Int>()
        for (key in names) {
            val kind: NotesDraftKind
            val visit: String
            when {
                key.startsWith(reportPrefix) -> {
                    kind = NotesDraftKind.REPORT
                    visit = key.removePrefix(reportPrefix)
                }
                key.startsWith(sourcePrefix) -> {
                    kind = NotesDraftKind.SOURCE
                    visit = key.removePrefix(sourcePrefix)
                }
                key.startsWith(typedPrefix) && key.endsWith("-typed") -> {
                    kind = NotesDraftKind.TYPED
                    visit = key.removePrefix(typedPrefix).removeSuffix("-typed")
                }
                else -> continue
            }
            if (!visitId.matches(visit)) continue
            val time = modified(key)
            if (time <= 0 || (kind != NotesDraftKind.TYPED && time < cutoff)) continue
            val json = read(key)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            if (kind == NotesDraftKind.REPORT && json.optString("template", "nursing") != "nursing") continue
            val text = if (json.isNull("text")) "" else json.optString("text")
            if (text.isBlank()) continue
            // Keep previews and response objects out of the UI list. Deduplicate only exact text from the same visit.
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).contentToString()
            val boundId = aliases[visit] ?: visit
            val identity = boundId to digest
            val revision = json.optInt("case_revision", 0)
            if (kind == NotesDraftKind.REPORT && !json.isNull("saved_id") && json.optString("saved_id").isNotBlank()) {
                alreadySaved += identity
                savedRevisions[boundId] = maxOf(savedRevisions[boundId] ?: 0, revision)
                continue
            }
            if (kind == NotesDraftKind.REPORT) reportVisits += boundId
            val row = NotesDeviceDraft(owner, visit, key, kind, time, revision)
            val previous = byText[identity]
            if (previous == null || row.kind > previous.kind ||
                (row.kind == previous.kind && row.modified > previous.modified)) byText[identity] = row
        }
        return byText.filter { (identity, draft) ->
            identity !in alreadySaved && (draft.kind != NotesDraftKind.SOURCE ||
                (identity.first !in reportVisits && (savedRevisions[identity.first] ?: -1) < draft.revision))
        }.values.sortedByDescending { it.modified }
    }
}
