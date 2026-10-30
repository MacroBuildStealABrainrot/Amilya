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

@Composable fun DocumentReviewTools(s:AppState){
    // Owner's request (2026-10-30): no red review notes under the report. Only a safety check
    // (side, drug, dose or a number that may be misheard) is shown, as one small grey line.
    s.documentAlerts.firstOrNull()?.let{message->Text(message,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
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
