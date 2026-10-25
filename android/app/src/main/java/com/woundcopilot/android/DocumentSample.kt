package com.woundcopilot.android

import org.json.JSONObject

/** Deterministic sample rendering. No AI response or extra clinical finding is simulated. */
fun sampleDocument(visit:JSONObject,format:String):JSONObject {
    val rejected=visit.items("facts").filter{it.str("confirmation_state")=="rejected"}.map{it.str("source_ref")}.toSet()
    val sources=visit.items("evidence").filter{it.str("id") !in rejected && it.str("kind") !in listOf("photo","audio_verification") && it.str("text").isNotBlank()}
    val represented=sources.map{it.str("id")}.toSet()
    val lines=(sources.flatMap{it.str("text").split(Regex("\\n+|(?<=[.!?])\\s+(?=[A-Z])"))}+visit.items("facts").filter{it.str("confirmation_state")!="rejected" && it.str("source_ref") !in represented}.map{prettyValue(it.opt("value"))})
        .map{it.trim()}.filter{it.isNotBlank() && !Regex("(?i)^(N/A|Unknown|Not provided|Not assessed|Hello|Hi|Test)[.! ]*$").matches(it)}.distinct()
    if(format=="referral_letter" && lines.none{Regex("(?i)\\brefer\\w*\\b.*\\b(for|because|due to)\\b\\s+\\S").containsMatchIn(it) && !Regex("(?i)\\b(no|not|declined|cancelled)\\b").containsMatchIn(it)})
        return obj("text" to "","status" to "referral_not_supported","ui_message" to "Referral reason not found in visit information.","case_revision" to visit.getJSONObject("encounter").optInt("revision"))
    val groups=linkedMapOf<String,MutableList<String>>()
    lines.forEach{line->
        val future=Regex("(?i)\\b(will|next visit|tomorrow|continue|plan to)\\b").containsMatchIn(line)
        val subjective=Regex("(?i)\\b(reports?|denies|recall|thinks|pain|soreness)\\b").containsMatchIn(line)
        val care=Regex("(?i)\\b(cleansed|applied|removed|changed|irrigated|performed)\\b").containsMatchIn(line)
        val heading=when(format){"soap"->if(future)"Plan" else if(subjective)"Subjective" else "Objective";"sbar"->if(future)"Recommendation" else if(care)"Assessment" else "Situation";"handoff"->if(future)"Next steps" else if(care)"Treatment" else "Current problem";else->""}
        groups.getOrPut(heading){mutableListOf()}.add(line)
    }
    val order=when(format){"soap"->listOf("Subjective","Objective","Assessment","Plan");"sbar"->listOf("Situation","Background","Assessment","Recommendation");"handoff"->listOf("Current problem","Treatment","Next steps");else->listOf("")}
    val text=order.filter{groups[it]?.isNotEmpty()==true}.joinToString("\n\n"){(if(it.isBlank())"" else "$it\n")+groups[it]!!.joinToString(" ")}
    return obj("text" to text,"status" to if(text.isBlank())"no_content" else "ready","ui_message" to if(text.isBlank())"Not enough visit information to generate this document." else "","case_revision" to visit.getJSONObject("encounter").optInt("revision"))
}
