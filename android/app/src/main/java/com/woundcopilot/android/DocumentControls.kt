package com.woundcopilot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.ui.*

fun documentTypeTitle(value:String)=quickFormats[if(value=="referral")"physician_update" else value]?:value.replaceFirstChar{it.uppercase()}

@Composable fun DocumentTypeSelector(value:String,enabled:Boolean,onChange:(String)->Unit){
    var expanded by rememberSaveable{mutableStateOf(false)}
    Box{
        TextButton(onClick={expanded=true},enabled=enabled){Text("Document Type · ${documentTypeTitle(value)}");InstrumentIcon("down",Modifier.size(18.dp))}
        DropdownMenu(expanded,onDismissRequest={expanded=false}){
            quickFormats.forEach{(key,label)->DropdownMenuItem(text={Text(label)},onClick={expanded=false;onChange(key)})}
        }
    }
}

/** Kinds of report warning that can change patient care. These are never hidden and always come first. */
internal enum class SafetyAlert{MEDICATION,DOSE,SIDE,MEASUREMENT}

private val doseWords=Regex("\\b(dose|dosage|dosing|mg|mcg|µg|ug|units?|iu|ml|mL|tablets?|tabs?|puffs?|drops?|bid|tid|qid|qd|prn|q\\d+h|daily|twice|three times|frequency|strength|concentration)\\b",RegexOption.IGNORE_CASE)
private val medicationWords=Regex("\\b(medication|medicine|medications|drug|drugs|prescription|prescribed|antibiotic|antibiotics|insulin|anticoagulant|warfarin|heparin|opioid|analgesic|ointment|cream|product|dressing product|allerg\\w*)\\b",RegexOption.IGNORE_CASE)
private val sideWords=Regex("\\b(left|right|side|laterality|bilateral|lt|rt|l\\.|r\\.|medial|lateral|which (leg|foot|arm|hand|wound|toe|heel))\\b",RegexOption.IGNORE_CASE)
private val measurementWords=Regex("\\b(measure\\w*|size|length|width|depth|cm|mm|centimet\\w*|millimet\\w*|area|diameter|tunnel\\w*|undermin\\w*|temperature|temp|blood pressure|bp|pulse|heart rate|oxygen|o2|saturation|glucose|sugar|weight|kg|lb|number|value|reading|\\d+(\\.\\d+)?)\\b",RegexOption.IGNORE_CASE)

/** Which care-changing kinds a warning is about (from its field name and its wording). Empty = a general note. */
internal fun safetyKinds(field:String,message:String):Set<SafetyAlert>{
    val text="$field $message"
    return buildSet{
        if(medicationWords.containsMatchIn(text))add(SafetyAlert.MEDICATION)
        if(doseWords.containsMatchIn(text))add(SafetyAlert.DOSE)
        if(sideWords.containsMatchIn(text))add(SafetyAlert.SIDE)
        if(measurementWords.containsMatchIn(text))add(SafetyAlert.MEASUREMENT)
    }
}

/** Every warning from the server, safety warnings first (in their original order), then the rest. Nothing is dropped except blank lines and exact repeats. */
internal fun orderedReportAlerts(alerts:List<Pair<String,String>>):Pair<List<String>,Int>{
    val clean=alerts.map{(f,m)->f.trim() to m.trim()}.filter{it.second.isNotEmpty()}.distinctBy{it.second}
    val (safety,other)=clean.partition{safetyKinds(it.first,it.second).isNotEmpty()}
    return (safety+other).map{it.second} to safety.size
}

@Composable fun DocumentReviewTools(s:AppState){
    // Safety first (owner's request 2026-10-31): EVERY warning is shown. Warnings about a medication, dose, side or
    // measurement come first, in a clear colour, and can never be pushed out by another warning. The rest follow.
    if(s.documentAlerts.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        val safety=s.documentAlertSafetyCount.coerceIn(0,s.documentAlerts.size)
        if(safety>0)Text("Check before saving or sending:",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.titleSmall)
        s.documentAlerts.forEachIndexed{index,message->
            if(index<safety)Text("• $message",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
            else Text("• $message",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        TextButton(onClick={s.improveReport()},enabled=!s.loading && s.editor.isNotBlank() && s.editorCaseRevision==s.revision,modifier=Modifier.weight(1f)){Text("Improve Report")}
        TextButton(onClick={s.improveAssessment()},enabled=!s.loading,modifier=Modifier.weight(1f)){Text("Improve Assessment")}
    }
}

@Composable fun AssessmentReviewDialog(s:AppState){
    val result=s.assessmentReview?:return
    if(s.assessmentEncounter!=s.encounterId)return
    AlertDialog(onDismissRequest={s.assessmentReview=null},title={Text("Improve Assessment")},text={
        Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){
            if(s.assessmentRevision!=s.revision)Text("Visit information changed. These questions describe the earlier visit information.",color=MaterialTheme.colorScheme.error)
            Text(result.str("answer"))
            result.optJSONArray("considerations")?.let{items->(0 until items.length()).forEach{index->Text("${index+1}. ${items.optString(index)}")}}
            Text("Your report and sources have not changed.",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={s.assessmentReview=null}){Text("Done")}})
}
