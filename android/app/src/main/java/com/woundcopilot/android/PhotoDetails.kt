package com.woundcopilot.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional wound details picked on the photo review screen. Nothing is selected by default; anything left blank is
 * unknown and is simply not sent. A measurement is sent only when the clinician typed a number above zero.
 */
data class PhotoDetails(
    val woundBed: Set<String> = emptySet(),
    val surroundingSkin: Set<String> = emptySet(),
    val drainageType: String? = null,
    val drainageAmount: String? = null,
    val lengthCm: String = "",
    val widthCm: String = "",
    val depthCm: String = "",
) {
    fun isEmpty() = woundBed.isEmpty() && surroundingSkin.isEmpty() && drainageType == null && drainageAmount == null &&
        centimetres(lengthCm) == null && centimetres(widthCm) == null && centimetres(depthCm) == null

    /** A typed value that is not a positive number up to 100 cm. Shown under the box; never sent. */
    fun invalidMeasurement(value: String) = value.isNotBlank() && centimetres(value) == null

    fun hasInvalidMeasurement() = listOf(lengthCm, widthCm, depthCm).any { invalidMeasurement(it) }

    /** Exactly what the server accepts. Unknown items are left out entirely (never empty strings or zero). */
    fun toJson(): JSONObject = JSONObject().apply {
        if (woundBed.isNotEmpty()) put("wound_bed", JSONArray(WOUND_BED.keys.filter { it in woundBed }))
        if (surroundingSkin.isNotEmpty()) put("surrounding_skin", JSONArray(SURROUNDING_SKIN.keys.filter { it in surroundingSkin }))
        drainageType?.let { put("drainage_type", it) }
        drainageAmount?.let { put("drainage_amount", it) }
        centimetres(lengthCm)?.let { put("length_cm", it) }
        centimetres(widthCm)?.let { put("width_cm", it) }
        centimetres(depthCm)?.let { put("depth_cm", it) }
    }

    /** Saved form for this visit (keeps exactly what was typed, so a failed request loses nothing). */
    fun toStored(): String = JSONObject().apply {
        put("wound_bed", JSONArray(woundBed.toList())); put("surrounding_skin", JSONArray(surroundingSkin.toList()))
        put("drainage_type", drainageType ?: JSONObject.NULL); put("drainage_amount", drainageAmount ?: JSONObject.NULL)
        put("length", lengthCm); put("width", widthCm); put("depth", depthCm)
    }.toString()

    fun selectedCount() = woundBed.size + surroundingSkin.size + listOfNotNull(drainageType, drainageAmount).size +
        listOf(lengthCm, widthCm, depthCm).count { centimetres(it) != null }

    companion object {
        val WOUND_BED = linkedMapOf("granulation" to "Granulation", "slough" to "Slough", "eschar" to "Eschar",
            "epithelial" to "Epithelial", "hypergranulation" to "Hypergranulation", "necrotic" to "Necrotic")
        val SURROUNDING_SKIN = linkedMapOf("intact" to "Intact", "erythema" to "Redness", "maceration" to "Maceration",
            "edema" to "Swelling", "induration" to "Induration", "dry_flaky" to "Dry/flaky", "callus" to "Callus", "fragile" to "Fragile")
        val DRAINAGE_TYPE = linkedMapOf("none" to "None", "serous" to "Serous", "serosanguineous" to "Serosanguineous",
            "sanguineous" to "Bloody", "purulent" to "Purulent")
        val DRAINAGE_AMOUNT = linkedMapOf("none" to "None", "scant" to "Scant", "small" to "Small", "moderate" to "Moderate", "large" to "Large")

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
            PhotoDetails(set("wound_bed", WOUND_BED), set("surrounding_skin", SURROUNDING_SKIN), one("drainage_type", DRAINAGE_TYPE),
                one("drainage_amount", DRAINAGE_AMOUNT), o.optString("length").take(6), o.optString("width").take(6), o.optString("depth").take(6))
        }.getOrDefault(PhotoDetails())
    }
}
