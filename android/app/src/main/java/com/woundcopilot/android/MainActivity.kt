package com.woundcopilot.android

import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import com.woundcopilot.android.ui.*
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity:ComponentActivity(){
    private val state:AppState by viewModels()
    private val captureLaunchers=mutableMapOf<String,androidx.activity.result.ActivityResultLauncher<*>>()
    @Suppress("UNCHECKED_CAST")
    private fun <I,O> captureLauncher(id:String,contract:androidx.activity.result.contract.ActivityResultContract<I,O>,consume:(O)->Unit):androidx.activity.result.ActivityResultLauncher<I>{
        captureLaunchers[id]?.let{return it as androidx.activity.result.ActivityResultLauncher<I>}
        // Unique persisted keys prevent an abandoned result from reaching a newer capture.
        return activityResultRegistry.register("capture-$id",contract){result->consume(result);captureLaunchers.remove(id)?.unregister()}.also{captureLaunchers[id]=it}
    }
    private fun registerCapture(type:ExternalKind,id:String,launch:Boolean=false,file:java.io.File?=null){
        when(type){
            ExternalKind.PHOTO_PICKER->{val l=captureLauncher(id,androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(20)){state.externalCapture.receive(type,it,operationId=id)};if(launch)l.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))}
            ExternalKind.CAMERA->{val l=captureLauncher(id,androidx.activity.result.contract.ActivityResultContracts.TakePicture()){state.externalCapture.receive(type,emptyList(),it,id)};if(launch)l.launch(androidx.core.content.FileProvider.getUriForFile(this,BuildConfig.APPLICATION_ID+".files",file!!))}
            ExternalKind.DOCUMENT_PICKER,ExternalKind.AUDIO_PICKER->{val l=captureLauncher(id,androidx.activity.result.contract.ActivityResultContracts.OpenDocument()){state.externalCapture.receive(type,listOfNotNull(it),operationId=id)};if(launch)l.launch(if(type==ExternalKind.AUDIO_PICKER)arrayOf("audio/*","video/webm")else arrayOf("application/pdf","text/plain","text/csv"))}
            else->Unit
        }
    }
    fun launchCapture(type:ExternalKind,media:String,purpose:String){
        try{
            val file=if(type==ExternalKind.CAMERA)java.io.File(java.io.File(filesDir,"captures").apply{check(exists()||mkdirs())},"photo-${java.util.UUID.randomUUID()}.jpg")else null
            if(!state.externalCapture.begin(type,media,purpose,file))return
            registerCapture(type,state.externalCapture.operationId!!,true,file)
        }catch(_:Exception){state.externalCapture.clear();state.error="The system picker or camera could not open. Please try again."}
    }
    private fun deviceLocked()=getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked || !getSystemService(android.os.PowerManager::class.java).isInteractive
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        state.wearBridge
        state.externalCapture.operationId?.let{id->state.externalCapture.kind?.let{registerCapture(it,id)}}
        if(!BuildConfig.DEBUG && !BuildConfig.ALLOW_SCREEN_CAPTURE)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        LocalRetention.schedule(this)
        java.util.concurrent.Executors.newSingleThreadExecutor().let{executor->executor.execute{try{LocalRetention.clean(applicationContext)}finally{executor.shutdown()}}}
        RecordingService.restorePending(this)
        setContent{val captureProtected=state.protectCapture();SideEffect{
            if(captureProtected)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            WindowCompat.getInsetsController(window,window.decorView).apply{isAppearanceLightStatusBars=!state.dark;isAppearanceLightNavigationBars=!state.dark}
        };WoundTheme(state.dark){WoundApp(state)}}
    }
    override fun onStop(){if(state.case!=null)state.persistEditor();state.externalCapture.background(isChangingConfigurations,deviceLocked());super.onStop()}
    override fun onPause(){state.wearBridge.foreground(false);if(!BuildConfig.DEBUG&&!BuildConfig.ALLOW_SCREEN_CAPTURE&&!state.demo)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);super.onPause()}
    override fun onResume(){super.onResume();state.wearBridge.foreground(true);state.externalCapture.foreground(deviceLocked());if(state.protectCapture())window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    override fun onDestroy(){captureLaunchers.values.forEach{it.unregister()};captureLaunchers.clear();super.onDestroy()}
}

