package com.woundcopilot.android.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val SignalCoral = Color(0xFFFFA88F)

/** A consistent set of paths; icons retain text labels on every main action. */
@Composable
fun InstrumentIcon(name: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(24.dp)) {
        val k = size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x*k,y*k)
        fun line(x: Float,y: Float,x2: Float,y2: Float) = drawLine(tint,p(x,y),p(x2,y2),1.65f*k,StrokeCap.Round)
        fun path(vararg points: Pair<Float,Float>) {
            val path=Path();points.forEachIndexed { i,xy -> if(i==0)path.moveTo(xy.first*k,xy.second*k)else path.lineTo(xy.first*k,xy.second*k) }
            drawPath(path,tint,style=Stroke(1.65f*k,cap=StrokeCap.Round,join=StrokeJoin.Round))
        }
        when(name) {
            "share" -> {
                line(7f,11f,17f,6f);line(7f,13f,17f,18f)
                listOf(p(5f,12f),p(19f,5f),p(19f,19f)).forEach{drawCircle(tint,2.5f*k,it,style=Stroke(1.65f*k))}
            }
            "voice" -> {
                // Sound waves: spoken commands, distinct from the recording microphone.
                line(5f,10f,5f,14f);line(9f,7f,9f,17f);line(13f,4f,13f,20f);line(17f,8f,17f,16f);line(21f,10.5f,21f,13.5f)
            }
            "record", "audio" -> {
                drawRoundRect(tint,p(9f,3f),Size(6*k,12*k),CornerRadius(3*k),style=Stroke(1.65f*k))
                drawArc(tint,0f,180f,false,p(5.5f,7f),Size(13*k,12*k),style=Stroke(1.65f*k,cap=StrokeCap.Round))
                line(12f,19f,12f,22f);line(9f,22f,15f,22f)
            }
            "photo", "camera" -> {
                path(3f to 8f,7f to 8f,9f to 5f,15f to 5f,17f to 8f,21f to 8f,21f to 20f,3f to 20f,3f to 8f)
                drawCircle(tint,4*k,p(12f,13.5f),style=Stroke(1.65f*k))
            }
            "note", "typed", "edit" -> {
                path(4f to 20f,5f to 15f,17f to 3f,21f to 7f,9f to 19f,4f to 20f)
                line(14f,6f,18f,10f)
            }
            "measure", "measurement" -> {
                drawRoundRect(tint,p(2f,7f),Size(20*k,10*k),CornerRadius(2*k),style=Stroke(1.65f*k))
                for(i in 1..5)line(2f+i*3f,7f,2f+i*3f,if(i%2==0)12f else 10f)
            }
            "review", "check" -> { drawCircle(tint,9*k,p(12f,12f),style=Stroke(1.65f*k));path(7f to 12f,10.5f to 15.5f,17f to 8.5f) }
            "document", "documents" -> {path(6f to 2f,15f to 2f,20f to 7f,20f to 22f,6f to 22f,6f to 2f);path(15f to 2f,15f to 7f,20f to 7f);line(9f,12f,16f,12f);line(9f,16f,16f,16f)}
            "cases" -> {drawRoundRect(tint,p(3f,5f),Size(18*k,16*k),CornerRadius(3*k),style=Stroke(1.65f*k));line(7f,2f,7f,7f);line(17f,2f,17f,7f);line(3f,10f,21f,10f)}
            "capture" -> {path(8f to 3f,3f to 3f,3f to 8f);path(16f to 3f,21f to 3f,21f to 8f);path(3f to 16f,3f to 21f,8f to 21f);path(21f to 16f,21f to 21f,16f to 21f);line(12f,8f,12f,16f);line(8f,12f,16f,12f)}
            "search" -> {drawCircle(tint,6.5f*k,p(10.5f,10.5f),style=Stroke(1.65f*k));line(15.5f,15.5f,21f,21f)}
            "person", "account" -> {drawCircle(tint,4*k,p(12f,7f),style=Stroke(1.65f*k));drawArc(tint,180f,180f,false,p(4f,14f),Size(16*k,14*k),style=Stroke(1.65f*k,cap=StrokeCap.Round))}
            "back" -> path(15f to 5f,8f to 12f,15f to 19f)
            "arrow" -> {line(4f,12f,20f,12f);path(14f to 6f,20f to 12f,14f to 18f)}
            "down" -> path(6f to 9f,12f to 15f,18f to 9f)
            "close" -> {line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
            "add" -> {line(12f,4f,12f,20f);line(4f,12f,20f,12f)}
            "pause" -> {line(8f,5f,8f,19f);line(16f,5f,16f,19f)}
            "stop" -> drawRoundRect(tint,p(6f,6f),Size(12*k,12*k),CornerRadius(2*k))
            "refresh" -> {drawArc(tint,45f,280f,false,p(4f,4f),Size(16*k,16*k),style=Stroke(1.65f*k,cap=StrokeCap.Round));path(20f to 3f,20f to 9f,14f to 9f)}
            "history" -> {drawCircle(tint,9*k,p(12f,12f),style=Stroke(1.65f*k));path(12f to 6f,12f to 12f,16f to 14f)}
            "kosmo" -> {
                // Chat bubble with a small medical cross (Ask Consultant).
                drawRoundRect(tint,p(3f,3.5f),Size(18*k,13.5f*k),CornerRadius(5*k),style=Stroke(1.65f*k))
                path(8f to 17f,6.5f to 21f,12f to 17f)
                line(12f,7f,12f,13.5f);line(8.75f,10.25f,15.25f,10.25f)
            }
            else -> {line(5f,7f,19f,7f);line(5f,12f,19f,12f);line(5f,17f,15f,17f)}
        }
    }
}

