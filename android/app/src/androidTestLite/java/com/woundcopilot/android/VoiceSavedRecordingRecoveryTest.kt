package com.woundcopilot.android

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/** Each scenario runs in a fresh offline emulator process. All HTTPS responses are in memory. */
class VoiceSavedRecordingRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var state: AppState
    private lateinit var audio: File

    companion object {
        private const val OWNER = "voice-recovery-synthetic"
        private const val TEXT = "Synthetic speaker one. Synthetic speaker two."
        private const val PENDING = "This AI request is already processing or awaiting reconciliation. Refresh the case before retrying."
        private val visit = UUID.randomUUID().toString()
        private val uploads = AtomicInteger()
        private val reads = AtomicInteger()
        private val assemblies = AtomicInteger()
        @Volatile private var ready = false
        @Volatile private var job = ""

        @BeforeClass @JvmStatic fun useOfflineFixture() {
            check(BuildConfig.LITE_VOICE && Build.HARDWARE in setOf("ranchu", "goldfish"))
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL): HttpURLConnection = object : HttpURLConnection(url) {
                        private val body = ByteArrayOutputStream()
                        private val reply by lazy { respond(url, requestMethod, body.toString("UTF-8")) }
                        override fun connect() {}
                        override fun disconnect() {}
                        override fun usingProxy() = false
                        override fun getOutputStream() = body
                        override fun getResponseCode() = reply.first
                        override fun getInputStream() = reply.second.byteInputStream()
                        override fun getErrorStream() = reply.second.byteInputStream()
                        override fun getHeaderField(name: String?): String? = null
                        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
                    }
                }
            }
        }

        private fun respond(url: URL, method: String, body: String): Pair<Int, String> {
            val endpoint = url.path.substringAfter("/api/v1")
            return when {
                endpoint == "/config" || endpoint == "/auth/logout" -> 200 to "{}"
                endpoint == "/encounters/$visit/audio-parts" && method == "POST" -> {
                    uploads.incrementAndGet()
                    409 to obj("detail" to PENDING).toString()
                }
                endpoint.startsWith("/encounters/$visit/audio-parts/") && method == "GET" -> {
                    val found = endpoint.substringAfter("/audio-parts/").substringBefore('/')
                    if (job.isEmpty()) job = found
                    check(found == job && url.query == "index=0&count=1")
                    reads.incrementAndGet()
                    404 to obj("detail" to "Not Found").toString()
                }
                endpoint == "/encounters/$visit/audio-complete" && method == "POST" -> {
                    val request = org.json.JSONObject(body)
                    check(request.getString("job_id") == job && request.getInt("count") == 1)
                    val attempt = assemblies.incrementAndGet()
                    if (ready && attempt >= 2) {
                        200 to obj("evidence" to obj("kind" to "audio", "encounter_id" to visit, "text" to TEXT,
                            "metadata" to obj("job_id" to job, "segment_count" to 1))).toString()
                    } else 409 to obj("detail" to "A recording segment still needs transcription. Retry that segment first.").toString()
                }
                else -> 404 to obj("detail" to "Unsupported synthetic request").toString()
            }
        }
    }

    @Before fun restoreSavedCapture() {
        uploads.set(0)
        reads.set(0)
        assemblies.set(0)
        job = ""
        ready = false
        val inst = InstrumentationRegistry.getInstrumentation()
        state = compose.runOnIdle { ViewModelProvider(compose.activity)[AppState::class.java] }
        compose.waitUntil(30_000) { !state.loading }
        compose.runOnIdle {
            assertNull(state.user)
            assertNull(state.vault.read("session"))
            audio = File(RecordingService.recordingsDirectory(compose.activity), "recording-${System.currentTimeMillis()}-synthetic.m4a")
            inst.context.assets.open("speech-45.m4a").use { source -> audio.outputStream().use { source.copyTo(it) } }
            RecordingService.writeMetadata(audio, "ready", 45_000, null)
            state.vault.write("recording-owner-${audio.name}", "$OWNER:lite")
            state.vault.write("lite-visit-$OWNER", visit)
            // Same synthetic sign-in path as the repository's recording/recovery instrumentation.
            AppState::class.java.getDeclaredMethod("setSession", org.json.JSONObject::class.java)
                .apply { isAccessible = true }.invoke(state, obj("user" to obj("id" to OWNER, "workspace_id" to "sandbox")))
            state.unlock()
            state.screen = "home"
        }
        waitFor("Turn into text")
    }

    @After fun cleanUp() {
        if (!::audio.isInitialized) return
        compose.runOnIdle {
            RecordingService.clearPending(compose.activity, audio.absolutePath, deleteFile = true)
            for (key in listOf("recording-owner-${audio.name}", "lite-pending-$OWNER-${audio.name}",
                "audio-batch-lite-$OWNER-${audio.name}", "lite-draft-$OWNER", "lite-visit-$OWNER")) state.vault.remove(key)
            state.logout()
        }
        compose.waitUntil(10_000) { !state.loading }
    }

    @Test fun savedRecordingAutomaticallyRecoversWhenTheLegacyServerFinishes() {
        ready = true
        press("Turn into text")
        waitFor(TEXT)
        compose.onNodeWithText(TEXT).performScrollTo().assertIsDisplayed()
        assertEquals(1, uploads.get())
        assertEquals(2, reads.get())
        assertEquals(2, assemblies.get())
        compose.waitUntil(5_000) { !audio.exists() }
    }

    @Test fun unconfirmedRecordingStaysPlayableAfterTheLastAutomaticCheck() {
        ready = false
        val initialLength = audio.length()
        press("Turn into text")
        waitFor("Check again", 50_000)
        // Wait for the complete automatic polling window, including the last status response.
        compose.waitUntil(50_000) { assemblies.get() >= 4 }
        compose.waitForIdle()
        assertEquals(1, uploads.get())
        assertEquals(4, reads.get())
        assertEquals(initialLength, audio.length())
        compose.onNodeWithText(PENDING, substring = true).assertDoesNotExist()
        compose.onNodeWithText(TEXT).assertDoesNotExist()
        press("Listen to recording")
        waitFor("Stop playback")
        press("Stop playback")
        waitFor("Listen to recording")
        assertEquals(initialLength, audio.length())
    }

    private fun waitFor(label: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }

    private fun press(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()
}
