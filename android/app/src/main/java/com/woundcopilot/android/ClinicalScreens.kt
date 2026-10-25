package com.woundcopilot.android

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.woundcopilot.android.ui.*
import org.json.JSONObject

@Composable fun EvidenceItem(s:AppState,e:JSONObject){
    var verify by remember{mutableStateOf(false)}
    DetailDisclosure(sourceLabel(e.str("kind")),localTime(e.str("created_at"))){
        e.optJSONObject("metadata")?.str("filename")?.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.labelSmall)}
        Text(e.str("text").ifBlank{"Original media stays on the capturing device."},style=MaterialTheme.typography.bodyMedium)
        e.optJSONArray("warnings")?.let{if(it.length()>0)Text(prettyValue(it),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        s.localSource(e.str("id"))?.let{file->LocalMediaPreview(file,e.str("kind"));if(e.str("kind")=="audio")TextButton(onClick={verify=true}){Text("Verify an uncertain phrase")};if(verify)AudioVerificationDialog(s,e,file){verify=false}}
    }
}

@Composable fun FindingsScreen(s:AppState){
    val facts=s.case?.items("facts")?:emptyList()
    val pending=facts.count{it.str("confirmation_state")=="unconfirmed"}
    var filter by rememberSaveable(s.encounterId){mutableStateOf(if(pending>0)"Unconfirmed" else "All")}
    var selected by remember{mutableStateOf<JSONObject?>(null)}
    var editing by remember{mutableStateOf(false)}
    val c=MaterialTheme.colorScheme
    Page{
        InstrumentTitle("Review",if(pending>0)"$pending ${if(pending==1)"finding needs" else "findings need"} confirmation" else if(facts.isEmpty())"No findings yet" else "All findings reviewed")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("Unconfirmed","Confirmed","All","Rejected").forEach{f->FilterChip(filter==f,{filter=f},label={Text(f)})}
        }
        val visible=facts.filter{filter=="All"||it.str("confirmation_state").equals(filter,true)}
        if(visible.isEmpty())EmptyState("No findings here yet","",if(filter=="Unconfirmed")"Show all findings" else null){filter="All"}
        visible.forEach{fact->key(fact.str("id")){
            GlassPanel(Modifier.fillMaxWidth()){
                Row(verticalAlignment=Alignment.Top){
                    Text(fact.str("name").replace('_',' ').replaceFirstChar{it.uppercase()},Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    when(fact.str("confirmation_state")){
                        "confirmed"->InstrumentIcon("check",Modifier.size(21.dp).semantics{contentDescription="Confirmed"},c.primary)
                        "rejected"->StatusTag("Rejected")
                        else->StatusTag("Unconfirmed","pending")
                    }
                }
                Text(prettyValue(fact.opt("value")),style=MaterialTheme.typography.bodyLarge)
                val attribution=when(fact.str("source_type")){"patient_report"->"Patient reported";"clinician_manual"->"Clinician entered";else->fact.str("source_type").replace('_',' ').replaceFirstChar{it.uppercase()}}
                DetailDisclosure("Source & history",attribution){
                    if(fact.str("source_quote").isNotBlank())Text("“${fact.str("source_quote")}”",style=MaterialTheme.typography.bodyMedium,color=c.onSurfaceVariant)
                    val source=s.case?.items("evidence")?.firstOrNull{it.str("id")==fact.str("source_ref")}
                    if(fact.str("source_quote").isBlank()&&source!=null)Text(source.str("text"),style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={s.runTask{val history=if(s.demo)listOf(fact)else(s.api.call("/facts/${fact.str("id")}/history")as org.json.JSONArray).objects();s.notice=history.joinToString("\n"){"Revision ${it.optInt("revision")}: ${it.str("confirmation_state")} · ${prettyValue(it.opt("value"))}"}}}){Text("Revision history")}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                    if(fact.str("confirmation_state")!="confirmed")WcpButton("Confirm",{s.reviewFact(fact,"confirm")},Modifier.weight(1f),enabled=!s.loading)
                    WcpButton("Edit",{selected=fact;editing=true},if(fact.str("confirmation_state")=="confirmed")Modifier.weight(1f)else Modifier,enabled=!s.loading,secondary=true)
                    if(fact.str("confirmation_state")!="rejected")TextButton(onClick={selected=fact;editing=false},enabled=!s.loading){Text("Reject")}
                }
            }
        }}
        GlassPanel(Modifier.fillMaxWidth().semantics{contentDescription="Open case review"}.clickable(role=Role.Button){s.screen="review"},accent=c.tertiary){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){InstrumentIcon("kosmo",Modifier.size(28.dp),c.primary);Column(Modifier.weight(1f)){Text("Case review",style=MaterialTheme.typography.titleMedium);Text("Gaps, conflicts & questions",style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)};InstrumentIcon("arrow")}
        }
    }
    selected?.let{fact->if(editing)FactEditorDialog(fact,onDismiss={selected=null},onSave={value->s.reviewFact(fact,"edit",value);selected=null})else AlertDialog(onDismissRequest={selected=null},title={Text("Reject this finding?")},text={Text("It remains in the history and will be excluded from confirmed documentation.")},confirmButton={TextButton(onClick={s.reviewFact(fact,"reject");selected=null}){Text("Reject finding")}},dismissButton={TextButton(onClick={selected=null}){Text("Keep finding")}})}
}

