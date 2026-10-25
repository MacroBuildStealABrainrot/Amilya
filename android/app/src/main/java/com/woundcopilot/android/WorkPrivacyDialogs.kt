package com.woundcopilot.android
import androidx.compose.runtime.Composable
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column

@Composable fun WorkPrivacyDialogs(s:AppState){
    if(s.closeWorkPrompt)AlertDialog(onDismissRequest={s.closeWorkPrompt=false},title={Text("Finish this report?")},
        text={Text("Save keeps this work in your account. Finish without saving removes this temporary report and its sources from active app storage. Keep working if you still need to copy or export it.")},
        confirmButton={Column{
            TextButton({s.saveWorkAndClose()},enabled=!s.loading){Text("Save and close")}
            TextButton({s.closeTemporaryWork()},enabled=!s.loading){Text("Finish without saving")}
        }},dismissButton={TextButton({s.closeWorkPrompt=false}){Text("Keep working")}})
    s.deleteWorkId?.let{id->AlertDialog(onDismissRequest={s.deleteWorkId=null},title={Text(if(id=="all-saved")"Delete all saved work?"else "Delete this work?")},
        text={Text("This removes reports, assessments and sources from active app storage and clears local copies. Outstanding transfer links are revoked. Previously exported copies and existing backups cannot be recalled. This cannot be undone.")},
        confirmButton={TextButton({s.deleteWork(id)},enabled=!s.loading){Text("Permanently delete")}},
        dismissButton={TextButton({s.deleteWorkId=null}){Text("Cancel")}})}
}
