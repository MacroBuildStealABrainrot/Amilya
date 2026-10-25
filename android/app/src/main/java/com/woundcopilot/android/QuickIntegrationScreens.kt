package com.woundcopilot.android

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun HomeScreen(s:AppState){
    LaunchedEffect(s.user?.str("id")){s.refreshRecordingJobs()}
    StartScreen(
        recovery={
            s.recordingJobs.take(3).forEachIndexed{index,job->
                TextButton({s.resumeRecording(job)},enabled=!s.loading){Text(if(job.str("resume_from")=="REPORT_READY")"Review recovered report ${index+1}"else "${index+1} recording${if(index==0) "" else "s"} not finished")}
            }
            if(s.recordingJobs.size>3)DetailDisclosure("More saved recordings","${s.recordingJobs.size-3} more"){
                s.recordingJobs.drop(3).forEachIndexed{index,job->TextButton({s.resumeRecording(job)},enabled=!s.loading){Text("Resume saved recording ${index+4}")}}
            }
        },
        savedWork=s.encounters.filter{it.str("storage_state") in listOf("temporary","saved")}
            .sortedByDescending { it.str("updated_at") }.take(1).map { e ->
            val quick=e.optJSONObject("draft_data")?.str("quick_mode").orEmpty().isNotBlank()
            val name=s.patients.firstOrNull{it.str("id")==e.str("patient_id")}?.str("display_name").orEmpty()
            SimpleSavedWork(e.str("id"),if(quick || name.isBlank())e.str("title")else name,
                if(quick)localTime(e.str("updated_at"))else e.str("title"))
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
        s.recordingJobs.filter{it.optJSONObject("case")?.optJSONObject("encounter")?.str("id")==s.encounterId && it.str("resume_from")!="REPORT_READY"}.forEach{job->
            Text("Recording saved on this device.")
            TextButton({s.resumeRecording(job)},enabled=!s.loading){Text("Retry recording")}
        }
        TextButton({s.doneQuick()},enabled=!s.loading){Text("Done")}
    }
}

@Composable fun QuickPhotoScreen(s:AppState){PhotoCollectionScreen(s)}

@Composable fun QuickConsultantScreen(s:AppState){
    val sources=s.case?.items("evidence").orEmpty()
    ConsultantChatScreen(s.encounterId,s.consultantMessages.map{ConsultantMessage(it.str("id"),it.str("content"),it.str("role")=="user",it.str("effort"))},
        initialDraft=s.consultantDraft,draftResetKey=s.consultantReset,busy=s.loading,
        attachments=ConsultantAttachments(photoCount=sources.count{it.str("kind")=="photo"},fileCount=sources.count{it.str("kind")=="document"},recordingCount=sources.count{it.str("kind")=="audio"},otherCount=sources.count{it.str("kind") !in listOf("photo","document","audio")}),
        onDraftChange={s.updateConsultantDraft(it)},onSend={s.sendConsultant(it)},onAddPhoto={s.screen="photo"},onAddFiles={s.screen="upload"},onRecord={s.screen="record"},onDone={s.doneQuick()},
        onVoiceMessage={s.recordConsultantMessage()},onOpenReport=if(s.editor.isNotBlank()){{s.chooseQuickMode("document");s.screen="editor"}}else null)
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
