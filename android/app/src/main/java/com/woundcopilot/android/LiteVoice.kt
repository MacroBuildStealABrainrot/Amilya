package com.woundcopilot.android

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.woundcopilot.android.ui.WcpButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * "Amilya Voice": the light app. It does one thing: record your voice, turn it into text, and let you share that text.
 * No patient charts, photos, reports or consultant. It uses the same sign-in and the same server step that already
 * turns a spoken message into text (no new server code). The audio file lives only in the app's private folder and is
 * deleted as soon as the text arrives.
 */
private const val LITE_MAX_SECONDS = 40 * 60

private fun Context.liteActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.liteActivity(); else -> null }

internal class LiteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var current: File? = null
    private var startedAt = 0L

    private fun folder() = File(context.filesDir, "lite-voice").apply { mkdirs() }

    /** The newest recording that has not been turned into text yet (for example after an error or a closed app). */
    fun pendingFile(): File? = folder().listFiles { f -> f.isFile && f.extension == "m4a" && f.length() > 0 }
        ?.maxByOrNull { it.lastModified() }

    fun start(): Boolean {
        val file = File(folder(), "voice-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r; current = file; startedAt = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            runCatching { r.release() }
            file.delete()
            false
        }
    }

    /** Stops and returns the saved file with its length in seconds, or null if nothing usable was recorded. */
    fun stop(): Pair<File, Double>? {
        val r = recorder ?: return null
        val file = current
        val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
        recorder = null; current = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok || file == null || !file.isFile || file.length() == 0L) { file?.delete(); return null }
        return file to seconds
    }

    fun release() { recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }; recorder = null }
}

private suspend fun liteVisit(s: AppState, owner: String): String {
    val cache = "lite-visit-$owner"
    s.vault.read(cache)?.takeIf { it.isNotBlank() }?.let { return it }
    val request = UUID.randomUUID().toString()
    val response = s.api.call("/quick/start", "POST", obj("mode" to "document", "request_id" to request), request) as JSONObject
    val id = response.getJSONObject("encounter").str("id")
    s.vault.write(cache, id)
    return id
}

private suspend fun liteTranscribe(s: AppState, owner: String, file: File, seconds: Double): JSONObject {
    if (s.demo) return obj("text" to "Sample only. No transcription was performed.")
    suspend fun once(): JSONObject {
        val visit = liteVisit(s, owner)
        return s.api.multipart("/encounters/$visit/transcribe-message", file, "audio/mp4",
            mapOf("consent" to "true", "duration_seconds" to "%.1f".format(java.util.Locale.US, seconds.coerceAtLeast(1.0))),
            "lite-" + file.name)
    }
    return try { once() } catch (e: CancellationException) { throw e } catch (e: Exception) {
        // The temporary holder visit can expire on the server. Make a fresh one once and try again.
        if (e.message.orEmpty().contains("not found", ignoreCase = true)) { s.vault.remove("lite-visit-$owner"); once() } else throw e
    }
}

@Composable
fun LiteVoiceScreen(s: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = s.user?.str("id") ?: "sample"
    val recorder = remember { LiteRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf(s.vault.read("lite-text-$owner").orEmpty()) }
    var pending by remember { mutableStateOf(recorder.pendingFile()) }
    var lastSeconds by remember { mutableDoubleStateOf(0.0) }
    val activity = context.liteActivity()

    fun saveText(value: String) { text = value; if (value.isBlank()) s.vault.remove("lite-text-$owner") else s.vault.write("lite-text-$owner", value) }

    fun finishRecording() {
        val result = recorder.stop()
        recording = false
        if (result == null) { message = "Nothing was recorded. Please try again."; return }
        pending = result.first; lastSeconds = result.second
    }

    fun turnIntoText(file: File) {
        if (working) return
        scope.launch {
            working = true; message = null
            try {
                val duration = if (file == pending && lastSeconds > 0) lastSeconds else 60.0
                val r = liteTranscribe(s, owner, file, duration)
                val heard = r.str("text").trim()
                if (r.optBoolean("needs_rerecord") || heard.isBlank()) {
                    message = "No clear speech was heard. Move closer to the phone and record again."
                    file.delete(); pending = recorder.pendingFile()
                } else {
                    saveText(if (text.isBlank()) heard else text.trimEnd() + "\n\n" + heard)
                    file.delete(); pending = recorder.pendingFile()
                    message = null
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                message = (e.message?.takeIf { it.isNotBlank() } ?: "The text could not be made.") + " Your recording is saved on this phone. Tap Turn into text to try again."
            } finally { working = false }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { if (recorder.start()) { recording = true; seconds = 0; message = null } else message = "The microphone could not start. Close other apps that use it and try again." }
        else message = "Amilya Voice needs the microphone. Allow it in your phone's Settings."
    }

    fun startRecording() {
        if (working) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (recorder.start()) { recording = true; seconds = 0; message = null } else message = "The microphone could not start. Close other apps that use it and try again."
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(recording) {
        while (recording) { delay(1000); seconds++; if (seconds >= LITE_MAX_SECONDS) { finishRecording(); message = "Recording stopped at 40 minutes. Tap Turn into text." } }
    }
    // Keep the screen on while recording, and stop safely if the app is left (the phone silences the microphone for apps in the background).
    DisposableEffect(recording) {
        if (recording) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(activity) {
        val lifecycle = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && recording) { finishRecording(); message = "Recording stopped because you left the app. It is saved. Tap Turn into text." }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { recorder.release() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Amilya Voice", style = MaterialTheme.typography.headlineMedium)
        Text("Record your voice. Get the text. Share it.", style = MaterialTheme.typography.bodyLarge)
        if (s.demo) Text("Sample mode: nothing is sent and no real text is made.", color = MaterialTheme.colorScheme.error)

        if (recording) {
            Text("Recording  %d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
            Text("Keep this screen open while you talk.", style = MaterialTheme.typography.bodyMedium)
            WcpButton("Stop", { finishRecording() }, Modifier.fillMaxWidth())
        } else {
            WcpButton(if (text.isBlank() && pending == null) "Start recording" else "Record more", { startRecording() }, Modifier.fillMaxWidth(), enabled = !working)
            Text("Press record only when everyone being recorded knows and agrees.", style = MaterialTheme.typography.bodySmall)
        }

        pending?.let { file ->
            if (!recording) {
                WcpButton(if (working) "Making text..." else "Turn into text", { turnIntoText(file) }, Modifier.fillMaxWidth(), enabled = !working)
                if (!working) WcpButton("Delete this recording", { file.delete(); pending = recorder.pendingFile() }, Modifier.fillMaxWidth(), secondary = true)
            }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (text.isNotBlank() || pending == null) {
            OutlinedTextField(value = text, onValueChange = { saveText(it) }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                label = { Text("Your text (you can edit it)") })
        }
        if (text.isNotBlank()) {
            WcpButton("Share text", {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                runCatching { context.startActivity(Intent.createChooser(send, "Share text")) }
                    .onFailure { message = "No app was found to share with." }
            }, Modifier.fillMaxWidth())
            WcpButton("Copy text", {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Amilya Voice", text))
                message = null
            }, Modifier.fillMaxWidth(), secondary = true)
            WcpButton("Clear text", { saveText("") }, Modifier.fillMaxWidth(), secondary = true)
            Text("Check the text before you share it. Do not send patient details to apps you do not trust.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton({ s.screen = "account" }) { Text("Account and sign out") }
    }
}
