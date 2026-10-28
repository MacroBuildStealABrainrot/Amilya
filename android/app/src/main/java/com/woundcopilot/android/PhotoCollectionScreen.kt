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
    Page{
        InstrumentTitle("Photo assessment")
        if(s.pendingPhotoFiles().isNotEmpty())TextButton({s.uploadPhotos(s.pendingPhotoFiles())},enabled=!s.loading){Text("Resume photo uploads")}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text("${photos.size} photos")
            TextButton({s.retakePhoto()},enabled=!s.loading){Text("Add photos")}
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            photos.forEachIndexed{index,p->
                val file=s.localSource(p.str("id"))
                val bitmap=remember(file?.path){file?.let{BitmapFactory.decodeFile(it.path,BitmapFactory.Options().apply{inSampleSize=8})}?.asImageBitmap()}
                Column(Modifier.width(110.dp).clickable{selected=p.str("id")}){
                    if(bitmap!=null)Image(bitmap,"Photo ${index+1}",Modifier.size(100.dp))else Text("Photo ${index+1}")
                    Text(p.str("label").ifBlank{"Photo ${index+1}"},style=MaterialTheme.typography.labelMedium)
                }
            }
        }
        val current=photos.firstOrNull{it.str("id")==selected}?:photos.lastOrNull()
        current?.let{p->
            val id=p.str("id");s.localSource(id)?.let{LocalMediaPreview(it,"photo")}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                TextButton({editing=p;label=p.str("label");group=p.str("group")},enabled=!s.loading){Text("Label / group")}
                TextButton({val index=photos.indexOf(p);if(index>0){val copy=photos.toMutableList();java.util.Collections.swap(copy,index,index-1);s.arrangePhotos(copy)}},enabled=!s.loading&&photos.indexOf(p)>0){Text("Move earlier")}
                TextButton({deleting=id},enabled=!s.loading){Text("Remove")}
            }
        }
        photos.groupBy{it.str("group")}.entries.forEachIndexed{index,(groupName,views)->
            val ids=views.map{it.str("id")}.toSet()
            Text(if(groupName.startsWith("Unassigned"))"Photo ${photos.indexOf(views.first())+1} · wound not assigned"else groupName,style=MaterialTheme.typography.titleMedium)
            val findings=s.case?.items("facts").orEmpty().filter{it.str("source_ref") in ids && it.str("confirmation_state")!="rejected"}
                .distinctBy{it.str("name")+it.opt("value")?.toString().orEmpty()}
            val sourcePhotos=s.case?.items("evidence").orEmpty().filter{it.str("id") in ids}
            sourcePhotos.mapNotNull{it.optJSONObject("metadata")?.optJSONObject("photo_quality")}.filter{it.optBoolean("retake_required")}.forEach{Text(it.str("message"))}
            if(findings.isEmpty())Text("No assessable findings. Review or retake these photos.")
            // One chart-ready paragraph: wound bed, edges, periwound, then exudate.
            val order=listOf("wound_tissue","wound_edges","periwound","exudate")
            if(findings.isNotEmpty())Text(findings.sortedBy{order.indexOf(it.str("name")).let{i->if(i<0)order.size else i}}
                .map{prettyValue(it.opt("value")).trim().let{t->if(t.isEmpty()||t.last() in ".!?")t else "$t."}}.filter{it.isNotBlank()}.joinToString(" "))
        }
        TextButton({s.saveWorkAndClose()},enabled=!s.loading&&photos.isNotEmpty()){Text("Save assessment")}
        WcpButton("Done",{s.finishPhotoAssessment()},Modifier.fillMaxWidth(),enabled=!s.loading)
        TextButton({s.chooseQuickMode("document");if(s.editor.isBlank())s.quickReport()else s.screen="editor"},enabled=!s.loading){Text("Add to Report")}
        TextButton({s.quickConsultant()},enabled=!s.loading){Text("Ask Wound Consultant")}
    }
    editing?.let{p->AlertDialog(onDismissRequest={editing=null},title={Text("Photo details")},text={Column{
        OutlinedTextField(label,{label=it.take(120)},label={Text("View label")},placeholder={Text("Overview, close-up, measurement view")})
        OutlinedTextField(group,{group=it.take(80)},label={Text("Wound group")},placeholder={Text("Right heel, Wound 1")})
        photos.map{it.str("group")}.distinct().filterNot{it.startsWith("Unassigned")}.forEach{existing->TextButton({group=existing}){Text("Another view of $existing")}}
        TextButton({group="Wound ${photos.map{it.str("group")}.distinct().size+1}"}){Text("New wound")}
    }},confirmButton={TextButton({s.arrangePhotos(photos.map{if(it.str("id")==p.str("id"))obj("id" to p.str("id"),"label" to label.trim(),"group" to group.trim())else it});editing=null},enabled=group.isNotBlank()){Text("Apply")}},dismissButton={TextButton({editing=null}){Text("Cancel")}})}
    deleting?.let{id->AlertDialog(onDismissRequest={deleting=null},title={Text("Remove photo?")},text={Text("The local copy and its stored assessment will be removed. Previously written reports need separate review.")},confirmButton={TextButton({s.removePhoto(id);deleting=null}){Text("Remove")}},dismissButton={TextButton({deleting=null}){Text("Cancel")}})}
}
