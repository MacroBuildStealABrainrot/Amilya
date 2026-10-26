package com.woundcopilot.android

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Diagnostic events are STRICTLY allowlisted: never pass patient text, audio,
 * images, transcripts, HTTP bodies, stack traces, filenames or exception messages.
 * Telemetry is disabled unless the pilot build explicitly provides its own
 * PostHog ingestion host and project token.
 */
object ErrorTelemetry {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var appContext: Context? = null

    fun record(stage: String, outcome: String, code: String) {
        appContext?.let { record(it, stage, outcome, code) }
    }
    private val stages = setOf(
        "app", "recording", "recording_handover", "transcription",
        "report", "photo", "server", "ai_extraction"
    )
    private val outcomes = setOf("failed", "recovered", "crashed")
    private val codes = setOf(
        "unexpected", "permission_denied", "interrupted", "timeout",
        "empty_output", "network_failure", "handover_failed",
        "fallback_without_ai", "extraction_failed", "process_crash",
        "launch_failed", "upload_failed"
    )

    fun record(context: Context, stage: String, outcome: String, code: String) {
        if (stage !in stages || outcome !in outcomes || code !in codes) return
        sendSentry(stage, outcome, code)
        // Only this separate Pilot variant may emit diagnostics.
        if (BuildConfig.APPLICATION_ID != "com.woundcopilot.android.pilot") return
        val host = BuildConfig.TELEMETRY_HOST
        val token = BuildConfig.TELEMETRY_TOKEN
        if (token.isBlank() || !host.startsWith("https://") ||
            !host.matches(Regex("https://[A-Za-z0-9.-]+"))) return
        val app = context.applicationContext
        worker.execute {
            runCatching {
                val event = JSONObject()
                    .put("api_key", token)
                    .put("event", "amilya_operation_diagnostic")
                    .put("distinct_id", UUID.randomUUID().toString())
                    .put("properties", JSONObject()
                        .put("stage", stage)
                        .put("outcome", outcome)
                        .put("code", code)
                        .put("build", BuildConfig.VERSION_NAME)
                        .put("android_api", Build.VERSION.SDK_INT)
                        .put("\$geoip_disable", true))
                val connection = URL("$host/capture/").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(event.toString().toByteArray(Charsets.UTF_8)) }
                    connection.inputStream.use { it.close() }
                } finally { connection.disconnect() }
            }
        }
    }

    /** Minimal Sentry envelope: no SDK auto-capture, breadcrumbs, payloads or identifiers. */
    private fun sendSentry(stage: String, outcome: String, code: String) {
        if (BuildConfig.APPLICATION_ID != "com.woundcopilot.android.pilot") return
        val dsn = BuildConfig.SENTRY_DSN
        val uri = runCatching { java.net.URI(dsn) }.getOrNull() ?: return
        if (uri.scheme != "https" || uri.host == null || uri.userInfo.isNullOrBlank()) return
        val project = uri.path.trim('/').takeIf { it.matches(Regex("[0-9]+")) } ?: return
        val key = uri.userInfo.substringBefore(':').takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: return
        val endpoint = "https://${uri.host}/api/$project/envelope/"
        worker.execute {
            runCatching {
                val eventId = UUID.randomUUID().toString().replace("-", "")
                val timestamp = java.time.Instant.now().toString()
                val envelope = JSONObject().put("event_id", eventId).put("dsn", dsn).toString() + "\n" +
                    JSONObject().put("type", "event").toString() + "\n" +
                    JSONObject().put("event_id", eventId)
                        .put("timestamp", timestamp)
                        .put("platform", "java")
                        .put("level", if (outcome == "recovered") "warning" else "error")
                        .put("message", JSONObject().put("formatted", "Amilya technical failure"))
                        .put("fingerprint", org.json.JSONArray(listOf("amilya", stage, code)))
                        .put("tags", JSONObject().put("component", "android")
                            .put("stage", stage).put("outcome", outcome).put("code", code))
                        .toString() + "\n"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-sentry-envelope")
                    connection.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
                    connection.inputStream.use { it.close() }
                } finally { connection.disconnect() }
            }
        }
    }

    /** No exception detail is retained; failures are recorded by category only. */
    fun installCrashReporting(context: Context) {
        appContext = context.applicationContext
        if (BuildConfig.APPLICATION_ID != "com.woundcopilot.android.pilot") return
        val preferences = context.applicationContext.getSharedPreferences("amilya_diagnostic", Context.MODE_PRIVATE)
        if (preferences.getBoolean("previous_crash", false)) {
            preferences.edit().putBoolean("previous_crash", false).apply()
            record(context, "app", "crashed", "process_crash")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            preferences.edit().putBoolean("previous_crash", true).commit()
            if (previous != null) previous.uncaughtException(thread, throwable)
            else kotlin.system.exitProcess(10)
        }
    }
}
