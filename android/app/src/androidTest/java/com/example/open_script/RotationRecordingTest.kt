package com.example.open_script

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.open_script.media.CaptureResult
import com.example.open_script.media.CaptureStatus
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Exercises real Activity config changes, CameraX capture continuity and output rotation. */
@RunWith(AndroidJUnit4::class)
class RotationRecordingTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
    ).around(compose)

    @Test(timeout = 75_000L)
    fun rotatingDuringTakeKeepsOnePortraitFileThenNextTakeUsesLandscape() {
        val activity = compose.activity
        val previousOrientation = compose.runOnUiThread { activity.requestedOrientation }
        val model = compose.runOnUiThread { ViewModelProvider(activity)[StudioViewModel::class.java] }
        val camera = model.camera
        val deadline = SystemClock.elapsedRealtime() + 65_000L
        val first = File(activity.cacheDir, "rotation-test-${UUID.randomUUID()}.mp4")
        val second = File(activity.cacheDir, "rotation-test-${UUID.randomUUID()}.mp4")
        val firstResult = AtomicReference<CaptureResult?>(null)
        val secondResult = AtomicReference<CaptureResult?>(null)
        val firstCallbacks = AtomicInteger(0)
        val secondCallbacks = AtomicInteger(0)
        val stopEvents = AtomicInteger(0)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stopEvents.incrementAndGet()
        }

        try {
            compose.runOnUiThread { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("Open the portrait preview before starting.", 15_000L, deadline) {
                !model.state.value.loading && camera.state.value.ready &&
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            compose.runOnUiThread { activity.lifecycle.addObserver(lifecycleObserver) }
            val libraryBefore = model.state.value.library
            val takeIdsBefore = model.state.value.takes.map { it.id }
            assertFalse(first.exists())
            assertFalse(second.exists())
            compose.runOnUiThread {
                camera.start(first, includeAudio = false) {
                    firstCallbacks.incrementAndGet()
                    firstResult.set(it)
                }
            }
            await("CameraX must start the portrait take.", 8_000L, deadline) {
                camera.state.value.status == CaptureStatus.RECORDING || firstResult.get() != null
            }
            assertEquals(CaptureStatus.RECORDING, camera.state.value.status)
            await("Record two seconds before the screen rotates.", 7_000L, deadline) { camera.state.value.durationMs >= 2_000L }
            val durationBeforeRotation = camera.state.value.durationMs
            compose.runOnUiThread { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("The screen must relayout to landscape while the take remains active.", 8_000L, deadline) {
                firstResult.get() != null ||
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            compose.activityRule.scenario.onActivity { current ->
                assertSame("Rotation must keep the same Activity and native preview binding.", activity, current)
            }
            assertNull("Rotating the screen must not finalize the active take.", firstResult.get())
            assertEquals("A handled configuration change must not stop the camera lifecycle.", 0, stopEvents.get())
            assertEquals(CaptureStatus.RECORDING, camera.state.value.status)
            assertTrue("Recorded time cannot reset on screen rotation.", camera.state.value.durationMs >= durationBeforeRotation)
            await("Frames must continue arriving after the relayout.", 7_000L, deadline) {
                camera.state.value.durationMs >= durationBeforeRotation + 1_500L
            }
            compose.runOnUiThread { camera.stop() }
            await("Finalize the original portrait take.", 10_000L, deadline) { firstResult.get() != null }
            val portrait = requireNotNull(firstResult.get())
            assertEquals(1, firstCallbacks.get())
            assertNull(portrait.error)
            assertTrue("The same file must retain frames from both sides of the rotation.", portrait.durationMs >= 3_400L)
            assertTrue("An in-progress portrait take keeps its starting orientation.", portrait.height > portrait.width)
            assertVideoMetadata(portrait)

            await("The next take must be ready in landscape.", 8_000L, deadline) {
                camera.state.value.ready && camera.state.value.status == CaptureStatus.IDLE
            }
            compose.runOnUiThread {
                camera.start(second, includeAudio = false) {
                    secondCallbacks.incrementAndGet()
                    secondResult.set(it)
                }
            }
            await("Start a new take using the latest landscape display rotation.", 8_000L, deadline) {
                camera.state.value.status == CaptureStatus.RECORDING || secondResult.get() != null
            }
            assertEquals(CaptureStatus.RECORDING, camera.state.value.status)
            await("Record enough landscape frames to finalize.", 6_000L, deadline) { camera.state.value.durationMs >= 1_200L }
            compose.runOnUiThread { camera.stop() }
            await("Finalize the landscape take.", 10_000L, deadline) { secondResult.get() != null }
            val landscape = requireNotNull(secondResult.get())
            assertEquals(1, secondCallbacks.get())
            assertEquals(1, firstCallbacks.get())
            assertNull(landscape.error)
            assertTrue("A subsequent take adopts the new landscape orientation.", landscape.width > landscape.height)
            assertVideoMetadata(landscape)
            assertEquals(takeIdsBefore, model.state.value.takes.map { it.id })
            assertEquals(libraryBefore, model.state.value.library)
        } finally {
            compose.runOnUiThread {
                activity.lifecycle.removeObserver(lifecycleObserver)
                camera.stop()
            }
            runCatching { compose.waitUntil(timeoutMillis = 6_000L) { camera.state.value.status == CaptureStatus.IDLE } }
            if (camera.state.value.status == CaptureStatus.IDLE) {
                first.delete()
                second.delete()
            }
            compose.runOnUiThread { activity.requestedOrientation = previousOrientation }
        }
    }

    private fun await(description: String, maximumMs: Long, deadline: Long, condition: () -> Boolean) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        assertTrue("The rotation test exceeded its deadline: $description", remaining > 0L)
        try {
            compose.waitUntil(timeoutMillis = minOf(maximumMs, remaining), condition = condition)
        } catch (failure: Exception) {
            throw AssertionError(description, failure)
        }
    }

    private fun assertVideoMetadata(result: CaptureResult) {
        assertTrue(result.file.isFile && result.file.length() > 0L)
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(result.file.absolutePath)
            assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            val width = requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt()
            val height = requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)).toInt()
            val rotation = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            assertEquals(result.width, if (rotation == 90 || rotation == 270) height else width)
            assertEquals(result.height, if (rotation == 90 || rotation == 270) width else height)
        } finally {
            reader.release()
        }
    }
}