private val workspacePages=listOf("encounter","findings","documents")
private fun phase(page:String)=when(page){"findings","review"->"findings";"documents","editor"->"documents";else->"encounter"}
private fun isCasePage(page:String)=page in listOf("encounter","sources","record","photo","upload","findings","review","documents","editor","history","document_capture","photo_assessment","consultant","voice_correction")||page.startsWith("evidence:")

@Composable fun WoundApp(s:AppState){
    if(s.locked && !s.demo){AppLockScreen(s);return}
    val page=s.screen
    val rec by RecordingService.state.collectAsState()
    val inCase=s.case!=null&&isCasePage(page)
    val showSidebar=s.quickMode==null&&LocalWindowInfo.current.containerSize.width>=with(LocalDensity.current){840.dp.toPx()}&&inCase&&page !in listOf("record","photo","upload")&&!page.startsWith("evidence:")
    fun navigate(route:String){if(page=="editor")s.persistEditor();s.screen=route}
    fun back(){
        if(s.loading)return
        if(page=="record" && rec.recording){s.notice="Stop the recording before leaving this screen.";return}
        s.dismissError()
        if(s.voiceMessage){s.cancelVoiceMessage();return}
        if(s.voiceCorrection){s.cancelVoiceCorrection();return}
        if(s.quickMode!=null&&inCase){
            when(page){
                "document_capture","photo_assessment","consultant","editor"->s.doneQuick()
                "documents","history","sources"->navigate(if(s.editor.isNotBlank())"editor" else "document_capture")
                else->if(s.quickMode=="photo")s.doneQuick()else navigate(if(s.quickMode=="consultant")"consultant" else "document_capture")
            };return
        }
        navigate(when(page){"login","signup"->"welcome";"account","saved","new","encounter"->"home";"admin"->"account";"editor"->"documents";"review"->"findings";else->"encounter"})
    }
    BackHandler(page!="welcome"&&page!="home"){back()}
    val c=MaterialTheme.colorScheme
    Scaffold(containerColor=c.background,topBar={
        if(page!="welcome")Column(Modifier.statusBarsPadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                if(page !in listOf("home","account"))TouchIcon("back","Back",::back)
                if(inCase&&s.quickMode!=null)Box(Modifier.weight(1f).padding(start=6.dp)){
                    Wordmark(compact=true)
                }else if(inCase)Column(Modifier.weight(1f).padding(start=6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Text(s.case?.optJSONObject("patient")?.str("display_name").orEmpty(),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(s.case?.optJSONObject("wound")?.str("label")?.ifBlank{null}?:s.case?.optJSONObject("encounter")?.str("title").orEmpty(),style=MaterialTheme.typography.labelSmall,color=c.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                }else Box(Modifier.weight(1f).padding(start=8.dp)){Wordmark(compact=true)}
                TroubleshootingShare(s)
                if(inCase)TouchIcon("record","Voice commands",{s.voiceCommandsOpen=true},!s.loading)
                if(s.demo)Text("SAMPLE",Modifier.padding(horizontal=10.dp),style=MaterialTheme.typography.labelSmall,color=c.onSurfaceVariant)
                else if(page !in listOf("login","signup","admin"))TouchIcon("refresh","Refresh workspace",{s.refresh()},!s.loading)
            }
            if(rec.recording&&page!="record")Surface(onClick={navigate("record")},color=c.errorContainer){Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){InstrumentIcon("record",Modifier.size(18.dp),c.onErrorContainer);Text("${if(rec.paused)"Paused" else "Recording"} · ${rec.elapsedSeconds/60}:${(rec.elapsedSeconds%60).toString().padStart(2,'0')}",Modifier.weight(1f).padding(start=10.dp),style=MaterialTheme.typography.labelLarge);Text("Open",style=MaterialTheme.typography.labelLarge)}}
            if(s.loading){LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp));Text(s.taskLabel,Modifier.padding(horizontal=20.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium,color=c.onSurfaceVariant)}
        }
    },bottomBar={
        if(page in listOf("home","saved","account"))InstrumentDock(listOf(DockDestination("home","Home","cases"),DockDestination("saved","Saved work","document"),DockDestination("account","Account","account")),page,::navigate)
        else if(s.quickMode==null&&inCase&&!showSidebar&&page in listOf("encounter","findings","documents","sources","review","history"))InstrumentDock(listOf(DockDestination("encounter","Capture","capture"),DockDestination("findings","Review","review",s.case?.items("facts")?.count{it.str("confirmation_state")=="unconfirmed"}?:0),DockDestination("documents","Documents","document")),phase(page),::navigate)
    }){padding->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()){
            s.error?.let{RequestFeedback(it,retryAtMillis=s.retryAtMillis,onRetry=if(s.retryAvailable){{s.retryFailed()}}else null,onDismiss={s.dismissError()})}
            s.notice?.let{Surface(color=c.surfaceVariant){Row(Modifier.fillMaxWidth().padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Text(it,Modifier.weight(1f).padding(vertical=10.dp),style=MaterialTheme.typography.bodySmall);TouchIcon("close","Dismiss notification",{s.notice=null})}}}
            if(s.legalRequired&&!s.demo&&s.user!=null)LegalGate(s)
            else Box(Modifier.fillMaxWidth().weight(1f)){
                Row(Modifier.fillMaxSize()){
                    if(showSidebar)TabletContext(s)
                    AnimatedContent(targetState=if(s.quickMode==null&&page in workspacePages)"workspace" else page,modifier=Modifier.weight(1f),transitionSpec={fadeIn(tween(180))+slideInVertically(tween(220)){it/35} togetherWith fadeOut(tween(100))},label="Workspace transition"){route->
                        when{
                            route=="workspace"->EncounterPager(s)
                            route=="welcome"->Welcome(s)
                            route=="reconnect"->Page{Text("Your account is still saved on this phone.");Text("Connect to the internet to reopen your workspace.");Button({s.resumeStoredSession()},enabled=!s.loading){Text("Reconnect")}}
                            route=="login"||route=="signup"->AuthScreen(s,route=="signup")
                            route=="home"->HomeScreen(s)
                            route=="saved"->SavedWorkScreen(s)
                            route=="document_capture"->DocumentCaptureScreen(s)
                            route=="photo_assessment"->QuickPhotoScreen(s)
                            route=="consultant"->QuickConsultantScreen(s)
                            route=="voice_correction"->VoiceCorrectionScreen(s)
                            route=="documents"->DocumentsScreen(s)
                            route=="account"->AccountScreen(s)
                            route=="admin"->AdminScreen(s)
                            route=="developer_diagnostics"->DeveloperDiagnosticsScreen(s)
                            route=="new"->CreateEncounterScreen(s)
                            route=="sources"->SourcesScreen(s)
                            route=="record"->CaptureScreen(s,true)
                            route=="photo"||route=="upload"->CaptureScreen(s,false,route=="photo")
                            route.startsWith("evidence:")->EvidenceForm(s,route.substringAfter(':'))
                            route=="review"->ReviewScreen(s)
                            route=="editor"->if(s.quickMode!=null)QuickReportScreen(s)else EditorScreen(s)
                            route=="history"->HistoryScreen(s)
                        }
                    }
                }
            }
        }
    }
    VoiceCommandsDialog(s)
    WorkPrivacyDialogs(s)
    ActiveTransferDialog(s)
    AssessmentReviewDialog(s)
}

