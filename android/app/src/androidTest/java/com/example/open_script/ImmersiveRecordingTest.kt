package com.example.open_script

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.open_script.media.CaptureResult
import com.example.open_script.media.CaptureStatus
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Real recording without editing drafts or adding a test take to the user's library. */
@RunWith(AndroidJUnit4::class)
class ImmersiveRecordingTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
    ).around(compose)

    @Test(timeout = 60_000L)
    fun recordingFadesControlsWithoutMovingPreviewOrStopAcrossRotation() {
        val activity = compose.activity
        val previousOrientation = compose.runOnUiThread { activity.requestedOrientation }
        val model = compose.runOnUiThread { ViewModelProvider(activity)[StudioViewModel::class.java] }
        val camera = model.camera
        val deadline = SystemClock.elapsedRealtime() + 48_000L
        val file = File(activity.cacheDir, "immersive-recording-test-${UUID.randomUUID()}.mp4")
        val screenshotDirectory = File(activity.cacheDir, "immersive-test-captures/${UUID.randomUUID()}")
        val finished = AtomicReference<CaptureResult?>(null)
        val callbacks = AtomicInteger(0)
        val stopEvents = AtomicInteger(0)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stopEvents.incrementAndGet()
        }

        try {
            compose.runOnUiThread { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("Open the portrait studio preview.", 15_000L, deadline) {
                !model.state.value.loading && camera.state.value.ready &&
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            val libraryBefore = model.state.value.library
            val takesBefore = model.state.value.takes.map { it.id }
            compose.onNode(hasText("Editar roteiro") and hasClickAction()).assertIsDisplayed()
            compose.onNode(hasText("Conta") and hasClickAction()).assertIsDisplayed()
            val portraitRecordBounds = controlBounds("Gravar vídeo")
            val portraitPreview = previewGeometry()
            val portraitInsets = visibleInsets()
            assertPreviewCoversWindow()
            compose.runOnUiThread { activity.lifecycle.addObserver(lifecycleObserver) }

            // Drive finite visibility transitions explicitly. With the test clock stopped,
            // the prompt's continuous withFrameNanos loop cannot block semantics queries.
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                camera.start(file, includeAudio = false) {
                    callbacks.incrementAndGet()
                    finished.set(it)
                }
            }
            await("CameraX must acknowledge the silent test recording.", 8_000L, deadline) {
                camera.state.value.status == CaptureStatus.RECORDING || finished.get() != null
            }
            assertEquals(CaptureStatus.RECORDING, camera.state.value.status)
            settleVisibilityTransition()
            assertRecordingChromeHidden(libraryBefore.draft.content, libraryBefore.settings.showPrompt)
            assertEquals("Stop must occupy exactly the original record button's bounds.",
                portraitRecordBounds, controlBounds("Encerrar gravação"))
            assertEquals("Fading controls cannot resize, move or recreate the native camera view.", portraitPreview, previewGeometry())
            assertPreviewCoversWindow()
            assertEquals("Recording must not hide system bars or change their insets.", portraitInsets, visibleInsets())
            compose.activityRule.scenario.onActivity { assertSame(activity, it) }
            assertEquals("Fading controls must not stop the Activity.", 0, stopEvents.get())
            saveScreenshot(screenshotDirectory, "recording-portrait.png")

            val durationBeforeRotation = camera.state.value.durationMs
            compose.runOnUiThread { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("The recording preview must rotate without finalizing the take.", 8_000L, deadline) {
                finished.get() != null || activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            compose.activityRule.scenario.onActivity { assertSame(activity, it) }
            assertNull("Rotation must preserve the active take.", finished.get())
            assertEquals(CaptureStatus.RECORDING, camera.state.value.status)
            settleVisibilityTransition()
            awaitStablePreview(deadline)
            assertRecordingChromeHidden(libraryBefore.draft.content, libraryBefore.settings.showPrompt)
            assertPreviewCoversWindow()
            assertEquals("Rotation handled by configChanges cannot stop the active Activity.", 0, stopEvents.get())
            saveScreenshot(screenshotDirectory, "recording-landscape.png")
            await("Keep recording enough media to finalize a valid take.", 6_000L, deadline) {
                camera.state.value.durationMs >= maxOf(1_200L, durationBeforeRotation + 300L)
            }
            val landscapeStopBounds = controlBounds("Encerrar gravação")
            val landscapePreview = previewGeometry()
            val landscapeInsets = visibleInsets()

            // Exercise the sole remaining UI action rather than calling camera.stop().
            compose.onNodeWithContentDescription("Encerrar gravação")
                .assertIsDisplayed().assertHasClickAction().performClick()
            await("The visible stop action must finalize CameraX.", 12_000L, deadline) {
                finished.get() != null && camera.state.value.status == CaptureStatus.IDLE
            }
            val result = requireNotNull(finished.get())
            assertEquals("The UI must stop the same take exactly once.", 1, callbacks.get())
            assertNull(result.error)
            assertTrue(result.durationMs > 0L && result.width > 0 && result.height > 0)
            assertTrue(file.isFile && file.length() > 0L)
            settleVisibilityTransition()
            awaitStablePreview(deadline)
            compose.onNode(hasText("Ajustes") and hasClickAction()).assertIsDisplayed()
            compose.onNode(hasText("Editar roteiro") and hasClickAction()).assertIsDisplayed()
            compose.onNode(hasText("Conta") and hasClickAction()).assertIsDisplayed()
            compose.onNodeWithContentDescription("Gravar vídeo").assertIsDisplayed()
            compose.onAllNodes(hasContentDescription("Encerrar gravação")).assertCountEquals(0)
            assertEquals("Record must return in exactly the same position as Stop.", landscapeStopBounds, controlBounds("Gravar vídeo"))
            assertEquals("Returning the controls cannot resize or move the landscape preview.", landscapePreview, previewGeometry())
            assertPreviewCoversWindow()
            assertEquals("Returning controls must leave system bar visibility and insets unchanged.", landscapeInsets, visibleInsets())
            compose.activityRule.scenario.onActivity { assertSame(activity, it) }
            assertEquals("The same Activity must remain started throughout recording and restoration.", 0, stopEvents.get())
            saveScreenshot(screenshotDirectory, "studio-returned.png")
            assertEquals("A direct camera test must not add a library take.", takesBefore, model.state.value.takes.map { it.id })
            assertEquals("Recording controls must not change stored scripts or settings.", libraryBefore, model.state.value.library)
        } finally {
            compose.runOnUiThread { activity.lifecycle.removeObserver(lifecycleObserver); camera.stop() }
            runCatching { compose.waitUntil(timeoutMillis = 6_000L) { camera.state.value.status == CaptureStatus.IDLE } }
            if (camera.state.value.status == CaptureStatus.IDLE) file.delete()
            compose.runOnUiThread { activity.requestedOrientation = previousOrientation }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun settleVisibilityTransition() {
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
    }

    private fun controlBounds(description: String): Rect =
        compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    private data class PreviewGeometry(val view: PreviewView, val width: Int, val height: Int, val x: Int, val y: Int)

    private fun previewGeometry(): PreviewGeometry = compose.runOnUiThread {
        val view = requireNotNull(findPreview(compose.activity.window.decorView)) { "The native PreviewView must remain attached." }
        assertTrue("The native preview must have a measured size.", view.width > 0 && view.height > 0)
        val location = IntArray(2)
        view.getLocationInWindow(location)
        PreviewGeometry(view, view.width, view.height, location[0], location[1])
    }

    private fun assertPreviewCoversWindow() = compose.runOnUiThread {
        val decor = compose.activity.window.decorView
        val preview = requireNotNull(findPreview(decor)) { "The native PreviewView must remain attached." }
        assertTrue("The window must have a measured size.", decor.width > 0 && decor.height > 0)
        val windowLocation = IntArray(2)
        val previewLocation = IntArray(2)
        decor.getLocationInWindow(windowLocation)
        preview.getLocationInWindow(previewLocation)
        assertEquals("The camera preview must span the entire window width.", decor.width.toDouble(), preview.width.toDouble(), 1.0)
        assertEquals("The camera preview must span the entire window height.", decor.height.toDouble(), preview.height.toDouble(), 1.0)
        assertEquals("The camera preview must align with the window's left edge.", windowLocation[0].toDouble(), previewLocation[0].toDouble(), 1.0)
        assertEquals("The camera preview must align with the window's top edge.", windowLocation[1].toDouble(), previewLocation[1].toDouble(), 1.0)
    }

    private fun findPreview(view: View): PreviewView? {
        if (view is PreviewView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPreview(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private data class VisibleInsets(
        val statusVisible: Boolean, val navigationVisible: Boolean,
        val statusInsets: Insets, val navigationInsets: Insets,
    )

    private fun visibleInsets(): VisibleInsets = compose.runOnUiThread {
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
        VisibleInsets(
            insets.isVisible(WindowInsetsCompat.Type.statusBars()),
            insets.isVisible(WindowInsetsCompat.Type.navigationBars()),
            insets.getInsets(WindowInsetsCompat.Type.statusBars()),
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()),
        )
    }

    private fun awaitStablePreview(deadline: Long) {
        var last = previewGeometry()
        var stableSince = SystemClock.elapsedRealtime()
        await("Wait for the rotated viewport to finish laying out.", 3_000L, deadline) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val current = previewGeometry()
            if (current != last) {
                last = current
                stableSince = SystemClock.elapsedRealtime()
            }
            SystemClock.elapsedRealtime() - stableSince >= 200L
        }
    }

    /** Retained only in this test's UUID directory so the host can inspect the result. */
    private fun saveScreenshot(directory: File, name: String) {
        try {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            try {
                check(directory.isDirectory || directory.mkdirs())
                val destination = File(directory, name)
                destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                Log.i("ImmersiveRecordingTest", "SCREENSHOT ${destination.absolutePath}")
            } finally {
                bitmap.recycle()
            }
        } catch (failure: Exception) {
            Log.w("ImmersiveRecordingTest", "Optional screenshot unavailable: $name", failure)
        }
    }

    private fun assertRecordingChromeHidden(promptContent: String, showPrompt: Boolean) {
        // Prompt text belongs on screen, including a user's script that happens to use
        // one of these words. Exclude the complete prompt node from chrome assertions.
        val completePrompt = hasText(promptContent, substring = false)
        if (showPrompt && promptContent.isNotBlank()) {
            compose.onNode(completePrompt).assertExists().assertIsDisplayed()
        }
        val hiddenLabels = listOf(
            "Ajustes", "Editar roteiro", "Ensaiar", "Trocar câmera", "Trocar\ncâmera",
            "Pausar", "Retomar", "Velocidade", "Estúdio", "Roteiro", "Roteiros", "Takes", "Conta", "open", "script",
        )
        for (label in hiddenLabels) {
            var matcher = hasText(label) or hasContentDescription(label)
            if (showPrompt && promptContent.isNotBlank()) matcher = matcher and !completePrompt
            compose.onAllNodes(matcher).assertCountEquals(0)
        }
        var timer = hasText("Gravando", substring = true) or hasText("Em pausa", substring = true)
        if (showPrompt && promptContent.isNotBlank()) timer = timer and !completePrompt
        compose.onAllNodes(timer).assertCountEquals(0)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithContentDescription("Encerrar gravação").assertIsDisplayed().assertHasClickAction()
    }

    private fun await(description: String, maximumMs: Long, deadline: Long, condition: () -> Boolean) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        assertTrue("The recording-controls test exceeded its deadline: $description", remaining > 0L)
        try {
            compose.waitUntil(timeoutMillis = minOf(maximumMs, remaining), condition = condition)
        } catch (failure: Exception) {
            throw AssertionError(description, failure)
        }
    }
}
