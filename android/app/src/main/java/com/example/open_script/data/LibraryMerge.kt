package com.example.open_script.data

/** Attach the server revision without replacing edits made while the request was in flight. */
fun LibrarySnapshot.afterUpload(submitted: ScriptDocument, remote: ScriptDocument): LibrarySnapshot {
    fun link(current: ScriptDocument): ScriptDocument = current.copy(
        remoteId = remote.remoteId,
        revision = remote.revision,
        updatedAt = if (current == submitted) remote.updatedAt else current.updatedAt,
    )
    val nextDraft = if (draft.id == submitted.id) link(draft) else draft
    val nextScripts = scripts.map { if (it.id == submitted.id) link(it) else it }.toMutableList()
    if (draft.id == submitted.id) {
        nextScripts.removeAll { it.id == submitted.id }
        nextScripts.add(nextDraft)
    }
    return copy(draft = nextDraft, scripts = nextScripts.sortedByDescending { it.updatedAt })
}