@Composable private fun EncounterPager(s:AppState){
    val pager=rememberPagerState(initialPage=workspacePages.indexOf(s.screen).coerceAtLeast(0),pageCount={3})
    var requested by remember{mutableStateOf<Int?>(null)}
    LaunchedEffect(s.screen){val destination=workspacePages.indexOf(s.screen);if(destination>=0&&destination!=pager.currentPage){requested=destination;pager.animateScrollToPage(destination);requested=null}}
    LaunchedEffect(pager){snapshotFlow{pager.isScrollInProgress to pager.settledPage}.distinctUntilChanged().collect{(moving,position)->if(!moving&&requested==null&&s.screen in workspacePages&&workspacePages.indexOf(s.screen)!=position)s.screen=workspacePages[position]}}
    HorizontalPager(pager,Modifier.fillMaxSize().testTag("encounter-pager"),userScrollEnabled=!s.loading,key={workspacePages[it]}){index->when(index){0->EncounterScreen(s);1->FindingsScreen(s);2->DocumentsScreen(s)}}
}

@Composable fun Page(content:@Composable ColumnScope.()->Unit){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){Column(Modifier.widthIn(max=780.dp).fillMaxHeight().fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=22.dp).padding(top=14.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)}}

@Composable fun TabletContext(s:AppState){
    val c=MaterialTheme.colorScheme
    Column(Modifier.width(248.dp).fillMaxHeight().background(c.surfaceContainerLow).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        Wordmark(compact=true);Spacer(Modifier.height(8.dp))
        listOf(Triple("encounter","Capture","capture"),Triple("findings","Review","review"),Triple("documents","Documents","document")).forEach{(route,title,icon)->Surface(onClick={s.persistEditor();s.screen=route},shape=RoundedCornerShape(15.dp),color=if(phase(s.screen)==route)c.primaryContainer else Color.Transparent){Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){InstrumentIcon(icon);Text(title,style=MaterialTheme.typography.titleSmall)}}}
        if(s.screen!="encounter"){
            HorizontalDivider(color=c.outlineVariant);Text("Source material",style=MaterialTheme.typography.titleSmall)
            s.case?.items("evidence")?.forEach{e->DetailDisclosure(sourceLabel(e.str("kind"))){Text(e.str("text"),style=MaterialTheme.typography.bodySmall)}}
            TextButton(onClick={s.history()}){InstrumentIcon("history",Modifier.size(18.dp));Text("  History")}
        }
    }
}

