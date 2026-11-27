package com.woundcopilot.android

import android.Manifest
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
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator only. The runner supplies a synthetic tone to the virtual microphone. */
@RunWith(AndroidJUnit4::class)
class NotesScreenOffRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun keepsRecordingSoundWhileTheDisplayIsOff() {
        assertTrue("A disposable emulator is required", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertTrue("Use the Notes production recorder", BuildConfig.NOTES_ONLY && !BuildConfig.CRASH_SAFE_RECORDING)
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
                // Establish that the fixture reaches this exact recorder before putting Android to sleep.
                Log.i("NotesLockTest", "Recorder ready for synthetic tone")
                await("No synthetic microphone signal reached the awake foreground recorder") {
                    RecordingService.state.value.amplitude > 0.05f
                }
                val baselineUs = (SystemClock.elapsedRealtime() - start + 1_000) * 1_000
                SystemClock.sleep(5_000)
                shell("input keyevent KEYCODE_SLEEP")
                await("Emulator display never turned off") { !power.isInteractive }
                val offAt = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - offAt < 12_000) {
                    assertFalse("Display woke before the measurement ended", power.isInteractive)
                    val state = RecordingService.state.value
                    assertTrue("Recording stopped or paused while the display was off", state.recording && !state.paused)
                    assertEquals("Recorder switched files", path, state.filePath)
                    SystemClock.sleep(200)
                }
                val offUntil = SystemClock.elapsedRealtime()
                wakeScreen()
                assertTrue("Recording stopped on wake", RecordingService.state.value.recording)
                RecordingService.stop(context)
                await("Stop did not finalize the recording") { !RecordingService.state.value.recording }
                assertNull(RecordingService.state.value.error)
                assertEquals(path, RecordingService.state.value.filePath)
                // Trim a second at either boundary so button events and encoder startup cannot count.
                val fromUs = (offAt - start + 1_000) * 1_000
                val seconds = ((offUntil - offAt - 2_000) / 1_000).toInt()
                assertTrue("Screen-off measurement was too short", seconds >= 10)
                checkRecordedSound(File(path), baselineUs, fromUs, seconds)
                Log.i("NotesLockTest", "Decoded the synthetic microphone tone in all $seconds measured screen-off seconds.")
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

    private fun wakeScreen() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        await("Emulator display did not wake") { context.getSystemService(PowerManager::class.java).isInteractive }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        assertTrue(message, condition())
    }

    private fun checkRecordedSound(file: File, baselineUs: Long, fromUs: Long, seconds: Int) {
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
                val awake = ToneWindow("screen-on baseline", baselineUs, 3)
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
                            awake.add(info.presentationTimeUs, sampleRate, samples)
                            asleep.add(info.presentationTimeUs, sampleRate, samples)
                        }
                        outputFinished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
                assertTrue("AAC decoding did not complete", outputFinished)
                awake.verify()
                Log.i("NotesLockTest", "Decoded the synthetic tone in all 3 screen-on baseline seconds.")
                asleep.verify()
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
                if (value < -64) belowThreshold = true
                if (value > 64 && belowThreshold) {
                    toneCycles[second]++
                    belowThreshold = false
                }
            }
        }

        fun verify() {
            assertTrue("No decoded audio", rate > 0)
            for (second in frames.indices) {
                assertTrue("Audio was missing in $label second $second (${frames[second]} samples)", frames[second] >= rate * 8 / 10)
                // A 440 Hz fixture crosses the positive threshold about 440 times per second.
                assertTrue("The 440 Hz signal was missing in $label second $second (${toneCycles[second]} cycles)",
                    toneCycles[second] in 350..530)
            }
        }
    }
}
