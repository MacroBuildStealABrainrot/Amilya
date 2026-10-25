package com.woundcopilot.android

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A fully local, fictional encounter. It contains no provider output or account data. */
object DemoData {
    private const val patientId = "demo-patient"
    private const val woundId = "demo-wound"
    private const val encounterId = "demo-encounter"
    private const val authorId = "demo-clinician"
    private const val createdAt = "2026-09-05T13:30:00+00:00"
    private const val measuredAt = "2026-09-05T13:32:00+00:00"
    private const val narrativeAt = "2026-09-05T13:34:00+00:00"
    private const val savedAt = "2026-09-05T13:36:00+00:00"

    private const val measurementText = "Clinician measured the left heel wound at 2.4 x 1.6 cm. Depth was not measured."
    private const val narrativeText = "Patient reports discomfort of 3/10 during the dressing change."
    private const val documentText = "SYNTHETIC SAMPLE - NOT A CLINICAL RECORD\n\n" +
        "Left heel\n" +
        "Clinician assessed - Wound measured 2.4 x 1.6 cm; depth not recorded.\n\n" +
        "Only the confirmed measurement is included in this sample draft."

    private fun patient() = obj(
        "id" to patientId,
        "display_name" to "Jordan Maple",
        "external_ref" to "SAMPLE ONLY",
        "created_at" to createdAt
    )

    private fun wound() = obj(
        "id" to woundId,
        "patient_id" to patientId,
        "label" to "Left heel",
        "location" to "heel",
        "laterality" to "left",
        "wound_type" to null
    )

    private fun encounter() = obj(
        "id" to encounterId,
        "patient_id" to patientId,
        "wound_id" to woundId,
        "title" to "Sample encounter",
        "status" to "open",
        "revision" to 4,
        "draft_data" to obj(),
        "created_at" to createdAt,
        "updated_at" to savedAt
    )

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun measurement() = obj(
        "id" to "demo-fact-measurement",
        "revision_id" to "demo-fact-measurement-r1",
        "revision" to 1,
        "name" to "wound_measurement",
        "value" to obj("length_cm" to 2.4, "width_cm" to 1.6),
        "confirmation_state" to "confirmed",
        "source_type" to "clinician_manual",
        "source_ref" to "demo-evidence-measurement",
        "source_quote" to measurementText,
        "source_locator" to obj(),
        "wound_id" to woundId,
        "confirmed_by" to authorId,
        "confirmed_at" to measuredAt,
        "authored_by" to authorId,
        "action" to "attest",
        "created_at" to measuredAt
    )

    private fun reportedPain() = obj(
        "id" to "demo-fact-pain",
        "revision_id" to "demo-fact-pain-r1",
        "revision" to 1,
        "name" to "pain",
        "value" to obj("text" to narrativeText, "score_0_10" to 3.0),
        "confirmation_state" to "unconfirmed",
        "source_type" to "patient_report",
        "source_ref" to "demo-evidence-narrative",
        "source_quote" to narrativeText,
        "source_locator" to obj(),
        "wound_id" to woundId,
        "confirmed_by" to null,
        "confirmed_at" to null,
        "authored_by" to authorId,
        "action" to "extract",
        "created_at" to narrativeAt
    )

    private fun evidence(id: String, kind: String, text: String, at: String, attested: Boolean) = obj(
        "id" to id,
        "encounter_id" to encounterId,
        "wound_id" to woundId,
        "kind" to kind,
        "text" to text,
        "metadata" to obj(
            "clinician_attested" to attested,
            "transcript_consent" to null,
            "source_text_hash" to hash(text)
        ),
        "warnings" to JSONArray(),
        "created_by" to authorId,
        "created_at" to at
    )

    private fun document() = obj(
        "id" to "demo-document-1",
        "text" to documentText,
        "template" to "nursing",
        "custom_template" to null,
        "content_hash" to hash(documentText),
        "status" to "draft",
        "version" to 1,
        "case_revision" to 4,
        "saved_case_revision" to 4,
        "signed_at" to null,
        "signed_by" to null,
        "amends_id" to null,
        "reason" to null,
        "fact_snapshot" to JSONArray().put(measurement()),
        "created_at" to savedAt
    )

    /** Return fresh JSON so edits to a demo session cannot mutate the next session. */
    fun caseData(): JSONObject = obj(
        "encounter" to encounter(),
        "patient" to patient(),
        "wound" to wound(),
        "evidence" to JSONArray()
            .put(evidence("demo-evidence-measurement", "measurement", measurementText, measuredAt, true))
            .put(evidence("demo-evidence-narrative", "typed", narrativeText, narrativeAt, false)),
        "facts" to JSONArray().put(measurement()).put(reportedPain()),
        "documents" to JSONArray().put(document()),
        "review" to null,
        "conflicts" to JSONArray()
    )

    fun encounters(): List<JSONObject> = listOf(encounter())

    fun patients(): List<JSONObject> = listOf(patient())
}
