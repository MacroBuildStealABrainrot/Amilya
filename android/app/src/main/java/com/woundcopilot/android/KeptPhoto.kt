package com.woundcopilot.android
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.OffsetDateTime

/** When the phone no longer has its own copy, shows the server's short-lived copy; findings outlive it. */
fun keptPhotoExpiry(evidence:JSONObject?):OffsetDateTime?{
    val metadata=evidence?.optJSONObject("metadata")?:return null
    if(!metadata.optBoolean("raw_media_retained"))return null
    return runCatching{OffsetDateTime.parse(metadata.str("photo_expires_at"))}.getOrNull()
}

@Composable fun KeptPhoto(s:AppState,evidence:JSONObject?,description:String,thumbnail:Boolean){
    if(s.demo||evidence==null||evidence.optJSONObject("metadata")?.optBoolean("raw_media_retained")!=true)return
    val id=evidence.str("id")
    val expires=keptPhotoExpiry(evidence)
    if(expires==null||!expires.isAfter(OffsetDateTime.now())){
        if(!thumbnail)Text("This photo was deleted after its viewing period. Its findings remain.",style=MaterialTheme.typography.bodySmall)
        return
    }
    var failed by remember(id){mutableStateOf(false)}
    var expanded by remember(id){mutableStateOf(false)}
    val bitmap by produceState<ImageBitmap?>(null,id){
        value=withContext(Dispatchers.IO){runCatching{s.api.photo(id)?.let{bytes->
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=if(thumbnail)4 else 1})?.asImageBitmap()}}.getOrNull()}
        failed=value==null
    }
    when{
        bitmap!=null&&thumbnail->Image(bitmap!!,description,Modifier.size(100.dp))
        bitmap!=null->{
            Image(bitmap!!,description,Modifier.fillMaxWidth().heightIn(max=320.dp).clickable{expanded=true})
            Text("Viewable until ${expires.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}",style=MaterialTheme.typography.bodySmall)
            if(expanded)androidx.compose.ui.window.Dialog(onDismissRequest={expanded=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.systemBarsPadding()){
                    TextButton({expanded=false}){Text("Close")}
                    Image(bitmap!!,description,Modifier.fillMaxSize())
                }}
            }
        }
        failed&&!thumbnail->Text("The photo could not be loaded. Its findings remain.",style=MaterialTheme.typography.bodySmall)
    }
}
