package com.sahidcode404.camera.core.camera.orientation

import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.preview.PreviewAspectMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGeometryEngineTest {
    @Test
    fun rearRelativeRotationCoversAllDisplayRotations() {
        assertEquals(90, rotation(90, 0, CameraFacing.BACK))
        assertEquals(0, rotation(90, 90, CameraFacing.BACK))
        assertEquals(270, rotation(90, 180, CameraFacing.BACK))
        assertEquals(180, rotation(90, 270, CameraFacing.BACK))
    }

    @Test
    fun frontRelativeRotationCoversAllDisplayRotations() {
        assertEquals(90, rotation(90, 0, CameraFacing.FRONT))
        assertEquals(180, rotation(90, 90, CameraFacing.FRONT))
        assertEquals(270, rotation(90, 180, CameraFacing.FRONT))
        assertEquals(0, rotation(90, 270, CameraFacing.FRONT))
    }

    @Test
    fun frontPreviewMirrorsAndRearDoesNot() {
        val front = PreviewGeometryEngine.calculate(90, 0, CameraFacing.FRONT, 4.0 / 3.0, 1.0)
        val rear = PreviewGeometryEngine.calculate(90, 0, CameraFacing.BACK, 4.0 / 3.0, 1.0)

        assertTrue(front.mirrorHorizontally)
        assertFalse(rear.mirrorHorizontally)
    }

    @Test
    fun squareCenterCropIsSymmetric() {
        val crop = PreviewGeometryEngine.centerCrop(4.0 / 3.0, 1.0)
        assertEquals(0.125, crop.left, 0.000001)
        assertEquals(0.875, crop.right, 0.000001)
        assertEquals(0.0, crop.top, 0.0)
        assertEquals(1.0, crop.bottom, 0.0)
    }

    @Test
    fun presentationAspectUsesPortraitReciprocalWithoutChangingCameraStream() {
        assertEquals(
            3.0 / 4.0,
            PreviewGeometryEngine.presentationAspect(
                PreviewAspectMode.FOUR_THREE,
                sensorLandscapeAspect = 4.0 / 3.0,
                usableDisplayAspect = 9.0 / 20.0,
                portraitPresentation = true,
            ),
            0.000001,
        )
    }

    private fun rotation(sensor: Int, display: Int, facing: CameraFacing): Int =
        PreviewGeometryEngine.relativeRotationDegrees(sensor, display, facing)
}
