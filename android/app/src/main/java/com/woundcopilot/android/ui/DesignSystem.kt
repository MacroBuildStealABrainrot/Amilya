package com.woundcopilot.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Graphite = darkColorScheme(
    primary = Color(0xFF1AB8E8),
    onPrimary = Color(0xFF011E5A),
    primaryContainer = Color(0xFF0B3C86),
    onPrimaryContainer = Color(0xFFEDF8FE),
    secondary = Color(0xFF48F5F5),
    onSecondary = Color(0xFF011E5A),
    secondaryContainer = Color(0xFF0A4A80),
    onSecondaryContainer = Color(0xFFD8FBFB),
    tertiary = Color(0xFF8ED8F5),
    onTertiary = Color(0xFF011E5A),
    tertiaryContainer = Color(0xFF12356F),
    onTertiaryContainer = Color(0xFFEDF8FE),
    background = Color(0xFF011E5A),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF05286E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF0E3478),
    onSurfaceVariant = Color(0xFFC3DCF2),
    surfaceTint = Color(0xFF1AB8E8),
    inverseSurface = Color(0xFFEDF8FE),
    inverseOnSurface = Color(0xFF011E5A),
    inversePrimary = Color(0xFF0B7FAA),
    outline = Color(0xFF6C9BC8),
    outlineVariant = Color(0xFF1D4A8E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceDim = Color(0xFF011749),
    surfaceBright = Color(0xFF16408A),
    surfaceContainerLowest = Color(0xFF011649),
    surfaceContainerLow = Color(0xFF04246A),
    surfaceContainer = Color(0xFF082B74),
    surfaceContainerHigh = Color(0xFF0E3480),
    surfaceContainerHighest = Color(0xFF173F8C),
)

private val Paper = lightColorScheme(
    primary = Color(0xFF0B86B4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1AB8E8),
    onPrimaryContainer = Color(0xFF011E5A),
    secondary = Color(0xFF0A5F8C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F8F8),
    onSecondaryContainer = Color(0xFF011E5A),
    tertiary = Color(0xFF1F4F9A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCEBFB),
    onTertiaryContainer = Color(0xFF011E5A),
    background = Color(0xFFF6FBFE),
    onBackground = Color(0xFF011E5A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF011E5A),
    surfaceVariant = Color(0xFFEDF8FE),
    onSurfaceVariant = Color(0xFF2B4A7C),
    surfaceTint = Color(0xFF1AB8E8),
    inverseSurface = Color(0xFF011E5A),
    inverseOnSurface = Color(0xFFEDF8FE),
    inversePrimary = Color(0xFF48F5F5),
    outline = Color(0xFF5E86B5),
    outlineVariant = Color(0xFFC5DCEF),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBE0D9),
    onErrorContainer = Color(0xFF702219),
    surfaceDim = Color(0xFFD9E8F3),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3FAFE),
    surfaceContainer = Color(0xFFEDF8FE),
    surfaceContainerHigh = Color(0xFFE3F2FC),
    surfaceContainerHighest = Color(0xFFD9ECF9),
)

private val WoundTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-1.6).sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1.2).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 23.sp, lineHeight = 29.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.25.sp),
)

@Composable
fun WoundTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Graphite else Paper,
        typography = WoundTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

@Composable
fun Wordmark(compact: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.woundcopilot.android.R.drawable.amilya_logo), contentDescription = "Amilya", modifier = Modifier.size(if (compact) 32.dp else 44.dp))
        Spacer(Modifier.width(if (compact) 9.dp else 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "Amilya",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = if (compact) 20.sp else 23.sp,
                    letterSpacing = (-0.8).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            if (!compact) {
                Text(
                    "CLINICAL WORKSPACE",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.7.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun Eyebrow(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun PageHeading(title: String, subtitle: String = "") {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun WcpButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) .975f else 1f, tween(90), label = "button pressure")
    val c=MaterialTheme.colorScheme
    val view=LocalView.current
    val shape=RoundedCornerShape(17.dp)
    val fill=if(!enabled)c.surfaceContainerHigh else if(secondary)c.surfaceContainer else c.primary
    val ink=if(!enabled)c.onSurface.copy(alpha=.35f) else if(secondary)c.onSurface else c.onPrimary
    Box(modifier.heightIn(min=52.dp).graphicsLayer{scaleX=scale;scaleY=scale;translationY=if(pressed)1.dp.toPx()else 0f}
        .shadow(if(secondary||!enabled)0.dp else 4.dp,shape)
        .clip(shape).background(Brush.verticalGradient(listOf(fill,fill.copy(alpha=.90f))))
        .border(1.dp,(if(secondary)c.outlineVariant else c.onPrimary.copy(alpha=.12f)).copy(alpha=if(enabled).65f else .15f),shape)
        .clickable(interactionSource=interaction,indication=androidx.compose.material3.ripple(),enabled=enabled,role=Role.Button){view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);onClick()}
        .padding(horizontal=18.dp,vertical=13.dp),contentAlignment=Alignment.Center){Text(text,style=MaterialTheme.typography.labelLarge,color=ink)}
}

@Composable
fun StatusTag(text: String, tone: String = "neutral") {
    val colors = MaterialTheme.colorScheme
    val foreground = when (tone.lowercase()) {
        "success", "positive", "confirmed", "saved", "accent", "processing" -> colors.primary
        "warning", "pending", "review" -> if (colors.background == Graphite.background) Color(0xFFEBC58A) else Color(0xFF795013)
        "error", "danger", "failed", "recording" -> colors.error
        "info", "media" -> colors.tertiary
        else -> colors.onSurfaceVariant
    }
    val animatedColor by animateColorAsState(foreground, tween(160), label = "status color")
    Surface(color = animatedColor.copy(alpha = .10f), shape = RoundedCornerShape(6.dp)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(4.dp).background(animatedColor, RoundedCornerShape(2.dp)))
            Text(text, style = MaterialTheme.typography.labelSmall, color = animatedColor)
        }
    }
}

