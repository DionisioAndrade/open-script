package com.example.open_script.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real index/AtomicFile checks in a UUID directory, without touching the user's takes. */
@RunWith(AndroidJUnit4::class)
class TakeMetadataPersistenceTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var context: Context
    private lateinit var library: LocalLibrary

    @Before fun prepareIsolatedLibrary() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        cache = target.cacheDir.canonicalFile
        root = File(cache, "take-metadata-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        library = LocalLibrary(context)
    }

    @After fun cleanOnlyThisFixture() {
        if (::root.isInitialized) {
            check(root.canonicalFile.parentFile == cache)
            check(root.name.startsWith("take-metadata-test-"))
            assertTrue(root.deleteRecursively())
        }
    }

    @Test fun renameSurvivesReopeningAndPreservesTheVideoItsIdentityAndOtherMetadata() {
        val original = seedTake(null, "Nome anterior")
        val file = File(original.path)
        val videoBytes = file.readBytes()
        val modifiedAt = file.lastModified()

        val renamed = library.renameTake(null, original.id, "  Apresentação 🎥  ").single()
        val reopened = LocalLibrary(context).takes(null).single()

        assertEquals(original.copy(title = "Apresentação 🎥"), renamed)
        assertEquals(renamed, reopened)
        assertEquals(modifiedAt, file.lastModified())
        assertArrayEquals(videoBytes, file.readBytes())
        assertEquals(listOf(file.name), file.parentFile!!.listFiles()!!.map { it.name })
    }

    @Test fun renamingOneOwnerCannotChangeOrSelectAnotherOwnersTake() {
        val visitor = seedTake(null, "Visitante")
        val account = seedTake("another-owner", "Conta")
        val otherIndex = indexFile("another-owner").readBytes()
        val otherVideo = File(account.path).readBytes()

        library.renameTake(null, visitor.id, "Meu vídeo")
        try {
            library.renameTake(null, account.id, "Não pode mudar")
            fail("An id from another owner must not be accepted.")
        } catch (_: IOException) {
            // No caller-provided path is used to resolve the selected recording.
        }

        assertEquals("Meu vídeo", library.takes(null).single().title)
        assertEquals(account, library.takes("another-owner").single())
        assertArrayEquals(otherIndex, indexFile("another-owner").readBytes())
        assertArrayEquals(otherVideo, File(account.path).readBytes())
    }

    @Test fun aFailedAtomicWriteKeepsThePreviousNameAndVideo() {
        val original = seedTake("owner", "Nome salvo")
        val previousIndex = indexFile("owner").readBytes()
        val videoBytes = File(original.path).readBytes()
        // A nonempty directory blocks AtomicFile's temporary output deterministically.
        val blockedOutput = File(indexFile("owner").path + ".new").apply { check(mkdirs()) }
        File(blockedOutput, "blocker").writeText("fixture")

        try {
            library.renameTake("owner", original.id, "Não salvar")
            fail("The index write must fail.")
        } catch (_: IOException) {
            // The model must only publish the returned list after this write succeeds.
        }

        assertArrayEquals(previousIndex, indexFile("owner").readBytes())
        assertArrayEquals(videoBytes, File(original.path).readBytes())
        assertEquals(original, LocalLibrary(context).takes("owner").single())
    }

    @Test fun sizeComesFromTheFileWhenReadingALegacyIndexAndRefreshesWithoutMigratingIt() {
        val original = seedTake(null, "Legado")
        val previousIndex = indexFile(null).readBytes()
        assertFalse(JSONObject(previousIndex.toString(Charsets.UTF_8)).getJSONArray("takes")
            .getJSONObject(0).has("size_bytes"))
        val file = File(original.path)
        file.appendBytes(byteArrayOf(1, 2, 3, 4, 5))

        val reopened = LocalLibrary(context).takes(null).single()

        assertEquals(original.sizeBytes + 5L, reopened.sizeBytes)
        assertEquals(original.copy(sizeBytes = original.sizeBytes + 5L), reopened)
        assertArrayEquals(previousIndex, indexFile(null).readBytes())
    }

    private fun seedTake(owner: String?, title: String): RecordedTake {
        val file = library.newTakeFile(owner).apply { writeBytes(byteArrayOf(2, 4, 6, 8, 10, 12, 14)) }
        val take = RecordedTake(id = file.nameWithoutExtension, title = title, path = file.absolutePath,
            durationMs = 12_345L, createdAt = 1_700_000_000_000L, width = 720, height = 1280,
            sizeBytes = file.length())
        // This is an indexed fixture; these bytes need not be decoded as an actual MP4.
        val entry = JSONObject().put("id", take.id).put("title", take.title).put("path", take.path)
            .put("duration_ms", take.durationMs).put("created_at", take.createdAt)
            .put("width", take.width).put("height", take.height)
        indexFile(owner).writeText(JSONObject().put("version", 1).put("takes", JSONArray().put(entry)).toString())
        return take
    }

    private fun indexFile(owner: String?): File =
        File(root, "native-library-v1/${StorageIdentity.ownerKey(owner)}/takes.json")
}