@Composable fun Welcome(s:AppState){
    val c=MaterialTheme.colorScheme
    Page{
        Row(Modifier.padding(top=8.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f)){Wordmark(compact=true)};Text("ANDROID",style=MaterialTheme.typography.labelSmall,color=c.onSurfaceVariant)}
        Spacer(Modifier.height(10.dp))
        Text("Care,\nin focus.",style=MaterialTheme.typography.displayLarge.copy(fontFamily=FontFamily.Serif,fontWeight=FontWeight.Normal,fontSize=60.sp,lineHeight=62.sp,letterSpacing=(-2).sp))
        Text("Documentation and wound support, when you need it.",style=MaterialTheme.typography.bodyMedium,color=c.onSurfaceVariant)
        GlassPanel(Modifier.fillMaxWidth()){
            listOf(Triple("record","Document","Speak or write your findings."),Triple("photo","Photo Assessment","See what is visible."),Triple("kosmo","Wound Consultant","Talk through a question.")).forEach{(icon,title,detail)->
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
                    InstrumentIcon(icon,Modifier.size(26.dp),c.primary)
                    Column{Text(title,style=MaterialTheme.typography.titleMedium);Text(detail,style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)}
                }
            }
        }
        WcpButton("Sign in",{s.screen="login"},Modifier.fillMaxWidth())
        if(s.config.optBoolean("allow_signup",true))TextButton(onClick={s.screen="signup"},modifier=Modifier.align(Alignment.CenterHorizontally)){Text("Create account")}
    }
}

@Composable fun SavedWorkScreen(s:AppState){
    var recovery by rememberSaveable{mutableStateOf(false)};val c=MaterialTheme.colorScheme
    Page{
        InstrumentTitle("Saved work",if(s.demo)"Sample workspace" else if(!s.config.optBoolean("clinical_data_enabled"))"Synthetic data workspace" else null)
        Row{TextButton({recovery=false}){Text("Saved")};TextButton({recovery=true}){Text("Recovery & older work")}}
        val filtered=s.encounters.filter{if(recovery)!s.isSavedWork(it)else s.isSavedWork(it)}
        if(filtered.isEmpty()) {
            Text("Nothing saved yet. Start a report from Home.")
            WcpButton("Go to Home",{s.screen="home"},Modifier.fillMaxWidth())
        }
        if(!recovery)TextButton(onClick={s.deleteWorkId="all-saved"},enabled=!s.loading && filtered.isNotEmpty()){Text("Delete all saved work")}
        filtered.forEach{e->
            val quick=e.optJSONObject("draft_data")?.str("quick_mode").orEmpty().isNotBlank()
            val name=if(quick)e.str("title")else s.patients.firstOrNull{it.str("id")==e.str("patient_id")}?.str("display_name")?:"Visit"
            GlassPanel(Modifier.fillMaxWidth().clickable(role=Role.Button){s.openEncounter(e.str("id"))}){
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                    Box(Modifier.size(45.dp).background(c.primaryContainer,CircleShape),contentAlignment=Alignment.Center){Text(name.split(' ').mapNotNull{it.firstOrNull()}.take(2).joinToString(""),style=MaterialTheme.typography.titleMedium,color=c.onPrimaryContainer)}
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(name,style=MaterialTheme.typography.titleLarge);Text(e.str("title"),style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)}
                    InstrumentIcon("arrow",tint=c.primary)
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){TextButton({s.deleteWorkId=e.str("id")}){Text("Delete")};Spacer(Modifier.weight(1f));Text(localTime(e.str("updated_at")),style=MaterialTheme.typography.labelSmall,color=c.onSurfaceVariant)}
            }
        }
    }
}

