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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalLibraryRecoveryTest {
    private lateinit var root: File
    private lateinit var library: LocalLibrary
    private val owner = "recovery-test-owner"

    @Before fun prepareIsolatedPrivateDirectory() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "library-recovery-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        library = LocalLibrary(context)
    }

    @After fun removeOnlyIsolatedTestDirectory() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun malformedLibraryAndAtomicFileVariantsSurviveLoadAndFirstReplacement() {
        library.load(owner)
        val directory = ownerDirectory()
        val originals = writeMalformedVariants(File(directory, "library.json"))

        assertEquals("", library.load(owner).draft.content)
        originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertFalse(File(directory, "recovery").exists())

        val replacement = LibrarySnapshot(draft = ScriptDocument(content = "Novo rascunho explícito"))
        library.save(owner, replacement)

        assertEquals(replacement.draft.content, library.load(owner).draft.content)
        assertRawRecoveryCopies(directory, originals)
    }

    @Test fun malformedTakeIndexIsQuarantinedOutsideTheVideoRecoveryDirectory() {
        library.takes(owner)
        val directory = ownerDirectory()
        val originals = writeMalformedVariants(File(directory, "takes.json"))

        assertTrue(library.takes(owner).isEmpty())
        originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        val missingTake = RecordedTake(title = "Índice antigo", path = library.newTakeFile(owner).path, durationMs = 1000)
        library.deleteTake(owner, missingTake)

        assertTrue(library.takes(owner).isEmpty())
        assertRawRecoveryCopies(directory, originals)
        assertTrue(File(directory, "takes").listFiles().orEmpty().isEmpty())
    }

    private fun ownerDirectory(): File = File(root, "native-library-v1/${StorageIdentity.ownerKey(owner)}")

    private fun writeMalformedVariants(file: File): Map<File, ByteArray> {
        val variants = listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
        return variants.mapIndexed { index, source ->
            val bytes = "{\"texto_original_$index\":\"não descartar\"".toByteArray(Charsets.UTF_8)
            source.writeBytes(bytes)
            source to bytes
        }.toMap()
    }

    private fun assertRawRecoveryCopies(directory: File, originals: Map<File, ByteArray>) {
        val copies = File(directory, "recovery").walkTopDown().filter { it.isFile }.toList()
        assertEquals(originals.size, copies.size)
        originals.forEach { (source, bytes) ->
            assertArrayEquals(bytes, copies.single { it.name == source.name }.readBytes())
        }
    }
}