@Composable fun Field(label:String,value:String,onChange:(String)->Unit){OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth())}

@Composable fun ReviewScreen(s:AppState){
    var question by rememberSaveable(s.encounterId){mutableStateOf("")}
    var askOpen by rememberSaveable(s.encounterId){mutableStateOf(false)}
    val review=s.case?.optJSONObject("review")
    val c=MaterialTheme.colorScheme
    Page{
        InstrumentTitle("Case review")
        GlassPanel(Modifier.fillMaxWidth(),accent=c.tertiary){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){InstrumentIcon("kosmo",Modifier.size(36.dp),c.primary);Column{Text("Kosmo",style=MaterialTheme.typography.titleLarge);EffortLine(review)}}
            WcpButton(if(review==null)"Review this case" else "Update case review",{s.reviewCase(true)},Modifier.fillMaxWidth(),enabled=!s.loading)
            TextButton(onClick={s.reviewCase(false)},modifier=Modifier.align(Alignment.CenterHorizontally),enabled=!s.loading){Text("Run checks")}
        }
        if(review!=null){
            if(reviewIsStale(review,s.revision))Text("Evidence changed. Update this review.",color=c.error)
            listOf("warnings" to "Warnings","conflicts" to "Conflicting evidence","gaps" to "Missing information","considerations" to "Considerations").forEach{(key,title)->
                val arr=review.optJSONArray(key)
                if(arr!=null&&arr.length()>0){SectionLabel(title);for(i in 0 until arr.length())EvidenceRail{Text(issueText(arr.opt(i)))}}
            }
            if(listOf("warnings","conflicts","gaps","considerations").all{review.optJSONArray(it)?.length()==0})Text("No issues returned by these checks.",style=MaterialTheme.typography.bodyMedium)
        }
        if(!askOpen)WcpButton("Ask a question",{askOpen=true},Modifier.fillMaxWidth(),secondary=true)
        if(askOpen){
            OutlinedTextField(question,{question=it},label={Text("Ask about this encounter")},modifier=Modifier.fillMaxWidth(),minLines=2)
            WcpButton("Ask Kosmo",{s.ask(question)},Modifier.fillMaxWidth(),enabled=question.isNotBlank()&&!s.loading)
        }
        s.answer?.let{answer->GlassPanel(Modifier.fillMaxWidth()){EffortLine(answer);Text(answer.str("answer"),style=MaterialTheme.typography.bodyLarge);if(answer.optInt("case_revision")!=s.revision)Text("The case has changed since this response.",color=c.error)}}
    }
}
fun issueText(value:Any?):String {if(value !is JSONObject)return prettyValue(value);return listOf(value.str("title").ifBlank{value.str("question").ifBlank{value.str("message").ifBlank{value.str("code")}}},value.str("rationale").ifBlank{value.str("detail").ifBlank{value.str("description")}}).filter{it.isNotBlank()}.joinToString("\n").ifBlank{prettyValue(value)}}
@Composable fun EffortLine(result:JSONObject?){val effort=result?.str("effort_level")?.ifBlank{result.optJSONObject("routing")?.str("effort_level")?:""}?:"";Text(if(effort.isNotBlank())"Kosmo · Effort: $effort" else "Kosmo · Automatic routing",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)}

