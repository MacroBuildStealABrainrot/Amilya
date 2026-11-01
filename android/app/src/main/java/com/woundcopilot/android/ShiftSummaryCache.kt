package com.woundcopilot.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

/** Stored through the existing encrypted vault. No identifiers or text go into diagnostics. */
internal class ShiftSummaryCache(private val store: CodeStore, private val names: () -> List<String>) {
    // The draft- prefix also gives these copies the existing seven-day recovery-cleanup fallback.
    private fun prefix(owner: String): String { val encoded = encode(owner); return "draft-shift-summary-${encoded.length}-$encoded-" }
    private fun key(owner: String, id: String) = prefix(owner) + encode(id)

    fun remember(owner: String, visit: ShiftVisit, today: LocalDate, zone: ZoneId) {
        if (visit.startedAt.atZone(zone).toLocalDate() != today) { remove(owner, visit.id); return }
        store.write(key(owner, visit.id), serialize(visit))
    }

    fun metadata(owner: String, visit: ShiftVisit, today: LocalDate, zone: ZoneId) {
        val old = read(owner, visit.id)
        if (old != null && old.revision > visit.revision) return
        remember(owner, if (old == null) visit else visit.copy(
            reportsKnown = old.reportsKnown && old.revision == visit.revision,
            reports = old.reports,
        ), today, zone)
    }

    fun read(owner: String, id: String): ShiftVisit? = store.read(key(owner, id))?.let(::deserialize)
    fun remove(owner: String, id: String) = store.remove(key(owner, id))

    /** Inaccessible/corrupt records fail visibly; they never turn into a reassuring empty list. */
    fun today(owner: String, date: LocalDate, zone: ZoneId): List<ShiftVisit> = names().filter { it.startsWith(prefix(owner)) }.mapNotNull { name ->
        val visit = store.read(name)?.let(::deserialize) ?: error("Some saved visits could not be read. Reopen the summary before using it.")
        if (visit.startedAt.atZone(zone).toLocalDate() == date) visit else { store.remove(name); null }
    }

    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun serialize(visit: ShiftVisit): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String) { val b = value.toByteArray(Charsets.UTF_8); out.writeInt(b.size); out.write(b) }
            out.writeInt(1); text(visit.id); text(visit.label); text(visit.startedAt.toString()); out.writeInt(visit.revision); out.writeBoolean(visit.reportsKnown)
            out.writeInt(visit.reports.size)
            visit.reports.forEach { report ->
                text(report.id); out.writeInt(report.version); text(report.status); text(report.text)
                out.writeBoolean(report.notice != null); if (report.notice != null) text(report.notice)
                out.writeBoolean(report.warningRecordKnown); out.writeInt(report.alerts.size); report.alerts.forEach(::text)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun deserialize(raw: String): ShiftVisit = try {
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(raw))).use { input ->
            fun text(): String {
                val size = input.readInt(); require(size in 0..2_000_000 && size <= input.available())
                return ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8)
            }
            require(input.readInt() == 1)
            val id = text(); val label = text(); val started = Instant.parse(text()); val revision = input.readInt(); val known = input.readBoolean()
            val count = input.readInt(); require(count in 0..1000)
            val reports = List(count) {
                val documentId = text(); val version = input.readInt(); val status = text(); val body = text()
                val notice = if (input.readBoolean()) text() else null
                val warningsKnown = input.readBoolean(); val alertCount = input.readInt(); require(alertCount in 0..1000)
                ShiftReport(documentId, version, status, body, notice, List(alertCount) { text() }, warningsKnown)
            }
            require(input.available() == 0)
            ShiftVisit(id, label, started, revision, known, reports)
        }
    } catch (_: Exception) {
        // Never reuse an exception containing a stored field or patient text.
        throw IllegalStateException("Some saved visits could not be read. Reopen the summary before using it.")
    }
}
