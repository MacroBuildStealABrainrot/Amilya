package com.woundcopilot.android
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column

@Composable fun WorkPrivacyDialogs(s:AppState){
    if(s.closeWorkPrompt)AlertDialog(onDismissRequest={s.closeWorkPrompt=false},title={Text("Finish this report?")},
        text={Text("Save keeps this work in your account. Finish without saving removes this temporary report and its sources from active app storage. Keep working if you still need to copy or export it.")},
        // All three choices stacked in one column so they never overlap on narrow phones.
        confirmButton={Column(horizontalAlignment=androidx.compose.ui.Alignment.End){
            TextButton({s.saveWorkAndClose()},enabled=!s.loading){Text("Save and close")}
            TextButton({s.closeTemporaryWork()},enabled=!s.loading){Text("Finish without saving")}
            TextButton({s.closeWorkPrompt=false}){Text("Keep working")}
        }})
    s.renameWorkId?.let{id->
        var text by remember(id){mutableStateOf(s.visitName(id))}
        AlertDialog(onDismissRequest={s.renameWorkId=null},title={Text("Rename this visit")},
            text={Column{Text("Use a name that helps you find it. It stays on this phone only. Avoid patient names or identifiers.")
                OutlinedTextField(value=text,onValueChange={text=it.take(60)},singleLine=true,label={Text("Name")})}},
            confirmButton={TextButton({s.renameVisit(id,text)}){Text("Save")}},
            dismissButton={TextButton({s.renameWorkId=null}){Text("Cancel")}})}
    s.deleteWorkId?.let{id->AlertDialog(onDismissRequest={s.deleteWorkId=null},title={Text(if(id=="all-saved")"Delete all saved work?"else "Delete this work?")},
        text={Text("This removes reports, assessments and sources from active app storage and clears local copies. Outstanding transfer links are revoked. Previously exported copies and existing backups cannot be recalled. This cannot be undone.")},
        confirmButton={TextButton({s.deleteWork(id)},enabled=!s.loading){Text("Permanently delete")}},
        dismissButton={TextButton({s.deleteWorkId=null}){Text("Cancel")}})}
}
