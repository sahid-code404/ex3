package com.sahidcode404.camera.core.camera.orientation

import com.sahidcode404.camera.core.camera.model.CameraFacing
import com.sahidcode404.camera.core.camera.preview.PreviewAspectMode
import kotlin.math.max

public data class NormalizedRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left in 0.0..1.0 && top in 0.0..1.0)
        require(right in 0.0..1.0 && bottom in 0.0..1.0)
        require(right > left && bottom > top)
    }
}

public data class PreviewGeometry(
    val relativeRotationDegrees: Int,
    val swapBufferDimensions: Boolean,
    val mirrorHorizontally: Boolean,
    val centerCrop: NormalizedRect,
)

public object PreviewGeometryEngine {
    public fun calculate(
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        facing: CameraFacing,
        rotatedSourceAspect: Double,
        targetPresentationAspect: Double,
        mirrorFrontPreview: Boolean = true,
    ): PreviewGeometry {
        validateRightAngle(sensorOrientationDegrees)
        validateRightAngle(displayRotationDegrees)
        require(rotatedSourceAspect > 0.0)
        require(targetPresentationAspect > 0.0)

        val relativeRotation = relativeRotationDegrees(
            sensorOrientationDegrees = sensorOrientationDegrees,
            displayRotationDegrees = displayRotationDegrees,
            facing = facing,
        )
        return PreviewGeometry(
            relativeRotationDegrees = relativeRotation,
            swapBufferDimensions = relativeRotation == 90 || relativeRotation == 270,
            mirrorHorizontally = mirrorFrontPreview && facing == CameraFacing.FRONT,
            centerCrop = centerCrop(rotatedSourceAspect, targetPresentationAspect),
        )
    }

    public fun relativeRotationDegrees(
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        facing: CameraFacing,
    ): Int {
        validateRightAngle(sensorOrientationDegrees)
        validateRightAngle(displayRotationDegrees)
        val raw = if (facing == CameraFacing.FRONT) {
            sensorOrientationDegrees + displayRotationDegrees
        } else {
            sensorOrientationDegrees - displayRotationDegrees
        }
        return ((raw % 360) + 360) % 360
    }

    public fun presentationAspect(
        mode: PreviewAspectMode,
        sensorLandscapeAspect: Double,
        usableDisplayAspect: Double,
        portraitPresentation: Boolean,
    ): Double {
        require(sensorLandscapeAspect > 0.0)
        require(usableDisplayAspect > 0.0)
        val landscape = when (mode) {
            PreviewAspectMode.SENSOR -> sensorLandscapeAspect
            PreviewAspectMode.SQUARE -> 1.0
            PreviewAspectMode.FOUR_THREE -> 4.0 / 3.0
            PreviewAspectMode.SIXTEEN_NINE -> 16.0 / 9.0
            PreviewAspectMode.FULL -> max(usableDisplayAspect, 1.0 / usableDisplayAspect)
        }
        return if (portraitPresentation && landscape != 1.0) 1.0 / landscape else landscape
    }

    public fun centerCrop(sourceAspect: Double, targetAspect: Double): NormalizedRect {
        require(sourceAspect > 0.0)
        require(targetAspect > 0.0)
        return when {
            sourceAspect > targetAspect -> {
                val retainedWidth = targetAspect / sourceAspect
                val margin = (1.0 - retainedWidth) / 2.0
                NormalizedRect(margin, 0.0, 1.0 - margin, 1.0)
            }
            sourceAspect < targetAspect -> {
                val retainedHeight = sourceAspect / targetAspect
                val margin = (1.0 - retainedHeight) / 2.0
                NormalizedRect(0.0, margin, 1.0, 1.0 - margin)
            }
            else -> NormalizedRect(0.0, 0.0, 1.0, 1.0)
        }
    }

    private fun validateRightAngle(degrees: Int) {
        require(degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270) {
            "Rotation must be 0, 90, 180, or 270 degrees"
        }
    }
}