@Composable
fun TouchIcon(name: String, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val view=LocalView.current
    IconButton(onClick={view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);onClick()},enabled=enabled,modifier=Modifier.size(48.dp).semantics{contentDescription=label}) {
        InstrumentIcon(name,tint=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled)1f else .35f))
    }
}

/** Optical depth is confined to controls; evidence and document text remain opaque. */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val c=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(26.dp)
    val light=c.background.luminance()>.5f
    Column(modifier.shadow(10.dp,shape,ambientColor=Color.Black.copy(alpha=.1f),spotColor=Color.Black.copy(alpha=.12f))
        .clip(shape).background(Brush.linearGradient(listOf((accent?:c.surfaceContainerHigh).copy(alpha=if(accent!=null).17f else .97f),c.surfaceContainerLow)))
        .border(1.dp,Brush.linearGradient(listOf(c.onSurface.copy(alpha=if(light).10f else .16f),c.outlineVariant.copy(alpha=.18f))),shape)
        .padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
}

@Composable
fun InstrumentTitle(title:String, detail:String?=null, action: (@Composable () -> Unit)?=null) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(title,style=MaterialTheme.typography.headlineLarge.copy(fontFamily=FontFamily.Serif,fontWeight=FontWeight.Normal,fontSize=34.sp,lineHeight=38.sp),modifier=Modifier.semantics{heading()})
            if(!detail.isNullOrBlank())Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
fun CaptureTile(title:String, icon:String, modifier:Modifier=Modifier, subtitle:String?=null, primary:Boolean=false, enabled:Boolean=true, onClick:()->Unit) {
    val c=MaterialTheme.colorScheme;val interaction=remember{MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed).96f else 1f,spring(dampingRatio=.8f,stiffness=750f),label="capture pressure")
    val view=LocalView.current
    val color=if(primary)c.primary else c.surfaceContainerHigh
    val ink=if(primary)c.onPrimary else c.onSurface
    Column(modifier.heightIn(min=112.dp).graphicsLayer{scaleX=scale;scaleY=scale}.clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(color,if(primary)color.copy(alpha=.82f)else c.surfaceContainerLow)))
        .border(1.dp,(if(primary)c.primary else c.outlineVariant).copy(alpha=.4f),RoundedCornerShape(24.dp))
        .clickable(interactionSource=interaction,indication=ripple(),enabled=enabled,role=Role.Button){view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);onClick()}
        .padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){InstrumentIcon(icon,Modifier.size(29.dp),ink);Spacer(Modifier.weight(1f));if(primary)InstrumentIcon("arrow",Modifier.size(18.dp),ink.copy(alpha=.6f))}
        Text(title,style=MaterialTheme.typography.titleMedium,color=ink)
        if(!subtitle.isNullOrBlank())Text(subtitle,style=MaterialTheme.typography.labelSmall,color=ink.copy(alpha=.75f))
    }
}

