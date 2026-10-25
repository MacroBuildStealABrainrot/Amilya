package com.woundcopilot.android

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.woundcopilot.android.ui.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal fun phoneRecipient(input:String):String? {
    if(!input.matches(Regex("[+0-9() .-]+")))return null
    val number=input.replace(Regex("[() .-]"),"")
    return number.takeIf{it.matches(Regex("\\+?[0-9]{7,15}"))}
}
internal fun reportMessageIntent(number:String,report:String):Intent {
    val recipient=requireNotNull(phoneRecipient(number)){"Enter one phone number, including its area code."}
    require(report.isNotBlank())
    return Intent(Intent.ACTION_SENDTO,Uri.fromParts("smsto",recipient,null)).putExtra("sms_body",report)
}
internal fun mediaShareIntent(uri:Uri,mime:String):Intent {
    require(uri.scheme=="content" && (mime.startsWith("image/") || mime.startsWith("video/")))
    return Intent(Intent.ACTION_SEND).apply {
        type=mime;putExtra(Intent.EXTRA_STREAM,uri)
        clipData=ClipData.newRawUri("Shared capture",uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
private fun Context.activity():Activity?=when(this){is Activity->this;is ContextWrapper->baseContext.activity();else->null}

internal suspend fun captureScreen(activity:Activity):Uri {
    check(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE==0){"Clinical screens are protected from capture."}
    val view=activity.window.decorView
    check(view.width>0 && view.height>0){"The screen is not ready to capture."}
    val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
    try {
        val result=suspendCoroutine<Int>{continuation->
            PixelCopy.request(activity.window,bitmap,{code->continuation.resume(code)},Handler(Looper.getMainLooper()))
        }
        check(result==PixelCopy.SUCCESS){"This screen could not be captured. Try your phone's screenshot buttons."}
        return withContext(Dispatchers.IO){
            val root=File(activity.cacheDir,"exports").apply{check(exists()||mkdirs())}
            val file=File(root,"screenshot-${UUID.randomUUID()}.png")
            try {
                file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                FileProvider.getUriForFile(activity,BuildConfig.APPLICATION_ID+".files",file)
            }catch(error:Exception){file.delete();throw error}
        }
    }finally{bitmap.recycle()}
}

@Composable fun TroubleshootingShare(s:AppState){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var open by remember{mutableStateOf(false)}
    var capturing by remember{mutableStateOf(false)}
    fun share(uri:Uri){
        try {
            val mime=context.contentResolver.getType(uri)?:throw IllegalArgumentException()
            context.startActivity(Intent.createChooser(mediaShareIntent(uri,mime),"Share screenshot or recording"))
        }catch(_:Exception){s.error="This capture could not be shared. Choose an image or video with an available sharing app."}
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(::share)}
    TouchIcon("share","Share screen or recording",{open=true},!capturing)
    if(open)AlertDialog(onDismissRequest={open=false},title={Text("Share screen or recording")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Check the capture for patient details before sharing.")
            if(s.protectCapture())Text("Clinical screens are protected. Use a sample encounter to capture a troubleshooting example.")
            WcpButton("Capture and share this screen",{
                open=false;capturing=true
                scope.launch {
                    try {
                        delay(250) // Allow the dismissed dialog to leave the window before copying pixels.
                        val activity=context.activity()?:error("Screen unavailable")
                        if(s.locked && !s.demo)return@launch
                        val uri=captureScreen(activity)
                        context.startActivity(Intent.createChooser(mediaShareIntent(uri,"image/png"),"Share screenshot"))
                    }catch(error:Exception){if(error is CancellationException)throw error;s.error="Screenshot could not be shared. Try your phone's screenshot buttons."}
                    finally{capturing=false}
                }
            },Modifier.fillMaxWidth(),enabled=!s.protectCapture())
            WcpButton("Share saved screenshot or recording",{
                open=false
                try{picker.launch(arrayOf("image/*","video/*"))}catch(_:Exception){s.error="The file picker could not be opened. Share the capture from your phone's Photos or Files app."}
            },Modifier.fillMaxWidth(),secondary=true)
            Text("To record the screen, use Screen record in your phone’s Quick Settings, then choose the saved video here.",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton({open=false}){Text("Close")}})
}

@Composable fun SendReportToPhone(s:AppState){
    val context=LocalContext.current
    var open by remember{mutableStateOf(false)}
    var number by remember{mutableStateOf("")}
    var problem by remember{mutableStateOf<String?>(null)}
    WcpButton("Send to phone number",{open=true;problem=null},Modifier.fillMaxWidth(),enabled=s.editor.isNotBlank()&&!s.loading,secondary=true)
    if(open)AlertDialog(onDismissRequest={open=false;number=""},title={Text("Send report")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(number,{number=it;problem=null},label={Text("Phone number")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),modifier=Modifier.fillMaxWidth())
            Text("Opens Messages with this report and recipient filled in. Review and tap Send there. Long reports may be split into several messages.",style=MaterialTheme.typography.bodySmall)
            problem?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(enabled=phoneRecipient(number)!=null,onClick={
        try{context.startActivity(reportMessageIntent(number,s.editor));open=false;number=""}
        catch(_:android.content.ActivityNotFoundException){problem="No text messaging app is installed. Use Share PDF or Share Text instead."}
        catch(_:Exception){problem="Messages could not be opened. Your report remains available."}
    }){Text("Open Messages")}},dismissButton={TextButton({open=false;number=""}){Text("Cancel")}})
}

@Composable fun SharePerformanceDiagnostics(s:AppState){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)}
    WcpButton("Share performance timings",{
        busy=true
        scope.launch{
            try{
                val payload=obj("app_version" to BuildConfig.VERSION_NAME,"stages" to Performance.snapshot()).toString(2)
                val uri=withContext(Dispatchers.IO){ExportTools.createText(context,payload,"Performance timings")}
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("Timings",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share performance timings"))
            }catch(e:Exception){if(e is CancellationException)throw e;s.error="Timings could not be shared. Try again."}
            finally{busy=false}
        }
    },Modifier.fillMaxWidth(),enabled=!busy,secondary=true)
}
