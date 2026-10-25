package com.woundcopilot.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun storedInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
    ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
    // Older SQLite records omit an offset, but the storage contract is UTC.
    ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()

internal fun formatLocalTime(value: String, zone: ZoneId, use24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
    val instant=storedInstant(value) ?: return value
    return DateTimeFormatter.ofPattern(if(use24Hour) "MMM d, yyyy · HH:mm" else "MMM d, yyyy · h:mm a",locale)
        .withZone(zone).format(instant)
}

@Composable fun localTime(value: String): String = formatLocalTime(value,ZoneId.systemDefault(),android.text.format.DateFormat.is24HourFormat(LocalContext.current))