@Composable
fun SectionLabel(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (action != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(action, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp)) {
        Box(Modifier.width(28.dp).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        if(body.isNotBlank()){Spacer(Modifier.height(8.dp));Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)}
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            WcpButton(action, onAction, secondary = true)
        }
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    com.woundcopilot.android.FadingFeedback(message) {
        Surface(
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            color = colors.errorContainer,
            contentColor = colors.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onErrorContainer),
                ) { Text("Dismiss") }
            }
        }
    }
}

@Composable
fun SourceMark(kind: String, modifier: Modifier = Modifier) {
    val source = kind.lowercase()
    val colors = MaterialTheme.colorScheme
    val color = when {
        source.contains("image") || source.contains("photo") || source.contains("camera") -> colors.tertiary
        source.contains("audio") || source.contains("record") || source.contains("transcript") -> colors.primary
        source.contains("document") || source.contains("report") -> colors.secondary
        else -> colors.onSurfaceVariant
    }
    Canvas(
        modifier.size(40.dp)
            .background(color.copy(alpha = .09f), RoundedCornerShape(10.dp))
            .semantics { contentDescription = "$kind source" }
            .padding(9.dp),
    ) {
        val width = size.width
        val height = size.height
        val stroke = 1.6.dp.toPx()
        when {
            source.contains("audio") || source.contains("record") || source.contains("transcript") -> {
                listOf(.30f, .60f, .93f, .51f, .73f).forEachIndexed { index, extent ->
                    val x = width * (.1f + index * .2f)
                    drawLine(color, Offset(x, height * (1 - extent) / 2), Offset(x, height * (1 + extent) / 2), stroke, StrokeCap.Round)
                }
            }
            source.contains("image") || source.contains("photo") || source.contains("camera") -> {
                drawRoundRect(color, Offset(width * .05f, height * .08f), Size(width * .90f, height * .84f), CornerRadius(2.dp.toPx()), style = Stroke(stroke))
                drawCircle(color, width * .09f, Offset(width * .69f, height * .33f))
                val mountain = Path().apply {
                    moveTo(width * .1f, height * .77f)
                    lineTo(width * .38f, height * .46f)
                    lineTo(width * .57f, height * .65f)
                    lineTo(width * .72f, height * .52f)
                    lineTo(width * .9f, height * .75f)
                }
                drawPath(mountain, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            source.contains("measure") -> {
                drawRoundRect(color, Offset(width * .03f, height * .30f), Size(width * .94f, height * .4f), CornerRadius(1.dp.toPx()), style = Stroke(stroke))
                for (i in 1..4) {
                    val x = width * (i * .18f + .05f)
                    drawLine(color, Offset(x, height * .31f), Offset(x, height * if (i % 2 == 0) .51f else .44f), stroke, StrokeCap.Round)
                }
            }
            else -> {
                drawRoundRect(color, Offset(width * .13f, height * .05f), Size(width * .74f, height * .9f), CornerRadius(2.dp.toPx()), style = Stroke(stroke))
                for (i in 0..2) {
                    val y = height * (.31f + i * .19f)
                    drawLine(color, Offset(width * .30f, y), Offset(width * if (i == 2) .56f else .70f, y), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
fun EvidenceRail(content: @Composable () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth()
            .drawBehind {
                val x = 4.dp.toPx()
                drawLine(color.copy(alpha = .27f), Offset(x, 9.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                drawCircle(color, 3.dp.toPx(), Offset(x, 8.dp.toPx()))
            }
            .padding(start = 23.dp, bottom = 20.dp),
    ) { content() }
}

