package com.woundcopilot.android

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteOrder
import java.nio.ShortBuffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real Voice and Notes recorder on an offline emulator; a tone fixture is optional. */
@RunWith(AndroidJUnit4::class)
class ScreenOffRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun serviceAndEncodedTimelineContinueWhileScreenOff() = recordWhileScreenOff(checkMicrophoneSignal = false)

    @Test fun keepsRecordingSoundWhileTheDisplayIsOff() = recordWhileScreenOff(checkMicrophoneSignal = true)

    private fun recordWhileScreenOff(checkMicrophoneSignal: Boolean) {
        assumeTrue("Only Voice and Notes hold the CPU during recording", BuildConfig.LITE_VOICE || BuildConfig.NOTES_ONLY)
        assertTrue("A disposable emulator is required", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertFalse("Use the production recorder", BuildConfig.CRASH_SAFE_RECORDING)
        assertFalse("A recording was already in progress", RecordingService.state.value.recording)
        val power = context.getSystemService(PowerManager::class.java)
        wakeScreen()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        var captured: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            try {
                activity.onActivity { RecordingService.start(it) }
                await("Recorder did not start") { RecordingService.state.value.recording }
                val start = SystemClock.elapsedRealtime()
                val path = requireNotNull(RecordingService.state.value.filePath)
                captured = path
                await("The microphone service was not in the foreground") { isMicrophoneServiceForeground() }
                assertTrue("Android did not register the recorder CPU hold", ownsRecordingWakeLock())
                val baselineUs = if (checkMicrophoneSignal) {
                    Log.i("RecorderLockTest", "Recorder ready for synthetic tone")
                    var audibleSince = 0L
                    var lastAudible = 0L
                    val audible = waitFor {
                        val now = SystemClock.elapsedRealtime()
                        if (RecordingService.state.value.amplitude > 0.08f) {
                            if (now - lastAudible > 250 || audibleSince == 0L) audibleSince = now
                            lastAudible = now
                        }
                        audibleSince > 0L && now - lastAudible <= 250 && now - audibleSince >= 1_500
                    }
                    requireAwakeTone(audible, "No sustained input while the display was on")
                    val baseline = (SystemClock.elapsedRealtime() - start + 1_000) * 1_000
                    SystemClock.sleep(5_000)
                    baseline
                } else {
                    SystemClock.sleep(1_000)
                    null
                }
                shell("input keyevent KEYCODE_SLEEP")
                await("Emulator display never turned off") { !power.isInteractive }
                val offAt = SystemClock.elapsedRealtime()
                assertTrue("Android released the CPU hold on screen off", ownsRecordingWakeLock())
                assertTrue("Microphone service left the foreground on screen off", isMicrophoneServiceForeground())
                while (SystemClock.elapsedRealtime() - offAt < 12_000) {
                    assertFalse("Display woke before the measurement ended", power.isInteractive)
                    val state = RecordingService.state.value
                    assertTrue("Recording stopped or paused while the display was off", state.recording && !state.paused)
                    assertEquals("Recorder switched files", path, state.filePath)
                    SystemClock.sleep(200)
                }
                assertTrue("CPU hold missing at the end of the screen-off interval", ownsRecordingWakeLock())
                assertTrue("Foreground service missing at the end of the screen-off interval", isMicrophoneServiceForeground())
                val offUntil = SystemClock.elapsedRealtime()
                wakeScreen()
                assertTrue("Recording stopped on wake", RecordingService.state.value.recording)
                if (!checkMicrophoneSignal) {
                    RecordingService.pause(context)
                    await("Recording did not pause") { RecordingService.state.value.paused }
                    await("Android kept the CPU hold while paused") { !ownsRecordingWakeLock() }
                    RecordingService.resume(context)
                    await("Recording did not resume") { RecordingService.state.value.recording && !RecordingService.state.value.paused }
                    await("The resumed recorder did not recover the CPU hold") { ownsRecordingWakeLock() }
                    assertEquals("Resuming switched recording files", path, RecordingService.state.value.filePath)
                    SystemClock.sleep(1_000)
                }
                RecordingService.stop(context)
                await("Stop did not finalize the recording") { !RecordingService.state.value.recording }
                await("Android kept the CPU hold after stop") { !ownsRecordingWakeLock() }
                assertNull(RecordingService.state.value.error)
                assertEquals(path, RecordingService.state.value.filePath)
                // Trim a second at either boundary so button events and encoder startup cannot count.
                val fromUs = (offAt - start + 1_000) * 1_000
                val seconds = ((offUntil - offAt - 2_000) / 1_000).toInt()
                assertTrue("Screen-off measurement was too short", seconds >= 10)
                checkRecording(File(path), baselineUs, fromUs, seconds)
                if (checkMicrophoneSignal) {
                    Log.i("RecorderLockTest", "TONE_SCREEN_OFF_VERIFIED: 440 Hz captured in all $seconds measured screen-off seconds")
                } else {
                    Log.i("RecorderLockTest", "SERVICE_TIMELINE_VERIFIED: one foreground recorder, Android CPU hold during screen off, pause/resume/stop power checks, finalized AAC decoded in all $seconds measured screen-off seconds")
                }
            } finally {
                wakeScreen()
                if (RecordingService.state.value.recording) {
                    RecordingService.stop(context)
                    await("Could not stop the test recording") { !RecordingService.state.value.recording }
                }
                captured?.let { RecordingService.clearPending(context, it, deleteFile = true) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isMicrophoneServiceForeground(): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(64).any {
            it.service.className == RecordingService::class.java.name && it.foreground
        }

    private fun ownsRecordingWakeLock(): Boolean = shell("dumpsys power").lineSequence().any {
        it.trimStart().startsWith("PARTIAL_WAKE_LOCK") && it.contains("${context.packageName}:Microphone")
    }

    private fun requireAwakeTone(available: Boolean, reason: String) {
        if (!available) Log.i("RecorderLockTest", "TONE_FIXTURE_UNAVAILABLE: $reason")
        assumeTrue(reason, available)
    }

    private fun wakeScreen() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        await("Emulator display did not wake") { context.getSystemService(PowerManager::class.java).isInteractive }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun await(message: String, condition: () -> Boolean) {
        assertTrue(message, waitFor(condition))
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + 10_000
        do {
            if (condition()) return true
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < until)
        return false
    }

    private fun checkRecording(file: File, baselineUs: Long?, fromUs: Long, seconds: Int) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                val awake = baselineUs?.let { ToneWindow("screen-on baseline", it, 3) }
                val asleep = ToneWindow("screen-off", fromUs, seconds)
                val info = MediaCodec.BufferInfo()
                var inputFinished = false
                var outputFinished = false
                val deadline = SystemClock.elapsedRealtime() + 20_000
                while (!outputFinished && SystemClock.elapsedRealtime() < deadline) {
                    if (!inputFinished) {
                        val index = decoder.dequeueInputBuffer(5_000)
                        if (index >= 0) {
                            val input = requireNotNull(decoder.getInputBuffer(index))
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputFinished = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 5_000)
                    if (index >= 0) {
                        if (info.size > 0) {
                            val outputFormat = decoder.outputFormat
                            assertEquals(1, outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                            if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                assertEquals(AudioFormat.ENCODING_PCM_16BIT, outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING))
                            }
                            val buffer = requireNotNull(decoder.getOutputBuffer(index)).duplicate()
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val samples = buffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                            val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            awake?.add(info.presentationTimeUs, sampleRate, samples)
                            asleep.add(info.presentationTimeUs, sampleRate, samples)
                        }
                        outputFinished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
                assertTrue("AAC decoding did not complete", outputFinished)
                awake?.logMeasurements()
                asleep.logMeasurements()
                if (awake != null) {
                    awake.verifyFrames()
                    requireAwakeTone(awake.hasToneInEverySecond(), "440 Hz did not reach all three awake baseline seconds")
                    Log.i("RecorderLockTest", "TONE_BASELINE_VERIFIED: 440 Hz captured throughout the three awake baseline seconds")
                }
                asleep.verifyFrames()
                if (awake != null) assertTrue("The verified awake 440 Hz tone disappeared while locked", asleep.hasToneInEverySecond())
            } finally {
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }

    private class ToneWindow(private val label: String, private val startUs: Long, seconds: Int) {
        private var firstPts: Long? = null
        private var rate = 0
        private val frames = IntArray(seconds)
        private val peak = IntArray(seconds)
        private val toneCycles = IntArray(seconds)
        private var belowThreshold = false

        fun add(pts: Long, sampleRate: Int, pcm: ShortBuffer) {
            if (firstPts == null) firstPts = pts
            rate = sampleRate
            val bufferStart = pts - requireNotNull(firstPts) - startUs
            for (index in 0 until pcm.remaining()) {
                val relative = bufferStart + 1_000_000L * index / sampleRate
                if (relative < 0 || relative >= frames.size * 1_000_000L) continue
                val second = (relative / 1_000_000).toInt()
                val value = pcm.get(index).toInt()
                frames[second]++
                peak[second] = maxOf(peak[second], kotlin.math.abs(value))
                if (value < -64) belowThreshold = true
                if (value > 64 && belowThreshold) {
                    toneCycles[second]++
                    belowThreshold = false
                }
            }
        }

        fun logMeasurements() {
            Log.i("RecorderLockTest", "$label: $rate Hz PCM, samples ${frames.toList()}, peaks ${peak.toList()}, 440 Hz crossings ${toneCycles.toList()}")
        }

        fun verifyFrames() {
            assertTrue("No decoded audio", rate > 0)
            for (second in frames.indices) {
                assertTrue("Audio was missing in $label second $second (${frames[second]} samples)", frames[second] >= rate * 8 / 10)
            }
        }

        // A 440 Hz fixture crosses the positive threshold about 440 times per second.
        fun hasToneInEverySecond(): Boolean = toneCycles.all { it in 350..530 }
    }
}
