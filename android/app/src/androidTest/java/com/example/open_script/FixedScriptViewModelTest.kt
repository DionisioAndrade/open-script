package com.example.open_script

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.system.Os
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.open_script.data.JsonCodec
import com.example.open_script.data.LibrarySnapshot
import com.example.open_script.data.LocalLibrary
import com.example.open_script.data.ScriptDocument
import com.example.open_script.data.StorageIdentity
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixedScriptViewModelTest {
    @SuppressLint("RestrictedApi") // Own the store explicitly to exercise onCleared without an Activity.
    @Test(timeout = 90_000L)
    fun immediateExitKeepsLatestTextAndIntentionalEmptyDraft() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "fixed-script-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val app = IsolatedApplication(target, root)
        val file = File(app.filesDir, "native-library-v1/${StorageIdentity.ownerKey(null)}/library.json")
        val handles = mutableListOf<ModelHandle>()
        val legacy = LibrarySnapshot(
            draft = ScriptDocument(title = "Título antigo preservado", content = "Texto anterior", updatedAt = 1L),
            scripts = listOf(ScriptDocument(title = "Roteiro arquivado", content = "Não apagar este texto.", updatedAt = 2L)),
        )
        val latest = "Primeira linha: ação, coração e café.\n\nSegunda linha com acentos: amanhã será possível. 🎬\n"

        fun open(): ModelHandle {
            lateinit var handle: ModelHandle
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val model = StudioViewModel(app)
                handle = ModelHandle(ViewModelStore().apply { put("fixed-script", model) }, model)
                handles.add(handle)
                assertTrue("Storage is still loading within the constructor's main-loop turn.", model.state.value.loading)
                val loadingLibrary = model.state.value.library
                model.editDraft("Não substituir o último roteiro durante a abertura.")
                model.setSettings(loadingLibrary.settings.copy(speed = 71f))
                model.flushDraft()
                assertEquals("Loading must reject edits and settings instead of persisting defaults.",
                    loadingLibrary, model.state.value.library)
            }
            await("Load the isolated library.") { !handle.model.state.value.loading }
            assertNull(handle.model.state.value.message)
            assertNull(handle.model.state.value.session)
            return handle
        }

        fun awaitWrite(handle: ModelHandle) {
            if (handle.writeCompleted) return
            val expected = handle.expected ?: return
            await("Finish the final write before reopening or cleaning the isolated library.") {
                version(file) != handle.previousVersion && !File(file.path + ".new").exists() && !File(file.path + ".bak").exists() &&
                    runCatching { JsonCodec.snapshot(JSONObject(file.readText(Charsets.UTF_8))) == expected }.getOrDefault(false)
            }
            handle.writeCompleted = true
        }

        fun clear(handle: ModelHandle, content: String? = null) {
            if (!handle.cleared) {
                handle.previousVersion = version(file)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    // Both calls share one main-loop turn: the 300 ms debounce cannot save first.
                    if (content != null) handle.model.editDraft(content)
                    if (!handle.model.state.value.loading) handle.expected = handle.model.state.value.library
                    handle.store.clear()
                    handle.cleared = true
                }
            }
            awaitWrite(handle)
        }

        try {
            LocalLibrary(app).save(null, legacy)
            val first = open()
            assertEquals(legacy, first.model.state.value.library)
            clear(first, latest)

            val second = open()
            assertEquals(latest, second.model.state.value.library.draft.content)
            assertEquals(legacy.draft.id, second.model.state.value.library.draft.id)
            assertEquals(legacy.draft.title, second.model.state.value.library.draft.title)
            assertEquals(legacy.scripts, second.model.state.value.library.scripts)
            clear(second, "")

            val third = open()
            assertEquals("", third.model.state.value.library.draft.content)
            assertEquals(legacy.draft.id, third.model.state.value.library.draft.id)
            assertEquals(legacy.draft.title, third.model.state.value.library.draft.title)
            assertEquals(legacy.scripts, third.model.state.value.library.scripts)
            clear(third)
        } finally {
            // A loading model must not manufacture a replacement for the on-disk fixture.
            // Await every independent onCleared write before deleting this test's own files.
            handles.forEach { clear(it) }
            check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile)
            try { assertTrue("Remove only the test's UUID directory.", root.deleteRecursively()) }
            finally { app.removePreferences() }
        }
    }

    private class ModelHandle(val store: ViewModelStore, val model: StudioViewModel) {
        var cleared = false
        var writeCompleted = false
        var expected: LibrarySnapshot? = null
        var previousVersion: Pair<Long, Long>? = null
    }

    private fun version(file: File): Pair<Long, Long>? = runCatching {
        Os.stat(file.absolutePath).st_ino to file.lastModified()
    }.getOrNull()

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (!condition()) {
            assertTrue(description, SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(20L)
        }
    }

    private class IsolatedApplication(private val target: Context, root: File) : Application() {
        private val preferencePrefix = "fixed-script-${UUID.randomUUID()}-"
        private val preferenceNames = mutableSetOf<String>()

        init {
            val files = File(root, "files").apply { check(mkdirs()) }
            val cache = File(root, "cache").apply { check(mkdirs()) }
            val noBackup = File(root, "no-backup").apply { check(mkdirs()) }
            attachBaseContext(object : ContextWrapper(target) {
                override fun getApplicationContext(): Context = this@IsolatedApplication
                override fun getFilesDir(): File = files
                override fun getCacheDir(): File = cache
                override fun getNoBackupFilesDir(): File = noBackup
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val isolatedName = preferencePrefix + name
                    synchronized(preferenceNames) { preferenceNames.add(isolatedName) }
                    return target.getSharedPreferences(isolatedName, mode)
                }
            })
        }

        fun removePreferences() {
            synchronized(preferenceNames) { preferenceNames.toList() }.forEach { target.deleteSharedPreferences(it) }
        }
    }
}
