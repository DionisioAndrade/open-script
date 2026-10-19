package com.example.open_script.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual private JSON/AtomicFile persistence without opening or changing user data. */
@RunWith(AndroidJUnit4::class)
class FixedScriptPersistenceTest {
    private lateinit var directory: File
    private lateinit var cacheDirectory: File
    private lateinit var isolatedContext: Context
    private lateinit var library: LocalLibrary
    private val owner = "fixed-script-account"

    @Before fun createIsolatedStorage() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDirectory = target.cacheDir.canonicalFile
        directory = File(cacheDirectory, "fixed-script-persistence-${UUID.randomUUID()}").apply { check(mkdirs()) }
        isolatedContext = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        library = LocalLibrary(isolatedContext)
    }

    @After fun removeOnlyThisTestsStorage() {
        if (::directory.isInitialized) {
            check(directory.canonicalFile.parentFile == cacheDirectory)
            check(directory.name.startsWith("fixed-script-persistence-"))
            assertTrue("The isolated test files must be removable.", directory.deleteRecursively())
        }
    }

    @Test fun fixedTextSurvivesReopeningWithAccentsWhitespaceAndTrailingNewlinesUnchanged() {
        val content = "\n  Olá, ação! Çãõ — câmera 🎥\n\nSegunda linha\r\nÚltima linha com espaço  \n\n"
        val snapshot = LibrarySnapshot(draft = ScriptDocument(
            title = "Título interno para a API", content = content, updatedAt = 1_700_000_000_001L,
        ))

        library.save(owner, snapshot)
        val reopened = LocalLibrary(isolatedContext).load(owner)

        assertEquals(snapshot, reopened)
        assertArrayEquals("A stored script is plain text: line endings and whitespace cannot be normalized.",
            content.toByteArray(Charsets.UTF_8), reopened.draft.content.toByteArray(Charsets.UTF_8))
    }

    @Test fun aVisitorReopensTheLastEditedTextInsteadOfThePreviousVersion() {
        val first = LibrarySnapshot(draft = ScriptDocument(
            title = "Roteiro", content = "Texto anterior.\n", updatedAt = 1_700_000_000_010L,
        ))
        library.save(null, first)
        val editorSession = LocalLibrary(isolatedContext)
        val editing = editorSession.load(null)
        val latest = editing.copy(draft = editing.draft.copy(
            content = "Texto que usei por último.\nContinuação com acentuação.\n\n",
            updatedAt = 1_700_000_000_020L,
        ))

        editorSession.save(null, latest)
        val reopened = LocalLibrary(isolatedContext).load(null)

        assertEquals(latest.draft, reopened.draft)
        assertEquals("Editing the fixed script must preserve its identity.", first.draft.id, reopened.draft.id)
    }

    @Test fun editingFixedTextKeepsLegacyScriptsSettingsAndAnotherOwnersFileIntact() {
        val legacyScripts = listOf(
            ScriptDocument(title = "Roteiro legado A", content = "Não substituir.\n", updatedAt = 1_700_000_000_100L),
            ScriptDocument(title = "Roteiro legado da conta", content = "Texto remoto preservado.",
                updatedAt = 1_700_000_000_200L, remoteId = UUID.randomUUID().toString(), revision = 7),
        )
        val settings = PromptSettings(language = "es", speed = 59.5f, font = "mono", size = 44f,
            autoSize = false, opacity = 25f, color = "#ab12cd", showPrompt = true, mirror = false)
        val initial = LibrarySnapshot(
            draft = ScriptDocument(title = "Interno", content = "Conteúdo atual."),
            scripts = legacyScripts, settings = settings,
        )
        val otherOwner = "another-fixed-script-account"
        val other = LibrarySnapshot(
            draft = ScriptDocument(title = "Outra conta", content = "Este texto pertence à outra conta.\n"),
            scripts = listOf(ScriptDocument(title = "Legado de outra conta", content = "Guardar também.")),
            settings = PromptSettings(language = "en", speed = 27f, opacity = 40f),
        )
        library.save(owner, initial)
        library.save(otherOwner, other)
        val otherFile = ownerFile(otherOwner)
        val otherBytes = otherFile.readBytes()
        val changed = LocalLibrary(isolatedContext).load(owner).let { snapshot ->
            snapshot.copy(draft = snapshot.draft.copy(content = "Somente este texto mudou.\n"))
        }

        LocalLibrary(isolatedContext).save(owner, changed)
        val reopened = LocalLibrary(isolatedContext)

        assertEquals(changed.draft, reopened.load(owner).draft)
        assertEquals(legacyScripts, reopened.load(owner).scripts)
        assertEquals(settings, reopened.load(owner).settings)
        assertEquals(other, reopened.load(otherOwner))
        assertArrayEquals("Saving one fixed script cannot rewrite another owner's data.", otherBytes, otherFile.readBytes())
    }

    @Test fun clearingTheFixedTextPersistsEmptyAndDoesNotRestoreAnOlderSavedScript() {
        val legacy = ScriptDocument(title = "Antigo", content = "Não recuperar automaticamente este texto.",
            updatedAt = 1_700_000_001_000L)
        val initial = LibrarySnapshot(
            draft = ScriptDocument(title = "Interno", content = "Texto que vou apagar.\n", updatedAt = 1_700_000_000_500L),
            scripts = listOf(legacy),
        )
        library.save(owner, initial)
        val loaded = LocalLibrary(isolatedContext).load(owner)
        val cleared = loaded.copy(draft = loaded.draft.copy(content = "", updatedAt = 1_700_000_002_000L))

        LocalLibrary(isolatedContext).save(owner, cleared)

        repeat(2) {
            val reopened = LocalLibrary(isolatedContext).load(owner)
            assertEquals("An intentionally empty fixed script must remain empty after reopening.", "", reopened.draft.content)
            assertEquals(initial.draft.id, reopened.draft.id)
            assertEquals(listOf(legacy), reopened.scripts)
            assertEquals(initial.settings, reopened.settings)
        }
    }

    private fun ownerFile(ownerId: String): File =
        File(directory, "native-library-v1/${StorageIdentity.ownerKey(ownerId)}/library.json")
}
