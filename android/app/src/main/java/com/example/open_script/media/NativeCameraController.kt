package com.example.open_script.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Looper
import android.os.Handler
import android.view.Surface
import androidx.camera.core.CameraState
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Native file capture. Public commands and callbacks run on the main thread.
 * An unbound preview never cancels the pending final-file callback.
 */
class NativeCameraController(context: Context) {
    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()
    private val orientation = CaptureOrientation()
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var lifecycleObserver: LifecycleEventObserver? = null
    private var observedCameraState: androidx.lifecycle.LiveData<CameraState>? = null
    private var cameraObserver: Observer<CameraState>? = null
    private var bindingGeneration = 0L
    private var recordingGeneration = 0L
    private var activeSession: Session? = null
    private var frontCamera = true
    private var displayListener: DisplayManager.DisplayListener? = null

    private class Session(
        val id: Long,
        val file: File,
        val audio: Boolean,
        val onFinished: (CaptureResult) -> Unit,
    ) {
        val progress = RecordingProgress()
        var recording: Recording? = null
        var callbackDelivered = false
        var interrupted = false
        var recreateRecorder = false
    }

    suspend fun bind(owner: LifecycleOwner, view: PreviewView, front: Boolean) =
        withContext(Dispatchers.Main.immediate) {
            if (activeSession != null) {
                reportError("Finalize a gravação antes de trocar a câmera.")
                return@withContext
            }
            val generation = ++bindingGeneration
            detachPreview()
            mutableState.value = mutableState.value.copy(ready = false, error = null)
            if (!hasPermission(Manifest.permission.CAMERA)) {
                reportError("Permita o acesso à câmera para abrir a prévia.")
                return@withContext
            }
            if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@withContext

            try {
                val cameraProvider = awaitCameraProvider()
                if (generation != bindingGeneration || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                    return@withContext
                }
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!cameraProvider.hasCamera(selector)) {
                    reportError(if (front) "Este aparelho não tem câmera frontal disponível." else "Este aparelho não tem câmera traseira disponível.")
                    return@withContext
                }
                // Fill the preview without letterboxing; video capture keeps its own framing.
                view.scaleType = PreviewView.ScaleType.FILL_CENTER
                // TextureView supports Compose overlays consistently; CameraX mirrors only the front preview.
                view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                view.scaleX = 1f
                view.scaleY = 1f
                orientation.update(view.display?.rotation ?: orientation.requestedRotation)
                val rotation = orientation.targetRotation
                val newPreview = Preview.Builder().setTargetRotation(rotation).build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD, Quality.LOWEST)))
                    .build()
                val capture = VideoCapture.Builder(recorder)
                    .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
                    .build().also { it.targetRotation = rotation }
                val camera = cameraProvider.bindToLifecycle(owner, selector, newPreview, capture)
                provider = cameraProvider
                preview = newPreview
                videoCapture = capture
                previewView = view
                frontCamera = front
                lifecycleOwner = owner
                observeDisplay(view, generation)
                lifecycleObserver = LifecycleEventObserver { _, event ->
                    if (generation == bindingGeneration) {
                        when (event) {
                            Lifecycle.Event.ON_STOP -> {
                                mutableState.value = mutableState.value.copy(ready = false)
                                activeSession?.interrupted = true
                                stop()
                            }
                            Lifecycle.Event.ON_DESTROY -> unbind()
                            else -> Unit
                        }
                    }
                }.also { owner.lifecycle.addObserver(it) }
                val liveState = camera.cameraInfo.cameraState
                val observer = Observer<CameraState> { cameraState ->
                    if (generation == bindingGeneration) {
                        val visible = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        val ready = cameraState.type == CameraState.Type.OPEN && visible
                        mutableState.value = mutableState.value.copy(ready = ready)
                        if (cameraState.error != null) {
                            reportError("A câmera ficou indisponível. Feche outros aplicativos que estejam usando a câmera e tente novamente.")
                            activeSession?.interrupted = true
                            stop()
                        }
                    }
                }
                observedCameraState = liveState
                cameraObserver = observer
                liveState.observe(owner, observer)
            } catch (cancelled: CancellationException) {
                if (generation == bindingGeneration) {
                    ++bindingGeneration
                    detachPreview()
                }
                throw cancelled
            } catch (_: Exception) {
                if (generation == bindingGeneration) {
                    detachPreview()
                    reportError("Não foi possível abrir a câmera neste aparelho.")
                }
            }
        }

    @SuppressLint("MissingPermission")
    fun start(file: File, includeAudio: Boolean, onFinished: (CaptureResult) -> Unit) {
        onMain {
            val capture = videoCapture
            val owner = lifecycleOwner
            val failure = when {
                activeSession != null -> "Já existe uma gravação em andamento."
                !hasPermission(Manifest.permission.CAMERA) -> "Permita o acesso à câmera antes de gravar."
                includeAudio && !hasPermission(Manifest.permission.RECORD_AUDIO) -> "Permita o acesso ao microfone ou escolha gravar sem áudio."
                capture == null || !mutableState.value.ready || owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true ->
                    "Aguarde a câmera ficar pronta antes de gravar."
                else -> null
            }
            if (failure != null) {
                failStart(file, failure, onFinished)
                return@onMain
            }
            val destination = try {
                privateDestination(file)
            } catch (_: Exception) {
                failStart(file, "Não foi possível criar um novo arquivo privado para esta gravação.", onFinished)
                return@onMain
            }
            val session = Session(++recordingGeneration, destination, includeAudio, onFinished)
            // Snapshot the display orientation before STARTING. Screen relayout never changes
            // this take's video output orientation, even while paused or finalizing.
            orientation.update(previewView?.display?.rotation ?: orientation.requestedRotation)
            val rotation = orientation.beginCapture()
            activeSession = session
            mutableState.value = CaptureState(
                status = CaptureStatus.STARTING, hasAudio = includeAudio, ready = true,
            )
            try {
                capture!!.targetRotation = rotation
                preview?.targetRotation = rotation
                var pending = capture.output.prepareRecording(appContext, FileOutputOptions.Builder(destination).build())
                if (includeAudio) pending = pending.withAudioEnabled()
                session.recording = pending.start(executor) { event -> handleEvent(session, event) }
            } catch (_: Exception) {
                session.progress.accept(CaptureSignal.FINALIZED, 0)
                finishSession(session, "Não foi possível iniciar a gravação. Verifique câmera, microfone e espaço disponível.")
            }
        }
    }

    fun pause() = onMain {
        val session = activeSession ?: return@onMain
        if (!session.progress.requestPause()) return@onMain
        try {
            session.recording?.pause()
        } catch (_: Exception) {
            session.progress.commandFailed()
            reportError("Não foi possível pausar esta gravação.")
        }
    }

    fun resume() = onMain {
        val session = activeSession ?: return@onMain
        if (lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true) return@onMain
        if (!hasPermission(Manifest.permission.CAMERA) || (session.audio && !hasPermission(Manifest.permission.RECORD_AUDIO))) {
            session.interrupted = true
            reportError("Uma permissão foi removida. A gravação será encerrada para preservar o vídeo.")
            stop()
            return@onMain
        }
        if (!session.progress.requestResume()) return@onMain
        try {
            session.recording?.resume()
        } catch (_: Exception) {
            session.progress.commandFailed()
            reportError("Não foi possível continuar esta gravação.")
        }
    }

    fun stop() = onMain {
        val session = activeSession ?: return@onMain
        if (!session.progress.requestStop()) return@onMain
        publishProgress(session)
        try {
            session.recording?.stop()
        } catch (_: Exception) {
            // A Finalize event may already be queued. Keep its sole ownership of the result.
            runCatching { session.recording?.close() }
            reportError("A gravação está sendo encerrada.")
        }
    }

    fun unbind() = onMain {
        ++bindingGeneration
        if (activeSession != null) {
            activeSession?.interrupted = true
            stop()
        }
        detachPreview()
        mutableState.value = mutableState.value.copy(ready = false)
    }

    fun clearError() = onMain {
        mutableState.value = mutableState.value.copy(error = null)
    }

    /**
     * Accepts Surface.ROTATION_0/90/180/270 (0..3), not degrees. Rotation updates
     * never rebind, stop or resume the camera. The preview follows the display,
     * while the recorded take keeps its starting orientation until it finalizes.
     */
    fun updateRotation(rotation: Int) = onMain {
        if (rotation !in Surface.ROTATION_0..Surface.ROTATION_270) return@onMain
        orientation.update(rotation)
        applyTargetRotation()
    }

    private fun handleEvent(session: Session, event: VideoRecordEvent) {
        if (activeSession?.id != session.id || session.callbackDelivered) return
        val signal = when (event) {
            is VideoRecordEvent.Start -> CaptureSignal.STARTED
            is VideoRecordEvent.Pause -> CaptureSignal.PAUSED
            is VideoRecordEvent.Resume -> CaptureSignal.RESUMED
            is VideoRecordEvent.Finalize -> CaptureSignal.FINALIZED
            else -> CaptureSignal.STATUS
        }
        if (!session.progress.accept(signal, event.recordingStats.recordedDurationNanos / 1_000_000L)) return
        publishProgress(session)
        if (event is VideoRecordEvent.Finalize) {
            session.recording = null
            session.recreateRecorder = event.error == VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ||
                event.error == VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED
            finishSession(session, finalizeError(event, session.interrupted))
        }
    }

    private fun finishSession(session: Session, eventError: String?) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                inspectVideo(session.file, session.progress.durationMs, eventError)
            }
            if (activeSession?.id != session.id || session.callbackDelivered) return@launch
            session.callbackDelivered = true
            activeSession = null
            orientation.finishCapture()
            applyTargetRotation()
            val previousBinding = bindingGeneration
            val owner = lifecycleOwner
            val view = previewView
            val front = frontCamera
            mutableState.value = mutableState.value.copy(
                status = CaptureStatus.IDLE, durationMs = result.durationMs,
                error = result.error, hasAudio = session.audio,
            )
            session.onFinished(result)
            // CameraX marks recorder errors as unrecoverable. Rebuild the preview/recorder,
            // without starting a recording or resuming the interrupted take.
            if (session.recreateRecorder && previousBinding == bindingGeneration && activeSession == null &&
                owner != null && view != null && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                bind(owner, view, front)
            }
        }
    }

    private fun publishProgress(session: Session) {
        mutableState.value = mutableState.value.copy(
            status = session.progress.status, durationMs = session.progress.durationMs,
            hasAudio = session.audio,
        )
    }

    private fun reportError(message: String) {
        mutableState.value = mutableState.value.copy(error = message)
    }

    private fun failStart(file: File, message: String, callback: (CaptureResult) -> Unit) {
        reportError(message)
        callback(CaptureResult(file, 0, 0, 0, message))
    }

    private fun privateDestination(file: File): File {
        val destination = file.canonicalFile
        val roots = listOf(appContext.filesDir, appContext.cacheDir, appContext.noBackupFilesDir) +
            appContext.getExternalFilesDirs(null).filterNotNull() + appContext.externalCacheDirs.filterNotNull()
        require(roots.any { isInsideDirectory(destination, it) })
        require(!destination.exists()) // Never overwrite an earlier take, including an empty placeholder.
        val parent = requireNotNull(destination.parentFile)
        require(parent.isDirectory || parent.mkdirs())
        require(destination.createNewFile()) // Atomically reserve the fresh path before CameraX writes it.
        return destination
    }

    private fun detachPreview() {
        displayListener?.let { displayManager?.unregisterDisplayListener(it) }
        displayListener = null
        lifecycleObserver?.let { observer -> lifecycleOwner?.lifecycle?.removeObserver(observer) }
        cameraObserver?.let { observer -> observedCameraState?.removeObserver(observer) }
        val useCases = listOfNotNull(preview, videoCapture)
        if (useCases.isNotEmpty()) provider?.unbind(*useCases.toTypedArray())
        preview = null
        videoCapture = null
        previewView = null
        lifecycleOwner = null
        lifecycleObserver = null
        observedCameraState = null
        cameraObserver = null
    }

    private fun applyTargetRotation() {
        // A TextureView with an explicit Preview target does not automatically follow
        // display rotation. Keep it upright without changing the recording's orientation.
        val previewRotation = orientation.requestedRotation
        preview?.let { if (it.targetRotation != previewRotation) it.targetRotation = previewRotation }
        if (activeSession == null) {
            val captureRotation = orientation.targetRotation
            videoCapture?.let { if (it.targetRotation != captureRotation) it.targetRotation = captureRotation }
        }
    }

    private fun observeDisplay(view: PreviewView, generation: Long) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val display = view.display ?: return
                if (generation == bindingGeneration && display.displayId == displayId) {
                    updateRotation(display.rotation)
                }
            }
        }
        displayListener = listener
        displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            if (continuation.isActive) {
                try {
                    continuation.resume(future.get())
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            }
        }, executor)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else executor.execute { action() }
    }

    private fun finalizeError(event: VideoRecordEvent.Finalize, interrupted: Boolean): String? = when {
        !event.hasError() && !interrupted -> null
        !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
            "A gravação foi encerrada quando a câmera deixou de estar disponível."
        event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
            "O espaço disponível acabou durante a gravação."
        event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ->
            "A gravação atingiu o limite de tamanho do arquivo."
        event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ->
            "A gravação atingiu o limite de duração."
        else -> "A gravação foi interrompida por uma falha na câmera ou no codificador."
    }

    /** A recoverable Finalize error does not discard a playable interrupted take. */
    private fun inspectVideo(file: File, recordedDurationMs: Long, eventError: String?): CaptureResult {
        val invalidMessage = "Não foi possível salvar um vídeo reproduzível. Tente gravar novamente."
        if (!file.isFile || file.length() == 0L) {
            // This path was new, private and reserved for this session; no earlier file is touched.
            if (file.isFile && file.length() == 0L) file.delete()
            return CaptureResult(file, 0, 0, 0, eventError ?: invalidMessage)
        }
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            if (videoTrack != null) extractor.selectTrack(videoTrack)
            if (duration <= 0L || width <= 0 || height <= 0 || videoTrack == null || extractor.sampleTime < 0L) {
                CaptureResult(file, 0, 0, 0, eventError ?: invalidMessage)
            } else {
                val dimensions = normalizedVideoDimensions(width, height, rotation)
                CaptureResult(file, duration.takeIf { it > 0L } ?: recordedDurationMs, dimensions.first, dimensions.second, eventError)
            }
        } catch (_: Exception) {
            // Keep non-empty files when metadata is unreadable: recovery must not delete a valid take.
            CaptureResult(file, 0, 0, 0, eventError ?: invalidMessage)
        } finally {
            runCatching { retriever.release() }
            runCatching { extractor.release() }
        }
    }
}
