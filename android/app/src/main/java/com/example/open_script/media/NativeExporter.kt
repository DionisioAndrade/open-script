package com.example.open_script.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExportState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    val output: File? = null,
    val notice: String? = null,
)

/** Exports a new private MP4. Input takes are never modified or deleted. */
@UnstableApi
class NativeExporter(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = mutableState.asStateFlow()

    private var transformer: Transformer? = null
    private var preparation: Job? = null
    private var progressTask: Runnable? = null
    private var completion: ((File?) -> Unit)? = null
    private var activeOutput: File? = null
    private var generation = 0L
    private val protectedInputs = mutableSetOf<String>()

    fun export(input: File, profile: ExportProfile, onCompleted: (File?) -> Unit) = onMain {
        if (mutableState.value.running) {
            onCompleted(null)
            return@onMain
        }
        val operation = ++generation
        protectedInputs.add(fileIdentity(input))
        val protectedPaths = protectedInputs.toSet() + listOfNotNull(activeOutput?.let(::fileIdentity))
        completion = onCompleted
        mutableState.value = ExportState(running = true)
        val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        preparation = preparationScope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    removeExpiredExports(protectedPaths)
                    sourceDimensions(input)
                }
                if (operation != generation) return@launch
                val settings = ExportProfiles.settings(profile, source.width, source.height)
                val directory = File(appContext.cacheDir, "exports")
                check(directory.isDirectory || directory.mkdirs()) { "Não foi possível preparar a exportação." }
                val output = File(directory, "openscript-${UUID.randomUUID()}.mp4")
                activeOutput = output
                val notice = if (settings.limitedBySource) {
                    "O original tem ${source.width}×${source.height}. A exportação usará " +
                        "${settings.dimensions.width}×${settings.dimensions.height} para não ampliar a imagem."
                } else null
                mutableState.value = mutableState.value.copy(notice = notice)
                startTransformer(operation, input, output, settings)
            } catch (_: CancellationException) {
                // cancel()/clear() perform cleanup and deliver the completion callback.
            } catch (_: Exception) {
                finish(operation, error = "Não foi possível exportar este vídeo. O original foi mantido.")
            } finally {
                if (operation == generation) preparation = null
                preparationScope.cancel()
            }
        }
    }

    /** Stops this export, removes only its unfinished file, and completes with null. */
    fun cancel() = onMain { cancelCurrent() }

    /** Detaches the result. Finished copies stay available for shared-URI readers for 24 hours. */
    fun clear() = onMain {
        val callback = cancelCurrent(deliverCallback = false)
        mutableState.value = ExportState()
        callback?.invoke(null)
    }

    private fun sourceDimensions(input: File): ExportDimensions {
        require(input.isFile && input.canRead() && input.length() > 0) { "O vídeo original não está disponível." }
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(input.absolutePath)
            val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            require(width >= 2 && height >= 2) { "O arquivo não contém um vídeo válido." }
            return if (rotation == 90 || rotation == 270) ExportDimensions(height, width) else ExportDimensions(width, height)
        } finally {
            reader.release()
        }
    }

    private fun startTransformer(
        operation: Long,
        input: File,
        output: File,
        settings: ExportSettings,
        softwareDecoding: Boolean = false,
    ) {
        val encoderFactory = DefaultEncoderFactory.Builder(appContext)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(settings.videoBitsPerSecond).build(),
            )
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder().setBitrate(settings.audioBitsPerSecond).build(),
            )
            .build()
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (!isCurrentAttempt(operation, output)) return
                if (output.isFile && output.length() > 0) {
                    finish(operation, output = output)
                } else {
                    finish(operation, error = "A exportação não gerou um arquivo válido. O original foi mantido.")
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                if (!isCurrentAttempt(operation, output)) return
                val decoderFailure = exportException.errorCode == ExportException.ERROR_CODE_DECODING_FAILED ||
                    exportException.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED
                if (!softwareDecoding && decoderFailure) {
                    retryWithSoftwareDecoding(operation, input, output, settings)
                    return
                }
                finish(operation, error = "Este aparelho não conseguiu concluir a exportação. O original foi mantido.")
            }

            override fun onFallbackApplied(
                composition: Composition,
                originalTransformationRequest: TransformationRequest,
                fallbackTransformationRequest: TransformationRequest,
            ) {
                if (!isCurrentAttempt(operation, output)) return
                // A device may lower resolution, but an MP4 requested as H.264/AAC must stay compatible.
                val video = fallbackTransformationRequest.videoMimeType
                val audio = fallbackTransformationRequest.audioMimeType
                if ((video != null && video != MimeTypes.VIDEO_H264) ||
                    (audio != null && audio != MimeTypes.AUDIO_AAC)
                ) {
                    finish(operation, error = "O aparelho não oferece esta exportação em MP4 H.264/AAC. O original foi mantido.")
                } else {
                    val adjustment = "O aparelho ajustou a resolução para um formato de codificação compatível."
                    mutableState.value = mutableState.value.copy(
                        notice = listOfNotNull(mutableState.value.notice, adjustment).joinToString(" "),
                    )
                }
            }
        }
        val builder = Transformer.Builder(appContext)
            .setLooper(Looper.getMainLooper())
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(listener)
        if (softwareDecoding) {
            val decoderFactory = DefaultDecoderFactory.Builder(appContext)
                .setMediaCodecSelector(SoftwareDecoders.selector)
                .setEnableDecoderFallback(true)
                .build()
            builder.setAssetLoaderFactory(DefaultAssetLoaderFactory(appContext, decoderFactory, Clock.DEFAULT, null))
        }
        val engine = builder.build()
        transformer = engine
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setRemoveAudio(false)
            .setFrameRate(settings.frameRate)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        Presentation.createForWidthAndHeight(
                            settings.dimensions.width,
                            settings.dimensions.height,
                            Presentation.LAYOUT_SCALE_TO_FIT,
                        ),
                        FrameDropEffect.createDefaultFrameDropEffect(settings.frameRate.toFloat()),
                    ),
                ),
            )
            .build()
        // No clipping configuration or social-platform duration limit is applied.
        engine.start(item, output.absolutePath)
        pollProgress(operation)
    }

    /** One retry for decoder failures; callbacks from the failed output cannot finish this attempt. */
    private fun retryWithSoftwareDecoding(operation: Long, input: File, failedOutput: File, settings: ExportSettings) {
        if (!isCurrentAttempt(operation, failedOutput)) return
        progressTask?.let(main::removeCallbacks)
        progressTask = null
        val failedEngine = transformer
        transformer = null
        activeOutput = null
        runCatching { failedEngine?.cancel() }
        deleteOwnedOutput(failedOutput)
        val adjustment = "Usando decodificação alternativa compatível com este aparelho."
        mutableState.value = mutableState.value.copy(
            progress = 0f,
            notice = listOfNotNull(mutableState.value.notice, adjustment).joinToString(" "),
        )
        main.post {
            if (operation != generation || completion == null) return@post
            try {
                val output = File(appContext.cacheDir, "exports/openscript-${UUID.randomUUID()}.mp4")
                activeOutput = output
                startTransformer(operation, input, output, settings, softwareDecoding = true)
            } catch (_: Exception) {
                finish(operation, error = "Este aparelho não conseguiu concluir a exportação. O original foi mantido.")
            }
        }
    }

    private fun isCurrentAttempt(operation: Long, output: File): Boolean =
        operation == generation && completion != null && activeOutput == output

    private fun pollProgress(operation: Long) {
        val holder = ProgressHolder()
        val task = object : Runnable {
            override fun run() {
                if (operation != generation || completion == null) return
                val engine = transformer ?: return
                try {
                    if (engine.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        mutableState.value = mutableState.value.copy(progress = (holder.progress / 100f).coerceIn(0f, 1f))
                    }
                    main.postDelayed(this, 250)
                } catch (_: Exception) {
                    finish(operation, error = "A exportação foi interrompida. O original foi mantido.")
                }
            }
        }
        progressTask = task
        main.post(task)
    }

    private fun finish(operation: Long, output: File? = null, error: String? = null) {
        if (operation != generation || completion == null) return
        val callback = completion
        completion = null
        progressTask?.let(main::removeCallbacks)
        progressTask = null
        val engine = transformer
        transformer = null
        if (output == null) runCatching { engine?.cancel() }
        if (output == null) activeOutput?.let(::deleteOwnedOutput)
        activeOutput = null
        mutableState.value = ExportState(
            progress = if (output != null) 1f else 0f,
            error = error,
            output = output,
            notice = mutableState.value.notice,
        )
        callback?.invoke(output)
    }

    private fun cancelCurrent(deliverCallback: Boolean = true): ((File?) -> Unit)? {
        ++generation
        val callback = completion
        completion = null
        preparation?.cancel()
        preparation = null
        progressTask?.let(main::removeCallbacks)
        progressTask = null
        val engine = transformer
        transformer = null
        runCatching { engine?.cancel() }
        activeOutput?.let(::deleteOwnedOutput)
        activeOutput = null
        if (mutableState.value.running) mutableState.value = ExportState(notice = "Exportação cancelada. O original foi mantido.")
        if (deliverCallback) callback?.invoke(null)
        return callback
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun deleteOwnedOutput(file: File) {
        if (fileIdentity(file) !in protectedInputs) file.delete()
    }

    private fun removeExpiredExports(protectedPaths: Set<String>) {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        val ownName = Regex("openscript-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.mp4")
        File(appContext.cacheDir, "exports").listFiles()?.forEach { file ->
            if (file.isFile && ownName.matches(file.name) && file.lastModified() in 1L..cutoff &&
                fileIdentity(file) !in protectedPaths
            ) file.delete()
        }
    }

    private fun fileIdentity(file: File): String = runCatching { file.canonicalPath }
        .getOrElse { file.absoluteFile.normalize().path }
}
