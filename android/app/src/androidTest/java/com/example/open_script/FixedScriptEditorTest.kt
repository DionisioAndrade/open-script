package com.example.open_script

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.open_script.data.ScriptDocument
import com.example.open_script.ui.theme.Open_scriptTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The isolated form never opens a real library, account or camera. */
@RunWith(AndroidJUnit4::class)
class FixedScriptEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 30_000L)
    fun onePlainTextFieldKeepsExactEditsAndUsesTheCameraWithoutResettingContent() {
        val initial = ScriptDocument(title = "Título legado invisível", content = "Texto original do roteiro.\n")
        val renderedDraft = AtomicReference(initial)
        val latestCallback = AtomicReference<String?>(null)
        val edits = AtomicInteger(0)
        val cameraActions = AtomicInteger(0)
        compose.setContent {
            var draft by remember { mutableStateOf(initial) }
            // Read in this composition scope, rather than only inside SideEffect's lambda.
            // Otherwise an inner Surface recomposition can update the form without rerunning it.
            val currentDraft = draft
            SideEffect { renderedDraft.set(currentDraft) }
            Open_scriptTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ScriptPage(
                        draft = currentDraft,
                        onChange = { text ->
                            latestCallback.set(text)
                            edits.incrementAndGet()
                            draft = draft.copy(content = text)
                        },
                        onRecord = { cameraActions.incrementAndGet() },
                        onUpload = null,
                        busy = false,
                    )
                }
            }
        }

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        for (label in listOf("Título", initial.title, "Salvar roteiro", "Novo")) {
            compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label, substring = true)).assertCountEquals(0)
        }
        val editor = compose.onNode(hasSetTextAction()).assertIsDisplayed()
        assertEquals(initial.content, editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val replacement = "  Olá, ação e câmera!\n\nSegunda linha com espaços  \nÚltima linha — çãõ 🎥\n\n"

        editor.performTextReplacement(replacement)
        compose.waitForIdle()

        assertTrue("The sole text field must deliver the content change.", edits.get() > 0)
        assertEquals("The callback must retain all spaces, accents and trailing newlines.", replacement, latestCallback.get())
        awaitRenderedText(replacement, renderedDraft)
        assertEquals(replacement, compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(replacement, renderedDraft.get().content)
        assertEquals("The internal API title must stay unchanged while editing the text.", initial.title, renderedDraft.get().title)
        assertEquals(initial.id, renderedDraft.get().id)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        val editsBeforeCamera = edits.get()

        compose.onNodeWithText("Usar na câmera").assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals(1, cameraActions.get())
        assertEquals("Using the camera must not clear or rewrite the fixed text.", editsBeforeCamera, edits.get())
        assertEquals(replacement, latestCallback.get())
        assertEquals(replacement, renderedDraft.get().content)
        assertEquals(replacement, compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(initial.title, renderedDraft.get().title)
        saveOptionalScreenshot()
    }

    private fun awaitRenderedText(expected: String, renderedDraft: AtomicReference<ScriptDocument>) {
        try {
            compose.waitUntil(timeoutMillis = 5_000L) {
                // Material text input and semantic nodes can settle on the frame following SetText.
                compose.mainClock.advanceTimeByFrame()
                val editable = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
                    .config[SemanticsProperties.EditableText].text
                renderedDraft.get().content == expected && editable == expected
            }
        } catch (failure: Exception) {
            val editable = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text
            throw AssertionError("Text did not settle: rendered draft=${renderedDraft.get().content}, EditableText=$editable", failure)
        }
    }

    /** This test owns only the UUID screenshot directory retained for visual inspection. */
    private fun saveOptionalScreenshot() {
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(instrumentation.targetContext.cacheDir, "fixed-script-editor-test/${UUID.randomUUID()}")
            val image = instrumentation.uiAutomation.takeScreenshot() ?: return
            try {
                check(directory.mkdirs())
                val destination = File(directory, "single-script-editor.png")
                destination.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                Log.i("FixedScriptEditorTest", "SCREENSHOT ${destination.absolutePath}")
            } finally {
                image.recycle()
            }
        } catch (failure: Exception) {
            Log.w("FixedScriptEditorTest", "Optional isolated form screenshot unavailable.", failure)
        }
    }
}
