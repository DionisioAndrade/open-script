package com.example.open_script

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.open_script.media.CaptureResult
import com.example.open_script.media.CaptureStatus
import com.example.open_script.media.ExportProfile
import com.example.open_script.media.NativeExporter
import com.example.open_script.data.LocalLibrary
import com.example.open_script.data.RecordedTake
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/**
 * Real codecs/camera integration. Run on a camera-enabled emulator or device with
 * CAMERA and RECORD_AUDIO granted by the test rule. It does not edit drafts.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class NativeRecordingExportTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
    ).around(compose)

    @Test(timeout = 90_000L)
    fun recordsAudioVideoWithPauseThenExportsAndCancelsWithoutChangingOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("The test device must grant camera permission first.", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        assertEquals("The test device must grant microphone permission first.", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))

        val deadline = SystemClock.elapsedRealtime() + 80_000L
        val model = compose.runOnUiThread { ViewModelProvider(compose.activity)[StudioViewModel::class.java] }
        val camera = model.camera
        val privateDirectory = File(context.cacheDir, "native-recording-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = privateDirectory
            override fun getCacheDir(): File = File(privateDirectory, "cache").apply { mkdirs() }
        }
        val exporter = NativeExporter(isolatedContext)
        val local = LocalLibrary(isolatedContext)
        val original = local.newTakeFile("test")
        val outputToClean = AtomicReference<File?>(null)
        val recordingResult = AtomicReference<CaptureResult?>(null)
        val recordingCallbacks = AtomicInteger(0)

        try {
            await("The studio must finish loading and open its CameraX preview.", 12_000, deadline) {
                !model.state.value.loading && camera.state.value.ready
            }
            val libraryBefore = model.state.value.library
            val takeIdsBefore = model.state.value.takes.map { it.id }
            assertFalse("Each test needs a fresh isolated file.", original.exists())

            val recordingStartedAt = SystemClock.elapsedRealtime()
            compose.runOnUiThread {
                camera.start(original, includeAudio = true) { result ->
                    recordingCallbacks.incrementAndGet()
                    recordingResult.set(result)
                }
            }
            await("CameraX did not start recording: ${camera.state.value.error}", 8_000, deadline) {
                camera.state.value.status == CaptureStatus.RECORDING || recordingResult.get() != null
            }
            assertEquals("CameraX must acknowledge Start before pause/resume.", CaptureStatus.RECORDING, camera.state.value.status)
            assertTrue("This integration test records an actual audio track.", camera.state.value.hasAudio)

            await("Record two seconds of media before pausing.", 7_000, deadline) { camera.state.value.durationMs >= 2_000L }
            compose.runOnUiThread { camera.pause() }
            await("CameraX must acknowledge Pause.", 3_000, deadline) { camera.state.value.status == CaptureStatus.PAUSED }
            val pausedMediaDuration = camera.state.value.durationMs
            val pauseStartedAt = SystemClock.elapsedRealtime()
            await("Hold the recording paused for one real second.", 2_000, deadline) {
                SystemClock.elapsedRealtime() - pauseStartedAt >= 1_000L
            }
            val pauseWallDuration = SystemClock.elapsedRealtime() - pauseStartedAt
            assertTrue("Paused wall time must not extend the recorded media clock.",
                abs(camera.state.value.durationMs - pausedMediaDuration) <= 150L)

            compose.runOnUiThread { camera.resume() }
            await("CameraX must acknowledge Resume.", 3_000, deadline) { camera.state.value.status == CaptureStatus.RECORDING }
            await("Record a further two seconds after resuming.", 7_000, deadline) {
                camera.state.value.durationMs >= pausedMediaDuration + 2_000L
            }
            val recordedAtStop = camera.state.value.durationMs
            val wallDuration = SystemClock.elapsedRealtime() - recordingStartedAt
            compose.runOnUiThread { camera.stop() }
            await("CameraX must finalize the original file.", 12_000, deadline) { recordingResult.get() != null }
            val captured = requireNotNull(recordingResult.get())
            assertEquals("A take has exactly one final callback.", 1, recordingCallbacks.get())
            assertEquals(CaptureStatus.IDLE, camera.state.value.status)
            assertNull("Capture unexpectedly failed: ${captured.error}", captured.error)
            assertTrue(captured.file.isFile && captured.file.length() > 0L)
            assertTrue(captured.width > 0 && captured.height > 0)
            assertTrue("The portrait activity must produce portrait display dimensions.", captured.height >= captured.width)
            assertTrue("Two two-second segments should produce approximately four seconds of media.",
                captured.durationMs in 3_800L..6_000L)
            assertTrue("Final duration should follow CameraX media timestamps, excluding the pause.",
                abs(captured.durationMs - recordedAtStop) < 700L)
            assertTrue("The one-second pause must be absent from MP4 playback duration.",
                wallDuration - captured.durationMs >= pauseWallDuration - 500L)

            val input = inspectMp4(captured.file)
            assertTrue(input.videoMime.startsWith("video/"))
            assertTrue(input.audioMime.startsWith("audio/"))
            assertTrue(input.hasAudioSamples)
            assertEquals(captured.width, input.width)
            assertEquals(captured.height, input.height)
            assertTrue(abs(input.durationMs - captured.durationMs) < 100L)
            val originalHash = sha256(captured.file)
            val take = RecordedTake(id = original.nameWithoutExtension, title = "Teste isolado", path = original.path,
                durationMs = captured.durationMs, width = captured.width, height = captured.height)
            local.addTake("test", take)
            assertEquals("A completed take must survive reopening the local index.", listOf(take.copy(path = original.canonicalPath)), LocalLibrary(isolatedContext).takes("test"))

            val exportFinished = AtomicBoolean(false)
            val exportResult = AtomicReference<File?>(null)
            val exportCallbacks = AtomicInteger(0)
            val square = ExportProfile(
                id = "instrumentation-square", label = "Teste quadrado", aspect = "1:1",
                resolution = 360, quality = "compact", frameRate = 24,
            )
            compose.runOnUiThread {
                exporter.export(captured.file, square) { output ->
                    exportCallbacks.incrementAndGet()
                    exportResult.set(output)
                    outputToClean.set(output)
                    exportFinished.set(true)
                }
            }
            await("Media3 must finish the square MP4 export.", 25_000, deadline) { exportFinished.get() }
            assertEquals(1, exportCallbacks.get())
            assertNull("Media3 export failed: ${exporter.state.value.error}", exporter.state.value.error)
            assertNotNull("Media3 must return its finished output.", exportResult.get())
            val exported = requireNotNull(exportResult.get())
            assertTrue(exported.canonicalFile != captured.file.canonicalFile)
            val output = inspectMp4(exported)
            assertEquals("video/avc", output.videoMime)
            assertEquals("audio/mp4a-latm", output.audioMime)
            assertTrue(output.hasAudioSamples)
            assertEquals(360, output.width)
            assertEquals(360, output.height)
            assertTrue("Export must retain the complete recording.", abs(output.durationMs - input.durationMs) < 500L)
            // A slower source must not have invented frames; 24 fps is the requested ceiling.
            assertTrue("The 24 fps profile must cap encoded video samples; observed ${output.framesPerSecond} fps.",
                output.framesPerSecond > 0.0 && output.framesPerSecond <= 25.5)
            assertArrayEquals("Export must not modify the original MP4.", originalHash, sha256(captured.file))

            val cancelFinished = AtomicBoolean(false)
            val cancelCallbacks = AtomicInteger(0)
            val cancelResult = AtomicReference<File?>(null)
            compose.runOnUiThread {
                exporter.export(captured.file, square.copy(id = "instrumentation-cancel", resolution = 1080, quality = "high")) {
                    cancelCallbacks.incrementAndGet()
                    cancelResult.set(it)
                    cancelFinished.set(true)
                }
                assertTrue("The new export must enter its running state.", exporter.state.value.running)
                // Cancel in the same main-loop turn: deterministic even on very fast codecs.
                exporter.cancel()
            }
            await("Cancelling must complete its callback.", 2_000, deadline) { cancelFinished.get() }
            assertEquals(1, cancelCallbacks.get())
            assertNull(cancelResult.get())
            assertFalse(exporter.state.value.running)
            assertTrue(captured.file.isFile)
            assertArrayEquals("Cancelling must preserve the original byte for byte.", originalHash, sha256(captured.file))
            assertEquals("The test must not create a library take.", takeIdsBefore, model.state.value.takes.map { it.id })
            assertEquals("The test must not edit or save the user's scripts.", libraryBefore, model.state.value.library)
        } finally {
            compose.runOnUiThread { camera.stop(); exporter.clear() }
            // Never remove an MP4 that CameraX is still finalizing.
            runCatching { compose.waitUntil(timeoutMillis = 6_000L) { camera.state.value.status == CaptureStatus.IDLE } }
            if (camera.state.value.status == CaptureStatus.IDLE) original.delete()
            outputToClean.get()?.delete()
            if (camera.state.value.status == CaptureStatus.IDLE && privateDirectory.canonicalFile.parentFile == context.cacheDir.canonicalFile) {
                privateDirectory.deleteRecursively()
            }
        }
    }

    private fun await(description: String, maximumMs: Long, deadline: Long, condition: () -> Boolean) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        assertTrue("The integration test exceeded its total deadline: $description", remaining > 0L)
        try {
            compose.waitUntil(timeoutMillis = minOf(maximumMs, remaining), condition = condition)
        } catch (failure: Exception) {
            throw AssertionError(description, failure)
        }
    }

    private data class Mp4Probe(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val videoMime: String,
        val audioMime: String,
        val hasAudioSamples: Boolean,
        val framesPerSecond: Double,
    )

    private fun inspectMp4(file: File): Mp4Probe {
        assertTrue("An MP4 must be a non-empty regular file.", file.isFile && file.length() > 12L)
        val header = file.inputStream().use { stream -> ByteArray(12).also { assertEquals(12, stream.read(it)) } }
        assertEquals("MP4 must contain its file-type box.", "ftyp", String(header, 4, 4, Charsets.US_ASCII))
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(file.absolutePath)
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            val duration = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            val width = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt()
            val height = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)).toInt()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            assertTrue(duration > 0L && width > 0 && height > 0)
            extractor.setDataSource(file.absolutePath)
            val videoIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val audioIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            assertNotNull("The MP4 must contain a video track.", videoIndex)
            assertNotNull("The MP4 must contain an audio track.", audioIndex)
            val video = requireNotNull(videoIndex)
            val audio = requireNotNull(audioIndex)
            val videoMime = requireNotNull(extractor.getTrackFormat(video).getString(MediaFormat.KEY_MIME))
            val audioMime = requireNotNull(extractor.getTrackFormat(audio).getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(video)
            var count = 0
            var first = Long.MAX_VALUE
            var last = Long.MIN_VALUE
            while (extractor.sampleTime >= 0L) {
                first = minOf(first, extractor.sampleTime)
                last = maxOf(last, extractor.sampleTime)
                count++
                if (!extractor.advance()) break
            }
            assertTrue("Video track must contain playable samples.", count >= 2 && last > first)
            extractor.unselectTrack(video)
            extractor.selectTrack(audio)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val audioSamples = extractor.sampleTime >= 0L
            val portraitRotation = rotation == 90 || rotation == 270
            return Mp4Probe(duration, if (portraitRotation) height else width, if (portraitRotation) width else height,
                videoMime, audioMime, audioSamples, (count - 1) * 1_000_000.0 / (last - first))
        } finally {
            runCatching { extractor.release() }
            runCatching { retriever.release() }
        }
    }

    private fun sha256(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                hash.update(buffer, 0, read)
            }
        }
        return hash.digest()
    }
}