data class DockDestination(val route:String,val title:String,val icon:String,val badge:Int=0)

@Composable
fun InstrumentDock(destinations:List<DockDestination>, selected:String, onSelect:(String)->Unit) {
    val c=MaterialTheme.colorScheme;val view=LocalView.current
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.background.copy(alpha=0f),c.background))).navigationBarsPadding().padding(horizontal=20.dp,vertical=10.dp),contentAlignment=Alignment.Center) {
        Row(Modifier.widthIn(max=500.dp).fillMaxWidth().shadow(14.dp,RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(c.surfaceContainerHigh,c.surfaceContainer)))
            .border(1.dp,c.outlineVariant.copy(alpha=.65f),RoundedCornerShape(28.dp)).padding(6.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            destinations.forEach { d ->
                val active=d.route==selected
                val fill by animateColorAsState(if(active)c.primary else Color.Transparent,tween(180),label="dock selection")
                val ink by animateColorAsState(if(active)c.onPrimary else c.onSurfaceVariant,tween(180),label="dock label")
                Column(Modifier.weight(1f).clip(RoundedCornerShape(23.dp)).background(fill)
                    .semantics{this.selected=active;contentDescription=d.title}
                    .clickable(role=Role.Tab){if(!active){view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);onSelect(d.route)}}
                    .heightIn(min=60.dp).padding(vertical=8.dp,horizontal=4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Box {InstrumentIcon(d.icon,Modifier.size(21.dp),ink);if(d.badge>0)Box(Modifier.align(Alignment.TopEnd).offset(x=8.dp,y=(-3).dp).size(7.dp).background(if(active)c.onPrimary else SignalCoral,CircleShape))}
                    Text(d.title,style=MaterialTheme.typography.labelSmall,color=ink)
                }
            }
        }
    }
}

@Composable
fun DetailDisclosure(title:String, summary:String?=null, initiallyOpen:Boolean=false, content:@Composable ColumnScope.()->Unit) {
    var open by rememberSaveable(title){mutableStateOf(initiallyOpen)}
    val angle by animateFloatAsState(if(open)180f else 0f,tween(180),label="details disclosure")
    Column(Modifier.fillMaxWidth().animateContentSize(tween(180))) {
        Row(Modifier.fillMaxWidth().semantics{stateDescription=if(open)"Expanded" else "Collapsed"}.clickable(role=Role.Button){open=!open}.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(!summary.isNullOrBlank())Text(summary,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            InstrumentIcon("down",Modifier.padding(start=8.dp).size(18.dp).graphicsLayer{rotationZ=angle},MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open){Column(Modifier.padding(bottom=10.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)}
    }
}

@Composable
fun RecorderControl(enabled:Boolean,recording:Boolean,paused:Boolean,onRecord:()->Unit,onStop:()->Unit) {
    val c=MaterialTheme.colorScheme;val view=LocalView.current
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed).92f else 1f,spring(.8f,700f),label="record control")
    val label=if(recording)"Stop and save" else "Start recording"
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(94.dp).graphicsLayer{scaleX=scale;scaleY=scale}.shadow(if(enabled)12.dp else 0.dp,CircleShape).clip(CircleShape)
            .background(if(enabled)SignalCoral else c.surfaceContainerHigh).border(5.dp,c.background.copy(alpha=.35f),CircleShape)
            .clickable(interactionSource=interaction,indication=ripple(),enabled=enabled,role=Role.Button,onClick={view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);if(recording)onStop()else onRecord()})
            .semantics{contentDescription=label},contentAlignment=Alignment.Center) {
            InstrumentIcon(if(recording)"stop" else "record",Modifier.size(34.dp),if(enabled)Color(0xFF442B26)else c.onSurfaceVariant.copy(alpha=.4f))
        }
        Text(label,style=MaterialTheme.typography.labelLarge,color=if(enabled)c.onSurface else c.onSurfaceVariant)
    }
}
