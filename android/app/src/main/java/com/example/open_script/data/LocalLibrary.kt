package com.example.open_script.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** App-private, separated visitor/account libraries. Reads never replace a damaged file. */
class LocalLibrary(context: Context) {
    private val base = File(context.applicationContext.filesDir, "native-library-v1")
    private val damagedFiles = mutableSetOf<String>()

    @Synchronized
    fun load(ownerId: String?): LibrarySnapshot {
        val file = File(ownerDirectory(ownerId), "library.json")
        return try {
            readDocument(file) { JsonCodec.snapshot(it) }
        } catch (_: Exception) {
            // Preserve both the original and AtomicFile backup for a possible recovery.
            LibrarySnapshot()
        }
    }

    @Synchronized
    fun save(ownerId: String?, snapshot: LibrarySnapshot) {
        writeJson(File(ownerDirectory(ownerId), "library.json"), JsonCodec.snapshot(snapshot))
    }

    /** Call while the recorder is idle so a still-open MP4 is never offered as a completed take. */
    @Synchronized
    fun takes(ownerId: String?): List<RecordedTake> {
        val indexed = indexedTakes(ownerId).mapNotNull { take ->
            val file = ownedTakeFile(ownerId, take.path)
            val size = file?.takeIf { it.isFile }?.length() ?: 0L
            if (file != null && size > 0L) take.copy(path = file.absolutePath, sizeBytes = size) else null
        }
        val knownPaths = indexed.map { it.path }.toSet()
        val recovered = takeDirectory(ownerId).listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) && it.length() > 0 }
            .mapNotNull { file ->
                val owned = ownedTakeFile(ownerId, file.path)
                if (owned == null || owned.absolutePath in knownPaths) null else recoverTake(owned)
            }
        // Recovery stays in memory until an explicit add/delete saves the index.
        return (indexed + recovered).distinctBy { it.path }.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun addTake(ownerId: String?, take: RecordedTake) {
        val file = ownedTakeFile(ownerId, take.path)
            ?: throw IOException("A gravação não pertence à biblioteca desta conta.")
        if (!file.isFile || file.length() == 0L) throw IOException("O arquivo da gravação não foi concluído.")
        val values = takes(ownerId).filterNot { it.id == take.id || it.path == file.absolutePath } + take.copy(path = file.absolutePath)
        writeTakes(ownerId, values)
    }

    @Synchronized
    fun deleteTake(ownerId: String?, take: RecordedTake) {
        val file = ownedTakeFile(ownerId, take.path)
            ?: throw IOException("A gravação não pertence à biblioteca desta conta.")
        val values = takes(ownerId).filterNot { it.id == take.id || it.path == file.absolutePath }
        if (file.exists() && !file.delete()) throw IOException("Não foi possível excluir a gravação.")
        writeTakes(ownerId, values)
    }

    /** Resolve identity inside this owner's library; never accept a path supplied by the UI. */
    @Synchronized
    fun renameTake(ownerId: String?, takeId: String, title: String): List<RecordedTake> {
        val normalized = TakeNames.normalize(title)
        val current = takes(ownerId)
        val existing = current.firstOrNull { it.id == takeId }
            ?: throw IOException("Este take não está disponível nesta biblioteca.")
        if (existing.title == normalized) return current
        val renamed = current.map { if (it.id == takeId) it.copy(title = normalized) else it }
        // Return new UI state only after the atomic index write succeeds.
        writeTakes(ownerId, renamed)
        return renamed
    }

    @Synchronized
    fun newTakeFile(ownerId: String?): File = File(takeDirectory(ownerId), "${UUID.randomUUID()}.mp4")

    private fun ownerDirectory(ownerId: String?): File = File(base, StorageIdentity.ownerKey(ownerId)).apply {
        if (!isDirectory && !mkdirs()) throw IOException("Não foi possível abrir a biblioteca local.")
    }

    private fun takeDirectory(ownerId: String?): File = File(ownerDirectory(ownerId), "takes").apply {
        if (!isDirectory && !mkdirs()) throw IOException("Não foi possível abrir as gravações locais.")
    }

    private fun ownedTakeFile(ownerId: String?, path: String): File? = try {
        val file = File(path).canonicalFile
        file.takeIf { it.parentFile == takeDirectory(ownerId).canonicalFile && it.extension.equals("mp4", ignoreCase = true) }
    } catch (_: IOException) { null }

    private fun indexedTakes(ownerId: String?): List<RecordedTake> {
        val file = File(ownerDirectory(ownerId), "takes.json")
        return try {
            readDocument(file) { json ->
                require(json.getInt("version") == 1)
                val values = json.getJSONArray("takes")
                (0 until values.length()).mapNotNull {
                    try { JsonCodec.take(values.getJSONObject(it)) }
                    catch (_: Exception) { markDamaged(file); null }
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeTakes(ownerId: String?, takes: List<RecordedTake>) {
        val values = JSONArray().apply { takes.forEach { put(JsonCodec.take(it)) } }
        writeJson(File(ownerDirectory(ownerId), "takes.json"), JSONObject().put("version", 1).put("takes", values))
    }

    private fun recoverTake(file: File): RecordedTake? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            if (duration <= 0 || width <= 0 || height <= 0) return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val rotated = rotation == 90 || rotation == 270
            RecordedTake(
                id = if (ApiContract.isUuid(file.nameWithoutExtension)) file.nameWithoutExtension
                    else UUID.nameUUIDFromBytes(file.absolutePath.toByteArray(Charsets.UTF_8)).toString(),
                title = "Gravação recuperada", path = file.absolutePath, durationMs = duration,
                createdAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                width = if (rotated) height else width, height = if (rotated) width else height,
                sizeBytes = file.length(),
            )
        } catch (_: Exception) {
            // An incomplete or unsupported MP4 is preserved on disk but not shown as complete.
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { /* The file remains untouched. */ }
        }
    }

    /** AtomicFile.openRead may rename/delete legacy backups, so recovery reads are non-mutating. */
    private fun <T> readDocument(file: File, decode: (JSONObject) -> T): T {
        val candidates = listOf(File(file.path + ".bak"), file).filter { it.exists() }
        if (candidates.isEmpty()) {
            if (File(file.path + ".new").exists()) markDamaged(file)
            throw FileNotFoundException("A biblioteca ainda não foi salva.")
        }
        var failure: Exception? = null
        for (source in candidates) {
            try {
                return decode(JSONObject(source.readBytes().decodeToString(throwOnInvalidSequence = true)))
            } catch (error: Exception) {
                markDamaged(file)
                failure = error
            }
        }
        throw requireNotNull(failure)
    }

    private fun markDamaged(file: File) {
        damagedFiles.add(file.absolutePath)
    }

    /** Preserve every raw version before a later explicit/autosave can replace a failed read. */
    private fun quarantineIfDamaged(file: File) {
        if (file.absolutePath !in damagedFiles) return
        val originals = listOf(file, File(file.path + ".bak"), File(file.path + ".new")).filter { it.exists() }
        if (originals.isEmpty()) return
        val quarantine = File(file.parentFile, "recovery/${System.currentTimeMillis()}-${UUID.randomUUID()}")
        if (!quarantine.mkdirs()) throw IOException("Não foi possível preservar a biblioteca anterior. Nenhum dado foi substituído.")
        for (source in originals) {
            val destination = File(quarantine, source.name)
            source.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        }
    }

    private fun writeJson(file: File, value: JSONObject) {
        // Do this before entering AtomicFile's failure path: failWrite may remove an old .new file.
        quarantineIfDamaged(file)
        val atomic = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            damagedFiles.remove(file.absolutePath)
        } catch (error: Exception) {
            if (stream != null) atomic.failWrite(stream)
            throw IOException("Não foi possível salvar a biblioteca no aparelho.", error)
        }
    }
}
