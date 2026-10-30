package com.woundcopilot.android

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun HomeScreen(s:AppState){
    LaunchedEffect(s.user?.str("id")){s.refreshRecordingJobs()}
    FirstModeChooser(s)
    StartScreen(
        header={ModeBar(s)},
        recovery={
            val pending = s.recordingJobs.filter { it.str("resume_from") != "REPORT_READY" }
            val reports = s.recordingJobs.filter { it.str("resume_from") == "REPORT_READY" }
            if (pending.isNotEmpty()) {
                TextButton({ s.resumeRecording(pending.first()) }, enabled = !s.loading) {
                    Text("${pending.size} recording${if (pending.size == 1) "" else "s"} not finished")
                }
                if (pending.size > 1) DetailDisclosure("Other unfinished recordings", "${pending.size - 1} more") {
                    pending.drop(1).forEachIndexed { index, job ->
                        TextButton({ s.resumeRecording(job) }, enabled = !s.loading) { Text("Open unfinished recording ${index + 2}") }
                    }
                }
            }
            if (reports.isNotEmpty()) {
                TextButton({ s.resumeRecording(reports.first()) }, enabled = !s.loading) { Text("Review recovered report") }
                if (reports.size > 1) DetailDisclosure("Other recovered reports", "${reports.size - 1} more") {
                    reports.drop(1).forEachIndexed { index, job ->
                        TextButton({ s.resumeRecording(job) }, enabled = !s.loading) { Text("Review recovered report ${index + 2}") }
                    }
                }
            }
        },
        savedWork=s.encounters.filter{it.str("storage_state") in listOf("temporary","saved")}
            .sortedByDescending { it.str("updated_at") }.take(1).map { e ->
            val mode=e.optJSONObject("draft_data")?.str("quick_mode").orEmpty().lowercase()
            val type=when(mode){
                "photo","photo_assessment","image"->"Photo assessment"
                "consultant","wound_consultant","ask_consultant"->"Wound consultant"
                "document","report","nursing"->"Nursing visit"
                else->"Visit"
            }
            val rawTitle=e.str("title").trim()
            val title=if(rawTitle.isNotBlank() && !rawTitle.equals("New report",true) && !rawTitle.equals("New encounter",true))
                rawTitle else type
            SimpleSavedWork(e.str("id"),title,localTime(e.str("updated_at")))
        },busy=s.loading,onDocument={s.startQuick("document")},onPhoto={s.startQuick("photo")},
        onConsultant={s.startQuick("consultant")},onOpenWork={s.openEncounter(it)},
        onAccount={s.screen="account"},onSendAll={s.sendReports(true)})
}

@Composable fun DocumentCaptureScreen(s:AppState){
    LaunchedEffect(Unit){withFrameNanos{};Performance.end("document_open")}
    val sources=s.case?.items("evidence").orEmpty()
    Page{
        InstrumentTitle("Document")
        DocumentTypeSelector(s.editorTemplate, !s.loading) { if(s.editor.isBlank()){s.updateTemplate(it);s.persistEditor()}else s.reformatQuick(it) }
        WcpButton("Record",{s.autoStartRecording=true;s.screen="record"},Modifier.fillMaxWidth(),enabled=!s.loading)
        WcpButton("Write instead",{s.screen="evidence:typed"},Modifier.fillMaxWidth(),enabled=!s.loading,secondary=true)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
            TextButton({s.screen="photo"},enabled=!s.loading){InstrumentIcon("photo",Modifier.size(18.dp));Text("  Add photo")}
            TextButton({s.screen="upload"},enabled=!s.loading){InstrumentIcon("add",Modifier.size(18.dp));Text("  Add Files")}
        }
        if(s.editor.isNotBlank())WcpButton("Return to report",{s.screen="editor"},Modifier.fillMaxWidth(),enabled=!s.loading)
        else if(sources.isNotEmpty())WcpButton("Generate report",{s.quickReport()},Modifier.fillMaxWidth(),enabled=!s.loading)
        if(sources.isNotEmpty())DetailDisclosure("Sources","${sources.size} added"){
            sources.forEach{EvidenceItem(s,it)}
            if(s.editor.isBlank())TextButton({s.quickReport(false)},enabled=!s.loading){Text("Create draft without AI")}
        }
        // Saved-recording status, Retry recording and Done removed at the owner's request;
        // unfinished recordings remain in Saved work > Recovery & older work.
    }
}

@Composable fun QuickPhotoScreen(s:AppState){PhotoCollectionScreen(s)}

@Composable fun QuickConsultantScreen(s:AppState){
    val sources=s.case?.items("evidence").orEmpty()
    ConsultantChatScreen(s.encounterId,s.consultantMessages.map{ConsultantMessage(it.str("id"),it.str("content"),it.str("role")=="user",it.str("effort"))},
        initialDraft=s.consultantDraft,draftResetKey=s.consultantReset,busy=s.loading,
        attachments=ConsultantAttachments(photoCount=sources.count{it.str("kind")=="photo"},fileCount=sources.count{it.str("kind")=="document"},recordingCount=sources.count{it.str("kind")=="audio"},otherCount=sources.count{it.str("kind") !in listOf("photo","document","audio")}),
        onDraftChange={s.updateConsultantDraft(it)},onSend={s.sendConsultant(it)},onAddPhoto={s.screen="photo"},onAddFiles={s.screen="upload"},onRecord={s.screen="record"},onDone={s.finishConsultant()},
        onVoiceMessage={s.recordConsultantMessage()},onOpenReport=if(s.editor.isNotBlank()){{s.chooseQuickMode("document");s.screen="editor"}}else null)
    if(s.consultantReportPrompt){
        var format by rememberSaveable{mutableStateOf(s.editorTemplate)}
        AlertDialog(onDismissRequest={s.consultantReportPrompt=false},
            title={Text("Create a report from this consultation?")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("Your questions, the consultant's answers and your attached photos and files will be used. You can review and edit the draft before saving.")
                DocumentTypeSelector(format,true){format=it}
                Text("Closing without a report discards this conversation.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }},
            confirmButton={TextButton({s.reportFromConsultant(format)}){Text("Create report")}},
            dismissButton={Row{TextButton({s.consultantReportPrompt=false}){Text("Keep talking")};TextButton({s.closeConsultantWithoutReport()}){Text("Close")}}})
    }
}

@Composable fun ActiveTransferDialog(s:AppState){
    val transfer=s.transfer?:return
    val context=LocalContext.current
    val expires=localTime(transfer.str("expires_at"))
    TransferDialog(transfer.str("url"),transfer.str("code"),if(expires.isBlank())"Expires after 15 minutes. Opens once."else "Expires at $expires. Opens once.",
        onDismiss={s.transfer=null},onShare={
            try{context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,"WoundCoPilot report transfer\n${transfer.str("url")}\nOne-time code: ${transfer.str("code")}\nExpires in 15 minutes. Open once in your browser.")},"Open on your work phone"))}
            catch(_:Exception){s.error="No sharing app is available. Copy the link and code from this dialog."}
        })
}