@Composable fun EncounterScreen(s:AppState){
    val data=s.case?:return;val facts=data.items("facts");val pending=facts.count{it.str("confirmation_state")=="unconfirmed"};val confirmed=facts.count{it.str("confirmation_state")=="confirmed"};val c=MaterialTheme.colorScheme
    Page{
        InstrumentTitle("Capture",data.optJSONObject("encounter")?.str("title"))
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            CaptureTile("Record","record",primary=true,modifier=Modifier.weight(1f),enabled=!s.loading){s.screen="record"}
            CaptureTile("Photo","photo",modifier=Modifier.weight(1f),enabled=!s.loading){s.screen="photo"}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            CaptureTile("Write note","note",modifier=Modifier.weight(1f),enabled=!s.loading){s.screen="evidence:typed"}
            CaptureTile("Measure","measure",modifier=Modifier.weight(1f),enabled=!s.loading){s.screen="evidence:measurement"}
        }
        TextButton(onClick={s.screen="sources"},modifier=Modifier.fillMaxWidth()){InstrumentIcon("add",Modifier.size(18.dp));Text("  More ways to add evidence")}
        val nextTitle=when{pending>0->"Review $pending ${if(pending==1)"finding" else "findings"}";confirmed>0&&data.items("documents").isEmpty()->"Create your document";data.items("documents").isNotEmpty()->"Continue documentation";else->"Add your first observation"}
        val nextDetail=when{pending>0->"$confirmed confirmed · $pending to review";confirmed>0->"$confirmed confirmed findings";else->"Record, photograph or write a note"}
        GlassPanel(Modifier.fillMaxWidth().clickable(role=Role.Button){s.screen=if(pending>0)"findings" else if(confirmed>0||data.items("documents").isNotEmpty())"documents" else "sources"},accent=c.primary){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                InstrumentIcon(if(pending>0)"review" else "document",Modifier.size(28.dp),c.primary)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(nextTitle,style=MaterialTheme.typography.titleMedium);Text(nextDetail,style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)}
                InstrumentIcon("arrow",Modifier.size(22.dp),c.primary)
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Sources",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Text(data.items("evidence").size.toString(),style=MaterialTheme.typography.labelMedium,color=c.onSurfaceVariant)}
        if(data.items("evidence").isEmpty())Text("No sources added.",style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
        data.items("evidence").reversed().forEach{e->EvidenceItem(s,e)}
        TextButton(onClick={s.history()}){InstrumentIcon("history",Modifier.size(18.dp));Text("  Encounter history")}
    }
}

fun sourceLabel(kind:String)=when(kind){"typed"->"Written note";"photo"->"Photograph";"audio"->"Recording";"manual"->"Assessment";else->kind.replace('_',' ').replaceFirstChar{it.uppercase()}}

@Composable fun SourcesScreen(s:AppState){Page{
    InstrumentTitle("Add evidence")
    listOf(Triple("Manual assessment","review","evidence:manual"),Triple("Transcript","note","evidence:transcript"),Triple("Clinical result","document","evidence:result"),Triple("Upload a document","document","upload"),Triple("Record a visit","record","record"),Triple("Wound photograph","photo","photo"),Triple("Measurements","measure","evidence:measurement"),Triple("Written note","note","evidence:typed")).chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){row.forEach{(title,icon,route)->CaptureTile(title,icon,modifier=Modifier.weight(1f)){s.screen=route}}}}
}}

@Composable fun HistoryScreen(s:AppState){Page{InstrumentTitle("History");s.audit.forEach{e->EvidenceRail{Column{Text(e.str("action").replace('.',' ').replace('_',' '),fontWeight=FontWeight.Medium);Text(localTime(e.str("created_at")),style=MaterialTheme.typography.bodySmall);if(e.optJSONObject("detail")?.length()!=0)Text(prettyValue(e.opt("detail")),style=MaterialTheme.typography.bodySmall)}}}}}
