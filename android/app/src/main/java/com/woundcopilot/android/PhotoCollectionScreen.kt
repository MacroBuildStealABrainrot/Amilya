package com.woundcopilot.android
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.ui.*
import org.json.JSONObject

@Composable fun PhotoCollectionScreen(s:AppState){
    val photos=s.photoLayout()
    var selected by remember(s.encounterId){mutableStateOf("")}
    var editing by remember{mutableStateOf<JSONObject?>(null)}
    var deleting by remember{mutableStateOf<String?>(null)}
    var label by remember{mutableStateOf("")}
    var group by remember{mutableStateOf("")}
    LaunchedEffect(s.encounterId){s.loadPhotoNote()}
    val twoPart=s.photoChartNote!=null && s.photoResultVisit==s.encounterId
    Page{
        InstrumentTitle("Photo assessment")
        if(s.pendingPhotoFiles().isNotEmpty())TextButton({s.uploadPhotos(s.pendingPhotoFiles())},enabled=!s.loading){Text("Resume photo uploads")}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text(if(photos.size==1)"1 photo" else "${photos.size} photos")
            TextButton({s.retakePhoto()},enabled=!s.loading){Text("Add photos")}
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            photos.forEachIndexed{index,p->
                val file=s.localSource(p.str("id"))
                val bitmap=remember(file?.path){file?.let{BitmapFactory.decodeFile(it.path,BitmapFactory.Options().apply{inSampleSize=8})}?.asImageBitmap()}
                Column(Modifier.width(110.dp).clickable{selected=p.str("id")}){
                    if(bitmap!=null)Image(bitmap,"Photo ${index+1}",Modifier.size(100.dp))else Text("Photo ${index+1}")
                }
            }
        }
        val current=photos.firstOrNull{it.str("id")==selected}?:photos.lastOrNull()
        current?.let{p->
            val id=p.str("id");s.localSource(id)?.let{LocalMediaPreview(it,"photo")}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                val saveCtx=androidx.compose.ui.platform.LocalContext.current
                TextButton({s.localSource(id)?.takeIf{it.isFile}?.let{f->val r=PhoneSave.save(saveCtx,f,"image/jpeg");s.notice=if(r.startsWith("Saved"))"Saved to your phone in Pictures > Amilya."else r}},enabled=!s.loading){Text("Save")}
                TextButton({deleting=id},enabled=!s.loading){Text("Remove")}
            }
        }
        val sendParts=mutableListOf<String>()
        photos.groupBy{it.str("group")}.entries.forEachIndexed{index,(groupName,views)->
            val ids=views.map{it.str("id")}.toSet()
            if(!groupName.startsWith("Unassigned"))Text(groupName,style=MaterialTheme.typography.titleMedium)
            val findings=s.case?.items("facts").orEmpty().filter{it.str("source_ref") in ids && it.str("confirmation_state")!="rejected"}
                .distinctBy{it.str("name")+it.opt("value")?.toString().orEmpty()}
            val sourcePhotos=s.case?.items("evidence").orEmpty().filter{it.str("id") in ids}
            sourcePhotos.mapNotNull{it.optJSONObject("metadata")?.optJSONObject("photo_quality")}.filter{it.optBoolean("retake_required")}.forEach{Text(it.str("message"))}
            if(findings.isEmpty())Text("Nothing could be assessed in this photo. Retake it with the area uncovered if safe.")
            // One chart-ready paragraph: wound bed, edges, periwound, then exudate.
            val order=listOf("wound_tissue","wound_edges","periwound","exudate")
            val advisory=setOf("photo_interpretation","photo_next_steps")
            val interpretation=findings.filter{it.str("name")=="photo_interpretation"}.map{prettyValue(it.opt("value")).trim()}.filter{it.isNotBlank()}
            val nextSteps=findings.filter{it.str("name")=="photo_next_steps"}.map{prettyValue(it.opt("value")).trim()}.filter{it.isNotBlank()}
            val paragraph=findings.filter{it.str("name") !in advisory}.sortedBy{order.indexOf(it.str("name")).let{i->if(i<0)order.size else i}}
                .map{prettyValue(it.opt("value")).trim().let{t->if(t.isEmpty()||t.last() in ".!?")t else "$t."}}.filter{it.isNotBlank()}.joinToString(" ")
            if(paragraph.isNotBlank()){
                OutlinedCard(Modifier.fillMaxWidth()){Text(paragraph,Modifier.padding(14.dp))}
                sendParts+=if(groupName.startsWith("Unassigned"))paragraph else "$groupName: $paragraph"
            }
            if(twoPart)return@forEachIndexed
            // Owner request 2026-11-06: the AI review sits in its own box under the photo report, as short direct points.
            val points=reviewPoints(interpretation)+reviewPoints(nextSteps)
            if(points.isNotEmpty())OutlinedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text("Clinical review",style=MaterialTheme.typography.titleSmall)
                    points.forEach{Text("• $it")}
                }
            }
        }
        // The clinician's own words about the wound: what a photo cannot show (side, how long, warmth, pain, odour, history).
        if(photos.isNotEmpty()){
            Text("Your notes about this wound",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(s.photoNote,{s.updatePhotoNote(it)},Modifier.fillMaxWidth(),enabled=!s.loading,minLines=2,
                placeholder={Text("For example: left heel, 3 weeks, warm, odour, diabetic, using Inadine")})
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                TextButton({s.recordPhotoNote()},enabled=!s.loading){Text("Add by voice")}
                if(s.photoNote.isNotBlank())TextButton({s.updatePhotoNote("")},enabled=!s.loading){Text("Clear")}
            }
            WcpButton(if(twoPart)"Update note and review" else "Create charting note and consultant review",{s.photoNoteAndReview()},Modifier.fillMaxWidth(),enabled=!s.loading)
        }
        if(twoPart){
            val chart=s.photoChartNote.orEmpty()
            Text("Part 1 · Charting note",style=MaterialTheme.typography.titleMedium)
            s.photoNoteAlerts.forEach{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            Text(chart)
            SendOptions(chart,"Photo charting note",s.loading,onComputer=null,onMessage={s.notice=it})
            TextButton({s.openPhotoNoteAsReport()},enabled=!s.loading){Text("Open as report to edit or save")}
            s.photoConsultReview?.let{review->
                Text("Part 2 · Consultant review (AI suggestions, not charted)",style=MaterialTheme.typography.titleMedium)
                Text("For your clinical judgement only. It is not part of the note and is never sent with it.",style=MaterialTheme.typography.bodySmall)
                // Lists from the consultant arrive as " - item"; show each point on its own line.
                Text(review.replace(Regex("\\s+-\\s+(?=[A-Z0-9])"),"\n• ").replace(Regex("^-\\s+"),"• ").replace(Regex("(Assessment|Plan|Healing|Escalate if|Missing information|Check|Red flags):\\s*•"),"$1:\n•"))
                val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current
                TextButton({clipboard.setText(androidx.compose.ui.text.AnnotatedString(review));s.notice="Consultant review copied."}){Text("Copy review")}
            }
        } else SendOptions(sendParts.joinToString("\n\n"),"Photo assessment",s.loading,onComputer=null,onMessage={s.notice=it})
        val shareContext=androidx.compose.ui.platform.LocalContext.current
        val photoFiles=photos.mapNotNull{s.localSource(it.str("id"))}.filter{it.isFile}
        if(photoFiles.isNotEmpty())WcpButton(if(photoFiles.size==1)"Share photo" else "Share photos",{try{
            val dir=java.io.File(shareContext.cacheDir,"exports").apply{mkdirs()}
            val uris=ArrayList<android.net.Uri>(photoFiles.mapIndexed{i,f->val copy=java.io.File(dir,"photo-"+System.currentTimeMillis()+"-"+i+"."+f.extension.ifBlank{"jpg"});f.copyTo(copy,true);androidx.core.content.FileProvider.getUriForFile(shareContext,shareContext.packageName+".files",copy)})
            val intent=android.content.Intent(if(uris.size==1)android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_SEND_MULTIPLE).apply{type="image/*";addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            if(uris.size==1)intent.putExtra(android.content.Intent.EXTRA_STREAM,uris[0]) else intent.putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM,uris)
            shareContext.startActivity(android.content.Intent.createChooser(intent,"Share photos"))
        }catch(_:Exception){s.notice="The photos could not be shared."}},Modifier.fillMaxWidth(),enabled=!s.loading,secondary=true)
        TextButton({s.saveWorkAndClose()},enabled=!s.loading&&photos.isNotEmpty()){Text("Save assessment")}
        WcpButton("Done",{s.finishPhotoAssessment()},Modifier.fillMaxWidth(),enabled=!s.loading)
        TextButton({s.chooseQuickMode("document");if(s.editor.isBlank())s.quickReport()else s.screen="editor"},enabled=!s.loading){Text("Add to Report")}
        TextButton({s.quickConsultant()},enabled=!s.loading){Text("Ask Consultant")}
    }
    editing?.let{p->AlertDialog(onDismissRequest={editing=null},title={Text("Photo details")},text={Column{
        OutlinedTextField(label,{label=it.take(120)},label={Text("View label")},placeholder={Text("Overview, close-up, measurement view")})
        OutlinedTextField(group,{group=it.take(80)},label={Text("Wound group")},placeholder={Text("Right heel, Wound 1")})
        photos.map{it.str("group")}.distinct().filterNot{it.startsWith("Unassigned")}.forEach{existing->TextButton({group=existing}){Text("Another view of $existing")}}
        TextButton({group="Wound ${photos.map{it.str("group")}.distinct().size+1}"}){Text("New wound")}
    }},confirmButton={TextButton({s.arrangePhotos(photos.map{if(it.str("id")==p.str("id"))obj("id" to p.str("id"),"label" to label.trim(),"group" to group.trim())else it});editing=null},enabled=group.isNotBlank()){Text("Apply")}},dismissButton={TextButton({editing=null}){Text("Cancel")}})}
    deleting?.let{id->AlertDialog(onDismissRequest={deleting=null},title={Text("Remove photo?")},text={Text("The local copy and its stored assessment will be removed. Previously written reports need separate review.")},confirmButton={TextButton({s.removePhoto(id);deleting=null}){Text("Remove")}},dismissButton={TextButton({deleting=null}){Text("Cancel")}})}
}

/** Short points from the AI review: one point per "- " line, or per sentence for an older long answer. Labels are dropped. */
internal fun reviewPoints(texts:List<String>):List<String> = texts.flatMap{text->
    val clean=text.replace(Regex("^\\s*(For clinician review|Possible|Check|Suggested assessment)\\s*:\\s*",RegexOption.IGNORE_CASE),"")
    val lines=clean.split(Regex("\\n|(?:^|\\s)-\\s+(?=\\S)")).map{it.trim().trimStart('-','•').trim()}.filter{it.isNotBlank()}
    if(lines.size>1)lines else clean.split(Regex("(?<=[.;])\\s+")).map{it.trim()}.filter{it.isNotBlank()}
}.map{it.removeSuffix(".").removeSuffix(";").trim()}.filter{it.isNotBlank()}
