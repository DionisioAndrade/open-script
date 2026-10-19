package com.example.open_script.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingProgressTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun pausedDurationComesOnlyFromRecordedMediaTimestamps() {
        val progress = RecordingProgress()
        progress.accept(CaptureSignal.STARTED, 0)
        progress.accept(CaptureSignal.STATUS, 1250)
        assertTrue(progress.requestPause())
        progress.accept(CaptureSignal.PAUSED, 1300)
        assertEquals(CaptureStatus.PAUSED, progress.status)
        // A much later paused status contains the same media duration, not elapsed wall time.
        progress.accept(CaptureSignal.STATUS, 1300)
        assertEquals(1300L, progress.durationMs)
        assertTrue(progress.requestResume())
        progress.accept(CaptureSignal.RESUMED, 1300)
        progress.accept(CaptureSignal.STATUS, 1800)
        assertEquals(1800L, progress.durationMs)
    }

    @Test fun stopBeforeStartEventCannotReturnSessionToRecording() {
        val progress = RecordingProgress()
        assertTrue(progress.requestStop())
        progress.accept(CaptureSignal.STARTED, 0)
        progress.accept(CaptureSignal.STATUS, 400)
        progress.accept(CaptureSignal.PAUSED, 400)
        assertEquals(CaptureStatus.STOPPING, progress.status)
        assertFalse(progress.requestResume())
        assertFalse(progress.requestPause())
        assertFalse(progress.requestStop())
        assertEquals(400L, progress.durationMs)
    }

    @Test fun repeatedCommandsWaitForCameraAcknowledgement() {
        val progress = RecordingProgress()
        assertFalse(progress.requestPause())
        progress.accept(CaptureSignal.STARTED, 0)
        assertTrue(progress.requestPause())
        assertFalse(progress.requestPause())
        progress.accept(CaptureSignal.STATUS, 100)
        assertFalse(progress.requestPause())
        progress.accept(CaptureSignal.PAUSED, 100)
        assertTrue(progress.requestResume())
        assertFalse(progress.requestResume())
        progress.commandFailed()
        assertTrue(progress.requestResume())
        progress.accept(CaptureSignal.RESUMED, 100)
        assertTrue(progress.requestPause())
    }

    @Test fun finalizationIsAcceptedOnceAndRejectsLateEvents() {
        val progress = RecordingProgress()
        progress.accept(CaptureSignal.STARTED, 0)
        progress.accept(CaptureSignal.STATUS, 500)
        assertTrue(progress.accept(CaptureSignal.FINALIZED, 650))
        assertFalse(progress.accept(CaptureSignal.FINALIZED, 650))
        assertFalse(progress.accept(CaptureSignal.STATUS, 900))
        assertFalse(progress.accept(CaptureSignal.RESUMED, 900))
        assertEquals(650L, progress.durationMs)
        assertTrue(progress.finalized)
        assertEquals(CaptureStatus.STOPPING, progress.status)
    }

    @Test fun durationDoesNotRegressForOutOfOrderOrInvalidStats() {
        val progress = RecordingProgress()
        progress.accept(CaptureSignal.STATUS, -1)
        assertEquals(0L, progress.durationMs)
        progress.accept(CaptureSignal.STATUS, 1200)
        progress.accept(CaptureSignal.FINALIZED, 1100)
        assertEquals(1200L, progress.durationMs)
    }

    @Test fun portraitDimensionsApplyRotationMetadataExactlyOnce() {
        assertEquals(1080 to 1920, normalizedVideoDimensions(1920, 1080, 90))
        assertEquals(1080 to 1920, normalizedVideoDimensions(1920, 1080, 270))
        assertEquals(1080 to 1920, normalizedVideoDimensions(1920, 1080, -90))
        assertEquals(1920 to 1080, normalizedVideoDimensions(1920, 1080, 180))
        assertEquals(1080 to 1920, normalizedVideoDimensions(1080, 1920, 0))
    }

    @Test fun outputContainmentRejectsParentTraversalAndSiblingPrefix() {
        val privateDirectory = directory.newFolder("private")
        val inside = File(privateDirectory, "takes/take.mp4")
        assertTrue(isInsideDirectory(inside, privateDirectory))
        assertFalse(isInsideDirectory(File(privateDirectory, "../outside.mp4"), privateDirectory))
        assertFalse(isInsideDirectory(File(directory.root, "private-other/take.mp4"), privateDirectory))
        assertFalse(isInsideDirectory(privateDirectory, privateDirectory))
    }
}
