package com.woundcopilot.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional wound details picked on the photo review screen. Nothing is selected by default; anything left blank is
 * unknown and is simply not sent. A measurement is sent only when the clinician typed a number above zero.
 */
data class PhotoDetails(
    val woundBed: Set<String> = emptySet(),
    val woundEdges: Set<String> = emptySet(),
    val surroundingSkin: Set<String> = emptySet(),
    val drainageType: String? = null,
    val drainageAmount: String? = null,
    val lengthCm: String = "",
    val widthCm: String = "",
    val depthCm: String = "",
    val infectionSigns: Set<String> = emptySet(),
    val previousLengthCm: String = "",
    val previousWidthCm: String = "",
    val previousWeeksAgo: String = "",
) {
    fun isEmpty() = woundBed.isEmpty() && woundEdges.isEmpty() && infectionSigns.isEmpty() && surroundingSkin.isEmpty() && drainageType == null && drainageAmount == null &&
        centimetres(lengthCm) == null && centimetres(widthCm) == null && centimetres(depthCm) == null

    /** A typed value that is not a positive number up to 100 cm. Shown under the box; never sent. */
    fun invalidMeasurement(value: String) = value.isNotBlank() && centimetres(value) == null

    fun hasInvalidMeasurement() = listOf(lengthCm, widthCm, depthCm, previousLengthCm, previousWidthCm).any { invalidMeasurement(it) } ||
        invalidWeeks() || progressIncomplete()

    fun invalidWeeks() = previousWeeksAgo.isNotBlank() && weeks(previousWeeksAgo) == null

    /** Something typed under Healing progress, but not enough to compare (today's and the earlier length and width). */
    fun progressIncomplete(): Boolean {
        val started = listOf(previousLengthCm, previousWidthCm, previousWeeksAgo).any { it.isNotBlank() }
        return started && listOf(lengthCm, widthCm, previousLengthCm, previousWidthCm).any { centimetres(it) == null }
    }

    /** Percent area reduction (length x width): positive is smaller, negative is larger. Null when it cannot be worked out. */
    fun areaChangePercent(): Int? {
        val now = (centimetres(lengthCm) ?: return null) * (centimetres(widthCm) ?: return null)
        val before = (centimetres(previousLengthCm) ?: return null) * (centimetres(previousWidthCm) ?: return null)
        return Math.round((before - now) / before * 100).toInt()
    }

    /** Exactly what the server accepts. Unknown items are left out entirely (never empty strings or zero). */
    fun toJson(): JSONObject = JSONObject().apply {
        if (woundBed.isNotEmpty()) put("wound_bed", JSONArray(WOUND_BED.keys.filter { it in woundBed }))
        if (woundEdges.isNotEmpty()) put("wound_edges", JSONArray(WOUND_EDGES.keys.filter { it in woundEdges }))
        if (infectionSigns.isNotEmpty()) put("infection_signs", JSONArray(INFECTION_SIGNS.keys.filter { it in infectionSigns }))
        if (surroundingSkin.isNotEmpty()) put("surrounding_skin", JSONArray(SURROUNDING_SKIN.keys.filter { it in surroundingSkin }))
        drainageType?.let { put("drainage_type", it) }
        drainageAmount?.let { put("drainage_amount", it) }
        centimetres(lengthCm)?.let { put("length_cm", it) }
        centimetres(widthCm)?.let { put("width_cm", it) }
        centimetres(depthCm)?.let { put("depth_cm", it) }
        if (areaChangePercent() != null) {
            put("previous_length_cm", centimetres(previousLengthCm)); put("previous_width_cm", centimetres(previousWidthCm))
            weeks(previousWeeksAgo)?.let { put("previous_weeks_ago", it) }
        }
    }

    /** Saved form for this visit (keeps exactly what was typed, so a failed request loses nothing). */
    fun toStored(): String = JSONObject().apply {
        put("wound_bed", JSONArray(woundBed.toList())); put("surrounding_skin", JSONArray(surroundingSkin.toList()))
        put("drainage_type", drainageType ?: JSONObject.NULL); put("drainage_amount", drainageAmount ?: JSONObject.NULL)
        put("length", lengthCm); put("width", widthCm); put("depth", depthCm)
        put("wound_edges", JSONArray(woundEdges.toList())); put("infection_signs", JSONArray(infectionSigns.toList()))
        put("previous_length", previousLengthCm); put("previous_width", previousWidthCm); put("previous_weeks", previousWeeksAgo)
    }.toString()

    fun selectedCount() = woundBed.size + infectionSigns.size + woundEdges.size + surroundingSkin.size + listOfNotNull(drainageType, drainageAmount).size +
        listOf(lengthCm, widthCm, depthCm).count { centimetres(it) != null } + (if (areaChangePercent() != null) 1 else 0)

    companion object {
        val WOUND_BED = linkedMapOf("granulation" to "Granulation", "hypergranulation" to "Hypergranulation", "slough" to "Slough",
            "eschar" to "Eschar", "necrotic" to "Necrotic", "epithelial" to "Epithelial", "bone" to "Bone", "tendon" to "Tendon")
        val WOUND_EDGES = linkedMapOf("attached" to "Attached", "unattached" to "Unattached", "rolled" to "Rolled (epibole)",
            "undermining" to "Undermining", "tunnelling" to "Tunnelling")

        /** Attached and Unattached exclude each other; rolled, undermining and tunnelling can go with either. */
        fun toggleEdge(current: Set<String>, key: String): Set<String> = when {
            key in current -> current - key
            key == "attached" -> current - "unattached" + key
            key == "unattached" -> current - "attached" + key
            else -> current + key
        }
        /** Periwound. The saved key stays "surrounding_skin" so earlier visits still open. */
        val SURROUNDING_SKIN = linkedMapOf("intact" to "Intact", "erythema" to "Erythema", "maceration" to "Maceration",
            "edema" to "Edema", "induration" to "Induration", "dry_flaky" to "Dry/flaky", "callus" to "Callus", "fragile" to "Fragile")
        val INFECTION_SIGNS = linkedMapOf("none" to "None", "warmth" to "Warmth", "increased_pain" to "Increased pain", "odour" to "Odour",
            "spreading_erythema" to "Spreading erythema", "friable_tissue" to "Friable tissue", "increased_drainage" to "Increased drainage",
            "wound_breakdown" to "Wound breakdown", "delayed_healing" to "Delayed healing")

        /** "None" stands alone: picking it clears the signs, and picking a sign clears "None". */
        fun toggleInfection(current: Set<String>, key: String): Set<String> = when {
            key in current -> current - key
            key == "none" -> setOf("none")
            else -> current - "none" + key
        }
        val DRAINAGE_TYPE = linkedMapOf("none" to "None", "serous" to "Serous", "serosanguineous" to "Serosanguineous",
            "sanguineous" to "Sanguineous", "purulent" to "Purulent")
        val DRAINAGE_AMOUNT = linkedMapOf("none" to "None", "scant" to "Scant", "small" to "Small", "moderate" to "Moderate", "large" to "Large")

        fun weeks(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it in 1..104 }

        fun centimetres(value: String): Double? {
            val text = value.trim().replace(',', '.')
            if (!Regex("^\\d{1,3}(\\.\\d{1,2})?$").matches(text)) return null
            return text.toDouble().takeIf { it > 0 && it <= 100 }
        }

        fun fromStored(raw: String?): PhotoDetails = runCatching {
            val o = JSONObject(raw ?: return PhotoDetails())
            fun set(name: String, allowed: Map<String, String>) = (0 until (o.optJSONArray(name)?.length() ?: 0))
                .map { o.getJSONArray(name).getString(it) }.filter { it in allowed }.toSet()
            fun one(name: String, allowed: Map<String, String>) = o.optString(name).takeIf { !o.isNull(name) && it in allowed }
            PhotoDetails(woundBed = set("wound_bed", WOUND_BED), woundEdges = set("wound_edges", WOUND_EDGES),
                surroundingSkin = set("surrounding_skin", SURROUNDING_SKIN), drainageType = one("drainage_type", DRAINAGE_TYPE),
                drainageAmount = one("drainage_amount", DRAINAGE_AMOUNT), lengthCm = o.optString("length").take(6),
                widthCm = o.optString("width").take(6), depthCm = o.optString("depth").take(6),
                infectionSigns = set("infection_signs", INFECTION_SIGNS), previousLengthCm = o.optString("previous_length").take(6),
                previousWidthCm = o.optString("previous_width").take(6), previousWeeksAgo = o.optString("previous_weeks").take(3))
        }.getOrDefault(PhotoDetails())
    }
}
