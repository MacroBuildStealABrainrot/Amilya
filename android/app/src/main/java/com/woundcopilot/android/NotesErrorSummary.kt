package com.woundcopilot.android

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLException

private val notesVersion = Regex("""\d{1,4}\.\d{1,4}\.\d{1,4}(?:-notes)?""")
// The live service generates a random, canonical UUID for each request.
private val serverRequestId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

/** Clipboard text comes only from known labels, numeric fields, and the server request ID. */
internal fun notesErrorSummary(
    failure: Exception, purpose: String?, task: String, screen: String, version: String, build: Int,
    time: Instant = Instant.now(),
): String {
    val step = when {
        purpose == "report" || task in setOf("Creating report", "Preparing your report", "Preparing a source-based draft") -> "Writing report"
        purpose == "audio" -> "Transcribing recording"
        task == "Saving report…" -> "Saving report"
        task == "Signing in" -> "Signing in"
        task == "Connecting" -> "Connecting"
        screen == "evidence:typed" -> "Submitting typed note"
        screen == "record" -> "Recording"
        else -> "App request"
    }
    val response = failure as? ApiException
    val reason = when (failure) {
        is ApiException -> failure.category?.takeIf { it in PHOTO_ERROR_CATEGORIES } ?: "SERVER_RESPONSE"
        is SSLException -> "SECURE_CONNECTION"
        is SocketTimeoutException -> "CONNECTION_TIMEOUT"
        is UnknownHostException, is SocketException -> "NETWORK_UNAVAILABLE"
        is IOException -> "READ_OR_WRITE"
        else -> "APP_ERROR"
    }
    return buildList {
        val release = version.takeIf(notesVersion::matches)?.let { " $it" }.orEmpty()
        add("Amilya Notes$release (build $build)")
        add(time.toString())
        add(step)
        add(reason)
        response?.status?.takeIf { it in 100..599 }?.let { add("HTTP $it") }
        response?.requestId?.takeIf(serverRequestId::matches)?.let { add("Request $it") }
    }.joinToString("\n")
}
