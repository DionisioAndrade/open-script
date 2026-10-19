package com.example.open_script.media

import java.io.File

enum class CaptureStatus { IDLE, STARTING, RECORDING, PAUSED, STOPPING }

data class CaptureState(
    val status: CaptureStatus = CaptureStatus.IDLE,
    val durationMs: Long = 0L,
    val error: String? = null,
    val hasAudio: Boolean = false,
    val ready: Boolean = false,
)

/** Width and height describe the displayed video, after its rotation metadata. */
data class CaptureResult(
    val file: File,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val error: String? = null,
)

internal enum class CaptureSignal { STARTED, STATUS, PAUSED, RESUMED, FINALIZED }

/** Camera timestamps exclude paused time; wall-clock time is never added here. */
internal class RecordingProgress {
    var status = CaptureStatus.STARTING
        private set
    var durationMs = 0L
        private set
    var finalized = false
        private set
    private var commandPending = false

    fun requestPause(): Boolean {
        if (finalized || commandPending || status != CaptureStatus.RECORDING) return false
        commandPending = true
        return true
    }

    fun requestResume(): Boolean {
        if (finalized || commandPending || status != CaptureStatus.PAUSED) return false
        commandPending = true
        return true
    }

    fun commandFailed() {
        commandPending = false
    }

    fun requestStop(): Boolean {
        if (finalized || status == CaptureStatus.STOPPING) return false
        status = CaptureStatus.STOPPING
        commandPending = false
        return true
    }

    fun accept(signal: CaptureSignal, recordedDurationMs: Long): Boolean {
        if (finalized) return false
        durationMs = maxOf(durationMs, recordedDurationMs.coerceAtLeast(0L))
        if (signal == CaptureSignal.FINALIZED) {
            finalized = true
            status = CaptureStatus.STOPPING
            commandPending = false
        } else if (status != CaptureStatus.STOPPING) {
            when (signal) {
                CaptureSignal.STARTED, CaptureSignal.RESUMED -> status = CaptureStatus.RECORDING
                CaptureSignal.PAUSED -> status = CaptureStatus.PAUSED
                else -> Unit
            }
            if (signal != CaptureSignal.STATUS) commandPending = false
        }
        return true
    }
}

internal fun normalizedVideoDimensions(width: Int, height: Int, rotation: Int): Pair<Int, Int> {
    val normalizedRotation = ((rotation % 360) + 360) % 360
    return if (normalizedRotation == 90 || normalizedRotation == 270) height to width else width to height
}

internal fun isInsideDirectory(file: File, directory: File): Boolean =
    file.canonicalPath.startsWith(directory.canonicalPath + File.separator)

/** Surface rotation constants are 0..3, rather than rotation degrees. */
internal class CaptureOrientation {
    var requestedRotation = 0
        private set
    private var recordingRotation: Int? = null
    val targetRotation: Int get() = recordingRotation ?: requestedRotation

    /** The latest screen rotation is remembered, including while the take is locked. */
    fun update(rotation: Int): Boolean {
        if (rotation !in 0..3) return false
        requestedRotation = rotation
        return recordingRotation == null
    }

    fun beginCapture(): Int {
        check(recordingRotation == null) { "The previous take must finalize before changing orientation." }
        recordingRotation = requestedRotation
        return targetRotation
    }

    /** Call only after Finalize and file inspection, before accepting another start. */
    fun finishCapture(): Int {
        recordingRotation = null
        return requestedRotation
    }
}
