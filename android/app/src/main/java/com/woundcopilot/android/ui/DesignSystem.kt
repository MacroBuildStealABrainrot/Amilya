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
    primary = Color(0xFFACE5CF),
    onPrimary = Color(0xFF10382C),
    primaryContainer = Color(0xFF234D40),
    onPrimaryContainer = Color(0xFFCCF6E5),
    secondary = Color(0xFFD6D1BD),
    onSecondary = Color(0xFF35352B),
    secondaryContainer = Color(0xFF44483D),
    onSecondaryContainer = Color(0xFFF2EED9),
    tertiary = Color(0xFFB4C9EB),
    onTertiary = Color(0xFF18334F),
    tertiaryContainer = Color(0xFF304763),
    onTertiaryContainer = Color(0xFFDCE8FF),
    background = Color(0xFF11191B),
    onBackground = Color(0xFFF2F2E9),
    surface = Color(0xFF172224),
    onSurface = Color(0xFFF2F2E9),
    surfaceVariant = Color(0xFF263335),
    onSurfaceVariant = Color(0xFFB8C5C3),
    surfaceTint = Color(0xFFACE5CF),
    inverseSurface = Color(0xFFE3E6DE),
    inverseOnSurface = Color(0xFF25302E),
    inversePrimary = Color(0xFF2F6653),
    outline = Color(0xFF80938D),
    outlineVariant = Color(0xFF3A4948),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF602824),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceDim = Color(0xFF11191B),
    surfaceBright = Color(0xFF364244),
    surfaceContainerLowest = Color(0xFF0D1416),
    surfaceContainerLow = Color(0xFF172224),
    surfaceContainer = Color(0xFF1C292B),
    surfaceContainerHigh = Color(0xFF273537),
    surfaceContainerHighest = Color(0xFF324244),
)

private val Paper = lightColorScheme(
    primary = Color(0xFF225C47),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EDDE),
    onPrimaryContainer = Color(0xFF123B2A),
    secondary = Color(0xFF615D45),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E4CE),
    onSecondaryContainer = Color(0xFF343421),
    tertiary = Color(0xFF355F85),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE8F8),
    onTertiaryContainer = Color(0xFF18364D),
    background = Color(0xFFF4F3EC),
    onBackground = Color(0xFF202D2C),
    surface = Color(0xFFFFFEF9),
    onSurface = Color(0xFF202D2C),
    surfaceVariant = Color(0xFFE5E8DF),
    onSurfaceVariant = Color(0xFF4C5E58),
    surfaceTint = Color(0xFF225C47),
    inverseSurface = Color(0xFF283733),
    inverseOnSurface = Color(0xFFF2F2E9),
    inversePrimary = Color(0xFFACE5CF),
    outline = Color(0xFF708179),
    outlineVariant = Color(0xFFCCD4C9),
    error = Color(0xFF9E3229),
    onError = Color.White,
    errorContainer = Color(0xFFFBE0D9),
    onErrorContainer = Color(0xFF702219),
    surfaceDim = Color(0xFFDADDD3),
    surfaceBright = Color(0xFFFFFEF9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F6EE),
    surfaceContainer = Color(0xFFEEEEE5),
    surfaceContainerHigh = Color(0xFFE7E9DF),
    surfaceContainerHighest = Color(0xFFE1E5DA),
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
        Canvas(Modifier.size(if (compact) 27.dp else 34.dp)) {
            val unit = size.minDimension / 34f
            val stroke = 2.2f * unit
            // Open brackets and one evidence point recur throughout the workspace.
            val left = Path().apply {
                moveTo(13f * unit, 4f * unit)
                lineTo(5f * unit, 4f * unit)
                lineTo(5f * unit, 30f * unit)
                lineTo(13f * unit, 30f * unit)
            }
            val right = Path().apply {
                moveTo(21f * unit, 4f * unit)
                lineTo(29f * unit, 4f * unit)
                lineTo(29f * unit, 30f * unit)
                lineTo(21f * unit, 30f * unit)
            }
            drawPath(left, accent, style = Stroke(stroke, cap = StrokeCap.Round))
            drawPath(right, accent, style = Stroke(stroke, cap = StrokeCap.Round))
            drawLine(accent.copy(alpha = .42f), Offset(17f * unit, 9f * unit), Offset(17f * unit, 25f * unit), strokeWidth = unit)
            drawCircle(accent, radius = 3.3f * unit, center = Offset(17f * unit, 17f * unit))
        }
        Spacer(Modifier.width(if (compact) 9.dp else 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "WoundCoPilot",
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