@Composable fun DocumentsScreen(s:AppState){
    val docs=s.case?.items("documents")?:emptyList()
    val c=MaterialTheme.colorScheme
    Page{
        InstrumentTitle("Documents",action={TouchIcon("add","Create a document",{s.newDocument()})})
        if(s.editor.isNotBlank()&&s.savedId==null)GlassPanel(Modifier.fillMaxWidth(),accent=c.primary){
            Row{Text("Working draft",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);StatusTag("On this device")}
            Text(s.editor.take(130),maxLines=3,style=MaterialTheme.typography.bodyMedium,color=c.onSurfaceVariant)
            WcpButton("Resume local draft",{s.screen="editor"},Modifier.fillMaxWidth())
        }
        if(docs.isEmpty()){
            GlassPanel(Modifier.fillMaxWidth()){
                InstrumentIcon("document",Modifier.size(44.dp),c.primary)
                Text("Your clinical record",style=MaterialTheme.typography.headlineSmall)
                WcpButton("Create a document",{s.newDocument()},Modifier.fillMaxWidth())
            }
        }
        docs.sortedByDescending{it.optInt("version")}.forEach{doc->
            GlassPanel(Modifier.fillMaxWidth().clickable{s.loadDocument(doc)}){
                Row(verticalAlignment=Alignment.CenterVertically){InstrumentIcon("document",Modifier.size(24.dp),c.primary);Text("Version ${doc.optInt("version")}",Modifier.weight(1f).padding(start=10.dp),style=MaterialTheme.typography.titleLarge);StatusTag(doc.str("status").replaceFirstChar{it.uppercase()})}
                Text(doc.str("template").uppercase(),style=MaterialTheme.typography.labelSmall,color=c.onSurfaceVariant)
                Text(doc.str("text").take(190),maxLines=3,style=MaterialTheme.typography.bodyMedium,color=c.onSurfaceVariant)
            }
        }
        if(s.hasRecoverableDraft)TextButton(onClick={s.restorePreviousDraft()}){Text("Recover previous working text")}
    }
}
@Composable fun EditorScreen(s:AppState){val context=LocalContext.current;var signConfirm by remember{mutableStateOf(false)};var replaceConfirm by remember{mutableStateOf<Boolean?>(null)};val doc=s.case?.items("documents")?.firstOrNull{it.str("id")==s.savedId};val signed=doc?.str("status")=="signed";Page{
    InstrumentTitle(if(signed)"Signed document" else "Write & refine")
    if(!signed){
        DocumentTypeSelector(s.editorTemplate,!s.loading){ if(s.editor.isBlank()){s.updateTemplate(it);s.persistEditor()}else s.reformatQuick(it) }
        if(s.editorTemplate=="custom"){Field("Template name",s.customName){s.customName=it;s.persistEditor()};Field("Headings and layout preferences",s.customInstructions){s.customInstructions=it;s.persistEditor()}}
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){WcpButton("Source-based draft",{if(s.editor.isBlank())s.generate(false)else replaceConfirm=false},Modifier.weight(1f),enabled=!s.loading,secondary=true);WcpButton("Generate report",{if(s.editor.isBlank())s.generate(true)else replaceConfirm=true},Modifier.weight(1f),enabled=!s.loading)}
        if(s.amendId!=null)Field("Reason for amendment",s.amendReason){s.amendReason=it;s.persistEditor()}
    }
    if(signed)Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(12.dp)){Text(s.editor,Modifier.fillMaxWidth().padding(24.dp),style=MaterialTheme.typography.bodyLarge)}else OutlinedTextField(s.editor,{s.edit(it)},modifier=Modifier.fillMaxWidth().heightIn(min=360.dp),label={Text("Clinical document")},textStyle=MaterialTheme.typography.bodyLarge)
    Text(if(signed)"Signed · Version ${doc?.optInt("version")}" else if(s.savedId!=null)"Saved to workspace · Version ${doc?.optInt("version")}" else "Draft — Clinician Review Required",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(!signed)DocumentReviewTools(s)
    if(!signed && s.editorCaseRevision!=s.revision){Text("The encounter changed after this draft. Refresh and review current findings before saving.",color=MaterialTheme.colorScheme.error);WcpButton("I reviewed the current findings",{s.useCurrentRevision()},secondary=true)}
    if(!signed){WcpButton("Save exact document",{s.persistEditor();s.saveDocument()},Modifier.fillMaxWidth(),enabled=s.editor.isNotBlank()&&!s.loading&&s.editorCaseRevision==s.revision&&(s.amendId==null||s.amendReason.isNotBlank()));if(doc!=null&&doc.str("text")==s.editor&&doc.optInt("case_revision")==s.revision)WcpButton("Review and sign",{signConfirm=true},Modifier.fillMaxWidth(),enabled=!s.loading,secondary=true)}else WcpButton("Create amendment",{s.startAmendment(doc)},Modifier.fillMaxWidth(),secondary=true)
    SectionLabel("Share")
    SendReportToPhone(s)
    Text("${if(signed)"Signed" else "Draft"} · ${doc?.let{"Version ${it.optInt("version")}"} ?: "Local working copy"}",style=MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){listOf("PDF","Text").forEach{format->WcpButton("Share $format",{s.runTask {val title=documentTypeTitle(s.editorTemplate);val uri=if(format=="PDF")ExportTools.createPdf(context,s.editor,title)else ExportTools.createText(context,s.editor,title);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=if(format=="PDF")"application/pdf" else "text/plain";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share ${format.lowercase()}"))}},Modifier.weight(1f),enabled=s.editor.isNotBlank()&&!s.loading,secondary=true)}}
};if(signConfirm&&doc!=null)AlertDialog(onDismissRequest={signConfirm=false},title={Text("Sign this exact version?")},text={Text("${s.case?.optJSONObject("patient")?.str("display_name")}\nVersion ${doc.optInt("version")}\n\nConfirm that you have reviewed the complete document. Signing preserves this wording; later changes require an amendment.")},confirmButton={TextButton(onClick={signConfirm=false;s.sign(doc)}){Text("Confirm and sign")}},dismissButton={TextButton(onClick={signConfirm=false}){Text("Keep reviewing")}})
    replaceConfirm?.let{ai->AlertDialog(onDismissRequest={replaceConfirm=null},title={Text("Replace the working text?")},text={Text("A new draft will replace the text in this editor. Saved versions remain available.")},confirmButton={TextButton(onClick={replaceConfirm=null;s.generate(ai)}){Text("Generate new draft")}},dismissButton={TextButton(onClick={replaceConfirm=null}){Text("Cancel")}})}
}




