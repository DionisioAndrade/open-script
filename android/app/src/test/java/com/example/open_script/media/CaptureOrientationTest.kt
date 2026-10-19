package com.example.open_script.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOrientationTest {
    @Test fun rotatingDuringTakeKeepsItsOrientationAndNextTakeUsesLatestScreenRotation() {
        val orientation = CaptureOrientation()
        orientation.update(0)
        assertEquals(0, orientation.beginCapture())
        assertFalse(orientation.update(1))
        assertFalse(orientation.update(2))
        assertFalse(orientation.update(3))
        assertEquals(0, orientation.targetRotation)
        assertEquals(3, orientation.finishCapture())
        assertEquals(3, orientation.beginCapture())
    }

    @Test fun pendingRotationCanReturnToStartingOrientationBeforeFinalization() {
        val orientation = CaptureOrientation()
        orientation.update(1)
        orientation.beginCapture()
        orientation.update(0)
        orientation.update(1)
        assertEquals(1, orientation.targetRotation)
        assertEquals(1, orientation.finishCapture())
    }

    @Test fun reversePortraitWhileIdleIsAcceptedEvenWithoutScreenSizeChange() {
        val orientation = CaptureOrientation()
        assertTrue(orientation.update(2))
        assertEquals(2, orientation.targetRotation)
        assertEquals(2, orientation.beginCapture())
    }

    @Test fun invalidDegreesCannotOverwriteTheLastValidSurfaceRotation() {
        val orientation = CaptureOrientation()
        orientation.update(1)
        for (invalid in listOf(-1, 4, 90, 180, 270)) {
            assertFalse(orientation.update(invalid))
            assertEquals(1, orientation.targetRotation)
        }
        orientation.beginCapture()
        orientation.update(3)
        assertFalse(orientation.update(90))
        assertEquals(1, orientation.targetRotation)
        assertEquals(3, orientation.finishCapture())
    }

    @Test(expected = IllegalStateException::class)
    fun anotherCaptureCannotUnlockATakeStillBeingFinalized() {
        val orientation = CaptureOrientation()
        orientation.beginCapture()
        orientation.update(1)
        orientation.beginCapture()
    }
}
