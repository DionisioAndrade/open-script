package com.example.open_script.data

import org.junit.Assert.*
import org.junit.Test

class LibraryMergeTest {
    private val submitted = ScriptDocument(content = "Enviado", updatedAt = 100)
    private val remote = submitted.copy(remoteId = "remote", revision = 2, updatedAt = 200)

    @Test fun editsDuringUploadKeepTheirTextAndGainServerRevision() {
        val edited = submitted.copy(content = "Importado durante o envio", updatedAt = 300)
        val result = LibrarySnapshot(draft = edited).afterUpload(submitted, remote)
        assertEquals(edited.content, result.draft.content)
        assertEquals(300L, result.draft.updatedAt)
        assertEquals("remote", result.draft.remoteId)
        assertEquals(2, result.draft.revision)
        assertEquals(result.draft, result.scripts.single())
    }

    @Test fun switchingDraftDuringUploadPreservesActiveAndSavedEdits() {
        val active = ScriptDocument(content = "Outro roteiro")
        val edited = submitted.copy(content = "Edição salva", updatedAt = 400)
        val result = LibrarySnapshot(draft = active, scripts = listOf(edited)).afterUpload(submitted, remote)
        assertEquals(active, result.draft)
        assertEquals(edited.content, result.scripts.single().content)
        assertEquals(2, result.scripts.single().revision)
    }

    @Test fun deletingDraftDuringUploadDoesNotRestoreIt() {
        val active = ScriptDocument()
        val result = LibrarySnapshot(draft = active).afterUpload(submitted, remote)
        assertEquals(active, result.draft)
        assertTrue(result.scripts.isEmpty())
    }
}
