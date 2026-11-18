package com.woundcopilot.android

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import kotlinx.coroutines.delay

/** No onDispose/onDismiss writes: leaving this composition cannot erase a warning or retry. */
@Composable
internal fun FadingFeedback(
    message: String,
    sequence: Long = 0,
    hasAction: Boolean = false,
    content: @Composable () -> Unit,
) {
    val timer = remember(message, sequence, hasAction) {
        FeedbackFadeTimer(message, sequence, SystemClock.elapsedRealtime(), hasAction)
    }
    var visible by remember(timer) { mutableStateOf(true) }
    LaunchedEffect(timer) {
        timer.fadeAfterMillis?.let {
            delay(it)
            visible = timer.isVisibleAt(SystemClock.elapsedRealtime())
        }
    }
    AnimatedVisibility(visible = visible, exit = fadeOut(tween(250))) { content() }
}
